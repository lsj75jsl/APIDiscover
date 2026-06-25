// 도메인 분류 설정 override 리포지토리 테스트 — host 조회/부재 (doc/10 §7)
package com.pentasecurity.apidiscover.domain;

import static org.assertj.core.api.Assertions.assertThat;

import com.pentasecurity.apidiscover.model.ClassificationProfile;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;

@DataJpaTest
class DomainClassificationConfigRepositoryTest {

    @Autowired
    private DomainClassificationConfigRepository repo;

    @Test
    void hostLookupAndAbsence() {
        assertThat(repo.findById("api.example.com")).isEmpty();

        DomainClassificationConfig d = new DomainClassificationConfig();
        d.setHost("api.example.com");
        d.setProfile(ClassificationProfile.LOW);
        d.setThresholdOverride(0.5);
        d.setMatcherJson("{\"excludePathPrefixes\":[\"/legacy\"],\"includeWebForms\":false}");
        d.setUpdatedAt(Instant.EPOCH);
        repo.save(d);

        assertThat(repo.findById("api.example.com")).get().satisfies(x -> {
            assertThat(x.getProfile()).isEqualTo(ClassificationProfile.LOW);
            assertThat(x.getThresholdOverride()).isEqualTo(0.5);
            assertThat(x.getMatcherJson()).contains("/legacy");
        });
        // 다른 host 는 부재(행 없음 = 전역 사용)
        assertThat(repo.findById("other.example.com")).isEmpty();
    }
}
