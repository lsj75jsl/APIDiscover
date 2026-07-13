// 로그 파싱 설정 — 선택적 필드 인덱스 (doc/23 §9 M3 acrm·doc/40 §6 8.3 신규 신호, "있으면 읽는")
package com.pentasecurity.apidiscover.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * {@code apidiscover.parse.*} 바인딩(D12 정적 인프라). 모든 인덱스 기본 -1=미사용 →
 * 파서가 해당 필드 안 읽음 → 전 신호 null/부재 → 게이트·점수 DORMANT → 현행 100%(무회귀, doc/23 §9.2·doc/40 §4).
 * org 가 nginx log_format 에 필드 추가 시 그 인덱스로 설정(8.3 append: 24/26/27/28/29/30).
 *
 * <p>★명명 주의(doc/40 §6): {@code responseContentTypeFieldIndex}(응답 $sent_http_content_type, idx 24)는
 * 미소비 요청측 {@code $content_type}(idx 25)과 다르다 — 운영자가 25 를 잘못 세팅하지 않도록 이름을 명시.
 */
@ConfigurationProperties(prefix = "apidiscover.parse")
public record ParseProperties(
        @DefaultValue("-1") int acrmFieldIndex,                  // idx 28 $http_access_control_request_method (M3)
        @DefaultValue("-1") int responseContentTypeFieldIndex,   // idx 24 $sent_http_content_type → endpoint_kind
        @DefaultValue("-1") int acceptFieldIndex,                // idx 26 $http_accept → acceptJson
        @DefaultValue("-1") int xRequestedWithFieldIndex,        // idx 27 $http_x_requested_with → xRequestedWith
        @DefaultValue("-1") int originFieldIndex,                // idx 29 $http_origin → originHeader
        @DefaultValue("-1") int authSchemeFieldIndex             // idx 30 $auth_scheme → authScheme
) {
    // ★생성자는 canonical 하나만 유지 — @ConfigurationProperties 레코드 바인딩은 생성자 1개 전제(복수면 "No default
    //   constructor" 로 컨텍스트 실패). acrm 만 지정하려면 acrmOnly(int) 팩터리 사용(테스트/편의).

    /** 테스트/기본 인스턴스 (yml 미바인딩 컨텍스트용). 전 인덱스 -1=미사용. */
    public static ParseProperties defaults() {
        return new ParseProperties(-1, -1, -1, -1, -1, -1);
    }

    /** acrm 인덱스만 지정, 8.3 신규는 -1(dormant) — ACRM 테스트/편의 팩터리. */
    public static ParseProperties acrmOnly(int acrmFieldIndex) {
        return new ParseProperties(acrmFieldIndex, -1, -1, -1, -1, -1);
    }
}
