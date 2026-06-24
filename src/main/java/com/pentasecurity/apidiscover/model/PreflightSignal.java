// CORS preflight 결정신호(acrm) 가용성 — DiscoveryReport 노출 (doc/23 §9.5 M3)
package com.pentasecurity.apidiscover.model;

/**
 * acrm(Access-Control-Request-Method) 로깅 가용 여부. ACTIVE=acrm 관측됨(preflight↔genuine 결정 판정 가능),
 * DORMANT=미관측(현행 skip+M2). {@link SignalStatus} 재사용(doc/20). 항상 non-null({@link #NONE}=DORMANT).
 */
public record PreflightSignal(SignalStatus status, long acrmPresentOptions) {

    /** 신호 비가용 기본값 (DORMANT, acrm 0). */
    public static final PreflightSignal NONE = new PreflightSignal(SignalStatus.DORMANT, 0);
}
