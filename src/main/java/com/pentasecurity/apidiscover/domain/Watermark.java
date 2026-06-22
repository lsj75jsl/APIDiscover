// 도메인별 증분 수집 watermark — 마지막 성공 수집 종료시각 (doc/05 §3.1)
package com.pentasecurity.apidiscover.domain;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

// NOTE: 스캐폴딩 단순화를 위해 public 필드 사용(TODO: 캡슐화).
@Entity
@Table(name = "watermark")
public class Watermark {

    /** 도메인(host). */
    @Id
    public String host;

    /** 마지막으로 성공 분석한 윈도우의 end (다음 윈도우의 start). */
    public Instant lastEnd;
}
