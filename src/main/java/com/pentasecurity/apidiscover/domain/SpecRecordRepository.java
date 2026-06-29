// 스펙 저장 리포지토리 (Spec Store)
package com.pentasecurity.apidiscover.domain;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface SpecRecordRepository extends JpaRepository<SpecRecord, Long> {

    /** 현재 활성 스펙(스캔이 사용). */
    Optional<SpecRecord> findFirstByHostAndActiveIsTrueOrderBySpecVersionDesc(String host);

    /** 다음 버전 산정을 위한 최신 버전. */
    Optional<SpecRecord> findFirstByHostOrderBySpecVersionDesc(String host);

    /** 새 버전 업로드 시 비활성화할 기존 활성 레코드. */
    List<SpecRecord> findByHostAndActiveIsTrue(String host);

    /**
     * ★REST 메타 조회용 projection (doc/28·35 M2/M4/M6) — 메타 컬럼만 SELECT(rawDoc oid·canonicalJson·warningsJson 미선택)라
     * 트랜잭션 밖(auto-commit) 조회에서도 oid materialize 없이 안전. specName asc·specVersion asc 정렬(결정적, M6 목록).
     * ★{@code nulls first} 명시(specName 레거시 null 가능): H2 ASC=NULLS FIRST 가 PG ASC=NULLS LAST 발산을 가림(h2-pg-null-ordering-trap,
     * D48 findDueForScan 동형) → 기존 인메모리 {@code Comparator.nullsFirst} 동작·결정성 일치 위해 JPQL 에 명시(PG/H2 동일).
     * 엔티티 반환({@code findByHostAndActiveIsTrue}·{@code findFirst...})은 스캔 경로(@Transactional analyze)용으로 유지.
     */
    @Query("select new com.pentasecurity.apidiscover.domain.SpecMetaProjection("
            + "s.format, s.specVersion, s.endpointCount, s.uploadedAt, s.specName, s.filename, s.active) "
            + "from SpecRecord s where s.host = :host and s.active = true "
            + "order by s.specName asc nulls first, s.specVersion asc")
    List<SpecMetaProjection> findActiveSpecMetas(@Param("host") String host);

    /**
     * ★API 상태추적 diff용 canonical projection (doc/36 M7.2) — canonicalJson 포함, rawDoc oid 미선택(비-tx 안전, D51).
     * 한 (host, specName) 의 active+inactive 전 버전을 specVersion desc 로([0]=현 active·이후 직전들). specName 은
     * {@code coalesce(...,'default')} 로 매칭(레거시 null specName="default" 행 포함). diff 가 [현 active] vs [최대버전 inactive] 비교.
     */
    @Query("select new com.pentasecurity.apidiscover.domain.SpecCanonicalProjection("
            + "s.specName, s.specVersion, s.canonicalJson, s.uploadedAt, s.filename, s.active) "
            + "from SpecRecord s where s.host = :host and coalesce(s.specName, 'default') = :specName "
            + "order by s.specVersion desc")
    List<SpecCanonicalProjection> findCanonicalVersions(@Param("host") String host, @Param("specName") String specName);
}
