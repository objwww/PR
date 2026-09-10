package com.objwww.pr.control.ops.dutybot.domain;

import com.objwww.pr.control.alert.domain.repository.IncidentQueryReader;
import com.objwww.pr.control.ops.duty.domain.DutyResolver;
import com.objwww.pr.control.ops.duty.domain.DutyScheduleSnapshot;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;

/**
 * UX-02 机器人回复组装：纯函数、确定性，只引用真实查询结果——
 * 无数据时如实说"查询无结果"，不编造（方案 §六）。
 *
 * <p>文本模板冻结于此（改文案 = Git 审查）；引用实体进 refs 供前端跳转，
 * 文本与 refs 同源同真。intent 名 = {@link BotIntent.Kind} 名（落 chat_message.intent）。
 */
public final class BotReplyComposer {

    /** 能力清单（HELP 与 UNSUPPORTED 共用同一份，不两份漂移） */
    public static final String CAPABILITIES = """
            我目前能回答（仿真演练面，数据全真）：
            1. 值班——"今晚谁值班" / "明天谁值班" / "2026-09-15 谁值班"（读真实值班表）
            2. 告警——"现在有哪些告警" / "已恢复的告警" / "服务 order-arena 的告警" / "这个告警什么情况 <incidentId>"（读真实 incident 投影）
            3. 通知——"通知发出去了吗"（读真实 notify_outbox 投递状态）
            4. 帮助——"帮助"
            仿真不回写任何真实业务状态（无认领/静默副作用）。""";

    /** 一条回复：text + 引用实体（可空表）+ intent 名 */
    public record Reply(String intent, String text, List<DutyBotStore.Ref> refs) {
        public Reply {
            refs = refs == null ? List.of() : List.copyOf(refs);
        }
    }

    private static final DateTimeFormatter HUMAN =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    private BotReplyComposer() {
    }

    // ------------------------------------------------------------------ 值班

    /**
     * 值班回复：date=null → 此刻（now）；否则该日排班 handoff 时刻（班次日界，
     * 时区随快照）。解析走 {@link DutyResolver} 真快照——层/顶班/通道链全真。
     */
    public static Reply duty(BotIntent intent, Instant now, DutyScheduleSnapshot snapshot) {
        Instant at = intent.date() == null ? now
                : intent.date().atTime(snapshot.handoffTime()).atZone(snapshot.zone()).toInstant();
        DutyResolver.Resolution r = DutyResolver.resolve(at, snapshot);
        String day = at.atZone(snapshot.zone()).toLocalDate()
                + " " + at(at, snapshot.zone()) + "（" + snapshot.zone().getId() + "）";

        StringBuilder text = new StringBuilder();
        if (r.onCall() == null && r.channelChain().isEmpty()) {
            // 排班层与通道皆空 = 值班表未配置/为空——如实无结果
            text.append("查询无结果：值班表 ").append(snapshot.name())
                    .append(" 未配置任何排班层与通知通道，").append(day).append(" 无法解析当班人。");
        } else if (r.onCall() == null) {
            text.append(day).append(" 无当班人（无排班层命中、无顶班）。")
                    .append("仅剩应急通道：").append(channels(r.channelChain())).append('。');
        } else {
            text.append(day).append(" 当班：").append(r.onCall())
                    .append(r.viaOverride() ? "（顶班）" : "（轮换）").append('。');
            if (!r.escalationChain().isEmpty()) {
                text.append("升级链：").append(String.join("、", r.escalationChain())).append('。');
            }
            text.append("通知通道：").append(channels(r.channelChain())).append('。');
        }
        return new Reply(BotIntent.Kind.DUTY_ONCALL.name(), text.toString(), List.of());
    }

    private static String channels(List<DutyScheduleSnapshot.Channel> chain) {
        List<String> parts = new ArrayList<>();
        for (DutyScheduleSnapshot.Channel c : chain) {
            parts.add(c.name() + "(" + c.platform() + ", P" + c.priority()
                    + (c.isFallback() ? ", 应急" : "") + ")");
        }
        return String.join(" → ", parts);
    }

    // ------------------------------------------------------------------ 告警

    /** 告警列表回复：total 来自过滤后真实计数；空页如实"查询无结果" */
    public static Reply incidents(BotIntent intent, IncidentQueryReader.IncidentPage page,
                                  Instant now) {
        String filter = "status=" + (intent.status() == null ? "全部" : intent.status())
                + (intent.service() == null ? "" : "，service=" + intent.service());
        if (page.items().isEmpty()) {
            return new Reply(BotIntent.Kind.INCIDENT_QUERY.name(),
                    "查询无结果：没有符合条件的告警（" + filter + "）。", List.of());
        }
        StringBuilder text = new StringBuilder();
        text.append("符合条件的告警共 ").append(page.total()).append(" 条（").append(filter)
                .append("），最新 ").append(page.items().size()).append(" 条：");
        int i = 1;
        for (IncidentQueryReader.IncidentRow row : page.items()) {
            text.append('\n').append(i++).append(". [").append(orDash(row.severity()))
                    .append("] ").append(orDash(row.alertname()))
                    .append("（").append(orDash(row.service())).append("）")
                    .append(row.status())
                    .append(" · 已持续 ").append(durationSince(row.episodeStartedAt(), now))
                    .append(" · incidentId=").append(row.incidentId());
        }
        return new Reply(BotIntent.Kind.INCIDENT_QUERY.name(), text.toString(),
                incidentRefs(page.items()));
    }

