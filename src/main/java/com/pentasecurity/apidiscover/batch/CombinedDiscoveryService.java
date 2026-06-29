// host 단위 결합 Discovery 뷰 — 누적 discovered_endpoint ∪ active spec 을 Classifier(불변)로 분류 (doc/26 §6/§7)
package com.pentasecurity.apidiscover.batch;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pentasecurity.apidiscover.classify.ApiScorer;
import com.pentasecurity.apidiscover.classify.Classifier;
import com.pentasecurity.apidiscover.classify.EffectiveClassification;
import com.pentasecurity.apidiscover.classify.EffectiveClassificationResolver;
import com.pentasecurity.apidiscover.domain.DiscoveredEndpointRecord;
import com.pentasecurity.apidiscover.domain.DiscoveredEndpointRepository;
import com.pentasecurity.apidiscover.domain.DomainConfigRepository;
import com.pentasecurity.apidiscover.match.EndpointMatcher;
import com.pentasecurity.apidiscover.match.EndpointMatcherCache;
import com.pentasecurity.apidiscover.model.CanonicalEndpoint;
import com.pentasecurity.apidiscover.model.ClassificationProfile;
import com.pentasecurity.apidiscover.model.CombinedDiscovery;
import com.pentasecurity.apidiscover.model.DiscoveredEndpoint;
import com.pentasecurity.apidiscover.model.EffectiveClassificationView;
import com.pentasecurity.apidiscover.model.EndpointKind;
import com.pentasecurity.apidiscover.model.Finding;
import com.pentasecurity.apidiscover.model.ParamCandidates;
import com.pentasecurity.apidiscover.model.SpecMergeStrategy;
import com.pentasecurity.apidiscover.model.SpecSource;
import com.pentasecurity.apidiscover.model.TemplateSource;
import com.pentasecurity.apidiscover.model.VersionTag;
import com.pentasecurity.apidiscover.spec.SpecStore;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class CombinedDiscoveryService {

    private static final Logger log = LoggerFactory.getLogger(CombinedDiscoveryService.class);
    private static final TypeReference<Map<String, Long>> STATUS_DIST = new TypeReference<>() {};

    private final DiscoveredEndpointRepository discoveredRepo;
    private final SpecStore specStore;
    private final EndpointMatcherCache matcherCache;
    private final Classifier classifier;
    private final EffectiveClassificationResolver classificationResolver;
    private final DomainConfigRepository domainRepo;
    private final ObjectMapper objectMapper;

    public CombinedDiscoveryService(DiscoveredEndpointRepository discoveredRepo,
                                    SpecStore specStore,
                                    EndpointMatcherCache matcherCache,
                                    Classifier classifier,
                                    EffectiveClassificationResolver classificationResolver,
                                    DomainConfigRepository domainRepo,
                                    ObjectMapper objectMapper) {
        this.discoveredRepo = discoveredRepo;
        this.specStore = specStore;
        this.matcherCache = matcherCache;
        this.classifier = classifier;
        this.classificationResolver = classificationResolver;
        this.domainRepo = domainRepo;
        this.objectMapper = objectMapper;
    }

    /**
     * host 결합 Discovery: 누적 검출 카탈로그(discovered_endpoint) ∪ active spec → Classifier(불변)로
     * Shadow/Active/Zombie/Unused 산출. VERSION_GROUPED 모드면 version 라벨별 그룹도 함께 노출(그 외 flat).
     * per-scan /result 와 별개의 누적 뷰(분류 로직·게이트는 동일, 입력만 누적 카탈로그).
     */
    public CombinedDiscovery forHost(String host) {
        List<DiscoveredEndpoint> discovered = discoveredRepo.findByHost(host).stream()
                .map(this::toDiscovered).toList();

        boolean hasSpec = !specStore.activeRecords(host).isEmpty();
        List<CanonicalEndpoint> spec = hasSpec ? specStore.loadActiveCanonical(host) : List.of();
        long specVersion = spec.isEmpty() ? 0L : SpecStore.syntheticVersion(spec, objectMapper);
        EndpointMatcher matcher = matcherCache.get(host, specVersion, () -> new EndpointMatcher(spec));

        EffectiveClassification eff = classificationResolver.resolve(host);
        // base-path-strip (doc/27 §3) — null=off=현행. 결합 뷰도 동일 at-match 재시도.
        String stripPrefix = domainRepo.findById(host).map(c -> c.getBasePathStrip()).orElse(null);
        // 판단 근거 동봉(doc/34) — 스캔 경로와 동일 게이트/corsKeys, findings 와 rationale 병렬. 추가 조회 0(eff·discovered 이미 보유).
        Classifier.ExplainedClassification explained =
                classifier.classifyExplained(discovered, spec, matcher, eff.scorer(), eff.hints(), stripPrefix);
        List<Finding> findings = explained.findings();

        SpecMergeStrategy mode = modeOf(host);
        SpecSource specSource = hasSpec
                ? SpecStore.specSourceFrom(specVersion, specStore.activeRecords(host), objectMapper)
                : SpecSource.EMPTY;
        List<CombinedDiscovery.VersionGroup> groups = (mode == SpecMergeStrategy.VERSION_GROUPED)
                ? groupByVersion(findings, spec) : List.of();
        return new CombinedDiscovery(host, specVersion, mode, findings, groups, specSource,
                effectiveView(eff), explained.rationale());
    }

    /** effective 분류 설정 뷰(doc/34 §2): profile·threshold·weightsSource(CUSTOM=custom)·14신호 weights 맵. */
    private static EffectiveClassificationView effectiveView(EffectiveClassification eff) {
        String src = (eff.profile() == ClassificationProfile.CUSTOM) ? "custom" : "preset";
        return new EffectiveClassificationView(
                eff.profile(), eff.scorer().threshold(), src, ApiScorer.weightsAsMap(eff.weights()));
    }

    /** discovered_endpoint 행 → DiscoveredEndpoint 재구성. 분석 상세(distinctClients/p50/p95/acrm)는 카탈로그 미보유→0(doc/26 §2). */
    private DiscoveredEndpoint toDiscovered(DiscoveredEndpointRecord r) {
        Map<String, Long> statusDist = parseStatusDist(r.getStatusDistJson());
        ParamCandidates params = parseParams(r.getParamsJson());
        var metrics = new DiscoveredEndpoint.Metrics(
                r.getHits(), r.getFirstSeen(), r.getLastSeen(), statusDist, 0L, 0L, 0L, 0L);
        TemplateSource ts = (r.getTemplateSource() != null)
                ? TemplateSource.valueOf(r.getTemplateSource()) : TemplateSource.INFERRED;
        EndpointKind kind = (r.getEndpointKind() != null)
                ? EndpointKind.valueOf(r.getEndpointKind()) : EndpointKind.UNKNOWN;
        return new DiscoveredEndpoint(
                r.getMethod() + " " + r.getHost() + " " + r.getPathTemplate(), r.getMethod(), r.getHost(), r.getPathTemplate(),
                ts, kind, r.getKindConfidence(), r.isHadQuery(), r.isNonBrowserUa(), metrics, params);
    }

    private Map<String, Long> parseStatusDist(String json) {
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        try {
            return objectMapper.readValue(json, STATUS_DIST);
        } catch (JsonProcessingException e) {
            log.warn("corrupt discovered_endpoint statusDistJson — treating as empty");
            return Map.of();
        }
    }

    private ParamCandidates parseParams(String json) {
        if (json == null || json.isBlank()) {
            return ParamCandidates.EMPTY;
        }
        try {
            return objectMapper.readValue(json, ParamCandidates.class);
        } catch (JsonProcessingException e) {
            log.warn("corrupt discovered_endpoint paramsJson — treating as empty");
            return ParamCandidates.EMPTY;
        }
    }

    private SpecMergeStrategy modeOf(String host) {
        return domainRepo.findById(host)
                .map(c -> c.getSpecMergeStrategy())
                .filter(Objects::nonNull)
                .orElse(SpecMergeStrategy.MERGE);
    }

    /**
     * version 라벨별 findings 그룹 (doc/26 §4): 검출 path-version(^v\d+$) ∪ spec endpoint version.
     * 라벨 정렬로 결정적. 미식별=unversioned 그룹.
     */
    private static List<CombinedDiscovery.VersionGroup> groupByVersion(
            List<Finding> findings, List<CanonicalEndpoint> spec) {
        Map<String, String> specVersions = new HashMap<>();
        for (CanonicalEndpoint e : spec) {
            if (e.version() != null) {
                specVersions.put(key(e.method(), e.host(), e.pathTemplate()), e.version());
            }
        }
        Map<String, List<Finding>> byVersion = new TreeMap<>();
        for (Finding f : findings) {
            byVersion.computeIfAbsent(versionLabel(f, specVersions), k -> new ArrayList<>()).add(f);
        }
        List<CombinedDiscovery.VersionGroup> out = new ArrayList<>(byVersion.size());
        byVersion.forEach((v, fs) -> out.add(new CombinedDiscovery.VersionGroup(v, fs)));
        return out;
    }

    private static String versionLabel(Finding f, Map<String, String> specVersions) {
        String pathV = VersionTag.ofPath(f.pathTemplate());
        if (pathV != null) {
            return pathV;
        }
        String specV = specVersions.get(key(f.method(), f.host(), f.pathTemplate()));
        return (specV != null) ? specV : "unversioned";
    }

    /** 매칭 동일성 키(Classifier.key 동형). host=null → host-agnostic "*". */
    private static String key(String method, String host, String template) {
        String h = (host == null) ? "*" : host.toLowerCase(Locale.ROOT);
        return method.toUpperCase(Locale.ROOT) + "|" + h + "|" + template;
    }
}
