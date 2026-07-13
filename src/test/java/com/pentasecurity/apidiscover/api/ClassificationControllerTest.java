// 분류 설정 REST API e2e (doc/11 §6) — @SpringBootTest + MockMvc, 실 resolver/H2 병합·캐시·검증
package com.pentasecurity.apidiscover.api;

import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.pentasecurity.apidiscover.classify.EffectiveClassificationResolver;
import com.pentasecurity.apidiscover.domain.ClassificationConfig;
import com.pentasecurity.apidiscover.domain.ClassificationConfigRepository;
import com.pentasecurity.apidiscover.domain.DomainClassificationConfigRepository;
import com.pentasecurity.apidiscover.domain.DomainConfig;
import com.pentasecurity.apidiscover.domain.DomainConfigRepository;
import com.pentasecurity.apidiscover.ingest.LokiClient;
import com.pentasecurity.apidiscover.model.ClassificationProfile;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
class ClassificationControllerTest {

    @Autowired
    private MockMvc mvc;
    @Autowired
    private ClassificationConfigRepository globalRepo;
    @Autowired
    private DomainClassificationConfigRepository overrideRepo;
    @Autowired
    private DomainConfigRepository domainRepo;
    @Autowired
    private EffectiveClassificationResolver resolver;

    // 운영 Loki 보호: 컨텍스트 부팅 시 DiscoveryScheduler 가 1회 실행되더라도 실 네트워크 호출 차단
    @MockBean
    private LokiClient lokiClient;

    @BeforeEach
    void clean() {
        overrideRepo.deleteAll();
        globalRepo.deleteAll();   // seeder 가 넣은 전역 행 제거 → "부재" 경로 검증 가능
        domainRepo.deleteAll();
        resolver.invalidateAll(); // 싱글톤 캐시 초기화(테스트 간 격리)
    }

    private void registerDomain(String host) {
        DomainConfig d = new DomainConfig();
        d.setHost(host);
        d.setEnabled(true);
        d.setCreatedAt(Instant.EPOCH);
        d.setUpdatedAt(Instant.EPOCH);
        domainRepo.save(d);
    }

    // --- happy-path ---

