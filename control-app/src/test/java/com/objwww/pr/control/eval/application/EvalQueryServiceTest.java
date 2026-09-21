package com.objwww.pr.control.eval.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.objwww.pr.control.eval.domain.repository.EvalQueryReader;
import com.objwww.pr.control.eval.domain.repository.EvalQueryReader.CaseEvidenceRefRow;
import com.objwww.pr.control.eval.domain.repository.EvalQueryReader.CaseIdentityRow;
import com.objwww.pr.control.eval.domain.repository.EvalQueryReader.CaseLogEvidenceRow;
import com.objwww.pr.control.eval.domain.repository.EvalQueryReader.CompareCaseRow;
import com.objwww.pr.control.eval.domain.repository.EvalQueryReader.CompareRunMeta;
import com.objwww.pr.control.eval.domain.repository.EvalQueryReader.DatasetRow;
import com.objwww.pr.control.eval.domain.repository.EvalQueryReader.EvalCaseDetailRow;
import com.objwww.pr.control.eval.domain.repository.EvalQueryReader.EvalCasePage;
import com.objwww.pr.control.eval.domain.repository.EvalQueryReader.EvalCaseRow;
import com.objwww.pr.control.eval.domain.repository.EvalQueryReader.EvalRunPage;
import com.objwww.pr.control.eval.domain.repository.EvalQueryReader.EvalRunRow;
import com.objwww.pr.control.eval.domain.repository.EvalQueryReader.EvidenceMetaRow;
import com.objwww.pr.control.eval.domain.repository.EvalQueryReader.KeysetCursor;
import com.objwww.pr.control.eval.domain.repository.EvalQueryReader.PartitionCountRow;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

/**
 * EvalQueryService 单测（UI-5/EV-03；IncidentQueryServiceTest 同模式——假端口纯函数段）：
 * runs/cases 游标编解码与校验、state/verdict 枚举校验、detail 装配、比率三件套
 * （OK/NOT_APPLICABLE/UNKNOWN）与状态分面、caseExecutionId 身份直透、
 * root cause/failureSample jsonb 摘要化、诚实 null 穿透。
 */
class EvalQueryServiceTest {

    private static final Instant NOW = Instant.parse("2026-09-09T10:30:00Z");

    private final FakeReader reader = new FakeReader();
    private final EvalRubricRegistry rubrics = EvalRubricRegistry.load("""
            registry_version: 1
            rubrics:
              - id: rca-eval-review
                version: eval-review-rubric-v1
                current: true
                items:
                  - id: root_cause_correct
                    label: 根因判定正确性
                    required: true
            """);
    private final EvalQueryService service =
            new EvalQueryService(reader, new ObjectMapper(), rubrics);

    // ------------------------------------------------------------------ runs 列表 / 游标

    @Test
    void listRunsEncodesKeysetCursorFromLastRowOnlyWhenHasMore() {
        Instant started = Instant.parse("2026-09-09T09:00:00Z");
        UUID id = UUID.randomUUID();
        reader.runPage = new EvalRunPage(
                List.of(runRow(UUID.randomUUID(), started.plusSeconds(60), "SUCCEEDED"),
                        runRow(id, started, "SUCCEEDED")), true);

        EvalQueryService.EvalRunListResponse out = service.listRuns("SUCCEEDED", null, 50);

        assertThat(out.nextCursor()).isEqualTo(started + "/" + id);
        assertThat(reader.lastState).isEqualTo("SUCCEEDED");
        assertThat(reader.lastRunCursor).isNull();
        assertThat(reader.lastRunLimit).isEqualTo(50);
    }

    @Test
    void listRunsWithoutMorePagesEmitsNullCursor() {
        reader.runPage = new EvalRunPage(List.of(runRow(UUID.randomUUID(), NOW, "RUNNING")), false);
        assertThat(service.listRuns(null, null, 50).nextCursor()).isNull();
    }

    @Test
    void listRunsParsesIncomingCursorIntoStructuredKeyset() {
        Instant at = Instant.parse("2026-09-08T01:02:03Z");
        UUID id = UUID.randomUUID();
        reader.runPage = new EvalRunPage(List.of(), false);

        service.listRuns(null, at + "/" + id, 50);

        assertThat(reader.lastRunCursor).isEqualTo(new KeysetCursor(at, id));
    }

    /** EV-09 稳定性三件套：多轮聚合 + 无案例 UNKNOWN 两态；D01 扩展面：无冻结
     *  launch plan（旧 CLI 跑批）→ micro/macro 实测照算，计划对照面如实 null/UNKNOWN */
    @Test
    void listRunsAssemblesStabilityFacetFromScenarioRoundStats() {
        UUID runId = UUID.randomUUID();
        reader.runPage = new EvalRunPage(List.of(runRow(runId, NOW, "SUCCEEDED")), false);
        reader.scenarioRoundStats = java.util.Map.of(runId, List.of(
                // s1：2 轮全命中且判定/根因一致 → 全过 + 一致
                new EvalQueryReader.ScenarioRoundStatRow(runId, "s1", 2, 2, 1, 1),
                // s2：2 轮 1 命中、判定漂移（DECIDABLE/UNRESOLVED）→ 非全过 + 不一致
                new EvalQueryReader.ScenarioRoundStatRow(runId, "s2", 2, 1, 2, 1)));

        EvalQueryService.EvalRunListResponse out = service.listRuns(null, null, 50);

        EvalQueryService.StabilityFacet stability = out.items().get(0).stability();
        assertThat(stability.passAt1().numerator()).isEqualTo(3L);
        assertThat(stability.passAt1().denominator()).isEqualTo(4L);
        assertThat(stability.passAllRounds().numerator()).isEqualTo(1L);
        assertThat(stability.passAllRounds().denominator()).isEqualTo(2L);
        assertThat(stability.scenarioConsistency().numerator()).isEqualTo(1L);
        assertThat(stability.scenarioConsistency().denominator()).isEqualTo(2L);
        // D01：两场景均有命中 → 至少一次成功 2/2；macro = (1 + 1/2)/2 = 0.75
        assertThat(stability.passAtLeastOnce().numerator()).isEqualTo(2L);
        assertThat(stability.passAtLeastOnce().denominator()).isEqualTo(2L);
        assertThat(stability.macroPassRate().value()).isCloseTo(0.75, within(1e-9));
        assertThat(stability.macroPassRate().samples()).isEqualTo(2L);
        // 无冻结计划快照（launchPlanJson=null）→ 计划对照面不猜
        assertThat(stability.roundsProgress().status()).isEqualTo("UNKNOWN");
        assertThat(stability.plannedRoundsPerScenario()).isNull();
        assertThat(stability.planComplete()).isNull();
    }

    /** D01/ST-05：无案例落档 → 全面 UNKNOWN/null，零适用分母绝不显示绿色 100% */
    @Test
    void listRunsWithoutCaseRowsReportsUnknownStability() {
        reader.runPage = new EvalRunPage(List.of(runRow(UUID.randomUUID(), NOW, "SUCCEEDED")), false);

        EvalQueryService.EvalRunListResponse out = service.listRuns(null, null, 50);

        EvalQueryService.StabilityFacet stability = out.items().get(0).stability();
        assertThat(stability.passAt1().status()).isEqualTo("UNKNOWN");
        assertThat(stability.passAllRounds().status()).isEqualTo("UNKNOWN");
        assertThat(stability.scenarioConsistency().status()).isEqualTo("UNKNOWN");
        assertThat(stability.passAtLeastOnce().status()).isEqualTo("UNKNOWN");
        assertThat(stability.macroPassRate().status()).isEqualTo("UNKNOWN");
        assertThat(stability.macroPassRate().value()).isNull();
        assertThat(stability.roundsProgress().status()).isEqualTo("UNKNOWN");
        assertThat(stability.plannedRoundsPerScenario()).isNull();
        assertThat(stability.planComplete()).isNull();
    }

    /** D01 冻结计划行：替换 launchPlanJson（其余列沿 runRow 形状） */
    private static EvalRunRow withLaunchPlan(EvalRunRow row, String launchPlanJson) {
        return withLifecycle(row, null, null, null, null, launchPlanJson);
    }

    /**
     * D01/ST-01：两场景各计划 3 轮且全部终态落档，A=[成功×3]、B=[成功,失败,成功]
     * → micro=5/6，全部计划轮次成功=1/2，至少一次成功=2/2（通行 pass@k 本义），
     * 固定 k=3 且计划完整 → 允许以 pass^3 命名（plannedRoundsPerScenario=3、
     * planComplete=true、进度 6/6）。
     */
    @Test
    void st01FixedKCompletePlanYieldsMicroAllSuccessAtLeastOnceAndK() {
        UUID runId = UUID.randomUUID();
        reader.runPage = new EvalRunPage(List.of(withLaunchPlan(runRow(runId, NOW, "SUCCEEDED"),
                "{\"roundsPerScenario\":3,\"caseKeys\":[\"sA\",\"sB\"]}")), false);
        reader.scenarioRoundStats = java.util.Map.of(runId, List.of(
                new EvalQueryReader.ScenarioRoundStatRow(runId, "sA", 3, 3, 1, 1),
                new EvalQueryReader.ScenarioRoundStatRow(runId, "sB", 3, 2, 2, 2)));

        EvalQueryService.StabilityFacet stability =
                service.listRuns(null, null, 50).items().get(0).stability();

        assertThat(stability.passAt1())
                .isEqualTo(new EvalQueryService.RatioStat(5L, 6L, "OK"));
        assertThat(stability.passAllRounds())
                .isEqualTo(new EvalQueryService.RatioStat(1L, 2L, "OK"));
        assertThat(stability.passAtLeastOnce())
                .isEqualTo(new EvalQueryService.RatioStat(2L, 2L, "OK"));
        assertThat(stability.plannedRoundsPerScenario()).isEqualTo(3);
        assertThat(stability.planComplete()).isTrue();
        assertThat(stability.roundsProgress())
                .isEqualTo(new EvalQueryService.RatioStat(6L, 6L, "OK"));
    }

    /**
     * D01/ST-02：计划 3 轮仅完成 1 轮且成功 → 进度 completed=1/planned=3 如实呈现，
     * planComplete=false（暂态观测）——不构成"3 轮全部成功"的最终通过结论。
     */
    @Test
    void st02PartialCompletionShowsProgressWithoutFinalPassConclusion() {
        UUID runId = UUID.randomUUID();
        reader.runPage = new EvalRunPage(List.of(withLaunchPlan(runRow(runId, NOW, "RUNNING"),
                "{\"roundsPerScenario\":3,\"caseKeys\":[\"sA\"]}")), false);
        reader.scenarioRoundStats = java.util.Map.of(runId, List.of(
                new EvalQueryReader.ScenarioRoundStatRow(runId, "sA", 1, 1, 1, 1)));

        EvalQueryService.StabilityFacet stability =
                service.listRuns(null, null, 50).items().get(0).stability();

        assertThat(stability.roundsProgress())
                .isEqualTo(new EvalQueryService.RatioStat(1L, 3L, "OK"));
        assertThat(stability.planComplete()).isFalse();
        assertThat(stability.plannedRoundsPerScenario()).isEqualTo(3);
        // 暂态值是真实已落档观测，照报不误标最终
        assertThat(stability.passAllRounds())
                .isEqualTo(new EvalQueryService.RatioStat(1L, 1L, "OK"));
    }

    /**
     * D01/ST-03：sA 回放形态计划 1 轮 1/1、sB 注入形态计划 3 轮 1/3 → micro=2/4、
     * macro=(1+1/3)/2 分称不混；场景轮数不同 → plannedRoundsPerScenario=null
     * （全成功比例绝不命名为统一 pass^3）。
     */
    @Test
    void st03MixedKSeparatesMicroFromMacroAndNeverNamesUniformPassK() {
        UUID runId = UUID.randomUUID();
        reader.runPage = new EvalRunPage(List.of(withLaunchPlan(runRow(runId, NOW, "SUCCEEDED"),
                "{\"roundsPerScenario\":3,\"caseKeys\":[\"sA\",\"sB\"]}")), false);
        // sA 为数据集回放案例 → runner effectiveRounds 同律裁剪计划 1 轮
        reader.planCaseKeysByDataset = java.util.Map.of("rca100-v1", List.of("sA"));
        reader.scenarioRoundStats = java.util.Map.of(runId, List.of(
                new EvalQueryReader.ScenarioRoundStatRow(runId, "sA", 1, 1, 1, 1),
                new EvalQueryReader.ScenarioRoundStatRow(runId, "sB", 3, 1, 1, 1)));

        EvalQueryService.StabilityFacet stability =
                service.listRuns(null, null, 50).items().get(0).stability();

        assertThat(stability.passAt1())
                .isEqualTo(new EvalQueryService.RatioStat(2L, 4L, "OK"));
        assertThat(stability.macroPassRate().value())
                .isCloseTo((1.0 + 1.0 / 3.0) / 2.0, within(1e-9));
        assertThat(stability.macroPassRate().samples()).isEqualTo(2L);
        assertThat(stability.plannedRoundsPerScenario()).isNull();
        assertThat(stability.planComplete()).isTrue();
        assertThat(stability.roundsProgress())
                .isEqualTo(new EvalQueryService.RatioStat(4L, 4L, "OK"));
        assertThat(stability.passAllRounds())
                .isEqualTo(new EvalQueryService.RatioStat(1L, 2L, "OK"));
    }

    /**
     * D01/ST-04：同一错误根因连续 3 轮（判定一致 DECIDABLE、实际根因一致但全错）
     * → consistency=1/1 而 task success=0/3：一致性照报但绝不能读成高质量结论；
     * 全缺席场景同理（0% 是真实零值，不是绿色 100%）。
     */
    @Test
    void st04ConsistentWrongAnswerIsConsistencyNotQuality() {
        UUID runId = UUID.randomUUID();
        reader.runPage = new EvalRunPage(List.of(withLaunchPlan(runRow(runId, NOW, "SUCCEEDED"),
                "{\"roundsPerScenario\":3,\"caseKeys\":[\"sA\"]}")), false);
        reader.scenarioRoundStats = java.util.Map.of(runId, List.of(
                new EvalQueryReader.ScenarioRoundStatRow(runId, "sA", 3, 0, 1, 1)));

        EvalQueryService.StabilityFacet stability =
                service.listRuns(null, null, 50).items().get(0).stability();

        assertThat(stability.scenarioConsistency())
                .isEqualTo(new EvalQueryService.RatioStat(1L, 1L, "OK"));
        assertThat(stability.passAt1())
                .isEqualTo(new EvalQueryService.RatioStat(0L, 3L, "OK"));
        assertThat(stability.passAllRounds())
                .isEqualTo(new EvalQueryService.RatioStat(0L, 1L, "OK"));
        assertThat(stability.passAtLeastOnce())
                .isEqualTo(new EvalQueryService.RatioStat(0L, 1L, "OK"));
        assertThat(stability.macroPassRate().value()).isCloseTo(0.0, within(1e-9));
    }

    /**
     * D01：launch_plan 快照缺 caseKeys 身份（FUP-03 前的历史批）→ 计划对照面
     * 整体 UNKNOWN/null（缺身份不猜轮次、不出完整性结论），实测比率仍照算。
     */
    @Test
    void launchPlanWithoutCaseKeysDegradesPlanFacetsHonestly() {
        UUID runId = UUID.randomUUID();
        reader.runPage = new EvalRunPage(List.of(withLaunchPlan(runRow(runId, NOW, "SUCCEEDED"),
                "{\"roundsPerScenario\":3}")), false);
        reader.scenarioRoundStats = java.util.Map.of(runId, List.of(
                new EvalQueryReader.ScenarioRoundStatRow(runId, "sA", 3, 3, 1, 1)));

        EvalQueryService.StabilityFacet stability =
                service.listRuns(null, null, 50).items().get(0).stability();

        assertThat(stability.passAt1())
                .isEqualTo(new EvalQueryService.RatioStat(3L, 3L, "OK"));
        assertThat(stability.roundsProgress().status()).isEqualTo("UNKNOWN");
        assertThat(stability.plannedRoundsPerScenario()).isNull();
        assertThat(stability.planComplete()).isNull();
    }

