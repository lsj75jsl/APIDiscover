// 도메인 자동 디스커버리 — Loki 서버측 집계로 (도메인×엣지) 열거 → DomainConfig 무삭제 업서트 (doc/30, D42)
package com.pentasecurity.apidiscover.batch;

import com.pentasecurity.apidiscover.config.ApiDiscoverProperties;
import com.pentasecurity.apidiscover.domain.DomainConfig;
import com.pentasecurity.apidiscover.domain.DomainConfigRepository;
import com.pentasecurity.apidiscover.ingest.LokiClient;
import com.pentasecurity.apidiscover.parse.LogLineParser;
import com.pentasecurity.apidiscover.util.DomainNames;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 수집 중 access log 에서 API 도메인을 자동 열거한다(doc/30). 원시 로그를 받지 않고 Loki 서버측 메트릭 집계
 * (sum by(host, real_host, hostname) count_over_time(... | pattern ...))로 카운트만 수신 → <b>클라이언트 coalesce</b>
 * (host 빈/"-"→real_host) → FQDN 검증·개수 상한 후 {@link DomainConfig} 를 <b>무삭제</b> 업서트한다.
 * (서버 label_format coalesce 는 실측 10배 느려 query-timeout → 제거, doc/30 §1.)
 *
 * <p>★불변식: 도메인/사용자 설정(basePathStrip·specMergeStrategy·enabled·intervalOverride) 자동 삭제·덮어쓰기 절대 금지(§5).
 */
@Service
public class DomainDiscoveryService {

    private static final Logger log = LoggerFactory.getLogger(DomainDiscoveryService.class);

    private final LokiClient loki;
    private final DomainConfigRepository repo;
    private final DomainUpserter upserter;
    private final ApiDiscoverProperties props;
    private final Pattern hostPattern;
    /** D62·D69: 대상 제외 엣지(hostname 라벨, 정확 일치 + 'X*' 접두) — 이 엣지의 관측은 없는 것으로 취급(등록·lastSeen 갱신 안 함). */
    private final EdgeExclusions excludedHostnames;

    public DomainDiscoveryService(LokiClient loki, DomainConfigRepository repo,
                                  DomainUpserter upserter, ApiDiscoverProperties props) {
        this.loki = loki;
        this.repo = repo;
        this.upserter = upserter;
        this.props = props;
        this.hostPattern = Pattern.compile(props.discovery().hostPattern());
        this.excludedHostnames = new EdgeExclusions(props.discovery().excludedHostnames());
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
        int excluded = 0;
        for (LokiClient.MetricSample s : vector) {
            // D62: 제외 엣지의 관측은 통째로 skip — 그 엣지에서만 보이는 도메인은 등록/lastSeen 갱신이 안 돼
            // 자동스캔 대상이 되지 않는다(기존 등록분은 lastSeen 정체 → inactive-after 게이트가 자연 제외).
            String hostname = s.labels().get("hostname");
            if (hostname != null && excludedHostnames.contains(hostname)) {
                excluded++;
                continue;
            }
            // 클라이언트 coalesce(host 빈/"-"→real_host) — LogLineParser line83 firstNonEmpty(nullIfDash(host),nullIfDash(real_host)) 동일.
            // 서버 label_format coalesce 는 실측 10배(2.2s→20.2s, query-timeout 초과) → 제거하고 클라이언트에서 처리(doc/30 §1).
            String domain = firstNonEmpty(
                    normalizeDomain(s.labels().get("host")), normalizeDomain(s.labels().get("real_host")));
            if (domain == null || !hostPattern.matcher(domain).matches()) {
                rejected++; // 빈/형식위반 Host(변조·랜덤) 자동등록 차단(§6)
                continue;
            }
            Set<String> hostnames = hostnamesByDomain.computeIfAbsent(domain, k -> new TreeSet<>());
            if (hostname != null && !hostname.isBlank()) {
                hostnames.add(hostname);
            }
            countByDomain.merge(domain, s.value(), Double::sum);
        }

        // max-domains 상한: cap<=0 = 무제한(전 도메인 등록, 정렬·드롭 생략) / cap>0 = 카운트 desc 상위 N(폭증 격리, §6)
        int cap = cfg.maxDomainsPerRun();
        List<String> ranked;
        int dropped;
        if (cap <= 0) {
            ranked = List.copyOf(countByDomain.keySet()); // 무제한(사용자 지시): 전수 등록
            dropped = 0;
        } else {
            List<String> sorted = countByDomain.entrySet().stream()
                    .sorted(Map.Entry.<String, Double>comparingByValue().reversed())
                    .map(Map.Entry::getKey)
                    .toList();
            dropped = Math.max(0, sorted.size() - cap);
            if (dropped > 0) {
                log.warn("domain discovery cap {} reached — {} domains dropped (count desc)", cap, dropped);
                sorted = sorted.subList(0, cap);
            }
            ranked = sorted;
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
        // D82(doc/43 §4.3): 무접속 sweep — 위 upsert 가 실요청 관측분을 ACTIVE·lastSeenAt=now 로 올린 뒤 실행하므로
        // 방금 관측분은 강등되지 않는다(cutoff=now−inactive-after). inactive-after=0/null=no-op(무회귀).
        int deactivated = sweepInactive(now);
        log.info("domain discovery: bootstrap={} window={} vector={} inserted={} updated={} rejected={} dropped={} excludedEdge={} deactivated={}",
                bootstrap, window, vector.size(), inserted, updated, rejected, dropped, excluded, deactivated);
        return new DiscoveryResult(bootstrap, inserted, updated, rejected, dropped);
    }

    /** 무접속 sweep — lastSeenAt<cutoff 인 ACTIVE 도메인을 INACTIVE 로 강등(별도 tx bulk UPDATE). 반환=강등 건수. */
    private int sweepInactive(Instant now) {
        Duration inactiveAfter = props.scan().inactiveAfter();
        if (inactiveAfter == null || inactiveAfter.isZero()) {
            return 0; // 게이트 비활성(무회귀)
        }
        return repo.deactivateStale(now, now.minus(inactiveAfter));
    }

    /**
     * doc/30 §1 LogQL — 서버측 집계(라인 미수신). pattern 포지션은 LogLineParser 와 공유 상수로 빌드(드리프트 차단).
     * ★서버 label_format coalesce 제거(실측 2.2s→20.2s, 10배·query-timeout 초과) → host/real_host/hostname 으로 group by 하고
     * coalesce(host→real_host 폴백)·도메인 필터는 클라이언트(discover 루프)에서 처리(운영 Loki 부하 ↓).
     */
    String buildLogQL(Duration window) {
        return "sum by (host, real_host, hostname) (count_over_time("
                + "{job=\"" + props.loki().jobLabel() + "\"}"
                + " | pattern \"" + buildPattern() + "\""
                + probeStatusFilter()
                + pathExcludeFilter()
                + " [" + window.toSeconds() + "s]))";
    }

    /**
     * D82(doc/43 §4.1): 실요청에서 제외할 URI 경로(예 {@code /.cloudbric/pron/}) 라벨 필터. pattern 이 뽑은 {@code <uri>} 를
     * RE2 비앵커 substring 부정매칭({@code !~})으로 제외 — 이 경로만 관측된 도메인은 vector 에서 빠져 등록/lastSeen 갱신 안 됨.
     * 빈/미설정 = 필터 없음 = 현행 무회귀. status 필터와 동일 라벨필터(광역 |= 아님 → Loki 부하 안전).
     */
    private String pathExcludeFilter() {
        List<String> paths = props.discovery().excludedPaths();
        if (paths == null || paths.isEmpty()) {
            return "";
        }
        String regex = paths.stream().map(DomainDiscoveryService::escapeRe2).collect(Collectors.joining("|"));
        // ★백틱 raw 문자열: 정규식의 '\.' 를 이중따옴표에 넣으면 LogQL(Go) 문자열 이스케이프 규칙 위반→파싱 400.
        // 백틱은 raw(이스케이프 미처리)라 RE2 가 '\.' 를 리터럴 dot 로 받는다. (status 필터는 백슬래시 없어 이중따옴표 유지)
        return " | uri !~ `" + regex + "`";
    }

    /** RE2 메타문자 이스케이프(경로의 '.' 등 리터럴화) — buildLogQL uri 필터 정규식 조립용. */
    static String escapeRe2(String s) {
        StringBuilder b = new StringBuilder(s.length() + 4);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if ("\\.+*?()|[]{}^$".indexOf(c) >= 0) {
                b.append('\\');
            }
            b.append(c);
        }
        return b.toString();
    }

