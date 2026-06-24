// EndpointHistory 리포지토리 (PK=host, 도메인당 1행)
package com.pentasecurity.apidiscover.domain;

import org.springframework.data.jpa.repository.JpaRepository;

public interface EndpointHistoryRepository extends JpaRepository<EndpointHistory, String> {
}
