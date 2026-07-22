// 응답 JSON 의 모든 배열을 {count, items} 로 감싸는 변환 (사용자 요청) — 파서가 항상 .count/.items 로 읽도록
package com.pentasecurity.apidiscover.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.Iterator;
import java.util.Map;

/**
 * JSON 트리의 <b>모든 배열</b>을 {@code {"count":n,"items":[...]}} 로 재귀 변환한다(사용자 요청).
 * 필드 값 배열·최상위 배열·배열 원소(객체)의 내부 배열·중첩 배열 모두 동일 규칙 적용 →
 * 소비자는 어디서든 {@code .count}·{@code .items} 두 키만 읽으면 된다. 원본 트리는 불변(새 트리 반환).
 */
public final class ArrayCountJson {

    private ArrayCountJson() {
    }

    /** node 를 재귀 변환한 <b>새</b> JsonNode 반환. ObjectNode=필드별 재귀, ArrayNode={count,items}(원소도 재귀), 그 외=그대로. */
    public static JsonNode wrap(JsonNode node) {
        JsonNodeFactory f = JsonNodeFactory.instance;
        if (node instanceof ObjectNode obj) {
            ObjectNode out = f.objectNode();
            Iterator<Map.Entry<String, JsonNode>> it = obj.fields();
            while (it.hasNext()) {
                Map.Entry<String, JsonNode> e = it.next();
                out.set(e.getKey(), wrap(e.getValue()));
            }
            return out;
        }
        if (node instanceof ArrayNode arr) {
            ArrayNode items = f.arrayNode(arr.size());
            for (JsonNode elem : arr) {
                items.add(wrap(elem)); // 원소가 객체/배열이면 그 내부 배열도 재귀 래핑
            }
            ObjectNode w = f.objectNode();
            w.put("count", arr.size());
            w.set("items", items);
            return w;
        }
        return node; // value node
    }
}
