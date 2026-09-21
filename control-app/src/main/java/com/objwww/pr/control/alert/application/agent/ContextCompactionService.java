package com.objwww.pr.control.alert.application.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.objwww.pr.control.alert.domain.agent.ContextSummary;
import com.objwww.pr.control.alert.domain.agent.PrimaryCheckpoint;
import com.objwww.pr.control.alert.domain.agent.RcaModelCallException;
import com.objwww.pr.control.alert.domain.agent.RcaModelOutcome;
import com.objwww.pr.control.alert.domain.evidence.EvidenceRepository;
import com.objwww.pr.control.alert.domain.repository.ContextSummaryPort;
import com.objwww.pr.control.alert.domain.repository.PrimaryCheckpointRepository;
import com.objwww.pr.shared.Digest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * LLM 压缩生命周期服务（R11/MA-04，R7 方案 §19.3~19.5）。默认关
 * （{@code app.alert.r7.compaction.enabled=false}）——放量前提是 MC34 三臂对照
 * 证明收益，否则只保留确定性裁剪（R1 界面，恒为第一刀，本服务永远在其之后）。
 *
 * <p>一步边界（工具结果入库后、下一模型发送前）依次执行：
 * <ol>
 *   <li>enabled/软阈值/次数上限/快照锚/同源幂等五道零模型调用闸；</li>
 *   <li>冻结区间 = [上一摘要 event_seq_to+1, 本步 decision_seq]（MC16：摘要只覆盖
 *       冻结窗，增量由后续摘要新区间承载，禁混 digest）；</li>
 *   <li>required_refs 宿主生成（绑定 inputRefs ∪ 检查点终局 evidence_refs），
 *       摘要模型不得删空（§19.4 L444）；</li>
 *   <li>受守卫的 COMPACTION 模型调用（生产装配 = {@code RcaActionGuard::guardedModelCall}
 *       ——预算预留/账本/epoch 栅栏同律；动作序占保留段 ≥10^6，与决策序不撞唯一键）；</li>
 *   <li>候选校验：{summary, refs} JSON / 引用存在性（越界剥离）/ 必需引用全覆盖
 *       （MC13 缺失即整候选拒绝）/ 有节省（MC15 无节省拒绝）；</li>
 *   <li>提交前复验（MC17）：检查点自冻结后推进/漂移 → SUPERSEDED 不落行
 *       （费用已由守卫落 rca_model_call，审计面不丢）；</li>
 *   <li>CAS 提交：uq(run, task, source) 冲突返回既有行（MC18 并发同源双写一胜一拒）。</li>
 * </ol>
 * 失败恢复：任何拒绝/失败一律丢弃候选保留原快照，主路径按既有确定性有界材料继续
 * （有界回退，无内联重试、无"摘要→摘要"递归——源锚恒为原始快照 digest）。
 * CL-07 起每次生成先经 V102 rca_compaction_attempt 预留（冻结来源/策略/执行身份，
 * 终态封闭不删行）；SHADOW_GENERATE 只留档不换输入，CONSUME_VALIDATED 才经 CL-01
 * 围栏更新 checkpoint.current_summary_id（信封消费面见 CL-08）。全量旧正文替换
 * 消费待 MC34 三臂对照证明收益后启用。
 */
public class ContextCompactionService {

    private static final Logger log = LoggerFactory.getLogger(ContextCompactionService.class);

    /** COMPACTION 动作序保留段基线（决策序首期为两位数量级；≥10^6 不撞账本唯一键；
     * 无独立 purpose 列，保留段即用途标记——偏差登记执行日志） */
    public static final long COMPACTION_ACTION_SEQ_BASE = 1_000_000L;

    /** 摘要 schema 钉版（v1：候选 = {"summary","refs"}） */
    public static final int SCHEMA_VERSION = 1;

    /** 摘要输出 max_tokens（有界输出；首期静态值，待 MC34 调参） */
    static final int SUMMARY_MAX_TOKENS = 2048;

