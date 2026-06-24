// Postman Collection v2.1 → Canonical (doc/03 §3, doc/14 §1). Jackson 트리 + 자체 매핑
package com.pentasecurity.apidiscover.spec;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pentasecurity.apidiscover.model.CanonicalEndpoint;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
public class PostmanSpecParser implements SpecParser {

    private static final Logger log = LoggerFactory.getLogger(PostmanSpecParser.class);
    private static final Pattern CURLY_VAR = Pattern.compile("\\{\\{\\s*([\\w.-]+)\\s*\\}\\}");

    private final ObjectMapper objectMapper;

    public PostmanSpecParser(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public SpecFormat format() {
        return SpecFormat.POSTMAN;
    }

    @Override
    public SpecParseResult parse(byte[] content) {
        JsonNode root;
        try {
            root = objectMapper.readTree(content);
        } catch (IOException e) {
            throw new IllegalArgumentException("invalid Postman JSON", e);
        }
        if (root == null || !root.isObject()) {
            throw new IllegalArgumentException("Postman root must be a JSON object");
        }
        JsonNode items = root.get("item");
        if (items == null || !items.isArray()) {
            throw new IllegalArgumentException("Postman collection missing 'item' array");
        }

        String version = text(root.path("info").path("version"));
        Map<String, String> vars = collectionVars(root);

        List<CanonicalEndpoint> out = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        for (JsonNode item : items) {
            walk(item, "", false, version, vars, out, warnings);
        }
        return new SpecParseResult(out, warnings);
    }

    /** recoverable 경고: warnings 수집 + 로그 유지(doc/25 §A.1). */
    private void warn(List<String> warnings, String message) {
        warnings.add(message);
        log.warn(message);
    }

    /** item 트리 DFS. 폴더 name·deprecated 를 자식에 전파. */
    private void walk(JsonNode node, String namePath, boolean parentDeprecated,
                      String version, Map<String, String> vars, List<CanonicalEndpoint> out,
                      List<String> warnings) {
        String name = text(node.path("name"));
        String path = namePath.isEmpty() ? (name == null ? "" : name)
                : (name == null ? namePath : namePath + "/" + name);
        boolean deprecated = parentDeprecated || isDeprecatedName(name);

        JsonNode children = node.get("item");
        if (children != null && children.isArray()) { // 폴더
            for (JsonNode child : children) {
                walk(child, path, deprecated, version, vars, out, warnings);
            }
            return;
        }
        JsonNode request = node.get("request");
        if (request != null && !request.isNull()) { // leaf
            CanonicalEndpoint ep = leaf(request, path, deprecated, version, vars, warnings);
            if (ep != null) {
                out.add(ep);
            }
        }
        // 폴더도 request 도 아니면 무시(빈 노드 — 경고 안 함)
    }

    private CanonicalEndpoint leaf(JsonNode request, String namePath, boolean deprecated,
                                   String version, Map<String, String> vars, List<String> warnings) {
        if (request.isTextual()) {
            warn(warnings, "Postman item '" + namePath + "' request has no method (string form), skipping");
            return null;
        }
        String method = text(request.path("method"));
        if (method == null) {
            warn(warnings, "Postman item '" + namePath + "' missing method, skipping");
            return null;
        }
        JsonNode urlNode = request.get("url");
        String rawPath = extractPath(urlNode);
        if (rawPath == null) {
            warn(warnings, "Postman item '" + namePath + "' missing url, skipping");
            return null;
        }
        String template = SpecNormalize.template(rawPath);
        String host = extractHost(urlNode, vars);
        boolean dep = deprecated || isDeprecatedDescription(request.path("description"));
        return new CanonicalEndpoint(method.toUpperCase(Locale.ROOT), template, host, dep,
                version, "postman#" + namePath);
    }

    // --- url path ---

    private static String extractPath(JsonNode urlNode) {
        if (urlNode == null || urlNode.isNull() || urlNode.isMissingNode()) {
            return null;
        }
        if (urlNode.isTextual()) {
            return pathFromRaw(urlNode.asText());
        }
        if (urlNode.isObject()) {
            JsonNode pathNode = urlNode.get("path");
            if (pathNode != null && pathNode.isArray()) {
                List<String> segs = new ArrayList<>();
                for (JsonNode seg : pathNode) {
                    if (seg.isTextual()) {
                        segs.add(seg.asText());
                    } else if (seg.isObject()) {
                        String v = text(seg.path("value"));
                        if (v != null) {
                            segs.add(v);
                        }
                    }
                }
                return "/" + String.join("/", segs);
            }
            if (pathNode != null && pathNode.isTextual()) {
                return pathFromRaw(pathNode.asText());
            }
            JsonNode raw = urlNode.get("raw");
            if (raw != null && raw.isTextual()) {
                return pathFromRaw(raw.asText());
            }
        }
        return null;
    }

    /** raw URL → 경로(scheme://host·선행 {{host}}·query/fragment 제거). */
    private static String pathFromRaw(String raw) {
        if (raw == null) {
            return null;
        }
        String s = raw.trim();
        int scheme = s.indexOf("://");
        if (scheme >= 0) {
            int slash = s.indexOf('/', scheme + 3);
            s = (slash < 0) ? "/" : s.substring(slash);
        } else if (s.startsWith("{{")) {
            int slash = s.indexOf('/');
            s = (slash < 0) ? "/" : s.substring(slash); // 선행 {{host}} 변수 제거
        }
        int q = s.indexOf('?');
        if (q >= 0) {
            s = s.substring(0, q);
        }
        int h = s.indexOf('#');
        if (h >= 0) {
            s = s.substring(0, h);
        }
        return s.isEmpty() ? "/" : s;
    }

    // --- url host (변수 host → 치환 시도, 실패 시 null) ---

    private static String extractHost(JsonNode urlNode, Map<String, String> vars) {
        if (urlNode == null || urlNode.isNull() || urlNode.isMissingNode()) {
            return null;
        }
        String hostStr;
        if (urlNode.isTextual()) {
            hostStr = hostFromRaw(urlNode.asText());
        } else {
            JsonNode hostNode = urlNode.get("host");
            if (hostNode != null && hostNode.isArray()) {
                List<String> parts = new ArrayList<>();
                hostNode.forEach(p -> parts.add(p.asText()));
                hostStr = String.join(".", parts);
            } else if (hostNode != null && hostNode.isTextual()) {
                hostStr = hostNode.asText();
            } else {
                JsonNode raw = urlNode.get("raw");
                hostStr = (raw != null && raw.isTextual()) ? hostFromRaw(raw.asText()) : null;
            }
        }
        return resolveHost(hostStr, vars);
    }

    private static String resolveHost(String hostStr, Map<String, String> vars) {
        if (hostStr == null || hostStr.isBlank()) {
            return null;
        }
        Matcher m = CURLY_VAR.matcher(hostStr);
        if (m.find()) {
            // 변수 host: collection variable 치환 시도, 실패/잔여변수면 null (host-agnostic)
            String value = vars.get(m.group(1));
            if (value == null) {
                return null;
            }
            String resolved = hostFromRaw(value);
            if (resolved == null || CURLY_VAR.matcher(resolved).find()) {
                return null;
            }
            return SpecNormalize.host(resolved);
        }
        return SpecNormalize.host(hostStr);
    }

    private static String hostFromRaw(String url) {
        if (url == null) {
            return null;
        }
        String s = url.trim();
        int scheme = s.indexOf("://");
        if (scheme >= 0) {
            int start = scheme + 3;
            int slash = s.indexOf('/', start);
            return (slash < 0) ? s.substring(start) : s.substring(start, slash);
        }
        if (s.startsWith("{{")) {
            int slash = s.indexOf('/');
            return (slash < 0) ? s : s.substring(0, slash); // 변수 host 후보(resolveHost 가 처리)
        }
        if (s.startsWith("/")) {
            return null; // 상대 경로 → host 없음
        }
        int slash = s.indexOf('/');
        String hostPart = (slash < 0) ? s : s.substring(0, slash);
        int q = hostPart.indexOf('?');
        if (q >= 0) {
            hostPart = hostPart.substring(0, q);
        }
        return hostPart.isEmpty() ? null : hostPart;
    }

    // --- deprecated / vars / util ---

    private static boolean isDeprecatedName(String name) {
        if (name == null) {
            return false;
        }
        String l = name.toLowerCase(Locale.ROOT);
        return l.contains("[deprecated]") || l.contains("(deprecated)");
    }

    private static boolean isDeprecatedDescription(JsonNode descNode) {
        if (descNode == null || descNode.isNull() || descNode.isMissingNode()) {
            return false;
        }
        String d = descNode.isObject() ? text(descNode.path("content")) : text(descNode);
        return d != null && d.toLowerCase(Locale.ROOT).contains("deprecated");
    }

    private static Map<String, String> collectionVars(JsonNode root) {
        Map<String, String> vars = new HashMap<>();
        JsonNode varNode = root.get("variable");
        if (varNode != null && varNode.isArray()) {
            for (JsonNode v : varNode) {
                String key = text(v.path("key"));
                String value = text(v.path("value"));
                if (key != null && value != null) {
                    vars.put(key, value);
                }
            }
        }
        return vars;
    }

    private static String text(JsonNode n) {
        if (n == null || !n.isValueNode() || n.isNull()) {
            return null;
        }
        String s = n.asText();
        return s.isEmpty() ? null : s;
    }
}
