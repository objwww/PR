package com.objwww.pr.control.eval.interfaces;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.objwww.pr.control.eval.application.EvalQueryService;
import com.objwww.pr.control.eval.application.EvalRubricRegistry;
import com.objwww.pr.control.eval.domain.repository.EvalQueryReader;
import com.objwww.pr.control.eval.domain.repository.EvalQueryReader.EvalPhaseEventPage;
import com.objwww.pr.control.eval.domain.repository.EvalQueryReader.EvalPhaseEventRow;
import com.objwww.pr.control.eval.domain.repository.EvalQueryReader.EvalRunRow;
import com.objwww.pr.control.eval.domain.repository.EvalQueryReader.KeysetCursor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import com.fasterxml.jackson.databind.SerializationFeature;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * EvalQueryController /runs/{runId}/events 契约（A3 §5.3；EventQueryControllerTest/
 * MetricsQueryControllerTest 同式——standalone MockMvc + 内存 EvalQueryReader 桩）：
 * eval_phase_event 键集游标 (entered_at, id) 读面——正常页字段映射、末页 nextCursor
 * null、游标续页解析、limit 钳 [1,200]、未知 run 404、畸形游标/非法 runId 400。
 * 端口级排序与键集边界的真 SQL 语义留 195 窗 IT（本地无 docker，NOT_RUN）。
 */
class EvalQueryControllerTest {

    private static final Instant T0 = Instant.parse("2026-09-09T10:00:00Z");

    private final StubReader reader = new StubReader();
    private MockMvc mvc;
    private UUID runId;

