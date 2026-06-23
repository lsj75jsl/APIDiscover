// 스펙 포맷 공유 정규화 — template/host 를 3종 포맷 간 동일하게 (doc/14 §0.1, doc/03 §1.1)
package com.pentasecurity.apidiscover.spec;

import java.util.Locale;
import java.util.regex.Pattern;

/** Postman/CSV 공용 path/host 정규화(OpenAPI 슬래시 규칙과 동일 산출). package-private. */
final class SpecNormalize {

    private SpecNormalize() {
    }

    /** {{var}} (공백 허용) → {var}. */
    private static final Pattern CURLY_VAR = Pattern.compile("\\{\\{\\s*([\\w.-]+)\\s*\\}\\}");

    /**
     * 경로 템플릿 정규화: 세그먼트 {@code :var→{var}}, {@code {{var}}→{var}}, 이미 {@code {x}} 는 유지.
     * 선행 {@code /} 보장, {@code //} collapse, 후행 {@code /} strip(루트 제외).
     */
    static String template(String raw) {
        if (raw == null || raw.isBlank()) {
            return "/";
        }
        String s = CURLY_VAR.matcher(raw).replaceAll("{$1}"); // {{x}} → {x}
        StringBuilder sb = new StringBuilder();
        for (String seg : s.split("/", -1)) {
            if (seg.isEmpty()) {
                continue; // 선행/후행/중복 슬래시 collapse
            }
            sb.append('/').append(seg.startsWith(":") ? "{" + seg.substring(1) + "}" : seg);
        }
        return sb.isEmpty() ? "/" : sb.toString();
    }

    /** host: trim, 빈값→null, 아니면 소문자. */
    static String host(String raw) {
        if (raw == null) {
            return null;
        }
        String t = raw.trim();
        return t.isEmpty() ? null : t.toLowerCase(Locale.ROOT);
    }
}
