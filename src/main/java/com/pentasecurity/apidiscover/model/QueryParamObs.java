// 요청 1건의 query param 관측 — 이름 + 값 길이 버킷(값 미저장) (doc/13 §2.1)
package com.pentasecurity.apidiscover.model;

public record QueryParamObs(String name, ValueLenBucket lenBucket) {
}
