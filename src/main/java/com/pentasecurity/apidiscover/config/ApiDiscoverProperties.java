// apidiscover.* 정적 설정을 바인딩하는 ConfigurationProperties (doc/05 §5, doc/07 §4)
package com.pentasecurity.apidiscover.config;

import java.time.Duration;
import java.util.List;
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
            String hostPattern,        // FQDN 검증 정규식
            // D62: 작업 대상 제외 엣지(hostname 라벨) — 디스커버리 등록·lastSeen 갱신·스캔 조회 모두 제외.
            // null/빈=제외 없음(무회귀). 정확 일치 매칭.
            List<String> excludedHostnames
    ) {}

    /** 엔드포인트 스캔 부하 운영정책 — B 틱당 예산·A 윈도우 상한·E 전역 레이트 가드 (doc/33 §8/§14, PR1·PR1.1) + C/D/F 티어링(§4–6, PR2/PR3 D48). */
    public record Scan(
            Duration tickInterval,     // B 스캔 틱 간격(짧게=순간부하 평탄화)
            int domainsPerTick,        // B 틱당 도메인 예산(least-recently-scanned 상위 K)
            Duration maxWindow,        // A per-scan 윈도우 상한(백필 슬라이스, 0/null=무제한=현행 무회귀)
            int maxQueriesPerHour,     // E 전역 시간당 쿼리 하드캡(0=무제한)
            long maxBytesPerHour,      // E 전역 시간당 응답바이트 하드캡(0=무제한)
            boolean throttleOnError,   // E 429/5xx 적응형 자동감속
            Duration sliceWindow,      // ① 부분전진 슬라이스 단위(0/null=loki.chunk-window 재사용, PR1.1 §14)
            int maxQueriesPerScan,     // ① per-scan 하드캡(슬라이스 경계, 0=무제한=현행, PR1.1 §14)
            // --- C 활동 티어 + F dormant + D off-peak (doc/33 §4–6, D48). off-peak 쿼리캡 상향은 범위 밖(ponytail: LokiBudget 시간당 stateful → 경계 스위치 지저분, 필요 시 후속). ---
            boolean tieringEnabled,      // C false=PR1 LRS 동작(무회귀 롤백 스위치). nextScanDueAt 항상 now=즉시 due.
            Duration activeInterval,     // C active 티어 주기(lastSeenAt age <= active-threshold)
            Duration defaultInterval,    // lastSeenAt 부재=신호 없음 보수적 중간
            Duration inactiveInterval,   // C inactive 티어 주기
            Duration activeThreshold,    // C lastSeenAt age 이내=active 티어
            int offPeakDomainsPerTick,   // D off-peak 틱당 도메인 K 상향
            Duration offPeakMaxWindow,   // D off-peak per-scan 윈도우 상향(백필 가속)
            String offPeakZone,          // D off-peak 판정 zone(빈값=시스템 기본)
            Duration dormantAfter,       // F dormant 진입 age(이후 최장 주기로 강등, 삭제 아님)
            Duration dormantInterval,    // F dormant 최장 주기
            // ★무접속 도메인 중단(요구): lastSeenAt 이 이 기간보다 오래됐으면(=마지막 접속 > N 전) 스캔(수집+평가) 제외.
            // 0/null=비활성(현행 무회귀). 기본 P30D. fleet 디스커버리는 계속 → 트래픽 재개 시 lastSeenAt 갱신=자동 재개.
            Duration inactiveAfter,
            // D63 배칭: 같은 엣지의 실조회 도메인 N개를 정규식 OR 1쿼리로(0=off 무회귀). Loki 청크 재읽기 1/N.
            int queryBatchSize,
            // D65: 엣지를 그룹 Master 로 치환해 조회(Master 로그=그룹 전체 집계, 사용자 확정 ㉮). false=off 무회귀.
            boolean edgeGroupMainOnly,
            // D66 롤링 샘플링: 주기 스캔 윈도우를 "최신 sample-window 만"으로(과거 백로그 의도적 skip → 발산 정지,
            // 신선도=재방문주기). 0/null=off(gap-free 크롤 무회귀). scan-now/온디맨드는 무관.
            Duration sampleWindow
    ) {}
}
