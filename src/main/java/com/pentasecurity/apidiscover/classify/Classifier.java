// Shadow/Zombie/Active/Unused/WebPage 분류 + 신뢰도 (doc/04 §3, §4)
package com.pentasecurity.apidiscover.classify;

import com.pentasecurity.apidiscover.match.EndpointMatcher;
import com.pentasecurity.apidiscover.model.CanonicalEndpoint;
import com.pentasecurity.apidiscover.model.DiscoveredEndpoint;
import com.pentasecurity.apidiscover.model.EndpointKind;
import com.pentasecurity.apidiscover.model.Finding;
import com.pentasecurity.apidiscover.model.TemplateSource;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.springframework.stereotype.Component;

@Component
public class Classifier {

    /**
     * 관찰 집합 D 와 문서 집합 S 를 매칭·분류한다.
     * <ul>
     *   <li>1차(관찰 측 D): 매칭 실패 → Shadow / WebPage (doc/04 §3.2)</li>
     *   <li>2차(문서 측 S): observed 여부로 Zombie / Unused / Active (doc/04 §3.1)</li>
     * </ul>
     */
    public List<Finding> classify(List<DiscoveredEndpoint> discovered,
                                  List<CanonicalEndpoint> spec,
                                  EndpointMatcher matcher) {
        List<Finding> findings = new ArrayList<>();
        Set<String> observedSpecKeys = new HashSet<>();

        // --- 1차: 관찰 측(D) ---
        for (DiscoveredEndpoint d : discovered) {
            Optional<CanonicalEndpoint> matched = matcher.match(d.method(), d.host(), d.pathTemplate());
            if (matched.isPresent()) {
                observedSpecKeys.add(key(matched.get()));
                continue; // Active/Zombie 는 2차에서 처리
            }
            // 문서에 없음 — endpoint_kind 로 분기
            switch (d.endpointKind()) {
                case STATIC ->
                    // 정적 리소스는 API 가 아님 → Shadow 로 보고하지 않음 (doc/02 §2.3)
                    { }
                case WEB_PAGE ->
                    // 미문서 웹 페이지 → API-Shadow 가 아닌 별도 버킷 (doc/04 §4.1.1)
                    findings.add(new Finding.WebPage(
                            d.host(), d.method(), d.pathTemplate(), d.kindConfidence()));
                default ->
                    findings.add(new Finding.Shadow(d.host(), d.method(), d.pathTemplate(),
                            shadowConfidence(d), "트래픽 존재, 문서 내 매칭 템플릿 없음"));
            }
        }

        // --- 2차: 문서 측(S) ---
        for (CanonicalEndpoint s : spec) {
            boolean observed = observedSpecKeys.contains(key(s));
            if (s.deprecated()) {
                if (observed) {
                    findings.add(new Finding.Zombie(s.host(), s.method(), s.pathTemplate(), 1.0,
                            s.sourceRef(), "문서에 deprecated 표기, 그러나 트래픽 발생"));
                }
                // deprecated && !observed → Deprecated-clean: 조치 불필요, Finding 미발행
            } else if (observed) {
                findings.add(new Finding.Active(s.host(), s.method(), s.pathTemplate(), s.sourceRef()));
            } else {
                findings.add(new Finding.Unused(s.host(), s.method(), s.pathTemplate(), s.sourceRef()));
            }
        }
        return findings;
    }

    /** Shadow 신뢰도 (doc/04 §4.1). 기본 1.0 에서 감산, [0,1] clamp. */
    private static double shadowConfidence(DiscoveredEndpoint d) {
        double c = 1.0;
        DiscoveredEndpoint.Metrics m = d.metrics();
        if (m != null) {
            long total = m.statusDist() == null ? 0
                    : m.statusDist().values().stream().mapToLong(Long::longValue).sum();
            long err4xx = (m.statusDist() == null) ? 0 : m.statusDist().getOrDefault("4xx", 0L);
            if (total > 0 && (double) err4xx / total >= 0.9) {
                c -= 0.7; // 거의 전부 4xx → 실재 엔드포인트 아닐 가능성
            }
            if (m.hits() < 5) {
                c -= 0.2; // 단발성
            }
            if (m.distinctClients() <= 1) {
                c -= 0.2; // 단일 클라이언트(스캐너 가능성)
            }
        }
        if (d.templateSource() == TemplateSource.INFERRED) {
            c -= 0.1; // 통계/휴리스틱 보정 템플릿(과병합 위험)
        }
        if (d.endpointKind() == EndpointKind.API_CANDIDATE) {
            c += 0.05; // 약한 양성 신호
        }
        return round3(clamp(c));
    }

    private static double clamp(double v) {
        return Math.max(0.0, Math.min(1.0, v));
    }

    /** 부동소수점 잡음 제거 + 리포트 가독성을 위해 소수 3자리 반올림. */
    private static double round3(double v) {
        return Math.round(v * 1000.0) / 1000.0;
    }

    /** 문서/관찰 엔드포인트의 매칭 동일성 키. host=null 은 host-agnostic("*"). */
    private static String key(CanonicalEndpoint e) {
        String host = (e.host() == null) ? "*" : e.host().toLowerCase();
        return e.method().toUpperCase() + "|" + host + "|" + e.pathTemplate();
    }
}
