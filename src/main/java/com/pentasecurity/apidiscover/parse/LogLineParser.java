// nginx access log(^|^ 구분 20필드)을 ParsedRequest 로 파싱 (doc/02 §1)
package com.pentasecurity.apidiscover.parse;

import com.pentasecurity.apidiscover.model.ParsedRequest;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

@Component
public class LogLineParser {

    /** 로그 필드 구분자. */
    static final String DELIM = "^|^";
    private static final Pattern DELIM_PATTERN = Pattern.compile(Pattern.quote(DELIM));

    private static final Set<String> HTTP_METHODS = Set.of(
            "GET", "POST", "PUT", "PATCH", "DELETE", "HEAD", "OPTIONS", "TRACE", "CONNECT");

    // 필드 인덱스 (0-based) — doc/02 §1.1
    private static final int F_CLIENT_REAL_IP = 0;
    private static final int F_REMOTE_ADDR = 1;
    private static final int F_TIME = 3;
    private static final int F_REQUEST = 5;
    private static final int F_RESPONSE_TIME = 7;
    private static final int F_REQUEST_URI = 8;
    private static final int F_STATUS = 9;
    private static final int F_BODY_BYTES = 10;
    private static final int F_HTTPS = 12;
    private static final int F_REFERER = 13;
    private static final int F_USER_AGENT = 14;
    private static final int F_HOST = 15;
    private static final int F_REAL_HOST = 16;
    private static final int F_TYPE = 19;          // $type (document/library 등)
    private static final int F_REQUEST_ID = 23;    // request_id (32 hex, dedup 키)
    private static final int FIELD_COUNT = 20;     // 필수 코어 필드 수(실로그는 24개까지)

    /**
     * 로그 한 줄을 파싱한다. 파싱 불가/비표준 메서드면 {@link Optional#empty()}.
     */
    public Optional<ParsedRequest> parse(String line) {
        if (line == null || line.isBlank()) {
            return Optional.empty();
        }
        String[] f = DELIM_PATTERN.split(line, -1);
        if (f.length < FIELD_COUNT) {
            return Optional.empty();
        }
        try {
            String method = firstToken(f[F_REQUEST]);
            if (method == null || !HTTP_METHODS.contains(method)) {
                return Optional.empty();
            }

            String requestUri = f[F_REQUEST_URI];
            String rawPath = stripQuery(requestUri);
            List<String> queryKeys = queryKeys(requestUri);

            int status = Integer.parseInt(f[F_STATUS].trim());
            long bodyBytes = parseLongOrZero(f[F_BODY_BYTES]);
            long respTimeMs = Math.round(parseDoubleOrZero(f[F_RESPONSE_TIME]) * 1000.0);
            Instant ts = OffsetDateTime.parse(f[F_TIME].trim()).toInstant();
            boolean https = "on".equalsIgnoreCase(nullIfDash(f[F_HTTPS]));

            String host = firstNonEmpty(nullIfDash(f[F_HOST]), nullIfDash(f[F_REAL_HOST]));
            String clientIp = firstNonEmpty(nullIfDash(f[F_CLIENT_REAL_IP]), nullIfDash(f[F_REMOTE_ADDR]));
            String userAgent = nullIfDash(f[F_USER_AGENT]);
            String referer = nullIfDash(f[F_REFERER]);
            // type/requestId 는 실로그(24필드)에만 존재 — 없으면 null
            String type = (f.length > F_TYPE) ? nullIfDash(f[F_TYPE]) : null;
            String requestId = (f.length > F_REQUEST_ID) ? nullIfDash(f[F_REQUEST_ID]) : null;

            return Optional.of(new ParsedRequest(
                    method, rawPath, queryKeys, status, host, clientIp, userAgent,
                    ts, respTimeMs, bodyBytes, https, referer, type, requestId));
        } catch (RuntimeException e) {
            // 숫자/시간 파싱 실패 등 — 손상된 라인은 폐기 (doc/02 §2)
            return Optional.empty();
        }
    }

    private static String firstToken(String request) {
        if (request == null) {
            return null;
        }
        String trimmed = request.trim();
        int sp = trimmed.indexOf(' ');
        return sp < 0 ? (trimmed.isEmpty() ? null : trimmed) : trimmed.substring(0, sp);
    }

    private static String stripQuery(String uri) {
        if (uri == null) {
            return "";
        }
        int q = uri.indexOf('?');
        return q < 0 ? uri : uri.substring(0, q);
    }

    private static List<String> queryKeys(String uri) {
        List<String> keys = new ArrayList<>();
        if (uri == null) {
            return keys;
        }
        int q = uri.indexOf('?');
        if (q < 0 || q == uri.length() - 1) {
            return keys;
        }
        Set<String> seen = new LinkedHashSet<>();
        for (String pair : uri.substring(q + 1).split("&")) {
            if (pair.isEmpty()) {
                continue;
            }
            int eq = pair.indexOf('=');
            String key = eq < 0 ? pair : pair.substring(0, eq);
            if (!key.isEmpty()) {
                seen.add(key);
            }
        }
        keys.addAll(seen);
        return keys;
    }

    private static String nullIfDash(String v) {
        if (v == null) {
            return null;
        }
        String t = v.trim();
        return (t.isEmpty() || "-".equals(t)) ? null : t;
    }

    private static String firstNonEmpty(String a, String b) {
        return a != null ? a : b;
    }

    private static long parseLongOrZero(String v) {
        try {
            return Long.parseLong(v.trim());
        } catch (NumberFormatException e) {
            return 0L;
        }
    }

    private static double parseDoubleOrZero(String v) {
        try {
            return Double.parseDouble(v.trim());
        } catch (NumberFormatException e) {
            return 0.0;
        }
    }
}
