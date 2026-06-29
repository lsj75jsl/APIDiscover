// main() 신문법 파싱 단위 테스트 — -domain 서브커맨드(-ls/-export/-scan) translate + 기존 --adc.cli.X= 제거 회귀 (doc/33 §15, D47)
package com.pentasecurity.apidiscover;

import static org.assertj.core.api.Assertions.assertThat;

import com.pentasecurity.apidiscover.ApiDiscoverWorkerApplication.CliArgs;
import org.junit.jupiter.api.Test;

class MainArgModeTest {

    @Test
    void listSubcommandInjectsListDomains() {
        CliArgs r = ApiDiscoverWorkerApplication.parseCli(new String[]{"-domain", "-ls"});
        assertThat(r.usageError()).isFalse();
        assertThat(r.inject()).containsExactly("--adc.cli.list-domains=true");
    }

    @Test
    void registerSubcommandInjectsRegisterDomain() {
        CliArgs r = ApiDiscoverWorkerApplication.parseCli(new String[]{"-domain", "-register", "foo.com"});
        assertThat(r.usageError()).isFalse();
        assertThat(r.inject()).containsExactly("--adc.cli.register-domain=foo.com");
    }

    @Test
    void registerWithoutDomainIsUsageError() {
        assertThat(ApiDiscoverWorkerApplication.parseCli(
                new String[]{"-domain", "-register"}).usageError()).isTrue();
    }

    @Test
    void registerWithAnotherSubcommandIsAmbiguousUsageError() {
        assertThat(ApiDiscoverWorkerApplication.parseCli(
                new String[]{"-domain", "-register", "foo.com", "-scan", "bar.com"}).usageError()).isTrue();
    }

    @Test
    void exportSubcommandInjectsExportDomain() {
        CliArgs r = ApiDiscoverWorkerApplication.parseCli(new String[]{"-domain", "-export", "foo.example.com"});
        assertThat(r.inject()).containsExactly("--adc.cli.export-domain=foo.example.com");
    }

    @Test
    void scanSubcommandInjectsScanDomainWithOptionalWindowAndEdge() {
        assertThat(ApiDiscoverWorkerApplication.parseCli(
                new String[]{"-domain", "-scan", "foo.example.com"}).inject())
                .containsExactly("--adc.cli.scan-domain=foo.example.com");

        assertThat(ApiDiscoverWorkerApplication.parseCli(
                new String[]{"-domain", "-scan", "foo.example.com", "-window", "PT1H", "-edge", "edge1"}).inject())
                .containsExactly("--adc.cli.scan-domain=foo.example.com",
                        "--adc.cli.window=PT1H", "--adc.cli.edge=edge1");

        // -edge 만(window 미지정) → window 미주입(런너 기본)
        assertThat(ApiDiscoverWorkerApplication.parseCli(
                new String[]{"-domain", "-scan", "foo.example.com", "-edge", "edge1"}).inject())
                .containsExactly("--adc.cli.scan-domain=foo.example.com", "--adc.cli.edge=edge1");
    }

    @Test
    void legacyPropertyStyleNoLongerTriggersCliMode() {
        // ★기존 --adc.cli.export-domain=/scan-domain= 사용자 트리거 제거 → 직접 입력해도 CLI 아님(서버 모드, D47 갱신)
        assertServerMode(ApiDiscoverWorkerApplication.parseCli(
                new String[]{"--adc.cli.export-domain=foo.example.com"}));
        assertServerMode(ApiDiscoverWorkerApplication.parseCli(
                new String[]{"--adc.cli.scan-domain=foo.example.com"}));
    }

    @Test
    void noDomainFlagIsServerMode() {
        assertServerMode(ApiDiscoverWorkerApplication.parseCli(new String[]{}));
        assertServerMode(ApiDiscoverWorkerApplication.parseCli(new String[]{"--server.port=8080"}));
    }

    @Test
    void domainAloneOrMissingDomainIsUsageError() {
        assertThat(ApiDiscoverWorkerApplication.parseCli(new String[]{"-domain"}).usageError()).isTrue();
        assertThat(ApiDiscoverWorkerApplication.parseCli(new String[]{"-domain", "-export"}).usageError()).isTrue();
        assertThat(ApiDiscoverWorkerApplication.parseCli(new String[]{"-domain", "-scan"}).usageError()).isTrue();
        // 도메인 자리에 플래그가 오면 도메인 누락(positional)
        assertThat(ApiDiscoverWorkerApplication.parseCli(
                new String[]{"-domain", "-export", "-window"}).usageError()).isTrue();
    }

    @Test
    void multipleSubcommandsAreAmbiguousUsageError() {
        // 복수 서브커맨드 = 모호 → fail-loud(조용히 하나 선택 금지)
        assertThat(ApiDiscoverWorkerApplication.parseCli(
                new String[]{"-domain", "-export", "foo", "-scan", "bar"}).usageError()).isTrue();
        assertThat(ApiDiscoverWorkerApplication.parseCli(
                new String[]{"-domain", "-ls", "-export", "foo"}).usageError()).isTrue();
        // 단일 서브커맨드는 정상(회귀 없음)
        assertThat(ApiDiscoverWorkerApplication.parseCli(
                new String[]{"-domain", "-export", "foo"}).inject())
                .containsExactly("--adc.cli.export-domain=foo");
    }

    private static void assertServerMode(CliArgs r) {
        assertThat(r.usageError()).isFalse();
        assertThat(r.inject()).isNull();
    }
}
