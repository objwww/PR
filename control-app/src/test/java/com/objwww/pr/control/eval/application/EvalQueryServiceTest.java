package com.objwww.pr.control.eval.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.objwww.pr.control.eval.domain.repository.EvalQueryReader;
import com.objwww.pr.control.eval.domain.repository.EvalQueryReader.DatasetRow;
import com.objwww.pr.control.eval.domain.repository.EvalQueryReader.EvalCasePage;
import com.objwww.pr.control.eval.domain.repository.EvalQueryReader.EvalCaseRow;
import com.objwww.pr.control.eval.domain.repository.EvalQueryReader.EvalRunPage;
import com.objwww.pr.control.eval.domain.repository.EvalQueryReader.EvalRunRow;
import com.objwww.pr.control.eval.domain.repository.EvalQueryReader.KeysetCursor;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * EvalQueryService 单测（UI-5/EV-03；IncidentQueryServiceTest 同模式——假端口纯函数段）：
 * runs/cases 游标编解码与校验、state/verdict 枚举校验、detail 装配、比率三件套
 * （OK/NOT_APPLICABLE/UNKNOWN）与状态分面、caseExecutionId 身份直透、
 * root cause/failureSample jsonb 摘要化、诚实 null 穿透。
 */
class EvalQueryServiceTest {

    private static final Instant NOW = Instant.parse("2026-09-09T10:30:00Z");

    private final FakeReader reader = new FakeReader();
    private final EvalQueryService service = new EvalQueryService(reader, new ObjectMapper());

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
        assertThat(out.facets().recoveryState()).isEqualTo("UNKNOWN");
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
                "AWAITING_RCA", NOW.plusSeconds(30));
        reader.runPage = new EvalRunPage(List.of(withPhase), false);

        EvalQueryService.EvalRunListItem out = service.listRuns(null, null, 50).items().get(0);

        assertThat(out.facets().phase()).isEqualTo("AWAITING_RCA");
        assertThat(out.facets().stageEnteredAt()).isEqualTo(NOW.plusSeconds(30));
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
    void detailOfUnknownRunIsEmpty() {
        reader.run = null;
        assertThat(service.detail(UUID.randomUUID())).isEmpty();
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
        reader.datasets = List.of(new DatasetRow("v1.1", "rca100", 30,
                List.of("redis-oom", "db-lock"), NOW));
        EvalQueryService.DatasetListResponse out = service.datasets();
        assertThat(out.items()).hasSize(1);
        assertThat(out.items().get(0).caseCount()).isEqualTo(30);
        assertThat(out.items().get(0).families()).containsExactly("redis-oom", "db-lock");
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
                null, null);
    }

    private static EvalRunRow runRowWithCounts(UUID id, Instant startedAt, int total,
                                               int decidable, int hit, int unresolved) {
        return new EvalRunRow(id, "rca100-v1", "a".repeat(64), "gpt-5", "p3",
                "b".repeat(64), "SUCCEEDED", startedAt, startedAt.plusSeconds(600),
                0.0, 0.0, 0.0, 1.0, 0, 0, 0,
                null, null,
                total, decidable, hit, unresolved, total, startedAt.plusSeconds(590),
                null, null);
    }

    private static final class FakeReader implements EvalQueryReader {
        EvalRunPage runPage = new EvalRunPage(List.of(), false);
        EvalRunRow run;
        EvalCasePage casePage = new EvalCasePage(List.of(), false);
        List<DatasetRow> datasets = List.of();
        String lastState;
        KeysetCursor lastRunCursor;
        int lastRunLimit;
        String lastVerdict;
        String lastAfterScenario;
        Integer lastAfterRound;

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
    }
}
