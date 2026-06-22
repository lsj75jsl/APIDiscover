// CSV(method,path,host,deprecated,version,description) → Canonical (doc/03 §4). univocity
package com.pentasecurity.apidiscover.spec;

import com.pentasecurity.apidiscover.model.CanonicalEndpoint;
import java.util.List;
import org.springframework.stereotype.Component;

@Component
public class CsvSpecParser implements SpecParser {

    @Override
    public SpecFormat format() {
        return SpecFormat.CSV;
    }

    @Override
    public List<CanonicalEndpoint> parse(byte[] content) {
        // TODO(doc/03 §4): 헤더 검증, deprecated 파싱(true/false/1/0/y/n), :var→{var}
        throw new UnsupportedOperationException("TODO: CSV parsing");
    }
}
