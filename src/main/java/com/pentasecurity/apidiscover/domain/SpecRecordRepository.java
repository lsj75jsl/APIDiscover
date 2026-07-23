// 스펙 저장 리포지토리 (Spec Store)
package com.pentasecurity.apidiscover.domain;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

public interface SpecRecordRepository extends JpaRepository<SpecRecord, Long> {

    /** 도메인 삭제 cascade — host 의 모든 스펙 문서/버전 제거(D89). */
    @Transactional
    void deleteByHost(String host);

    /** 현재 활성 스펙(스캔이 사용). */
    Optional<SpecRecord> findFirstByHostAndActiveIsTrueOrderBySpecVersionDesc(String host);

    /** 다음 버전 산정을 위한 최신 버전. */
    Optional<SpecRecord> findFirstByHostOrderBySpecVersionDesc(String host);

    /** 새 버전 업로드 시 비활성화할 기존 활성 레코드. */
    List<SpecRecord> findByHostAndActiveIsTrue(String host);

    /**
     * ★REST 메타 조회용 projection (doc/28·35 M2/M4/M6) — 메타 컬럼만 SELECT(대용량 text canonicalJson·warningsJson 미선택)라
     * 목록/메타 조회 시 대용량 컬럼을 로드하지 않는다(LOB 폭증 방지). specName asc·specVersion asc 정렬(결정적, M6 목록).
     * ★{@code nulls first} 명시(specName 레거시 null 가능): H2 ASC=NULLS FIRST 가 PG ASC=NULLS LAST 발산을 가림(h2-pg-null-ordering-trap,
     * D48 findDueForScan 동형) → 기존 인메모리 {@code Comparator.nullsFirst} 동작·결정성 일치 위해 JPQL 에 명시(PG/H2 동일).
     * 엔티티 반환({@code findByHostAndActiveIsTrue}·{@code findFirst...})은 스캔 경로(@Transactional analyze)용으로 유지.
     */
    @Query("select new com.pentasecurity.apidiscover.domain.SpecMetaProjection("
            + "s.format, s.specVersion, s.endpointCount, s.uploadedAt, s.specName, s.filename, s.active) "
            + "from SpecRecord s where s.host = :host and s.active = true "
            + "order by s.specName asc nulls first, s.specVersion asc")
    List<SpecMetaProjection> findActiveSpecMetas(@Param("host") String host);
}
