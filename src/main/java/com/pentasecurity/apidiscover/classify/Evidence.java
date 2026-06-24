// 매칭된 관찰 d 의 누적 메트릭 + query param 후보 — Zombie severity·Active/Zombie params 입력 (doc/16 §2·§4, doc/25 §B)
package com.pentasecurity.apidiscover.classify;

import com.pentasecurity.apidiscover.model.DiscoveredEndpoint;
import com.pentasecurity.apidiscover.model.ParamCandidates;
import com.pentasecurity.apidiscover.model.ValueLenBucket;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * spec 키 1개에 매칭된 관찰 endpoint 들의 누적. host-agnostic spec 은 여러 host 의 d 가 한 키에 합산된다(doc/16 §4).
 * 가변 누적기(package-private). query param 후보는 이름 단위 union(count 합·lenBuckets 합집합·sensitive OR, doc/25 §B).
 */
final class Evidence {

    long hits;
    long success2xx;
    long total;
    Instant firstSeen;
    Instant lastSeen;
    /** query param 이름 → 누적(여러 host/관측 union). path 후보는 spec 템플릿에서 별도 산출(Classifier). */
    private final Map<String, QueryAccum> queryParams = new LinkedHashMap<>();

    void add(DiscoveredEndpoint d) {
        if (d == null) {
            return;
        }
        addMetrics(d.metrics());
        if (d.params() != null) {
            for (ParamCandidates.QueryParam q : d.params().query()) {
                QueryAccum acc = queryParams.computeIfAbsent(q.name(), k -> new QueryAccum());
                acc.count += q.count();
                acc.buckets.addAll(q.lenBuckets());
                acc.sensitive |= q.sensitive();
            }
        }
    }

    private void addMetrics(DiscoveredEndpoint.Metrics m) {
        if (m == null) {
            return;
        }
        hits += m.hits();
        if (m.statusDist() != null) {
            success2xx += m.statusDist().getOrDefault("2xx", 0L);
            total += m.statusDist().values().stream().mapToLong(Long::longValue).sum();
        }
        if (m.firstSeen() != null && (firstSeen == null || m.firstSeen().isBefore(firstSeen))) {
            firstSeen = m.firstSeen();
        }
        if (m.lastSeen() != null && (lastSeen == null || m.lastSeen().isAfter(lastSeen))) {
            lastSeen = m.lastSeen();
        }
    }

    /** 누적 query 후보(이름 정렬). path 는 호출측이 spec 템플릿으로 채움. */
    List<ParamCandidates.QueryParam> queryCandidates() {
        List<ParamCandidates.QueryParam> out = new ArrayList<>(queryParams.size());
        queryParams.forEach((name, acc) -> out.add(new ParamCandidates.QueryParam(
                name, acc.count, Set.copyOf(acc.buckets), acc.sensitive)));
        return out;
    }

    private static final class QueryAccum {
        long count;
        final EnumSet<ValueLenBucket> buckets = EnumSet.noneOf(ValueLenBucket.class);
        boolean sensitive;
    }
}
