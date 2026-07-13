// ParsedRequest 스트림 → Discovered 인벤토리(D) 집계 (doc/02 §3, §4, doc/13 §4.1 파이프라인)
package com.pentasecurity.apidiscover.normalize;

import com.pentasecurity.apidiscover.match.EndpointMatcher;
import com.pentasecurity.apidiscover.model.CanonicalEndpoint;
import com.pentasecurity.apidiscover.model.DiscoveredEndpoint;
import com.pentasecurity.apidiscover.model.DroppedByLimit;
import com.pentasecurity.apidiscover.model.DroppedNonExistent;
import com.pentasecurity.apidiscover.model.EndpointKindSignal;
import com.pentasecurity.apidiscover.model.ParsedRequest;
import com.pentasecurity.apidiscover.model.RefererSignal;
import com.pentasecurity.apidiscover.model.TemplateSource;
import com.pentasecurity.apidiscover.model.TypeDistribution;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Component;

@Component
public class InventoryBuilder {

    private final PathNormalizer pathNormalizer;
    private final EndpointKindClassifier kindClassifier;
    private final CardinalityNormalizer cardinalityNormalizer;
    private final ParamCandidateExtractor paramExtractor;
    private final RefererSignalExtractor refererSignalExtractor;

    public InventoryBuilder(PathNormalizer pathNormalizer,
                            EndpointKindClassifier kindClassifier,
                            CardinalityNormalizer cardinalityNormalizer,
                            ParamCandidateExtractor paramExtractor,
                            RefererSignalExtractor refererSignalExtractor) {
        this.pathNormalizer = pathNormalizer;
        this.kindClassifier = kindClassifier;
        this.cardinalityNormalizer = cardinalityNormalizer;
        this.paramExtractor = paramExtractor;
        this.refererSignalExtractor = refererSignalExtractor;
    }

    /** corpus $type 히스토그램 상한(top-N) — 초과분은 other 버킷(카디널리티 가드, doc/21 §3). */
    private static final int TYPE_TOP_N = 20;

    /** 인벤토리 + 상한 drop + 비실재(404-only) drop + endpoint_kind referer 신호 + $type 분포. */
    public record InventoryResult(List<DiscoveredEndpoint> endpoints, DroppedByLimit droppedByLimit,
                                  DroppedNonExistent droppedNonExistent,
                                  EndpointKindSignal endpointKindSignal,
                                  TypeDistribution typeDistribution) {}

    /**
     * 레거시: 인벤토리만 반환(하위호환). {@link #buildWithLimits} 위임.
     */
    public List<DiscoveredEndpoint> build(List<ParsedRequest> requests, EndpointMatcher matcher) {
        return buildWithLimits(requests, matcher).endpoints();
    }

