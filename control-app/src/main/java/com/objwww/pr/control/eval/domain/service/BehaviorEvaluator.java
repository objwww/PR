package com.objwww.pr.control.eval.domain.service;

import com.objwww.pr.control.alert.domain.claim.ClaimStatus;
import com.objwww.pr.control.alert.domain.model.EvidencePackageV2;
import com.objwww.pr.control.alert.domain.model.ReportClaim;
import com.objwww.pr.control.eval.domain.model.BehaviorCheckStatus;
import com.objwww.pr.control.eval.domain.model.BehaviorEvaluation;
import com.objwww.pr.control.eval.domain.model.BehaviorInput;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

/**
 * 逐案行为评测纯函数（ME-T04/D04 第 3/4/5 条；L0：不调 LLM、不碰 DB/HTTP）。
 *
 * <p>六项检查（确定性字段优先，语义关系留给后续裁判——本版支持性为词法启发式，
 * 明确打标不冒充语义判定）：
 * <ul>
 *   <li>{@value #CHECK_ATTACHMENT}：「证据引用非空」改名「引用附带率」——TRUE 根因
 *       claim 携带引用的比例（旧 conclusionGrounded 同口径，原列原义不动）；</li>
 *   <li>{@value #CHECK_EXISTENCE}：引用存在——ref 解析到真实证据行；读面不可用 →
 *       NOT_ASSESSED，dangling 与不可读不混同；</li>
 *   <li>{@value #CHECK_ATTRIBUTION}：同 run/租户归属——跨 run 引用 FAIL 且不进入
 *       后续可用证据集合（EV-03）；</li>
 *   <li>{@value #CHECK_TIME_WINDOW}：时窗有效——证据时间界与 run 窗口相交；界缺失 →
 *       NOT_ASSESSED，不猜；</li>
 *   <li>{@value #CHECK_SUPPORT}：支持/反驳——v1 词法启发式（claim 关键 token 命中 =
 *       支持倾向；token 与否定标记共现 = 反驳），语义判定归后续校准裁判/人工；</li>
 *   <li>{@value #CHECK_COVERAGE}：证据检查点覆盖——golden 检查点按实际
 *       evidence/result 语料子串命中；允许多个等价证据来源，不强制唯一工具顺序
 *       （EV-04）；仅在报告写关键词不算取证（EV-01）。</li>
 * </ul>
 * 缺证据不猜通过（EV-05）：正文不可得/语料截断/读面缺席一律 NOT_ASSESSED。
 */
public final class BehaviorEvaluator {

    public static final String GRADER_VERSION = "behavior-v1";

    public static final String CHECK_ATTACHMENT = "citation_attachment";
    public static final String CHECK_EXISTENCE = "citation_existence";
    public static final String CHECK_ATTRIBUTION = "citation_attribution";
    public static final String CHECK_TIME_WINDOW = "citation_time_window";
    public static final String CHECK_SUPPORT = "citation_support";
    public static final String CHECK_COVERAGE = "evidence_checkpoint_coverage";

    private static final List<String> CHECK_NAMES = List.of(CHECK_ATTACHMENT,
            CHECK_EXISTENCE, CHECK_ATTRIBUTION, CHECK_TIME_WINDOW, CHECK_SUPPORT,
            CHECK_COVERAGE);

    /** 反驳词法标记（v1 启发式；词表扩缩 = grader 版本变更，不原地改义） */
    private static final List<String> CONTRADICTION_MARKERS = List.of(
            "无异常", "未见异常", "指标正常", "运行正常", "服务正常",
            "no fault", "no error", "healthy", "not related", "ruled out", "排除");

    private final String graderVersion;

    public BehaviorEvaluator() {
        this(GRADER_VERSION);
    }

    /** 显式 grader 版本（重评并存：新版本新行，旧记录不覆盖——D04 第 6/7 条） */
    public BehaviorEvaluator(String graderVersion) {
        this.graderVersion = Objects.requireNonNull(graderVersion, "graderVersion 不得为 null");
    }

