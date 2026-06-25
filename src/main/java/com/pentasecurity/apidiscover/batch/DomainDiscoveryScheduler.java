// 도메인 자동 디스커버리 스케줄러 — 기존 per-domain 스캔과 분리·stagger (doc/30 §3, D42)
package com.pentasecurity.apidiscover.batch;

import com.pentasecurity.apidiscover.config.ApiDiscoverProperties;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * {@link DomainDiscoveryService} 를 주기 실행한다. 기존 {@link DiscoveryScheduler}(per-domain 스캔)와 분리하고
 * initialDelay 로 stagger 해 동시 Loki 타격을 피한다. {@code discovery.enabled=false} 면 no-op(무회귀 토글).
 * 동적 주기는 fixedDelayString 프로퍼티로 충분(런타임 동적=YAGNI, doc/30 §3).
 */
@Component
public class DomainDiscoveryScheduler {

    private static final Logger log = LoggerFactory.getLogger(DomainDiscoveryScheduler.class);

    private final DomainDiscoveryService service;
    private final ApiDiscoverProperties props;

    public DomainDiscoveryScheduler(DomainDiscoveryService service, ApiDiscoverProperties props) {
        this.service = service;
        this.props = props;
    }

    @Scheduled(fixedDelayString = "${apidiscover.discovery.interval}",
            initialDelayString = "${apidiscover.discovery.initial-delay}")
    public void discover() {
        if (!props.discovery().enabled()) {
            return; // 토글 off → no-op
        }
        try {
            // now=쿼리 기준 시각(=lastSeenAt). 스케줄러 경계에서만 now() 사용, 서비스는 주입값으로 결정적.
            service.discover(Instant.now());
        } catch (RuntimeException e) {
            // 디스커버리 실패가 스캔/서버를 막지 않게 격리(보조 기능)
            log.warn("domain discovery run failed", e);
        }
    }
}
