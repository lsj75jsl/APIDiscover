// 도메인별 분류 설정 override 엔티티 (doc/10 §1.2). DomainConfig 확장 아님(관심사 분리·희소 행)
package com.pentasecurity.apidiscover.domain;

import com.pentasecurity.apidiscover.model.ClassificationProfile;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Lob;
import jakarta.persistence.Table;
import java.time.Instant;

// NOTE: 스캐폴딩 단순화를 위해 public 필드 사용(TODO: 캡슐화). 행 부재 = "전역 사용"(희소).
@Entity
@Table(name = "domain_classification_config")
public class DomainClassificationConfig {

    /** 1:1 DomainConfig.host. */
    @Id
    public String host;

    /** nullable = 전역 프로파일 상속. */
    @Enumerated(EnumType.STRING)
    public ClassificationProfile profile;

    /** nullable = 전역/preset 임계 상속. */
    public Double thresholdOverride;

    /** nullable — Map&lt;String,Double&gt;(CUSTOM 한정 가중치 override). */
    @Lob
    public String customWeightsJson;

    /** nullable — MatcherConfig(도메인 override, includeWebForms nullable). */
    @Lob
    public String matcherJson;

    public Instant updatedAt;
}
