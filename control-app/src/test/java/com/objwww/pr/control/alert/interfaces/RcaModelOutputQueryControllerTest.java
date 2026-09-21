package com.objwww.pr.control.alert.interfaces;

import com.objwww.pr.control.alert.domain.agent.RcaModelOutputReadPort;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;
import java.util.UUID;

import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * /api/rca-runs/{runId}/model-outputs 契约（V167）：按 action_seq 返回每步
 * 输入/输出捕获投影；无捕获行侧 null 如实（不造空文本）；非法 runId 不 500。
 * RBAC 面 = /api/rca-runs/** 归 OPERATOR（SecurityConfig 链级覆盖，同
 * JevSelectionQueryController 律）。
 */
class RcaModelOutputQueryControllerTest {

    private final UUID runId = UUID.randomUUID();
    private final UUID callId = UUID.randomUUID();
    private StubReads stub;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        stub = new StubReads();
        mvc = MockMvcBuilders.standaloneSetup(new RcaModelOutputQueryController(stub))
                .build();
    }

    @Test
    void 步级投影_两侧捕获形状_掩敏侧带note() throws Exception {
        stub.rows = List.of(new RcaModelOutputReadPort.StepRow(callId, 3, "primary",
                "SUCCESS",
                new RcaModelOutputReadPort.CaptureSide("REDACTED", "掩文 prompt",
                        "d".repeat(64), 128, "masked"),
                new RcaModelOutputReadPort.CaptureSide("FULL", "决策JSON",
                        "e".repeat(64), 64, null)));

        mvc.perform(get("/api/rca-runs/{runId}/model-outputs", runId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.count").value(1))
                .andExpect(jsonPath("$.rows[0].modelCallId").value(callId.toString()))
                .andExpect(jsonPath("$.rows[0].actionSeq").value(3))
                .andExpect(jsonPath("$.rows[0].roleId").value("primary"))
                .andExpect(jsonPath("$.rows[0].state").value("SUCCESS"))
                .andExpect(jsonPath("$.rows[0].input.level").value("REDACTED"))
                .andExpect(jsonPath("$.rows[0].input.text").value("掩文 prompt"))
                .andExpect(jsonPath("$.rows[0].input.redactionNote").value("masked"))
                .andExpect(jsonPath("$.rows[0].output.level").value("FULL"))
                .andExpect(jsonPath("$.rows[0].output.text").value("决策JSON"))
                .andExpect(jsonPath("$.rows[0].output.redactionNote").value(nullValue()));
    }

    @Test
    void 无捕获行侧_null如实不造空文本() throws Exception {
        stub.rows = List.of(new RcaModelOutputReadPort.StepRow(callId, 0, "primary",
                "FAILED",
                new RcaModelOutputReadPort.CaptureSide("DIGEST_ONLY", null,
                        "f".repeat(64), 96, null),
                null));

        mvc.perform(get("/api/rca-runs/{runId}/model-outputs", runId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rows[0].input.level").value("DIGEST_ONLY"))
                .andExpect(jsonPath("$.rows[0].input.text").value(nullValue()))
                .andExpect(jsonPath("$.rows[0].input.digest").value("f".repeat(64)))
                .andExpect(jsonPath("$.rows[0].output").value(nullValue()));
    }

    @Test
    void 空run_空表如实() throws Exception {
        mvc.perform(get("/api/rca-runs/{runId}/model-outputs", runId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.count").value(0))
                .andExpect(jsonPath("$.rows").isArray());
    }

    @Test
    void 非法runId_error字段不500() throws Exception {
        mvc.perform(get("/api/rca-runs/{runId}/model-outputs", "not-a-uuid"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.error").value("runId 非法"));
    }

    /** 读面桩：行集由用例钉定 */
    private static final class StubReads implements RcaModelOutputReadPort {
        List<StepRow> rows = List.of();

        @Override
        public List<StepRow> byRun(UUID id, int limit) {
            return rows;
        }
    }
}
