package com.objwww.pr.control.eval.application;

import com.objwww.pr.control.alert.domain.evidence.EvidenceEnvelope;
import com.objwww.pr.control.alert.domain.model.RcaReport;
import com.objwww.pr.control.alert.domain.model.RcaRun;
import com.objwww.pr.control.alert.domain.model.RcaRunState;
import com.objwww.pr.control.alert.domain.model.ReportFeedback;
import com.objwww.pr.control.alert.domain.model.RunTrigger;
import com.objwww.pr.control.alert.domain.model.TypedRootCause;
import com.objwww.pr.control.alert.domain.model.ValidationStatus;
import com.objwww.pr.control.alert.support.AlertInMemoryStores;
import com.objwww.pr.control.eval.domain.model.DatasetVersion;
import com.objwww.pr.control.eval.domain.model.PartitionClass;
import com.objwww.pr.control.eval.domain.model.RegressionCandidate;
import com.objwww.pr.control.eval.domain.model.RegressionReview;
import com.objwww.pr.shared.Digest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * OP-01 回归案例准入单测（FO01~05/26~28）：来源契约门（FO01/FO03）、同源幂等
 * 不堆重复候选（FO02）、materialize 不可变+重放收敛（FO04）、sourceDigest 冻结
 * （FO28）、审核意见纯函数状态机含 DISPUTED（FO26/FO27）、GT 人工显式提供
 * （反馈/审核结论不作 GT）。
 */
class RegressionCaseAdmissionServiceTest {

