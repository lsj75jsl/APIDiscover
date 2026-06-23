// SensitiveKeyMatcher 단위 테스트 — 기본 키/정규식 대소문자무시 + 비민감 무영향 (doc/13 §3, §5)
package com.pentasecurity.apidiscover.normalize;

import static org.assertj.core.api.Assertions.assertThat;

import com.pentasecurity.apidiscover.config.SensitiveKeyProperties;
import org.junit.jupiter.api.Test;

class SensitiveKeyMatcherTest {

    private final SensitiveKeyMatcher matcher = new SensitiveKeyMatcher(SensitiveKeyProperties.defaults());

    @Test
    void flagsDefaultNamesCaseInsensitive() {
        assertThat(matcher.isSensitive("password")).isTrue();
        assertThat(matcher.isSensitive("Token")).isTrue();   // 대소문자 무시
        assertThat(matcher.isSensitive("API_KEY")).isTrue();
        assertThat(matcher.isSensitive("ssn")).isTrue();
    }

    @Test
    void flagsByRegexPartialMatch() {
        assertThat(matcher.isSensitive("user_password_hash")).isTrue(); // .*passw.*
        assertThat(matcher.isSensitive("my_secret_value")).isTrue();    // .*secret.*
    }

    @Test
    void nonSensitiveUnaffected() {
        assertThat(matcher.isSensitive("expand")).isFalse();
        assertThat(matcher.isSensitive("page")).isFalse();
        assertThat(matcher.isSensitive(null)).isFalse();
        assertThat(matcher.isSensitive("")).isFalse();
    }
}
