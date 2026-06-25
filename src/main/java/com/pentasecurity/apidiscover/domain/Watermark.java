// 도메인별 증분 수집 watermark — 마지막 성공 수집 종료시각 (doc/05 §3.1)
package com.pentasecurity.apidiscover.domain;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

// 캡슐화 완료(doc/29 D41): private 필드 + 접근자. 애너테이션은 필드 유지 → JPA field access 불변.
@Entity
@Table(name = "watermark")
public class Watermark {

    /** 도메인(host). */
    @Id
    private String host;

    /** 마지막으로 성공 분석한 윈도우의 end (다음 윈도우의 start). */
    private Instant lastEnd;

    public String getHost() {
        return host;
    }

    public void setHost(String host) {
        this.host = host;
    }

    public Instant getLastEnd() {
        return lastEnd;
    }

    public void setLastEnd(Instant lastEnd) {
        this.lastEnd = lastEnd;
    }
}
