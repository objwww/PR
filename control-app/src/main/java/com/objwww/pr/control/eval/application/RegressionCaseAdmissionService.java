package com.objwww.pr.control.eval.application;

import com.objwww.pr.control.alert.domain.evidence.EvidenceEnvelope;
import com.objwww.pr.control.alert.domain.evidence.EvidenceRepository;
import com.objwww.pr.control.alert.domain.model.RcaReport;
import com.objwww.pr.control.alert.domain.model.TypedRootCause;
import com.objwww.pr.control.alert.domain.model.ValidationStatus;
import com.objwww.pr.control.alert.domain.model.ReportFeedback;
import com.objwww.pr.control.alert.domain.repository.RcaReportRepository;
import com.objwww.pr.control.alert.domain.repository.RcaRunRepository;
import com.objwww.pr.control.alert.domain.repository.ReportFeedbackPort;
import com.objwww.pr.control.eval.domain.model.CaseVersion;
import com.objwww.pr.control.eval.domain.model.DatasetVersion;
import com.objwww.pr.control.eval.domain.model.EvalCaseV1;
import com.objwww.pr.control.eval.domain.model.PartitionClass;
import com.objwww.pr.control.eval.domain.model.RegressionCandidate;
import com.objwww.pr.control.eval.domain.model.RegressionReview;
import com.objwww.pr.control.eval.domain.model.SourceClass;
import com.objwww.pr.control.eval.domain.repository.DatasetVersionRepository;
import com.objwww.pr.control.eval.domain.repository.RegressionCandidatePort;
import com.objwww.pr.shared.Digest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * 回归案例准入（OP-01，后续优化方案 §3.1/§5.2）：已终态报告（可挂反馈）→
 * PENDING_REVIEW 候选 → 独立审核 → ACCEPTED 后 materialize 写不可变
 * DatasetVersion/CaseVersion。三层防自动采信：反馈只是原料（§5.2 反馈≠GT）；
 * 审核意见 append-only 且相异即 DISPUTED 进裁决（FO27）；入集 GT 由人工在
 * materialize 显式提供（纠正答案=新 CaseVersion，不覆盖已发布版本，FO04）。
 *
 * <p>sourceDigest 冻结来源指纹（run+报告 digest+证据 canonical 全集）——同源
 * 同 caseKey 幂等不堆重复候选（FO02）；源报告后续修订不静默改候选（FO28）。
 * 零证据轨迹拒绝入集（FO03 来源缺失不能编造）。症状/答案隔离与 GT 不可见由
 * 既有数据集四分区 RLS 承载（M5-02 权限矩阵）；同族跨分区的拆分尝试由 V20
 * 唯一约束兜底拒绝（FO05）。
 */
public class RegressionCaseAdmissionService {

    private static final Logger log =
            LoggerFactory.getLogger(RegressionCaseAdmissionService.class);

    /** 来源指纹与 rawArtifact 的适配器版本（案例内容形状变更=新版本） */
    public static final String ADAPTER_VERSION = "regression-admission.v1";

    private final RcaReportRepository reports;
    private final RcaRunRepository runs;
    private final EvidenceRepository evidence;
    private final ReportFeedbackPort feedbacks;
    private final RegressionCandidatePort candidates;
    private final DatasetVersionRepository datasets;
    private final Clock clock;

