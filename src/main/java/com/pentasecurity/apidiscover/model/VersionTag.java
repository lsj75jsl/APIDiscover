// path 기반 API 버전 라벨 추출 (doc/16 ^v\d+$, doc/26 §4 version 차원)
package com.pentasecurity.apidiscover.model;

import java.util.Locale;
import java.util.regex.Pattern;

/** path 의 첫 {@code ^v\d+$} 세그먼트를 버전 라벨로 인식 — 검출 version 컬럼·버전그룹 공용. */
public final class VersionTag {

    private VersionTag() {
    }

    private static final Pattern VERSION_SEGMENT = Pattern.compile("^v\\d+$", Pattern.CASE_INSENSITIVE);

    /** 첫 {@code ^v\d+$} 세그먼트(소문자 정규화). 없으면 null. */
    public static String ofPath(String template) {
        if (template == null) {
            return null;
        }
        String body = template.startsWith("/") ? template.substring(1) : template;
        if (body.isEmpty()) {
            return null;
        }
        for (String seg : body.split("/", -1)) {
            if (VERSION_SEGMENT.matcher(seg).matches()) {
                return seg.toLowerCase(Locale.ROOT);
            }
        }
        return null;
    }
}
