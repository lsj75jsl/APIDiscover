// 도메인별 최신 스캔 결과/메타 엔티티 (doc/07 §3.2, §3.3)
package com.pentasecurity.apidiscover.domain;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Lob;
import jakarta.persistence.Table;
import java.time.Instant;

// NOTE: 스캐폴딩 단순화를 위해 public 필드 사용(TODO: 캡슐화). 이력 보관은 후속.
@Entity
@Table(name = "scan_result")
public class ScanResult {

    /** 도메인별 최신 결과 1건. */
    @Id
    public String host;

    /** idle | running | failed. */
    public String state = "idle";

    public Instant lastScanAt;

    /** 결과 ETag(스캔 단위 버전). 중앙의 조건부 GET 기준. */
    public String version;

    public long specVersion;

    public Instant windowFrom;
    public Instant windowTo;

    /** 전체 리포트(JSON 직렬화, doc/01 §4). */
    @Lob
    public String reportJson;

    // summary (doc/01 §4)
    public int discovered;
    public int active;
    public int shadow;
    public int zombie;
    public int unused;
}
