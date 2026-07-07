// 도메인별 분류 설정 override 리포지토리 (host PK)
package com.pentasecurity.apidiscover.domain;

import org.springframework.data.jpa.repository.JpaRepository;

public interface DomainClassificationConfigRepository extends JpaRepository<DomainClassificationConfig, String> {
    // Spring Data — 표준 CRUD 는 JpaRepository 가 자동 구현, 커스텀 쿼리 없어 본문 비움(dead 아님).
}
