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
import com.pentasecurity.apidiscover.util.DomainNames;
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
        // ★등록 host 정규화(근본, doc/05 §2.2): domain_config.host 가 항상 정규화돼야 |= 쿼리·foreign-host 필터·identity 키와 정합.
        // auto-discovery 는 이미 normalize 후 등록 — 수동 등록도 동일 규칙(대소문자 무관 도메인 동일성=RFC 정합).
        String host = requireNormalizedHost(req.host());
        if (repo.existsById(host)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "domain already exists");
        }
        DomainConfig d = new DomainConfig();
        d.setHost(host);
        apply(d, req);
        d.setCreatedAt(Instant.now());
        d.setUpdatedAt(d.getCreatedAt());
        repo.save(d);
        return ResponseEntity.status(HttpStatus.CREATED).body(toView(d));
    }

    @GetMapping("/{host}")
    public DomainView get(@PathVariable String host) {
        return toView(find(requireNormalizedHost(host))); // 경로 host 정규화(등록 정규화와 자기일관, 대문자 경로 매칭)
    }

    @PutMapping("/{host}")
    public DomainView update(@PathVariable String host, @RequestBody DomainUpsert req) {
        DomainConfig d = find(requireNormalizedHost(host)); // 정규화 키로 기존 엔티티 매칭(등록 정규화 일관)
        apply(d, req);
        d.setUpdatedAt(Instant.now());
        repo.save(d);
        return toView(d);
    }

    @DeleteMapping("/{host}")
    public ResponseEntity<Void> delete(@PathVariable String host) {
        String key = requireNormalizedHost(host); // 경로 host 정규화(등록 정규화 일관)
        if (!repo.existsById(key)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        }
        repo.deleteById(key);
        return ResponseEntity.noContent().build();
    }

    private DomainConfig find(String host) {
        return repo.findById(host)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
    }

    /** host 정규화(create/PUT/GET/DELETE 공통) — 빈/blank/"-" → 400(doc/05 §2.2). */
    private static String requireNormalizedHost(String host) {
        String h = DomainNames.normalize(host);
        if (h == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "host is required");
        }
        return h;
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
