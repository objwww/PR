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
import java.util.Set;

/**
 * EN-05 change_event_diff 执行器（§一 change/部署面 P0）：V40 change_event 只读查询
 * ——窗内变更行 + <b>窗前基线</b>（紧邻窗口前最近一行）一次给出，变更前后 diff 由
 * 行序列自然呈现（T08：激活/回滚生效事实可查、rollback_of 引用可辨、时间顺序正确）。
 *
 * <p>纪律同 {@link ChangeQueryExecutor}：窗幅 ≤900s、ISO-8601、service allowlist
 * fail-closed、行数 200+1 探针截断、流式序列化字节上限即断、空结果 NO_DATA、
 * 只读 SELECT 模板零写副作用。
 */
public class ChangeDiffExecutor implements ToolExecutor {

    static final long MAX_WINDOW_SECONDS = 900;
    static final int ROW_LIMIT = 200;
    static final String DEFAULT_SERVICE = "control-app";

    private static final ObjectMapper JSON = new ObjectMapper();

    private static final String COLUMNS = """
            deploy_id, action, service, environment, image_digest, config_digest,
            commit_sha, actor, status, rollback_of, effective_at
            """;

    private static final String WINDOW_SQL = """
            SELECT %s
              FROM change_event
             WHERE service = :service
               AND effective_at >= :since AND effective_at < :until
             ORDER BY effective_at, deploy_id
             LIMIT :rowCap
            """.formatted(COLUMNS);

    private static final String BASELINE_SQL = """
            SELECT %s
              FROM change_event
             WHERE service = :service
               AND effective_at < :since
             ORDER BY effective_at DESC, deploy_id DESC
             LIMIT 1
            """.formatted(COLUMNS);

    private final JdbcClient jdbc;
    private final Set<String> serviceAllowlist;

    public ChangeDiffExecutor(JdbcClient jdbc, Set<String> serviceAllowlist) {
        if (serviceAllowlist == null || serviceAllowlist.isEmpty()) {
            throw new IllegalArgumentException("serviceAllowlist 不得为空（fail-closed）");
        }
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc 不得为 null");
        this.serviceAllowlist = Set.copyOf(serviceAllowlist);
    }

    @Override
    public byte[] execute(ToolExecution execution) {
        Query query = parseArgs(execution.validatedArgs(), serviceAllowlist);
        List<Map<String, Object>> rows = jdbc.sql(WINDOW_SQL)
                .param("service", query.service())
                .param("since", Timestamp.from(query.since()))
                .param("until", Timestamp.from(query.until()))
                .param("rowCap", ROW_LIMIT + 1)
                .query(ChangeDiffExecutor::rowOf)
                .list();
        if (rows.isEmpty()) {
            throw new ToolModelVisibleException(ToolModelVisibleReason.NO_DATA,
                    "变更源在查询窗内无记录（NO_DATA）");
        }
        Map<String, Object> baseline = jdbc.sql(BASELINE_SQL)
                .param("service", query.service())
                .param("since", Timestamp.from(query.since()))
                .query(ChangeDiffExecutor::rowOf)
                .optional()
                .orElse(null);
        boolean truncated = rows.size() > ROW_LIMIT;
        return render(truncated ? rows.subList(0, ROW_LIMIT) : rows, baseline,
                truncated, execution.resultLimitBytes());
    }

    // ------------------------------------------------------------ 语义面（静态 = L0 可测）

    record Query(Instant since, Instant until, String service) {
    }

    static Query parseArgs(Map<String, Object> args, Set<String> allowlist) {
        Instant since = parseInstant(args.get("since"), "since");
        Instant until = parseInstant(args.get("until"), "until");
        long windowMillis = until.toEpochMilli() - since.toEpochMilli();
        if (windowMillis < 0 || windowMillis > MAX_WINDOW_SECONDS * 1_000) {
            throw new ToolControlPlaneException(ToolControlReason.INVALID_ARGS,
                    "INVALID_ARGS: 查询窗幅超限（0 ≤ until-since ≤ "
                            + MAX_WINDOW_SECONDS + "s）");
        }
        String service = args.get("service") == null
                ? DEFAULT_SERVICE : String.valueOf(args.get("service"));
        if (!allowlist.contains(service)) {
            throw new ToolControlPlaneException(ToolControlReason.INVALID_ARGS,
                    "INVALID_ARGS: service 越出 allowlist");
        }
        return new Query(since, until, service);
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

    /** 序列化：data.baseline（可 null）+ data.result + data.truncated，流式字节上限即断 */
    static byte[] render(List<Map<String, Object>> rows, Map<String, Object> baseline,
            boolean truncated, long limitBytes) {
        long cap = Math.max(1, limitBytes);
        ByteArrayOutputStream out = new ByteArrayOutputStream(1_024);
        try (JsonGenerator gen = JSON.getFactory().createGenerator(out)) {
            gen.writeStartObject();
            gen.writeStringField("status", "success");
            gen.writeObjectFieldStart("data");
            gen.writeBooleanField("truncated", truncated);
            if (baseline == null) {
                gen.writeNullField("baseline");
            } else {
                gen.writeObjectField("baseline", baseline);
            }
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
            throw new IllegalStateException("change.diff 结果序列化失败", e);
        }
        if (out.size() > cap) {
            throw new ToolControlPlaneException(ToolControlReason.RESULT_OVERSIZE,
                    "RESULT_OVERSIZE: 响应超过 " + limitBytes + " 字节上限（流式即断）");
        }
        return out.toByteArray();
    }

    private static Map<String, Object> rowOf(ResultSet rs, int n) throws SQLException {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("deploy_id", rs.getString("deploy_id"));
        row.put("action", rs.getString("action"));
        row.put("service", rs.getString("service"));
        row.put("environment", rs.getString("environment"));
        row.put("image_digest", rs.getString("image_digest"));
        row.put("config_digest", rs.getString("config_digest"));
        row.put("commit_sha", rs.getString("commit_sha"));
        row.put("actor", rs.getString("actor"));
        row.put("status", rs.getString("status"));
        String rollbackOf = rs.getString("rollback_of");
        // char(64) 落列在 pgjdbc 下可能带补位空白——引用可辨即可，剥尾随空白
        row.put("rollback_of", rollbackOf == null ? null : rollbackOf.stripTrailing());
        row.put("effective_at", rs.getTimestamp("effective_at").toInstant().toString());
        return row;
    }
}
