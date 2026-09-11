package com.objwww.pr.control.alert.application.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.objwww.pr.control.alert.application.DeterministicSupervisor;
import com.objwww.pr.control.alert.domain.agent.AgentProfile;
import com.objwww.pr.control.alert.domain.agent.PrimaryCheckpoint;
import com.objwww.pr.control.alert.domain.agent.PrimaryDecision;
import com.objwww.pr.control.alert.domain.agent.RcaModelOutcome;
import com.objwww.pr.control.alert.domain.evidence.EvidenceRepository;
import com.objwww.pr.control.alert.domain.repository.PrimaryCheckpointRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * 受控有界 LLM 运行器（R7-X2，v2.1 §十一.1 首个受控运行器；§三 有界 AgentLoop）：
 * <b>无状态单步驱动</b>——全部推进状态在主任务检查点（V47 rca_primary_checkpoint），
 * 本类不持有任何跨步可变态，任意一步崩溃后重驱动从检查点续走（RD09 数据基础）。
 *
 * <p>一步 = 读检查点 → WAITING_CHILDREN 先复判唤醒 → 步数耗尽走确定性兜底 FINAL
 * （§四 终止兜底：不再花一次模型调用）→ 否则经 {@link RcaActionGuard} 模型路径
 * （§六固定顺序）取得一个互斥 Decision：
 * <ul>
 *   <li>TOOL_CALL：allowlist 校验（越权=结构化拒绝留痕）→ 受控工具口取证 →
 *       steps+1 仍 PRIMARY_READY；</li>
 *   <li>DELEGATE：Supervisor 确定性裁决（唯一建子任务路径）；获批 → WAITING_CHILDREN，
 *       全拒 → 有界继续；</li>
 *   <li>FINAL：{@link PrimaryClaimAdmission} 代码准入（RD05/RX20）→ 提案落检查点
 *       （phase 就地固化，报告相位消费）。</li>
 * </ul>
 * 决策不可解析同样 steps+1（重驱可试错；耗尽即兜底），不静默重放同一步。
 */
public class BoundedLlmRoleRunner implements RoleRunner {

    private static final Logger log = LoggerFactory.getLogger(BoundedLlmRoleRunner.class);

    /** 单步模型调用保守 token 估值（预算预留面；usage 实扣以服务端回执为准） */
    static final long TOKEN_ESTIMATE_PER_STEP = 1_500L;
    /** 单步 max_tokens（有界输出；Decision 是小对象，不允许长文） */
    static final int MAX_TOKENS_PER_STEP = 1_000;

    /**
     * 输出协议后缀（BA-110：runner 拥有的硬契约，不依赖可配置 prompt 记得携带——
     * 195 真窗实证：缺省 prompt 零协议描述时 glm-5 全程自然语言作答，8 步
     * JsonParseException 耗尽）。协议随信封逐步下发，prompt 版本只管调查策略。
     */
    static final String PROTOCOL_SUFFIX = "\n【输出协议（硬性）】回复必须且只能是一个 JSON"
            + " 对象：不要 markdown 围栏、不要解释文字、不要思考过程。三种形状恰选一：\n"
            + "1. 取证：{\"tool_call\":{\"tool_id\":\"<tool_allowlist 之一>\",\"args\":{...}}}\n"
            + "2. 委派（仅确需专业能力且 delegation_batches_remaining>0）："
            + "{\"delegate\":{\"requests\":[{\"gap_id\":\"g1\",\"role_id\":\"metrics|logs|change\","
            + "\"question\":\"...\",\"input_refs\":[],\"scope\":{},\"requested_budget\":4}]}}\n"
            + "3. 收敛：{\"final\":{\"claims\":[{\"claim_key\":\"c1\","
            + "\"kind\":\"ROOT_CAUSE|HYPOTHESIS|SYMPTOM|EXCLUSION\",\"statement\":\"...\","
            + "\"evidence_refs\":[\"<valid_artifact_refs 之一>\"]}],"
            + "\"missing_information\":[\"...\"]}}\n"
            + "规则：args 形状严格遵守 tool_schemas 的 properties/required；evidence_refs"
            + " 只允许引用 valid_artifact_refs 中的 id；证据不足时用"
            + " HYPOTHESIS 并在 missing_information 写明缺口；每步只输出一个决策对象。";

