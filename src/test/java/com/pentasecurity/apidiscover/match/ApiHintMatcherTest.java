// ApiHintMatcher 단위 테스트 — prefix 경계/regex full-match/상한/ReDoS (doc/09 §3, §6)
package com.pentasecurity.apidiscover.match;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertTimeout;

import com.pentasecurity.apidiscover.model.MatcherConfig;
import java.time.Duration;
import java.util.List;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

class ApiHintMatcherTest {

    private static ApiHintMatcher matcher(List<String> apiPrefixes, List<String> apiRegexes,
                                          List<String> excludePrefixes, List<String> excludeRegexes) {
        return new ApiHintMatcher(new MatcherConfig(
                apiPrefixes, apiRegexes, excludePrefixes, excludeRegexes, Boolean.FALSE));
    }

    private static List<String> nPrefixes(int n) {
        return IntStream.range(0, n).mapToObj(i -> "/p" + i).toList();
    }

    private static List<String> nRegexes(int n) {
        return IntStream.range(0, n).mapToObj(i -> "/r" + i).toList();
    }

    @Test
    void prefixMatchesOnSegmentBoundary() {
        var m = matcher(List.of("/api"), List.of(), List.of(), List.of());
        assertThat(m.apiHinted("/api")).isTrue();          // path == prefix
        assertThat(m.apiHinted("/api/users")).isTrue();    // prefix + "/"
        assertThat(m.apiHinted("/apidocs")).isFalse();     // 세그먼트 경계 아님
        assertThat(m.apiHinted("/other")).isFalse();
    }

    @Test
    void regexIsFullMatchAnchored() {
        var m = matcher(List.of(), List.of("/svc/[a-z]+/data"), List.of(), List.of());
        assertThat(m.apiHinted("/svc/orders/data")).isTrue();
        assertThat(m.apiHinted("/svc/orders/data/extra")).isFalse(); // full-match → 부분매칭 불가
        assertThat(m.apiHinted("/prefix/svc/orders/data")).isFalse();
    }

    @Test
    void excludePrefixAndRegexMatch() {
        var m = matcher(List.of(), List.of(), List.of("/legacy"), List.of(".*\\.(js|css|map)"));
        assertThat(m.excluded("/legacy")).isTrue();
        assertThat(m.excluded("/legacy/v1")).isTrue();
        assertThat(m.excluded("/assets/app.js")).isTrue();
        assertThat(m.excluded("/api/users")).isFalse();
    }

    @Test
    void excludeOnlyIsNotExplicitHintMode() {
        var m = matcher(List.of(), List.of(), List.of("/legacy"), List.of());
        assertThat(m.isExplicitHintMode()).isFalse();
        assertThat(m.excluded("/legacy/x")).isTrue();
    }

    @Test
    void apiHintEnablesExplicitHintMode() {
        assertThat(matcher(List.of("/api"), List.of(), List.of(), List.of()).isExplicitHintMode()).isTrue();
        assertThat(matcher(List.of(), List.of("/x"), List.of(), List.of()).isExplicitHintMode()).isTrue();
    }

    // --- 상한 경계 (정확히 상한값=통과, 상한+1=throw) (P3-3, §3.1) ---

    @Test
    void prefixCountBoundary() {
        assertThatCode(() -> matcher(nPrefixes(ApiHintMatcher.MAX_PREFIX_COUNT), List.of(), List.of(), List.of()))
                .doesNotThrowAnyException(); // 정확히 200 → 통과
        assertThatThrownBy(() -> matcher(nPrefixes(ApiHintMatcher.MAX_PREFIX_COUNT + 1), List.of(), List.of(), List.of()))
                .isInstanceOf(IllegalArgumentException.class); // 201 → throw
    }

    @Test
    void regexCountBoundary() {
        assertThatCode(() -> matcher(List.of(), nRegexes(ApiHintMatcher.MAX_REGEX_COUNT), List.of(), List.of()))
                .doesNotThrowAnyException(); // 정확히 50 → 통과
        assertThatThrownBy(() -> matcher(List.of(), nRegexes(ApiHintMatcher.MAX_REGEX_COUNT + 1), List.of(), List.of()))
                .isInstanceOf(IllegalArgumentException.class); // 51 → throw
    }

