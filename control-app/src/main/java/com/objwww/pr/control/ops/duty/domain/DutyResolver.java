package com.objwww.pr.control.ops.duty.domain;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 值班解析器（M7-12 纯函数；AM7 §3 DutyResolver）：
 * 时刻 + 排班快照 → 当班成员 + 通道优先级链。不查库、不触网、时钟由参数传入
 * （B-41 律：解析不自带时间源，测试可冻结）。
 *
 * <p>优先级：override（窗口内、startsAt 最早者）> layer（layerIndex 小者）；
 * 空解析恒落 fallback 通道（不变量 4——「告警无人可发」不允许静默消失）；
 * 连 fallback 都未配置时返回空链，由派发侧显式记账（诚实面，不许静默丢）。
 */
public final class DutyResolver {

    /** 解析结局：onCall 可空（空解析）；escalationChain 是更深层的当班成员（信息面） */
    public record Resolution(String onCall, boolean viaOverride, List<String> escalationChain,
                             List<DutyScheduleSnapshot.Channel> channelChain,
                             boolean viaFallback) {
    }

    private DutyResolver() {
    }

    public static Resolution resolve(Instant now, DutyScheduleSnapshot snapshot) {
        DutyScheduleSnapshot.Override active = snapshot.overrides().stream()
                .filter(o -> !now.isBefore(o.startsAt()) && now.isBefore(o.endsAt()))
                .min(Comparator.comparing(DutyScheduleSnapshot.Override::startsAt))
                .orElse(null);

        String onCall = null;
        boolean viaOverride = false;
        List<String> escalation = new ArrayList<>();
        if (active != null) {
            onCall = active.memberName();
            viaOverride = true;
        }
        for (DutyScheduleSnapshot.Layer layer : snapshot.layers().stream()
                .sorted(Comparator.comparingInt(DutyScheduleSnapshot.Layer::layerIndex))
                .toList()) {
            if (layer.memberNames().isEmpty()) {
                continue;
            }
            int pos = RotationMath.position(snapshot.rotation(), snapshot.anchorDate(),
                    snapshot.handoffTime(), snapshot.zone(), now, layer.memberNames().size());
            String member = layer.memberNames().get(pos);
            if (onCall == null) {
                onCall = member;
            } else {
                escalation.add(member);
            }
        }

        List<DutyScheduleSnapshot.Channel> enabled = snapshot.channels().stream()
                .filter(DutyScheduleSnapshot.Channel::enabled)
                .sorted(Comparator.comparingInt(DutyScheduleSnapshot.Channel::priority))
                .toList();

        boolean viaFallback = onCall == null;
        if (viaFallback) {
            List<DutyScheduleSnapshot.Channel> fallbackOnly = enabled.stream()
                    .filter(DutyScheduleSnapshot.Channel::isFallback)
                    .toList();
            return new Resolution(null, false, escalation, fallbackOnly, true);
        }
        return new Resolution(onCall, viaOverride, escalation, enabled, false);
    }
}
