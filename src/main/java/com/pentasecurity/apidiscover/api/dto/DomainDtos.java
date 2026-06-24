// 중앙 서버 연동 REST DTO 묶음 (doc/07 §3)
package com.pentasecurity.apidiscover.api.dto;

import com.pentasecurity.apidiscover.spec.SpecFormat;
import java.time.Instant;
import java.util.List;

public final class DomainDtos {

    private DomainDtos() {
    }

    /** 도메인 등록/수정 요청. */
    public record DomainUpsert(
            String host,                 // POST 시 사용(PUT 은 path 우선)
            boolean enabled,
            List<String> hostnames,      // 엣지 서버(Loki hostname 라벨)
            String intervalOverride      // ISO-8601 Duration 또는 null
    ) {}

    /** 도메인 조회 응답. */
    public record DomainView(
            String host,
            boolean enabled,
            List<String> hostnames,
            String intervalOverride,
            SpecMetaView spec            // 활성 스펙 메타(없으면 null)
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
