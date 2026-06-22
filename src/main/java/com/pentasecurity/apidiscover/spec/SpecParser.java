// 포맷별 스펙 파서 공통 인터페이스 → Canonical 변환 (doc/03)
package com.pentasecurity.apidiscover.spec;

import com.pentasecurity.apidiscover.model.CanonicalEndpoint;
import java.util.List;

public interface SpecParser {

    SpecFormat format();

    /** 원본 문서를 Canonical 엔드포인트 집합으로 변환. 검증 실패 시 예외. */
    List<CanonicalEndpoint> parse(byte[] content);
}