    private static final Instant NOW = Instant.parse("2026-09-13T00:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

    private final AlertInMemoryStores.Runs runs = new AlertInMemoryStores.Runs();
    private final AlertInMemoryStores.Reports reports = new AlertInMemoryStores.Reports();
    private final AlertInMemoryStores.Evidences evidences = new AlertInMemoryStores.Evidences();
    private final AlertInMemoryStores.Feedbacks feedbacks = new AlertInMemoryStores.Feedbacks();
    private final AlertInMemoryStores.RegressionCandidates candidates =
            new AlertInMemoryStores.RegressionCandidates();
    private final AlertInMemoryStores.Datasets datasets = new AlertInMemoryStores.Datasets();
    private final RegressionCaseAdmissionService service =
            new RegressionCaseAdmissionService(reports, runs, evidences, feedbacks,
                    candidates, datasets, CLOCK);

    // ------------------------------------------------------------------ 夹具

    private RcaRun terminalRun(RcaRunState state) {
        RcaRun run = new RcaRun(UUID.randomUUID(), UUID.randomUUID(), 1,
                RunTrigger.INITIAL, state, Digest.sha256Of("inv"), NOW, NOW,
                NOW, state.isActive() ? null : NOW, null);
        runs.insert(run);
        return run;
    }

    private RcaReport reportOf(RcaRun run, ValidationStatus status, String packageJson) {
        RcaReport report = new RcaReport(UUID.randomUUID(), run.id(),
                UUID.randomUUID(), 2, status, List.of(), packageJson, "raw", "m",
                null, null, null, true, NOW);
        reports.insert(report);
        return report;
    }

    private void evidenceOf(RcaRun run, String payload) {
        evidences.insert(new EvidenceEnvelope(UUID.randomUUID(), run.id(),
                UUID.randomUUID(), "prometheus_range", "am4-evidence.v1", 1,
                "tool:prom", Map.of(), NOW, NOW,
                "{\"q\":\"" + payload + "\"}", Digest.sha256Of(payload).value()));
    }

    private RegressionCaseAdmissionService.ProposeResult propose(RcaReport report,
            String caseKey) {
        return service.propose(new RegressionCaseAdmissionService.Proposal(
                report.id(), null, caseKey, "family-" + caseKey, "proposer"));
    }

    private RegressionCaseAdmissionService.MaterializeCommand materializeCommand(
            String datasetVersion) {
        return new RegressionCaseAdmissionService.MaterializeCommand(
                "rca-regression", datasetVersion, PartitionClass.TUNING,
                new TypedRootCause("db.pool", "exhaustion", "POOL_EXHAUSTED"),
                List.of("LATENCY_HIGH"), List.of("连接池耗尽", "pool exhausted"), "curator");
    }

    // ------------------------------------------------------------------ FO01/FO03 来源契约

    @Test
    @DisplayName("FO01 终态 run+已验证报告+非零证据 → PENDING_REVIEW 候选受理")
    void happyProposalAccepted() {
        RcaRun run = terminalRun(RcaRunState.SUCCEEDED);
        RcaReport report = reportOf(run, ValidationStatus.STRUCTURE_VALIDATED, "{\"a\":1}");
        evidenceOf(run, "up");

        RegressionCaseAdmissionService.ProposeResult result = propose(report, "case-1");

        assertThat(result.refusal()).isNull();
        assertThat(result.candidate().state()).isEqualTo(RegressionCandidate.ST_PENDING_REVIEW);
        assertThat(result.candidate().sourceReportId()).isEqualTo(report.id());
        assertThat(result.candidate().sourceRunId()).isEqualTo(run.id());
        assertThat(result.replayed()).isFalse();
    }

    @Test
    @DisplayName("FO01 运行中 run 拒入集（调查未封存）；零证据拒（FO03）；未验证报告拒")
    void sourceContractRefusals() {
        RcaRun active = terminalRun(RcaRunState.RUNNING);
        RcaReport activeReport = reportOf(active, ValidationStatus.STRUCTURE_VALIDATED, "{}");
        evidenceOf(active, "e");
        assertThat(propose(activeReport, "c1").refusal()).contains("未终态");

        RcaRun noEvidence = terminalRun(RcaRunState.SUCCEEDED);
        RcaReport noEvidenceReport = reportOf(noEvidence,
                ValidationStatus.STRUCTURE_VALIDATED, "{}");
        assertThat(propose(noEvidenceReport, "c2").refusal()).contains("零证据");

        RcaRun clean = terminalRun(RcaRunState.SUCCEEDED);
        RcaReport rejected = reportOf(clean, ValidationStatus.REJECTED_SCHEMA_MISMATCH, "{}");
        evidenceOf(clean, "e2");
        assertThat(propose(rejected, "c3").refusal()).contains("结构未通过");
    }

    @Test
    @DisplayName("FO01 反馈链：feedbackId 不属于该报告 → 拒（防错挂）")
    void foreignFeedbackRejected() {
        RcaRun runA = terminalRun(RcaRunState.SUCCEEDED);
        RcaRun runB = terminalRun(RcaRunState.SUCCEEDED);
        RcaReport reportA = reportOf(runA, ValidationStatus.STRUCTURE_VALIDATED, "{}");
        RcaReport reportB = reportOf(runB, ValidationStatus.STRUCTURE_VALIDATED, "{}");
        evidenceOf(runA, "a");
        evidenceOf(runB, "b");
        ReportFeedback foreign = new ReportFeedback(UUID.randomUUID(), reportB.id(),
                runB.id(), "d", "bob", ReportFeedback.Verdict.ACCEPTED, "r",
                List.of(), null, "kb", NOW);
        feedbacks.insert(foreign);

        RegressionCaseAdmissionService.ProposeResult result = service.propose(
                new RegressionCaseAdmissionService.Proposal(reportA.id(), foreign.id(),
                        "case-x", "f", "p"));

        assertThat(result.refusal()).contains("不属于该报告");
    }

    // ------------------------------------------------------------------ FO02/FO28 幂等与指纹冻结

    @Test
    @DisplayName("FO02 同源同 caseKey 二提幂等收敛（replayed=true 同 id）；不同 caseKey 新候选")
    void sameSourceSameCaseIdempotent() {
        RcaRun run = terminalRun(RcaRunState.SUCCEEDED);
        RcaReport report = reportOf(run, ValidationStatus.STRUCTURE_VALIDATED, "{}");
        evidenceOf(run, "e");

        RegressionCaseAdmissionService.ProposeResult first = propose(report, "case-1");
        RegressionCaseAdmissionService.ProposeResult second = propose(report, "case-1");
        RegressionCaseAdmissionService.ProposeResult other = propose(report, "case-2");

        assertThat(second.replayed()).isTrue();
        assertThat(second.candidate().id()).isEqualTo(first.candidate().id());
        assertThat(other.replayed()).isFalse();
        assertThat(candidates.rows).hasSize(2);
    }

    @Test
    @DisplayName("FO28 sourceDigest 冻结来源：证据集变化 → 新指纹新候选（旧候选不被改写）")
    void sourceDigestFreezesEvidenceSet() {
        RcaRun run = terminalRun(RcaRunState.SUCCEEDED);
        RcaReport report = reportOf(run, ValidationStatus.STRUCTURE_VALIDATED, "{}");
        evidenceOf(run, "e1");
        RegressionCandidate before = propose(report, "case-1").candidate();

        evidenceOf(run, "e2");
        RegressionCandidate after = propose(report, "case-1").candidate();

        assertThat(after.id()).isNotEqualTo(before.id());
        assertThat(after.sourceDigest()).isNotEqualTo(before.sourceDigest());
        assertThat(candidates.findById(before.id()).orElseThrow().sourceDigest())
                .isEqualTo(before.sourceDigest());
    }

    // ------------------------------------------------------------------ FO26/FO27 审核

    @Test
    @DisplayName("FO26 首个审核定状态：ACCEPTED_FOR_CANDIDATE → ACCEPTED")
    void firstReviewSetsState() {
        UUID candidateId = readyCandidate();

        RegressionCandidate reviewed = service.review(candidateId, "reviewer-1",
                RegressionReview.ACCEPTED_FOR_CANDIDATE, "evidence chain complete");

        assertThat(reviewed.state()).isEqualTo(RegressionCandidate.ST_ACCEPTED);
        assertThat(service.reviewsOf(candidateId)).hasSize(1);
    }

    @Test
    @DisplayName("FO26 同审核者重投幂等：意见不堆叠、状态不漂移")
    void reviewerRevoteIdempotent() {
        UUID candidateId = readyCandidate();
        service.review(candidateId, "reviewer-1",
                RegressionReview.ACCEPTED_FOR_CANDIDATE, "ok");
        RegressionCandidate again = service.review(candidateId, "reviewer-1",
                RegressionReview.ACCEPTED_FOR_CANDIDATE, "ok confirmed");

        assertThat(service.reviewsOf(candidateId)).hasSize(1);
        assertThat(again.state()).isEqualTo(RegressionCandidate.ST_ACCEPTED);
    }

    @Test
    @DisplayName("FO27 两审核者结论相异 → DISPUTED（不自动采纳最后写入）")
    void divergentReviewsDisputed() {
        UUID candidateId = readyCandidate();
        service.review(candidateId, "reviewer-1",
                RegressionReview.ACCEPTED_FOR_CANDIDATE, "fine");
        RegressionCandidate disputed = service.review(candidateId, "reviewer-2",
                RegressionReview.REJECTED, "replay path missing");

        assertThat(disputed.state()).isEqualTo(RegressionCandidate.ST_DISPUTED);
    }

    @Test
    @DisplayName("FO27 相异后第三票同向不自动翻案：DISPUTED 为吸收态（裁决=重开新候选，非计票）")
    void majorityDoesNotAutoResolveDisputed() {
        UUID candidateId = readyCandidate();
        service.review(candidateId, "reviewer-1",
                RegressionReview.ACCEPTED_FOR_CANDIDATE, "fine");
        service.review(candidateId, "reviewer-2",
                RegressionReview.REJECTED, "no");
        RegressionCandidate still = service.review(candidateId, "reviewer-3",
                RegressionReview.ACCEPTED_FOR_CANDIDATE, "verdict: enough");

        assertThat(still.state()).isEqualTo(RegressionCandidate.ST_DISPUTED);
        assertThat(service.reviewsOf(candidateId)).hasSize(3);
    }

    @Test
    @DisplayName("FO27 全部 NEEDS_EVIDENCE → NEEDS_EVIDENCE（候选不消亡可补证）")
    void unanimousNeedsEvidence() {
        UUID candidateId = readyCandidate();
        RegressionCandidate reviewed = service.review(candidateId, "reviewer-1",
                RegressionReview.NEEDS_EVIDENCE, "missing raw alert sample");

        assertThat(reviewed.state()).isEqualTo(RegressionCandidate.ST_NEEDS_EVIDENCE);
    }

    // ------------------------------------------------------------------ FO04 materialize

    @Test
    @DisplayName("FO04 仅 ACCEPTED 可入集；PENDING/DISPUTED 显式拒绝")
    void materializeRequiresAccepted() {
        UUID candidateId = readyCandidate();

        assertThatThrownBy(() -> service.materialize(candidateId,
                materializeCommand("v1")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("仅 ACCEPTED");

        service.review(candidateId, "reviewer-1",
                RegressionReview.ACCEPTED_FOR_CANDIDATE, "ok");
        service.review(candidateId, "reviewer-2",
                RegressionReview.REJECTED, "no");
        assertThatThrownBy(() -> service.materialize(candidateId,
                materializeCommand("v1")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("仅 ACCEPTED");
    }

    @Test
    @DisplayName("FO04 入集写不可变 DatasetVersion+CaseVersion；GT=人工显式 TypedRootCause")
    void materializeWritesImmutableRows() {
        UUID candidateId = readyCandidate();
        service.review(candidateId, "reviewer-1",
                RegressionReview.ACCEPTED_FOR_CANDIDATE, "ok");

        RegressionCaseAdmissionService.MaterializeResult result = service.materialize(
                candidateId, materializeCommand("v1"));

        assertThat(result.replayed()).isFalse();
        assertThat(result.dataset().name()).isEqualTo("rca-regression");
        assertThat(result.dataset().partitionClass()).isEqualTo(PartitionClass.TUNING);
        assertThat(result.caseVersion().caseContent().expectedRootCause().faultType())
                .isEqualTo("exhaustion");
        assertThat(result.caseVersion().caseContent().rawArtifact())
                .containsEntry("note", "调查可见输入=来源引用；症状与答案隔离由数据集分区承载");
        assertThat(datasets.findCasesValidAt(result.dataset().id(), NOW.plusSeconds(1)))
                .hasSize(1);
    }

    @Test
    @DisplayName("FO04 同数据集版本同案例重放收敛（不重复行）；同案例异 GT=新版本号入新行")
    void materializeReplayAndCorrection() {
        UUID candidateId = readyCandidate();
        service.review(candidateId, "reviewer-1",
                RegressionReview.ACCEPTED_FOR_CANDIDATE, "ok");

        RegressionCaseAdmissionService.MaterializeResult first = service.materialize(
                candidateId, materializeCommand("v1"));
        RegressionCaseAdmissionService.MaterializeResult replay = service.materialize(
                candidateId, materializeCommand("v1"));

        assertThat(replay.replayed()).isTrue();
        assertThat(replay.caseVersion().caseKey()).isEqualTo(first.caseVersion().caseKey());
        assertThat(datasets.caseRows).hasSize(1);

        RegressionCaseAdmissionService.MaterializeCommand corrected =
                new RegressionCaseAdmissionService.MaterializeCommand(
                        "rca-regression", "v2", PartitionClass.TUNING,
                        new TypedRootCause("db.pool", "timeout", "CONN_TIMEOUT"),
                        List.of(), List.of(), "curator");
        RegressionCaseAdmissionService.MaterializeResult fix = service.materialize(
                candidateId, corrected);

        assertThat(fix.replayed()).isFalse();
        assertThat(datasets.datasetRows).hasSize(2);
        assertThat(fix.dataset().version()).isEqualTo("v2");
    }

    @Test
    @DisplayName("FO04 GT 与反馈结论分存：materialize 输入为命令显式字段，不读反馈 verdict")
    void groundTruthIndependentOfFeedback() {
        RcaRun run = terminalRun(RcaRunState.SUCCEEDED);
        RcaReport report = reportOf(run, ValidationStatus.STRUCTURE_VALIDATED, "{}");
        evidenceOf(run, "e");
        feedbacks.insert(new ReportFeedback(UUID.randomUUID(), report.id(), run.id(),
                Digest.sha256Of("{}").value(), "op", ReportFeedback.Verdict.ACCEPTED,
                "report says db.pool timeout", List.of(), null, "kf", NOW));

        RegressionCaseAdmissionService.ProposeResult result = service.propose(
                new RegressionCaseAdmissionService.Proposal(report.id(),
                        feedbacks.rows.values().iterator().next().id(),
                        "case-fb", "family-fb", "p"));
        service.review(result.candidate().id(), "rev-1",
                RegressionReview.ACCEPTED_FOR_CANDIDATE, "ok");

        RegressionCaseAdmissionService.MaterializeResult out = service.materialize(
                result.candidate().id(), materializeCommand("v1"));

        // GT=命令字段（POOL_EXHAUSTED），不是反馈/审核文本（timeout）
        assertThat(out.caseVersion().caseContent().expectedRootCause().reasonCode())
                .isEqualTo("POOL_EXHAUSTED");
    }

    // ------------------------------------------------------------------ 内部

    /** 标准可审候选：终态 run+已验证报告+证据齐备后提出 */
    private UUID readyCandidate() {
        RcaRun run = terminalRun(RcaRunState.SUCCEEDED);
        RcaReport report = reportOf(run, ValidationStatus.STRUCTURE_VALIDATED, "{}");
        evidenceOf(run, "e");
        return propose(report, "case-" + UUID.randomUUID()).candidate().id();
    }
}
