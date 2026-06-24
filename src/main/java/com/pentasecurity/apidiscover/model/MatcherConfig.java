// API 힌트/제외 매처 설정 — 전역∪도메인 병합 + includeWebForms 상속 (doc/09 §1, §4)
package com.pentasecurity.apidiscover.model;

import java.util.LinkedHashSet;
import java.util.List;

/**
 * explicit-hint 매처의 effective 설정.
 *
 * <ul>
 *   <li>prefix 는 세그먼트 경계 매칭, regex 는 full-match, 둘 다 pathTemplate 기준 (doc/09 §1).</li>
 *   <li>{@code includeWebForms} 는 nullable — 도메인 override 의 "상속" 시맨틱(null=전역값 사용). (§4)</li>
 *   <li>{@link #NONE} = 빈값 + includeWebForms=true: 레거시/미배선 파이프라인용 비활성 센티넬(현행 동작 100% 동일).</li>
 * </ul>
 */
public record MatcherConfig(
        List<String> apiPathPrefixes,
        List<String> apiPathRegexes,
        List<String> excludePathPrefixes,
        List<String> excludePathRegexes,
        List<String> optionsOperationPrefixes, // "OPTIONS 가 진짜 operation 인 경로" operator 선언 (doc/23 §8, M2)
        Boolean includeWebForms // null = 상속(merge 에서 전역값/기본 false 로 해소), §4
) {

    /** list 는 null/요소를 허용하지 않는 불변 복사본으로 정규화. includeWebForms 는 nullable 유지(상속). */
    public MatcherConfig {
        apiPathPrefixes = copyOrEmpty(apiPathPrefixes);
        apiPathRegexes = copyOrEmpty(apiPathRegexes);
        excludePathPrefixes = copyOrEmpty(excludePathPrefixes);
        excludePathRegexes = copyOrEmpty(excludePathRegexes);
        optionsOperationPrefixes = copyOrEmpty(optionsOperationPrefixes);
    }

    /**
     * 하위호환 5-arg — optionsOperationPrefixes 기본 빈(doc/23 §8). 기존 호출부/stored matcherJson 무변경
     * (M1 의 Finding.Unused 4-arg 편의 ctor 패턴 동형).
     */
    public MatcherConfig(List<String> apiPathPrefixes, List<String> apiPathRegexes,
                         List<String> excludePathPrefixes, List<String> excludePathRegexes,
                         Boolean includeWebForms) {
        this(apiPathPrefixes, apiPathRegexes, excludePathPrefixes, excludePathRegexes,
                List.of(), includeWebForms);
    }

    /** 빈값 + includeWebForms=true. 매처 비활성 센티넬 — 레거시 오버로드·미배선 파이프라인이 쓰면 현행 동작 불변. */
    public static final MatcherConfig NONE =
            new MatcherConfig(List.of(), List.of(), List.of(), List.of(), List.of(), Boolean.TRUE);

    /**
     * 전역+도메인 effective 설정 병합 (doc/09 §4).
     * <ul>
     *   <li>4개 list = 전역 ∪ 도메인 (순서 보존 dedup).</li>
     *   <li>includeWebForms = 도메인 non-null → 도메인, 아니면 전역 non-null → 전역, 그래도 null → false.</li>
     * </ul>
     * 충돌(api 힌트 ∩ exclude)은 병합이 아니라 게이트에서 exclude 승리로 해소(§2.2 order 1).
     */
    public static MatcherConfig merge(MatcherConfig global, MatcherConfig domain) {
        MatcherConfig g = (global == null) ? NONE_EMPTY : global;
        MatcherConfig d = (domain == null) ? NONE_EMPTY : domain;
        Boolean webForms;
        if (d.includeWebForms != null) {
            webForms = d.includeWebForms;
        } else if (g.includeWebForms != null) {
            webForms = g.includeWebForms;
        } else {
            webForms = Boolean.FALSE;
        }
        return new MatcherConfig(
                union(g.apiPathPrefixes, d.apiPathPrefixes),
                union(g.apiPathRegexes, d.apiPathRegexes),
                union(g.excludePathPrefixes, d.excludePathPrefixes),
                union(g.excludePathRegexes, d.excludePathRegexes),
                union(g.optionsOperationPrefixes, d.optionsOperationPrefixes),
                webForms);
    }

    /** merge 내부용: 빈 list + includeWebForms=null(상속 미지정). NONE(=true) 과 달리 상속 시맨틱 보존. */
    private static final MatcherConfig NONE_EMPTY =
            new MatcherConfig(List.of(), List.of(), List.of(), List.of(), List.of(), null);

    private static List<String> union(List<String> a, List<String> b) {
        LinkedHashSet<String> set = new LinkedHashSet<>(a);
        set.addAll(b);
        return List.copyOf(set);
    }

    private static List<String> copyOrEmpty(List<String> in) {
        return (in == null || in.isEmpty()) ? List.of() : List.copyOf(in);
    }
}
