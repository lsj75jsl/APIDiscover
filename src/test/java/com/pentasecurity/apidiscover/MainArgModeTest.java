// main() CLI 모드 감지 단위 테스트 — `-domain -ls` 목록 모드 + 기존 export/scan 비회귀 (doc/33 §15, D47)
package com.pentasecurity.apidiscover;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class MainArgModeTest {

    @Test
    void detectsListDomainsOnlyWhenBothSingleDashFlagsPresent() {
        assertThat(ApiDiscoverWorkerApplication.isListDomains(new String[]{"-domain", "-ls"})).isTrue();
        assertThat(ApiDiscoverWorkerApplication.isListDomains(new String[]{"-ls", "-domain"})).isTrue();
        assertThat(ApiDiscoverWorkerApplication.isListDomains(new String[]{"-domain"})).isFalse(); // -ls 없음
        assertThat(ApiDiscoverWorkerApplication.isListDomains(new String[]{"-ls"})).isFalse();
        assertThat(ApiDiscoverWorkerApplication.isListDomains(new String[]{})).isFalse();
    }

    @Test
    void existingPropertyStyleCommandsAreNotListMode() {
        // 기존 export/scan(--adc.cli.X=) 은 목록 모드로 오인되지 않음(비회귀)
        assertThat(ApiDiscoverWorkerApplication.isListDomains(
                new String[]{"--adc.cli.export-domain=foo.example.com"})).isFalse();
        assertThat(ApiDiscoverWorkerApplication.isListDomains(
                new String[]{"--adc.cli.scan-domain=foo.example.com"})).isFalse();
    }
}
