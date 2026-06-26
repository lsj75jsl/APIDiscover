// CliListRunner.list() 단위 테스트 — 목록 출력 exit0·빈목록 정상·DB 오류 비0 (doc/33 §15, D47)
package com.pentasecurity.apidiscover.cli;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.pentasecurity.apidiscover.domain.DomainConfig;
import com.pentasecurity.apidiscover.domain.DomainConfigRepository;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.context.ConfigurableApplicationContext;

class CliListRunnerTest {

    private final DomainConfigRepository repo = mock(DomainConfigRepository.class);
    private final ConfigurableApplicationContext ctx = mock(ConfigurableApplicationContext.class);

    @Test
    void listsDomainsAndReturnsZero() {
        when(repo.findAll(any(org.springframework.data.domain.Sort.class)))
                .thenReturn(List.of(domain("a.example.com", true), domain("b.example.com", false)));
        assertThat(runner().list()).isEqualTo(CliListRunner.EXIT_OK);
    }

    @Test
    void emptyListIsOkExitZero() {
        when(repo.findAll(any(org.springframework.data.domain.Sort.class))).thenReturn(List.of());
        assertThat(runner().list()).isEqualTo(CliListRunner.EXIT_OK); // 빈 목록=정상
    }

    @Test
    void dbErrorReturnsNonZero() {
        when(repo.findAll(any(org.springframework.data.domain.Sort.class)))
                .thenThrow(new org.springframework.dao.DataAccessResourceFailureException("db down"));
        assertThat(runner().list()).isEqualTo(CliListRunner.EXIT_DB);
    }

    private CliListRunner runner() {
        return new CliListRunner(repo, new CliProperties(null, "/exports", null, null, null, true), ctx);
    }

    private static DomainConfig domain(String host, boolean enabled) {
        DomainConfig d = new DomainConfig();
        d.setHost(host);
        d.setEnabled(enabled);
        d.setHostnames(new ArrayList<>(List.of("E1")));
        d.setDiscoveredAt(Instant.EPOCH);
        d.setLastSeenAt(Instant.EPOCH);
        return d;
    }
}