    /** 主 Agent 受限直接取证口（§六 工具路径归既有受控面，X6 装配 ToolGateway 实现） */
    @FunctionalInterface
    public interface PrimaryToolPort {

        /** 一次只读取证 → 证据行 id（控制面拒绝/失败原样上抛，由运行器计步） */
        UUID invoke(SingleToolEvidenceAgent.CallContext ctx, String toolId,
                Map<String, Object> args);
    }

    private final RcaActionGuard guard;
    private final DeterministicSupervisor supervisor;
    private final PrimaryCheckpointRepository checkpoints;
    private final EvidenceRepository evidence;
    private final PrimaryToolPort toolPort;
    private final ObjectMapper mapper;
    private final Clock clock;

    public BoundedLlmRoleRunner(RcaActionGuard guard, DeterministicSupervisor supervisor,
            PrimaryCheckpointRepository checkpoints, EvidenceRepository evidence,
            PrimaryToolPort toolPort, ObjectMapper mapper, Clock clock) {
        this.guard = Objects.requireNonNull(guard, "guard");
        this.supervisor = Objects.requireNonNull(supervisor, "supervisor");
        this.checkpoints = Objects.requireNonNull(checkpoints, "checkpoints");
        this.evidence = Objects.requireNonNull(evidence, "evidence");
        this.toolPort = Objects.requireNonNull(toolPort, "toolPort");
        this.mapper = Objects.requireNonNull(mapper, "mapper");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @Override
    public String runtimeKind() {
        return com.objwww.pr.control.alert.domain.agent.RoleRuntimeKind.BOUNDED_LLM;
    }

    @Override
    public RoleRunner.RoleDriveResult drive(RoleRunner.RoleDriveRequest request) {
        PrimaryCheckpoint checkpoint = checkpoints.findByTask(request.task().id())
                .orElseThrow(() -> new IllegalStateException(
                        "主任务检查点缺失: " + request.task().id()));

        // WAITING_CHILDREN：不持锁等待，复判唤醒（批次结清条件驱动）
        if (checkpoint.phase() == PrimaryCheckpoint.Phase.WAITING_CHILDREN) {
            DeterministicSupervisor.WakeOutcome wake = supervisor.wakePrimary(
                    request.task().runId(), request.task().id());
            if (wake == DeterministicSupervisor.WakeOutcome.STILL_WAITING) {
                return new RoleRunner.RoleDriveResult(
                        RoleRunner.RoleDriveOutcome.WAITING_CHILDREN, List.of(),
                        "CHILDREN_UNSETTLED");
            }
            checkpoint = checkpoints.findByTask(request.task().id()).orElseThrow();
        }

        // 步数耗尽 → 确定性兜底 FINAL（零模型调用；§四 "流程终止≠根因确认"）
        if (checkpoint.stepsUsed() >= request.profile().maxSteps()) {
            return deterministicFinal(request, checkpoint);
        }

        RcaModelOutcome outcome = guard.guardedModelCall(
                actionOf(request, checkpoint), promptOf(request, checkpoint),
                MAX_TOKENS_PER_STEP, TOKEN_ESTIMATE_PER_STEP);

        PrimaryDecision decision;
        try {
            decision = PrimaryDecision.parse(
                    mapper.readValue(jsonOf(outcome.content()),
                            new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() { }));
        } catch (Exception e) {
            advanceStep(request, checkpoint, null,
                    "DECISION_UNPARSEABLE: 上一步输出不是合法决策 JSON。严格按协议输出"
                            + "恰一个纯 JSON 对象（tool_call/delegate/final 三形状选一），"
                            + "禁 markdown 围栏、禁思考过程、禁多余文字。");
            log.warn("主决策不可解析（{}），计步重驱 task={}",
                    e.getClass().getSimpleName(), request.task().id());
            return RoleRunner.RoleDriveResult.failed("DECISION_UNPARSEABLE");
        }
        return switch (decision.branch()) {
            case TOOL_CALL -> driveToolCall(request, checkpoint, decision);
            case DELEGATE -> driveDelegate(request, checkpoint, decision);
            case FINAL -> driveFinal(request, checkpoint, decision);
        };
    }

    // ------------------------------------------------------------------ 分支

    private RoleRunner.RoleDriveResult driveToolCall(RoleRunner.RoleDriveRequest request,
            PrimaryCheckpoint checkpoint, PrimaryDecision decision) {
        PrimaryDecision.ToolCall tool = decision.toolCall();
        if (!request.profile().toolAllowlist().contains(tool.toolId())) {
            advanceStep(request, checkpoint, null,
                    "TOOL_NOT_ALLOWED: 工具 " + tool.toolId()
                            + " 不在 tool_allowlist。只能从白名单工具中选择，"
                            + "按 tool_schemas 的形状重发 tool_call 或改走 final。");
            log.warn("TOOL_CALL 越权拒绝（不在 allowlist），计步重驱 task={} tool={}",
                    request.task().id(), tool.toolId());
            return RoleRunner.RoleDriveResult.failed("TOOL_NOT_ALLOWED");
        }
        UUID evidenceId;
        try {
            evidenceId = toolPort.invoke(request.callContext(), tool.toolId(), tool.args());
        } catch (com.objwww.pr.control.alert.domain.tool.ToolModelVisibleException e) {
            // 模型可见族（超时/限流/远端故障/零数据）：计步重驱，步数耗尽兜底保终止
            advanceStep(request, checkpoint, null,
                    "TOOL_FAILED " + e.reason().name() + ": 工具 " + tool.toolId()
                            + " 调用未成功。可修正查询（时间窗/service 过滤/指标名）后重试，"
                            + "或换用白名单内其他工具，或基于已有证据走 final。");
            log.warn("TOOL_CALL 模型可见失败（{}），计步重驱 task={} tool={}",
                    e.reason(), request.task().id(), tool.toolId());
            return RoleRunner.RoleDriveResult.failed(
                    "TOOL_RETRYABLE:" + e.reason().name());
        } catch (com.objwww.pr.control.alert.domain.tool.ToolControlPlaneException e) {
            // BA-112/BA-113：参数形状拒绝（INVALID_ARGS）对模型驱动环=计步重驱，reason
            // 归 TOOL_RETRYABLE:* 前缀族（NativeInvestigationExecutor 可重试封闭集只认
            // DECISION_UNPARSEABLE/TOOL_NOT_ALLOWED/TOOL_RETRYABLE:*，裸 TOOL_INVALID_ARGS
            // 会被当不可重试 → DEAD）；其余控制面终止族原样上抛降级 DEAD
            if (e.reason() == com.objwww.pr.control.alert.domain.tool.ToolControlReason
                    .INVALID_ARGS) {
                advanceStep(request, checkpoint, null,
                        "INVALID_ARGS: 工具 " + tool.toolId()
                                + " 的 args 未通过校验。严格对照 tool_schemas 里该工具的"
                                + " JSON Schema（字段名/类型/取值域）修正 args 后重发 tool_call。");
                log.warn("TOOL_CALL 参数形状拒绝（INVALID_ARGS），计步重驱 task={} tool={}",
                        request.task().id(), tool.toolId());
                return RoleRunner.RoleDriveResult.failed("TOOL_RETRYABLE:INVALID_ARGS");
            }
            throw e;
        }
        advanceStep(request, checkpoint, null, null);
        return new RoleRunner.RoleDriveResult(
                RoleRunner.RoleDriveOutcome.EVIDENCE_PRODUCED, List.of(evidenceId), null);
    }

    private RoleRunner.RoleDriveResult driveDelegate(RoleRunner.RoleDriveRequest request,
            PrimaryCheckpoint checkpoint, PrimaryDecision decision) {
        DeterministicSupervisor.Adjudication adjudication =
                supervisor.adjudicateDelegation(request.task().runId(),
                        request.task().id(), decision);
        if (adjudication.batchAccepted()) {
            return new RoleRunner.RoleDriveResult(
                    RoleRunner.RoleDriveOutcome.WAITING_CHILDREN, List.of(), null);
        }
        String codes = adjudication.decisions().stream()
                .filter(d -> d.status() == com.objwww.pr.control.alert.domain.agent
                        .DelegationDecision.Status.REJECTED)
                .map(d -> d.rejectReason() == null ? "REJECTED" : d.rejectReason())
                .distinct()
                .reduce((a, b) -> a + "," + b)
                .orElse("REJECTED");
        // 全拒不消耗步数（X4"状态不动"），但决策已出——决策序必须推进（动作身份单调）
        checkpoints.upsert(checkpoint.withDecisionAdvanced(clock.instant()));
        return new RoleRunner.RoleDriveResult(
                RoleRunner.RoleDriveOutcome.DELEGATE_REJECTED, List.of(), codes);
    }

    private RoleRunner.RoleDriveResult driveFinal(RoleRunner.RoleDriveRequest request,
            PrimaryCheckpoint checkpoint, PrimaryDecision decision) {
        PrimaryClaimAdmission.AdmissionResult admission = PrimaryClaimAdmission.admit(
                decision.finalAnswer().claims(), validRefsOf(request));
        List<Map<String, Object>> claimRows = new ArrayList<>();
        for (PrimaryClaimAdmission.AdmittedClaim claim : admission.claims()) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("claim_key", claim.claimKey());
            row.put("kind", claim.kind());
            row.put("statement", claim.statement());
            row.put("evidence_refs", claim.evidenceRefs());
            row.put("admission_note", claim.admissionNote());
            claimRows.add(row);
        }
        checkpoints.upsert(checkpoint.withFinal(claimRows,
                decision.finalAnswer().missingInformation(), clock.instant()));
        log.info("主 FINAL 提案落检查点 task={} claims={} downgraded={} stripped={}",
                request.task().id(), claimRows.size(), admission.downgraded(),
                admission.strippedRefs());
        return RoleRunner.RoleDriveResult.of(RoleRunner.RoleDriveOutcome.FINAL_READY);
    }

