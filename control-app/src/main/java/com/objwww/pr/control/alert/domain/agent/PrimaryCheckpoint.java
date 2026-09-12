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
 *
 * <p>memoryId/memoryDigest（V91，R10/§19.2）：本步输入所用工作记忆快照的关联锚
 * （current_memory_id 面；context_snapshot 锚即 inputSnapshotDigest）。快照本体
 * 落 rca_working_memory（append-only 深冻结），检查点只存引用不复制历史——
 * 崩溃恢复重驱读同快照，不额外生成另一版（MC07）。
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
        UUID memoryId,
        String memoryDigest,
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
        if (memoryId == null && memoryDigest != null) {
            throw new IllegalArgumentException("memoryDigest 必须与 memoryId 成对出现");
        }
        if (memoryId != null && memoryDigest == null) {
            throw new IllegalArgumentException("memoryId 必须与 memoryDigest 成对出现");
        }
        finalClaims = finalClaims == null ? List.of() : List.copyOf(finalClaims);
        finalMissingInformation = finalMissingInformation == null
                ? List.of() : List.copyOf(finalMissingInformation);
    }

    /** 初始检查点：PRIMARY_READY、零计数（主任务起跑时一次性 upsert） */
    public static PrimaryCheckpoint initial(UUID taskId, UUID runId, int roundId, Instant now) {
        return new PrimaryCheckpoint(taskId, runId, roundId, Phase.PRIMARY_READY,
                0, 0, 0, null, null, null, List.of(), List.of(), null, now);
    }

    public PrimaryCheckpoint withPhase(Phase newPhase, Instant now) {
        return new PrimaryCheckpoint(taskId, runId, roundId, newPhase, decisionSeq,
                stepsUsed, batchesUsed, inputSnapshotDigest, memoryId, memoryDigest,
                finalClaims, finalMissingInformation, lastError, now);
    }

    /**
     * 推进累计：decision_seq +1、steps +1（R7-X6 语义收紧：每步模型动作占一个决策序
     * ——guard 预算预留键与 rca_model_call.action_seq 的单调身份面，重复键 = 预算
     * 结算踩已结算条目）；可选搭带快照摘要回填（快照冻结恰在首步前发生一次）与本步
     * 所用工作记忆快照关联（R10：装配时点已 append 深冻结的快照）。lastError 为本步
     * 结束后留给下一步的反馈（null=成功/无反馈，清空旧值）。
     */
    public PrimaryCheckpoint withStepAdvanced(String snapshotDigest, UUID memoryId,
            String memoryDigest, String lastError, Instant now) {
        return new PrimaryCheckpoint(taskId, runId, roundId, phase, decisionSeq + 1,
                stepsUsed + 1, batchesUsed,
                snapshotDigest != null ? snapshotDigest : inputSnapshotDigest,
                memoryId != null ? memoryId : this.memoryId,
                memoryDigest != null ? memoryDigest : this.memoryDigest,
                finalClaims, finalMissingInformation, lastError, now);
    }

    /**
     * 决策序推进（DELEGATE 批全拒：决策已出但零请求获批——动作序仍须单调）。
     * lastError 为留给下一步的裁决反馈（BA-119：全拒不回喂 = 模型盲重提同一 gap
     * 烧步数——qwen 真窗 15 连撞实证）；null = 无反馈（保留旧值语义无此入口，
     * 全拒必有拒绝码，故本入口强制携带）。
     */
    public PrimaryCheckpoint withDecisionAdvanced(String lastError, Instant now) {
        return new PrimaryCheckpoint(taskId, runId, roundId, phase, decisionSeq + 1,
                stepsUsed, batchesUsed, inputSnapshotDigest, memoryId, memoryDigest,
                finalClaims, finalMissingInformation, lastError, now);
    }

    /**
     * 零推进留痕（R5 同签名熔断锚）：模型调用失败（无决策产出）时持久失败签名——
     * 计数/相位/快照全部不动（失败步不耗步数），重驱读到同签名即判"同失败重发"。
     * lastError 字段在本入口作机器签名载体（重驱成功后由 withStepAdvanced 正常覆写）。
     */
    public PrimaryCheckpoint withLastError(String failureSignature, Instant now) {
        return new PrimaryCheckpoint(taskId, runId, roundId, phase, decisionSeq,
                stepsUsed, batchesUsed, inputSnapshotDigest, memoryId, memoryDigest,
                finalClaims, finalMissingInformation,
                Objects.requireNonNull(failureSignature, "failureSignature"), now);
    }

    /** FINAL 落账：提案进检查点，phase 就地固化（后续只被报告相位消费） */
    public PrimaryCheckpoint withFinal(List<Map<String, Object>> claims,
            List<String> missingInformation, Instant now) {
        return new PrimaryCheckpoint(taskId, runId, roundId, phase, decisionSeq,
                stepsUsed, batchesUsed, inputSnapshotDigest, memoryId, memoryDigest,
                claims, missingInformation, lastError, now);
    }
}
