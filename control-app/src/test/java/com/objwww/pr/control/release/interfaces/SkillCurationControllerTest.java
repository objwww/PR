package com.objwww.pr.control.release.interfaces;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.objwww.pr.control.release.application.SkillCuratorService;
import com.objwww.pr.control.release.domain.model.SkillCandidate;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * CL-09 人工 curation 入口单测（standalone MockMvc，零 Spring 上下文）：
 * LLM_CURATE 如实 501、缺参/非法 runId 400、来源契约拒绝 422 零落库、TEMPLATE
 * 提炼 200。服务逻辑（来源契约/sourceDigest 幂等/S02 隔离）在
 * SkillCuratorServiceTest 覆盖，此处只测 HTTP 面。
 */
class SkillCurationControllerTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Instant NOW = Instant.parse("2026-09-13T04:00:00Z");

    private final SkillCuratorService curator = mock(SkillCuratorService.class);
    private final MockMvc mvc = MockMvcBuilders
            .standaloneSetup(new SkillCurationController(curator)).build();

    @BeforeEach
    void setUp() {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("curator-operator", null,
                        List.of()));
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    private static String json(Object... pairs) {
        Map<String, Object> body = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            body.put((String) pairs[i], pairs[i + 1]);
        }
        try {
            return MAPPER.writeValueAsString(body);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    @DisplayName("LLM_CURATE 未就绪：501 如实（不以静态样例伪装）")
    void llmCurateNotReady() throws Exception {
        mvc.perform(post("/api/skill-curations").contentType("application/json")
                        .content(json("runId", UUID.randomUUID().toString(),
                                "name", "s", "mode", "LLM_CURATE")))
                .andExpect(status().isNotImplemented())
                .andExpect(jsonPath("$.error").value(
                        org.hamcrest.Matchers.containsString("仅 TEMPLATE")));
    }

    @Test
    @DisplayName("缺 runId/name：400；runId 非法：400")
    void missingOrMalformedFieldsRejected() throws Exception {
        mvc.perform(post("/api/skill-curations").contentType("application/json")
                        .content(json("name", "s")))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/skill-curations").contentType("application/json")
                        .content(json("runId", "not-a-uuid", "name", "s")))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("来源契约拒绝：422 带 refusal（零落库）")
    void sourceContractRefusalIs422() throws Exception {
        when(curator.curate(any())).thenReturn(SkillCuratorService.Verdict.refused(
                "来源契约：run 尚未终态（RUNNING）"));
        mvc.perform(post("/api/skill-curations").contentType("application/json")
                        .content(json("runId", UUID.randomUUID().toString(), "name", "s")))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.refusal").value(
                        org.hamcrest.Matchers.containsString("尚未终态")));
    }

    @Test
    @DisplayName("TEMPLATE 提炼：200 返回候选 id/状态/mode")
    void templateCurateSucceeds() throws Exception {
        SkillCandidate row = new SkillCandidate(UUID.randomUUID(), "s",
                UUID.randomUUID(), "a".repeat(64), SkillCandidate.VERIFIED,
                "b".repeat(64), SkillCandidate.ST_DRAFT, null, "cur", null, null,
                null, null, null, NOW, NOW);
        when(curator.curate(any())).thenReturn(new SkillCuratorService.Verdict(row, null));
        mvc.perform(post("/api/skill-curations").contentType("application/json")
                        .content(json("runId", UUID.randomUUID().toString(), "name", "s")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.mode").value("TEMPLATE"))
                .andExpect(jsonPath("$.status").value("DRAFT"))
                .andExpect(jsonPath("$.candidateId").value(row.id().toString()));
    }
}
