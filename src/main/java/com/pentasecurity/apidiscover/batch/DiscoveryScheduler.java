// 주기 스캔 틱 — least-recently-scanned 슬라이스·예산 체크·커서 전진 (doc/33 §1 B, §9)
package com.pentasecurity.apidiscover.batch;

import com.pentasecurity.apidiscover.config.ApiDiscoverProperties;
import com.pentasecurity.apidiscover.domain.DomainConfig;
import com.pentasecurity.apidiscover.domain.DomainConfigRepository;
import com.pentasecurity.apidiscover.ingest.LokiBudget;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 상주 서비스 내부 스케줄러(@Scheduled). 짧은 틱(scan.tick-interval)마다 least-recently-scanned 상위 K 도메인만
 * 스캔한다(doc/33 §1 B) — 전수순회(과부하) 대체. attempt 마다 커서(lastScanAttemptAt) 전진(skip 포함 → up-to-date
 * 재선택 방지·FIFO 기아 방지), 전역 예산(E) 소진 시 틱 조기 종료(다음 틱 이월).
 * HA(replica&gt;1)는 ShedLock/Quartz 단일 실행 + 예산 DB-backing 필요(doc/33 §9, 단일 인스턴스 전제).
 */
@Component
public class DiscoveryScheduler {

    private static final Logger log = LoggerFactory.getLogger(DiscoveryScheduler.class);

    private final ScanSelector scanSelector;
    private final DiscoveryJobService jobService;
    private final DomainConfigRepository domains;
    private final LokiBudget budget;
    private final ApiDiscoverProperties props;
    private final Clock clock;

    public DiscoveryScheduler(ScanSelector scanSelector, DiscoveryJobService jobService,
                              DomainConfigRepository domains, LokiBudget budget,
                              ApiDiscoverProperties props, Clock clock) {
        this.scanSelector = scanSelector;
        this.jobService = jobService;
        this.domains = domains;
        this.budget = budget;
        this.props = props;
        this.clock = clock;
    }

    @Scheduled(fixedDelayString = "${apidiscover.scan.tick-interval}")
    public void scanTick() {
        // now 는 틱 단위 1회(일관). off-peak(D)면 백필 윈도우 상향(due 술어/정렬은 ScanSelector 가 불변 유지).
        Instant now = Instant.now(clock);
        boolean offPeak = OffPeakWindow.isOffPeak(now, props.schedule().offPeakWindow(),
                OffPeakWindow.zone(props.scan().offPeakZone()));
        Duration maxWindow = offPeak ? props.scan().offPeakMaxWindow() : props.scan().maxWindow();
        List<DomainConfig> slice = scanSelector.selectForTick();
        // D63 배칭 경로: 스케줄 커서는 동일하게 전진(재선택 방지)하고, 스캔은 배칭 서비스가 일괄 처리(게이트·예산 내부).
        if (props.scan().queryBatchSize() > 0) {
            java.util.List<String> hosts = new java.util.ArrayList<>(slice.size());
            for (DomainConfig domain : slice) {
                Duration interval = ScanTier.effectiveInterval(domain, now, props.scan());
                domains.touchScanSchedule(domain.getHost(), now, now.plus(interval));
                hosts.add(domain.getHost());
            }
            try {
                jobService.runScanBatched(hosts, maxWindow);
            } catch (RuntimeException e) {
                log.warn("batched scan tick failed", e); // 다음 틱 재시도(커서는 전진돼 기아 없음)
            }
            return;
        }
        for (DomainConfig domain : slice) {
            if (!budget.hasBudget()) {
                log.info("loki budget exhausted — deferring remaining domains to next tick");
                break; // E: 전역 예산 소진 → 이월
            }
            String host = domain.getHost();
            // ★스케줄 커서 전진은 attempt 마다(skip 포함) — lastScanAttemptAt(관측) + nextScanDueAt=now+effectiveInterval(C/F/override).
            // 별도 tx(단일 UPDATE), runScan 실패해도 due 전진(기아·재선택 방지, D48 §4.3).
            Duration interval = ScanTier.effectiveInterval(domain, now, props.scan());
            domains.touchScanSchedule(host, now, now.plus(interval));
            try {
                jobService.runScan(host, maxWindow);
            } catch (RuntimeException e) {
                // 도메인 격리: 한 도메인 실패가 다른 도메인을 막지 않게 (doc/05 §6)
                log.warn("scan failed for host={}", host, e);
            }
        }
    }
}
