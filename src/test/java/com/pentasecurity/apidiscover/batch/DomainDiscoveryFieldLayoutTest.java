// 필드 포지션 교차검증 — 디스커버리 LogQL pattern 이 LogLineParser 상수와 일치(드리프트 차단, doc/30 §4/§8)
package com.pentasecurity.apidiscover.batch;

import static org.assertj.core.api.Assertions.assertThat;

import com.pentasecurity.apidiscover.parse.LogLineParser;
import org.junit.jupiter.api.Test;

class DomainDiscoveryFieldLayoutTest {

    @Test
    void parserConstantsMatchDoc02Layout() {
        assertThat(LogLineParser.DELIM).isEqualTo("^|^");
        assertThat(LogLineParser.F_STATUS).isEqualTo(9);   // C(doc/42 §4.4) status 필터 위치
        assertThat(LogLineParser.F_HOST).isEqualTo(15);
        assertThat(LogLineParser.F_REAL_HOST).isEqualTo(16);
        assertThat(LogLineParser.F_REAL_HOST).isEqualTo(LogLineParser.F_HOST + 1); // host 바로 뒤 real_host
    }

    @Test
    void logqlPatternPlacesHostRealHostAndStatusAtParserIndices() {
        String pattern = DomainDiscoveryService.buildPattern();
        // 인덱스 0..F_HOST-1 은 skip(<_>), 단 F_STATUS=<status>(C 필터용), F_HOST=<host>, F_HOST+1=<real_host>
        String[] fields = pattern.split(java.util.regex.Pattern.quote(LogLineParser.DELIM), -1);
        assertThat(fields[LogLineParser.F_HOST]).isEqualTo("<host>");
        assertThat(fields[LogLineParser.F_REAL_HOST]).isEqualTo("<real_host>");
        assertThat(fields[LogLineParser.F_STATUS]).isEqualTo("<status>"); // status 필터가 참조
        assertThat(fields[LogLineParser.F_REQUEST_URI]).isEqualTo("<uri>"); // D82: 경로제외 필터가 참조
        // host 앞은 F_STATUS=<status>·F_REQUEST_URI=<uri>, 나머지는 skip
        for (int i = 0; i < LogLineParser.F_HOST; i++) {
            String expected = (i == LogLineParser.F_STATUS) ? "<status>"
                    : (i == LogLineParser.F_REQUEST_URI) ? "<uri>" : "<_>";
            assertThat(fields[i]).as("field %d", i).isEqualTo(expected);
        }
    }
}
