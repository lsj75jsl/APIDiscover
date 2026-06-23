// EffectiveClassificationResolver 병합/무회귀/fail-fast 단위 테스트 (doc/10 §3~§5, §7)
package com.pentasecurity.apidiscover.classify;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pentasecurity.apidiscover.domain.ClassificationConfig;
import com.pentasecurity.apidiscover.domain.ClassificationConfigRepository;
import com.pentasecurity.apidiscover.domain.DomainClassificationConfig;
import com.pentasecurity.apidiscover.domain.DomainClassificationConfigRepository;
import com.pentasecurity.apidiscover.match.ApiHintMatcher;
import com.pentasecurity.apidiscover.model.ClassificationProfile;
import com.pentasecurity.apidiscover.model.DiscoveredEndpoint;
import com.pentasecurity.apidiscover.model.EndpointKind;
import com.pentasecurity.apidiscover.model.MatcherConfig;
import com.pentasecurity.apidiscover.model.TemplateSource;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class EffectiveClassificationResolverTest {

    private static final String HOST = "shop.example.com";

    private final ClassificationConfigRepository globalRepo = mock(ClassificationConfigRepository.class);
    private final DomainClassificationConfigRepository domainRepo =
            mock(DomainClassificationConfigRepository.class);
    private final ObjectMapper om = new ObjectMapper();
    private final EffectiveClassificationResolver resolver =
            new EffectiveClassificationResolver(globalRepo, domainRepo, om);
    // 미스텁 findById → Mockito 기본 Optional.empty() (= 부재)

    private void global(ClassificationConfig g) {
        when(globalRepo.findById(1L)).thenReturn(Optional.ofNullable(g));
    }

    private void domain(DomainClassificationConfig d) {
        when(domainRepo.findById(HOST)).thenReturn(Optional.ofNullable(d));
    }

    // --- 무회귀 (최우선, §5) ---

    @Test
    void noRecordsIsNoRegression() {
        EffectiveClassification eff = resolver.resolve(HOST);
        assertThat(eff.profile()).isEqualTo(ClassificationProfile.MIDDLE);
        assertThat(eff.scorer().threshold()).isEqualTo(0.70);
        assertThat(eff.hints().isExplicitHintMode()).isFalse();
        assertThat(eff.hints().includeWebForms()).isTrue();
        // WEB_PAGE+POST·무강신호 → 억제 OFF 라 DROP_WEB_FORM 아님(현행 동작)
        assertThat(eff.scorer().evaluate(webFormPost(), false, eff.hints()))
                .isNotEqualTo(ApiScorer.Gate.DROP_WEB_FORM)
                .isEqualTo(ApiScorer.Gate.DROP_LOW_SCORE);
    }

    @Test
    void defaultSeedRecordIsNoRegression() {
        global(globalCfg(ClassificationProfile.MIDDLE, null, null, null)); // seed = override 없음
        EffectiveClassification eff = resolver.resolve(HOST);
        assertThat(eff.scorer().threshold()).isEqualTo(0.70);
        assertThat(eff.hints().includeWebForms()).isTrue();
        assertThat(eff.scorer().evaluate(webFormPost(), false, eff.hints()))
                .isEqualTo(ApiScorer.Gate.DROP_LOW_SCORE);
    }

    // --- profile/threshold/weights 병합 (§3) ---

    @Test
    void highProfileUsesHighPresetAndThreshold() {
        global(globalCfg(ClassificationProfile.HIGH, null, null, null));
        EffectiveClassification eff = resolver.resolve(HOST);
        assertThat(eff.profile()).isEqualTo(ClassificationProfile.HIGH);
        assertThat(eff.scorer().threshold()).isEqualTo(0.85);
        assertThat(eff.weights().hostApiSubdomain()).isEqualTo(0.35); // HIGH preset
    }

    @Test
    void thresholdPrecedenceDomainOverGlobalOverPreset() {
        global(globalCfg(ClassificationProfile.MIDDLE, 0.80, null, null));
        domain(domainCfg(null, 0.60, null, null));
        assertThat(resolver.resolve(HOST).scorer().threshold()).isEqualTo(0.60); // 도메인 승

        domain(domainCfg(null, null, null, null)); // 도메인 threshold 없음 → 전역
        assertThat(resolver.resolve(HOST).scorer().threshold()).isEqualTo(0.80);
    }

    @Test
    void presetProfileStillAllowsThresholdOverride() {
        global(globalCfg(ClassificationProfile.HIGH, 0.60, null, null));
        EffectiveClassification eff = resolver.resolve(HOST);
        assertThat(eff.scorer().threshold()).isEqualTo(0.60);          // override 반영
        assertThat(eff.weights().hostApiSubdomain()).isEqualTo(0.35);  // 그러나 weights 는 HIGH preset
    }

    @Test
    void presetProfileIgnoresWeightOverrides() {
        global(globalCfg(ClassificationProfile.MIDDLE, null, "{\"apiSegment\":0.99}", null));
        // preset(MIDDLE) → weights override 무시(doc/08 §5)
        assertThat(resolver.resolve(HOST).weights().apiSegment()).isEqualTo(0.55);
    }

    @Test
    void domainProfileOverridesGlobalProfile() {
        global(globalCfg(ClassificationProfile.MIDDLE, null, null, null));
        domain(domainCfg(ClassificationProfile.LOW, null, null, null));
        EffectiveClassification eff = resolver.resolve(HOST);
        assertThat(eff.profile()).isEqualTo(ClassificationProfile.LOW);
        assertThat(eff.scorer().threshold()).isEqualTo(0.55);          // LOW preset
        assertThat(eff.weights().hostApiSubdomain()).isEqualTo(0.45);  // LOW preset
    }

    @Test
    void customProfileMergesWeightsBaseGlobalDomain() {
        global(globalCfg(ClassificationProfile.CUSTOM, null, "{\"apiSegment\":0.9,\"query\":0.5}", null));
        domain(domainCfg(null, null, "{\"query\":0.7,\"pathHint\":0.33}", null));
        var w = resolver.resolve(HOST).weights();
        assertThat(w.apiSegment()).isEqualTo(0.9);     // global override
        assertThat(w.query()).isEqualTo(0.7);          // domain 승(키 충돌)
        assertThat(w.pathHint()).isEqualTo(0.33);      // domain
        assertThat(w.corsPreflight()).isEqualTo(0.30); // 미지정 → MIDDLE 베이스
        assertThat(w.threshold()).isEqualTo(0.70);     // 미지정 → MIDDLE preset
        assertThat(w.repeatMinCount()).isEqualTo(3);   // base 유지(override 범위 밖)
    }

    // --- matcher / includeWebForms (§4, §5) ---

    @Test
    void matcherUnionAcrossGlobalAndDomain() {
        global(globalCfg(ClassificationProfile.MIDDLE, null, null,
                matcherJson(new MatcherConfig(List.of("/g"), List.of(), List.of("/ex-g"), List.of(), null))));
        domain(domainCfg(null, null, null,
                matcherJson(new MatcherConfig(List.of("/d"), List.of(), List.of("/ex-d"), List.of(), null))));
        ApiHintMatcher h = resolver.resolve(HOST).hints();
        assertThat(h.apiHinted("/g/x")).isTrue();
        assertThat(h.apiHinted("/d/x")).isTrue();
        assertThat(h.excluded("/ex-g/y")).isTrue();
        assertThat(h.excluded("/ex-d/y")).isTrue();
        assertThat(h.isExplicitHintMode()).isTrue();
    }

    @Test
    void includeWebFormsDomainFalseOverrides() {
        domain(domainCfg(null, null, null,
                matcherJson(new MatcherConfig(List.of(), List.of(), List.of(), List.of(), false))));
        assertThat(resolver.resolve(HOST).hints().includeWebForms()).isFalse();
    }

    @Test
    void includeWebFormsGlobalFalseOptsInSuppression() {
        global(globalCfg(ClassificationProfile.MIDDLE, null, null,
                matcherJson(new MatcherConfig(List.of(), List.of(), List.of(), List.of(), false))));
        assertThat(resolver.resolve(HOST).hints().includeWebForms()).isFalse();
    }

    @Test
    void includeWebFormsDefaultsTrueWhenBothAbsent() {
        assertThat(resolver.resolve(HOST).hints().includeWebForms()).isTrue();
    }

    // --- fail-fast (§4) ---

    @Test
    void failsFastOnCorruptMatcherJson() {
        global(globalCfg(ClassificationProfile.MIDDLE, null, null, "{not valid json"));
        assertThatThrownBy(() -> resolver.resolve(HOST)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void failsFastOnCorruptWeightsJson() {
        global(globalCfg(ClassificationProfile.CUSTOM, null, "{not valid", null));
        assertThatThrownBy(() -> resolver.resolve(HOST)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void failsFastOnInvalidMatcherConfig() {
        // 빈 prefix → ApiHintMatcher 빌드 시 IllegalArgumentException (조용히 default 금지)
        global(globalCfg(ClassificationProfile.MIDDLE, null, null, "{\"apiPathPrefixes\":[\"\"]}"));
        assertThatThrownBy(() -> resolver.resolve(HOST)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void presetProfileStillValidatesCorruptWeightsJson() {
        // P3-1: preset(MIDDLE) 라도 customWeightsJson 손상 시 throw (값 적용 안 해도 항상 파싱·검증)
        global(globalCfg(ClassificationProfile.MIDDLE, null, "{not valid", null));
        assertThatThrownBy(() -> resolver.resolve(HOST)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void presetProfileRejectsUnknownWeightKey() {
        // P2-1/P3-1: preset 라도 오타 키 reject (always-validate)
        global(globalCfg(ClassificationProfile.MIDDLE, null, "{\"apiSegmnet\":0.5}", null));
        assertThatThrownBy(() -> resolver.resolve(HOST)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsGlobalThresholdOutOfRange() {
        // P3-2: 범위 밖 threshold → throw
        global(globalCfg(ClassificationProfile.MIDDLE, 2.0, null, null));
        assertThatThrownBy(() -> resolver.resolve(HOST)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsDomainThresholdOutOfRange() {
        domain(domainCfg(null, -1.0, null, null));
        assertThatThrownBy(() -> resolver.resolve(HOST)).isInstanceOf(IllegalArgumentException.class);
    }

    // --- helpers ---

    private static ClassificationConfig globalCfg(ClassificationProfile profile, Double threshold,
                                                  String customWeightsJson, String matcherJson) {
        var c = new ClassificationConfig();
        c.id = 1L;
        c.profile = profile;
        c.thresholdOverride = threshold;
        c.customWeightsJson = customWeightsJson;
        c.matcherJson = matcherJson;
        return c;
    }

    private static DomainClassificationConfig domainCfg(ClassificationProfile profile, Double threshold,
                                                        String customWeightsJson, String matcherJson) {
        var d = new DomainClassificationConfig();
        d.host = HOST;
        d.profile = profile;
        d.thresholdOverride = threshold;
        d.customWeightsJson = customWeightsJson;
        d.matcherJson = matcherJson;
        return d;
    }

    private String matcherJson(MatcherConfig m) {
        try {
            return om.writeValueAsString(m);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static DiscoveredEndpoint webFormPost() {
        var m = new DiscoveredEndpoint.Metrics(100, Instant.EPOCH, Instant.EPOCH, Map.of("2xx", 100L), 5, 10, 50);
        return new DiscoveredEndpoint("POST " + HOST + " /account/update", "POST", HOST, "/account/update",
                TemplateSource.INFERRED, EndpointKind.WEB_PAGE, 0.0, false, false, m);
    }
}
