// Canonical 엔드포인트 정규화 — dedupe + deprecated OR + 안정 정렬 (doc/14 §0.1, doc/03 §6)
package com.pentasecurity.apidiscover.spec;

import com.pentasecurity.apidiscover.model.CanonicalEndpoint;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * SpecStore.upload 의 parse 직후 전 포맷 균일 적용: (method,host,template) 중복 제거,
 * deprecated 는 OR 결합, (host,template,method) 안정 정렬(ETag 결정성·3종 동일성 비교).
 * 멀티 active 문서 병합({@link #merge})도 같은 dedupe/정렬 + 비-deprecated latest-wins(doc/26 §5).
 */
final class SpecCanonicalizer {

    private SpecCanonicalizer() {
    }

    /** (specVersion, 문서 canonical) — merge 입력. specVersion 으로 비-deprecated 충돌 latest-wins. */
    record VersionedCanonical(long specVersion, List<CanonicalEndpoint> endpoints) {}

    static List<CanonicalEndpoint> canonicalize(List<CanonicalEndpoint> input) {
        Map<String, CanonicalEndpoint> byKey = new LinkedHashMap<>();
        for (CanonicalEndpoint e : input) {
            String key = dedupeKey(e);
            CanonicalEndpoint existing = byKey.get(key);
            if (existing == null) {
                byKey.put(key, e);
            } else if (e.deprecated() && !existing.deprecated()) {
                // deprecated OR: 한쪽이라도 deprecated 면 deprecated (나머지 필드는 기존 유지)
                byKey.put(key, withDeprecated(existing, true));
            }
        }
        return sorted(byKey.values());
    }

    /**
     * 멀티 active 문서 병합(doc/26 §5): (method,host,template) dedupe + deprecated OR +
     * 비-deprecated 필드(version/sourceRef)는 latest-upload-wins(최신 specVersion, tie=sourceRef 큰 값).
     * group+max+OR 는 교환법칙 성립 → 업로드/문서 순서 무관 동일 merged SET. 단일 문서면 canonicalize 동치(무회귀).
     */
    static List<CanonicalEndpoint> merge(List<VersionedCanonical> docs) {
        Map<String, Winner> byKey = new LinkedHashMap<>();
        for (VersionedCanonical doc : docs) {
            for (CanonicalEndpoint e : doc.endpoints()) {
                Winner w = byKey.computeIfAbsent(dedupeKey(e), k -> new Winner());
                w.deprecated |= e.deprecated();
                boolean newer = w.endpoint == null
                        || doc.specVersion() > w.specVersion
                        || (doc.specVersion() == w.specVersion
                                && sourceRef(e).compareTo(sourceRef(w.endpoint)) > 0);
                if (newer) {
                    w.endpoint = e;
                    w.specVersion = doc.specVersion();
                }
            }
        }
        List<CanonicalEndpoint> out = new ArrayList<>(byKey.size());
        for (Winner w : byKey.values()) {
            out.add(withDeprecated(w.endpoint, w.deprecated));
        }
        return sorted(out);
    }

    private static final class Winner {
        CanonicalEndpoint endpoint;
        long specVersion;
        boolean deprecated;
    }

    private static String dedupeKey(CanonicalEndpoint e) {
        return e.method() + '' + (e.host() == null ? "" : e.host()) + '' + e.pathTemplate();
    }

    private static String sourceRef(CanonicalEndpoint e) {
        return e.sourceRef() == null ? "" : e.sourceRef();
    }

    /** deprecated 만 교체, 나머지 필드(params 포함) 유지. */
    private static CanonicalEndpoint withDeprecated(CanonicalEndpoint e, boolean deprecated) {
        if (e.deprecated() == deprecated) {
            return e;
        }
        return new CanonicalEndpoint(e.method(), e.pathTemplate(), e.host(), deprecated,
                e.version(), e.sourceRef(), e.params());
    }

    private static List<CanonicalEndpoint> sorted(Collection<CanonicalEndpoint> in) {
        List<CanonicalEndpoint> out = new ArrayList<>(in);
        out.sort(Comparator
                .comparing((CanonicalEndpoint e) -> e.host() == null ? "" : e.host())
                .thenComparing(CanonicalEndpoint::pathTemplate)
                .thenComparing(CanonicalEndpoint::method));
        return out;
    }
}
