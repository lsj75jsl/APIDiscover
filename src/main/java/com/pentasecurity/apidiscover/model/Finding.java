// 분류 결과 1건 (doc/04 §3). sealed interface + 패턴매칭 대상
package com.pentasecurity.apidiscover.model;

import com.fasterxml.jackson.annotation.JsonProperty;

public sealed interface Finding
        permits Finding.Shadow, Finding.Zombie, Finding.Active, Finding.Unused, Finding.WebPage {

    /** low_confidence 임계 (doc/25 §A.3, 1차값·캐비엇). 단일 진실원=confidence. */
    double LOW_CONFIDENCE_THRESHOLD = 0.5;

    Classification classification();

    String host();

    String method();

    String pathTemplate();

    /** 트래픽엔 있으나 문서에 없음 (D \ S). params=미문서 endpoint 파라미터 후보(고가치 보안신호, doc/13 §4.2). */
    record Shadow(String host, String method, String pathTemplate, double confidence, String reason,
                  ParamCandidates params)
            implements Finding {
        @Override public Classification classification() { return Classification.SHADOW; }

        /** 파생 — confidence < 임계 (doc/25 §A.3). 별도 섹션 아닌 플래그(findings 계약 불변). */
        @JsonProperty("low_confidence")
        public boolean lowConfidence() { return confidence < LOW_CONFIDENCE_THRESHOLD; }
    }

    /**
     * 문서에 deprecated 인데 트래픽 지속(S_deprecated ∩ D) 또는 버전 추정 Zombie(doc/16).
     * confidence=진짜 Zombie 인가(명시 1.0/추정 0.6), severity=조치 시급성(트래픽 메트릭) — 직교. estimated=버전 추정 여부.
     * params=관측 query + spec 템플릿 path 후보 (doc/25 §B).
     */
    record Zombie(String host, String method, String pathTemplate, double confidence,
                  Severity severity, boolean estimated, String specRef, String reason,
                  ParamCandidates params) implements Finding {
        /** 하위호환 — params 기본 EMPTY (doc/25 §B). */
        public Zombie(String host, String method, String pathTemplate, double confidence,
                      Severity severity, boolean estimated, String specRef, String reason) {
            this(host, method, pathTemplate, confidence, severity, estimated, specRef, reason,
                    ParamCandidates.EMPTY);
        }

        @Override public Classification classification() { return Classification.ZOMBIE; }

        /** 파생 — confidence < 임계 (doc/25 §A.3). */
        @JsonProperty("low_confidence")
        public boolean lowConfidence() { return confidence < LOW_CONFIDENCE_THRESHOLD; }
    }

    /** 문서에 있고(미deprecated) 트래픽 정상. params=관측 query + spec 템플릿 path 후보 (doc/25 §B). */
    record Active(String host, String method, String pathTemplate, String specRef, ParamCandidates params)
            implements Finding {
        /** 하위호환 — params 기본 EMPTY (doc/25 §B). */
        public Active(String host, String method, String pathTemplate, String specRef) {
            this(host, method, pathTemplate, specRef, ParamCandidates.EMPTY);
        }

        @Override public Classification classification() { return Classification.ACTIVE; }
    }

    /**
     * 문서엔 있으나 트래픽 없음 (S \ D). preflightAmbiguous=OPTIONS operation 인데 OPTIONS 트래픽은 관측됨 —
     * preflight/진짜 구분 불가라 Unused 저신뢰(operator 오도 방지, doc/23 M1). 기본 false=현행.
     */
    record Unused(String host, String method, String pathTemplate, String specRef, boolean preflightAmbiguous)
            implements Finding {
        /** 하위호환 — preflightAmbiguous 기본 false (doc/23 M1, doc/16 Zombie severity 추가와 동형). */
        public Unused(String host, String method, String pathTemplate, String specRef) {
            this(host, method, pathTemplate, specRef, false);
        }

        @Override public Classification classification() { return Classification.UNUSED; }
    }

    /** 미문서 경로가 사실은 웹 페이지(비 API) (doc/04 §4.1.1). */
    record WebPage(String host, String method, String pathTemplate, double kindConfidence)
            implements Finding {
        @Override public Classification classification() { return Classification.UNDOCUMENTED_WEB_PAGE; }
    }
}
