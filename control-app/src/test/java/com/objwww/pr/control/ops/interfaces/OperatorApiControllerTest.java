package com.objwww.pr.control.ops.interfaces;

import com.objwww.pr.control.ops.application.CaseDraft;
import com.objwww.pr.control.ops.application.InMemoryClaimStore;
import com.objwww.pr.control.ops.application.InMemoryEvidenceRepository;
import com.objwww.pr.control.ops.application.InMemoryOperatorCases;
import com.objwww.pr.control.ops.application.OperatorCaseService;
import com.objwww.pr.control.ops.application.OperatorQueryService;
import com.objwww.pr.shared.Digest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Operator API 最小集（M5-12；落码方案 §M5-12③ 契约，字段级以 mocks/cases.js +
 * CasesView.vue 实际调用为准）：summary/list/detail 投影形状、并发认领 409 携最新
 * 投影、resolve 结构化 reason、审计断言。拆解验收："RBAC、并发认领、审计测试"。
 * EX-C3a：bearer RBAC 面上移 SecurityFilterChain（SecurityConfigTest 链级覆盖）；
 * actor=认证主体（X-Operator-Id 自报面摘除——测试侧改铸 SecurityContext）。
 */
class OperatorApiControllerTest {

    private static final Supplier<Instant> CLOCK =
            () -> Instant.parse("2026-09-08T10:00:00Z");

