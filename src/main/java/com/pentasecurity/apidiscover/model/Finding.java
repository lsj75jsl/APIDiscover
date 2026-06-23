// 분류 결과 1건 (doc/04 §3). sealed interface + 패턴매칭 대상
package com.pentasecurity.apidiscover.model;

public sealed interface Finding
        permits Finding.Shadow, Finding.Zombie, Finding.Active, Finding.Unused, Finding.WebPage {

    Classification classification();

    String host();

    String method();

    String pathTemplate();

    /** 트래픽엔 있으나 문서에 없음 (D \ S). params=미문서 endpoint 파라미터 후보(고가치 보안신호, doc/13 §4.2). */
    record Shadow(String host, String method, String pathTemplate, double confidence, String reason,
                  ParamCandidates params)
            implements Finding {
        @Override public Classification classification() { return Classification.SHADOW; }
    }

    /** 문서에 deprecated 인데 트래픽 지속 (S_deprecated ∩ D). */
    record Zombie(String host, String method, String pathTemplate, double confidence,
                  String specRef, String reason) implements Finding {
        @Override public Classification classification() { return Classification.ZOMBIE; }
    }

    /** 문서에 있고(미deprecated) 트래픽 정상. */
    record Active(String host, String method, String pathTemplate, String specRef) implements Finding {
        @Override public Classification classification() { return Classification.ACTIVE; }
    }

    /** 문서엔 있으나 트래픽 없음 (S \ D). */
    record Unused(String host, String method, String pathTemplate, String specRef) implements Finding {
        @Override public Classification classification() { return Classification.UNUSED; }
    }

    /** 미문서 경로가 사실은 웹 페이지(비 API) (doc/04 §4.1.1). */
    record WebPage(String host, String method, String pathTemplate, double kindConfidence)
            implements Finding {
        @Override public Classification classification() { return Classification.UNDOCUMENTED_WEB_PAGE; }
    }
}
