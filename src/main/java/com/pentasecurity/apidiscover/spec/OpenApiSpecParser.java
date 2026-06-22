// OpenAPI 2.0/3.x → Canonical (doc/03 §2). swagger-parser v3 사용(2.0은 내부 변환)
package com.pentasecurity.apidiscover.spec;

import com.pentasecurity.apidiscover.model.CanonicalEndpoint;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.PathItem;
import io.swagger.v3.oas.models.servers.Server;
import io.swagger.v3.parser.OpenAPIV3Parser;
import io.swagger.v3.parser.core.models.ParseOptions;
import io.swagger.v3.parser.core.models.SwaggerParseResult;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
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
    public List<CanonicalEndpoint> parse(byte[] content) {
        String text = new String(content, StandardCharsets.UTF_8);

        ParseOptions options = new ParseOptions();
        options.setResolve(true); // $ref 해석

        SwaggerParseResult result = new OpenAPIV3Parser().readContents(text, null, options);
        OpenAPI api = result.getOpenAPI();
        if (api == null) {
            throw new IllegalArgumentException(
                    "invalid OpenAPI document: " + String.join("; ", safeMessages(result)));
        }

        String version = (api.getInfo() != null) ? api.getInfo().getVersion() : null;
        List<Origin> origins = origins(api);

        List<CanonicalEndpoint> endpoints = new ArrayList<>();
        if (api.getPaths() == null) {
            return endpoints;
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

                for (Origin origin : origins) {
                    String template = joinPath(origin.basePath(), pathKey);
                    endpoints.add(new CanonicalEndpoint(
                            method, template, origin.host(), deprecated, version, sourceRef));
                }
            }
        }
        return endpoints;
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
        // 상대 경로 형태 (예: "/v2")
        if (url.startsWith("/")) {
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
