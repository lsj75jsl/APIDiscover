// 시그니처별 트래픽 누적기 (InventoryBuilder/CardinalityNormalizer 공유, doc/13 §1·§4)
package com.pentasecurity.apidiscover.normalize;

import com.pentasecurity.apidiscover.model.DiscoveredEndpoint;
import com.pentasecurity.apidiscover.model.EndpointKind;
import com.pentasecurity.apidiscover.model.ParamCandidates;
import com.pentasecurity.apidiscover.model.ParsedRequest;
import com.pentasecurity.apidiscover.model.QueryParamObs;
import com.pentasecurity.apidiscover.model.TemplateSource;
import com.pentasecurity.apidiscover.model.ValueLenBucket;
import java.time.Instant;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.apache.datasketches.hll.HllSketch;
import org.apache.datasketches.hll.Union;
import org.apache.datasketches.kll.KllDoublesSketch;
import org.apache.datasketches.quantilescommon.QuantileSearchCriteria;

/** 시그니처(method+host+template) 단위 메트릭/쿼리 관측 누적기. package-private(정규화 계층 내부). */
final class Acc {

    private static final String[] SDK_UA = {
            "okhttp", "python-requests", "axios", "java/", "go-http", "curl", "wget",
            "postman", "apache-httpclient", "node-fetch", "dart", "ktor", "feign", "httpie"};

    // 근사 자료구조 크기(1차값, 코드 상수). 튜닝 seam = NormalizationProperties @ConfigurationProperties(D12 정적 우선, doc/22 §1).
    private static final int HLL_LG_K = 12;  // distinct client IP — RSE≈1.6%, 소-N exact(<=1 경계 무오차)
    private static final int KLL_K = 200;    // 응답시간 분위수 — rank err≈1.65%

    private final String method;
    private final String host;
    private final String template;
    private TemplateSource source;

    private long hits;
    private Instant firstSeen;
    private Instant lastSeen;
    private final long[] statusBuckets = new long[4]; // 0:2xx 1:3xx 2:4xx 3:5xx
    private long status404; // 404 전용(통합 4xx 와 별도 — 401/403 보호, doc/19 §1)
    private long acrmPresentCount; // acrm(Access-Control-Request-Method) 관측 수 = CORS preflight (doc/23 §9 M3)
    private HllSketch clientHll = new HllSketch(HLL_LG_K);   // distinct client IP 근사(merge 시 재할당)
    private final KllDoublesSketch respKll = KllDoublesSketch.newHeapInstance(KLL_K); // 응답시간 분위수 근사
    private final Map<String, Long> typeDist = new HashMap<>();
    // 8.3 응답 Content-Type 분포 — 2xx 응답만·정규화 후 키 (doc/40 §4.3 가드①⑤). endpoint_kind 소스.
    private final Map<String, Long> contentTypeDist = new HashMap<>();
    private boolean hadQuery;
    private long sdkUaCount;
    // 8.3 요청측 신호 presence 카운트(다수결 count*2>=hits 입력, nonBrowserUa 선례, doc/40 §3·§6).
    private long acceptJsonCount;
    private long xhrCount;
    private long originCount;
    private long authSchemeCount;
    /** query param 이름 → presence count + 관측 값 길이 버킷 집합 (T2). */
    private final Map<String, ParamObs> queryObs = new LinkedHashMap<>();

    Acc(String method, String host, String template, TemplateSource source) {
        this.method = method;
        this.host = host;
        this.template = template;
        this.source = source;
    }

    String method() {
        return method;
    }

    String host() {
        return host;
    }

    String template() {
        return template;
    }

    long hits() {
        return hits;
    }

    TemplateSource source() {
        return source;
    }

    /** 404-only(전 요청이 404) = 비실재 시그니처 (doc/19 §1). 통합 4xx 아닌 404 전용이라 401/403-only 는 보존. */
    boolean isNonExistent() {
        return hits > 0 && status404 == hits;
    }

    Map<String, Long> typeDist() {
        return typeDist;
    }

    /** 응답 CT 분포(2xx 만·정규화) — EndpointKindClassifier CT 분기 입력(doc/40 §4.3). 미수집 시 빈 맵 → 폴백. */
    Map<String, Long> contentTypeDist() {
        return contentTypeDist;
    }

    Map<String, ParamObs> queryObs() {
        return queryObs;
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
        if (r.status() == 404) {
            status404++;
        }
        if (r.acrm() != null) {
            acrmPresentCount++;
        }
        // 8.3 응답 CT: 2xx 응답만 분포 누적(§4.3 가드① — 에러/3xx html·인증실패 오염 차단, 401/403-only 는 빈 dist 로 폴백).
        if (bucket == 2) {
            String ct = normalizeContentType(r.responseContentType());
            if (ct != null) {
                contentTypeDist.merge(ct, 1L, Long::sum);
            }
        }
        // 8.3 요청측 신호 presence(다수결 입력) — 부재(null) 시 미가산 → dormant.
        if (isAcceptJson(r.accept())) {
            acceptJsonCount++;
        }
        if (isXhr(r.xRequestedWith())) {
            xhrCount++;
        }
        if (r.origin() != null) {
            originCount++;
        }
        if (r.authScheme() != null) {
            authSchemeCount++;
        }
        if (r.clientIp() != null) {
            clientHll.update(r.clientIp());
        }
        List<QueryParamObs> qps = r.queryParams();
        if (qps != null && !qps.isEmpty()) {
            hadQuery = true;
            for (QueryParamObs qp : qps) {
                ParamObs obs = queryObs.computeIfAbsent(qp.name(), k -> new ParamObs());
                obs.count++;
                obs.buckets.add(qp.lenBucket());
            }
        }
        if (isSdkUserAgent(r.userAgent())) {
            sdkUaCount++;
        }
        respKll.update((double) r.respTimeMs());
    }

