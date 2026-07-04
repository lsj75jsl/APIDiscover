// EdgeExclusions 단위 테스트 — 정확 일치(D62) + 'X*' 접두 와일드카드(D69)
package com.pentasecurity.apidiscover.batch;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class EdgeExclusionsTest {

    @Test
    void exactAndPrefixMatching() {
        EdgeExclusions ex = new EdgeExclusions(List.of("AAJ11", "P*"));
        assertThat(ex.contains("AAJ11")).isTrue();   // 정확 일치(D62)
        assertThat(ex.contains("AAJ12")).isFalse();  // 목록 밖
        assertThat(ex.contains("PAI13")).isTrue();   // P* 접두(D69)
        assertThat(ex.contains("PAIP8")).isTrue();
        assertThat(ex.contains("API13")).isFalse();  // A 시작 — P 접두 아님
        assertThat(ex.contains(null)).isFalse();
    }

    @Test
    void nullBlankAndEmptyEntriesAreIgnored() {
        assertThat(new EdgeExclusions(null).contains("PAI13")).isFalse();
        EdgeExclusions ex = new EdgeExclusions(java.util.Arrays.asList(null, " ", ""));
        assertThat(ex.contains("PAI13")).isFalse();
        assertThat(ex.contains("")).isFalse();
    }
}
