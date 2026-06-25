// 전역 분류 설정 리포지토리 테스트 — 단일행 upsert/부재 (doc/10 §7)
package com.pentasecurity.apidiscover.domain;

import static org.assertj.core.api.Assertions.assertThat;

import com.pentasecurity.apidiscover.model.ClassificationProfile;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;

@DataJpaTest
class ClassificationConfigRepositoryTest {

    @Autowired
    private ClassificationConfigRepository repo;

    @Test
    void absentByDefault() {
        assertThat(repo.findById(1L)).isEmpty();
    }

    @Test
    void singleRowUpsertOnFixedPk() {
        ClassificationConfig c = new ClassificationConfig();
        c.setId(1L);
        c.setProfile(ClassificationProfile.HIGH);
        c.setThresholdOverride(0.8);
        c.setCustomWeightsJson("{\"apiSegment\":0.9}");
        c.setMatcherJson("{\"apiPathPrefixes\":[\"/api\"]}");
        c.setUpdatedAt(Instant.EPOCH);
        repo.save(c);

        ClassificationConfig loaded = repo.findById(1L).orElseThrow();
        assertThat(loaded.getProfile()).isEqualTo(ClassificationProfile.HIGH);
        assertThat(loaded.getThresholdOverride()).isEqualTo(0.8);
        assertThat(loaded.getCustomWeightsJson()).contains("apiSegment");
        assertThat(loaded.getMatcherJson()).contains("/api");

        // 같은 고정 PK 재저장 → 단일행 유지(upsert)
        c.setProfile(ClassificationProfile.LOW);
        repo.save(c);
        assertThat(repo.count()).isEqualTo(1);
        assertThat(repo.findById(1L).orElseThrow().getProfile()).isEqualTo(ClassificationProfile.LOW);
    }
}
