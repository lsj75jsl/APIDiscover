// host 결합 Discovery 뷰 조회 API — 누적 검출∪스펙 분류 결과 (doc/26 §6/§7)
package com.pentasecurity.apidiscover.api;

import com.pentasecurity.apidiscover.batch.CombinedDiscoveryService;
import com.pentasecurity.apidiscover.model.CombinedDiscovery;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/domains/{host}")
public class CombinedDiscoveryController {

    private final CombinedDiscoveryService service;

    public CombinedDiscoveryController(CombinedDiscoveryService service) {
        this.service = service;
    }

    /** host 단위 결합 Discovery(검출∪스펙). VERSION_GROUPED 모드면 versionGroups 동봉. */
    @GetMapping("/discovery")
    public CombinedDiscovery discovery(@PathVariable String host) {
        return service.forHost(host);
    }
}
