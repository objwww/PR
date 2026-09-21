package com.objwww.pr.control.eval.domain.service;

import com.objwww.pr.control.alert.domain.claim.ClaimStatus;
import com.objwww.pr.control.alert.domain.model.EvidencePackageV2;
import com.objwww.pr.control.alert.domain.model.ReportClaim;
import com.objwww.pr.control.alert.domain.model.TypedRootCause;
import com.objwww.pr.control.eval.application.ScenarioEvaluator;
import com.objwww.pr.control.eval.domain.GoldenCase;
import com.objwww.pr.control.eval.domain.model.BehaviorCheckStatus;
import com.objwww.pr.control.eval.domain.model.BehaviorEvaluation;
import com.objwww.pr.control.eval.domain.model.BehaviorInput;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ME-T04（D04）逐案行为评测纯函数：EV-01～06 测试矩阵。
 * 旧版文本覆盖指标（ScenarioEvaluator.checkpointMatches）保留原名原义，
 * 新证据覆盖/引用质量检查并存出数，互不重写。
 */
class BehaviorEvaluationTest {

    private static final UUID RUN = UUID.randomUUID();
    private static final Instant T0 = Instant.parse("2026-01-01T00:00:00Z");
    private static final TypedRootCause CAUSE =
            new TypedRootCause("payment", "BUSINESS_ERROR_RATE", "PAYMENT_CHARGE_FAILURE");

    private final BehaviorEvaluator evaluator = new BehaviorEvaluator();

    private static EvidencePackageV2 pkg(String summary, List<ReportClaim> claims) {
        return new EvidencePackageV2(2, summary, CAUSE, claims,
                List.of(), "impact", "remediation", List.of());
    }

    private static ReportClaim trueClaim(List<String> refs) {
        return new ReportClaim("root_cause", ClaimStatus.TRUE, "payment",
                "BUSINESS_ERROR_RATE", List.of("checkout"), refs);
    }

    private static BehaviorInput.ResolvedCitation citation(String ref, int claimIndex,
                                                           UUID evidenceId, UUID runId,
                                                           Instant start, Instant end,
                                                           String content) {
        return new BehaviorInput.ResolvedCitation(ref, claimIndex, evidenceId, runId,
                start, end, content, content == null ? "digest-only" : "d-" + content.hashCode());
    }

    private static BehaviorInput.EvidenceContent corpus(UUID id, String content) {
        return new BehaviorInput.EvidenceContent(id, content,
                content == null ? "digest-only" : "d-" + content.hashCode(), false);
    }

    private static BehaviorInput input(EvidencePackageV2 pkg, List<String> checkpoints,
                                       Integer textCovered, Integer textTotal,
                                       List<BehaviorInput.TraceToolCall> calls,
                                       List<BehaviorInput.ResolvedCitation> citations,
                                       List<BehaviorInput.EvidenceContent> evidence) {
        return new BehaviorInput(pkg, checkpoints, textCovered, textTotal,
                RUN, T0, T0.plusSeconds(300), calls, citations, evidence, true);
    }

    private static BehaviorEvaluation.Check checkOf(BehaviorEvaluation ev, String name) {
        return ev.checks().stream().filter(c -> c.name().equals(name)).findFirst()
                .orElseThrow(() -> new AssertionError("缺检查项 " + name));
    }

    private static GoldenCase goldenWithCheckpoints(List<String> checkpoints) {
        return new GoldenCase("S1", "n", "FlagdScenarioDriver", null, "payment", CAUSE,
                List.of("checkout"), Map.of(), null, new GoldenCase.Timing(1, 1, 1, 1, 1),
                GoldenCase.KIND_INJECT, checkpoints);
    }

    // ------------------------------------------------------------------ EV-01

