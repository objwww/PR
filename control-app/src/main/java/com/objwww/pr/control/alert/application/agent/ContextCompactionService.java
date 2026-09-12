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
 * 摘要的消费面（信封注入与证据窗再裁剪）待 MC34 证明收益后启用；enabled=true 时
 * 本服务只生成不消费——放量前置条件写入执行日志。
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

    /** token 保守估算分母（与 ContextAssembler/RcaModelGateway 同一口径） */
    static final int CHARS_PER_TOKEN = 2;

    /** 封闭结果面：五道零调用闸 + 模型/校验失败族 + SUPERSEDED + COMMITTED */
    public enum OutcomeKind {
        DISABLED, BELOW_THRESHOLD, LIMIT_REACHED, NO_SOURCE, ALREADY_COMPACTED,
        MODEL_FAILED, REJECTED_UNPARSEABLE, REJECTED_MISSING_REQUIRED,
        REJECTED_NO_SAVINGS, SUPERSEDED, COMMITTED
    }

    /** 一步边界结果：kind + 已提交摘要（仅 COMMITTED 非 null）+ 机器可读细节 */
    public record CompactionOutcome(OutcomeKind kind, ContextSummary summary,
            String detail) {

        public boolean committed() {
            return kind == OutcomeKind.COMMITTED;
        }
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
    private final boolean enabled;
    private final double softThreshold;
    private final double targetRatio;
    private final int maxPerRun;
    private final int maxInputTokens;

    public ContextCompactionService(CompactionModelPort model, ContextSummaryPort summaries,
            PrimaryCheckpointRepository checkpoints, EvidenceRepository evidence,
            ObjectMapper mapper, Clock clock, boolean enabled, double softThreshold,
            double targetRatio, int maxPerRun, int maxInputTokens) {
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
        this.enabled = enabled;
        this.softThreshold = softThreshold;
        this.targetRatio = targetRatio;
        this.maxPerRun = maxPerRun;
        this.maxInputTokens = maxInputTokens;
    }

    /**
     * 一步边界调用（工具结果入库后、下一模型发送前）。永不抛出业务异常打断主路径
     * ——模型/守卫失败按封闭结果面返回，由调用方记日志后按原材料继续。
     */
    public CompactionOutcome afterToolResults(RoleRunner.RoleDriveRequest request,
            PrimaryCheckpoint checkpoint, ContextAssembler.Assembly assembly) {
        if (!enabled) {
            return new CompactionOutcome(OutcomeKind.DISABLED, null,
                    "compaction.enabled=false（放量前提 MC34 三臂对照）");
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
        long eventSeqFrom = summaries.latestByTask(runId, taskId)
                .map(s -> s.eventSeqTo() + 1).orElse(0L);
        long eventSeqTo = checkpoint.decisionSeq();

        // 引用值域全集 + 必需集（宿主生成：绑定承诺 ∪ 终局引用；模型不得删空）
        Set<String> validRefs = new LinkedHashSet<>(request.binding().inputRefs());
        evidence.findByRunId(runId).forEach(e -> validRefs.add(e.evidenceId().toString()));
        Set<String> requiredRefs = new LinkedHashSet<>(request.binding().inputRefs());
        for (Map<String, Object> claim : checkpoint.finalClaims()) {
            Object refs = claim.get("evidence_refs");
            if (refs instanceof List<?> list) {
                list.forEach(r -> requiredRefs.add(String.valueOf(r)));
            }
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
                source, () -> true);

        RcaModelOutcome outcome;
        try {
            outcome = model.call(action, prompt, SUMMARY_MAX_TOKENS);
        } catch (RcaModelCallException e) {
            // MC15：有界回退——候选丢弃保留原快照，确定性选材继续；无内联重试
            return new CompactionOutcome(OutcomeKind.MODEL_FAILED, null, e.errorCode());
        }

        JsonNode candidate = parseCandidate(outcome.content());
        if (candidate == null || !candidate.hasNonNull("summary")
                || !candidate.get("summary").isTextual()) {
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
            return new CompactionOutcome(OutcomeKind.REJECTED_MISSING_REQUIRED, null,
                    "必需引用缺失: " + missing);
        }
        int tokenAfter = summaryText.length() / CHARS_PER_TOKEN + 1;
        if (tokenAfter >= assembly.approxTokens()) {
            // MC15：没有节省 → 丢弃候选保留原快照（不无限重压缩）
            return new CompactionOutcome(OutcomeKind.REJECTED_NO_SAVINGS, null,
                    "tokenAfter=" + tokenAfter + " >= tokenBefore="
                            + assembly.approxTokens());
        }

        // MC17：提交前复验——检查点自冻结后推进/漂移 → 冻结区间失效，候选作废
        //（模型费用已由守卫落 rca_model_call，审计面不丢）
        PrimaryCheckpoint fresh = checkpoints.findByTask(taskId).orElse(null);
        if (fresh == null || fresh.decisionSeq() != eventSeqTo
                || !source.equals(fresh.inputSnapshotDigest())) {
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
        log.info("上下文压缩提交 run={} task={} source={} 区间=[{},{}] token {}→{} "
                        + "required={} omitted={}", runId, taskId, source,
                eventSeqFrom, eventSeqTo, row.tokenBefore(), row.tokenAfter(),
                requiredRefs.size(), omitted.size());
        return new CompactionOutcome(OutcomeKind.COMMITTED, committed, null);
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
        directive.put("output_protocol", "恰一个 JSON 对象：{\"summary\":\"<压缩后的调查"
                + "上下文。recall-first：先保全 required_refs 相关的事实/反证/未决缺口/"
                + "已做动作与停止条件，不得为达到压缩比移除反证；再精简其余>\","
                + "\"refs\":[<summary 保留项引用的证据 id：必须覆盖 required_refs，"
                + "且只能引用待压缩上下文中出现过的 id>]}");
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
