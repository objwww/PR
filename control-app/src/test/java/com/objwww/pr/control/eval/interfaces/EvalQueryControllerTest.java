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
        // standalone 装配不挂 Boot 自动配置，jsr310 需显式注册并关时间戳——与生产序列化
        // （ISO 字符串）对齐，沿 MetricsQueryControllerTest 惯例
        mvc = MockMvcBuilders.standaloneSetup(new EvalQueryController(service))
                .setMessageConverters(new MappingJackson2HttpMessageConverter(
                        Jackson2ObjectMapperBuilder.json()
                                .featuresToDisable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
                                .build()))
                .build();
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
        public List<UsageCallRow> listUsageCalls(UUID evalRunId) {
            throw new UnsupportedOperationException();
        }

        @Override
        public List<UsageCallRow> listUsageCallsForRuns(Iterable<UUID> evalRunIds) {
            throw new UnsupportedOperationException();
        }
    }
}