    // ------------------------------------------------------------------ 内部

    /** 步数耗尽的确定性兜底：空提案 + 缺口说明（已有事实由报告相位从工件面补集） */
    private RoleRunner.RoleDriveResult deterministicFinal(RoleRunner.RoleDriveRequest request,
            PrimaryCheckpoint checkpoint) {
        PrimaryClaimAdmission.AdmissionResult admission = PrimaryClaimAdmission.admit(
                List.of(), validRefsOf(request));
        checkpoints.upsert(checkpoint.withFinal(List.of(),
                List.of("STEPS_EXHAUSTED: max_steps=" + request.profile().maxSteps()
                        + " 已耗尽，按 §四 终止兜底以已有事实与缺口未决结束"), clock.instant()));
        log.warn("主任务步数耗尽 → 确定性未决 FINAL（零模型调用）task={} steps={}",
                request.task().id(), checkpoint.stepsUsed());
        return new RoleRunner.RoleDriveResult(RoleRunner.RoleDriveOutcome.FINAL_READY,
                List.of(), "STEPS_EXHAUSTED");
    }

    /**
     * 计步推进 + 反馈环（V88）：lastError = 本步结束后留给下一步模型的修正指引
     * （A0 八跑实证盲重驱=连猜同错；信封 last_error 面下发，成功步传 null 清空）
     */
    private void advanceStep(RoleRunner.RoleDriveRequest request,
            PrimaryCheckpoint checkpoint, String snapshotDigest, String lastError) {
        checkpoints.upsert(checkpoint.withStepAdvanced(snapshotDigest, lastError,
                clock.instant()));
    }

