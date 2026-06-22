// 분류 결과 → DiscoveryReport 조립 (doc/01 §4, doc/07 §3.3)
package com.pentasecurity.apidiscover.report;

import com.pentasecurity.apidiscover.ingest.LogWindow;
import com.pentasecurity.apidiscover.model.DiscoveryReport;
import com.pentasecurity.apidiscover.model.Finding;
import java.time.Instant;
import java.util.List;
import org.springframework.stereotype.Component;

@Component
public class ReportBuilder {

    /** findings 를 분류별로 집계해 리포트를 만든다. version(ETag)은 저장 시 EtagUtil 로 산정. */
    public DiscoveryReport build(String host, long specVersion, LogWindow window,
                                 int discoveredCount, List<Finding> findings) {
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
        return new DiscoveryReport(host, Instant.now(), window, specVersion, summary, findings);
    }
}
