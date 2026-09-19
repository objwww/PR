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
import java.nio.charset.StandardCharsets;
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
public final class ToolGateway implements ToolInvoker {

    private static final org.slf4j.Logger log =
            org.slf4j.LoggerFactory.getLogger(ToolGateway.class);

    private final ToolRegistry registry;
    private final ToolPolicy policy;
    private final ExecutorService callPool;
    private final Clock clock;
    private final RcaEventAppender events; // 可空：意图/进度事件账本（V14）
    private final InFlightToolCancels cancels; // 可空：WC-3 在途取消通知（丢了只慢不错）
    /** PA-A5：决策溯源标签（可空=未装配，意图事件不含 provenance 块） */
    private final com.objwww.pr.control.alert.domain.event.DecisionProvenance provenanceTags;
    /** PB-B1：意图台账（可空=未装配，意图仅事件面不落行——渐进采纳旧装配零漂移） */
    private final com.objwww.pr.control.alert.application.mutation.ActionIntentLedger
            intentLedger;
    /** BA-171：意图落账后的审批推进回调（可空=跳过；异常捕获降级不炸调查） */
    private final com.objwww.pr.control.alert.application.mutation.IntentFollowUp
            intentFollowUp;

    public ToolGateway(ToolRegistry registry, ToolPolicy policy, ExecutorService callPool,
            Clock clock, RcaEventAppender events) {
        this(registry, policy, callPool, clock, events, null);
    }

    public ToolGateway(ToolRegistry registry, ToolPolicy policy, ExecutorService callPool,
            Clock clock, RcaEventAppender events, InFlightToolCancels cancels) {
        this(registry, policy, callPool, clock, events, cancels, null);
    }

    /** PA-A5 全参形态：provenanceTags（agent_build_sha + policy_version 锚） */
    public ToolGateway(ToolRegistry registry, ToolPolicy policy, ExecutorService callPool,
            Clock clock, RcaEventAppender events, InFlightToolCancels cancels,
            com.objwww.pr.control.alert.domain.event.DecisionProvenance provenanceTags) {
        this(registry, policy, callPool, clock, events, cancels, provenanceTags, null);
    }

    /** PB-B1 全参形态：intentLedger（意图行 + 意图事件同短事务，V114） */
    public ToolGateway(ToolRegistry registry, ToolPolicy policy, ExecutorService callPool,
            Clock clock, RcaEventAppender events, InFlightToolCancels cancels,
            com.objwww.pr.control.alert.domain.event.DecisionProvenance provenanceTags,
            com.objwww.pr.control.alert.application.mutation.ActionIntentLedger intentLedger) {
        this(registry, policy, callPool, clock, events, cancels, provenanceTags, intentLedger,
                null);
    }

    /** BA-171 全参形态：intentFollowUp（意图落账后自动进审批队列；可空=跳过保兼容） */
    public ToolGateway(ToolRegistry registry, ToolPolicy policy, ExecutorService callPool,
            Clock clock, RcaEventAppender events, InFlightToolCancels cancels,
            com.objwww.pr.control.alert.domain.event.DecisionProvenance provenanceTags,
            com.objwww.pr.control.alert.application.mutation.ActionIntentLedger intentLedger,
            com.objwww.pr.control.alert.application.mutation.IntentFollowUp intentFollowUp) {
        this.registry = Objects.requireNonNull(registry);
        this.policy = Objects.requireNonNull(policy);
        this.callPool = Objects.requireNonNull(callPool);
        this.clock = Objects.requireNonNull(clock);
        this.events = events; // 可空（意图事件落档可选项）
        this.cancels = cancels; // 可空（默认不参与在途取消通知）
        this.provenanceTags = provenanceTags;
        this.intentLedger = intentLedger;
        this.intentFollowUp = intentFollowUp;
    }

    /** 下发给 LLM 的工具清单：被拒工具从清单删除（双闸之一） */
    public List<ToolRegistry.Registration> manifestFor() {
        return registry.all().stream()
                .filter(r -> policy.allows(r.definition().name()))
                .toList();
    }

