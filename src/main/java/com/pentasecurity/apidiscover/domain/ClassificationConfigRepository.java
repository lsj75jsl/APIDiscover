// 전역 분류 설정 리포지토리 (단일행, PK=1L)
package com.pentasecurity.apidiscover.domain;

import org.springframework.data.jpa.repository.JpaRepository;

public interface ClassificationConfigRepository extends JpaRepository<ClassificationConfig, Long> {
}
