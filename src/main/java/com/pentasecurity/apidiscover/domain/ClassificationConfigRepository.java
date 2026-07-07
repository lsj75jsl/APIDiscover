// 전역 분류 설정 리포지토리 (단일행, PK=1L)
package com.pentasecurity.apidiscover.domain;

import org.springframework.data.jpa.repository.JpaRepository;

public interface ClassificationConfigRepository extends JpaRepository<ClassificationConfig, Long> {
    // Spring Data — 표준 CRUD 는 JpaRepository 가 자동 구현, 커스텀 쿼리 없어 본문 비움(dead 아님).
}
