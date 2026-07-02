// LogQL 쿼리 빌더 (doc/05 §2.2). {job=access_log, hostname=...} |= `domain`
package com.pentasecurity.apidiscover.ingest;

import com.pentasecurity.apidiscover.config.ApiDiscoverProperties;
import org.springframework.stereotype.Component;

@Component
public class LokiQueryBuilder {

    private final ApiDiscoverProperties props;

    public LokiQueryBuilder(ApiDiscoverProperties props) {
        this.props = props;
    }

    /** 엣지 서버(hostname 라벨)로 1차 축소 + 도메인 라인필터. 권장 경로. */
    public String build(String hostnameLabel, String domain) {
        return "{job=\"" + props.loki().jobLabel() + "\", hostname=\"" + hostnameLabel + "\"} |= `"
                + domain + "`";
    }

    /** hostname 미지정 (스캔량 큼, 비권장). */
    public String build(String domain) {
        return "{job=\"" + props.loki().jobLabel() + "\"} |= `" + domain + "`";
    }

    /**
     * D63 배칭: 엣지 1개 + 도메인 N개를 정규식 OR 1쿼리로 — {@code |~ `(d1|d2|…)`}.
     * Loki 라인필터는 비인덱스(청크 전체 스캔)라, 같은 엣지를 도메인별로 N번 재읽던 것을 1번으로 줄인다.
     * ★도메인은 RE2 이스케이프 필수 — {@code a.com} 이 {@code axcom} 도 매치되는 과탐 방지.
     */
    public String buildBatch(String hostnameLabel, java.util.List<String> domains) {
        StringBuilder alt = new StringBuilder();
        for (String d : domains) {
            if (alt.length() > 0) {
                alt.append('|');
            }
            alt.append(escapeRegex(d));
        }
        return "{job=\"" + props.loki().jobLabel() + "\", hostname=\"" + hostnameLabel + "\"} |~ `("
                + alt + ")`";
    }

    /** RE2 메타문자 이스케이프(도메인은 [a-z0-9.-] 지만 방어적 전체 이스케이프). */
    static String escapeRegex(String s) {
        StringBuilder out = new StringBuilder(s.length() + 4);
        for (char c : s.toCharArray()) {
            if ("\\.+*?()|[]{}^$".indexOf(c) >= 0) {
                out.append('\\');
            }
            out.append(c);
        }
        return out.toString();
    }
}
