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

    /**
     * 스캔 due 선택 (doc/33 §4, D48) — enabled + (nextScanDueAt null=즉시 due OR &lt;= now).
     * ★정렬은 @Query 의 {@code order by nextScanDueAt asc nulls first} 가 보유(Hibernate JPQL→PG NULLS FIRST·H2 동일=결정적).
     * Pageable 은 limit(size=K) 전용 — Sort 불요(중복·PG 기본 ASC=NULLS LAST 회귀 방지, P1). null=신규 미스캔=맨 앞(기아 방지).
     * C(티어)·F(dormant)·override 가 nextScanDueAt 단일 값으로 collapse 돼 이식·인덱스 가능한 타임스탬프 술어로 단순화.
     * <p>★무접속 중단(요구): {@code staleCutoff}=now−inactive-after 보다 {@code lastSeenAt} 이 오래된(=마지막 접속이 더 과거인)
     * 도메인은 제외 → 스캔(수집+평가) 안 함. {@code lastSeenAt} null(미관측·수동등록)=제외 안 함(스캔 기회 부여).
     * ★{@code staleCutoff} 는 항상 non-null(비활성 시 호출자가 {@code Instant.EPOCH} 전달=모든 실 lastSeenAt 이후=필터 0).
     * {@code :staleCutoff is null} 같은 nullable 비교를 두지 않는다 — 실 PG 가 untyped-null($N) 타입추론 실패(h2-pg-null-ordering-trap 동류).
     */
    @Query("select d from DomainConfig d where d.enabled = true "
            + "and (d.nextScanDueAt is null or d.nextScanDueAt <= :now) "
            + "and (d.lastSeenAt is null or d.lastSeenAt >= :staleCutoff) "
            + "order by d.nextScanDueAt asc nulls first")
    List<DomainConfig> findDueForScan(@Param("now") Instant now,
                                      @Param("staleCutoff") Instant staleCutoff, Pageable pageable);

    /**
     * 스캔 스케줄 커서 전진 (doc/33 §4.3, D48) — lastScanAttemptAt(관측)·nextScanDueAt(다음 due) 동시 UPDATE.
     * 엔티티 로드 없는 직접 UPDATE라 동시 사용자 설정 PUT 과 lost-update 무관(D42 P3-1 일관). skip/실패도 갱신(재선택·기아 방지).
     */
    @Modifying
    @Transactional
    @Query("update DomainConfig d set d.lastScanAttemptAt = :now, d.nextScanDueAt = :nextDue where d.host = :host")
    void touchScanSchedule(@Param("host") String host, @Param("now") Instant now, @Param("nextDue") Instant nextDue);
}
