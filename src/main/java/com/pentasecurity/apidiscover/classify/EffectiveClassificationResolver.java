// host→effective 분류 설정 해석 — 전역+도메인 로드 → 병합 → scorer/hints 빌드 (doc/10 §3~§5)
package com.pentasecurity.apidiscover.classify;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pentasecurity.apidiscover.domain.ClassificationConfig;
import com.pentasecurity.apidiscover.domain.ClassificationConfigRepository;
import com.pentasecurity.apidiscover.domain.DomainClassificationConfig;
import com.pentasecurity.apidiscover.domain.DomainClassificationConfigRepository;
import com.pentasecurity.apidiscover.match.ApiHintMatcher;
import com.pentasecurity.apidiscover.model.ClassificationProfile;
import com.pentasecurity.apidiscover.model.MatcherConfig;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Service;

/**
 * 전역 단일 레코드(PK=1L)와 도메인 override(host)를 로드해 effective 분류 설정으로 병합한다(doc/10 §3).
 *
 * <p><b>무회귀(§5)</b>: 레코드 부재/기본 전역 = 현행(ApiScorer MIDDLE + ApiHintMatcher.NONE, 무억제)과 100% 동치.
 * 전역 includeWebForms=null 을 TRUE 로 정규화한 뒤 {@link MatcherConfig#merge} 호출 → 억제는 operator opt-in.
 *
 * <p><b>fail-fast(§4)</b>: matcherJson/customWeightsJson 파싱 실패·ApiHintMatcher 상한 위반 → throw(조용히 default 금지).
 *
 * <p><b>캐시(doc/11 §3)</b>: host별 {@link ConcurrentHashMap} + {@code computeIfAbsent}. effective 불변 → 동시 read·공유 안전.
 * 무효화 주체는 REST PUT({@link #invalidate}/{@link #invalidateAll}). build throw 시 항목 미저장(poisoning 없음).
 * HA 한계: in-memory per-instance → 다중 인스턴스 stale 가능(단일 인스턴스 전제, cross-instance 무효화는 후속).
 */
@Service
public class EffectiveClassificationResolver {

    private static final long GLOBAL_ID = 1L;
    private static final TypeReference<Map<String, Double>> WEIGHT_MAP = new TypeReference<>() {};

    private final ClassificationConfigRepository globalRepo;
    private final DomainClassificationConfigRepository domainRepo;
    private final ObjectMapper objectMapper;

    /** host → effective 캐시 (doc/11 §3). PUT 무효화 외에는 스캔당 재빌드 없음. */
    private final Map<String, EffectiveClassification> cache = new ConcurrentHashMap<>();

    public EffectiveClassificationResolver(ClassificationConfigRepository globalRepo,
                                           DomainClassificationConfigRepository domainRepo,
                                           ObjectMapper objectMapper) {
        this.globalRepo = globalRepo;
        this.domainRepo = domainRepo;
        this.objectMapper = objectMapper;
    }

    /** host 의 effective 분류 설정 해석. 캐시 히트 시 재빌드 없음(doc/11 §3). */
    public EffectiveClassification resolve(String host) {
        // build throw 시 미저장 → 다음 호출 재시도(캐시 poisoning 없음, fail-fast 보존)
        return cache.computeIfAbsent(host, this::build);
    }

    /**
     * 전역+도메인 로드 → §3 병합 → §5 default/정규화 → scorer/hints 빌드 (캐시 미스 시 1회).
     *
     * <p>여기서 다루는 입력은 <b>이미 저장된 설정</b>이다(PUT 이 요청 시점에 검증). 그럼에도 out-of-band DB 쓰기·마이그레이션
     * 등으로 저장값이 손상될 수 있으므로, 검증기/매처 빌드의 {@link IllegalArgumentException} 은 <b>요청 검증이 아니라
     * 데이터 손상</b>이다 → {@link IllegalStateException} 으로 래핑(컨트롤러 IAE→400 핸들러가 못 잡고 500, doc/11 §2).
     * 손상 JSON 파싱 실패는 parse 헬퍼가 이미 ISE 로 던진다.
     */
    private EffectiveClassification build(String host) {
        ClassificationConfig global = globalRepo.findById(GLOBAL_ID).orElse(null);
        DomainClassificationConfig domain = domainRepo.findById(host).orElse(null);
        try {
            // always-validate (doc/10 §4): profile 무관하게 항상 파싱·검증. 값 적용은 CUSTOM 일 때만(검증과 적용 분리, §3).
            Map<String, Double> globalWeights = parseWeights(global != null ? global.getCustomWeightsJson() : null);
            Map<String, Double> domainWeights = parseWeights(domain != null ? domain.getCustomWeightsJson() : null);
            ApiScorer.validateWeightOverrides(globalWeights);
            ApiScorer.validateWeightOverrides(domainWeights);
            ApiScorer.validateThreshold(global != null ? global.getThresholdOverride() : null);
            ApiScorer.validateThreshold(domain != null ? domain.getThresholdOverride() : null);

            ClassificationProfile profile = firstNonNull(
                    domain != null ? domain.getProfile() : null,
                    global != null ? global.getProfile() : null,
                    ClassificationProfile.MIDDLE);

            // threshold: 도메인 > 전역 > preset (어떤 프로파일에서도 override 가능, §3)
            Double thresholdOverride = firstNonNull(
                    domain != null ? domain.getThresholdOverride() : null,
                    global != null ? global.getThresholdOverride() : null);

            // weights: preset(profile), CUSTOM 일 때만 MIDDLE 베이스 + global∪domain override(키별 domain 승, §3)
            ApiScorer.Weights base = ApiScorer.presetWeights(toApiProfile(profile));
            Map<String, Double> weightOverrides = (profile == ClassificationProfile.CUSTOM)
                    ? mergeWeightMaps(globalWeights, domainWeights)
                    : Map.of(); // preset 은 weights override 무시(doc/08 §5)
            ApiScorer.Weights weights = ApiScorer.applyOverrides(base, weightOverrides, thresholdOverride);

            // matcher: 전역∪도메인 (전역 includeWebForms=null→TRUE 정규화 후 merge, §5)
            MatcherConfig matcher = MatcherConfig.merge(globalMatcher(global), domainMatcher(domain));

            ApiScorer scorer = new ApiScorer(weights);
            ApiHintMatcher hints = new ApiHintMatcher(matcher); // 상한/내용 위반 시 fail-fast
            return new EffectiveClassification(profile, weights, matcher, scorer, hints);
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException("corrupt stored classification config for host=" + host, e);
        }
    }

