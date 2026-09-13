package com.objwww.pr.control.alert.application.agent;

import com.objwww.pr.control.alert.domain.tool.RcaToolInvocationLedger;
import com.objwww.pr.control.alert.domain.tool.ToolControlPlaneException;
import com.objwww.pr.control.alert.domain.tool.ToolControlReason;
import com.objwww.pr.control.alert.domain.tool.ToolModelVisibleException;
import com.objwww.pr.control.alert.domain.tool.ToolModelVisibleReason;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * 主 Agent 受限直查生产口（R7-X6）：TOOL_CALL 经既有受控单工具 Agent 面（预算
 * TOOL_CALL 硬闸 + 账本 PENDING 先行 + 证据落库 + 熔断签名，EX-A1/F16 纪律原样
 * 复用——装配把 allowlist 内每个工具对位到已部署的单工具 Agent），本口只补三件事：
 * <ul>
 *   <li>物理请求身份：call_seq 每次调用从账本恢复面 max+1 重算（主驱动一步一物理
 *       请求，EX-A3 账本行可证）；</li>
 *   <li>{@link SingleToolEvidenceAgent.AgentResult} → 端口契约映射：NO_DATA / 模型
 *       可见失败 → {@link ToolModelVisibleException}（运行器计步重驱）；控制面终止
 *       族（策略/schema 拒绝）原样上抛（执行器降级 DEAD）；</li>
 *   <li><b>失败分类封闭解析（A0 补充方案 §3）</b>：AgentResult.errorClass 只认
 *       {@link ToolModelVisibleReason} 名与控制终止码（BUDGET_EXHAUSTED/
 *       DOOM_LOOP_TRIPPED → 控制面终止，不当网络错误重试）；未知值显式
 *       INTERNAL_ERROR，不再默认 REMOTE_UNAVAILABLE 诱导模型重试。工具越权
 *       （不在 allowlist）零触网有界反馈；allowlist 内但装配缺席=CONFIGURATION_ERROR
 *       控制终止（启动期校验为该缺口的首闸，本口为运行期兜底）。</li>
 * </ul>
 *
 * @author wanghua
 * @date 2026-09-11
 */
public final class PrimaryGatewayToolPort implements BoundedLlmRoleRunner.PrimaryToolPort {

    private final Map<String, SingleToolEvidenceAgent> delegates;
    private final RcaToolInvocationLedger ledger;
    /** 本 Run 工具允许集（与 AgentProfile.toolAllowlist 同源）：越权反馈/装配缺口区分面 */
    private final Set<String> allowlist;

    /** delegates：tool_id → 已装配单工具 Agent（allowlist 对位在装配期完成） */
    public PrimaryGatewayToolPort(Map<String, SingleToolEvidenceAgent> delegates,
            RcaToolInvocationLedger ledger, Set<String> allowlist) {
        this.delegates = Map.copyOf(Objects.requireNonNull(delegates, "delegates"));
        this.ledger = Objects.requireNonNull(ledger, "ledger");
        this.allowlist = Set.copyOf(Objects.requireNonNull(allowlist, "allowlist"));
    }

    @Override
    public UUID invoke(SingleToolEvidenceAgent.CallContext ctx, String toolId,
            Map<String, Object> args) {
        if (!allowlist.contains(toolId)) {
            // A0 补充方案 AS-04：零触网、权限不扩大（运行器 allowlist 首闸之外的兜底面）
            throw new ToolModelVisibleException(ToolModelVisibleReason.TOOL_NOT_ALLOWED,
                    "TOOL_NOT_ALLOWED: 工具 " + toolId + " 不在本 Run 允许集，权限不扩大；"
                            + "只能从 tool_allowlist 内选择");
        }
        SingleToolEvidenceAgent agent = delegates.get(toolId);
        if (agent == null) {
            // A0 补充方案 AS-05：允许集含工具但执行装配缺席=装配缺陷（启动期校验首闸，
            // 此处运行期 fail-fast 终止；不能等模型步步 UNKNOWN_TOOL 或伪装远端故障）
            throw new ToolControlPlaneException(ToolControlReason.CONFIGURATION_ERROR,
                    "CONFIGURATION_ERROR: allowlist 工具未对位受控执行面: " + toolId);
        }
        long callSeq = ledger.findRecoveryByTask(ctx.runId(), ctx.taskId()).stream()
                .mapToLong(RcaToolInvocationLedger.InvocationRecovery::callSeq)
                .max().orElse(0) + 1;
        // WC-3：控制身份（心跳+终态探针）与动作 deadline 随物理请求下传，不重铸
        SingleToolEvidenceAgent.CallContext stepCtx = new SingleToolEvidenceAgent.CallContext(
                ctx.runId(), ctx.taskId(), ctx.attemptId(), callSeq,
                ctx.observedGeneration(), ctx.investigationInputDigest(), ctx.timeRange(),
                ctx.controlSignal(), ctx.actionDeadline());
        SingleToolEvidenceAgent.AgentResult result = agent.investigate(stepCtx, args);
        return switch (result.outcome()) {
            case EVIDENCE_PRODUCED -> {
                List<UUID> ids = result.evidenceIds();
                yield ids.isEmpty() ? null : ids.get(0);
            }
            case NO_DATA -> throw new ToolModelVisibleException(
                    ToolModelVisibleReason.NO_DATA, "查询成功零数据（可调整查询参数重试）");
            case FAILED -> throw classifyFailure(result.errorClass());
        };
    }

    /**
     * 失败分类封闭解析（A0 补充方案 §3）：errorClass 只认模型可见原因名与控制终止
     * 码；未知值显式 INTERNAL_ERROR，不默认 REMOTE_UNAVAILABLE。
     */
    private static RuntimeException classifyFailure(String errorClass) {
        if (errorClass == null || errorClass.isBlank()) {
            return new ToolModelVisibleException(ToolModelVisibleReason.INTERNAL_ERROR,
                    "INTERNAL_ERROR: 工具失败原因未携带分类（不假报远端故障）");
        }
        switch (errorClass) {
            case "BUDGET_EXHAUSTED":
                // AS-03：控制终止——主循环有界结束，零网络重试
                return new ToolControlPlaneException(ToolControlReason.BUDGET_EXHAUSTED,
                        "BUDGET_EXHAUSTED: 本 Run 工具调用预算耗尽（控制终止，非网络错误）");
            case "DOOM_LOOP_TRIPPED":
                return new ToolControlPlaneException(ToolControlReason.DOOM_LOOP_TRIPPED,
                        "DOOM_LOOP_TRIPPED: 重复同参调用熔断触发（控制终止，非网络错误）");
            default:
                try {
                    ToolModelVisibleReason reason =
                            ToolModelVisibleReason.valueOf(errorClass);
                    // 原始分类保真：TIMEOUT_RETRYABLE/RATE_LIMITED/SOURCE_UNAVAILABLE/
                    // REMOTE_UNAVAILABLE 各自保留原面（不再统一折叠为远端故障）
                    return new ToolModelVisibleException(reason,
                            "TOOL_FAILED " + reason.name() + ": 工具调用未成功"
                                    + ("REMOTE_UNAVAILABLE".equals(errorClass)
                                    ? "（临时远端故障，可重试）" : "；按原因分类处理"));
                } catch (IllegalArgumentException unknown) {
                    return new ToolModelVisibleException(ToolModelVisibleReason.INTERNAL_ERROR,
                            "INTERNAL_ERROR: 工具失败原因未分类（" + errorClass
                                    + "）；不假报远端故障，不自动重试");
                }
        }
    }
}