    private final InMemoryOperatorCases repo = new InMemoryOperatorCases();
    private final OperatorCaseService service = new OperatorCaseService(repo, CLOCK);
    private final OperatorQueryService query = new OperatorQueryService(repo, CLOCK,
            new InMemoryEvidenceRepository(), new InMemoryClaimStore());
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.standaloneSetup(
                        new OperatorApiController(query, service))
                .build();
        actorIs("operator");
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    /** EX-C3a：actor 唯一来源 = 认证主体（浏览器用户名或 machine: 线主体）。 */
    private static void actorIs(String name) {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(name, null, List.of()));
    }

    // ------------------------------------------------------------------ summary（前端 tabs 形状）

    @Test
    void summaryMatchesViewTabsShapeWithBucketCounts() throws Exception {
        seed("fp-open-1", "P0", null, false, false);
        seed("fp-mine", "P0", "operator", false, false);
        seed("fp-unassigned", "P1", null, false, false);
        seed("fp-overdue", "P0", "operator", true, false);
        seed("fp-resolved", "P2", null, false, true);

        mvc.perform(get("/api/cases/summary"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tabs.mine").value(2))
                .andExpect(jsonPath("$.tabs.all").value(4))
                .andExpect(jsonPath("$.tabs.unassigned").value(2))
                .andExpect(jsonPath("$.tabs.overdue").value(1))
                .andExpect(jsonPath("$.tabs.notifyUnread").value(0))
                .andExpect(jsonPath("$.updatedAt").isNotEmpty());
    }

    // ------------------------------------------------------------------ list（SLA 风险排序 + 过滤）

    @Test
    void listSortsBySlaRiskWithOverdueFirst() throws Exception {
        seed("fp-p2-future", "P2", null, false, false);
        seed("fp-p0-future", "P0", null, false, false);
        seed("fp-overdue", "P1", null, true, false);

        mvc.perform(get("/api/cases"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(3))
                .andExpect(jsonPath("$[0].overdue").value(true))
                .andExpect(jsonPath("$[0].priority").value("P1"))
                .andExpect(jsonPath("$[0].slaLabel").value("已逾期"))
                .andExpect(jsonPath("$[1].priority").value("P0"))
                .andExpect(jsonPath("$[2].priority").value("P2"))
                .andExpect(jsonPath("$[0].evidenceCount").value(1))
                .andExpect(jsonPath("$[0].revision").value(1));
    }

    @Test
    void listFiltersByStatusPriorityReasonCode() throws Exception {
        seed("fp-a", "P0", "operator", false, false);
        seed("fp-b", "P1", null, false, false);

        mvc.perform(get("/api/cases").param("status", "OPEN"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].status").value("OPEN"));

        mvc.perform(get("/api/cases").param("priority", "P1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].priority").value("P1"));
    }

    // ------------------------------------------------------------------ detail（工作区投影）

    @Test
    void detailReturnsWorkspaceProjectionWithAuditTrail() throws Exception {
        UUID id = seed("fp-detail", "P0", null, false, false);
        service.claim(id, 1, "operator-a", "k-claim");

        mvc.perform(get("/api/cases/" + id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.headline").value("Claim 冲突"))
                .andExpect(jsonPath("$.sourceRefs.count").value(1))
                .andExpect(jsonPath("$.snapshot").isNotEmpty())
                .andExpect(jsonPath("$.generation").value(13))
                .andExpect(jsonPath("$.evidence.length()").value(0))
                .andExpect(jsonPath("$.claims.length()").value(0))
                .andExpect(jsonPath("$.activities[0]").isString())
                .andExpect(jsonPath("$.audits[0].action").value("CASE_CREATED"))
                .andExpect(jsonPath("$.audits[0].time").isNotEmpty())
                .andExpect(jsonPath("$.audits[1].action").value("CLAIM"))
                .andExpect(jsonPath("$.audits[1].key").value("k-claim"))
                .andExpect(jsonPath("$.resolution").value(nullValue()));
    }

    @Test
    void detailUnknownCaseIs404() throws Exception {
        mvc.perform(get("/api/cases/" + UUID.randomUUID()))
                .andExpect(status().isNotFound());
    }

    // ------------------------------------------------------------------ 命令面（claim/ack/resolve/assign）

    @Test
    void claimSetsOwnerFromAuthenticatedActor() throws Exception {
        UUID id = seed("fp-claim", "P0", null, false, false);
        actorIs("sre-li");

        mvc.perform(post("/api/cases/" + id + "/claim")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expectedRevision\":1,\"idempotencyKey\":\"ui-1\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ok").value(true))
                .andExpect(jsonPath("$.conflict").value(false))
                .andExpect(jsonPath("$.case.owner").value("sre-li"))
                .andExpect(jsonPath("$.case.status").value("ACKED"))
                .andExpect(jsonPath("$.case.revision").value(2));
    }

    @Test
    void staleRevisionClaimConflictsWith409AndLatestProjection() throws Exception {
        UUID id = seed("fp-conflict", "P0", null, false, false);
        service.assign(id, 1, "operator-a", "system", "k-assign");
        actorIs("sre-b");

        mvc.perform(post("/api/cases/" + id + "/claim")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expectedRevision\":1,\"idempotencyKey\":\"ui-2\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.ok").value(false))
                .andExpect(jsonPath("$.conflict").value(true))
                .andExpect(jsonPath("$.error").value("REVISION_CONFLICT"))
                .andExpect(jsonPath("$.case.owner").value("operator-a"))
                .andExpect(jsonPath("$.case.revision").value(2));
    }

    @Test
    void resolveRejectsMissingReasonAndAcceptsStructuredShape() throws Exception {
        // seed 先 claim（owner=operator-a，revision 2 ACKED）
        UUID id = seed("fp-resolve", "P0", "operator-a", false, false);
        actorIs("operator-a");

        mvc.perform(post("/api/cases/" + id + "/resolve")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expectedRevision\":2,\"idempotencyKey\":\"ui-3\"}"))
                .andExpect(status().isBadRequest());

        // 落码方案契约形状：reason{code,note}
        mvc.perform(post("/api/cases/" + id + "/resolve")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expectedRevision\":2,\"idempotencyKey\":\"ui-4\","
                                + "\"reason\":{\"code\":\"WONT_FIX\",\"note\":\"误报\"}}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ok").value(true))
                .andExpect(jsonPath("$.case.status").value("RESOLVED"));
    }

    @Test
    void resolveAcceptsViewFlatReasonAndRemarkShape() throws Exception {
        UUID id = seed("fp-flat", "P0", "operator-a", false, false);
        actorIs("operator-a");

        // CasesView.vue 实际发送形状：{reason:<code>, remark:<note>}（联调验收行为不变面）
        mvc.perform(post("/api/cases/" + id + "/resolve")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expectedRevision\":2,\"idempotencyKey\":\"ui-5\","
                                + "\"reason\":\"FIXED\",\"remark\":\"已修复\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ok").value(true))
                .andExpect(jsonPath("$.case.status").value("RESOLVED"));

        assertThat(repo.byId(id).resolution().code()).isEqualTo("FIXED");
        assertThat(repo.byId(id).resolution().note()).isEqualTo("已修复");
    }

    @Test
    void assignAcceptsSpecToOwnerAndViewAssignee() throws Exception {
        UUID id = seed("fp-assign", "P0", null, false, false);
        actorIs("dispatcher");

        mvc.perform(post("/api/cases/" + id + "/assign")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expectedRevision\":1,\"idempotencyKey\":\"ui-6\","
                                + "\"toOwner\":\"sre-li\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.case.owner").value("sre-li"));

        mvc.perform(post("/api/cases/" + id + "/assign")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expectedRevision\":2,\"idempotencyKey\":\"ui-7\","
                                + "\"assignee\":\"sre-wang\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.case.owner").value("sre-wang"));
    }

    @Test
    void illegalTransitionIs422AndUnknownCaseIs404() throws Exception {
        UUID id = seed("fp-illegal", "P0", null, false, false);
        service.resolve(id, 1, "WONT_FIX", "误报", "operator-a", "k-resolve");
        actorIs("sre-b");

        mvc.perform(post("/api/cases/" + id + "/claim")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expectedRevision\":2,\"idempotencyKey\":\"ui-8\"}"))
                .andExpect(status().isUnprocessableEntity());

        mvc.perform(post("/api/cases/" + UUID.randomUUID() + "/claim")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expectedRevision\":1,\"idempotencyKey\":\"ui-9\"}"))
                .andExpect(status().isNotFound());
    }

    // ------------------------------------------------------------------ 种子

    private UUID seed(String fingerprint, String priority, String owner,
                      boolean overdue, boolean resolved) {
        Instant ackDue = overdue ? CLOCK.get().minusSeconds(3600) : CLOCK.get().plusSeconds(600);
        Instant resolveDue = overdue ? CLOCK.get().minusSeconds(600) : CLOCK.get().plusSeconds(3600);
        UUID id = service.openOrMerge(new CaseDraft("tenant-1", fingerprint, "Claim 冲突", priority,
                "CLAIM_CONFLICT", UUID.randomUUID(), "root-cause", "payment-failure",
                Digest.sha256Of("snapshot-" + fingerprint), 13,
                List.of("evidence#81"), ackDue, resolveDue,
                "idem-" + fingerprint)).caseId();
        long revision = 1;
        if (owner != null) {
            revision = service.claim(id, revision, owner,
                    "seed-claim-" + fingerprint).revision();
        }
        if (resolved) {
            service.resolve(id, revision, "WONT_FIX", "seed",
                    owner == null ? "system" : owner, "seed-resolve-" + fingerprint);
        }
        return id;
    }
}