    public BehaviorEvaluation evaluate(BehaviorInput in) {
        Objects.requireNonNull(in, "in 不得为 null");
        List<BehaviorEvaluation.Check> checks = new ArrayList<>();
        List<BehaviorEvaluation.Metric> metrics = new ArrayList<>();
        List<String> failureLabels = new ArrayList<>();

        EvidencePackageV2 pkg = in.evidence();
        boolean unresolved = pkg != null && normalize(pkg.rootCause().component())
                .equals("unresolved");
        List<ClaimWithIndex> trueClaims = trueRootCauseClaims(pkg);

        // ---------------- 1. 引用附带率（旧「证据引用非空」改名） ----------------
        String attachmentNaReason = pkg == null ? "NO_REPORT"
                : unresolved ? "UNRESOLVED_SENTINEL"
                : trueClaims.isEmpty() ? "NO_TRUE_ROOT_CAUSE_CLAIM" : null;
        List<BehaviorInput.ResolvedCitation> allCitations = in.citations();
        if (attachmentNaReason != null) {
            checks.add(check(CHECK_ATTACHMENT, BehaviorCheckStatus.NOT_APPLICABLE,
                    attachmentNaReason, List.of()));
        } else {
            long attached = trueClaims.stream().filter(c -> !c.refs().isEmpty()).count();
            BehaviorCheckStatus status = attached == trueClaims.size()
                    ? BehaviorCheckStatus.PASS : BehaviorCheckStatus.FAIL;
            checks.add(check(CHECK_ATTACHMENT, status,
                    status == BehaviorCheckStatus.PASS ? "ATTACHMENT_FULL" : "CITATION_MISSING",
                    refsOf(allCitations)));
            metrics.add(new BehaviorEvaluation.Metric(
                    "citation_attachment_rate", attached, trueClaims.size()));
            if (status == BehaviorCheckStatus.FAIL) {
                failureLabels.add("CITATION_MISSING");
            }
        }

        // ---------------- 2. 引用存在 ----------------
        BehaviorCheckStatus existenceStatus;
        String existenceReason;
        long resolved = allCitations.stream().filter(c -> c.evidenceId() != null).count();
        if (attachmentNaReason != null) {
            existenceStatus = BehaviorCheckStatus.NOT_APPLICABLE;
            existenceReason = attachmentNaReason;
        } else if (allCitations.isEmpty()) {
            existenceStatus = BehaviorCheckStatus.NOT_APPLICABLE;
            existenceReason = "NO_CITATIONS";
        } else if (!in.evidenceReadAvailable()) {
            existenceStatus = BehaviorCheckStatus.NOT_ASSESSED;
            existenceReason = "EVIDENCE_READ_UNAVAILABLE";
        } else if (resolved == allCitations.size()) {
            existenceStatus = BehaviorCheckStatus.PASS;
            existenceReason = "CITATIONS_RESOLVED";
            metrics.add(new BehaviorEvaluation.Metric(
                    "citation_existence_rate", resolved, allCitations.size()));
        } else {
            existenceStatus = BehaviorCheckStatus.FAIL;
            existenceReason = "CITATION_DANGLING";
            metrics.add(new BehaviorEvaluation.Metric(
                    "citation_existence_rate", resolved, allCitations.size()));
            failureLabels.add("CITATION_DANGLING");
        }
        checks.add(check(CHECK_EXISTENCE, existenceStatus, existenceReason,
                resolvedRefs(allCitations)));

        // ---------------- 3. 同 run/租户归属 ----------------
        List<BehaviorInput.ResolvedCitation> resolvedCitations = allCitations.stream()
                .filter(c -> c.evidenceId() != null).toList();
        List<BehaviorInput.ResolvedCitation> attributed = resolvedCitations.stream()
                .filter(c -> in.rcaRunId() != null && in.rcaRunId().equals(c.evidenceRunId()))
                .toList();
        BehaviorCheckStatus attributionStatus;
        String attributionReason;
        if (existenceStatus == BehaviorCheckStatus.NOT_APPLICABLE) {
            attributionStatus = BehaviorCheckStatus.NOT_APPLICABLE;
            attributionReason = existenceReason;
        } else if (existenceStatus == BehaviorCheckStatus.NOT_ASSESSED) {
            attributionStatus = BehaviorCheckStatus.NOT_ASSESSED;
            attributionReason = existenceReason;
        } else if (resolvedCitations.isEmpty()) {
            attributionStatus = BehaviorCheckStatus.NOT_APPLICABLE;
            attributionReason = "NO_RESOLVED_CITATION";
        } else if (in.rcaRunId() == null) {
            attributionStatus = BehaviorCheckStatus.NOT_ASSESSED;
            attributionReason = "NO_RUN_CONTEXT";
        } else if (attributed.size() == resolvedCitations.size()) {
            attributionStatus = BehaviorCheckStatus.PASS;
            attributionReason = "SAME_RUN";
            metrics.add(new BehaviorEvaluation.Metric("citation_attribution_rate",
                    attributed.size(), resolvedCitations.size()));
        } else {
            attributionStatus = BehaviorCheckStatus.FAIL;
            attributionReason = "CITATION_CROSS_RUN";
            metrics.add(new BehaviorEvaluation.Metric("citation_attribution_rate",
                    attributed.size(), resolvedCitations.size()));
            failureLabels.add("CITATION_CROSS_RUN");
        }
        checks.add(check(CHECK_ATTRIBUTION, attributionStatus, attributionReason,
                resolvedRefs(attributed)));

        // ---------------- 4. 时窗有效 ----------------
        BehaviorCheckStatus windowStatus;
        String windowReason;
        List<BehaviorInput.ResolvedCitation> windowChecked = new ArrayList<>();
        long inWindow = 0;
        if (attributionStatus == BehaviorCheckStatus.NOT_APPLICABLE) {
            windowStatus = BehaviorCheckStatus.NOT_APPLICABLE;
            windowReason = attributionReason;
        } else if (attributionStatus == BehaviorCheckStatus.NOT_ASSESSED) {
            windowStatus = BehaviorCheckStatus.NOT_ASSESSED;
            windowReason = attributionReason;
        } else if (attributed.isEmpty()) {
            windowStatus = BehaviorCheckStatus.NOT_APPLICABLE;
            windowReason = "NO_ATTRIBUTED_CITATION";
        } else if (in.runStartedAt() == null) {
            windowStatus = BehaviorCheckStatus.NOT_ASSESSED;
            windowReason = "RUN_WINDOW_UNKNOWN";
        } else {
            Instant runStart = in.runStartedAt();
            Instant runEnd = in.runFinishedAt() != null ? in.runFinishedAt() : runStart;
            for (BehaviorInput.ResolvedCitation c : attributed) {
                if (c.timeStart() == null && c.timeEnd() == null) {
                    continue; // 双界缺失 = 时窗不可评，不进分母不猜
                }
                windowChecked.add(c);
                boolean afterStart = c.timeEnd() == null || !c.timeEnd().isBefore(runStart);
                boolean beforeEnd = c.timeStart() == null || !c.timeStart().isAfter(runEnd);
                if (afterStart && beforeEnd) {
                    inWindow++;
                }
            }
            if (windowChecked.isEmpty()) {
                windowStatus = BehaviorCheckStatus.NOT_ASSESSED;
                windowReason = "EVIDENCE_TIME_BOUNDS_MISSING";
            } else if (inWindow == windowChecked.size()) {
                windowStatus = BehaviorCheckStatus.PASS;
                windowReason = "IN_WINDOW";
                metrics.add(new BehaviorEvaluation.Metric(
                        "citation_time_window_rate", inWindow, windowChecked.size()));
            } else {
                windowStatus = BehaviorCheckStatus.FAIL;
                windowReason = "TIME_WINDOW_VIOLATION";
                metrics.add(new BehaviorEvaluation.Metric(
                        "citation_time_window_rate", inWindow, windowChecked.size()));
                failureLabels.add("TIME_WINDOW_VIOLATION");
            }
        }
        checks.add(check(CHECK_TIME_WINDOW, windowStatus, windowReason,
                resolvedRefs(windowChecked)));

        // ---------------- 5. 支持/反驳（v1 词法启发式，语义判定归后续裁判） ----------------
        List<BehaviorInput.ResolvedCitation> usable = attributed.stream()
                .filter(c -> c.contentText() != null).toList();
        BehaviorCheckStatus supportStatus;
        String supportReason;
        if (attachmentNaReason != null) {
            supportStatus = BehaviorCheckStatus.NOT_APPLICABLE;
            supportReason = attachmentNaReason;
        } else if (allCitations.isEmpty()) {
            supportStatus = BehaviorCheckStatus.NOT_APPLICABLE;
            supportReason = "NO_CITATIONS";
        } else if (usable.isEmpty()) {
            // 归属失败引用不进可用集合；正文不可得（digest-only/截断读失败）不猜
            supportStatus = BehaviorCheckStatus.NOT_ASSESSED;
            supportReason = attributed.isEmpty() ? "NO_USABLE_EVIDENCE" : "CONTENT_UNAVAILABLE";
        } else {
            boolean anyContradicted = false;
            boolean allSupported = true;
            for (ClaimWithIndex claim : trueClaims) {
                List<BehaviorInput.ResolvedCitation> cited = usable.stream()
                        .filter(c -> c.claimIndex() == claim.index()).toList();
                if (cited.isEmpty()) {
                    allSupported = false;
                    continue;
                }
                List<String> tokens = claimTokens(claim.claim());
                boolean supported = cited.stream().anyMatch(c ->
                        containsAny(normalize(c.contentText()), tokens));
                boolean contradicted = supported && cited.stream().anyMatch(c -> {
                    String text = normalize(c.contentText());
                    return containsAny(text, tokens)
                            && CONTRADICTION_MARKERS.stream().anyMatch(text::contains);
                });
                if (contradicted) {
                    anyContradicted = true;
                } else if (!supported) {
                    allSupported = false;
                }
            }
            if (anyContradicted) {
                supportStatus = BehaviorCheckStatus.FAIL;
                supportReason = "SUPPORT_CONTRADICTED";
                failureLabels.add("SUPPORT_CONTRADICTED");
            } else if (allSupported) {
                supportStatus = BehaviorCheckStatus.PASS;
                supportReason = "SUPPORT_TOKEN_MATCH";
            } else {
                supportStatus = BehaviorCheckStatus.NOT_ASSESSED;
                supportReason = "SUPPORT_UNDETERMINED";
            }
        }
        checks.add(check(CHECK_SUPPORT, supportStatus, supportReason, resolvedRefs(usable)));

        // ---------------- 6. 证据检查点覆盖（实际 evidence/result 语料） ----------------
        List<String> checkpoints = in.expectedCheckpoints();
        Integer evidenceCovered = null;
        Integer evidenceTotal = null;
        BehaviorCheckStatus coverageStatus;
        String coverageReason;
        if (checkpoints.isEmpty()) {
            coverageStatus = BehaviorCheckStatus.NOT_APPLICABLE;
            coverageReason = "NO_CHECKPOINTS";
        } else {
            evidenceTotal = checkpoints.size();
            List<BehaviorInput.EvidenceContent> corpus = in.runEvidence().stream()
                    .filter(e -> e.contentText() != null).toList();
            if (in.rcaRunId() == null) {
                coverageStatus = BehaviorCheckStatus.NOT_ASSESSED;
                coverageReason = "TRACE_MISSING";
            } else if (!in.evidenceReadAvailable()) {
                // 证据读面缺席 ≠ 零取证——不可读如实 NOT_ASSESSED，不冒充 FAIL/PASS
                coverageStatus = BehaviorCheckStatus.NOT_ASSESSED;
                coverageReason = "EVIDENCE_READ_UNAVAILABLE";
            } else if (in.runEvidence().isEmpty()) {
                // run 存在但零证据产出 = 真实零取证（EV-01：报告写满关键词不算取证）
                evidenceCovered = 0;
                coverageStatus = BehaviorCheckStatus.FAIL;
                coverageReason = "NO_RUN_EVIDENCE";
                metrics.add(new BehaviorEvaluation.Metric(
                        "evidence_checkpoint_coverage", 0, evidenceTotal));
                failureLabels.add("CHECKPOINT_EVIDENCE_MISSING");
            } else if (corpus.isEmpty()) {
                coverageStatus = BehaviorCheckStatus.NOT_ASSESSED;
                coverageReason = "EVIDENCE_CORPUS_UNAVAILABLE";
            } else {
                int covered = 0;
                for (String cp : checkpoints) {
                    String needle = normalize(cp);
                    boolean hit = corpus.stream().anyMatch(e ->
                            normalize(e.contentText()).contains(needle));
                    if (hit) {
                        covered++;
                    }
                }
                evidenceCovered = covered;
                boolean anyTruncated = in.runEvidence().stream()
                        .anyMatch(BehaviorInput.EvidenceContent::contentTruncated);
                if (covered == evidenceTotal) {
                    coverageStatus = BehaviorCheckStatus.PASS;
                    coverageReason = "EVIDENCE_COVERAGE_FULL";
                    metrics.add(new BehaviorEvaluation.Metric(
                            "evidence_checkpoint_coverage", covered, evidenceTotal));
                } else if (anyTruncated) {
                    // 语料截断不能排除未命中检查点藏在截断段——不猜 FAIL 也不猜 PASS
                    coverageStatus = BehaviorCheckStatus.NOT_ASSESSED;
                    coverageReason = "CORPUS_TRUNCATED";
                } else {
                    coverageStatus = BehaviorCheckStatus.FAIL;
                    coverageReason = "CHECKPOINT_EVIDENCE_MISSING";
                    metrics.add(new BehaviorEvaluation.Metric(
                            "evidence_checkpoint_coverage", covered, evidenceTotal));
                    failureLabels.add("CHECKPOINT_EVIDENCE_MISSING");
                }
            }
        }
        checks.add(check(CHECK_COVERAGE, coverageStatus, coverageReason,
                corpusRefs(in.runEvidence())));

        return new BehaviorEvaluation(graderVersion, traceDigest(in),
                new BehaviorEvaluation.Coverage(in.textCheckpointsCovered(),
                        in.textCheckpointsTotal(), evidenceCovered, evidenceTotal),
                checks, metrics, List.copyOf(failureLabels), resolvedRefs(attributed));
    }

