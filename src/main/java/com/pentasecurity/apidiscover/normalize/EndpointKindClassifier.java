// endpoint_kind 판정 — nginx $type 필드 기반 (doc/02 §5, 실로그 검증)
package com.pentasecurity.apidiscover.normalize;

import com.pentasecurity.apidiscover.model.EndpointKind;
import com.pentasecurity.apidiscover.model.RefererSignal;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * 실로그의 {@code $type} 필드(document/library 등)로 web_page vs static vs API 를 판정한다.
 * referer 재구성보다 직접적이고 신뢰도 높다(실데이터 검증). type 이 없으면 확장자로 보강.
 */
@Component
public class EndpointKindClassifier {

    private static final Set<String> API_TYPES = Set.of("xhr", "fetch", "json", "api", "ajax");
    private static final String[] STATIC_EXT = {
            ".js", ".css", ".png", ".jpg", ".jpeg", ".gif", ".svg", ".ico",
            ".woff", ".woff2", ".ttf", ".eot", ".map"};
    /** 정적 자식 ≥2 의 부모 = 페이지 확정 (referer 보조 임계, doc/20 §3). */
    private static final long MIN_CHILD_HITS = 2;
    private static final double REFERER_WEB_PAGE_CONFIDENCE = 0.6;

    public record KindResult(EndpointKind kind, double confidence) {}

    /** 하위호환 2-arg: referer 신호 없음(dormant) 위임 → 기존 동작 그대로 (doc/20 §3). */
    public KindResult classify(String pathTemplate, Map<String, Long> typeDist) {
        return classify(pathTemplate, typeDist, RefererSignal.dormant());
    }

    /**
     * $type+확장자 우선 판정 후, 결과가 UNKNOWN 이고 referer 신호가 active 이며 PAGE_URLS 에
     * 자식 ≥ {@value #MIN_CHILD_HITS} 면 WEB_PAGE 보조 양성(비대칭 — 부재 시 UNKNOWN 유지·무감점, doc/20 §3).
     *
     * @param pathTemplate 정규화된 경로(확장자 보조 판정용)
     * @param typeDist     해당 시그니처에서 관찰된 $type 값 분포(비어있을 수 있음)
     * @param signal       corpus referer 신호(dormant 면 보조 분기 skip)
     */
    public KindResult classify(String pathTemplate, Map<String, Long> typeDist, RefererSignal signal) {
        KindResult k = classifyByType(pathTemplate, typeDist);
        if (k.kind() == EndpointKind.UNKNOWN && signal.active()
                && signal.pageUrls().getOrDefault(pathTemplate, 0L) >= MIN_CHILD_HITS) {
            return new KindResult(EndpointKind.WEB_PAGE, REFERER_WEB_PAGE_CONFIDENCE);
        }
        return k;
    }

    /** $type+확장자 기반 핵심 판정(referer 무관) — 현행 로직. */
    private KindResult classifyByType(String pathTemplate, Map<String, Long> typeDist) {
        String dominant = null;
        long domCount = 0;
        long total = 0;
        for (Map.Entry<String, Long> e : typeDist.entrySet()) {
            total += e.getValue();
            if (e.getValue() > domCount) {
                domCount = e.getValue();
                dominant = e.getKey();
            }
        }
        double typeFraction = (total == 0) ? 0.0 : round3((double) domCount / total);

        // 1) 확장자가 정적 판정의 권위 신호 — $type 보다 우선.
        //    (실데이터 검증: .js/.css 가 $type=document 로 찍히는 경우가 있어 $type 단독은 부정확)
        if (isStaticPath(pathTemplate)) {
            return new KindResult(EndpointKind.STATIC, 1.0);
        }
        // 2) $type 보조 신호
        if ("library".equalsIgnoreCase(dominant)) {
            return new KindResult(EndpointKind.STATIC, typeFraction);
        }
        if ("document".equalsIgnoreCase(dominant)) {
            return new KindResult(EndpointKind.WEB_PAGE, typeFraction);
        }
        if (dominant != null && API_TYPES.contains(dominant.toLowerCase())) {
            return new KindResult(EndpointKind.API_CANDIDATE, typeFraction);
        }
        return new KindResult(EndpointKind.UNKNOWN, 0.0);
    }

    /** 정적 자원 경로(확장자 기반) 여부 — RefererSignalExtractor 가 static 요청 판정에 재사용(DRY, doc/20 §1). */
    public static boolean isStaticPath(String path) {
        if (path == null) {
            return false;
        }
        String p = path.toLowerCase();
        for (String ext : STATIC_EXT) {
            if (p.endsWith(ext)) {
                return true;
            }
        }
        return false;
    }

    private static double round3(double v) {
        return Math.round(v * 1000.0) / 1000.0;
    }
}