    /** 摘要输出协议（候选 = {"summary","refs"}；compactionPrompt 与资产钉版同源） */
    static final String OUTPUT_PROTOCOL = "恰一个 JSON 对象：{\"summary\":\"<压缩后的调查"
            + "上下文。recall-first：先保全 required_refs 相关的事实/反证/未决缺口/"
            + "已做动作与停止条件，不得为达到压缩比移除反证；再精简其余>\","
            + "\"refs\":[<summary 保留项引用的证据 id：必须覆盖 required_refs，"
            + "且只能引用待压缩上下文中出现过的 id>]}";

    /** token 保守估算分母（与 ContextAssembler/RcaModelGateway 同一口径） */
    static final int CHARS_PER_TOKEN = 2;

    /** 封闭结果面：五道零调用闸 + 模型/校验失败族 + SUPERSEDED + COMMITTED */
    public enum OutcomeKind {
        DISABLED, BELOW_THRESHOLD, LIMIT_REACHED, NO_SOURCE, ALREADY_COMPACTED,
        MODEL_FAILED, REJECTED_UNPARSEABLE, REJECTED_MISSING_REQUIRED,
        REJECTED_NO_SAVINGS, SUPERSEDED, COMMITTED
    }

    /**
     * CL-07 三模式：OFF 关闭；SHADOW_GENERATE 只生成留档不换输入（旧 enabled=true
     * 的全部语义）；CONSUME_VALIDATED 生成后经围栏把 current_summary_id 钉上检查点
     * ——默认仍关，放量前提 MC34 三臂对照（OFF/确定性/消费）证明收益。
     */
    public enum Mode { OFF, SHADOW_GENERATE, CONSUME_VALIDATED }

    /**
     * CL-07 消费口（生产装配 = CL-01 提交围栏 SUMMARY_CONSUMED 条件写）：把已提交
     * 摘要钉为检查点消费指针。返回 false=围栏拒绝（失租/revision 漂移），调用方
     * 保留旧指针不打断主路径。
     */
    @FunctionalInterface
    public interface SummaryConsumer {

        boolean consume(UUID runId, UUID taskId, String owner, long leaseEpoch,
                Long configEpoch, long expectedRevision, String actionKey,
                UUID summaryId);
    }

    /** 一步边界结果：kind + 已提交摘要（仅 COMMITTED 非 null）+ 机器可读细节
     * + 消费观测（仅 COMMITTED 非 null） */
    public record CompactionOutcome(OutcomeKind kind, ContextSummary summary,
            String detail, ConsumptionObservation consumption) {

        /** 非提交路径兼容形（无消费观测） */
        public CompactionOutcome(OutcomeKind kind, ContextSummary summary, String detail) {
            this(kind, summary, detail, null);
        }

        public boolean committed() {
            return kind == OutcomeKind.COMMITTED;
        }
    }

    /**
     * D06（REPORT 步骤 6/7）观测面：记录实际被消费的内容与策略——OFF/
     * SHADOW_GENERATE/CONSUME_VALIDATED 是运行模式不是实验臂：consumerInvoked=
     * false（OFF/SHADOW）即"只生成留档不换输入"；consumed=false（CONSUME 围栏
     * 拒绝 KEPT_OLD_POINTER）的候选不得计入"摘要消费后效果"，原路径继续、成本
     * 仍计（CTX-12）。policyDigest 与 V102 台账策略指纹同源。tokenBefore/After
     * 是 summaryText.length()/2 近似（CHARS_PER_TOKEN），不能证明模型总输入
     * 下降——真实发送面 token 统计归后续专项。
     */
    public record ConsumptionObservation(String mode, boolean consumerInvoked,
            Boolean consumed, String policyDigest) {
    }

    /**
     * 压缩模型调用口（生产装配 = {@code RcaActionGuard::guardedModelCall}——
     * Run 活跃/generation/租约/deadline/角色五栅栏 + 预算预留 + 账本审计同律）。
     */
    @FunctionalInterface
    public interface CompactionModelPort {

        RcaModelOutcome call(RcaActionGuard.ModelAction action, String prompt,
                int maxTokens) throws RcaModelCallException;
    }

