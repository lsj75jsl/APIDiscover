// EndpointKindClassifier ($type 기반) 단위 테스트 (doc/02 §5)
package com.pentasecurity.apidiscover.normalize;

import static org.assertj.core.api.Assertions.assertThat;

import com.pentasecurity.apidiscover.model.EndpointKind;
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
}
