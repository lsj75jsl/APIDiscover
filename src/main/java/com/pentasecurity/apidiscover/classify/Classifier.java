// Shadow/Zombie/Active/Unused/WebPage 분류 + 신뢰도 (doc/04 §3, §4)
package com.pentasecurity.apidiscover.classify;

import com.pentasecurity.apidiscover.match.ApiHintMatcher;
import com.pentasecurity.apidiscover.match.EndpointMatcher;
import com.pentasecurity.apidiscover.model.CanonicalEndpoint;
import com.pentasecurity.apidiscover.model.DiscoveredEndpoint;
import com.pentasecurity.apidiscover.model.DroppedNonApi;
import com.pentasecurity.apidiscover.model.EndpointKind;
import com.pentasecurity.apidiscover.model.Finding;
import com.pentasecurity.apidiscover.model.PreflightSignal;
import com.pentasecurity.apidiscover.model.SignalStatus;
import com.pentasecurity.apidiscover.model.TemplateSource;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.springframework.stereotype.Component;

@Component
public class Classifier {

    private final ApiScorer apiScorer;

    public Classifier(ApiScorer apiScorer) {
        this.apiScorer = apiScorer;
    }

    /**
     * 관찰 집합 D 와 문서 집합 S 를 매칭·분류한다.
     * <ul>
     *   <li>1차(관찰 측 D): 매칭 실패 시 ApiScorer 게이트 → 통과만 Shadow, 미달은 not_api 로 제외 (doc/08 §6)</li>
     *   <li>2차(문서 측 S): observed 여부로 Zombie / Unused / Active (doc/04 §3.1)</li>
     * </ul>
     * 스펙에 매칭된 endpoint 는 스펙이 권위 → 점수 게이트를 우회한다.
     */
    public List<Finding> classify(List<DiscoveredEndpoint> discovered,
                                  List<CanonicalEndpoint> spec,
                                  EndpointMatcher matcher) {
        // 레거시 3-arg: 힌트 없음(NONE) → 현행 동작과 동일 (doc/09 §6 하위호환)
        return classify(discovered, spec, matcher, ApiHintMatcher.NONE);
    }

    /**
     * explicit-hint 매처를 받는 4-arg 오버로드 (doc/09 §2). field scorer 로 5-arg 에 위임(하위호환).
     */
    public List<Finding> classify(List<DiscoveredEndpoint> discovered,
                                  List<CanonicalEndpoint> spec,
                                  EndpointMatcher matcher,
                                  ApiHintMatcher hints) {
        return classify(discovered, spec, matcher, apiScorer, hints);
    }

    /**
     * effective scorer(가중치/임계)+hints 를 전달받는 5-arg 오버로드 (doc/10 §6). findings 만 필요할 때.
     * 기존 3/4-arg 는 field scorer 로 이 메서드에 위임 → 하위호환.
     */
    public List<Finding> classify(List<DiscoveredEndpoint> discovered,
                                  List<CanonicalEndpoint> spec,
                                  EndpointMatcher matcher,
                                  ApiScorer scorer,
                                  ApiHintMatcher hints) {
        return classifyWithMetrics(discovered, spec, matcher, scorer, hints).findings();
    }