    /** 单条告警详情回复：incident 不存在如实"查询无结果" */
    public static Reply incidentDetail(UUID incidentId,
                                       IncidentQueryReader.IncidentDetail detail, Instant now) {
        if (detail == null) {
            return new Reply(BotIntent.Kind.INCIDENT_DETAIL.name(),
                    "查询无结果：incident " + incidentId + " 不存在（或 id 有误）。", List.of());
        }
        IncidentQueryReader.IncidentRow row = detail.row();
        StringBuilder text = new StringBuilder();
        text.append("告警 ").append(orDash(row.alertname())).append("：")
                .append("服务 ").append(orDash(row.service()))
                .append(" · 严重度 ").append(orDash(row.severity()))
                .append(" · 状态 ").append(row.status())
                .append(" · 分类 ").append(orDash(row.category()))
                .append("（").append(orDash(row.categorySource())).append("）")
                .append(" · 首次发生 ").append(at(row.episodeStartedAt(), ZoneId.of("Asia/Shanghai")))
                .append(" · 已持续 ").append(durationSince(row.episodeStartedAt(), now))
                .append(" · 收到事件 ").append(row.receivedCount()).append(" 条");
        if (row.currentRcaRunId() != null) {
            text.append("；RCA 调查 ").append(row.currentRcaRunId())
                    .append(" 状态 ").append(orDash(row.runState()));
        } else {
            text.append("；尚未发起 RCA 调查");
        }
        text.append("。incidentId=").append(incidentId);
        return new Reply(BotIntent.Kind.INCIDENT_DETAIL.name(), text.toString(),
                List.of(new DutyBotStore.Ref("incident", incidentId.toString())));
    }

    // ------------------------------------------------------------------ 通知

    /** 通知 outbox 回复：total=0 如实"查询无结果"；无失败行明说"无失败/重试中" */
    public static Reply notifyStatus(NotifyStatusReader.OutboxStatus status) {
        if (status.total() == 0) {
            return new Reply(BotIntent.Kind.NOTIFY_STATUS.name(),
                    "查询无结果：notify_outbox 当前没有任何通知行。", List.of());
        }
        StringBuilder text = new StringBuilder();
        text.append("notify_outbox 共 ").append(status.total()).append(" 行，状态分布：")
                .append(stateCounts(status.byState())).append('。');
        List<DutyBotStore.Ref> refs = new ArrayList<>();
        if (status.recentProblems().isEmpty()) {
            text.append("当前无失败（DEAD）或重试中（RETRY_WAIT）的投递行。");
        } else {
            text.append("失败/重试中最新 ").append(status.recentProblems().size()).append(" 条：");
            int i = 1;
            for (NotifyStatusReader.ProblemRow p : status.recentProblems()) {
                text.append('\n').append(i++).append(". ").append(p.state())
                        .append(" · channel=").append(p.channel())
                        .append(" · reportId=").append(p.reportId())
                        .append(" · 已试 ").append(p.attemptCount()).append(" 次")
                        .append(p.lastError() == null ? "" : " · lastError=" + p.lastError())
                        .append(" · outboxId=").append(p.id());
                refs.add(new DutyBotStore.Ref("notify_outbox", p.id().toString()));
            }
        }
        return new Reply(BotIntent.Kind.NOTIFY_STATUS.name(), text.toString(), refs);
    }

    // ------------------------------------------------------------------ 清单与兜底

    public static Reply help() {
        return new Reply(BotIntent.Kind.HELP.name(), CAPABILITIES, List.of());
    }

    public static Reply unsupported() {
        return new Reply(BotIntent.Kind.UNSUPPORTED.name(),
                "暂不支持这个问题。\n" + CAPABILITIES, List.of());
    }

    // ------------------------------------------------------------------ 内部

    /** 持续时长（诚实取整：未满 1 分钟称"不足 1 分钟"；start 晚于 now 按 0 计） */
    static String durationSince(Instant start, Instant now) {
        if (start == null) {
            return "未知";
        }
        Duration d = Duration.between(start, now);
        if (d.isNegative()) {
            d = Duration.ZERO;
        }
        long minutes = d.toMinutes();
        if (minutes < 1) {
            return "不足 1 分钟";
        }
        if (minutes < 60) {
            return minutes + " 分钟";
        }
        long hours = minutes / 60;
        if (hours < 48) {
            return hours + " 小时 " + (minutes % 60) + " 分";
        }
        return (hours / 24) + " 天 " + (hours % 24) + " 小时";
    }

    static String at(Instant instant, ZoneId zone) {
        return HUMAN.withZone(zone).format(instant);
    }

    /** 状态计数串（键字典序，确定性输出）：PENDING=2 · SENT=38 */
    static String stateCounts(Map<String, Long> byState) {
        StringBuilder sb = new StringBuilder();
        new TreeMap<>(byState).forEach((state, count) -> {
            if (sb.length() > 0) {
                sb.append(" · ");
            }
            sb.append(state).append('=').append(count);
        });
        return sb.toString();
    }

    static List<DutyBotStore.Ref> incidentRefs(List<IncidentQueryReader.IncidentRow> rows) {
        List<DutyBotStore.Ref> refs = new ArrayList<>();
        for (IncidentQueryReader.IncidentRow row : rows) {
            refs.add(new DutyBotStore.Ref("incident", row.incidentId().toString()));
        }
        return refs;
    }

    static String orDash(String value) {
        return value == null || value.isBlank() ? "-" : value;
    }
}
