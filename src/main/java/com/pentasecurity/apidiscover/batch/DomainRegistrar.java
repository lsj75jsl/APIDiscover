// 단일 도메인 수동 등록 헬퍼 — 정규화 host 로 없으면 enabled=true 등록(멱등). CLI -register / -scan 자동등록 공유(Loki 미호출)
package com.pentasecurity.apidiscover.batch;

import com.pentasecurity.apidiscover.domain.DomainConfig;
import com.pentasecurity.apidiscover.domain.DomainConfigRepository;
import com.pentasecurity.apidiscover.util.DomainNames;
import java.time.Instant;
import org.springframework.stereotype.Component;

/**
 * 수동 등록 단일 진입점 — {@code -domain -register} 와 {@code -domain -scan} 미등록 자동등록이 공유한다(중복 방지).
 *
 * <p>★자동 디스커버리({@link DomainUpserter})와 분리한 이유: upsert 는 {@code discoveredAt}=now 를 찍어
 * 자동 발견 도메인으로 표시하지만(doc/30 §5), 수동 등록 도메인은 {@code discoveredAt=null}(자연 구분, DomainConfig).
 * REST {@link com.pentasecurity.apidiscover.api.DomainController#create}는 409·ResponseStatusException 이라 CLI 부적합.
 * hostnames 등 나머지는 기본값 — 디스커버리·스캔이 이후 채운다(YAGNI).
 */
@Component
public class DomainRegistrar {

    private final DomainConfigRepository repo;

    public DomainRegistrar(DomainConfigRepository repo) {
        this.repo = repo;
    }

    /**
     * 정규화 host 로 없으면 enabled=true 로 등록하고, 있으면 그대로 반환(멱등). {@code rawHost} null/빈/"-"=등록 불가 →
     * {@link IllegalArgumentException}. host 는 항상 정규화(lowercase·trim) 저장(doc/05 §2.2) — discovery·identity 키 정합.
     */
    public DomainConfig registerIfAbsent(String rawHost) {
        String host = DomainNames.normalize(rawHost);
        if (host == null) {
            throw new IllegalArgumentException("host required");
        }
        return repo.findById(host).orElseGet(() -> {
            DomainConfig d = new DomainConfig();
            d.setHost(host);
            d.setEnabled(true);           // enabled=true 명시(자동등록=즉시 활성). specMergeStrategy=MERGE 는 필드 기본값. discoveredAt=null=수동 등록.
            Instant now = Instant.now();
            d.setCreatedAt(now);
            d.setUpdatedAt(now);
            return repo.save(d);
        });
    }
}