    /**
     * C(doc/42 §4.4): 프로브 status(기본 404·470) 만 관측된 Host 를 등록에서 배제하는 라벨 필터.
     * pattern 이 추출한 {@code <status>} 를 전체앵커 부등호 매칭(!~)으로 제외 — 스푸핑/프로브 Host 억제·재유입 종식.
     * 빈/미설정 = 필터 없음 = 현행 무회귀. 광역 |= 이 아니라 라벨 필터라 Loki 부하 안전(pattern 파싱은 이미 수행 중).
     */
    private String probeStatusFilter() {
        List<Integer> statuses = props.discovery().probeStatuses();
        if (statuses == null || statuses.isEmpty()) {
            return "";
        }
        return " | status !~ \"" + statuses.stream().map(String::valueOf).collect(Collectors.joining("|")) + "\"";
    }

    /**
     * pattern: 인덱스 0..F_HOST-1 skip(<_>^|^, 단 F_STATUS 는 status 필터용 <status> 명명) → <host>^|^<real_host>^|^<_>.
     * 포지션=LogLineParser 상수(doc/02 §1.1) — 드리프트 차단.
     */
    static String buildPattern() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < LogLineParser.F_HOST; i++) {
            String name = (i == LogLineParser.F_STATUS) ? "<status>"
                    : (i == LogLineParser.F_REQUEST_URI) ? "<uri>" : "<_>"; // D82: uri 경로제외 필터용
            sb.append(name).append(LogLineParser.DELIM);
        }
        sb.append("<host>").append(LogLineParser.DELIM)
                .append("<real_host>").append(LogLineParser.DELIM).append("<_>");
        return sb.toString();
    }

    /** 공유 정규화 위임(DomainNames) — 스캔 foreign-host 필터와 동일 규칙 보장(단일 진실원, doc/05 §2.2). */
    private static String normalizeDomain(String raw) {
        return DomainNames.normalize(raw);
    }

    /** 클라이언트 coalesce — host 우선, 빈/dash(=null)면 real_host (LogLineParser.firstNonEmpty 동형). */
    private static String firstNonEmpty(String a, String b) {
        return (a != null) ? a : b;
    }
}