    @Test
    @DisplayName("EV-01：报告写满 checkpoint 关键词但零取证——旧文本覆盖命中，新证据覆盖不命中")
    void ev01TextCoverageHitsButEvidenceCoverageMisses() {
        List<String> checkpoints = List.of("OA_STUCK_ORDERS", "logs.query");
        EvidencePackageV2 report = pkg(
                "order-arena 卡单 oa_stuck_orders_current 走高，logs.query 显示超时",
                List.of(trueClaim(List.of("ref-1"))));
        // 旧版文本覆盖（报告语料子串）——全部命中，原名原义保留
        ScenarioEvaluator legacy = new ScenarioEvaluator(null);
        List<ScenarioEvaluator.CheckpointMatch> textMatches =
                legacy.checkpointMatches(goldenWithCheckpoints(checkpoints), report);
        assertThat(textMatches).allMatch(ScenarioEvaluator.CheckpointMatch::matched);

        // 新证据覆盖：run 存在但零证据产出 = 真实零取证 FAIL（不命中）
        BehaviorEvaluation ev = evaluator.evaluate(input(report, checkpoints,
                (int) textMatches.stream().filter(ScenarioEvaluator.CheckpointMatch::matched).count(),
                textMatches.size(),
                List.of(), List.of(), List.of()));
        BehaviorEvaluation.Check coverage = checkOf(ev, BehaviorEvaluator.CHECK_COVERAGE);
        assertThat(coverage.status()).isEqualTo(BehaviorCheckStatus.FAIL);
        assertThat(coverage.reasonCode()).isEqualTo("NO_RUN_EVIDENCE");
        assertThat(ev.coverage().textCovered()).isEqualTo(2);
        assertThat(ev.coverage().evidenceCovered()).isZero();
        assertThat(ev.coverage().evidenceTotal()).isEqualTo(2);
        assertThat(ev.failureLabels()).contains("CHECKPOINT_EVIDENCE_MISSING");
        // 证据语料在场但不含检查点内容 → 同样 FAIL（写关键词不算取证）
        BehaviorEvaluation ev2 = evaluator.evaluate(input(report, checkpoints, 2, 2,
                List.of(), List.of(),
                List.of(corpus(UUID.randomUUID(), "{\"text\":\"无关内容\"}"))));
        assertThat(checkOf(ev2, BehaviorEvaluator.CHECK_COVERAGE).status())
                .isEqualTo(BehaviorCheckStatus.FAIL);
        assertThat(checkOf(ev2, BehaviorEvaluator.CHECK_COVERAGE).reasonCode())
                .isEqualTo("CHECKPOINT_EVIDENCE_MISSING");
    }

    // ------------------------------------------------------------------ EV-02

    @Test
    @DisplayName("EV-02：引用存在但内容否定结论——存在性通过、支持性失败，不统一显示 GROUNDED")
    void ev02CitationExistsButContradictsConclusion() {
        UUID evId = UUID.randomUUID();
        String content = "payment 服务各项指标正常，business_error_rate 无异常已排除";
        EvidencePackageV2 report = pkg("s", List.of(trueClaim(List.of(evId.toString()))));
        // 旧口径 conclusionGrounded 只看引用非空——仍 GROUNDED（原义不动）
        assertThat(new ScenarioEvaluator(null).conclusionGrounded(report))
                .isEqualTo(ScenarioEvaluator.GROUNDED);

        BehaviorEvaluation ev = evaluator.evaluate(input(report, List.of(), null, null,
                List.of(),
                List.of(citation(evId.toString(), 0, evId, RUN,
                        T0, T0.plusSeconds(60), content)),
                List.of(corpus(evId, content))));
        assertThat(checkOf(ev, BehaviorEvaluator.CHECK_EXISTENCE).status())
                .isEqualTo(BehaviorCheckStatus.PASS);
        assertThat(checkOf(ev, BehaviorEvaluator.CHECK_ATTRIBUTION).status())
                .isEqualTo(BehaviorCheckStatus.PASS);
        BehaviorEvaluation.Check support = checkOf(ev, BehaviorEvaluator.CHECK_SUPPORT);
        assertThat(support.status()).isEqualTo(BehaviorCheckStatus.FAIL);
        assertThat(support.reasonCode()).isEqualTo("SUPPORT_CONTRADICTED");
        assertThat(support.evidenceRefs()).contains(evId.toString());
        assertThat(ev.failureLabels()).contains("SUPPORT_CONTRADICTED");
    }

    // ------------------------------------------------------------------ EV-03

