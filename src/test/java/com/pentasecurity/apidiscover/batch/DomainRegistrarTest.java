// DomainRegistrar.registerIfAbsent 단위 테스트 — 신규=enabled=true·discoveredAt null·save / 기존=동일반환·save 없음 / 정규화·null거부
package com.pentasecurity.apidiscover.batch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.pentasecurity.apidiscover.domain.DomainConfig;
import com.pentasecurity.apidiscover.domain.DomainConfigRepository;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class DomainRegistrarTest {

    private final DomainConfigRepository repo = mock(DomainConfigRepository.class);
    private final DomainRegistrar registrar = new DomainRegistrar(repo);

    @Test
    void registersAbsentDomainEnabledWithNoDiscoveredAt() {
        when(repo.findById("foo.com")).thenReturn(Optional.empty());
        when(repo.save(any(DomainConfig.class))).thenAnswer(inv -> inv.getArgument(0));

        DomainConfig d = registrar.registerIfAbsent("FOO.COM"); // 대문자 → 정규화 "foo.com"

        assertThat(d.getHost()).isEqualTo("foo.com");
        assertThat(d.isEnabled()).isTrue();
        assertThat(d.getDiscoveredAt()).isNull();  // 수동 등록 = discoveredAt null(자동 디스커버리와 자연 구분)
        assertThat(d.getCreatedAt()).isNotNull();
        verify(repo).save(any(DomainConfig.class));
    }

    @Test
    void existingDomainReturnedWithoutSave() {
        DomainConfig existing = new DomainConfig();
        existing.setHost("foo.com");
        when(repo.findById("foo.com")).thenReturn(Optional.of(existing));

        assertThat(registrar.registerIfAbsent("foo.com")).isSameAs(existing);
        verify(repo, never()).save(any()); // 멱등 — 기존이면 쓰지 않음
    }

    @Test
    void rejectsBlankHost() {
        assertThatThrownBy(() -> registrar.registerIfAbsent(" "))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> registrar.registerIfAbsent(null))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
