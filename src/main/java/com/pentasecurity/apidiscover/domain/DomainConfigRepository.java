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
     * 스캔 라운드로빈 선택 (doc/33 §1 B) — enabled + least-recently-scanned 상위 K.
     * 호출측이 {@code Pageable}(size=K, Sort lastScanAttemptAt asc NULLS FIRST)을 넘긴다(미스캔=우선).
     */
    List<DomainConfig> findByEnabledIsTrue(Pageable pageable);

    /** 특정 엣지 서버(hostname 라벨)가 서빙하는 도메인 설정들 — host↔domain 역방향 조회. */
    @Query("select d from DomainConfig d join d.hostnames h where h = :hostname")
    List<DomainConfig> findByHostname(@Param("hostname") String hostname);

    /**
     * 라운드로빈 커서 전진 (doc/33 §1 B) — lastScanAttemptAt 단일 컬럼만 UPDATE.
     * 엔티티 로드 없는 직접 UPDATE라 동시 사용자 설정 PUT 과 lost-update 무관(D42 P3-1 일관).
     */
    @Modifying
    @Transactional
    @Query("update DomainConfig d set d.lastScanAttemptAt = :now where d.host = :host")
    void touchLastScanAttempt(@Param("host") String host, @Param("now") Instant now);
}
