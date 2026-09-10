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
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * change.query 真实执行器（EX-B1）：只读查 V40 change_event（时间窗 + 服务），
 * 变更事实由 config 激活同事务与部署脚本写入，Agent 保持只读（评审 B1）。
 *
 * <p>语义约束（executor 域内判，同 {@link PrometheusQueryExecutor} 惯例）：
 * since/until 必为 ISO-8601 Instant 且窗幅 ≤ {@value #MAX_WINDOW_SECONDS}s；
 * service 可选（缺省 {@value #DEFAULT_SERVICE}）且必须落在 allowlist；
 * 行数硬顶 {@value #ROW_LIMIT}（SQL LIMIT 探针，超出截断并标 truncated）；
 * 序列化流式过字节上限 → 控制面 RESULT_OVERSIZE。空结果 → 模型可见 NO_DATA
 * （正常空结果，非故障）。响应形状沿 SingleToolEvidenceAgent 统一解析面。
 */
public class ChangeQueryExecutor implements ToolExecutor {

    /** 卡面 ≤15min 查询窗 */
    static final long MAX_WINDOW_SECONDS = 900;
    /** 卡面行数上限（SQL LIMIT 取 +1 探针判截断） */
    static final int ROW_LIMIT = 200;
    /** service 缺省值 */
    static final String DEFAULT_SERVICE = "control-app";

    private static final ObjectMapper JSON = new ObjectMapper();

    private static final String SELECT_SQL = """
            SELECT deploy_id, action, service, environment, image_digest, config_digest,
                   commit_sha, actor, status, rollback_of, effective_at
              FROM change_event
             WHERE service = :service
               AND effective_at >= :since AND effective_at < :until
             ORDER BY effective_at, deploy_id
             LIMIT :rowCap
            """;

    private final JdbcClient jdbc;
    private final Set<String> serviceAllowlist;

    public ChangeQueryExecutor(JdbcClient jdbc, Set<String> serviceAllowlist) {
        if (serviceAllowlist == null || serviceAllowlist.isEmpty()) {
            throw new IllegalArgumentException("serviceAllowlist 不得为空（fail-closed）");
        }
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc 不得为 null");
        this.serviceAllowlist = Set.copyOf(serviceAllowlist);
    }

    @Override
    public byte[] execute(ToolExecution execution) {
        Query query = parseArgs(execution.validatedArgs(), serviceAllowlist);
        var rows = jdbc.sql(SELECT_SQL)
                .param("service", query.service())
                .param("since", Timestamp.from(query.since()))
                .param("until", Timestamp.from(query.until()))
                .param("rowCap", ROW_LIMIT + 1)
                .query(ChangeQueryExecutor::rowOf)
                .list();
        if (rows.isEmpty()) {
            throw new ToolModelVisibleException(ToolModelVisibleReason.NO_DATA,
                    "变更源在查询窗内无记录（NO_DATA）");
        }
        boolean truncated = rows.size() > ROW_LIMIT;
        return render(truncated ? rows.subList(0, ROW_LIMIT) : rows,
                truncated, execution.resultLimitBytes());
    }

    // ------------------------------------------------------------------ 语义面（静态 = L0 可测）

    /** 查询计划：since/until/service 三件（语义校验后） */
    record Query(Instant since, Instant until, String service) {
    }

    /** 参数语义校验：窗幅 ≤900s、service 落 allowlist（违约 = 控制面 INVALID_ARGS） */
    static Query parseArgs(Map<String, Object> args, Set<String> allowlist) {
        Instant since = parseInstant(args.get("since"), "since");
        Instant until = parseInstant(args.get("until"), "until");
        long windowMillis = until.toEpochMilli() - since.toEpochMilli();
        if (windowMillis < 0 || windowMillis > MAX_WINDOW_SECONDS * 1_000) {
            throw new ToolControlPlaneException(ToolControlReason.INVALID_ARGS,
                    "INVALID_ARGS: 查询窗幅超限（0 ≤ until-since ≤ " + MAX_WINDOW_SECONDS + "s）");
        }
        String service = args.get("service") == null
                ? DEFAULT_SERVICE
                : String.valueOf(args.get("service"));
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

    /** 结果序列化：逐行流式写 + 字节上限探针（超限即断，同 F17 readBounded 语义） */
    static byte[] render(java.util.List<Map<String, Object>> rows, boolean truncated,
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
            throw new IllegalStateException("change.query 结果序列化失败", e);
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
        row.put("rollback_of", rs.getString("rollback_of"));
        row.put("effective_at", rs.getTimestamp("effective_at").toInstant().toString());
        return row;
    }
}
