// 카디널리티 상한 초과로 drop 된 수 (host template / endpoint param) (doc/13 §1.2)
package com.pentasecurity.apidiscover.model;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * 상한 초과 drop 집계. {@code DroppedNonApi} 패턴 재사용 — DiscoveryReport top-level + ETag 포함.
 * 조용한 누락 금지: 카디널리티 폭발을 운영자가 인지하도록 항상 노출.
 */
public record DroppedByLimit(int templates, int params) {

    /** 빈 결과 (상한 미발동 시, 항상 non-null). */
    public static final DroppedByLimit NONE = new DroppedByLimit(0, 0);

    @JsonProperty("total")
    public int total() {
        return templates + params;
    }
}
