package com.objwww.pr.control.ops.dutybot.domain;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * UX-02 通知 outbox 只读状态面（V9 notify_outbox；control_app 本持 SELECT，
 * 零新授权）。机器人"通知查询"意图的唯一数据源——只读投影，不写不猜。
 */
public interface NotifyStatusReader {

    /** 失败/重试中行（state ∈ DEAD/RETRY_WAIT，按 created_at desc 截顶） */
    record ProblemRow(UUID id, UUID reportId, String channel, String state,
                      int attemptCount, String lastError, Instant createdAt) {
    }

    /**
     * outbox 状态快照：total=全行数；byState=按 state 分组计数（六态词表见 V9
     * ck_notify_outbox_state）；recentProblems=失败/重试中 Top N（无则空表）。
     * total=0 = 如实"查询无结果"，不补零编造。
     */
    record OutboxStatus(long total, Map<String, Long> byState, List<ProblemRow> recentProblems) {
    }

    OutboxStatus summarize(int problemLimit);
}
