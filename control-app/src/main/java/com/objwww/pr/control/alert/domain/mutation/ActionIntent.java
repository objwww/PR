package com.objwww.pr.control.alert.domain.mutation;

import com.objwww.pr.control.alert.domain.tool.ToolRisk;

import java.util.Objects;
import java.util.UUID;

/**
 * R2/R3 调用意图（PB-B1，设计基线 §1 执行链入口账本）：Agent 侧一次 mutation
 * 意图的持久身份——ActionIntent / ApprovalRequest / Grant / OperationAuthorization /
 * operations / 审计事件全链绑定同一 {@code (action_id, action_digest)}（§2.1）。
 *
 * <p>生命周期（本波只落 OPEN；PLANNED/VOIDED 随 B4 消费模板与 §2.7 作废矩阵启用）：
 * <ul>
 *   <li>{@code OPEN} —— 已过双闸与参数校验的 VALIDATE_ONLY 意图（ToolGateway 落账）；</li>
 *   <li>{@code PLANNED} —— 已铸 operation（回填 operation_id，消费模板 B4）；</li>
 *   <li>{@code VOIDED} —— 生命周期作废（run 终态/取消先于计划，§2.7 矩阵）。</li>
 * </ul>
 *
 * <p>授权效力边界：requested_resource_key 只是<b>请求面</b>输入——授权资源只信
 * Resource Resolver canonical identity（B2，B 组不变量），告警标签零授权效力。
 */
public record ActionIntent(
        UUID intentId,
        UUID runId,
        UUID taskId,
        UUID attemptId,
        long callSeq,
        String toolName,
        String toolVersion,
        String actionDigest,
        ToolRisk risk,
        String requestedResourceKey,
        IntentStatus status,
        UUID operationId,
        String argsJson) {

    public enum IntentStatus { OPEN, PLANNED, VOIDED }

    public ActionIntent {
        Objects.requireNonNull(intentId, "intentId");
        Objects.requireNonNull(runId, "runId");
        if (callSeq < 0) {
            throw new IllegalArgumentException("callSeq 不得为负");
        }
        Objects.requireNonNull(toolName, "toolName");
        Objects.requireNonNull(toolVersion, "toolVersion");
        if (actionDigest == null || actionDigest.length() != 64) {
            throw new IllegalArgumentException("actionDigest 必须为 64 位摘要: " + actionDigest);
        }
        if (risk != ToolRisk.R2 && risk != ToolRisk.R3) {
            throw new IllegalArgumentException("意图账本只收 mutation 面风险级: " + risk);
        }
        Objects.requireNonNull(status, "status");
        if (status == IntentStatus.PLANNED && operationId == null) {
            throw new IllegalArgumentException("PLANNED 必须回填 operation_id");
        }
        if (status != IntentStatus.PLANNED && operationId != null) {
            throw new IllegalArgumentException("非 PLANNED 不得携带 operation_id");
        }
        Objects.requireNonNull(argsJson, "argsJson");
    }

    /** ToolGateway VALIDATE_ONLY 路径的初始形态（OPEN；资源键未解析可空） */
    public static ActionIntent open(UUID intentId, UUID runId, UUID taskId, UUID attemptId,
            long callSeq, String toolName, String toolVersion, String actionDigest,
            ToolRisk risk, String requestedResourceKey, String argsJson) {
        return new ActionIntent(intentId, runId, taskId, attemptId, callSeq, toolName,
                toolVersion, actionDigest, risk, requestedResourceKey,
                IntentStatus.OPEN, null, argsJson);
    }

    /** §2.1 授权身份锚（ActionIntent/Request/Grant/Authorization/operations 五账本同源） */
    public String actionId() {
        return toolName;
    }
}
