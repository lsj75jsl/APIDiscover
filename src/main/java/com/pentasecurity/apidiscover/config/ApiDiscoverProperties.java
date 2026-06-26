// apidiscover.* 정적 설정을 바인딩하는 ConfigurationProperties (doc/05 §5, doc/07 §4)
package com.pentasecurity.apidiscover.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "apidiscover")
public record ApiDiscoverProperties(Loki loki, Schedule schedule, Central central, Discovery discovery, Scan scan) {

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
            int maxDomainsPerRun,      // 0=무제한(전수 등록), >0=카운트 desc 상위 N 캡(폭증 가드)
            String hostPattern         // FQDN 검증 정규식
    ) {}

    /** 엔드포인트 스캔 부하 운영정책 — B 틱당 예산·A 윈도우 상한·E 전역 레이트 가드 (doc/33 §8, PR1). */
    public record Scan(
            Duration tickInterval,     // B 스캔 틱 간격(짧게=순간부하 평탄화)
            int domainsPerTick,        // B 틱당 도메인 예산(least-recently-scanned 상위 K)
            Duration maxWindow,        // A per-scan 윈도우 상한(백필 슬라이스, 0/null=무제한=현행 무회귀)
            int maxQueriesPerHour,     // E 전역 시간당 쿼리 하드캡(0=무제한)
            long maxBytesPerHour,      // E 전역 시간당 응답바이트 하드캡(0=무제한)
            boolean throttleOnError    // E 429/5xx 적응형 자동감속
    ) {}
}
