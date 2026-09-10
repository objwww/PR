package com.objwww.pr.control.alert.domain.identity;

import java.util.regex.Pattern;

/**
 * EX-A0 三 digest 身份族的共享校验（F04）：64 位小写 hex，与 char(64) digest 列对齐。
 * 三 digest 无公共接口、无隐式互转——混用由类型系统在编译期拒绝（EX-A0 验收面）。
 */
final class Hex64 {

    private static final Pattern HEX64 = Pattern.compile("[0-9a-f]{64}");

    private Hex64() {
    }

    static String require(String value, String label) {
        if (value == null || !HEX64.matcher(value).matches()) {
            throw new IllegalArgumentException(label + " 必须为 64 位小写 hex: " + value);
        }
        return value;
    }
}
