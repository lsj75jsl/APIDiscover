// ReportBuilder 요약 집계 단위 테스트 (doc/01 §4)
package com.pentasecurity.apidiscover.report;

import static org.assertj.core.api.Assertions.assertThat;

import com.pentasecurity.apidiscover.ingest.LogWindow;
import com.pentasecurity.apidiscover.model.DiscoveryReport;
import com.pentasecurity.apidiscover.model.DroppedByLimit;
import com.pentasecurity.apidiscover.model.DroppedNonApi;
import com.pentasecurity.apidiscover.model.DroppedNonExistent;
import com.pentasecurity.apidiscover.model.EndpointKindSignal;
import com.pentasecurity.apidiscover.model.PreflightSignal;
import com.pentasecurity.apidiscover.model.SignalStatus;
import com.pentasecurity.apidiscover.model.SpecSource;
import com.pentasecurity.apidiscover.model.TypeDistribution;
import com.pentasecurity.apidiscover.spec.SpecFormat;
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
                new Finding.Shadow("h", "POST", "/s2", 0.4, "r", ParamCandidates.EMPTY), // <0.5 → low_confidence
                new Finding.Zombie("h", "GET", "/z", 1.0, new Severity(0.5), false, "ref", "r"),
                new Finding.Unused("h", "GET", "/u", "ref"),
                new Finding.WebPage("h", "GET", "/page", 0.8)); // 요약 제외

        LogWindow window = new LogWindow(Instant.EPOCH, Instant.EPOCH.plusSeconds(3600));
        var typeDist = new TypeDistribution(List.of(new TypeDistribution.Entry("document", 10L)), 2);
        DiscoveryReport report = builder.build("api.example.com", 7L, window, 4, findings,
                new DroppedNonApi(2, 1, 3), new DroppedByLimit(4, 5), new DroppedNonExistent(8),
                new EndpointKindSignal(SignalStatus.ACTIVE, 0.1, 0.5), typeDist,
                new PreflightSignal(SignalStatus.ACTIVE, 3),
                new SpecSource(7L, SpecFormat.OPENAPI, List.of("w1")));

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
        assertThat(s.lowConfidence()).isEqualTo(1); // /s2 confidence 0.4<0.5 (doc/25 §A.3)

        // dropped 메트릭 임베드 (doc/12 §2, doc/13 §1.2)
        assertThat(report.droppedNonApi()).isEqualTo(new DroppedNonApi(2, 1, 3));
        assertThat(report.droppedNonApi().total()).isEqualTo(6);
        assertThat(report.droppedByLimit()).isEqualTo(new DroppedByLimit(4, 5));
        assertThat(report.droppedByLimit().total()).isEqualTo(9);
        assertThat(report.droppedNonExistent()).isEqualTo(new DroppedNonExistent(8)); // doc/19
        assertThat(report.endpointKindSignal())
                .isEqualTo(new EndpointKindSignal(SignalStatus.ACTIVE, 0.1, 0.5)); // doc/20
        assertThat(report.typeDistribution()).isEqualTo(typeDist); // doc/21
        assertThat(report.typeDistribution().distinctKeys()).containsExactly("document"); // ETag 키(count 제외)
        assertThat(report.preflightSignal()).isEqualTo(new PreflightSignal(SignalStatus.ACTIVE, 3)); // doc/23
        assertThat(report.specSource()).isEqualTo(new SpecSource(7L, SpecFormat.OPENAPI, List.of("w1"))); // doc/25 §A
    }

    @Test
    void handlesEmptyFindings() {
        DiscoveryReport report = builder.build("h", 1L,
                new LogWindow(Instant.EPOCH, Instant.EPOCH), 0, List.of(), null, null, null, null, null, null, null);

        assertThat(report.findings()).isEmpty();
        assertThat(report.summary().shadow()).isZero();
        assertThat(report.summary().zombie()).isZero();
        // null 전달 → 빈 결과 으로 정규화(항상 non-null, doc/12 §3, doc/13 §1.2, doc/19 §4)
        assertThat(report.droppedNonApi()).isEqualTo(new DroppedNonApi(0, 0, 0));
        assertThat(report.droppedByLimit()).isEqualTo(new DroppedByLimit(0, 0));
        assertThat(report.droppedNonExistent()).isEqualTo(DroppedNonExistent.NONE);
        assertThat(report.endpointKindSignal()).isEqualTo(EndpointKindSignal.NONE); // doc/20
        assertThat(report.typeDistribution()).isEqualTo(TypeDistribution.NONE); // doc/21
        assertThat(report.preflightSignal()).isEqualTo(PreflightSignal.NONE); // doc/23 (null→NONE)
        assertThat(report.specSource()).isEqualTo(SpecSource.EMPTY); // doc/25 §A (null→EMPTY)
        assertThat(report.summary().lowConfidence()).isZero();
    }
}
