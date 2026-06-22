// ParsedRequest 스트림 → Discovered 인벤토리(D) 집계 (doc/02 §3, §4)
package com.pentasecurity.apidiscover.normalize;

import com.pentasecurity.apidiscover.match.EndpointMatcher;
import com.pentasecurity.apidiscover.model.CanonicalEndpoint;
import com.pentasecurity.apidiscover.model.DiscoveredEndpoint;
import com.pentasecurity.apidiscover.model.EndpointKind;
import com.pentasecurity.apidiscover.model.ParsedRequest;
import com.pentasecurity.apidiscover.model.TemplateSource;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.springframework.stereotype.Component;

@Component
public class InventoryBuilder {

    private final PathNormalizer pathNormalizer;
    private final EndpointKindClassifier kindClassifier;

    public InventoryBuilder(PathNormalizer pathNormalizer, EndpointKindClassifier kindClassifier) {
        this.pathNormalizer = pathNormalizer;
        this.kindClassifier = kindClassifier;
    }

    /**
     * 시그니처별로 메트릭을 집계해 Discovered 집합(D)을 만든다.
     *
     * <p>정규화는 2단계(doc/02 §3): 스펙이 있으면 matcher 로 우선 매칭(SPEC),
     * 매칭 실패 시 휴리스틱 추론(INFERRED). matcher 가 null 이면 전부 휴리스틱.
     */
    public List<DiscoveredEndpoint> build(List<ParsedRequest> requests, EndpointMatcher matcher) {
        Map<String, Acc> bySignature = new LinkedHashMap<>();

        for (ParsedRequest r : requests) {
            Resolved resolved = resolveTemplate(r, matcher);
            String signature = r.method() + " " + r.host() + " " + resolved.template();
            Acc acc = bySignature.computeIfAbsent(signature,
                    k -> new Acc(r.method(), r.host(), resolved.template(), resolved.source()));
            acc.add(r);
        }

        List<DiscoveredEndpoint> result = new ArrayList<>(bySignature.size());
        for (Acc acc : bySignature.values()) {
            EndpointKindClassifier.KindResult kind =
                    kindClassifier.classify(acc.template(), acc.typeDist());
            result.add(acc.toEndpoint(kind.kind(), kind.confidence()));
        }
        return result;
    }

    /** 스펙 우선 매칭 → 없으면 휴리스틱. */
    private Resolved resolveTemplate(ParsedRequest r, EndpointMatcher matcher) {
        if (matcher != null) {
            Optional<CanonicalEndpoint> matched = matcher.match(r.method(), r.host(), r.rawPath());
            if (matched.isPresent()) {
                return new Resolved(matched.get().pathTemplate(), TemplateSource.SPEC);
            }
        }
        return new Resolved(pathNormalizer.inferTemplate(r.rawPath()), TemplateSource.INFERRED);
    }

    private record Resolved(String template, TemplateSource source) {}

    private static final String[] SDK_UA = {
            "okhttp", "python-requests", "axios", "java/", "go-http", "curl", "wget",
            "postman", "apache-httpclient", "node-fetch", "dart", "ktor", "feign", "httpie"};

    /** SDK/CLI 클라이언트 user-agent 여부 (프로그램적 호출 신호). */
    private static boolean isSdkUserAgent(String ua) {
        if (ua == null) {
            return false;
        }
        String u = ua.toLowerCase();
        for (String s : SDK_UA) {
            if (u.contains(s)) {
                return true;
            }
        }
        return false;
    }

    /** 시그니처별 누적기. */
    private static final class Acc {
        private final String method;
        private final String host;
        private final String template;
        private final TemplateSource source;

        private long hits;
        private Instant firstSeen;
        private Instant lastSeen;
        private final long[] statusBuckets = new long[4]; // 0:2xx 1:3xx 2:4xx 3:5xx
        private final Set<String> clients = new HashSet<>();
        private final List<Long> respTimes = new ArrayList<>();
        private final Map<String, Long> typeDist = new HashMap<>(); // $type 분포 (endpoint_kind)
        private boolean hadQuery;
        private long sdkUaCount; // SDK/CLI user-agent 관측 수

        Acc(String method, String host, String template, TemplateSource source) {
            this.method = method;
            this.host = host;
            this.template = template;
            this.source = source;
        }

        String template() {
            return template;
        }

        Map<String, Long> typeDist() {
            return typeDist;
        }

        void add(ParsedRequest r) {
            hits++;
            if (r.type() != null) {
                typeDist.merge(r.type(), 1L, Long::sum);
            }
            Instant ts = r.ts();
            if (ts != null) {
                if (firstSeen == null || ts.isBefore(firstSeen)) {
                    firstSeen = ts;
                }
                if (lastSeen == null || ts.isAfter(lastSeen)) {
                    lastSeen = ts;
                }
            }
            int bucket = r.status() / 100;
            if (bucket >= 2 && bucket <= 5) {
                statusBuckets[bucket - 2]++;
            }
            if (r.clientIp() != null) {
                clients.add(r.clientIp());
            }
            if (r.queryKeys() != null && !r.queryKeys().isEmpty()) {
                hadQuery = true;
            }
            if (isSdkUserAgent(r.userAgent())) {
                sdkUaCount++;
            }
            respTimes.add(r.respTimeMs());
        }

        DiscoveredEndpoint toEndpoint(EndpointKind kind, double kindConfidence) {
            Map<String, Long> statusDist = new LinkedHashMap<>();
            statusDist.put("2xx", statusBuckets[0]);
            statusDist.put("3xx", statusBuckets[1]);
            statusDist.put("4xx", statusBuckets[2]);
            statusDist.put("5xx", statusBuckets[3]);

            Collections.sort(respTimes);
            // TODO(doc/02 §4): 대용량은 distinctClients=HLL, 분위수=KLL 근사로 교체
            var metrics = new DiscoveredEndpoint.Metrics(
                    hits, firstSeen, lastSeen, statusDist,
                    clients.size(), percentile(respTimes, 50), percentile(respTimes, 95));

            String signature = method + " " + host + " " + template;
            boolean nonBrowserUa = sdkUaCount * 2 >= hits; // 다수가 SDK/CLI
            return new DiscoveredEndpoint(
                    signature, method, host, template, source, kind, kindConfidence,
                    hadQuery, nonBrowserUa, metrics);
        }

        private static long percentile(List<Long> sorted, double p) {
            if (sorted.isEmpty()) {
                return 0L;
            }
            int idx = (int) Math.ceil(p / 100.0 * sorted.size()) - 1;
            idx = Math.max(0, Math.min(sorted.size() - 1, idx));
            return sorted.get(idx);
        }
    }
}
