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
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
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
        // ★old(row.paramsJson) vs new(p.params) 를 setParamsJson 전에 계산 — 덮어쓰면 사후 재계산 불가(doc/38 §3.4).
        ParamDiff.Result d = ParamDiff.diff(readParams(row.getParamsJson()), p.params());
        if (row.getStatus() == ApiStatus.DELETED) {
            // 재등장 → 추가로 취급
            row.setLastChange(ApiChangeKind.ADDED);
            row.setParamsJson(writeParams(p.params()));
            row.setDeprecated(p.deprecated());
            row.setVersion(p.version());
            row.setChangedAt(now);
            clearChangeDetail(row);
        } else if (!d.change().isEmpty() || row.isDeprecated() != p.deprecated()
                || !Objects.equals(row.getVersion(), p.version())) {
            row.setLastChange(ApiChangeKind.UPDATED);
            row.setParamsJson(writeParams(p.params()));
            row.setDeprecated(p.deprecated());
            row.setVersion(p.version());
            row.setChangedAt(now);
            row.setLastChangeBreaking(d.breaking());
            row.setLastChangeDetailJson(writeChange(d.change()));
        } else {
            row.setLastChange(ApiChangeKind.UNCHANGED);
            clearChangeDetail(row);
        }
        row.setStatus(ApiStatus.ACTIVE);
        row.setSourceSpecVersion(newSpecVersion);
        row.setLastDocumentedAt(now);
    }

    /** UPDATED 외 lastChange 는 breaking/detail 미보유 — 직전 UPDATED 잔존값 정리. */
    private static void clearChangeDetail(DocumentedApiRecord row) {
        row.setLastChangeBreaking(false);
        row.setLastChangeDetailJson(null);
    }

    /**
     * /apis 목록 (doc/37 §4·doc/38 §3.5). 필터=specName(정규화 일치)·status·method(대문자)·breakingOnly(breaking UPDATED 만). 정렬은 repo.
     */
    public List<DocumentedApiView> list(String host, String specName, ApiStatus status,
                                        String method, boolean breakingOnly) {
        String nameFilter = (specName == null || specName.isBlank())
                ? null : specName.trim().toLowerCase(Locale.ROOT);
        String methodFilter = (method == null || method.isBlank())
                ? null : method.trim().toUpperCase(Locale.ROOT);
        return repo.findByHostOrdered(host).stream()
                .filter(r -> nameFilter == null || nameFilter.equals(r.getSpecName()))
                .filter(r -> status == null || status == r.getStatus())
                .filter(r -> methodFilter == null || methodFilter.equals(r.getMethod()))
                .filter(r -> !breakingOnly || r.isLastChangeBreaking())
                .map(this::toView)
                .toList();
    }

    /**
     * 도메인-merged 뷰 (doc/38 §4) — 문서 구분 없이 (method, path_template) 병합. compute-on-read(스키마 0).
     * status=하나라도 ACTIVE→ACTIVE·전부 DELETED→DELETED, deprecated OR, version/params=최신 sourceSpecVersion 기준.
     * 필터 status(병합 후)·method. 정렬 path asc·method asc(결정적).
     */
    public List<MergedApiView> listMerged(String host, ApiStatus status, String method) {
        String methodFilter = (method == null || method.isBlank())
                ? null : method.trim().toUpperCase(Locale.ROOT);
        Map<String, List<DocumentedApiRecord>> groups = new LinkedHashMap<>();
        for (DocumentedApiRecord r : repo.findByHostOrdered(host)) {
            groups.computeIfAbsent(key(r.getMethod(), r.getPathTemplate()), k -> new ArrayList<>()).add(r);
        }
        List<MergedApiView> out = new ArrayList<>();
        for (List<DocumentedApiRecord> group : groups.values()) {
            MergedApiView m = merge(group);
            if (status != null && status != m.status()) {
                continue;
            }
            if (methodFilter != null && !methodFilter.equals(m.method())) {
                continue;
            }
            out.add(m);
        }
        out.sort(Comparator.comparing(MergedApiView::pathTemplate).thenComparing(MergedApiView::method));
        return out;
    }

    /** (method, path_template) 그룹 병합 (doc/38 §4.2·SpecCanonicalizer.merge 정합). */
    private MergedApiView merge(List<DocumentedApiRecord> group) {
        List<DocumentedApiRecord> active = group.stream()
                .filter(r -> r.getStatus() == ApiStatus.ACTIVE).toList();
        ApiStatus status = active.isEmpty() ? ApiStatus.DELETED : ApiStatus.ACTIVE;
        // ★대표값(version/params/deprecated/sourceSpecVersion)은 ACTIVE pool 기준(전부 DELETED 면 group 폴백) — ACTIVE 계약 대표여야
        //   merge 동형(doc/38 §4.2). DELETED 행(나중 업로드서 삭제·sourceSpecVersion 더 큼)이 version 을 발산시키지 않도록 latestPool 로 통일.
        List<DocumentedApiRecord> pool = active.isEmpty() ? group : active;
        DocumentedApiRecord latestPool = maxBySourceVersion(pool);
        boolean deprecated = pool.stream().anyMatch(DocumentedApiRecord::isDeprecated);
        List<String> specNames = group.stream()
                .map(DocumentedApiRecord::getSpecName).distinct().sorted().toList();
        return new MergedApiView(latestPool.getMethod(), latestPool.getPathTemplate(), status, deprecated,
                latestPool.getVersion(), readParams(latestPool.getParamsJson()), latestPool.getSourceSpecVersion(), specNames);
    }

    private static DocumentedApiRecord maxBySourceVersion(List<DocumentedApiRecord> rows) {
        return rows.stream().max(Comparator.comparingLong(DocumentedApiRecord::getSourceSpecVersion)).orElseThrow();
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
                r.getFirstDocumentedAt(), r.getLastDocumentedAt(), r.getChangedAt(),
                r.isLastChangeBreaking(), readChange(r.getLastChangeDetailJson()));
    }

    private String writeChange(ParamChange change) {
        try {
            return objectMapper.writeValueAsString(change);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("failed to serialize param change", e);
        }
    }

    private ParamChange readChange(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return objectMapper.readValue(json, ParamChange.class);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("failed to deserialize param change", e);
        }
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