    public RegressionCaseAdmissionService(RcaReportRepository reports,
            RcaRunRepository runs, EvidenceRepository evidence,
            ReportFeedbackPort feedbacks, RegressionCandidatePort candidates,
            DatasetVersionRepository datasets, Clock clock) {
        this.reports = Objects.requireNonNull(reports, "reports");
        this.runs = Objects.requireNonNull(runs, "runs");
        this.evidence = Objects.requireNonNull(evidence, "evidence");
        this.feedbacks = Objects.requireNonNull(feedbacks, "feedbacks");
        this.candidates = Objects.requireNonNull(candidates, "candidates");
        this.datasets = Objects.requireNonNull(datasets, "datasets");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /** 入集提案：reportId 必填；feedbackId 可选（有反馈走反馈→候选链） */
    public record Proposal(UUID reportId, UUID feedbackId, String caseKey,
            String scenarioFamilyId, String createdBy) {
    }

    /** propose 结果：replayed=同源同 caseKey 已有候选（幂等锚命中） */
    public record ProposeResult(RegressionCandidate candidate, boolean replayed,
            String refusal) {

        public static ProposeResult refused(String reason) {
            return new ProposeResult(null, false, reason);
        }
    }

    public ProposeResult propose(Proposal p) {
        RcaReport report = reports.findById(p.reportId()).orElse(null);
        if (report == null) {
            return ProposeResult.refused("来源契约：报告不存在（轨迹不可溯源）");
        }
        if (report.validationStatus() != ValidationStatus.STRUCTURE_VALIDATED) {
            return ProposeResult.refused("来源契约：报告结构未通过（"
                    + report.validationStatus() + "）——不合格报告不作回归原料");
        }
        var run = runs.findById(report.runId()).orElse(null);
        if (run == null || run.state().isActive()) {
            return ProposeResult.refused("来源契约：run 未终态——调查未封存不入集");
        }
        if (p.feedbackId() != null) {
            ReportFeedback feedback = feedbacks.findById(p.feedbackId()).orElse(null);
            if (feedback == null || !feedback.reportId().equals(p.reportId())) {
                return ProposeResult.refused("来源契约：反馈不存在或不属于该报告");
            }
        }
        List<EvidenceEnvelope> rows = evidence.findByRunId(report.runId());
        if (rows.isEmpty()) {
            return ProposeResult.refused("来源契约：零证据轨迹不入集（无证经验，FO03）");
        }
        String reportDigest = Digest.sha256Of(report.packageJson()).value();
        String sourceDigest = sourceDigestOf(report, rows);
        RegressionCandidate fresh = new RegressionCandidate(UUID.randomUUID(),
                report.runId(), report.id(), p.feedbackId(), sourceDigest,
                p.caseKey(), p.scenarioFamilyId(),
                RegressionCandidate.ST_PENDING_REVIEW, p.createdBy(),
                clock.instant(), null, null, null);
        RegressionCandidate stored = candidates.insertIfAbsent(fresh);
        boolean replayed = !stored.id().equals(fresh.id());
        if (!replayed) {
            log.info("回归候选提出 case={} family={} report={} feedback={} by={}",
                    p.caseKey(), p.scenarioFamilyId(), p.reportId(), p.feedbackId(),
                    p.createdBy());
        }
        return new ProposeResult(stored, replayed, null);
    }

    /** 审核：意见 append-only；候选状态=意见集的纯函数（相异→DISPUTED） */
    public RegressionCandidate review(UUID candidateId, String reviewer,
            String verdict, String reason) {
        RegressionCandidate candidate = candidates.findById(candidateId).orElseThrow(
                () -> new IllegalArgumentException("候选不存在: " + candidateId));
        RegressionReview review = candidates.insertReview(new RegressionReview(
                UUID.randomUUID(), candidateId, reviewer, verdict, reason,
                clock.instant()));
        List<RegressionReview> reviews = candidates.reviewsOf(candidateId);
        String computed = stateOf(reviews);
        String reviewerForState = reviews.stream()
                .max(Comparator.comparing(RegressionReview::createdAt))
                .map(RegressionReview::reviewer).orElse(reviewer);
        String reasonForState = reviews.stream()
                .map(r -> r.reviewer() + "=" + r.verdict())
                .reduce((a, b) -> a + ", " + b).orElse(reason);
        if (computed.equals(candidate.state())) {
            return candidate;
        }
        RegressionCandidate next = candidate.withReviewed(computed, reviewerForState,
                reasonForState, clock.instant());
        return candidates.casState(candidateId, candidate.state(), next)
                .orElseThrow(() -> new IllegalStateException(
                        "候选状态已被并发推进（重读后重试）: " + candidateId));
    }

    public List<RegressionReview> reviewsOf(UUID candidateId) {
        return candidates.reviewsOf(candidateId);
    }

    /** materialize 入集请求：GT 由人工显式提供（反馈/审核结论不作 GT）；
     *  expectedEvidenceCheckpoints = P3 路径维 GT（可空——无则路径维 NOT_APPLICABLE） */
    public record MaterializeCommand(String datasetName, String datasetVersion,
            PartitionClass partition,
            TypedRootCause expectedRootCause, List<String> expectedSymptomCodes,
            List<String> expectedEvidenceCheckpoints,
            String actor) {
    }

    public record MaterializeResult(DatasetVersion dataset, CaseVersion caseVersion,
            boolean replayed) {
    }

    /** ACCEPTED 候选 → 不可变 DatasetVersion + CaseVersion（FO01/FO28） */
    public MaterializeResult materialize(UUID candidateId, MaterializeCommand cmd) {
        Objects.requireNonNull(cmd, "cmd");
        RegressionCandidate candidate = candidates.findById(candidateId).orElseThrow(
                () -> new IllegalArgumentException("候选不存在: " + candidateId));
        if (!RegressionCandidate.ST_ACCEPTED.equals(candidate.state())) {
            throw new IllegalStateException("仅 ACCEPTED 候选可入集（state="
                    + candidate.state() + "）——反馈/分歧候选不能绕过审核");
        }
        Map<String, Object> rawArtifact = new LinkedHashMap<>();
        rawArtifact.put("source_run_id", candidate.sourceRunId().toString());
        rawArtifact.put("source_report_id", candidate.sourceReportId().toString());
        rawArtifact.put("source_digest", candidate.sourceDigest());
        if (candidate.sourceFeedbackId() != null) {
            rawArtifact.put("source_feedback_id",
                    candidate.sourceFeedbackId().toString());
        }
        rawArtifact.put("note", "调查可见输入=来源引用；症状与答案隔离由数据集分区承载");
        // P3 路径维 GT：人工显式检查点走保留键（EvalCaseV1 契约不动，扩展走 artifact 面）
        if (cmd.expectedEvidenceCheckpoints() != null
                && !cmd.expectedEvidenceCheckpoints().isEmpty()) {
            rawArtifact.put("gt_evidence_checkpoints", cmd.expectedEvidenceCheckpoints());
        }
        EvalCaseV1 caseContent = new EvalCaseV1(candidate.caseKey(),
                candidate.scenarioFamilyId(), cmd.expectedRootCause(),
                cmd.expectedSymptomCodes(), rawArtifact);
        Digest contentDigest = Digest.sha256Of(
                com.objwww.pr.control.alert.domain.tool.InternalCanonicalJsonV1
                        .canonicalize(rawArtifact)
                        + "|" + candidate.caseKey() + "|" + candidate.scenarioFamilyId());
        DatasetVersion dataset = datasets
                .findDataset(cmd.datasetName(), cmd.datasetVersion())
                .orElseGet(() -> {
                    DatasetVersion fresh = new DatasetVersion(UUID.randomUUID(),
                            "rca_report_feedback", cmd.datasetName(),
                            cmd.datasetVersion(), "run://" + candidate.sourceRunId(),
                            "internal", "INTERNAL", contentDigest, ADAPTER_VERSION,
                            clock.instant(), SourceClass.PRIVATE,
                            cmd.partition(),
                            DatasetVersion.familyDigest(List.of(caseContent)));
                    datasets.insertDatasetVersion(fresh);
                    return fresh;
                });
        CaseVersion caseRow = new CaseVersion(UUID.randomUUID(), dataset.id(),
                caseContent.caseKey(), caseContent.scenarioFamilyId(),
                clock.instant(), null, contentDigest,
                "candidate:" + candidateId, caseContent);
        boolean inserted = datasets.insertCaseVersion(caseRow);
        log.info("回归案例入集 case={} dataset={}:{} partition={} actor={} replayed={}",
                candidate.caseKey(), cmd.datasetName(), cmd.datasetVersion(),
                cmd.partition(), cmd.actor(), !inserted);
        return new MaterializeResult(dataset, caseRow, !inserted);
    }

    public List<RegressionCandidate> byState(String state) {
        return candidates.findByState(state);
    }

    /** 候选状态 = 审核意见集纯函数：零意见=PENDING；全同=该结论；相异=DISPUTED */
    private static String stateOf(List<RegressionReview> reviews) {
        if (reviews.isEmpty()) {
            return RegressionCandidate.ST_PENDING_REVIEW;
        }
        boolean same = reviews.stream().map(RegressionReview::verdict).distinct()
                .count() == 1;
        if (!same) {
            return RegressionCandidate.ST_DISPUTED;
        }
        return switch (reviews.get(0).verdict()) {
            case RegressionReview.ACCEPTED_FOR_CANDIDATE ->
                    RegressionCandidate.ST_ACCEPTED;
            case RegressionReview.REJECTED -> RegressionCandidate.ST_REJECTED;
            default -> RegressionCandidate.ST_NEEDS_EVIDENCE;
        };
    }

    /** 来源指纹：run+报告 digest+证据 canonical 全集（排序稳定） */
    private static String sourceDigestOf(RcaReport report,
            List<EvidenceEnvelope> rows) {
        StringBuilder sb = new StringBuilder(report.runId().toString())
                .append('|').append(Digest.sha256Of(report.packageJson()).value());
        rows.stream()
                .sorted(Comparator.comparing(e -> e.evidenceId().toString()))
                .forEach(e -> sb.append('|').append(e.canonicalPayload()));
        return Digest.sha256Of(sb.toString()).value();
    }
}