    @Test
    void regexLengthBoundary() {
        String at = "a".repeat(ApiHintMatcher.MAX_REGEX_LEN);          // 200
        String over = "a".repeat(ApiHintMatcher.MAX_REGEX_LEN + 1);     // 201
        assertThatCode(() -> matcher(List.of(), List.of(at), List.of(), List.of()))
                .doesNotThrowAnyException();
        assertThatThrownBy(() -> matcher(List.of(), List.of(over), List.of(), List.of()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void prefixLengthBoundary() {
        String at = "/" + "a".repeat(ApiHintMatcher.MAX_PREFIX_LEN - 1);  // 256
        String over = "/" + "a".repeat(ApiHintMatcher.MAX_PREFIX_LEN);     // 257
        assertThatCode(() -> matcher(List.of(at), List.of(), List.of(), List.of()))
                .doesNotThrowAnyException();
        assertThatThrownBy(() -> matcher(List.of(over), List.of(), List.of(), List.of()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // --- 내용 검증 (비공백·'/'시작, P2/P3-2, §3.1) ---

    @Test
    void blankApiPrefixThrows() {
        // 빈/공백 prefix → startsWith 로 전 경로 match-all 위험 (P2)
        assertThatThrownBy(() -> matcher(List.of(""), List.of(), List.of(), List.of()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> matcher(List.of("   "), List.of(), List.of(), List.of()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void blankExcludePrefixThrows() {
        // 빈/공백 exclude prefix → drop-all 위험 (P2)
        assertThatThrownBy(() -> matcher(List.of(), List.of(), List.of(""), List.of()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void nonSlashPrefixThrowsWithPatternInMessage() {
        assertThatThrownBy(() -> matcher(List.of("api"), List.of(), List.of(), List.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("api"); // '/' 미시작 (P3-2)
    }

    @Test
    void blankRegexThrows() {
        assertThatThrownBy(() -> matcher(List.of(), List.of("   "), List.of(), List.of()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void invalidRegexThrowsWithPatternInMessage() {
        assertThatThrownBy(() -> matcher(List.of(), List.of("(unclosed"), List.of(), List.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("(unclosed"); // 조용히 skip 금지(§3.1)
    }

    // --- ReDoS deadline (P3-1) ---

    @Test
    void redosDeadlineTruncatesCatastrophicMatching() {
        // JDK21 은 고전 `(a+)+$`/`(.+)+X` 류를 비파국적으로 최적화한다(probe 확인). 특정 패턴 의존을 줄이려
        // 여전히 파국적인 `(.*X){1,N}` 족 여러 개로 deadline 메커니즘 자체를 검증한다(패턴이 매치되는지가
        // 아니라 deadline 동작이 핵심): (1) no-match fallback, (2) timeoutCount 증가,
        // (3) unguarded 면 수 초~분 걸릴 입력이 50ms deadline 로 truncate 되어 bounded 시간 내 종료.
        record Redos(String regex, String input) {}
        List<Redos> cases = List.of(
                new Redos("(.*a){1,30}", "a".repeat(34) + "X"),
                new Redos("(.*b){1,30}", "b".repeat(34) + "X"),
                new Redos("(.*/){1,30}", "/".repeat(34) + "X"));
        for (Redos c : cases) {
            var m = matcher(List.of(), List.of(c.regex()), List.of(), List.of());
            Boolean matched = assertTimeout(Duration.ofSeconds(2), () -> m.apiHinted(c.input()));
            assertThat(matched).as("no-match fallback: %s", c.regex()).isFalse();
            assertThat(m.timeoutCount()).as("deadline fired: %s", c.regex()).isPositive();
        }
    }

    @Test
    void excludeRegexReDosFallsBackToNotExcluded() {
        // exclude regex timeout → 미제외(안전 fallback): 스캔을 죽이지 않고 score 게이트로 (§3.2, P3-4)
        var m = matcher(List.of(), List.of(), List.of(), List.of("(.*a){1,30}"));
        String evil = "a".repeat(34) + "X";
        Boolean excluded = assertTimeout(Duration.ofSeconds(2), () -> m.excluded(evil));
        assertThat(excluded).isFalse();
        assertThat(m.timeoutCount()).isPositive();
    }

    @Test
    void oversizedInputSkipsRegexAsNoMatchAndCounts() {
        var m = matcher(List.of(), List.of(".*needle.*"), List.of(), List.of());
        String big = "/" + "x".repeat(ApiHintMatcher.MAX_INPUT_LEN + 10);
        assertThat(m.apiHinted(big)).isFalse();
        assertThat(m.timeoutCount()).isEqualTo(1);
    }

    @Test
    void noneMatchesNothingAndIncludesWebForms() {
        assertThat(ApiHintMatcher.NONE.isExplicitHintMode()).isFalse();
        assertThat(ApiHintMatcher.NONE.apiHinted("/api/x")).isFalse();
        assertThat(ApiHintMatcher.NONE.excluded("/api/x")).isFalse();
        assertThat(ApiHintMatcher.NONE.includeWebForms()).isTrue();
        assertThat(ApiHintMatcher.NONE.timeoutCount()).isZero();
    }

    @Test
    void nullTemplateIsNeverMatched() {
        var m = matcher(List.of("/api"), List.of(".*"), List.of("/api"), List.of(".*"));
        assertThat(m.apiHinted(null)).isFalse();
        assertThat(m.excluded(null)).isFalse();
    }
}