    private final CompactionModelPort model;
    private final ContextSummaryPort summaries;
    private final PrimaryCheckpointRepository checkpoints;
    private final EvidenceRepository evidence;
    private final ObjectMapper mapper;
    private final Clock clock;
    /** CL-07 三模式（旧 enabled 语义：true=SHADOW_GENERATE，false=OFF） */
    private final Mode mode;
    /** CL-07 尝试台账（可空=无持久面装配，零台账零语义漂移） */
    private final com.objwww.pr.control.alert.domain.repository.CompactionAttemptPort attempts;
    /** CL-07 消费口（仅 CONSUME_VALIDATED 调用；可空=只生成不消费） */
    private final SummaryConsumer consumer;
    private final double softThreshold;
    private final double targetRatio;
    private final int maxPerRun;
    private final int maxInputTokens;

    /** 旧构造（enabled 布尔映射 SHADOW_GENERATE/OFF）：既有装配/测试零改动 */
    public ContextCompactionService(CompactionModelPort model, ContextSummaryPort summaries,
            PrimaryCheckpointRepository checkpoints, EvidenceRepository evidence,
            ObjectMapper mapper, Clock clock, boolean enabled, double softThreshold,
            double targetRatio, int maxPerRun, int maxInputTokens) {
        this(model, summaries, checkpoints, evidence, mapper, clock,
                enabled ? Mode.SHADOW_GENERATE : Mode.OFF, softThreshold,
                targetRatio, maxPerRun, maxInputTokens, null, null);
    }

    /** CL-07 全参构造：模式 + V102 尝试台账 + 消费口（围栏化 current_summary_id） */
    public ContextCompactionService(CompactionModelPort model, ContextSummaryPort summaries,
            PrimaryCheckpointRepository checkpoints, EvidenceRepository evidence,
            ObjectMapper mapper, Clock clock, Mode mode, double softThreshold,
            double targetRatio, int maxPerRun, int maxInputTokens,
            com.objwww.pr.control.alert.domain.repository.CompactionAttemptPort attempts,
            SummaryConsumer consumer) {
        this.model = Objects.requireNonNull(model, "model");
        this.summaries = Objects.requireNonNull(summaries, "summaries");
        this.checkpoints = Objects.requireNonNull(checkpoints, "checkpoints");
        this.evidence = Objects.requireNonNull(evidence, "evidence");
        this.mapper = Objects.requireNonNull(mapper, "mapper");
        this.clock = Objects.requireNonNull(clock, "clock");
        if (softThreshold <= 0 || softThreshold > 1) {
            throw new IllegalArgumentException("compaction.soft-threshold 须在 (0,1]: "
                    + softThreshold);
        }
        if (targetRatio <= 0 || targetRatio >= 1) {
            throw new IllegalArgumentException("compaction.target-ratio 须在 (0,1): "
                    + targetRatio);
        }
        if (maxPerRun <= 0) {
            throw new IllegalArgumentException("compaction.max-per-run 须为正: " + maxPerRun);
        }
        if (maxInputTokens <= 0) {
            throw new IllegalArgumentException("max-input-tokens 须为正: " + maxInputTokens);
        }
        this.mode = Objects.requireNonNull(mode, "mode");
        this.softThreshold = softThreshold;
        this.targetRatio = targetRatio;
        this.maxPerRun = maxPerRun;
        this.maxInputTokens = maxInputTokens;
        this.attempts = attempts;
        this.consumer = consumer;
    }

