package com.objwww.pr.control.alert.interfaces;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.objwww.pr.control.alert.application.ReportQueryService;
import com.objwww.pr.control.alert.domain.model.PublicationState;
import com.objwww.pr.control.alert.domain.model.RcaRun;
import com.objwww.pr.control.alert.domain.model.RcaRunState;
import com.objwww.pr.control.alert.domain.model.ReportPublication;
import com.objwww.pr.control.alert.domain.model.RunTrigger;
import com.objwww.pr.control.alert.domain.model.ValidationStatus;
import com.objwww.pr.control.alert.domain.repository.RcaReportReader;
import com.objwww.pr.control.alert.domain.repository.RcaReportReader.RcaReportView;
import com.objwww.pr.control.alert.domain.repository.RcaRunRepository;
import com.objwww.pr.control.alert.domain.repository.ReportPublicationRepository;
import com.objwww.pr.shared.Digest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.UUID;

import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * ReportQueryController 契约（报告 tab 读面；MetricsQueryControllerTest 同模式——
 * standalone MockMvc，RBAC 面上移 SecurityFilterChain）：
 * run 无报告 200 {"state":"NONE"} / 有报告 OK 投影 / REJECTED 原因链+原文透传 /
 * publication 无记录 null 如实 / 多报告只投影最新+supersededCount /
 * run 不存在 404 / runId 非法 400。
 */
class ReportQueryControllerTest {

    private static final Instant NOW = Instant.parse("2026-09-12T08:00:00Z");
    private static final UUID RUN_ID = UUID.randomUUID();
    private static final UUID REPORT_ID = UUID.randomUUID();

    private FakeRuns runs;
    private Map<UUID, List<RcaReportView>> reportRows;
    private Map<UUID, ReportPublication> publicationRows;
    private MockMvc mvc;

    /** standalone 装配不挂 Boot 自动配置，jsr310 需显式注册并关时间戳——与生产序列化（ISO 字符串）对齐 */
    private static MockMvc standalone(Object controller) {
        return MockMvcBuilders.standaloneSetup(controller)
                .setMessageConverters(new MappingJackson2HttpMessageConverter(
                        Jackson2ObjectMapperBuilder.json()
                                .featuresToDisable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
                                .build()))
                .build();
    }

    @BeforeEach
    void setUp() {
        runs = new FakeRuns();
        reportRows = new LinkedHashMap<>();
        publicationRows = new LinkedHashMap<>();
        RcaReportReader reader = runId -> reportRows.getOrDefault(runId, List.of());
        ReportPublicationRepository publications = new FakePublications(publicationRows);
        mvc = standalone(new ReportQueryController(
                new ReportQueryService(reader, publications, new ObjectMapper()), runs));
    }

