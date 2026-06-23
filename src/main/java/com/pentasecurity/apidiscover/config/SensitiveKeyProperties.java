// 민감 query 파라미터 키 매칭 설정 — 기본 키목록 + 정규식 내장 (doc/13 §3)
package com.pentasecurity.apidiscover.config;

import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * {@code apidiscover.sensitive-keys.{names,patterns}} 바인딩. yml 미지정 시 내장 기본값 사용(compact ctor).
 * 대소문자 무시 매칭은 {@code SensitiveKeyMatcher} 담당.
 */
@ConfigurationProperties(prefix = "apidiscover.sensitive-keys")
public record SensitiveKeyProperties(List<String> names, List<String> patterns) {

    /** 기본 민감 키 이름(소문자). */
    public static final List<String> DEFAULT_NAMES = List.of(
            "password", "passwd", "pwd", "token", "access_token", "refresh_token", "secret",
            "apikey", "api_key", "session", "sid", "authorization", "auth", "otp", "pin", "cvv",
            "ssn", "card", "cardno");

    /** 기본 민감 키 정규식(부분 매칭, 대소문자 무시). */
    public static final List<String> DEFAULT_PATTERNS = List.of(".*(passw|secret|token|apikey).*");

    /** yml 미지정(null/빈) 항목은 내장 기본값으로 채운다. */
    public SensitiveKeyProperties {
        names = (names == null || names.isEmpty()) ? DEFAULT_NAMES : List.copyOf(names);
        patterns = (patterns == null || patterns.isEmpty()) ? DEFAULT_PATTERNS : List.copyOf(patterns);
    }

    /** 테스트/기본 인스턴스. */
    public static SensitiveKeyProperties defaults() {
        return new SensitiveKeyProperties(null, null);
    }
}
