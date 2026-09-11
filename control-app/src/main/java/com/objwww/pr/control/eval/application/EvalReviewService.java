package com.objwww.pr.control.eval.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.objwww.pr.control.alert.domain.model.EvidencePackageV2;
import com.objwww.pr.control.eval.domain.model.ReviewAssignment;
import com.objwww.pr.control.eval.domain.model.ReviewVerdict;
import com.objwww.pr.control.eval.domain.repository.EvalQueryReader;
import com.objwww.pr.control.eval.domain.repository.EvalQueryReader.EvalCaseDetailRow;
import com.objwww.pr.control.eval.domain.repository.EvalQueryReader.EvalCasePage;
import com.objwww.pr.control.eval.domain.repository.EvalQueryReader.EvalCaseRow;
import com.objwww.pr.control.eval.domain.repository.ReviewAssignmentRepository;
import com.objwww.pr.control.eval.domain.repository.ReviewAssignmentRepository.KeysetCursor;
import com.objwww.pr.control.eval.domain.repository.ReviewVerdictRepository;
import org.springframework.dao.DuplicateKeyException;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;

/**
 * EV-08 人工评审应用服务（/api/eval/reviews/** 面；方案 §3.6/§5.3 "独立评审记录与
 * rubric 版本，不覆盖原机器评分"）。SQL 归端口，本类承担参数校验、领取/提交 CAS
 * 归因、盲评投影、分歧检测与进度分桶——纯函数段，假端口可测。
 *
 * <p>冻结语义：
 * <ul>
 *   <li><b>领取（EU29）</b>：单行条件 UPDATE CAS——行数 1 = 领到，0 = 重读归因
 *       （他人持有效租约 409 / 已提交 409）；同人持有效租约重复领取 = 幂等 REPLAYED；
 *       同人对同案例的第二份任务领取被 uq 部分唯一索引拒绝（409）；</li>
 *   <li><b>租约</b>：有界 30 分钟；超时惰性回收——读面投影 PENDING、领取谓词可重领，
 *       无 worker 无定时任务（EV-08 卡"选简单可靠的"）。回收重领 revision+1，
 *       旧持有者提交必撞 revision 409（不静默覆盖）；</li>
 *   <li><b>提交</b>：rubric 版本必带且必须在注册表（未知 400，不自造版本锚）；
 *       理由必填（400）；expectedRevision 必带（CAS 锚）；提交后不可改——
 *       更正/重评分 = 新任务 + 新 review_verdict 行（insert-only 审计闭环，
 *       旧行永不覆盖，机器评分列不触碰）；</li>
 *   <li><b>盲评（EU31/EV-05 裁定）</b>：案例身份经 case_version 精确键解析——
 *       HOLDOUT 行 RLS 不可见 → blind=true，投影只给症状与系统输出
 *       （actual 面字段/failureSample/报告摘要），GT 字段（expectedRootCause/
 *       expectedSymptomCodes/rootCauseHit）一律 null；非 HOLDOUT 可见 GT；</li>
 *   <li><b>无法判定/来源不足</b>是一等结论（UNDECIDABLE/INSUFFICIENT_SOURCE），
 *       不要求审核者强行二选一（§3.6）。</li>
 * </ul>
 * 草稿面（方案 §3.6"草稿与最终提交分开"）本卡不交付——提交一次性完成，
 * 偏差如实记录于 EV-08 文档。
 */
public class EvalReviewService {

    /** 有界租约时长（秒）：30 分钟——评审单例工作区的合理持有窗口 */
    static final long LEASE_SECONDS = 30 * 60;

    /** 每案例评份数上下限（双人评审 = 2；防无限生成） */
    static final int MIN_PER_CASE = 1;
    static final int MAX_PER_CASE = 4;

    private static final Set<String> SCOPES = Set.of("mine", "all");

    private final ReviewAssignmentRepository assignments;
    private final ReviewVerdictRepository verdicts;
    private final EvalQueryReader reader;
    private final EvalRubricRegistry rubrics;
    private final ObjectMapper mapper;