    @Test
    void getGlobalReturnsDefaultWhenAbsent() throws Exception {
        mvc.perform(get("/api/v1/classification"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.profile").value("MIDDLE"))
                .andExpect(jsonPath("$.thresholdOverride").value(nullValue()))
                .andExpect(jsonPath("$.updatedAt").value(nullValue())); // default → null
    }

    @Test
    void putGlobalUpsertsAndGetReflects() throws Exception {
        mvc.perform(put("/api/v1/classification").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"profile\":\"HIGH\",\"thresholdOverride\":0.8}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.profile").value("HIGH"))
                .andExpect(jsonPath("$.thresholdOverride").value(0.8));

        mvc.perform(get("/api/v1/classification"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.profile").value("HIGH"))
                .andExpect(jsonPath("$.updatedAt").exists());
    }

    @Test
    void getDomainReturnsEffectiveWhenNoOverride() throws Exception {
        registerDomain("shop.example.com");
        mvc.perform(get("/api/v1/domains/shop.example.com/classification"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.host").value("shop.example.com"))
                .andExpect(jsonPath("$.override.profile").value(nullValue()))   // 행 부재 → null 필드
                .andExpect(jsonPath("$.effective.profile").value("MIDDLE"))     // 전역 default
                .andExpect(jsonPath("$.effective.threshold").value(0.7))
                .andExpect(jsonPath("$.effective.matcher.includeWebForms").value(true)); // 정규화 effective
    }

    @Test
    void putDomainUpsertsOverride() throws Exception {
        registerDomain("shop.example.com");
        mvc.perform(put("/api/v1/domains/shop.example.com/classification").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"profile\":\"LOW\",\"thresholdOverride\":0.5}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.override.profile").value("LOW"))
                .andExpect(jsonPath("$.effective.profile").value("LOW"))
                .andExpect(jsonPath("$.effective.threshold").value(0.5)); // 도메인 threshold override
    }

    // --- effective 노출 정확성 (전역 CUSTOM + 도메인 override 병합) ---

    @Test
    void effectiveMergesGlobalCustomAndDomainOverride() throws Exception {
        mvc.perform(put("/api/v1/classification").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"profile\":\"CUSTOM\",\"customWeights\":{\"apiSegment\":0.9,\"query\":0.5}}"))
                .andExpect(status().isOk());
        registerDomain("shop.example.com");
        mvc.perform(put("/api/v1/domains/shop.example.com/classification").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"customWeights\":{\"query\":0.7},\"matcher\":{\"apiPathPrefixes\":[\"/svc\"]}}"))
                .andExpect(status().isOk());

        mvc.perform(get("/api/v1/domains/shop.example.com/classification"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.effective.profile").value("CUSTOM"))           // 도메인 null → 전역 CUSTOM 상속
                .andExpect(jsonPath("$.effective.weights.apiSegment").value(0.9))     // 전역
                .andExpect(jsonPath("$.effective.weights.query").value(0.7))          // 도메인 승
                .andExpect(jsonPath("$.effective.matcher.apiPathPrefixes[0]").value("/svc"));
    }

    @Test
    void putCustomResponseTypeApiWeightReflectedInEffective() throws Exception {
        // doc/17: responseTypeApi override 가 컨트롤러/리졸버 변경 없이 WEIGHT_KEYS 검증 통과·echo·effective 반영
        mvc.perform(put("/api/v1/classification").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"profile\":\"CUSTOM\",\"customWeights\":{\"responseTypeApi\":0.42}}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.customWeights.responseTypeApi").value(0.42)); // echo
        registerDomain("shop.example.com");
        mvc.perform(get("/api/v1/domains/shop.example.com/classification"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.effective.weights.responseTypeApi").value(0.42)); // 전역 CUSTOM 상속
    }

    @Test
    void putMatcherOptionsOperationPrefixesReflectedInEffective() throws Exception {
        // doc/23 §8 M2: optionsOperationPrefixes 가 MatcherConfig=REST DTO 라 컨트롤러 변경 없이 수용·effective 반영
        mvc.perform(put("/api/v1/classification").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"profile\":\"MIDDLE\",\"matcher\":{\"optionsOperationPrefixes\":[\"/api/widgets\"]}}"))
                .andExpect(status().isOk());
        registerDomain("shop.example.com");
        mvc.perform(get("/api/v1/domains/shop.example.com/classification"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.effective.matcher.optionsOperationPrefixes[0]").value("/api/widgets"));
    }

    @Test
    void putRejectsInvalidOptionsOperationPrefix() throws Exception {
        // 검증 자동(ApiHintMatcher validatePrefixes 재사용) — '/' 미시작 prefix → 400 (profile 포함, 검증이 진짜 prefix 에서 터지게)
        mvc.perform(put("/api/v1/classification").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"profile\":\"MIDDLE\",\"matcher\":{\"optionsOperationPrefixes\":[\"api/widgets\"]}}"))
                .andExpect(status().isBadRequest());
    }

    // --- 400 검증 ---

    @Test
    void putRejectsUnknownWeightKey() throws Exception {
        mvc.perform(put("/api/v1/classification").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"profile\":\"MIDDLE\",\"customWeights\":{\"apiSegmnet\":0.5}}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void putRejectsThresholdOutOfRange() throws Exception {
        mvc.perform(put("/api/v1/classification").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"profile\":\"MIDDLE\",\"thresholdOverride\":2.0}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void putRejectsBadMatcherRegex() throws Exception {
        mvc.perform(put("/api/v1/classification").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"profile\":\"MIDDLE\",\"matcher\":{\"apiPathRegexes\":[\"(unclosed\"]}}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void putGlobalRequiresProfile() throws Exception {
        mvc.perform(put("/api/v1/classification").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"thresholdOverride\":0.5}"))
                .andExpect(status().isBadRequest());
    }

    // --- 부재 / 404 ---

    @Test
    void domainEndpointsReturn404WhenDomainNotRegistered() throws Exception {
        mvc.perform(get("/api/v1/domains/ghost.example.com/classification"))
                .andExpect(status().isNotFound());
        mvc.perform(put("/api/v1/domains/ghost.example.com/classification").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"profile\":\"LOW\"}"))
                .andExpect(status().isNotFound());
    }

    // --- invalidate 반영 (캐시) ---

    @Test
    void putDomainInvalidatesCacheSoGetReflectsImmediately() throws Exception {
        registerDomain("shop.example.com");
        mvc.perform(get("/api/v1/domains/shop.example.com/classification"))
                .andExpect(jsonPath("$.effective.threshold").value(0.7)); // 캐시 적재(MIDDLE 0.70)

        mvc.perform(put("/api/v1/domains/shop.example.com/classification").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"thresholdOverride\":0.5}"))
                .andExpect(status().isOk());

        mvc.perform(get("/api/v1/domains/shop.example.com/classification"))
                .andExpect(jsonPath("$.effective.threshold").value(0.5)); // 즉시 반영(무효화)
    }

    @Test
    void putGlobalInvalidatesAllHosts() throws Exception {
        registerDomain("a.example.com");
        mvc.perform(get("/api/v1/domains/a.example.com/classification"))
                .andExpect(jsonPath("$.effective.profile").value("MIDDLE")); // 캐시 적재

        mvc.perform(put("/api/v1/classification").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"profile\":\"HIGH\"}"))
                .andExpect(status().isOk());

        mvc.perform(get("/api/v1/domains/a.example.com/classification"))
                .andExpect(jsonPath("$.effective.profile").value("HIGH"))      // 전역 변경 전 호스트 반영
                .andExpect(jsonPath("$.effective.threshold").value(0.85));
    }

    // --- A2: PATCH weights (부분 편집·profile 자동 CUSTOM·편집 안 한 키 유지, doc/35 A2) ---

    @Test
    void patchDomainWeightsBecomesCustomKeepingUneditedEffectiveKeys() throws Exception {
        // 전역 HIGH → 도메인은 HIGH 상속. 1키 PATCH 후 나머지는 HIGH 값 유지(MIDDLE 리셋 아님)
        mvc.perform(put("/api/v1/classification").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"profile\":\"HIGH\"}")).andExpect(status().isOk());
        registerDomain("shop.example.com");

        mvc.perform(patch("/api/v1/domains/shop.example.com/classification/weights")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"apiSegment\":0.99}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.override.profile").value("CUSTOM"))            // 자동 CUSTOM
                .andExpect(jsonPath("$.effective.profile").value("CUSTOM"))
                .andExpect(jsonPath("$.effective.weights.apiSegment").value(0.99))    // 편집 키 반영
                .andExpect(jsonPath("$.effective.weights.hostApiSubdomain").value(0.35)); // ★HIGH 값 유지(MIDDLE 0.40 아님)
    }

    @Test
    void patchGlobalWeightsBecomesCustomKeepingUneditedEffectiveKeys() throws Exception {
        mvc.perform(put("/api/v1/classification").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"profile\":\"HIGH\"}")).andExpect(status().isOk());

        mvc.perform(patch("/api/v1/classification/weights")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"query\":0.99}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.profile").value("CUSTOM"))
                .andExpect(jsonPath("$.customWeights.query").value(0.99))             // 편집 키
                .andExpect(jsonPath("$.customWeights.hostApiSubdomain").value(0.35)); // ★HIGH 값 유지
    }

