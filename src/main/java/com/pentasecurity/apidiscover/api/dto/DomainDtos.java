// 중앙 서버 연동 REST DTO 묶음 (doc/07 §3)
package com.pentasecurity.apidiscover.api.dto;

import com.pentasecurity.apidiscover.domain.ActivityStatus;
import com.pentasecurity.apidiscover.domain.SpecMetaProjection;
import com.pentasecurity.apidiscover.domain.SpecRecord;
import com.pentasecurity.apidiscover.model.EffectiveClassificationView;
import com.pentasecurity.apidiscover.model.SpecMergeStrategy;
import com.pentasecurity.apidiscover.spec.SpecFormat;
import java.time.Instant;
import java.util.List;

public final class DomainDtos {

    private DomainDtos() {
    }

    /**
     * 도메인 등록/수정 요청. ★PUT 부분수정(doc/35 M3): 전 필드 nullable=미전달=기존값 유지(present-only apply).
     * {@code enabled} 은 Boolean(미전달 null 과 명시 false 구분). {@code hostnames} 는 null=유지·{@code []}=비우기.
     * POST(create)는 fresh 엔티티 기본값(enabled=true·hostnames=[]·MERGE)에 present-only 적용 → null=기본값 유지(무회귀).
     */
    public record DomainUpsert(
            String host,                          // POST 시 사용(PUT 은 path 우선)
            Boolean enabled,                      // null=미전달(유지/생성기본 true), true/false=명시 설정
            List<String> hostnames,               // null=유지, []=비우기, 값=교체(엣지 Loki hostname 라벨)
            String intervalOverride,              // ISO-8601 Duration, null=유지
            SpecMergeStrategy specMergeStrategy,  // null=유지(생성 시 MERGE 기본, doc/26 §5)
            String basePathStrip                  // 프록시 strip base prefix, null=유지(doc/27 §3)
    ) {}

    /** 도메인 목록(M1) 조회 응답 — 경량(spec 메타만). 목록 성능 위해 lastScanAt·effectiveClassification 미포함(단건 M2 전용). */
    public record DomainView(
            String host,
            boolean enabled,
            List<String> hostnames,
            String intervalOverride,
            SpecMergeStrategy specMergeStrategy,  // 병합 전략(doc/26 §5)
            String basePathStrip,                 // base-path-strip prefix(doc/27 §3, null=off)
            SpecMetaView spec                     // 활성 스펙 메타(없으면 null)
    ) {}

    /**
     * 도메인 단건(M2) 상세 응답 (doc/35 M2) — DomainView 경량 필드 + lastScanAt·effectiveClassification 보강.
     * ★목록(M1)과 분리: 단건 GET /{host} 만 scan_result·resolver 조회(목록 N+1 회귀 방지).
     */
    public record DomainDetailView(
            String host,
            boolean enabled,
            List<String> hostnames,
            String intervalOverride,
            SpecMergeStrategy specMergeStrategy,
            String basePathStrip,
            SpecMetaView spec,                              // 최근 active 스펙(filename·uploadedAt 포함), 없으면 null
            Instant lastScanAt,                             // scan_result.lastScanAt, 미스캔 null
            EffectiveClassificationView effectiveClassification, // 도메인 현재 분류 설정(doc/34, resolver)
            ActivityStatus activityStatus,                  // D82: 활동상태(ACTIVE=주기 스캔 대상, INACTIVE=무접속 제외)
            Instant activityStatusChangedAt,                // D82: 마지막 활동상태 전이 시각(null=이력 없음)
            boolean ghostSuppressed                         // D83: 유령 억제(스캔 반복에도 self-endpoint 0)=주기 스캔 제외
    ) {}

    /** 스펙 메타 (doc/07 §3.1, doc/35 M2/M6). filename·specName·active 가산(additive). */
    public record SpecMetaView(
            SpecFormat format,
            long specVersion,
            int endpointCount,
            Instant uploadedAt,
            String specName,    // 문서 식별(멀티 스펙), null 가능
            String filename,    // 원본 파일명(PUT ?filename=), 기존행/미전달 null
            boolean active      // 활성 버전 여부
    ) {
        /** SpecRecord → 메타 뷰 매핑(업로드 응답 등 엔티티 보유 경로). */
        public static SpecMetaView of(SpecRecord r) {
            return new SpecMetaView(r.getFormat(), r.getSpecVersion(), r.getEndpointCount(), r.getUploadedAt(),
                    r.getSpecName(), r.getFilename(), r.isActive());
        }

        /** SpecMetaProjection → 메타 뷰 매핑(★REST 메타 조회 — 대용량 text 미로드 projection, doc/28). */
        public static SpecMetaView of(SpecMetaProjection p) {
            return new SpecMetaView(p.format(), p.specVersion(), p.endpointCount(), p.uploadedAt(),
                    p.specName(), p.filename(), p.active());
        }
    }

    /** 스캔 상태 — 경량 메타 (doc/07 §3.2). totalDropped=dropped 3종 합 at-a-glance (doc/25 §C). latestSpec=최근 active 스펙(doc/35 M4). */
    public record ScanStatusView(
            String host,
            String state,
            Instant lastScanAt,
            String version,
            SummaryView summary,
            int totalDropped,
            SpecMetaView latestSpec,              // 최근 active 스펙 메타(filename·uploadedAt·endpointCount), 없으면 null
            ApiLists apis                         // 유형별 API 목록(per-scan report_json 기준, summary 와 동일 집합, 사용자 요청)
    ) {}

    public record SummaryView(
            int discovered,
            int active,
            int shadow,
            int zombie,
            int unused
    ) {}

    /**
     * scan-status 유형별 API 목록 (사용자 요청) — per-scan report_json finding 을 분류별로 나열.
     * 각 원소 형식은 {@code "GET [https://host/pathTemplate]"}. scheme 은 미저장이라 https 고정(WAAP API 트래픽 전제).
     * {@code discovered}=전체 finding(분류 무관·webpage 포함), 나머지=classification 별. summary 카운트와 동일 집합.
     */
    public record ApiLists(
            List<String> discovered,
            List<String> active,
            List<String> shadow,
            List<String> zombie,
            List<String> unused
    ) {}
}
