// 도메인별 분류 설정 override 엔티티 (doc/10 §1.2). DomainConfig 확장 아님(관심사 분리·희소 행)
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
// 행 부재 = "전역 사용"(희소).
@Entity
@Table(name = "domain_classification_config")
public class DomainClassificationConfig {

    /** 1:1 DomainConfig.host. */
    @Id
    private String host;

    /** nullable = 전역 프로파일 상속. */
    @Enumerated(EnumType.STRING)
    private ClassificationProfile profile;

    /** nullable = 전역/preset 임계 상속. */
    private Double thresholdOverride;

    /** nullable — Map&lt;String,Double&gt;(CUSTOM 한정 가중치 override). */
    @Column(columnDefinition = "text") // PG text 매핑(@Lob String→oid 회피, doc/28 D40/D37)
    private String customWeightsJson;

    /** nullable — MatcherConfig(도메인 override, includeWebForms nullable). */
    @Column(columnDefinition = "text") // PG text 매핑(@Lob String→oid 회피, doc/28 D40/D37)
    private String matcherJson;

    private Instant updatedAt;

    public String getHost() {
        return host;
    }

    public void setHost(String host) {
        this.host = host;
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
