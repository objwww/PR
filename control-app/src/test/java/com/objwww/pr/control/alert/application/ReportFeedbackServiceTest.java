package com.objwww.pr.control.alert.application;

import com.objwww.pr.control.alert.domain.model.RcaReport;
import com.objwww.pr.control.alert.domain.model.RcaRun;
import com.objwww.pr.control.alert.domain.model.RcaRunState;
import com.objwww.pr.control.alert.domain.model.ReportFeedback;
import com.objwww.pr.control.alert.domain.model.RunTrigger;
import com.objwww.pr.control.alert.domain.model.ValidationStatus;
import com.objwww.pr.control.alert.support.AlertInMemoryStores;
import com.objwww.pr.shared.Digest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * OP-04 终态报告反馈单测（FO21~25/29）：与活跃命令面分离（FO22）、幂等键
 * 收敛与显式冲突（FO23）、digest 钉面（FO24/FO29）、更正链一胜一拒（FO25）。
 */
class ReportFeedbackServiceTest {

    private static final Instant NOW = Instant.parse("2026-09-13T00:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

    private final AlertInMemoryStores.Runs runs = new AlertInMemoryStores.Runs();
    private final AlertInMemoryStores.Reports reports = new AlertInMemoryStores.Reports();
    private final AlertInMemoryStores.Feedbacks feedbacks = new AlertInMemoryStores.Feedbacks();
    private final ReportFeedbackService service = new ReportFeedbackService(
            reports, runs, feedbacks, CLOCK);

    // ------------------------------------------------------------------ 夹具

    private RcaRun run(RcaRunState state) {
        RcaRun run = new RcaRun(UUID.randomUUID(), UUID.randomUUID(), 1,
                RunTrigger.INITIAL, state, Digest.sha256Of("inv"), NOW, NOW,
                NOW, state.isActive() ? null : NOW, null);
        runs.insert(run);
        return run;
    }

    private RcaReport report(RcaRun run) {
        RcaReport report = new RcaReport(UUID.randomUUID(), run.id(),
                UUID.randomUUID(), 2, ValidationStatus.STRUCTURE_VALIDATED,
                List.of(), "{\"root_cause\":\"x\"}", "raw", "m", null, null,
                null, true, NOW);
        reports.insert(report);
        return report;
    }

    private ReportFeedbackService.SubmitResult submit(RcaReport report,
            String key, ReportFeedback.Verdict verdict) {
        return service.submit(report.id(), null, verdict, "reason", List.of(),
                null, key, "op-a");
    }

    // ------------------------------------------------------------------ FO21/FO22

    @Test
    @DisplayName("FO21 终态 run 的已发布报告可提交反馈：落档不重放，byReport 可回读")
    void terminalRunFeedbackAccepted() {
        RcaRun run = run(RcaRunState.SUCCEEDED);
        RcaReport report = report(run);
        ReportFeedbackService.SubmitResult result = submit(report, "k1",
                ReportFeedback.Verdict.ACCEPTED);

        assertThat(result.stored().id()).isNotNull();
        assertThat(result.replayed()).isFalse();
        assertThat(result.conflict()).isNull();
        assertThat(result.stored().runId()).isEqualTo(run.id());
        assertThat(result.stored().author()).isEqualTo("op-a");
        assertThat(service.byReport(report.id())).hasSize(1);
    }

    @Test
    @DisplayName("FO22 运行中 run 拒反馈（走 Hint 命令面）；QUEUED/RUNNING/REPORTING 同律")
    void activeRunRejected() {
        for (RcaRunState state : List.of(RcaRunState.QUEUED, RcaRunState.RUNNING,
                RcaRunState.REPORTING)) {
            RcaReport report = report(run(state));
            assertThatThrownBy(() -> submit(report, "k-" + state,
                    ReportFeedback.Verdict.ACCEPTED))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("终态");
        }
    }

    @Test
    @DisplayName("FO22 报告不存在显式 404 语义（不静默造反馈）")
    void missingReportRejected() {
        assertThatThrownBy(() -> service.submit(UUID.randomUUID(), null,
                ReportFeedback.Verdict.INCORRECT, "r", List.of(), null, "k", "op-a"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("报告不存在");
    }

    // ------------------------------------------------------------------ FO23

    @Test
    @DisplayName("FO23 同 (author,idempotencyKey) 同载荷重放收敛既有行")
    void idempotentReplayConverges() {
        RcaReport report = report(run(RcaRunState.SUCCEEDED));
        ReportFeedbackService.SubmitResult first = submit(report, "k1",
                ReportFeedback.Verdict.PARTIAL);
        ReportFeedbackService.SubmitResult second = submit(report, "k1",
                ReportFeedback.Verdict.PARTIAL);

        assertThat(second.replayed()).isTrue();
        assertThat(second.stored().id()).isEqualTo(first.stored().id());
        assertThat(service.byReport(report.id())).hasSize(1);
    }

    @Test
    @DisplayName("FO23 同键异载荷显式冲突（不是静默覆盖也不是重放）")
    void sameKeyDifferentPayloadConflicts() {
        RcaReport report = report(run(RcaRunState.SUCCEEDED));
        submit(report, "k1", ReportFeedback.Verdict.ACCEPTED);

        assertThatThrownBy(() -> submit(report, "k1",
                ReportFeedback.Verdict.INCORRECT))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("同幂等键不同载荷");
        assertThat(service.byReport(report.id())).hasSize(1);
    }

    // ------------------------------------------------------------------ FO24/FO29

    @Test
    @DisplayName("FO29 reportDigest 服务端按 packageJson sha256 计算（评价对象钉面）")
    void digestPinnedToPackageJson() {
        RcaReport report = report(run(RcaRunState.SUCCEEDED));

        assertThat(ReportFeedbackService.digestOf(report))
                .isEqualTo(Digest.sha256Of(report.packageJson()).value());
    }

    @Test
    @DisplayName("FO24 expectedReportDigest 不符=409 语义（版本错位不静默评错对象）")
    void digestMismatchRejected() {
        RcaReport report = report(run(RcaRunState.SUCCEEDED));

        assertThatThrownBy(() -> service.submit(report.id(),
                "deadbeef" + "0".repeat(56), ReportFeedback.Verdict.ACCEPTED,
                "r", List.of(), null, "k9", "op-a"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("报告版本不一致");
    }

    @Test
    @DisplayName("FO24 verdict 四值全可落档（ACCEPTED/PARTIAL/INCORRECT/INSUFFICIENT）")
    void allVerdictsStorable() {
        for (ReportFeedback.Verdict verdict : ReportFeedback.Verdict.values()) {
            RcaReport report = report(run(RcaRunState.SUCCEEDED));
            ReportFeedbackService.SubmitResult result = submit(report,
                    "k-" + verdict, verdict);
            assertThat(result.stored().verdict()).isEqualTo(verdict);
        }
    }

    // ------------------------------------------------------------------ FO25

    @Test
    @DisplayName("FO25 更正=新行 supersedesId 指向本人前序；两行并存不覆盖")
    void correctionSupersedesOwnRow() {
        RcaReport report = report(run(RcaRunState.SUCCEEDED));
        ReportFeedbackService.SubmitResult first = submit(report, "k1",
                ReportFeedback.Verdict.PARTIAL);

        ReportFeedbackService.SubmitResult second = service.submit(report.id(),
                ReportFeedbackService.digestOf(report),
                ReportFeedback.Verdict.ACCEPTED, "after recheck", List.of(),
                first.stored().id(), "k2", "op-a");

        assertThat(second.stored().supersedesId()).isEqualTo(first.stored().id());
        assertThat(service.byReport(report.id())).hasSize(2);
    }

    @Test
    @DisplayName("FO25 同一前序的并发更正一胜一拒：败者收 SUPERSEDED_BY 冲突标识")
    void concurrentCorrectionOneWins() {
        RcaReport report = report(run(RcaRunState.SUCCEEDED));
        ReportFeedbackService.SubmitResult first = submit(report, "k1",
                ReportFeedback.Verdict.PARTIAL);

        ReportFeedbackService.SubmitResult winner = service.submit(report.id(),
                null, ReportFeedback.Verdict.ACCEPTED, "first", List.of(),
                first.stored().id(), "k2", "op-a");
        ReportFeedbackService.SubmitResult loser = service.submit(report.id(),
                null, ReportFeedback.Verdict.INCORRECT, "second", List.of(),
                first.stored().id(), "k3", "op-a");

        assertThat(winner.conflict()).isNull();
        assertThat(loser.conflict()).isEqualTo("SUPERSEDED_BY:" + winner.stored().id());
        assertThat(loser.stored().id()).isEqualTo(winner.stored().id());
    }

    @Test
    @DisplayName("FO25 只能更正本人反馈：跨作者更正显式拒绝（意见并存=追加新行）")
    void cannotSupersedeOthersRow() {
        RcaReport report = report(run(RcaRunState.SUCCEEDED));
        ReportFeedbackService.SubmitResult alice = service.submit(report.id(),
                null, ReportFeedback.Verdict.PARTIAL, "r", List.of(), null,
                "k-a", "alice");

        assertThatThrownBy(() -> service.submit(report.id(), null,
                ReportFeedback.Verdict.ACCEPTED, "r", List.of(),
                alice.stored().id(), "k-b", "bob"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("不同操作者意见并存");
    }

    @Test
    @DisplayName("FO25 前序不存在或属别的报告：显式拒绝")
    void invalidPredecessorRejected() {
        RcaReport reportA = report(run(RcaRunState.SUCCEEDED));
        RcaReport reportB = report(run(RcaRunState.SUCCEEDED));

        assertThatThrownBy(() -> service.submit(reportA.id(), null,
                ReportFeedback.Verdict.ACCEPTED, "r", List.of(),
                UUID.randomUUID(), "k1", "op-a"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("前序反馈不存在");

        ReportFeedbackService.SubmitResult other = submit(reportB, "kB",
                ReportFeedback.Verdict.ACCEPTED);
        assertThatThrownBy(() -> service.submit(reportA.id(), null,
                ReportFeedback.Verdict.ACCEPTED, "r", List.of(),
                other.stored().id(), "k2", "op-a"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("不属于该报告");
    }

    @Test
    @DisplayName("append-only：提交与更正都不改动既有行（前序 verdict 原样）")
    void appendOnlyNeverMutates() {
        RcaReport report = report(run(RcaRunState.SUCCEEDED));
        ReportFeedbackService.SubmitResult first = submit(report, "k1",
                ReportFeedback.Verdict.PARTIAL);

        service.submit(report.id(), null, ReportFeedback.Verdict.ACCEPTED,
                "changed mind", List.of(), first.stored().id(), "k2", "op-a");

        assertThat(service.byId(first.stored().id()).orElseThrow().verdict())
                .isEqualTo(ReportFeedback.Verdict.PARTIAL);
    }
}
