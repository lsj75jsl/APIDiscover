// endpoint_kind 판정 — nginx $type 필드 기반 (doc/02 §5, 실로그 검증)
package com.pentasecurity.apidiscover.normalize;

import com.pentasecurity.apidiscover.model.EndpointKind;
import com.pentasecurity.apidiscover.model.RefererSignal;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * 실로그의 {@code $type} 필드(document/library 등)로 web_page vs static vs API 를 판정한다.
 * referer 재구성보다 직접적이고 신뢰도 높다(실데이터 검증). type 이 없으면 확장자로 보강.
 */
@Component
public class EndpointKindClassifier {

    // API성 $type → API_CANDIDATE. 실 Loki 샘플링(doc/21 §A-결과: 3윈도우·2호스트·peak/off-peak)에서 이 5값
    // 실관측 0, 관측 vocab={document,library} 뿐 → 데이터가 추가·제거 근거를 안 줘 관례 집합 그대로 유지(무변경 확정, D30).
    // 부재 시 dormant(무감점)이고 ApiScorer.responseTypeApi 는 API_CANDIDATE 만 소비하므로 집합 정제가 자동 전파(doc/17 §1).
    private static final Set<String> API_TYPES = Set.of("xhr", "fetch", "json", "api", "ajax");

    /** 기본 정적 확장자(D55/D56) — 외부 DB 설정(StaticClassifyRule) 미시드 시 seed 값·부팅 초기값. */
    public static final List<String> DEFAULT_STATIC_EXT = List.of(
            ".js", ".css", ".png", ".jpg", ".jpeg", ".gif", ".svg", ".ico",
            ".webp", ".avif", ".bmp", ".tiff", ".woff", ".woff2", ".ttf", ".eot", ".otf", ".map",
            // 설정·시크릿 파일 — 실 API 일 수 없음(스캐너의 .env/키/설정 하베스팅 오탐 차단, 사용자 요구).
            // ★.json/.yaml 은 제외: 진짜 데이터 API 다수(chart_data/{id}/dat.json·Jira REST 등), endsWith veto 시 대량 오탐.
            ".env", ".ini", ".pem", ".key", ".tf", ".save");
    // 기본 정적 리소스 파일명 토큰(D56) — 동적 확장자(.php 등)로 이미지/CSS/링크위젯 등을 서빙하는 경우
    // (img.php·resize_image.php·view_css.php·link.php) API 오탐 감점용. ★모호 토큰(photo·view·file·get)은 제외(실 API 가능).
    // ★"link"(사용자 요청): substring 매치라 unlink/linkedin 도 매치될 수 있음(과탐 주의). 감점(-0.6)이라 강신호 API 는 생존.
    /** 기본 정적 리소스 파일명 토큰(D56). 외부 DB 설정 미시드 시 seed 값·부팅 초기값. */
    public static final List<String> DEFAULT_NAME_TOKENS = List.of(
            "img", "image", "thumb", "thumbnail", "resize", "icon", "logo",
            "banner", "sprite", "avatar", "favicon", "css", "link", "download", "attachment");

    // ★런타임 교체 가능(외부 DB 설정 + reload, D56). 초기값=기본. StaticClassifyRules.reload 가 applyRules 로 교체.
    // ApiScorer 가 정적 메서드로 호출(ApiScorer 는 new 로 생성돼 빈 주입 불가) → 정적 volatile 보유.
    private static volatile Set<String> activeExt = new LinkedHashSet<>(DEFAULT_STATIC_EXT);
    private static volatile Set<String> activeNameTokens = new LinkedHashSet<>(DEFAULT_NAME_TOKENS);

    /** 외부 DB 설정으로 정적 확장자·토큰을 교체(StaticClassifyRules reload, D56). 소문자·trim 정규화. */
    public static void applyRules(Collection<String> extensions, Collection<String> nameTokens) {
        activeExt = normalizeSet(extensions);
        activeNameTokens = normalizeSet(nameTokens);
    }

