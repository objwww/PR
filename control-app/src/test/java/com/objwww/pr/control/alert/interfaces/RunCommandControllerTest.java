package com.objwww.pr.control.alert.interfaces;

import com.objwww.pr.control.alert.application.CommandService;
import com.objwww.pr.control.alert.domain.model.RcaRun;
import com.objwww.pr.control.alert.domain.model.RcaRunState;
import com.objwww.pr.control.alert.domain.model.RunTrigger;
import com.objwww.pr.control.alert.support.AlertInMemoryStores;
import com.objwww.pr.shared.Digest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * RunCommandController 契约（M5-14；落码方案 §M5-14③/④）：body 必填
 * 400 面、APPLIED=200/REJECTED_STALE=409/REJECTED_FORBIDDEN=403、幂等重放原
 * commandId、未知 run 404。拆解验收："幂等命令、旧 revision、越权测试"。
 * EX-C3a：bearer RBAC 面上移 SecurityFilterChain（SecurityConfigTest 链级覆盖）；
 * actor=测试侧铸入 SecurityContext 的认证（X-Operator-Id 自报面摘除）。
 */
class RunCommandControllerTest {

    private static final Instant NOW = Instant.parse("2026-09-08T10:00:00Z");
    private static final Supplier<Instant> CLOCK = () -> NOW;

    private final AlertInMemoryStores stores = new AlertInMemoryStores();
    private MockMvc mvc;
    private UUID runId;

