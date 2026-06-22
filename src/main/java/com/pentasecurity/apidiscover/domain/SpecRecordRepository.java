// 스펙 저장 리포지토리 (Spec Store)
package com.pentasecurity.apidiscover.domain;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SpecRecordRepository extends JpaRepository<SpecRecord, Long> {

    /** 현재 활성 스펙(스캔이 사용). */
    Optional<SpecRecord> findFirstByHostAndActiveIsTrueOrderBySpecVersionDesc(String host);

    /** 다음 버전 산정을 위한 최신 버전. */
    Optional<SpecRecord> findFirstByHostOrderBySpecVersionDesc(String host);

    /** 새 버전 업로드 시 비활성화할 기존 활성 레코드. */
    List<SpecRecord> findByHostAndActiveIsTrue(String host);
}
