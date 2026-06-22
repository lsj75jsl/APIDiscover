// Postman Collection v2.1 → Canonical (doc/03 §3). Jackson + 자체 매핑
package com.pentasecurity.apidiscover.spec;

import com.pentasecurity.apidiscover.model.CanonicalEndpoint;
import java.util.List;
import org.springframework.stereotype.Component;

@Component
public class PostmanSpecParser implements SpecParser {

    @Override
    public SpecFormat format() {
        return SpecFormat.POSTMAN;
    }

    @Override
    public List<CanonicalEndpoint> parse(byte[] content) {
        // TODO(doc/03 §3): item 트리 DFS, url.path join, :var/{{var}}→{var},
        //   deprecated 규약([DEPRECATED] 등) 적용
        throw new UnsupportedOperationException("TODO: Postman parsing");
    }
}
