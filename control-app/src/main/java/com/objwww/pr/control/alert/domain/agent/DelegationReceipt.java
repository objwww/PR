package com.objwww.pr.control.alert.domain.agent;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * 委派子任务结构化回执（MC21~23，V96 rca_delegation_receipt；R7 方案 §20.1
 * 结果合并契约 / §19.5 子任务结果契约）——子任务结论以<b>有界载荷 + 幂等键</b>
 * 准入，不是"任务终态+隐式证据行"的无契约回流：
 * <ul>
 *   <li>{@code messageId} 唯一 = 幂等准入键：同键重复投递恰一行，有效结果只合入
 *       一次（合并面只读本表 ACCEPTED 行，重复投递结构上不产生第二次合并）；</li>
 *   <li>{@code admission} 准入裁决封闭集：ACCEPTED（合入）/OVERSIZED（超限显式
 *       拒绝，载荷不落库）/LATE（run 终态后迟到，仅审计不合入——MC23 不冒充新
 *       现场）/REJECTED_SHAPE（结构契约违约审计）；</li>
 *   <li>失败必须返回结构化缺口：FAILED 的 ACCEPTED 回执 missingInformation 非空
 *       （V96 ck 强制），不存在"空字符串失败"。</li>
 * </ul>
 * 行只增不改（准入在写入时点一次定案）。
 */
public record DelegationReceipt(
        UUID id,
        UUID messageId,
        UUID runId,
        UUID primaryTaskId,
        UUID childTaskId,
        int roundId,
        String gapId,
        String roleId,
        ChildStatus childStatus,
        Admission admission,
        List<String> findings,
        List<String> supportRefs,
        List<String> counterRefs,
        List<String> missingInformation,
        String payloadDigest,
        int payloadBytes,
        Instant receivedAt) {

    /** 子任务结论面（§19.5 status）：失败也必须带结构化缺口 */
    public enum ChildStatus {SUCCEEDED, FAILED}

    /** 准入裁决（MC21/23 封闭集，与 V96 ck 同词表） */
    public enum Admission {ACCEPTED, OVERSIZED, LATE, REJECTED_SHAPE}

    public DelegationReceipt {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(messageId, "messageId");
        Objects.requireNonNull(runId, "runId");
        Objects.requireNonNull(admission, "admission");
        Objects.requireNonNull(childTaskId, "childTaskId");
        if (admission == Admission.ACCEPTED) {
            // 审计行（身份面拒绝）解析不出归属主任务——可空是诚实态；
            // ACCEPTED 行必须可归属（合并面按 (run, primary_task, round) 取面）
            Objects.requireNonNull(primaryTaskId, "primaryTaskId");
        }
        if (roundId < 0) {
            throw new IllegalArgumentException("roundId 不得为负");
        }
        Objects.requireNonNull(gapId, "gapId");
        Objects.requireNonNull(roleId, "roleId");
        Objects.requireNonNull(childStatus, "childStatus");
        Objects.requireNonNull(admission, "admission");
        findings = List.copyOf(Objects.requireNonNull(findings, "findings"));
        supportRefs = List.copyOf(Objects.requireNonNull(supportRefs, "supportRefs"));
        counterRefs = List.copyOf(Objects.requireNonNull(counterRefs, "counterRefs"));
        missingInformation = List.copyOf(
                Objects.requireNonNull(missingInformation, "missingInformation"));
        Objects.requireNonNull(payloadDigest, "payloadDigest");
        if (payloadDigest.length() != 64) {
            throw new IllegalArgumentException("payloadDigest 必须为 sha256 hex（64 字符）");
        }
        if (payloadBytes < 0) {
            throw new IllegalArgumentException("payloadBytes 不得为负");
        }
        Objects.requireNonNull(receivedAt, "receivedAt");
        if (admission == Admission.OVERSIZED && !findings.isEmpty()) {
            throw new IllegalArgumentException("OVERSIZED 审计行不得携带载荷（无内存失控面）");
        }
        if (admission == Admission.ACCEPTED && childStatus == ChildStatus.FAILED
                && missingInformation.isEmpty()) {
            throw new IllegalArgumentException(
                    "失败回执必须带结构化缺口（missingInformation 非空，§20.1）");
        }
    }

    /** 准入是否进入合并面（主任务信封 child_receipts 槽只消费 ACCEPTED 行） */
    public boolean merged() {
        return admission == Admission.ACCEPTED;
    }
}
