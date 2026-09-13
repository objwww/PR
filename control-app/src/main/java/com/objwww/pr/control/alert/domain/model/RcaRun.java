package com.objwww.pr.control.alert.domain.model;

import com.objwww.pr.shared.Digest;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * 一轮 RCA 调查（三级化第一层，评审 #1）。不存报告正文——正文在 RcaReport。
 *
 * <p>investigationHash = 铸造时材料快照（§6.7）：finishTask 用它与 incident.pending
 * 比较判"调查期间材料是否变化"→ 变化则铸下一轮 RERUN（ST-A05 只派生一个后续 run 的锚点）。
 * generation = 铸造时 incident.generation 快照（episode 代；RERUN 同代，仅 RESOLVED→FIRING 再现才 +1）。
 *
 * <p>SR §3.1 身份三列：purpose（准入身份，null 读侧归一 LEGACY_UNKNOWN——V108 前存量行）、
 * purposeSource（来源任务/触发器标识）、completionKind（终态补充语义，如影子
 * SHADOW_EVIDENCE_ONLY=完成取证未发布）。原始状态、报告质量和发布策略三者分开。
 */
public record RcaRun(
        UUID id,
        UUID incidentId,
        int generation,
        RunTrigger trigger,
        RcaRunState state,
        Digest investigationHash,
        Instant createdAt,
        Instant updatedAt,
        Instant startedAt,
        Instant finishedAt,
        String lastError,
        RunPurpose purpose,
        String purposeSource,
        String completionKind
) {
    /** SR §3.1：影子取证完成、未生成正式报告（发布准入层禁止影子产报告/通知） */
    public static final String COMPLETION_SHADOW_EVIDENCE_ONLY = "SHADOW_EVIDENCE_ONLY";

    /** SR §4.2：QUEUED 超期收口（QUEUED 无 EXPIRED 出边，按现有合法 FAILED 语义收尾） */
    public static final String COMPLETION_QUEUE_DEADLINE = "QUEUE_DEADLINE";

    /** SR §4.2：RUNNING/REPORTING 到达可信硬期限后按状态机 EXPIRED 收尾 */
    public static final String COMPLETION_DEADLINE_EXPIRED = "DEADLINE_EXPIRED";

    public RcaRun {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(incidentId, "incidentId");
        Objects.requireNonNull(trigger, "trigger");
        Objects.requireNonNull(state, "state");
        Objects.requireNonNull(investigationHash, "investigationHash");
        Objects.requireNonNull(createdAt, "createdAt");
        Objects.requireNonNull(updatedAt, "updatedAt");
        if (generation < 0) {
            throw new IllegalArgumentException("generation 不能为负");
        }
        // 身份归一：null purpose = V108 前存量（DB 列 NULL），域面统一 LEGACY_UNKNOWN
        purpose = purpose == null ? RunPurpose.LEGACY_UNKNOWN : purpose;
    }

    /** V108 前形态兼容构造（既有测试/铸造点逐步补 purpose；缺省 = LEGACY_UNKNOWN 如实） */
    public RcaRun(UUID id, UUID incidentId, int generation, RunTrigger trigger,
                  RcaRunState state, Digest investigationHash, Instant createdAt,
                  Instant updatedAt, Instant startedAt, Instant finishedAt,
                  String lastError) {
        this(id, incidentId, generation, trigger, state, investigationHash, createdAt,
                updatedAt, startedAt, finishedAt, lastError, null, null, null);
    }
}
