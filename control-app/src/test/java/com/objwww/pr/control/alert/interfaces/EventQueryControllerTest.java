package com.objwww.pr.control.alert.interfaces;

import com.objwww.pr.control.alert.application.EventPayloadSanitizer;
import com.objwww.pr.control.alert.application.EventQueryService;
import com.objwww.pr.control.alert.application.RunQueryService;
import com.objwww.pr.control.alert.application.SseStreamService;
import com.objwww.pr.control.alert.domain.model.RcaEngine;
import com.objwww.pr.control.alert.domain.model.RcaRun;
import com.objwww.pr.control.alert.domain.model.RcaRunState;
import com.objwww.pr.control.alert.domain.model.RunTrigger;
import com.objwww.pr.control.alert.domain.repository.RcaEventReader;
import com.objwww.pr.control.alert.domain.repository.RcaRunRepository;
import com.objwww.pr.control.alert.domain.dag.DependencyType;
import com.objwww.pr.control.alert.domain.repository.RcaTaskRepository;
import com.objwww.pr.control.alert.domain.model.RcaTask;
import com.objwww.pr.control.alert.domain.model.RcaTaskState;
import com.objwww.pr.control.alert.domain.repository.TaskEdgeRepository;
import com.objwww.pr.shared.Digest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.UUID;
import java.util.function.Supplier;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * EventQueryController 契约（M5-13 §M5-13③；落码方案验收"断线重连/游标/权限"单测面）：
 * runs 列表/详情投影、events 游标+level 面、stream ticket 单次/绑主体、
 * Last-Event-ID 续传（SSE 单次排水 + asyncDispatch）。
 * EX-C3a：bearer RBAC 面上移 SecurityFilterChain（SecurityConfigTest 链级覆盖）；
 * 主体 = 测试侧铸入 SecurityContext 的认证（AuthenticatedActor 面）。
 */
class EventQueryControllerTest {

    private static final Instant NOW = Instant.parse("2026-09-08T10:00:00Z");
    private static final Supplier<Instant> CLOCK = () -> NOW;

    private final StubRuns runs = new StubRuns();
    private final StubEvents eventRows = new StubEvents();
    private MockMvc mvc;
    private UUID runId;

    @BeforeEach
    void setUp() {
        runId = UUID.randomUUID();
        runs.put(run(runId, RcaRunState.RUNNING));
        EventQueryService events = new EventQueryService(eventRows, new EventPayloadSanitizer(200));
        RunQueryService runQuery = new RunQueryService(runs, new StubTasks(), new StubEdges(), CLOCK);
        SseStreamService sse = new SseStreamService(events, Duration.ofSeconds(30));
        mvc = MockMvcBuilders.standaloneSetup(
                        new EventQueryController(runQuery, events, sse, runs))
                .build();
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("operator", null, java.util.List.of()));
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    // ------------------------------------------------------------------ 列表/详情

    @Test
    void listReturnsSummaryAndRowShape() throws Exception {
        mvc.perform(get("/api/rca-runs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.summary.buckets.mine").value(0))
                .andExpect(jsonPath("$.summary.sla.projectionLag").value(nullValue()))
                .andExpect(jsonPath("$.rows").isArray())
                .andExpect(jsonPath("$.nextCursor").value(nullValue()));
    }

