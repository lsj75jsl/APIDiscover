// 분류 결과 + non_api dropped 메트릭 + preflight 신호 (doc/12 §1, doc/23 §9). classifyWithMetrics 반환 타입
package com.pentasecurity.apidiscover.classify;

import com.pentasecurity.apidiscover.model.DroppedNonApi;
import com.pentasecurity.apidiscover.model.EndpointObservation;
import com.pentasecurity.apidiscover.model.Finding;
import com.pentasecurity.apidiscover.model.PreflightSignal;
import java.util.List;
import java.util.Map;

/** observedTimes = spec 매칭 endpoint(specKey)별 관측 구간 — 이력 merge 입력 (doc/24 §7). */
public record ClassificationResult(List<Finding> findings, DroppedNonApi dropped,
                                   PreflightSignal preflightSignal,
                                   Map<String, EndpointObservation> observedTimes) {

    /** 하위호환 3-arg — observedTimes 기본 빈 map (doc/24, 이력 미배선 경로). */
    public ClassificationResult(List<Finding> findings, DroppedNonApi dropped, PreflightSignal preflightSignal) {
        this(findings, dropped, preflightSignal, Map.of());
    }
}
