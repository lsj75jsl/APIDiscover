// referer 기반 web_page 보조 신호의 내부 corpus (PAGE_URLS + 커버리지) — classify 입력 (doc/20 §1·§2)
package com.pentasecurity.apidiscover.model;

import java.util.Map;

/**
 * corpus pre-pass 결과. {@code pageUrls} = 정적 자식의 부모 페이지 URL(정규화 template) → 자식 수.
 * EndpointKindClassifier 가 UNKNOWN endpoint 의 보조 양성 판정에 사용(내부). 노출은 {@link EndpointKindSignal}.
 */
public record RefererSignal(SignalStatus status, Map<String, Long> pageUrls,
                            double staticRatio, double refererPresentRatio) {

    /** 신호 비가용(커버리지 미달/요청 없음) — 전 endpoint 현행 UNKNOWN 유지. */
    public static RefererSignal dormant() {
        return new RefererSignal(SignalStatus.DORMANT, Map.of(), 0.0, 0.0);
    }

    public boolean active() {
        return status == SignalStatus.ACTIVE;
    }
}
