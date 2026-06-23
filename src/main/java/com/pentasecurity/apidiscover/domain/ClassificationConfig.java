// 전역 분류 설정 단일 레코드 엔티티 (doc/10 §1.1). 고정 PK=1L 로 단일행 보장
package com.pentasecurity.apidiscover.domain;

import com.pentasecurity.apidiscover.model.ClassificationProfile;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Lob;
import jakarta.persistence.Table;
import java.time.Instant;

// NOTE: 스캐폴딩 단순화를 위해 public 필드 사용(TODO: 캡슐화). 벤더 JSON 타입 미사용 — @Lob String(H2/PG 이식).
@Entity
@Table(name = "classification_config")
public class ClassificationConfig {

    /** 단일행 보장: 고정 PK=1. resolver 는 findById(1L) upsert (CHECK 제약은 H2/PG 이식성 떨어져 미채택). */
    @Id
    public Long id = 1L;

    @Enumerated(EnumType.STRING)
    public ClassificationProfile profile = ClassificationProfile.MIDDLE;

    /** nullable — 어떤 프로파일에서도 임계 override 가능(doc/10 §3). */
    public Double thresholdOverride;

    /** nullable — Map&lt;String,Double&gt;(CUSTOM 한정 가중치 override, key=Weights 필드명). */
    @Lob
    public String customWeightsJson;

    /** nullable — MatcherConfig(전역) 직렬화. */
    @Lob
    public String matcherJson;

    public Instant updatedAt;
}
