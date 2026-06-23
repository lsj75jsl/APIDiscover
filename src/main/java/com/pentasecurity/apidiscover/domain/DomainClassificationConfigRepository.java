// 도메인별 분류 설정 override 리포지토리 (host PK)
package com.pentasecurity.apidiscover.domain;

import org.springframework.data.jpa.repository.JpaRepository;

public interface DomainClassificationConfigRepository extends JpaRepository<DomainClassificationConfig, String> {
}
