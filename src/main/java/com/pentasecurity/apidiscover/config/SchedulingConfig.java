// 스케줄링 활성화 — CLI 모드(@Profile !cli)에선 미활성: 스캔·디스커버리 스케줄러 미기동 (doc/31 B)
package com.pentasecurity.apidiscover.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * {@code @EnableScheduling} 을 메인 클래스에서 분리해 {@code @Profile("!cli")} 로 게이트한다(doc/31 B1).
 * 서버 모드(비-cli)는 동일하게 활성(무회귀), CLI 모드는 스케줄러·Loki 트리거 미기동 → 1 명령 후 종료.
 */
@Configuration
@EnableScheduling
@Profile("!cli")
public class SchedulingConfig {
    // 본문 없음이 정상 — @EnableScheduling 활성화 자체가 목적(빈·메서드 불필요, dead 아님).
}