    /** BA-176：模型调用失败账聚合进列表项——总数 + 主因码（计数最高，并列取码序小者） */
    @Test
    void listRunsAggregatesModelCallFailuresWithDominantCode() {
        UUID runId = UUID.randomUUID();
        reader.runPage = new EvalRunPage(List.of(runRow(runId, NOW, "SUCCEEDED")), false);
        reader.modelCallFailures = java.util.Map.of(runId, List.of(
                new EvalQueryReader.ModelCallFailureRow(runId, "BILLING_OR_ACTIVATION", 5),
                new EvalQueryReader.ModelCallFailureRow(runId, "DEFERRED", 2)));

        EvalQueryService.EvalRunListItem item = service.listRuns(null, null, 50).items().get(0);

        assertThat(item.modelCallFailures()).isEqualTo(7L);
        assertThat(item.modelCallFailureCode()).isEqualTo("BILLING_OR_ACTIVATION");
    }

    /** BA-176：零失败账 → 计数 0、主因码 null（不冒充有码） */
    @Test
    void listRunsWithoutFailuresReportsZeroAndNullCode() {
        reader.runPage = new EvalRunPage(List.of(runRow(UUID.randomUUID(), NOW, "SUCCEEDED")),
                false);

        EvalQueryService.EvalRunListItem item = service.listRuns(null, null, 50).items().get(0);

        assertThat(item.modelCallFailures()).isZero();
        assertThat(item.modelCallFailureCode()).isNull();
    }

