// 검출 SoT — 누적 검출 인벤토리 + recency(EndpointHistory 흡수) 엔티티 (doc/26 §2, D36)
package com.pentasecurity.apidiscover.domain;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Lob;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.Instant;

// NOTE: 스캐폴딩 단순화를 위해 public 필드(TODO 캡슐화, spec_record 스타일 일치). ddl-auto(update) 신규 테이블, 기존 데이터 무영향.
@Entity
@Table(name = "discovered_endpoint",
        uniqueConstraints = @UniqueConstraint(columnNames = {"host", "method", "path_template"}),
        indexes = {@Index(columnList = "host"), @Index(columnList = "host,version")})
public class DiscoveredEndpointRecord {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;

    /** 도메인 조회 키(indexed). */
    public String host;

    /** unique(host,method,path_template) = upsert 키. */
    public String method;
    public String pathTemplate;

    /** SPEC / INFERRED (doc/01 TemplateSource). */
    public String templateSource;

    /** WEB_PAGE/STATIC/API_CANDIDATE/UNKNOWN + 신뢰도 (doc/04 EndpointKind). */
    public String endpointKind;
    public double kindConfidence;

    /** 버전 태그(nullable) — path ^v\d+$ 또는 매칭 spec.version 도출 (doc/26 §4). index (host,version). */
    public String version;

    /** 누적 recency (EndpointHistory 흡수): firstSeen=min, lastSeen=max. severity entrenchment 입력(doc/24). */
    public Instant firstSeen;
    public Instant lastSeen;
    /** 최신 스캔 윈도우 끝(데이터 ts, now 미사용). */
    public Instant lastScanAt;

    /** 최신 윈도우 스냅샷(카탈로그 표시) — 분석 상세(p50/p95·distinctClients)는 reportJson 유지(doc/26 §2). */
    public long hits;
    @Lob
    public String statusDistJson;

    /** ApiScorer 신호 + ParamCandidates(doc/13) 스냅샷. */
    public boolean hadQuery;
    public boolean nonBrowserUa;
    @Lob
    public String paramsJson;
}
