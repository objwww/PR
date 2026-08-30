package com.objwww.pr.control.application;

import com.objwww.pr.control.domain.model.PrSubjectState;
import com.objwww.pr.shared.Digest;

import java.util.Objects;

/**
 * T1 建 Run 的输入（application 层值对象）。
 * diffDigest / sourceSnapshotDigest 由 T0（SnapshotService，事务外）算好后传入——
 * PRRevision 构造时 digest 必须已就绪（评审修正 #3，I12）。
 * triggerKey = X-GitHub-Delivery：重投同 delivery → 同 run_key → 唯一约束幂等兜底（B-3）。
 */
public record IntakeCommand(
        long installationId,
        long repositoryId,
        String repositoryFullName,
        int prNumber,
        PrSubjectState prState,
        boolean draft,
        boolean merged,
        String headSha,
        String baseRef,
        String baseSha,
        String mergeBaseSha,
        Digest diffDigest,
        Digest sourceSnapshotDigest,
        String policyVersion,
        String promptVersion,
        String toolsetVersion,
        String triggerKey) {

    public IntakeCommand {
        Objects.requireNonNull(repositoryFullName, "repositoryFullName");
        Objects.requireNonNull(prState, "prState");
        Objects.requireNonNull(headSha, "headSha");
        Objects.requireNonNull(baseRef, "baseRef");
        Objects.requireNonNull(baseSha, "baseSha");
        Objects.requireNonNull(diffDigest, "diffDigest（T0 必须先算好）");
        Objects.requireNonNull(sourceSnapshotDigest, "sourceSnapshotDigest（T0 必须先算好）");
        Objects.requireNonNull(policyVersion, "policyVersion");
        Objects.requireNonNull(promptVersion, "promptVersion");
        Objects.requireNonNull(toolsetVersion, "toolsetVersion");
        Objects.requireNonNull(triggerKey, "triggerKey");
    }

    /** PR 的 outbox 聚合键（每 PR 独立 sequence 空间，ST-07） */
    public String aggregateKey() {
        return "pr:" + repositoryId + "#" + prNumber;
    }
}
