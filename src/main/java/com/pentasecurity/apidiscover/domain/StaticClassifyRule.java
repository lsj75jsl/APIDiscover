// 정적 파일 분류 규칙(확장자·파일명 토큰) DB 저장 — 관리자 편집·reload (D56)
package com.pentasecurity.apidiscover.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

/**
 * 정적 파일 분류 규칙 1건 — {@link StaticRuleKind}(확장자/파일명토큰) + 값. UNIQUE(kind, value).
 * EndpointKindClassifier 의 하드코드 대신 DB 로 외부화 → 관리자가 REST 로 추가/삭제 후 reload(D56).
 * ddl-auto ADD TABLE(무손실). 첫 기동 시 기본값 seed(StaticClassifyRules).
 */
@Entity
@Table(name = "static_classify_rule",
        uniqueConstraints = @UniqueConstraint(name = "uk_static_rule", columnNames = {"kind", "rule_value"}))
public class StaticClassifyRule {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(name = "kind", nullable = false, length = 20)
    private StaticRuleKind kind;

    // 'value' 는 일부 DB 예약어라 컬럼명 rule_value 사용(H2/PG 안전).
    @Column(name = "rule_value", nullable = false, length = 100)
    private String value;

    protected StaticClassifyRule() {
    }

    public StaticClassifyRule(StaticRuleKind kind, String value) {
        this.kind = kind;
        this.value = value;
    }

    public Long getId() {
        return id;
    }

    public StaticRuleKind getKind() {
        return kind;
    }

    public void setKind(StaticRuleKind kind) {
        this.kind = kind;
    }

    public String getValue() {
        return value;
    }

    public void setValue(String value) {
        this.value = value;
    }
}
