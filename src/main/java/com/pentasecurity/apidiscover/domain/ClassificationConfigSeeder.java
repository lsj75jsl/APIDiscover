// 전역 분류 설정 기본 레코드 idempotent seed (doc/10 §7). 부재 시에만 MIDDLE 기본 삽입
package com.pentasecurity.apidiscover.domain;

import com.pentasecurity.apidiscover.model.ClassificationProfile;
import java.time.Instant;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;

/**
 * 전역 레코드(PK=1L)가 없으면 MIDDLE/override 없음 기본값으로 1회 삽입한다.
 * 이 레코드는 resolver 의 "부재" 경로와 동치(무회귀) — seed 는 선택이고, resolver 가 부재도 무회귀를 보장한다.
 */
@Component
public class ClassificationConfigSeeder implements CommandLineRunner {

    private final ClassificationConfigRepository repo;

    public ClassificationConfigSeeder(ClassificationConfigRepository repo) {
        this.repo = repo;
    }

    @Override
    public void run(String... args) {
        if (repo.findById(1L).isEmpty()) {
            ClassificationConfig c = new ClassificationConfig();
            c.setId(1L);
            c.setProfile(ClassificationProfile.MIDDLE); // override 없음 = 현행(MIDDLE/0.70/NONE)
            c.setUpdatedAt(Instant.now());
            repo.save(c);
        }
    }
}
