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
        String lastError
) {
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
    }
}
