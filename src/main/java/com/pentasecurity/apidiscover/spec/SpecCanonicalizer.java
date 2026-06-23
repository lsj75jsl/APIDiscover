// Canonical 엔드포인트 정규화 — dedupe + deprecated OR + 안정 정렬 (doc/14 §0.1, doc/03 §6)
package com.pentasecurity.apidiscover.spec;

import com.pentasecurity.apidiscover.model.CanonicalEndpoint;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * SpecStore.upload 의 parse 직후 전 포맷 균일 적용: (method,host,template) 중복 제거,
 * deprecated 는 OR 결합, (host,template,method) 안정 정렬(ETag 결정성·3종 동일성 비교).
 */
final class SpecCanonicalizer {

    private SpecCanonicalizer() {
    }

    static List<CanonicalEndpoint> canonicalize(List<CanonicalEndpoint> input) {
        Map<String, CanonicalEndpoint> byKey = new LinkedHashMap<>();
        for (CanonicalEndpoint e : input) {
            String key = e.method() + '\u0001' + (e.host() == null ? "" : e.host()) + '\u0001' + e.pathTemplate();
            CanonicalEndpoint existing = byKey.get(key);
            if (existing == null) {
                byKey.put(key, e);
            } else if (e.deprecated() && !existing.deprecated()) {
                // deprecated OR: 한쪽이라도 deprecated 면 deprecated (나머지 필드는 기존 유지)
                byKey.put(key, new CanonicalEndpoint(existing.method(), existing.pathTemplate(),
                        existing.host(), true, existing.version(), existing.sourceRef()));
            }
        }
        List<CanonicalEndpoint> out = new ArrayList<>(byKey.values());
        out.sort(Comparator
                .comparing((CanonicalEndpoint e) -> e.host() == null ? "" : e.host())
                .thenComparing(CanonicalEndpoint::pathTemplate)
                .thenComparing(CanonicalEndpoint::method));
        return out;
    }
}