    /** 观测读失败 ERROR 行（SAFE-07 同律：读失败如实落 ERROR，不冒充零问题通过） */
    public BehaviorEvaluation readError() {
        List<BehaviorEvaluation.Check> checks = CHECK_NAMES.stream()
                .map(n -> check(n, BehaviorCheckStatus.ERROR, "TRACE_READ_ERROR", List.of()))
                .toList();
        return new BehaviorEvaluation(graderVersion, null,
                new BehaviorEvaluation.Coverage(null, null, null, null),
                checks, List.of(), List.of("TRACE_READ_ERROR"), List.of());
    }

    // ------------------------------------------------------------------ 内部

    /** 轨迹投影内容摘要（确定性：同投影同 digest——EV-06 幂等校验面） */
    private static String traceDigest(BehaviorInput in) {
        StringBuilder canonical = new StringBuilder("run:")
                .append(in.rcaRunId() == null ? "~null~" : in.rcaRunId());
        in.toolCalls().stream()
                .sorted(Comparator.comparingLong(BehaviorInput.TraceToolCall::callSeq)
                        .thenComparing(c -> c.invocationId().toString()))
                .forEach(c -> canonical.append("\ncall:").append(c.invocationId())
                        .append('|').append(c.taskId())
                        .append('|').append(c.toolName())
                        .append('|').append(c.toolVersion())
                        .append('|').append(c.callSeq())
                        .append('|').append(c.actionDigest())
                        .append('|').append(c.state())
                        .append('|').append(c.reasonCode())
                        .append('|').append(c.resultRef()));
        in.runEvidence().stream()
                .sorted(Comparator.comparing(e -> e.evidenceId().toString()))
                .forEach(e -> canonical.append("\nev:").append(e.evidenceId())
                        .append('|').append(e.payloadDigest()));
        return com.objwww.pr.shared.Digests.sha256Hex(canonical.toString());
    }