    /**
     * 一步边界调用（工具结果入库后、下一模型发送前）。永不抛出业务异常打断主路径
     * ——模型/守卫失败按封闭结果面返回，由调用方记日志后按原材料继续。
     */
    public CompactionOutcome afterToolResults(RoleRunner.RoleDriveRequest request,
            PrimaryCheckpoint checkpoint, ContextAssembler.Assembly assembly) {
        if (mode == Mode.OFF) {
            return new CompactionOutcome(OutcomeKind.DISABLED, null,
                    "compaction mode=OFF（放量前提 MC34 三臂对照）");
        }
        int softLine = (int) Math.ceil(softThreshold * maxInputTokens);
        if (assembly.approxTokens() < softLine) {
            return new CompactionOutcome(OutcomeKind.BELOW_THRESHOLD, null,
                    "approx=" + assembly.approxTokens() + " < soft=" + softLine);
        }
        UUID runId = request.task().runId();
        UUID taskId = request.task().id();
        long used = summaries.countByRun(runId);
        if (used >= maxPerRun) {
            // MC19：拒绝新增摘要调用；Run 预算与 steps 不动（本服务从不写检查点计数）
            return new CompactionOutcome(OutcomeKind.LIMIT_REACHED, null,
                    "run 已压缩 " + used + " 次 >= 上限 " + maxPerRun);
        }
        String source = checkpoint.inputSnapshotDigest();
        if (source == null || source.isBlank()) {
            return new CompactionOutcome(OutcomeKind.NO_SOURCE, null,
                    "检查点无快照锚（首步前未冻结）");
        }
        if (summaries.findBySource(runId, taskId, source).isPresent()) {
            return new CompactionOutcome(OutcomeKind.ALREADY_COMPACTED, null, source);
        }
        // CL-07 §6.4-2：短事务预留尝试资格——外部调用前冻结来源/策略/四类执行身份
        // （V102 台账）；同逻辑动作并发预留一胜一拒，败者读胜者行收敛（首期一动作
        // 一物理尝试；崩溃残留 IN_FLIGHT 行即对账面，不静默删除）
        java.util.UUID wishId = UUID.randomUUID();
        com.objwww.pr.control.alert.domain.agent.CompactionAttempt attempt = null;
        if (attempts != null) {
            com.objwww.pr.control.alert.domain.agent.CompactionAttempt stored =
                    attempts.insertIfAbsent(
                            com.objwww.pr.control.alert.domain.agent.CompactionAttempt
                                    .reserve(wishId, runId, taskId, source,
                                            policyDigest(), request.task().leaseOwner(),
                                            request.task().leaseEpoch(),
                                            request.binding().configEpoch(),
                                            checkpoint.revision(),
                                            "compaction:" + taskId + ":" + source,
                                            clock.instant()));
            if (!stored.id().equals(wishId)) {
                return new CompactionOutcome(OutcomeKind.ALREADY_COMPACTED, null,
                        "attempt=" + stored.id() + " state=" + stored.state());
            }
            attempts.casState(stored.id(),
                    com.objwww.pr.control.alert.domain.agent.CompactionAttempt.RESERVED,
                    com.objwww.pr.control.alert.domain.agent.CompactionAttempt.IN_FLIGHT,
                    null, null);
            attempt = stored;
        }
        long eventSeqFrom = summaries.latestByTask(runId, taskId)
                .map(s -> s.eventSeqTo() + 1).orElse(0L);
        long eventSeqTo = checkpoint.decisionSeq();

        // 引用值域全集 + 必需集（宿主生成：绑定承诺 ∪ 终局引用 ∪ 工作记忆反证；模型不得删空）
        Set<String> validRefs = new LinkedHashSet<>(request.binding().inputRefs());
        evidence.findByRunId(runId).forEach(e -> validRefs.add(e.evidenceId().toString()));
        Set<String> requiredRefs = new LinkedHashSet<>(request.binding().inputRefs());
        for (Map<String, Object> claim : checkpoint.finalClaims()) {
            Object refs = claim.get("evidence_refs");
            if (refs instanceof List<?> list) {
                list.forEach(r -> requiredRefs.add(String.valueOf(r)));
            }
        }
        // MC22 反证保留（消费面）：工作记忆累计反证也是必需引用——摘要候选漏反证
        // 等于把"已推翻方向"从模型视野删掉，与"早期反证不被挤掉"同律
        if (assembly.memory() != null) {
            requiredRefs.addAll(assembly.memory().slots()
                    .getOrDefault("counter_evidence_refs", List.of()));
        }

        String prompt = compactionPrompt(source, eventSeqFrom, eventSeqTo,
                requiredRefs, assembly.prompt());
        long ordinal = summaries.countByTask(runId, taskId) + 1;
        RcaActionGuard.ModelAction action = new RcaActionGuard.ModelAction(
                runId, taskId, request.callContext().attemptId(),
                COMPACTION_ACTION_SEQ_BASE + ordinal, checkpoint.roundId(),
                request.binding().roleId(), request.binding().roleVersion(),
                request.binding().roleDigest(),
                request.callContext().observedGeneration(),
                request.task().leaseEpoch(),
                request.task().leaseUntil() != null ? request.task().leaseUntil()
                        : request.task().deadlineAt(),
                request.binding().configEpoch(), request.binding().releaseDigest(),
                source, com.objwww.pr.control.alert.application.ExecutionControl.aliveHeartbeat(
                        request.callContext().controlSignal()));

        RcaModelOutcome outcome;
        try {
            outcome = model.call(action, prompt, SUMMARY_MAX_TOKENS);
        } catch (RcaModelCallException e) {
            // MC15：有界回退——候选丢弃保留原快照，确定性选材继续；无内联重试
            settle(attempt, com.objwww.pr.control.alert.domain.agent.CompactionAttempt.FAILED,
                    e.errorCode());
            return new CompactionOutcome(OutcomeKind.MODEL_FAILED, null, e.errorCode());
        }

        JsonNode candidate = parseCandidate(outcome.content());
        if (candidate == null || !candidate.hasNonNull("summary")
                || !candidate.get("summary").isTextual()) {
            settle(attempt, com.objwww.pr.control.alert.domain.agent.CompactionAttempt.REJECTED,
                    OutcomeKind.REJECTED_UNPARSEABLE.name());
            return new CompactionOutcome(OutcomeKind.REJECTED_UNPARSEABLE, null,
                    "候选不是 {\"summary\",\"refs\"} JSON 形状");
        }
        String summaryText = candidate.get("summary").asText();
        Set<String> refs = new LinkedHashSet<>();
        if (candidate.has("refs") && candidate.get("refs").isArray()) {
            candidate.get("refs").forEach(n -> refs.add(n.asText()));
        }
        // 引用存在性代码可检：越界引用剥离（X5 准入同律）；必需引用缺失=整候选拒绝（MC13）
        refs.retainAll(validRefs);
        if (!refs.containsAll(requiredRefs)) {
            Set<String> missing = new LinkedHashSet<>(requiredRefs);
            missing.removeAll(refs);
            settle(attempt, com.objwww.pr.control.alert.domain.agent.CompactionAttempt.REJECTED,
                    OutcomeKind.REJECTED_MISSING_REQUIRED.name());
            return new CompactionOutcome(OutcomeKind.REJECTED_MISSING_REQUIRED, null,
                    "必需引用缺失: " + missing);
        }
        int tokenAfter = summaryText.length() / CHARS_PER_TOKEN + 1;
        if (tokenAfter >= assembly.approxTokens()) {
            // MC15：没有节省 → 丢弃候选保留原快照（不无限重压缩）
            settle(attempt, com.objwww.pr.control.alert.domain.agent.CompactionAttempt.REJECTED,
                    OutcomeKind.REJECTED_NO_SAVINGS.name());
            return new CompactionOutcome(OutcomeKind.REJECTED_NO_SAVINGS, null,
                    "tokenAfter=" + tokenAfter + " >= tokenBefore="
                            + assembly.approxTokens());
        }

        // MC17：提交前复验——检查点自冻结后推进/漂移 → 冻结区间失效，候选作废
        //（模型费用已由守卫落 rca_model_call，审计面不丢）
        PrimaryCheckpoint fresh = checkpoints.findByTask(taskId).orElse(null);
        if (fresh == null || fresh.decisionSeq() != eventSeqTo
                || !source.equals(fresh.inputSnapshotDigest())) {
            settle(attempt, com.objwww.pr.control.alert.domain.agent.CompactionAttempt.SUPERSEDED,
                    OutcomeKind.SUPERSEDED.name());
            return new CompactionOutcome(OutcomeKind.SUPERSEDED, null,
                    "检查点已推进/漂移，冻结区间失效");
        }

        Set<String> omitted = new LinkedHashSet<>(validRefs);
        omitted.removeAll(refs);
        ContextSummary row = ContextSummary.of(UUID.randomUUID(), runId, taskId,
                SCHEMA_VERSION, source, eventSeqFrom, eventSeqTo,
                Digest.sha256Of(prompt).value(),
                outcome.actualModel() == null ? "unknown" : outcome.actualModel(),
                assembly.approxTokens(), tokenAfter, List.copyOf(requiredRefs),
                List.copyOf(omitted), summaryText, "REFS_VALIDATED",
                "llm:" + (outcome.routeId() == null ? "unknown" : outcome.routeId()),
                request.binding().configEpoch(), clock.instant());
        ContextSummary committed = summaries.append(row);
        settleCommitted(attempt, committed.id());
        log.info("上下文压缩提交 run={} task={} source={} 区间=[{},{}] token {}→{} "
                        + "required={} omitted={}", runId, taskId, source,
                eventSeqFrom, eventSeqTo, row.tokenBefore(), row.tokenAfter(),
                requiredRefs.size(), omitted.size());
        ConsumptionObservation consumption = consumeIfValidated(request, checkpoint,
                committed);
        return new CompactionOutcome(OutcomeKind.COMMITTED, committed, null, consumption);
    }

