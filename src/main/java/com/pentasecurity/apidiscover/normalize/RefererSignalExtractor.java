// referer corpus pre-pass — 정적 자원의 부모 페이지(PAGE_URLS) + 커버리지 게이트 산출 (doc/20 §1·§2)
package com.pentasecurity.apidiscover.normalize;

import com.pentasecurity.apidiscover.model.ParsedRequest;
import com.pentasecurity.apidiscover.model.RefererSignal;
import com.pentasecurity.apidiscover.model.SignalStatus;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * 요청 corpus 를 1회 횡단해 referer 기반 web_page 보조 신호를 만든다(doc/20 §1).
 * static 요청(확장자/$type=library)의 referer = 부모 페이지 URL → 정규화 template 별 자식 수(PAGE_URLS).
 * 커버리지(static_ratio·referer_present_ratio) 게이트 미달 시 DORMANT(신호 미사용, doc/20 §2).
 */
@Component
public class RefererSignalExtractor {

    /** 커버리지 게이트 임계 (1차값·캐비엇, doc/20 §2). seam=@ConfigurationProperties(후속). */
    static final double MIN_STATIC_RATIO = 0.05;
    static final double MIN_REFERER_PRESENT_RATIO = 0.20;

    private final PathNormalizer pathNormalizer;

    public RefererSignalExtractor(PathNormalizer pathNormalizer) {
        this.pathNormalizer = pathNormalizer;
    }

    public RefererSignal build(List<ParsedRequest> requests) {
        if (requests == null || requests.isEmpty()) {
            return RefererSignal.dormant();
        }
        long total = requests.size();
        long staticCount = 0;
        long refererPresent = 0;
        Map<String, Long> pageUrls = new HashMap<>();
        for (ParsedRequest r : requests) {
            boolean isStatic = isStatic(r);
            if (isStatic) {
                staticCount++;
            }
            if (r.referer() != null) {
                refererPresent++;
                // 정적 요청의 referer = 부모 페이지 → path 정규화해 PAGE_URLS 빈도 누적
                if (isStatic) {
                    String path = refererPath(r.referer());
                    if (path != null) {
                        pageUrls.merge(pathNormalizer.inferTemplate(path), 1L, Long::sum);
                    }
                }
            }
        }
        // 게이트는 원시 ratio 로 비교(반올림으로 임계 경계가 뒤집히지 않게), round3 은 노출용에만 적용
        double rawStaticRatio = (double) staticCount / total;
        double rawRefererPresentRatio = (double) refererPresent / total;
        boolean active = rawStaticRatio >= MIN_STATIC_RATIO && rawRefererPresentRatio >= MIN_REFERER_PRESENT_RATIO;
        // DORMANT 면 PAGE_URLS 비워 신호 미사용 명시(노출 ratios 는 round3 으로 보존)
        return new RefererSignal(active ? SignalStatus.ACTIVE : SignalStatus.DORMANT,
                active ? pageUrls : Map.of(), round3(rawStaticRatio), round3(rawRefererPresentRatio));
    }

    private static boolean isStatic(ParsedRequest r) {
        if (EndpointKindClassifier.isStaticPath(r.rawPath())) {
            return true;
        }
        return r.type() != null && "library".equalsIgnoreCase(r.type());
    }

    /** referer URL 에서 path 만 추출(scheme/host·query·fragment 제거) — endpoint template 과 동일 정규화 위해. */
    static String refererPath(String referer) {
        if (referer == null) {
            return null;
        }
        String s = referer.trim();
        if (s.isEmpty()) {
            return null;
        }
        int scheme = s.indexOf("://");
        if (scheme >= 0) {
            int pathStart = s.indexOf('/', scheme + 3);
            s = (pathStart >= 0) ? s.substring(pathStart) : "/";
        }
        int q = s.indexOf('?');
        if (q >= 0) {
            s = s.substring(0, q);
        }
        int frag = s.indexOf('#');
        if (frag >= 0) {
            s = s.substring(0, frag);
        }
        return s.isEmpty() ? "/" : s;
    }

    private static double round3(double v) {
        return Math.round(v * 1000.0) / 1000.0;
    }
}