    @BeforeEach
    void setUp() {
        runId = UUID.randomUUID();
        stores.runs.insert(new RcaRun(runId, UUID.randomUUID(), 0, RunTrigger.INITIAL,
                RcaRunState.RUNNING, Digest.sha256Of("inv"),
                NOW.minus(Duration.ofMinutes(5)), NOW, NOW, null, null));
        CommandService service = new CommandService(
                stores.commands, stores.runs, stores.rcaEvents, CLOCK);
        // EN-04：CONFIG_SWITCH 分流面（代理件空史 = 一切切换快败，本类只测 HTTP 面）
        com.objwww.pr.control.alert.application.RunConfigSwitchService switchService =
                new com.objwww.pr.control.alert.application.RunConfigSwitchService(
                        stores.commands, stores.runs, stores.tasks,
                        com.objwww.pr.control.alert.domain.repository.RunConfigEpochRepository.NO_OP,
                        new NoopBundles(), new NoopQualifications(),
                        new com.objwww.pr.control.alert.application.agent.AgentRegistry(
                                java.util.List.of(new com.objwww.pr.control.alert.domain.agent.AgentProfile(
                                        "primary", "1", "p", "pv", java.util.Set.of(),
                                        java.util.Map.of(), java.util.Map.of()))),
                        stores.modelCalls, stores.rcaEvents,
                        new org.springframework.transaction.support.TransactionOperations() {
                            @Override
                            @SuppressWarnings("unchecked")
                            public <T> T execute(
                                    org.springframework.transaction.support.TransactionCallback<T> action) {
                                return action.doInTransaction(null);
                            }
                        }, CLOCK);
        mvc = MockMvcBuilders.standaloneSetup(
                        new RunCommandController(service, switchService, stores.runs))
                .build();
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("sre-li", null, java.util.List.of()));
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void missingOrIllegalBodyFieldsAreBadRequest() throws Exception {
        mvc.perform(post("/api/rca-runs/" + runId + "/commands")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/rca-runs/" + runId + "/commands")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"type\":\"RESURRECT\",\"idempotencyKey\":\"k\",\"expectedRevision\":0}"))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/rca-runs/" + runId + "/commands")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"type\":\"CANCEL\",\"expectedRevision\":0}"))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/rca-runs/" + runId + "/commands")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"type\":\"CANCEL\",\"idempotencyKey\":\"k\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void unknownRunIs404WithoutPersistingCommand() throws Exception {
        mvc.perform(post("/api/rca-runs/" + UUID.randomUUID() + "/commands")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"type\":\"CANCEL\",\"idempotencyKey\":\"k\",\"expectedRevision\":0}"))
                .andExpect(status().isNotFound());
        assertThat(stores.commands.all()).isEmpty();
    }

    @Test
    void cancelAppliesAndStaleAndForbiddenMapToHttpFaces() throws Exception {
        mvc.perform(post("/api/rca-runs/" + runId + "/commands")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"type\":\"CANCEL\",\"idempotencyKey\":\"op-1\","
                                + "\"expectedRevision\":0,\"payload\":{\"reason\":\"误报\"}}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("APPLIED"))
                .andExpect(jsonPath("$.replayed").value(false));
        assertThat(stores.runs.findById(runId).orElseThrow().state())
                .isEqualTo(RcaRunState.CANCELLED);
        assertThat(stores.commands.all().get(0).actor()).isEqualTo("sre-li");

        // 幂等重放：原 commandId + replayed=true，零二次生效
        String commandId = stores.commands.all().get(0).id().toString();
        mvc.perform(post("/api/rca-runs/" + runId + "/commands")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"type\":\"CANCEL\",\"idempotencyKey\":\"op-1\",\"expectedRevision\":0}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.commandId").value(commandId))
                .andExpect(jsonPath("$.replayed").value(true));
        assertThat(stores.rcaEvents.all()).as("重放不重复落事件").hasSize(1);

        // 新幂等键 + 旧 revision → 409 REJECTED_STALE
        mvc.perform(post("/api/rca-runs/" + runId + "/commands")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"type\":\"HINT\",\"idempotencyKey\":\"op-2\",\"expectedRevision\":9,"
                                + "\"payload\":{\"text\":\"x\"}}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.state").value("REJECTED_STALE"));

        // 终态 Run 越权 → 403 REJECTED_FORBIDDEN
        UUID terminalRun = UUID.randomUUID();
        stores.runs.insert(new RcaRun(terminalRun, UUID.randomUUID(), 0,
                RunTrigger.INITIAL, RcaRunState.SUCCEEDED, Digest.sha256Of("inv2"),
                NOW.minus(Duration.ofMinutes(9)), NOW, NOW, NOW, null));
        mvc.perform(post("/api/rca-runs/" + terminalRun + "/commands")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"type\":\"FEEDBACK\",\"idempotencyKey\":\"op-3\",\"expectedRevision\":0}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.state").value("REJECTED_FORBIDDEN"));
    }

    /** EN-04 代理件：bundle/资格面零可用（本类只钉 CANCEL/HINT/FEEDBACK 的 HTTP 面） */
    private static final class NoopBundles
            implements com.objwww.pr.control.release.domain.repository.ConfigBundleRepository {
        @Override
        public long nextRevision() {
            return 1;
        }

        @Override
        public boolean insert(com.objwww.pr.control.release.domain.model.ConfigBundle b) {
            return false;
        }

        @Override
        public java.util.Optional<com.objwww.pr.control.release.domain.model.ConfigBundle>
        findByDigest(com.objwww.pr.shared.Digest digest) {
            return java.util.Optional.empty();
        }

        @Override
        public java.util.Optional<com.objwww.pr.shared.Digest> activeDigest() {
            return java.util.Optional.empty();
        }

        @Override
        public java.util.Optional<ActivePointer> findActivePointer() {
            return java.util.Optional.empty();
        }

        @Override
        public boolean activateQualified(com.objwww.pr.shared.Digest toDigest,
                long expectedActiveRevision, String by, Instant at) {
            return false;
        }
    }

    private static final class NoopQualifications
            implements com.objwww.pr.control.release.domain.repository.ReleaseQualificationRepository {
        @Override
        public boolean insert(
                com.objwww.pr.control.release.domain.model.ReleaseQualification q) {
            return false;
        }

        @Override
        public java.util.Optional<com.objwww.pr.control.release.domain.model.ReleaseQualification>
        findUnrevokedFor(com.objwww.pr.shared.Digest candidate) {
            return java.util.Optional.empty();
        }

        @Override
        public boolean revoke(UUID id, String by, String reason, Instant at) {
            return false;
        }
    }
}
