// 분류 결과 + non_api dropped 메트릭 (doc/12 §1). classifyWithMetrics 반환 타입
package com.pentasecurity.apidiscover.classify;

import com.pentasecurity.apidiscover.model.DroppedNonApi;
import com.pentasecurity.apidiscover.model.Finding;
import java.util.List;

public record ClassificationResult(List<Finding> findings, DroppedNonApi dropped) {
}
