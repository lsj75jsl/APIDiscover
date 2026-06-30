// 업로드 스펙 문서의 API 인벤토리 조회 — GET /api/v1/domains/{host}/spec/apis (doc/37 §4)
package com.pentasecurity.apidiscover.api;

import com.pentasecurity.apidiscover.domain.ApiStatus;
import com.pentasecurity.apidiscover.spec.ApiInventoryService;
import com.pentasecurity.apidiscover.util.DomainNames;
import java.util.List;
import java.util.Locale;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/v1/domains/{host}/spec/apis")
public class ApiInventoryController {

    private final ApiInventoryService inventory;

    public ApiInventoryController(ApiInventoryService inventory) {
        this.inventory = inventory;
    }

    /**
     * 인벤토리 목록(doc/37 §4·doc/38 §3.5·§4) — 문서별 등록 API 의 현재 상태·파라미터. DELETED 행도 노출(필터 없으면 전부).
     * 필터 {@code ?specName=}·{@code ?status=ACTIVE|DELETED}·{@code ?method=}·{@code ?breaking=true}(breaking UPDATED 만).
     * {@code ?view=merged}=도메인 전체 병합 표면(문서 구분 없이 method+path 병합·specName/breaking 미적용). 기본=per-document(무회귀).
     * host 정규화 null→400, 잘못된 status→400.
     */
    @GetMapping
    public List<?> apis(@PathVariable String host,
                        @RequestParam(required = false) String specName,
                        @RequestParam(required = false) String status,
                        @RequestParam(required = false) String method,
                        @RequestParam(required = false) String view,
                        @RequestParam(required = false) String breaking) {
        String normalized = DomainNames.normalize(host);
        if (normalized == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "host is required");
        }
        ApiStatus statusFilter = parseStatus(status);
        if ("merged".equalsIgnoreCase(view)) {
            return inventory.listMerged(normalized, statusFilter, method);
        }
        return inventory.list(normalized, specName, statusFilter, method, "true".equalsIgnoreCase(breaking));
    }

    private static ApiStatus parseStatus(String status) {
        if (status == null || status.isBlank()) {
            return null;
        }
        try {
            return ApiStatus.valueOf(status.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "status must be ACTIVE or DELETED");
        }
    }
}
