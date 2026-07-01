// 정적 파일 분류 규칙 종류 — 확장자 / 파일명 토큰 (D56 외부 설정)
package com.pentasecurity.apidiscover.domain;

public enum StaticRuleKind {
    /** 정적 확장자(.css/.js/.png…) — 경로 endsWith 매치 → 하드 veto 대상(STATIC). */
    EXTENSION,
    /** 정적 리소스 파일명 토큰(img/css/link…) — 마지막 세그먼트 contains 매치 → 큰 감점. */
    NAME_TOKEN
}
