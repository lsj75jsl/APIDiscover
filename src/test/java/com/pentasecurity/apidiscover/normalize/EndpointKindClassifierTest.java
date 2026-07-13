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
    void configSecretExtensionsAreStaticButJsonYamlAreNot() {
        // 설정·시크릿 파일 하드 veto(사용자 요구) — 스캐너의 .env/키/설정 하베스팅 오탐 차단
        assertThat(EndpointKindClassifier.isStaticPath("/api/shared/.env")).isTrue();
        assertThat(EndpointKindClassifier.isStaticPath("/account/api-config.ini")).isTrue();
        assertThat(EndpointKindClassifier.isStaticPath("/api/keys/private.key/config/keys.pem")).isTrue();
        assertThat(EndpointKindClassifier.isStaticPath("/api/private.key")).isTrue();
        assertThat(EndpointKindClassifier.isStaticPath("/api/config/vars.tf")).isTrue();
        assertThat(EndpointKindClassifier.isStaticPath("/api/objects/codes.php.save")).isTrue();
        // ★.json/.yaml 은 veto 제외(사용자 확정) — 진짜 데이터 API 다수라 endsWith veto 시 대량 오탐
        assertThat(EndpointKindClassifier.isStaticPath("/chart_data/42/dat.json")).isFalse();
        assertThat(EndpointKindClassifier.isStaticPath("/api/status.json")).isFalse();
        assertThat(EndpointKindClassifier.isStaticPath("/openapi.yaml")).isFalse();
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

    // --- 8.3 응답 Content-Type 분기 (4-arg, doc/40 §4.3) ---

    private KindResult ct(String path, Map<String, Long> typeDist, Map<String, Long> ctDist) {
        return classifier.classify(path, typeDist, RefererSignal.dormant(), ctDist);
    }

    @Test
    void contentTypeJsonIsApiCandidateWithFractionConfidence() {
        KindResult r = ct("/svc/data", Map.of(), Map.of("application/json", 8L, "text/html", 2L));
        assertThat(r.kind()).isEqualTo(EndpointKind.API_CANDIDATE);
        assertThat(r.confidence()).isEqualTo(0.8);
    }

    @Test
    void contentTypeHtmlIsWebPageIncludingXhtml() {
        assertThat(ct("/page", Map.of(), Map.of("text/html", 5L)).kind()).isEqualTo(EndpointKind.WEB_PAGE);
        // xhtml+xml 은 html 검사가 xml 보다 앞 → WEB_PAGE(잘못된 API 분류 회피)
        assertThat(ct("/page", Map.of(), Map.of("application/xhtml+xml", 5L)).kind())
                .isEqualTo(EndpointKind.WEB_PAGE);
    }

    @Test
    void contentTypeImageCssJsIsStatic() {
        assertThat(ct("/render", Map.of(), Map.of("image/png", 5L)).kind()).isEqualTo(EndpointKind.STATIC);
        assertThat(ct("/style", Map.of(), Map.of("text/css", 5L)).kind()).isEqualTo(EndpointKind.STATIC);
        assertThat(ct("/bundle", Map.of(), Map.of("application/javascript", 5L)).kind())
                .isEqualTo(EndpointKind.STATIC);
    }

    @Test
    void contentTypeXmlAndPlusJsonSuffixAreApi() {
        assertThat(ct("/soap", Map.of(), Map.of("application/xml", 5L)).kind())
                .isEqualTo(EndpointKind.API_CANDIDATE);
        assertThat(ct("/jsonapi", Map.of(), Map.of("application/vnd.api+json", 5L)).kind())
                .isEqualTo(EndpointKind.API_CANDIDATE);
    }

    @Test
    void contentTypeWinsOverType() {
        // CT 는 $type 앞 — $type=document 여도 CT=json 이면 API_CANDIDATE(더 직접적 신호, §4.3)
        assertThat(ct("/x", Map.of("document", 9L), Map.of("application/json", 9L)).kind())
                .isEqualTo(EndpointKind.API_CANDIDATE);
    }

    @Test
    void staticExtensionVetoBeatsContentType() {
        // §4.3 ④: 확장자 정적 veto 가 CT 보다 우선 — .js 경로는 CT=json 이어도 STATIC(D55/D56 보존)
        KindResult r = ct("/assets/app.js", Map.of(), Map.of("application/json", 9L));
        assertThat(r.kind()).isEqualTo(EndpointKind.STATIC);
        assertThat(r.confidence()).isEqualTo(1.0);
    }

    @Test
    void contentTypeBelowMajorityFallsBackToType() {
        // §4.3 ②: dominant < 0.5(혼합) → CT 분기 skip → $type 폴백
        KindResult r = ct("/x", Map.of("document", 10L),
                Map.of("application/json", 4L, "text/html", 3L, "application/xml", 3L)); // json 4/10=0.4
        assertThat(r.kind()).isEqualTo(EndpointKind.WEB_PAGE); // $type=document 폴백
    }

    @Test
    void contentTypeExactHalfTieFallsBackDeterministically() {
        // ★P2: 50/50 tie(fraction==0.5) 는 엄격 과반(>0.5) 미달 → CT 분기 skip → $type 폴백.
        //   $type=library(STATIC)로 폴백 확인 — CT 분기가 탔다면 json→API 또는 html→WEB_PAGE(never STATIC)라
        //   STATIC 단언이 비결정 분기를 판별(Map.of 반복순은 JVM 런마다 salt 랜덤 → tie skip 안 하면 flaky).
        KindResult r = ct("/svc", Map.of("library", 5L),
                Map.of("application/json", 3L, "text/html", 3L)); // 3/6 = 0.5
        assertThat(r.kind()).isEqualTo(EndpointKind.STATIC);
    }

    @Test
    void emptyContentTypeDistFallsBackToTypeNoRegression() {
        // §4.3 ③: 미수집(빈 dist) → 기존 $type 경로 그대로(무회귀)
        assertThat(ct("/api/orders", Map.of("xhr", 3L), Map.of()).kind()).isEqualTo(EndpointKind.API_CANDIDATE);
        assertThat(ct("/page", Map.of("document", 3L), Map.of()).kind()).isEqualTo(EndpointKind.WEB_PAGE);
        assertThat(ct("/x", Map.of(), Map.of()).kind()).isEqualTo(EndpointKind.UNKNOWN);
    }

    @Test
    void unmappedContentTypeFallsBackToType() {
        // 미매핑 CT(text/plain 등) → null → $type 폴백
        assertThat(ct("/x", Map.of("document", 5L), Map.of("text/plain", 5L)).kind())
                .isEqualTo(EndpointKind.WEB_PAGE);
    }
}
