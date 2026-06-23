// query param 값 길이 버킷 — 값 자체는 폐기, 길이 구간만 보존(privacy-preserving) (doc/13 §2.1)
package com.pentasecurity.apidiscover.model;

public enum ValueLenBucket {
    NONE, S, M, L, XL;

    /**
     * 값 길이 → 버킷. bounds=[b0,b1,b2] 는 S/M/L 상한(포함).
     * len≤0→NONE, ≤b0→S, ≤b1→M, ≤b2→L, 그 외 XL.
     */
    public static ValueLenBucket of(int len, int[] bounds) {
        if (len <= 0) {
            return NONE;
        }
        if (len <= bounds[0]) {
            return S;
        }
        if (len <= bounds[1]) {
            return M;
        }
        if (len <= bounds[2]) {
            return L;
        }
        return XL;
    }
}