    @Test
    @DisplayName("EV-03：跨 run 引用/错误时间窗——归属与时窗检查失败，不进可用证据集合")
    void ev03CrossRunCitationAndWrongTimeWindowRejected() {
        UUID foreign = UUID.randomUUID();
        UUID otherRun = UUID.randomUUID();
        EvidencePackageV2 report = pkg("s", List.of(trueClaim(List.of(foreign.toString()))));
        BehaviorEvaluation crossRun = evaluator.evaluate(input(report, List.of(), null, null,
                List.of(),
                List.of(citation(foreign.toString(), 0, foreign, otherRun,
                        T0, T0.plusSeconds(60), "payment 命中")) ,
                List.of()));
        assertThat(checkOf(crossRun, BehaviorEvaluator.CHECK_EXISTENCE).status())
                .isEqualTo(BehaviorCheckStatus.PASS);
        BehaviorEvaluation.Check attribution = checkOf(crossRun, BehaviorEvaluator.CHECK_ATTRIBUTION);
        assertThat(attribution.status()).isEqualTo(BehaviorCheckStatus.FAIL);
        assertThat(attribution.reasonCode()).isEqualTo("CITATION_CROSS_RUN");
        // 跨 run 引用不送入可用证据集合：支持性不可评（不拿它猜支持/反驳）
        assertThat(checkOf(crossRun, BehaviorEvaluator.CHECK_SUPPORT).status())
                .isEqualTo(BehaviorCheckStatus.NOT_ASSESSED);
        assertThat(checkOf(crossRun, BehaviorEvaluator.CHECK_SUPPORT).reasonCode())
                .isEqualTo("NO_USABLE_EVIDENCE");
        assertThat(crossRun.evidenceRefs()).isEmpty();
        assertThat(crossRun.failureLabels()).contains("CITATION_CROSS_RUN");

        // 同 run 但证据时间窗与 run 窗口完全相离 → 时窗 FAIL
        UUID stale = UUID.randomUUID();
        BehaviorEvaluation wrongWindow = evaluator.evaluate(input(report,
                List.of(), null, null, List.of(),
                List.of(citation(stale.toString(), 0, stale, RUN,
                        T0.minusSeconds(86400 * 2), T0.minusSeconds(86400), "payment 命中")),
                List.of()));
        BehaviorEvaluation.Check window = checkOf(wrongWindow, BehaviorEvaluator.CHECK_TIME_WINDOW);
        assertThat(window.status()).isEqualTo(BehaviorCheckStatus.FAIL);
        assertThat(window.reasonCode()).isEqualTo("TIME_WINDOW_VIOLATION");
        assertThat(wrongWindow.failureLabels()).contains("TIME_WINDOW_VIOLATION");
    }

    // ------------------------------------------------------------------ EV-04

    @Test
    @DisplayName("EV-04：两条不同工具路径均获得正确证据——都通过，不按固定调用序列误判")
    void ev04EquivalentToolPathsBothPass() {
        List<String> checkpoints = List.of("payment timeout");
        UUID evId = UUID.randomUUID();
        List<BehaviorInput.EvidenceContent> corpus =
                List.of(corpus(evId, "{\"text\":\"payment timeout 命中率 50%\"}"));
        BehaviorInput.TraceToolCall callA = new BehaviorInput.TraceToolCall(
                UUID.randomUUID(), UUID.randomUUID(), "prometheus_query", "v1", 1,
                "dig-a", "SUCCESS", null, evId.toString());
        BehaviorInput.TraceToolCall callB = new BehaviorInput.TraceToolCall(
                UUID.randomUUID(), UUID.randomUUID(), "logs_query", "v1", 2,
                "dig-b", "SUCCESS", null, null);

        BehaviorEvaluation pathA = evaluator.evaluate(input(
                pkg("s", List.of(trueClaim(List.of(evId.toString())))), checkpoints, null, null,
                List.of(callA, callB),
                List.of(citation(evId.toString(), 0, evId, RUN, T0, T0.plusSeconds(60),
                        "payment timeout 命中率 50%")),
                corpus));
        // 工具顺序颠倒的另一条等价路径
        BehaviorEvaluation pathB = evaluator.evaluate(input(
                pkg("s", List.of(trueClaim(List.of(evId.toString())))), checkpoints, null, null,
                List.of(new BehaviorInput.TraceToolCall(callB.invocationId(), callB.taskId(),
                                callB.toolName(), callB.toolVersion(), 1,
                                callB.actionDigest(), callB.state(), null, null),
                        new BehaviorInput.TraceToolCall(callA.invocationId(), callA.taskId(),
                                callA.toolName(), callA.toolVersion(), 2,
                                callA.actionDigest(), callA.state(), null, evId.toString())),
                List.of(citation(evId.toString(), 0, evId, RUN, T0, T0.plusSeconds(60),
                        "payment timeout 命中率 50%")),
                corpus));

        for (BehaviorEvaluation ev : List.of(pathA, pathB)) {
            assertThat(checkOf(ev, BehaviorEvaluator.CHECK_COVERAGE).status())
                    .isEqualTo(BehaviorCheckStatus.PASS);
            assertThat(ev.coverage().evidenceCovered()).isEqualTo(1);
            assertThat(ev.coverage().evidenceTotal()).isEqualTo(1);
            assertThat(ev.failureLabels()).isEmpty();
        }
    }

