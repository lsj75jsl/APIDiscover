// 주기적으로 활성 도메인을 스캔 트리거 (doc/06 배포모델 B, doc/05)
package com.pentasecurity.apidiscover.batch;

import com.pentasecurity.apidiscover.domain.DomainConfig;
import com.pentasecurity.apidiscover.domain.DomainConfigRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 상주 서비스 내부 스케줄러(@Scheduled). k8s CronJob 아님(doc/06 §1).
 * HA(replica>1) 시 ShedLock/Quartz 클러스터로 단일 실행 보장 필요(doc/06 §6).
 */
@Component
public class DiscoveryScheduler {

    private static final Logger log = LoggerFactory.getLogger(DiscoveryScheduler.class);

    private final DomainConfigRepository domains;
    private final DiscoveryJobService jobService;

    public DiscoveryScheduler(DomainConfigRepository domains, DiscoveryJobService jobService) {
        this.domains = domains;
        this.jobService = jobService;
    }

    // 전역 기본 인터벌. 도메인별 override 적용은 후속(doc/07 §3.1 intervalOverride).
    @Scheduled(fixedDelayString = "${apidiscover.schedule.default-interval}")
    public void scanEnabledDomains() {
        for (DomainConfig domain : domains.findByEnabledIsTrue()) {
            try {
                jobService.runScan(domain.getHost());
            } catch (RuntimeException e) {
                // 도메인 격리: 한 도메인 실패가 다른 도메인을 막지 않게 (doc/05 §6)
                log.warn("scan failed for host={}", domain.getHost(), e);
            }
        }
    }
}
