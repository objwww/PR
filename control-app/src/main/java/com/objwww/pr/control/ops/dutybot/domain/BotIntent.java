package com.objwww.pr.control.ops.dutybot.domain;

import java.time.LocalDate;
import java.util.UUID;

/**
 * UX-02 仿真机器人意图（识别结果值对象；docs/告警-UX02-仿真机器人-v1.md §一）。
 *
 * <p>参数面（均为可空，按 kind 取用）：
 * <ul>
 *   <li>{@code date}——DUTY_ONCALL 的目标日（null=此刻）；解析时刻 = 该日排班
 *       handoff 时刻（班次日界），时区随值班表快照；</li>
 *   <li>{@code status}——INCIDENT_QUERY 的 incident 状态过滤（FIRING/RESOLVED/
 *       null=全部；未提及时默认 FIRING，由识别器落值）；</li>
 *   <li>{@code service}——INCIDENT_QUERY 的服务过滤（"服务 xxx"显式词元）；</li>
 *   <li>{@code incidentId}——INCIDENT_DETAIL 的目标 incident（消息内 UUID 词元）。</li>
 * </ul>
 */
public record BotIntent(Kind kind, LocalDate date, String status, String service,
                        UUID incidentId) {

    public enum Kind {
        /** 值班查询（当前/某日谁值班） */
        DUTY_ONCALL,
        /** 告警列表查询（按状态/服务过滤） */
        INCIDENT_QUERY,
        /** 单条告警详情（消息内带 incidentId） */
        INCIDENT_DETAIL,
        /** 通知 outbox 投递状态 */
        NOTIFY_STATUS,
        /** 能力清单 */
        HELP,
        /** 未识别（回复"暂不支持"+可问清单） */
        UNSUPPORTED
    }

    public static BotIntent duty(LocalDate date) {
        return new BotIntent(Kind.DUTY_ONCALL, date, null, null, null);
    }

    public static BotIntent incidents(String status, String service) {
        return new BotIntent(Kind.INCIDENT_QUERY, null, status, service, null);
    }

    public static BotIntent incidentDetail(UUID incidentId) {
        return new BotIntent(Kind.INCIDENT_DETAIL, null, null, null, incidentId);
    }

    public static BotIntent notifyStatus() {
        return new BotIntent(Kind.NOTIFY_STATUS, null, null, null, null);
    }

    public static BotIntent help() {
        return new BotIntent(Kind.HELP, null, null, null, null);
    }

    public static BotIntent unsupported() {
        return new BotIntent(Kind.UNSUPPORTED, null, null, null, null);
    }
}
