// 서비스 간 인증 설정 (doc/07 §5). 스캐폴딩 단계에서는 전체 허용 + TODO
package com.pentasecurity.apidiscover.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;

@Configuration
public class SecurityConfig {

    // TODO(doc/07 §5): mTLS 또는 OAuth2 client-credentials 로 중앙↔Worker 인증 적용.
    //  현재는 스캐폴딩이라 모든 요청 허용.
    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
                .csrf(csrf -> csrf.disable())
                .authorizeHttpRequests(auth -> auth.anyRequest().permitAll());
        return http.build();
    }
}
