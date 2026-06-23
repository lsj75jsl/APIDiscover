// 분류 설정 중앙 REST API (doc/11). 전역/도메인 GET·PUT + effective 노출 + 검증→400 + PUT invalidate
package com.pentasecurity.apidiscover.api;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pentasecurity.apidiscover.api.dto.ClassificationDtos.ClassificationUpsert;
import com.pentasecurity.apidiscover.api.dto.ClassificationDtos.DomainClassificationView;
import com.pentasecurity.apidiscover.api.dto.ClassificationDtos.EffectiveView;
import com.pentasecurity.apidiscover.api.dto.ClassificationDtos.GlobalClassificationView;
import com.pentasecurity.apidiscover.api.dto.ClassificationDtos.OverrideView;
import com.pentasecurity.apidiscover.classify.ApiScorer;
import com.pentasecurity.apidiscover.classify.EffectiveClassification;
import com.pentasecurity.apidiscover.classify.EffectiveClassificationResolver;
import com.pentasecurity.apidiscover.domain.ClassificationConfig;
import com.pentasecurity.apidiscover.domain.ClassificationConfigRepository;
import com.pentasecurity.apidiscover.domain.DomainClassificationConfig;
import com.pentasecurity.apidiscover.domain.DomainClassificationConfigRepository;
import com.pentasecurity.apidiscover.domain.DomainConfigRepository;
import com.pentasecurity.apidiscover.match.ApiHintMatcher;
import com.pentasecurity.apidiscover.model.ClassificationProfile;
import com.pentasecurity.apidiscover.model.MatcherConfig;
import java.time.Instant;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * 분류 설정 전역/도메인 GET·PUT (doc/11 §1). PUT=전체 교체(null=clear), 저장 전 검증(→400), 저장 후 캐시 무효화.
 * 검증 IAE 는 컨트롤러-로컬 {@link #handleValidation}→400 으로 매핑(전역 advice 신설 안 함, 타 컨트롤러 불변).
 */
@RestController
@RequestMapping("/api/v1")
public class ClassificationController {

    private static final long GLOBAL_ID = 1L;
    private static final TypeReference<Map<String, Double>> WEIGHT_MAP = new TypeReference<>() {};

    private final ClassificationConfigRepository globalRepo;
    private final DomainClassificationConfigRepository overrideRepo;
    private final DomainConfigRepository domainRepo;
    private final EffectiveClassificationResolver resolver;
    private final ObjectMapper objectMapper;

    public ClassificationController(ClassificationConfigRepository globalRepo,
                                    DomainClassificationConfigRepository overrideRepo,
                                    DomainConfigRepository domainRepo,
                                    EffectiveClassificationResolver resolver,
                                    ObjectMapper objectMapper) {
        this.globalRepo = globalRepo;
        this.overrideRepo = overrideRepo;
        this.domainRepo = domainRepo;
        this.resolver = resolver;
        this.objectMapper = objectMapper;
    }

    // --- 전역 ---

    @GetMapping("/classification")
    public GlobalClassificationView getGlobal() {
        return toGlobalView(globalRepo.findById(GLOBAL_ID).orElse(null));
    }

    @PutMapping("/classification")
    public GlobalClassificationView putGlobal(@RequestBody ClassificationUpsert req) {
        if (req.profile() == null) {
            throw new IllegalArgumentException("global classification requires profile");
        }
        validate(req);
        ClassificationConfig c = globalRepo.findById(GLOBAL_ID).orElseGet(ClassificationConfig::new);
        c.id = GLOBAL_ID;
        c.profile = req.profile();
        c.thresholdOverride = req.thresholdOverride();
        c.customWeightsJson = toJsonOrNull(req.customWeights());
        c.matcherJson = toJsonOrNull(req.matcher());
        c.updatedAt = Instant.now();
        globalRepo.save(c);
        resolver.invalidateAll(); // 전역 변경은 모든 host effective 에 영향
        return toGlobalView(c);
    }

    // --- 도메인 ---

    @GetMapping("/domains/{host}/classification")
    public DomainClassificationView getDomain(@PathVariable String host) {
        requireDomain(host);
        return toDomainView(host);
    }

    @PutMapping("/domains/{host}/classification")
    public DomainClassificationView putDomain(@PathVariable String host,
                                              @RequestBody ClassificationUpsert req) {
        requireDomain(host);
        validate(req); // profile 은 도메인에서 nullable(상속) — required 검사 없음
        DomainClassificationConfig d = overrideRepo.findById(host).orElseGet(DomainClassificationConfig::new);
        d.host = host;
        d.profile = req.profile();
        d.thresholdOverride = req.thresholdOverride();
        d.customWeightsJson = toJsonOrNull(req.customWeights());
        d.matcherJson = toJsonOrNull(req.matcher());
        d.updatedAt = Instant.now();
        overrideRepo.save(d);
        resolver.invalidate(host);
        return toDomainView(host);
    }

    // --- 검증 (doc/11 §2) ---

    /** 저장 전 검증(스캔까지 안 끌고 감). customWeights 검증은 profile 무관 항상(적용만 CUSTOM). */
    private static void validate(ClassificationUpsert req) {
        ApiScorer.validateThreshold(req.thresholdOverride());
        ApiScorer.validateWeightOverrides(req.customWeights());
        if (req.matcher() != null) {
            new ApiHintMatcher(req.matcher()); // 상한/regex/blank/'/'시작 검증 후 폐기
        }
    }

    /** 요청 검증 IllegalArgumentException → 400 (컨트롤러-로컬, 타 컨트롤러 불변). */
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<String> handleValidation(IllegalArgumentException e) {
        return ResponseEntity.badRequest().body(e.getMessage());
    }

    /**
     * 저장 데이터 손상 IllegalStateException → 500 (doc/11 §2 P3-1).
     * <p>직접 타입 핸들러를 둬야 한다: resolver 가 손상 IAE 를 ISE(cause=IAE)로 래핑하는데, Spring 의 @ExceptionHandler 는
     * 직접 매칭 실패 시 <b>cause 체인</b>까지 탐색하므로, ISE 직접 핸들러가 없으면 cause 의 IAE 가 위 400 핸들러에 잡혀 오매핑된다.
     * 직접 ISE 핸들러가 있으면 직접 타입 매칭이 우선 → 500(요청 검증과 분리).
     */
    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<String> handleCorruptStored(IllegalStateException e) {
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(e.getMessage());
    }

    // --- 매핑 helper ---

    private void requireDomain(String host) {
        if (!domainRepo.existsById(host)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "domain not registered: " + host);
        }
    }

    private GlobalClassificationView toGlobalView(ClassificationConfig c) {
        if (c == null) {
            // 전역 분류는 개념상 항상 존재(부재=MIDDLE default), 404 아님 (doc/11 §4)
            return new GlobalClassificationView(ClassificationProfile.MIDDLE, null, null, null, null);
        }
        return new GlobalClassificationView(c.profile, c.thresholdOverride,
                readWeights(c.customWeightsJson), readMatcher(c.matcherJson), c.updatedAt);
    }

    private DomainClassificationView toDomainView(String host) {
        DomainClassificationConfig o = overrideRepo.findById(host).orElse(null);
        OverrideView override = (o == null)
                ? new OverrideView(null, null, null, null, null) // 행 부재 → 모든 필드 null (doc/11 §4)
                : new OverrideView(o.profile, o.thresholdOverride,
                        readWeights(o.customWeightsJson), readMatcher(o.matcherJson), o.updatedAt);
        EffectiveClassification eff = resolver.resolve(host); // 항상 산출 가능(전역 기반 병합)
        EffectiveView effective = new EffectiveView(eff.profile(), eff.weights(), eff.matcher());
        return new DomainClassificationView(host, override, effective);
    }

    private String toJsonOrNull(Object value) {
        if (value == null) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("failed to serialize classification config", e);
        }
    }

    private Map<String, Double> readWeights(String json) {
        if (json == null) {
            return null;
        }
        try {
            return objectMapper.readValue(json, WEIGHT_MAP);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("failed to read customWeightsJson", e);
        }
    }

    private MatcherConfig readMatcher(String json) {
        if (json == null) {
            return null;
        }
        try {
            return objectMapper.readValue(json, MatcherConfig.class);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("failed to read matcherJson", e);
        }
    }
}
