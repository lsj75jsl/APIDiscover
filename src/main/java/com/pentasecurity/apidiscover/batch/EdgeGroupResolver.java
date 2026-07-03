// 엣지 그룹 Master 해석 — 그룹(단일/이중화/3중화+)의 Main 엣지로 조회를 수렴 (D65, 사용자 규칙 4유형)
package com.pentasecurity.apidiscover.batch;

import com.pentasecurity.apidiscover.domain.DomainConfigRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/**
 * 엣지는 그룹(단일/이중화/3중화 이상)으로 운용되고 <b>Master 엣지 로그에 그룹 전체 요청이 집계</b>된다(사용자 확정 ㉮).
 * 따라서 스캔 조회는 엣지를 그룹 Master 로 치환해 1곳만 읽는다 — 활성 조회 매핑 −47%(시뮬레이션, D65).
 * 치환(매핑)이므로 replica 에만 매핑된 도메인(고아 ~6.9k)도 Master 조회로 자연 커버.
 *
 * <p>그룹 규칙(사용자 정의 4유형, 우선순위 순).
 * <ul>
 *   <li>유형1 {@code ^[A-Za-z]OC…}: 그룹 = {@code (M|S)[0-9]} 접미를 뗀 이름. Master = {@code M[0-9]} 접미 멤버
 *       (M=Master/S=Slave, 사용자 확정 — 예: AOC1-NRT-2509M0 ← AOC1-NRT-2509S1/S2). M 멤버 부재 시 자기 자신(보수적).</li>
 *   <li>유형2 2번째 문자 {@code L}: 전부 단일 그룹 = 자기 자신(예: ALI21, ALKN1, MLIZ1).</li>
 *   <li>유형3 {@code AAI[0-9][0-9]}: 그룹 = 5번째(마지막) 자리. Master = 그룹 내 사전순 첫 번째(예: AAI13 ← AAI23, AAI33).</li>
 *   <li>유형4 나머지: 그룹 = 앞 4자리. Master = 그룹 내 사전순 첫 번째(예: HAJ11 ← HAJ12, HAJ13).</li>
 * </ul>
 *
 * <p>인벤토리(distinct 엣지)는 DB 관측 기반이라 주기 갱신(TTL 10분). 미지 엣지는 자기 자신 반환(무해).
 */
@Component
public class EdgeGroupResolver {

    private static final Duration TTL = Duration.ofMinutes(10);
    private static final Pattern T1_FAMILY = Pattern.compile("^[A-Za-z]OC.*");
    private static final Pattern T1_MASTER = Pattern.compile(".*M[0-9]$");
    private static final Pattern T1_SUFFIX = Pattern.compile("[MS][0-9]$");
    private static final Pattern T3 = Pattern.compile("^AAI[0-9][0-9]$");

    private final DomainConfigRepository repo;
    private final Clock clock;
    private volatile Map<String, String> masterByEdge = Map.of();
    private volatile Instant loadedAt = Instant.EPOCH;

    public EdgeGroupResolver(DomainConfigRepository repo, Clock clock) {
        this.repo = repo;
        this.clock = clock;
    }

    /** 엣지의 그룹 Master. 미지 엣지 = 자기 자신. */
    public String masterOf(String edge) {
        refreshIfStale();
        return masterByEdge.getOrDefault(edge, edge);
    }

    private void refreshIfStale() {
        if (Duration.between(loadedAt, Instant.now(clock)).compareTo(TTL) < 0) {
            return;
        }
        synchronized (this) {
            if (Duration.between(loadedAt, Instant.now(clock)).compareTo(TTL) < 0) {
                return;
            }
            masterByEdge = build(repo.findDistinctHostnames());
            loadedAt = Instant.now(clock);
        }
    }

    /** 규칙 4유형으로 엣지→Master 맵 구성(순수 함수, 테스트 가시). */
    static Map<String, String> build(List<String> edges) {
        Map<String, List<String>> groups = new HashMap<>();
        for (String e : edges) {
            groups.computeIfAbsent(groupKey(e), k -> new ArrayList<>()).add(e);
        }
        Map<String, String> out = new HashMap<>();
        for (List<String> members : groups.values()) {
            members.sort(String::compareTo);
            String master = pickMaster(members);
            for (String m : members) {
                out.put(m, (master != null) ? master : m); // Master 미상=자기 자신(보수적 — 유실 없음)
            }
        }
        return Map.copyOf(out);
    }

    private static String groupKey(String e) {
        if (T1_FAMILY.matcher(e).matches()) {
            return "1:" + T1_SUFFIX.matcher(e).replaceFirst(""); // (M|S)# 접미 제거 = 그룹명
        }
        if (e.length() >= 2 && e.charAt(1) == 'L') {
            return "2:" + e; // 단일 그룹(자기 자신)
        }
        if (T3.matcher(e).matches()) {
            return "3:" + e.charAt(4); // 5번째(마지막) 자리 = 그룹
        }
        return "4:" + e.substring(0, Math.min(4, e.length())); // 앞 4자리 = 그룹
    }

    /** 그룹 Master 선정 — 유형1 은 M# 멤버(정렬 첫), 그 외 사전순 첫 번째. 유형1 에 M 멤버 없으면 null(=자기 자신). */
    private static String pickMaster(List<String> sortedMembers) {
        String first = sortedMembers.get(0);
        if (T1_FAMILY.matcher(first).matches() && T1_SUFFIX.matcher(first).find()) {
            for (String m : sortedMembers) {
                if (T1_MASTER.matcher(m).matches()) {
                    return m;
                }
            }
            return null;
        }
        return first;
    }
}
