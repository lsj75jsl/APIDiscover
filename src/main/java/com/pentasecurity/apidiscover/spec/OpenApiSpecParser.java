// OpenAPI 2.0(Swagger)/3.x → Canonical (doc/03 §2). 통합 OpenAPIParser 사용 — 2.0 은 v2-converter 로 3.0 자동 변환(D70)
package com.pentasecurity.apidiscover.spec;

import com.pentasecurity.apidiscover.model.CanonicalEndpoint;
import com.pentasecurity.apidiscover.model.ParamIn;
import com.pentasecurity.apidiscover.model.SpecParam;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.PathItem;
import io.swagger.v3.oas.models.media.MediaType;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.parameters.Parameter;
import io.swagger.v3.oas.models.parameters.RequestBody;
import io.swagger.v3.oas.models.servers.Server;
import io.swagger.parser.OpenAPIParser;
import io.swagger.v3.parser.core.models.ParseOptions;
import io.swagger.v3.parser.core.models.SwaggerParseResult;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Component;

@Component
public class OpenApiSpecParser implements SpecParser {

    @Override
    public SpecFormat format() {
        return SpecFormat.OPENAPI;
    }

    @Override
    public SpecParseResult parse(byte[] content) {
        String text = new String(content, StandardCharsets.UTF_8);

        ParseOptions options = new ParseOptions();
        options.setResolve(true); // $ref 해석

        // ★통합 OpenAPIParser: swagger:"2.0" → v2-converter 로 3.0 변환, openapi:3.x → v3 파싱(자동 판별, D70).
        SwaggerParseResult result = new OpenAPIParser().readContents(text, null, options);
        OpenAPI api = result.getOpenAPI();
        if (api == null) {
            throw new IllegalArgumentException(
                    "invalid OpenAPI document: " + String.join("; ", safeMessages(result)));
        }
        // api!=null 이나 messages 가 있으면 recoverable 경고($ref 미해석 등) → warnings 수집(doc/25 §A.1)
        List<String> warnings = (result.getMessages() == null) ? List.of() : List.copyOf(result.getMessages());

        String version = (api.getInfo() != null) ? api.getInfo().getVersion() : null;
        List<Origin> origins = origins(api);

        List<CanonicalEndpoint> endpoints = new ArrayList<>();
        if (api.getPaths() == null) {
            return new SpecParseResult(endpoints, warnings);
        }

        for (Map.Entry<String, PathItem> pathEntry : api.getPaths().entrySet()) {
            String pathKey = pathEntry.getKey();
            PathItem pathItem = pathEntry.getValue();
            if (pathItem == null) {
                continue;
            }
            for (Map.Entry<PathItem.HttpMethod, Operation> opEntry
                    : pathItem.readOperationsMap().entrySet()) {
                String method = opEntry.getKey().name();
                Operation op = opEntry.getValue();
                boolean deprecated = Boolean.TRUE.equals(op.getDeprecated());
                String sourceRef = "openapi#"
                        + (op.getOperationId() != null ? op.getOperationId() : method + " " + pathKey);
                List<SpecParam> params = params(pathItem, op);

                for (Origin origin : origins) {
                    String template = joinPath(origin.basePath(), pathKey);
                    endpoints.add(new CanonicalEndpoint(
                            method, template, origin.host(), deprecated, version, sourceRef, params));
                }
            }
        }
        return new SpecParseResult(endpoints, warnings);
    }

    // --- 파라미터 추출 (doc/37 §2): path-level + operation-level parameters + requestBody → BODY ---

    private static List<SpecParam> params(PathItem pathItem, Operation op) {
        List<SpecParam> out = new ArrayList<>();
        addParameters(out, pathItem.getParameters()); // path-item 공유 파라미터
        addParameters(out, op.getParameters());
        RequestBody body = op.getRequestBody();
        if (body != null) {
            boolean required = Boolean.TRUE.equals(body.getRequired());
            out.add(new SpecParam("body", ParamIn.BODY, required, bodyType(body)));
        }
        return out;
    }

