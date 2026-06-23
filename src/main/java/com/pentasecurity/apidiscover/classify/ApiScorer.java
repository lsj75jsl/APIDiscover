// API 후보 점수화 — 가산식 신호 점수 + 프로파일 (doc/08, 실데이터 보정 반영)
package com.pentasecurity.apidiscover.classify;

import com.pentasecurity.apidiscover.match.ApiHintMatcher;
import com.pentasecurity.apidiscover.model.DiscoveredEndpoint;
import com.pentasecurity.apidiscover.model.EndpointKind;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/**
 * discovered endpoint 가 API 후보인지 0.0~1.0 점수로 평가한다(doc/08).
 * html penalty 는 쓰지 않는다($type=document 가 JSON API 응답에도 붙음 — 보정 §8).
 * 강한 가용 신호: host=api 서브도메인, CORS preflight(OPTIONS sibling).
 */
@Component
public class ApiScorer {

    public enum Profile { HIGH, MIDDLE, LOW }

    /** 게이트 판정 결과 (doc/09 §2.2). DROP 사유 분리 → non_api dropped 메트릭 버킷팅(후속). */
    public enum Gate { ADMIT, DROP_EXCLUDED, DROP_WEB_FORM, DROP_LOW_SCORE }

    /** 신호별 가중치 + 임계값 (doc/08 §4 보정값, doc/09 §6 pathHint 추가). */
    public record Weights(
            double hostApiSubdomain, double corsPreflight, double apiSegment, double graphqlSegment,
            double versionSegment, double pathIdSegment, double machineEndpoint, double writeMethod,
            double query, double nonBrowserUa, double staticAssetPenalty, double repeatBonus,
            int repeatMinCount, double pathHint, double threshold) {}

    private static final Weights MIDDLE = new Weights(
            0.40, 0.30, 0.55, 0.55, 0.26, 0.15, 0.20, 0.34, 0.12, 0.24, -0.60, 0.12, 3, 0.55, 0.70);
    private static final Weights HIGH = new Weights(
            0.35, 0.25, 0.50, 0.50, 0.20, 0.10, 0.12, 0.30, 0.06, 0.18, -0.70, 0.08, 5, 0.50, 0.85);
    private static final Weights LOW = new Weights(
            0.45, 0.35, 0.65, 0.65, 0.34, 0.22, 0.28, 0.42, 0.18, 0.30, -0.50, 0.18, 2, 0.65, 0.55);

    private static final Pattern API_HOST = Pattern.compile("^(api|apis|[a-z0-9-]*-api|api-[a-z0-9-]*)\\.");
    private static final Pattern VERSION_SEG = Pattern.compile("^v\\d+$");
    private static final Set<String> MACHINE = Set.of(
            "healthz", "health", "status", "metrics", "ping", "livez", "readyz", "actuator");
    private static final Set<String> WRITE = Set.of("POST", "PUT", "PATCH", "DELETE");
    private static final Set<String> ID_TOKENS = Set.of("{id}", "{uuid}", "{token}", "{date}", "{var}");

    /** override 가능한 13 Weights 필드명(단일 명명원, doc/10 §2). threshold/repeatMinCount 는 제외. */
    public static final Set<String> WEIGHT_KEYS = Set.of(
            "hostApiSubdomain", "corsPreflight", "apiSegment", "graphqlSegment", "versionSegment",
            "pathIdSegment", "machineEndpoint", "writeMethod", "query", "nonBrowserUa",
            "staticAssetPenalty", "repeatBonus", "pathHint");

    private final Weights w;

    public ApiScorer() {
        this(Profile.MIDDLE);
    }

    public ApiScorer(Profile profile) {
        this.w = presetWeights(profile);
    }

    /** 임의 가중치 주입(CUSTOM/override 용, doc/10 §4). resolver 가 effective Weights 로 빌드. */
    public ApiScorer(Weights weights) {
        this.w = weights;
    }

    public double threshold() {
        return w.threshold();
    }

    /** 이 scorer 의 effective 가중치 (effective 노출·테스트용, doc/10 §4). */
    public Weights weights() {
        return w;
    }

    /** preset 프로파일의 가중치(doc/10 §3). CUSTOM 베이스로는 MIDDLE 을 쓴다(resolver 가 매핑). */
    public static Weights presetWeights(Profile profile) {
        return switch (profile) {
            case HIGH -> HIGH;
            case MIDDLE -> MIDDLE;
            case LOW -> LOW;
        };
    }

