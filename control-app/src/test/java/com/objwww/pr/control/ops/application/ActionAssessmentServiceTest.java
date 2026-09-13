package com.objwww.pr.control.ops.application;

import com.objwww.pr.control.alert.domain.evidence.EvidenceEnvelope;
import com.objwww.pr.control.alert.domain.model.RcaReport;
import com.objwww.pr.control.alert.domain.model.RcaTask;
import com.objwww.pr.control.alert.domain.model.RcaTaskState;
import com.objwww.pr.control.alert.domain.model.ValidationStatus;
import com.objwww.pr.control.alert.domain.tool.RcaToolInvocationLedger.InvocationIdentity;
import com.objwww.pr.control.alert.domain.tool.ToolInvocationState;
import com.objwww.pr.control.alert.support.AlertInMemoryStores;
import com.objwww.pr.control.ops.domain.model.ActionAssessment;
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

/**
 * OP-03 动作分析单测（FO11~19）：内容去重（FO11/FO12）、逻辑聚合（FO14）、
 * 重入幂等与新快照重算（FO15）、证据链（FO16/FO17）、分类面（FO18）。
 */
class ActionAssessmentServiceTest {

    private static final Instant NOW = Instant.parse("2026-09-13T00:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

    private final AlertInMemoryStores.Tasks tasks = new AlertInMemoryStores.Tasks();
    private final AlertInMemoryStores.ToolLedger ledger = new AlertInMemoryStores.ToolLedger();
    private final AlertInMemoryStores.Evidences evidences = new AlertInMemoryStores.Evidences();
    private final AlertInMemoryStores.Reports reports = new AlertInMemoryStores.Reports();
    private final AlertInMemoryStores.ActionAssessments assessments =
            new AlertInMemoryStores.ActionAssessments();
    private final ActionAssessmentService service = new ActionAssessmentService(
            tasks, ledger, evidences, reports, assessments, CLOCK);

    // ------------------------------------------------------------------ 夹具

    private UUID runId = UUID.randomUUID();

    private UUID task() {
        RcaTask task = new RcaTask(UUID.randomUUID(), runId, "investigate",
                RcaTaskState.DONE, 1, NOW, NOW, NOW.plusSeconds(600), null, null, 0,
                1, 3, NOW, NOW, 1);
        tasks.insert(task);
        return task.id();
    }

    /** 落一笔账本行并回执证据；digest 同则同逻辑动作（重试），payload 同则同观察 */
    private UUID call(UUID taskId, long callSeq, String actionDigest, String payload,
            ToolInvocationState state, boolean withEvidence) {
        UUID operationId = UUID.randomUUID();
        ledger.open(new InvocationIdentity(operationId, runId, taskId,
                UUID.randomUUID(), callSeq, "prom_range", "v1", actionDigest));
        if (withEvidence) {
            EvidenceEnvelope evidence = new EvidenceEnvelope(UUID.randomUUID(), runId,
                    taskId, "prometheus_range", "am4-evidence.v1", 1, "tool:prom",
                    Map.of(), NOW, NOW,
                    "{\"q\":\"" + payload + "\"}", Digest.sha256Of(payload).value());
            evidences.insert(evidence);
            ledger.markResultRef(operationId, evidence.evidenceId());
            ledger.succeed(operationId);
        } else if (state == ToolInvocationState.FAILED) {
            ledger.fail(operationId, ToolInvocationState.FAILED,
                    com.objwww.pr.control.alert.domain.tool.ToolReasonCode.REMOTE_5XX);
        } else if (state == ToolInvocationState.UNKNOWN) {
            ledger.fail(operationId, ToolInvocationState.UNKNOWN,
                    com.objwww.pr.control.alert.domain.tool.ToolReasonCode.TRANSPORT_UNKNOWN);
        } else if (state == ToolInvocationState.SUCCESS) {
            ledger.succeed(operationId);
        }
        return operationId;
    }

    private void finalReport(String packageJson) {
        reports.insert(new RcaReport(UUID.randomUUID(), runId, UUID.randomUUID(), 2,
                ValidationStatus.STRUCTURE_VALIDATED, List.of(), packageJson, "raw",
                "m", null, null, null, true, NOW));
    }

    // ------------------------------------------------------------------ FO18 分类面

    @Test
    @DisplayName("FO18 全败物理尝试 → SOURCE_FAILED；无终态知识 → UNDETERMINED")
    void sourceFailedAndUndetermined() {
        UUID taskId = task();
        call(taskId, 1, "digest-fail", null, ToolInvocationState.FAILED, false);
        call(taskId, 2, "digest-fail", null, ToolInvocationState.FAILED, false);
        call(taskId, 3, "digest-unknown", null, ToolInvocationState.UNKNOWN, false);

        ActionAssessmentService.AssessmentOutcome out = service.assess(runId);

        assertThat(out.rows()).hasSize(2);
        assertThat(out.rows().stream()
                .filter(r -> r.logicalActionKey().endsWith("#digest-fail"))
                .findFirst().orElseThrow().classification())
                .isEqualTo(ActionAssessment.SOURCE_FAILED);
        assertThat(out.rows().stream()
                .filter(r -> r.logicalActionKey().endsWith("#digest-unknown"))
                .findFirst().orElseThrow().classification())
                .isEqualTo(ActionAssessment.UNDETERMINED);
    }

    @Test
    @DisplayName("FO16/FO18 成功但无可解析证据（resultRef 缺失）→ NO_DATA")
    void successWithoutEvidenceIsNoData() {
        UUID taskId = task();
        call(taskId, 1, "digest-nodata", null, ToolInvocationState.SUCCESS, false);

        ActionAssessmentService.AssessmentOutcome out = service.assess(runId);

        assertThat(out.rows()).hasSize(1);
        assertThat(out.rows().get(0).classification()).isEqualTo(ActionAssessment.NO_DATA);
    }

    @Test
    @DisplayName("FO11 同 (evidenceType,canonicalPayload) 不同证据 UUID → DUPLICATE_SAME_SNAPSHOT")
    void sameContentDifferentEvidenceIdIsDuplicate() {
        UUID taskId = task();
        call(taskId, 1, "digest-a", "up:5m", ToolInvocationState.SUCCESS, true);
        call(taskId, 2, "digest-b", "up:5m", ToolInvocationState.SUCCESS, true);

        ActionAssessmentService.AssessmentOutcome out = service.assess(runId);

        assertThat(evidences.rows).hasSize(2);
        assertThat(out.rows()).hasSize(2);
        assertThat(out.rows().stream()
                .filter(r -> r.logicalActionKey().endsWith("#digest-a"))
                .findFirst().orElseThrow().classification())
                .isEqualTo(ActionAssessment.NEW_OBSERVATION);
        assertThat(out.rows().stream()
                .filter(r -> r.logicalActionKey().endsWith("#digest-b"))
                .findFirst().orElseThrow().classification())
                .isEqualTo(ActionAssessment.DUPLICATE_SAME_SNAPSHOT);
    }

    @Test
    @DisplayName("FO12 同查询不同窗口/频次（payload 变化）→ 新观察，不判重复")
    void changedWindowOrFrequencyIsNewObservation() {
        UUID taskId = task();
        call(taskId, 1, "digest-a", "up:5m", ToolInvocationState.SUCCESS, true);
        call(taskId, 2, "digest-a2", "up:30m", ToolInvocationState.SUCCESS, true);

        ActionAssessmentService.AssessmentOutcome out = service.assess(runId);

        assertThat(out.rows())
                .extracting(ActionAssessment::classification)
                .containsOnly(ActionAssessment.NEW_OBSERVATION);
        assertThat(out.rows())
                .allSatisfy(r -> assertThat(r.newObservationCount()).isEqualTo(1));
    }

    @Test
    @DisplayName("FO17 最终报告引用的证据 → CONFIRMS_OR_REFUTES（citationCount 计数）")
    void reportCitationClassified() {
        UUID taskId = task();
        UUID op = call(taskId, 1, "digest-cite", "up:5m",
                ToolInvocationState.SUCCESS, true);
        UUID evidenceId = ledger.rows.get(op).resultRef;
        call(taskId, 2, "digest-uncite", "mem:rss",
                ToolInvocationState.SUCCESS, true);
        finalReport("{\"citations\":[\"" + evidenceId + "\"],\"root_cause\":\"x\"}");

        ActionAssessmentService.AssessmentOutcome out = service.assess(runId);

        ActionAssessment cited = out.rows().stream()
                .filter(r -> r.logicalActionKey().endsWith("#digest-cite"))
                .findFirst().orElseThrow();
        ActionAssessment uncited = out.rows().stream()
                .filter(r -> r.logicalActionKey().endsWith("#digest-uncite"))
                .findFirst().orElseThrow();
        assertThat(cited.classification()).isEqualTo(ActionAssessment.CONFIRMS_OR_REFUTES);
        assertThat(cited.reportCitationCount()).isEqualTo(1);
        // 未引用 ≠ 无价值（§4.1）：仍是新观察
        assertThat(uncited.classification()).isEqualTo(ActionAssessment.NEW_OBSERVATION);
    }

    // ------------------------------------------------------------------ FO14 聚合

    @Test
    @DisplayName("FO14 同逻辑动作三次物理尝试 → 一行，physical_attempts=3")
    void physicalAttemptsAggregated() {
        UUID taskId = task();
        call(taskId, 1, "digest-retry", null, ToolInvocationState.FAILED, false);
        call(taskId, 2, "digest-retry", null, ToolInvocationState.FAILED, false);
        call(taskId, 3, "digest-retry", "up:5m", ToolInvocationState.SUCCESS, true);

        ActionAssessmentService.AssessmentOutcome out = service.assess(runId);

        assertThat(out.rows()).hasSize(1);
        assertThat(out.rows().get(0).physicalAttempts()).isEqualTo(3);
        // 有成功行 → 不判 SOURCE_FAILED；首见内容 → NEW_OBSERVATION
        assertThat(out.rows().get(0).classification())
                .isEqualTo(ActionAssessment.NEW_OBSERVATION);
    }

    // ------------------------------------------------------------------ FO15 重入与新快照

    @Test
    @DisplayName("FO15 同版本同快照重入不重复：返回既有行，台账不增")
    void reentrantSameSnapshotIdempotent() {
        UUID taskId = task();
        call(taskId, 1, "digest-a", "up:5m", ToolInvocationState.SUCCESS, true);

        ActionAssessmentService.AssessmentOutcome first = service.assess(runId);
        ActionAssessmentService.AssessmentOutcome second = service.assess(runId);

        assertThat(second.replayed()).isTrue();
        assertThat(second.rows().get(0).id()).isEqualTo(first.rows().get(0).id());
        assertThat(assessments.rows).hasSize(1);
    }

    @Test
    @DisplayName("FO15 迟到证据 → 新快照摘要 → 重算新行（旧行保留可追溯）")
    void lateEvidenceTriggersNewSnapshot() {
        UUID taskId = task();
        call(taskId, 1, "digest-a", "up:5m", ToolInvocationState.SUCCESS, true);
        String firstSnapshot = service.assess(runId).evidenceSnapshotDigest();

        call(taskId, 2, "digest-late", "mem:rss",
                ToolInvocationState.SUCCESS, true);
        ActionAssessmentService.AssessmentOutcome second = service.assess(runId);

        assertThat(second.evidenceSnapshotDigest()).isNotEqualTo(firstSnapshot);
        assertThat(second.replayed()).isFalse();
        assertThat(assessments.rows).hasSize(3);
        assertThat(assessments.findByRun(runId).stream()
                .map(ActionAssessment::evidenceSnapshotDigest).distinct())
                .hasSize(2);
    }

    // ------------------------------------------------------------------ 口径钉面

    @Test
    @DisplayName("assessor_version/confidence_kind 落行（分析口径版本化，非黑箱）")
    void versionPinnedOnRows() {
        UUID taskId = task();
        call(taskId, 1, "digest-a", "up:5m", ToolInvocationState.SUCCESS, true);

        ActionAssessment row = service.assess(runId).rows().get(0);

        assertThat(row.assessorVersion())
                .isEqualTo(ActionAssessmentService.ASSESSOR_VERSION);
        assertThat(row.confidenceKind())
                .isEqualTo(ActionAssessmentService.CONFIDENCE_KIND);
    }

    @Test
    @DisplayName("空 run（零账本行）→ 零分析行，不编造动作")
    void emptyRunProducesNothing() {
        task();

        ActionAssessmentService.AssessmentOutcome out = service.assess(runId);

        assertThat(out.rows()).isEmpty();
        assertThat(assessments.rows).isEmpty();
    }
}
