// 수집 시간창 [from, to) (doc/05 §2.4, §3)
package com.pentasecurity.apidiscover.ingest;

import java.time.Instant;

public record LogWindow(Instant from, Instant to) {
    // record — 필드는 헤더 괄호에 선언, 본문 비움이 정상(순수 데이터, dead 아님).
}
