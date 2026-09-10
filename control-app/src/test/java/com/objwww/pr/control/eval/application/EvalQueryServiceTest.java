package com.objwww.pr.control.eval.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.objwww.pr.control.eval.domain.repository.EvalQueryReader;
import com.objwww.pr.control.eval.domain.repository.EvalQueryReader.CaseEvidenceRefRow;
import com.objwww.pr.control.eval.domain.repository.EvalQueryReader.CaseIdentityRow;
import com.objwww.pr.control.eval.domain.repository.EvalQueryReader.CaseLogEvidenceRow;
import com.objwww.pr.control.eval.domain.repository.EvalQueryReader.DatasetRow;
import com.objwww.pr.control.eval.domain.repository.EvalQueryReader.EvalCaseDetailRow;
import com.objwww.pr.control.eval.domain.repository.EvalQueryReader.EvalCasePage;
import com.objwww.pr.control.eval.domain.repository.EvalQueryReader.EvalCaseRow;
import com.objwww.pr.control.eval.domain.repository.EvalQueryReader.EvalRunPage;
import com.objwww.pr.control.eval.domain.repository.EvalQueryReader.EvalRunRow;
import com.objwww.pr.control.eval.domain.repository.EvalQueryReader.EvidenceMetaRow;
import com.objwww.pr.control.eval.domain.repository.EvalQueryReader.KeysetCursor;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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
                row.launchPlanJson());
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
                recoveryState, terminalReason, cancelRequestedAt, launchPlanJson);
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
                null, null, null, null);
    }

    private static EvalRunRow runRowWithCounts(UUID id, Instant startedAt, int total,
                                               int decidable, int hit, int unresolved) {
        return new EvalRunRow(id, "rca100-v1", "a".repeat(64), "gpt-5", "p3",
                "b".repeat(64), "SUCCEEDED", startedAt, startedAt.plusSeconds(600),
                0.0, 0.0, 0.0, 1.0, 0, 0, 0,
                null, null,
                total, decidable, hit, unresolved, total, startedAt.plusSeconds(590),
                null, null,
                null, null, null, null);
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
        EvalCaseDetailRow caseDetail;
        CaseIdentityRow caseIdentity;
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
    }
}
