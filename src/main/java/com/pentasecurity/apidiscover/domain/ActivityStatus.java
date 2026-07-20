// 도메인 활동 상태 — 실요청 유무로 자동 관리(스캔 대상 게이트). 사용자 수동 설정 enabled 와 별도 축 (doc/43 §4.3, D82)
package com.pentasecurity.apidiscover.domain;

/**
 * 도메인의 활동 상태(주기 스캔 대상 게이트). {@code enabled}(사용자 수동 토글, 자동변경 금지 doc/30 §5) 와 <b>별도 축</b>이며,
 * "활성만 스캔" = {@code enabled=true AND activityStatus=ACTIVE}.
 *
 * <p>단일 진실원: {@code lastSeenAt}(discovery 실요청 관측, 입력) → sweep → {@code activityStatus}(결정) → scan 게이트가 읽음.
 * 전이: (i) discovery 틱 종료 sweep 이 {@code lastSeenAt < now−inactive-after} 를 INACTIVE 로,
 * (ii) discovery 실요청 재관측(upsert)·(iii) 수동 API 스캔이 ACTIVE 로.
 */
public enum ActivityStatus {
    /** 최근(inactive-after 이내) 실요청 관측됨 또는 수동 승격 — 주기 스캔 대상. */
    ACTIVE,
    /** inactive-after 기간 무요청 — 주기 스캔 제외(실요청/수동 스캔 시 자동 ACTIVE 복귀). */
    INACTIVE
}
