// DomainNames.normalize 단위 테스트 — trim·lowercase·빈/"-"/null→null (doc/05 §2.2)
package com.pentasecurity.apidiscover.util;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class DomainNamesTest {

    @Test
    void trimsAndLowercases() {
        assertThat(DomainNames.normalize("  API.Example.COM  ")).isEqualTo("api.example.com");
        assertThat(DomainNames.normalize("api.example.com")).isEqualTo("api.example.com");
    }

    @Test
    void blankDashNullBecomeNull() {
        assertThat(DomainNames.normalize(null)).isNull();
        assertThat(DomainNames.normalize("")).isNull();
        assertThat(DomainNames.normalize("   ")).isNull();
        assertThat(DomainNames.normalize("-")).isNull();
        assertThat(DomainNames.normalize("  -  ")).isNull();
    }
}
