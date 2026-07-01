// 정적 분류 규칙 로더 — DB(static_classify_rule) → EndpointKindClassifier 적용, seed·reload·CRUD (D56)
package com.pentasecurity.apidiscover.normalize;

import com.pentasecurity.apidiscover.domain.StaticClassifyRule;
import com.pentasecurity.apidiscover.domain.StaticClassifyRuleRepository;
import com.pentasecurity.apidiscover.domain.StaticRuleKind;
import jakarta.annotation.PostConstruct;
import java.util.List;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 정적 파일 분류 규칙(확장자·파일명 토큰)을 DB 에서 로드해 {@link EndpointKindClassifier} 에 적용(D56).
 * 하드코드 대신 외부 DB 로 관리 → 관리자가 REST({@code /api/v1/config/static-classify})로 추가/삭제 후 reload 로 런타임 반영.
 * <p>첫 기동 시 테이블이 비어 있으면 {@code EndpointKindClassifier} 기본값을 seed. reload/add/remove 는 항상 DB→classifier 재적용.
 */
@Component
public class StaticClassifyRules {

    private final StaticClassifyRuleRepository repo;

    public StaticClassifyRules(StaticClassifyRuleRepository repo) {
        this.repo = repo;
    }

    /** 기동 시 1회: 빈 테이블이면 기본값 seed 후 DB → classifier 적용. */
    @PostConstruct
    @Transactional
    public void init() {
        seedIfEmpty();
        reload();
    }

    /** DB 규칙을 classifier 에 재적용(런타임 교체). 관리자 편집·외부 DB 수정 후 호출. */
    @Transactional(readOnly = true)
    public void reload() {
        EndpointKindClassifier.applyRules(values(StaticRuleKind.EXTENSION), values(StaticRuleKind.NAME_TOKEN));
    }

    /** 현재 DB 규칙 목록(조회용). */
    @Transactional(readOnly = true)
    public List<String> values(StaticRuleKind kind) {
        return repo.findByKind(kind).stream().map(StaticClassifyRule::getValue).sorted().toList();
    }

    /** 규칙 추가(멱등) 후 재적용. EXTENSION 은 leading '.' 보정. 빈 값 거부. */
    @Transactional
    public void add(StaticRuleKind kind, String rawValue) {
        String v = normalize(kind, rawValue);
        if (v.isEmpty()) {
            throw new IllegalArgumentException("value is required");
        }
        if (!repo.existsByKindAndValue(kind, v)) {
            repo.save(new StaticClassifyRule(kind, v));
        }
        reload();
    }

    /** 규칙 삭제 후 재적용. 반환 true=삭제됨. */
    @Transactional
    public boolean remove(StaticRuleKind kind, String rawValue) {
        long n = repo.deleteByKindAndValue(kind, normalize(kind, rawValue));
        reload();
        return n > 0;
    }

    private void seedIfEmpty() {
        if (repo.count() > 0) {
            return;
        }
        for (String ext : EndpointKindClassifier.DEFAULT_STATIC_EXT) {
            repo.save(new StaticClassifyRule(StaticRuleKind.EXTENSION, ext));
        }
        for (String token : EndpointKindClassifier.DEFAULT_NAME_TOKENS) {
            repo.save(new StaticClassifyRule(StaticRuleKind.NAME_TOKEN, token));
        }
    }

    /** trim+소문자. EXTENSION 은 leading '.' 없으면 보정(endsWith 매치 정확도). */
    private static String normalize(StaticRuleKind kind, String raw) {
        if (raw == null) {
            return "";
        }
        String v = raw.trim().toLowerCase();
        if (kind == StaticRuleKind.EXTENSION && !v.isEmpty() && !v.startsWith(".")) {
            v = "." + v;
        }
        return v;
    }
}
