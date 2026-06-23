// ParsedRequest 스트림 → Discovered 인벤토리(D) 집계 (doc/02 §3, §4, doc/13 §4.1 파이프라인)
package com.pentasecurity.apidiscover.normalize;

import com.pentasecurity.apidiscover.match.EndpointMatcher;
import com.pentasecurity.apidiscover.model.CanonicalEndpoint;
import com.pentasecurity.apidiscover.model.DiscoveredEndpoint;
import com.pentasecurity.apidiscover.model.DroppedByLimit;
import com.pentasecurity.apidiscover.model.DroppedNonExistent;
import com.pentasecurity.apidiscover.model.ParsedRequest;
import com.pentasecurity.apidiscover.model.TemplateSource;
import java.util.ArrayList;
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

    public InventoryBuilder(PathNormalizer pathNormalizer,
                            EndpointKindClassifier kindClassifier,
                            CardinalityNormalizer cardinalityNormalizer,
                            ParamCandidateExtractor paramExtractor) {
        this.pathNormalizer = pathNormalizer;
        this.kindClassifier = kindClassifier;
        this.cardinalityNormalizer = cardinalityNormalizer;
        this.paramExtractor = paramExtractor;
    }

    /** 인벤토리 + 상한 drop + 비실재(404-only) drop 집계. */
    public record InventoryResult(List<DiscoveredEndpoint> endpoints, DroppedByLimit droppedByLimit,
                                  DroppedNonExistent droppedNonExistent) {}

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
                    kindClassifier.classify(acc.template(), acc.typeDist());
            result.add(acc.toEndpoint(kind.kind(), kind.confidence(), pr.candidates()));
        }
        DroppedByLimit dropped = new DroppedByLimit(norm.droppedTemplates(), droppedParams);
        return new InventoryResult(result, dropped, new DroppedNonExistent(droppedNonExistent));
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
