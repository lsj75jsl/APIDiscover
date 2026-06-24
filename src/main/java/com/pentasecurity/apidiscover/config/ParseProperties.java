// 로그 파싱 설정 — 선택적 필드 인덱스 (doc/23 §9 M3, acrm 결정신호 "있으면 읽는")
package com.pentasecurity.apidiscover.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * {@code apidiscover.parse.*} 바인딩(D12 정적 인프라). {@code acrmFieldIndex} 기본 -1=미사용 →
 * 파서가 acrm 안 읽음 → 전 acrm null → preflight 게이트 자동 DORMANT → 현행 100%(무회귀, doc/23 §9.2).
 * org 가 nginx log_format 에 {@code $http_access_control_request_method} 추가 시 그 인덱스로 설정.
 */
@ConfigurationProperties(prefix = "apidiscover.parse")
public record ParseProperties(
        @DefaultValue("-1") int acrmFieldIndex
) {

    /** 테스트/기본 인스턴스 (yml 미바인딩 컨텍스트용). 기본 -1=미사용. */
    public static ParseProperties defaults() {
        return new ParseProperties(-1);
    }
}
