// 엔드포인트 종류 (doc/02 §5). web_page vs API 구분으로 Shadow 정밀도 개선
package com.pentasecurity.apidiscover.model;

public enum EndpointKind {
    WEB_PAGE,
    STATIC,
    API_CANDIDATE,
    UNKNOWN
}