    /** §六 模型动作身份：绑定三元组 + 检查点计数（decision_seq 为账本动作序） */
    private RcaActionGuard.ModelAction actionOf(RoleRunner.RoleDriveRequest request,
            PrimaryCheckpoint checkpoint) {
        var task = request.task();
        var binding = request.binding();
        return new RcaActionGuard.ModelAction(task.runId(), task.id(),
                request.callContext().attemptId(), checkpoint.decisionSeq(),
                checkpoint.roundId(), binding.roleId(), binding.roleVersion(),
                binding.roleDigest(), request.callContext().observedGeneration(),
                task.leaseEpoch(), task.leaseUntil() != null ? task.leaseUntil()
                        : task.deadlineAt(),
                binding.configEpoch(), binding.releaseDigest(),
                checkpoint.inputSnapshotDigest(), () -> true);
    }

    /** 有界任务信封（§十一.3）：固定版本上下文+窗口+剩余步数+合法引用，不广播历史 */
    private String promptOf(RoleRunner.RoleDriveRequest request,
            PrimaryCheckpoint checkpoint) {
        try {
            Map<String, Object> envelope = new LinkedHashMap<>();
            envelope.put("role", request.profile().name() + "@" + request.profile().version());
            envelope.put("run_id", request.task().runId().toString());
            envelope.put("task_id", request.task().id().toString());
            envelope.put("round_id", checkpoint.roundId());
            envelope.put("steps_remaining",
                    Math.max(0, request.profile().maxSteps() - checkpoint.stepsUsed()));
            envelope.put("delegation_batches_remaining",
                    Math.max(0, DeterministicSupervisor.MAX_DELEGATION_BATCHES
                            - checkpoint.batchesUsed()));
            envelope.put("time_window", request.startEpoch() + "/" + request.endEpoch());
            envelope.put("tool_allowlist", request.profile().toolAllowlist().stream()
                    .sorted().toList());
            // BA-112：allowlist 工具的 args JSON Schema 钉版下发（Profile inputSchema
            // 进 digest，模型首发的参数形状依据；空=旧 Profile 无 schema 面）
            envelope.put("tool_schemas", request.profile().inputSchema());
            envelope.put("valid_artifact_refs", validRefsOf(request).stream().sorted()
                    .toList());
            // 反馈环（V88）：上一步可重试失败的原因与修正指引随信封回喂——A0 八跑
            // 实证盲重驱=模型连猜同错 4 次；成功步该面缺席（检查点已清空）
            if (checkpoint.lastError() != null) {
                envelope.put("last_error", checkpoint.lastError());
            }
            return request.profile().prompt() + "\n"
                    + mapper.writeValueAsString(envelope) + PROTOCOL_SUFFIX;
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalStateException("任务信封序列化失败", e);
        }
    }

    /**
     * 真实模型输出整形（BA-110）：去前后空白与 markdown 围栏（```json … ```）。
     * 不提取任意文本中的 JSON 片段——解析容忍面越宽注入面越大，围栏整形是上限。
     */
    static String jsonOf(String content) {
        String s = content == null ? "" : content.strip();
        if (s.startsWith("```")) {
            s = s.replaceFirst("^```[a-zA-Z0-9]*\\s*", "");
            int tail = s.lastIndexOf("```");
            if (tail >= 0) {
                s = s.substring(0, tail);
            }
            s = s.strip();
        }
        return s;
    }

    /** 本 run 合法引用全集（X5 准入面）：已准入证据行 id + 绑定编译期 artifact 键 */
    private Set<String> validRefsOf(RoleRunner.RoleDriveRequest request) {
        Set<String> refs = new LinkedHashSet<>(request.binding().inputRefs());
        evidence.findByRunId(request.task().runId())
                .forEach(e -> refs.add(e.evidenceId().toString()));
        return refs;
    }
}
