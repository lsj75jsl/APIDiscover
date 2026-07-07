// 요청 1건의 query param 관측 — 이름 + 값 길이 버킷(값 미저장) (doc/13 §2.1)
package com.pentasecurity.apidiscover.model;

public record QueryParamObs(String name, ValueLenBucket lenBucket) {
    // record — 필드는 헤더 괄호에 선언, 본문 비움이 정상(순수 데이터, dead 아님).
}
