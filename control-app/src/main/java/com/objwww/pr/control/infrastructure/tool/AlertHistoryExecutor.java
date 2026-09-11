package com.objwww.pr.control.infrastructure.tool;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.objwww.pr.control.alert.application.tool.ToolExecutor;
import com.objwww.pr.control.alert.domain.tool.ToolControlPlaneException;
import com.objwww.pr.control.alert.domain.tool.ToolControlReason;
import com.objwww.pr.control.alert.domain.tool.ToolModelVisibleException;
import com.objwww.pr.control.alert.domain.tool.ToolModelVisibleReason;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * EN-05 alert_history_query 执行器（§一 alert/记忆类 P0）：V7 alert_event 只读时间线
 * ——alertname 或 fingerprint + 窗（触发时刻 starts_at 落窗）→ firing/resolved 交替
 * 序列（"是否复发/抖动"判定输入；status 保持 AM 原文小写，§6.3）。
 *
 * <p>只读边界（§一）：SELECT 固定模板零写副作用；窗幅上限 72h（复发判断需要天级窗，
 * 显式钉死不设无限窗）；行数 200+1 探针截断；流式序列化字节上限即断；空窗 NO_DATA。
 * 语句级超时由 Gateway 硬 deadline 兜底（超时 cancel(true)），PG 侧 statement_timeout
 * 归部署面（195 窗清单）。
 */
public class AlertHistoryExecutor implements ToolExecutor {

    /** 复发/抖动判断需要天级窗：72h 上界（硬门显式钉，超界 INVALID_ARGS） */
    static final long MAX_WINDOW_SECONDS = 259_200;
    static final int ROW_LIMIT = 200;

    private static final ObjectMapper JSON = new ObjectMapper();

    private final JdbcClient jdbc;

    public AlertHistoryExecutor(JdbcClient jdbc) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc 不得为 null");
    }

    @Override
    public byte[] execute(ToolExecution execution) {
        Query query = parseArgs(execution.validatedArgs());
        boolean byName = query.alertname() != null;
        boolean byFingerprint = query.fingerprint() != null;
        StringBuilder where = new StringBuilder();
        if (byName) {
            where.append("labels->>'alertname' = :alertname");
        }
        if (byName && byFingerprint) {
            where.append(" OR ");
        }
        if (byFingerprint) {
            where.append("fingerprint = :fingerprint");
        }
        String sql = """
                SELECT fingerprint, status, labels->>'alertname' AS alertname,
                       labels->>'service' AS service, starts_at, ends_at, recorded_at
                  FROM alert_event
                 WHERE (%s)
                   AND starts_at >= :since AND starts_at < :until
                 ORDER BY starts_at, recorded_at
                 LIMIT :rowCap
                """.formatted(where.toString());
        var rows = jdbc.sql(sql)
                .param("alertname", byName ? query.alertname() : "")
                .param("fingerprint", byFingerprint ? query.fingerprint() : "")
                .param("since", Timestamp.from(query.since()))
                .param("until", Timestamp.from(query.until()))
                .param("rowCap", ROW_LIMIT + 1)
                .query(AlertHistoryExecutor::rowOf)
                .list();
        if (rows.isEmpty()) {
            throw new ToolModelVisibleException(ToolModelVisibleReason.NO_DATA,
                    "NO_DATA: 查询窗内无该告警的时间线记录（空结果如实呈现）");
        }
        boolean truncated = rows.size() > ROW_LIMIT;
        return render(truncated ? rows.subList(0, ROW_LIMIT) : rows,
                truncated, execution.resultLimitBytes());
    }

    // ------------------------------------------------------------ 语义面（静态 = L0 可测）

    record Query(String alertname, String fingerprint, Instant since, Instant until) {
    }

    static Query parseArgs(Map<String, Object> args) {
        String alertname = optionalText(args.get("alertname"));
        String fingerprint = optionalText(args.get("fingerprint"));
        if (alertname == null && fingerprint == null) {
            throw new ToolControlPlaneException(ToolControlReason.INVALID_ARGS,
                    "INVALID_ARGS: alertname/fingerprint 至少一项必填");
        }
        Instant since = parseInstant(args.get("since"), "since");
        Instant until = parseInstant(args.get("until"), "until");
        long windowMillis = until.toEpochMilli() - since.toEpochMilli();
        if (windowMillis < 0 || windowMillis > MAX_WINDOW_SECONDS * 1_000) {
            throw new ToolControlPlaneException(ToolControlReason.INVALID_ARGS,
                    "INVALID_ARGS: 查询窗幅超限（0 ≤ until-since ≤ "
                            + MAX_WINDOW_SECONDS / 3_600 + "h）");
        }
        return new Query(alertname, fingerprint, since, until);
    }

    private static String optionalText(Object value) {
        if (value == null) {
            return null;
        }
        String text = String.valueOf(value);
        return text.isBlank() ? null : text;
    }

    private static Instant parseInstant(Object value, String field) {
        if (value == null) {
            throw new ToolControlPlaneException(ToolControlReason.INVALID_ARGS,
                    "INVALID_ARGS: " + field + " 必填（ISO-8601 Instant）");
        }
        try {
            return Instant.parse(String.valueOf(value));
        } catch (Exception e) {
            throw new ToolControlPlaneException(ToolControlReason.INVALID_ARGS,
                    "INVALID_ARGS: " + field + " 必为 ISO-8601 Instant");
        }
    }

    /** 序列化：统一形状 data.result 时间线行 + truncated，流式字节上限即断 */
    static byte[] render(List<Map<String, Object>> rows, boolean truncated,
            long limitBytes) {
        long cap = Math.max(1, limitBytes);
        ByteArrayOutputStream out = new ByteArrayOutputStream(1_024);
        try (JsonGenerator gen = JSON.getFactory().createGenerator(out)) {
            gen.writeStartObject();
            gen.writeStringField("status", "success");
            gen.writeObjectFieldStart("data");
            gen.writeBooleanField("truncated", truncated);
            gen.writeFieldName("result");
            gen.writeStartArray();
            for (Map<String, Object> row : rows) {
                gen.writeObject(row);
                gen.flush();
                if (out.size() > cap) {
                    throw new ToolControlPlaneException(ToolControlReason.RESULT_OVERSIZE,
                            "RESULT_OVERSIZE: 响应超过 " + limitBytes + " 字节上限（流式即断）");
                }
            }
            gen.writeEndArray();
            gen.writeEndObject();
            gen.writeEndObject();
        } catch (IOException e) {
            throw new IllegalStateException("alert.history 结果序列化失败", e);
        }
        if (out.size() > cap) {
            throw new ToolControlPlaneException(ToolControlReason.RESULT_OVERSIZE,
                    "RESULT_OVERSIZE: 响应超过 " + limitBytes + " 字节上限（流式即断）");
        }
        return out.toByteArray();
    }

    private static Map<String, Object> rowOf(ResultSet rs, int n) throws SQLException {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("fingerprint", rs.getString("fingerprint"));
        row.put("status", rs.getString("status"));
        row.put("alertname", rs.getString("alertname"));
        row.put("service", rs.getString("service"));
        row.put("starts_at", rs.getTimestamp("starts_at").toInstant().toString());
        java.sql.Timestamp endsAt = rs.getTimestamp("ends_at");
        row.put("ends_at", endsAt == null ? null : endsAt.toInstant().toString());
        row.put("recorded_at", rs.getTimestamp("recorded_at").toInstant().toString());
        return row;
    }
}
