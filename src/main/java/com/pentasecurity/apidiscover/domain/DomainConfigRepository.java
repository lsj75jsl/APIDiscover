// 도메인 설정 리포지토리
package com.pentasecurity.apidiscover.domain;

import java.time.Instant;
import java.util.List;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

public interface DomainConfigRepository extends JpaRepository<DomainConfig, String> {

    List<DomainConfig> findByEnabledIsTrue();

    /** D65: 관측된 엣지(hostname) 전체 — EdgeGroupResolver 의 그룹 Master 맵 구성용(413행 수준, 경량). */
    @Query("select distinct h from DomainConfig d join d.hostnames h")
    List<String> findDistinctHostnames();

    /**
     * 스캔 due 선택 (doc/33 §4, D48) — enabled + (nextScanDueAt null=즉시 due OR &lt;= now).
     * ★정렬은 @Query 의 {@code order by nextScanDueAt asc nulls first} 가 보유(Hibernate JPQL→PG NULLS FIRST·H2 동일=결정적).
     * Pageable 은 limit(size=K) 전용 — Sort 불요(중복·PG 기본 ASC=NULLS LAST 회귀 방지, P1). null=신규 미스캔=맨 앞(기아 방지).
     * C(티어)·F(dormant)·override 가 nextScanDueAt 단일 값으로 collapse 돼 이식·인덱스 가능한 타임스탬프 술어로 단순화.
     * <p>★무접속 자동스캔 제외(D59, D57 재설계): {@code staleCutoff}=now−inactive-after 보다 {@code lastSeenAt}(discovery 가
     * 매 관측마다 갱신하는 실시간 Loki 관측시각)이 오래된 도메인은 제외 → 자동 스캔 안 함. discovery 는 10분마다 Loki 실시간
     * 집계로 트래픽 있는 도메인을 관측하므로, {@code lastSeenAt} 이 오래됨 = "신규 로그가 Loki 에 안 들어옴"을 실시간으로 판정
     * (D57 의 {@code lastAccessLogAt}=스캔이 채우는 값은 스캔 지연[~1.5일]·미스캔 다수로 신선도 낮아 D59에서 전환). scan-now(명시)는
     * ScanSelector 미경유라 항상 스캔. self-healing: discovery 가 트래픽 재관측하면 {@code lastSeenAt} 갱신→자동 재포함.
     * {@code lastSeenAt} 은 upsert insert 시 항상 set(사실상 non-null)이나 방어적으로 null=제외 안 함.
     * ★{@code staleCutoff} 는 항상 non-null(비활성 시 호출자가 {@code Instant.EPOCH} 전달). nullable 비교 금지(실 PG untyped-null).
     */
    @Query("select d from DomainConfig d where d.enabled = true "
            + "and (d.nextScanDueAt is null or d.nextScanDueAt <= :now) "
            + "and (d.lastSeenAt is null or d.lastSeenAt >= :staleCutoff) "
            + "order by d.nextScanDueAt asc nulls first")
    List<DomainConfig> findDueForScan(@Param("now") Instant now,
                                      @Param("staleCutoff") Instant staleCutoff, Pageable pageable);

    /**
     * D64 활성 우선(Phase 3): due 중 "워터마크 이후 신규 트래픽 확정" 도메인 — discovery 관측(lastSeenAt)이
     * 워터마크(lastEnd)보다 최신이거나 미스캔(watermark 없음) = 실조회가 필요한 활성분. 술어·정렬은 findDueForScan 동일.
     * findDueWithoutNewTraffic 과 정확히 분할(같은 due 집합의 여집합).
     */
    @Query("select d from DomainConfig d left join Watermark w on w.host = d.host "
            + "where d.enabled = true "
            + "and (d.nextScanDueAt is null or d.nextScanDueAt <= :now) "
            + "and (d.lastSeenAt is null or d.lastSeenAt >= :staleCutoff) "
            + "and (w.lastEnd is null or d.lastSeenAt > w.lastEnd) "
            + "order by d.nextScanDueAt asc nulls first")
    List<DomainConfig> findDueWithNewTraffic(@Param("now") Instant now,
                                             @Param("staleCutoff") Instant staleCutoff, Pageable pageable);

    /** D64: findDueWithNewTraffic 의 여집합 — 신규 트래픽 없음(delta-skip 예정, 값싼 워터마크 전진용). */
    @Query("select d from DomainConfig d join Watermark w on w.host = d.host "
            + "where d.enabled = true "
            + "and (d.nextScanDueAt is null or d.nextScanDueAt <= :now) "
            + "and (d.lastSeenAt is null or d.lastSeenAt >= :staleCutoff) "
            + "and (d.lastSeenAt is null or d.lastSeenAt <= w.lastEnd) "
            + "order by d.nextScanDueAt asc nulls first")
    List<DomainConfig> findDueWithoutNewTraffic(@Param("now") Instant now,
                                                @Param("staleCutoff") Instant staleCutoff, Pageable pageable);

    /**
     * ★실 access log 최신 시각 갱신(D56) — 스캔이 관측한 최신 로그시각으로 {@code last_access_log_at} 전진(never decrease).
     * ★D59부터 무접속 게이트 기준은 {@code lastSeenAt}(discovery)로 이전 — 이 값은 이제 정보성(마지막 스캔이 본 실 로그시각). 유지.
     * 직접 UPDATE(엔티티 로드 없음)라 동시 설정 PUT 과 무관. 더 최신일 때만 갱신.
     */
    @Modifying
    @Transactional
    @Query("update DomainConfig d set d.lastAccessLogAt = :ts "
            + "where d.host = :host and (d.lastAccessLogAt is null or d.lastAccessLogAt < :ts)")
    void touchLastAccessLogAt(@Param("host") String host, @Param("ts") Instant ts);

    /**
     * 스캔 스케줄 커서 전진 (doc/33 §4.3, D48) — lastScanAttemptAt(관측)·nextScanDueAt(다음 due) 동시 UPDATE.
     * 엔티티 로드 없는 직접 UPDATE라 동시 사용자 설정 PUT 과 lost-update 무관(D42 P3-1 일관). skip/실패도 갱신(재선택·기아 방지).
     */
    @Modifying
    @Transactional
    @Query("update DomainConfig d set d.lastScanAttemptAt = :now, d.nextScanDueAt = :nextDue where d.host = :host")
    void touchScanSchedule(@Param("host") String host, @Param("now") Instant now, @Param("nextDue") Instant nextDue);
}
