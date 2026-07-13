// API 판단 스코어링 신호(14 weight + threshold + repeatMinCount)의 의미 설명 정적 사전 (ko/en, doc/39 D78)
package com.pentasecurity.apidiscover.classify;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 분류설정 조회 응답의 {@code descriptions} 블록 단일 진실원. 값은 순수 숫자로 두고(쓰기 바디 호환) 의미는 여기서만 관리.
 * 키 = {@link ApiScorer#WEIGHT_KEYS}(14) ∪ {@code threshold}·{@code repeatMinCount}. 매뉴얼(한글) 문구와 일치시킨다.
 */
public final class ScoringWeightCatalog {

    /** 신호 의미 한 줄 설명(한국어/영어). */
    public record Description(String ko, String en) {}

    /** 조회 응답용 불변 사전(선언 순서 유지 = threshold·repeatMinCount 먼저, 이후 14 weight). */
    public static final Map<String, Description> ALL = build();

    private ScoringWeightCatalog() {
    }

    private static Map<String, Description> build() {
        Map<String, Description> m = new LinkedHashMap<>();
        m.put("threshold", new Description(
                "API 판정 점수 임계 — 점수 ≥ 이면 API 후보(미만은 비-API)",
                "Score cutoff for API admission — score ≥ threshold ⇒ API candidate"));
        m.put("repeatMinCount", new Description(
                "repeatBonus 발화 최소 반복 횟수 (override 불가)",
                "Min repeat count to fire repeatBonus (not overridable)"));
        m.put("hostApiSubdomain", new Description(
                "호스트가 API 서브도메인(api.*)",
                "Host is an API subdomain (api.*)"));
        m.put("corsPreflight", new Description(
                "CORS preflight(OPTIONS) 관측",
                "CORS preflight (OPTIONS) observed"));
        m.put("apiSegment", new Description(
                "경로에 /api 세그먼트",
                "Path contains an /api segment"));
        m.put("graphqlSegment", new Description(
                "경로에 graphql/rpc/jsonrpc 세그먼트",
                "Path contains a graphql/rpc/jsonrpc segment"));
        m.put("versionSegment", new Description(
                "경로에 버전 세그먼트(v1, v2 …)",
                "Path contains a version segment (v1, v2, …)"));
        m.put("pathIdSegment", new Description(
                "경로에 ID형 치환 세그먼트({id}, {uuid} …)",
                "Path has an ID-like templated segment ({id}, {uuid}, …)"));
        m.put("machineEndpoint", new Description(
                "머신/운영 엔드포인트(health, metrics, ping …)",
                "Machine/ops endpoint (health, metrics, ping, …)"));
        m.put("writeMethod", new Description(
                "쓰기 메서드(POST/PUT/PATCH/DELETE)",
                "Write HTTP method (POST/PUT/PATCH/DELETE)"));
        m.put("query", new Description(
                "쿼리스트링 존재",
                "Has a query string"));
        m.put("nonBrowserUa", new Description(
                "비브라우저/SDK User-Agent(okhttp, python-requests …)",
                "Non-browser/SDK user-agent (okhttp, python-requests, …)"));
        m.put("staticAssetPenalty", new Description(
                "정적 리소스 파일명 토큰 감점(img, thumb …)",
                "Penalty for static-asset filename tokens (img, thumb, …)"));
        m.put("repeatBonus", new Description(
                "반복 접근 보너스(repeatMinCount 이상)",
                "Bonus for repeated access (≥ repeatMinCount)"));
        m.put("pathHint", new Description(
                "운영자 지정 API 경로 힌트 매칭",
                "Matches operator-configured API path hint"));
        m.put("responseTypeApi", new Description(
                "API성 $type 신호(xhr/fetch/json)",
                "API-like $type signal (xhr/fetch/json)"));
        m.put("acceptJson", new Description(
                "요청 Accept 가 application/json 다수(8.3)",
                "Request Accept is application/json (majority, 8.3)"));
        m.put("xRequestedWith", new Description(
                "X-Requested-With: XMLHttpRequest 다수(8.3, AJAX/XHR)",
                "X-Requested-With: XMLHttpRequest (majority, 8.3, AJAX/XHR)"));
        m.put("originHeader", new Description(
                "요청 Origin 헤더 존재 다수(8.3, cross-origin)",
                "Request Origin header present (majority, 8.3, cross-origin)"));
        m.put("authScheme", new Description(
                "Authorization 스킴(bearer/basic 등) 존재 다수(8.3)",
                "Authorization scheme (bearer/basic, …) present (majority, 8.3)"));
        return Collections.unmodifiableMap(m); // LinkedHashMap 순서 보존(threshold·repeatMinCount 먼저)
    }
}
