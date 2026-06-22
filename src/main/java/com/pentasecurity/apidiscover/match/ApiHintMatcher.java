// explicit-hint/exclude 매처 — 세그먼트경계 prefix·full-match regex·ReDoS deadline 방어 (doc/09 §3)
package com.pentasecurity.apidiscover.match;

import com.pentasecurity.apidiscover.model.MatcherConfig;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * effective {@link MatcherConfig} 로 1회 생성되며 상한 검증·regex 컴파일을 수행한다(= compile 캐시).
 * 설정 변경 = 새 인스턴스 = 자연 무효화.
 *
 * <ul>
 *   <li>prefix: 세그먼트 경계 매칭({@code path == prefix} 또는 {@code path.startsWith(prefix + "/")}). (§1)</li>
 *   <li>regex: full-match({@code matches()}), spec EndpointMatcher 와 동일 앵커 정책. (§1)</li>
 *   <li>ReDoS: 매칭당 deadline(50ms) interruptible CharSequence, 초과 시 해당 regex no-match + 카운터. (§3.2)</li>
 * </ul>
 */
public final class ApiHintMatcher {

    private static final Logger log = LoggerFactory.getLogger(ApiHintMatcher.class);

    // 상한 (doc/09 §3.1) — 위반 시 fail-fast (후속 중앙 API 에선 400)
    static final int MAX_PREFIX_COUNT = 200;
    static final int MAX_REGEX_COUNT = 50;
    static final int MAX_REGEX_LEN = 200;
    static final int MAX_PREFIX_LEN = 256;

    // ReDoS 방어 (doc/09 §3.2)
    static final long MATCH_BUDGET_NANOS = 50_000_000L; // 50ms
    static final int MAX_INPUT_LEN = 4096;

    /** 빈 설정 + includeWebForms=true. 레거시 오버로드·미배선 파이프라인용(현행 동작 불변). */
    public static final ApiHintMatcher NONE = new ApiHintMatcher(MatcherConfig.NONE);

    private final List<String> apiPrefixes;
    private final List<String> excludePrefixes;
    private final List<CompiledRegex> apiRegexes;
    private final List<CompiledRegex> excludeRegexes;
    private final boolean includeWebForms;
    private final boolean explicitHintMode;

    private final AtomicLong timeoutCount = new AtomicLong();
    private final java.util.Set<String> warned = ConcurrentHashMap.newKeySet();

    public ApiHintMatcher(MatcherConfig cfg) {
        Objects.requireNonNull(cfg, "cfg");
        this.apiPrefixes = validatePrefixes(cfg.apiPathPrefixes(), "apiPathPrefixes");
        this.excludePrefixes = validatePrefixes(cfg.excludePathPrefixes(), "excludePathPrefixes");
        this.apiRegexes = compileRegexes(cfg.apiPathRegexes(), "apiPathRegexes");
        this.excludeRegexes = compileRegexes(cfg.excludePathRegexes(), "excludePathRegexes");
        this.includeWebForms = Boolean.TRUE.equals(cfg.includeWebForms());
        // 힌트가 하나라도 설정되면 explicit-hint 모드(내장 path-shape 비활성, §2.3). exclude-only 는 false.
        this.explicitHintMode = !apiPrefixes.isEmpty() || !apiRegexes.isEmpty();
    }

    /** 힌트(api prefix/regex)가 하나라도 설정됐는지 — ApiScorer 의 path-shape 비활성 분기 (doc/09 §2.3). */
    public boolean isExplicitHintMode() {
        return explicitHintMode;
    }

    /** template 이 api 힌트(prefix 세그먼트경계 또는 regex full-match)에 매치되는지. */
    public boolean apiHinted(String template) {
        if (template == null) {
            return false;
        }
        for (String p : apiPrefixes) {
            if (prefixMatch(template, p)) {
                return true;
            }
        }
        return anyRegexMatch(apiRegexes, template);
    }

    /** template 이 exclude(prefix/regex)에 매치되는지. */
    public boolean excluded(String template) {
        if (template == null) {
            return false;
        }
        for (String p : excludePrefixes) {
            if (prefixMatch(template, p)) {
                return true;
            }
        }
        return anyRegexMatch(excludeRegexes, template);
    }

    /** include_web_forms effective 값 (null=상속은 생성 시 false 로 해소). */
    public boolean includeWebForms() {
        return includeWebForms;
    }

    /** ReDoS deadline/입력상한으로 no-match 처리된 매칭 횟수 (메트릭 후속 배선용, 테스트 검증용). */
    public long timeoutCount() {
        return timeoutCount.get();
    }

    // --- 매칭 헬퍼 ---

    /** 세그먼트 경계 prefix: {@code path == prefix} 또는 {@code path.startsWith(prefix + "/")} (§1). */
    private static boolean prefixMatch(String path, String prefix) {
        return path.equals(prefix) || path.startsWith(prefix + "/");
    }

