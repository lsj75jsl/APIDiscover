// 영속 API 인벤토리 리포지토리 — reconcile·/apis 조회·DELETED 키(Zombie) (doc/37 §1·§3·§6)
package com.pentasecurity.apidiscover.domain;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface DocumentedApiRepository extends JpaRepository<DocumentedApiRecord, Long> {

    /** reconcile 입력 — 그 문서(specName)의 현재 인벤토리 상태. 삭제 격리(다른 specName 미접근, doc/37 §3). */
    List<DocumentedApiRecord> findByHostAndSpecName(String host, String specName);

    /** /apis 목록 — host 전체. 결정적 정렬(specName 은 reconcile 정규화로 항상 비-null, doc/37 §4). */
    @Query("select d from DocumentedApiRecord d where d.host = :host "
            + "order by d.specName asc, d.pathTemplate asc, d.method asc")
    List<DocumentedApiRecord> findByHostOrdered(@Param("host") String host);

    /**
     * Zombie 결합용 — host 의 DELETED 키(method, path_template) 1쿼리(doc/37 §6.5). paramsJson(text) 미선택.
     * 분류기는 이 키집합과 관측 endpoint 를 교차(active spec 미매칭 SHADOW → deleted-from-spec Zombie).
     */
    @Query("select d.method, d.pathTemplate from DocumentedApiRecord d "
            + "where d.host = :host and d.status = :status")
    List<Object[]> findKeysByHostAndStatus(@Param("host") String host, @Param("status") ApiStatus status);
}