    /** Per-run opt-in variant. Existing compaction mode and disabled-run behavior remain unchanged. */
    public ContextCompactionService forJevRun() {
        return new ContextCompactionService(model, summaries, checkpoints, evidence, mapper, clock,
                Mode.CONSUME_VALIDATED, 0.01, targetRatio, Math.min(2,maxPerRun), maxInputTokens,
                attempts, consumer);
    }

    /**
     * CL-07 §6.4-4 消费面（CONSUME_VALIDATED 才走）：经 CL-01 提交围栏把
     * current_summary_id 钉上检查点（SUMMARY_CONSUMED，零推进字段）。围栏拒绝
     * （失租/revision 漂移/REPLAYED 收敛）一律保留旧指针不打断主路径——§6.4
     * "候选超时/失租/无收益均保留旧指针"。全量旧正文替换消费仍待 MC34 三臂对照。
     */
    private ConsumptionObservation consumeIfValidated(RoleRunner.RoleDriveRequest request,
            PrimaryCheckpoint checkpoint, ContextSummary committed) {
        if (mode != Mode.CONSUME_VALIDATED || consumer == null) {
            // OFF/SHADOW_GENERATE：只生成留档不换输入（观测面如实记录未消费）
            return new ConsumptionObservation(mode.name(), false, null, policyDigest());
        }
        String actionKey = "summary-consumed:" + committed.id();
        boolean consumed = consumer.consume(committed.runId(), committed.taskId(),
                request.task().leaseOwner(), request.task().leaseEpoch(),
                request.binding().configEpoch(), checkpoint.revision(),
                actionKey, committed.id());
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("run_id", String.valueOf(committed.runId()));
        fields.put("task_id", String.valueOf(committed.taskId()));
        fields.put("summary_id", String.valueOf(committed.id()));
        fields.put("result", consumed ? "CONSUMED" : "KEPT_OLD_POINTER");
        com.objwww.pr.control.infrastructure.observability.StructuredLog.event(log,
                "COMPACTION_CONSUME", fields);
        if (!consumed) {
            log.warn("摘要消费围栏拒绝（保留旧指针）run={} task={} summary={} action={}",
                    committed.runId(), committed.taskId(), committed.id(), actionKey);
        }
        return new ConsumptionObservation(mode.name(), true, consumed, policyDigest());
    }

