// 분석 대상 도메인 설정 엔티티 (doc/07 §3.1). 중앙 API 로 세팅, DB 영속
package com.pentasecurity.apidiscover.domain;

import com.pentasecurity.apidiscover.model.SpecMergeStrategy;
import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

// NOTE: 스캐폴딩 단순화를 위해 public 필드 사용. 캡슐화는 구현 시 정리(TODO).
@Entity
@Table(name = "domain_config")
public class DomainConfig {

    @Id
    public String host;

    public boolean enabled = true;

    /** 이 도메인을 서빙하는 엣지 서버(Loki hostname 라벨) (doc/05 §2.3). */
    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "domain_hostnames", joinColumns = @JoinColumn(name = "host"))
    @Column(name = "hostname")
    public List<String> hostnames = new ArrayList<>();

    /** ISO-8601 Duration 문자열(예 "PT1H") 또는 null(전역 기본 사용). */
    public String intervalOverride;

    /** 멀티 스펙 병합 전략 (doc/26 §5). ddl-auto 시 기존 행 null → 읽을 때 MERGE 로 해석(SpecStore). */
    @Enumerated(EnumType.STRING)
    public SpecMergeStrategy specMergeStrategy = SpecMergeStrategy.MERGE;

    /** 프록시가 관측 경로에서 제거한 base prefix(예 "/v2"). null=off(현행). at-match 재부착(doc/27 §3). */
    public String basePathStrip;

    public Instant createdAt;
    public Instant updatedAt;
}
