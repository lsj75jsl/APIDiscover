// 모든 API 응답 JSON 의 배열을 {count, items} 로 감싸는 전역 어드바이스 (사용자 요청)
package com.pentasecurity.apidiscover.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.core.MethodParameter;
import org.springframework.http.MediaType;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyAdvice;

/**
 * {@code com.pentasecurity.apidiscover.api} 컨트롤러의 JSON 응답에서 모든 배열을 {@link ArrayCountJson} 로 {count,items} 화(사용자 요청).
 * <p>String 본문(예: {@code /scan-result/detail} 의 report_json)은 {@code ScanController.inlineBasis} 에서 이미 래핑하므로 통과.
 * actuator 등 다른 패키지는 basePackages 스코프 밖이라 무영향. 204/202(null)·비-JSON 도 통과.
 */
@RestControllerAdvice(basePackages = "com.pentasecurity.apidiscover.api")
public class ArrayCountResponseAdvice implements ResponseBodyAdvice<Object> {

    private final ObjectMapper mapper;

    public ArrayCountResponseAdvice(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public boolean supports(MethodParameter returnType, Class<? extends HttpMessageConverter<?>> converterType) {
        return true;
    }

    @Override
    public Object beforeBodyWrite(Object body, MethodParameter returnType, MediaType selectedContentType,
                                  Class<? extends HttpMessageConverter<?>> selectedConverterType,
                                  ServerHttpRequest request, ServerHttpResponse response) {
        if (body == null || body instanceof String || body instanceof byte[]) {
            return body; // String=이미 래핑됨(inlineBasis)·null=본문 없음
        }
        if (selectedContentType == null || !selectedContentType.isCompatibleWith(MediaType.APPLICATION_JSON)) {
            return body;
        }
        JsonNode tree = mapper.valueToTree(body);
        return ArrayCountJson.wrap(tree);
    }
}
