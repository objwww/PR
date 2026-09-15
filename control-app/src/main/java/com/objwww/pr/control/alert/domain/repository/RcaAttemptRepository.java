package com.objwww.pr.control.alert.domain.repository;

import com.objwww.pr.control.alert.domain.model.RcaAttempt;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * rca_attempt 端口（V1 step_attempt 同构：uq(task_id, attempt_no)）。
 *
 * <p>PA-A1（V111）进度双列：{@code last_activity_at}（系统在干活——worker 心跳回写）
 * 与 {@code last_meaningful_progress_at}（incident 真推进——检查点 APPLIED 同事务回写）。
 * 判定纪律：lease 续租两列都不回写；LLM token 流只算 activity（R5 评审裁定）。
 */
public interface RcaAttemptRepository {

    /** STARTED 行（领取后、触网前） */
    void insert(RcaAttempt attempt);

    /** 终态回写（调用方已过 epoch 栅栏） */
    boolean update(RcaAttempt attempt);

    List<RcaAttempt> findByTaskId(UUID taskId);

    /** LIVE_BUT_STUCK 判定投影：task 当前 STARTED attempt 的进度视图（无 STARTED 行 = empty） */
    record AttemptProgress(UUID attemptId, UUID taskId, int attemptNo, long leaseEpoch,
            Instant startedAt, Instant lastActivityAt, Instant lastMeaningfulProgressAt) {

        /** 判定基线：无进展写点的 attempt 回退 startedAt（V111 存量行两列为 NULL） */
        public Instant effectiveProgressAt() {
            return lastMeaningfulProgressAt != null ? lastMeaningfulProgressAt : startedAt;
        }
    }

    /** 活跃度回写（心跳/工具等待——不构成进展；仅 STARTED 行，返回 false = attempt 已终态） */
    boolean markActivityByTask(UUID taskId, Instant at);

    /** 有效进展回写（检查点推进/新证据——隐含活跃；仅 STARTED 行） */
    boolean markMeaningfulProgressByTask(UUID taskId, Instant at);

    /** 进度读面：该 task 最新 STARTED attempt 的进度视图 */
    Optional<AttemptProgress> findStartedProgressByTaskId(UUID taskId);
}
