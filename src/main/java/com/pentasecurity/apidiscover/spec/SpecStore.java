// Spec Store — 업로드 시 1회 파싱·영속, 스캔 시 Canonical 재사용 (doc/03 §7)
package com.pentasecurity.apidiscover.spec;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pentasecurity.apidiscover.domain.SpecRecord;
import com.pentasecurity.apidiscover.domain.SpecRecordRepository;
import com.pentasecurity.apidiscover.model.CanonicalEndpoint;
import java.time.Instant;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class SpecStore {

    private static final TypeReference<List<CanonicalEndpoint>> CANONICAL_LIST =
            new TypeReference<>() {};

    private final SpecRecordRepository repo;
    private final SpecFormatDetector detector;
    private final ObjectMapper objectMapper;
    private final Map<SpecFormat, SpecParser> parsersByFormat;

    public SpecStore(SpecRecordRepository repo,
                     SpecFormatDetector detector,
                     ObjectMapper objectMapper,
                     List<SpecParser> parsers) {
        this.repo = repo;
        this.detector = detector;
        this.objectMapper = objectMapper;
        Map<SpecFormat, SpecParser> map = new EnumMap<>(SpecFormat.class);
        for (SpecParser parser : parsers) {
            map.put(parser.format(), parser);
        }
        this.parsersByFormat = map;
    }

    /**
     * 업로드 시점 처리(동기): 포맷 감지 → 파싱 → 검증 → 새 specVersion 으로 영속.
     * 무효 문서/빈 스펙은 IllegalArgumentException(중앙에 400 으로 동기 피드백, doc/07 §3.1).
     */
    @Transactional
    public SpecRecord upload(String host, byte[] content) {
        SpecFormat format = detector.detect(content);
        SpecParser parser = parsersByFormat.get(format);
        if (parser == null) {
            throw new IllegalStateException("no parser registered for format " + format);
        }

        // parse 직후 전 포맷 균일 정규화(dedupe+deprecated OR+안정정렬, doc/14 §0.1)
        List<CanonicalEndpoint> canonical = SpecCanonicalizer.canonicalize(parser.parse(content));
        if (canonical.isEmpty()) {
            throw new IllegalArgumentException("no endpoints found in spec");
        }

        long nextVersion = repo.findFirstByHostOrderBySpecVersionDesc(host)
                .map(prev -> prev.specVersion + 1)
                .orElse(1L);

        // 이전 활성 버전 비활성화 (활성은 항상 1개)
        for (SpecRecord prev : repo.findByHostAndActiveIsTrue(host)) {
            prev.active = false;
            repo.save(prev);
        }

        SpecRecord record = new SpecRecord();
        record.host = host;
        record.format = format;
        record.specVersion = nextVersion;
        record.rawDoc = content;
        record.canonicalJson = writeCanonical(canonical);
        record.endpointCount = canonical.size();
        record.uploadedAt = Instant.now();
        record.active = true;

        // TODO(doc/03 §7.3): EndpointMatcher 캐시 (host, specVersion) evict — 매처 구현 후 연결
        return repo.save(record);
    }

    /** 스캔 시 활성 버전의 Canonical 집합 로드(원본 재파싱 없음, doc/03 §7.5). */
    public List<CanonicalEndpoint> loadActiveCanonical(String host) {
        SpecRecord record = repo.findFirstByHostAndActiveIsTrueOrderBySpecVersionDesc(host)
                .orElseThrow(() -> new IllegalStateException("no active spec for host " + host));
        return readCanonical(record.canonicalJson);
    }

    /** 활성 스펙 메타(없으면 empty). */
    public Optional<SpecRecord> activeMeta(String host) {
        return repo.findFirstByHostAndActiveIsTrueOrderBySpecVersionDesc(host);
    }

    private String writeCanonical(List<CanonicalEndpoint> canonical) {
        try {
            return objectMapper.writeValueAsString(canonical);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("failed to serialize canonical endpoints", e);
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
