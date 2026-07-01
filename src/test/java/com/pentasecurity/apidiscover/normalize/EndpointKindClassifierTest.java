// EndpointKindClassifier ($type 기반) 단위 테스트 (doc/02 §5)
package com.pentasecurity.apidiscover.normalize;

import static org.assertj.core.api.Assertions.assertThat;

import com.pentasecurity.apidiscover.model.EndpointKind;
import com.pentasecurity.apidiscover.model.RefererSignal;
import com.pentasecurity.apidiscover.model.SignalStatus;
import com.pentasecurity.apidiscover.normalize.EndpointKindClassifier.KindResult;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class EndpointKindClassifierTest {

    private final EndpointKindClassifier classifier = new EndpointKindClassifier();

    // ★정적 규칙(activeExt/activeNameTokens)은 volatile 정적 상태 — Spring 컨텍스트 테스트(StaticClassifyRules)가
    // 바꿔놓을 수 있어, 각 단위테스트 전 기본값으로 리셋해 결정성 보장(D56).
    @BeforeEach
    void resetToDefaults() {
        EndpointKindClassifier.applyRules(
                EndpointKindClassifier.DEFAULT_STATIC_EXT, EndpointKindClassifier.DEFAULT_NAME_TOKENS);
    }

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
    void newStaticExtensionsAreStatic() {
        // D55 확장: webp/avif/bmp/tiff/otf 추가
        assertThat(EndpointKindClassifier.isStaticPath("/img/hero.webp")).isTrue();
        assertThat(EndpointKindClassifier.isStaticPath("/img/x.avif")).isTrue();
        assertThat(EndpointKindClassifier.isStaticPath("/fonts/x.otf")).isTrue();
        assertThat(EndpointKindClassifier.isStaticPath("/api/orders")).isFalse(); // 비-정적 무회귀
    }

    @Test
    void hasStaticResourceNameMatchesDynamicExtServingStatic() {
        // D55: 동적 확장자(.php)로 정적 리소스 서빙 → 파일명 토큰 매치(감점 대상)
        assertThat(EndpointKindClassifier.hasStaticResourceName("/api/blogwidget/img.php")).isTrue();
        assertThat(EndpointKindClassifier.hasStaticResourceName("/resize_image.php")).isTrue();
        assertThat(EndpointKindClassifier.hasStaticResourceName("/view_css.php")).isTrue();
        assertThat(EndpointKindClassifier.hasStaticResourceName("/api/blogwidget/link.php")).isTrue(); // D56 후속: link 토큰
        // ★확장자 없는 컬렉션 경로는 제외(REST 리소스 가능)
        assertThat(EndpointKindClassifier.hasStaticResourceName("/api/images")).isFalse();
        // ★정적 토큰 없는 동적 파일은 미해당(.php=실 API 보존)
        assertThat(EndpointKindClassifier.hasStaticResourceName("/api/blogwidget/list.php")).isFalse();
        // ★모호 토큰(photo) 제외 — 실 API 가능성
        assertThat(EndpointKindClassifier.hasStaticResourceName("/add_favorite_photo.php")).isFalse();
    }

    @Test
    void allApiTypesMapToApiCandidate() {
        // doc/21 Tier0: API_TYPES 5값 무변경 확정 — 매핑 잠금(샘플링 실관측 0이나 관례 집합 유지)
        for (String t : new String[] {"xhr", "fetch", "json", "api", "ajax"}) {
            assertThat(classifier.classify("/svc", Map.of(t, 5L)).kind())
                    .as("$type=%s", t).isEqualTo(EndpointKind.API_CANDIDATE);
        }
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
