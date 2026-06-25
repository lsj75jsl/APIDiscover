// apidiscover.* 정적 설정을 바인딩하는 ConfigurationProperties (doc/05 §5, doc/07 §4)
package com.pentasecurity.apidiscover.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "apidiscover")
public record ApiDiscoverProperties(Loki loki, Schedule schedule, Central central, Discovery discovery) {

    /** Loki 접속 + 운영 부하 보호 설정. 인증은 사용하지 않는다(운영 정책). */
    public record Loki(
            String addr,
            String jobLabel,
            Duration queryTimeout,
            Duration chunkWindow,        // range query 1회당 시간창
            int pageLimit,               // query_range limit (유한값)
            int maxConcurrentQueries,    // Loki 동시 쿼리 상한
            Duration minQueryInterval    // 쿼리 간 최소 간격(스로틀)
    ) {}

    /** 스캔 주기/지연/백필 설정. */
    public record Schedule(
            Duration defaultInterval,
            Duration ingestLag,
            Duration initialBackfill,
            String offPeakWindow
    ) {}

    /** 중앙 서버 연동(웹훅 등) 설정. */
    public record Central(String baseUrl) {}

    /** 도메인 자동 디스커버리 설정 (doc/30 §7). enabled=false 면 스케줄러 no-op(무회귀 토글). */
    public record Discovery(
            boolean enabled,
            Duration interval,         // 롤링 주기
            Duration window,           // interval + 오버랩
            Duration bootstrapWindow,  // 첫 실행 1회
            Duration initialDelay,     // 스캔 스케줄과 stagger offset
            int maxDomainsPerRun,      // 폭증 가드(카운트 desc 상한)
            String hostPattern         // FQDN 검증 정규식
    ) {}
}