    /** 台账终态化（无台账装配零副作用；CAS 败=他人已终态化，留 warn 不覆盖） */
    private void settle(com.objwww.pr.control.alert.domain.agent.CompactionAttempt attempt,
            String toState, String errorCode) {
        if (attempt == null || attempts == null) {
            return;
        }
        if (!attempts.casState(attempt.id(),
                com.objwww.pr.control.alert.domain.agent.CompactionAttempt.IN_FLIGHT,
                toState, errorCode, null)) {
            log.warn("attempt 终态 CAS 失败（他人已终态化）id={} 目标={}",
                    attempt.id(), toState);
        }
    }

    /** COMMITTED 终态化（携带 summary_id；errorCode 恒空） */
    private void settleCommitted(
            com.objwww.pr.control.alert.domain.agent.CompactionAttempt attempt,
            java.util.UUID summaryId) {
        if (attempt == null || attempts == null) {
            return;
        }
        if (!attempts.casState(attempt.id(),
                com.objwww.pr.control.alert.domain.agent.CompactionAttempt.IN_FLIGHT,
                com.objwww.pr.control.alert.domain.agent.CompactionAttempt.COMMITTED,
                null, summaryId)) {
            log.warn("attempt 终态 CAS 失败（他人已终态化）id={} 目标=COMMITTED",
                    attempt.id());
        }
    }