    /** 다른 Acc 를 흡수(통계 {var} 승격 시 형제 재병합). 병합 결과 source=INFERRED. */
    void mergeFrom(Acc o) {
        this.hits += o.hits;
        if (o.firstSeen != null && (firstSeen == null || o.firstSeen.isBefore(firstSeen))) {
            firstSeen = o.firstSeen;
        }
        if (o.lastSeen != null && (lastSeen == null || o.lastSeen.isAfter(lastSeen))) {
            lastSeen = o.lastSeen;
        }
        for (int i = 0; i < statusBuckets.length; i++) {
            statusBuckets[i] += o.statusBuckets[i];
        }
        this.status404 += o.status404;
        this.acrmPresentCount += o.acrmPresentCount;
        Union union = new Union(HLL_LG_K); // HLL 합집합(중복 client 제거 — HashSet.addAll 의미 보존)
        union.update(this.clientHll);
        union.update(o.clientHll);
        this.clientHll = union.getResult();
        respKll.merge(o.respKll); // KLL 분포 결합(ArrayList.addAll 의미의 근사)
        o.typeDist.forEach((k, v) -> typeDist.merge(k, v, Long::sum));
        o.contentTypeDist.forEach((k, v) -> contentTypeDist.merge(k, v, Long::sum));
        hadQuery |= o.hadQuery;
        sdkUaCount += o.sdkUaCount;
        acceptJsonCount += o.acceptJsonCount;
        xhrCount += o.xhrCount;
        originCount += o.originCount;
        authSchemeCount += o.authSchemeCount;
        o.queryObs.forEach((name, obs) -> {
            ParamObs into = queryObs.computeIfAbsent(name, k -> new ParamObs());
            into.count += obs.count;
            into.buckets.addAll(obs.buckets);
        });
        this.source = TemplateSource.INFERRED; // 승격/병합 결과는 추론
    }

    DiscoveredEndpoint toEndpoint(EndpointKind kind, double kindConfidence, ParamCandidates params) {
        Map<String, Long> statusDist = new LinkedHashMap<>();
        statusDist.put("2xx", statusBuckets[0]);
        statusDist.put("3xx", statusBuckets[1]);
        statusDist.put("4xx", statusBuckets[2]);
        statusDist.put("5xx", statusBuckets[3]);

        // 근사: distinctClients=HLL, p50/p95=KLL (doc/22). acrmPresentCount=preflight 게이트 입력(doc/23 §9). Metrics long shape.
        var metrics = new DiscoveredEndpoint.Metrics(
                hits, firstSeen, lastSeen, statusDist,
                Math.round(clientHll.getEstimate()), quantileMs(0.5), quantileMs(0.95), acrmPresentCount);

        String signature = method + " " + host + " " + template;
        boolean nonBrowserUa = sdkUaCount * 2 >= hits; // 다수가 SDK/CLI
        // 8.3 요청측 신호: 다수결(count*2>=hits, nonBrowserUa 선례) — 스캐너 단발 flip 차단(doc/40 §6 리뷰 P2).
        boolean acceptJson = acceptJsonCount * 2 >= hits;
        boolean xRequestedWith = xhrCount * 2 >= hits;
        boolean originHeader = originCount * 2 >= hits;
        boolean authScheme = authSchemeCount * 2 >= hits;
        return new DiscoveredEndpoint(
                signature, method, host, template, source, kind, kindConfidence,
                hadQuery, nonBrowserUa, acceptJson, xRequestedWith, originHeader, authScheme, metrics, params);
    }

    /** 응답 CT 정규화(doc/40 §4.3 ⑤): ';' 파라미터(charset 등) 제거·trim·소문자. 빈 값 → null. */
    private static String normalizeContentType(String ct) {
        if (ct == null) {
            return null;
        }
        int semi = ct.indexOf(';');
        String base = (semi >= 0 ? ct.substring(0, semi) : ct).trim().toLowerCase(Locale.ROOT);
        return base.isEmpty() ? null : base;
    }

    /** Accept 값에 application/json 또는 +json 포함(대소문자 무시) → 발화. 와일드카드 기본값은 문자열 미포함이라 자연 미발화(doc/40 §6). */
    private static boolean isAcceptJson(String accept) {
        if (accept == null) {
            return false;
        }
        String a = accept.toLowerCase(Locale.ROOT);
        return a.contains("application/json") || a.contains("+json");
    }

    /** X-Requested-With: XMLHttpRequest (대소문자·주변 공백 무시). */
    private static boolean isXhr(String xrw) {
        return xrw != null && "xmlhttprequest".equals(xrw.trim().toLowerCase(Locale.ROOT));
    }

    private static boolean isSdkUserAgent(String ua) {
        if (ua == null) {
            return false;
        }
        String u = ua.toLowerCase(Locale.ROOT);
        for (String s : SDK_UA) {
            if (u.contains(s)) {
                return true;
            }
        }
        return false;
    }

    /** KLL 분위수(ms, 반올림). 빈 sketch → 0. INCLUSIVE = nearest-rank 와 동일 의미(소-N exact). */
    private long quantileMs(double rank) {
        return respKll.isEmpty() ? 0L : Math.round(respKll.getQuantile(rank, QuantileSearchCriteria.INCLUSIVE));
    }

    /** query param 관측 집계: presence count + 값 길이 버킷 집합. */
    static final class ParamObs {
        long count;
        final EnumSet<ValueLenBucket> buckets = EnumSet.noneOf(ValueLenBucket.class);
    }
}
