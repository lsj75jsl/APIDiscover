// CSV(method,path,host,deprecated,version,description) → Canonical (doc/03 §4, doc/14 §2). univocity
package com.pentasecurity.apidiscover.spec;

import com.pentasecurity.apidiscover.model.CanonicalEndpoint;
import com.univocity.parsers.csv.CsvParser;
import com.univocity.parsers.csv.CsvParserSettings;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
public class CsvSpecParser implements SpecParser {

    private static final Logger log = LoggerFactory.getLogger(CsvSpecParser.class);
    private static final Set<String> TRUE_TOKENS = Set.of("true", "1", "y", "yes");
    private static final Set<String> FALSE_TOKENS = Set.of("false", "0", "n", "no");

    @Override
    public SpecFormat format() {
        return SpecFormat.CSV;
    }

    @Override
    public List<CanonicalEndpoint> parse(byte[] content) {
        byte[] body = stripBom(content);

        CsvParserSettings settings = new CsvParserSettings();
        settings.setHeaderExtractionEnabled(true);
        settings.setMaxCharsPerColumn(1_000_000); // 긴 description 허용
        settings.setMaxColumns(64);
        CsvParser parser = new CsvParser(settings);

        List<CanonicalEndpoint> out = new ArrayList<>();
        parser.beginParsing(new ByteArrayInputStream(body), StandardCharsets.UTF_8);
        try {
            Map<String, Integer> col = headerIndex(parser.getContext().headers()); // 필수 헤더 검증 포함

            int row = 0;
            String[] values;
            while ((values = parser.parseNext()) != null) {
                row++;
                String method = at(values, col.get("method"));
                String path = at(values, col.get("path"));
                if (isBlank(method) || isBlank(path)) {
                    log.warn("CSV row {} missing method/path, skipping", row);
                    continue;
                }
                String host = SpecNormalize.host(at(values, col.get("host")));
                boolean deprecated = parseDeprecated(at(values, col.get("deprecated")), row);
                String version = nullIfBlank(at(values, col.get("version")));
                String template = SpecNormalize.template(path);
                out.add(new CanonicalEndpoint(method.trim().toUpperCase(Locale.ROOT), template, host,
                        deprecated, version, "csv#row" + row));
            }
        } finally {
            parser.stopParsing();
        }
        return out;
    }

    /** 헤더 인덱스 맵(소문자·trim). 필수 method/path 누락 시 fatal. */
    private static Map<String, Integer> headerIndex(String[] headers) {
        if (headers == null) {
            throw new IllegalArgumentException("CSV missing header row");
        }
        Map<String, Integer> col = new HashMap<>();
        for (int i = 0; i < headers.length; i++) {
            if (headers[i] != null) {
                col.put(headers[i].trim().toLowerCase(Locale.ROOT), i);
            }
        }
        if (!col.containsKey("method") || !col.containsKey("path")) {
            throw new IllegalArgumentException("CSV requires 'method' and 'path' headers");
        }
        return col;
    }

    private static boolean parseDeprecated(String raw, int row) {
        if (isBlank(raw)) {
            return false;
        }
        String t = raw.trim().toLowerCase(Locale.ROOT);
        if (TRUE_TOKENS.contains(t)) {
            return true;
        }
        if (FALSE_TOKENS.contains(t)) {
            return false;
        }
        log.warn("CSV row {} unrecognized deprecated value '{}', treating as false", row, raw);
        return false;
    }

    /** col 인덱스(nullable)로 값 조회. 컬럼수 부족/미존재 → null. */
    private static String at(String[] values, Integer idx) {
        if (idx == null || idx >= values.length) {
            return null;
        }
        return values[idx];
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    private static String nullIfBlank(String s) {
        return isBlank(s) ? null : s.trim();
    }

    /** UTF-8 BOM(EF BB BF) 제거. */
    private static byte[] stripBom(byte[] content) {
        if (content.length >= 3
                && (content[0] & 0xFF) == 0xEF
                && (content[1] & 0xFF) == 0xBB
                && (content[2] & 0xFF) == 0xBF) {
            byte[] body = new byte[content.length - 3];
            System.arraycopy(content, 3, body, 0, body.length);
            return body;
        }
        return content;
    }
}
