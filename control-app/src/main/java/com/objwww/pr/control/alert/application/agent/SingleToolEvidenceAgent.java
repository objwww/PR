package com.objwww.pr.control.alert.application.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.objwww.pr.control.alert.application.tool.ToolGateway;
import com.objwww.pr.control.alert.application.tool.ToolInvoker;
import com.objwww.pr.control.alert.application.tool.ToolRegistry;
import com.objwww.pr.control.alert.domain.agent.AgentProfile;
import com.objwww.pr.control.alert.domain.evidence.EvidenceEnvelope;
import com.objwww.pr.control.alert.domain.evidence.EvidenceRepository;
import com.objwww.pr.control.alert.domain.tool.ActionDigest;
import com.objwww.pr.control.alert.domain.tool.ActionEnvelope;
import com.objwww.pr.control.alert.domain.tool.RcaToolInvocationLedger;
import com.objwww.pr.control.alert.domain.tool.ToolControlPlaneException;
import com.objwww.pr.control.alert.domain.tool.ToolControlReason;
import com.objwww.pr.control.alert.domain.tool.ToolInvocationState;
import com.objwww.pr.control.alert.domain.tool.ToolModelVisibleException;
import com.objwww.pr.control.alert.domain.tool.ToolModelVisibleReason;
import com.objwww.pr.control.alert.domain.tool.ToolReasonCode;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * 单工具证据 Agent 基座（AM4 M4-27/28/29 执行器族同构面）：每个 Agent 只做一种 R0
 * 只读查询（allowlist 构造期强制恰为一工具），产 EvidencePackage 落证据账本，
 * 不直接发报告、不提 Claim（Native RCA 的 Claim 面归 M4-30）。
 *
 * <p>调用纪律：账本 PENDING 先行（action digest 本侧对 Gateway 同 envelope canonicalize
 * 预计算，崩溃悬挂账本可查）→ Gateway 唯一咽喉（策略双闸/schema 硬拒绝/硬 deadline/
 * 结果上限）→ 终态 CAS。错误两族分岔：模型可见族（超时/限流/远端故障）→ 账本 FAILED
 * + {@link AgentResult} FAILED（可重试）；控制面终止族 → 账本 FAILED 后<b>原样重抛</b>
 * （调用方终止/升级，不进重试循环）。数据源响应契约：{@code status=success} + data.result
 * 序列；status=error → 账本 REMOTE_4XX 记账 FAILED；空序列 = NO_DATA（调用成功零证据，
 * 无故障不制造证据 E2E-M4-00 同纪律）。
 */
public class SingleToolEvidenceAgent {

    /** 调用身份与上下文（timeRange 非空——ActionEnvelope 契约） */
    public record CallContext(UUID runId, UUID taskId, UUID attemptId, long callSeq,
            long observedGeneration, String inputSnapshotDigest, String timeRange) {
    }

    public enum AgentOutcome {EVIDENCE_PRODUCED, NO_DATA, FAILED}

    /** 执行结局（FAILED 时 errorClass = 模型可见原因码名） */
    public record AgentResult(AgentOutcome outcome, List<UUID> evidenceIds, String errorClass) {
    }

    /** Agent 的单工具身份三元组（工具钉版本 + 证据类型 + 来源标签） */
    public record ToolSpec(String toolName, String toolVersion, String evidenceType,
            String source) {
        public ToolSpec {
            Objects.requireNonNull(toolName);
            Objects.requireNonNull(toolVersion);
            Objects.requireNonNull(evidenceType);
            Objects.requireNonNull(source);
        }
    }

    private final ToolSpec spec;
    private final ToolRegistry registry;
    private final ToolInvoker gateway;
    private final EvidenceRepository evidence;
    private final RcaToolInvocationLedger ledger;
    private final ObjectMapper mapper;

    protected SingleToolEvidenceAgent(AgentProfile profile, ToolSpec spec,
            ToolRegistry registry, ToolInvoker gateway, EvidenceRepository evidence,
            RcaToolInvocationLedger ledger, ObjectMapper mapper) {
        this.spec = Objects.requireNonNull(spec);
        this.registry = Objects.requireNonNull(registry);
        this.gateway = Objects.requireNonNull(gateway);
        this.evidence = Objects.requireNonNull(evidence);
        this.ledger = Objects.requireNonNull(ledger);
        this.mapper = Objects.requireNonNull(mapper);
        if (!profile.toolAllowlist().equals(Set.of(spec.toolName()))) {
            throw new IllegalArgumentException(
                    "Agent allowlist 必须恰为 {" + spec.toolName() + "}（只做一种只读查询），"
                            + "实际: " + profile.toolAllowlist());
        }
        registry.find(spec.toolName(), spec.toolVersion()).orElseThrow(() ->
                new IllegalStateException("工具未注册: " + spec.toolName() + "@"
                        + spec.toolVersion()));
    }

