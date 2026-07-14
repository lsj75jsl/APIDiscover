// LokiClient 페이지네이션/백오프 테스트 — JDK HttpServer 로 Loki 모사
package com.pentasecurity.apidiscover.ingest;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pentasecurity.apidiscover.config.ApiDiscoverProperties;
import com.sun.net.httpserver.HttpExchange;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class LokiClientTest {

    private HttpServer server;
    private int port;

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop(0);
        }
    }

    private void startServer(HttpHandler handler) throws IOException {
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/loki/api/v1/query_range", handler);
        server.setExecutor(null);
        server.start();
        port = server.getAddress().getPort();
    }

    private LokiClient client() {
        ApiDiscoverProperties p = props("http://localhost:" + port, Duration.ofSeconds(5));
        LokiBudget budget = new LokiBudget(p, new SimpleMeterRegistry(), Clock.systemUTC());
        return new LokiClient(p, new ObjectMapper(), budget);
    }

    private final LogWindow window =
            new LogWindow(Instant.parse("2026-06-22T04:00:00Z"), Instant.parse("2026-06-22T04:10:00Z"));

    @Test
    void paginatesUntilPageBelowLimit() throws IOException {
        AtomicInteger calls = new AtomicInteger();
        startServer(ex -> {
            int call = calls.incrementAndGet();
            // pageLimit=2: 첫 페이지 2건(==limit → 계속), 둘째 1건(<limit → 종료)
            String json = (call == 1)
                    ? streams(new String[]{"line-1", "line-2"}, new long[]{100, 200})
                    : streams(new String[]{"line-3"}, new long[]{300});
            respond(ex, 200, json);
        });

        List<String> lines = client().queryRange("{job=\"access_log\"}", window);

        assertThat(lines).containsExactly("line-1", "line-2", "line-3");
        assertThat(calls.get()).isEqualTo(2);
    }

    @Test
    void retriesOn429ThenSucceeds() throws IOException {
        AtomicInteger calls = new AtomicInteger();
        startServer(ex -> {
            int call = calls.incrementAndGet();
            if (call == 1) {
                respond(ex, 429, null);
            } else {
                respond(ex, 200, streams(new String[]{"ok-1"}, new long[]{100}));
            }
        });

        List<String> lines = client().queryRange("{job=\"access_log\"}", window);

        assertThat(lines).containsExactly("ok-1");
        assertThat(calls.get()).isEqualTo(2); // 429 한 번 재시도 후 성공
    }

    @Test
    void failsAfterMaxRetries() throws IOException {
        startServer(ex -> respond(ex, 503, null));
        try {
            client().queryRange("{job=\"access_log\"}", window);
            assertThat(false).as("should have thrown").isTrue();
        } catch (IllegalStateException e) {
            assertThat(e.getMessage()).contains("HTTP 503");
        }
    }

    @Test
    void timeoutEscalatesThrottleAndCountsAsFailure() throws IOException {
        // 응답을 query-timeout(100ms)보다 늦게 → HttpTimeoutException → 실패 계상 + 적응형 감속 (D58)
        startServer(ex -> {
            try {
                Thread.sleep(400);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
            }
            respond(ex, 200, streams(new String[]{"late"}, new long[]{100}));
        });
        SimpleMeterRegistry reg = new SimpleMeterRegistry();
        ApiDiscoverProperties p = props("http://localhost:" + port, Duration.ofMillis(100));
        LokiClient c = new LokiClient(p, new ObjectMapper(), new LokiBudget(p, reg, Clock.systemUTC()));

        try {
            c.queryRange("{job=\"access_log\"} |= `example.com`", window);
            assertThat(false).as("타임아웃 시 예외 발생해야 함").isTrue();
        } catch (IllegalStateException e) {
            assertThat(e.getMessage()).contains("I/O error");
        }

        assertThat(reg.get("loki.queries").counter().count()).isGreaterThanOrEqualTo(1.0);   // 실패도 쿼리로 계상
        assertThat(reg.get("loki.errors").tag("status", "io").counter().count())
                .isGreaterThanOrEqualTo(1.0);                                                // 에러 메트릭
        assertThat(c.currentThrottleLevel()).isGreaterThanOrEqualTo(1);                      // ★ Loki 지연→감속 발동
    }

    // --- helpers ---

    private static String streams(String[] lines, long[] ts) {
        StringBuilder vals = new StringBuilder();
        for (int i = 0; i < lines.length; i++) {
            if (i > 0) {
                vals.append(',');
            }
            vals.append("[\"").append(ts[i]).append("\",\"").append(lines[i]).append("\"]");
        }
        return "{\"status\":\"success\",\"data\":{\"resultType\":\"streams\",\"result\":["
                + "{\"stream\":{\"job\":\"access_log\"},\"values\":[" + vals + "]}]}}";
    }

    private static void respond(HttpExchange ex, int status, String body) {
        try {
            if (body == null) {
                ex.sendResponseHeaders(status, -1);
                ex.close();
                return;
            }
            byte[] b = body.getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(status, b.length);
            try (OutputStream os = ex.getResponseBody()) {
                os.write(b);
            }
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    private static ApiDiscoverProperties props(String addr, Duration queryTimeout) {
        return new ApiDiscoverProperties(
                new ApiDiscoverProperties.Loki(addr, "access_log",
                        queryTimeout,            // query-timeout (테스트별 주입)
                        Duration.ofHours(1),     // chunk-window (단일 chunk)
                        2,                       // page-limit
                        2,                       // max-concurrent-queries
                        Duration.ofMillis(5)),   // min-query-interval (빠른 스로틀/백오프)
                new ApiDiscoverProperties.Schedule(Duration.ofHours(1), Duration.ofMinutes(10),
                        Duration.ofDays(7), "01:00-06:00"),
                new ApiDiscoverProperties.Central("https://central.internal"),
                new ApiDiscoverProperties.Discovery(true, Duration.ofMinutes(10), Duration.ofMinutes(12),
                        Duration.ofHours(1), Duration.ofMinutes(2), 200,
                        "^[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?(\\.[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?)+$", java.util.List.of(), java.util.List.of()),
                new ApiDiscoverProperties.Scan(Duration.ofMinutes(5), 100, Duration.ZERO, 0, 0L, true, Duration.ZERO, 0, false, Duration.ofMinutes(30), Duration.ofHours(2), Duration.ofHours(6), Duration.ofHours(24), 500, Duration.ofHours(24), "", Duration.ofDays(14), Duration.ofDays(1), Duration.ZERO, 0, false, Duration.ZERO));
    }
}
