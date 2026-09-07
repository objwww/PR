package com.objwww.pr.control.alert.application.tool;

import com.objwww.pr.control.alert.domain.event.RcaEventAppender;
import com.objwww.pr.control.alert.domain.tool.ActionDigest;
import com.objwww.pr.control.alert.domain.tool.ActionEnvelope;
import com.objwww.pr.control.alert.domain.tool.InternalCanonicalJsonV1;
import com.objwww.pr.control.alert.domain.tool.ToolArgsValidator;
import com.objwww.pr.control.alert.domain.tool.ToolControlPlaneException;
import com.objwww.pr.control.alert.domain.tool.ToolControlReason;
import com.objwww.pr.control.alert.domain.tool.ToolModelVisibleException;
import com.objwww.pr.control.alert.domain.tool.ToolModelVisibleReason;
import com.objwww.pr.control.alert.domain.tool.ToolPolicy;
import com.objwww.pr.control.alert.domain.tool.ToolRisk;

import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/**
 * 工具调用唯一咽喉（AM4 M4-16/17）。固定序：注册表解析 → 策略二次鉴权（双闸之二；
 * 第一闸 = LLM 下发清单已按 policy 裁剪，见 {@link #manifestFor}）→ schema 校验
 * （未声明字段硬拒绝）→ canonical digest → R2/R3 VALIDATE_ONLY 记录意图零执行 →
 * 硬 deadline 执行（独立调用池，超时 cancel(true)，迟到结果直接丢弃——不补旧 Snapshot）
 * → 结果上限裁断（RESULT_OVERSIZE）。
 *
 * <p>错误两族（评审裁定）：{@link ToolControlPlaneException}（终止族——不触发模型
 * 重试循环）与 {@link ToolModelVisibleException}（模型可见族——结构化脱敏固定文案，
 * executor 异常文本一律不透传）。
 */
public final class ToolGateway {

    private final ToolRegistry registry;
    private final ToolPolicy policy;
    private final ExecutorService callPool;
    private final Clock clock;
    private final RcaEventAppender events; // 可空：意图/进度事件账本（V14）

    public ToolGateway(ToolRegistry registry, ToolPolicy policy, ExecutorService callPool,
            Clock clock, RcaEventAppender events) {
        this.registry = Objects.requireNonNull(registry);
        this.policy = Objects.requireNonNull(policy);
        this.callPool = Objects.requireNonNull(callPool);
        this.clock = Objects.requireNonNull(clock);
        this.events = events; // 可空（意图事件落档可选项）
    }

    /** 下发给 LLM 的工具清单：被拒工具从清单删除（双闸之一） */
    public List<ToolRegistry.Registration> manifestFor() {
        return registry.all().stream()
                .filter(r -> policy.allows(r.definition().name()))
                .toList();
    }

    /** 一次调用请求（幂等/审计身份五元组 + 工具语义字段） */
    public record ToolInvocation(UUID runId, UUID taskId, UUID attemptId, long callSeq,
            String toolName, String toolVersion, String timeRange,
            Map<String, Object> args, String inputSnapshotDigest) {
    }

    /** 调用结局：EXECUTED（真实执行）或 VALIDATE_ONLY（R2/R3 意图记录，零执行） */
    public record ToolInvocationResult(Kind kind, String actionDigest, byte[] body) {
        public enum Kind { EXECUTED, VALIDATE_ONLY }
    }