    /**
     * 파이프라인(doc/13 §4.1): 1차 템플릿+집계 → T1 승격 → T1 상한 → T2 param 후보 → T3 sensitive → 방출.
     */
    public InventoryResult buildWithLimits(List<ParsedRequest> requests, EndpointMatcher matcher) {
        // 0: corpus pre-pass — referer 기반 web_page 보조 신호(정적 부모 PAGE_URLS + 커버리지 게이트, doc/20 §1)
        RefererSignal refererSignal = refererSignalExtractor.build(requests);

        // 1·2: 1차 템플릿(spec/휴리스틱) + Acc 집계(+query obs)
        Map<String, Acc> bySignature = new LinkedHashMap<>();
        for (ParsedRequest r : requests) {
            Resolved resolved = resolveTemplate(r, matcher);
            String signature = r.method() + " " + r.host() + " " + resolved.template();
            Acc acc = bySignature.computeIfAbsent(signature,
                    k -> new Acc(r.method(), r.host(), resolved.template(), resolved.source()));
            acc.add(r);
        }

        // 2.5: 실재성 404-only hard-drop (INFERRED only, 승격/상한 전, SPEC 보호) — doc/19 §2
        // 스캐너 탐침 noise 를 가장 먼저 제거해 상한 예산을 정상 template 에 보존. SPEC(스펙 매칭)은 권위라 제외 안 함.
        List<Acc> existing = new ArrayList<>(bySignature.size());
        int droppedNonExistent = 0;
        for (Acc acc : bySignature.values()) {
            if (acc.source() == TemplateSource.INFERRED && acc.isNonExistent()) {
                droppedNonExistent++;
            } else {
                existing.add(acc);
            }
        }

        // 3·4: T1 통계 {var} 승격 → host template 상한
        CardinalityNormalizer.Result norm = cardinalityNormalizer.normalize(existing);

        // 5·6·7: param 후보(+per-endpoint 상한·sensitive) → DiscoveredEndpoint 방출
        List<DiscoveredEndpoint> result = new ArrayList<>(norm.accs().size());
        int droppedParams = 0;
        for (Acc acc : norm.accs()) {
            ParamCandidateExtractor.Result pr = paramExtractor.extract(acc);
            droppedParams += pr.droppedParams();
            EndpointKindClassifier.KindResult kind =
                    kindClassifier.classify(acc.template(), acc.typeDist(), refererSignal, acc.contentTypeDist());
            result.add(acc.toEndpoint(kind.kind(), kind.confidence(), pr.candidates()));
        }
        DroppedByLimit dropped = new DroppedByLimit(norm.droppedTemplates(), droppedParams);
        EndpointKindSignal kindSignal = new EndpointKindSignal(refererSignal.status(),
                refererSignal.staticRatio(), refererSignal.refererPresentRatio());
        // corpus $type 히스토그램 — 필터 전 전체 시그니처 기준(관측 vocabulary 완전성, doc/21 §3 Tier1)
        TypeDistribution typeDist = buildTypeDistribution(bySignature.values());
        return new InventoryResult(result, dropped, new DroppedNonExistent(droppedNonExistent),
                kindSignal, typeDist);
    }

    /** corpus 전체 Acc 의 typeDist 를 합산 → count 내림차순 top-N + other 버킷 (doc/21 §3 Tier1). */
    private static TypeDistribution buildTypeDistribution(Collection<Acc> accs) {
        Map<String, Long> corpus = new HashMap<>();
        for (Acc acc : accs) {
            acc.typeDist().forEach((t, c) -> corpus.merge(t, c, Long::sum));
        }
        if (corpus.isEmpty()) {
            return TypeDistribution.NONE;
        }
        List<Map.Entry<String, Long>> sorted = new ArrayList<>(corpus.entrySet());
        // count 내림차순, 동률은 type 오름차순(안정적 top-N 컷)
        sorted.sort(Comparator.comparingLong((Map.Entry<String, Long> e) -> e.getValue()).reversed()
                .thenComparing(Map.Entry::getKey));
        List<TypeDistribution.Entry> top = new ArrayList<>();
        long other = 0;
        for (int i = 0; i < sorted.size(); i++) {
            if (i < TYPE_TOP_N) {
                top.add(new TypeDistribution.Entry(sorted.get(i).getKey(), sorted.get(i).getValue()));
            } else {
                other += sorted.get(i).getValue();
            }
        }
        return new TypeDistribution(top, other);
    }

    /** 스펙 우선 매칭 → 없으면 휴리스틱. */
    private Resolved resolveTemplate(ParsedRequest r, EndpointMatcher matcher) {
        if (matcher != null) {
            Optional<CanonicalEndpoint> matched = matcher.match(r.method(), r.host(), r.rawPath());
            if (matched.isPresent()) {
                return new Resolved(matched.get().pathTemplate(), TemplateSource.SPEC);
            }
        }
        return new Resolved(pathNormalizer.inferTemplate(r.rawPath()), TemplateSource.INFERRED);
    }

    private record Resolved(String template, TemplateSource source) {}
}
