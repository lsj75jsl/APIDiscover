// DomainCsvWriter 단위 테스트 — 헤더·5 status·source 파생·RFC4180 이스케이프·discovered join 공란 (doc/31 §B2)
package com.pentasecurity.apidiscover.cli;

import static org.assertj.core.api.Assertions.assertThat;

import com.pentasecurity.apidiscover.domain.DiscoveredEndpointRecord;
import com.pentasecurity.apidiscover.model.Finding;
import com.pentasecurity.apidiscover.model.ParamCandidates;
import com.pentasecurity.apidiscover.model.ParamCandidates.PathParam;
import com.pentasecurity.apidiscover.model.ParamCandidates.QueryParam;
import com.pentasecurity.apidiscover.model.Severity;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class DomainCsvWriterTest {

    private static final String HOST = "api.example.com";

    @Test
    void headerIsFixed15Columns() {
        String csv = DomainCsvWriter.toCsv(List.of(), Map.of());
        String header = csv.split("\r\n", -1)[0];
        assertThat(header).isEqualTo("host,method,path_template,status,source,confidence,severity,"
                + "estimated,spec_ref,preflight_ambiguous,low_confidence,param_query,param_path,first_seen,last_seen");
        assertThat(csv).doesNotContain("score"); // score 범위 밖(헤더 미포함)
    }

    @Test
    void derivesStatusAndSourceForAllFiveSubtypes() {
        List<Finding> findings = List.of(
                new Finding.Shadow(HOST, "POST", "/a", 0.9, "r", ParamCandidates.EMPTY),
                new Finding.Zombie(HOST, "GET", "/b", 1.0, new Severity(0.7), false, "specB", "r",
                        ParamCandidates.EMPTY),
                new Finding.Active(HOST, "GET", "/c", "specC"),
                new Finding.Unused(HOST, "GET", "/d", "specD", true),
                new Finding.WebPage(HOST, "GET", "/e", 0.42));

        String csv = DomainCsvWriter.toCsv(findings, Map.of());
        Map<String, String[]> byPath = rowsByPath(csv);

        // status,source 파생 (정렬 무관 — path 로 조회)
        assertThat(byPath.get("/a")).contains("SHADOW", atIndex(3)).contains("detected", atIndex(4));
        assertThat(byPath.get("/b")[3]).isEqualTo("ZOMBIE");
        assertThat(byPath.get("/b")[4]).isEqualTo("both");
        assertThat(byPath.get("/b")[6]).isEqualTo("HIGH");   // severity band(0.7≥0.66)
        assertThat(byPath.get("/b")[7]).isEqualTo("false");  // estimated
        assertThat(byPath.get("/c")[4]).isEqualTo("both");   // Active
        assertThat(byPath.get("/d")[3]).isEqualTo("UNUSED");
        assertThat(byPath.get("/d")[4]).isEqualTo("spec");
        assertThat(byPath.get("/d")[9]).isEqualTo("true");   // preflight_ambiguous
        assertThat(byPath.get("/e")[3]).isEqualTo("UNDOCUMENTED_WEB_PAGE");
        assertThat(byPath.get("/e")[4]).isEqualTo("detected");
        assertThat(byPath.get("/e")[5]).isEqualTo("0.42");   // confidence=kindConfidence
    }

    @Test
    void joinsFirstLastSeenFromDiscoveredAndBlankForSpecOnly() {
        Finding shadow = new Finding.Shadow(HOST, "GET", "/seen", 0.9, "r", ParamCandidates.EMPTY);
        Finding unused = new Finding.Unused(HOST, "GET", "/unseen", "spec", false); // spec-only → 검출 없음

        DiscoveredEndpointRecord rec = new DiscoveredEndpointRecord();
        rec.setMethod("GET");
        rec.setHost(HOST);
        rec.setPathTemplate("/seen");
        rec.setFirstSeen(Instant.parse("2026-06-01T00:00:00Z"));
        rec.setLastSeen(Instant.parse("2026-06-25T00:00:00Z"));
        Map<String, DiscoveredEndpointRecord> bySig =
                Map.of(DomainCsvWriter.key("GET", HOST, "/seen"), rec);

        String csv = DomainCsvWriter.toCsv(List.of(shadow, unused), bySig);
        Map<String, String[]> byPath = rowsByPath(csv);

        assertThat(byPath.get("/seen")[13]).isEqualTo("2026-06-01T00:00:00Z");  // first_seen
        assertThat(byPath.get("/seen")[14]).isEqualTo("2026-06-25T00:00:00Z");  // last_seen
        assertThat(byPath.get("/unseen")[13]).isEmpty();                         // spec-only 공란
        assertThat(byPath.get("/unseen")[14]).isEmpty();
    }

    @Test
    void joinsParamNamesWithSemicolon() {
        ParamCandidates params = new ParamCandidates(
                List.of(new QueryParam("q1", 1, Set.of(), false), new QueryParam("q2", 1, Set.of(), false)),
                List.of(new PathParam(1, "{id}")));
        Finding shadow = new Finding.Shadow(HOST, "GET", "/p", 0.9, "r", params);

        String[] row = rowsByPath(DomainCsvWriter.toCsv(List.of(shadow), Map.of())).get("/p");
        assertThat(row[11]).isEqualTo("q1;q2");  // param_query
        assertThat(row[12]).isEqualTo("{id}");   // param_path
    }

    @Test
    void escapesRfc4180SpecialChars() {
        // path_template 에 콤마·따옴표·개행 → "..."로 감싸고 내부 "→""
        Finding f = new Finding.Shadow(HOST, "GET", "/a,b\"c\nd", 0.9, "r", ParamCandidates.EMPTY);
        String csv = DomainCsvWriter.toCsv(List.of(f), Map.of());
        // 원본 필드 /a,b"c\nd → 이스케이프 "/a,b""c\nd"
        assertThat(csv).contains("\"/a,b\"\"c\nd\"");
    }

    // --- helpers ---

    /** path_template(컬럼 2) → 필드배열. 이스케이프 케이스가 없는 정상 행 전용(테스트 입력 한정). */
    private static Map<String, String[]> rowsByPath(String csv) {
        String[] lines = csv.split("\r\n", -1);
        Map<String, String[]> out = new java.util.HashMap<>();
        for (int i = 1; i < lines.length; i++) {
            if (lines[i].isEmpty()) {
                continue;
            }
            String[] fields = lines[i].split(",", -1);
            out.put(fields[2], fields);
        }
        return out;
    }

    private static org.assertj.core.data.Index atIndex(int i) {
        return org.assertj.core.data.Index.atIndex(i);
    }
}