    /**
     * findings + non_api dropped 메트릭을 함께 산출(doc/12 §1). 게이트 ADMIT→Shadow, DROP_*→사유별 카운트.
     * dropped 대상: non-OPTIONS·spec 미매칭·게이트 DROP_*(OPTIONS·spec 매칭·ADMIT 은 제외).
     */
    public ClassificationResult classifyWithMetrics(List<DiscoveredEndpoint> discovered,
                                                    List<CanonicalEndpoint> spec,
                                                    EndpointMatcher matcher,
                                                    ApiScorer scorer,
                                                    ApiHintMatcher hints) {
        List<Finding> findings = new ArrayList<>();
        int excluded = 0;
        int webForm = 0;
        int lowScore = 0;
        // 매칭 키 → 누적 Evidence (severity 입력). host-agnostic spec 은 여러 host d 가 한 키에 합산 (doc/16 §4)
        Map<String, Evidence> observedSpec = new HashMap<>();

        // M3 가용성 게이트(doc/23 §9.3): acrm(결정적 preflight 신호) 관측 합 > 0 → ACTIVE, 아니면 DORMANT(현행+M2).
        // 기본 parse.acrm-field-index=-1 → acrm 전부 null → 합 0 → DORMANT → 현행 100%(무회귀 핵심).
        long acrmPresentOptions = 0;
        for (DiscoveredEndpoint d : discovered) {
            if ("OPTIONS".equalsIgnoreCase(d.method()) && d.metrics() != null) {
                acrmPresentOptions += d.metrics().acrmPresentCount();
            }
        }
        boolean preflightActive = acrmPresentOptions > 0;

        // CORS preflight 신호: host+template 이 OPTIONS 로 관측된 집합 (doc/08 §4).
        // ACTIVE 면 진짜 preflight(acrm>0)만 — genuine-only OPTIONS 은 보너스 안 줌(정밀). DORMANT 면 모든 OPTIONS(현행).
        Set<String> corsKeys = new HashSet<>();
        for (DiscoveredEndpoint d : discovered) {
            if ("OPTIONS".equalsIgnoreCase(d.method())) {
                boolean isPreflightSig = d.metrics() != null && d.metrics().acrmPresentCount() > 0;
                if (!preflightActive || isPreflightSig) {
                    corsKeys.add(hostTemplateKey(d.host(), d.pathTemplate()));
                }
            }
        }

        // --- 1차: 관찰 측(D) ---
        for (DiscoveredEndpoint d : discovered) {
            // OPTIONS 분기 (doc/23). corsKeys 는 위에서 구축됨.
            //   ACTIVE(acrm 가용): acrm-absent hit 존재 = genuine OPTIONS 호출 → 정상 매칭(매칭→Active, 미매칭→gate→Shadow,
            //     preflight 는 acrm 으로 제외돼 flood 없음). pure preflight(acrm-only) → skip(cors 신호만). [M3]
            //   DORMANT(acrm 미가용): M2 operator 선언 + 스펙 OPTIONS 매칭만 observed(→Active), 그 외 skip(현행). [M2]
            if ("OPTIONS".equalsIgnoreCase(d.method())) {
                boolean genuine;
                if (preflightActive) {
                    long acrm = (d.metrics() == null) ? 0 : d.metrics().acrmPresentCount();
                    long hits = (d.metrics() == null) ? 0 : d.metrics().hits();
                    genuine = hits - acrm > 0;
                } else {
                    genuine = hints.genuineOptions(d.pathTemplate());
                }
                if (!genuine) {
                    continue; // pure preflight(ACTIVE) / 미선언(DORMANT) → cors 신호만, 보고 안 함
                }
                if (!preflightActive) {
                    // DORMANT genuine(M2): spec-match 한정 observed (미매칭은 Shadow 안 만듦 — 불변식 보존)
                    matcher.match(d.method(), d.host(), d.pathTemplate())
                            .ifPresent(ce -> observedSpec.computeIfAbsent(key(ce), k -> new Evidence()).add(d.metrics()));
                    continue;
                }
                // ACTIVE genuine → 아래 일반 매칭/게이트 로직으로 fall through (매칭→Active, 미매칭→Shadow)
            }
            Optional<CanonicalEndpoint> matched = matcher.match(d.method(), d.host(), d.pathTemplate());
            if (matched.isPresent()) {
                // Active/Zombie 는 2차 (스펙 권위, 게이트 우회). 매칭 d 메트릭을 Evidence 에 누적(severity 용)
                observedSpec.computeIfAbsent(key(matched.get()), k -> new Evidence()).add(d.metrics());
                continue;
            }
            // 문서에 없음 → ApiScorer 게이트 (doc/08, doc/09 §2.2). 전달된 scorer 사용(doc/10 §6)
            boolean cors = corsKeys.contains(hostTemplateKey(d.host(), d.pathTemplate()));
            // 게이트 결과별 분기: ADMIT→Shadow 보고, DROP_*→사유별 카운트(미보고). (doc/12 §1)
            ApiScorer.Gate gate = scorer.evaluate(d, cors, hints);
            switch (gate) {
                case ADMIT -> findings.add(new Finding.Shadow(d.host(), d.method(), d.pathTemplate(),
                        shadowConfidence(d), "트래픽 존재, 문서 내 매칭 템플릿 없음", d.params()));
                case DROP_EXCLUDED -> excluded++;   // not_api: operator 제외
                case DROP_WEB_FORM -> webForm++;    // not_api: web form 제출(write-to-WEB_PAGE)
                case DROP_LOW_SCORE -> lowScore++;  // not_api: 점수 미달
                // 향후 Gate 값 추가 시 silent 미카운트 방지 (fail-fast). 현재 4값 완전 처리라 동작 무변경.
                default -> throw new IllegalStateException("unhandled gate: " + gate);
            }
        }

        // --- 2차: 문서 측(S) ---
        // active(observed & 비-deprecated) 중 버전 추정 Zombie 집합 (신버전 active + 구버전 트래픽 지속, doc/16 §1)
        List<CanonicalEndpoint> active = new ArrayList<>();
        for (CanonicalEndpoint s : spec) {
            if (!s.deprecated() && observedSpec.containsKey(key(s))) {
                active.add(s);
            }
        }
        Set<CanonicalEndpoint> estimatedZombies = VersionZombieInference.estimate(active);

        for (CanonicalEndpoint s : spec) {
            Evidence ev = observedSpec.get(key(s));
            boolean observed = ev != null;
            if (s.deprecated()) {
                if (observed) {
                    // 명시 deprecated Zombie: confidence 1.0·estimated=false (무회귀) + severity 가산
                    findings.add(new Finding.Zombie(s.host(), s.method(), s.pathTemplate(), 1.0,
                            ZombieSeverity.of(ev), false, s.sourceRef(), "문서에 deprecated 표기, 그러나 트래픽 발생"));
                }
                // deprecated && !observed → Deprecated-clean: 조치 불필요, Finding 미발행
            } else if (observed && estimatedZombies.contains(s)) {
                // 버전 추정 Zombie: confidence 0.6·estimated=true (doc/16 §1, 명시보다 낮게)
                findings.add(new Finding.Zombie(s.host(), s.method(), s.pathTemplate(), 0.6,
                        ZombieSeverity.of(ev), true, s.sourceRef(),
                        "신버전 active, 구버전 트래픽 지속 — deprecated 미표기"));
            } else if (observed) {
                findings.add(new Finding.Active(s.host(), s.method(), s.pathTemplate(), s.sourceRef()));
            } else {
                // !deprecated && !observed → Unused. DORMANT 에서만 M1 preflightAmbiguous(저신뢰).
                // ACTIVE(M3)면 acrm 으로 확실 판정 → genuine→Active(여기 미도달)/pure preflight→plain Unused → ambiguous 자동 승급(doc/23 §9.4)
                boolean preflightAmbiguous = !preflightActive
                        && "OPTIONS".equalsIgnoreCase(s.method())
                        && optionsTrafficObserved(corsKeys, s);
                findings.add(new Finding.Unused(
                        s.host(), s.method(), s.pathTemplate(), s.sourceRef(), preflightAmbiguous));
            }
        }
        PreflightSignal preflightSignal = preflightActive
                ? new PreflightSignal(SignalStatus.ACTIVE, acrmPresentOptions)
                : PreflightSignal.NONE;
        return new ClassificationResult(
                findings, new DroppedNonApi(excluded, webForm, lowScore), preflightSignal);
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
        String host = (e.host() == null) ? "*" : e.host().toLowerCase(Locale.ROOT);
        return e.method().toUpperCase(Locale.ROOT) + "|" + host + "|" + e.pathTemplate();
    }

    /** CORS 신호용 host+template 키 (method 무관 — sibling 메서드에 신호 전파). */
    private static String hostTemplateKey(String host, String template) {
        String h = (host == null) ? "*" : host.toLowerCase(Locale.ROOT);
        return h + "|" + template;
    }

    /**
     * OPTIONS 트래픽이 이 spec OPTIONS operation 의 template 으로 관측됐는지 (corsKeys 재사용, doc/23 M1).
     * corsKeys 는 discovered concrete host 키라, host-agnostic spec(host=null)은 template 으로 매칭(observed 와 동일하게 host 무관 취급).
     */
    private static boolean optionsTrafficObserved(Set<String> corsKeys, CanonicalEndpoint s) {
        if (s.host() != null) {
            return corsKeys.contains(hostTemplateKey(s.host(), s.pathTemplate()));
        }
        String suffix = "|" + s.pathTemplate();
        return corsKeys.stream().anyMatch(k -> k.endsWith(suffix));
    }
}
