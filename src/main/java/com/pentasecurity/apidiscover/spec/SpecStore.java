// Spec Store — 업로드 시 1회 파싱·영속, 스캔 시 Canonical 재사용 (doc/03 §7)
package com.pentasecurity.apidiscover.spec;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pentasecurity.apidiscover.domain.DomainConfigRepository;
import com.pentasecurity.apidiscover.domain.SpecRecord;
import com.pentasecurity.apidiscover.domain.SpecRecordRepository;
import com.pentasecurity.apidiscover.match.EndpointMatcherCache;
import com.pentasecurity.apidiscover.model.CanonicalEndpoint;
import com.pentasecurity.apidiscover.model.SpecMergeStrategy;
import com.pentasecurity.apidiscover.model.SpecSource;
import com.pentasecurity.apidiscover.report.EtagUtil;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class SpecStore {

    private static final TypeReference<List<CanonicalEndpoint>> CANONICAL_LIST =
            new TypeReference<>() {};
    private static final TypeReference<List<String>> WARNINGS_LIST =
            new TypeReference<>() {};

    private final SpecRecordRepository repo;
    private final SpecFormatDetector detector;
    private final ObjectMapper objectMapper;
    private final EndpointMatcherCache matcherCache;
    private final DomainConfigRepository domainRepo;
    private final Map<SpecFormat, SpecParser> parsersByFormat;

    public SpecStore(SpecRecordRepository repo,
                     SpecFormatDetector detector,
                     ObjectMapper objectMapper,
                     EndpointMatcherCache matcherCache,
                     DomainConfigRepository domainRepo,
                     List<SpecParser> parsers) {
        this.repo = repo;
        this.detector = detector;
        this.objectMapper = objectMapper;
        this.matcherCache = matcherCache;
        this.domainRepo = domainRepo;
        Map<SpecFormat, SpecParser> map = new EnumMap<>(SpecFormat.class);
        for (SpecParser parser : parsers) {
            map.put(parser.format(), parser);
        }
        this.parsersByFormat = map;
    }

    /** 하위호환 — 문서명·파일명 미지정 업로드는 "default" 문서(현행 단일 스펙 경로). */
    public SpecRecord upload(String host, byte[] content) {
        return upload(host, "default", content, null);
    }

    /** 파일명 동반 업로드(PUT /spec ?filename=, doc/35 M2/M6) — specName="default"(M7 에서 filename→specName 도출). */
    public SpecRecord upload(String host, byte[] content, String filename) {
        return upload(host, "default", content, filename);
    }

    /** 하위호환 — 파일명 미지정 멀티문서 업로드(specName 지정). */
    public SpecRecord upload(String host, String specName, byte[] content) {
        return upload(host, specName, content, null);
    }

    /**
     * 업로드 시점 처리(동기): 포맷 감지 → 파싱 → 검증 → 새 specVersion 으로 영속. 멀티 스펙(doc/26 §3/§5).
     * 모드별 비활성화: SEPARATE=host 전체 교체, MERGE/VERSION_GROUPED=같은 specName 만(형제 문서 유지).
     * 무효 문서/빈 스펙은 IllegalArgumentException(중앙에 400 으로 동기 피드백, doc/07 §3.1). {@code filename}=원본 파일명(nullable, doc/35).
     */
    @Transactional
    public SpecRecord upload(String host, String specName, byte[] content, String filename) {
        SpecFormat format = detector.detect(content);
        SpecParser parser = parsersByFormat.get(format);
        if (parser == null) {
            throw new IllegalStateException("no parser registered for format " + format);
        }

        // parse 직후 전 포맷 균일 정규화(dedupe+deprecated OR+안정정렬, doc/14 §0.1)
        SpecParseResult parsed = parser.parse(content);
        List<CanonicalEndpoint> canonical = SpecCanonicalizer.canonicalize(parsed.endpoints());
        if (canonical.isEmpty()) {
            throw new IllegalArgumentException("no endpoints found in spec");
        }

        String name = normalizeName(specName);
        long nextVersion = repo.findFirstByHostOrderBySpecVersionDesc(host)
                .map(prev -> prev.getSpecVersion() + 1)
                .orElse(1L);

        // 모드별 기존 active 비활성화 (doc/26 §5). null specName(기존행)=default 로 해석.
        SpecMergeStrategy mode = mode(host);
        boolean replaceAll = (mode == SpecMergeStrategy.SEPARATE);
        for (SpecRecord prev : repo.findByHostAndActiveIsTrue(host)) {
            if (replaceAll || name.equals(normalizeName(prev.getSpecName()))) {
                prev.setActive(false);
                repo.save(prev);
            }
        }

        SpecRecord record = new SpecRecord();
        record.setHost(host);
        record.setSpecName(name);
        record.setFilename(filename); // 원본 파일명(nullable, doc/35 M2/M6)
        record.setFormat(format);
        record.setSpecVersion(nextVersion);
        record.setRawDoc(content);
        record.setCanonicalJson(writeCanonical(canonical));
        record.setWarningsJson(writeWarnings(parsed.warnings()));
        record.setEndpointCount(canonical.size());
        record.setUploadedAt(Instant.now());
        record.setActive(true);

        SpecRecord saved = repo.save(record);
        // 업로드(콘텐츠 변화) → 구버전 matcher 슬롯 해제(doc/15 §2). 새 합성버전은 version-miss 로 자동 재빌드.
        matcherCache.invalidate(host);
        return saved;
    }

    /**
     * 스캔 시 활성 문서 집합의 Canonical 병합 로드(원본 재파싱 없음, doc/03 §7.5, doc/26 §5).
     * 단일 active 문서면 merge=canonicalize 동치(무회귀).
     */
    public List<CanonicalEndpoint> loadActiveCanonical(String host) {
        List<SpecRecord> actives = repo.findByHostAndActiveIsTrue(host);
        if (actives.isEmpty()) {
            throw new IllegalStateException("no active spec for host " + host);
        }
        List<SpecCanonicalizer.VersionedCanonical> docs = new ArrayList<>(actives.size());
        for (SpecRecord r : actives) {
            docs.add(new SpecCanonicalizer.VersionedCanonical(r.getSpecVersion(), readCanonical(r.getCanonicalJson())));
        }
        return SpecCanonicalizer.merge(docs);
    }

    /**
     * merged canonical 콘텐츠 결정적 해시 → 합성 spec 버전(long). 동일 콘텐츠=동일 버전, 문서순서 무관(doc/26 §8).
     * 해시는 ETag 와 동일 SHA-256(앞 16 hex=64bit)을 사용 — 코드베이스 일관·CRC32(32bit)보다 충돌 강건(doc/07 §8).
     */
    public static long syntheticVersion(List<CanonicalEndpoint> canonical, ObjectMapper om) {
        try {
            return Long.parseUnsignedLong(EtagUtil.of(om.writeValueAsString(canonical)), 16);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("failed to hash canonical for synthetic version", e);
        }
    }

    private static String normalizeName(String specName) {
        return (specName == null || specName.isBlank()) ? "default" : specName;
    }

    /** 도메인 병합 전략(없거나 null → MERGE=현행, doc/26 §5). */
    private SpecMergeStrategy mode(String host) {
        return domainRepo.findById(host)
                .map(c -> c.getSpecMergeStrategy())
                .filter(Objects::nonNull)
                .orElse(SpecMergeStrategy.MERGE);
    }

    /** 활성 스펙 메타(없으면 empty). 멀티문서면 최신 버전 1건(존재 판정·단건 메타). */
    public Optional<SpecRecord> activeMeta(String host) {
        return repo.findFirstByHostAndActiveIsTrueOrderBySpecVersionDesc(host);
    }

    /** 활성 문서 집합(멀티 스펙). SpecSource documents·결합 뷰 입력 (doc/26 §4/§7). */
    public List<SpecRecord> activeRecords(String host) {
        return repo.findByHostAndActiveIsTrue(host);
    }

    /**
     * 활성 문서 집합 → SpecSource (doc/26 §4 멀티문서 확장). specName 정렬로 결정적(ETag 안정).
     * format=단일 포맷이면 그것·혼합 null, warnings=문서별 union(dedupe), documents=문서별 메타.
     */
    public static SpecSource specSourceFrom(long specVersion, List<SpecRecord> records, ObjectMapper om) {
        if (records.isEmpty()) {
            return SpecSource.EMPTY;
        }
        List<SpecRecord> sorted = records.stream()
                .sorted(Comparator.comparing((SpecRecord r) -> normalizeName(r.getSpecName()))
                        .thenComparingLong(r -> r.getSpecVersion()))
                .toList();
        SpecFormat format = sorted.get(0).getFormat();
        LinkedHashSet<String> warnings = new LinkedHashSet<>();
        List<SpecSource.SpecDocument> docs = new ArrayList<>(sorted.size());
        for (SpecRecord r : sorted) {
            if (r.getFormat() != format) {
                format = null; // 혼합 포맷 → null (doc/26 §9)
            }
            warnings.addAll(parseWarnings(r.getWarningsJson(), om));
            docs.add(new SpecSource.SpecDocument(normalizeName(r.getSpecName()), r.getFormat(), r.getSpecVersion()));
        }
        return new SpecSource(specVersion, format, new ArrayList<>(warnings), docs);
    }

    /** SpecRecord.warningsJson(List&lt;String&gt;) 파싱. null/손상 → 빈 list. */
    public static List<String> parseWarnings(String json, ObjectMapper om) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            return om.readValue(json, WARNINGS_LIST);
        } catch (JsonProcessingException e) {
            return List.of();
        }
    }

    private String writeCanonical(List<CanonicalEndpoint> canonical) {
        try {
            return objectMapper.writeValueAsString(canonical);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("failed to serialize canonical endpoints", e);
        }
    }

    /** warnings 직렬화. 빈 list → null(컬럼 비움). */
    private String writeWarnings(List<String> warnings) {
        if (warnings == null || warnings.isEmpty()) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(warnings);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("failed to serialize spec warnings", e);
        }
    }

    private List<CanonicalEndpoint> readCanonical(String json) {
        try {
            return objectMapper.readValue(json, CANONICAL_LIST);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("failed to deserialize canonical endpoints", e);
        }
    }
}