    // ------------------------------------------------------------------ EV-05

    @Test
    @DisplayName("EV-05：正文不可得（仅 digest）——依赖正文的检查 NOT_ASSESSED，不猜通过")
    void ev05DigestOnlyTraceLeavesContentChecksNotAssessed() {
        UUID evId = UUID.randomUUID();
        EvidencePackageV2 report = pkg("s", List.of(trueClaim(List.of(evId.toString()))));
        BehaviorEvaluation ev = evaluator.evaluate(input(report,
                List.of("payment timeout"), null, null, List.of(),
                List.of(citation(evId.toString(), 0, evId, RUN, T0, T0.plusSeconds(60), null)),
                List.of(corpus(evId, null))));
        // 确定性字段检查照常出数：引用附带/存在/归属可判
        assertThat(checkOf(ev, BehaviorEvaluator.CHECK_ATTACHMENT).status())
                .isEqualTo(BehaviorCheckStatus.PASS);
        assertThat(checkOf(ev, BehaviorEvaluator.CHECK_EXISTENCE).status())
                .isEqualTo(BehaviorCheckStatus.PASS);
        // 依赖正文的检查一律 NOT_ASSESSED
        BehaviorEvaluation.Check support = checkOf(ev, BehaviorEvaluator.CHECK_SUPPORT);
        assertThat(support.status()).isEqualTo(BehaviorCheckStatus.NOT_ASSESSED);
        assertThat(support.reasonCode()).isEqualTo("CONTENT_UNAVAILABLE");
        BehaviorEvaluation.Check coverage = checkOf(ev, BehaviorEvaluator.CHECK_COVERAGE);
        assertThat(coverage.status()).isEqualTo(BehaviorCheckStatus.NOT_ASSESSED);
        assertThat(coverage.reasonCode()).isEqualTo("EVIDENCE_CORPUS_UNAVAILABLE");
        // 没有任何检查靠猜测通过
        assertThat(ev.checks())
                .noneMatch(c -> c.status() == BehaviorCheckStatus.PASS
                        && (c.name().equals(BehaviorEvaluator.CHECK_SUPPORT)
                        || c.name().equals(BehaviorEvaluator.CHECK_COVERAGE)));
    }

    // ------------------------------------------------------------------ EV-06

