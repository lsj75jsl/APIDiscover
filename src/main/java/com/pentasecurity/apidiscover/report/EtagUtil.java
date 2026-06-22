// 결과 버전(ETag) 산정 — 동일 입력은 동일 버전 (doc/07 §3.3, §8)
package com.pentasecurity.apidiscover.report;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

public final class EtagUtil {

    private EtagUtil() {
    }

    /** 입력 문자열의 SHA-256 앞 16자리(hex)를 버전으로 사용. */
    public static String of(String content) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(content.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest).substring(0, 16);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
