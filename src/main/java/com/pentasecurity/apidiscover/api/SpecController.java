// 도메인별 스펙 업로드/조회 API (doc/07 §3.1, doc/03 §7)
package com.pentasecurity.apidiscover.api;

import com.pentasecurity.apidiscover.api.dto.DomainDtos.SpecMetaView;
import com.pentasecurity.apidiscover.domain.SpecRecord;
import com.pentasecurity.apidiscover.spec.SpecStore;
import java.util.Comparator;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/domains/{host}/spec")
public class SpecController {

    private final SpecStore specStore;

    public SpecController(SpecStore specStore) {
        this.specStore = specStore;
    }

    /** 고객→중앙→Worker 로 전달된 문서를 업로드. 업로드 시점에 파싱·검증·영속(doc/03 §7). filename=원본 파일명(옵션, doc/35 M2/M6). */
    @PutMapping
    public SpecMetaView upload(@PathVariable String host, @RequestBody byte[] content,
                               @RequestParam(required = false) String filename) {
        return SpecMetaView.of(specStore.upload(host, content, filename));
    }

    /** 도메인의 active 스펙 문서 목록 (doc/35 M6) — 문서별 filename·uploadedAt 포함. 무스펙=빈 배열(200, 404 아님). */
    @GetMapping
    public List<SpecMetaView> meta(@PathVariable String host) {
        return specStore.activeRecords(host).stream()
                .sorted(Comparator.comparing(SpecRecord::getSpecName,
                                Comparator.nullsFirst(Comparator.naturalOrder()))
                        .thenComparingLong(SpecRecord::getSpecVersion)) // 결정적 순서
                .map(SpecMetaView::of)
                .toList();
    }
}
