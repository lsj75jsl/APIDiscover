// 인벤토리 실재성 필터로 제외된 404-only(비실재) 시그니처 수 (doc/19 §4)
package com.pentasecurity.apidiscover.model;

/**
 * 404-only(전 요청이 404) 라 인벤토리에서 hard-drop 된 INFERRED 시그니처 수.
 * 게이트 탈락({@link DroppedNonApi})·카디널리티 상한({@link DroppedByLimit})과 성격이 다른 실재성 필터.
 * 단순 제외가 아니라 노출(보안 도구 운영자 가시성) — DiscoveryReport top-level + ETag 포함(doc/19 §4).
 */
public record DroppedNonExistent(int notFound) {

    /** 빈 결과 (필터 미발동 시, 항상 non-null). */
    public static final DroppedNonExistent NONE = new DroppedNonExistent(0);
}
