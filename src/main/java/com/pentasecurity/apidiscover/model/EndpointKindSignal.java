// endpoint_kind referer 보조 신호의 노출 메트릭 (pageUrls 제외) — DiscoveryReport top-level (doc/20 §5)
package com.pentasecurity.apidiscover.model;

/**
 * referer 보조 신호의 환경 커버리지 노출(운영자 가시성). 항상 non-null({@link #NONE}=DORMANT/0).
 * 게이트 dropped 메트릭과 같은 형제 record 패턴.
 */
public record EndpointKindSignal(SignalStatus status, double staticRatio, double refererPresentRatio) {

    /** 신호 비가용 기본값 (DORMANT). */
    public static final EndpointKindSignal NONE = new EndpointKindSignal(SignalStatus.DORMANT, 0.0, 0.0);
}
