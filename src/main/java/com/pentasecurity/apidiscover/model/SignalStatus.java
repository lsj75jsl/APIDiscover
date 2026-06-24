// endpoint_kind referer 보조 신호의 환경 가용성 상태 (doc/20 §2)
package com.pentasecurity.apidiscover.model;

public enum SignalStatus {
    ACTIVE,   // 정적 자원+referer 커버리지 충분 → 보조 신호 사용
    DORMANT   // 커버리지 미달/요청 없음 → 신호 미사용(전 endpoint 현행)
}
