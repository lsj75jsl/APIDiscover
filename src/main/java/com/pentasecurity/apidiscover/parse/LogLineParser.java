// nginx access log(^|^ 구분 20필드)을 ParsedRequest 로 파싱 (doc/02 §1)
package com.pentasecurity.apidiscover.parse;

import com.pentasecurity.apidiscover.config.NormalizationProperties;
import com.pentasecurity.apidiscover.config.ParseProperties;
import com.pentasecurity.apidiscover.model.ParsedRequest;
import com.pentasecurity.apidiscover.model.QueryParamObs;
import com.pentasecurity.apidiscover.model.ValueLenBucket;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

@Component
public class LogLineParser {

    private final int[] valueLenBucketBounds;
    private final int acrmFieldIndex; // -1=미사용(acrm 안 읽음 → preflight 게이트 DORMANT, doc/23 §9.2)
    // 8.3 append 신규 인덱스 (doc/40 §6) — 각 -1=미사용 → 부재 신호 → 점수/kind DORMANT(무회귀).
    private final int responseContentTypeFieldIndex;
    private final int acceptFieldIndex;
    private final int xRequestedWithFieldIndex;
    private final int originFieldIndex;
    private final int authSchemeFieldIndex;

    public LogLineParser(NormalizationProperties props, ParseProperties parseProps) {
        this.valueLenBucketBounds = props.valueLenBucketBounds();
        this.acrmFieldIndex = parseProps.acrmFieldIndex();
        this.responseContentTypeFieldIndex = parseProps.responseContentTypeFieldIndex();
        this.acceptFieldIndex = parseProps.acceptFieldIndex();
        this.xRequestedWithFieldIndex = parseProps.xRequestedWithFieldIndex();
        this.originFieldIndex = parseProps.originFieldIndex();
        this.authSchemeFieldIndex = parseProps.authSchemeFieldIndex();
    }

    /** 로그 필드 구분자. doc/02 §1.1 — 도메인 디스커버리 LogQL pattern 과 공유(단일 근거, 드리프트 차단). */
    public static final String DELIM = "^|^";
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
    // host/real_host 필드 인덱스 — 도메인 디스커버리 LogQL pattern 과 공유(doc/02 §1.1, 교차검증 테스트로 고정).
    public static final int F_HOST = 15;
    public static final int F_REAL_HOST = 16;
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
            // ★matrix 파라미터(;key=value, RFC3986) 제거 — ;jsessionid 등 세션ID 가 세그먼트에 붙어 엔드포인트가 분리·부풀려지는 것 방지.
            String rawPath = stripMatrixParams(stripQuery(requestUri));
            List<QueryParamObs> queryParams = queryParams(requestUri);

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
            // acrm: 설정 인덱스로 "있으면 읽는"(기본 -1=미사용). 부재 → null → preflight 게이트 DORMANT (doc/23 §9.2)
            String acrm = readOptional(f, acrmFieldIndex);
            // 8.3 append 신규 신호 — 동형 nullable read(인덱스 -1 또는 필드 부족 시 null → dormant, doc/40 §6)
            String responseContentType = readOptional(f, responseContentTypeFieldIndex);
            String accept = readOptional(f, acceptFieldIndex);
            String xRequestedWith = readOptional(f, xRequestedWithFieldIndex);
            String origin = readOptional(f, originFieldIndex);
            String authScheme = readOptional(f, authSchemeFieldIndex);

            return Optional.of(new ParsedRequest(
                    method, rawPath, queryParams, status, host, clientIp, userAgent,
                    ts, respTimeMs, bodyBytes, https, referer, type, requestId, acrm,
                    responseContentType, accept, xRequestedWith, origin, authScheme));
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

    /**
     * 세그먼트별(/ 분리) 첫 ';' 이후 제거 — RFC3986 matrix 파라미터(예 {@code ;jsessionid=X}). leading slash 보존.
     * {@code /a;p=1/b;q=2}→{@code /a/b}, {@code /st/login;jsessionid=X}→{@code /st/login}. ';' 없으면 조기반환(무오버헤드).
     * matrix 구분자 ';' 는 endpoint identity 와 무관(세션ID 노이즈)이라 전부 제거 안전(doc/02 §3.2).
     */
    private static String stripMatrixParams(String path) {
        if (path == null || path.indexOf(';') < 0) {
            return path;
        }
        String[] segs = path.split("/", -1);
        StringBuilder sb = new StringBuilder(path.length());
        for (int i = 0; i < segs.length; i++) {
            if (i > 0) {
                sb.append('/');
            }
            int semi = segs[i].indexOf(';');
            sb.append(semi >= 0 ? segs[i].substring(0, semi) : segs[i]);
        }
        return sb.toString();
    }

    /**
     * query string → 파라미터별 (이름 + 값 길이 버킷). <b>값 자체는 폐기</b>하고 길이만 버킷화한다(doc/13 §2.1).
     * 같은 이름 중복 시 첫 관측 버킷 유지(distinct 키 단위, hadQuery 신호 보존).
     */
    private List<QueryParamObs> queryParams(String uri) {
        if (uri == null) {
            return List.of();
        }
        int q = uri.indexOf('?');
        if (q < 0 || q == uri.length() - 1) {
            return List.of();
        }
        Map<String, ValueLenBucket> byName = new LinkedHashMap<>();
        for (String pair : uri.substring(q + 1).split("&")) {
            if (pair.isEmpty()) {
                continue;
            }
            int eq = pair.indexOf('=');
            String key = eq < 0 ? pair : pair.substring(0, eq);
            if (key.isEmpty()) {
                continue;
            }
            // 값 길이만 측정 후 즉시 폐기 (값 미저장)
            int valueLen = (eq < 0) ? 0 : (pair.length() - eq - 1);
            byName.putIfAbsent(key, ValueLenBucket.of(valueLen, valueLenBucketBounds));
        }
        List<QueryParamObs> out = new ArrayList<>(byName.size());
        byName.forEach((name, bucket) -> out.add(new QueryParamObs(name, bucket)));
        return out;
    }

    /** 설정 인덱스로 선택적 필드 읽기 — 인덱스 -1(미사용) 또는 필드 부족 시 null(acrm/8.3 동형, doc/40 §6). */
    private static String readOptional(String[] f, int idx) {
        return (idx >= 0 && f.length > idx) ? nullIfDash(f[idx]) : null;
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
