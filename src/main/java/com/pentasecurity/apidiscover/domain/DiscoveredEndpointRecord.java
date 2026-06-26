// 검출 SoT — 누적 검출 인벤토리 + recency(EndpointHistory 흡수) 엔티티 (doc/26 §2, D36)
package com.pentasecurity.apidiscover.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.Instant;

// 캡슐화 완료(doc/29 D41): private 필드 + 접근자. 애너테이션은 필드 유지 → JPA field access·unique/index·text 매핑 불변.
// 자동생성 id 는 setter 미노출. ddl-auto(update) 신규 테이블, 기존 데이터 무영향.
@Entity
@Table(name = "discovered_endpoint",
        uniqueConstraints = @UniqueConstraint(columnNames = {"host", "method", "path_template"}),
        indexes = {@Index(columnList = "host"), @Index(columnList = "host,version")})
public class DiscoveredEndpointRecord {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 도메인 조회 키(indexed). */
    private String host;

    /** unique(host,method,path_template) = upsert 키. */
    private String method;
    /** 정규화 경로 — 임의 길이(긴 REST·다세그먼트) → text(varchar(255) 오버플로 회피, 실배포 발견, D40 동일 패턴). */
    @Column(columnDefinition = "text")
    private String pathTemplate;

    /** SPEC / INFERRED (doc/01 TemplateSource). */
    private String templateSource;

    /** WEB_PAGE/STATIC/API_CANDIDATE/UNKNOWN + 신뢰도 (doc/04 EndpointKind). */
    private String endpointKind;
    private double kindConfidence;

    /** 버전 태그(nullable) — path ^v\d+$ 또는 매칭 spec.version 도출 (doc/26 §4). index (host,version). */
    private String version;

    /** 누적 recency (EndpointHistory 흡수): firstSeen=min, lastSeen=max. severity entrenchment 입력(doc/24). */
    private Instant firstSeen;
    private Instant lastSeen;
    /** 최신 스캔 윈도우 끝(데이터 ts, now 미사용). */
    private Instant lastScanAt;

    /** 최신 윈도우 스냅샷(카탈로그 표시) — 분석 상세(p50/p95·distinctClients)는 reportJson 유지(doc/26 §2). */
    private long hits;
    @Column(columnDefinition = "text") // PG text 매핑(@Lob String→oid 회피, doc/28 D40/D37)
    private String statusDistJson;

    /** ApiScorer 신호 + ParamCandidates(doc/13) 스냅샷. */
    private boolean hadQuery;
    private boolean nonBrowserUa;
    @Column(columnDefinition = "text") // PG text 매핑(@Lob String→oid 회피, doc/28 D40/D37)
    private String paramsJson;

    /** 자동생성 PK — getter 만(setter 미노출, doc/29 §4). */
    public Long getId() {
        return id;
    }

    public String getHost() {
        return host;
    }

    public void setHost(String host) {
        this.host = host;
    }

    public String getMethod() {
        return method;
    }

    public void setMethod(String method) {
        this.method = method;
    }

    public String getPathTemplate() {
        return pathTemplate;
    }

    public void setPathTemplate(String pathTemplate) {
        this.pathTemplate = pathTemplate;
    }

    public String getTemplateSource() {
        return templateSource;
    }

    public void setTemplateSource(String templateSource) {
        this.templateSource = templateSource;
    }

    public String getEndpointKind() {
        return endpointKind;
    }

    public void setEndpointKind(String endpointKind) {
        this.endpointKind = endpointKind;
    }

    public double getKindConfidence() {
        return kindConfidence;
    }

    public void setKindConfidence(double kindConfidence) {
        this.kindConfidence = kindConfidence;
    }

    public String getVersion() {
        return version;
    }

    public void setVersion(String version) {
        this.version = version;
    }

    public Instant getFirstSeen() {
        return firstSeen;
    }

    public void setFirstSeen(Instant firstSeen) {
        this.firstSeen = firstSeen;
    }

    public Instant getLastSeen() {
        return lastSeen;
    }

    public void setLastSeen(Instant lastSeen) {
        this.lastSeen = lastSeen;
    }

    public Instant getLastScanAt() {
        return lastScanAt;
    }

    public void setLastScanAt(Instant lastScanAt) {
        this.lastScanAt = lastScanAt;
    }

    public long getHits() {
        return hits;
    }

    public void setHits(long hits) {
        this.hits = hits;
    }

    public String getStatusDistJson() {
        return statusDistJson;
    }

    public void setStatusDistJson(String statusDistJson) {
        this.statusDistJson = statusDistJson;
    }

    public boolean isHadQuery() {
        return hadQuery;
    }

    public void setHadQuery(boolean hadQuery) {
        this.hadQuery = hadQuery;
    }

    public boolean isNonBrowserUa() {
        return nonBrowserUa;
    }

    public void setNonBrowserUa(boolean nonBrowserUa) {
        this.nonBrowserUa = nonBrowserUa;
    }

    public String getParamsJson() {
        return paramsJson;
    }

    public void setParamsJson(String paramsJson) {
        this.paramsJson = paramsJson;
    }
}
