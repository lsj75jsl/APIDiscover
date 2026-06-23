// 도메인 1회 스캔의 최종 리포트 (doc/01 §4)
package com.pentasecurity.apidiscover.model;

import com.pentasecurity.apidiscover.ingest.LogWindow;
import java.time.Instant;
import java.util.List;

public record DiscoveryReport(
        String host,
        Instant generatedAt,
        LogWindow logWindow,
        long specVersion,
        Summary summary,
        List<Finding> findings,
        DroppedNonApi droppedNonApi   // non_api 게이트 탈락 사유별 집계 (doc/12), 항상 non-null
) {
    public record Summary(
            int discovered,
            int active,
            int shadow,
            int zombie,
            int unused
    ) {}
}