    public EvalReviewService(ReviewAssignmentRepository assignments,
                             ReviewVerdictRepository verdicts, EvalQueryReader reader,
                             EvalRubricRegistry rubrics, ObjectMapper mapper) {
        this.assignments = Objects.requireNonNull(assignments, "assignments");
        this.verdicts = Objects.requireNonNull(verdicts, "verdicts");
        this.reader = Objects.requireNonNull(reader, "reader");
        this.rubrics = Objects.requireNonNull(rubrics, "rubrics");
        this.mapper = Objects.requireNonNull(mapper, "mapper");
    }

    // ------------------------------------------------------------------ DTO（record，字段名即 JSON 契约）

    /** 任务列表项/工作区任务面：effectiveStatus = 惰性回收投影（租约超时的
     *  IN_PROGRESS 如实 PENDING，存储列原样携带） */
    public record AssignmentItem(UUID assignmentId, UUID runId, UUID caseExecutionId,
                                 String scenarioId, Integer roundNo,
                                 String status, String effectiveStatus, String reviewer,
                                 int revision, Instant claimedAt, Instant leaseExpiresAt,
                                 Instant submittedAt, Instant createdAt) {
    }

    public record AssignmentListResponse(List<AssignmentItem> items, String nextCursor,
                                         Instant asOf) {
    }

    public record GenerateResult(int created, int openTotal, int perCase) {
    }

    public enum ClaimStatus {CLAIMED, REPLAYED, CONFLICT_HELD, CONFLICT_SUBMITTED,
        CONFLICT_SELF_ACTIVE}

    public record ClaimResult(ClaimStatus status, AssignmentItem assignment) {
    }

    public enum SubmitStatus {SUBMITTED, CONFLICT_STATE, CONFLICT_REVISION,
        CONFLICT_ACTOR, CONFLICT_DUPLICATE}

    public record SubmitResult(SubmitStatus status, UUID verdictId, Integer currentRevision) {
    }

    /** 盲评案例投影（EU31）：blind=true 时 GT 字段恒 null——HOLDOUT 案例只给
     *  症状与系统输出。reportSummary 为系统输出面，盲评可见。 */
    public record ReviewCaseProjection(UUID caseExecutionId, String scenarioId, int roundNo,
                                       boolean blind, String machineVerdict,
                                       Boolean rootCauseHit,
                                       String expectedRootCause, String actualRootCause,
                                       List<String> expectedSymptomCodes,
                                       List<String> actualSymptomCodes,
                                       String failureSample, Long latencyMs,
                                       ReportSummary report) {
    }

    /** 报告人读摘要（系统输出面；非 v2/解析失败如实 null） */
    public record ReportSummary(String summary, String impact, String remediation,
                                List<String> references) {
    }

    public record VerdictItem(UUID verdictId, UUID assignmentId, String reviewer,
                              String rubricVersion, String verdict, Integer score,
                              List<String> labels, String reason, List<String> evidenceRefs,
                              Instant createdAt) {
    }

    public record ReviewWorkspaceResponse(AssignmentItem assignment,
                                          ReviewCaseProjection reviewCase,
                                          List<VerdictItem> verdictHistory, Instant asOf) {
    }

    /** 评审进度分桶（run 级；分桶计数原始值，分母 0 由前端如实处理） */
    public record ReviewProgressResponse(UUID runId, long caseCount, long totalAssignments,
                                         long pending, long inProgress, long submitted,
                                         long reviewedCases, long disagreementCases,
                                         Instant asOf) {
    }

    /** 分歧项：同一案例两名评审最新结论不一致（结论或分数差异） */
    public record DisagreementItem(UUID caseExecutionId, String scenarioId, int roundNo,
                                   List<VerdictItem> latestByReviewer) {
    }

    public record DisagreementListResponse(UUID runId, List<DisagreementItem> items,
                                           Instant asOf) {
    }

    // ------------------------------------------------------------------ 任务生成

