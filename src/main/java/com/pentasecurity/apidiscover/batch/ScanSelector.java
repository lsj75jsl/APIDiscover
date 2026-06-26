// 스캔 틱 대상 선택 — least-recently-scanned 상위 K (doc/33 §1 B). C/D(티어·off-peak)는 PR2 확장점
package com.pentasecurity.apidiscover.batch;

import com.pentasecurity.apidiscover.config.ApiDiscoverProperties;
import com.pentasecurity.apidiscover.domain.DomainConfig;
import com.pentasecurity.apidiscover.domain.DomainConfigRepository;
import java.util.List;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Component;

/**
 * 이번 틱에 스캔할 도메인을 고른다(doc/33 §1 B): enabled + {@code lastScanAttemptAt} 오름차순(NULLS FIRST=미스캔 우선)
 * 상위 {@code domains-per-tick} K. 영속 타임스탬프 커서라 재기동 생존·FIFO 공정(기아 없음). 전수순회 대체.
 *
 * <p>PR2(C 티어링·D off-peak)는 이 선택 로직 확장점 — due 판정/예산·윈도우 스위치를 여기 적층.
 */
@Component
public class ScanSelector {

    private final DomainConfigRepository repo;
    private final ApiDiscoverProperties props;

    public ScanSelector(DomainConfigRepository repo, ApiDiscoverProperties props) {
        this.repo = repo;
        this.props = props;
    }

    /** 이번 틱 처리 대상(LRS 상위 K). */
    public List<DomainConfig> selectForTick() {
        int k = Math.max(1, props.scan().domainsPerTick());
        Pageable page = PageRequest.of(0, k,
                Sort.by(Sort.Order.asc("lastScanAttemptAt").nullsFirst()));
        return repo.findByEnabledIsTrue(page);
    }
}
