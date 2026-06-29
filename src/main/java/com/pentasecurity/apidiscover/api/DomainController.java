// 도메인 설정 CRUD API (doc/07 §3.1)
package com.pentasecurity.apidiscover.api;

import com.pentasecurity.apidiscover.api.dto.DomainDtos.DomainDetailView;
import com.pentasecurity.apidiscover.api.dto.DomainDtos.DomainUpsert;
import com.pentasecurity.apidiscover.api.dto.DomainDtos.DomainView;
import com.pentasecurity.apidiscover.api.dto.DomainDtos.SpecMetaView;
import com.pentasecurity.apidiscover.classify.EffectiveClassificationResolver;
import com.pentasecurity.apidiscover.domain.DomainConfig;
import com.pentasecurity.apidiscover.domain.DomainConfigRepository;
import com.pentasecurity.apidiscover.domain.ScanResult;
import com.pentasecurity.apidiscover.domain.ScanResultRepository;
import com.pentasecurity.apidiscover.model.SpecMergeStrategy;
import com.pentasecurity.apidiscover.spec.SpecStore;
import com.pentasecurity.apidiscover.util.DomainNames;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/v1/domains")
public class DomainController {

    private final DomainConfigRepository repo;
    private final SpecStore specStore;
    private final ScanResultRepository scanRepo;
    private final EffectiveClassificationResolver classificationResolver;

    public DomainController(DomainConfigRepository repo, SpecStore specStore,
                            ScanResultRepository scanRepo, EffectiveClassificationResolver classificationResolver) {
        this.repo = repo;
        this.specStore = specStore;
        this.scanRepo = scanRepo;
        this.classificationResolver = classificationResolver;
    }

    /** 페이지 크기 상한 (doc/35 M1) — N+1·페이로드 폭주 차단. 14k 도메인을 page 당 ≤1000 으로 분할. */
    static final int MAX_PAGE_SIZE = 1000;

    /**
     * 도메인 목록 (doc/35 M1, ★사용자 확정: body=JSON 배열 유지 + 페이지 정보는 헤더). page=0-based(기본 0), size 상한 1000.
     * 헤더: {@code X-Total-Count}(전체 수)·{@code X-Total-Pages}(전체 페이지)·{@code X-Current-Page}(0-based 현재 페이지).
     * host asc 정렬(결정적). page 초과 시 빈 배열+헤더.
     */
    @GetMapping
    public ResponseEntity<List<DomainView>> list(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "1000") int size) {
        int safePage = Math.max(0, page);
        int safeSize = Math.min(Math.max(1, size), MAX_PAGE_SIZE); // [1,1000] clamp
        Page<DomainConfig> p = repo.findAll(PageRequest.of(safePage, safeSize, Sort.by("host")));
        // ponytail: toView 가 도메인별 specStore.latestSpecMeta() 호출 → page 당 최대 1000회(기존 14k 전건에서 page-bounded 로 완화).
        // batch spec meta 로드는 SpecStore 배치 API 신설이 필요해 후속(doc/35 M1) — page 상한으로 폭주는 이미 차단.
        List<DomainView> body = p.getContent().stream().map(this::toView).toList();
        return ResponseEntity.ok()
                .header("X-Total-Count", String.valueOf(p.getTotalElements()))
                .header("X-Total-Pages", String.valueOf(p.getTotalPages()))
                .header("X-Current-Page", String.valueOf(p.getNumber()))
                .body(body);
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

    /** 단건 상세(M2, doc/35) — 경량 toView + lastScanAt·effectiveClassification 보강(목록 M1 은 미보강=성능 회귀 방지). */
    @GetMapping("/{host}")
    public DomainDetailView get(@PathVariable String host) {
        DomainConfig d = find(requireNormalizedHost(host)); // 경로 host 정규화(등록 정규화와 자기일관, 대문자 경로 매칭)
        SpecMetaView spec = specStore.latestSpecMeta(d.getHost()).map(SpecMetaView::of).orElse(null); // projection(rawDoc oid 미접근, doc/28)
        SpecMergeStrategy mode = d.getSpecMergeStrategy() != null ? d.getSpecMergeStrategy() : SpecMergeStrategy.MERGE;
        Instant lastScanAt = scanRepo.findById(d.getHost()).map(ScanResult::getLastScanAt).orElse(null);
        var effective = classificationResolver.resolve(d.getHost()).toView(); // 공유 빌더(EffectiveClassification.toView)
        return new DomainDetailView(d.getHost(), d.isEnabled(), d.getHostnames(), d.getIntervalOverride(),
                mode, d.getBasePathStrip(), spec, lastScanAt, effective);
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

    /**
     * present-only 적용 (doc/35 M3, PATCH 의미) — 전달된 필드(non-null)만 반영, 미전달(null)은 기존값 유지.
     * PUT 부분수정과 create 가 공유: create 는 fresh 엔티티 기본값(enabled=true·hostnames=[]·MERGE)에 적용하므로
     * 미전달 필드가 기본값으로 남는다(무회귀). hostnames=[](빈 배열 명시)은 non-null 이라 "비우기"로 반영(null=유지와 구분).
     */
    private void apply(DomainConfig d, DomainUpsert req) {
        if (req.enabled() != null) {
            d.setEnabled(req.enabled());
        }
        if (req.hostnames() != null) {
            d.setHostnames(new ArrayList<>(req.hostnames())); // []=비우기, 값=교체
        }
        if (req.intervalOverride() != null) {
            d.setIntervalOverride(req.intervalOverride());
        }
        if (req.specMergeStrategy() != null) {
            d.setSpecMergeStrategy(req.specMergeStrategy());
        }
        if (req.basePathStrip() != null) {
            d.setBasePathStrip(req.basePathStrip());
        }
    }

    /** 목록(M1) 경량 뷰 — spec 메타만(lastScanAt·effectiveClassification 미조회=page 당 N+1 회귀 방지). */
    private DomainView toView(DomainConfig d) {
        SpecMetaView spec = specStore.latestSpecMeta(d.getHost()).map(SpecMetaView::of).orElse(null); // projection(rawDoc oid 미접근, doc/28)
        SpecMergeStrategy mode = d.getSpecMergeStrategy() != null ? d.getSpecMergeStrategy() : SpecMergeStrategy.MERGE;
        return new DomainView(d.getHost(), d.isEnabled(), d.getHostnames(), d.getIntervalOverride(), mode, d.getBasePathStrip(), spec);
    }
}
