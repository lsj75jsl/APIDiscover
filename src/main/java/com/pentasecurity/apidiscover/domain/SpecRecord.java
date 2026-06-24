// 도메인×버전 스펙 저장 엔티티 (doc/03 §7.3). 원본 + Canonical(직렬화) 보관
package com.pentasecurity.apidiscover.domain;

import com.pentasecurity.apidiscover.spec.SpecFormat;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Lob;
import jakarta.persistence.Table;
import java.time.Instant;

// NOTE: 스캐폴딩 단순화를 위해 public 필드 사용(TODO: 캡슐화).
@Entity
@Table(name = "spec_record")
public class SpecRecord {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;

    public String host;

    @Enumerated(EnumType.STRING)
    public SpecFormat format;

    public long specVersion;

    /** 원본 문서(감사/재파싱용). */
    @Lob
    public byte[] rawDoc;

    /** Canonical 엔드포인트 집합 직렬화(JSON). 매칭의 진실원. */
    @Lob
    public String canonicalJson;

    /** nullable — 파싱 recoverable 경고 List&lt;String&gt; 직렬화(doc/25 §A.2). 스캔이 specSource.warnings 로 로드. */
    @Lob
    public String warningsJson;

    public int endpointCount;

    public Instant uploadedAt;

    /** 활성 버전 여부. */
    public boolean active;
}
