// 도메인별 스펙 업로드/조회 + API 상태추적 API (doc/07 §3.1, doc/03 §7, doc/36 M7a)
package com.pentasecurity.apidiscover.api;

import com.pentasecurity.apidiscover.api.dto.DomainDtos.SpecMetaView;
import com.pentasecurity.apidiscover.spec.SpecChanges;
import com.pentasecurity.apidiscover.spec.SpecDiffService;
import com.pentasecurity.apidiscover.spec.SpecStore;
import com.pentasecurity.apidiscover.util.DomainNames;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/v1/domains/{host}/spec")
public class SpecController {

    private final SpecStore specStore;
    private final SpecDiffService specDiffService;

    public SpecController(SpecStore specStore, SpecDiffService specDiffService) {
        this.specStore = specStore;
        this.specDiffService = specDiffService;
    }

    /** 고객→중앙→Worker 로 전달된 문서를 업로드. 업로드 시점에 파싱·검증·영속(doc/03 §7). filename=원본 파일명(옵션, doc/35 M2/M6·doc/36 specName 도출). */
    @PutMapping
    public SpecMetaView upload(@PathVariable String host, @RequestBody byte[] content,
                               @RequestParam(required = false) String filename) {
        return SpecMetaView.of(specStore.upload(host, content, filename));
    }

    /**
     * 도메인의 active 스펙 문서 목록 (doc/35 M6) — 문서별 filename·uploadedAt 포함. 무스펙=빈 배열(200, 404 아님).
     * ★projection 조회(rawDoc oid 미접근, doc/28) — 트랜잭션 밖(auto-commit) 에서도 LOB materialize 500 없이 안전. specName/version 정렬은 repo.
     */
    @GetMapping
    public List<SpecMetaView> meta(@PathVariable String host) {
        return specStore.activeSpecMetas(host).stream().map(SpecMetaView::of).toList();
    }

    /**
     * API 상태추적 (doc/36 M7a) — specName 별 현 active vs 직전 버전 diff(ADDED/DELETED/UPDATED). M6 목록과 분리.
     * 기본=active 전 specName. {@code ?specName=X} 한정 / {@code &from=&to=}(specVersion) 명시 버전 쌍 / {@code ?status=ADDED,DELETED} 필터.
     * ★canonicalJson projection 으로만 읽어 비-tx oid materialize 회피(D51). {@code updatedScope} 로 UPDATED 한계(deprecated/version) 자기노출.
     */
    @GetMapping("/changes")
    public SpecChanges changes(@PathVariable String host,
                               @RequestParam(required = false) String specName,
                               @RequestParam(required = false) Long from,
                               @RequestParam(required = false) Long to,
                               @RequestParam(required = false) String status) {
        String normalized = DomainNames.normalize(host);
        if (normalized == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "host is required");
        }
        Set<String> statusFilter = (status == null || status.isBlank()) ? Set.of()
                : Arrays.stream(status.split(",")).map(s -> s.trim().toUpperCase(Locale.ROOT))
                        .filter(s -> !s.isEmpty()).collect(Collectors.toSet());
        return specDiffService.changes(normalized, specName, from, to, statusFilter);
    }
}
