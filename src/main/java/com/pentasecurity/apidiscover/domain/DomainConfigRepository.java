// 도메인 설정 리포지토리
package com.pentasecurity.apidiscover.domain;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface DomainConfigRepository extends JpaRepository<DomainConfig, String> {

    List<DomainConfig> findByEnabledIsTrue();

    /** 특정 엣지 서버(hostname 라벨)가 서빙하는 도메인 설정들 — host↔domain 역방향 조회. */
    @Query("select d from DomainConfig d join d.hostnames h where h = :hostname")
    List<DomainConfig> findByHostname(@Param("hostname") String hostname);
}
