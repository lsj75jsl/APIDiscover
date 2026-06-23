// 정규화 고카디널리티 방지 설정 — 통계 승격 임계·상한·값길이 버킷 경계 (doc/13 §3.3)
package com.pentasecurity.apidiscover.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * {@code apidiscover.normalization.*} 바인딩. 정적 인프라 정책(D12 정적→yml). 도메인 override·중앙 API 는 후속.
 */
@ConfigurationProperties(prefix = "apidiscover.normalization")
public record NormalizationProperties(
        @DefaultValue("5000") int maxTemplatesPerHost,         // host 당 distinct template 상한
        @DefaultValue("50") int maxQueryParamsPerEndpoint,     // endpoint 당 distinct query param 상한
        @DefaultValue("0.3") double statVarRatio,              // distinct/requests 승격 임계 (doc/02 §3.3)
        @DefaultValue("20") int statVarMinDistinct,            // 소표본 오승격 방지 최소 distinct
        @DefaultValue("0.7") double statVarMinConvergence,     // false merge 방지 수렴 임계 (doc/02 §3.4)
        @DefaultValue({"8", "32", "128"}) int[] valueLenBucketBounds  // value 길이 버킷 상한(포함): S≤8, M≤32, L≤128, XL>128
) {

    /** 테스트/기본 인스턴스 (yml 미바인딩 컨텍스트용). */
    public static NormalizationProperties defaults() {
        return new NormalizationProperties(5000, 50, 0.3, 20, 0.7, new int[]{8, 32, 128});
    }
}
