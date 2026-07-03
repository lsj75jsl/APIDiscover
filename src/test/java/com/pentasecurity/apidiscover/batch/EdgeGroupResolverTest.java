// EdgeGroupResolver 단위 테스트 — 그룹 규칙 4유형·Master 선정 (D65, 사용자 규칙)
package com.pentasecurity.apidiscover.batch;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class EdgeGroupResolverTest {

    @Test
    void type1MastersAreMSuffixMembersIncludingAlnumSegments() {
        // M#=Master / S#=Slave(사용자 확정). 260DM0 처럼 문자 섞인 세그먼트도 Master(사용자 예시 260dM0).
        Map<String, String> m = EdgeGroupResolver.build(List.of(
                "AOC1-NRT-2509M0", "AOC1-NRT-2509S1", "AOC1-NRT-2509S2",
                "AOC1-NRT-260DM0",
                "POC9-XXX-0001S1"));                       // M 멤버 없는 그룹 → 자기 자신(보수적)
        assertThat(m.get("AOC1-NRT-2509S1")).isEqualTo("AOC1-NRT-2509M0");
        assertThat(m.get("AOC1-NRT-2509S2")).isEqualTo("AOC1-NRT-2509M0");
        assertThat(m.get("AOC1-NRT-2509M0")).isEqualTo("AOC1-NRT-2509M0");
        assertThat(m.get("AOC1-NRT-260DM0")).isEqualTo("AOC1-NRT-260DM0");
        assertThat(m.get("POC9-XXX-0001S1")).isEqualTo("POC9-XXX-0001S1");
    }

    @Test
    void type2SecondCharLAreAllSingleGroups() {
        Map<String, String> m = EdgeGroupResolver.build(List.of("ALI21", "ALKN1", "MLIZ1"));
        assertThat(m.get("ALI21")).isEqualTo("ALI21");
        assertThat(m.get("ALKN1")).isEqualTo("ALKN1");
        assertThat(m.get("MLIZ1")).isEqualTo("MLIZ1");
    }

    @Test
    void type3AaiGroupsByLastDigitMasterIsFirst() {
        // 그룹 = 5번째(마지막) 자리 → AAI13 ← AAI23, AAI33 (사용자 예시: AAI1x 만 조회)
        Map<String, String> m = EdgeGroupResolver.build(List.of("AAI13", "AAI23", "AAI33", "AAI14", "AAI24"));
        assertThat(m.get("AAI23")).isEqualTo("AAI13");
        assertThat(m.get("AAI33")).isEqualTo("AAI13");
        assertThat(m.get("AAI24")).isEqualTo("AAI14");
        assertThat(m.get("AAI13")).isEqualTo("AAI13");
    }

    @Test
    void type4GroupsByFirstFourCharsMasterIsFirst() {
        // 그룹 = 앞 4자리 → HAJ11 ← HAJ12, HAJ13. Master(첫 멤버) 부재 시 존재하는 첫 멤버(AAIP2).
        Map<String, String> m = EdgeGroupResolver.build(List.of(
                "HAJ11", "HAJ12", "HAJ13", "AAIP2", "AAIP4", "AAIP8", "AOSE1"));
        assertThat(m.get("HAJ12")).isEqualTo("HAJ11");
        assertThat(m.get("HAJ13")).isEqualTo("HAJ11");
        assertThat(m.get("AAIP4")).isEqualTo("AAIP2");   // AAIP1 없음 → 사전순 첫 멤버
        assertThat(m.get("AAIP8")).isEqualTo("AAIP2");
        assertThat(m.get("AOSE1")).isEqualTo("AOSE1");   // 단독 그룹
    }
}
