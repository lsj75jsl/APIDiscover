// CsvSpecParser 단위 테스트 (doc/14 §2, §6)
package com.pentasecurity.apidiscover.spec;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.pentasecurity.apidiscover.model.CanonicalEndpoint;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;

class CsvSpecParserTest {

    private final CsvSpecParser parser = new CsvSpecParser();

    private List<CanonicalEndpoint> parse(String csv) {
        return parser.parse(csv.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void rejectsMissingRequiredHeaders() {
        // method/path 둘 다 없음 → fatal
        assertThatThrownBy(() -> parse("verb,route\nGET,/x\n"))
                .isInstanceOf(IllegalArgumentException.class);
        // path 만 없음 → fatal
        assertThatThrownBy(() -> parse("method,host\nGET,api.example.com\n"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void convertsRowsWithVarHostDeprecatedVersion() {
        String csv = """
                method,path,host,deprecated,version,description
                GET,/v2/users/:id,api.example.com,false,2.0.0,Get user
                POST,/v2/users,api.example.com,1,2.0.0,"Create, with comma"
                DELETE,/v2/users/:id,,no,2.0.0,
                """;
        List<CanonicalEndpoint> all = parse(csv);
        assertThat(all).hasSize(3);

        CanonicalEndpoint get = all.get(0);
        assertThat(get.method()).isEqualTo("GET");
        assertThat(get.pathTemplate()).isEqualTo("/v2/users/{id}"); // :id→{id}
        assertThat(get.host()).isEqualTo("api.example.com");
        assertThat(get.deprecated()).isFalse();
        assertThat(get.version()).isEqualTo("2.0.0");
        assertThat(get.sourceRef()).isEqualTo("csv#row1");

        CanonicalEndpoint post = all.get(1); // 내장 콤마 따옴표 → 컬럼 분리 안 됨
        assertThat(post.method()).isEqualTo("POST");
        assertThat(post.pathTemplate()).isEqualTo("/v2/users");
        assertThat(post.deprecated()).isTrue(); // "1"

        CanonicalEndpoint del = all.get(2);
        assertThat(del.host()).isNull();       // host 빈값 → null
        assertThat(del.deprecated()).isFalse(); // "no"
    }

    @Test
    void parsesAllDeprecatedTokensAndUnrecognized() {
        String csv = """
                method,path,deprecated
                GET,/a,true
                GET,/b,YES
                GET,/c,Y
                GET,/d,0
                GET,/e,n
                GET,/f,maybe
                """;
        List<CanonicalEndpoint> all = parse(csv);
        assertThat(all).extracting(CanonicalEndpoint::deprecated)
                .containsExactly(true, true, true, false, false, false); // maybe → 미인식 false
    }

    @Test
    void emptyDeprecatedCellIsFalse() {
        // P3-2: deprecated 셀 빈값 → false (blank 처리)
        String csv = """
                method,path,deprecated
                GET,/a,
                GET,/b,true
                """;
        assertThat(parse(csv)).extracting(CanonicalEndpoint::deprecated).containsExactly(false, true);
    }

    @Test
    void skipsBlankMethodOrPathRows() {
        String csv = """
                method,path,version
                GET,/ok,1
                ,/skipme,1
                POST,,1
                DELETE,/ok2,1
                """;
        List<CanonicalEndpoint> all = parse(csv);
        assertThat(all).extracting(CanonicalEndpoint::pathTemplate).containsExactly("/ok", "/ok2");
        // 물리 행 번호 보존(스킵 행도 카운트): row1, row4
        assertThat(all).extracting(CanonicalEndpoint::sourceRef).containsExactly("csv#row1", "csv#row4");
    }

    @Test
    void headerOnlyReturnsEmpty() {
        assertThat(parse("method,path,host\n")).isEmpty();
    }

    @Test
    void stripsBomAndParsesHeader() {
        String csv = "method,path\nGET,/users/:id\n";
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        bos.writeBytes(new byte[]{(byte) 0xEF, (byte) 0xBB, (byte) 0xBF}); // UTF-8 BOM
        bos.writeBytes(csv.getBytes(StandardCharsets.UTF_8));

        List<CanonicalEndpoint> all = parser.parse(bos.toByteArray());
        assertThat(all).singleElement().satisfies(e -> {
            assertThat(e.method()).isEqualTo("GET");
            assertThat(e.pathTemplate()).isEqualTo("/users/{id}"); // BOM 이 method 헤더 인식 깨지 않음
        });
    }
}
