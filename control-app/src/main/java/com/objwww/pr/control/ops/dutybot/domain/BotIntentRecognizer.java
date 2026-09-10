package com.objwww.pr.control.ops.dutybot.domain;

import java.time.LocalDate;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * UX-02 意图识别器：纯函数、确定性、规则表冻结（首期不接 LLM——方案 §六 /
 * UX 迭代方案 §二"机器人不做真 LLM 应答"）。
 *
 * <p>裁决律（沿 IncidentClassifier 纪律）：
 * <ul>
 *   <li>规则表序即冻结优先级，首条命中即裁决；ruleId 稳定进消息 intent 列，
 *       改词表 = 改本表 + Git 审查；</li>
 *   <li>输入先截 {@link #MAX_INPUT_CHARS} 字符再匹配——复杂度天然有界；
 *       仅关键词子串 + 三个有界小正则（ISO 日期 / 中文日期 / 服务词元 /
 *       UUID），作用面都在截断后的输入上；</li>
 *   <li>零命中 → UNSUPPORTED（回复"暂不支持"+可问清单），不猜不编。</li>
 * </ul>
 *
 * <p>相对日期词（今天/明天/昨天/后天）由调用方传入 today（运维时区当日，
 * 默认 Asia/Shanghai，与 duty_schedule 默认 timezone 对齐）。
 */
public final class BotIntentRecognizer {

    /** 规则表版本（Git 审查演进；不持久化，仅文档锚） */
    public static final String RULE_VERSION = "ux02-intents-v1";

    /** 单条消息匹配输入上限（字符）——超长截断后匹配，防失控输入放大匹配成本 */
    static final int MAX_INPUT_CHARS = 512;

    private static final Pattern ISO_DATE = Pattern.compile("(\\d{4})-(\\d{1,2})-(\\d{1,2})");
    private static final Pattern CN_DATE = Pattern.compile("(\\d{1,2})月(\\d{1,2})[日号]");
    private static final Pattern SERVICE_TOKEN =
            Pattern.compile("服务\\s*[:：=]?\\s*([A-Za-z0-9][A-Za-z0-9._-]{0,62})");
    private static final Pattern UUID_TOKEN = Pattern.compile(
            "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");

    /** 关键词表（表序=优先级之外的组内并列，任一词命中即成立） */
    private static final List<String> HELP_WORDS =
            List.of("帮助", "help", "你会什么", "能做什么", "可以做什么", "怎么用", "指令", "命令清单");
    private static final List<String> DUTY_WORDS =
            List.of("值班", "当班", "谁值", "oncall", "on-call", "on call");
    private static final List<String> NOTIFY_WORDS =
            List.of("通知", "投递", "送达", "outbox", "发出去", "发出去了");
    private static final List<String> INCIDENT_WORDS =
            List.of("告警", "alert", "报警", "故障", "incident", "什么情况", "怎么回事");

    private BotIntentRecognizer() {
    }

    /**
     * 识别（null/空白 → UNSUPPORTED；today = 相对日期词的锚）。
     *
     * <p>优先级：HELP &gt; DUTY_ONCALL &gt; NOTIFY_STATUS &gt; INCIDENT_DETAIL &gt;
     * INCIDENT_QUERY &gt; UNSUPPORTED。值班词最具体（"值班"语义不被告警词吞掉）；
     * 通知词先于告警词（"告警通知发出去没"是投递面问题）；UUID 词元只在告警语境
     * 下升级为详情查询（裸发 UUID 不算——零上下文不猜）。
     */
    public static BotIntent recognize(String raw, LocalDate today) {
        if (raw == null || raw.isBlank() || today == null) {
            return BotIntent.unsupported();
        }
        String bounded = raw.length() > MAX_INPUT_CHARS
                ? raw.substring(0, MAX_INPUT_CHARS) : raw;
        String text = bounded.toLowerCase(Locale.ROOT);

        if (containsAny(text, HELP_WORDS)) {
            return BotIntent.help();
        }
        if (containsAny(text, DUTY_WORDS)) {
            return BotIntent.duty(parseDate(text, today));
        }
        if (containsAny(text, NOTIFY_WORDS)) {
            return BotIntent.notifyStatus();
        }
        if (containsAny(text, INCIDENT_WORDS)) {
            Matcher uuid = UUID_TOKEN.matcher(bounded);
            if (uuid.find()) {
                return BotIntent.incidentDetail(UUID.fromString(uuid.group()));
            }
            return BotIntent.incidents(parseStatus(text), parseService(bounded));
        }
        return BotIntent.unsupported();
    }

    /** 状态过滤：默认 FIRING（"哪些告警"=当前进行中）；显式"全部/所有"→ null */
    private static String parseStatus(String text) {
        if (text.contains("已恢复") || text.contains("已解决") || text.contains("resolved")) {
            return "RESOLVED";
        }
        if (text.contains("全部") || text.contains("所有")) {
            return null;
        }
        return "FIRING";
    }

    /** 服务词元（"服务 xxx"/"服务：xxx"/"服务=xxx"）；无 → null（不过滤） */
    private static String parseService(String bounded) {
        Matcher m = SERVICE_TOKEN.matcher(bounded);
        return m.find() ? m.group(1) : null;
    }

    /**
     * 日期词元：相对词优先（今天/今晚 → today；明天/明晚 → +1；后天 → +2；
     * 昨天/昨晚 → -1），其次 ISO（2026-09-15）与中文（9月15日，年=今年；
     * 该日已过去 60 天以上则视为明年——排班查询的实用口径）；非法日期
     * （2月30日）如实放弃解析 = 此刻（date=null）。
     */
    private static LocalDate parseDate(String text, LocalDate today) {
        if (text.contains("后天")) {
            return today.plusDays(2);
        }
        if (text.contains("明天") || text.contains("明晚")) {
            return today.plusDays(1);
        }
        if (text.contains("昨天") || text.contains("昨晚")) {
            return today.minusDays(1);
        }
        if (text.contains("今天") || text.contains("今日") || text.contains("今晚")) {
            return today;
        }
        Matcher iso = ISO_DATE.matcher(text);
        if (iso.find()) {
            return safeDate(Integer.parseInt(iso.group(1)), Integer.parseInt(iso.group(2)),
                    Integer.parseInt(iso.group(3)));
        }
        Matcher cn = CN_DATE.matcher(text);
        if (cn.find()) {
            LocalDate candidate = safeDate(today.getYear(), Integer.parseInt(cn.group(1)),
                    Integer.parseInt(cn.group(2)));
            if (candidate != null && candidate.isBefore(today.minusDays(60))) {
                candidate = candidate.plusYears(1);
            }
            return candidate;
        }
        return null;
    }

    private static LocalDate safeDate(int year, int month, int day) {
        try {
            return LocalDate.of(year, month, day);
        } catch (java.time.DateTimeException e) {
            return null;
        }
    }

    private static boolean containsAny(String text, List<String> words) {
        for (String word : words) {
            if (text.contains(word)) {
                return true;
            }
        }
        return false;
    }
}
