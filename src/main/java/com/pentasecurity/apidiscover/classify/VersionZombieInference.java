// 버전 기반 Zombie 추정 — 신버전 active 인데 구버전도 트래픽 지속 (doc/16 §1, doc/04 §5)
package com.pentasecurity.apidiscover.classify;

import com.pentasecurity.apidiscover.model.CanonicalEndpoint;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * active(observed &amp; 비-deprecated) endpoint 들을 resourceKey(버전 위치 {V} 치환·나머지 동일)로 페어링.
 * 그룹 내 최대 버전 Vmax 보다 낮은 버전의 active endpoint → 추정 Zombie(원래 Active 를 재분류, doc/04 §5).
 */
final class VersionZombieInference {

    private VersionZombieInference() {
    }

    /** 첫 ^v(\d+)$ 세그먼트(대소문자 무시)를 버전으로 인식. */
    private static final Pattern VERSION = Pattern.compile("^v(\\d+)$", Pattern.CASE_INSENSITIVE);

    /** active endpoint 중 추정 Zombie(같은 resourceKey 그룹의 Vmax 미만) 집합. */
    static Set<CanonicalEndpoint> estimate(List<CanonicalEndpoint> activeEndpoints) {
        Map<CanonicalEndpoint, Parsed> parsed = new LinkedHashMap<>();
        Map<String, Integer> vmaxByResource = new HashMap<>();
        for (CanonicalEndpoint e : activeEndpoints) {
            Parsed p = parse(e);
            if (p == null) {
                continue; // 비버전 → 페어링 대상 외
            }
            parsed.put(e, p);
            vmaxByResource.merge(p.resourceKey(), p.version(), Math::max);
        }
        Set<CanonicalEndpoint> estimated = new HashSet<>();
        for (Map.Entry<CanonicalEndpoint, Parsed> en : parsed.entrySet()) {
            Parsed p = en.getValue();
            if (p.version() < vmaxByResource.get(p.resourceKey())) {
                estimated.add(en.getKey()); // 신버전 존재(Vmax) + 자신은 구버전 active
            }
        }
        return estimated;
    }

    /** (버전 int, resourceKey=method|host|버전위치 {V} 치환 template). 버전 없으면 null. */
    private record Parsed(int version, String resourceKey) {}

    private static Parsed parse(CanonicalEndpoint e) {
        String[] segs = segments(e.pathTemplate());
        for (int i = 0; i < segs.length; i++) {
            Matcher m = VERSION.matcher(segs[i]);
            if (m.matches()) {
                int version;
                try {
                    version = Integer.parseInt(m.group(1));
                } catch (NumberFormatException overflow) {
                    // 병적 초대형 버전 숫자 → 비버전 취급(페어링 제외)하여 스캔을 깨지 않는 안전 폴백
                    continue;
                }
                String[] copy = segs.clone();
                copy[i] = "{V}"; // 버전 위치 치환(위치 인식 — 다른 위치 버전은 페어 안 됨)
                String template = "/" + String.join("/", copy);
                String host = (e.host() == null) ? "*" : e.host().toLowerCase(Locale.ROOT);
                String resourceKey = e.method().toUpperCase(Locale.ROOT) + "|" + host + "|" + template;
                return new Parsed(version, resourceKey);
            }
        }
        return null;
    }

    private static String[] segments(String template) {
        if (template == null) {
            return new String[0];
        }
        String body = template.startsWith("/") ? template.substring(1) : template;
        return body.isEmpty() ? new String[0] : body.split("/", -1);
    }
}