    private static void addParameters(List<SpecParam> out, List<Parameter> parameters) {
        if (parameters == null) {
            return;
        }
        for (Parameter p : parameters) {
            ParamIn in = parseIn(p.getIn());
            if (in == null || p.getName() == null) {
                continue; // $ref 미해석/비표준 in → 건너뜀
            }
            out.add(new SpecParam(p.getName(), in,
                    Boolean.TRUE.equals(p.getRequired()), schemaType(p.getSchema())));
        }
    }

    /** OpenAPI in(query/path/header/cookie) → ParamIn. 알 수 없으면 null. */
    private static ParamIn parseIn(String in) {
        if (in == null) {
            return null;
        }
        try {
            return ParamIn.valueOf(in.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /** 스키마 타입 요약(array 는 item 타입 동봉). null → object. */
    private static String schemaType(Schema<?> s) {
        if (s == null) {
            return "object";
        }
        if ("array".equals(s.getType()) && s.getItems() != null) {
            String item = s.getItems().getType();
            return "array<" + (item != null ? item : "object") + ">";
        }
        return s.getType() != null ? s.getType() : "object";
    }

    /** requestBody content 의 첫 media type schema 요약(없으면 object). */
    private static String bodyType(RequestBody body) {
        if (body.getContent() == null || body.getContent().isEmpty()) {
            return "object";
        }
        MediaType mt = body.getContent().values().iterator().next();
        return (mt == null) ? "object" : schemaType(mt.getSchema());
    }

    private static List<String> safeMessages(SwaggerParseResult result) {
        List<String> messages = result.getMessages();
        return (messages == null || messages.isEmpty()) ? List.of("no parser messages") : messages;
    }

    // --- host / basePath 추출 (doc/03 §2.1) ---

    /** servers 항목에서 도출한 (host, basePath). host=null 이면 host-agnostic. */
    private record Origin(String host, String basePath) {}

    private List<Origin> origins(OpenAPI api) {
        List<Server> servers = api.getServers();
        if (servers == null || servers.isEmpty()) {
            return List.of(new Origin(null, ""));
        }
        // 중복 origin 제거(서버 여러 개가 같은 host/basePath 를 가리킬 수 있음)
        Set<Origin> distinct = new LinkedHashSet<>();
        for (Server server : servers) {
            distinct.add(toOrigin(server.getUrl()));
        }
        return new ArrayList<>(distinct);
    }

    private static Origin toOrigin(String url) {
        if (url == null || url.isBlank()) {
            return new Origin(null, "");
        }
        // 상대 경로 형태 (예: "/v2"). 단 "//host/base"(protocol-relative)는 host 를 담으므로 제외 —
        // Swagger 2.0→3.0 변환이 schemes 부재 시 servers.url 을 "//host/basePath" 로 내보낸다(D70).
        if (url.startsWith("/") && !url.startsWith("//")) {
            return new Origin(null, stripTrailingSlash(url));
        }
        try {
            URI uri = URI.create(url);
            String host = uri.getHost(); // 템플릿 변수 등으로 추출 불가 시 null
            String path = (uri.getPath() == null) ? "" : uri.getPath();
            return new Origin(host == null ? null : host.toLowerCase(), stripTrailingSlash(path));
        } catch (IllegalArgumentException e) {
            // URL 에 {var} 등 비표준 문자 → host-agnostic 으로 처리
            return new Origin(null, "");
        }
    }

    // --- path 정규화 (doc/03 §1.1) ---

    private static String joinPath(String basePath, String pathKey) {
        String joined = (basePath == null ? "" : basePath) + (pathKey == null ? "" : pathKey);
        joined = joined.replaceAll("/{2,}", "/"); // basePath+path 결합 시 // 정리
        if (joined.isEmpty()) {
            return "/";
        }
        if (!joined.startsWith("/")) {
            joined = "/" + joined;
        }
        return stripTrailingSlash(joined);
    }

    private static String stripTrailingSlash(String path) {
        if (path.length() > 1 && path.endsWith("/")) {
            return path.substring(0, path.length() - 1);
        }
        return path;
    }
}
