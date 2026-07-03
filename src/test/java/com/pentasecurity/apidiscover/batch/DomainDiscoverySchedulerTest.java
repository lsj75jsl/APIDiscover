// DomainDiscoveryScheduler 단위 테스트 — enabled 토글(off→no-op)·예외 격리 (doc/30 §3)
package com.pentasecurity.apidiscover.batch;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.pentasecurity.apidiscover.config.ApiDiscoverProperties;
import java.time.Duration;
import org.junit.jupiter.api.Test;

class DomainDiscoverySchedulerTest {

    private final DomainDiscoveryService service = mock(DomainDiscoveryService.class);

    @Test
    void runsDiscoveryWhenEnabled() {
        new DomainDiscoveryScheduler(service, props(true)).discover();
        verify(service, times(1)).discover(any());
    }

    @Test
    void noOpWhenDisabled() {
        new DomainDiscoveryScheduler(service, props(false)).discover();
        verify(service, never()).discover(any());
    }

    @Test
    void swallowsServiceExceptionSoSchedulerSurvives() {
        when(service.discover(any())).thenThrow(new IllegalStateException("loki down"));
        // 예외가 스케줄러 밖으로 전파되면 안 됨(보조 기능 격리)
        new DomainDiscoveryScheduler(service, props(true)).discover();
        verify(service, times(1)).discover(any());
    }

    private static ApiDiscoverProperties props(boolean enabled) {
        return new ApiDiscoverProperties(
                new ApiDiscoverProperties.Loki("http://loki.local:3200", "access_log",
                        Duration.ofSeconds(30), Duration.ofMinutes(10), 2000, 2, Duration.ofMillis(1)),
                new ApiDiscoverProperties.Schedule(Duration.ofHours(1), Duration.ofMinutes(10),
                        Duration.ofDays(7), "01:00-06:00"),
                new ApiDiscoverProperties.Central("https://central.internal"),
                new ApiDiscoverProperties.Discovery(enabled, Duration.ofMinutes(10), Duration.ofMinutes(12),
                        Duration.ofHours(1), Duration.ofMinutes(2), 200,
                        "^[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?(\\.[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?)+$", java.util.List.of()),
                new ApiDiscoverProperties.Scan(Duration.ofMinutes(5), 100, Duration.ZERO, 0, 0L, true, Duration.ZERO, 0, false, Duration.ofMinutes(30), Duration.ofHours(2), Duration.ofHours(6), Duration.ofHours(24), 500, Duration.ofHours(24), "", Duration.ofDays(14), Duration.ofDays(1), Duration.ZERO, 0, false));
    }
}