    /** 도메인 PUT 시 해당 host effective 캐시 제거 (doc/11 §3). */
    public void invalidate(String host) {
        cache.remove(host);
    }

    /** 전역 PUT 시 전 호스트 effective 캐시 제거 (전역 변경은 모든 host effective 에 영향, doc/11 §3). */
    public void invalidateAll() {
        cache.clear();
    }

    // --- 병합 helper ---

    /** HIGH/LOW preset 매핑. MIDDLE/CUSTOM 은 MIDDLE 베이스(CUSTOM 은 override 가 얹힘). */
    private static ApiScorer.Profile toApiProfile(ClassificationProfile p) {
        return switch (p) {
            case HIGH -> ApiScorer.Profile.HIGH;
            case LOW -> ApiScorer.Profile.LOW;
            case MIDDLE, CUSTOM -> ApiScorer.Profile.MIDDLE;
        };
    }

    /** 전역∪도메인 weight override 병합(키별 domain 승). 이미 파싱·검증된 map 을 받는다. */
    private static Map<String, Double> mergeWeightMaps(Map<String, Double> global, Map<String, Double> domain) {
        Map<String, Double> merged = new LinkedHashMap<>(global);
        merged.putAll(domain); // 키 충돌 시 domain 승
        return merged;
    }

    /** 전역 매처: 없으면 NONE(빈+TRUE). 있으면 파싱 후 includeWebForms=null→TRUE 정규화(억제 opt-in, §5). */
    private MatcherConfig globalMatcher(ClassificationConfig global) {
        MatcherConfig m = (global != null && global.getMatcherJson() != null)
                ? parseMatcher(global.getMatcherJson(), "global")
                : MatcherConfig.NONE;
        if (m.includeWebForms() == null) {
            // includeWebForms 만 TRUE 로 정규화 — 나머지 필드(optionsOperationPrefixes 포함) 전부 보존(6-arg)
            m = new MatcherConfig(m.apiPathPrefixes(), m.apiPathRegexes(),
                    m.excludePathPrefixes(), m.excludePathRegexes(), m.optionsOperationPrefixes(), Boolean.TRUE);
        }
        return m;
    }

    /** 도메인 매처: 없으면 빈+null(상속). includeWebForms 정규화 안 함(null=전역 상속). */
    private MatcherConfig domainMatcher(DomainClassificationConfig domain) {
        return (domain != null && domain.getMatcherJson() != null)
                ? parseMatcher(domain.getMatcherJson(), "domain:" + domain.getHost())
                : new MatcherConfig(List.of(), List.of(), List.of(), List.of(), null);
    }

    private MatcherConfig parseMatcher(String json, String scope) {
        try {
            return objectMapper.readValue(json, MatcherConfig.class);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("invalid matcherJson (" + scope + "): " + json, e);
        }
    }

    private Map<String, Double> parseWeights(String json) {
        if (json == null) {
            return Map.of();
        }
        try {
            Map<String, Double> m = objectMapper.readValue(json, WEIGHT_MAP);
            return m != null ? m : Map.of();
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("invalid customWeightsJson: " + json, e);
        }
    }

    @SafeVarargs
    private static <T> T firstNonNull(T... values) {
        for (T v : values) {
            if (v != null) {
                return v;
            }
        }
        return null;
    }
}
