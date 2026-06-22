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
}
