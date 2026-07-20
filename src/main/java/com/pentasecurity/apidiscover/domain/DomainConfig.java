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
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.hibernate.annotations.DynamicUpdate;

// 캡슐화 완료(doc/29 D41): private 필드 + 접근자. 애너테이션은 필드 유지 → JPA field access·@ElementCollection 매핑 불변.
// @DynamicUpdate: dirty 컬럼만 UPDATE — 디스커버리 writer 는 hostnames/lastSeenAt 만 변경하므로 동시 사용자 설정 PUT
// (basePathStrip·specMergeStrategy·enabled·intervalOverride)을 stale 값으로 되쓰지 않음(lost-update 방지, D42/D18 §5).
// @Version(낙관락)은 D18 §5(last-writer-wins) 결정대로 미도입 — @DynamicUpdate 는 컬럼 범위 축소라 그 결정과 일관.
@Entity
@DynamicUpdate
@Table(name = "domain_config", indexes = {
        @Index(columnList = "next_scan_due_at"),
        // D82: 스캔 선택 hot 쿼리(enabled AND activity_status=ACTIVE AND due 정렬)·sweep bulk UPDATE 술어 — seq scan 회피(D75).
        @Index(columnList = "activity_status, next_scan_due_at")
})
public class DomainConfig {

    @Id
    private String host;

    private boolean enabled = true;

    /** 이 도메인을 서빙하는 엣지 서버(Loki hostname 라벨) (doc/05 §2.3). */
    // ★host 인덱스 필수(D75): EAGER 라 도메인 로드마다 `where host=?` 조회 발생 — 인덱스 없으면 95k행 seq scan
    // (틱당 수백회 = PG 백엔드 CPU 포화). FK 는 자식 컬럼 인덱스를 자동 생성하지 않으므로 명시.
    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "domain_hostnames", joinColumns = @JoinColumn(name = "host"),
            indexes = @Index(name = "idx_domain_hostnames_host", columnList = "host"))
    @Column(name = "hostname")
    private List<String> hostnames = new ArrayList<>();

    /** ISO-8601 Duration 문자열(예 "PT1H") 또는 null(전역 기본 사용). */
    private String intervalOverride;

    /** 멀티 스펙 병합 전략 (doc/26 §5). ddl-auto 시 기존 행 null → 읽을 때 MERGE 로 해석(SpecStore). */
    @Enumerated(EnumType.STRING)
    private SpecMergeStrategy specMergeStrategy = SpecMergeStrategy.MERGE;

    /** 프록시가 관측 경로에서 제거한 base prefix(예 "/v2"). null=off(현행). at-match 재부착(doc/27 §3). */
    private String basePathStrip;

    /** 자동 디스커버리 최초 발견 시각(doc/30 §5). 수동 등록 도메인은 null(자연 구분). ddl-auto 가산. */
    private Instant discoveredAt;
    /** 자동 디스커버리 최근 관측(집계 윈도우 끝). staleness 가시화용·삭제 트리거 아님(doc/30 §5). ddl-auto 가산. */
    private Instant lastSeenAt;

    /**
     * ★실제 access log 최신 시각(time_iso8601, D56 후속). 스캔이 Loki 에서 관측한 최신 로그시각(max discovered_endpoint.last_seen).
     * 무접속 자동스캔 제외 게이트(ScanSelector)의 기준 — {@code last_seen_at}(discovery 관측시각)이 아닌 실 로그시각.
     * 스캔 시에만 증가(never decrease). null=미스캔(제외 안 함). ddl-auto 가산.
     */
    private Instant lastAccessLogAt;

    /** 스캔 라운드로빈 커서(doc/33 §1 B) — least-recently-scanned asc nulls-first. attempt 마다 전진(skip 포함). ddl-auto. */
    private Instant lastScanAttemptAt;

    /** 다음 스캔 due 시각(doc/33 §4, D48) — 스캔 시 now+effectiveInterval 갱신, null=즉시 due. ddl-auto nullable, index. */
    private Instant nextScanDueAt;

    /**
     * D82(doc/43): 활동 상태 게이트(주기 스캔 대상). {@code enabled} 과 별도 축 — "활성만 스캔"=enabled AND ACTIVE.
     * ★{@code columnDefinition default 'ACTIVE'}: ddl-auto ADD COLUMN 시 <b>기존 행 전부 ACTIVE</b>(무회귀 — 안 그러면
     * NULL≠ACTIVE 로 전 도메인 스캔 중단). 배포 후 첫 discovery sweep 이 무접속분을 INACTIVE 로 수렴(D79 boolean default 패턴 동형).
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "activity_status", columnDefinition = "varchar not null default 'ACTIVE'")
    private ActivityStatus activityStatus = ActivityStatus.ACTIVE;

    /** 마지막 활동상태 전이 시각(감사·중앙연동용, doc/43 §4.3). flip 시에만 갱신. null=전이 이력 없음. */
    private Instant activityStatusChangedAt;

    private Instant createdAt;
    private Instant updatedAt;

    public String getHost() {
        return host;
    }

    public void setHost(String host) {
        this.host = host;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public List<String> getHostnames() {
        return hostnames;
    }

    public void setHostnames(List<String> hostnames) {
        this.hostnames = hostnames;
    }

    public String getIntervalOverride() {
        return intervalOverride;
    }

    public void setIntervalOverride(String intervalOverride) {
        this.intervalOverride = intervalOverride;
    }

    public SpecMergeStrategy getSpecMergeStrategy() {
        return specMergeStrategy;
    }

    public void setSpecMergeStrategy(SpecMergeStrategy specMergeStrategy) {
        this.specMergeStrategy = specMergeStrategy;
    }

    public String getBasePathStrip() {
        return basePathStrip;
    }

    public void setBasePathStrip(String basePathStrip) {
        this.basePathStrip = basePathStrip;
    }

    public Instant getDiscoveredAt() {
        return discoveredAt;
    }

    public void setDiscoveredAt(Instant discoveredAt) {
        this.discoveredAt = discoveredAt;
    }

    public Instant getLastSeenAt() {
        return lastSeenAt;
    }

    public void setLastSeenAt(Instant lastSeenAt) {
        this.lastSeenAt = lastSeenAt;
    }

    public Instant getLastAccessLogAt() {
        return lastAccessLogAt;
    }

    public void setLastAccessLogAt(Instant lastAccessLogAt) {
        this.lastAccessLogAt = lastAccessLogAt;
    }

    public Instant getLastScanAttemptAt() {
        return lastScanAttemptAt;
    }

    public void setLastScanAttemptAt(Instant lastScanAttemptAt) {
        this.lastScanAttemptAt = lastScanAttemptAt;
    }

    public Instant getNextScanDueAt() {
        return nextScanDueAt;
    }

    public void setNextScanDueAt(Instant nextScanDueAt) {
        this.nextScanDueAt = nextScanDueAt;
    }

    public ActivityStatus getActivityStatus() {
        return activityStatus;
    }

    public void setActivityStatus(ActivityStatus activityStatus) {
        this.activityStatus = activityStatus;
    }

    public Instant getActivityStatusChangedAt() {
        return activityStatusChangedAt;
    }

    public void setActivityStatusChangedAt(Instant activityStatusChangedAt) {
        this.activityStatusChangedAt = activityStatusChangedAt;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(Instant updatedAt) {
        this.updatedAt = updatedAt;
    }
}
