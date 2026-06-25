// Loki query_range 클라이언트 — 윈도우분할·페이지네이션·스로틀·백오프 (doc/05 §2.4, §6)
package com.pentasecurity.apidiscover.ingest;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pentasecurity.apidiscover.config.ApiDiscoverProperties;
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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
public class LokiClient {

    private static final Logger log = LoggerFactory.getLogger(LokiClient.class);
    private static final int MAX_ATTEMPTS = 4;
    private static final Set<Integer> RETRYABLE = Set.of(429, 502, 503, 504);

    private final ApiDiscoverProperties props;
    private final ObjectMapper objectMapper;
    private final HttpClient http;
    /** 동시 쿼리 상한 (max-concurrent-queries, doc/05 §2.4). */
    private final Semaphore concurrencyGate;

    public LokiClient(ApiDiscoverProperties props, ObjectMapper objectMapper) {
        this.props = props;
        this.objectMapper = objectMapper;
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
        JsonNode data = requestWithRetry(url);
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
            JsonNode data = requestWithRetry(url);
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

    /** 단일 Loki HTTP GET + 429/5xx 지수 백오프. data 노드 반환. query_range·instant 공용(doc/30 §2, URL 인자형). */
    private JsonNode requestWithRetry(String url) {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(props.loki().queryTimeout())
                .GET()
                .build();

        for (int attempt = 0; attempt < MAX_ATTEMPTS; attempt++) {
            throttle();
            HttpResponse<String> response = send(request);
            int status = response.statusCode();
            if (status == 200) {
                return parseData(response.body());
            }
            if (RETRYABLE.contains(status) && attempt < MAX_ATTEMPTS - 1) {
                log.warn("Loki {} (attempt {}/{}), backing off", status, attempt + 1, MAX_ATTEMPTS);
                backoff(attempt);
                continue;
            }
            throw new IllegalStateException("Loki query failed: HTTP " + status);
        }
        throw new IllegalStateException("Loki query failed after " + MAX_ATTEMPTS + " attempts");
    }

    private HttpResponse<String> send(HttpRequest request) {
        try {
            concurrencyGate.acquire();
            try {
                return http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            } finally {
                concurrencyGate.release();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted during Loki query", e);
        } catch (java.io.IOException e) {
            throw new IllegalStateException("Loki query I/O error", e);
        }
    }

    private JsonNode parseData(String body) {
        try {
            return objectMapper.readTree(body).path("data");
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalStateException("failed to parse Loki response", e);
        }
    }

    /** 쿼리 간 최소 간격(스로틀, doc/05 §2.4). */
    private void throttle() {
        sleep(props.loki().minQueryInterval().toMillis());
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
