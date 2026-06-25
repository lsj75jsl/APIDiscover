// 도메인별 최신 스캔 결과/메타 엔티티 (doc/07 §3.2, §3.3)
package com.pentasecurity.apidiscover.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

// 캡슐화 완료(doc/29 D41): private 필드 + 접근자. 애너테이션은 필드 유지 → JPA field access·columnDefinition 매핑 불변.
@Entity
@Table(name = "scan_result")
public class ScanResult {

    /** 도메인별 최신 결과 1건. */
    @Id
    private String host;

    /** idle | running | failed. */
    private String state = "idle";

    private Instant lastScanAt;

    /** 결과 ETag(스캔 단위 버전). 중앙의 조건부 GET 기준. */
    private String version;

    private long specVersion;

    private Instant windowFrom;
    private Instant windowTo;

    /** 전체 리포트(JSON 직렬화, doc/01 §4). */
    @Column(columnDefinition = "text") // PG text 매핑(@Lob String→oid 회피, doc/28 D40/D37)
    private String reportJson;

    // summary (doc/01 §4)
    private int discovered;
    private int active;
    private int shadow;
    private int zombie;
    private int unused;

    /** dropped 3종 합계(non_api+byLimit+nonExistent) — scan-status at-a-glance (doc/25 §C). */
    // ddl-auto ALTER ADD COLUMN 시 DEFAULT 0 → 기존 PG 행 NULL 없이 0 백필(int primitive 안전).
    @Column(columnDefinition = "integer default 0")
    private int totalDropped;

    public String getHost() {
        return host;
    }

    public void setHost(String host) {
        this.host = host;
    }

    public String getState() {
        return state;
    }

    public void setState(String state) {
        this.state = state;
    }

    public Instant getLastScanAt() {
        return lastScanAt;
    }

    public void setLastScanAt(Instant lastScanAt) {
        this.lastScanAt = lastScanAt;
    }

    public String getVersion() {
        return version;
    }

    public void setVersion(String version) {
        this.version = version;
    }

    public long getSpecVersion() {
        return specVersion;
    }

    public void setSpecVersion(long specVersion) {
        this.specVersion = specVersion;
    }

    public Instant getWindowFrom() {
        return windowFrom;
    }

    public void setWindowFrom(Instant windowFrom) {
        this.windowFrom = windowFrom;
    }

    public Instant getWindowTo() {
        return windowTo;
    }

    public void setWindowTo(Instant windowTo) {
        this.windowTo = windowTo;
    }

    public String getReportJson() {
        return reportJson;
    }

    public void setReportJson(String reportJson) {
        this.reportJson = reportJson;
    }

    public int getDiscovered() {
        return discovered;
    }

    public void setDiscovered(int discovered) {
        this.discovered = discovered;
    }

    public int getActive() {
        return active;
    }

    public void setActive(int active) {
        this.active = active;
    }

    public int getShadow() {
        return shadow;
    }

    public void setShadow(int shadow) {
        this.shadow = shadow;
    }

    public int getZombie() {
        return zombie;
    }

    public void setZombie(int zombie) {
        this.zombie = zombie;
    }

    public int getUnused() {
        return unused;
    }

    public void setUnused(int unused) {
        this.unused = unused;
    }

    public int getTotalDropped() {
        return totalDropped;
    }

    public void setTotalDropped(int totalDropped) {
        this.totalDropped = totalDropped;
    }
}
