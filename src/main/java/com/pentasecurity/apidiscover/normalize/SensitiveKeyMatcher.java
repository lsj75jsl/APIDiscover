// 민감 query 파라미터 키 매칭 (대소문자 무시) (doc/13 §3.1)
package com.pentasecurity.apidiscover.normalize;

import com.pentasecurity.apidiscover.config.SensitiveKeyProperties;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

/**
 * 키 이름이 민감 목록(정확 일치) 또는 정규식(부분 일치)에 매칭되는지 대소문자 무시로 판정.
 * 정책(doc/13 §3.2): 매칭 시 이름은 보존하고 sensitive 플래그 + 값 길이 버킷 억제(호출부 ParamCandidateExtractor).
 */
@Component
public class SensitiveKeyMatcher {

    private final Set<String> names;        // 소문자
    private final List<Pattern> patterns;   // CASE_INSENSITIVE

    public SensitiveKeyMatcher(SensitiveKeyProperties props) {
        this.names = props.names().stream()
                .map(n -> n.toLowerCase(java.util.Locale.ROOT))
                .collect(Collectors.toUnmodifiableSet());
        this.patterns = props.patterns().stream()
                .map(p -> Pattern.compile(p, Pattern.CASE_INSENSITIVE))
                .toList();
    }

    /** key 가 민감 파라미터인지 (이름 정확일치 또는 정규식 부분일치, 대소문자 무시). */
    public boolean isSensitive(String key) {
        if (key == null || key.isBlank()) {
            return false;
        }
        String lower = key.toLowerCase(java.util.Locale.ROOT);
        if (names.contains(lower)) {
            return true;
        }
        for (Pattern p : patterns) {
            if (p.matcher(key).matches()) {
                return true;
            }
        }
        return false;
    }
}