    /**
     * 生成评审任务（ensure 语义幂等）：每案例补足 perCase 份开放任务（PENDING +
     * 租约有效的 IN_PROGRESS 计为开放），重复调用补 0 = 天然幂等。run 未知 →
     * empty（404 面）；caseExecutionIds=null → run 全部案例。
     */
    public Optional<GenerateResult> generate(UUID runId, List<UUID> caseExecutionIds,
                                             int perCase, String actor) {
        if (perCase < MIN_PER_CASE || perCase > MAX_PER_CASE) {
            throw new IllegalArgumentException("assignmentsPerCase 必在 " + MIN_PER_CASE
                    + ".." + MAX_PER_CASE + "（双人评审 = 2）");
        }
        if (reader.findRun(runId).isEmpty()) {
            return Optional.empty();
        }
        Map<UUID, EvalCaseRow> cases = casesOf(runId);
        List<UUID> targets = caseExecutionIds == null || caseExecutionIds.isEmpty()
                ? List.copyOf(cases.keySet()) : caseExecutionIds;
        Instant now = Instant.now();
        int created = 0;
        int openTotal = 0;
        for (UUID caseId : targets) {
            if (!cases.containsKey(caseId)) {
                throw new IllegalArgumentException(
                        "caseExecutionId 不属于该 run: " + caseId);
            }
            int open = assignments.countOpenByCase(runId, caseId, now);
            for (int i = open; i < perCase; i++) {
                assignments.insert(ReviewAssignment.pending(UUID.randomUUID(), runId,
                        caseId, actor, now));
                created++;
                open++;
            }
            openTotal += open;
        }
        return Optional.of(new GenerateResult(created, openTotal, perCase));
    }

    /** run 全部案例（键集翻页；任务生成的目标集） */
    private Map<UUID, EvalCaseRow> casesOf(UUID runId) {
        Map<UUID, EvalCaseRow> out = new LinkedHashMap<>();
        String afterScenario = null;
        Integer afterRound = null;
        while (true) {
            EvalCasePage page = reader.listCases(runId, null, afterScenario, afterRound, 200);
            for (EvalCaseRow row : page.items()) {
                out.put(row.caseExecutionId(), row);
            }
            if (!page.hasMore() || page.items().isEmpty()) {
                return out;
            }
            EvalCaseRow last = page.items().get(page.items().size() - 1);
            afterScenario = last.scenarioId();
            afterRound = last.roundNo();
        }
    }

    // ------------------------------------------------------------------ 领取（CAS）

    /**
     * 领取：任务未知 → empty（404 面）。同人持有效租约重复领取 = REPLAYED（幂等）；
     * 已提交 = CONFLICT_SUBMITTED；CAS 行数 0 = 他人持有 CONFLICT_HELD；同人对同案例
     * 已有另一份进行中任务（uq 兜底）= CONFLICT_SELF_ACTIVE。
     */
    public Optional<ClaimResult> claim(UUID assignmentId, String actor) {
        Optional<ReviewAssignment> found = assignments.findById(assignmentId);
        if (found.isEmpty()) {
            return Optional.empty();
        }
        ReviewAssignment current = found.get();
        Instant now = Instant.now();
        if (current.status() == ReviewAssignment.Status.SUBMITTED) {
            return Optional.of(new ClaimResult(ClaimStatus.CONFLICT_SUBMITTED,
                    item(current, now, null)));
        }
        if (current.status() == ReviewAssignment.Status.IN_PROGRESS
                && actor.equals(current.reviewer()) && !current.leaseExpired(now)) {
            return Optional.of(new ClaimResult(ClaimStatus.REPLAYED,
                    item(current, now, null)));
        }
        boolean won;
        try {
            won = assignments.claim(assignmentId, actor, now,
                    now.plusSeconds(LEASE_SECONDS), now);
        } catch (DuplicateKeyException e) {
            return Optional.of(new ClaimResult(ClaimStatus.CONFLICT_SELF_ACTIVE,
                    item(current, now, null)));
        }
        if (won) {
            ReviewAssignment after = assignments.findById(assignmentId)
                    .orElseThrow(() -> new IllegalStateException(
                            "领取成功后任务消失: " + assignmentId));
            return Optional.of(new ClaimResult(ClaimStatus.CLAIMED, item(after, now, null)));
        }
        ReviewAssignment loser = assignments.findById(assignmentId).orElse(current);
        ClaimStatus status = loser.status() == ReviewAssignment.Status.SUBMITTED
                ? ClaimStatus.CONFLICT_SUBMITTED : ClaimStatus.CONFLICT_HELD;
        return Optional.of(new ClaimResult(status, item(loser, now, null)));
    }