    /**
     * base 가중치에 override 를 적용해 새 Weights 를 만든다(doc/10 §4).
     * 13 double 은 override map(key=Weights 필드명)에 있으면 교체, 없으면 base. threshold 는 thresholdOverride(nullable)
     * 가 있으면 그 값, 없으면 base. repeatMinCount 는 base 유지(v1 override 범위 밖, doc/10 §2).
     */
    public static Weights applyOverrides(Weights base, Map<String, Double> overrides, Double thresholdOverride) {
        validateWeightOverrides(overrides); // unknown 키·비유한 값 reject (doc/10 §4)
        validateThreshold(thresholdOverride);
        return new Weights(
                ov(overrides, "hostApiSubdomain", base.hostApiSubdomain()),
                ov(overrides, "corsPreflight", base.corsPreflight()),
                ov(overrides, "apiSegment", base.apiSegment()),
                ov(overrides, "graphqlSegment", base.graphqlSegment()),
                ov(overrides, "versionSegment", base.versionSegment()),
                ov(overrides, "pathIdSegment", base.pathIdSegment()),
                ov(overrides, "machineEndpoint", base.machineEndpoint()),
                ov(overrides, "writeMethod", base.writeMethod()),
                ov(overrides, "query", base.query()),
                ov(overrides, "nonBrowserUa", base.nonBrowserUa()),
                ov(overrides, "staticAssetPenalty", base.staticAssetPenalty()),
                ov(overrides, "repeatBonus", base.repeatBonus()),
                base.repeatMinCount(),
                ov(overrides, "pathHint", base.pathHint()),
                thresholdOverride != null ? thresholdOverride : base.threshold());
    }

    private static double ov(Map<String, Double> overrides, String key, double dflt) {
        Double v = (overrides == null) ? null : overrides.get(key);
        return v != null ? v : dflt;
    }

    /**
     * weight override map 검증(doc/10 §4): 키는 {@link #WEIGHT_KEYS} 의 13개 필드명만 허용(오타/미지원 키 reject),
     * 값은 유한(NaN/Infinity/null 금지). 위반 → IllegalArgumentException (조용한 무시 금지).
     */
    public static void validateWeightOverrides(Map<String, Double> overrides) {
        if (overrides == null) {
            return;
        }
        for (Map.Entry<String, Double> e : overrides.entrySet()) {
            if (!WEIGHT_KEYS.contains(e.getKey())) {
                throw new IllegalArgumentException(
                        "unknown weight override key: " + e.getKey() + " (allowed: " + WEIGHT_KEYS + ")");
            }
            Double v = e.getValue();
            if (v == null || !Double.isFinite(v)) {
                throw new IllegalArgumentException("non-finite weight override: " + e.getKey() + "=" + v);
            }
        }
    }

    /** threshold override 범위 검증(doc/10 §4): [0,1] + 유한. null 은 허용(미지정). 위반 → IllegalArgumentException. */
    public static void validateThreshold(Double threshold) {
        if (threshold == null) {
            return;
        }
        if (!Double.isFinite(threshold) || threshold < 0.0 || threshold > 1.0) {
            throw new IllegalArgumentException("threshold override out of [0,1]: " + threshold);
        }
    }

    /** 레거시 2-arg: 힌트 없음(NONE) → pathless strict, 현행 동작과 동일. */
    public boolean isApiCandidate(DiscoveredEndpoint d, boolean corsPreflight) {
        return evaluate(d, corsPreflight, ApiHintMatcher.NONE) == Gate.ADMIT;
    }

    public boolean isApiCandidate(DiscoveredEndpoint d, boolean corsPreflight, ApiHintMatcher hints) {
        return evaluate(d, corsPreflight, hints) == Gate.ADMIT;
    }

    /**
     * 게이트 판정 (doc/09 §2.2). 호출자는 spec 미매칭·non-OPTIONS endpoint 만 넘긴다.
     * 순서: exclude(최우선) → api 힌트(임계 우회 admit) → web-form 억제 → score 게이트.
     */
    public Gate evaluate(DiscoveredEndpoint d, boolean corsPreflight, ApiHintMatcher hints) {
        // 1. exclude — 게이트 내 최우선(힌트·점수 무시)
        if (hints.excluded(d.pathTemplate())) {
            return Gate.DROP_EXCLUDED;
        }
        // 2. api 힌트 — operator 선언적 단언 → 임계 우회 강제 admit (doc/09 §2.1)
        if (hints.apiHinted(d.pathTemplate())) {
            return Gate.ADMIT;
        }
        // 3. web-form 억제 (doc/09 §5): WEB_PAGE + write_method, 단 강신호(host_api·cors) 없을 때만
        if (!hints.includeWebForms()
                && d.endpointKind() == EndpointKind.WEB_PAGE
                && isWriteMethod(d)
                && !hasStrongApiSignal(d, corsPreflight)) {
            return Gate.DROP_WEB_FORM;
        }
        // 4. score 게이트
        return score(d, corsPreflight, hints) >= w.threshold() ? Gate.ADMIT : Gate.DROP_LOW_SCORE;
    }

