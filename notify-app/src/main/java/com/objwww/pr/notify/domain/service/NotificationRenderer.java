package com.objwww.pr.notify.domain.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * 白名单通知渲染器（M3-21；§6.5 渲染字段白名单，禁 raw report）。
 *
 * <p>两层防线：生产方 payload 已是白名单摘录（ReportCompletedNotifier），本渲染器
 * <b>再次</b>按白名单取字段并逐项消毒——M3-21 冻结处理面：
 * <ul>
 *   <li>控制字符剥离（C0 全剥，保留换行——IM markdown 排版）；</li>
 *   <li>@all/@everyone 中和（防 IM 全员@骚扰/注入）；</li>
 *   <li>Markdown 链接只放行 http(s) scheme，其余剥成纯文本（javascript:/file: 等）；</li>
 *   <li>超长截断（单字段 + 总文本双上限）；</li>
 *   <li>秘密值遮蔽（sk-/Bearer/token 形态值 → [REDACTED]，防摘录夹带凭据）。</li>
 * </ul>
 * 纯函数、零触网；payload 不可解析/缺 operation_id → RenderException（执行器落 DEAD）。
 */
public final class NotificationRenderer {

    /** 渲染产物（title/text 均已消毒；渠道 handler 只做平台 body 包装，不再碰内容） */
    public record RenderedNotification(String title, String text, UUID operationId) {
    }

    /** payload 违反白名单契约（缺 operation_id / 不可解析）——确定性拒绝，不猜测 */
    public static final class RenderException extends RuntimeException {

        public RenderException(String message) {
            super(message);
        }
    }

    private static final ObjectMapper JSON = new ObjectMapper();

    private static final Pattern CONTROL_CHARS = Pattern.compile("[\\x00-\\x09\\x0B-\\x1F\\x7F]");
    private static final Pattern AT_ALL = Pattern.compile("@(all|everyone|所有人|全体成员)",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern MD_LINK = Pattern.compile("\\[([^\\]]*)]\\(([^)\\s]+)\\)");
    private static final Pattern SECRET_LIKE = Pattern.compile(
            "(sk-[A-Za-z0-9_-]{8,}|Bearer\\s+[A-Za-z0-9._-]{8,}|"
                    + "(?i)(api[_-]?key|secret|token|password)[\"'\\s:=:]{1,4}[A-Za-z0-9._-]{8,})");
    private static final String REDACTED = "[REDACTED]";

    private final int maxFieldChars;
    private final int maxTotalChars;

    public NotificationRenderer(int maxFieldChars, int maxTotalChars) {
        if (maxFieldChars < 1 || maxTotalChars < maxFieldChars) {
            throw new IllegalArgumentException(
                    "渲染上限非法：maxFieldChars>=1 且 maxTotalChars>=maxFieldChars");
        }
        this.maxFieldChars = maxFieldChars;
        this.maxTotalChars = maxTotalChars;
    }

    public RenderedNotification render(String payloadJson) {
        JsonNode payload;
        try {
            payload = JSON.readTree(Objects.requireNonNull(payloadJson, "payloadJson"));
        } catch (Exception e) {
            throw new RenderException("payload 不可解析: " + e.getMessage());
        }
        if (!payload.isObject() || payload.path("operation_id").asText("").isBlank()) {
            throw new RenderException("payload 缺 operation_id（重复可检测性破坏，拒绝渲染）");
        }

        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("notice", text(payload, "notice"));
        fields.put("summary", text(payload, "summary"));
        fields.put("root_cause", text(payload, "root_cause_component") + " / "
                + text(payload, "root_cause_fault_type") + " / "
                + text(payload, "root_cause_reason_code"));
        fields.put("impact", text(payload, "impact"));
        fields.put("remediation", text(payload, "remediation"));
        fields.put("report_id", text(payload, "report_id"));
        fields.put("run_id", text(payload, "run_id"));

        UUID operationId = UUID.fromString(payload.get("operation_id").asText());
        String candidateMark = payload.path("candidate").asBoolean(false) ? "【AI 候选】" : "";
        String title = truncate(candidateMark + "RCA 报告通知 "
                + fields.get("summary"), 60);

        StringBuilder body = new StringBuilder();
        fields.forEach((key, value) -> {
            if (!value.isBlank()) {
                body.append("- **").append(key).append("**: ").append(value).append('\n');
            }
        });
        body.append("- operation_id: ").append(operationId).append('\n');
        return new RenderedNotification(title, truncate(body.toString(), maxTotalChars),
                operationId);
    }

    /** 逐项消毒 + 截断（白名单字段专用，白名单外的 payload 字段一律不取） */
    private String text(JsonNode payload, String field) {
        String raw = payload.path(field).asText("");
        return truncate(sanitize(raw), maxFieldChars);
    }

    private String truncate(String value, int limit) {
        return value.length() <= limit ? value : value.substring(0, limit - 1) + "…";
    }

    /** 消毒顺序固定：秘密遮蔽 → 控制字符 → @all → 链接 scheme（后续步骤不在前步产物里再引入风险） */
    String sanitize(String raw) {
        if (raw == null || raw.isEmpty()) {
            return "";
        }
        String value = SECRET_LIKE.matcher(raw).replaceAll(REDACTED);
        value = CONTROL_CHARS.matcher(value).replaceAll(" ");
        value = AT_ALL.matcher(value).replaceAll("@ all");
        value = MD_LINK.matcher(value).replaceAll(match -> {
            String label = match.group(1);
            String url = match.group(2);
            boolean safeScheme = url.startsWith("http://") || url.startsWith("https://");
            return safeScheme ? "[" + label + "](" + url + ")" : label;
        });
        return value;
    }
}
