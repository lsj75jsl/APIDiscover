// CliListRunner.list(stamp) 단위 테스트 — CSV 파일 출력·헤더·hostnames ';' 조인·빈목록 헤더만·DB/IO 오류 exit (doc/33 §15, D47)
package com.pentasecurity.apidiscover.cli;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.pentasecurity.apidiscover.domain.DomainConfig;
import com.pentasecurity.apidiscover.domain.DomainConfigRepository;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.data.domain.Sort;

class CliListRunnerTest {

    private final DomainConfigRepository repo = mock(DomainConfigRepository.class);
    private final ConfigurableApplicationContext ctx = mock(ConfigurableApplicationContext.class);

    @TempDir
    Path tmp;

    @Test
    void writesDomainsCsvAndReturnsZero() throws Exception {
        when(repo.findAll(any(Sort.class))).thenReturn(List.of(
                domain("a.example.com", true, List.of("E1", "E2"), Instant.EPOCH),
                domain("b.example.com", false, List.of(), null))); // null discoveredAt·빈 hostnames

        assertThat(runner(tmp.toString()).list(123L)).isEqualTo(CliListRunner.EXIT_OK);

        String csv = Files.readString(tmp.resolve("domains-123.csv"));
        assertThat(csv).startsWith("host,enabled,hostnames,discovered_at,last_seen_at\r\n");
        assertThat(csv).contains("a.example.com,true,E1;E2,1970-01-01T00:00:00Z,\r\n"); // hostnames ';' 조인·lastSeen 공란
        assertThat(csv).contains("b.example.com,false,,,\r\n");                         // 빈 hostnames·null discovered·null last = 3 공란
    }

    @Test
    void emptyListWritesHeaderOnlyExitZero() throws Exception {
        when(repo.findAll(any(Sort.class))).thenReturn(List.of());
        assertThat(runner(tmp.toString()).list(1L)).isEqualTo(CliListRunner.EXIT_OK);
        assertThat(Files.readString(tmp.resolve("domains-1.csv")))
                .isEqualTo("host,enabled,hostnames,discovered_at,last_seen_at\r\n"); // 헤더만(빈 목록=정상)
    }

    @Test
    void dbErrorReturnsExitDb() {
        when(repo.findAll(any(Sort.class)))
                .thenThrow(new DataAccessResourceFailureException("db down"));
        assertThat(runner(tmp.toString()).list(1L)).isEqualTo(CliListRunner.EXIT_DB);
    }

    @Test
    void ioErrorReturnsExitIo() throws Exception {
        when(repo.findAll(any(Sort.class)))
                .thenReturn(List.of(domain("a.example.com", true, List.of(), null)));
        Path blocker = Files.createFile(tmp.resolve("blocker")); // 파일 → 그 하위 디렉터리 생성 불가(IOException)
        assertThat(runner(blocker.resolve("sub").toString()).list(1L)).isEqualTo(CliListRunner.EXIT_IO);
    }

    private CliListRunner runner(String outputDir) {
        return new CliListRunner(repo, new CliProperties(null, outputDir, null, null, null, true), ctx);
    }

    private static DomainConfig domain(String host, boolean enabled, List<String> hostnames, Instant discoveredAt) {
        DomainConfig d = new DomainConfig();
        d.setHost(host);
        d.setEnabled(enabled);
        d.setHostnames(new ArrayList<>(hostnames));
        d.setDiscoveredAt(discoveredAt);
        d.setLastSeenAt(null);
        return d;
    }
}
