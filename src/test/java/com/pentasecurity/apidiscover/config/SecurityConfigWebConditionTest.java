// SecurityConfig 웹 조건부 로드 검증 — CLI(web=NONE)에서 HttpSecurity 미요구로 기동 (doc/31 B1 버그수정)
package com.pentasecurity.apidiscover.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.security.web.SecurityFilterChain;

/**
 * CLI 모드(web=NONE)는 `HttpSecurity` 빈이 없어, 무조건 로드되던 SecurityConfig.filterChain(HttpSecurity) 이
 * 컨텍스트 기동을 깨뜨렸다(실배포 'Consider defining a bean of type HttpSecurity'). {@code @ConditionalOnWebApplication}
 * 으로 웹 컨텍스트에서만 로드되게 고쳤다. 서버(웹) 모드 로드 유지는 기존 servlet @SpringBootTest 스위트가 커버.
 */
class SecurityConfigWebConditionTest {

    @Test
    void securityConfigSkippedInNonWebContextSoCliBoots() {
        // 비웹 컨텍스트(=CLI web=NONE 재현): @ConditionalOnWebApplication false → 미로드 → HttpSecurity 불요 → 기동 성공
        new ApplicationContextRunner()
                .withUserConfiguration(SecurityConfig.class)
                .run(ctx -> {
                    assertThat(ctx).hasNotFailed();                       // 수정 전이면 HttpSecurity 미해결로 실패
                    assertThat(ctx).doesNotHaveBean(SecurityConfig.class);
                    assertThat(ctx).doesNotHaveBean(SecurityFilterChain.class);
                });
    }

    @Test
    void securityConfigGatedByWebApplicationCondition() {
        // 의도 고정: 웹 전용 게이트(서버 모드는 웹이라 로드=permitAll 유지, 무회귀)
        assertThat(SecurityConfig.class.isAnnotationPresent(ConditionalOnWebApplication.class)).isTrue();
    }
}
