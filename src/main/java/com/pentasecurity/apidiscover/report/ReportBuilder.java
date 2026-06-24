// 분류 결과 → DiscoveryReport 조립 (doc/01 §4, doc/07 §3.3)
package com.pentasecurity.apidiscover.report;

import com.pentasecurity.apidiscover.ingest.LogWindow;
import com.pentasecurity.apidiscover.model.DiscoveryReport;
import com.pentasecurity.apidiscover.model.DroppedByLimit;
import com.pentasecurity.apidiscover.model.DroppedNonApi;
import com.pentasecurity.apidiscover.model.DroppedNonExistent;
import com.pentasecurity.apidiscover.model.EndpointKindSignal;
import com.pentasecurity.apidiscover.model.Finding;
import java.time.Instant;
import java.util.List;
import org.springframework.stereotype.Component;

@Component
public class ReportBuilder {

    /** findings 를 분류별로 집계해 리포트를 만든다. version(ETag)은 저장 시 EtagUtil 로 산정. */
    public DiscoveryReport build(String host, long specVersion, LogWindow window,
                                 int discoveredCount, List<Finding> findings, DroppedNonApi dropped,
                                 DroppedByLimit droppedByLimit, DroppedNonExistent droppedNonExistent,
                                 EndpointKindSignal endpointKindSignal) {
        int active = 0;
        int shadow = 0;
        int zombie = 0;
        int unused = 0;
        for (Finding f : findings) {
            switch (f) {
                case Finding.Active a -> active++;
                case Finding.Shadow s -> shadow++;
                case Finding.Zombie z -> zombie++;
                case Finding.Unused u -> unused++;
                case Finding.WebPage w -> { /* 비 API: 요약 카운트 제외 */ }
            }
        }

        var summary = new DiscoveryReport.Summary(discoveredCount, active, shadow, zombie, unused);
        // droppedNonApi/droppedByLimit 는 항상 non-null(빈 결과) → shape 일관 (doc/12 §3, doc/13 §1.2)
        DroppedNonApi nonApi = dropped != null ? dropped : new DroppedNonApi(0, 0, 0);
        DroppedByLimit byLimit = droppedByLimit != null ? droppedByLimit : DroppedByLimit.NONE;
        DroppedNonExistent nonExistent = droppedNonExistent != null ? droppedNonExistent : DroppedNonExistent.NONE;
        EndpointKindSignal kindSignal = endpointKindSignal != null ? endpointKindSignal : EndpointKindSignal.NONE;
        return new DiscoveryReport(host, Instant.now(), window, specVersion, summary, findings,
                nonApi, byLimit, nonExistent, kindSignal);
    }
}
