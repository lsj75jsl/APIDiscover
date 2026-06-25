// 필드 포지션 교차검증 — 디스커버리 LogQL pattern 이 LogLineParser 상수와 일치(드리프트 차단, doc/30 §4/§8)
package com.pentasecurity.apidiscover.batch;

import static org.assertj.core.api.Assertions.assertThat;

import com.pentasecurity.apidiscover.parse.LogLineParser;
import org.junit.jupiter.api.Test;

class DomainDiscoveryFieldLayoutTest {

    @Test
    void parserConstantsMatchDoc02Layout() {
        assertThat(LogLineParser.DELIM).isEqualTo("^|^");
        assertThat(LogLineParser.F_HOST).isEqualTo(15);
        assertThat(LogLineParser.F_REAL_HOST).isEqualTo(16);
        assertThat(LogLineParser.F_REAL_HOST).isEqualTo(LogLineParser.F_HOST + 1); // host 바로 뒤 real_host
    }

    @Test
    void logqlPatternPlacesHostAndRealHostAtParserIndices() {
        String pattern = DomainDiscoveryService.buildPattern();
        // 인덱스 0..F_HOST-1 은 skip(<_>), F_HOST=<host>, F_HOST+1=<real_host>
        String[] fields = pattern.split(java.util.regex.Pattern.quote(LogLineParser.DELIM), -1);
        assertThat(fields[LogLineParser.F_HOST]).isEqualTo("<host>");
        assertThat(fields[LogLineParser.F_REAL_HOST]).isEqualTo("<real_host>");
        // host 앞은 정확히 F_HOST 개의 skip 필드
        for (int i = 0; i < LogLineParser.F_HOST; i++) {
            assertThat(fields[i]).as("field %d skip", i).isEqualTo("<_>");
        }
    }
}
