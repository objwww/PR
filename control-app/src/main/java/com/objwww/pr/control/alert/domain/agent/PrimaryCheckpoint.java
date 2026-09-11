package com.objwww.pr.control.alert.domain.agent;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * 主任务执行检查点（R7-X4，v2.1 §四 必持久清单）：主 Runner 无状态单步推进，
 * 全部推进状态落本记录（DB 面 V47 rca_primary_checkpoint）——任意一步崩溃后
 * 重驱动从此续走（RD09 恢复语义的数据基础）。
 *
 * <p>phase 两态：PRIMARY_READY（可取下一步决策）/ WAITING_CHILDREN（等本轮子任务
 * 收官，不持锁等待，由确定性 Supervisor 复判唤醒）。decision_seq = 已出决策序
 * （模型决策与裁决请求累计；R7-X6 起每步模型动作占一序——动作身份单调面）；
 * steps_used = 已耗主步数（对照 Profile maxSteps）；batches_used = 已耗委派批数
 * （对照 max_delegation_batches=2）。round_id 随每个获批委派批 +1（子任务落新轮）。
 *
 * <p>finalClaims/finalMissingInformation 是 FINAL 分支的持久化提案（原始 JSON 形状，
 * 键为 snake_case 协议字段）——先落检查点再进报告相位，报告准入面按协议重解析。
 *
 * <p>lastError（V88，反馈环）：上一步可重试失败的原因与修正指引，随信封
 * last_error 面回喂模型（A0 八跑实证：盲重驱无反馈=模型连猜同错 4 次）；成功步
 * 清空。崩溃恢复后仍在检查点上，重驱动信封同样携带——反馈不丢。
 */
public record PrimaryCheckpoint(
        UUID taskId,
        UUID runId,
        int roundId,
        Phase phase,
        int decisionSeq,
        int stepsUsed,
        int batchesUsed,
        String inputSnapshotDigest,
        List<Map<String, Object>> finalClaims,
        List<String> finalMissingInformation,
        String lastError,
        Instant updatedAt) {

    public enum Phase {PRIMARY_READY, WAITING_CHILDREN}

    public PrimaryCheckpoint {
        Objects.requireNonNull(taskId, "taskId");
        Objects.requireNonNull(runId, "runId");
        Objects.requireNonNull(phase, "phase");
        if (roundId < 0 || decisionSeq < 0 || stepsUsed < 0 || batchesUsed < 0) {
            throw new IllegalArgumentException("round/计数不得为负: round=" + roundId
                    + " decisionSeq=" + decisionSeq + " stepsUsed=" + stepsUsed
                    + " batchesUsed=" + batchesUsed);
        }
        finalClaims = finalClaims == null ? List.of() : List.copyOf(finalClaims);
        finalMissingInformation = finalMissingInformation == null
                ? List.of() : List.copyOf(finalMissingInformation);
    }

    /** 初始检查点：PRIMARY_READY、零计数（主任务起跑时一次性 upsert） */
    public static PrimaryCheckpoint initial(UUID taskId, UUID runId, int roundId, Instant now) {
        return new PrimaryCheckpoint(taskId, runId, roundId, Phase.PRIMARY_READY,
                0, 0, 0, null, List.of(), List.of(), null, now);
    }

    public PrimaryCheckpoint withPhase(Phase newPhase, Instant now) {
        return new PrimaryCheckpoint(taskId, runId, roundId, newPhase, decisionSeq,
                stepsUsed, batchesUsed, inputSnapshotDigest, finalClaims,
                finalMissingInformation, lastError, now);
    }

    /**
     * 推进累计：decision_seq +1、steps +1（R7-X6 语义收紧：每步模型动作占一个决策序
     * ——guard 预算预留键与 rca_model_call.action_seq 的单调身份面，重复键 = 预算
     * 结算踩已结算条目）；可选搭带快照摘要回填（快照冻结恰在首步前发生一次）。
     * lastError 为本步结束后留给下一步的反馈（null=成功/无反馈，清空旧值）。
     */
    public PrimaryCheckpoint withStepAdvanced(String snapshotDigest, String lastError,
            Instant now) {
        return new PrimaryCheckpoint(taskId, runId, roundId, phase, decisionSeq + 1,
                stepsUsed + 1, batchesUsed,
                snapshotDigest != null ? snapshotDigest : inputSnapshotDigest,
                finalClaims, finalMissingInformation, lastError, now);
    }

    /** 决策序推进（DELEGATE 批全拒：决策已出但零请求获批——动作序仍须单调） */
    public PrimaryCheckpoint withDecisionAdvanced(Instant now) {
        return new PrimaryCheckpoint(taskId, runId, roundId, phase, decisionSeq + 1,
                stepsUsed, batchesUsed, inputSnapshotDigest, finalClaims,
                finalMissingInformation, lastError, now);
    }

    /** FINAL 落账：提案进检查点，phase 就地固化（后续只被报告相位消费） */
    public PrimaryCheckpoint withFinal(List<Map<String, Object>> claims,
            List<String> missingInformation, Instant now) {
        return new PrimaryCheckpoint(taskId, runId, roundId, phase, decisionSeq,
                stepsUsed, batchesUsed, inputSnapshotDigest, claims, missingInformation,
                lastError, now);
    }
}
