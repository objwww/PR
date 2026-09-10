package com.objwww.pr.control.ops.duty.domain;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * M7-12 值班解析器锚点测试（AM7 §11 单元面 + 不变量 4 + 契约⑤）：
 * override > layer、layer_index 小者优先、空解析恒落 fallback、通道链过滤排序。
 */
class DutyResolverTest {

    private static final ZoneId CST = ZoneId.of("Asia/Shanghai");
    private static final LocalDate ANCHOR = LocalDate.of(2026, 9, 7);
    private static final LocalTime HANDOFF = LocalTime.of(9, 0);
    private static final Instant NOW =
            ANCHOR.plusDays(1).atTime(LocalTime.of(10, 0)).atZone(CST).toInstant();

    private static DutyScheduleSnapshot.Channel channel(String name, int priority,
                                                        boolean fallback, boolean enabled) {
        return new DutyScheduleSnapshot.Channel(UUID.randomUUID(), name, "DINGTALK",
                "NOTIFY_CHANNEL_" + name.toUpperCase() + "_WEBHOOK", null,
                priority, fallback, enabled);
    }

    private static DutyScheduleSnapshot snapshot(List<DutyScheduleSnapshot.Layer> layers,
                                                 List<DutyScheduleSnapshot.Override> overrides,
                                                 List<DutyScheduleSnapshot.Channel> channels) {
        return new DutyScheduleSnapshot(UUID.randomUUID(), "primary", 3L, NOW.minusSeconds(10),
                NOW.plusSeconds(60), CST, RotationMath.Rotation.DAILY, ANCHOR, HANDOFF,
                layers, overrides, channels);
    }

    @Test
    void overrideBeatsLayers() {
        var snap = snapshot(
                List.of(new DutyScheduleSnapshot.Layer(0, List.of("alice", "bob"))),
                List.of(new DutyScheduleSnapshot.Override("carol",
                        NOW.minusSeconds(3600), NOW.plusSeconds(3600))),
                List.of(channel("primary-bot", 1, false, true)));
        var r = DutyResolver.resolve(NOW, snap);
        assertThat(r.onCall()).isEqualTo("carol");
        assertThat(r.viaOverride()).isTrue();
    }

    @Test
    void expiredOverrideIsIgnored() {
        var snap = snapshot(
                List.of(new DutyScheduleSnapshot.Layer(0, List.of("alice", "bob"))),
                // 日轮换 day1 → position 1 → bob；窗口已结束
                List.of(new DutyScheduleSnapshot.Override("carol",
                        NOW.minusSeconds(7200), NOW.minusSeconds(3600))),
                List.of(channel("primary-bot", 1, false, true)));
        var r = DutyResolver.resolve(NOW, snap);
        assertThat(r.onCall()).isEqualTo("bob");
        assertThat(r.viaOverride()).isFalse();
    }

    @Test
    void smallerLayerIndexWinsAndDeeperLayersFormEscalationChain() {
        var snap = snapshot(
                List.of(new DutyScheduleSnapshot.Layer(1, List.of("boss")),
                        new DutyScheduleSnapshot.Layer(0, List.of("alice", "bob"))),
                List.of(),
                List.of(channel("bot", 1, false, true)));
        var r = DutyResolver.resolve(NOW, snap);
        assertThat(r.onCall()).isEqualTo("bob");
        assertThat(r.escalationChain()).containsExactly("boss");
    }

    @Test
    void emptyLayerIsSkipped() {
        var snap = snapshot(
                List.of(new DutyScheduleSnapshot.Layer(0, List.of()),
                        new DutyScheduleSnapshot.Layer(1, List.of("solo"))),
                List.of(),
                List.of(channel("bot", 1, false, true)));
        var r = DutyResolver.resolve(NOW, snap);
        assertThat(r.onCall()).isEqualTo("solo");
    }

    @Test
    void emptyResolutionFallsBackToSingleFallbackChannel() {
        // 不变量 4：告警无人可发不允许静默消失——空解析恒落 fallback 通道（契约⑤）
        var snap = snapshot(List.of(), List.of(),
                List.of(channel("primary-bot", 1, false, true),
                        channel("emergency", 9, true, true)));
        var r = DutyResolver.resolve(NOW, snap);
        assertThat(r.onCall()).isNull();
        assertThat(r.viaFallback()).isTrue();
        assertThat(r.channelChain()).hasSize(1);
        assertThat(r.channelChain().get(0).name()).isEqualTo("emergency");
    }

    @Test
    void channelChainFiltersDisabledAndSortsByPriority() {
        var snap = snapshot(
                List.of(new DutyScheduleSnapshot.Layer(0, List.of("alice"))),
                List.of(),
                List.of(channel("secondary", 2, false, true),
                        channel("disabled-bot", 3, false, false),
                        channel("primary-bot", 1, false, true)));
        var r = DutyResolver.resolve(NOW, snap);
        assertThat(r.channelChain())
                .extracting(DutyScheduleSnapshot.Channel::name)
                .containsExactly("primary-bot", "secondary");
        assertThat(r.viaFallback()).isFalse();
    }

    @Test
    void noFallbackConfiguredWithEmptyResolutionYieldsEmptyChain() {
        // 诚实面：连 fallback 都没配 → 空链（派发侧必须显式记账，不许静默丢）
        var snap = snapshot(List.of(), List.of(),
                List.of(channel("primary-bot", 1, false, true)));
        var r = DutyResolver.resolve(NOW, snap);
        assertThat(r.onCall()).isNull();
        assertThat(r.viaFallback()).isTrue();
        assertThat(r.channelChain()).isEmpty();
    }
}
