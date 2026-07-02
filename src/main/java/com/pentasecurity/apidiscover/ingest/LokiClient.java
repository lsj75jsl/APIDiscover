// Loki query_range 클라이언트 — 윈도우분할·페이지네이션·스로틀·백오프 (doc/05 §2.4, §6)
package com.pentasecurity.apidiscover.ingest;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pentasecurity.apidiscover.config.ApiDiscoverProperties;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
public class LokiClient {

    private static final Logger log = LoggerFactory.getLogger(LokiClient.class);
    // 쿼리별 상세(응답시간·도메인·성공/실패유형) 전용 로거 — logback-spring.xml 이 /opt/adc-log 파일로 분리(D58, 장애 원인분석).
    private static final Logger queryLog = LoggerFactory.getLogger("com.pentasecurity.apidiscover.loki.query");
    private static final int MAX_ATTEMPTS = 4;
    private static final int MAX_THROTTLE_LEVEL = 4; // 적응형 throttle 상한: min-interval × 2^level (≤16×)
    private static final Set<Integer> RETRYABLE = Set.of(429, 502, 503, 504);

    private final ApiDiscoverProperties props;
    private final ObjectMapper objectMapper;
    private final LokiBudget budget;
    private final HttpClient http;
    /** 동시 쿼리 상한 (max-concurrent-queries, doc/05 §2.4). */
    private final Semaphore concurrencyGate;
    /** 적응형 throttle 레벨 (E, doc/33 §3): 429/5xx 시 증가·성공 시 감쇠. min-interval × 2^level 슬립. */
    private final AtomicInteger throttleLevel = new AtomicInteger(0);

    public LokiClient(ApiDiscoverProperties props, ObjectMapper objectMapper, LokiBudget budget) {
        this.props = props;
        this.objectMapper = objectMapper;
        this.budget = budget;
        this.concurrencyGate = new Semaphore(Math.max(1, props.loki().maxConcurrentQueries()));
        this.http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    }

    /**
     * 한 시간창의 로그 라인을 조회한다. 윈도우를 chunk-window 로 쪼개 순차 조회하고,
     * 각 chunk 는 page-limit 단위로 페이지네이션한다. (doc/05 §2.4)
     */
    public List<String> queryRange(String logql, LogWindow window) {
        List<String> out = new ArrayList<>();
        Duration chunk = props.loki().chunkWindow();
        Instant cursor = window.from();
        while (cursor.isBefore(window.to())) {
            Instant chunkEnd = cursor.plus(chunk);
            if (chunkEnd.isAfter(window.to())) {
                chunkEnd = window.to();
            }
            fetchChunk(logql, cursor, chunkEnd, out);
            cursor = chunkEnd;
        }
        return out;
    }

    /**
     * instant 벡터 쿼리(/loki/api/v1/query) — 서버측 메트릭 집계 1행 수신(도메인 디스커버리, doc/30 §2).
     * throttle/concurrency/백오프/timeout 은 query_range 와 동일 경로(requestWithRetry) 재사용. 윈도우 분할 불요(집계 1행).
     */
    public List<MetricSample> queryInstant(String logql, Instant time) {
        String url = props.loki().addr() + "/loki/api/v1/query"
                + "?query=" + URLEncoder.encode(logql, StandardCharsets.UTF_8)
                + "&time=" + toNanos(time);
        JsonNode data = requestWithRetry(url, logql);
        List<MetricSample> out = new ArrayList<>();
        for (JsonNode r : data.path("result")) {
            Map<String, String> labels = new HashMap<>();
            r.path("metric").fields().forEachRemaining(e -> labels.put(e.getKey(), e.getValue().asText()));
            JsonNode value = r.path("value"); // [ts, "count"]
            double count = (value.isArray() && value.size() == 2) ? Double.parseDouble(value.get(1).asText()) : 0.0;
            out.add(new MetricSample(labels, count));
        }
        return out;
    }

    /** instant 벡터 결과 1행: 라벨맵({domain,hostname,...}) + 집계값. */
    public record MetricSample(Map<String, String> labels, double value) {}

    private void fetchChunk(String logql, Instant start, Instant end, List<String> out) {
        long endNs = toNanos(end);
        long startNs = toNanos(start);
        int limit = props.loki().pageLimit();
        while (startNs < endNs) {
            String url = props.loki().addr() + "/loki/api/v1/query_range"
                    + "?query=" + URLEncoder.encode(logql, StandardCharsets.UTF_8)
                    + "&start=" + startNs + "&end=" + endNs
                    + "&limit=" + limit + "&direction=forward";
            JsonNode data = requestWithRetry(url, logql);
            long maxTs = -1L;
            int count = 0;
            for (JsonNode stream : data.path("result")) {
                for (JsonNode entry : stream.path("values")) {
                    long ts = Long.parseLong(entry.get(0).asText());
                    out.add(entry.get(1).asText());
                    maxTs = Math.max(maxTs, ts);
                    count++;
                }
            }
            if (count < limit || maxTs < 0) {
                break; // 마지막 페이지
            }
            startNs = maxTs + 1; // forward: 다음 페이지는 마지막 ts+1ns 부터
        }
    }