    private static Set<String> normalizeSet(Collection<String> vals) {
        Set<String> s = new LinkedHashSet<>();
        for (String v : vals) {
            if (v != null && !v.isBlank()) {
                s.add(v.trim().toLowerCase());
            }
        }
        return s;
    }
    /** 정적 자식 ≥2 의 부모 = 페이지 확정 (referer 보조 임계, doc/20 §3). */
    private static final long MIN_CHILD_HITS = 2;
    private static final double REFERER_WEB_PAGE_CONFIDENCE = 0.6;

    public record KindResult(EndpointKind kind, double confidence) {}

    /** 하위호환 2-arg: referer·응답 CT 없음(dormant) 위임 → 기존 동작 그대로 (doc/20 §3). */
    public KindResult classify(String pathTemplate, Map<String, Long> typeDist) {
        return classify(pathTemplate, typeDist, RefererSignal.dormant(), Map.of());
    }

    /** 하위호환 3-arg: 응답 CT 없음(dormant) 위임 → 기존 $type/referer 동작 그대로. */
    public KindResult classify(String pathTemplate, Map<String, Long> typeDist, RefererSignal signal) {
        return classify(pathTemplate, typeDist, signal, Map.of());
    }

    /**
     * 확장자 veto → 응답 CT → $type 순으로 판정 후, UNKNOWN 이고 referer active·PAGE_URLS 자식
     * ≥ {@value #MIN_CHILD_HITS} 면 WEB_PAGE 보조 양성(비대칭 — 부재 시 UNKNOWN 유지·무감점, doc/20 §3).
     *
     * @param pathTemplate    정규화된 경로(확장자 보조 판정용)
     * @param typeDist        해당 시그니처에서 관찰된 $type 값 분포(비어있을 수 있음)
     * @param signal          corpus referer 신호(dormant 면 보조 분기 skip)
     * @param contentTypeDist 2xx 응답 Content-Type 분포(정규화 키, 비어있으면 CT 분기 skip; doc/40 §4.3)
     */
    public KindResult classify(String pathTemplate, Map<String, Long> typeDist, RefererSignal signal,
                               Map<String, Long> contentTypeDist) {
        KindResult k = classifyByType(pathTemplate, typeDist, contentTypeDist);
        if (k.kind() == EndpointKind.UNKNOWN && signal.active()
                && signal.pageUrls().getOrDefault(pathTemplate, 0L) >= MIN_CHILD_HITS) {
            return new KindResult(EndpointKind.WEB_PAGE, REFERER_WEB_PAGE_CONFIDENCE);
        }
        return k;
    }

    /** 확장자 veto → 응답 CT → $type 기반 핵심 판정(referer 무관). */
    private KindResult classifyByType(String pathTemplate, Map<String, Long> typeDist,
                                      Map<String, Long> contentTypeDist) {
        // 1) 확장자가 정적 판정의 권위 신호 — $type·CT 보다 우선(D55/D56 정적 veto 보존, doc/40 §4.3 ④).
        //    (실데이터 검증: .js/.css 가 $type=document 로 찍히는 경우가 있어 $type 단독은 부정확)
        if (isStaticPath(pathTemplate)) {
            return new KindResult(EndpointKind.STATIC, 1.0);
        }
        // 2) 응답 Content-Type 분기 — 확장자 veto 다음·$type 앞(doc/40 §4.3). 2xx dist 는 Acc 에서 이미 필터됨.
        //    빈 dist(미수집·401/403-only) 또는 dominant 과반 미달(<0.5) 또는 미매핑 CT → null → $type 폴백.
        KindResult ct = classifyByContentType(contentTypeDist);
        if (ct != null) {
            return ct;
        }
        // 3) $type 보조 신호
        Dominant d = dominantOf(typeDist);
        if ("library".equalsIgnoreCase(d.value())) {
            return new KindResult(EndpointKind.STATIC, d.fraction());
        }
        if ("document".equalsIgnoreCase(d.value())) {
            return new KindResult(EndpointKind.WEB_PAGE, d.fraction());
        }
        if (d.value() != null && API_TYPES.contains(d.value().toLowerCase())) {
            return new KindResult(EndpointKind.API_CANDIDATE, d.fraction());
        }
        return new KindResult(EndpointKind.UNKNOWN, 0.0);
    }

