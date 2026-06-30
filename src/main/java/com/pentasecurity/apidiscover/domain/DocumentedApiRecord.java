// 영속 API 인벤토리 — 문서(specName)별 등록 API 의 현재 상태·파라미터 (doc/37 §1). 자연키 host+specName+method+path_template
package com.pentasecurity.apidiscover.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.Instant;
import org.hibernate.annotations.DynamicUpdate;

/**
 * 신규 테이블 {@code documented_api} (ddl-auto=update = ADD TABLE·무손실, doc/37 §1). status=현재 상태 속성(직전 reconcile 결과),
 * history 아님. paramsJson=text(@Lob 금지=oid 함정 회피). @DynamicUpdate=reconcile 부분 UPDATE. 캡슐화(D41): private 필드 + 접근자.
 */
@Entity
@DynamicUpdate
@Table(name = "documented_api",
        uniqueConstraints = @UniqueConstraint(
                columnNames = {"host", "spec_name", "method", "path_template"}),
        indexes = {
                @Index(columnList = "host"),
                @Index(columnList = "host,spec_name"),
                @Index(columnList = "host,status")
        })
public class DocumentedApiRecord {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String host;

    /** 문서 식별(filename 도출, 미전달=default). reconcile 이 정규화해 항상 비-null. */
    private String specName;

    private String method;

    @Column(columnDefinition = "text")
    private String pathTemplate;

    /** 구조화 파라미터 직렬화(List&lt;SpecParam&gt;). ★@Lob 금지(oid 함정, doc/37 §1). */
    @Column(columnDefinition = "text")
    private String paramsJson;

    @Enumerated(EnumType.STRING)
    private ApiStatus status;

    @Enumerated(EnumType.STRING)
    private ApiChangeKind lastChange;

    /** 직전 reconcile 가 UPDATED 일 때 breaking 변경 여부(doc/38 §3). ddl-auto ADD·기본 false. 그 외 lastChange 는 false. */
    private boolean lastChangeBreaking;

    /** 직전 UPDATED 의 param delta(ParamChange) 직렬화(doc/38 §3.4). nullable·UPDATED 외 null. ★@Lob 금지(oid 함정). */
    @Column(columnDefinition = "text")
    private String lastChangeDetailJson;

    /** 스펙 deprecated 표기(Zombie 입력, doc/37 §6). */
    private boolean deprecated;

    /** 스펙 version(nullable). */
    private String version;

    /** 이 행을 마지막으로 갱신한 spec_record.specVersion. */
    private long sourceSpecVersion;

    /** 이 문서에 처음 등장(추가)한 시각(트래픽 아님, doc/37 §1). */
    private Instant firstDocumentedAt;

    /** 이 문서의 마지막 업로드에서 문서에 존재한 시각. */
    private Instant lastDocumentedAt;

    /** status/param 마지막 변경 시각. */
    private Instant changedAt;

    /** 자동생성 PK — getter 만(setter 미노출, doc/29 §4). */
    public Long getId() {
        return id;
    }

    public String getHost() {
        return host;
    }

    public void setHost(String host) {
        this.host = host;
    }

    public String getSpecName() {
        return specName;
    }

    public void setSpecName(String specName) {
        this.specName = specName;
    }

    public String getMethod() {
        return method;
    }

    public void setMethod(String method) {
        this.method = method;
    }

    public String getPathTemplate() {
        return pathTemplate;
    }

    public void setPathTemplate(String pathTemplate) {
        this.pathTemplate = pathTemplate;
    }

    public String getParamsJson() {
        return paramsJson;
    }

    public void setParamsJson(String paramsJson) {
        this.paramsJson = paramsJson;
    }

    public ApiStatus getStatus() {
        return status;
    }

    public void setStatus(ApiStatus status) {
        this.status = status;
    }

    public ApiChangeKind getLastChange() {
        return lastChange;
    }

    public void setLastChange(ApiChangeKind lastChange) {
        this.lastChange = lastChange;
    }

    public boolean isLastChangeBreaking() {
        return lastChangeBreaking;
    }

    public void setLastChangeBreaking(boolean lastChangeBreaking) {
        this.lastChangeBreaking = lastChangeBreaking;
    }

    public String getLastChangeDetailJson() {
        return lastChangeDetailJson;
    }

    public void setLastChangeDetailJson(String lastChangeDetailJson) {
        this.lastChangeDetailJson = lastChangeDetailJson;
    }

    public boolean isDeprecated() {
        return deprecated;
    }

    public void setDeprecated(boolean deprecated) {
        this.deprecated = deprecated;
    }

    public String getVersion() {
        return version;
    }

    public void setVersion(String version) {
        this.version = version;
    }

    public long getSourceSpecVersion() {
        return sourceSpecVersion;
    }

    public void setSourceSpecVersion(long sourceSpecVersion) {
        this.sourceSpecVersion = sourceSpecVersion;
    }

    public Instant getFirstDocumentedAt() {
        return firstDocumentedAt;
    }

    public void setFirstDocumentedAt(Instant firstDocumentedAt) {
        this.firstDocumentedAt = firstDocumentedAt;
    }

    public Instant getLastDocumentedAt() {
        return lastDocumentedAt;
    }

    public void setLastDocumentedAt(Instant lastDocumentedAt) {
        this.lastDocumentedAt = lastDocumentedAt;
    }

    public Instant getChangedAt() {
        return changedAt;
    }

    public void setChangedAt(Instant changedAt) {
        this.changedAt = changedAt;
    }
}
