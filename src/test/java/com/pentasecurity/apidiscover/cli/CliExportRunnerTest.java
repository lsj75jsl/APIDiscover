// CliExportRunner.export() 단위 테스트 — exit code(미존재/검출0=비0)·CSV 파일 생성 (doc/31 B1, System.exit 미경유)
package com.pentasecurity.apidiscover.cli;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.pentasecurity.apidiscover.batch.CombinedDiscoveryService;
import com.pentasecurity.apidiscover.domain.DiscoveredEndpointRepository;
import com.pentasecurity.apidiscover.model.CombinedDiscovery;
import com.pentasecurity.apidiscover.model.Finding;
import com.pentasecurity.apidiscover.model.ParamCandidates;
import com.pentasecurity.apidiscover.model.SpecMergeStrategy;
import com.pentasecurity.apidiscover.model.SpecSource;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.context.ConfigurableApplicationContext;

class CliExportRunnerTest {

    private static final String HOST = "api.example.com";

    private final CombinedDiscoveryService discovery = mock(CombinedDiscoveryService.class);
    private final DiscoveredEndpointRepository discoveredRepo = mock(DiscoveredEndpointRepository.class);
    private final ConfigurableApplicationContext ctx = mock(ConfigurableApplicationContext.class);

    @Test
    void returnsNonZeroWhenDomainBlank(@TempDir Path dir) {
        CliExportRunner runner = runner(null, dir);
        assertThat(runner.export(1L)).isEqualTo(CliExportRunner.EXIT_NO_DOMAIN);
    }

    @Test
    void returnsNonZeroWhenDomainHasNoEndpoints(@TempDir Path dir) {
        when(discovery.forHost(HOST)).thenReturn(combined(List.of())); // 미존재/검출 0건
        CliExportRunner runner = runner(HOST, dir);
        assertThat(runner.export(1L)).isEqualTo(CliExportRunner.EXIT_EMPTY);
    }

    @Test
    void writesCsvAndReturnsZeroOnSuccess(@TempDir Path dir) throws Exception {
        when(discovery.forHost(HOST)).thenReturn(combined(List.of(
                new Finding.Shadow(HOST, "POST", "/api/orders", 0.9, "r", ParamCandidates.EMPTY))));
        when(discoveredRepo.findByHost(HOST)).thenReturn(List.of());

        CliExportRunner runner = runner(HOST, dir);
        assertThat(runner.export(12345L)).isEqualTo(CliExportRunner.EXIT_OK);

        Path file = dir.resolve(HOST + "-12345.csv");
        assertThat(file).exists();
        String csv = Files.readString(file);
        assertThat(csv).startsWith("host,method,path_template,status,source");
        assertThat(csv).contains("api.example.com,POST,/api/orders,SHADOW,detected");
    }

    // --- helpers ---

    private CliExportRunner runner(String domain, Path outputDir) {
        return new CliExportRunner(discovery, discoveredRepo,
                new CliProperties(domain, outputDir.toString(), null, null, null, false), ctx);
    }

    private static CombinedDiscovery combined(List<Finding> findings) {
        return new CombinedDiscovery(HOST, 0L, SpecMergeStrategy.MERGE, findings, List.of(), SpecSource.EMPTY);
    }
}
