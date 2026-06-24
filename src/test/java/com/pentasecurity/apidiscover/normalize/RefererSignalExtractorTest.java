// RefererSignalExtractor corpus pre-pass 단위 테스트 (doc/20 §1·§2)
package com.pentasecurity.apidiscover.normalize;

import static org.assertj.core.api.Assertions.assertThat;

import com.pentasecurity.apidiscover.model.ParsedRequest;
import com.pentasecurity.apidiscover.model.RefererSignal;
import com.pentasecurity.apidiscover.model.SignalStatus;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class RefererSignalExtractorTest {

    private final RefererSignalExtractor extractor = new RefererSignalExtractor(new PathNormalizer());

    private static ParsedRequest req(String path, String type, String referer) {
        return new ParsedRequest("GET", path, List.of(), 200, "h", "c",
                "ua", Instant.EPOCH, 10, 100, true, referer, type, null);
    }

    @Test
    void buildsPageUrlsFromStaticReferersAndGatesActive() {
        // 정적 자원(.js/.css/.png)의 referer = 부모 페이지 /shop/{id} → 정규화 후 PAGE_URLS 누적
        List<ParsedRequest> reqs = List.of(
                req("/a.js", null, "https://h/shop/12"),
                req("/b.css", null, "https://h/shop/34?v=2"),
                req("/c.png", null, "https://h/shop/56"),
                req("/shop/12", "document", "https://h/"), // 페이지 자체(비정적) — PAGE_URLS 기여 안 함
                req("/api/x", "json", null));
        RefererSignal s = extractor.build(reqs);

        assertThat(s.status()).isEqualTo(SignalStatus.ACTIVE); // static 3/5=0.6≥0.05, referer 4/5=0.8≥0.20
        assertThat(s.pageUrls()).containsExactlyInAnyOrderEntriesOf(java.util.Map.of("/shop/{id}", 3L));
    }

    @Test
    void dormantWhenStaticRatioTooLow() {
        // 정적 요청 없음(전부 api) → static_ratio 0 < 0.05 → DORMANT, PAGE_URLS 빈
        List<ParsedRequest> reqs = List.of(
                req("/api/a", "json", "https://h/page/1"),
                req("/api/b", "json", "https://h/page/1"));
        RefererSignal s = extractor.build(reqs);

        assertThat(s.status()).isEqualTo(SignalStatus.DORMANT);
        assertThat(s.pageUrls()).isEmpty();
    }

    @Test
    void dormantWhenRefererMostlyAbsent() {
        // 정적은 충분(2/10=0.2)하나 referer 전부 부재 → referer_present_ratio 0 < 0.20 → DORMANT
        List<ParsedRequest> list = new ArrayList<>();
        list.add(req("/a.js", null, null));
        list.add(req("/b.js", null, null));
        for (int i = 0; i < 8; i++) {
            list.add(req("/api/" + i, "json", null));
        }
        assertThat(extractor.build(list).status()).isEqualTo(SignalStatus.DORMANT);
    }

    @Test
    void emptyCorpusIsDormant() {
        assertThat(extractor.build(List.of()).status()).isEqualTo(SignalStatus.DORMANT);
    }

    @Test
    void gateBoundaryInclusiveAtExactThresholds() {
        // static_ratio 정확히 0.05(1/20)·referer_present_ratio 정확히 0.20(4/20) → >= 경계 포함 → ACTIVE
        List<ParsedRequest> list = new ArrayList<>();
        list.add(req("/a.js", null, "https://h/page/1")); // static 1 + referer 1
        list.add(req("/api/0", "json", "https://h/page/1")); // referer 2
        list.add(req("/api/1", "json", "https://h/page/1")); // referer 3
        list.add(req("/api/2", "json", "https://h/page/1")); // referer 4
        for (int i = 3; i < 19; i++) {
            list.add(req("/api/" + i, "json", null)); // 비정적·referer 부재 16건
        }
        // total=20, static=1(1/20=0.05), referer present=4(4/20=0.20)
        assertThat(extractor.build(list).status()).isEqualTo(SignalStatus.ACTIVE);
    }

    @Test
    void refererPathStripsSchemeHostQueryFragment() {
        assertThat(RefererSignalExtractor.refererPath("https://host/shop/1?x=2#frag")).isEqualTo("/shop/1");
        assertThat(RefererSignalExtractor.refererPath("/already/path")).isEqualTo("/already/path");
        assertThat(RefererSignalExtractor.refererPath("https://host")).isEqualTo("/");
        assertThat(RefererSignalExtractor.refererPath(null)).isNull();
    }
}
