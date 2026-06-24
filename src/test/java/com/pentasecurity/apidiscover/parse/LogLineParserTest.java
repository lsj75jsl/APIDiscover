// LogLineParser 단위 테스트 (doc/02 §5 예시 기반)
package com.pentasecurity.apidiscover.parse;

import static org.assertj.core.api.Assertions.assertThat;

import com.pentasecurity.apidiscover.config.NormalizationProperties;
import com.pentasecurity.apidiscover.config.ParseProperties;
import com.pentasecurity.apidiscover.model.ParsedRequest;
import com.pentasecurity.apidiscover.model.QueryParamObs;
import com.pentasecurity.apidiscover.model.ValueLenBucket;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class LogLineParserTest {

    private final LogLineParser parser =
            new LogLineParser(NormalizationProperties.defaults(), ParseProperties.defaults());

    private static final String SAMPLE = String.join("^|^", List.of(
            "203.0.113.5",                              // 1 client_real_ip
            "10.0.0.2",                                 // 2 remote_addr
            "51514",                                    // 3 remote_port
            "2026-06-22T09:00:00+09:00",                // 4 time_iso8601
            "MISS",                                     // 5 upstream_cache_status
            "GET /users/12345?expand=orders HTTP/1.1",  // 6 request
            "OK",                                       // 7 request_completion
            "0.035",                                    // 8 response_time
            "/users/12345?expand=orders",               // 9 request_uri
            "200",                                      // 10 status
            "812",                                      // 11 body_bytes_sent
            "99",                                       // 12 connection
            "on",                                       // 13 https
            "-",                                        // 14 referer
            "okhttp/4.9",                               // 15 user_agent
            "api.example.com",                          // 16 host
            "api.example.com",                          // 17 real_host
            "10.0.0.10",                                // 18 server_addr
            "443",                                      // 19 server_port
            "api"));                                    // 20 type

    @Test
    void parsesAllRelevantFields() {
        ParsedRequest r = parser.parse(SAMPLE).orElseThrow();

        assertThat(r.method()).isEqualTo("GET");
        assertThat(r.rawPath()).isEqualTo("/users/12345");
        // queryParams: 이름 + 값 길이 버킷("orders"=6자→S). 값 자체는 미저장 (doc/13 §2.1)
        assertThat(r.queryParams()).containsExactly(new QueryParamObs("expand", ValueLenBucket.S));
        assertThat(r.status()).isEqualTo(200);
        assertThat(r.host()).isEqualTo("api.example.com");
        assertThat(r.clientIp()).isEqualTo("203.0.113.5");
        assertThat(r.userAgent()).isEqualTo("okhttp/4.9");
        assertThat(r.bodyBytes()).isEqualTo(812L);
        assertThat(r.respTimeMs()).isEqualTo(35L);
        assertThat(r.https()).isTrue();
        assertThat(r.ts()).isEqualTo(OffsetDateTime.parse("2026-06-22T09:00:00+09:00").toInstant());
        // 20필드 라인: type=field19("api"), referer="-"→null, requestId 없음→null
        assertThat(r.type()).isEqualTo("api");
        assertThat(r.referer()).isNull();
        assertThat(r.requestId()).isNull();
        assertThat(r.acrm()).isNull(); // 기본 parse.acrm-field-index=-1 → 미사용 (doc/23 §9.2)
    }

    @Test
    void readsAcrmAtConfiguredIndexWhenPresentElseNull() {
        // doc/23 M3: "있으면 읽는" — 설정 인덱스에 필드 존재 시 읽고, "-"/부재면 null
        var p = new LogLineParser(NormalizationProperties.defaults(), new ParseProperties(20));
        assertThat(p.parse(SAMPLE + "^|^GET").orElseThrow().acrm()).isEqualTo("GET"); // idx20 존재
        assertThat(p.parse(SAMPLE + "^|^-").orElseThrow().acrm()).isNull();            // "-" → null
        assertThat(p.parse(SAMPLE).orElseThrow().acrm()).isNull();                     // idx20 부재(20필드) → null
    }

    @Test
    void parses24FieldRealFormat() {
        // 실로그(24필드): type=document, referer 존재, requestId 32hex (doc/02 §1)
        String real = String.join("^|^", List.of(
                "218.152.121.177", "218.152.121.177", "64227", "2026-06-22T04:00:00+00:00", "-",
                "GET /modal/modal.mypage.quickinfo2 HTTP/2.0", "OK", "0.581",
                "/modal/modal.mypage.quickinfo2", "200", "138", "10356022", "on",
                "https://www.computer.co.kr/mypage/orderlist", "Mozilla/5.0", "www.computer.co.kr",
                "computer.co.kr", "50.0.255.248", "443", "document",
                "KR", "0", "0", "a6b21dd580721fc452087d8c2ad7135a"));

        ParsedRequest r = parser.parse(real).orElseThrow();

        assertThat(r.method()).isEqualTo("GET");
        assertThat(r.rawPath()).isEqualTo("/modal/modal.mypage.quickinfo2");
        assertThat(r.status()).isEqualTo(200);
        assertThat(r.host()).isEqualTo("www.computer.co.kr");
        assertThat(r.type()).isEqualTo("document");
        assertThat(r.referer()).isEqualTo("https://www.computer.co.kr/mypage/orderlist");
        assertThat(r.requestId()).isEqualTo("a6b21dd580721fc452087d8c2ad7135a");
    }

    @Test
    void queryParamValuesAreBucketedNotStored() {
        // q=foo(3→S), token=<33자 secret>(L), big=<129자>(XL), flag(값없음→NONE)
        String secret33 = "s".repeat(33);
        String big129 = "b".repeat(129);
        String uri = "/search?q=foo&token=" + secret33 + "&big=" + big129 + "&flag";
        String line = SAMPLE.replace("/users/12345?expand=orders", uri);

        ParsedRequest r = parser.parse(line).orElseThrow();

        assertThat(r.rawPath()).isEqualTo("/search");
        assertThat(r.queryParams()).containsExactly(
                new QueryParamObs("q", ValueLenBucket.S),
                new QueryParamObs("token", ValueLenBucket.L),
                new QueryParamObs("big", ValueLenBucket.XL),
                new QueryParamObs("flag", ValueLenBucket.NONE));
        // 값 미저장: 어떤 관측에도 원본 값 문자열이 보존되지 않음(record 구조상 name+bucket 뿐)
        assertThat(r.queryParams().toString()).doesNotContain(secret33).doesNotContain(big129).doesNotContain("foo");
    }

    @Test
    void rejectsNonHttpMethod() {
        String bad = SAMPLE.replace("GET /users/12345?expand=orders HTTP/1.1", "GARBAGE line");
        assertThat(parser.parse(bad)).isEmpty();
    }

    @Test
    void rejectsTooFewFields() {
        assertThat(parser.parse("only^|^three^|^fields")).isEmpty();
    }

    @Test
    void rejectsBlankLine() {
        assertThat(parser.parse("")).isEqualTo(Optional.empty());
    }
}