    private record ClaimWithIndex(int index, ReportClaim claim, List<String> refs) {
    }

    private static List<ClaimWithIndex> trueRootCauseClaims(EvidencePackageV2 pkg) {
        if (pkg == null) {
            return List.of();
        }
        List<ClaimWithIndex> result = new ArrayList<>();
        List<ReportClaim> claims = pkg.claims();
        for (int i = 0; i < claims.size(); i++) {
            ReportClaim claim = claims.get(i);
            if ("root_cause".equals(claim.claimType()) && claim.status() == ClaimStatus.TRUE) {
                result.add(new ClaimWithIndex(i, claim, claim.evidenceRefs()));
            }
        }
        return result;
    }

    private static List<String> claimTokens(ReportClaim claim) {
        List<String> tokens = new ArrayList<>();
        if (!normalize(claim.component()).isEmpty()) {
            tokens.add(normalize(claim.component()));
        }
        if (!normalize(claim.faultType()).isEmpty()) {
            tokens.add(normalize(claim.faultType()));
        }
        return tokens;
    }

    private static boolean containsAny(String text, List<String> tokens) {
        return tokens.stream().anyMatch(text::contains);
    }

    private static BehaviorEvaluation.Check check(String name, BehaviorCheckStatus status,
                                                  String reason, List<String> refs) {
        return new BehaviorEvaluation.Check(name, status, reason, refs);
    }

    private static List<String> refsOf(List<BehaviorInput.ResolvedCitation> citations) {
        return citations.stream().map(BehaviorInput.ResolvedCitation::ref).distinct().sorted()
                .toList();
    }

    private static List<String> resolvedRefs(List<BehaviorInput.ResolvedCitation> citations) {
        Set<String> refs = new LinkedHashSet<>();
        for (BehaviorInput.ResolvedCitation c : citations) {
            if (c.evidenceId() != null) {
                refs.add(c.evidenceId().toString());
            }
        }
        return refs.stream().sorted().toList();
    }

    private static List<String> corpusRefs(List<BehaviorInput.EvidenceContent> corpus) {
        return corpus.stream().map(e -> e.evidenceId().toString()).distinct().sorted().toList();
    }

    /** 期望/实际统一规范化（与 ScenarioEvaluator 同法：trim + ASCII casefold） */
    private static String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }
}
