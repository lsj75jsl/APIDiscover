// (선택) 스캔 완료 시 중앙에 신선도 신호 push (doc/07 §6)
package com.pentasecurity.apidiscover.central;

import org.springframework.stereotype.Component;

@Component
public class CentralWebhookClient {

    // TODO(doc/07 §6): 스캔 완료 시 POST {central}/workers/{id}/scan-events
    //   { host, version, lastScanAt, summary } — 요약만 push, 데이터는 조건부 pull.
    public void notifyScanCompleted(String host, String version) {
        // no-op (스캐폴딩)
    }
}
