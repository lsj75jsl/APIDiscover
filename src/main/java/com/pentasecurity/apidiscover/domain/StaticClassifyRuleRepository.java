// 정적 분류 규칙 리포지토리 (D56)
package com.pentasecurity.apidiscover.domain;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.transaction.annotation.Transactional;

public interface StaticClassifyRuleRepository extends JpaRepository<StaticClassifyRule, Long> {

    List<StaticClassifyRule> findByKind(StaticRuleKind kind);

    boolean existsByKindAndValue(StaticRuleKind kind, String value);

    @Transactional
    @Modifying
    long deleteByKindAndValue(StaticRuleKind kind, String value);
}