    @Test
    void detailReturnsProjectionOr404() throws Exception {
        mvc.perform(get("/api/rca-runs/" + runId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.run.id").value(runId.toString()))
                .andExpect(jsonPath("$.run.severity").value(nullValue()))
                .andExpect(jsonPath("$.tasks").isArray())
                .andExpect(jsonPath("$.edges").isArray());
        mvc.perform(get("/api/rca-runs/" + UUID.randomUUID()))
                .andExpect(status().isNotFound());
    }

    // ------------------------------------------------------------------ events 查询

    @Test
    void eventsExposeTypeLevelAndCursorFaces() throws Exception {
        eventRows.rows.addAll(List.of(
                row(1, "RUN_STARTED", "{\"summary\":\"开始\"}"),
                row(2, "TASK_RETRY_SCHEDULED", "{\"task_id\":\"ROOT_CAUSE\",\"summary\":\"退避 90s\"}"),
                row(3, "TOOL_CALL_FAILED", "{\"task_id\":\"ROOT_CAUSE\",\"summary\":\"REMOTE_5XX\"}")));

        mvc.perform(get("/api/rca-runs/" + runId + "/events"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.events[0].seq").value(1))
                .andExpect(jsonPath("$.events[0].type").value("RUN_STARTED"))
                .andExpect(jsonPath("$.events[1].level").value("warn"))
                .andExpect(jsonPath("$.events[1].taskId").value("ROOT_CAUSE"))
                .andExpect(jsonPath("$.events[2].level").value("error"))
                .andExpect(jsonPath("$.events[2].payload.summary").value("REMOTE_5XX"))
                .andExpect(jsonPath("$.latestSeq").value(3))
                .andExpect(jsonPath("$.gap").value(false));

        mvc.perform(get("/api/rca-runs/" + runId + "/events")
                        .param("after_seq", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.events.length()").value(1))
                .andExpect(jsonPath("$.events[0].seq").value(3));

        mvc.perform(get("/api/rca-runs/" + UUID.randomUUID() + "/events"))
                .andExpect(status().isNotFound());
    }

    // ------------------------------------------------------------------ SSE 换票 + 流

    @Test
    void streamResumesFromLastEventIdAndTicketIsSingleUse() throws Exception {
        eventRows.rows.addAll(List.of(
                row(1, "RUN_STARTED", "{\"summary\":\"a\"}"),
                row(2, "TASK_LEASED", "{\"task_id\":\"METRICS\",\"summary\":\"领取\"}")));

        String ticket = mvc.perform(post("/api/rca-runs/" + runId + "/events/stream-ticket"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ticket").isString())
                .andReturn().getResponse().getContentAsString()
                .replaceAll(".*\"ticket\":\"([^\"]+)\".*", "$1");

        // Last-Event-ID=1 → 只续传 seq=2（断线重连续传面）
        MvcResult result = mvc.perform(get("/api/rca-runs/" + runId + "/events/stream")
                        .queryParam("ticket", ticket)
                        .queryParam("subject", "operator")
                        .header("Last-Event-ID", "1"))
                .andExpect(request().asyncStarted())
                .andReturn();
        mvc.perform(asyncDispatch(result))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("id:2")))
                .andExpect(content().string(containsString("event:rca_event")))
                .andExpect(content().string(containsString("TASK_LEASED")));

        // 票单次：同票重放开流拒绝
        mvc.perform(get("/api/rca-runs/" + runId + "/events/stream")
                        .queryParam("ticket", ticket)
                        .queryParam("subject", "operator"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void streamRejectsBadTicketAndForeignSubject() throws Exception {
        String ticket = issueTicket();

        mvc.perform(get("/api/rca-runs/" + runId + "/events/stream")
                        .queryParam("ticket", "no-such-ticket"))
                .andExpect(status().isUnauthorized());

        mvc.perform(get("/api/rca-runs/" + runId + "/events/stream")
                        .queryParam("ticket", ticket)
                        .queryParam("subject", "someone-else"))
                .andExpect(status().isUnauthorized());
    }

    private String issueTicket() throws Exception {
        return mvc.perform(post("/api/rca-runs/" + runId + "/events/stream-ticket"))
                .andReturn().getResponse().getContentAsString()
                .replaceAll(".*\"ticket\":\"([^\"]+)\".*", "$1");
    }

    // ------------------------------------------------------------------ 内存假

    private static RcaRun run(UUID id, RcaRunState state) {
        return new RcaRun(id, UUID.randomUUID(), 0, RunTrigger.INITIAL, state,
                Digest.sha256Of("inv"), NOW.minus(Duration.ofMinutes(5)), NOW,
                NOW, null, null);
    }

    private static RcaEventReader.EventRow row(long seq, String type, String payload) {
        return new RcaEventReader.EventRow(seq, type, payload, NOW);
    }

    static final class StubRuns implements RcaRunRepository {
        private final Map<UUID, RcaRun> rows = new LinkedHashMap<>();

        void put(RcaRun run) {
            rows.put(run.id(), run);
        }

        @Override
        public void insert(RcaRun run) {
            rows.put(run.id(), run);
        }

        // C-61 fake 镜像：无路由记录 = 普通 insert 存量行 = 默认 HOLMES
        @Override
        public boolean existsNativeRunByIncidentId(UUID incidentId) {
            return false;
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
            return rows.containsKey(run.id());
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
            return Optional.of(new RoutingView(RcaEngine.HOLMES, null, null, null,
                    null, null, null));
        }

        @Override
        public java.util.OptionalLong currentRevision(UUID id) {
            return rows.containsKey(id) ? java.util.OptionalLong.of(0) : java.util.OptionalLong.empty();
        }
    }

    static final class StubEvents implements RcaEventReader {
        final List<RcaEventReader.EventRow> rows = new java.util.ArrayList<>();

        @Override
        public List<RcaEventReader.EventRow> readAfter(UUID runId, long afterSeq, int limit) {
            return rows.stream().filter(r -> r.seq() > afterSeq).limit(limit).toList();
        }

        @Override
        public OptionalLong latestSeq(UUID runId) {
            return rows.isEmpty()
                    ? OptionalLong.empty()
                    : OptionalLong.of(rows.get(rows.size() - 1).seq());
        }
    }

    static final class StubTasks implements RcaTaskRepository {
        @Override
        public void insert(RcaTask task) {
        }

        @Override
        public Optional<RcaTask> claimNext(String owner, Instant now, Duration lease) {
            return Optional.empty();
        }

        @Override
        public boolean requireCurrentLease(UUID id, String owner, long leaseEpoch) {
            return false;
        }

        @Override
        public boolean update(RcaTask task) {
            return false;
        }

        @Override
        public void heartbeat(UUID id, String owner, long leaseEpoch, Instant now, Duration extend) {
        }

        @Override
        public List<RcaTask> findExpiredLeased(Instant now) {
            return List.of();
        }

        @Override
        public Optional<RcaTask> findById(UUID id) {
            return Optional.empty();
        }

        @Override
        public List<RcaTask> findByRunId(UUID runId) {
            return List.of();
        }

        @Override
        public boolean transitionState(UUID id, RcaTaskState from, RcaTaskState to) {
            return false;
        }

        @Override
        public int countQueued() {
            return 0;
        }
    }

    static final class StubEdges implements TaskEdgeRepository {
        @Override
        public void insert(UUID runId, UUID fromTaskId, UUID toTaskId, DependencyType dependencyType) {
        }

        @Override
        public List<com.objwww.pr.control.alert.domain.dag.TaskEdge> findByRunId(UUID runId) {
            return List.of();
        }
    }
}
