// 업로드 문서의 포맷 자동 감지 (doc/03 §5)
package com.pentasecurity.apidiscover.spec;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;
import java.nio.charset.StandardCharsets;
import org.springframework.stereotype.Component;

@Component
public class SpecFormatDetector {

    // YAML 매퍼는 JSON 도 파싱한다(YAML 은 JSON 의 상위집합).
    private final YAMLMapper yamlMapper = new YAMLMapper();

    /** 지원 포맷을 판별한다. 인식 불가 시 IllegalArgumentException. */
    public SpecFormat detect(byte[] content) {
        String text = new String(content, StandardCharsets.UTF_8).strip();
        if (text.isEmpty()) {
            throw new IllegalArgumentException("empty document");
        }

        // JSON/YAML 트리 검사
        try {
            JsonNode root = yamlMapper.readTree(content);
            if (root != null && root.isObject()) {
                if (root.has("openapi") || root.has("swagger")) {
                    return SpecFormat.OPENAPI;
                }
                JsonNode info = root.get("info");
                String schema = (info != null && info.hasNonNull("schema"))
                        ? info.get("schema").asText() : "";
                boolean postmanSchema = schema.contains("getpostman.com")
                        || schema.contains("schema.postman.com"); // 신버전 컬렉션 (doc/14 §3)
                if (postmanSchema || (root.has("item") && info != null)) {
                    return SpecFormat.POSTMAN;
                }
            }
        } catch (Exception e) {
            // 파싱 실패 → 아래 미지원 처리
        }

        throw new IllegalArgumentException("unsupported or unrecognized spec format");
    }
}