    public ToolInvocationResult invoke(ToolInvocation invocation) {
        ToolRegistry.Registration registration = registry
                .find(invocation.toolName(), invocation.toolVersion())
                .orElseThrow(() -> new ToolControlPlaneException(ToolControlReason.UNKNOWN_TOOL,
                        "UNKNOWN_TOOL: " + invocation.toolName() + "@"
                                + invocation.toolVersion()));
        // 双闸之二：执行时仍鉴权（清单裁剪可被绕过，此处不可）
        if (!policy.allows(invocation.toolName())) {
            throw new ToolControlPlaneException(ToolControlReason.POLICY_DENIED,
                    "POLICY_DENIED: " + invocation.toolName());
        }
        try {
            ToolArgsValidator.validate(registration.definition(), invocation.args());
        } catch (IllegalArgumentException e) {
            throw new ToolControlPlaneException(ToolControlReason.INVALID_ARGS, e.getMessage());
        }
        String digest = ActionDigest.of(new ActionEnvelope("rca", invocation.toolName(),
                invocation.toolVersion(), registration.definition().schemaHash(),
                invocation.args(), invocation.timeRange(), invocation.inputSnapshotDigest()));
        ToolRisk risk = registration.definition().risk();
        if (!risk.executable()) {
            recordIntent(invocation, digest, risk);
            return new ToolInvocationResult(ToolInvocationResult.Kind.VALIDATE_ONLY, digest,
                    null);
        }
        byte[] body = executeWithDeadline(registration, invocation);
        if (body.length > registration.definition().resultLimitBytes()) {
            throw new ToolControlPlaneException(ToolControlReason.RESULT_OVERSIZE,
                    "RESULT_OVERSIZE: " + body.length + " > "
                            + registration.definition().resultLimitBytes());
        }
        return new ToolInvocationResult(ToolInvocationResult.Kind.EXECUTED, digest, body);
    }

    /** 硬 deadline 执行：独立调用池 + 超时 cancel(true)，迟到结果丢弃不补旧快照 */
    private byte[] executeWithDeadline(ToolRegistry.Registration registration,
            ToolInvocation invocation) {
        long deadline = clock.millis() + registration.definition().timeoutMillis();
        ToolExecutor.ToolExecution execution = new ToolExecutor.ToolExecution(
                invocation.args(), deadline, registration.definition().resultLimitBytes());
        Future<byte[]> future = callPool.submit(
                () -> registration.executor().execute(execution));
        try {
            return future.get(deadline - clock.millis(), TimeUnit.MILLISECONDS);
        } catch (java.util.concurrent.TimeoutException e) {
            future.cancel(true); // 迟到结果作废
            throw new ToolModelVisibleException(ToolModelVisibleReason.TIMEOUT_RETRYABLE,
                    "工具调用超时（可重试）");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            future.cancel(true);
            throw new ToolModelVisibleException(ToolModelVisibleReason.REMOTE_UNAVAILABLE,
                    "工具调用被中断（临时远端故障）");
        } catch (java.util.concurrent.ExecutionException e) {
            future.cancel(true);
            throw mapExecutorFailure(e.getCause() == null ? e : e.getCause());
        }
    }

    /** executor 异常 → 错误两族；文案固定脱敏，绝不透传底层 message */
    private RuntimeException mapExecutorFailure(Throwable cause) {
        if (cause instanceof ToolModelVisibleException visible) {
            return visible; // 执行器自判的模型可见族（如 NO_DATA）原样保留
        }
        if (cause instanceof ToolControlPlaneException control) {
            return control; // 执行器显式上抛的终止族（如 AUTH_FAILED）
        }
        return new ToolModelVisibleException(ToolModelVisibleReason.REMOTE_UNAVAILABLE,
                "工具远端暂不可用（临时故障，可重试）");
    }

    /** R2/R3 意图记录（VALIDATE_ONLY，零执行；AM4 不引入审批态） */
    private void recordIntent(ToolInvocation invocation, String digest, ToolRisk risk) {
        if (events == null) {
            return;
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("kind", "TOOL_INTENT_VALIDATED");
        payload.put("tool", invocation.toolName());
        payload.put("version", invocation.toolVersion());
        payload.put("risk", risk.name());
        payload.put("action_digest", digest);
        events.appendIndependent(invocation.runId(), new RcaEventAppender.EventDraft(
                UUID.randomUUID(), "TOOL_INTENT_VALIDATED",
                InternalCanonicalJsonV1.canonicalize(payload)));
    }
}
