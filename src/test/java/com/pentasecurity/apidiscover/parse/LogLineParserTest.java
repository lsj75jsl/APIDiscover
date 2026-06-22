// LogLineParser 단위 테스트 (doc/02 §5 예시 기반)
package com.pentasecurity.apidiscover.parse;

import static org.assertj.core.api.Assertions.assertThat;

import com.pentasecurity.apidiscover.model.ParsedRequest;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class LogLineParserTest {

    private final LogLineParser parser = new LogLineParser();

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
        assertThat(r.queryKeys()).containsExactly("expand");
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
