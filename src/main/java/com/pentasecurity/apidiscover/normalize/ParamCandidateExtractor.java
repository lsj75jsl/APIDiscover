// Acc 의 query 관측 + 템플릿 변수세그먼트 → ParamCandidates (상한·sensitive 적용) (doc/13 §2, §3)
package com.pentasecurity.apidiscover.normalize;

import com.pentasecurity.apidiscover.config.NormalizationProperties;
import com.pentasecurity.apidiscover.model.ParamCandidates;
import com.pentasecurity.apidiscover.model.ParamCandidates.PathParam;
import com.pentasecurity.apidiscover.model.ParamCandidates.QueryParam;
import com.pentasecurity.apidiscover.model.ValueLenBucket;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * endpoint 의 파라미터 후보 생성. query = 관측 이름+presence+값 길이 버킷(민감 키는 버킷 억제),
 * path = 템플릿 변수 세그먼트. per-endpoint query param 상한 초과분 drop → DroppedByLimit.params (doc/13 §1.2).
 */
@Component
public class ParamCandidateExtractor {

    private final SensitiveKeyMatcher sensitiveMatcher;
    private final NormalizationProperties props;

    public ParamCandidateExtractor(SensitiveKeyMatcher sensitiveMatcher, NormalizationProperties props) {
        this.sensitiveMatcher = sensitiveMatcher;
        this.props = props;
    }

    /** 추출 결과 + 상한 초과로 drop 된 query param 수. */
    record Result(ParamCandidates candidates, int droppedParams) {}

    Result extract(Acc acc) {
        // query 후보: 민감 키는 이름 보존 + sensitive=true + 값 길이 버킷 억제(REDACTED, doc/13 §3.2)
        List<QueryParam> query = new ArrayList<>();
        for (Map.Entry<String, Acc.ParamObs> e : acc.queryObs().entrySet()) {
            String name = e.getKey();
            Acc.ParamObs obs = e.getValue();
            boolean sensitive = sensitiveMatcher.isSensitive(name);
            Set<ValueLenBucket> buckets = (sensitive || obs.buckets.isEmpty())
                    ? Set.of()
                    : EnumSet.copyOf(obs.buckets);
            query.add(new QueryParam(name, obs.count, buckets, sensitive));
        }

        // per-endpoint 상한: 초과 시 presence count 낮은 순 drop
        int droppedParams = 0;
        int cap = props.maxQueryParamsPerEndpoint();
        if (query.size() > cap) {
            query.sort(Comparator.comparingLong(QueryParam::count).reversed());
            droppedParams = query.size() - cap;
            query = new ArrayList<>(query.subList(0, cap));
        }

        // path 후보: 템플릿 변수 세그먼트(저신뢰, T1 의 {var} 포함)
        List<PathParam> path = new ArrayList<>();
        String[] segs = segments(acc.template());
        for (int i = 0; i < segs.length; i++) {
            if (segs[i].startsWith("{") && segs[i].endsWith("}")) {
                path.add(new PathParam(i, segs[i]));
            }
        }
        return new Result(new ParamCandidates(List.copyOf(query), List.copyOf(path)), droppedParams);
    }

    private static String[] segments(String template) {
        if (template == null) {
            return new String[0];
        }
        String body = template.startsWith("/") ? template.substring(1) : template;
        return body.isEmpty() ? new String[0] : body.split("/", -1);
    }
}
