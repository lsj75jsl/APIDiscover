// 도메인×버전 스펙 저장 엔티티 (doc/03 §7.3). 원본 + Canonical(직렬화) 보관
package com.pentasecurity.apidiscover.domain;

import com.pentasecurity.apidiscover.spec.SpecFormat;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Lob;
import jakarta.persistence.Table;
import java.time.Instant;

// 캡슐화 완료(doc/29 D41): private 필드 + 접근자. 애너테이션은 필드 유지 → JPA field access·@Lob byte[] 매핑 불변.
// 자동생성 id 는 setter 미노출(Hibernate field-access 기록).
@Entity
@Table(name = "spec_record")
public class SpecRecord {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String host;

    /** host 내 문서 식별(멀티 스펙, doc/26 §3). null→"default" 로 해석. 멀티문서 upsert 동작은 2단계. */
    private String specName;

    /** 원본 파일명(PUT /spec ?filename=, doc/35 M2/M6). ADD-only(ddl-auto)·기존행/미전달 null→표시 폴백. */
    private String filename;

    @Enumerated(EnumType.STRING)
    private SpecFormat format;

    private long specVersion;

    /** 원본 문서(감사/재파싱용). */
    @Lob
    private byte[] rawDoc;

    /** Canonical 엔드포인트 집합 직렬화(JSON). 매칭의 진실원. */
    @Column(columnDefinition = "text") // PG text 매핑(@Lob String→oid 회피, doc/28 D40/D37)
    private String canonicalJson;

    /** nullable — 파싱 recoverable 경고 List&lt;String&gt; 직렬화(doc/25 §A.2). 스캔이 specSource.warnings 로 로드. */
    @Column(columnDefinition = "text") // PG text 매핑(@Lob String→oid 회피, doc/28 D40/D37)
    private String warningsJson;

    private int endpointCount;

    private Instant uploadedAt;

    /** 활성 버전 여부. */
    private boolean active;

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

    public String getSpecName() {
        return specName;
    }

    public void setSpecName(String specName) {
        this.specName = specName;
    }

    public String getFilename() {
        return filename;
    }

    public void setFilename(String filename) {
        this.filename = filename;
    }

    public SpecFormat getFormat() {
        return format;
    }

    public void setFormat(SpecFormat format) {
        this.format = format;
    }

    public long getSpecVersion() {
        return specVersion;
    }

    public void setSpecVersion(long specVersion) {
        this.specVersion = specVersion;
    }

    public byte[] getRawDoc() {
        return rawDoc;
    }

    public void setRawDoc(byte[] rawDoc) {
        this.rawDoc = rawDoc;
    }

    public String getCanonicalJson() {
        return canonicalJson;
    }

    public void setCanonicalJson(String canonicalJson) {
        this.canonicalJson = canonicalJson;
    }

    public String getWarningsJson() {
        return warningsJson;
    }

    public void setWarningsJson(String warningsJson) {
        this.warningsJson = warningsJson;
    }

    public int getEndpointCount() {
        return endpointCount;
    }

    public void setEndpointCount(int endpointCount) {
        this.endpointCount = endpointCount;
    }

    public Instant getUploadedAt() {
        return uploadedAt;
    }

    public void setUploadedAt(Instant uploadedAt) {
        this.uploadedAt = uploadedAt;
    }

    public boolean isActive() {
        return active;
    }

    public void setActive(boolean active) {
        this.active = active;
    }
}
