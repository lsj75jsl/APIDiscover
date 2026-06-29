// 스펙에서 추출한 API 파라미터 — 이름·위치·필수·타입요약 (doc/37 §2). 인벤토리 paramsJson 직렬화 단위
package com.pentasecurity.apidiscover.model;

/** {@code type}=스키마 요약 문자열(string·integer·array&lt;string&gt;·object 등). 전체 JSON Schema 미보유(과설계 방지, doc/37 §2). */
public record SpecParam(String name, ParamIn in, boolean required, String type) {
}
