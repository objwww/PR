package com.objwww.pr.control.alert.domain.agent;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * 委派裁决台账行（R7-X4/X11，V47 rca_delegation_decision）：主 Agent DELEGATE
 * 请求经确定性 Supervisor 校验（目录/去重/上限）后的既成事实——APPROVED 必回填
 * child_task_id，REJECTED 必带封闭原因码。同 (run, gap_id) 全 run 唯一 = 信息缺口
 * 只裁一次（去重面）；行只增不改（child 回填除外）。
 */
public record DelegationDecision(
        UUID id,
        UUID runId,
        UUID primaryTaskId,
        int roundId,
        int seq,
        String gapId,
        String roleId,
        String roleVersion,
        /** R3 路线B 语义钉面：仅台账审计（模型自述的信息缺口追问）；子任务执行面
         * 不消费——专家按绑定 profile 的固定查询+冻结窗+input_refs 执行。 */
        String question,
        Status status,
        String rejectReason,
        UUID childTaskId,
        Instant createdAt) {

    public enum Status {APPROVED, REJECTED}

    public DelegationDecision {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(runId, "runId");
        Objects.requireNonNull(primaryTaskId, "primaryTaskId");
        if (roundId < 0 || seq < 0) {
            throw new IllegalArgumentException("round/seq 不得为负");
        }
        Objects.requireNonNull(gapId, "gapId");
        Objects.requireNonNull(roleId, "roleId");
        Objects.requireNonNull(roleVersion, "roleVersion");
        Objects.requireNonNull(question, "question");
        Objects.requireNonNull(status, "status");
        switch (status) {
            case APPROVED -> {
                if (rejectReason != null) {
                    throw new IllegalArgumentException("APPROVED 不得带拒绝原因");
                }
            }
            case REJECTED -> {
                if (rejectReason == null || rejectReason.isBlank()) {
                    throw new IllegalArgumentException("REJECTED 必须带拒绝原因");
                }
                if (childTaskId != null) {
                    throw new IllegalArgumentException("REJECTED 不得回填子任务");
                }
            }
        }
    }

    /** 裁决通过后回填子任务（唯一允许的行变更） */
    public DelegationDecision withChild(UUID newChildTaskId) {
        if (status != Status.APPROVED) {
            throw new IllegalStateException("仅 APPROVED 行可回填子任务");
        }
        return new DelegationDecision(id, runId, primaryTaskId, roundId, seq, gapId,
                roleId, roleVersion, question, status, rejectReason,
                Objects.requireNonNull(newChildTaskId, "childTaskId"), createdAt);
    }
}
