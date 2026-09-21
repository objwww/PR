package com.objwww.pr.control.alert.application.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.objwww.pr.control.alert.application.RunBudgetGate;
import com.objwww.pr.control.alert.domain.agent.RcaJevSelection;
import com.objwww.pr.control.alert.domain.agent.PrimaryCheckpoint;
import com.objwww.pr.control.alert.domain.agent.RcaModelCallLedger;
import com.objwww.pr.control.alert.domain.repository.RcaJevSelectionPort;
import com.objwww.pr.control.alert.domain.budget.BudgetKind;
import com.objwww.pr.control.alert.domain.budget.ReservationKey;
import com.objwww.pr.control.alert.domain.evidence.EvidenceEnvelope;
import com.objwww.pr.control.alert.domain.model.RcaRun;
import com.objwww.pr.control.alert.domain.model.RcaTask;
import com.objwww.pr.control.alert.domain.repository.RcaRunRepository;
import com.objwww.pr.control.alert.domain.repository.RcaTaskRepository;
import com.objwww.pr.control.alert.domain.repository.WorkingMemoryPort;
import com.objwww.pr.control.infrastructure.observability.StructuredLog;
import com.objwww.pr.shared.Digest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Jev 增强服务（JE-01，方案 §2 数据流）：冻结状态 → 确定性保护 → Jev 评分 →
 * 代码按预算选材 / FINAL 边界复核。职责边界照方案钉死——Jev 只出有类型判断，
 * <b>保留权在宿主代码</b>：保护项（绑定承诺 ∪ 工作记忆反证）永远入窗、不进可裁
 * 候选（JEV-02）；低置信不自动转删除；异常/超时/契约违约一律有界回退（选材回
 * 现有窗口、复核放行本次 final），永不打断主链。
 *
 * <p>预算与账目（方案 §3/§5 步骤6）：每次 Jev 调用 = RunBudgetGate TOKEN 维
 * 原子预留 → rca_model_call PENDING 先行（roleId=jev-selector/jev-reviewer，
 * 动作序占 {@link #JEV_ACTION_SEQ_BASE} 保留段，与决策序/压缩段不撞唯一键）→
 * 发送 → usage/cost 实扣结算，全部计入同一次调查账目。费用口径 = 输入
 * {@code inputUsdPerMillion} USD/百万 token（Jev 输出免费，2026-09-20 官方快照），
 * 输出按 0 计但不伪称精确账单。
 *
 * <p>触发边界：run 行 jev_enabled（铸造冻结）+ 全局 mode ≠ OFF + 客户端在场 +
 * 池存在冗余（&gt;{@value ContextAssembler#EVIDENCE_LIMIT} 条，JEV-07 短路）。
 * SHADOW 只评分记档不改输入；SELECT 才替换窗口。
 */
public class JevEnhancementService implements JevEnhancementPort {

    private static final Logger log = LoggerFactory.getLogger(JevEnhancementService.class);

    /** 账本角色身份（与调查/压缩调用同表分行；动作序保留段互不重叠） */
    public static final String SELECTOR_ROLE_ID = "jev-selector";
    public static final String REVIEWER_ROLE_ID = "jev-reviewer";
    /** Jev 动作序保留段（压缩占 ≥10^6；Jev 占 ≥2×10^6，decisionSeq 每步至多两调用） */
    public static final long JEV_ACTION_SEQ_BASE = 2_000_000L;
    public static final long MODEL_RETRY_SEQ_BASE = 3_000_000L;

    @Override public boolean available() { return client != null && mode == Mode.SELECT && reviewEnabled; }
    @Override public boolean enabledFor(RoleRunner.RoleDriveRequest request) {
        return available() && runs.jevEnabledById(request.task().runId());
    }
    @Override public boolean allowModelRetry(RoleRunner.RoleDriveRequest request) {
        if (!enabledFor(request)) return false;
        UUID run=request.task().runId();
        return ledger.listSettledUsageByRunId(run).stream().noneMatch(c -> c.actionSeq() >= MODEL_RETRY_SEQ_BASE
                && c.actionSeq() < MODEL_RETRY_SEQ_BASE + 1_000_000)
                && ledger.findUnsettledByRun(run).stream().noneMatch(c -> c.actionSeq() >= MODEL_RETRY_SEQ_BASE
                && c.actionSeq() < MODEL_RETRY_SEQ_BASE + 1_000_000);
    }

    /** 复核缺口反馈机器签名前缀（与 MODEL_FAILURE_SIGNATURE_PREFIX 同槽共存纪律） */
    public static final String REVIEW_FEEDBACK_SIG = "JEV_REVIEW_GAP: sig=";
    /** 同签名缺口第二次 = 接受本次 final（不烧剩余步数） */
    private static final int SIGNATURE_LENGTH = 16;
    /** 单次复核最多逐条检查的 claim 数（超出按整包覆盖题兜底） */
    static final int REVIEW_CLAIM_LIMIT = 8;
    /** 复核缺口判定线（首版静态；校准后改旋钮，不静默漂移） */
    static final double GAP_THRESHOLD = 0.5;
    /** 每候选随 state 下发文本上限（chars） */
    static final int CANDIDATE_CLIP = 600;
    /** token 保守估算分母（与装配器同一口径） */
    static final int CHARS_PER_TOKEN = 2;
    /** Jev 请求估算上限（官方整请求 64k / state+单题 32k 内保守值；超限本轮跳过） */
    static final int MAX_SELECTION_INPUT_TOKENS = 28_000;

    /** Jev 路径三模式：OFF 关；SHADOW 只评分记档；SELECT 替换证据窗口 */
    public enum Mode { OFF, SHADOW, SELECT }

    private final RcaRunRepository runs;
    private final RcaTaskRepository tasks;
    private final RunBudgetGate budgetGate;
    private final RcaModelCallLedger ledger;
    private final WorkingMemoryPort workingMemory;
    private final JevClient client;
    private final ObjectMapper mapper;
    private final Clock clock;
    private final Mode mode;
    private final String model;
    private final double selectThreshold;
    private final boolean reviewEnabled;
    private final double inputUsdPerMillion;
    /** 选材触发线（JE-01 热修旋钮）：池 ≥ 此值才调用 Jev。默认 20 = 既有窗口线
     *  （零漂移）；195 生产实测池最大 11/均值 5.8——20 恒不触发，部署面按需调低 */
    private final int minPoolSize;
    /** 选材上限（SELECT 态真正的收缩预算；SHADOW 只影响记档里的 selected 数） */
    private final int maxSelected;
    /** 选材审计台账（JE-02，可空=无审计面装配——落行降级，行为不受影响） */
    private final RcaJevSelectionPort selections;
    private final String policyDigest;

    public JevEnhancementService(RcaRunRepository runs, RcaTaskRepository tasks,
            RunBudgetGate budgetGate, RcaModelCallLedger ledger,
            WorkingMemoryPort workingMemory, JevClient client, ObjectMapper mapper,
            Clock clock, Mode mode, String model, double selectThreshold,
            boolean reviewEnabled, double inputUsdPerMillion) {
        this(runs, tasks, budgetGate, ledger, workingMemory, client, mapper, clock,
                mode, model, selectThreshold, reviewEnabled, inputUsdPerMillion,
                ContextAssembler.EVIDENCE_LIMIT, ContextAssembler.EVIDENCE_LIMIT, null);
    }

    public JevEnhancementService(RcaRunRepository runs, RcaTaskRepository tasks,
            RunBudgetGate budgetGate, RcaModelCallLedger ledger,
            WorkingMemoryPort workingMemory, JevClient client, ObjectMapper mapper,
            Clock clock, Mode mode, String model, double selectThreshold,
            boolean reviewEnabled, double inputUsdPerMillion,
            int minPoolSize, int maxSelected) {
        this(runs, tasks, budgetGate, ledger, workingMemory, client, mapper, clock,
                mode, model, selectThreshold, reviewEnabled, inputUsdPerMillion,
                minPoolSize, maxSelected, null);
    }

    public JevEnhancementService(RcaRunRepository runs, RcaTaskRepository tasks,
            RunBudgetGate budgetGate, RcaModelCallLedger ledger,
            WorkingMemoryPort workingMemory, JevClient client, ObjectMapper mapper,
            Clock clock, Mode mode, String model, double selectThreshold,
            boolean reviewEnabled, double inputUsdPerMillion,
            int minPoolSize, int maxSelected, RcaJevSelectionPort selections) {
        this.runs = Objects.requireNonNull(runs, "runs");
        this.tasks = Objects.requireNonNull(tasks, "tasks");
        this.budgetGate = Objects.requireNonNull(budgetGate, "budgetGate");
        this.ledger = Objects.requireNonNull(ledger, "ledger");
        this.workingMemory = workingMemory;
        this.client = client;
        this.mapper = Objects.requireNonNull(mapper, "mapper");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.mode = Objects.requireNonNull(mode, "mode");
        this.model = Objects.requireNonNull(model, "model").strip();
        if (this.model.isEmpty()) {
            throw new IllegalArgumentException("Jev model 不能为空");
        }
        if (selectThreshold <= 0 || selectThreshold > 1) {
            throw new IllegalArgumentException("jev.select-threshold 须在 (0,1]: "
                    + selectThreshold);
        }
        this.selectThreshold = selectThreshold;
        this.reviewEnabled = reviewEnabled;
        this.inputUsdPerMillion = inputUsdPerMillion;
        if (minPoolSize < 1) {
            throw new IllegalArgumentException("jev.min-pool-size 须 ≥1: " + minPoolSize);
        }
        this.minPoolSize = minPoolSize;
        if (maxSelected < 1) {
            throw new IllegalArgumentException("jev.max-selected 须 ≥1: " + maxSelected);
        }
        this.maxSelected = maxSelected;
        this.selections = selections;
        this.policyDigest = Digest.sha256Of("jev-policy|" + mode.name() + "|" + model
                + "|" + selectThreshold + "|" + reviewEnabled + "|"
                + inputUsdPerMillion + "|" + minPoolSize + "|" + maxSelected + "|"
                + JevVersion.QUESTION_VERSION).value();
    }

    /** 策略/题面版本锚（变任何一项 = 新逻辑动作身份，旧校准不静默套用，JEV-13） */
    static final class JevVersion {
        static final String QUESTION_VERSION = "jev-question-v1";
        private JevVersion() {
        }
    }

    @Override
    public ContextAssembler.EvidenceSelection selectContext(SelectionInput input) {
        if (client == null || mode == Mode.OFF) {
            return null;
        }
        RoleRunner.RoleDriveRequest request = input.request();
        UUID runId = request.task().runId();
        try {
            if (!runs.jevEnabledById(runId)) {
                return null;
            }
            List<EvidenceEnvelope> pool = input.snapshot().rows();
            if (pool.size() <= minPoolSize) {
                // JEV-07：池未过触发线不调用（默认 20 与既有窗口线严格等价：
                // >20 才调用；部署面可调低让 SHADOW 真实触发）
                return null;
            }
            Set<String> poolIds = pool.stream().map(row -> row.evidenceId().toString())
                    .collect(Collectors.toCollection(LinkedHashSet::new));
            Set<String> protectedRefs = protectedRefs(request, input.checkpoint(),
                    poolIds);
            int budget = Math.min(ContextAssembler.EVIDENCE_LIMIT - protectedRefs.size(),
                    maxSelected);
            if (budget <= 0) {
                // 保护项已填满窗口：不再评分，窗口 = 保护项（确定性，无 Jev 费用）
                return new ContextAssembler.EvidenceSelection(protectedRefs,
                        mode == Mode.SELECT, "jev:protected-full");
            }
            List<EvidenceEnvelope> candidates = pool.stream()
                    .filter(row -> !protectedRefs.contains(row.evidenceId().toString()))
                    .toList();
            Map<String, Object> state = selectionState(request, input.checkpoint(),
                    input.material(), candidates);
            long estimate = estimateTokens(state);
            if (estimate > MAX_SELECTION_INPUT_TOKENS) {
                StructuredLog.event(log, "JEV_SELECTION", Map.of(
                        "run_id", String.valueOf(runId),
                        "result", "SKIPPED_INPUT_TOO_LARGE",
                        "estimate_tokens", String.valueOf(estimate)));
                return null;
            }
            Instant selectionBegin = clock.instant();
            JevEnhancementService.GuardedJev guarded = guardedJevCall(request,
                    input.checkpoint(), SELECTOR_ROLE_ID, false, state,
                    candidateQuestions(candidates), estimate);
            JevClient.JevAnswer answer = guarded == null ? null : guarded.answer();
            if (answer == null) {
                return null;
            }
            // 确定性合并：阈值过滤 → 概率降序（平局 id 升序）→ 预算截断
            List<EvidenceEnvelope> selected = candidates.stream()
                    .filter(row -> {
                        Double probability = answer.probabilities()
                                .get(row.evidenceId().toString());
                        return probability != null && probability >= selectThreshold;
                    })
                    .sorted(Comparator
                            .comparing((EvidenceEnvelope row) ->
                                    answer.probabilities().get(row.evidenceId().toString()))
                            .reversed()
                            .thenComparing(row -> row.evidenceId().toString()))
                    .limit(budget)
                    .toList();
            Set<String> pinned = new LinkedHashSet<>(protectedRefs);
            selected.forEach(row -> pinned.add(row.evidenceId().toString()));
            long selectionLatencyMs = Duration.between(selectionBegin, clock.instant())
                    .toMillis();
            persistSelection(request, mode.name(), mode == Mode.SELECT, poolIds,
                    protectedRefs, selected, pinned, answer.probabilities(),
                    guarded.callId(), selectionLatencyMs);
            StructuredLog.event(log, "JEV_SELECTION", Map.of(
                    "run_id", String.valueOf(runId),
                    "task_id", String.valueOf(request.task().id()),
                    "mode", mode.name(),
                    "applied", String.valueOf(mode == Mode.SELECT),
                    "pool", String.valueOf(pool.size()),
                    "protected", String.valueOf(protectedRefs.size()),
                    "selected", String.valueOf(selected.size()),
                    "input_tokens", String.valueOf(answer.usage().inputTokens()),
                    "total_tokens", String.valueOf(answer.usage().totalTokens()),
                    "usage_missing", String.valueOf(answer.usage().usageMissing())));
            return new ContextAssembler.EvidenceSelection(pinned,
                    mode == Mode.SELECT, "jev:" + mode.name().toLowerCase());
        } catch (RuntimeException e) {
            // 有界回退（方案 §5）：任何异常 → 现有确定性窗口，明确记因，无静默删除
            log.warn("Jev 选材边界异常（回退现有窗口）run={} {}: {}",
                    runId, e.getClass().getSimpleName(), e.getMessage());
            StructuredLog.event(log, "JEV_SELECTION", Map.of(
                    "run_id", String.valueOf(runId),
                    "result", "FELL_BACK",
                    "error", String.valueOf(e.getClass().getSimpleName())));
            return null;
        }
    }

    @Override
    public Optional<String> reviewFinal(ReviewInput input) {
        if (client == null || mode != Mode.SELECT || !reviewEnabled) {
            return Optional.empty();
        }
        RoleRunner.RoleDriveRequest request = input.request();
        PrimaryCheckpoint checkpoint = input.checkpoint();
        UUID runId = request.task().runId();
        try {
            if (!runs.jevEnabledById(runId)) {
                return Optional.empty();
            }
            long priorReviews=ledger.listSettledUsageByRunId(runId).stream()
                    .filter(c -> REVIEWER_ROLE_ID.equals(c.roleId())).count();
            if (priorReviews >= 3) return Optional.of("JEV_REVIEW_LIMIT: 复核次数耗尽，保留缺口并以未决结束");
            List<Map<String, Object>> claims = input.admittedClaimRows();
            if (claims.isEmpty()) {
                return Optional.empty();
            }
            if (checkpoint.stepsUsed() >= request.profile().maxSteps()) {
                // 无剩余步数：缺口也无法重取证，直接放行（预算内复核纪律）
                return Optional.empty();
            }
            String digest = Digest.sha256Of(reviewSignature(input)).value()
                    .substring(0, SIGNATURE_LENGTH);
            if (checkpoint.lastError() != null
                    && checkpoint.lastError().startsWith(REVIEW_FEEDBACK_SIG + digest)) {
                // 同签名缺口第二次 = 修正无效，接受本次 final（不烧剩余步数）
                log.info("Jev 复核同签名缺口重复，接受本次 final run={} sig={}",
                        runId, digest);
                return Optional.empty();
            }
            Map<String, String> questions = reviewQuestions(claims);
            Map<String, Object> state = reviewState(request, checkpoint, claims,
                    input.missingInformation());
            state.put("evidence", input.evidence());
            if (estimateTokens(state) > MAX_SELECTION_INPUT_TOKENS) return Optional.empty();
            JevEnhancementService.GuardedJev guarded = guardedJevCall(request,
                    checkpoint, REVIEWER_ROLE_ID, true, state, questions,
                    estimateTokens(state));
            if (guarded == null) {
                return Optional.empty();
            }
            JevClient.JevAnswer answer = guarded.answer();
            List<String> gaps = new ArrayList<>();
            int reviewed = 0;
            for (Map<String, Object> claim : claims) {
                if (++reviewed > REVIEW_CLAIM_LIMIT) {
                    break;
                }
                String questionId = "claim-" + claim.getOrDefault("claim_key",
                        String.valueOf(reviewed));
                Double probability = answer.probabilities().get(questionId);
                if (probability != null && probability < GAP_THRESHOLD) {
                    gaps.add("claim " + questionId.substring("claim-".length())
                            + " 证据支持不足");
                }
            }
            Double coverage = answer.probabilities().get("coverage");
            if (coverage != null && coverage < GAP_THRESHOLD) {
                gaps.add("现有证据仍不足以回答目标，存在未取证的关键缺口");
            }
            if (gaps.isEmpty()) {
                StructuredLog.event(log, "JEV_REVIEW", Map.of(
                        "run_id", String.valueOf(runId),
                        "result", "ACCEPTED"));
                return Optional.empty();
            }
            String feedback = REVIEW_FEEDBACK_SIG + digest
                    + " Jev 复核发现缺口：" + String.join("；", gaps)
                    + "。在剩余步数内优先补取证（tool_call，按冻结窗与 tool_schemas"
                    + " 取参），或修正 final；证据确实不足则如实写 missing_information。";
            StructuredLog.event(log, "JEV_REVIEW", Map.of(
                    "run_id", String.valueOf(runId),
                    "task_id", String.valueOf(request.task().id()),
                    "result", "GAPS",
                    "gaps", String.valueOf(gaps.size()),
                    "sig", digest));
            return Optional.of(feedback);
        } catch (RuntimeException e) {
            log.warn("Jev 复核边界异常（接受本次 final）run={} {}: {}",
                    runId, e.getClass().getSimpleName(), e.getMessage());
            return Optional.empty();
        }
    }

    // ------------------------------------------------------------------ 保护与候选

    /**
     * 固定保护项（方案 §2 材料三类）：绑定承诺引用 ∪ 工作记忆累计反证——Jev 无权
     * 裁（JEV-02），低分不删；不在池内的引用自然落空。
     */
    private Set<String> protectedRefs(RoleRunner.RoleDriveRequest request,
            PrimaryCheckpoint checkpoint, Set<String> poolIds) {
        Set<String> refs = new LinkedHashSet<>();
        for (String ref : request.binding().inputRefs()) {
            if (poolIds.contains(ref)) {
                refs.add(ref);
            }
        }
        for (String ref : counterEvidenceRefs(request.task().runId(), checkpoint)) {
            if (poolIds.contains(ref)) {
                refs.add(ref);
            }
        }
        return refs;
    }

    private List<String> counterEvidenceRefs(UUID runId, PrimaryCheckpoint checkpoint) {
        if (workingMemory == null || checkpoint.memoryId() == null) {
            return List.of();
        }
        return workingMemory.findById(checkpoint.memoryId())
                .map(memory -> memory.slots()
                        .getOrDefault("counter_evidence_refs", List.of()))
                .orElse(List.of());
    }

    // ------------------------------------------------------------------ 请求构建

    private Map<String, Object> selectionState(RoleRunner.RoleDriveRequest request,
            PrimaryCheckpoint checkpoint, ContextAssembler.AlertMaterial material,
            List<EvidenceEnvelope> candidates) {
        Map<String, Object> state = new LinkedHashMap<>();
        state.put("objective", ContextAssembler.objectiveOf(request, material));
        Map<String, Object> alert = new LinkedHashMap<>();
        if (material != null) {
            if (material.alertname() != null) {
                alert.put("alertname", material.alertname());
            }
            if (material.service() != null) {
                alert.put("service", material.service());
            }
            if (material.severity() != null) {
                alert.put("severity", material.severity());
            }
        }
        Map<String, Object> window = new LinkedHashMap<>();
        window.put("start_epoch", Long.parseLong(request.startEpoch()));
        window.put("end_epoch", Long.parseLong(request.endEpoch()));
        alert.put("window", window);
        state.put("alert", alert);
        List<String> hypotheses = new ArrayList<>();
        List<String> ruledOut = new ArrayList<>();
        for (Map<String, Object> claim : checkpoint.finalClaims()) {
            Object statement = claim.get("statement");
            if (statement == null) {
                continue;
            }
            if ("EXCLUSION".equals(claim.get("kind"))) {
                ruledOut.add(ContextAssembler.clip(String.valueOf(statement),
                        ContextAssembler.ITEM_LIMIT).text());
            } else {
                hypotheses.add(ContextAssembler.clip(String.valueOf(statement),
                        ContextAssembler.ITEM_LIMIT).text());
            }
        }
        state.put("hypotheses", hypotheses);
        state.put("ruled_out", ruledOut);
        state.put("counter_evidence_refs",
                counterEvidenceRefs(request.task().runId(), checkpoint));
        if (checkpoint.lastError() != null) {
            state.put("last_feedback", ContextAssembler.clip(checkpoint.lastError(),
                    ContextAssembler.ITEM_LIMIT).text());
        }
        List<Map<String, Object>> evidence = new ArrayList<>();
        for (EvidenceEnvelope row : candidates) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", row.evidenceId().toString());
            item.put("type", row.evidenceType());
            if (row.timeEnd() != null) {
                item.put("time_end", row.timeEnd().toString());
            }
            item.put("text", ContextAssembler.clip(row.canonicalPayload() == null
                    ? "" : row.canonicalPayload().strip(), CANDIDATE_CLIP).text());
            evidence.add(item);
        }
        state.put("evidence", evidence);
        return state;
    }

    /** 逐候选一题（noul 信封与 criteria 由客户端钉版，id 入 candidate_id） */
    private static Map<String, String> candidateQuestions(
            List<EvidenceEnvelope> candidates) {
        Map<String, String> questions = new LinkedHashMap<>();
        for (EvidenceEnvelope row : candidates) {
            questions.put(row.evidenceId().toString(),
                    "Is the state.evidence item identified by candidate_id useful for the investigation objective, "
                            + "including counterevidence, time boundaries or causal changes?");
        }
        return questions;
    }

    private Map<String, String> reviewQuestions(List<Map<String, Object>> claims) {
        Map<String, String> questions = new LinkedHashMap<>();
        int reviewed = 0;
        for (Map<String, Object> claim : claims) {
            if (++reviewed > REVIEW_CLAIM_LIMIT) {
                break;
            }
            String statement = String.valueOf(claim.get("statement"));
            questions.put("claim-" + claim.getOrDefault("claim_key",
                            String.valueOf(reviewed)),
                    "Claim: \"" + ContextAssembler.clip(statement, 300).text()
                            + "\" — is the statement supported by the actual evidence content, after accounting "
                            + "for REFUTES and CONTEXT references? A reference ID alone is not support.");
        }
        questions.put("coverage",
                "Taken together, is the currently collected evidence sufficient to "
                        + "answer the investigation objective without further tool "
                        + "collection?");
        return questions;
    }

    private Map<String, Object> reviewState(RoleRunner.RoleDriveRequest request,
            PrimaryCheckpoint checkpoint, List<Map<String, Object>> claims,
            List<String> missingInformation) {
        Map<String, Object> state = new LinkedHashMap<>();
        state.put("objective", "调查在冻结窗 " + request.startEpoch() + "/"
                + request.endEpoch() + " 内的根因");
        List<Map<String, Object>> claimRows = new ArrayList<>();
        for (Map<String, Object> claim : claims) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("claim_key", String.valueOf(claim.get("claim_key")));
            row.put("kind", String.valueOf(claim.get("kind")));
            row.put("statement", ContextAssembler.clip(
                    String.valueOf(claim.get("statement")), 300).text());
            row.put("evidence_refs", claim.get("evidence_refs"));
            row.put("evidence_roles", claim.getOrDefault("evidence_roles", List.of()));
            claimRows.add(row);
        }
        state.put("claims", claimRows);
        state.put("missing_information", missingInformation == null ? List.of()
                : missingInformation);
        if (checkpoint.lastError() != null) {
            state.put("last_feedback", ContextAssembler.clip(checkpoint.lastError(),
                    ContextAssembler.ITEM_LIMIT).text());
        }
        return state;
    }

    private static String reviewSignature(JevEnhancementPort.ReviewInput input) {
        StringBuilder signature = new StringBuilder("review|");
        for (Map<String, Object> claim : input.admittedClaimRows()) {
            signature.append(claim.get("claim_key")).append('|')
                    .append(claim.get("statement")).append('|')
                    .append(claim.get("evidence_refs")).append('\n');
        }
        signature.append("missing|").append(input.missingInformation());
        return signature.toString();
    }

    private static long estimateTokens(Map<String, Object> state) {
        int chars = String.valueOf(state).length();
        return chars / CHARS_PER_TOKEN + 1;
    }

    // ------------------------------------------------------------------ 守卫调用

    /** 守卫调用的出参：答案 + 账本行 id（审计台账关联用，JE-02） */
    private record GuardedJev(JevClient.JevAnswer answer, UUID callId) {
    }

    /** 选材决策落审计台账（JE-02）：失败降级记 warn，绝不打断主链 */
    private void persistSelection(RoleRunner.RoleDriveRequest request, String mode,
            boolean applied, Set<String> poolIds, Set<String> protectedRefs,
            List<EvidenceEnvelope> selected, Set<String> pinned,
            Map<String, Double> probabilities, UUID callId, long latencyMs) {
        if (selections == null) {
            return;
        }
        try {
            List<String> selectedIds = selected.stream()
                    .map(row -> row.evidenceId().toString()).toList();
            List<String> omitted = poolIds.stream()
                    .filter(id -> !pinned.contains(id)).toList();
            selections.append(RcaJevSelection.of(UUID.randomUUID(),
                    request.task().runId(), request.task().id(), mode, applied,
                    List.copyOf(poolIds), List.copyOf(protectedRefs), selectedIds,
                    omitted, probabilities, callId, policyDigest, latencyMs,
                    clock.instant()));
        } catch (RuntimeException e) {
            log.warn("Jev 选材台账落库失败（审计降级，不打断主链）: {}",
                    e.getClass().getSimpleName());
        }
    }

    /**
     * 围栏 + 预算 + 账本三面俱全的一次 Jev 调用；任何拒绝/失败返回 null（调用方
     * 有界回退），绝不向主链上抛。围栏与 RcaActionGuard ①②④⑤ 同源简化（Jev 判断
     * 不写检查点，generation/角色栅栏由结果应用点的提交围栏承担）。
     */
    private GuardedJev guardedJevCall(RoleRunner.RoleDriveRequest request,
            PrimaryCheckpoint checkpoint, String roleId, boolean review,
            Map<String, Object> state, Map<String, String> questions,
            long tokenEstimate) {
        UUID runId = request.task().runId();
        UUID taskId = request.task().id();
        RcaRun run = runs.findById(runId).orElse(null);
        if (run == null || !run.state().isActive()
                || run.generation() != request.callContext().observedGeneration()) {
            log.info("Jev 调用围栏拒绝（run 不活跃）run={} role={}", runId, roleId);
            return null;
        }
        RcaTask task = tasks.findById(taskId).orElse(null);
        if (task == null || task.leaseEpoch() != request.task().leaseEpoch()) {
            log.info("Jev 调用围栏拒绝（租约漂移）task={}", taskId);
            return null;
        }
        Instant now = clock.instant();
        if (!now.isBefore(task.deadlineAt())) {
            log.info("Jev 调用围栏拒绝（deadline 已过）task={}", taskId);
            return null;
        }
        if (task.leaseUntil() != null && !now.isBefore(task.leaseUntil())) return null;
        request.callContext().controlSignal().run();
        long actionSeq = JEV_ACTION_SEQ_BASE + checkpoint.decisionSeq() * 2
                + (review ? 1 : 0);
        UUID callId = UUID.randomUUID();
        JevClient.JevRequest jevRequest = new JevClient.JevRequest(model, state,
                questions);
        String promptDigest = requestDigest(jevRequest);
        Instant begin = clock.instant();
        try {
            return budgetGate.call(
                    Map.of(BudgetKind.TOKEN, Math.max(1L, tokenEstimate)),
                    new ReservationKey(runId, taskId,
                            request.callContext().attemptId(), actionSeq,
                            BudgetKind.TOKEN),
                    () -> {
                        ledger.open(new RcaModelCallLedger.OpenRow(callId, runId,
                                taskId, request.callContext().attemptId(), actionSeq,
                                1, checkpoint.roundId(), roleId, "v1", policyDigest,
                                promptDigest, null, checkpoint.inputSnapshotDigest(),
                                request.binding().configEpoch(),
                                request.binding().releaseDigest(),
                                task.leaseEpoch()));
                        JevClient.JevAnswer answer;
                        try {
                            answer = client.score(jevRequest);
                        } catch (JevClient.JevClientException e) {
                            ledger.fail(callId, e.code());
                            throw e;
                        } catch (RuntimeException e) {
                            ledger.fail(callId, JevClient.JevClientException.NETWORK);
                            throw e;
                        }
                        long latencyMs = Duration.between(begin, clock.instant())
                                .toMillis();
                        JevClient.JevUsage usage = answer.usage();
                        long costMicros = usage.inputTokens() == null ? 0L
                                : Math.round(usage.inputTokens() * inputUsdPerMillion);
                        if (!ledger.succeed(callId,
                                new RcaModelCallLedger.UsageOutcome(
                                        usage.inputTokens() == null ? 0
                                                : usage.inputTokens(),
                                        usage.outputTokens() == null ? 0
                                                : usage.outputTokens(),
                                        usage.totalTokens() == null ? 0
                                                : usage.totalTokens(),
                                        usage.usageMissing(),
                                        usage.usageMissing() ? null : costMicros,
                                        usage.usageMissing() ? null
                                                : "jev-input-" + inputUsdPerMillion
                                                        + "usd-per-m-20260920",
                                        usage.usageMissing() ? null : "USD",
                                        null, "jev", answer.model(), latencyMs,
                                        null))) {
                            throw new IllegalStateException("Jev ledger settlement rejected");
                        }
                        return new GuardedJev(answer, callId);
                    },
                    guarded -> Map.of(BudgetKind.TOKEN,
                            guarded.answer().usage().usageMissing()
                                    ? RunBudgetGate.Usage.unmatched()
                                    : RunBudgetGate.Usage.of(guarded.answer().usage().totalTokens())),
                    e -> e instanceof JevClient.JevClientException j && j.zeroSend());
        } catch (RuntimeException e) {
            log.warn("Jev 调用失败（有界回退）run={} role={} {}: {}", runId, roleId,
                    e.getClass().getSimpleName(), e.getMessage());
            return null;
        }
    }

    private String requestDigest(JevClient.JevRequest request) {
        try {
            return Digest.sha256Of(mapper.writeValueAsString(request)).value();
        } catch (Exception e) {
            return Digest.sha256Of(String.valueOf(request.questions().keySet()))
                    .value();
        }
    }
}
