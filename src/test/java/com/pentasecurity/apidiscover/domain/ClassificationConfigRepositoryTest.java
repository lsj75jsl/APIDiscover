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
        c.id = 1L;
        c.profile = ClassificationProfile.HIGH;
        c.thresholdOverride = 0.8;
        c.customWeightsJson = "{\"apiSegment\":0.9}";
        c.matcherJson = "{\"apiPathPrefixes\":[\"/api\"]}";
        c.updatedAt = Instant.EPOCH;
        repo.save(c);

        ClassificationConfig loaded = repo.findById(1L).orElseThrow();
        assertThat(loaded.profile).isEqualTo(ClassificationProfile.HIGH);
        assertThat(loaded.thresholdOverride).isEqualTo(0.8);
        assertThat(loaded.customWeightsJson).contains("apiSegment");
        assertThat(loaded.matcherJson).contains("/api");

        // 같은 고정 PK 재저장 → 단일행 유지(upsert)
        c.profile = ClassificationProfile.LOW;
        repo.save(c);
        assertThat(repo.count()).isEqualTo(1);
        assertThat(repo.findById(1L).orElseThrow().profile).isEqualTo(ClassificationProfile.LOW);
    }
}