    /**
     * 一次调用请求（幂等/审计身份五元组 + 工具语义字段；EX-A0：输入身份=调查输入绑定）。
     * WC-3：{@code externalDeadline} = 任务/Run 硬期限（Host 下发，可空）——工具等待
     * deadline 取 min(工具超时, 外部期限)，过期即停止事实，不吞成工具超时。
     */
    public record ToolInvocation(UUID runId, UUID taskId, UUID attemptId, long callSeq,
            String toolName, String toolVersion, String timeRange,
            Map<String, Object> args,
            com.objwww.pr.control.alert.domain.identity.InvestigationInputDigest
                    investigationInputDigest,
            java.time.Instant externalDeadline) {

        public ToolInvocation(UUID runId, UUID taskId, UUID attemptId, long callSeq,
                String toolName, String toolVersion, String timeRange,
                Map<String, Object> args,
                com.objwww.pr.control.alert.domain.identity.InvestigationInputDigest
                        investigationInputDigest) {
            this(runId, taskId, attemptId, callSeq, toolName, toolVersion, timeRange,
                    args, investigationInputDigest, null);
        }
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
                invocation.args(), invocation.timeRange(),
                invocation.investigationInputDigest()));
        ToolRisk risk = registration.definition().risk();
        if (!risk.executable()) {
            UUID intentId = recordIntent(invocation, digest, risk);
            return new ToolInvocationResult(ToolInvocationResult.Kind.VALIDATE_ONLY, digest,
                    pendingApprovalBody(invocation, risk, intentId));
        }
        byte[] body = executeWithDeadline(registration, invocation);
        if (body.length > registration.definition().resultLimitBytes()) {
            throw new ToolControlPlaneException(ToolControlReason.RESULT_OVERSIZE,
                    "RESULT_OVERSIZE: " + body.length + " > "
                            + registration.definition().resultLimitBytes());
        }
        return new ToolInvocationResult(ToolInvocationResult.Kind.EXECUTED, digest, body);
    }

    /**
     * 硬 deadline 执行：独立调用池 + 超时 cancel(true)，迟到结果丢弃不补旧快照。
     * WC-3：deadline = min(工具超时, 外部硬期限)（§5.1 最小化）；外部期限已过 =
     * 停止事实（{@code RUN_DEADLINE_EXCEEDED}，不吞成工具超时重试）；Run 取消的
     * 在途中断通知经 {@link InFlightToolCancels} 转 {@code RUN_CANCELLED} 停止。
     */
    private byte[] executeWithDeadline(ToolRegistry.Registration registration,
            ToolInvocation invocation) {
        java.time.Instant hard = clock.instant()
                .plusMillis(registration.definition().timeoutMillis());
        java.time.Instant external = invocation.externalDeadline();
        if (external != null && external.isBefore(hard)) {
            if (!clock.instant().isBefore(external)) {
                throw new com.objwww.pr.control.alert.application.ExecutionControl
                        .StoppedException(
                        com.objwww.pr.control.alert.application.ExecutionControl
                                .STOP_RUN_DEADLINE_EXCEEDED,
                        "外部硬期限已过，工具等待不再开始: " + external);
            }
            hard = external;
        }
        long deadline = hard.toEpochMilli();
        ToolExecutor.ToolExecution execution = new ToolExecutor.ToolExecution(
                invocation.args(), deadline, registration.definition().resultLimitBytes());
        // RV03：先注册句柄（QUEUED）再入池——执行包装器入口做 QUEUED→RUNNING CAS
        // （取消与启动竞争，取消胜=零执行），finally 置实际退出事实
        InFlightToolCancels.Handle handle = cancels == null
                ? null : cancels.register(invocation.runId());
        Future<byte[]> future;
        try {
            future = callPool.submit(() -> {
                if (handle != null && !handle.beginRun()) {
                    // 排队取消获胜：执行体不启动（执行次数 0），类型化停止
                    throw new com.objwww.pr.control.alert.application.ExecutionControl
                            .StoppedException(
                            com.objwww.pr.control.alert.application.ExecutionControl
                                    .STOP_RUN_CANCELLED,
                            "run " + invocation.runId() + " 已取消，排队取消不启动执行");
                }
                try {
                    return registration.executor().execute(execution);
                } finally {
                    if (handle != null) {
                        cancels.exit(handle); // 执行体 finally 才是退出事实（RV03/T12）
                    }
                }
            });
        } catch (java.util.concurrent.RejectedExecutionException e) {
            // EX-A4a（F17）：bulkhead 满则明确拒绝——独立池有界队列的背压语义，
            // 不静默排队也不靠兜底映射；模型可见族（可退避重试）。入池拒绝走回收路径
            if (handle != null) {
                cancels.reclaim(invocation.runId(), handle);
            }
            throw new ToolModelVisibleException(ToolModelVisibleReason.REMOTE_UNAVAILABLE,
                    "工具调用通道拥塞（背压拒绝，可稍后重试）");
        }
        if (handle != null) {
            handle.attach(future); // 中断面（cancel(true) 只请求中断，不证明退出）
        }
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
        } catch (java.util.concurrent.CancellationException e) {
            if (cancels != null && cancels.wasStopCancelled(invocation.runId())) {
                // WC-3：Run 取消的后置在途中断——类型化停止，不折成可重试远端故障
                throw new com.objwww.pr.control.alert.application.ExecutionControl
                        .StoppedException(
                        com.objwww.pr.control.alert.application.ExecutionControl
                                .STOP_RUN_CANCELLED,
                        "run " + invocation.runId() + " 已取消，中断在途工具等待");
            }
            throw e; // 非 stop 取消（线程池 shutdown 等）——原样上抛
        } catch (java.util.concurrent.ExecutionException e) {
            future.cancel(true);
            throw mapExecutorFailure(e.getCause() == null ? e : e.getCause());
        } finally {
            if (handle != null) {
                // RV03：调用方 finally 只结束「等待」——执行体忽略中断仍在运行时，
                // 在飞事实由句柄保留（EXITED 由执行包装器 finally 回收）
                cancels.endWait(invocation.runId(), handle);
            }
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

    /**
     * R2/R3 意图记录（VALIDATE_ONLY，零执行；PB-B1 起意图行同短事务入账）。
     * BA-171：①请求面资源键从 args["service"] 提取随意图落账（替代旧的恒 null——
     * 授权身份仍只信 Resolver 权威解析，本键只是解析输入）；②意图行提交后挂
     * IntentFollowUp 审批推进回调（可空=跳过；回调异常只记日志不炸调查——意图留
     * OPEN 未解析是诚实状态）。返回意图 id（未装配任何账本面 → null）。
     */
    private UUID recordIntent(ToolInvocation invocation, String digest, ToolRisk risk) {
        if (events == null && intentLedger == null) {
            return null;
        }
        var intentId = java.util.UUID.randomUUID();
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("kind", "TOOL_INTENT_VALIDATED");
        payload.put("intent_id", intentId.toString());
        payload.put("tool", invocation.toolName());
        payload.put("version", invocation.toolVersion());
        payload.put("risk", risk.name());
        payload.put("action_digest", digest);
        // PA-A5：决策溯源统一块（build sha + policy version + 工具 schema 锚）
        if (provenanceTags != null) {
            payload.put("provenance", provenanceTags
                    .withTool(invocation.toolName(), invocation.toolVersion(),
                            registrationSchemaHash(invocation))
                    .toCanonicalJson());
        }
        String payloadJson = InternalCanonicalJsonV1.canonicalize(payload);
        if (intentLedger != null) {
            // BA-171 资源键约定：args["service"] 原样作为 requested_resource_key——
            // resource_alias.resource_key 精确匹配面（键格式以 Resolver 为准）
            String requestedKey = requestedResourceKey(invocation.args());
            var intent = com.objwww.pr.control.alert.domain.mutation.ActionIntent.open(
                    intentId, invocation.runId(), invocation.taskId(), invocation.attemptId(),
                    invocation.callSeq(), invocation.toolName(), invocation.toolVersion(),
                    digest, risk, requestedKey, payloadJson);
            intentLedger.record(intent, new RcaEventAppender.EventDraft(
                    intentId, "TOOL_INTENT_VALIDATED", payloadJson));
            // 审批推进（意图行已随 REQUIRES_NEW 短事务提交，回调可读）：无资源键
            // 无法解析，跳过；回调失败只记日志——意图留 OPEN 未解析，不炸调查
            if (intentFollowUp != null && requestedKey != null) {
                try {
                    intentFollowUp.onIntentRecorded(intentId, requestedKey);
                } catch (Exception e) {
                    log.warn("意图审批推进失败（意图留 OPEN 未解析，可人工 resolve/request 兜底）"
                            + " intent={}: {}", intentId, e.toString());
                }
            }
            return intentId;
        }
        events.appendIndependent(invocation.runId(), new RcaEventAppender.EventDraft(
                intentId, "TOOL_INTENT_VALIDATED", payloadJson));
        return intentId;
    }

    /** 资源键提取约定（BA-171）：args["service"] 原样透出；缺/空白 → null（跳过审批推进） */
    private static String requestedResourceKey(Map<String, Object> args) {
        if (args == null) {
            return null;
        }
        Object service = args.get("service");
        return service instanceof String s && !s.isBlank() ? s.trim() : null;
    }

    /**
     * VALIDATE_ONLY 的模型可见反馈体（BA-171）：写类调用不再零反馈——语义=「已提交
     * 人工审批（审批编号 = intentId 前 8 位），审批通过后方执行；请勿重试同一操作，
     * 继续其他只读取证」。意图台账未装配时如实降级文案（不冒充已进审批链）。
     */
    private static byte[] pendingApprovalBody(ToolInvocation invocation, ToolRisk risk,
            UUID intentId) {
        Map<String, Object> pending = new LinkedHashMap<>();
        pending.put("status", "PENDING_APPROVAL");
        pending.put("tool", invocation.toolName());
        pending.put("risk", risk.name());
        if (intentId != null) {
            pending.put("intent_id", intentId.toString());
        }
        pending.put("message", intentId != null
                ? "该操作为写类高危操作，已提交人工审批（审批编号 "
                        + intentId.toString().substring(0, 8) + "），审批通过后方执行；"
                        + "请勿重试同一操作，继续其他只读取证"
                : "该操作为写类高危操作，仅校验记录未执行（意图台账未装配，无法进入审批链）；"
                        + "请勿重试同一操作，继续其他只读取证");
        return InternalCanonicalJsonV1.canonicalize(pending)
                .getBytes(StandardCharsets.UTF_8);
    }

    private String registrationSchemaHash(ToolInvocation invocation) {
        return registry.find(invocation.toolName(), invocation.toolVersion())
                .map(reg -> reg.definition().schemaHash())
                .orElse(null);
    }
}
