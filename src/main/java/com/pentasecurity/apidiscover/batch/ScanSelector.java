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
 *
 * <p>★무접속 중단(D82, doc/43): 게이트는 {@code activityStatus = ACTIVE} 술어로 판정한다(과거 lastSeenAt 직접 비교 대체).
 * discovery 틱 종료 sweep({@code deactivateStale}, cutoff=now−{@code scan.inactive-after} 기본 P7D)이 무접속분을 INACTIVE 로
 * 강등하고, discovery 실요청 재관측·수동 스캔이 ACTIVE 로 복귀시킨다(self-healing). {@code inactive-after}=0/null=sweep no-op(무회귀).
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

    /**
     * 이번 틱 처리 대상(due 도래 상위 K, off-peak 시 K 상향, 무접속 도메인 제외).
     * <p>★D64 활성 우선(Phase 3): "워터마크 이후 신규 트래픽 확정"(=실조회 필요) 도메인을 먼저 뽑아
     * 활성 도메인의 스캔 지연을 최소화한다. 신규 트래픽 없는(delta-skip 예정) 도메인은 틱당 최소 K/5 를
     * 예약해 기아를 방지 — 이들이 주기적으로 선택돼야 D61 점프로 워터마크가 near-now 를 유지해,
     * 트래픽 재개 시 빈 과거 윈도우를 기어가는 낭비가 없다. 활성분이 부족하면 예약 이상을 채운다.
     */
    public List<DomainConfig> selectForTick() {
        Instant now = Instant.now(clock);
        boolean offPeak = OffPeakWindow.isOffPeak(now, props.schedule().offPeakWindow(),
                OffPeakWindow.zone(props.scan().offPeakZone()));
        int k = Math.max(1, offPeak ? props.scan().offPeakDomainsPerTick() : props.scan().domainsPerTick());
        int reserve = Math.max(1, k / 5); // ponytail: 고정 1/5 예약 — 필요 시 설정 승격
        // 1) skip-류 예약 확인 → 2) 활성 우선 채움 → 3) 남는 몫 skip-류 추가. 무접속 게이트=activity_status(D82, sweep 이 유지).
        List<DomainConfig> restProbe = repo.findDueWithoutNewTraffic(now, PageRequest.of(0, reserve));
        List<DomainConfig> active = repo.findDueWithNewTraffic(now,
                PageRequest.of(0, Math.max(1, k - restProbe.size())));
        int restLimit = k - active.size();
        if (restLimit <= 0) {
            return active;
        }
        List<DomainConfig> rest = (restProbe.size() >= restLimit)
                ? restProbe.subList(0, restLimit)
                : repo.findDueWithoutNewTraffic(now, PageRequest.of(0, restLimit));
        List<DomainConfig> out = new java.util.ArrayList<>(active.size() + rest.size());
        out.addAll(active);
        out.addAll(rest);
        return out;
    }
}
