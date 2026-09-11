package com.objwww.pr.control.drill.domain.model;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * DR-02 演练作业（V86 drill_job 行的域形；§7.4 状态机载体）：
 * <ul>
 *   <li>稳定身份：id 受理时生成，worker 领取/崩溃重放不换 id；模板与参数在启动时
 *       冻结（scenarioName/templateDigest/paramsJson），运行中模板发布不改变本场；</li>
 *   <li>outcome 另存：PASS/FAIL/INCONCLUSIVE 仅在 CLOSED 落值（库 CHECK 同律），
 *       CLOSED 只表示恢复核验完成，不冒充演练目标达成；</li>
 *   <li>operator 唯一来源 = 认证主体，请求体不自报。</li>
 * </ul>
 */
public record DrillJob(
        UUID id,
        String scenarioId,
        String scenarioName,
        String templateDigest,
        String targetEnv,
        String operator,
        State state,
        String outcome,
        String terminalReason,
        String paramsJson,
        String payloadHash,
        String idempotencyKey,
        String stopIdempotencyKey,
        Instant stopRequestedAt,
        String workerId,
        Instant claimedAt,
        long revision,
        UUID relatedIncidentId,
        UUID relatedRunId,
        Instant createdAt,
        Instant updatedAt,
        Instant closedAt) {

    /** §7.4 作业相位 + 异常收口态 */
    public enum State {
        QUEUED, PRECHECK, INJECTING, OBSERVING, RECOVERING, VERIFYING,
        CLOSED, CANCELLED, FAILED, RECOVERY_FAILED;

        /** 终态（CLOSED/CANCELLED/FAILED）；RECOVERY_FAILED 非终态——保留占位待处理 */
        public boolean isTerminal() {
            return this == CLOSED || this == CANCELLED || this == FAILED;
        }

        /** 是否持有靶场活动占位（与 V86 部分唯一索引活动集一致，DU15） */
        public boolean holdsEnvPlaceholder() {
            return !isTerminal();
        }
    }

    public DrillJob {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(scenarioId, "scenarioId");
        Objects.requireNonNull(scenarioName, "scenarioName");
        Objects.requireNonNull(templateDigest, "templateDigest");
        Objects.requireNonNull(targetEnv, "targetEnv");
        Objects.requireNonNull(operator, "operator");
        Objects.requireNonNull(state, "state");
        Objects.requireNonNull(paramsJson, "paramsJson");
        Objects.requireNonNull(payloadHash, "payloadHash");
        Objects.requireNonNull(idempotencyKey, "idempotencyKey");
        Objects.requireNonNull(createdAt, "createdAt");
        Objects.requireNonNull(updatedAt, "updatedAt");
        if (state == State.CLOSED && (outcome == null || closedAt == null)) {
            throw new IllegalArgumentException("CLOSED 必带 outcome 与 closedAt（CLOSED≠成功，outcome 另存）");
        }
        if (state != State.CLOSED && (outcome != null || closedAt != null)) {
            throw new IllegalArgumentException("非 CLOSED 不得携带 outcome/closedAt");
        }
    }

    /** 受理态新作业（QUEUED，零 outcome/closedAt） */
    public static DrillJob queued(UUID id, String scenarioId, String scenarioName,
                                  String templateDigest, String targetEnv, String operator,
                                  String paramsJson, String payloadHash,
                                  String idempotencyKey, Instant now) {
        return new DrillJob(id, scenarioId, scenarioName, templateDigest, targetEnv,
                operator, State.QUEUED, null, null, paramsJson, payloadHash,
                idempotencyKey, null, null, null, null, 0, null, null, now, now, null);
    }

    /** 相位推进后的新形（revision+1；终态列按目标态钉死） */
    public DrillJob advanced(State to, String terminalReason, String outcome,
                             Instant closedAt, Instant now) {
        return new DrillJob(id, scenarioId, scenarioName, templateDigest, targetEnv,
                operator, to, outcome, terminalReason, paramsJson, payloadHash,
                idempotencyKey, stopIdempotencyKey, stopRequestedAt, workerId,
                claimedAt, revision + 1, relatedIncidentId, relatedRunId,
                createdAt, now, closedAt);
    }
}
