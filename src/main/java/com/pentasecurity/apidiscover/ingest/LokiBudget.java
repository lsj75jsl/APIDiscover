// Loki 전역 레이트 가드 + 계측 — 시간당 쿼리/바이트 하드캡(초과=틱 이월) + Micrometer (doc/33 §3 E)
package com.pentasecurity.apidiscover.ingest;

import com.pentasecurity.apidiscover.config.ApiDiscoverProperties;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import org.springframework.stereotype.Component;

/**
 * LokiClient 보호(동시·throttle·백오프) <b>위에</b> 전역 시간당 예산(쿼리/바이트 하드캡)과 Micrometer 계측을 둔다(doc/33 §3).
 * 틱 루프가 도메인마다 {@link #hasBudget()} 로 천장을 확인하고 초과 시 틱을 조기 종료(다음 틱 이월). 단일 인스턴스 전제(§9).
 *
 * <p>한계(정직): 바이트는 응답 본문 길이 근사(헤더/압축 제외), 시간창 경계는 ±1틱 오차 — 운영 가시화엔 충분.
 */
@Component
public class LokiBudget {

    private static final long MILLIS_PER_HOUR = 3_600_000L;

    private final int maxQueriesPerHour;
    private final long maxBytesPerHour;
    private final Clock clock;
    private final MeterRegistry registry;
    private final Counter queryCounter;
    private final Counter byteCounter;

    // 시간창(epoch-hour) 누적 — synchronized 보호(동시 2 쿼리)
    private long windowEpochHour = -1L;
    private int queriesThisHour;
    private long bytesThisHour;

    public LokiBudget(ApiDiscoverProperties props, MeterRegistry registry, Clock clock) {
        this.maxQueriesPerHour = props.scan().maxQueriesPerHour();
        this.maxBytesPerHour = props.scan().maxBytesPerHour();
        this.registry = registry;
        this.clock = clock;
        this.queryCounter = Counter.builder("loki.queries")
                .description("Loki 쿼리 총 횟수").register(registry);
        this.byteCounter = Counter.builder("loki.response.bytes")
                .description("Loki 응답 바이트 근사").baseUnit("bytes").register(registry);
    }

    /** 현재 시간창에 예산 잔여가 있는가(틱 루프가 도메인마다 체크, 초과=이월). 0=무제한. */
    public synchronized boolean hasBudget() {
        rollIfNeeded();
        if (maxQueriesPerHour > 0 && queriesThisHour >= maxQueriesPerHour) {
            return false;
        }
        return !(maxBytesPerHour > 0 && bytesThisHour >= maxBytesPerHour);
    }

    /** Loki HTTP 응답 1건 기록 — 시간당 누적 + Micrometer(쿼리·바이트·429/5xx). LokiClient 가 호출. */
    public synchronized void record(int status, long bytes) {
        rollIfNeeded();
        long b = Math.max(0, bytes);
        queriesThisHour++;
        bytesThisHour += b;
        queryCounter.increment();
        byteCounter.increment(b);
        if (status == 429 || status >= 500) {
            registry.counter("loki.errors", "status", Integer.toString(status)).increment();
        }
    }

    /**
     * Loki I/O 실패(타임아웃·연결실패 등 HTTP 상태 이전) 계측 — 실패도 1쿼리로 시간당 예산에 계상해
     * (D58) 느린 Loki 를 향한 무한 hammering 을 예산으로도 제어. + {@code loki.errors{status=kind}} 에러 메트릭.
     */
    public synchronized void recordFailure(String kind) {
        rollIfNeeded();
        queriesThisHour++;
        queryCounter.increment();
        registry.counter("loki.errors", "status", kind).increment();
    }

    private void rollIfNeeded() {
        long hour = clock.millis() / MILLIS_PER_HOUR;
        if (hour != windowEpochHour) {
            windowEpochHour = hour;
            queriesThisHour = 0;
            bytesThisHour = 0L;
        }
    }
}
