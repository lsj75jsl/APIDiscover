// CLI 내보내기 설정 (adc.cli.*) — export-domain/output-dir (doc/31 B1/B3)
package com.pentasecurity.apidiscover.cli;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * {@code adc.cli.export-domain} = 내보낼 도메인(이 인자 존재 시 CLI 모드, main 게이트).
 * {@code adc.cli.output-dir} = CSV 출력 디렉터리(기본 {@code /exports} — PGDATA 밖, C 에서 마운트, doc/31 B3).
 */
@ConfigurationProperties(prefix = "adc.cli")
public record CliProperties(String exportDomain, @DefaultValue("/exports") String outputDir) {
}
