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
 *       全拒 → 拒绝码+修正指引写检查点 lastError 回喂（BA-119），有界继续；</li>
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

    /** R5 同签名熔断签名前缀（与模型可见反馈文本同槽共存，按前缀+值精确比对） */
    static final String MODEL_FAILURE_SIGNATURE_PREFIX = "MODEL_FAILURE_SIGNATURE:";

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
            + " HYPOTHESIS 并在 missing_information 写明缺口；每步只输出一个决策对象；"
            + "委派=按冻结时间窗+input_refs 的固定查询专家（确定性执行，不接受自由文本"
            + " 指令；question 仅入台账审计，不进子任务执行面）。";

    /** R3/MC 一致性断言面：委派请求的模型可见字段集（与 PrimaryDecision.DelegateRequest
     * 分量集、supervisor 透传面三方可账；协议文案漂移=新决策字段无协议描述） */
    static final List<String> DELEGATE_REQUEST_FIELDS =
            List.of("gap_id", "role_id", "question", "input_refs", "scope",
                    "requested_budget");

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
    private final ContextAssembler assembler;
    private final PrimaryToolPort toolPort;
    private final ObjectMapper mapper;
    private final Clock clock;
    /** R11/MA-04 一步边界压缩（可空=null 零压缩姿态，既有装配零行为漂移） */
    private final ContextCompactionService compaction;

    public BoundedLlmRoleRunner(RcaActionGuard guard, DeterministicSupervisor supervisor,
            PrimaryCheckpointRepository checkpoints, EvidenceRepository evidence,
            ContextAssembler assembler, PrimaryToolPort toolPort, ObjectMapper mapper,
            Clock clock) {
        this(guard, supervisor, checkpoints, evidence, assembler, toolPort, mapper,
                clock, null);
    }

    public BoundedLlmRoleRunner(RcaActionGuard guard, DeterministicSupervisor supervisor,
            PrimaryCheckpointRepository checkpoints, EvidenceRepository evidence,
            ContextAssembler assembler, PrimaryToolPort toolPort, ObjectMapper mapper,
            Clock clock, ContextCompactionService compaction) {
        this.guard = Objects.requireNonNull(guard, "guard");
        this.supervisor = Objects.requireNonNull(supervisor, "supervisor");
        this.checkpoints = Objects.requireNonNull(checkpoints, "checkpoints");
        this.evidence = Objects.requireNonNull(evidence, "evidence");
        this.assembler = Objects.requireNonNull(assembler, "assembler");
        this.toolPort = Objects.requireNonNull(toolPort, "toolPort");
        this.mapper = Objects.requireNonNull(mapper, "mapper");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.compaction = compaction;
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

        // 信封装配委托 ContextAssembler（R1 am4-envelope.v2：真实内容入模）；
        // snapshotDigest = 稳定面（不含 last_error 反馈）——R5 签名键 + 快照回填共用
        int batchesRemaining = Math.max(0, supervisor.maxDelegationBatches()
                - checkpoint.batchesUsed());
        ContextAssembler.Assembly assembly =
                assembler.assemble(request, checkpoint, batchesRemaining);
        maybeCompact(request, checkpoint, assembly);
        RcaModelOutcome outcome;
        try {
            outcome = guard.guardedModelCall(actionOf(request, checkpoint), assembly.prompt(),
                    MAX_TOKENS_PER_STEP, TOKEN_ESTIMATE_PER_STEP);
        } catch (com.objwww.pr.control.alert.domain.agent.RcaModelCallException e) {
            // R5 同签名熔断（BA-120/MC25）：模型面终态失败（零触网栅栏/步级可重试除外）
            // 同 (errorCode+稳定信封) 连续第 2 次 → 确定性未决收敛，不再同参重发
            if (!e.zeroNetwork() && !e.retryable()) {
                String signature = e.errorCode() + "|" + assembly.snapshotDigest();
                if (isSameFailureSignature(checkpoint.lastError(), signature)) {
                    return modelFailureFinal(request, checkpoint, e.errorCode());
                }
                checkpoints.upsert(checkpoint.withLastError(
                        MODEL_FAILURE_SIGNATURE_PREFIX + signature, clock.instant()));
            }
            throw e;
        }

        PrimaryDecision decision;
        try {
            decision = PrimaryDecision.parse(
                    mapper.readValue(jsonOf(outcome.content()),
                            new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() { }));
        } catch (Exception e) {
            advanceStep(request, checkpoint, assembly.snapshotDigest(), assembly.memory(),
                    "DECISION_UNPARSEABLE: 上一步输出不是合法决策 JSON。严格按协议输出"
                            + "恰一个纯 JSON 对象（tool_call/delegate/final 三形状选一），"
                            + "禁 markdown 围栏、禁思考过程、禁多余文字。");
            log.warn("主决策不可解析（{}），计步重驱 task={}",
                    e.getClass().getSimpleName(), request.task().id());
            return RoleRunner.RoleDriveResult.failed("DECISION_UNPARSEABLE");
        }
        return switch (decision.branch()) {
            case TOOL_CALL -> driveToolCall(request, checkpoint, decision,
                    assembly.snapshotDigest(), assembly.memory());
            case DELEGATE -> driveDelegate(request, checkpoint, decision);
            case FINAL -> driveFinal(request, checkpoint, decision);
        };
    }

    // ------------------------------------------------------------------ 分支

    private RoleRunner.RoleDriveResult driveToolCall(RoleRunner.RoleDriveRequest request,
            PrimaryCheckpoint checkpoint, PrimaryDecision decision, String stableDigest,
            com.objwww.pr.control.alert.domain.agent.WorkingMemory memory) {
        PrimaryDecision.ToolCall tool = decision.toolCall();
        if (!request.profile().toolAllowlist().contains(tool.toolId())) {
            advanceStep(request, checkpoint, stableDigest, memory,
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
            advanceStep(request, checkpoint, stableDigest, memory,
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
                advanceStep(request, checkpoint, stableDigest, memory,
                        "INVALID_ARGS: 工具 " + tool.toolId()
                                + " 的 args 未通过校验。严格对照 tool_schemas 里该工具的"
                                + " JSON Schema（字段名/类型/取值域）修正 args 后重发 tool_call。");
                log.warn("TOOL_CALL 参数形状拒绝（INVALID_ARGS），计步重驱 task={} tool={}",
                        request.task().id(), tool.toolId());
                return RoleRunner.RoleDriveResult.failed("TOOL_RETRYABLE:INVALID_ARGS");
            }
            throw e;
        }
        advanceStep(request, checkpoint, stableDigest, memory, null);
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
        // 全拒不消耗步数（X4"状态不动"），但决策已出——决策序必须推进（动作身份单调）；
        // BA-119 反馈环扩面：全拒原因+修正指引写 lastError 随下步信封回喂（否则模型
        // 拿不到"该 gap 已有台账/配额耗尽"的裁决事实，盲重提同一 gap 烧尽驱动上限）
        checkpoints.upsert(checkpoint.withDecisionAdvanced(
                "DELEGATE_REJECTED: 委派批全拒（" + codes + "）。"
                        + "GAP_ALREADY_ADJUDICATED=该 gap 已有台账行，勿换汤不换药重提同一 gap；"
                        + "DELEGATION_BUDGET_EXHAUSTED=委派配额耗尽，不可再委派。"
                        + "改用 tool_allowlist 内工具直查补证，或基于已有证据走 final"
                        + "（缺口如实写 missing_information）。",
                clock.instant()));
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
     * R11/MA-04 一步边界压缩（工具结果入库后、下一模型发送前，§19.3 触发时点）。
     * 默认关（compaction=null 或 enabled=false 零开销）；任何结果都不改变本步输入
     * ——已装配信封按确定性有界材料照发，压缩产物只落档供消费面（MC34 后启用）。
     * 异常不打断主路径（压缩是优化，有界回退=按原材料继续）。
     */
    private void maybeCompact(RoleRunner.RoleDriveRequest request,
            PrimaryCheckpoint checkpoint, ContextAssembler.Assembly assembly) {
        if (compaction == null) {
            return;
        }
        try {
            ContextCompactionService.CompactionOutcome outcome =
                    compaction.afterToolResults(request, checkpoint, assembly);
            if (outcome.committed()) {
                log.info("R11 压缩已提交 task={} summaryId={} token {}→{}",
                        request.task().id(), outcome.summary().id(),
                        outcome.summary().tokenBefore(), outcome.summary().tokenAfter());
            }
        } catch (RuntimeException e) {
            log.warn("R11 压缩边界异常（不打断主路径）task={} {}",
                    request.task().id(), e.getClass().getSimpleName());
        }
    }

    /**
     * 计步推进 + 反馈环（V88）：lastError = 本步结束后留给下一步模型的修正指引
     * （A0 八跑实证盲重驱=连猜同错；信封 last_error 面下发，成功步传 null 清空）。
     * R10：本步所用工作记忆快照随检查点钉面（memory_id/digest），DECISION_UNPARSEABLE
     * 重驱读同快照不另生成（MC07）。
     */    private void advanceStep(RoleRunner.RoleDriveRequest request,
            PrimaryCheckpoint checkpoint, String snapshotDigest,
            com.objwww.pr.control.alert.domain.agent.WorkingMemory memory,
            String lastError) {
        checkpoints.upsert(checkpoint.withStepAdvanced(snapshotDigest,
                memory == null ? null : memory.id(),
                memory == null ? null : memory.memoryDigest(),
                lastError, clock.instant()));
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

    /** R5：lastError 是否本签名的前次落痕（前缀识别——反馈文本与机器签名同槽共存） */
    private static boolean isSameFailureSignature(String lastError, String signature) {
        return lastError != null && lastError.startsWith(MODEL_FAILURE_SIGNATURE_PREFIX)
                && lastError.substring(MODEL_FAILURE_SIGNATURE_PREFIX.length()).equals(signature);
    }

    /**
     * R5 同签名熔断收敛：同 (errorCode+稳定信封) 连续第 2 次失败 → 确定性未决 FINAL
     * （零模型调用；"流程终止≠根因确认"，缺口如实入 missing_information）。
     */
    private RoleRunner.RoleDriveResult modelFailureFinal(RoleRunner.RoleDriveRequest request,
            PrimaryCheckpoint checkpoint, String errorCode) {
        checkpoints.upsert(checkpoint.withFinal(List.of(),
                List.of("MODEL_FAILURE_UNRESOLVED: 模型调用同签名连续失败（code=" + errorCode
                        + "，同参重发必然同败），按 §四 终止兜底以已有事实与缺口未决结束；"
                        + "已有事实由报告相位从工件面补集"),
                clock.instant()));
        log.warn("主任务同签名模型失败 ×2 → 确定性未决 FINAL（零模型调用）task={} code={}",
                request.task().id(), errorCode);
        return new RoleRunner.RoleDriveResult(RoleRunner.RoleDriveOutcome.FINAL_READY,
                List.of(), "MODEL_FAILURE_" + errorCode);
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
