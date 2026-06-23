// ReportBuilder 요약 집계 단위 테스트 (doc/01 §4)
package com.pentasecurity.apidiscover.report;

import static org.assertj.core.api.Assertions.assertThat;

import com.pentasecurity.apidiscover.ingest.LogWindow;
import com.pentasecurity.apidiscover.model.DiscoveryReport;
import com.pentasecurity.apidiscover.model.DroppedByLimit;
import com.pentasecurity.apidiscover.model.DroppedNonApi;
import com.pentasecurity.apidiscover.model.DroppedNonExistent;
import com.pentasecurity.apidiscover.model.Finding;
import com.pentasecurity.apidiscover.model.ParamCandidates;
import com.pentasecurity.apidiscover.model.Severity;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class ReportBuilderTest {

    private final ReportBuilder builder = new ReportBuilder();

    @Test
    void summarizesFindingsByClassification() {
        List<Finding> findings = List.of(
                new Finding.Active("h", "GET", "/a", "ref"),
                new Finding.Shadow("h", "GET", "/s1", 0.9, "r", ParamCandidates.EMPTY),
                new Finding.Shadow("h", "POST", "/s2", 0.5, "r", ParamCandidates.EMPTY),
                new Finding.Zombie("h", "GET", "/z", 1.0, new Severity(0.5), false, "ref", "r"),
                new Finding.Unused("h", "GET", "/u", "ref"),
                new Finding.WebPage("h", "GET", "/page", 0.8)); // 요약 제외

        LogWindow window = new LogWindow(Instant.EPOCH, Instant.EPOCH.plusSeconds(3600));
        DiscoveryReport report = builder.build("api.example.com", 7L, window, 4, findings,
                new DroppedNonApi(2, 1, 3), new DroppedByLimit(4, 5), new DroppedNonExistent(8));

        assertThat(report.host()).isEqualTo("api.example.com");
        assertThat(report.specVersion()).isEqualTo(7L);
        assertThat(report.logWindow()).isEqualTo(window);
        assertThat(report.generatedAt()).isNotNull();
        assertThat(report.findings()).hasSize(6);

        DiscoveryReport.Summary s = report.summary();
        assertThat(s.discovered()).isEqualTo(4);
        assertThat(s.active()).isEqualTo(1);
        assertThat(s.shadow()).isEqualTo(2);
        assertThat(s.zombie()).isEqualTo(1);
        assertThat(s.unused()).isEqualTo(1);

        // dropped 메트릭 임베드 (doc/12 §2, doc/13 §1.2)
        assertThat(report.droppedNonApi()).isEqualTo(new DroppedNonApi(2, 1, 3));
        assertThat(report.droppedNonApi().total()).isEqualTo(6);
        assertThat(report.droppedByLimit()).isEqualTo(new DroppedByLimit(4, 5));
        assertThat(report.droppedByLimit().total()).isEqualTo(9);
        assertThat(report.droppedNonExistent()).isEqualTo(new DroppedNonExistent(8)); // doc/19
    }

    @Test
    void handlesEmptyFindings() {
        DiscoveryReport report = builder.build("h", 1L,
                new LogWindow(Instant.EPOCH, Instant.EPOCH), 0, List.of(), null, null, null);

        assertThat(report.findings()).isEmpty();
        assertThat(report.summary().shadow()).isZero();
        assertThat(report.summary().zombie()).isZero();
        // null 전달 → 빈 결과 으로 정규화(항상 non-null, doc/12 §3, doc/13 §1.2, doc/19 §4)
        assertThat(report.droppedNonApi()).isEqualTo(new DroppedNonApi(0, 0, 0));
        assertThat(report.droppedByLimit()).isEqualTo(new DroppedByLimit(0, 0));
        assertThat(report.droppedNonExistent()).isEqualTo(DroppedNonExistent.NONE);
    }
}
