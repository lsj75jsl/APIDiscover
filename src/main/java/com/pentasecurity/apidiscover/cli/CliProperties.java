// CLI 설정 (adc.cli.*) — export-domain/output-dir(CSV, doc/31) + scan-domain/window/edge(온디맨드 스캔, doc/33 §7)
package com.pentasecurity.apidiscover.cli;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * {@code adc.cli.export-domain} = CSV 내보낼 도메인 / {@code output-dir} = CSV 출력 디렉터리(기본 {@code /exports}, doc/31 B3).
 * {@code adc.cli.scan-domain} = 온디맨드 스캔 도메인(doc/33 §7) / {@code window} = 스캔 윈도우(미지정=scan.max-window) /
 * {@code edge} = 특정 엣지 hostname만(미지정=도메인 hostnames 전체). 해당 인자 존재 시 CLI 모드(main 게이트).
 */
@ConfigurationProperties(prefix = "adc.cli")
public record CliProperties(
        String exportDomain,
        @DefaultValue("/exports") String outputDir,
        String scanDomain,
        Duration window,
        String edge) {
}
