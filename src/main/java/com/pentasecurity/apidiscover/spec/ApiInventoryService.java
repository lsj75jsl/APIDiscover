// 영속 API 인벤토리 — 업로드 reconcile + /apis 조회 + DELETED 키(Zombie 결합) (doc/37 §3·§4·§6)
package com.pentasecurity.apidiscover.spec;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pentasecurity.apidiscover.domain.ApiChangeKind;
import com.pentasecurity.apidiscover.domain.ApiStatus;
import com.pentasecurity.apidiscover.domain.DocumentedApiRecord;
import com.pentasecurity.apidiscover.domain.DocumentedApiRepository;
import com.pentasecurity.apidiscover.model.CanonicalEndpoint;
import com.pentasecurity.apidiscover.model.SpecParam;
import java.time.Instant;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.springframework.stereotype.Service;

@Service
public class ApiInventoryService {

    private static final TypeReference<List<SpecParam>> PARAM_LIST = new TypeReference<>() {};

    private final DocumentedApiRepository repo;
    private final ObjectMapper objectMapper;

    public ApiInventoryService(DocumentedApiRepository repo, ObjectMapper objectMapper) {
        this.repo = repo;
        this.objectMapper = objectMapper;
    }

    /**
     * 업로드 파싱 결과로 specName 단위 인벤토리 reconcile (doc/37 §3). ★SpecStore.upload 의 @Transactional 경계 안에서 호출
     * (이 메서드는 별도 빈이라 프록시 정상 적용·기존 tx 전파 REQUIRED, self-invocation 무관, D51). SpecRecord 엔티티 미접근(파싱 결과·documented_api 만).
     * <ul>
     *   <li>부재 → INSERT(ACTIVE/ADDED)</li>
     *   <li>DELETED 였던 키 재등장 → ACTIVE/ADDED(resurrect)</li>
     *   <li>param/deprecated/version 변경 → UPDATED</li>
     *   <li>동일 → UNCHANGED</li>
     *   <li>인벤토리 ACTIVE 인데 파싱에 없음 → DELETED (★그 specName 한정 = 삭제 격리, doc/37 §3)</li>
     * </ul>
     */
    public void reconcile(String host, String specName, List<CanonicalEndpoint> parsed,
                          long newSpecVersion, Instant now) {
        List<DocumentedApiRecord> existing = repo.findByHostAndSpecName(host, specName);
        Map<String, DocumentedApiRecord> byKey = new HashMap<>();
        for (DocumentedApiRecord r : existing) {
            byKey.put(key(r.getMethod(), r.getPathTemplate()), r);
        }
        Set<String> parsedKeys = new HashSet<>();
        for (CanonicalEndpoint p : parsed) {
            String k = key(p.method(), p.pathTemplate());
            parsedKeys.add(k);
            DocumentedApiRecord row = byKey.get(k);
            if (row == null) {
                repo.save(insert(host, specName, p, newSpecVersion, now));
            } else {
                applyUpdate(row, p, newSpecVersion, now);
                repo.save(row);
            }
        }
        // 삭제: 그 specName 의 ACTIVE 인데 파싱결과에 없음 → DELETED (다른 specName 미접근=union 보존)
        for (DocumentedApiRecord row : existing) {
            if (row.getStatus() == ApiStatus.ACTIVE
                    && !parsedKeys.contains(key(row.getMethod(), row.getPathTemplate()))) {
                row.setStatus(ApiStatus.DELETED);
                row.setChangedAt(now);
                repo.save(row);
            }
        }
    }

    private DocumentedApiRecord insert(String host, String specName, CanonicalEndpoint p,
                                       long newSpecVersion, Instant now) {
        DocumentedApiRecord rec = new DocumentedApiRecord();
        rec.setHost(host);
        rec.setSpecName(specName);
        rec.setMethod(p.method());
        rec.setPathTemplate(p.pathTemplate());
        rec.setParamsJson(writeParams(p.params()));
        rec.setStatus(ApiStatus.ACTIVE);
        rec.setLastChange(ApiChangeKind.ADDED);
        rec.setDeprecated(p.deprecated());
        rec.setVersion(p.version());
        rec.setSourceSpecVersion(newSpecVersion);
        rec.setFirstDocumentedAt(now);
        rec.setLastDocumentedAt(now);
        rec.setChangedAt(now);
        return rec;
    }

