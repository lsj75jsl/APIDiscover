// ClassificationConfigSeeder 단위 테스트 — idempotent·default=무회귀 (doc/10 §7)
package com.pentasecurity.apidiscover.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.pentasecurity.apidiscover.model.ClassificationProfile;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class ClassificationConfigSeederTest {

    private final ClassificationConfigRepository repo = mock(ClassificationConfigRepository.class);
    private final ClassificationConfigSeeder seeder = new ClassificationConfigSeeder(repo);

    @Test
    void seedsNoRegressionDefaultWhenAbsent() {
        when(repo.findById(1L)).thenReturn(Optional.empty());

        seeder.run();

        ArgumentCaptor<ClassificationConfig> cap = ArgumentCaptor.forClass(ClassificationConfig.class);
        verify(repo).save(cap.capture());
        ClassificationConfig saved = cap.getValue();
        assertThat(saved.getId()).isEqualTo(1L);
        assertThat(saved.getProfile()).isEqualTo(ClassificationProfile.MIDDLE); // 무회귀(override 없음)
        assertThat(saved.getThresholdOverride()).isNull();
        assertThat(saved.getCustomWeightsJson()).isNull();
        assertThat(saved.getMatcherJson()).isNull();
    }

    @Test
    void idempotentWhenAlreadyPresent() {
        when(repo.findById(1L)).thenReturn(Optional.of(new ClassificationConfig()));

        seeder.run();

        verify(repo, never()).save(any()); // 재삽입 안 함
    }
}
