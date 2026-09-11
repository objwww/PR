package com.objwww.pr.control.alert.application.agent;

import com.objwww.pr.control.alert.domain.tool.RcaToolInvocationLedger;
import com.objwww.pr.control.alert.domain.tool.ToolControlPlaneException;
import com.objwww.pr.control.alert.domain.tool.ToolControlReason;
import com.objwww.pr.control.alert.domain.tool.ToolModelVisibleException;
import com.objwww.pr.control.alert.domain.tool.ToolModelVisibleReason;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * 主 Agent 受限直查生产口（R7-X6）：TOOL_CALL 经既有受控单工具 Agent 面（预算
 * TOOL_CALL 硬闸 + 账本 PENDING 先行 + 证据落库 + 熔断签名，EX-A1/F16 纪律原样
 * 复用——装配把 allowlist 内每个工具对位到已部署的单工具 Agent），本口只补两件事：
 * <ul>
 *   <li>物理请求身份：call_seq 每次调用从账本恢复面 max+1 重算（主驱动一步一物理
 *       请求，EX-A3 账本行可证）；</li>
 *   <li>{@link SingleToolEvidenceAgent.AgentResult} → 端口契约映射：NO_DATA / 模型
 *       可见失败 → {@link ToolModelVisibleException}（运行器计步重驱）；控制面终止
 *       族（策略/schema 拒绝）原样上抛（执行器降级 DEAD）。</li>
 * </ul>
 *
 * @author wanghua
 * @date 2026-09-11
 */
public final class PrimaryGatewayToolPort implements BoundedLlmRoleRunner.PrimaryToolPort {

    private final Map<String, SingleToolEvidenceAgent> delegates;
    private final RcaToolInvocationLedger ledger;

    /** delegates：tool_id → 已装配单工具 Agent（allowlist 对位在装配期完成） */
    public PrimaryGatewayToolPort(Map<String, SingleToolEvidenceAgent> delegates,
            RcaToolInvocationLedger ledger) {
        this.delegates = Map.copyOf(Objects.requireNonNull(delegates, "delegates"));
        this.ledger = Objects.requireNonNull(ledger, "ledger");
    }

    @Override
    public UUID invoke(SingleToolEvidenceAgent.CallContext ctx, String toolId,
            Map<String, Object> args) {
        SingleToolEvidenceAgent agent = delegates.get(toolId);
        if (agent == null) {
            throw new ToolControlPlaneException(ToolControlReason.UNKNOWN_TOOL,
                    "工具未对位受控执行面: " + toolId);
        }
        long callSeq = ledger.findRecoveryByTask(ctx.runId(), ctx.taskId()).stream()
                .mapToLong(RcaToolInvocationLedger.InvocationRecovery::callSeq)
                .max().orElse(0) + 1;
        SingleToolEvidenceAgent.CallContext stepCtx = new SingleToolEvidenceAgent.CallContext(
                ctx.runId(), ctx.taskId(), ctx.attemptId(), callSeq,
                ctx.observedGeneration(), ctx.investigationInputDigest(), ctx.timeRange());
        SingleToolEvidenceAgent.AgentResult result = agent.investigate(stepCtx, args);
        return switch (result.outcome()) {
            case EVIDENCE_PRODUCED -> {
                List<UUID> ids = result.evidenceIds();
                yield ids.isEmpty() ? null : ids.get(0);
            }
            case NO_DATA -> throw new ToolModelVisibleException(
                    ToolModelVisibleReason.NO_DATA, "查询成功零数据（可调整查询参数重试）");
            case FAILED -> throw new ToolModelVisibleException(
                    ToolModelVisibleReason.REMOTE_UNAVAILABLE,
                    result.errorClass() == null ? "TOOL_FAILED" : result.errorClass());
        };
    }
}
