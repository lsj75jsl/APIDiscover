// 중앙 서버 연동 REST DTO 묶음 (doc/07 §3)
package com.pentasecurity.apidiscover.api.dto;

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

    /** 도메인 조회 응답. */
    public record DomainView(
            String host,
            boolean enabled,
            List<String> hostnames,
            String intervalOverride,
            SpecMergeStrategy specMergeStrategy,  // 병합 전략(doc/26 §5)
            String basePathStrip,                 // base-path-strip prefix(doc/27 §3, null=off)
            SpecMetaView spec                     // 활성 스펙 메타(없으면 null)
    ) {}

    /** 스펙 메타 (doc/07 §3.1). */
    public record SpecMetaView(
            SpecFormat format,
            long specVersion,
            int endpointCount,
            Instant uploadedAt
    ) {}

    /** 스캔 상태 — 경량 메타 (doc/07 §3.2). totalDropped=dropped 3종 합 at-a-glance (doc/25 §C). */
    public record ScanStatusView(
            String host,
            String state,
            Instant lastScanAt,
            String version,
            SummaryView summary,
            int totalDropped
    ) {}

    public record SummaryView(
            int discovered,
            int active,
            int shadow,
            int zombie,
            int unused
    ) {}
}
