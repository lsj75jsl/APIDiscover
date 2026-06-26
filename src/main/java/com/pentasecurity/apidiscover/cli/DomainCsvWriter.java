// 결합 Discovery(List<Finding>) → CSV 직렬화 — RFC4180 이스케이프·source 파생·discovered join (doc/31 §B2)
package com.pentasecurity.apidiscover.cli;

import com.pentasecurity.apidiscover.domain.DiscoveredEndpointRecord;
import com.pentasecurity.apidiscover.domain.DomainConfig;
import com.pentasecurity.apidiscover.model.Finding;
import com.pentasecurity.apidiscover.model.ParamCandidates;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * {@code CombinedDiscoveryService.forHost} 의 {@code List<Finding>} 를 CSV 로 평탄화한다(doc/31 §B2).
 *
 * <p>first/last_seen 은 Finding 미보유 → {@code discovered_endpoint} 를 (method,host,template) 키로 join(spec-only 행은 공란).
 * score 는 영속 안 됨(런타임) → CSV 범위 밖(헤더 미포함, doc/31 §B2 한계). 안정 정렬(status→method→path).
 */
public final class DomainCsvWriter {

    private DomainCsvWriter() {
    }

    static final String[] HEADER = {
            "host", "method", "path_template", "status", "source", "confidence", "severity",
            "estimated", "spec_ref", "preflight_ambiguous", "low_confidence",
            "param_query", "param_path", "first_seen", "last_seen"
    };

    /** 도메인 목록 CSV 헤더 (-domain -ls). */
    static final String[] DOMAIN_HEADER = {"host", "enabled", "hostnames", "discovered_at", "last_seen_at"};

    /**
     * 도메인 목록 → CSV 문자열(헤더 + 행, CRLF). hostnames 여러개는 ';' 조인, null Instant→공란.
     * 정렬은 호출측(Sort host)에서 이미 수행. escape/CRLF 는 {@link #toCsv} 와 동일 헬퍼 재사용(RFC4180).
     */
    public static String domainsToCsv(List<DomainConfig> domains) {
        StringBuilder sb = new StringBuilder();
        appendRecord(sb, DOMAIN_HEADER);
        for (DomainConfig d : domains) {
            appendRecord(sb, new String[]{
                    nz(d.getHost()),
                    Boolean.toString(d.isEnabled()),
                    names(d.getHostnames() != null ? d.getHostnames() : List.of()),
                    (d.getDiscoveredAt() != null) ? d.getDiscoveredAt().toString() : "",
                    (d.getLastSeenAt() != null) ? d.getLastSeenAt().toString() : ""
            });
        }
        return sb.toString();
    }

    /** (method,host,template) join 키 — CombinedDiscoveryService.key 동형(host=null→"*"). */
    public static String key(String method, String host, String template) {
        String h = (host == null) ? "*" : host.toLowerCase(Locale.ROOT);
        return method.toUpperCase(Locale.ROOT) + "|" + h + "|" + template;
    }

    /** findings → CSV 문자열(헤더 + 행, CRLF). bySig = key()→discovered 레코드(first/last_seen join, 없으면 공란). */
    public static String toCsv(List<Finding> findings, Map<String, DiscoveredEndpointRecord> bySig) {
        List<String[]> rows = new ArrayList<>(findings.size());
        for (Finding f : findings) {
            rows.add(row(f, bySig.get(key(f.method(), f.host(), f.pathTemplate()))));
        }
        // 안정 정렬(가독성, ETag 아님): status → method → path
        rows.sort(Comparator.comparing((String[] r) -> r[3])
                .thenComparing(r -> r[1]).thenComparing(r -> r[2]));

        StringBuilder sb = new StringBuilder();
        appendRecord(sb, HEADER);
        for (String[] r : rows) {
            appendRecord(sb, r);
        }
        return sb.toString();
    }

    private static String[] row(Finding f, DiscoveredEndpointRecord rec) {
        String status = f.classification().name();
        String source;
        String confidence = "";
        String severity = "";
        String estimated = "";
        String specRef = "";
        String preflightAmbiguous = "";
        String lowConfidence = "";
        ParamCandidates params = ParamCandidates.EMPTY;

        switch (f) {
            case Finding.Shadow s -> {
                source = "detected";
                confidence = num(s.confidence());
                lowConfidence = Boolean.toString(s.lowConfidence());
                params = s.params();
            }
            case Finding.Zombie z -> {
                source = "both";
                confidence = num(z.confidence());
                severity = (z.severity() != null) ? z.severity().band().name() : "";
                estimated = Boolean.toString(z.estimated());
                specRef = nz(z.specRef());
                lowConfidence = Boolean.toString(z.lowConfidence());
                params = z.params();
            }
            case Finding.Active a -> {
                source = "both";
                specRef = nz(a.specRef());
                params = a.params();
            }
            case Finding.Unused u -> {
                source = "spec";
                specRef = nz(u.specRef());
                preflightAmbiguous = Boolean.toString(u.preflightAmbiguous());
            }
            case Finding.WebPage w -> {
                source = "detected";
                confidence = num(w.kindConfidence());
            }
        }

        String firstSeen = (rec != null && rec.getFirstSeen() != null) ? rec.getFirstSeen().toString() : "";
        String lastSeen = (rec != null && rec.getLastSeen() != null) ? rec.getLastSeen().toString() : "";

        return new String[]{
                nz(f.host()), nz(f.method()), nz(f.pathTemplate()), status, source, confidence, severity,
                estimated, specRef, preflightAmbiguous, lowConfidence,
                names(params.query().stream().map(ParamCandidates.QueryParam::name).toList()),
                names(params.path().stream().map(ParamCandidates.PathParam::token).toList()),
                firstSeen, lastSeen
        };
    }

    private static String names(List<String> names) {
        return String.join(";", names);
    }

    private static String num(double v) {
        return Double.toString(v);
    }

    private static String nz(String v) {
        return (v == null) ? "" : v;
    }

    private static void appendRecord(StringBuilder sb, String[] fields) {
        sb.append(java.util.Arrays.stream(fields).map(DomainCsvWriter::escape).collect(Collectors.joining(",")));
        sb.append("\r\n"); // RFC4180 CRLF
    }

    /** RFC4180: ','·'"'·CR·LF 포함 필드는 "..." 로 감싸고 내부 '"'→'""'. */
    private static String escape(String field) {
        if (field.indexOf(',') < 0 && field.indexOf('"') < 0
                && field.indexOf('\r') < 0 && field.indexOf('\n') < 0) {
            return field;
        }
        return "\"" + field.replace("\"", "\"\"") + "\"";
    }
}
