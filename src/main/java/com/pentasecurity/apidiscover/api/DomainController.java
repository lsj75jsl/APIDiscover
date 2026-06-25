// 도메인 설정 CRUD API (doc/07 §3.1)
package com.pentasecurity.apidiscover.api;

import com.pentasecurity.apidiscover.api.dto.DomainDtos.DomainUpsert;
import com.pentasecurity.apidiscover.api.dto.DomainDtos.DomainView;
import com.pentasecurity.apidiscover.api.dto.DomainDtos.SpecMetaView;
import com.pentasecurity.apidiscover.domain.DomainConfig;
import com.pentasecurity.apidiscover.domain.DomainConfigRepository;
import com.pentasecurity.apidiscover.domain.SpecRecord;
import com.pentasecurity.apidiscover.model.SpecMergeStrategy;
import com.pentasecurity.apidiscover.spec.SpecStore;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/v1/domains")
public class DomainController {

    private final DomainConfigRepository repo;
    private final SpecStore specStore;

    public DomainController(DomainConfigRepository repo, SpecStore specStore) {
        this.repo = repo;
        this.specStore = specStore;
    }

    @GetMapping
    public List<DomainView> list() {
        return repo.findAll().stream().map(this::toView).toList();
    }

    @PostMapping
    public ResponseEntity<DomainView> create(@RequestBody DomainUpsert req) {
        if (req.host() == null || req.host().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "host is required");
        }
        if (repo.existsById(req.host())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "domain already exists");
        }
        DomainConfig d = new DomainConfig();
        d.setHost(req.host());
        apply(d, req);
        d.setCreatedAt(Instant.now());
        d.setUpdatedAt(d.getCreatedAt());
        repo.save(d);
        return ResponseEntity.status(HttpStatus.CREATED).body(toView(d));
    }

    @GetMapping("/{host}")
    public DomainView get(@PathVariable String host) {
        return toView(find(host));
    }

    @PutMapping("/{host}")
    public DomainView update(@PathVariable String host, @RequestBody DomainUpsert req) {
        DomainConfig d = find(host);
        apply(d, req);
        d.setUpdatedAt(Instant.now());
        repo.save(d);
        return toView(d);
    }

    @DeleteMapping("/{host}")
    public ResponseEntity<Void> delete(@PathVariable String host) {
        if (!repo.existsById(host)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        }
        repo.deleteById(host);
        return ResponseEntity.noContent().build();
    }

    private DomainConfig find(String host) {
        return repo.findById(host)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
    }

    private void apply(DomainConfig d, DomainUpsert req) {
        d.setEnabled(req.enabled());
        d.setHostnames(req.hostnames() != null ? new ArrayList<>(req.hostnames()) : new ArrayList<>());
        d.setIntervalOverride(req.intervalOverride());
        // null → MERGE 유지(현행 무회귀, doc/26 §5). 미지정 PUT 이 모드를 지우지 않음.
        if (req.specMergeStrategy() != null) {
            d.setSpecMergeStrategy(req.specMergeStrategy());
        }
        // base-path-strip prefix (doc/27 §3). null=off — intervalOverride 와 동형(직접 대입).
        d.setBasePathStrip(req.basePathStrip());
    }

    private DomainView toView(DomainConfig d) {
        SpecMetaView spec = specStore.activeMeta(d.getHost()).map(DomainController::toSpecView).orElse(null);
        SpecMergeStrategy mode = d.getSpecMergeStrategy() != null ? d.getSpecMergeStrategy() : SpecMergeStrategy.MERGE;
        return new DomainView(d.getHost(), d.isEnabled(), d.getHostnames(), d.getIntervalOverride(), mode, d.getBasePathStrip(), spec);
    }

    private static SpecMetaView toSpecView(SpecRecord r) {
        return new SpecMetaView(r.getFormat(), r.getSpecVersion(), r.getEndpointCount(), r.getUploadedAt());
    }
}
