// 도메인별 spec endpoint 누적 관측 이력 — cross-scan recency(entrenchment) 입력 (doc/24 §3)
package com.pentasecurity.apidiscover.domain;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Lob;
import jakarta.persistence.Table;
import java.time.Instant;

// NOTE: 스캐폴딩 단순화를 위해 public 필드(TODO 캡슐화). 벤더 JSON 타입 미사용 — @Lob String(H2/PG 이식, doc/11/D17 컨벤션).
// spec 매칭 endpoint 만 기록 → spec 크기로 bound(누적 방지). ddl-auto(update) 신규 테이블, 기존 데이터 무영향.
@Entity
@Table(name = "endpoint_history")
public class EndpointHistory {

    /** PK = host (도메인당 1행, in-place 갱신). */
    @Id
    public String host;

    /** Map&lt;specKey, EndpointObservation&gt; JSON. specKey="METHOD|host|template"(host=null→*). */
    @Lob
    public String historyJson;

    public Instant updatedAt;
}
