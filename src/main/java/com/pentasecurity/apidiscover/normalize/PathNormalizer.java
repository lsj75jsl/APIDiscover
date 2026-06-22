// 경로 정규화 — 휴리스틱 변수 세그먼트 추론 (doc/02 §3.2, §3.4)
package com.pentasecurity.apidiscover.normalize;

import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/**
 * 스펙 매칭이 안 된 경로(=Shadow 후보)에 대해 변수 세그먼트를 휴리스틱으로 추론한다.
 * 스펙 우선 매칭(1단계)·통계 보정(3단계)은 별도 컴포넌트(doc/02 §3.1, §3.3).
 */
@Component
public class PathNormalizer {

    private static final Pattern UUID =
            Pattern.compile("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$");
    private static final Pattern NUMERIC = Pattern.compile("^\\d+$");
    private static final Pattern LONG_HEX = Pattern.compile("^[0-9a-fA-F]{16,}$");
    private static final Pattern TOKENISH = Pattern.compile("^[A-Za-z0-9_-]{20,}$");
    private static final Pattern DATE = Pattern.compile("^\\d{4}-\\d{2}-\\d{2}$");

    /** concrete path → 추론 템플릿. 예: /users/12345 → /users/{id} */
    public String inferTemplate(String rawPath) {
        if (rawPath == null || rawPath.isEmpty() || "/".equals(rawPath)) {
            return "/";
        }
        String trimmed = stripTrailingSlash(rawPath);
        String[] segments = trimmed.split("/", -1);
        StringBuilder sb = new StringBuilder();
        for (String seg : segments) {
            if (seg.isEmpty()) {
                continue; // 선행 슬래시로 인한 빈 세그먼트
            }
            sb.append('/').append(classify(seg));
        }
        return sb.isEmpty() ? "/" : sb.toString();
    }

    private static String classify(String seg) {
        if (UUID.matcher(seg).matches()) {
            return "{uuid}";
        }
        if (NUMERIC.matcher(seg).matches()) {
            return "{id}";
        }
        if (DATE.matcher(seg).matches()) {
            return "{date}";
        }
        if (LONG_HEX.matcher(seg).matches() || TOKENISH.matcher(seg).matches()) {
            return "{token}";
        }
        return seg;
    }

    private static String stripTrailingSlash(String path) {
        if (path.length() > 1 && path.endsWith("/")) {
            return path.substring(0, path.length() - 1);
        }
        return path;
    }
}
