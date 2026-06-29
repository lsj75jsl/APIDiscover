// API 상태추적 diff — compute-on-read (현 active vs 직전 inactive canonical, ADDED/DELETED/UPDATED) (doc/36 M7.2)
package com.pentasecurity.apidiscover.spec;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pentasecurity.apidiscover.domain.SpecCanonicalProjection;
import com.pentasecurity.apidiscover.domain.SpecRecordRepository;
import com.pentasecurity.apidiscover.model.CanonicalEndpoint;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import org.springframework.stereotype.Service;

/**
 * 같은 specName 의 현 active 버전 vs 직전(active=false 중 specVersion 최대) canonical 을 ★projection 으로 읽어(rawDoc oid 미접근, D51)
 * compute-on-read diff 한다(저장 불요). 동일성=method+path_template. ADDED/DELETED 완전, UPDATED=deprecated/version(M7a 한계, M7b 후속).
 * 직전 부재(최초 업로드)=전부 ADDED. 신규 테이블·스키마 0.
 */
@Service
public class SpecDiffService {

    private static final TypeReference<List<CanonicalEndpoint>> CANONICAL_LIST = new TypeReference<>() {};
    static final String ADDED = "ADDED";
    static final String DELETED = "DELETED";
    static final String UPDATED = "UPDATED";

    private final SpecRecordRepository repo;
    private final ObjectMapper objectMapper;

    public SpecDiffService(SpecRecordRepository repo, ObjectMapper objectMapper) {
        this.repo = repo;
        this.objectMapper = objectMapper;
    }

    /**
     * host 의 spec 변경 상태. specNameFilter=null → active 전 specName, 아니면 그 1개. from/to(specVersion) 동반 시 명시 버전 쌍 비교.
     * statusFilter=null/빈 → 전 status, 아니면 해당만. updatedScope 는 항상 deprecated_version_only(M7a).
     */
    public SpecChanges changes(String host, String specNameFilter, Long from, Long to, Set<String> statusFilter) {
        List<String> specNames = (specNameFilter != null && !specNameFilter.isBlank())
                ? List.of(normalizeName(specNameFilter))
                : activeSpecNames(host);
        List<SpecChanges.DocChanges> documents = new ArrayList<>();
        for (String specName : specNames) {
            SpecChanges.DocChanges doc = diffDoc(host, specName, from, to, statusFilter);
            if (doc != null) {
                documents.add(doc);
            }
        }
        return new SpecChanges(host, documents, SpecChanges.SCOPE_DEPRECATED_VERSION_ONLY);
    }

    /** active specName 목록(정규화·중복제거). 레거시 null specName="default". */
    private List<String> activeSpecNames(String host) {
        return repo.findActiveSpecMetas(host).stream()
                .map(m -> m.specName() == null ? "default" : m.specName())
                .distinct()
                .toList();
    }

    /** 한 (host, specName) 전 버전 canonical projection(rawDoc oid 미접근, specVersion desc) — diff 의 oid-안전 단일 로드 지점. */
    private List<SpecCanonicalProjection> loadVersions(String host, String specName) {
        return repo.findCanonicalVersions(host, specName);
    }

    private SpecChanges.DocChanges diffDoc(String host, String specName, Long from, Long to, Set<String> statusFilter) {
        List<SpecCanonicalProjection> versions = loadVersions(host, specName); // specVersion desc, projection
        if (versions.isEmpty()) {
            return null;
        }
        SpecCanonicalProjection current;
        SpecCanonicalProjection previous;
        if (from != null && to != null) {
            current = versions.stream().filter(v -> v.specVersion() == to).findFirst().orElse(null);
            previous = versions.stream().filter(v -> v.specVersion() == from).findFirst().orElse(null);
            if (current == null) {
                return null; // 지정 to 버전 부재
            }
        } else {
            // 현 active(보통 최대 버전) vs 직전(active=false 중 최대 버전 = desc 첫 inactive)
            current = versions.stream().filter(SpecCanonicalProjection::active).findFirst().orElse(versions.get(0));
            previous = versions.stream().filter(v -> !v.active()).findFirst().orElse(null);
        }
        Map<String, CanonicalEndpoint> cur = byKey(read(current.canonicalJson()));
        Map<String, CanonicalEndpoint> prev = (previous == null) ? Map.of() : byKey(read(previous.canonicalJson()));
        List<SpecChanges.ApiChange> changes = computeChanges(prev, cur);
        if (statusFilter != null && !statusFilter.isEmpty()) {
            changes = changes.stream().filter(c -> statusFilter.contains(c.status())).toList();
        }
        return new SpecChanges.DocChanges(specName, current.filename(), current.specVersion(),
                previous != null ? previous.specVersion() : null,
                current.uploadedAt(), previous != null ? previous.uploadedAt() : null, changes);
    }

    /** 직전(prev) vs 현(cur) endpoint 맵 diff. key(method+path) 정렬로 결정적. UNCHANGED 미보고. */
    private static List<SpecChanges.ApiChange> computeChanges(Map<String, CanonicalEndpoint> prev,
                                                              Map<String, CanonicalEndpoint> cur) {
        TreeMap<String, Boolean> keys = new TreeMap<>(); // 정렬된 union (결정적 순서)
        prev.keySet().forEach(k -> keys.put(k, true));
        cur.keySet().forEach(k -> keys.put(k, true));
        List<SpecChanges.ApiChange> out = new ArrayList<>();
        for (String k : keys.keySet()) {
            CanonicalEndpoint c = cur.get(k);
            CanonicalEndpoint p = prev.get(k);
            if (c != null && p == null) {
                out.add(new SpecChanges.ApiChange(c.method(), c.pathTemplate(), ADDED, null, null));
            } else if (c == null) {
                out.add(new SpecChanges.ApiChange(p.method(), p.pathTemplate(), DELETED, null, null));
            } else {
                List<String> changed = new ArrayList<>();
                Map<String, SpecChanges.FromTo> detail = new LinkedHashMap<>();
                if (p.deprecated() != c.deprecated()) {
                    changed.add("deprecated");
                    detail.put("deprecated", new SpecChanges.FromTo(p.deprecated(), c.deprecated()));
                }
                if (!Objects.equals(p.version(), c.version())) {
                    changed.add("version");
                    detail.put("version", new SpecChanges.FromTo(p.version(), c.version()));
                }
                if (!changed.isEmpty()) {
                    out.add(new SpecChanges.ApiChange(c.method(), c.pathTemplate(), UPDATED, changed, detail));
                }
                // changed 비면 UNCHANGED — 미보고(param-level 변경은 (a) 스코프 밖, updatedScope 로 노출)
            }
        }
        return out;
    }

    /** method(대문자)+path_template 동일성 키. 같은 키 중복 시 첫 관측 유지. */
    private static Map<String, CanonicalEndpoint> byKey(List<CanonicalEndpoint> endpoints) {
        Map<String, CanonicalEndpoint> map = new LinkedHashMap<>();
        for (CanonicalEndpoint e : endpoints) {
            map.putIfAbsent(e.method().toUpperCase(Locale.ROOT) + " " + e.pathTemplate(), e);
        }
        return map;
    }

    private List<CanonicalEndpoint> read(String canonicalJson) {
        if (canonicalJson == null || canonicalJson.isBlank()) {
            return List.of();
        }
        try {
            return objectMapper.readValue(canonicalJson, CANONICAL_LIST);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("failed to deserialize canonicalJson for spec diff", e);
        }
    }

    private static String normalizeName(String specName) {
        return (specName == null || specName.isBlank()) ? "default" : specName.trim().toLowerCase(Locale.ROOT);
    }
}
