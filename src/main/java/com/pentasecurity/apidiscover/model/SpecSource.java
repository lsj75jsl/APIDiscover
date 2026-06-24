// 리포트의 스펙 출처/경고 노출 (doc/25 §A.2). 업로드 시 결정된 warnings 를 스캔 리포트에 실음
package com.pentasecurity.apidiscover.model;

import com.pentasecurity.apidiscover.spec.SpecFormat;
import java.util.List;

/**
 * specVersion·format·파싱 warnings. warnings 는 spec 버전당 고정(업로드 시 결정) → ETag 안정(신규 업로드=새 버전 bump 편승).
 * 항상 non-null({@link #EMPTY}=무스펙).
 */
public record SpecSource(long specVersion, SpecFormat format, List<String> warnings) {

    public static final SpecSource EMPTY = new SpecSource(0L, null, List.of());

    public SpecSource {
        warnings = (warnings == null) ? List.of() : List.copyOf(warnings);
    }
}
