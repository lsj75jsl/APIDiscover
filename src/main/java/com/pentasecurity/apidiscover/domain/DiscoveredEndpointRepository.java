// 검출 SoT 리포지토리 — host 기준 조회 + 누적 upsert + retention prune (doc/26 §2/§7)
package com.pentasecurity.apidiscover.domain;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.transaction.annotation.Transactional;

public interface DiscoveredEndpointRepository extends JpaRepository<DiscoveredEndpointRecord, Long> {

    /** host 카탈로그(스캔 upsert 로드·결합 조회). */
    List<DiscoveredEndpointRecord> findByHost(String host);

    /** host 내 버전 그룹 조회 (doc/26 §4, stage 3 결합 뷰). */
    List<DiscoveredEndpointRecord> findByHostAndVersion(String host, String version);

    /** upsert 단건 키 (doc/26 §2). 스캔 경로는 findByHost 1회 로드+맵 재사용; 단건/외부 조회용. */
    Optional<DiscoveredEndpointRecord> findByHostAndMethodAndPathTemplate(
            String host, String method, String pathTemplate);

    /** retention prune — stale(lastSeen < cutoff) 삭제(스캐너 noise 누적 방지, doc/26 §2). */
    @Transactional
    void deleteByHostAndLastSeenBefore(String host, Instant cutoff);

    /** 도메인 삭제 cascade — host 의 모든 검출 endpoint 제거(D89). */
    @Transactional
    void deleteByHost(String host);
}
