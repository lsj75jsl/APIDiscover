// 게이트 DROP_* 사유별 non_api 집계 (doc/12 §3). 스캔 결과에 임베드되어 /result 로 노출
package com.pentasecurity.apidiscover.model;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * 관찰됐지만 spec 미매칭 + 게이트 탈락한 시그니처를 사유별로 집계(doc/12 §1).
 * non-OPTIONS·spec 미매칭·게이트 DROP_* 만 대상(OPTIONS·spec 매칭·ADMIT 은 제외).
 * {@code staticFile}=정적 파일(확장자/$type=library) 하드 veto 탈락(D55 후속, 사용자 요구).
 */
public record DroppedNonApi(int excluded, int webForm, int lowScore, int staticFile) {

    /** 사유 합계 (파생, 단일 진실원). JSON 에 "total" 로 출현(Jackson record accessor 직렬화). */
    @JsonProperty("total")
    public int total() {
        return excluded + webForm + lowScore + staticFile;
    }
}