    private boolean anyRegexMatch(List<CompiledRegex> regexes, String path) {
        if (regexes.isEmpty()) {
            return false;
        }
        if (path.length() > MAX_INPUT_LEN) {
            // 입력 길이 상한 초과 → regex skip = no-match + 카운터 (§3.2)
            timeoutCount.incrementAndGet();
            return false;
        }
        for (CompiledRegex cr : regexes) {
            if (matchesWithDeadline(cr, path)) {
                return true;
            }
        }
        return false;
    }

    /** deadline 기반 interruptible 매칭. 초과 시 no-match + 카운터 + 패턴별 WARN 1회 (throw 금지, §3.2). */
    private boolean matchesWithDeadline(CompiledRegex cr, String path) {
        long deadline = System.nanoTime() + MATCH_BUDGET_NANOS;
        try {
            return cr.pattern().matcher(new DeadlineCharSequence(path, deadline)).matches();
        } catch (RegexTimeoutException e) {
            timeoutCount.incrementAndGet();
            warnOnce(cr.source());
            return false; // 안전한 fallback: 양성 timeout=미힌트, exclude timeout=미제외 (둘 다 score 게이트로)
        }
    }

    private void warnOnce(String pattern) {
        if (warned.add(pattern)) {
            log.warn("regex 매칭이 {}ms budget 초과 — no-match 로 처리: {}", MATCH_BUDGET_NANOS / 1_000_000L, pattern);
        }
    }

    // --- 빌드 시 검증/컴파일 (§3.1) ---

    private static List<String> validatePrefixes(List<String> prefixes, String field) {
        if (prefixes.size() > MAX_PREFIX_COUNT) {
            throw new IllegalArgumentException(
                    field + " 개수 상한 " + MAX_PREFIX_COUNT + " 초과: " + prefixes.size());
        }
        for (String p : prefixes) {
            if (p.isBlank()) {
                // 빈/공백 prefix 는 startsWith 로 전 경로 매치 → match-all(api)/drop-all(exclude) 위험 (§3.1)
                throw new IllegalArgumentException(field + " 빈/공백 prefix 금지: \"" + p + "\"");
            }
            if (!p.startsWith("/")) {
                throw new IllegalArgumentException(field + " prefix 는 '/' 로 시작해야 함: " + p);
            }
            if (p.length() > MAX_PREFIX_LEN) {
                throw new IllegalArgumentException(
                        field + " 길이 상한 " + MAX_PREFIX_LEN + " 초과(" + p.length() + "): " + p);
            }
        }
        return prefixes; // MatcherConfig 가 이미 불변 복사본
    }

    private static List<CompiledRegex> compileRegexes(List<String> regexes, String field) {
        if (regexes.size() > MAX_REGEX_COUNT) {
            throw new IllegalArgumentException(
                    field + " 개수 상한 " + MAX_REGEX_COUNT + " 초과: " + regexes.size());
        }
        List<CompiledRegex> out = new ArrayList<>(regexes.size());
        for (String r : regexes) {
            if (r.isBlank()) {
                throw new IllegalArgumentException(field + " 빈/공백 regex 금지: \"" + r + "\"");
            }
            if (r.length() > MAX_REGEX_LEN) {
                throw new IllegalArgumentException(
                        field + " 패턴 길이 상한 " + MAX_REGEX_LEN + " 초과(" + r.length() + "): " + r);
            }
            try {
                out.add(new CompiledRegex(Pattern.compile(r), r));
            } catch (PatternSyntaxException e) {
                // 조용히 skip 금지 — 활성 오인 방지(§3.1)
                throw new IllegalArgumentException(field + " 잘못된 정규식: " + r, e);
            }
        }
        return List.copyOf(out);
    }

    private record CompiledRegex(Pattern pattern, String source) {}

    /** deadline 초과 시 매칭 중단을 위한 내부 예외(스택 불요). */
    private static final class RegexTimeoutException extends RuntimeException {
        RegexTimeoutException() {
            super(null, null, false, false);
        }
    }

    /**
     * {@code charAt()} 마다 nanoTime deadline 을 검사하는 CharSequence (doc/09 §3.2).
     * 파국적 백트래킹은 내부 char 접근을 반복하므로 deadline 에 걸려 중단된다(별도 스레드 불필요).
     */
    private static final class DeadlineCharSequence implements CharSequence {
        private final CharSequence inner;
        private final long deadlineNanos;

        DeadlineCharSequence(CharSequence inner, long deadlineNanos) {
            this.inner = inner;
            this.deadlineNanos = deadlineNanos;
        }

        @Override
        public char charAt(int index) {
            if (System.nanoTime() > deadlineNanos) {
                throw new RegexTimeoutException();
            }
            return inner.charAt(index);
        }

        @Override
        public int length() {
            return inner.length();
        }

        @Override
        public CharSequence subSequence(int start, int end) {
            return new DeadlineCharSequence(inner.subSequence(start, end), deadlineNanos);
        }

        @Override
        public String toString() {
            return inner.toString();
        }
    }
}
