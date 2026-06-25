// 도메인 1건 무삭제 업서트 — managed 엔티티 단일 트랜잭션 (설정 lost-update 방지의 필수 조건, doc/30 §5 P3-1)
package com.pentasecurity.apidiscover.batch;

import com.pentasecurity.apidiscover.domain.DomainConfig;
import com.pentasecurity.apidiscover.domain.DomainConfigRepository;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 단일 도메인 무삭제 업서트를 <b>managed 엔티티 단일 트랜잭션</b>으로 수행한다(doc/30 §5).
 *
 * <p>★별도 빈인 이유: {@code @Transactional} 은 Spring 프록시 경유라 <b>같은 빈 내 self-invocation 은 무효</b>.
 * {@link DomainDiscoveryService} 가 이 빈을 주입·호출해야 프록시 트랜잭션이 적용된다.
 *
 * <p>★managed-tx 가 detached-merge 결함을 고치는 이유: findById→mutate→커밋이 한 트랜잭션이면 entity 가 내내 managed →
 * 커밋 시 dirty-check 가 <b>load 스냅샷</b> 기준으로 동작 → 안 건드린 설정 컬럼(basePathStrip 등)은 비-dirty → {@code @DynamicUpdate}
 * 가 UPDATE 에서 제외 → 동시 사용자 PUT 이 바꾼 설정을 되쓰지 않음. (비-tx 면 entity 가 detached → save=merge 가 stale 한
 * 전 필드를 fresh managed 에 복사 → 설정 컬럼이 dirty 로 잡혀 덮어씀 = P3-1 결함.)
 */
@Component
public class DomainUpserter {

    private final DomainConfigRepository repo;

    public DomainUpserter(DomainConfigRepository repo) {
        this.repo = repo;
    }

    /** 무삭제 업서트(§5). 신규=INSERT, 기존=hostnames 합집합·lastSeenAt 만(설정 컬럼 미터치). 반환 true=신규 INSERT. */
    @Transactional
    public boolean upsert(String domain, Set<String> discoveredHostnames, Instant now) {
        DomainConfig existing = repo.findById(domain).orElse(null);
        if (existing == null) {
            DomainConfig fresh = new DomainConfig();
            fresh.setHost(domain);                                  // enabled·specMergeStrategy 는 필드 기본값(true·MERGE)
            fresh.setHostnames(new ArrayList<>(new TreeSet<>(discoveredHostnames)));
            fresh.setDiscoveredAt(now);
            fresh.setLastSeenAt(now);
            repo.save(fresh);
            return true;
        }
        // managed 엔티티 — hostnames(합집합)·lastSeenAt 만 변경. 설정 컬럼은 손대지 않음 → dirty-check 제외.
        // 명시적 save 불요: tx 커밋 시 dirty 컬럼만 flush(@DynamicUpdate). 관리 컬렉션은 in-place 갱신(참조 교체 회피).
        Set<String> merged = new TreeSet<>();
        if (existing.getHostnames() != null) {
            merged.addAll(existing.getHostnames());
        }
        merged.addAll(discoveredHostnames);
        if (existing.getHostnames() == null) {
            existing.setHostnames(new ArrayList<>(merged));
        } else {
            List<String> hostnames = existing.getHostnames();
            hostnames.clear();
            hostnames.addAll(merged);
        }
        existing.setLastSeenAt(now);
        return false;
    }
}
