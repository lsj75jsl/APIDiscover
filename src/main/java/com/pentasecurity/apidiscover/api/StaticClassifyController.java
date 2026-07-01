// 정적 분류 규칙(확장자·파일명 토큰) 관리 REST — 목록/추가/삭제/reload (D56)
package com.pentasecurity.apidiscover.api;

import com.pentasecurity.apidiscover.domain.StaticRuleKind;
import com.pentasecurity.apidiscover.normalize.StaticClassifyRules;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * 정적 파일 분류 규칙 관리(D56) — 관리자가 확장자/파일명 토큰을 DB 로 추가/삭제하고 런타임 reload.
 * 소스코드 하드코드 대신 외부 설정. 변경은 add/remove 시 즉시 재적용되며, 외부 DB 수정 시 {@code /reload} 로 반영.
 */
@RestController
@RequestMapping("/api/v1/config/static-classify")
public class StaticClassifyController {

    private final StaticClassifyRules rules;

    public StaticClassifyController(StaticClassifyRules rules) {
        this.rules = rules;
    }

    /** 현재 규칙 목록 — {extensions:[...], nameTokens:[...]}. */
    @GetMapping
    public Map<String, List<String>> list() {
        return Map.of(
                "extensions", rules.values(StaticRuleKind.EXTENSION),
                "nameTokens", rules.values(StaticRuleKind.NAME_TOKEN));
    }

    /** 규칙 추가(멱등) + 즉시 재적용. body={kind:EXTENSION|NAME_TOKEN, value:"..."}. */
    @PostMapping
    public ResponseEntity<Map<String, List<String>>> add(@RequestBody RuleRequest req) {
        rules.add(parseKind(req.kind()), requireValue(req.value()));
        return ResponseEntity.status(HttpStatus.CREATED).body(list());
    }

    /** 규칙 삭제 + 즉시 재적용. 없으면 404. */
    @DeleteMapping("/{kind}/{value}")
    public ResponseEntity<Void> remove(@PathVariable String kind, @PathVariable String value) {
        boolean removed = rules.remove(parseKind(kind), requireValue(value));
        return removed ? ResponseEntity.noContent().build() : ResponseEntity.notFound().build();
    }

    /** DB 규칙을 classifier 에 재적용(외부 DB 수정 반영용). */
    @PostMapping("/reload")
    public Map<String, List<String>> reload() {
        rules.reload();
        return list();
    }

    private static StaticRuleKind parseKind(String kind) {
        try {
            return StaticRuleKind.valueOf(kind.trim().toUpperCase());
        } catch (IllegalArgumentException | NullPointerException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "kind must be EXTENSION or NAME_TOKEN");
        }
    }

    private static String requireValue(String value) {
        if (value == null || value.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "value is required");
        }
        return value;
    }

    /** 규칙 추가 요청 본문. */
    public record RuleRequest(String kind, String value) {
    }
}
