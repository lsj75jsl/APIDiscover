// 도메인 자동 디스커버리 — Loki 서버측 집계로 (도메인×엣지) 열거 → DomainConfig 무삭제 업서트 (doc/30, D42)
package com.pentasecurity.apidiscover.batch;

import com.pentasecurity.apidiscover.config.ApiDiscoverProperties;
import com.pentasecurity.apidiscover.domain.DomainConfig;
import com.pentasecurity.apidiscover.domain.DomainConfigRepository;
import com.pentasecurity.apidiscover.ingest.LokiClient;
import com.pentasecurity.apidiscover.parse.LogLineParser;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 수집 중 access log 에서 API 도메인을 자동 열거한다(doc/30). 원시 로그를 받지 않고 Loki 서버측 메트릭 집계
 * (sum by(domain,hostname) count_over_time(... | pattern | label_format domain=coalesce(host,real_host) ...))로
 * (도메인 × 엣지 hostname) 카운트만 수신 → FQDN 검증·개수 상한 후 {@link DomainConfig} 를 <b>무삭제</b> 업서트한다.
 *
 * <p>★불변식: 도메인/사용자 설정(basePathStrip·specMergeStrategy·enabled·intervalOverride) 자동 삭제·덮어쓰기 절대 금지(§5).
 */
@Service
public class DomainDiscoveryService {

    private static final Logger log = LoggerFactory.getLogger(DomainDiscoveryService.class);

    /** label_format coalesce(Go 템플릿) — LogLineParser line 83 firstNonEmpty(nullIfDash(host),nullIfDash(real_host)) 와 동일(doc/30 §1). */
    private static final String COALESCE_TEMPLATE =
            "{{ if or (eq .host \\\"\\\") (eq .host \\\"-\\\") }}{{ .real_host }}{{ else }}{{ .host }}{{ end }}";

    private final LokiClient loki;
    private final DomainConfigRepository repo;
    private final DomainUpserter upserter;
    private final ApiDiscoverProperties props;
    private final Pattern hostPattern;

    public DomainDiscoveryService(LokiClient loki, DomainConfigRepository repo,
                                  DomainUpserter upserter, ApiDiscoverProperties props) {
        this.loki = loki;
        this.repo = repo;
        this.upserter = upserter;
        this.props = props;
        this.hostPattern = Pattern.compile(props.discovery().hostPattern());
    }

    /** 디스커버리 1회 실행 결과(스케줄러 로그·테스트용). */
    public record DiscoveryResult(boolean bootstrap, int inserted, int updated, int rejected, int dropped) {}

    /**
     * 1회 디스커버리: 윈도우 결정(빈 도메인 DB=부트스트랩 1h, 아니면 롤링) → instant 집계 → 도메인별 hostnames 합산 →
     * FQDN 검증·카운트 desc 상한 → 무삭제 업서트. {@code now} = 쿼리 기준 시각(=lastSeenAt, 스케줄러가 주입).
     *
     * <p>★트랜잭션 범위: 의도적으로 비-@Transactional. Loki 네트워크 호출(queryInstant)을 DB 트랜잭션 밖에 두고,
     * 각 도메인 upsert(findById→mutate→save)만 repo 기본 트랜잭션으로 짧게 처리한다(긴 tx 회피, lost-update 창 최소화).
     * 도메인 간 원자성은 불요(멱등·합집합 수렴). 설정 보존은 {@code @DynamicUpdate}(dirty 컬럼만 UPDATE)로 구조 보장.
     */
    public DiscoveryResult discover(Instant now) {
        ApiDiscoverProperties.Discovery cfg = props.discovery();
        // ponytail: 빈 도메인 DB = 첫 실행 → 부트스트랩 1h(doc/30 §4). 영속 플래그 불요(YAGNI). 빈 Loki 면 다음 실행도 부트스트랩(무해).
        boolean bootstrap = repo.count() == 0;
        Duration window = bootstrap ? cfg.bootstrapWindow() : cfg.window();

        List<LokiClient.MetricSample> vector = loki.queryInstant(buildLogQL(window), now);

        // 도메인 → 엣지 hostname 집합(정렬=결정적) + 도메인별 총 카운트(상한 정렬용)
        Map<String, Set<String>> hostnamesByDomain = new LinkedHashMap<>();
        Map<String, Double> countByDomain = new LinkedHashMap<>();
        int rejected = 0;
        for (LokiClient.MetricSample s : vector) {
            String domain = normalizeDomain(s.labels().get("domain"));
            if (domain == null || !hostPattern.matcher(domain).matches()) {
                rejected++; // 빈/형식위반 Host(변조·랜덤) 자동등록 차단(§6)
                continue;
            }
            Set<String> hostnames = hostnamesByDomain.computeIfAbsent(domain, k -> new TreeSet<>());
            String hostname = s.labels().get("hostname");
            if (hostname != null && !hostname.isBlank()) {
                hostnames.add(hostname);
            }
            countByDomain.merge(domain, s.value(), Double::sum);
        }

        // 카운트 desc 정렬 후 max-domains 상한(폭증 1회 영향 격리, §6)
        List<String> ranked = countByDomain.entrySet().stream()
                .sorted(Map.Entry.<String, Double>comparingByValue().reversed())
                .map(Map.Entry::getKey)
                .toList();
        int cap = cfg.maxDomainsPerRun();
        int dropped = Math.max(0, ranked.size() - cap);
        if (dropped > 0) {
            log.warn("domain discovery cap {} reached — {} domains dropped (count desc)", cap, dropped);
            ranked = ranked.subList(0, cap);
        }

        int inserted = 0;
        int updated = 0;
        for (String domain : ranked) {
            // per-domain 단일 트랜잭션(별도 빈) — managed 엔티티 dirty-check + @DynamicUpdate 로 설정 lost-update 방지(§5 P3-1)
            if (upserter.upsert(domain, hostnamesByDomain.get(domain), now)) {
                inserted++;
            } else {
                updated++;
            }
        }
        log.info("domain discovery: bootstrap={} window={} vector={} inserted={} updated={} rejected={} dropped={}",
                bootstrap, window, vector.size(), inserted, updated, rejected, dropped);
        return new DiscoveryResult(bootstrap, inserted, updated, rejected, dropped);
    }

    /** doc/30 §1 LogQL — 서버측 집계(라인 미수신). pattern 포지션은 LogLineParser 와 공유 상수로 빌드(드리프트 차단). */
    String buildLogQL(Duration window) {
        return "sum by (domain, hostname) (count_over_time("
                + "{job=\"" + props.loki().jobLabel() + "\"}"
                + " | pattern \"" + buildPattern() + "\""
                + " | label_format domain=\"" + COALESCE_TEMPLATE + "\""
                + " | domain!=\"\" | domain!=\"-\""
                + " [" + window.toSeconds() + "s]))";
    }

    /** pattern: 인덱스 0..F_HOST-1 skip(<_>^|^) → <host>^|^<real_host>^|^<_>. 포지션=LogLineParser 상수(doc/02 §1.1). */
    static String buildPattern() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < LogLineParser.F_HOST; i++) {
            sb.append("<_>").append(LogLineParser.DELIM);
        }
        sb.append("<host>").append(LogLineParser.DELIM)
                .append("<real_host>").append(LogLineParser.DELIM).append("<_>");
        return sb.toString();
    }

    private static String normalizeDomain(String raw) {
        if (raw == null) {
            return null;
        }
        String t = raw.trim().toLowerCase(Locale.ROOT);
        return (t.isEmpty() || "-".equals(t)) ? null : t;
    }
}
