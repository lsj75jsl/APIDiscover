// DomainController 등록 정규화 단위 테스트 — create/PUT host normalize·blank 400·중복 정규화 매칭 (doc/05 §2.2)
package com.pentasecurity.apidiscover.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.pentasecurity.apidiscover.api.dto.DomainDtos.DomainUpsert;
import com.pentasecurity.apidiscover.domain.DomainConfig;
import com.pentasecurity.apidiscover.domain.DomainConfigRepository;
import com.pentasecurity.apidiscover.spec.SpecStore;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

class DomainControllerTest {

    private final DomainConfigRepository repo = mock(DomainConfigRepository.class);
    private final SpecStore specStore = mock(SpecStore.class);
    private final DomainController controller = new DomainController(repo, specStore);

    @Test
    void createNormalizesHostBeforeSave() {
        when(specStore.activeMeta(any())).thenReturn(Optional.empty());
        when(repo.existsById("example.com")).thenReturn(false);
        when(repo.save(any(DomainConfig.class))).thenAnswer(inv -> inv.getArgument(0));

        var view = controller.create(upsert("  Example.COM  ")).getBody(); // 대문자+공백

        ArgumentCaptor<DomainConfig> saved = ArgumentCaptor.forClass(DomainConfig.class);
        verify(repo).save(saved.capture());
        assertThat(saved.getValue().getHost()).isEqualTo("example.com"); // trim+lowercase 저장
        assertThat(view.host()).isEqualTo("example.com");
        verify(repo).existsById("example.com");                          // 중복검사도 정규화 키
    }

    @Test
    void createBlankOrDashHostIs400() {
        for (String h : new String[]{null, "", "   ", "-", "  -  "}) {
            assertThatThrownBy(() -> controller.create(upsert(h)))
                    .isInstanceOfSatisfying(ResponseStatusException.class,
                            e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST));
        }
    }

    @Test
    void createDuplicateMatchesByNormalizedHost() {
        when(repo.existsById("example.com")).thenReturn(true); // 기존(정규화) 존재
        assertThatThrownBy(() -> controller.create(upsert("EXAMPLE.com")))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.CONFLICT));
    }

    @Test
    void updateLooksUpByNormalizedPathHost() {
        when(specStore.activeMeta(any())).thenReturn(Optional.empty());
        DomainConfig existing = new DomainConfig();
        existing.setHost("example.com");
        when(repo.findById("example.com")).thenReturn(Optional.of(existing));
        when(repo.save(any(DomainConfig.class))).thenAnswer(inv -> inv.getArgument(0));

        controller.update("EXAMPLE.COM", upsert(null)); // path host 비정규화 → 정규화 키로 매칭

        verify(repo).findById("example.com"); // 404 아님
    }

    @Test
    void updateBlankPathHostIs400() {
        assertThatThrownBy(() -> controller.update("  -  ", upsert(null)))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST));
    }

    @Test
    void getLooksUpByNormalizedPathHost() {
        when(specStore.activeMeta(any())).thenReturn(Optional.empty());
        DomainConfig existing = new DomainConfig();
        existing.setHost("example.com");
        when(repo.findById("example.com")).thenReturn(Optional.of(existing));

        var view = controller.get("EXAMPLE.COM"); // 대문자 경로 → 정규화 매칭(404 아님)

        assertThat(view.host()).isEqualTo("example.com");
        verify(repo).findById("example.com");
    }

    @Test
    void deleteUsesNormalizedPathHost() {
        when(repo.existsById("example.com")).thenReturn(true);

        controller.delete("EXAMPLE.com"); // 대문자 경로 → 정규화 키로 삭제

        verify(repo).existsById("example.com");
        verify(repo).deleteById("example.com");
    }

    private static DomainUpsert upsert(String host) {
        return new DomainUpsert(host, true, null, null, null, null);
    }
}
