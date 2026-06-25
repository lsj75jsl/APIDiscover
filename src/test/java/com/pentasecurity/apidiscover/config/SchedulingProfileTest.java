// 스케줄링 프로파일 게이트 구조 검증 — @EnableScheduling 이 SchedulingConfig(@Profile !cli)로 분리됨 (doc/31 B1)
package com.pentasecurity.apidiscover.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.pentasecurity.apidiscover.ApiDiscoverWorkerApplication;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * CLI 모드 스케줄러 미기동·서버 모드 동일 활성을 <b>구조로 고정</b>한다. 동시성/부팅 통합테스트는 운영 Loki 위험이 있어
 * 애너테이션 배선으로 검증(서버 모드 무회귀는 기존 @SpringBootTest 스위트가 비-cli 부팅으로 커버).
 */
class SchedulingProfileTest {

    @Test
    void schedulingMovedToProfileGatedConfig() {
        // @EnableScheduling 은 SchedulingConfig 에만(메인 클래스에서 제거)
        assertThat(SchedulingConfig.class.isAnnotationPresent(EnableScheduling.class)).isTrue();
        assertThat(ApiDiscoverWorkerApplication.class.isAnnotationPresent(EnableScheduling.class)).isFalse();
    }

    @Test
    void schedulingExcludedInCliProfileOnly() {
        // @Profile("!cli") → 서버 모드(비-cli) 활성, CLI 모드 비활성
        Profile profile = SchedulingConfig.class.getAnnotation(Profile.class);
        assertThat(profile).isNotNull();
        assertThat(profile.value()).containsExactly("!cli");
    }
}
