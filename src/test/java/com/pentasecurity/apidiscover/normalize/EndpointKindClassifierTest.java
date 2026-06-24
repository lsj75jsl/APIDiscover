// EndpointKindClassifier ($type 기반) 단위 테스트 (doc/02 §5)
package com.pentasecurity.apidiscover.normalize;

import static org.assertj.core.api.Assertions.assertThat;

import com.pentasecurity.apidiscover.model.EndpointKind;
import com.pentasecurity.apidiscover.model.RefererSignal;
import com.pentasecurity.apidiscover.model.SignalStatus;
import com.pentasecurity.apidiscover.normalize.EndpointKindClassifier.KindResult;
import java.util.Map;
import org.junit.jupiter.api.Test;

class EndpointKindClassifierTest {

    private final EndpointKindClassifier classifier = new EndpointKindClassifier();

    @Test
    void documentTypeIsWebPage() {
        KindResult r = classifier.classify("/mypage/orderlist", Map.of("document", 10L));
        assertThat(r.kind()).isEqualTo(EndpointKind.WEB_PAGE);
        assertThat(r.confidence()).isEqualTo(1.0);
    }

    @Test
    void libraryTypeIsStatic() {
        KindResult r = classifier.classify("/MPushSW.min.js", Map.of("library", 5L));
        assertThat(r.kind()).isEqualTo(EndpointKind.STATIC);
    }

    @Test
    void staticExtensionIsStaticEvenWithoutType() {
        KindResult r = classifier.classify("/theme/app.css", Map.of());
        assertThat(r.kind()).isEqualTo(EndpointKind.STATIC);
        assertThat(r.confidence()).isEqualTo(1.0);
    }

    @Test
    void apiTypeIsApiCandidate() {
        KindResult r = classifier.classify("/api/orders", Map.of("xhr", 3L));
        assertThat(r.kind()).isEqualTo(EndpointKind.API_CANDIDATE);
    }

    @Test
    void unknownWhenNoSignal() {
        KindResult r = classifier.classify("/api/orders", Map.of());
        assertThat(r.kind()).isEqualTo(EndpointKind.UNKNOWN);
        assertThat(r.confidence()).isEqualTo(0.0);
    }

    @Test
    void picksDominantTypeWithFractionConfidence() {
        KindResult r = classifier.classify("/page", Map.of("document", 8L, "xhr", 2L));
        assertThat(r.kind()).isEqualTo(EndpointKind.WEB_PAGE);
        assertThat(r.confidence()).isEqualTo(0.8);
    }

    // --- referer 보조 신호 (3-arg, doc/20 §3) ---

    private static RefererSignal active(Map<String, Long> pageUrls) {
        return new RefererSignal(SignalStatus.ACTIVE, pageUrls, 0.5, 0.5);
    }

    @Test
    void refererSignalPromotesUnknownToWebPage() {
        // $type 부재(UNKNOWN) + active 신호 + PAGE_URLS 자식≥2 → WEB_PAGE 보조 양성 conf 0.6
        KindResult r = classifier.classify("/blog/{id}", Map.of(), active(Map.of("/blog/{id}", 3L)));
        assertThat(r.kind()).isEqualTo(EndpointKind.WEB_PAGE);
        assertThat(r.confidence()).isEqualTo(0.6);
    }

    @Test
    void refererAbsentOrBelowThresholdKeepsUnknown() {
        // 비대칭: PAGE_URLS 부재 → UNKNOWN(무감점), 자식 1(<2) → UNKNOWN
        assertThat(classifier.classify("/blog/{id}", Map.of(), active(Map.of())).kind())
                .isEqualTo(EndpointKind.UNKNOWN);
        assertThat(classifier.classify("/blog/{id}", Map.of(), active(Map.of("/blog/{id}", 1L))).kind())
                .isEqualTo(EndpointKind.UNKNOWN);
    }

    @Test
    void typeDecisionWinsOverRefererSignal() {
        // $type 결정 케이스는 referer 분기 안 탐 — json→API_CANDIDATE, library→STATIC, document→WEB_PAGE($type conf)
        RefererSignal s = active(Map.of("/x", 5L, "/lib", 5L, "/doc", 5L));
        assertThat(classifier.classify("/x", Map.of("json", 4L), s).kind()).isEqualTo(EndpointKind.API_CANDIDATE);
        assertThat(classifier.classify("/lib", Map.of("library", 4L), s).kind()).isEqualTo(EndpointKind.STATIC);
        KindResult doc = classifier.classify("/doc", Map.of("document", 4L), s);
        assertThat(doc.kind()).isEqualTo(EndpointKind.WEB_PAGE);
        assertThat(doc.confidence()).isEqualTo(1.0); // referer 0.6 아닌 $type conf
    }

    @Test
    void dormantSignalSkipsRefererBranch() {
        // dormant(active=false) → PAGE_URLS 있어도 보조 분기 skip → UNKNOWN 현행(2-arg 위임과 동일)
        KindResult r = classifier.classify("/blog/{id}", Map.of(), RefererSignal.dormant());
        assertThat(r.kind()).isEqualTo(EndpointKind.UNKNOWN);
    }
}
