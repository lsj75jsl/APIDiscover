// Shadow/Zombie/Active/Unused/WebPage 분류 + 신뢰도 (doc/04 §3, §4)
package com.pentasecurity.apidiscover.classify;

import com.pentasecurity.apidiscover.match.ApiHintMatcher;
import com.pentasecurity.apidiscover.match.EndpointMatcher;
import com.pentasecurity.apidiscover.model.ApiBasis;
import com.pentasecurity.apidiscover.model.CanonicalEndpoint;
import com.pentasecurity.apidiscover.model.Classification;
import com.pentasecurity.apidiscover.model.DiscoveredEndpoint;
import com.pentasecurity.apidiscover.model.DroppedNonApi;
import com.pentasecurity.apidiscover.model.EndpointIdentity;
import com.pentasecurity.apidiscover.model.EndpointKind;
import com.pentasecurity.apidiscover.model.EndpointRationale;
import com.pentasecurity.apidiscover.model.Finding;
import com.pentasecurity.apidiscover.model.ParamCandidates;
import com.pentasecurity.apidiscover.model.PreflightSignal;
import com.pentasecurity.apidiscover.model.SignalStatus;
import com.pentasecurity.apidiscover.model.TemplateSource;
import java.time.Instant;
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

    /** deleted-from-spec Zombie 의 specRef/근거 마커(doc/37 §6) — deprecated(문서 내 폐기예정)와 구분. */
    private static final String DELETED_FROM_SPEC = "deleted-from-spec";

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
     * 6-arg classify: +stripPrefix(base-path-strip, doc/27 §3). 결합 뷰 등 findings 만 필요할 때.
     * stripPrefix=null → as-is(현행). 5-arg 는 null 위임(하위호환).
     */
    public List<Finding> classify(List<DiscoveredEndpoint> discovered,
                                  List<CanonicalEndpoint> spec,
                                  EndpointMatcher matcher,
                                  ApiScorer scorer,
                                  ApiHintMatcher hints,
                                  String stripPrefix) {
        return classifyWithMetrics(discovered, spec, matcher, scorer, hints, Map.of(), stripPrefix).findings();
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
        // 하위호환 5-arg: 이력 없음(빈 map) → entrenchment 보너스 0 = 현행 (doc/24 §4)
        return classifyWithMetrics(discovered, spec, matcher, scorer, hints, Map.of());
    }

    /**
     * 6-arg: cross-scan 이력(priorFirstSeen: 검출 signature "{METHOD} {host} {template}"→이력상 최초 firstSeen)으로
     * Zombie severity entrenchment 보강(doc/24 §7, doc/26 §8 — discovered_endpoint.firstSeen 에서 로드).
     * 빈 map → 콜드스타트(현행). stripPrefix 없음(null) → at-match strip 미발동(현행, doc/27).
     */
    public ClassificationResult classifyWithMetrics(List<DiscoveredEndpoint> discovered,
                                                    List<CanonicalEndpoint> spec,
                                                    EndpointMatcher matcher,
                                                    ApiScorer scorer,
                                                    ApiHintMatcher hints,
                                                    Map<String, Instant> priorFirstSeen) {
        return classifyWithMetrics(discovered, spec, matcher, scorer, hints, priorFirstSeen, null);
    }

    /**
     * 7-arg: +stripPrefix(base-path-strip, doc/27 §3). null → as-is 만(현행 무회귀).
     * host 없음 → recency lookup host=null(빈 prior 전제 하위호환 — 실제 recency 는 8-arg 사용).
     */
    public ClassificationResult classifyWithMetrics(List<DiscoveredEndpoint> discovered,
                                                    List<CanonicalEndpoint> spec,
                                                    EndpointMatcher matcher,
                                                    ApiScorer scorer,
                                                    ApiHintMatcher hints,
                                                    Map<String, Instant> priorFirstSeen,
                                                    String stripPrefix) {
        return classifyWithMetrics(discovered, spec, matcher, scorer, hints, priorFirstSeen, stripPrefix, null);
    }

    /**
     * 8-arg: +host(스캔 도메인) — priorFirstSeen 키({@code EndpointIdentity.key(method,host,template)}, upsert/영속과 동일)와 정합.
     * {@code d.signature()} 가 최종 template·파싱 host 와 발산해도 recency 정확 매칭(doc/24·26 §2, identity 통일). host=null → 빈 prior 전제 하위호환.
     * <p>스캔 경로(report_json) 진입점 — rationale 미수집(null) → 9-arg core 와 <b>findings/metrics 바이트 동일</b>(doc/34 §3).
     */
    public ClassificationResult classifyWithMetrics(List<DiscoveredEndpoint> discovered,
                                                    List<CanonicalEndpoint> spec,
                                                    EndpointMatcher matcher,
                                                    ApiScorer scorer,
                                                    ApiHintMatcher hints,
                                                    Map<String, Instant> priorFirstSeen,
                                                    String stripPrefix,
                                                    String host) {
        return classifyWithMetrics(discovered, spec, matcher, scorer, hints, priorFirstSeen, stripPrefix, host, Set.of(), null);
    }

    /**
     * 9-arg(+deletedKeys): 스캔 경로의 DELETED→Zombie 결합(doc/37 §6). deletedKeys=host 의 DELETED 인벤토리 키집합
     * ("METHOD path_template"). 관측이 active spec 미매칭이고 이 집합에 있으면 SHADOW 대신 Zombie(deleted-from-spec, 0.8).
     * 빈 집합(현행)=무회귀. rationale 미수집(스캔 경로).
     */
    public ClassificationResult classifyWithMetrics(List<DiscoveredEndpoint> discovered,
                                                    List<CanonicalEndpoint> spec,
                                                    EndpointMatcher matcher,
                                                    ApiScorer scorer,
                                                    ApiHintMatcher hints,
                                                    Map<String, Instant> priorFirstSeen,
                                                    String stripPrefix,
                                                    String host,
                                                    Set<String> deletedKeys) {
        return classifyWithMetrics(discovered, spec, matcher, scorer, hints, priorFirstSeen, stripPrefix, host, deletedKeys, null);
    }

    /** findings + rationale(판단 근거) 동시 산출 결과 (doc/34). rationale 는 findings 와 동일 순서·identity 병렬. */
    public record ExplainedClassification(List<Finding> findings, List<EndpointRationale> rationale) {}

    /**
     * 판단 근거 노출(doc/34 §3) — 분류 core 를 공유하되 rationale 도 수집. /discovery 전용(forHost). priorFirstSeen 불요(근거는 recency 무관).
     * 스캔 경로(classifyWithMetrics)와 <b>동일 게이트·corsKeys</b>(분기 발산 금지) — explain 여부만 다름.
     */
    public ExplainedClassification classifyExplained(List<DiscoveredEndpoint> discovered,
                                                     List<CanonicalEndpoint> spec,
                                                     EndpointMatcher matcher,
                                                     ApiScorer scorer,
                                                     ApiHintMatcher hints,
                                                     String stripPrefix) {
        return classifyExplained(discovered, spec, matcher, scorer, hints, stripPrefix, Set.of());
    }

    /**
     * 7-arg(+deletedKeys): 결합 뷰(forHost /discovery·/result)의 DELETED→Zombie 결합(doc/37 §6). 스캔 경로와 동일 게이트,
     * rationale 동봉. 빈 집합=현행 무회귀. deleted-from-spec Zombie 도 rationale 병렬(SpecMatchBasis, specRef="deleted-from-spec").
     */
    public ExplainedClassification classifyExplained(List<DiscoveredEndpoint> discovered,
                                                     List<CanonicalEndpoint> spec,
                                                     EndpointMatcher matcher,
                                                     ApiScorer scorer,
                                                     ApiHintMatcher hints,
                                                     String stripPrefix,
                                                     Set<String> deletedKeys) {
        List<EndpointRationale> rationale = new ArrayList<>();
        ClassificationResult r = classifyWithMetrics(
                discovered, spec, matcher, scorer, hints, Map.of(), stripPrefix, null, deletedKeys, rationale);
        return new ExplainedClassification(r.findings(), rationale);
    }

    /**
     * 10-arg core: +deletedKeys(doc/37 §6) +rationaleOut(nullable, doc/34 §2). deletedKeys 빈 + rationaleOut null(스캔 현행)이면
     * findings/dropped/preflight 가 이전과 완전 동일(무회귀·ETag 불변). rationaleOut 비-null 이면 finding 과 병렬로 판단 근거 수집.
     */
    public ClassificationResult classifyWithMetrics(List<DiscoveredEndpoint> discovered,
                                                    List<CanonicalEndpoint> spec,
                                                    EndpointMatcher matcher,
                                                    ApiScorer scorer,
                                                    ApiHintMatcher hints,
                                                    Map<String, Instant> priorFirstSeen,
                                                    String stripPrefix,
                                                    String host,
                                                    Set<String> deletedKeys,
                                                    List<EndpointRationale> rationaleOut) {
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
                    matcher.match(d.method(), d.host(), d.pathTemplate(), stripPrefix)
                            .ifPresent(ce -> observedSpec.computeIfAbsent(key(ce), k -> new Evidence())
                                    .add(d, priorFirstSeen.get(EndpointIdentity.key(d.method(), host, d.pathTemplate()))));
                    continue;
                }
                // ACTIVE genuine → 아래 일반 매칭/게이트 로직으로 fall through (매칭→Active, 미매칭→Shadow)
            }
            Optional<CanonicalEndpoint> matched = matcher.match(d.method(), d.host(), d.pathTemplate(), stripPrefix);
            if (matched.isPresent()) {
                // Active/Zombie 는 2차 (스펙 권위, 게이트 우회). 매칭 d 메트릭을 Evidence 에 누적(severity 용)
                observedSpec.computeIfAbsent(key(matched.get()), k -> new Evidence())
                        .add(d, priorFirstSeen.get(EndpointIdentity.key(d.method(), host, d.pathTemplate())));
                continue;
            }
            // ★active spec 미매칭이지만 DELETED 인벤토리 키 → deleted-from-spec Zombie (doc/37 §6). 게이트 우회(한때 문서화=실 API).
            //   active spec 미포함이라 2차(deprecated/version Zombie)와 중복 없음. 빈 deletedKeys=현행(무회귀).
            if (deletedKeys.contains(deletedKey(d.method(), d.pathTemplate()))) {
                Evidence ev = new Evidence();
                ev.add(d, priorFirstSeen.get(EndpointIdentity.key(d.method(), host, d.pathTemplate())));
                Instant prior = ev.entrenchedFirstSeen != null ? ev.entrenchedFirstSeen : ev.firstSeen;
                findings.add(new Finding.Zombie(d.host(), d.method(), d.pathTemplate(), 0.8,
                        ZombieSeverity.of(ev, prior), false, DELETED_FROM_SPEC,
                        "문서에서 제거됐으나 트래픽 지속 — 차단/마이그레이션 검토",
                        new ParamCandidates(ev.queryCandidates(), pathParamsFromTemplate(d.pathTemplate()))));
                if (rationaleOut != null) {
                    rationaleOut.add(new EndpointRationale(d.method(), d.host(), d.pathTemplate(),
                            Classification.ZOMBIE, new ApiBasis.SpecMatchBasis(DELETED_FROM_SPEC, false, false)));
                }
                continue;
            }
            // 문서에 없음 → ApiScorer 게이트 (doc/08, doc/09 §2.2). 전달된 scorer 사용(doc/10 §6)
            boolean cors = corsKeys.contains(hostTemplateKey(d.host(), d.pathTemplate()));
            // 게이트 결과별 분기: ADMIT→Shadow 보고, DROP_*→사유별 카운트(미보고). (doc/12 §1)
            ApiScorer.Gate gate = scorer.evaluate(d, cors, hints);
            switch (gate) {
                case ADMIT -> {
                    findings.add(new Finding.Shadow(d.host(), d.method(), d.pathTemplate(),
                            shadowConfidence(d), "트래픽 존재, 문서 내 매칭 템플릿 없음", d.params()));
                    if (rationaleOut != null) {
                        rationaleOut.add(shadowRationale(scorer, d, cors, hints)); // 근거=점수 게이트(ADMIT)
                    }
                }
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
                    // 명시 deprecated Zombie: confidence 1.0·estimated=false (무회귀) + severity 가산(이력 entrenchment 포함)
                    Instant prior = ev.entrenchedFirstSeen != null ? ev.entrenchedFirstSeen : ev.firstSeen;
                    findings.add(new Finding.Zombie(s.host(), s.method(), s.pathTemplate(), 1.0,
                            ZombieSeverity.of(ev, prior), false, s.sourceRef(), "문서에 deprecated 표기, 그러나 트래픽 발생",
                            specParams(ev, s)));
                    if (rationaleOut != null) {
                        rationaleOut.add(specMatchRationale(s, Classification.ZOMBIE, true, false)); // 명시 deprecated
                    }
                }
                // deprecated && !observed → Deprecated-clean: 조치 불필요, Finding 미발행
            } else if (observed && estimatedZombies.contains(s)) {
                // 버전 추정 Zombie: confidence 0.6·estimated=true (doc/16 §1, 명시보다 낮게)
                Instant prior = ev.entrenchedFirstSeen != null ? ev.entrenchedFirstSeen : ev.firstSeen;
                findings.add(new Finding.Zombie(s.host(), s.method(), s.pathTemplate(), 0.6,
                        ZombieSeverity.of(ev, prior), true, s.sourceRef(),
                        "신버전 active, 구버전 트래픽 지속 — deprecated 미표기", specParams(ev, s)));
                if (rationaleOut != null) {
                    rationaleOut.add(specMatchRationale(s, Classification.ZOMBIE, false, true)); // 버전 추정
                }
            } else if (observed) {
                findings.add(new Finding.Active(s.host(), s.method(), s.pathTemplate(), s.sourceRef(),
                        specParams(ev, s)));
                if (rationaleOut != null) {
                    rationaleOut.add(specMatchRationale(s, Classification.ACTIVE, false, false)); // 스펙 매칭
                }
            } else {
                // !deprecated && !observed → Unused. DORMANT 에서만 M1 preflightAmbiguous(저신뢰).
                // ACTIVE(M3)면 acrm 으로 확실 판정 → genuine→Active(여기 미도달)/pure preflight→plain Unused → ambiguous 자동 승급(doc/23 §9.4)
                boolean preflightAmbiguous = !preflightActive
                        && "OPTIONS".equalsIgnoreCase(s.method())
                        && optionsTrafficObserved(corsKeys, s);
                findings.add(new Finding.Unused(
                        s.host(), s.method(), s.pathTemplate(), s.sourceRef(), preflightAmbiguous));
                if (rationaleOut != null) {
                    rationaleOut.add(specOnlyRationale(s)); // 스펙에 있고 무트래픽
                }
            }
        }
        PreflightSignal preflightSignal = preflightActive
                ? new PreflightSignal(SignalStatus.ACTIVE, acrmPresentOptions)
                : PreflightSignal.NONE;
        return new ClassificationResult(
                findings, new DroppedNonApi(excluded, webForm, lowScore), preflightSignal);
    }

    /** Shadow 근거(doc/34 §2): 점수 게이트(ADMIT). scoreExplain 으로 신호별 내역·총점·임계 수집(mode=pathless|explicit_hint). */
    private static EndpointRationale shadowRationale(ApiScorer scorer, DiscoveredEndpoint d,
                                                     boolean cors, ApiHintMatcher hints) {
        var bd = scorer.scoreExplain(d, cors, hints);
        String mode = hints.isExplicitHintMode() ? "explicit_hint" : "pathless";
        return new EndpointRationale(d.method(), d.host(), d.pathTemplate(), Classification.SHADOW,
                new ApiBasis.ScoreBasis(bd.total(), scorer.threshold(), "ADMIT", mode, bd.signals()));
    }

    /** Active/Zombie 근거(doc/34 §2): 스펙 매칭(점수 무관). deprecated=명시 deprecated, estimated=버전 추정 Zombie. */
    private static EndpointRationale specMatchRationale(CanonicalEndpoint s, Classification c,
                                                        boolean deprecated, boolean estimated) {
        return new EndpointRationale(s.method(), s.host(), s.pathTemplate(), c,
                new ApiBasis.SpecMatchBasis(s.sourceRef(), deprecated, estimated));
    }

    /** Unused 근거(doc/34 §2): 스펙에 있고 무트래픽(관측 부재). */
    private static EndpointRationale specOnlyRationale(CanonicalEndpoint s) {
        return new EndpointRationale(s.method(), s.host(), s.pathTemplate(), Classification.UNUSED,
                new ApiBasis.SpecOnlyBasis(s.sourceRef()));
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

    /** DELETED 인벤토리 키 = METHOD(대문자) + path_template (ApiInventoryService.deletedKeys 와 동형, doc/37 §6.5). */
    private static String deletedKey(String method, String pathTemplate) {
        return method.toUpperCase(Locale.ROOT) + " " + pathTemplate;
    }

    /** 문서/관찰 엔드포인트의 매칭 동일성 키. host=null 은 host-agnostic("*"). */
    private static String key(CanonicalEndpoint e) {
        String host = (e.host() == null) ? "*" : e.host().toLowerCase(Locale.ROOT);
        return e.method().toUpperCase(Locale.ROOT) + "|" + host + "|" + e.pathTemplate();
    }

    /**
     * Active/Zombie param 후보 (doc/25 §B): query=관측 누적(ev), path=spec 템플릿 변수(권위).
     * canonical 은 query param 정의 미보유(doc/03) → query 는 관측 기반.
     */
    private static ParamCandidates specParams(Evidence ev, CanonicalEndpoint s) {
        return new ParamCandidates(ev.queryCandidates(), pathParamsFromTemplate(s.pathTemplate()));
    }

    /** spec 템플릿의 {var} 세그먼트 → PathParam(0-based 세그먼트 인덱스). */
    private static List<ParamCandidates.PathParam> pathParamsFromTemplate(String template) {
        List<ParamCandidates.PathParam> out = new ArrayList<>();
        if (template == null) {
            return out;
        }
        String body = template.startsWith("/") ? template.substring(1) : template;
        if (body.isEmpty()) {
            return out;
        }
        String[] segs = body.split("/", -1);
        for (int i = 0; i < segs.length; i++) {
            if (segs[i].startsWith("{") && segs[i].endsWith("}")) {
                out.add(new ParamCandidates.PathParam(i, segs[i]));
            }
        }
        return out;
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
