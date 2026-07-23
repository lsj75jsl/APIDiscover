// 스캔 결과 리포지토리
package com.pentasecurity.apidiscover.domain;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.transaction.annotation.Transactional;

public interface ScanResultRepository extends JpaRepository<ScanResult, String> {

    /** 도메인 삭제 cascade — host 의 스캔 결과 제거(D89). PK=host 라 deleteById 와 동치이나 부재 시 no-op 안전. */
    @Transactional
    void deleteByHost(String host);
}
