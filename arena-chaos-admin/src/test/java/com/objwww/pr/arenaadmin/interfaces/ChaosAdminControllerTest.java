package com.objwww.pr.arenaadmin.interfaces;

import com.objwww.pr.arenaadmin.application.ChaosActivationService;
import com.objwww.pr.arenaadmin.infrastructure.persistence.PostgresChaosAdminStore.Activation;
import com.objwww.pr.arenaadmin.infrastructure.persistence.PostgresChaosAdminStore.BackfillResult;
import com.objwww.pr.arenaadmin.infrastructure.persistence.PostgresChaosAdminStore.GtFields;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.Map;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 管理 API 契约（standalone MockMvc）：token fail-closed（未配置 503/错凭证 401）、
 * 校验失败 400、CAS 未中 409、激活 201、回填 202。
 */
class ChaosAdminControllerTest {

    private com.objwww.pr.arenaadmin.infrastructure.persistence.PostgresChaosAdminStore store;
    private ChaosActivationService service;
    private MockMvc mvcWithToken;
    private MockMvc mvcNoToken;

    private static final java.util.Map<String, String> LABELS = java.util.Map.of(
            "alertname", "ArenaOrderStuck", "fault_type", "F3", "service", "order-arena");

    @BeforeEach
    void setUp() {
        store = mock(com.objwww.pr.arenaadmin.infrastructure.persistence.PostgresChaosAdminStore.class);
        // 真服务（校验逻辑在测）；只 mock 存储层
        service = new ChaosActivationService(store, 30, 7200);
        mvcWithToken = MockMvcBuilders.standaloneSetup(
                        new ChaosAdminController(service, "secret-token"))
                .build();
        mvcNoToken = MockMvcBuilders.standaloneSetup(
                        new ChaosAdminController(service, ""))
                .build();
    }

    private static final String BODY = """
            {
              "scenarioId": "f3-e2e-001",
              "target": "chaos-f3e2e",
              "ttlSeconds": 600,
              "operator": "it",
              "configDigest": "%s",
              "groundTruth": {"schemaVersion": 1, "datasetVersion": "ds-1",
                              "payloadDigest": "%s", "applicableScope": "arena"},
              "alertLabels": {"alertname": "ArenaOrderStuck", "fault_type": "F3",
                              "service": "order-arena"},
              "ruleDigest": "%s"
            }
            """.formatted("a".repeat(64), "b".repeat(64), "c".repeat(64));

    @Test
    void 未配置token_503拒绝一切() throws Exception {
        mvcNoToken.perform(post("/chaos/F3/on")
                        .contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isServiceUnavailable());
    }

    @Test
    void 错token_401() throws Exception {
        mvcWithToken.perform(post("/chaos/F3/on")
                        .header("X-Admin-Token", "wrong")
                        .contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void 激活成功_201带指纹() throws Exception {
        when(store.activate("f3-e2e-001", "F3", "chaos-f3e2e", 600, "it",
                "a".repeat(64),
                new com.objwww.pr.arenaadmin.infrastructure.persistence.PostgresChaosAdminStore.GtFields(
                        1, "ds-1", "b".repeat(64), "arena"),
                LABELS,
                "c".repeat(64)))
                .thenReturn(new Activation(java.util.UUID.randomUUID(),
                        java.util.UUID.randomUUID(), "f3-e2e-001", "F3",
                        "f95e79c26f0e7b4c"));
        mvcWithToken.perform(post("/chaos/F3/on")
                        .header("X-Admin-Token", "secret-token")
                        .contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.alertFingerprint").value("f95e79c26f0e7b4c"))
                .andExpect(jsonPath("$.generation").value(0));
    }

    @Test
    void TTL越界_400() throws Exception {
        String bad = BODY.replace("\"ttlSeconds\": 600", "\"ttlSeconds\": 5");
        mvcWithToken.perform(post("/chaos/F3/on")
                        .header("X-Admin-Token", "secret-token")
                        .contentType(MediaType.APPLICATION_JSON).content(bad))
                .andExpect(status().isBadRequest());
    }

    @Test
    void CAS未中_409() throws Exception {
        when(store.casRecovering("f3-e2e-001", 7L)).thenReturn(false);
        mvcWithToken.perform(post("/chaos/F3/off")
                        .header("X-Admin-Token", "secret-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"scenarioId": "f3-e2e-001", "expectedGeneration": 7}
                                """))
                .andExpect(status().isConflict());
    }

    @Test
    void off命中_202进入恢复() throws Exception {
        when(store.casRecovering("f3-e2e-001", 0L)).thenReturn(true);
        mvcWithToken.perform(post("/chaos/F3/off")
                        .header("X-Admin-Token", "secret-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"scenarioId": "f3-e2e-001", "expectedGeneration": 0}
                                """))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.state").value("RECOVERING"));
    }

    @Test
    void 回填_旧代事件拒绝409() throws Exception {
        when(store.backfillIncident("f3-e2e-001", "INC-1", 3L, "run-1", "rep-1"))
                .thenReturn(null);
        mvcWithToken.perform(post("/chaos/scenario-map/backfill")
                        .header("X-Admin-Token", "secret-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"scenarioId": "f3-e2e-001", "incidentId": "INC-1",
                                 "incidentGeneration": 3, "runId": "run-1", "reportId": "rep-1"}
                                """))
                .andExpect(status().isConflict());
    }

    @Test
    void 状态未知场景_404() throws Exception {
        when(store.findSession("nope")).thenReturn(java.util.Optional.empty());
        mvcWithToken.perform(get("/chaos/status")
                        .header("X-Admin-Token", "secret-token")
                        .param("scenarioId", "nope"))
                .andExpect(status().isNotFound());
    }
}