    // ------------------------------------------------------------------ 提交（CAS + insert-only 结论）

    /**
     * 提交评分：rubricVersion 必带且在注册表、verdict 封闭值域、理由必填、
     * expectedRevision 必带（400 面）；任务未知 → empty（404 面）。CAS 四锚
     * （id+reviewer+IN_PROGRESS+revision）失败重读归因 409；成功后结论落档
     * insert-only（uq(assignment_id) 兜底 = 同任务重复提交 CONFLICT_DUPLICATE）。
     */
    public Optional<SubmitResult> submit(UUID assignmentId, String actor,
                                         String rubricVersion, String verdict,
                                         Integer score, List<String> labels,
                                         String reason, List<String> evidenceRefs,
                                         Integer expectedRevision) {
        if (rubricVersion == null || rubricVersion.isBlank()) {
            throw new IllegalArgumentException("rubricVersion 必填（冻结 rubric 版本锚）");
        }
        if (rubrics.find(rubricVersion).isEmpty()) {
            throw new IllegalArgumentException("rubricVersion 未知（注册表无此冻结版本）: "
                    + rubricVersion);
        }
        ReviewVerdict.Verdict parsedVerdict = parseVerdict(verdict);
        if (reason == null || reason.isBlank()) {
            throw new IllegalArgumentException("reason 必填（§3.6 必填理由）");
        }
        if (expectedRevision == null) {
            throw new IllegalArgumentException("expectedRevision 必填（提交 CAS 锚）");
        }
        Optional<ReviewAssignment> found = assignments.findById(assignmentId);
        if (found.isEmpty()) {
            return Optional.empty();
        }
        ReviewAssignment current = found.get();
        Instant now = Instant.now();
        if (!actor.equals(current.reviewer())) {
            return Optional.of(new SubmitResult(SubmitStatus.CONFLICT_ACTOR, null,
                    current.revision()));
        }
        if (current.status() != ReviewAssignment.Status.IN_PROGRESS) {
            return Optional.of(new SubmitResult(SubmitStatus.CONFLICT_STATE, null,
                    current.revision()));
        }
        if (current.revision() != expectedRevision) {
            return Optional.of(new SubmitResult(SubmitStatus.CONFLICT_REVISION, null,
                    current.revision()));
        }
        if (!assignments.submit(assignmentId, actor, expectedRevision, now)) {
            // 并发面：预检与 CAS 之间状态漂移，重读归因
            ReviewAssignment drifted = assignments.findById(assignmentId).orElse(current);
            SubmitStatus status = !actor.equals(drifted.reviewer())
                    ? SubmitStatus.CONFLICT_ACTOR
                    : drifted.status() != ReviewAssignment.Status.IN_PROGRESS
                            ? SubmitStatus.CONFLICT_STATE : SubmitStatus.CONFLICT_REVISION;
            return Optional.of(new SubmitResult(status, null, drifted.revision()));
        }
        ReviewVerdict verdict2 = new ReviewVerdict(UUID.randomUUID(), assignmentId,
                current.runId(), current.caseExecutionId(), actor, rubricVersion,
                parsedVerdict, score, labels, reason.trim(), evidenceRefs, now);
        try {
            verdicts.insert(verdict2);
        } catch (DuplicateKeyException e) {
            return Optional.of(new SubmitResult(SubmitStatus.CONFLICT_DUPLICATE, null,
                    expectedRevision + 1));
        }
        return Optional.of(new SubmitResult(SubmitStatus.SUBMITTED, verdict2.id(),
                expectedRevision + 1));
    }

