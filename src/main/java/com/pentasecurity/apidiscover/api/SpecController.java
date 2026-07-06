// 도메인별 스펙 업로드/조회 API (doc/07 §3.1, doc/03 §7, doc/35 M6)
package com.pentasecurity.apidiscover.api;

import com.pentasecurity.apidiscover.api.dto.DomainDtos.SpecMetaView;
import com.pentasecurity.apidiscover.spec.SpecStore;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/v1/domains/{host}/spec")
public class SpecController {

    private final SpecStore specStore;

    public SpecController(SpecStore specStore) {
        this.specStore = specStore;
    }

    /** 고객→중앙→Worker 로 전달된 문서를 업로드. 업로드 시점에 파싱·검증·영속(doc/03 §7). filename=원본 파일명(옵션, doc/35 M2/M6·doc/36 specName 도출).
     * ★무효/미인식 문서·엔드포인트 0건은 {@link IllegalArgumentException} → 400(클라이언트 오류, 중앙에 동기 피드백). 종전 파싱 실패가 500 으로 새던 것 교정(D70). */
    @PutMapping
    public SpecMetaView upload(@PathVariable String host, @RequestBody byte[] content,
                               @RequestParam(required = false) String filename) {
        try {
            return SpecMetaView.of(specStore.upload(host, content, filename));
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "invalid spec document: " + e.getMessage(), e);
        }
    }

    /**
     * 도메인의 active 스펙 문서 목록 (doc/35 M6) — 문서별 filename·uploadedAt 포함. 무스펙=빈 배열(200, 404 아님).
     * ★projection 조회(대용량 text canonicalJson/warningsJson 미로드=LOB 폭증 방지, doc/28). specName/version 정렬은 repo.
     */
    @GetMapping
    public List<SpecMetaView> meta(@PathVariable String host) {
        return specStore.activeSpecMetas(host).stream().map(SpecMetaView::of).toList();
    }
}
