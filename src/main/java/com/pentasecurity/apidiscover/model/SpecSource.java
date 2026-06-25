// 리포트의 스펙 출처/경고 노출 (doc/25 §A.2, doc/26 §4 멀티문서 확장). 업로드 시 결정된 메타를 스캔 리포트에 실음
package com.pentasecurity.apidiscover.model;

import com.pentasecurity.apidiscover.spec.SpecFormat;
import java.util.List;

/**
 * 합성 specVersion·format·파싱 warnings·문서별 메타(documents). warnings/documents 는 활성 문서 집합에서 결정 →
 * ETag 안정(데이터 ts 비의존). 항상 non-null({@link #EMPTY}=무스펙).
 * <ul>
 *   <li>format: 활성 문서 단일 포맷이면 그것, 혼합이면 null (doc/26 §9).</li>
 *   <li>warnings: 활성 문서별 union(dedupe).</li>
 *   <li>documents: 활성 문서별 (specName, format, specVersion).</li>
 * </ul>
 */
public record SpecSource(long specVersion, SpecFormat format, List<String> warnings, List<SpecDocument> documents) {

    public static final SpecSource EMPTY = new SpecSource(0L, null, List.of(), List.of());

    /** 멀티 스펙 문서별 메타 (doc/26 §4). specName=문서/그룹 라벨, specVersion=per-record 이력값. */
    public record SpecDocument(String specName, SpecFormat format, long specVersion) {}

    public SpecSource {
        warnings = (warnings == null) ? List.of() : List.copyOf(warnings);
        documents = (documents == null) ? List.of() : List.copyOf(documents);
    }

    /** 하위호환 3-arg — documents 빈 list(단일/레거시 경로). */
    public SpecSource(long specVersion, SpecFormat format, List<String> warnings) {
        this(specVersion, format, warnings, List.of());
    }
}