    /** 策略指纹（V102 policy_digest）：模式旋钮+协议的确定性摘要，变旋钮即新逻辑动作 */
    private String policyDigest() {
        return Digest.sha256Of("compaction-policy|" + SCHEMA_VERSION + "|"
                + OUTPUT_PROTOCOL + "|" + softThreshold + "|" + targetRatio + "|"
                + maxPerRun + "|" + maxInputTokens + "|" + SUMMARY_MAX_TOKENS).value();
    }

    /**
     * 资产钉版面（EN-02/MC36）：压缩指令的稳定模板 + 策略旋钮值——release_asset
     * PROMPT kind 的登记内容。行级 summary_prompt_digest（V92，含材料全文的随行
     * 冻结 digest）之外的资产级锚：内容寻址幂等，版本中心/资格面可引用，热切/
     * 回滚按 digest 精确失效。不含 run 专属材料与区间（那些由行级 digest 承载）。
     */
    public Map<String, Object> directiveTemplate() {
        Map<String, Object> template = new LinkedHashMap<>();
        template.put("kind", "context-compaction-directive");
        template.put("schema_version", SCHEMA_VERSION);
        template.put("output_protocol", OUTPUT_PROTOCOL);
        // PROMPT kind 资产契约（ReleaseAsset.of 校验，P02）：messages_template 非
        // blank + {{var}} 占位符全部在 variables_schema 声明。run 专属值以 {{var}}
        // 占位符进模板（行级 summary_prompt_digest 承载随行原文），资产级
        // target_ratio 为构造注入旋钮的具体值。
        template.put("messages_template", "mode=COMPACTION"
                + " | schema_version=" + SCHEMA_VERSION
                + " | source_snapshot_digest={{source_snapshot_digest}}"
                + " | event_seq_from={{event_seq_from}}"
                + " | event_seq_to={{event_seq_to}}"
                + " | required_refs={{required_refs}}"
                + " | target_ratio=" + targetRatio
                + " | " + OUTPUT_PROTOCOL);
        template.put("variables_schema", List.of(
                "source_snapshot_digest", "event_seq_from", "event_seq_to", "required_refs"));
        template.put("policy", policyView());
        return template;
    }

    /** 压缩策略旋钮值（资产登记内容；与构造注入的运行时值同源） */
    public Map<String, Object> policyView() {
        Map<String, Object> policy = new LinkedHashMap<>();
        policy.put("mode", mode.name());
        policy.put("soft_threshold", softThreshold);
        policy.put("target_ratio", targetRatio);
        policy.put("max_per_run", maxPerRun);
        policy.put("max_input_tokens", maxInputTokens);
        policy.put("summary_max_tokens", SUMMARY_MAX_TOKENS);
        policy.put("chars_per_token_estimate", CHARS_PER_TOKEN);
        return policy;
    }

    /** 摘要指令信封（确定性装配）：recall-first 目标 + 输出协议 + 冻结区间元数据 */
    private String compactionPrompt(String source, long eventSeqFrom, long eventSeqTo,
            Set<String> requiredRefs, String material) {
        Map<String, Object> directive = new LinkedHashMap<>();
        directive.put("mode", "COMPACTION");
        directive.put("schema_version", SCHEMA_VERSION);
        directive.put("source_snapshot_digest", source);
        directive.put("event_seq_from", eventSeqFrom);
        directive.put("event_seq_to", eventSeqTo);
        directive.put("required_refs", List.copyOf(requiredRefs));
        directive.put("target_ratio", targetRatio);
        directive.put("output_protocol", OUTPUT_PROTOCOL);
        try {
            return mapper.writeValueAsString(directive)
                    + "\n【待压缩上下文（冻结窗）】\n" + material;
        } catch (Exception e) {
            throw new IllegalStateException("压缩指令序列化失败", e);
        }
    }

    /** 候选整形（BA-110 同律：去围栏不扩容——不提取任意文本中的 JSON 片段） */
    private JsonNode parseCandidate(String content) {
        if (content == null || content.isBlank()) {
            return null;
        }
        try {
            return mapper.readTree(BoundedLlmRoleRunner.jsonOf(content));
        } catch (Exception e) {
            return null;
        }
    }
}