    /** 一次只读查询 → 证据（或 NO_DATA / FAILED） */
    public AgentResult investigate(CallContext ctx, Map<String, Object> args) {
        String schemaHash = registry.find(spec.toolName(), spec.toolVersion()).orElseThrow()
                .definition().schemaHash();
        String actionDigest = ActionDigest.of(new ActionEnvelope("rca", spec.toolName(),
                spec.toolVersion(), schemaHash, args, ctx.timeRange(),
                ctx.inputSnapshotDigest()));
        UUID operationId = UUID.randomUUID();
        ledger.open(new RcaToolInvocationLedger.InvocationIdentity(operationId, ctx.runId(),
                ctx.taskId(), ctx.attemptId(), ctx.callSeq(), spec.toolName(),
                spec.toolVersion(), actionDigest));

        ToolGateway.ToolInvocationResult result;
        try {
            result = gateway.invoke(new ToolGateway.ToolInvocation(ctx.runId(), ctx.taskId(),
                    ctx.attemptId(), ctx.callSeq(), spec.toolName(), spec.toolVersion(),
                    ctx.timeRange(), args, ctx.inputSnapshotDigest()));
        } catch (ToolModelVisibleException e) {
            ledger.fail(operationId, ToolInvocationState.FAILED, ledgerCode(e.reason()));
            return new AgentResult(AgentOutcome.FAILED, List.of(), e.reason().name());
        } catch (ToolControlPlaneException e) {
            ledger.fail(operationId, ToolInvocationState.FAILED, ledgerCode(e.reason()));
            throw e;
        }
        if (result.kind() == ToolGateway.ToolInvocationResult.Kind.VALIDATE_ONLY) {
            // R0 工具恒可执行；可达此分支说明注册面被改坏——终止族显式失败
            ledger.fail(operationId, ToolInvocationState.FAILED, ToolReasonCode.POLICY_DENIED);
            throw new ToolControlPlaneException(ToolControlReason.POLICY_DENIED,
                    "VALIDATE_ONLY 不可达: R0 工具必须可执行");
        }

        Map<String, Object> payload = parsePayload(result.body());
        if (!"success".equals(payload.get("status"))) {
            // 数据源拒绝查询（status=error）——账本按源侧 4xx 归因
            ledger.fail(operationId, ToolInvocationState.FAILED, ToolReasonCode.REMOTE_4XX);
            return new AgentResult(AgentOutcome.FAILED, List.of(), "REMOTE_UNAVAILABLE");
        }
        ledger.succeed(operationId);

        List<?> series = dataSeries(payload);
        if (series.isEmpty()) {
            return new AgentResult(AgentOutcome.NO_DATA, List.of(), null);
        }
        EvidenceEnvelope envelope = EvidenceEnvelope.create(UUID.randomUUID(), ctx.runId(),
                ctx.taskId(), spec.evidenceType(), EvidenceEnvelope.SCHEMA_VERSION,
                ctx.observedGeneration(), spec.source(), scopeOf(ctx), null, null, payload);
        evidence.insert(envelope);
        return new AgentResult(AgentOutcome.EVIDENCE_PRODUCED, List.of(envelope.evidenceId()),
                null);
    }

    // ------------------------------------------------------------------ 内部

    private Map<String, Object> parsePayload(byte[] body) {
        try {
            return (Map<String, Object>) mapper.readValue(body, Map.class);
        } catch (IOException e) {
            // 响应体损坏 = 源侧异常，走模型可见可重试文案（不透传底层细节）
            throw new ToolModelVisibleException(ToolModelVisibleReason.REMOTE_UNAVAILABLE,
                    "工具响应不可解析（临时故障，可重试）");
        }
    }

    private static List<?> dataSeries(Map<String, Object> payload) {
        Object data = payload.get("data");
        if (!(data instanceof Map)) {
            return List.of();
        }
        Object result = ((Map<?, ?>) data).get("result");
        return result instanceof List<?> series ? series : List.of();
    }

    private static Map<String, Object> scopeOf(CallContext ctx) {
        LinkedHashMap<String, Object> scope = new LinkedHashMap<>();
        scope.put("time_range", ctx.timeRange());
        if (ctx.inputSnapshotDigest() != null) {
            scope.put("input_snapshot_digest", ctx.inputSnapshotDigest());
        }
        return scope;
    }

    /** 模型可见族 → 账本十码归因映射 */
    private static ToolReasonCode ledgerCode(ToolModelVisibleReason reason) {
        return switch (reason) {
            case TIMEOUT_RETRYABLE -> ToolReasonCode.TIMEOUT;
            case RATE_LIMITED -> ToolReasonCode.RATE_LIMITED;
            case REPLAY_MISS -> ToolReasonCode.REPLAY_MISS;
            case REMOTE_UNAVAILABLE, NO_DATA -> ToolReasonCode.TRANSPORT_UNKNOWN;
        };
    }

    /** 控制面终止族 → 账本十码归因映射（无对应码收敛 TRANSPORT_UNKNOWN） */
    private static ToolReasonCode ledgerCode(ToolControlReason reason) {
        return switch (reason) {
            case POLICY_DENIED -> ToolReasonCode.POLICY_DENIED;
            case INVALID_ARGS, UNKNOWN_TOOL -> ToolReasonCode.INVALID_INPUT;
            case AUTH_FAILED -> ToolReasonCode.AUTH_FAILED;
            case BUDGET_EXHAUSTED, RESULT_OVERSIZE, STALE_GENERATION ->
                    ToolReasonCode.TRANSPORT_UNKNOWN;
        };
    }
}
