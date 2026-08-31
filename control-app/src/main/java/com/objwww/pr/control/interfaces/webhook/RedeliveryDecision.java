package com.objwww.pr.control.interfaces.webhook;

import com.objwww.pr.control.domain.model.InboxState;

/**
 * 同 delivery 同 digest 重投的应答决策（M1 技术方案 v1.2 §4.2 主键冲突分支，纯函数可单测）。
 * 防什么：防重投产生第二次派发（I9）——按原行 state 把"当初的处理结果"如实回放给 GitHub，
 * 而不是重新走一遍处理；DEAD_LETTER 必须如实相告且绝不唤醒（I16）。
 */
public enum RedeliveryDecision {

    /** 已有终态结论（PROCESSED/IGNORED）→ 200 {"status":"duplicate"} */
    DUPLICATE,
    /** 在途（RECEIVED/PROCESSING/RETRY_WAIT）→ 202 {"status":"processing"}，等既有处理收尾 */
    PROCESSING,
    /** 死信 → 200 {"status":"dead_letter"}；不唤醒，复活只有显式管理操作一条路（I16/CT-16） */
    DEAD_LETTER;

    public static RedeliveryDecision of(InboxState state) {
        return switch (state) {
            case PROCESSED, IGNORED -> DUPLICATE;
            case RECEIVED, PROCESSING, RETRY_WAIT -> PROCESSING;
            case DEAD_LETTER -> DEAD_LETTER;
        };
    }
}