    private static ReviewVerdict.Verdict parseVerdict(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new IllegalArgumentException("verdict 必填（CORRECT/PARTIAL/INCORRECT/"
                    + "UNDECIDABLE/INSUFFICIENT_SOURCE）");
        }
        try {
            return ReviewVerdict.Verdict.valueOf(raw);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("verdict 封闭值域外: " + raw);
        }
    }

    // ------------------------------------------------------------------ 任务列表（我的/全部）

    /** scope ∈ mine/all（默认 mine）；status 按有效状态过滤；run 未知 → empty（404 面） */
    public Optional<AssignmentListResponse> listAssignments(UUID runId, String scope,
                                                            String status, String actor,
                                                            String cursor, int limit) {
        String effectiveScope = scope == null || scope.isBlank() ? "mine" : scope;
        if (!SCOPES.contains(effectiveScope)) {
            throw new IllegalArgumentException("scope 必为 " + SCOPES + ": " + scope);
        }
        ReviewAssignment.Status statusFilter = parseStatus(status);
        if (reader.findRun(runId).isEmpty()) {
            return Optional.empty();
        }
        Instant now = Instant.now();
        ReviewAssignmentRepository.AssignmentPage page = assignments.listByRun(runId,
                "mine".equals(effectiveScope) ? actor : null, statusFilter, now,
                parseCursor(cursor), limit);
        Map<UUID, EvalCaseRow> cases = casesOf(runId);
        List<AssignmentItem> items = new ArrayList<>(page.items().size());
        for (ReviewAssignment row : page.items()) {
            EvalCaseRow caseRow = cases.get(row.caseExecutionId());
            items.add(item(row, now, caseRow));
        }
        String nextCursor = null;
        if (page.hasMore() && !page.items().isEmpty()) {
            ReviewAssignment last = page.items().get(page.items().size() - 1);
            nextCursor = last.createdAt() + "/" + last.id();
        }
        return Optional.of(new AssignmentListResponse(List.copyOf(items), nextCursor,
                Instant.now()));
    }

    private static ReviewAssignment.Status parseStatus(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return ReviewAssignment.Status.valueOf(raw);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(
                    "status 必为 PENDING/IN_PROGRESS/SUBMITTED（有效状态）: " + raw);
        }
    }

    private static KeysetCursor parseCursor(String cursor) {
        if (cursor == null || cursor.isBlank()) {
            return null;
        }
        int slash = cursor.lastIndexOf('/');
        if (slash <= 0 || slash == cursor.length() - 1) {
            throw new IllegalArgumentException("cursor 非法（期形 <createdAt>/<assignmentId>）");
        }
        try {
            return new KeysetCursor(Instant.parse(cursor.substring(0, slash)),
                    UUID.fromString(cursor.substring(slash + 1)));
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("cursor 非法（期形 <createdAt>/<assignmentId>）");
        }
    }

    // ------------------------------------------------------------------ 单例工作区（盲评投影）

    /**
     * 评审工作区：任务 + 盲评案例投影 + 本案例结论历史（审计闭环）。任务未知 →
     * empty（404 面）。盲评判定 = case_version 精确键解析缺席（HOLDOUT RLS 不可见
     * /无匹配/歧义）或分区 HOLDOUT——GT 字段一律 null（EV-05 裁定同律）。
     */
    public Optional<ReviewWorkspaceResponse> workspace(UUID assignmentId) {
        Optional<ReviewAssignment> found = assignments.findById(assignmentId);
        if (found.isEmpty()) {
            return Optional.empty();
        }
        ReviewAssignment assignment = found.get();
        Instant now = Instant.now();
        EvalCaseDetailRow detail = reader
                .findCaseDetail(assignment.runId(), assignment.caseExecutionId())
                .orElseThrow(() -> new IllegalStateException(
                        "评审任务引用案例消失: " + assignment.caseExecutionId()));
        boolean blind = reader.findCaseIdentity(detail.datasetVersion(), detail.scenarioId())
                .map(id -> "HOLDOUT".equals(id.partitionClass()))
                .orElse(true);
        List<VerdictItem> history = verdicts
                .listByCase(assignment.runId(), assignment.caseExecutionId())
                .stream().map(EvalReviewService::verdictItem).toList();
        return Optional.of(new ReviewWorkspaceResponse(
                item(assignment, now, null), caseProjection(detail, blind),
                history, Instant.now()));
    }

    /** 盲评投影：blind → GT 三字段（expectedRootCause/expectedSymptomCodes/
     *  rootCauseHit）恒 null，只出症状与系统输出 */
    private ReviewCaseProjection caseProjection(EvalCaseDetailRow row, boolean blind) {
        return new ReviewCaseProjection(row.caseExecutionId(), row.scenarioId(),
                row.roundNo(), blind, row.verdict(),
                blind ? null : row.rootCauseHit(),
                blind ? null
                        : EvalQueryService.summarizeRootCause(mapper,
                                row.expectedRootCauseJson()),
                EvalQueryService.summarizeRootCause(mapper, row.actualRootCauseJson()),
                blind ? null : parseStringArray(row.expectedSymptomCodesJson()),
                parseStringArray(row.actualSymptomCodesJson()),
                failureSample(row.failureSampleJson()), row.latencyMs(),
                reportSummary(row));
    }

    /** 报告人读摘要（系统输出面，盲评可见；非 v2/解析失败如实 null） */
    private ReportSummary reportSummary(EvalCaseDetailRow row) {
        if (row.packageJson() == null || row.reportSchemaVersion() == null
                || row.reportSchemaVersion() != EvidencePackageV2.SCHEMA_VERSION) {
            return null;
        }
        try {
            EvidencePackageV2 pkg =
                    EvidencePackageV2.fromJson(mapper.readTree(row.packageJson()));
            return new ReportSummary(pkg.summary(), pkg.impact(), pkg.remediation(),
                    pkg.referenceArtifactRefs());
        } catch (Exception e) {
            return null;
        }
    }

    // ------------------------------------------------------------------ 进度分桶

    /** run 级评审进度：已评/待评/进行/分歧计数（原始计数，如实零）。run 未知 → empty */
    public Optional<ReviewProgressResponse> progress(UUID runId) {
        return reader.findRun(runId).map(run -> {
            Instant now = Instant.now();
            List<ReviewAssignment> all = assignments.listAllByRun(runId);
            long pending = 0;
            long inProgress = 0;
            long submitted = 0;
            for (ReviewAssignment a : all) {
                switch (a.effectiveStatus(now)) {
                    case PENDING -> pending++;
                    case IN_PROGRESS -> inProgress++;
                    case SUBMITTED -> submitted++;
                }
            }
            List<ReviewVerdict> runVerdicts = verdicts.listByRun(runId);
            long reviewedCases = runVerdicts.stream()
                    .map(ReviewVerdict::caseExecutionId).distinct().count();
            return new ReviewProgressResponse(runId, run.caseCount(), all.size(), pending,
                    inProgress, submitted, reviewedCases,
                    disagreements(runVerdicts).size(), Instant.now());
        });
    }

    // ------------------------------------------------------------------ 分歧检测

    /** 分歧清单：同一案例 ≥2 名评审且最新结论（verdict 或 score）不一致。run 未知 → empty */
    public Optional<DisagreementListResponse> disagreements(UUID runId) {
        if (reader.findRun(runId).isEmpty()) {
            return Optional.empty();
        }
        List<ReviewVerdict> runVerdicts = verdicts.listByRun(runId);
        Map<UUID, EvalCaseRow> cases = casesOf(runId);
        List<DisagreementItem> items = new ArrayList<>();
        for (List<ReviewVerdict> caseVerdicts : disagreements(runVerdicts)) {
            ReviewVerdict first = caseVerdicts.get(0);
            EvalCaseRow caseRow = cases.get(first.caseExecutionId());
            items.add(new DisagreementItem(first.caseExecutionId(),
                    caseRow == null ? null : caseRow.scenarioId(),
                    caseRow == null ? 0 : caseRow.roundNo(),
                    caseVerdicts.stream().map(EvalReviewService::verdictItem).toList()));
        }
        return Optional.of(new DisagreementListResponse(runId, List.copyOf(items),
                Instant.now()));
    }

    /**
     * 分歧检测（纯函数段）：按案例分组 → 每评审人取最新一行 → ≥2 人且
     * （verdict 去重 >1 或 score 去重 >1）= 分歧。返回各分歧案例的"每评审人
     * 最新行"清单（重评分新行自然取代旧行参与判定，旧行留档不参与）。
     */
    static List<List<ReviewVerdict>> disagreements(List<ReviewVerdict> runVerdicts) {
        Map<UUID, Map<String, ReviewVerdict>> latestByCaseReviewer = new LinkedHashMap<>();
        for (ReviewVerdict v : runVerdicts) {
            Map<String, ReviewVerdict> byReviewer = latestByCaseReviewer
                    .computeIfAbsent(v.caseExecutionId(), k -> new LinkedHashMap<>());
            ReviewVerdict prior = byReviewer.get(v.reviewer());
            if (prior == null || v.createdAt().isAfter(prior.createdAt())) {
                byReviewer.put(v.reviewer(), v);
            }
        }
        List<List<ReviewVerdict>> out = new ArrayList<>();
        for (Map<String, ReviewVerdict> byReviewer : latestByCaseReviewer.values()) {
            if (byReviewer.size() < 2) {
                continue;
            }
            Set<String> verdictWords = new TreeSet<>();
            Set<String> scores = new TreeSet<>();
            for (ReviewVerdict v : byReviewer.values()) {
                verdictWords.add(v.verdict().name());
                scores.add(String.valueOf(v.score()));
            }
            if (verdictWords.size() > 1 || scores.size() > 1) {
                out.add(List.copyOf(byReviewer.values()));
            }
        }
        return out;
    }

    // ------------------------------------------------------------------ 内部

    private static AssignmentItem item(ReviewAssignment a, Instant now, EvalCaseRow caseRow) {
        return new AssignmentItem(a.id(), a.runId(), a.caseExecutionId(),
                caseRow == null ? null : caseRow.scenarioId(),
                caseRow == null ? null : caseRow.roundNo(),
                a.status().name(), a.effectiveStatus(now).name(), a.reviewer(),
                a.revision(), a.claimedAt(), a.leaseExpiresAt(), a.submittedAt(),
                a.createdAt());
    }

    private static VerdictItem verdictItem(ReviewVerdict v) {
        return new VerdictItem(v.id(), v.assignmentId(), v.reviewer(), v.rubricVersion(),
                v.verdict().name(), v.score(), v.labels(), v.reason(), v.evidenceRefs(),
                v.createdAt());
    }

    /** 症状码 jsonb 数组 → 字符串表（EvalQueryService 同口径）；null/解析失败如实 null */
    private List<String> parseStringArray(String json) {
        if (json == null) {
            return null;
        }
        try {
            JsonNode node = mapper.readTree(json);
            if (!node.isArray()) {
                return null;
            }
            List<String> out = new ArrayList<>(node.size());
            for (JsonNode item : node) {
                if (item.isTextual()) {
                    out.add(item.asText());
                }
            }
            return List.copyOf(out);
        } catch (Exception e) {
            return null;
        }
    }

    /** failure_sample jsonb：文本标量取字面值，其余取 JSON 原文（EvalQueryService 同口径） */
    private String failureSample(String json) {
        if (json == null) {
            return null;
        }
        try {
            JsonNode node = mapper.readTree(json);
            return node.isTextual() ? node.asText() : json;
        } catch (Exception e) {
            return json;
        }
    }
}
