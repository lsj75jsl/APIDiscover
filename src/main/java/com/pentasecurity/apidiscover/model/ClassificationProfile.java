// 분류 프로파일 — preset 3종(HIGH/MIDDLE/LOW) + CUSTOM (doc/10 §1.1)
package com.pentasecurity.apidiscover.model;

/**
 * 분류 설정 프로파일. HIGH/MIDDLE/LOW 는 {@code ApiScorer.Profile} preset 으로 매핑되고,
 * CUSTOM 은 MIDDLE 베이스 + 가중치 override 로 해석된다(doc/10 §3).
 * {@code ApiScorer.Profile}(3 preset) 와 분리한 이유: CUSTOM 은 preset 가중치가 없어 ApiScorer 단독으로는 무의미.
 */
public enum ClassificationProfile {
    HIGH, MIDDLE, LOW, CUSTOM
}