    /**
     * 응답 CT 분포 → kind (doc/40 §4.3). 확장자 정적 veto 다음에만 도달(정적 우선 보존).
     * json/xml/grpc→API, html→WEB_PAGE, image/css/js→STATIC. 빈 dist·dominant<0.5·미매핑 → null(폴백).
     */
    private static KindResult classifyByContentType(Map<String, Long> contentTypeDist) {
        if (contentTypeDist == null || contentTypeDist.isEmpty()) {
            return null; // 미수집(dormant)·401/403-only(2xx 없음) → $type/경로 폴백(§4.3 가드①③)
        }
        Dominant d = dominantOf(contentTypeDist);
        if (d.fraction() < 0.5) {
            return null; // 과반 미달 혼합 CT → 결정적 분기 회피(§4.3 가드②)
        }
        EndpointKind kind = mapContentTypeToKind(d.value());
        return (kind == null) ? null : new KindResult(kind, d.fraction());
    }

    /** 정규화된 dominant CT(소문자·charset 제거) → kind. 매핑 없으면 null(폴백). html 을 xml 보다 먼저 검사(xhtml+xml 회피). */
    private static EndpointKind mapContentTypeToKind(String ct) {
        if (ct == null) {
            return null;
        }
        if (ct.startsWith("image/") || ct.equals("text/css")
                || ct.equals("application/javascript") || ct.equals("text/javascript")
                || ct.equals("application/x-javascript")) {
            return EndpointKind.STATIC;
        }
        if (ct.contains("html")) { // text/html·application/xhtml+xml → 페이지
            return EndpointKind.WEB_PAGE;
        }
        if (ct.contains("json") || ct.endsWith("+json")) { // application/json·vnd.api+json·ld+json
            return EndpointKind.API_CANDIDATE;
        }
        if (ct.contains("xml") || ct.endsWith("+xml")) { // application/xml·text/xml (xhtml 은 위에서 제외)
            return EndpointKind.API_CANDIDATE;
        }
        if (ct.contains("grpc")) {
            return EndpointKind.API_CANDIDATE;
        }
        return null;
    }

    /** 분포에서 dominant 값 + 과반 비율(round3). 빈 분포 → (null, 0). */
    private static Dominant dominantOf(Map<String, Long> dist) {
        String dominant = null;
        long domCount = 0;
        long total = 0;
        for (Map.Entry<String, Long> e : dist.entrySet()) {
            total += e.getValue();
            if (e.getValue() > domCount) {
                domCount = e.getValue();
                dominant = e.getKey();
            }
        }
        return new Dominant(dominant, (total == 0) ? 0.0 : round3((double) domCount / total));
    }

    private record Dominant(String value, double fraction) {}

    /** 정적 자원 경로(확장자 기반) 여부 — RefererSignalExtractor 가 static 요청 판정에 재사용(DRY, doc/20 §1). */
    public static boolean isStaticPath(String path) {
        if (path == null) {
            return false;
        }
        String p = path.toLowerCase();
        for (String ext : activeExt) {
            if (p.endsWith(ext)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 마지막 세그먼트(파일명)가 정적 리소스 서빙으로 보이는지(D55 후속, 사용자 요구) — ApiScorer 가 큰 감점에 사용.
     * 조건: 확장자(.) 있는 파일 + 정적 리소스 토큰({@link #DEFAULT_NAME_TOKENS}, 런타임 DB 교체 가능) 포함. ★확장자 없는 경로(예: {@code /api/images} 컬렉션)는
     * 제외(REST 리소스일 수 있음). {@code .php} 등 동적 확장자라도 파일명이 정적 리소스면 감점(veto 아님 — 실 API 가능성 보존).
     */
    public static boolean hasStaticResourceName(String path) {
        if (path == null) {
            return false;
        }
        int slash = path.lastIndexOf('/');
        String seg = (slash >= 0 ? path.substring(slash + 1) : path).toLowerCase();
        if (seg.indexOf('.') < 0) {
            return false; // 확장자 없음 = 특정 파일 아님(컬렉션/리소스) → 제외
        }
        for (String token : activeNameTokens) {
            if (seg.contains(token)) {
                return true;
            }
        }
        return false;
    }

    private static double round3(double v) {
        return Math.round(v * 1000.0) / 1000.0;
    }
}
