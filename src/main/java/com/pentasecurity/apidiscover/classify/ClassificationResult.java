// 분류 결과 + non_api dropped 메트릭 + preflight 신호 (doc/12 §1, doc/23 §9). classifyWithMetrics 반환 타입
package com.pentasecurity.apidiscover.classify;

import com.pentasecurity.apidiscover.model.DroppedNonApi;
import com.pentasecurity.apidiscover.model.Finding;
import com.pentasecurity.apidiscover.model.PreflightSignal;
import java.util.List;

public record ClassificationResult(List<Finding> findings, DroppedNonApi dropped,
                                   PreflightSignal preflightSignal) {
}
