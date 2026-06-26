// 검출 endpoint 식별 키 — DB unique(host,method,path_template) 제약·priorFirstSeen·upsert·recency 공통 단일 진실원 (doc/26 §2)
package com.pentasecurity.apidiscover.model;

public final class EndpointIdentity {

    private EndpointIdentity() {
    }

    /**
     * 식별 키 "{METHOD} {host} {pathTemplate}" — DB unique 제약 튜플과 동일. upsert dedup·priorFirstSeen·recency lookup 이
     * 모두 이 키로 통일돼 {@code DiscoveredEndpoint.signature} 발산(T1 {var} 승격 전 template·파싱 host)과 무관하게 정합한다.
     */
    public static String key(String method, String host, String pathTemplate) {
        return method + " " + host + " " + pathTemplate;
    }
}
