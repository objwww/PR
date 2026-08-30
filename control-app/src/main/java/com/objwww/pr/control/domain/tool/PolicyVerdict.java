package com.objwww.pr.control.domain.tool;

/** Policy 裁决结果（沿用 domain 既有 verdict 风格：不抛异常，交调用方决定如何落账） */
public record PolicyVerdict(boolean allowed, String reason) {

    public static PolicyVerdict allow() {
        return new PolicyVerdict(true, null);
    }

    public static PolicyVerdict reject(String reason) {
        return new PolicyVerdict(false, reason);
    }
}
