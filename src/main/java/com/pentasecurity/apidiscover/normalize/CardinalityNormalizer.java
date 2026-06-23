// 통계적 {var} 승격(2차 패스) + host template 상한 (doc/13 §1, doc/02 §3.3/§3.4)
package com.pentasecurity.apidiscover.normalize;

import com.pentasecurity.apidiscover.config.NormalizationProperties;
import com.pentasecurity.apidiscover.model.TemplateSource;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * 1차 휴리스틱이 못 잡은 고카디널리티 정적 세그먼트를 통계적으로 {var} 로 수렴(승격)하고,
 * 그래도 폭발하는 host 는 template 상한으로 잘라낸다(최후 안전망).
 *
 * <p>승격 조건(전부 충족, doc/13 §1.1): 클러스터(method/host/segcount/위치 i 제외 세그먼트 동일)에서
 * distinct≥statVarMinDistinct, distinct/requests≥statVarRatio, 수렴(한 값 비지배)≥statVarMinConvergence.
 */
@Component
public class CardinalityNormalizer {

    private static final char K = '\u0001'; // cluster key 구분자(경로에 없음)

    private final NormalizationProperties props;

    public CardinalityNormalizer(NormalizationProperties props) {
        this.props = props;
    }

    /** 승격·상한 적용 결과. */
    record Result(List<Acc> accs, int droppedTemplates) {}

    Result normalize(List<Acc> input) {
        List<Acc> work = promoteVars(input);
        return capPerHost(work);
    }

    // --- 1.1 통계 {var} 승격 ---

    private List<Acc> promoteVars(List<Acc> input) {
        List<Acc> work = new ArrayList<>(input);
        int maxSeg = work.stream().mapToInt(a -> segments(a.template()).length).max().orElse(0);
        for (int pos = 0; pos < maxSeg; pos++) {
            work = dedupBySignature(promoteAtPosition(work, pos));
        }
        return work;
    }

    private List<Acc> promoteAtPosition(List<Acc> work, int pos) {
        Map<String, List<Acc>> clusters = new LinkedHashMap<>();
        List<Acc> out = new ArrayList<>();
        for (Acc a : work) {
            String[] segs = segments(a.template());
            if (segs.length <= pos) {
                out.add(a); // 해당 위치 없음 → 그대로
                continue;
            }
            clusters.computeIfAbsent(clusterKey(a, segs, pos), k -> new ArrayList<>()).add(a);
        }
        for (List<Acc> cluster : clusters.values()) {
            List<Acc> statics = new ArrayList<>();
            List<Acc> others = new ArrayList<>();
            for (Acc a : cluster) {
                if (isStatic(segments(a.template())[pos])) {
                    statics.add(a);
                } else {
                    others.add(a);
                }
            }
            if (shouldPromote(statics)) {
                String[] segs = segments(statics.get(0).template());
                Acc merged = new Acc(statics.get(0).method(), statics.get(0).host(),
                        withVar(segs, pos), TemplateSource.INFERRED);
                for (Acc s : statics) {
                    merged.mergeFrom(s);
                }
                out.add(merged);
                out.addAll(others);
            } else {
                out.addAll(cluster);
            }
        }
        return out;
    }

    private boolean shouldPromote(List<Acc> statics) {
        int distinct = statics.size(); // 같은 클러스터 내 정적값은 서로 다름 → distinct = 멤버 수
        if (distinct < props.statVarMinDistinct()) {
            return false;
        }
        long clusterHits = statics.stream().mapToLong(Acc::hits).sum();
        if (clusterHits <= 0) {
            return false;
        }
        if ((double) distinct / clusterHits < props.statVarRatio()) {
            return false; // 저비율 = enum 류
        }
        long maxHits = statics.stream().mapToLong(Acc::hits).max().orElse(0);
        // 수렴: 한 값이 지배적이면 ID 공간이 아님(false merge 방지). merged_hits(비지배분)/cluster_hits ≥ 임계
        double convergence = (double) (clusterHits - maxHits) / clusterHits;
        return convergence >= props.statVarMinConvergence();
    }

    // --- 1.2 host template 상한 ---

    private Result capPerHost(List<Acc> work) {
        Map<String, List<Acc>> byHost = new LinkedHashMap<>();
        for (Acc a : work) {
            byHost.computeIfAbsent(a.host(), k -> new ArrayList<>()).add(a);
        }
        List<Acc> kept = new ArrayList<>();
        int dropped = 0;
        int cap = props.maxTemplatesPerHost();
        for (List<Acc> group : byHost.values()) {
            if (group.size() <= cap) {
                kept.addAll(group);
                continue;
            }
            group.sort(Comparator.comparingLong(Acc::hits).reversed()); // hits 높은 순 보존
            kept.addAll(group.subList(0, cap));
            dropped += group.size() - cap; // 초과분(hits 낮은) drop
        }
        return new Result(kept, dropped);
    }

    // --- helpers ---

    private static List<Acc> dedupBySignature(List<Acc> accs) {
        Map<String, Acc> bySig = new LinkedHashMap<>();
        for (Acc a : accs) {
            String sig = a.method() + " " + a.host() + " " + a.template();
            Acc existing = bySig.get(sig);
            if (existing == null) {
                bySig.put(sig, a);
            } else {
                existing.mergeFrom(a); // 승격으로 동일 템플릿 충돌 시 병합
            }
        }
        return new ArrayList<>(bySig.values());
    }

    private static String clusterKey(Acc a, String[] segs, int pos) {
        StringBuilder sb = new StringBuilder(a.method()).append(K).append(a.host())
                .append(K).append(segs.length);
        for (int i = 0; i < segs.length; i++) {
            sb.append(K).append(i == pos ? "*" : segs[i]);
        }
        return sb.toString();
    }

    private static boolean isStatic(String seg) {
        return !(seg.startsWith("{") && seg.endsWith("}"));
    }

    private static String withVar(String[] segs, int pos) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < segs.length; i++) {
            sb.append('/').append(i == pos ? "{var}" : segs[i]);
        }
        return sb.toString();
    }

    private static String[] segments(String template) {
        if (template == null) {
            return new String[0];
        }
        String body = template.startsWith("/") ? template.substring(1) : template;
        return body.isEmpty() ? new String[0] : body.split("/", -1);
    }
}
