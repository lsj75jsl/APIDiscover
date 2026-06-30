// API 파라미터 변경 delta — added/removed/modified (doc/38 §3). last_change_detail_json 직렬화·changedParams 노출
package com.pentasecurity.apidiscover.spec;

import com.pentasecurity.apidiscover.model.ParamIn;
import com.pentasecurity.apidiscover.model.SpecParam;
import java.util.List;

/** UPDATED 한 엔드포인트의 param-level delta. (name,in) 키로 added/removed/modified 분류. */
public record ParamChange(
        List<SpecParam> added,
        List<SpecParam> removed,
        List<ParamModification> modified) {

    public ParamChange {
        added = (added == null) ? List.of() : List.copyOf(added);
        removed = (removed == null) ? List.of() : List.copyOf(removed);
        modified = (modified == null) ? List.of() : List.copyOf(modified);
    }

    public boolean isEmpty() {
        return added.isEmpty() && removed.isEmpty() && modified.isEmpty();
    }

    /** 변경된 파라미터 1건 — (name,in) 동일·required/type 전이(doc/38 §3.4). */
    public record ParamModification(
            String name,
            ParamIn in,
            boolean fromRequired,
            boolean toRequired,
            String fromType,
            String toType) {}
}