    @Test
    void noReportIs200None() throws Exception {
        runs.put(RUN_ID);
        mvc.perform(get("/api/rca-runs/{runId}/report", RUN_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("NONE"));
    }

    @Test
    void validatedReportProjectsOkWithPublication() throws Exception {
        runs.put(RUN_ID);
        reportRows.put(RUN_ID, List.of(report(REPORT_ID, ValidationStatus.STRUCTURE_VALIDATED,
                List.of(), NOW,
                "{\"schema_version\":2,\"summary\":\"连接池耗尽\",\"root_cause\":"
                        + "{\"component\":\"db-pool\",\"fault_type\":\"EXHAUSTION\",\"reason_code\":\"POOL_EXHAUSTED\"},"
                        + "\"evidence\":[],\"impact\":\"写入失败\",\"remediation\":\"扩容\",\"references\":[]}")));
        publicationRows.put(REPORT_ID, new ReportPublication(
                UUID.randomUUID(), REPORT_ID, PublicationState.SENT,
                null, null, 0, 1, 5, NOW, null, NOW, NOW));

        mvc.perform(get("/api/rca-runs/{runId}/report", RUN_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("OK"))
                .andExpect(jsonPath("$.reportId").value(REPORT_ID.toString()))
                .andExpect(jsonPath("$.schemaVersion").value(2))
                .andExpect(jsonPath("$.validationStatus").value("STRUCTURE_VALIDATED"))
                .andExpect(jsonPath("$.model").value("qwen-plus"))
                .andExpect(jsonPath("$.promptTokens").value(12))
                .andExpect(jsonPath("$.completionTokens").value(22))
                .andExpect(jsonPath("$.totalTokens").value(34))
                .andExpect(jsonPath("$.usageMissing").value(false))
                .andExpect(jsonPath("$.createdAt").value(NOW.toString()))
                .andExpect(jsonPath("$.supersededCount").value(0))
                .andExpect(jsonPath("$.packageJson.summary").value("连接池耗尽"))
                .andExpect(jsonPath("$.packageJson.root_cause.component").value("db-pool"))
                .andExpect(jsonPath("$.publication.state").value("SENT"))
                .andExpect(jsonPath("$.publication.attemptCount").value(1))
                .andExpect(jsonPath("$.publication.maxAttempts").value(5))
                .andExpect(jsonPath("$.publication.updatedAt").value(NOW.toString()))
                .andExpect(jsonPath("$.publication.lastError").value(nullValue()));
    }

    @Test
    void noPublicationIsNullNotFabricated() throws Exception {
        runs.put(RUN_ID);
        reportRows.put(RUN_ID, List.of(report(REPORT_ID, ValidationStatus.STRUCTURE_VALIDATED,
                List.of(), NOW, "{\"schema_version\":1,\"summary\":\"s\"}")));

        mvc.perform(get("/api/rca-runs/{runId}/report", RUN_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("OK"))
                .andExpect(jsonPath("$.publication").value(nullValue()));
    }

    @Test
    void rejectedReportProjectsReasonChainAndKeepsPackage() throws Exception {
        runs.put(RUN_ID);
        reportRows.put(RUN_ID, List.of(report(REPORT_ID, ValidationStatus.REJECTED_SCHEMA_MISMATCH,
                List.of("缺少必备字段: references", "evidence 非数组"), NOW,
                "{\"schema_version\":2,\"summary\":\"残缺包\"}")));

        mvc.perform(get("/api/rca-runs/{runId}/report", RUN_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("REJECTED"))
                .andExpect(jsonPath("$.validationStatus").value("REJECTED_SCHEMA_MISMATCH"))
                .andExpect(jsonPath("$.validationErrors[0]").value("缺少必备字段: references"))
                .andExpect(jsonPath("$.validationErrors[1]").value("evidence 非数组"))
                .andExpect(jsonPath("$.packageJson.summary").value("残缺包"));
    }

    @Test
    void multipleReportsProjectLatestWithSupersededCount() throws Exception {
        runs.put(RUN_ID);
        UUID older = UUID.randomUUID();
        reportRows.put(RUN_ID, List.of(
                report(older, ValidationStatus.STRUCTURE_VALIDATED, List.of(),
                        NOW.minusSeconds(300), "{\"schema_version\":2,\"summary\":\"旧\"}"),
                report(REPORT_ID, ValidationStatus.STRUCTURE_VALIDATED, List.of(),
                        NOW, "{\"schema_version\":2,\"summary\":\"新\"}")));

        mvc.perform(get("/api/rca-runs/{runId}/report", RUN_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.reportId").value(REPORT_ID.toString()))
                .andExpect(jsonPath("$.packageJson.summary").value("新"))
                .andExpect(jsonPath("$.supersededCount").value(1));
    }

    @Test
    void malformedPackagePassesThroughAsRawString() throws Exception {
        runs.put(RUN_ID);
        reportRows.put(RUN_ID, List.of(report(REPORT_ID, ValidationStatus.REJECTED_MALFORMED,
                List.of("analysis 非合法 JSON"), NOW, "not-json{truncated")));

        mvc.perform(get("/api/rca-runs/{runId}/report", RUN_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("REJECTED"))
                .andExpect(jsonPath("$.packageJson").value("not-json{truncated"));
    }

    @Test
    void unknownRunIs404() throws Exception {
        mvc.perform(get("/api/rca-runs/{runId}/report", UUID.randomUUID()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("run 不存在"));
    }

    @Test
    void illegalRunIdIs400() throws Exception {
        mvc.perform(get("/api/rca-runs/{runId}/report", "not-a-uuid"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("runId 非法"));
    }

    // ------------------------------------------------------------------ fixtures

    private static RcaReportView report(UUID id, ValidationStatus status, List<String> errors,
                                        Instant createdAt, String packageJson) {
        return new RcaReportView(id, RUN_ID, UUID.randomUUID(), 2, status, errors, packageJson,
                "qwen-plus", 12, 22, 34, false, createdAt);
    }

    private static final class FakeRuns implements RcaRunRepository {
        private final Map<UUID, RcaRun> rows = new LinkedHashMap<>();

        void put(UUID id) {
            rows.put(id, new RcaRun(id, UUID.randomUUID(), 0, RunTrigger.INITIAL,
                    RcaRunState.SUCCEEDED, Digest.sha256Of("inv-" + id),
                    NOW.minusSeconds(600), NOW, NOW.minusSeconds(600), NOW, null));
        }

        @Override
        public void insert(RcaRun run) {
            rows.put(run.id(), run);
        }

        @Override
        public Optional<RcaRun> findByIdForUpdate(UUID id) {
            return Optional.ofNullable(rows.get(id));
        }

        @Override
        public Optional<RcaRun> findById(UUID id) {
            return Optional.ofNullable(rows.get(id));
        }

        @Override
        public boolean update(RcaRun run) {
            throw new UnsupportedOperationException("只读面测试不涉及");
        }

        @Override
        public Optional<RcaRun> findActiveByIncidentId(UUID incidentId) {
            return Optional.empty();
        }

        @Override
        public List<RcaRun> findAll() {
            return List.copyOf(rows.values());
        }

        @Override
        public Optional<RoutingView> findRoutingById(UUID id) {
            return Optional.empty();
        }

        @Override
        public boolean existsNativeRunByIncidentId(UUID incidentId) {
            return false;
        }

        @Override
        public OptionalLong currentRevision(UUID id) {
            return OptionalLong.empty();
        }
    }

    private static final class FakePublications implements ReportPublicationRepository {
        private final Map<UUID, ReportPublication> rows;

        FakePublications(Map<UUID, ReportPublication> rows) {
            this.rows = rows;
        }

        @Override
        public void insert(ReportPublication publication) {
            rows.put(publication.reportId(), publication);
        }

        @Override
        public Optional<ReportPublication> findByReportId(UUID reportId) {
            return Optional.ofNullable(rows.get(reportId));
        }
    }
}
