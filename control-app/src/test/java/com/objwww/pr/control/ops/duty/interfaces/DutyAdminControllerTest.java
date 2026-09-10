package com.objwww.pr.control.ops.duty.interfaces;

import com.objwww.pr.control.ops.duty.domain.DutyAdminStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * M7-15 管理面校验分支锚：必填/平台枚举/override 正区间 400、
 * 唯一冲突（uq priority / partial unique fallback）→ 409、
 * 行不在场 404、排班首启 exists:false（契约⑤：首启无快照不炸）。
 */
class DutyAdminControllerTest {

    /** 极简假件：默认成功路径，冲突场景用 Mockito 单点改写 */
    private final DutyAdminStore admin = mock(DutyAdminStore.class);
    private MockMvc mvc;

    private final UUID memberId = UUID.randomUUID();
    private final UUID scheduleId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        when(admin.insertMember("alice", null)).thenReturn(
                new DutyAdminStore.MemberView(memberId, "alice", null, true));
        when(admin.insertChannel("bot", "DINGTALK", "K_WEBHOOK", null, 1, false, true))
                .thenReturn(new DutyAdminStore.ChannelView(UUID.randomUUID(), "bot",
                        "DINGTALK", "K_WEBHOOK", null, 1, false, true));
        when(admin.insertChannel("dup", "DINGTALK", "K_WEBHOOK", null, 1, false, true))
                .thenThrow(new DataIntegrityViolationException("uq"));
        when(admin.insertLayer(scheduleId, List.of(memberId))).thenReturn(
                new DutyAdminStore.LayerView(UUID.randomUUID(), 0, List.of(memberId)));
        when(admin.insertOverride(any(), any(), any(), any(), any())).thenReturn(
                new DutyAdminStore.OverrideView(UUID.randomUUID(), memberId,
                        Instant.parse("2026-09-12T09:00:00Z"),
                        Instant.parse("2026-09-12T18:00:00Z"), null));
        when(admin.updateMember(any(), any(), any())).thenReturn(true);
        when(admin.replaceLayerMembers(any(), any())).thenReturn(true);
        when(admin.deleteLayer(any())).thenReturn(true);
        when(admin.updateSchedule(any(), any(), any(), any(), any(), any())).thenReturn(true);
        when(admin.deleteOverride(any())).thenReturn(true);
        when(admin.currentSchedule()).thenReturn(null);
        when(admin.listMembers()).thenReturn(List.of());
        when(admin.listChannels()).thenReturn(List.of());
        when(admin.listLayers()).thenReturn(List.of());
        when(admin.listOverrides()).thenReturn(List.of());
        mvc = MockMvcBuilders.standaloneSetup(new DutyAdminController(admin)).build();
    }

    @Test
    void memberCreateValidatesRequiredName() throws Exception {
        mvc.perform(post("/api/duty/members")
                        .contentType("application/json").content("{\"name\":\"alice\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ok").value(true))
                .andExpect(jsonPath("$.member.name").value("alice"));
        mvc.perform(post("/api/duty/members")
                        .contentType("application/json").content("{\"name\":\" \"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.ok").value(false));
    }

    @Test
    void channelCreateValidatesFieldsAndMapsConflictTo409() throws Exception {
        mvc.perform(post("/api/duty/channels").contentType("application/json").content(
                        "{\"name\":\"bot\",\"platform\":\"DINGTALK\",\"envKeyWebhook\":\"K_WEBHOOK\","
                                + "\"priority\":1}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.channel.envKeyWebhook").value("K_WEBHOOK"));
        mvc.perform(post("/api/duty/channels").contentType("application/json").content(
                        "{\"name\":\"x\",\"platform\":\"DINGTALK\",\"priority\":1}"))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/duty/channels").contentType("application/json").content(
                        "{\"name\":\"x\",\"platform\":\"SLACK\",\"envKeyWebhook\":\"K\",\"priority\":1}"))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/duty/channels").contentType("application/json").content(
                        "{\"name\":\"dup\",\"platform\":\"DINGTALK\",\"envKeyWebhook\":\"K_WEBHOOK\","
                                + "\"priority\":1}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value(
                        "priority/fallback 冲突（每优先级一通道、fallback 恰一行）"));
    }

    @Test
    void overrideRequiresPositiveWindow() throws Exception {
        mvc.perform(post("/api/duty/overrides").contentType("application/json").content(String.format(
                        "{\"scheduleId\":\"%s\",\"memberId\":\"%s\","
                                + "\"startsAt\":\"2026-09-12T09:00:00Z\","
                                + "\"endsAt\":\"2026-09-12T18:00:00Z\"}", scheduleId, memberId)))
                .andExpect(status().isOk());
        mvc.perform(post("/api/duty/overrides").contentType("application/json").content(String.format(
                        "{\"scheduleId\":\"%s\",\"memberId\":\"%s\","
                                + "\"startsAt\":\"2026-09-12T18:00:00Z\","
                                + "\"endsAt\":\"2026-09-12T18:00:00Z\"}", scheduleId, memberId)))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/duty/overrides").contentType("application/json").content(
                        "{\"memberId\":\"" + memberId + "\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void scheduleAndLayerMutationsCarryMissingRowAs404() throws Exception {
        // 首启：无排班 → exists:false（契约⑤ 首启无快照）
        mvc.perform(get("/api/duty/schedule"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.exists").value(false));

        when(admin.currentSchedule()).thenReturn(new DutyAdminStore.ScheduleView(
                scheduleId, "primary", "Asia/Shanghai", "WEEKLY", "2026-09-07", "09:00", 3L));
        when(admin.listLayers()).thenReturn(List.of(
                new DutyAdminStore.LayerView(UUID.randomUUID(), 0, List.of(memberId))));
        when(admin.listOverrides()).thenReturn(List.of(new DutyAdminStore.OverrideView(
                UUID.randomUUID(), memberId, Instant.parse("2026-09-12T09:00:00Z"),
                Instant.parse("2026-09-12T18:00:00Z"), "假期代班")));
        mvc.perform(get("/api/duty/schedule"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.exists").value(true))
                .andExpect(jsonPath("$.schedule.scheduleVersion").value(3))
                .andExpect(jsonPath("$.layers[0].memberIds[0]").value(memberId.toString()))
                .andExpect(jsonPath("$.overrides[0].reason").value("假期代班"));

        mvc.perform(post("/api/duty/schedule/{id}/layers", scheduleId)
                        .contentType("application/json")
                        .content("{\"memberIds\":[]}"))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/duty/schedule/{id}/layers", scheduleId)
                        .contentType("application/json")
                        .content("{\"memberIds\":[\"" + memberId + "\"]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.layer.layerIndex").value(0));

        when(admin.deleteLayer(any())).thenReturn(false);
        mvc.perform(delete("/api/duty/layers/" + UUID.randomUUID()))
                .andExpect(status().isNotFound());
        when(admin.updateSchedule(any(), any(), any(), any(), any(), any())).thenReturn(false);
        mvc.perform(put("/api/duty/schedule/" + scheduleId)
                        .contentType("application/json").content("{\"name\":\"x\"}"))
                .andExpect(status().isNotFound());
    }
}
