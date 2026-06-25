// 전역 분류 설정 단일 레코드 엔티티 (doc/10 §1.1). 고정 PK=1L 로 단일행 보장
package com.pentasecurity.apidiscover.domain;

import com.pentasecurity.apidiscover.model.ClassificationProfile;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

// 캡슐화 완료(doc/29 D41): private 필드 + 접근자. 애너테이션은 필드 유지 → JPA field access 불변.
// 벤더 JSON 타입 미사용 — @Column(columnDefinition="text")로 PG text 매핑(@Lob String→oid·LOB 결함 회피, doc/28 D40/D37).
@Entity
@Table(name = "classification_config")
public class ClassificationConfig {

    /** 단일행 보장: 고정 PK=1. resolver 는 findById(1L) upsert (CHECK 제약은 H2/PG 이식성 떨어져 미채택). */
    @Id
    private Long id = 1L;

    @Enumerated(EnumType.STRING)
    private ClassificationProfile profile = ClassificationProfile.MIDDLE;

    /** nullable — 어떤 프로파일에서도 임계 override 가능(doc/10 §3). */
    private Double thresholdOverride;

    /** nullable — Map&lt;String,Double&gt;(CUSTOM 한정 가중치 override, key=Weights 필드명). */
    @Column(columnDefinition = "text") // PG text 매핑(@Lob String→oid 회피, doc/28 D40/D37)
    private String customWeightsJson;

    /** nullable — MatcherConfig(전역) 직렬화. */
    @Column(columnDefinition = "text") // PG text 매핑(@Lob String→oid 회피, doc/28 D40/D37)
    private String matcherJson;

    private Instant updatedAt;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public ClassificationProfile getProfile() {
        return profile;
    }

    public void setProfile(ClassificationProfile profile) {
        this.profile = profile;
    }

    public Double getThresholdOverride() {
        return thresholdOverride;
    }

    public void setThresholdOverride(Double thresholdOverride) {
        this.thresholdOverride = thresholdOverride;
    }

    public String getCustomWeightsJson() {
        return customWeightsJson;
    }

    public void setCustomWeightsJson(String customWeightsJson) {
        this.customWeightsJson = customWeightsJson;
    }

    public String getMatcherJson() {
        return matcherJson;
    }

    public void setMatcherJson(String matcherJson) {
        this.matcherJson = matcherJson;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(Instant updatedAt) {
        this.updatedAt = updatedAt;
    }
}