    private void applyUpdate(DocumentedApiRecord row, CanonicalEndpoint p, long newSpecVersion, Instant now) {
        if (row.getStatus() == ApiStatus.DELETED) {
            // 재등장 → 추가로 취급
            row.setLastChange(ApiChangeKind.ADDED);
            row.setParamsJson(writeParams(p.params()));
            row.setDeprecated(p.deprecated());
            row.setVersion(p.version());
            row.setChangedAt(now);
        } else if (paramsChanged(row, p) || row.isDeprecated() != p.deprecated()
                || !Objects.equals(row.getVersion(), p.version())) {
            row.setLastChange(ApiChangeKind.UPDATED);
            row.setParamsJson(writeParams(p.params()));
            row.setDeprecated(p.deprecated());
            row.setVersion(p.version());
            row.setChangedAt(now);
        } else {
            row.setLastChange(ApiChangeKind.UNCHANGED);
        }
        row.setStatus(ApiStatus.ACTIVE);
        row.setSourceSpecVersion(newSpecVersion);
        row.setLastDocumentedAt(now);
    }

    /** 파라미터 변경 = 두 집합 차이(추가·제거·required flip·type 변경). SpecParam record 값동등 → Set 비교가 전부 포착(doc/37 §2). */
    private boolean paramsChanged(DocumentedApiRecord row, CanonicalEndpoint p) {
        Set<SpecParam> old = new HashSet<>(readParams(row.getParamsJson()));
        Set<SpecParam> cur = new HashSet<>(p.params());
        return !old.equals(cur);
    }

    /** /apis 목록 (doc/37 §4). 필터=specName(정규화 일치)·status·method(대문자). 정렬은 repo. */
    public List<DocumentedApiView> list(String host, String specName, ApiStatus status, String method) {
        String nameFilter = (specName == null || specName.isBlank())
                ? null : specName.trim().toLowerCase(Locale.ROOT);
        String methodFilter = (method == null || method.isBlank())
                ? null : method.trim().toUpperCase(Locale.ROOT);
        return repo.findByHostOrdered(host).stream()
                .filter(r -> nameFilter == null || nameFilter.equals(r.getSpecName()))
                .filter(r -> status == null || status == r.getStatus())
                .filter(r -> methodFilter == null || methodFilter.equals(r.getMethod()))
                .map(this::toView)
                .toList();
    }

    /** Zombie 결합용 — host 의 DELETED 키집합("METHOD path_template", doc/37 §6.5). 비면 분류 무영향(무회귀). */
    public Set<String> deletedKeys(String host) {
        Set<String> out = new HashSet<>();
        for (Object[] r : repo.findKeysByHostAndStatus(host, ApiStatus.DELETED)) {
            out.add(key((String) r[0], (String) r[1]));
        }
        return out;
    }

    private DocumentedApiView toView(DocumentedApiRecord r) {
        return new DocumentedApiView(r.getSpecName(), r.getMethod(), r.getPathTemplate(),
                r.getStatus(), r.getLastChange(), r.isDeprecated(), r.getVersion(),
                readParams(r.getParamsJson()), r.getSourceSpecVersion(),
                r.getFirstDocumentedAt(), r.getLastDocumentedAt(), r.getChangedAt());
    }

    /** 매칭/dedupe 키 = METHOD(대문자) + path_template (doc/37 §2 매칭키 불변). */
    private static String key(String method, String pathTemplate) {
        return method.toUpperCase(Locale.ROOT) + " " + pathTemplate;
    }

    private String writeParams(List<SpecParam> params) {
        try {
            return objectMapper.writeValueAsString(params == null ? List.of() : params);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("failed to serialize spec params", e);
        }
    }

    private List<SpecParam> readParams(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            return objectMapper.readValue(json, PARAM_LIST);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("failed to deserialize spec params", e);
        }
    }
}