    @Test
    void patchDomainWeightsLeavesThresholdUntouched() throws Exception {
        registerDomain("shop.example.com");
        mvc.perform(put("/api/v1/domains/shop.example.com/classification").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"profile\":\"LOW\",\"thresholdOverride\":0.5}")).andExpect(status().isOk());

        mvc.perform(patch("/api/v1/domains/shop.example.com/classification/weights")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"apiSegment\":0.9}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.effective.profile").value("CUSTOM"))
                .andExpect(jsonPath("$.effective.threshold").value(0.5)); // threshold 미터치(weights 만)
    }

    @Test
    void patchWeightsRejectsUnknownKey() throws Exception {
        mvc.perform(patch("/api/v1/classification/weights")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"apiSegmnet\":0.5}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void putClassificationUnaffectedByA2() throws Exception {
        // A2(PATCH)=가산. 기존 PUT /classification 전체 교체 동작 불변
        mvc.perform(put("/api/v1/classification").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"profile\":\"LOW\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.profile").value("LOW"));
    }

    // --- P3-1: 저장 데이터 손상 → 서버 오류(500), 요청 검증(400) 아님 ---

    @Test
    void storedCorruptConfigSurfacesAsServerErrorNot400() throws Exception {
        registerDomain("shop.example.com");
        // out-of-band: 컨트롤러 검증 우회하고 손상된 전역 설정(threshold 범위 밖) 직접 저장
        ClassificationConfig bad = new ClassificationConfig();
        bad.setId(1L);
        bad.setProfile(ClassificationProfile.MIDDLE);
        bad.setThresholdOverride(2.0); // 범위 밖
        bad.setUpdatedAt(Instant.EPOCH);
        globalRepo.save(bad);
        resolver.invalidateAll();

        // effective 산출(resolver.build) 중 손상 감지 → ISE → 500. 요청 검증(400) 아님.
        mvc.perform(get("/api/v1/domains/shop.example.com/classification"))
                .andExpect(status().is5xxServerError());
    }

    // --- P3-2: PUT=전체 교체(null=clear) 회귀 ---

    @Test
    void putReplacesAndClearsOmittedFields() throws Exception {
        // 1차: weights + matcher 포함
        mvc.perform(put("/api/v1/classification").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"profile\":\"CUSTOM\",\"customWeights\":{\"apiSegment\":0.9},"
                                + "\"matcher\":{\"apiPathPrefixes\":[\"/svc\"]}}"))
                .andExpect(status().isOk());
        mvc.perform(get("/api/v1/classification"))
                .andExpect(jsonPath("$.customWeights.apiSegment").value(0.9))
                .andExpect(jsonPath("$.matcher.apiPathPrefixes[0]").value("/svc"));

        // 2차: weights/matcher 생략(null) → 해당 항목 clear (PATCH 아님)
        mvc.perform(put("/api/v1/classification").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"profile\":\"MIDDLE\"}"))
                .andExpect(status().isOk());
        mvc.perform(get("/api/v1/classification"))
                .andExpect(jsonPath("$.profile").value("MIDDLE"))
                .andExpect(jsonPath("$.customWeights").value(nullValue())) // clear
                .andExpect(jsonPath("$.matcher").value(nullValue()));       // clear
    }

    // --- P3-3: 전역 GET round-trip (customWeights·matcher echo) ---

    @Test
    void globalGetRoundTripsCustomWeightsAndMatcher() throws Exception {
        mvc.perform(put("/api/v1/classification").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"profile\":\"CUSTOM\",\"customWeights\":{\"apiSegment\":0.9,\"query\":0.5},"
                                + "\"matcher\":{\"apiPathPrefixes\":[\"/api\"],"
                                + "\"excludePathPrefixes\":[\"/legacy\"],\"includeWebForms\":false}}"))
                .andExpect(status().isOk());

        mvc.perform(get("/api/v1/classification"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.profile").value("CUSTOM"))
                .andExpect(jsonPath("$.customWeights.apiSegment").value(0.9))
                .andExpect(jsonPath("$.customWeights.query").value(0.5))
                .andExpect(jsonPath("$.matcher.apiPathPrefixes[0]").value("/api"))
                .andExpect(jsonPath("$.matcher.excludePathPrefixes[0]").value("/legacy"))
                .andExpect(jsonPath("$.matcher.includeWebForms").value(false)); // 저장값 그대로 echo(정규화 전)
    }

    // --- D78: effective 노출 강화 (threshold 최상위 분리·weightsSource·repeatMinCount·descriptions ko/en) ---

    @Test
    void globalGetExposesEffectivePolicyValuesForPreset() throws Exception {
        // preset(부재=MIDDLE)이라 저장 customWeights=null 이어도 effective 로 실적용 14 weight·threshold 노출
        mvc.perform(get("/api/v1/classification"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.customWeights").value(nullValue()))         // 저장값은 여전히 null
                .andExpect(jsonPath("$.effective.profile").value("MIDDLE"))
                .andExpect(jsonPath("$.effective.weightsSource").value("preset"))
                .andExpect(jsonPath("$.effective.threshold").value(0.7))            // ★최상위 분리
                .andExpect(jsonPath("$.effective.repeatMinCount").value(3))         // ★최상위(override 불가)
                .andExpect(jsonPath("$.effective.weights.apiSegment").value(0.55))
                .andExpect(jsonPath("$.effective.weights.staticAssetPenalty").value(-0.6))
                .andExpect(jsonPath("$.effective.weights.threshold").doesNotExist()); // threshold 는 weights 안에 없음
    }

    @Test
    void globalGetCustomEffectiveShowsWeightsSourceCustom() throws Exception {
        mvc.perform(put("/api/v1/classification").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"profile\":\"CUSTOM\",\"customWeights\":{\"apiSegment\":0.9},"
                                + "\"thresholdOverride\":0.6}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.effective.weightsSource").value("custom"))
                .andExpect(jsonPath("$.effective.threshold").value(0.6))            // override 반영
                .andExpect(jsonPath("$.effective.weights.apiSegment").value(0.9));
    }

    @Test
    void descriptionsAttachedWithKoEnAndCompleteKeys() throws Exception {
        mvc.perform(get("/api/v1/classification"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.descriptions.*", hasSize(20)))              // 18 weight + threshold + repeatMinCount
                .andExpect(jsonPath("$.descriptions.threshold.ko").isNotEmpty())
                .andExpect(jsonPath("$.descriptions.threshold.en").isNotEmpty())
                .andExpect(jsonPath("$.descriptions.graphqlSegment.ko").isNotEmpty())
                .andExpect(jsonPath("$.descriptions.repeatMinCount.en").isNotEmpty());
    }

    @Test
    void domainGetAttachesDescriptionsAndTopLevelThreshold() throws Exception {
        registerDomain("shop.example.com");
        mvc.perform(get("/api/v1/domains/shop.example.com/classification"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.effective.threshold").value(0.7))
                .andExpect(jsonPath("$.effective.weightsSource").value("preset"))
                .andExpect(jsonPath("$.effective.repeatMinCount").value(3))
                .andExpect(jsonPath("$.descriptions.*", hasSize(20)));
    }
}