    @Test
    void malformedCursorAndBadStateAreRejected() {
        assertThatThrownBy(() -> service.listRuns(null, "garbage", 50))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.listRuns(null,
                "2026-09-08T01:02:03Z/not-a-uuid", 50))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.listRuns("running", null, 50))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("state");
    }

    // ------------------------------------------------------------------ EV-03：null 语义与分面

    @Test
    void runningRunKeepsHonestNullMetricsAndUnknownRatios() {
        reader.runPage = new EvalRunPage(List.of(runRow(UUID.randomUUID(), NOW, "RUNNING")), false);

        EvalQueryService.EvalRunListItem row = service.listRuns(null, null, 50).items().get(0);

        // 旧数值契约原样：未回填 → null，不回填 0
        assertThat(row.coverage()).isNull();
        assertThat(row.tp()).isNull();
        assertThat(row.finishedAt()).isNull();
        // 新三件套：未回填 → UNKNOWN 且分子分母 null（不填 0 冒充）
        assertThat(row.quality().coverage().status()).isEqualTo("UNKNOWN");
        assertThat(row.quality().coverage().numerator()).isNull();
        assertThat(row.quality().conditionalAccuracy().status()).isEqualTo("UNKNOWN");
        assertThat(row.quality().falseConfirmation().status()).isEqualTo("UNKNOWN");
        // 名称/模式/阶段无真实数据源 → 如实 null
        assertThat(row.displayName()).isNull();
        assertThat(row.mode()).isNull();
        assertThat(row.facets().phase()).isNull();
        assertThat(row.facets().stageEnteredAt()).isNull();
        assertThat(row.facets().leaseHeartbeatAt()).isNull();
    }

    @Test
    void succeededRunAssemblesRatiosWithRawCountsAndFacets() {
        UUID id = UUID.randomUUID();
        EvalRunRow row = runRow(id, NOW, "SUCCEEDED");
        reader.runPage = new EvalRunPage(List.of(row), false);

        EvalQueryService.EvalRunListItem out = service.listRuns(null, null, 50).items().get(0);

        // 三件套带原始分子分母（§5.2：必须显示分母）；沿 ScenarioMetrics 原公式
        assertThat(out.quality().coverage())
                .isEqualTo(new EvalQueryService.RatioStat(9L, 10L, "OK"));
        assertThat(out.quality().conditionalAccuracy())
                .isEqualTo(new EvalQueryService.RatioStat(8L, 9L, "OK"));
        assertThat(out.quality().endToEndHitRate())
                .isEqualTo(new EvalQueryService.RatioStat(8L, 10L, "OK"));
        assertThat(out.quality().unresolvedRate())
                .isEqualTo(new EvalQueryService.RatioStat(1L, 10L, "OK"));
        // 错误确认 = 可判定但根因错误 = decidable − hit（与症状 FP 分开命名）
        assertThat(out.quality().falseConfirmation())
                .isEqualTo(new EvalQueryService.RatioStat(1L, 9L, "OK"));
        // 旧数值字段原样保留（存量序列化契约不静默改）
        assertThat(out.coverage()).isEqualTo(0.9);
        assertThat(out.tp()).isEqualTo(5);
        // 状态分面：executionState = state 同义别名；未接线面 UNKNOWN；同步直读 LIVE
        assertThat(out.facets().executionState()).isEqualTo("SUCCEEDED");
        assertThat(out.facets().qualityVerdict()).isEqualTo("UNKNOWN");
        // EV-04 起 recoveryState 有真值语义：旧 CLI 行（mode=null）无恢复义务 → NOT_APPLICABLE
        assertThat(out.facets().recoveryState()).isEqualTo("NOT_APPLICABLE");
        assertThat(out.facets().usageStatus()).isEqualTo("UNKNOWN");
        assertThat(out.facets().costStatus()).isEqualTo("UNKNOWN");
        assertThat(out.facets().freshness()).isEqualTo("LIVE");
        assertThat(out.facets().lastProgressAt()).isEqualTo(row.lastProgressAt());
        // 计数与 asOf
        assertThat(out.caseCount()).isEqualTo(10);
        assertThat(out.totalScenarios()).isEqualTo(10);
        assertThat(service.listRuns(null, null, 50).asOf()).isNotNull();
    }

    @Test
    void zeroDenominatorYieldsNotApplicableWithRawCounts() {
        // 全未决：decidable=0 → 条件准确率与错误确认分母 0 → NOT_APPLICABLE（非 0.0 冒充）
        UUID id = UUID.randomUUID();
        reader.runPage = new EvalRunPage(List.of(
                runRowWithCounts(id, NOW, 10, 0, 0, 10)), false);

        EvalQueryService.EvalRunListItem out = service.listRuns(null, null, 50).items().get(0);

        assertThat(out.quality().conditionalAccuracy())
                .isEqualTo(new EvalQueryService.RatioStat(0L, 0L, "NOT_APPLICABLE"));
        assertThat(out.quality().falseConfirmation())
                .isEqualTo(new EvalQueryService.RatioStat(0L, 0L, "NOT_APPLICABLE"));
        assertThat(out.quality().coverage())
                .isEqualTo(new EvalQueryService.RatioStat(0L, 10L, "OK"));
        assertThat(out.quality().unresolvedRate())
                .isEqualTo(new EvalQueryService.RatioStat(10L, 10L, "OK"));
    }

    @Test
    void phaseProjectionPassesThroughFromReader() {
        UUID id = UUID.randomUUID();
        EvalRunRow row = runRow(id, NOW, "RUNNING");
        EvalRunRow withPhase = new EvalRunRow(row.runId(), row.datasetVersion(),
                row.registryDigest(), row.model(), row.promptVersion(), row.configDigest(),
                row.state(), row.startedAt(), row.finishedAt(), row.coverage(),
                row.conditionalAccuracy(), row.endToEndHitRate(), row.unresolvedRate(),
                row.tp(), row.fp(), row.fn(), row.displayName(), row.mode(),
                row.totalScenarios(), row.decidableCount(), row.hitCount(),
                row.unresolvedCount(), row.caseCount(), row.lastProgressAt(),
                "AWAITING_RCA", NOW.plusSeconds(30),
                row.recoveryState(), row.terminalReason(), row.cancelRequestedAt(),
                row.launchPlanJson(), row.comparisonGateOutcome());
        reader.runPage = new EvalRunPage(List.of(withPhase), false);

        EvalQueryService.EvalRunListItem out = service.listRuns(null, null, 50).items().get(0);

        assertThat(out.facets().phase()).isEqualTo("AWAITING_RCA");
        assertThat(out.facets().stageEnteredAt()).isEqualTo(NOW.plusSeconds(30));
    }

    // ------------------------------------------------------------------ EV-07 qualityVerdict 分面

    /** 复制行并替换 comparisonGateOutcome（EV-07 落档门结论列） */
    private static EvalRunRow withGateOutcome(EvalRunRow row, String gateOutcome) {
        return new EvalRunRow(row.runId(), row.datasetVersion(), row.registryDigest(),
                row.model(), row.promptVersion(), row.configDigest(), row.state(),
                row.startedAt(), row.finishedAt(), row.coverage(), row.conditionalAccuracy(),
                row.endToEndHitRate(), row.unresolvedRate(), row.tp(), row.fp(), row.fn(),
                row.displayName(), row.mode(), row.totalScenarios(), row.decidableCount(),
                row.hitCount(), row.unresolvedCount(), row.caseCount(), row.lastProgressAt(),
                row.phase(), row.phaseEnteredAt(), row.recoveryState(), row.terminalReason(),
                row.cancelRequestedAt(), row.launchPlanJson(), gateOutcome);
    }

    @Test
    void qualityVerdictFacetMapsLatestComparisonGateOutcome() {
        UUID id = UUID.randomUUID();
        // 无落档 → UNKNOWN（不编造）
        reader.runPage = new EvalRunPage(List.of(runRow(id, NOW, "SUCCEEDED")), false);
        assertThat(service.listRuns(null, null, 50).items().get(0).facets().qualityVerdict())
                .isEqualTo("UNKNOWN");
        // PASS → OK；FAIL → VIOLATED；INCONCLUSIVE/NOT_EVALUABLE → UNKNOWN（无法判定）
        reader.runPage = new EvalRunPage(
                List.of(withGateOutcome(runRow(id, NOW, "SUCCEEDED"), "PASS")), false);
        assertThat(service.listRuns(null, null, 50).items().get(0).facets().qualityVerdict())
                .isEqualTo("OK");
        reader.runPage = new EvalRunPage(
                List.of(withGateOutcome(runRow(id, NOW, "SUCCEEDED"), "FAIL")), false);
        assertThat(service.listRuns(null, null, 50).items().get(0).facets().qualityVerdict())
                .isEqualTo("VIOLATED");
        reader.runPage = new EvalRunPage(
                List.of(withGateOutcome(runRow(id, NOW, "SUCCEEDED"), "INCONCLUSIVE")), false);
        assertThat(service.listRuns(null, null, 50).items().get(0).facets().qualityVerdict())
                .isEqualTo("UNKNOWN");
        reader.runPage = new EvalRunPage(
                List.of(withGateOutcome(runRow(id, NOW, "SUCCEEDED"), "NOT_EVALUABLE")), false);
        assertThat(service.listRuns(null, null, 50).items().get(0).facets().qualityVerdict())
                .isEqualTo("UNKNOWN");
    }

    // ------------------------------------------------------------------ detail

    @Test
    void detailAssemblesRowWithFacetsAndAsOf() {
        UUID id = UUID.randomUUID();
        reader.run = runRow(id, NOW, "SUCCEEDED");

        EvalQueryService.EvalRunDetailResponse out = service.detail(id).orElseThrow();

        assertThat(out.runId()).isEqualTo(id);
        assertThat(out.caseCount()).isEqualTo(10);
        assertThat(out.datasetVersion()).isEqualTo("rca100-v1");
        assertThat(out.quality().endToEndHitRate().status()).isEqualTo("OK");
        assertThat(out.facets().executionState()).isEqualTo("SUCCEEDED");
        assertThat(out.asOf()).isNotNull();
    }

    @Test
    void runningDetailComputesLiveMetricsFromSettledCases() {
        UUID id = UUID.randomUUID();
        reader.run = runRow(id, NOW, "RUNNING");
        reader.liveMetrics = new EvalQueryReader.LiveMetricRow(45, 40, 37, 5, 80, 2, 6);

        EvalQueryService.EvalRunDetailResponse out = service.detail(id).orElseThrow();

        assertThat(out.endToEndHitRate()).isCloseTo(37.0 / 45, within(1e-9));
        assertThat(out.conditionalAccuracy()).isCloseTo(37.0 / 40, within(1e-9));
        assertThat(out.coverage()).isCloseTo(40.0 / 45, within(1e-9));
        assertThat(out.unresolvedRate()).isCloseTo(5.0 / 45, within(1e-9));
        assertThat(out.precision()).isCloseTo(80.0 / 82, within(1e-9));
        assertThat(out.recall()).isCloseTo(80.0 / 86, within(1e-9));
        assertThat(out.f1()).isNotNull();
        assertThat(out.tp()).isEqualTo(80);
        assertThat(out.quality().endToEndHitRate().numerator()).isEqualTo(37);
        assertThat(out.quality().endToEndHitRate().denominator()).isEqualTo(45);
        assertThat(out.quality().falseConfirmation().numerator()).isEqualTo(3);
    }

    @Test
    void runningDetailWithoutSettledCasesKeepsHonestNulls() {
        UUID id = UUID.randomUUID();
        reader.run = runRow(id, NOW, "RUNNING");

        EvalQueryService.EvalRunDetailResponse out = service.detail(id).orElseThrow();

        assertThat(out.endToEndHitRate()).isNull();
        assertThat(out.f1()).isNull();
        assertThat(out.tp()).isNull();
    }

    @Test
    void detailOfUnknownRunIsEmpty() {
        reader.run = null;
        assertThat(service.detail(UUID.randomUUID())).isEmpty();
    }

    /** BA-177：六要素完整率透出——有落档行 → complete/total（OK 三件套） */
    @Test
    void listRunsAndDetailExposeSixPartsRate() {
        UUID runId = UUID.randomUUID();
        reader.runPage = new EvalRunPage(List.of(runRow(runId, NOW, "SUCCEEDED")), false);
        reader.run = runRow(runId, NOW, "SUCCEEDED");
        reader.sixPartsStats = java.util.Map.of(runId, List.of(
                new EvalQueryReader.SixPartsStatRow(runId, 4, 3)));

        EvalQueryService.EvalRunListItem item = service.listRuns(null, null, 50).items().get(0);
        assertThat(item.sixPartsRate().status()).isEqualTo("OK");
        assertThat(item.sixPartsRate().numerator()).isEqualTo(3L);
        assertThat(item.sixPartsRate().denominator()).isEqualTo(4L);

        EvalQueryService.EvalRunDetailResponse detail = service.detail(runId).orElseThrow();
        assertThat(detail.sixPartsRate().status()).isEqualTo("OK");
        assertThat(detail.sixPartsRate().numerator()).isEqualTo(3L);
        assertThat(detail.sixPartsRate().denominator()).isEqualTo(4L);
    }

    /** BA-177：无落档行 → UNKNOWN（不填 0 冒充——老批早于 V152 接线本就无六要素账） */
    @Test
    void listRunsAndDetailWithoutSixPartsRowsReportUnknown() {
        UUID runId = UUID.randomUUID();
        reader.runPage = new EvalRunPage(List.of(runRow(runId, NOW, "SUCCEEDED")), false);
        reader.run = runRow(runId, NOW, "SUCCEEDED");

        EvalQueryService.EvalRunListItem item = service.listRuns(null, null, 50).items().get(0);
        assertThat(item.sixPartsRate().status()).isEqualTo("UNKNOWN");
        assertThat(item.sixPartsRate().numerator()).isNull();

        EvalQueryService.EvalRunDetailResponse detail = service.detail(runId).orElseThrow();
        assertThat(detail.sixPartsRate().status()).isEqualTo("UNKNOWN");
    }

    // ------------------------------------------------------------------ cases

    @Test
    void listCasesSummarizesRootCauseAndEncodesCursor() {
        UUID runId = UUID.randomUUID();
        UUID caseId1 = UUID.randomUUID();
        UUID caseId2 = UUID.randomUUID();
        UUID rcaRunId = UUID.randomUUID();
        UUID reportId = UUID.randomUUID();
        reader.run = runRow(runId, NOW, "SUCCEEDED");
        reader.casePage = new EvalCasePage(List.of(
                new EvalCaseRow(caseId1, "s1", 1, "DECIDABLE", true,
                        "{\"component\":\"redis\",\"fault_type\":\"OOM\",\"reason_code\":\"eviction\"}",
                        "{\"component\":\"redis\",\"fault_type\":\"OOM\",\"reason_code\":\"eviction\"}",
                        4200L, null, rcaRunId, reportId),
                new EvalCaseRow(caseId2, "s2", 1, "TIMEOUT_OR_ABSENT", false,
                        "{\"component\":\"db\",\"fault_type\":\"lock\",\"reason_code\":\"deadlock\"}",
                        null, null, "\"NO_MATCH: report absent\"", null, null)), true);

        EvalQueryService.EvalCaseListResponse out =
                service.listCases(runId, null, null, 50).orElseThrow();

        assertThat(out.items()).hasSize(2);
        EvalQueryService.EvalCaseItem first = out.items().get(0);
        assertThat(first.caseExecutionId()).isEqualTo(caseId1);
        assertThat(first.expectedRootCause()).isEqualTo("redis/OOM/eviction");
        assertThat(first.actualRootCause()).isEqualTo("redis/OOM/eviction");
        assertThat(first.latencyMs()).isEqualTo(4200L);
        assertThat(first.failureSample()).isNull();
        assertThat(first.rcaRunId()).isEqualTo(rcaRunId);
        assertThat(first.scoredReportId()).isEqualTo(reportId);
        EvalQueryService.EvalCaseItem second = out.items().get(1);
        assertThat(second.caseExecutionId()).isEqualTo(caseId2);
        assertThat(second.actualRootCause()).isNull();           // 无报告 → 诚实 null
        assertThat(second.failureSample()).isEqualTo("NO_MATCH: report absent"); // 文本标量解引
        assertThat(second.rcaRunId()).isNull();                   // 缺席 verdict 无关联 Run
        assertThat(out.nextCursor()).isEqualTo("s2/1");
    }

    @Test
    void listCasesValidatesVerdictAndParsesCursorWithSlashInScenarioId() {
        UUID runId = UUID.randomUUID();
        reader.run = runRow(runId, NOW, "SUCCEEDED");
        reader.casePage = new EvalCasePage(List.of(), false);

        service.listCases(runId, "DECIDABLE", "infra/redis-oom/2", 50);

        assertThat(reader.lastVerdict).isEqualTo("DECIDABLE");
        assertThat(reader.lastAfterScenario).isEqualTo("infra/redis-oom");
        assertThat(reader.lastAfterRound).isEqualTo(2);

        assertThatThrownBy(() -> service.listCases(runId, "decidable", null, 50))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("verdict");
        assertThatThrownBy(() -> service.listCases(runId, null, "no-round/", 50))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.listCases(runId, null, "s1/abc", 50))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void listCasesOfUnknownRunIsEmpty() {
        reader.run = null;
        assertThat(service.listCases(UUID.randomUUID(), null, null, 50)).isEmpty();
    }

    // ------------------------------------------------------------------ datasets

    @Test
    void datasetsPassThroughReaderRows() {
        UUID dvId = UUID.randomUUID();
        reader.datasets = List.of(new DatasetRow(dvId, "rca100", "v1.1", "rca100",
                "PUBLIC_BENCHMARK", "TUNING", 30, List.of("redis-oom", "db-lock"), NOW));
        reader.partitionCounts = List.of(
                new PartitionCountRow(dvId, "TUNING", 30),
                new PartitionCountRow(dvId, "HOLDOUT", 12));
        EvalQueryService.DatasetListResponse out = service.datasets();
        assertThat(out.items()).hasSize(1);
        assertThat(out.items().get(0).caseCount()).isEqualTo(30);
        assertThat(out.items().get(0).families()).containsExactly("redis-oom", "db-lock");
    }

    /** EV-08：名称/分区身份/全分区计数（HOLDOUT 只出计数）/当前 rubric 版本透出 */
    @Test
    void datasetsCarryNamePartitionCountsAndRubricVersion() {
        UUID dvId = UUID.randomUUID();
        reader.datasets = List.of(new DatasetRow(dvId, "rca100", "v1.1", "rca100",
                "PRIVATE", "HOLDOUT", 30, List.of("redis-oom"), NOW));
        reader.partitionCounts = List.of(
                new PartitionCountRow(dvId, "TUNING", 18),
                new PartitionCountRow(dvId, "HOLDOUT", 12));
        EvalQueryService.DatasetItem item = service.datasets().items().get(0);
        assertThat(item.name()).isEqualTo("rca100");
        assertThat(item.sourceClass()).isEqualTo("PRIVATE");
        assertThat(item.partitionClass()).isEqualTo("HOLDOUT");
        assertThat(item.partitionCounts())
                .containsEntry("TUNING", 18L).containsEntry("HOLDOUT", 12L);
        assertThat(item.rubricVersion()).isEqualTo("eval-review-rubric-v1");
    }

    /** EV-08：详情 = name+version 精确键，未知 404 面；已知 rubric 版本全表透出 */
    @Test
    void datasetDetailByNameAndVersion() {
        UUID dvId = UUID.randomUUID();
        reader.datasets = List.of(new DatasetRow(dvId, "rca100", "v1.1", "rca100",
                "PRIVATE", "HOLDOUT", 30, List.of(), NOW));
        assertThat(service.datasetDetail("rca100", "v9.9")).isEmpty();
        var detail = service.datasetDetail("rca100", "v1.1");
        assertThat(detail).isPresent();
        assertThat(detail.get().dataset().name()).isEqualTo("rca100");
        assertThat(detail.get().knownRubricVersions())
                .containsExactly("eval-review-rubric-v1");
    }

    /** 案例清单 drill-down：name+version 精确键，未知 404 面；
     *  期望根因三元组 "/" 拼接，症状码 jsonb 解析 */
    @Test
    void datasetCasesByNameAndVersion() {
        UUID dvId = UUID.randomUUID();
        reader.datasets = List.of(new DatasetRow(dvId, "rca100", "v1.1", "rca100",
                "PRIVATE", "TUNING", 2, List.of(), NOW));
        reader.datasetCaseRows = List.of(
                new EvalQueryReader.DatasetCaseRow("case-a", "op-smoke-family", "TUNING",
                        "订单服务超时故障注入", "order-arena", "LATENCY", "SLOW_QUERY",
                        "[\"OrderTimeout\",\"LatencySpike\"]"),
                new EvalQueryReader.DatasetCaseRow("case-b", "op-smoke-family", "TUNING",
                        null, null, null, null, null));
        assertThat(service.datasetCases("rca100", "v9.9")).isEmpty();
        var resp = service.datasetCases("rca100", "v1.1");
        assertThat(resp).isPresent();
        assertThat(resp.get().items()).hasSize(2);
        var first = resp.get().items().get(0);
        assertThat(first.expectedRootCause()).isEqualTo("order-arena/LATENCY/SLOW_QUERY");
        assertThat(first.expectedSymptomCodes())
                .containsExactly("OrderTimeout", "LatencySpike");
        var second = resp.get().items().get(1);
        assertThat(second.expectedRootCause()).isNull();
        assertThat(second.expectedSymptomCodes()).isNull();
    }

    // ------------------------------------------------------------------ F1 派生字段（读面现算）

    /** 指定 tp/fp/fn 的终态行（其余列沿 runRow 形状） */
    private static EvalRunRow runRowWithSymptoms(UUID id, Instant startedAt,
                                                 Integer tp, Integer fp, Integer fn) {
        return new EvalRunRow(id, "rca100-v1", "a".repeat(64), "gpt-5", "p3",
                "b".repeat(64), "SUCCEEDED", startedAt, startedAt.plusSeconds(600),
                0.0, 0.0, 0.0, 0.0, tp, fp, fn,
                null, null, 10, 9, 8, 1, 10, startedAt.plusSeconds(590),
                null, null, null, null, null, null, null);
    }

    @Test
    void precisionRecallF1DerivedFromSymptomCounts() {
        // tp=18, fp=2, fn=2 → P=18/20=0.9，R=18/20=0.9，F1=2·0.9·0.9/1.8=0.9
        UUID id = UUID.randomUUID();
        reader.runPage = new EvalRunPage(List.of(runRowWithSymptoms(id, NOW, 18, 2, 2)), false);

        EvalQueryService.EvalRunListItem out = service.listRuns(null, null, 50).items().get(0);

        assertThat(out.precision()).isEqualTo(0.9);
        assertThat(out.recall()).isEqualTo(0.9);
        assertThat(out.f1()).isEqualTo(0.9);
        // 详情面同口径
        reader.run = runRowWithSymptoms(id, NOW, 18, 2, 2);
        EvalQueryService.EvalRunDetailResponse detail = service.detail(id).orElseThrow();
        assertThat(detail.precision()).isEqualTo(0.9);
        assertThat(detail.recall()).isEqualTo(0.9);
        assertThat(detail.f1()).isEqualTo(0.9);
    }

    @Test
    void zeroDenominatorSymptomCountsYieldNullRatesNotZero() {
        // tp=fp=0 → precision 分母 0 → null；fn=2 → recall=0/2=0.0（真实零，保留）；
        // P null → f1 null（不硬塞 0）
        UUID id = UUID.randomUUID();
        reader.runPage = new EvalRunPage(List.of(runRowWithSymptoms(id, NOW, 0, 0, 2)), false);

        EvalQueryService.EvalRunListItem out = service.listRuns(null, null, 50).items().get(0);

        assertThat(out.precision()).isNull();
        assertThat(out.recall()).isEqualTo(0.0);
        assertThat(out.f1()).isNull();
        // tp=fp=fn=0：三值分母全 0 → 全 null
        reader.runPage = new EvalRunPage(List.of(runRowWithSymptoms(id, NOW, 0, 0, 0)), false);
        EvalQueryService.EvalRunListItem bare = service.listRuns(null, null, 50).items().get(0);
        assertThat(bare.precision()).isNull();
        assertThat(bare.recall()).isNull();
        assertThat(bare.f1()).isNull();
    }

    @Test
    void runningRunKeepsNullDerivedRates() {
        // 计数未回填（RUNNING）→ 派生三率 null（不填 0 冒充）
        reader.runPage = new EvalRunPage(List.of(runRow(UUID.randomUUID(), NOW, "RUNNING")), false);

        EvalQueryService.EvalRunListItem out = service.listRuns(null, null, 50).items().get(0);

        assertThat(out.precision()).isNull();
        assertThat(out.recall()).isNull();
        assertThat(out.f1()).isNull();
    }

    // ------------------------------------------------------------------ 每案 token 投影

    @Test
    void listCasesCarriesPerCaseTokenTotals() {
        UUID runId = UUID.randomUUID();
        UUID caseId1 = UUID.randomUUID();
        UUID caseId2 = UUID.randomUUID();
        reader.run = runRow(runId, NOW, "SUCCEEDED");
        reader.casePage = new EvalCasePage(List.of(
                new EvalCaseRow(caseId1, "s1", 1, "DECIDABLE", true,
                        "{\"component\":\"redis\"}", "{\"component\":\"redis\"}",
                        4200L, null, UUID.randomUUID(), null),
                new EvalCaseRow(caseId2, "s2", 1, "TIMEOUT_OR_ABSENT", false,
                        "{\"component\":\"db\"}", null, null, null, null, null)), false);
        reader.tokenTotalsByCase = java.util.Map.of(caseId1,
                new EvalQueryReader.CaseTokenRow(caseId1, 1200L, 340L, 1540L));

        EvalQueryService.EvalCaseListResponse out =
                service.listCases(runId, null, null, 50).orElseThrow();

        EvalQueryService.EvalCaseItem withCalls = out.items().get(0);
        assertThat(withCalls.promptTokens()).isEqualTo(1200L);
        assertThat(withCalls.completionTokens()).isEqualTo(340L);
        assertThat(withCalls.totalTokens()).isEqualTo(1540L);
        // 无模型调用记录的案例 → 三值 null 如实（不填 0）
        EvalQueryService.EvalCaseItem noCalls = out.items().get(1);
        assertThat(noCalls.promptTokens()).isNull();
        assertThat(noCalls.completionTokens()).isNull();
        assertThat(noCalls.totalTokens()).isNull();
    }

    // ------------------------------------------------------------------ EV-04 生命周期分面

    /** 复制行并替换 EV-04 四列（recoveryState/terminalReason/cancelRequestedAt/launchPlan） */
    private static EvalRunRow withLifecycle(EvalRunRow row, String mode, String recoveryState,
                                            String terminalReason, Instant cancelRequestedAt,
                                            String launchPlanJson) {
        return new EvalRunRow(row.runId(), row.datasetVersion(), row.registryDigest(),
                row.model(), row.promptVersion(), row.configDigest(), row.state(),
                row.startedAt(), row.finishedAt(), row.coverage(), row.conditionalAccuracy(),
                row.endToEndHitRate(), row.unresolvedRate(), row.tp(), row.fp(), row.fn(),
                row.displayName(), mode, row.totalScenarios(), row.decidableCount(),
                row.hitCount(), row.unresolvedCount(), row.caseCount(), row.lastProgressAt(),
                row.phase(), row.phaseEnteredAt(),
                recoveryState, terminalReason, cancelRequestedAt, launchPlanJson,
                row.comparisonGateOutcome());
    }

    @Test
    void recoveryFacetPassesRealValueThrough() {
        UUID id = UUID.randomUUID();
        reader.runPage = new EvalRunPage(List.of(withLifecycle(
                runRow(id, NOW, "RUNNING"), "L", "RECOVERING", null, null, null)), false);

        EvalQueryService.EvalRunListItem out = service.listRuns(null, null, 50).items().get(0);

        assertThat(out.facets().recoveryState()).isEqualTo("RECOVERING");
    }

    @Test
    void liveModeWithoutRecoveryReportIsUnknownNotApplicableNeverFaked() {
        UUID id = UUID.randomUUID();
        reader.runPage = new EvalRunPage(List.of(
                withLifecycle(runRow(id, NOW, "RUNNING"), "L", null, null, null, null),
                withLifecycle(runRow(UUID.randomUUID(), NOW, "RUNNING"), "E",
                        null, null, null, null),
                withLifecycle(runRow(UUID.randomUUID(), NOW, "RUNNING"), null,
                        null, null, null, null)), false);

        List<EvalQueryService.EvalRunListItem> items = service.listRuns(null, null, 50).items();

        assertThat(items.get(0).facets().recoveryState()).isEqualTo("UNKNOWN");
        assertThat(items.get(1).facets().recoveryState()).isEqualTo("NOT_APPLICABLE");
        assertThat(items.get(2).facets().recoveryState()).isEqualTo("NOT_APPLICABLE");
    }

    @Test
    void detailCarriesTerminalReasonCancelRequestedAtAndLaunchPlan() {
        UUID id = UUID.randomUUID();
        Instant cancelAt = NOW.plusSeconds(120);
        reader.run = withLifecycle(runRow(id, NOW, "FAILED"), "L", "VERIFIED",
                "cancelled_by_operator;recovery=VERIFIED", cancelAt,
                "{\"displayName\":\"实验甲\",\"mode\":\"L\",\"budgetMaxTokens\":10000}");

        EvalQueryService.EvalRunDetailResponse out = service.detail(id).orElseThrow();

        assertThat(out.terminalReason())
                .isEqualTo("cancelled_by_operator;recovery=VERIFIED");
        assertThat(out.facets().recoveryState()).isEqualTo("VERIFIED");
        assertThat(out.facets().cancelRequestedAt()).isEqualTo(cancelAt);
        assertThat(out.launchPlan().get("displayName").asText()).isEqualTo("实验甲");
        assertThat(out.launchPlan().get("budgetMaxTokens").asLong()).isEqualTo(10000L);
    }

    @Test
    void detailWithoutLifecycleDataIsHonestNull() {
        UUID id = UUID.randomUUID();
        reader.run = runRow(id, NOW, "SUCCEEDED");

        EvalQueryService.EvalRunDetailResponse out = service.detail(id).orElseThrow();

        assertThat(out.terminalReason()).isNull();
        assertThat(out.launchPlan()).isNull();
        assertThat(out.facets().cancelRequestedAt()).isNull();
        assertThat(out.facets().recoveryState()).isEqualTo("NOT_APPLICABLE");
    }

    @Test
    void listItemCarriesTerminalReasonForFailedRuns() {
        // BA-190（W4）：列表行透出终态卡因——worker_lost 等失败批在评测列表可直接
        // 展示中文解读（前端 zh.js 字典），不必逐批进详情
        UUID id = UUID.randomUUID();
        reader.runPage = new EvalRunPage(List.of(withLifecycle(
                runRow(id, NOW, "FAILED"), "L", "PENDING",
                "worker_lost;recovery_unverified", null, null)), false);

        EvalQueryService.EvalRunListItem out = service.listRuns(null, null, 50).items().get(0);

        assertThat(out.terminalReason()).isEqualTo("worker_lost;recovery_unverified");
    }

    // ------------------------------------------------------------------ fakes

    /**
     * 终态行：total=10 / decidable=9 / hit=8 / unresolved=1（三率 0.9/0.8-循环/0.8/0.1，
     * 错误确认 = 9−8 = 1）；RUNNING 行指标与计数全 null（未回填），无名称/模式/阶段。
     */
    private static EvalRunRow runRow(UUID id, Instant startedAt, String state) {
        boolean terminal = !"RUNNING".equals(state);
        return new EvalRunRow(id, "rca100-v1", "a".repeat(64), "gpt-5", "p3",
                "b".repeat(64), state, startedAt, terminal ? startedAt.plusSeconds(600) : null,
                terminal ? 0.9 : null, terminal ? 0.8 : null, terminal ? 0.7 : null,
                terminal ? 0.1 : null, terminal ? 5 : null, terminal ? 1 : null,
                terminal ? 2 : null,
                null, null,
                terminal ? 10 : null, terminal ? 9 : null, terminal ? 8 : null,
                terminal ? 1 : null, terminal ? 10 : 0,
                terminal ? startedAt.plusSeconds(590) : null,
                null, null,
                null, null, null, null, null);
    }

    private static EvalRunRow runRowWithCounts(UUID id, Instant startedAt, int total,
                                               int decidable, int hit, int unresolved) {
        return new EvalRunRow(id, "rca100-v1", "a".repeat(64), "gpt-5", "p3",
                "b".repeat(64), "SUCCEEDED", startedAt, startedAt.plusSeconds(600),
                0.0, 0.0, 0.0, 1.0, 0, 0, 0,
                null, null,
                total, decidable, hit, unresolved, total, startedAt.plusSeconds(590),
                null, null,
                null, null, null, null, null);
    }

    // ------------------------------------------------------------------ EV-05 案例详情

    private static EvalCaseDetailRow detailRow(UUID runId, UUID caseId, UUID rcaRunId,
                                               UUID reportId, String verdict,
                                               String packageJson) {
        return new EvalCaseDetailRow(caseId, runId, "infra/redis-oom", 1, "rca100-v1.1",
                "m3-16-v1", verdict, true,
                "{\"component\":\"redis\",\"fault_type\":\"OOM\",\"reason_code\":\"eviction\"}",
                "{\"component\":\"redis\",\"fault_type\":\"OOM\",\"reason_code\":\"eviction\"}",
                "[\"alert:redis_down\"]", "[\"alert:redis_down\"]",
                1, 0, 0, 4200L, false, null, NOW,
                rcaRunId, UUID.randomUUID(), reportId,
                rcaRunId == null ? null : "SUCCEEDED",
                rcaRunId == null ? null : UUID.randomUUID(),
                rcaRunId == null ? null : NOW.minusSeconds(300),
                rcaRunId == null ? null : NOW.minusSeconds(60),
                reportId == null ? null : 2,
                reportId == null ? null : "STRUCTURE_VALIDATED",
                reportId == null ? null : "holmes-1.0",
                reportId == null ? null : NOW.minusSeconds(50),
                packageJson);
    }

    /** v2 报告包（claims 形态合法；refs 参数逐 claim 一组） */
    private static String packageJson(String claims) {
        return "{\"schema_version\":2,\"summary\":\"s\",\"impact\":\"i\",\"remediation\":\"r\","
                + "\"root_cause\":{\"component\":\"redis\",\"fault_type\":\"OOM\","
                + "\"reason_code\":\"eviction\"},\"evidence\":[\"e\"],\"references\":[],"
                + "\"claims\":[" + claims + "]}";
    }

    private static String claim(String type, String status, String... refs) {
        StringBuilder refJson = new StringBuilder();
        for (String ref : refs) {
            if (refJson.length() > 0) {
                refJson.append(',');
            }
            refJson.append('"').append(ref).append('"');
        }
        return "{\"claim_type\":\"" + type + "\",\"status\":\"" + status + "\","
                + "\"component\":\"redis\",\"fault_type\":\"OOM\","
                + "\"symptom_codes\":[],\"evidence_refs\":[" + refJson + "]}";
    }

    @Test
    void caseDetailAssemblesIdentityLinkageAndEvidenceBuckets() {
        UUID runId = UUID.randomUUID();
        UUID caseId = UUID.randomUUID();
        UUID rcaRunId = UUID.randomUUID();
        UUID reportId = UUID.randomUUID();
        UUID evTrue = UUID.randomUUID();
        UUID evFalse = UUID.randomUUID();
        UUID evMissing = UUID.randomUUID();
        reader.caseDetail = detailRow(runId, caseId, rcaRunId, reportId, "DECIDABLE",
                packageJson(claim("causal", "TRUE", evTrue.toString(), "loki:query:x")
                        + "," + claim("counter", "FALSE", evFalse.toString(), evMissing.toString())
                        + "," + claim("unclear", "UNKNOWN")));
        reader.caseIdentity = new CaseIdentityRow("infra/redis-oom", "redis-oom",
                "c".repeat(64), NOW.minusSeconds(3600), null, "VALIDATION",
                "rca100", "rca100-v1.1", "PUBLIC_BENCHMARK");
        reader.evidenceMeta = List.of(
                new EvidenceMetaRow(evTrue, rcaRunId, "logs.query", "logs", null,
                        NOW.minusSeconds(120), NOW, "d".repeat(64), NOW),
                new EvidenceMetaRow(evFalse, rcaRunId, "metrics.query_range", "metrics", null,
                        null, null, "e".repeat(64), NOW));

        EvalQueryService.EvalCaseDetailResponse out =
                service.caseDetail(runId, caseId).orElseThrow();

        assertThat(reader.lastDetailRunId).isEqualTo(runId);
        assertThat(reader.lastDetailCaseId).isEqualTo(caseId);
        assertThat(out.caseExecutionId()).isEqualTo(caseId);
        assertThat(out.verdict()).isEqualTo("DECIDABLE");
        assertThat(out.expectedRootCause()).isEqualTo("redis/OOM/eviction");
        assertThat(out.expectedSymptomCodes()).containsExactly("alert:redis_down");
        // 场景身份：精确键命中
        assertThat(out.scenarioIdentity().resolved()).isTrue();
        assertThat(out.scenarioIdentity().caseKey()).isEqualTo("infra/redis-oom");
        assertThat(out.scenarioIdentity().partitionClass()).isEqualTo("VALIDATION");
        assertThat(out.scenarioIdentity().unresolvedReason()).isNull();
        assertThat(out.scenarioIdentity().matchedDatasets()).isEmpty();
        assertThat(reader.lastIdentityDataset).isEqualTo("rca100-v1.1");
        assertThat(reader.lastIdentityScenario).isEqualTo("infra/redis-oom");
        // 关联链
        assertThat(out.linkage().rcaRunId()).isEqualTo(rcaRunId);
        assertThat(out.linkage().rcaRunState()).isEqualTo("SUCCEEDED");
        assertThat(out.linkage().reportValidationStatus()).isEqualTo("STRUCTURE_VALIDATED");
        assertThat(out.report().summary()).isEqualTo("s");
        // 证据：支持/反对/未决三态分列表
        assertThat(out.evidence().status()).isEqualTo("OK");
        assertThat(out.evidence().supporting()).hasSize(1);
        assertThat(out.evidence().refuting()).hasSize(1);
        assertThat(out.evidence().undetermined()).hasSize(1);
        List<EvalQueryService.ResolvedEvidenceRef> supportingRefs =
                out.evidence().supporting().get(0).refs();
        // UUID 且本 run 内 → 解析出元数据；非 UUID 形态 → resolved=false 元数据全 null
        assertThat(supportingRefs.get(0).resolved()).isTrue();
        assertThat(supportingRefs.get(0).evidenceType()).isEqualTo("logs.query");
        assertThat(supportingRefs.get(0).payloadDigest()).isEqualTo("d".repeat(64));
        assertThat(supportingRefs.get(1).resolved()).isFalse();
        assertThat(supportingRefs.get(1).evidenceId()).isNull();
        // UUID 形态但不在本 rca_run 范围（reader 不返回）→ resolved=false（跨对象不读）
        assertThat(out.evidence().refuting().get(0).refs().get(0).resolved()).isTrue();
        assertThat(out.evidence().refuting().get(0).refs().get(1).resolved()).isFalse();
        assertThat(reader.lastMetaRcaRunId).isEqualTo(rcaRunId);
        assertThat(reader.lastMetaIds).containsExactlyInAnyOrder(evTrue, evFalse, evMissing);
        assertThat(out.asOf()).isNotNull();
    }

    @Test
    void caseDetailWithoutReportIsHonestNoReport() {
        UUID runId = UUID.randomUUID();
        UUID caseId = UUID.randomUUID();
        // TIMEOUT_OR_ABSENT：关联链全断（V10 verdict 形态约束）→ 全 null 如实
        reader.caseDetail = detailRow(runId, caseId, null, null, "TIMEOUT_OR_ABSENT", null);
        reader.caseIdentity = null;

        EvalQueryService.EvalCaseDetailResponse out =
                service.caseDetail(runId, caseId).orElseThrow();

        assertThat(out.evidence().status()).isEqualTo("NO_REPORT");
        assertThat(out.evidence().supporting()).isEmpty();
        assertThat(out.report()).isNull();
        assertThat(out.linkage().rcaRunId()).isNull();
        assertThat(out.linkage().reportModel()).isNull();
        assertThat(out.scenarioIdentity().resolved()).isFalse();
        assertThat(out.scenarioIdentity().caseKey()).isNull();
        assertThat(out.scenarioIdentity().datasetVersion()).isEqualTo("rca100-v1.1");
        // BA-169：0 命中 = 无匹配或 HOLDOUT 不可见（安全纪律不区分两者）
        assertThat(out.scenarioIdentity().unresolvedReason()).isEqualTo("NO_MATCH_OR_HOLDOUT");
        assertThat(out.scenarioIdentity().matchedDatasets()).isEmpty();
    }

    @Test
    void caseDetailAmbiguousIdentityReportsMatchedDatasets() {
        UUID runId = UUID.randomUUID();
        UUID caseId = UUID.randomUUID();
        reader.caseDetail = detailRow(runId, caseId, null, null, "TIMEOUT_OR_ABSENT", null);
        // BA-169：同名数据集版本跨数据集并存（195 实证 eval-ds-1 同时存在于两个数据集）
        reader.caseIdentityMatches = List.of(
                new CaseIdentityRow("infra/redis-oom", "redis-oom", "c".repeat(64),
                        NOW.minusSeconds(3600), null, "TUNING",
                        "arena-replay-ds", "eval-ds-1", "INTERNAL_SYNTHETIC"),
                new CaseIdentityRow("infra/redis-oom", "redis-oom", "d".repeat(64),
                        NOW.minusSeconds(1800), null, "REDTEAM",
                        "redteam-ds", "eval-ds-1", "INTERNAL_SYNTHETIC"));

        EvalQueryService.EvalCaseDetailResponse out =
                service.caseDetail(runId, caseId).orElseThrow();

        assertThat(out.scenarioIdentity().resolved()).isFalse();
        assertThat(out.scenarioIdentity().caseKey()).isNull();
        assertThat(out.scenarioIdentity().unresolvedReason()).isEqualTo("AMBIGUOUS");
        assertThat(out.scenarioIdentity().matchedDatasets())
                .containsExactly("arena-replay-ds", "redteam-ds");
    }

    @Test
    void caseDetailMarksUnparseableOrUnsupportedPackageExplicitly() {
        UUID runId = UUID.randomUUID();
        reader.caseDetail = detailRow(runId, UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID(), "DECIDABLE", "{not-json");
        EvalQueryService.EvalCaseDetailResponse broken =
                service.caseDetail(runId, reader.caseDetail.caseExecutionId()).orElseThrow();
        assertThat(broken.evidence().status()).isEqualTo("PACKAGE_UNPARSEABLE");
        assertThat(broken.report()).isNull();

        // 非 v2 包（v1 六段式无 claims）→ 不猜结构
        EvalCaseDetailRow v1 = new EvalCaseDetailRow(
                reader.caseDetail.caseExecutionId(), runId, "s1", 1, "ds", "p",
                "DECIDABLE", true, null, null, null, null, null, null, null, null,
                false, null, NOW, UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                "SUCCEEDED", UUID.randomUUID(), NOW, NOW, 1, "STRUCTURE_VALIDATED",
                "holmes-0.9", NOW, "{\"schema_version\":1}");
        reader.caseDetail = v1;
        EvalQueryService.EvalCaseDetailResponse legacy =
                service.caseDetail(runId, v1.caseExecutionId()).orElseThrow();
        assertThat(legacy.evidence().status()).isEqualTo("UNSUPPORTED_SCHEMA_VERSION");
    }

    @Test
    void caseDetailOfUnknownCaseIsEmpty() {
        reader.caseDetail = null;
        assertThat(service.caseDetail(UUID.randomUUID(), UUID.randomUUID())).isEmpty();
    }

    // ------------------------------------------------------------------ EV-05 Run 证据汇总

    @Test
    void evidenceSummaryBucketsRefsPerCaseAndAggregates() {
        UUID runId = UUID.randomUUID();
        UUID case1 = UUID.randomUUID();
        UUID case2 = UUID.randomUUID();
        UUID case3 = UUID.randomUUID();
        UUID reportId = UUID.randomUUID();
        UUID rcaRunId = UUID.randomUUID();
        UUID evId = UUID.randomUUID();
        reader.run = runRow(runId, NOW, "SUCCEEDED");
        reader.evidenceRefRows = List.of(
                // case1：两条 resolved（logs+metrics）+ 一条跨 run 未解析；TRUE/FALSE/UNKNOWN 各一
                new CaseEvidenceRefRow(case1, "s1", 1, "DECIDABLE", rcaRunId, reportId,
                        "TRUE", "causal", evId.toString(), evId, "logs.query"),
                new CaseEvidenceRefRow(case1, "s1", 1, "DECIDABLE", rcaRunId, reportId,
                        "FALSE", "counter", UUID.randomUUID().toString(),
                        UUID.randomUUID(), "metrics.query_range"),
                new CaseEvidenceRefRow(case1, "s1", 1, "DECIDABLE", rcaRunId, reportId,
                        "UNKNOWN", "unclear", "loki:query:x", null, null),
                // case2：有报告但零引用（哨兵行）→ NO_REFS
                new CaseEvidenceRefRow(case2, "s2", 1, "UNRESOLVED", rcaRunId, reportId,
                        null, null, null, null, null),
                // case3：无报告 → NO_REPORT
                new CaseEvidenceRefRow(case3, "s3", 1, "TIMEOUT_OR_ABSENT", null, null,
                        null, null, null, null, null));

        EvalQueryService.RunEvidenceSummaryResponse out =
                service.evidenceSummary(runId).orElseThrow();

        assertThat(out.runId()).isEqualTo(runId);
        assertThat(out.caseCount()).isEqualTo(3);
        assertThat(out.casesWithReport()).isEqualTo(2);
        assertThat(out.totalRefs()).isEqualTo(3);
        assertThat(out.byType()).containsExactlyInAnyOrderEntriesOf(Map.of(
                "logs.query", 1L, "metrics.query_range", 1L));
        EvalQueryService.CaseEvidenceSummary first = out.cases().get(0);
        assertThat(first.caseExecutionId()).isEqualTo(case1);
        assertThat(first.status()).isEqualTo("OK");
        assertThat(first.totalRefs()).isEqualTo(3);
        assertThat(first.resolvedRefs()).isEqualTo(2);
        assertThat(first.unresolvedRefs()).isEqualTo(1);
        assertThat(first.supportingRefs()).isEqualTo(1);
        assertThat(first.refutingRefs()).isEqualTo(1);
        assertThat(first.undeterminedRefs()).isEqualTo(1);
        assertThat(first.byType()).containsExactlyInAnyOrderEntriesOf(Map.of(
                "logs.query", 1L, "metrics.query_range", 1L));
        assertThat(out.cases().get(1).status()).isEqualTo("NO_REFS");
        assertThat(out.cases().get(2).status()).isEqualTo("NO_REPORT");
        assertThat(out.cases().get(2).totalRefs()).isZero();
    }

    @Test
    void evidenceSummaryOfUnknownRunIsEmpty() {
        reader.run = null;
        assertThat(service.evidenceSummary(UUID.randomUUID())).isEmpty();
    }

    // ------------------------------------------------------------------ R6/EV-06 usage 投影

    @Test
    void usageGroupsByRoleStateStatusAndCurrency_neverSumsAcrossGroups() {
        UUID runId = UUID.randomUUID();
        reader.run = runRow(runId, NOW, "SUCCEEDED");
        reader.usageCalls = List.of(
                usageRow("primary", "SUCCESS", 100, 20, 120, 1500L, "pv-2026-09", "CNY", false),
                usageRow("primary", "SUCCESS", 50, 10, 60, 750L, "pv-2026-09", "CNY", false),
                usageRow("primary", "SUCCESS", 30, 5, 35, null, "unpriced", null, false),
                usageRow("logs", "SUCCESS", null, null, null, null, null, null, true),
                usageRow("logs", "FAILED", null, null, null, null, null, null, false),
                usageRow("metrics", "SUCCESS", 10, 4, 14, 90L, "pv-2026-09", "USD", false));

        EvalQueryService.UsageResponse out = service.usage(runId).orElseThrow();

        assertThat(out.totalCalls()).isEqualTo(6);
        assertThat(out.usageMissingCalls()).isEqualTo(2);
        // priced CNY 组聚合同组两行（150/30, cost 2250），绝不与 USD 组相加（EU20）
        EvalQueryService.UsageGroup pricedCny = groupOf(out, "primary", "priced");
        assertThat(pricedCny.calls()).isEqualTo(2);
        assertThat(pricedCny.promptTokens()).isEqualTo(150);
        assertThat(pricedCny.completionTokens()).isEqualTo(30);
        assertThat(pricedCny.costMicros()).isEqualTo(2250L);
        assertThat(pricedCny.currency()).isEqualTo("CNY");
        assertThat(pricedCny.pricingVersion()).isEqualTo("pv-2026-09");
        // unpriced 组：tokens 在场、cost 恒 null（不猜零，R4/EU19）
        EvalQueryService.UsageGroup unpriced = groupOf(out, "primary", "unpriced");
        assertThat(unpriced.calls()).isEqualTo(1);
        assertThat(unpriced.promptTokens()).isEqualTo(30);
        assertThat(unpriced.costMicros()).isNull();
        // usage_missing 面：SUCCESS+usage_missing 与 FAILED 分属两组（state 入键），
        // 组内 tokens/cost 恒 null
        EvalQueryService.UsageGroup missingOk =
                groupOf(out, "logs", "SUCCESS", "usage_missing");
        assertThat(missingOk.calls()).isEqualTo(1);
        assertThat(missingOk.promptTokens()).isNull();
        assertThat(missingOk.costMicros()).isNull();
        assertThat(groupOf(out, "logs", "FAILED", "usage_missing").calls()).isEqualTo(1);
        // USD 独立组
        assertThat(groupOf(out, "metrics", "priced").costMicros()).isEqualTo(90L);
    }

    @Test
    void usageOfUnknownRunIsEmpty() {
        reader.run = null;
        assertThat(service.usage(UUID.randomUUID())).isEmpty();
    }

    @Test
    void listRunsWiresUsageAndCostFacetsFromRcaChain_worstCaseRollup() {
        UUID pricedRun = UUID.randomUUID();
        UUID unpricedRun = UUID.randomUUID();
        UUID missingRun = UUID.randomUUID();
        UUID bareRun = UUID.randomUUID();
        reader.runPage = new EvalRunPage(List.of(
                runRow(pricedRun, NOW, "SUCCEEDED"),
                runRow(unpricedRun, NOW, "SUCCEEDED"),
                runRow(missingRun, NOW, "SUCCEEDED"),
                runRow(bareRun, NOW, "SUCCEEDED")), false);
        List<EvalQueryReader.UsageCallRow> usage = new ArrayList<>();
        usage.add(usageRow(pricedRun, "primary", "SUCCESS", 10, 2, 12, 100L,
                "pv-2026-09", "CNY", false));
        usage.add(usageRow(unpricedRun, "primary", "SUCCESS", 8, 2, 10, null,
                "unpriced", null, false));
        usage.add(usageRow(missingRun, "primary", "SUCCESS", null, null, null, null,
                "pv-2026-09", "CNY", true));
        reader.usageCallsForRuns = Map.of(pricedRun,
                List.of(usage.get(0)), unpricedRun, List.of(usage.get(1)),
                missingRun, List.of(usage.get(2)));

        List<EvalQueryService.EvalRunListItem> items =
                service.listRuns(null, null, 50).items();

        assertThat(items).hasSize(4);
        assertThat(facetsOf(items, pricedRun).usageStatus()).isEqualTo("OK");
        assertThat(facetsOf(items, pricedRun).costStatus()).isEqualTo("OK");
        // unpriced：用量在场、价目缺 → 未定价（R4/EU19，绝不显 0）
        assertThat(facetsOf(items, unpricedRun).usageStatus()).isEqualTo("OK");
        assertThat(facetsOf(items, unpricedRun).costStatus()).isEqualTo("UNPRICED");
        // usage_missing 压倒一切：整 run 用量未知，费用随之 UNKNOWN（不猜）
        assertThat(facetsOf(items, missingRun).usageStatus()).isEqualTo("USAGE_MISSING");
        assertThat(facetsOf(items, missingRun).costStatus()).isEqualTo("UNKNOWN");
        // 无 rca 链/无已结算调用 → 双 UNKNOWN 如实
        assertThat(facetsOf(items, bareRun).usageStatus()).isEqualTo("UNKNOWN");
        assertThat(facetsOf(items, bareRun).costStatus()).isEqualTo("UNKNOWN");
    }

    private static EvalQueryService.RunFacets facetsOf(
            List<EvalQueryService.EvalRunListItem> items, UUID runId) {
        return items.stream().filter(i -> i.runId().equals(runId)).findFirst()
                .orElseThrow().facets();
    }

    private static EvalQueryService.UsageGroup groupOf(EvalQueryService.UsageResponse out,
            String roleId, String usageStatus) {
        return groupOf(out, roleId, null, usageStatus);
    }

    private static EvalQueryService.UsageGroup groupOf(EvalQueryService.UsageResponse out,
            String roleId, String state, String usageStatus) {
        return out.groups().stream()
                .filter(g -> g.roleId().equals(roleId) && g.usageStatus().equals(usageStatus)
                        && (state == null || g.state().equals(state)))
                .findFirst().orElseThrow();
    }

    private static EvalQueryReader.UsageCallRow usageRow(String roleId, String state,
            Integer prompt, Integer completion, Integer total, Long costMicros,
            String pricingVersion, String currency, boolean usageMissing) {
        return usageRow(UUID.randomUUID(), roleId, state, prompt, completion, total,
                costMicros, pricingVersion, currency, usageMissing);
    }

    private static EvalQueryReader.UsageCallRow usageRow(UUID evalRunId, String roleId,
            String state, Integer prompt, Integer completion, Integer total,
            Long costMicros, String pricingVersion, String currency, boolean usageMissing) {
        return new EvalQueryReader.UsageCallRow(evalRunId, UUID.randomUUID(),
                UUID.randomUUID(), roleId, state, prompt, completion, total, costMicros,
                pricingVersion, currency, usageMissing);
    }

    // ------------------------------------------------------------------ EV-05 受限日志比较

    private static CaseLogEvidenceRow logRow(UUID caseId, String scenarioId, int round,
                                             UUID rcaRunId, String scope, String payload) {
        return new CaseLogEvidenceRow(caseId, scenarioId, round, rcaRunId, UUID.randomUUID(),
                "logs", scope, null, null, payload, "f".repeat(64), NOW);
    }

    private static String logPayload(boolean truncated, String... lines) {
        StringBuilder result = new StringBuilder();
        for (int i = 0; i < lines.length; i++) {
            if (i > 0) {
                result.append(',');
            }
            result.append("{\"ts\":\"2026-09-09T10:00:0").append(i).append("Z\",")
                    .append("\"service\":\"checkout\",\"line\":\"").append(lines[i])
                    .append("\"}");
        }
        return "{\"status\":\"success\",\"data\":{\"result\":[" + result
                + "],\"truncated\":" + truncated + "}}";
    }

    @Test
    void logCompareDiffsErrorSignaturesPerRound() {
        UUID baseline = UUID.randomUUID();
        UUID candidate = UUID.randomUUID();
        UUID baseCase = UUID.randomUUID();
        UUID candCase = UUID.randomUUID();
        reader.run = runRow(baseline, NOW, "SUCCEEDED");
        // findRun 对两 run 均需命中——FakeReader.findRun 返回同一 run 桩即可（存在性门）
        reader.logEvidenceByRun.put(baseline, List.of(logRow(baseCase, "s1", 1,
                UUID.randomUUID(), "{\"time_range\":\"w1\"}",
                logPayload(false, "2026-09-09 10:00:01 ERROR redis OOM eviction 12345",
                        "INFO warmup done",
                        "2026-09-09 10:00:02 ERROR redis OOM eviction 67890"))));
        reader.logEvidenceByRun.put(candidate, List.of(logRow(candCase, "s1", 1,
                UUID.randomUUID(), "{\"time_range\":\"w2\"}",
                logPayload(true, "2026-09-09 10:00:03 ERROR db deadlock txn 111",
                        "2026-09-09 10:00:04 ERROR redis OOM eviction 99999"))));

        EvalQueryService.EvalLogCompareResponse out =
                service.logCompare(baseline, candidate, "s1").orElseThrow();

        assertThat(out.compareStatus()).isEqualTo("OK");
        assertThat(out.rounds()).hasSize(1);
        EvalQueryService.RoundLogDiff round = out.rounds().get(0);
        assertThat(round.baseline().caseExecutionId()).isEqualTo(baseCase);
        assertThat(round.baseline().totalLines()).isEqualTo(3);
        assertThat(round.baseline().errorLines()).isEqualTo(2);
        assertThat(round.baseline().services()).containsExactly("checkout");
        assertThat(round.baseline().scopeTimeRange()).isEqualTo("w1");
        assertThat(round.baseline().windowStart())
                .isEqualTo(Instant.parse("2026-09-09T10:00:00Z"));
        assertThat(round.candidate().truncated()).isTrue();
        EvalLogCompare.Diff diff = round.diff();
        assertThat(diff).isNotNull();
        // 数字归一化后 "ERROR redis OOM eviction <n>" 两侧同签名：2 vs 1 → decreased
        assertThat(diff.decreased()).hasSize(1);
        assertThat(diff.decreased().get(0).signature()).contains("redis OOM eviction <n>");
        assertThat(diff.decreased().get(0).baselineCount()).isEqualTo(2);
        assertThat(diff.decreased().get(0).candidateCount()).isEqualTo(1);
        assertThat(diff.onlyInCandidate()).hasSize(1);
        assertThat(diff.onlyInCandidate().get(0).signature()).contains("db deadlock txn");
        assertThat(diff.onlyInBaseline()).isEmpty();
    }

    @Test
    void logCompareHonestWhenSideOrEvidenceMissing() {
        UUID baseline = UUID.randomUUID();
        UUID candidate = UUID.randomUUID();
        reader.run = runRow(baseline, NOW, "SUCCEEDED");
        UUID baseCase = UUID.randomUUID();
        reader.logEvidenceByRun.put(baseline, List.of(
                logRow(baseCase, "s1", 1, UUID.randomUUID(), null,
                        logPayload(false, "ERROR redis down 1")),
                logRow(UUID.randomUUID(), "s1", 2, UUID.randomUUID(), null,
                        logPayload(false, "ERROR redis down 2"))));
        // candidate 仅 round 1 有证据 → round 2 diff 为 null（任一侧缺席不可比）
        reader.logEvidenceByRun.put(candidate, List.of(
                logRow(UUID.randomUUID(), "s1", 1, UUID.randomUUID(), null,
                        logPayload(false, "ERROR redis down 3"))));

        EvalQueryService.EvalLogCompareResponse out =
                service.logCompare(baseline, candidate, "s1").orElseThrow();

        assertThat(out.rounds()).hasSize(2);
        assertThat(out.rounds().get(0).diff()).isNotNull();
        assertThat(out.rounds().get(1).diff()).isNull();
        assertThat(out.rounds().get(1).baseline()).isNotNull();
        assertThat(out.rounds().get(1).candidate()).isNull();
    }

    @Test
    void logCompareNoEvidenceBothSidesIsExplicitStatus() {
        UUID baseline = UUID.randomUUID();
        UUID candidate = UUID.randomUUID();
        reader.run = runRow(baseline, NOW, "SUCCEEDED");

        EvalQueryService.EvalLogCompareResponse out =
                service.logCompare(baseline, candidate, "s-no-logs").orElseThrow();

        assertThat(out.compareStatus()).isEqualTo("NO_LOG_EVIDENCE");
        assertThat(out.rounds()).isEmpty();
    }

    @Test
    void logCompareValidatesRestrictedParams() {
        UUID runId = UUID.randomUUID();
        reader.run = runRow(runId, NOW, "SUCCEEDED");
        assertThatThrownBy(() -> service.logCompare(runId, runId, "s1"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.logCompare(UUID.randomUUID(), UUID.randomUUID(), " "))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.logCompare(UUID.randomUUID(), UUID.randomUUID(),
                "x".repeat(EvalQueryService.MAX_SCENARIO_ID_CHARS + 1)))
                .isInstanceOf(IllegalArgumentException.class);
        // 任一 run 未知 → empty（404 面）；FakeReader.run=null 即全未知
        reader.run = null;
        assertThat(service.logCompare(UUID.randomUUID(), UUID.randomUUID(), "s1")).isEmpty();
    }

    @Test
    void logCompareMarksUnparseableEvidenceExplicitly() {
        UUID baseline = UUID.randomUUID();
        UUID candidate = UUID.randomUUID();
        reader.run = runRow(baseline, NOW, "SUCCEEDED");
        UUID baseCase = UUID.randomUUID();
        reader.logEvidenceByRun.put(baseline, List.of(
                logRow(baseCase, "s1", 1, UUID.randomUUID(), null, "{broken")));
        reader.logEvidenceByRun.put(candidate, List.of(
                logRow(UUID.randomUUID(), "s1", 1, UUID.randomUUID(), null,
                        logPayload(false, "ERROR redis down 1"))));

        EvalQueryService.EvalLogCompareResponse out =
                service.logCompare(baseline, candidate, "s1").orElseThrow();

        assertThat(out.rounds().get(0).baseline().unparseableEvidence()).isEqualTo(1);
        assertThat(out.rounds().get(0).baseline().errorLines()).isZero();
        assertThat(out.rounds().get(0).diff()).isNotNull();
    }

    // ------------------------------------------------------------------ P4 安全汇总

    @Test
    void safetySummaryCountsDecoyAdoptedOnlyForRedteamHits() {
        // BA-162 回归钉死：redteam 归属取评分期冻结的 case 行标志（s.redteam），
        // 非红队案例命中根因绝不算"诱饵被采纳"（195 曾因 dataset_version 同名
        // version 扇出 join 把 TUNING 批幻影成红队劫持 72/74）
        UUID runId = UUID.randomUUID();
        reader.run = runRow(runId, NOW, "SUCCEEDED");
        reader.safetyRows = List.of(
                new EvalQueryReader.CaseSafetyRow("S3", 1, "PASS", null, false, true),
                new EvalQueryReader.CaseSafetyRow("S3", 2, "PASS", null, false, true),
                new EvalQueryReader.CaseSafetyRow("RT1", 1, "PASS", null, true, true),
                new EvalQueryReader.CaseSafetyRow("RT1", 2, "PASS", null, true, false),
                new EvalQueryReader.CaseSafetyRow("RT2", 1, "REJECT",
                        "[{\"face\":\"UNAUTHORIZED_TOOL\"}]", true, null));

        EvalQueryService.SafetySummaryResponse out = service.safetySummary(runId).orElseThrow();
        assertThat(out.assessedCases()).isEqualTo(5);
        assertThat(out.rejects()).isEqualTo(1);
        assertThat(out.redteamCases()).isEqualTo(3);
        assertThat(out.redteamDecoyAdopted()).isEqualTo(1);
        assertThat(out.redteamSafetyRejects()).isEqualTo(1);
        assertThat(out.faceCounts()).containsExactly(
                new EvalQueryService.SafetyFaceCount("UNAUTHORIZED_TOOL", 1));
    }

    @Test
    void safetySummaryUnknownRunIsEmpty() {
        assertThat(service.safetySummary(UUID.randomUUID())).isEmpty();
    }

    @Test
    void safetySummarySeparatesFiveVerdictStatesAndAggregatesTally() {
        // ME-T02 五态分列：NOT_ASSESSED 不得计入 passes（未评不冒充通过）；
        // tally 三事实/覆盖分母合计只加有 tally 行
        UUID runId = UUID.randomUUID();
        reader.run = runRow(runId, NOW, "SUCCEEDED");
        reader.safetyRows = List.of(
                new EvalQueryReader.CaseSafetyRow("S1", 1, "PASS", null, false, true,
                        "{\"attempted\":2,\"blocked\":2,\"executedViolations\":0,"
                                + "\"assessedFaces\":5,\"notAssessedFaces\":0}"),
                new EvalQueryReader.CaseSafetyRow("S2", 1, "REJECT",
                        "[{\"face\":\"SCHEMA\"}]", false, false,
                        "{\"attempted\":1,\"blocked\":1,\"executedViolations\":0,"
                                + "\"assessedFaces\":5,\"notAssessedFaces\":0}"),
                new EvalQueryReader.CaseSafetyRow("S3", 1, "NOT_ASSESSED", null, false, null,
                        "{\"attempted\":0,\"blocked\":0,\"executedViolations\":0,"
                                + "\"assessedFaces\":3,\"notAssessedFaces\":2}"),
                new EvalQueryReader.CaseSafetyRow("S4", 1, "NOT_APPLICABLE", null, false, null),
                new EvalQueryReader.CaseSafetyRow("S5", 1, "ERROR", null, false, null));

        EvalQueryService.SafetySummaryResponse out = service.safetySummary(runId).orElseThrow();

        assertThat(out.assessedCases()).isEqualTo(5);
        assertThat(out.passes()).isEqualTo(1);
        assertThat(out.rejects()).isEqualTo(1);
        assertThat(out.notAssessed()).isEqualTo(1);
        assertThat(out.notApplicable()).isEqualTo(1);
        assertThat(out.errors()).isEqualTo(1);
        assertThat(out.attempted()).isEqualTo(3);
        assertThat(out.blocked()).isEqualTo(3);
        assertThat(out.executedViolations()).isZero();
        assertThat(out.assessedFaces()).isEqualTo(13);
        assertThat(out.notAssessedFaces()).isEqualTo(2);
        assertThat(out.tallyCases()).isEqualTo(3);
        assertThat(out.tallyMissingCases()).isEqualTo(2);
        assertThat(out.faceCounts()).containsExactly(
                new EvalQueryService.SafetyFaceCount("SCHEMA", 1));
    }

    @Test
    void safetySummaryLegacyRowsWithoutTallyYieldNullTallyHonestly() {
        // 旧批无 tally 行：各合计 null 如实"旧口径"，不填 0；passes 仍按 PASS 计数
        // （旧二态批 passes = assessed - rejects，值不变）
        UUID runId = UUID.randomUUID();
        reader.run = runRow(runId, NOW, "SUCCEEDED");
        reader.safetyRows = List.of(
                new EvalQueryReader.CaseSafetyRow("S1", 1, "PASS", null, false, true),
                new EvalQueryReader.CaseSafetyRow("S2", 1, "REJECT",
                        "[{\"face\":\"UNAUTHORIZED_TOOL\"}]", false, false));

        EvalQueryService.SafetySummaryResponse out = service.safetySummary(runId).orElseThrow();

        assertThat(out.passes()).isEqualTo(1);
        assertThat(out.attempted()).isNull();
        assertThat(out.blocked()).isNull();
        assertThat(out.executedViolations()).isNull();
        assertThat(out.assessedFaces()).isNull();
        assertThat(out.notAssessedFaces()).isNull();
        assertThat(out.tallyCases()).isZero();
        assertThat(out.tallyMissingCases()).isEqualTo(2);
    }

    // ------------------------------------------------------------------ M-e T11 行为评测汇总

    private static EvalQueryReader.CaseBehaviorRow behaviorRow(UUID caseResultId,
                                                               String scenarioId,
                                                               String graderVersion,
                                                               String coverageJson,
                                                               String checksJson,
                                                               String metricsJson,
                                                               String failureLabelsJson,
                                                               String evidenceRefsJson) {
        return new EvalQueryReader.CaseBehaviorRow(caseResultId, scenarioId, 1,
                graderVersion, "d".repeat(64), coverageJson, checksJson, metricsJson,
                failureLabelsJson, evidenceRefsJson);
    }

    @Test
    void behaviorSummaryAggregatesChecksMetricsLabelsAndCoverage() {
        UUID runId = UUID.randomUUID();
        reader.run = runRow(runId, NOW, "SUCCEEDED");
        UUID case1 = UUID.randomUUID();
        UUID case2 = UUID.randomUUID();
        reader.behaviorRows = List.of(
                behaviorRow(case1, "S1", "behavior-v1",
                        "{\"textCovered\":2,\"textTotal\":3,"
                                + "\"evidenceCovered\":2,\"evidenceTotal\":3}",
                        "[{\"name\":\"citation_attachment\",\"status\":\"PASS\","
                                + "\"reasonCode\":\"ATTACHMENT_FULL\",\"evidenceRefs\":[]},"
                                + "{\"name\":\"evidence_checkpoint_coverage\",\"status\":\"FAIL\","
                                + "\"reasonCode\":\"CHECKPOINT_EVIDENCE_MISSING\",\"evidenceRefs\":[\"e1\"]}]",
                        "[{\"name\":\"citation_attachment_rate\",\"numerator\":1,\"denominator\":1},"
                                + "{\"name\":\"evidence_checkpoint_coverage\",\"numerator\":2,\"denominator\":3}]",
                        "[\"CHECKPOINT_EVIDENCE_MISSING\"]",
                        "[\"e1\"]"),
                behaviorRow(case2, "S2", "behavior-v1",
                        // 无检查点案例：evidence 轨 null = 该轨未评，不进分母不填 0
                        "{\"textCovered\":null,\"textTotal\":null,"
                                + "\"evidenceCovered\":null,\"evidenceTotal\":null}",
                        "[{\"name\":\"citation_attachment\",\"status\":\"NOT_ASSESSED\","
                                + "\"reasonCode\":\"EVIDENCE_READ_UNAVAILABLE\",\"evidenceRefs\":[]},"
                                + "{\"name\":\"evidence_checkpoint_coverage\",\"status\":\"NOT_APPLICABLE\","
                                + "\"reasonCode\":\"NO_CHECKPOINTS\",\"evidenceRefs\":[]}]",
                        "[{\"name\":\"citation_attachment_rate\",\"numerator\":0,\"denominator\":1}]",
                        "[]",
                        "[]"));

        EvalQueryService.BehaviorSummaryResponse out =
                service.behaviorSummary(runId).orElseThrow();

        assertThat(out.assessed()).isEqualTo(2);
        assertThat(out.rows()).isEqualTo(2);
        assertThat(out.graderVersions()).containsExactly("behavior-v1");
        // 覆盖双轨：text 合计含 null 行不加值；evidence 分母只含被评行
        assertThat(out.coverage().textCovered()).isEqualTo(2);
        assertThat(out.coverage().textTotal()).isEqualTo(3);
        assertThat(out.coverage().evidenceCovered()).isEqualTo(2);
        assertThat(out.coverage().evidenceTotal()).isEqualTo(3);
        assertThat(out.coverage().evidenceAssessed()).isEqualTo(1);
        // checks 五态计数（按检查名聚合）
        assertThat(out.checks()).hasSize(2);
        EvalQueryService.BehaviorCheckStat attachment = out.checks().stream()
                .filter(c -> c.name().equals("citation_attachment")).findFirst().orElseThrow();
        assertThat(attachment.statusCounts())
                .containsEntry("PASS", 1L).containsEntry("NOT_ASSESSED", 1L);
        EvalQueryService.BehaviorCheckStat coverageCheck = out.checks().stream()
                .filter(c -> c.name().equals("evidence_checkpoint_coverage"))
                .findFirst().orElseThrow();
        assertThat(coverageCheck.statusCounts())
                .containsEntry("FAIL", 1L).containsEntry("NOT_APPLICABLE", 1L);
        // metrics 分子/分母合计（分母 0 如实不约分）
        assertThat(out.metrics()).containsExactlyInAnyOrder(
                new EvalQueryService.BehaviorMetricStat("citation_attachment_rate", 1, 2),
                new EvalQueryService.BehaviorMetricStat("evidence_checkpoint_coverage", 2, 3));
        assertThat(out.failureLabels()).containsExactly(
                new EvalQueryService.BehaviorLabelCount("CHECKPOINT_EVIDENCE_MISSING", 1));
        assertThat(out.asOf()).isNotNull();
    }

    @Test
    void behaviorSummaryNoRowsAssessedZeroHonestly() {
        UUID runId = UUID.randomUUID();
        reader.run = runRow(runId, NOW, "SUCCEEDED");
        reader.behaviorRows = List.of();

        EvalQueryService.BehaviorSummaryResponse out =
                service.behaviorSummary(runId).orElseThrow();

        assertThat(out.assessed()).isZero();
        assertThat(out.rows()).isZero();
        assertThat(out.coverage().textCovered()).isNull();
        assertThat(out.coverage().evidenceTotal()).isNull();
        assertThat(out.checks()).isEmpty();
        assertThat(out.metrics()).isEmpty();
        assertThat(out.failureLabels()).isEmpty();
    }

    @Test
    void behaviorSummaryUnknownRunIsEmpty() {
        assertThat(service.behaviorSummary(UUID.randomUUID())).isEmpty();
    }

    @Test
    void caseDetailIncludesBehaviorEntriesPerGrader() {
        UUID runId = UUID.randomUUID();
        UUID caseId = UUID.randomUUID();
        reader.caseDetail = detailRow(runId, caseId, null, null, "TIMEOUT_OR_ABSENT", null);
        reader.behaviorByCase.put(caseId, List.of(
                behaviorRow(caseId, "infra/redis-oom", "behavior-v1",
                        "{\"textCovered\":1,\"textTotal\":2,"
                                + "\"evidenceCovered\":1,\"evidenceTotal\":2}",
                        "[{\"name\":\"citation_support\",\"status\":\"NOT_ASSESSED\","
                                + "\"reasonCode\":\"CONTENT_UNAVAILABLE\",\"evidenceRefs\":[\"e1\"]}]",
                        "[{\"name\":\"citation_attachment_rate\",\"numerator\":1,\"denominator\":2}]",
                        "[\"CITATION_DANGLING\"]",
                        "[\"e1\"]")));

        EvalQueryService.EvalCaseDetailResponse out =
                service.caseDetail(runId, caseId).orElseThrow();

        assertThat(out.behavior()).hasSize(1);
        EvalQueryService.CaseBehaviorEntry entry = out.behavior().get(0);
        assertThat(entry.graderVersion()).isEqualTo("behavior-v1");
        assertThat(entry.coverage().textCovered()).isEqualTo(1);
        assertThat(entry.coverage().evidenceTotal()).isEqualTo(2);
        assertThat(entry.checks()).containsExactly(
                new EvalQueryService.CaseBehaviorCheck("citation_support", "NOT_ASSESSED",
                        "CONTENT_UNAVAILABLE", List.of("e1")));
        assertThat(entry.metrics()).containsExactly(
                new EvalQueryService.CaseBehaviorMetric("citation_attachment_rate", 1, 2));
        assertThat(entry.failureLabels()).containsExactly("CITATION_DANGLING");
        assertThat(entry.evidenceRefs()).containsExactly("e1");
    }

    @Test
    void caseDetailWithoutBehaviorRowsYieldsEmptyListHonestly() {
        UUID runId = UUID.randomUUID();
        UUID caseId = UUID.randomUUID();
        reader.caseDetail = detailRow(runId, caseId, null, null, "TIMEOUT_OR_ABSENT", null);

        EvalQueryService.EvalCaseDetailResponse out =
                service.caseDetail(runId, caseId).orElseThrow();

        assertThat(out.behavior()).isEmpty();
    }

    // ------------------------------------------------------------------ ME-T12 死循环评测汇总

    private static EvalQueryReader.CaseLoopRow loopRow(UUID caseResultId,
                                                       String scenarioId,
                                                       String stopReason,
                                                       Integer detectionEventIndex,
                                                       String checksJson,
                                                       String metricsJson,
                                                       String failureLabelsJson) {
        return new EvalQueryReader.CaseLoopRow(caseResultId, scenarioId, 1,
                "loop-behavior-v1", stopReason, detectionEventIndex, null, 0, 2L, null,
                60L, checksJson, metricsJson, failureLabelsJson);
    }

    @Test
    void loopSummaryAggregatesStopReasonsChecksMetricsAndLabels() {
        UUID runId = UUID.randomUUID();
        reader.run = runRow(runId, NOW, "SUCCEEDED");
        UUID case1 = UUID.randomUUID();
        UUID case2 = UUID.randomUUID();
        reader.loopRows = List.of(
                loopRow(case1, "S1", "COMPLETED", null,
                        "[{\"name\":\"loop_detection\",\"status\":\"NOT_APPLICABLE\","
                                + "\"reasonCode\":\"NORMAL_CONTROL\",\"evidenceRefs\":[]},"
                                + "{\"name\":\"loop_false_positive\",\"status\":\"PASS\","
                                + "\"reasonCode\":\"NO_FALSE_POSITIVE\",\"evidenceRefs\":[]}]",
                        "[{\"name\":\"loop_false_positive\",\"numerator\":0,\"denominator\":1},"
                                + "{\"name\":\"normal_task_success\",\"numerator\":1,\"denominator\":1}]",
                        "[]"),
                loopRow(case2, "S2", null, null, // 观测读失败 ERROR 行：终态 null 如实
                        "[{\"name\":\"loop_detection\",\"status\":\"ERROR\","
                                + "\"reasonCode\":\"TRACE_READ_ERROR\",\"evidenceRefs\":[]},"
                                + "{\"name\":\"loop_false_positive\",\"status\":\"ERROR\","
                                + "\"reasonCode\":\"TRACE_READ_ERROR\",\"evidenceRefs\":[]}]",
                        "[]",
                        "[\"TRACE_READ_ERROR\"]"));

        EvalQueryService.LoopSummaryResponse out = service.loopSummary(runId).orElseThrow();

        assertThat(out.assessed()).isEqualTo(2);
        assertThat(out.rows()).isEqualTo(2);
        assertThat(out.graderVersions()).containsExactly("loop-behavior-v1");
        // 终态分布：null 终态（ERROR 行）如实单列 NONE 不丢弃
        assertThat(out.stopReasons())
                .containsEntry("COMPLETED", 1L).containsEntry("NONE", 1L);
        // checks 五态计数（按检查名聚合，TreeMap 定序）
        EvalQueryService.BehaviorCheckStat detection = out.checks().stream()
                .filter(c -> c.name().equals("loop_detection")).findFirst().orElseThrow();
        assertThat(detection.statusCounts())
                .containsEntry("NOT_APPLICABLE", 1L).containsEntry("ERROR", 1L);
        EvalQueryService.BehaviorCheckStat falsePositive = out.checks().stream()
                .filter(c -> c.name().equals("loop_false_positive")).findFirst()
                .orElseThrow();
        assertThat(falsePositive.statusCounts())
                .containsEntry("PASS", 1L).containsEntry("ERROR", 1L);
        // metrics 分子/分母合计（分母 0 如实不约分）
        assertThat(out.metrics()).containsExactlyInAnyOrder(
                new EvalQueryService.BehaviorMetricStat("loop_false_positive", 0, 1),
                new EvalQueryService.BehaviorMetricStat("normal_task_success", 1, 1));
        assertThat(out.failureLabels()).containsExactly(
                new EvalQueryService.BehaviorLabelCount("TRACE_READ_ERROR", 1));
        assertThat(out.asOf()).isNotNull();
    }

    @Test
    void loopSummaryNoRowsAssessedZeroHonestly() {
        UUID runId = UUID.randomUUID();
        reader.run = runRow(runId, NOW, "SUCCEEDED");
        reader.loopRows = List.of();

        EvalQueryService.LoopSummaryResponse out = service.loopSummary(runId).orElseThrow();

        assertThat(out.assessed()).isZero();
        assertThat(out.rows()).isZero();
        assertThat(out.stopReasons()).isEmpty();
        assertThat(out.checks()).isEmpty();
        assertThat(out.metrics()).isEmpty();
        assertThat(out.failureLabels()).isEmpty();
    }

    @Test
    void loopSummaryUnknownRunIsEmpty() {
        assertThat(service.loopSummary(UUID.randomUUID())).isEmpty();
    }

    @Test
    void caseDetailIncludesLoopEntriesPerGrader() {
        UUID runId = UUID.randomUUID();
        UUID caseId = UUID.randomUUID();
        reader.caseDetail = detailRow(runId, caseId, null, null, "TIMEOUT_OR_ABSENT", null);
        reader.loopByCase.put(caseId, List.of(
                loopRow(caseId, "infra/redis-oom", "COMPLETED", null,
                        "[{\"name\":\"normal_task_completion\",\"status\":\"PASS\","
                                + "\"reasonCode\":\"NORMAL_COMPLETED\",\"evidenceRefs\":[]}]",
                        "[{\"name\":\"normal_task_success\",\"numerator\":1,\"denominator\":1}]",
                        "[]")));

        EvalQueryService.EvalCaseDetailResponse out =
                service.caseDetail(runId, caseId).orElseThrow();

        assertThat(out.loop()).hasSize(1);
        EvalQueryService.CaseLoopEntry entry = out.loop().get(0);
        assertThat(entry.graderVersion()).isEqualTo("loop-behavior-v1");
        assertThat(entry.stopReason()).isEqualTo("COMPLETED");
        assertThat(entry.detectionEventIndex()).isNull();
        assertThat(entry.physicalCallsFromOnset()).isEqualTo(2L);
        assertThat(entry.tokensFromOnset()).isNull();
        assertThat(entry.secondsFromOnset()).isEqualTo(60L);
        assertThat(entry.checks()).containsExactly(
                new EvalQueryService.CaseBehaviorCheck("normal_task_completion", "PASS",
                        "NORMAL_COMPLETED", List.of()));
        assertThat(entry.metrics()).containsExactly(
                new EvalQueryService.CaseBehaviorMetric("normal_task_success", 1, 1));
        assertThat(entry.failureLabels()).isEmpty();
    }

    @Test
    void caseDetailWithoutLoopRowsYieldsEmptyListHonestly() {
        UUID runId = UUID.randomUUID();
        UUID caseId = UUID.randomUUID();
        reader.caseDetail = detailRow(runId, caseId, null, null, "TIMEOUT_OR_ABSENT", null);

        EvalQueryService.EvalCaseDetailResponse out =
                service.caseDetail(runId, caseId).orElseThrow();

        assertThat(out.loop()).isEmpty();
    }

    // ------------------------------------------------------------------ ME-T12a 协作评测汇总

    private static EvalQueryReader.CaseCollabRow collabRow(UUID caseResultId,
                                                           String scenarioId,
                                                           Integer edgeCount,
                                                           Integer admittedCount,
                                                           Long tokenCostTotal,
                                                           String checksJson,
                                                           String metricsJson,
                                                           String failureLabelsJson,
                                                           String suspectedJson,
                                                           String supportedJson) {
        return new EvalQueryReader.CaseCollabRow(caseResultId, scenarioId, 1,
                "collaboration-v1", edgeCount, admittedCount, tokenCostTotal,
                checksJson, metricsJson, failureLabelsJson, suspectedJson, supportedJson);
    }

    @Test
    void collabSummaryAggregatesScalarsChecksMetricsLabelsAndAttributions() {
        UUID runId = UUID.randomUUID();
        reader.run = runRow(runId, NOW, "SUCCEEDED");
        UUID case1 = UUID.randomUUID();
        UUID case2 = UUID.randomUUID();
        reader.collabRows = List.of(
                collabRow(case1, "S1", 2, 1, 150L,
                        "[{\"name\":\"delegation_necessity_choice\",\"status\":\"NOT_ASSESSED\","
                                + "\"reasonCode\":\"NEED_LABEL_MISSING\",\"evidenceRefs\":[]},"
                                + "{\"name\":\"cancellation_fence\",\"status\":\"FAIL\","
                                + "\"reasonCode\":\"DISPATCH_AFTER_CANCEL\",\"evidenceRefs\":[\"e1\"]}]",
                        "[{\"name\":\"role_token_cost\",\"numerator\":150,\"denominator\":2}]",
                        "[\"MAST_TASK_DERAIL\"]",
                        "[\"MAST_TASK_DERAIL @ e1（疑似归因，无干预对照）\"]",
                        "[]"),
                collabRow(case2, "S2", null, null, null, // ERROR 行：标量未观测如实
                        "[{\"name\":\"delegation_necessity_choice\",\"status\":\"ERROR\","
                                + "\"reasonCode\":\"TRACE_READ_ERROR\",\"evidenceRefs\":[]}]",
                        "[]",
                        "[\"TRACE_READ_ERROR\"]",
                        "[]",
                        "[]"));

        EvalQueryService.CollabSummaryResponse out =
                service.collabSummary(runId).orElseThrow();

        assertThat(out.assessed()).isEqualTo(2);
        assertThat(out.rows()).isEqualTo(2);
        assertThat(out.graderVersions()).containsExactly("collaboration-v1");
        // 标量合计：null 行（未观测）不加值不填 0
        assertThat(out.edges()).isEqualTo(2);
        assertThat(out.admitted()).isEqualTo(1);
        assertThat(out.tokenCostTotal()).isEqualTo(150);
        // checks 五态计数
        EvalQueryService.BehaviorCheckStat necessity = out.checks().stream()
                .filter(c -> c.name().equals("delegation_necessity_choice")).findFirst()
                .orElseThrow();
        assertThat(necessity.statusCounts())
                .containsEntry("NOT_ASSESSED", 1L).containsEntry("ERROR", 1L);
        EvalQueryService.BehaviorCheckStat fence = out.checks().stream()
                .filter(c -> c.name().equals("cancellation_fence")).findFirst().orElseThrow();
        assertThat(fence.statusCounts()).containsEntry("FAIL", 1L);
        // metrics 分子/分母合计
        assertThat(out.metrics()).containsExactly(
                new EvalQueryService.BehaviorMetricStat("role_token_cost", 150, 2));
        // MAST 标签计数（计数降序、同计数标签升序）
        assertThat(out.failureLabels()).containsExactlyInAnyOrder(
                new EvalQueryService.BehaviorLabelCount("MAST_TASK_DERAIL", 1),
                new EvalQueryService.BehaviorLabelCount("TRACE_READ_ERROR", 1));
        // 归因双轨计数分列不混
        assertThat(out.suspectedAttributions()).isEqualTo(1);
        assertThat(out.supportedAttributions()).isZero();
        assertThat(out.asOf()).isNotNull();
    }

    @Test
    void collabSummaryNoRowsAssessedZeroHonestly() {
        UUID runId = UUID.randomUUID();
        reader.run = runRow(runId, NOW, "SUCCEEDED");
        reader.collabRows = List.of();

        EvalQueryService.CollabSummaryResponse out =
                service.collabSummary(runId).orElseThrow();

        assertThat(out.assessed()).isZero();
        assertThat(out.rows()).isZero();
        assertThat(out.edges()).isNull();
        assertThat(out.admitted()).isNull();
        assertThat(out.tokenCostTotal()).isNull();
        assertThat(out.checks()).isEmpty();
        assertThat(out.metrics()).isEmpty();
        assertThat(out.failureLabels()).isEmpty();
        assertThat(out.suspectedAttributions()).isZero();
        assertThat(out.supportedAttributions()).isZero();
    }

    @Test
    void collabSummaryUnknownRunIsEmpty() {
        assertThat(service.collabSummary(UUID.randomUUID())).isEmpty();
    }

    @Test
    void caseDetailIncludesCollabEntriesPerGrader() {
        UUID runId = UUID.randomUUID();
        UUID caseId = UUID.randomUUID();
        reader.caseDetail = detailRow(runId, caseId, null, null, "TIMEOUT_OR_ABSENT", null);
        reader.collabByCase.put(caseId, List.of(
                collabRow(caseId, "infra/redis-oom", 1, 1, 100L,
                        "[{\"name\":\"handoff_fact_constraint_retention\",\"status\":\"NOT_ASSESSED\","
                                + "\"reasonCode\":\"HANDOFF_CONTENT_UNOBSERVED\",\"evidenceRefs\":[]}]",
                        "[{\"name\":\"role_token_cost\",\"numerator\":100,\"denominator\":1}]",
                        "[]",
                        "[]",
                        "[\"edge:e1 替换错误回执后重放改善（干预前后轨迹俱在），支持该交接边因果归因\"]")));

        EvalQueryService.EvalCaseDetailResponse out =
                service.caseDetail(runId, caseId).orElseThrow();

        assertThat(out.collab()).hasSize(1);
        EvalQueryService.CaseCollabEntry entry = out.collab().get(0);
        assertThat(entry.graderVersion()).isEqualTo("collaboration-v1");
        assertThat(entry.edgeCount()).isEqualTo(1);
        assertThat(entry.admittedCount()).isEqualTo(1);
        assertThat(entry.tokenCostTotal()).isEqualTo(100L);
        assertThat(entry.checks()).containsExactly(
                new EvalQueryService.CaseBehaviorCheck("handoff_fact_constraint_retention",
                        "NOT_ASSESSED", "HANDOFF_CONTENT_UNOBSERVED", List.of()));
        assertThat(entry.metrics()).containsExactly(
                new EvalQueryService.CaseBehaviorMetric("role_token_cost", 100, 1));
        assertThat(entry.failureLabels()).isEmpty();
        assertThat(entry.suspectedAttributions()).isEmpty();
        assertThat(entry.supportedAttributions()).hasSize(1);
    }

    @Test
    void caseDetailWithoutCollabRowsYieldsEmptyListHonestly() {
        UUID runId = UUID.randomUUID();
        UUID caseId = UUID.randomUUID();
        reader.caseDetail = detailRow(runId, caseId, null, null, "TIMEOUT_OR_ABSENT", null);

        EvalQueryService.EvalCaseDetailResponse out =
                service.caseDetail(runId, caseId).orElseThrow();

        assertThat(out.collab()).isEmpty();
    }

    // ------------------------------------------------------------------ ME-T12a 漂移评测汇总

    private static EvalQueryReader.CaseDriftRow driftRow(UUID caseResultId,
                                                         String scenarioId,
                                                         String summaryDigest,
                                                         String consumptionJson,
                                                         String checksJson,
                                                         String metricsJson,
                                                         String failureLabelsJson,
                                                         String deferredJson) {
        return new EvalQueryReader.CaseDriftRow(caseResultId, scenarioId, 1,
                "context-drift-v1", summaryDigest, consumptionJson, checksJson,
                metricsJson, failureLabelsJson, deferredJson);
    }

    @Test
    void driftSummaryAggregatesConsumptionChecksMetricsLabelsAndDeferred() {
        UUID runId = UUID.randomUUID();
        reader.run = runRow(runId, NOW, "SUCCEEDED");
        UUID case1 = UUID.randomUUID();
        UUID case2 = UUID.randomUUID();
        reader.driftRows = List.of(
                driftRow(case1, "S1", "digest-1",
                        "{\"mode\":\"CONSUME_VALIDATED\",\"summaryCommitted\":true,"
                                + "\"consumerInvoked\":true,\"consumed\":true,"
                                + "\"policyDigest\":\"p1\"}",
                        "[{\"name\":\"key_fact_retention\",\"status\":\"FAIL\","
                                + "\"reasonCode\":\"FACT_MISSING\",\"evidenceRefs\":[\"e1\"]}]",
                        "[{\"name\":\"key_fact_retention_rate\",\"numerator\":3,\"denominator\":4}]",
                        "[\"FACT_MISSING\"]",
                        "[\"task_quality_change:x\",\"total_cost_change:y\"]"),
                driftRow(case2, "S2", null,           // 无压缩事件：digest/消费面缺席如实
                        "null",
                        "[{\"name\":\"key_fact_retention\",\"status\":\"NOT_APPLICABLE\","
                                + "\"reasonCode\":\"NO_SUMMARY\",\"evidenceRefs\":[]}]",
                        "[]",
                        "[]",
                        "[\"task_quality_change:x\",\"position_length_buckets:z\"]"));

        EvalQueryService.DriftSummaryResponse out =
                service.driftSummary(runId).orElseThrow();

        assertThat(out.assessed()).isEqualTo(2);
        assertThat(out.rows()).isEqualTo(2);
        assertThat(out.graderVersions()).containsExactly("context-drift-v1");
        assertThat(out.withSummary()).isEqualTo(1);   // 无压缩事件行不计入
        // consumption 四件计数：null 面不计入任一项
        assertThat(out.consumptionObserved()).isEqualTo(1);
        assertThat(out.summaryCommitted()).isEqualTo(1);
        assertThat(out.consumerInvoked()).isEqualTo(1);
        assertThat(out.consumed()).isEqualTo(1);
        // checks 五态计数
        EvalQueryService.BehaviorCheckStat retention = out.checks().stream()
                .filter(c -> c.name().equals("key_fact_retention")).findFirst().orElseThrow();
        assertThat(retention.statusCounts())
                .containsEntry("FAIL", 1L).containsEntry("NOT_APPLICABLE", 1L);
        assertThat(out.metrics()).containsExactly(
                new EvalQueryService.BehaviorMetricStat("key_fact_retention_rate", 3, 4));
        assertThat(out.failureLabels()).containsExactly(
                new EvalQueryService.BehaviorLabelCount("FACT_MISSING", 1));
        // deferred 并集（跨行去重）
        assertThat(out.deferred()).containsExactlyInAnyOrder(
                "task_quality_change:x", "total_cost_change:y", "position_length_buckets:z");
        assertThat(out.asOf()).isNotNull();
    }

    @Test
    void driftSummaryNoRowsAssessedZeroHonestly() {
        UUID runId = UUID.randomUUID();
        reader.run = runRow(runId, NOW, "SUCCEEDED");
        reader.driftRows = List.of();

        EvalQueryService.DriftSummaryResponse out =
                service.driftSummary(runId).orElseThrow();

        assertThat(out.assessed()).isZero();
        assertThat(out.rows()).isZero();
        assertThat(out.withSummary()).isZero();
        assertThat(out.consumptionObserved()).isZero();
        assertThat(out.checks()).isEmpty();
        assertThat(out.metrics()).isEmpty();
        assertThat(out.failureLabels()).isEmpty();
        assertThat(out.deferred()).isEmpty();
    }

    @Test
    void driftSummaryUnknownRunIsEmpty() {
        assertThat(service.driftSummary(UUID.randomUUID())).isEmpty();
    }

    @Test
    void caseDetailIncludesDriftEntriesPerGrader() {
        UUID runId = UUID.randomUUID();
        UUID caseId = UUID.randomUUID();
        reader.caseDetail = detailRow(runId, caseId, null, null, "TIMEOUT_OR_ABSENT", null);
        reader.driftByCase.put(caseId, List.of(
                driftRow(caseId, "infra/redis-oom", "digest-9",
                        "{\"mode\":\"SHADOW_GENERATE\",\"summaryCommitted\":true,"
                                + "\"consumerInvoked\":false,\"consumed\":null,"
                                + "\"policyDigest\":null}",
                        "[{\"name\":\"fact_distortion\",\"status\":\"NOT_APPLICABLE\","
                                + "\"reasonCode\":\"NO_REQUIRED_FACTS\",\"evidenceRefs\":[]}]",
                        "[]",
                        "[]",
                        "[\"total_cost_change:y\"]")));

        EvalQueryService.EvalCaseDetailResponse out =
                service.caseDetail(runId, caseId).orElseThrow();

        assertThat(out.drift()).hasSize(1);
        EvalQueryService.CaseDriftEntry entry = out.drift().get(0);
        assertThat(entry.graderVersion()).isEqualTo("context-drift-v1");
        assertThat(entry.summaryDigest()).isEqualTo("digest-9");
        assertThat(entry.consumption().mode()).isEqualTo("SHADOW_GENERATE");
        assertThat(entry.consumption().summaryCommitted()).isTrue();
        assertThat(entry.consumption().consumerInvoked()).isFalse();
        assertThat(entry.consumption().consumed()).as("未观测 null 透传不猜").isNull();
        assertThat(entry.checks()).containsExactly(
                new EvalQueryService.CaseBehaviorCheck("fact_distortion",
                        "NOT_APPLICABLE", "NO_REQUIRED_FACTS", List.of()));
        assertThat(entry.metrics()).isEmpty();
        assertThat(entry.failureLabels()).isEmpty();
        assertThat(entry.deferred()).containsExactly("total_cost_change:y");
    }

    @Test
    void caseDetailWithoutDriftRowsYieldsEmptyListHonestly() {
        UUID runId = UUID.randomUUID();
        UUID caseId = UUID.randomUUID();
        reader.caseDetail = detailRow(runId, caseId, null, null, "TIMEOUT_OR_ABSENT", null);

        EvalQueryService.EvalCaseDetailResponse out =
                service.caseDetail(runId, caseId).orElseThrow();

        assertThat(out.drift()).isEmpty();
    }

    // ------------------------------------------------------------------ M-d T5 六要素汇总

    @Test
    void sixPartsSummaryAggregatesRateAndLevelCounts() {
        UUID runId = UUID.randomUUID();
        reader.run = runRow(runId, NOW, "SUCCEEDED");
        reader.sixPartsRows = List.of(
                new EvalQueryReader.SixPartsRow("S3", 1, true, "HIGH"),
                new EvalQueryReader.SixPartsRow("S3", 2, true, "MEDIUM"),
                new EvalQueryReader.SixPartsRow("S4", 1, false, null));

        EvalQueryService.SixPartsSummaryResponse out =
                service.sixPartsSummary(runId).orElseThrow();

        assertThat(out.assessed()).isEqualTo(3);
        assertThat(out.complete()).isEqualTo(2);
        assertThat(out.rate()).isEqualTo(2.0 / 3);
        assertThat(out.high()).isEqualTo(1);
        assertThat(out.medium()).isEqualTo(1);
        assertThat(out.low()).isZero();
        assertThat(out.asOf()).isNotNull();
    }

    @Test
    void sixPartsSummaryWithNoRowsYieldsNullRateHonestly() {
        UUID runId = UUID.randomUUID();
        reader.run = runRow(runId, NOW, "SUCCEEDED");
        reader.sixPartsRows = List.of();

        EvalQueryService.SixPartsSummaryResponse out =
                service.sixPartsSummary(runId).orElseThrow();

        assertThat(out.assessed()).isZero();
        assertThat(out.complete()).isZero();
        assertThat(out.rate()).isNull();
    }

    @Test
    void sixPartsSummaryUnknownRunIsEmpty() {
        assertThat(service.sixPartsSummary(UUID.randomUUID())).isEmpty();
    }

    // ------------------------------------------------------------------ M-d T3 过程面汇总

    @Test
    void processMetricsAssembleRatesFromRawCounters() {
        UUID runId = UUID.randomUUID();
        reader.run = runRow(runId, NOW, "SUCCEEDED");
        reader.processMetrics = new EvalQueryReader.ProcessMetricsRow(
                10, 2,                          // settled=10, 结构拒 2 → 通过率 0.8
                50, 40,                         // total=50, unique=40 → 重复率 0.2
                20, 15,                         // checkpoints → 覆盖 0.75
                8, 6,                           // grounded assessed=8, GROUNDED=6 → 0.75
                1000L, 4000L,                   // P50/P95
                60, 3);                         // 工具账本 total=60, FAILED=3 → 0.05

        EvalQueryService.ProcessMetricsResponse out =
                service.processMetricsSummary(runId).orElseThrow();

        assertThat(out.settled()).isEqualTo(10);
        assertThat(out.structurePassRate()).isCloseTo(0.8, within(1e-9));
        assertThat(out.repeatedActionRate()).isCloseTo(0.2, within(1e-9));
        assertThat(out.toolErrorRate()).isEqualTo(3.0 / 60);
        assertThat(out.checkpointCoverageRate()).isEqualTo(0.75);
        assertThat(out.conclusionGroundedRate()).isEqualTo(0.75);
        assertThat(out.p50LatencyMs()).isEqualTo(1000L);
        assertThat(out.p95LatencyMs()).isEqualTo(4000L);
        assertThat(out.asOf()).isNotNull();
    }

    @Test
    void processMetricsZeroDenominatorsYieldNullRatesHonestly() {
        UUID runId = UUID.randomUUID();
        reader.run = runRow(runId, NOW, "RUNNING");
        reader.processMetrics = new EvalQueryReader.ProcessMetricsRow(
                0, 0, 0, 0, 0, 0, 0, 0, null, null, 0, 0);

        EvalQueryService.ProcessMetricsResponse out =
                service.processMetricsSummary(runId).orElseThrow();

        assertThat(out.settled()).isZero();
        assertThat(out.structurePassRate()).isNull();
        assertThat(out.repeatedActionRate()).isNull();
        assertThat(out.toolErrorRate()).isNull();
        assertThat(out.checkpointCoverageRate()).isNull();
        assertThat(out.conclusionGroundedRate()).isNull();
        assertThat(out.p50LatencyMs()).isNull();
    }

    @Test
    void processMetricsUnknownRunIsEmpty() {
        assertThat(service.processMetricsSummary(UUID.randomUUID())).isEmpty();
    }

    // ------------------------------------------------------------------ M-d T8 审批链观测

    @Test
    void approvalChainSummaryCarriesFiveLedgerCounts() {
        UUID runId = UUID.randomUUID();
        reader.run = runRow(runId, NOW, "SUCCEEDED");
        reader.approvalChain = new EvalQueryReader.ApprovalChainRow(2, 1, 1, 1, 1);

        EvalQueryService.ApprovalChainResponse out =
                service.approvalChainSummary(runId).orElseThrow();

        assertThat(out.intents()).isEqualTo(2);
        assertThat(out.requests()).isEqualTo(1);
        assertThat(out.decisions()).isEqualTo(1);
        assertThat(out.grants()).isEqualTo(1);
        assertThat(out.authorizations()).isEqualTo(1);
        assertThat(out.asOf()).isNotNull();
    }

    @Test
    void approvalChainSummaryZeroActivityIsHonest() {
        UUID runId = UUID.randomUUID();
        reader.run = runRow(runId, NOW, "RUNNING");

        EvalQueryService.ApprovalChainResponse out =
                service.approvalChainSummary(runId).orElseThrow();

        assertThat(out.intents()).isZero();
        assertThat(out.requests()).isZero();
        assertThat(out.authorizations()).isZero();
    }

    @Test
    void approvalChainSummaryUnknownRunIsEmpty() {
        assertThat(service.approvalChainSummary(UUID.randomUUID())).isEmpty();
    }

    private static final class FakeReader implements EvalQueryReader {
        EvalRunPage runPage = new EvalRunPage(List.of(), false);
        EvalRunRow run;
        EvalCasePage casePage = new EvalCasePage(List.of(), false);
        List<EvalQueryReader.CaseSafetyRow> safetyRows = List.of();
        List<EvalQueryReader.CaseBehaviorRow> behaviorRows = List.of();
        Map<UUID, List<EvalQueryReader.CaseBehaviorRow>> behaviorByCase = new LinkedHashMap<>();
        List<EvalQueryReader.CaseLoopRow> loopRows = List.of();
        Map<UUID, List<EvalQueryReader.CaseLoopRow>> loopByCase = new LinkedHashMap<>();
        List<EvalQueryReader.CaseCollabRow> collabRows = List.of();
        Map<UUID, List<EvalQueryReader.CaseCollabRow>> collabByCase = new LinkedHashMap<>();
        List<EvalQueryReader.CaseDriftRow> driftRows = List.of();
        Map<UUID, List<EvalQueryReader.CaseDriftRow>> driftByCase = new LinkedHashMap<>();
        List<EvalQueryReader.SixPartsRow> sixPartsRows = List.of();
        EvalQueryReader.ProcessMetricsRow processMetrics =
                new EvalQueryReader.ProcessMetricsRow(0, 0, 0, 0, 0, 0, 0, 0,
                        null, null, 0, 0);
        EvalQueryReader.ApprovalChainRow approvalChain =
                new EvalQueryReader.ApprovalChainRow(0, 0, 0, 0, 0);
        EvalQueryReader.LiveMetricRow liveMetrics;
        List<DatasetRow> datasets = List.of();
        List<PartitionCountRow> partitionCounts = List.of();
        List<EvalQueryReader.DatasetCaseRow> datasetCaseRows = List.of();
        String lastState;
        KeysetCursor lastRunCursor;
        int lastRunLimit;
        String lastVerdict;
        String lastAfterScenario;
        Integer lastAfterRound;
        EvalCaseDetailRow caseDetail;
        CaseIdentityRow caseIdentity;
        /** BA-169：显式设置的匹配列表（歧义场景用）；null = 由 findCaseIdentity 推导 */
        List<CaseIdentityRow> caseIdentityMatches;
        List<CaseEvidenceRefRow> evidenceRefRows = List.of();
        List<EvidenceMetaRow> evidenceMeta = List.of();
        Map<UUID, List<CaseLogEvidenceRow>> logEvidenceByRun = new LinkedHashMap<>();
        UUID lastDetailRunId;
        UUID lastDetailCaseId;
        String lastIdentityDataset;
        String lastIdentityScenario;
        UUID lastEvidenceRunId;
        UUID lastMetaRcaRunId;
        List<UUID> lastMetaIds;
        UUID lastLogRunId;
        String lastLogScenario;

        @Override
        public EvalRunPage listRuns(String state, KeysetCursor cursor, int limit) {
            this.lastState = state;
            this.lastRunCursor = cursor;
            this.lastRunLimit = limit;
            return runPage;
        }

        @Override
        public Optional<EvalRunRow> findRun(UUID runId) {
            return Optional.ofNullable(run);
        }

        @Override
        public List<EvalQueryReader.SixPartsRow> listSixParts(UUID evalRunId) {
            return sixPartsRows;
        }

        @Override
        public EvalQueryReader.ProcessMetricsRow processMetrics(UUID runId) {
            return processMetrics;
        }

        @Override
        public EvalQueryReader.ApprovalChainRow approvalChain(UUID runId) {
            return approvalChain;
        }

        @Override
        public EvalCasePage listCases(UUID runId, String verdict, String afterScenario,
                                      Integer afterRound, int limit) {
            this.lastVerdict = verdict;
            this.lastAfterScenario = afterScenario;
            this.lastAfterRound = afterRound;
            return casePage;
        }

        @Override
        public List<DatasetRow> listDatasets() {
            return datasets;
        }

        @Override
        public List<PartitionCountRow> listPartitionCounts() {
            return partitionCounts;
        }

        @Override
        public List<EvalQueryReader.DatasetCaseRow> listDatasetCases(String name,
                                                                     String version) {
            return datasetCaseRows;
        }

        @Override
        public Optional<EvalCaseDetailRow> findCaseDetail(UUID runId, UUID caseExecutionId) {
            this.lastDetailRunId = runId;
            this.lastDetailCaseId = caseExecutionId;
            return Optional.ofNullable(caseDetail);
        }

        @Override
        public Optional<CaseIdentityRow> findCaseIdentity(String datasetVersion,
                                                          String scenarioId) {
            this.lastIdentityDataset = datasetVersion;
            this.lastIdentityScenario = scenarioId;
            return Optional.ofNullable(caseIdentity);
        }

        @Override
        public List<CaseIdentityRow> findCaseIdentityMatches(String datasetVersion,
                                                             String scenarioId) {
            this.lastIdentityDataset = datasetVersion;
            this.lastIdentityScenario = scenarioId;
            if (caseIdentityMatches != null) {
                return caseIdentityMatches;
            }
            return Optional.ofNullable(caseIdentity).map(List::of).orElse(List.of());
        }

        @Override
        public List<CaseEvidenceRefRow> listCaseEvidenceRefs(UUID runId) {
            this.lastEvidenceRunId = runId;
            return evidenceRefRows;
        }

        @Override
        public List<EvidenceMetaRow> listEvidenceMeta(UUID rcaRunId, List<UUID> evidenceIds) {
            this.lastMetaRcaRunId = rcaRunId;
            this.lastMetaIds = evidenceIds;
            return evidenceMeta;
        }

        @Override
        public List<CaseLogEvidenceRow> listCaseLogEvidence(UUID runId, String scenarioId) {
            this.lastLogRunId = runId;
            this.lastLogScenario = scenarioId;
            return logEvidenceByRun.getOrDefault(runId, List.of());
        }

        @Override
        public Optional<CompareRunMeta> findCompareMeta(UUID runId) {
            throw new UnsupportedOperationException();
        }

        @Override
        public List<CompareCaseRow> listCasesForCompare(UUID runId, int limit) {
            throw new UnsupportedOperationException();
        }

        java.util.Map<String, List<String>> planCaseKeysByDataset = java.util.Map.of();

        @Override
        public List<String> listPlanCaseKeys(String datasetVersion) {
            return planCaseKeysByDataset.getOrDefault(datasetVersion, List.of());
        }

        @Override
        public EvalPhaseEventPage listPhaseEvents(UUID runId, KeysetCursor cursor,
                                                  int limit) {
            throw new UnsupportedOperationException();
        }

        List<EvalQueryReader.UsageCallRow> usageCalls = List.of();

        @Override
        public List<EvalQueryReader.UsageCallRow> listUsageCalls(UUID evalRunId) {
            return usageCalls;
        }

        java.util.Map<UUID, List<EvalQueryReader.UsageCallRow>> usageCallsForRuns = java.util.Map.of();

        @Override
        public List<EvalQueryReader.UsageCallRow> listUsageCallsForRuns(
                Iterable<UUID> evalRunIds) {
            List<EvalQueryReader.UsageCallRow> out = new ArrayList<>();
            for (UUID id : evalRunIds) {
                out.addAll(usageCallsForRuns.getOrDefault(id, List.of()));
            }
            return out;
        }

        java.util.Map<UUID, List<EvalQueryReader.ScenarioRoundStatRow>> scenarioRoundStats =
                java.util.Map.of();

        @Override
        public List<EvalQueryReader.ScenarioRoundStatRow> listScenarioRoundStatsForRuns(
                Iterable<UUID> evalRunIds) {
            List<EvalQueryReader.ScenarioRoundStatRow> out = new ArrayList<>();
            for (UUID id : evalRunIds) {
                out.addAll(scenarioRoundStats.getOrDefault(id, List.of()));
            }
            return out;
        }

        java.util.Map<UUID, List<EvalQueryReader.ModelCallFailureRow>> modelCallFailures =
                java.util.Map.of();

        @Override
        public List<EvalQueryReader.ModelCallFailureRow> listModelCallFailuresForRuns(
                Iterable<UUID> evalRunIds) {
            List<EvalQueryReader.ModelCallFailureRow> out = new ArrayList<>();
            for (UUID id : evalRunIds) {
                out.addAll(modelCallFailures.getOrDefault(id, List.of()));
            }
            return out;
        }

        java.util.Map<UUID, List<EvalQueryReader.SixPartsStatRow>> sixPartsStats =
                java.util.Map.of();

        @Override
        public List<EvalQueryReader.SixPartsStatRow> listSixPartsStatsForRuns(
                Iterable<UUID> evalRunIds) {
            List<EvalQueryReader.SixPartsStatRow> out = new ArrayList<>();
            for (UUID id : evalRunIds) {
                out.addAll(sixPartsStats.getOrDefault(id, List.of()));
            }
            return out;
        }

        @Override
        public EvalQueryReader.LiveMetricRow liveMetrics(UUID runId) {
            return liveMetrics != null ? liveMetrics : EvalQueryReader.super.liveMetrics(runId);
        }

        @Override
        public List<EvalQueryReader.CaseSafetyRow> listCaseSafety(UUID evalRunId) {
            return safetyRows;
        }

        @Override
        public List<EvalQueryReader.CaseBehaviorRow> listCaseBehavior(UUID evalRunId) {
            return behaviorRows;
        }

        @Override
        public List<EvalQueryReader.CaseBehaviorRow> listCaseBehaviorForCase(
                UUID caseResultId) {
            return behaviorByCase.getOrDefault(caseResultId, List.of());
        }

        @Override
        public List<EvalQueryReader.CaseLoopRow> listCaseLoop(UUID evalRunId) {
            return loopRows;
        }

        @Override
        public List<EvalQueryReader.CaseLoopRow> listCaseLoopForCase(UUID caseResultId) {
            return loopByCase.getOrDefault(caseResultId, List.of());
        }

        @Override
        public List<EvalQueryReader.CaseCollabRow> listCaseCollab(UUID evalRunId) {
            return collabRows;
        }

        @Override
        public List<EvalQueryReader.CaseCollabRow> listCaseCollabForCase(
                UUID caseResultId) {
            return collabByCase.getOrDefault(caseResultId, List.of());
        }

        @Override
        public List<EvalQueryReader.CaseDriftRow> listCaseDrift(UUID evalRunId) {
            return driftRows;
        }

        @Override
        public List<EvalQueryReader.CaseDriftRow> listCaseDriftForCase(
                UUID caseResultId) {
            return driftByCase.getOrDefault(caseResultId, List.of());
        }

        @Override
        public List<EvalQueryReader.CaseJudgeRow> listJudge(UUID evalRunId) {
            return List.of();
        }

        java.util.Map<UUID, EvalQueryReader.CaseTokenRow> tokenTotalsByCase =
                java.util.Map.of();

        @Override
        public List<EvalQueryReader.CaseTokenRow> listCaseTokenTotals(UUID evalRunId,
                List<UUID> caseExecutionIds) {
            List<EvalQueryReader.CaseTokenRow> out = new ArrayList<>();
            for (UUID id : caseExecutionIds) {
                EvalQueryReader.CaseTokenRow row = tokenTotalsByCase.get(id);
                if (row != null) {
                    out.add(row);
                }
            }
            return out;
        }

        @Override
        public Optional<UUID> findAutoCompareBaseline(UUID candidateRunId) {
            throw new UnsupportedOperationException();
        }
    }
}
