// 문서 템플릿 매처 — concrete path 를 Canonical 템플릿에 매칭 (doc/04 §1, §2)
package com.pentasecurity.apidiscover.match;

import com.pentasecurity.apidiscover.model.CanonicalEndpoint;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * 문서 템플릿(S)을 정규식 + (method, host, 세그먼트수) 버킷으로 컴파일하고,
 * 관찰된 (method, host, path)를 매칭한다. 다중 매칭 시 specificity 우선순위(정적 > 변수, doc/04 §2.4).
 *
 * <p>경로는 대소문자 구분, host 는 소문자 비교. host=null 인 템플릿은 host-agnostic(모든 host 매칭).
 */
public class EndpointMatcher {

    private static final String HOST_AGNOSTIC = "*";

    /** 버킷 키 → 컴파일된 후보 목록. */
    private final Map<String, List<CompiledEndpoint>> index = new HashMap<>();

    public EndpointMatcher(List<CanonicalEndpoint> endpoints) {
        for (CanonicalEndpoint endpoint : endpoints) {
            CompiledEndpoint compiled = compile(endpoint);
            String hostKey = (endpoint.host() == null)
                    ? HOST_AGNOSTIC
                    : endpoint.host().toLowerCase(Locale.ROOT);
            String key = bucketKey(endpoint.method(), hostKey, compiled.segmentCount());
            index.computeIfAbsent(key, k -> new ArrayList<>()).add(compiled);
        }
    }

    /** 매칭되는 가장 구체적인 문서 엔드포인트(없으면 empty → Shadow 후보). */
    public Optional<CanonicalEndpoint> match(String method, String host, String path) {
        String m = method.toUpperCase(Locale.ROOT);
        String h = (host == null) ? null : host.toLowerCase(Locale.ROOT);
        String normPath = normalizePath(path);
        int segCount = segments(normPath).size();

        List<CompiledEndpoint> candidates = new ArrayList<>();
        addAll(candidates, index.get(bucketKey(m, HOST_AGNOSTIC, segCount))); // host-agnostic
        if (h != null) {
            addAll(candidates, index.get(bucketKey(m, h, segCount)));         // host-specific
        }

        CompiledEndpoint best = null;
        for (CompiledEndpoint c : candidates) {
            if (c.pattern().matcher(normPath).matches() && (best == null || moreSpecific(c, best))) {
                best = c;
            }
        }
        return Optional.ofNullable(best).map(CompiledEndpoint::endpoint);
    }

    // --- 컴파일 ---

    private record CompiledEndpoint(CanonicalEndpoint endpoint, Pattern pattern,
                                    int[] specificity, int segmentCount) {}

    private static CompiledEndpoint compile(CanonicalEndpoint endpoint) {
        List<String> segs = segments(stripTrailingSlash(endpoint.pathTemplate()));
        int[] specificity = new int[segs.size()];
        StringBuilder rx = new StringBuilder("^");
        if (segs.isEmpty()) {
            rx.append("/"); // 루트 "/"
        } else {
            for (int i = 0; i < segs.size(); i++) {
                String seg = segs.get(i);
                rx.append('/');
                if (isCatchAll(seg)) {
                    rx.append(".+");
                    specificity[i] = 0;
                } else if (isVariable(seg)) {
                    rx.append("[^/]+");
                    specificity[i] = 0;
                } else {
                    rx.append(Pattern.quote(seg));
                    specificity[i] = 1; // 정적 세그먼트
                }
            }
        }
        rx.append('$');
        return new CompiledEndpoint(endpoint, Pattern.compile(rx.toString()), specificity, segs.size());
    }

    // --- specificity 비교 (doc/04 §2.4) ---

    private static boolean moreSpecific(CompiledEndpoint a, CompiledEndpoint b) {
        int[] sa = a.specificity();
        int[] sb = b.specificity();
        int n = Math.min(sa.length, sb.length);
        for (int i = 0; i < n; i++) {
            if (sa[i] != sb[i]) {
                return sa[i] > sb[i]; // 앞쪽 세그먼트에서 정적(1)이 변수(0)를 이긴다
            }
        }
        return staticCount(sa) > staticCount(sb);
    }

    private static int staticCount(int[] specificity) {
        int sum = 0;
        for (int v : specificity) {
            sum += v;
        }
        return sum;
    }

    // --- path 유틸 ---

    private static List<String> segments(String pathNorm) {
        if (pathNorm == null || pathNorm.isEmpty() || "/".equals(pathNorm)) {
            return List.of();
        }
        String body = pathNorm.startsWith("/") ? pathNorm.substring(1) : pathNorm;
        return Arrays.asList(body.split("/", -1));
    }

    private static String normalizePath(String path) {
        if (path == null || path.isEmpty()) {
            return "/";
        }
        String p = path.startsWith("/") ? path : "/" + path;
        return stripTrailingSlash(p);
    }

    private static String stripTrailingSlash(String path) {
        if (path != null && path.length() > 1 && path.endsWith("/")) {
            return path.substring(0, path.length() - 1);
        }
        return path;
    }

    private static boolean isVariable(String seg) {
        return seg.startsWith("{") && seg.endsWith("}");
    }

    private static boolean isCatchAll(String seg) {
        return isVariable(seg) && seg.contains("+");
    }

    private static String bucketKey(String method, String hostKey, int segCount) {
        return method + '|' + hostKey + '|' + segCount;
    }

    private static void addAll(List<CompiledEndpoint> target, List<CompiledEndpoint> src) {
        if (src != null) {
            target.addAll(src);
        }
    }
}
