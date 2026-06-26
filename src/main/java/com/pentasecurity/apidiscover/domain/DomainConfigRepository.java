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
     */
    @Query("select d from DomainConfig d where d.enabled = true "
            + "and (d.nextScanDueAt is null or d.nextScanDueAt <= :now) "
            + "order by d.nextScanDueAt asc nulls first")
    List<DomainConfig> findDueForScan(@Param("now") Instant now, Pageable pageable);

    /** 특정 엣지 서버(hostname 라벨)가 서빙하는 도메인 설정들 — host↔domain 역방향 조회. */
    @Query("select d from DomainConfig d join d.hostnames h where h = :hostname")
    List<DomainConfig> findByHostname(@Param("hostname") String hostname);

    /**
     * 스캔 스케줄 커서 전진 (doc/33 §4.3, D48) — lastScanAttemptAt(관측)·nextScanDueAt(다음 due) 동시 UPDATE.
     * 엔티티 로드 없는 직접 UPDATE라 동시 사용자 설정 PUT 과 lost-update 무관(D42 P3-1 일관). skip/실패도 갱신(재선택·기아 방지).
     */
    @Modifying
    @Transactional
    @Query("update DomainConfig d set d.lastScanAttemptAt = :now, d.nextScanDueAt = :nextDue where d.host = :host")
    void touchScanSchedule(@Param("host") String host, @Param("now") Instant now, @Param("nextDue") Instant nextDue);
}
