package com.objwww.pr.control.alert.domain.tool;

import java.util.Set;

/**
 * 账本原因码集（AM4 M4-18，评审裁定冻结十码，不照搬 CrewAI 六分类）。
 * 与控制面终止族（{@link ToolControlReason}）是两个面：本集面向调用落档归因。
 */
public enum ToolReasonCode {
    INVALID_INPUT,
    POLICY_DENIED,
    TIMEOUT,
    RATE_LIMITED,
    AUTH_FAILED,
    REMOTE_4XX,
    REMOTE_5XX,
    TRANSPORT_UNKNOWN,
    CANCELLED,
    REPLAY_MISS;

    /** 冻结面断言锚点（M4-18 原因码穷举 UT） */
    public static Set<String> frozenCodeSet() {
        return Set.of("INVALID_INPUT", "POLICY_DENIED", "TIMEOUT", "RATE_LIMITED",
                "AUTH_FAILED", "REMOTE_4XX", "REMOTE_5XX", "TRANSPORT_UNKNOWN",
                "CANCELLED", "REPLAY_MISS");
    }
}
