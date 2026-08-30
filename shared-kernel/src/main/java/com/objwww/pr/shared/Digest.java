package com.objwww.pr.shared;

import java.util.regex.Pattern;

/**
 * 64 位小写 hex digest 值对象（与 V1 各 char(64) digest 列对齐）。
 */
public record Digest(String value) {

    private static final Pattern HEX64 = Pattern.compile("[0-9a-f]{64}");

    public Digest {
        if (value == null || !HEX64.matcher(value).matches()) {
            throw new IllegalArgumentException("digest 必须为 64 位小写 hex: " + value);
        }
    }

    /** 对规范化字符串做 SHA-256 */
    public static Digest sha256Of(String canonical) {
        return new Digest(Digests.sha256Hex(canonical));
    }

    @Override
    public String toString() {
        return value;
    }
}