    /**
     * 단일 Loki HTTP GET + 429/5xx 지수 백오프. data 노드 반환. query_range·instant 공용(doc/30 §2, URL 인자형).
     * <p>★I/O 실패(타임아웃·연결실패)도 {@link #escalateThrottle()} 로 적응형 감속하고 budget 에 계상(D58) — 종전엔
     * HTTP 429/5xx 에만 감속이 걸려, Loki 지연 시 앱이 감속 없이 전속으로 실패 쿼리를 계속 던졌다. 재시도는 안 한다
     * (느린 Loki 가중 방지) — 실패는 다음 틱 resume. 모든 쿼리의 응답시간·결과를 {@code queryLog} 로 남긴다(장애 원인분석).
     * @param logql 로깅용 원본 LogQL(엣지 hostname + 대상 도메인 포함) — url 은 인코딩돼 있어 사람이 읽기 어렵다.
     */
    private JsonNode requestWithRetry(String url, String logql) {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(props.loki().queryTimeout())
                .GET()
                .build();

        for (int attempt = 0; attempt < MAX_ATTEMPTS; attempt++) {
            throttle();
            long t0 = System.nanoTime();
            HttpResponse<String> response;
            try {
                response = send(request);
            } catch (IOException e) {
                long ms = elapsedMs(t0);
                budget.recordFailure("io");   // 실패도 1쿼리로 시간당 예산 계상 + 에러 메트릭 (D58)
                escalateThrottle();            // ★ Loki 지연/실패 → 적응형 감속 (D58) — throttle-on-error 시
                queryLog.warn("loki query FAILED type={} elapsedMs={} throttle={} attempt={}/{} query={}",
                        e.getClass().getSimpleName(), ms, throttleLevel.get(), attempt + 1, MAX_ATTEMPTS, logql);
                throw new IllegalStateException("Loki query I/O error", e); // 재시도 안 함(느린 Loki 가중 방지)
            }
            long ms = elapsedMs(t0);
            int status = response.statusCode();
            String body = response.body();
            long bytes = (body != null) ? body.length() : 0L;
            budget.record(status, bytes); // E 계측·시간당 누적
            queryLog.info("loki query status={} elapsedMs={} bytes={} throttle={} attempt={}/{} query={}",
                    status, ms, bytes, throttleLevel.get(), attempt + 1, MAX_ATTEMPTS, logql);
            if (status == 200) {
                relaxThrottle(); // 성공 → 적응형 throttle 감쇠
                return parseData(body);
            }
            escalateThrottle(); // 429/5xx → 지속 감속
            if (RETRYABLE.contains(status) && attempt < MAX_ATTEMPTS - 1) {
                log.warn("Loki {} (attempt {}/{}), backing off", status, attempt + 1, MAX_ATTEMPTS);
                backoff(attempt);
                continue;
            }
            throw new IllegalStateException("Loki query failed: HTTP " + status);
        }
        throw new IllegalStateException("Loki query failed after " + MAX_ATTEMPTS + " attempts");
    }

    private HttpResponse<String> send(HttpRequest request) throws IOException {
        try {
            concurrencyGate.acquire();
            try {
                return http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            } finally {
                concurrencyGate.release();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("interrupted during Loki query", e);
        }
    }

    /** 경과시간(ms) — 쿼리 응답시간 계측(단조 시계 nanoTime 기준). */
    private static long elapsedMs(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000L;
    }

    /** 현재 적응형 throttle 레벨 — 테스트에서 감속 발동 검증용(package-private). */
    int currentThrottleLevel() {
        return throttleLevel.get();
    }

    private JsonNode parseData(String body) {
        try {
            return objectMapper.readTree(body).path("data");
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalStateException("failed to parse Loki response", e);
        }
    }

    /** 쿼리 간 최소 간격(스로틀, doc/05 §2.4) × 적응형 배수(2^level, E throttle-on-error). */
    private void throttle() {
        long base = props.loki().minQueryInterval().toMillis();
        sleep(base << throttleLevel.get()); // level=0 이면 base(현행)
    }

    /** 429/5xx → throttle 레벨 +1(상한까지). throttle-on-error=false 면 no-op(현행). */
    private void escalateThrottle() {
        if (props.scan().throttleOnError()) {
            throttleLevel.updateAndGet(l -> Math.min(l + 1, MAX_THROTTLE_LEVEL));
        }
    }

    /** 성공 → throttle 레벨 −1(0 까지) 감쇠. */
    private void relaxThrottle() {
        throttleLevel.updateAndGet(l -> Math.max(l - 1, 0));
    }

    /** 지수 백오프: base * 2^attempt (base = min-query-interval). */
    private void backoff(int attempt) {
        sleep(props.loki().minQueryInterval().toMillis() * (1L << attempt));
    }

    private static void sleep(long millis) {
        if (millis <= 0) {
            return;
        }
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static long toNanos(Instant instant) {
        return instant.getEpochSecond() * 1_000_000_000L + instant.getNano();
    }
}