    @Test
    @DisplayName("EV-06：同轨迹同 grader 重算幂等；换 grader 版本结果并存不覆盖")
    void ev06SameTraceSameGraderIdempotentAndVersionsCoexist() {
        UUID evId = UUID.randomUUID();
        BehaviorInput in = input(pkg("s", List.of(trueClaim(List.of(evId.toString())))),
                List.of("payment timeout"), null, null,
                List.of(new BehaviorInput.TraceToolCall(UUID.randomUUID(), UUID.randomUUID(),
                        "logs_query", "v1", 1, "dig", "SUCCESS", null, evId.toString())),
                List.of(citation(evId.toString(), 0, evId, RUN, T0, T0.plusSeconds(60),
                        "payment timeout 命中")),
                List.of(corpus(evId, "payment timeout 命中")));

        BehaviorEvaluation first = evaluator.evaluate(in);
        BehaviorEvaluation second = evaluator.evaluate(in);
        // 同轨迹同 grader 重算：结果完全相等（traceDigest 稳定；落库侧 on conflict 幂等
        // 归 PostgresEvalCaseBehaviorSinkSqlContractTest）
        assertThat(second).isEqualTo(first);

        // 换 grader 版本：新结果带新版本号并存，旧结果不被改写
        BehaviorEvaluation regraded = new BehaviorEvaluator("behavior-v2").evaluate(in);
        assertThat(regraded.graderVersion()).isEqualTo("behavior-v2");
        assertThat(regraded.traceDigest()).isEqualTo(first.traceDigest());
        assertThat(regraded.checks()).isEqualTo(first.checks());
        assertThat(first.graderVersion()).isEqualTo(BehaviorEvaluator.GRADER_VERSION);
    }

    // ------------------------------------------------------------------ 补充面

    @Test
    @DisplayName("引用附带率：TRUE 根因 claim 无引用 FAIL 且 metrics 带分子/分母")
    void attachmentRateMetricCarriesNumeratorAndDenominator() {
        EvidencePackageV2 report = pkg("s", List.of(trueClaim(List.of()),
                trueClaim(List.of("ref-1"))));
        BehaviorEvaluation ev = evaluator.evaluate(input(report, List.of(), null, null,
                List.of(),
                List.of(citation("ref-1", 1, null, null, null, null, null)),
                List.of()));
        BehaviorEvaluation.Check attachment = checkOf(ev, BehaviorEvaluator.CHECK_ATTACHMENT);
        assertThat(attachment.status()).isEqualTo(BehaviorCheckStatus.FAIL);
        assertThat(attachment.reasonCode()).isEqualTo("CITATION_MISSING");
        BehaviorEvaluation.Metric metric = ev.metrics().stream()
                .filter(m -> m.name().equals("citation_attachment_rate")).findFirst()
                .orElseThrow();
        assertThat(metric.numerator()).isEqualTo(1);
        assertThat(metric.denominator()).isEqualTo(2);
        assertThat(ev.failureLabels()).contains("CITATION_MISSING");
    }

    @Test
    @DisplayName("观测读失败 ERROR 行：全检查 ERROR、traceDigest null、不冒充零问题")
    void readErrorRowMarksAllChecksError() {
        BehaviorEvaluation err = evaluator.readError();
        assertThat(err.traceDigest()).isNull();
        assertThat(err.checks()).hasSize(6).allMatch(c ->
                c.status() == BehaviorCheckStatus.ERROR
                        && c.reasonCode().equals("TRACE_READ_ERROR"));
        assertThat(err.failureLabels()).containsExactly("TRACE_READ_ERROR");
        assertThat(err.metrics()).isEmpty();
    }

    @Test
    @DisplayName("无报告/谨慎拒答：引用类检查 NOT_APPLICABLE，不冒充有据")
    void noReportOrUnresolvedLeavesCitationChecksNotApplicable() {
        BehaviorEvaluation noReport = evaluator.evaluate(input(null,
                List.of("cp"), null, null, List.of(), List.of(), List.of()));
        assertThat(checkOf(noReport, BehaviorEvaluator.CHECK_ATTACHMENT).status())
                .isEqualTo(BehaviorCheckStatus.NOT_APPLICABLE);
        assertThat(checkOf(noReport, BehaviorEvaluator.CHECK_SUPPORT).status())
                .isEqualTo(BehaviorCheckStatus.NOT_APPLICABLE);

        EvidencePackageV2 unresolved = new EvidencePackageV2(2, "s",
                new TypedRootCause("unresolved", "BUSINESS_ERROR_RATE", "PAYMENT_CHARGE_FAILURE"),
                List.of(trueClaim(List.of("r"))), List.of(), "i", "r", List.of());
        BehaviorEvaluation ev = evaluator.evaluate(input(unresolved,
                List.of(), null, null, List.of(), List.of(), List.of()));
        assertThat(checkOf(ev, BehaviorEvaluator.CHECK_ATTACHMENT).reasonCode())
                .isEqualTo("UNRESOLVED_SENTINEL");
    }
}