    @BeforeEach
    void setUp() {
        runId = UUID.randomUUID();
        reader.run = runRow(runId);
        EvalRubricRegistry rubrics = EvalRubricRegistry.load("""
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
        EvalQueryService service = new EvalQueryService(reader, new ObjectMapper(), rubrics);
        // OP-02 质量面：本测试不触（qualityOf 由 QualitySummaryServiceTest 覆盖），
        // 注入最小桩——run 不存在路径仅校验 404 映射不误伤既有端点
        com.objwww.pr.control.eval.domain.repository.EvalRunRepository emptyRuns =
                new com.objwww.pr.control.eval.domain.repository.EvalRunRepository() {
                    @Override
                    public void insertRunning(
                            com.objwww.pr.control.eval.domain.EvalRun running) {
                        throw new UnsupportedOperationException();
                    }

                    @Override
                    public boolean finalizeOnce(
                            com.objwww.pr.control.eval.domain.EvalRun terminal) {
                        throw new UnsupportedOperationException();
                    }

                    @Override
                    public boolean applyLaunchIdentity(UUID runId, String displayName,
                            String mode, String launchPlanJson) {
                        throw new UnsupportedOperationException();
                    }

                    @Override
                    public boolean updateRecoveryState(UUID runId, String recoveryState) {
                        throw new UnsupportedOperationException();
                    }

                    @Override
                    public boolean insertCaseResult(
                            com.objwww.pr.control.eval.domain.EvalCaseResult result) {
                        throw new UnsupportedOperationException();
                    }

                    @Override
                    public Optional<com.objwww.pr.control.eval.domain.EvalRun> findById(
                            UUID id) {
                        return Optional.empty();
                    }

                    @Override
                    public List<com.objwww.pr.control.eval.domain.EvalCaseResult>
                            findCasesByRunId(UUID id) {
                        return List.of();
                    }
                };
        // standalone 装配不挂 Boot 自动配置，jsr310 需显式注册并关时间戳——与生产序列化
        // （ISO 字符串）对齐，沿 MetricsQueryControllerTest 惯例
        mvc = MockMvcBuilders.standaloneSetup(new EvalQueryController(service,
                new com.objwww.pr.control.eval.application.QualitySummaryService(emptyRuns),
                commandService(), com.objwww.pr.control.eval.application.EvalLaunchGate
                        .closed(java.util.Set.of("L"), "eval-ds-1", 1, 10)))
                .setMessageConverters(new MappingJackson2HttpMessageConverter(
                        Jackson2ObjectMapperBuilder.json()
                                .featuresToDisable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
                                .build()))
                .build();
    }

    /** PAGE-10/03 控制面依赖：内存命令账本（acceptCommand 预置受理行）+ 闭面闸门 */
    private final java.util.List<com.objwww.pr.control.eval.domain.model.EvalRunCommand>
            launched = new java.util.ArrayList<>();
    private com.objwww.pr.control.eval.domain.model.EvalRunCommand acceptedCommand;

    private com.objwww.pr.control.eval.application.EvalCommandService commandService() {
        com.objwww.pr.control.eval.domain.repository.EvalRunCommandRepository repo =
                new com.objwww.pr.control.eval.domain.repository.EvalRunCommandRepository() {
                    @Override
                    public void insert(com.objwww.pr.control.eval.domain.model.EvalRunCommand c) {
                        launched.add(c);
                    }

                    @Override
                    public Optional<com.objwww.pr.control.eval.domain.model.EvalRunCommand> findLatestLaunch(
                            UUID runId) {
                        return acceptedCommand != null
                                && acceptedCommand.evalRunId().equals(runId)
                                ? Optional.of(acceptedCommand) : Optional.empty();
                    }

                    @Override
                    public Optional<com.objwww.pr.control.eval.domain.model.EvalRunCommand> findByKey(
                            com.objwww.pr.control.eval.domain.model.EvalRunCommand.Type t,
                            String k) {
                        return Optional.empty();
                    }

                    @Override
                    public boolean cancelAccepted(UUID runId) {
                        return false;
                    }

                    @Override
                    public Optional<Instant> cancelRequestedAt(UUID runId) {
                        return Optional.empty();
                    }

                    @Override
                    public Optional<com.objwww.pr.control.eval.domain.model.EvalRunCommand> claimNextLaunch(
                            String w, Instant at) {
                        return Optional.empty();
                    }

                    @Override
                    public boolean finish(UUID id,
                            com.objwww.pr.control.eval.domain.model.EvalRunCommand.State s,
                            Instant at) {
                        return true;
                    }

                    @Override
                    public List<com.objwww.pr.control.eval.domain.model.EvalRunCommand> findOrphanedClaims(
                            Instant before) {
                        return List.of();
                    }

                    @Override
                    public boolean requeue(UUID id) {
                        return false;
                    }
                };
        return new com.objwww.pr.control.eval.application.EvalCommandService(repo,
                reader, new ObjectMapper(), com.objwww.pr.control.eval.application.EvalLaunchGate
                        .closed(java.util.Set.of("L"), "eval-ds-1", 1, 10));
    }

    // ------------------------------------------------------------------ 正常页 / 末页 / 续页

    @Test
    void firstPageReturnsMappedRowsAndNextCursor() throws Exception {
        UUID e1 = UUID.randomUUID();
        UUID e2 = UUID.randomUUID();
        reader.page = new EvalPhaseEventPage(List.of(
                new EvalPhaseEventRow(e1, "PREPARING", T0, "eval-worker-1",
                        "{\"reason\":\"cold start\"}"),
                new EvalPhaseEventRow(e2, "INJECTING", T0.plusSeconds(30), null, null)),
                true);

        mvc.perform(get("/api/eval/runs/" + runId + "/events"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].id").value(e1.toString()))
                .andExpect(jsonPath("$.items[0].phase").value("PREPARING"))
                .andExpect(jsonPath("$.items[0].enteredAt").value(T0.toString()))
                .andExpect(jsonPath("$.items[0].workerId").value("eval-worker-1"))
                .andExpect(jsonPath("$.items[0].detail.reason").value("cold start"))
                .andExpect(jsonPath("$.items[1].workerId").value(nullValue()))
                .andExpect(jsonPath("$.items[1].detail").value(nullValue()))
                .andExpect(jsonPath("$.nextCursor")
                        .value(T0.plusSeconds(30) + "|" + e2));
        assertThat(reader.lastRunId).isEqualTo(runId);
        assertThat(reader.lastCursor).isNull();
        assertThat(reader.lastLimit).isEqualTo(50);
    }

    @Test
    void lastPageEmitsNullNextCursor() throws Exception {
        reader.page = new EvalPhaseEventPage(List.of(
                new EvalPhaseEventRow(UUID.randomUUID(), "SCORING", T0, "w", null)),
                false);

        mvc.perform(get("/api/eval/runs/" + runId + "/events"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.nextCursor").value(nullValue()));
    }

    @Test
    void incomingCursorIsParsedIntoStructuredKeyset() throws Exception {
        UUID anchor = UUID.randomUUID();
        reader.page = new EvalPhaseEventPage(List.of(), false);

        mvc.perform(get("/api/eval/runs/" + runId + "/events")
                        .param("cursor", T0 + "|" + anchor))
                .andExpect(status().isOk());

        assertThat(reader.lastCursor).isEqualTo(new KeysetCursor(T0, anchor));
    }

    // ------------------------------------------------------------------ limit 钳制

    @Test
    void limitIsClampedIntoOneToTwoHundred() throws Exception {
        reader.page = new EvalPhaseEventPage(List.of(), false);

        mvc.perform(get("/api/eval/runs/" + runId + "/events").param("limit", "0"))
                .andExpect(status().isOk());
        assertThat(reader.lastLimit).isEqualTo(1);

        mvc.perform(get("/api/eval/runs/" + runId + "/events").param("limit", "201"))
                .andExpect(status().isOk());
        assertThat(reader.lastLimit).isEqualTo(200);
    }

    // ------------------------------------------------------------------ 400 / 404

    @Test
    void unknownRunReturns404() throws Exception {
        mvc.perform(get("/api/eval/runs/" + UUID.randomUUID() + "/events"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("eval run 不存在"));
    }

    @Test
    void malformedCursorReturns400() throws Exception {
        mvc.perform(get("/api/eval/runs/" + runId + "/events")
                        .param("cursor", "garbage"))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/api/eval/runs/" + runId + "/events")
                        .param("cursor", T0 + "|not-a-uuid"))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/api/eval/runs/" + runId + "/events")
                        .param("cursor", "|" + UUID.randomUUID()))
                .andExpect(status().isBadRequest());
    }

    @Test
    void nonUuidRunIdReturns400() throws Exception {
        mvc.perform(get("/api/eval/runs/not-a-uuid/events"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("runId 非法"));
    }

    // ------------------------------------------------------------------ 桩与行构造

    @Test
    @DisplayName("PAGE-10：run 未落库但有已受理 LAUNCH 命令 → 200 acceptedOnly 投影（非 404）")
    void acceptedButNotCreatedRunProjectsCommand() throws Exception {
        UUID pendingRunId = UUID.randomUUID();
        acceptedCommand = com.objwww.pr.control.eval.domain.model.EvalRunCommand.pending(
                UUID.randomUUID(),
                com.objwww.pr.control.eval.domain.model.EvalRunCommand.Type.LAUNCH,
                pendingRunId, "k",
                "{\"displayName\":\"排队实验\",\"mode\":\"L\",\"datasetVersion\":\"eval-ds-1\"}",
                "h".repeat(64), "operator", T0);
        reader.run = null;

        mvc.perform(get("/api/eval/runs/" + pendingRunId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.acceptedOnly").value(true))
                .andExpect(jsonPath("$.commandState").value("PENDING"))
                .andExpect(jsonPath("$.displayName").value("排队实验"))
                .andExpect(jsonPath("$.mode").value("L"));
    }

    @Test
    @DisplayName("PAGE-10：run 与命令都不存在 → 404（随机 id 不进等待轮询面）")
    void unknownRunWithoutCommandStays404() throws Exception {
        reader.run = null;
        acceptedCommand = null;

        mvc.perform(get("/api/eval/runs/" + UUID.randomUUID()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("eval run 不存在"));
    }

    @Test
    @DisplayName("PAGE-03 能力读面：/launch-capability 与闸门同源（modes/maxConcurrency/闭面标记）")
    void launchCapabilityExposesGateDescribe() throws Exception {
        mvc.perform(get("/api/eval/launch-capability"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.modes[0]").value("L"))
                .andExpect(jsonPath("$.maxConcurrency").value(1))
                .andExpect(jsonPath("$.budgetMaxTokens").value(false))
                .andExpect(jsonPath("$.deadlineSeconds").value(false))
                .andExpect(jsonPath("$.modelOverride").value(false));
    }

    private static EvalRunRow runRow(UUID id) {
        return new EvalRunRow(id, "ds-v1", "reg-digest", "model-x", "pv1", "cfg",
                "RUNNING", T0, null,
                null, null, null, null, null, null, null,
                null, null, null, null, null, null, 0L, null, null, null,
                null, null, null, null, null);
    }

    /** 内存 EvalQueryReader 桩（只接 findRun 存在性 + listPhaseEvents 页，
     *  其余端口本面不触——抛 UnsupportedOperationException 同 EvalQueryServiceTest 惯例） */
    private static final class StubReader implements EvalQueryReader {
        EvalRunRow run;
        EvalPhaseEventPage page = new EvalPhaseEventPage(List.of(), false);
        UUID lastRunId;
        KeysetCursor lastCursor;
        int lastLimit = -1;

        @Override
        public Optional<EvalRunRow> findRun(UUID runId) {
            return Optional.ofNullable(run).filter(r -> r.runId().equals(runId));
        }

        @Override
        public EvalPhaseEventPage listPhaseEvents(UUID runId, KeysetCursor cursor,
                                                  int limit) {
            this.lastRunId = runId;
            this.lastCursor = cursor;
            this.lastLimit = limit;
            return page;
        }

        @Override
        public EvalRunPage listRuns(String state, KeysetCursor cursor, int limit) {
            throw new UnsupportedOperationException();
        }

        @Override
        public EvalCasePage listCases(UUID runId, String verdict, String afterScenario,
                                      Integer afterRound, int limit) {
            throw new UnsupportedOperationException();
        }

        @Override
        public List<DatasetRow> listDatasets() {
            throw new UnsupportedOperationException();
        }

        @Override
        public List<PartitionCountRow> listPartitionCounts() {
            throw new UnsupportedOperationException();
        }

        @Override
        public Optional<EvalCaseDetailRow> findCaseDetail(UUID runId,
                                                          UUID caseExecutionId) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Optional<CaseIdentityRow> findCaseIdentity(String datasetVersion,
                                                          String scenarioId) {
            throw new UnsupportedOperationException();
        }

        @Override
        public List<CaseEvidenceRefRow> listCaseEvidenceRefs(UUID runId) {
            throw new UnsupportedOperationException();
        }

        @Override
        public List<EvidenceMetaRow> listEvidenceMeta(UUID rcaRunId,
                                                      List<UUID> evidenceIds) {
            throw new UnsupportedOperationException();
        }

        @Override
        public List<CaseLogEvidenceRow> listCaseLogEvidence(UUID runId,
                                                            String scenarioId) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Optional<CompareRunMeta> findCompareMeta(UUID runId) {
            throw new UnsupportedOperationException();
        }

        @Override
        public List<CompareCaseRow> listCasesForCompare(UUID runId, int limit) {
            throw new UnsupportedOperationException();
        }

        @Override
        public List<String> listPlanCaseKeys(String datasetVersion) {
            throw new UnsupportedOperationException();
        }

        @Override
        public List<UsageCallRow> listUsageCalls(UUID evalRunId) {
            throw new UnsupportedOperationException();
        }

        @Override
        public List<UsageCallRow> listUsageCallsForRuns(Iterable<UUID> evalRunIds) {
            throw new UnsupportedOperationException();
        }

        @Override
        public List<ScenarioRoundStatRow> listScenarioRoundStatsForRuns(Iterable<UUID> evalRunIds) {
            throw new UnsupportedOperationException();
        }

        @Override
        public List<CaseSafetyRow> listCaseSafety(UUID evalRunId) {
            throw new UnsupportedOperationException();
        }

        @Override
        public List<CaseJudgeRow> listJudge(UUID evalRunId) {
            return List.of();
        }

        @Override
        public List<CaseTokenRow> listCaseTokenTotals(UUID evalRunId,
                                                      List<UUID> caseExecutionIds) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Optional<UUID> findAutoCompareBaseline(UUID candidateRunId) {
            throw new UnsupportedOperationException();
        }
    }
}
