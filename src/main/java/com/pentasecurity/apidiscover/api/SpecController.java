// 도메인별 스펙 업로드/조회 API (doc/07 §3.1, doc/03 §7)
package com.pentasecurity.apidiscover.api;

import com.pentasecurity.apidiscover.api.dto.DomainDtos.SpecMetaView;
import com.pentasecurity.apidiscover.domain.SpecRecord;
import com.pentasecurity.apidiscover.spec.SpecStore;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/v1/domains/{host}/spec")
public class SpecController {

    private final SpecStore specStore;

    public SpecController(SpecStore specStore) {
        this.specStore = specStore;
    }

    /** 고객→중앙→Worker 로 전달된 문서를 업로드. 업로드 시점에 파싱·검증·영속(doc/03 §7). */
    @PutMapping
    public SpecMetaView upload(@PathVariable String host, @RequestBody byte[] content) {
        SpecRecord r = specStore.upload(host, content);
        return new SpecMetaView(r.getFormat(), r.getSpecVersion(), r.getEndpointCount(), r.getUploadedAt());
    }

    @GetMapping
    public SpecMetaView meta(@PathVariable String host) {
        return specStore.activeMeta(host)
                .map(r -> new SpecMetaView(r.getFormat(), r.getSpecVersion(), r.getEndpointCount(), r.getUploadedAt()))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "no spec"));
    }
}
