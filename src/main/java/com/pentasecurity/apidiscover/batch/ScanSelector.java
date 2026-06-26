// 스캔 틱 대상 선택 — due 도래 도메인 상위 K (doc/33 §4, D48). C 티어·F dormant·override 가 nextScanDueAt 로 collapse
package com.pentasecurity.apidiscover.batch;

import com.pentasecurity.apidiscover.config.ApiDiscoverProperties;
import com.pentasecurity.apidiscover.domain.DomainConfig;
import com.pentasecurity.apidiscover.domain.DomainConfigRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Component;

/**
 * 이번 틱에 스캔할 도메인을 고른다(doc/33 §4, D48): enabled + due 도래(nextScanDueAt null=즉시 또는 &lt;= now)
 * 를 상위 K. 정렬(nextScanDueAt asc nulls first)은 {@code findDueForScan} @Query 가 보유 → 가장 밀린 순=FIFO(기아 없음).
 *
 * <p>off-peak(D)면 K 를 {@code off-peak-domains-per-tick} 로 상향(due 술어는 불변 — 백필 우선순위는 due 정렬이 이미 보장).
 * tiering-enabled=false 면 nextScanDueAt 가 항상 now(=즉시 due)라 이 선택은 PR1 의 LRS 와 동치(롤백 스위치).
 */
@Component
public class ScanSelector {

    private final DomainConfigRepository repo;
    private final ApiDiscoverProperties props;
    private final Clock clock;

    public ScanSelector(DomainConfigRepository repo, ApiDiscoverProperties props, Clock clock) {
        this.repo = repo;
        this.props = props;
        this.clock = clock;
    }

    /** 이번 틱 처리 대상(due 도래 상위 K, off-peak 시 K 상향). */
    public List<DomainConfig> selectForTick() {
        Instant now = Instant.now(clock);
        boolean offPeak = OffPeakWindow.isOffPeak(now, props.schedule().offPeakWindow(),
                OffPeakWindow.zone(props.scan().offPeakZone()));
        int k = Math.max(1, offPeak ? props.scan().offPeakDomainsPerTick() : props.scan().domainsPerTick());
        Pageable page = PageRequest.of(0, k); // ORDER BY 는 findDueForScan @Query 가 보유(nulls first 결정적, P1)
        return repo.findDueForScan(now, page);
    }
}
