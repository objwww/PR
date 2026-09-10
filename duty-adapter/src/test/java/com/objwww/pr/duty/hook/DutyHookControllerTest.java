package com.objwww.pr.duty.hook;

import com.objwww.pr.duty.snapshot.SnapshotRefresher;
import com.objwww.pr.duty.snapshot.SnapshotState;
import com.objwww.pr.duty.webhook.DutyWebhookClient;
import com.objwww.pr.duty.writeback.WriteBackClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 接收入口锚（M7-17）：bearer 验签 401、不可解析 400、通道链降级至次通道、
 * 通道链尽 503、台账回写必发（投递成败不影响回写）、GATUS 事件字段透传。
 */
class DutyHookControllerTest {

    private static final String TOKEN = "gatus-line-token";

    /** 通道结果可编排的假发客户端 */
    static final class ScriptedWebhooks extends DutyWebhookClient {
        final List<String> sentTo = new ArrayList<>();
        final List<String> failOn = new ArrayList<>();

        ScriptedWebhooks() {
            super(null);
        }

        @Override
        public SendResult send(SnapshotState.Channel channel, String text) {
            sentTo.add(channel.name());
            if (failOn.contains(channel.name())) {
                return new SendResult(false, channel.name(), "errcode_45009:限流");
            }
            return new SendResult(true, channel.name(), "ok");
        }
    }

    /** 回写记录假件 */
    static final class RecordingWriteBack extends WriteBackClient {
        final List<Event> pushed = new ArrayList<>();

        RecordingWriteBack(Path dataDir) {
            super(null, "http://unused", "tok", dataDir.toString());
        }

        @Override
        public void push(Event event) {
            pushed.add(event);
        }
    }

    /** 快照 stub（current() 固定返回） */
    static final class FixedSnapshot extends SnapshotRefresher {
        private final SnapshotState snapshot;

        FixedSnapshot(SnapshotState snapshot, Path dataDir) {
            super(null, "http://unused", "tok", dataDir.toString());
            this.snapshot = snapshot;
        }

        @Override
        public SnapshotState current() {
            return snapshot;
        }
    }

    @TempDir
    Path dataDir;

    private ScriptedWebhooks webhooks;
    private RecordingWriteBack writeBack;
    private MockMvc mvc;

    private SnapshotState snapshotOf(List<SnapshotState.Channel> channels) {
        return new SnapshotState("alice", false, 7L,
                java.time.Instant.parse("2026-09-10T08:00:00Z"),
                java.time.Instant.parse("2026-09-10T08:01:30Z"), channels);
    }

    @BeforeEach
    void setUp() {
        webhooks = new ScriptedWebhooks();
        writeBack = new RecordingWriteBack(dataDir);
    }

    private void withSnapshot(SnapshotState snapshot) {
        mvc = MockMvcBuilders.standaloneSetup(new DutyHookController(
                new FixedSnapshot(snapshot, dataDir), webhooks, writeBack, TOKEN)).build();
    }

    private static final String FIRING_BODY = """
            {"status":"TRIGGERED","endpoint":"RCA_SYSTEM_control_health",
             "group":"control-plane","target":"http://195:8080/actuator/health",
             "description":"3 连败","errors":"Get "http://195:8080": refused"}""";

    @Test
    @DisplayName("伪 bearer 401；空 body 400；有效事件+首通道成功 → 202 + 回写一行")
    void bearerAndHappyPath() throws Exception {
        withSnapshot(snapshotOf(List.of(
                new SnapshotState.Channel("primary-bot", "DINGTALK", 1, false,
                        "K_WEBHOOK", "K_SECRET"))));

        mvc.perform(post("/hook/gatus")
                        .header("Authorization", "Bearer wrong")
                        .contentType("application/json").content("{}"))
                .andExpect(status().isUnauthorized());
        mvc.perform(post("/hook/gatus")
                        .header("Authorization", "Bearer " + TOKEN)
                        .contentType("application/json").content("not parseable"))
                .andExpect(status().isBadRequest());

        mvc.perform(post("/hook/gatus")
                        .header("Authorization", "Bearer " + TOKEN)
                        .contentType("application/json").content(FIRING_BODY))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.delivered").value(true))
                .andExpect(jsonPath("$.channel").value("primary-bot"));

        assertThat(webhooks.sentTo).containsExactly("primary-bot");
        assertThat(writeBack.pushed).hasSize(1);
        WriteBackClient.Event event = writeBack.pushed.get(0);
        assertThat(event.eventStatus()).isEqualTo("firing");
        assertThat(event.title()).contains("RCA_SYSTEM_control_health");
        assertThat(event.groupKey()).isEqualTo("gatus/control-plane");
        assertThat(event.labels()).containsEntry("endpoint", "RCA_SYSTEM_control_health");
        assertThat(event.triggeredAt()).isNotBlank();
    }

    @Test
    @DisplayName("首通道业务码失败 → 降级次通道送达（通道链按序迭代）")
    void degradesToNextChannelOnBusinessCodeFailure() throws Exception {
        webhooks.failOn.add("primary-bot");
        withSnapshot(snapshotOf(List.of(
                new SnapshotState.Channel("primary-bot", "DINGTALK", 1, false,
                        "K_WEBHOOK", "K_SECRET"),
                new SnapshotState.Channel("fallback-bot", "WECOM", 9, true,
                        "K_WEBHOOK_FB", null))));

        mvc.perform(post("/hook/gatus")
                        .header("Authorization", "Bearer " + TOKEN)
                        .contentType("application/json").content(FIRING_BODY))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.channel").value("fallback-bot"));

        assertThat(webhooks.sentTo).containsExactly("primary-bot", "fallback-bot");
        assertThat(writeBack.pushed).hasSize(1);   // 投递降级不影响回写
    }

    @Test
    @DisplayName("快照缺席（null）或通道链尽 → 503（Gatus 日志面可见）+ 回写仍落")
    void exhaustedChainReturns503AndStillWritesBack() throws Exception {
        withSnapshot(null);
        mvc.perform(post("/hook/gatus")
                        .header("Authorization", "Bearer " + TOKEN)
                        .contentType("application/json").content(FIRING_BODY))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.detail").value("no_snapshot"));
        assertThat(writeBack.pushed).hasSize(1);

        webhooks.failOn.add("primary-bot");
        withSnapshot(snapshotOf(List.of(
                new SnapshotState.Channel("primary-bot", "DINGTALK", 1, false,
                        "K_WEBHOOK", "K_SECRET"))));
        mvc.perform(post("/hook/gatus")
                        .header("Authorization", "Bearer " + TOKEN)
                        .contentType("application/json").content(FIRING_BODY))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.detail").value("errcode_45009:限流"));
        assertThat(writeBack.pushed).hasSize(2);
    }

    @Test
    @DisplayName("RESOLVED 事件：eventStatus=resolved（恢复同报）")
    void resolvedEventsCarryResolvedStatus() throws Exception {
        withSnapshot(snapshotOf(List.of(
                new SnapshotState.Channel("primary-bot", "DINGTALK", 1, false,
                        "K_WEBHOOK", "K_SECRET"))));

        mvc.perform(post("/hook/gatus")
                        .header("Authorization", "Bearer " + TOKEN)
                        .contentType("application/json").content("""
                                {"status":"RESOLVED","endpoint":"RCA_SYSTEM_control_health",
                                 "group":"control-plane","target":"t","description":"2 连胜","errors":""}"""))
                .andExpect(status().isAccepted());

        assertThat(writeBack.pushed).hasSize(1);
        assertThat(writeBack.pushed.get(0).eventStatus()).isEqualTo("resolved");
    }
}
