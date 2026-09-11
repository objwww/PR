package com.objwww.pr.control.infrastructure.rag;

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
import java.util.Set;

/**
 * EN-07 history_rca_search 执行器（§三阶段 1：历史 RCA/判例走<b>结构化</b>过滤，非语义
 * 相似度）：rca_report JOIN rca_run JOIN incident 只读查询——service 过滤先行于行集
 * （R10：allowlist 成员校验发出前拒，越权项零进 SQL 结果面；incident_key 格式
 * alertname=X|service=Y|…，两端 LIKE 锚），只取 STRUCTURE_VALIDATED 判例（准入面），
 * 窗幅上限 30d（历史面不做无限窗），50+1 探针截断。
 *
 * <p>结果恒为参考区语义：note 明示"历史结论≠当前根因，与当前证据矛盾时保留反证"
 * （R06）。空窗 NO_DATA（R02 与 SOURCE_UNAVAILABLE 分码）。
 */
public class HistoryRcaSearchExecutor implements ToolExecutor {

    /** 历史判例 30d 窗上界（硬门显式钉，超界 INVALID_ARGS） */
    static final long MAX_WINDOW_SECONDS = 2_592_000;
    static final int ROW_LIMIT = 50;

    private static final ObjectMapper JSON = new ObjectMapper();

    private final JdbcClient jdbc;
    private final Set<String> serviceAllowlist;

    public HistoryRcaSearchExecutor(JdbcClient jdbc, Set<String> serviceAllowlist) {
        if (serviceAllowlist == null || serviceAllowlist.isEmpty()) {
            throw new IllegalArgumentException(
                    "history rca service allowlist 不得为空（fail-closed）");
        }
        this.jdbc = jdbc;
        this.serviceAllowlist = Set.copyOf(serviceAllowlist);
    }

    @Override
    public byte[] execute(ToolExecution execution) {
        Query query = parseArgs(execution.validatedArgs(), serviceAllowlist);
        String likeKey = "%|service=" + query.service() + "|%";
        String likeTail = "%|service=" + query.service();
        List<Map<String, Object>> rows;
        try {
            rows = jdbc.sql("""
                    SELECT r.id AS report_id, r.run_id, i.incident_key, r.created_at,
                           r.package_json->>'summary' AS prior_summary
                      FROM rca_report r
                      JOIN rca_run rr ON rr.id = r.run_id
                      JOIN incident i ON i.id = rr.incident_id
                     WHERE r.validation_status = 'STRUCTURE_VALIDATED'
                       AND (i.incident_key LIKE :keyLike OR i.incident_key LIKE :tailLike)
                       AND r.created_at >= :since AND r.created_at < :until
                     ORDER BY r.created_at DESC
                     LIMIT :rowCap
                    """)
                    .param("keyLike", likeKey)
                    .param("tailLike", likeTail)
                    .param("since", Timestamp.from(query.since()))
                    .param("until", Timestamp.from(query.until()))
                    .param("rowCap", ROW_LIMIT + 1)
                    .query(HistoryRcaSearchExecutor::rowOf)
                    .list();
        } catch (org.springframework.dao.DataAccessException e) {
            throw new ToolModelVisibleException(ToolModelVisibleReason.SOURCE_UNAVAILABLE,
                    "SOURCE_UNAVAILABLE: 历史 RCA 查询不可用（数据面异常已脱敏）");
        }
        if (rows.isEmpty()) {
            throw new ToolModelVisibleException(ToolModelVisibleReason.NO_DATA,
                    "NO_DATA: 窗内该服务无 STRUCTURE_VALIDATED 历史 RCA（空结果如实呈现）");
        }
        boolean truncated = rows.size() > ROW_LIMIT;
        return render(truncated ? rows.subList(0, ROW_LIMIT) : rows, truncated,
                execution.resultLimitBytes());
    }

    // ------------------------------------------------------------ 语义面（静态 = L0 可测）

    record Query(String service, Instant since, Instant until) {
    }

    /** service allowlist 成员校验发出前拒（R10 先行过滤；prom 同律，预算零扣） */
    static Query parseArgs(Map<String, Object> args, Set<String> serviceAllowlist) {
        String service = optionalText(args.get("service"));
        if (service == null) {
            throw new ToolControlPlaneException(ToolControlReason.INVALID_ARGS,
                    "INVALID_ARGS: service 必填");
        }
        if (!serviceAllowlist.contains(service)) {
            throw new ToolControlPlaneException(ToolControlReason.INVALID_ARGS,
                    "INVALID_ARGS: service 不在权限范围（allowlist 先行过滤，R10）: " + service);
        }
        Instant since = parseInstant(args.get("since"), "since");
        Instant until = parseInstant(args.get("until"), "until");
        long windowMillis = until.toEpochMilli() - since.toEpochMilli();
        if (windowMillis < 0 || windowMillis > MAX_WINDOW_SECONDS * 1_000) {
            throw new ToolControlPlaneException(ToolControlReason.INVALID_ARGS,
                    "INVALID_ARGS: 查询窗幅超限（0 ≤ until-since ≤ "
                            + MAX_WINDOW_SECONDS / 86_400 + "d）");
        }
        return new Query(service, since, until);
    }

    /** 渲染恒带参考区标记（R06：历史相似≠当前根因，反证保留） */
    static byte[] render(List<Map<String, Object>> rows, boolean truncated, long limitBytes) {
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
            gen.writeStringField("note", "历史 RCA 为结构化过滤召回的参考（非语义相似度）；"
                    + "历史结论≠当前根因，不得单独支撑 ROOT_CAUSE，与当前证据矛盾时保留反证（R06）");
            gen.writeEndObject();
            gen.writeEndObject();
        } catch (IOException e) {
            throw new IllegalStateException("history_rca_search 结果序列化失败", e);
        }
        if (out.size() > cap) {
            throw new ToolControlPlaneException(ToolControlReason.RESULT_OVERSIZE,
                    "RESULT_OVERSIZE: 响应超过 " + limitBytes + " 字节上限（流式即断）");
        }
        return out.toByteArray();
    }

    private static Map<String, Object> rowOf(ResultSet rs, int n) throws SQLException {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("report_id", rs.getString("report_id"));
        row.put("run_id", rs.getString("run_id"));
        row.put("incident_key", rs.getString("incident_key"));
        row.put("prior_summary", rs.getString("prior_summary"));
        row.put("created_at", rs.getTimestamp("created_at").toInstant().toString());
        return row;
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
}
