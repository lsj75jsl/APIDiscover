// 포맷별 스펙 파서 공통 인터페이스 → Canonical 변환 (doc/03, doc/25 §A.1)
package com.pentasecurity.apidiscover.spec;

public interface SpecParser {

    SpecFormat format();

    /** 원본 문서를 Canonical 엔드포인트 + recoverable 경고로 변환. fatal(무효 문서/필수 누락) 은 예외. */
    SpecParseResult parse(byte[] content);
}
