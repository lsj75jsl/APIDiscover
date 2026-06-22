// 수집 시간창 [from, to) (doc/05 §2.4, §3)
package com.pentasecurity.apidiscover.ingest;

import java.time.Instant;

public record LogWindow(Instant from, Instant to) {}