    /** 레거시 2-arg: 힌트 없음(NONE) → pathless strict 점수, 현행 동작과 동일. */
    public double score(DiscoveredEndpoint d, boolean corsPreflight) {
        return score(d, corsPreflight, ApiHintMatcher.NONE);
    }

    /**
     * API 후보 점수 (clamp 0..1). corsPreflight = 같은 host+template 이 OPTIONS 로 관측됐는지.
     * explicit-hint 모드(hints 에 api 힌트 설정)면 내장 path-shape 신호를 비활성하고 pathHint 만 가산(doc/09 §2.3).
     */
    public double score(DiscoveredEndpoint d, boolean corsPreflight, ApiHintMatcher hints) {
        double s = 0.0;

        // host/cors 는 양 모드 공통
        if (hasApiHost(d)) {
            s += w.hostApiSubdomain();
        }
        if (corsPreflight) {
            s += w.corsPreflight();
        }

        if (hints.isExplicitHintMode()) {
            // explicit-hint 모드: 내장 path-shape 비활성, 힌트 매치 시 pathHint (doc/09 §2.3)
            if (hints.apiHinted(d.pathTemplate())) {
                s += w.pathHint();
            }
        } else {
            // pathless strict: 내장 path-shape 신호(현행)
            String[] segs = segments(d.pathTemplate());
            if (hasApiSegment(segs)) {
                s += w.apiSegment();
            }
            if (hasGraphqlSegment(segs)) {
                s += w.graphqlSegment();
            }
            if (anyMatch(segs, VERSION_SEG)) {
                s += w.versionSegment();
            }
            if (anyIn(segs, ID_TOKENS)) {
                s += w.pathIdSegment();
            }
            if (anyIn(segs, MACHINE)) {
                s += w.machineEndpoint();
            }
        }

        // method/query/ua/repeat/static 는 양 모드 공통
        if (isWriteMethod(d)) {
            s += w.writeMethod();
        }
        if (d.hadQuery()) {
            s += w.query();
        }
        if (d.nonBrowserUa()) {
            s += w.nonBrowserUa();
        }
        if (d.metrics() != null && d.metrics().hits() >= w.repeatMinCount()) {
            s += w.repeatBonus();
        }
        if (d.endpointKind() == EndpointKind.STATIC) {
            s += w.staticAssetPenalty();
        }
        return Math.max(0.0, Math.min(1.0, Math.round(s * 1000.0) / 1000.0));
    }

    private static boolean hasApiHost(DiscoveredEndpoint d) {
        return d.host() != null && API_HOST.matcher(d.host().toLowerCase(Locale.ROOT)).find();
    }

    private static boolean isWriteMethod(DiscoveredEndpoint d) {
        return d.method() != null && WRITE.contains(d.method().toUpperCase(Locale.ROOT));
    }

    /** web-form 억제 override 판단용 강신호: api 서브도메인 host 또는 CORS preflight (doc/09 §5). */
    private static boolean hasStrongApiSignal(DiscoveredEndpoint d, boolean corsPreflight) {
        return corsPreflight || hasApiHost(d);
    }

    private static String[] segments(String template) {
        if (template == null) {
            return new String[0];
        }
        String body = template.startsWith("/") ? template.substring(1) : template;
        return body.isEmpty() ? new String[0] : body.split("/", -1);
    }

    private static boolean hasApiSegment(String[] segs) {
        for (String s : segs) {
            String l = s.toLowerCase(Locale.ROOT);
            if (l.equals("api") || l.equals("apis") || l.endsWith("-api") || l.startsWith("api-")) {
                return true;
            }
        }
        return false;
    }

    private static boolean hasGraphqlSegment(String[] segs) {
        for (String s : segs) {
            String l = s.toLowerCase(Locale.ROOT);
            if (l.equals("graphql") || l.equals("rpc") || l.equals("jsonrpc")) {
                return true;
            }
        }
        return false;
    }

    private static boolean anyMatch(String[] segs, Pattern p) {
        for (String s : segs) {
            if (p.matcher(s).matches()) {
                return true;
            }
        }
        return false;
    }

    private static boolean anyIn(String[] segs, Set<String> set) {
        for (String s : segs) {
            if (set.contains(s) || set.contains(s.toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }
}
