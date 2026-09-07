package com.objwww.pr.control.infrastructure.persistence;

import com.objwww.pr.control.alert.domain.tool.ToolReplayStore;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * V19 精确回放账本的 Postgres 实现（AM4 M4-32）。哑存储：键与冲突语义全部冻结在
 * {@link ToolReplayStore} 端口契约与应用层 {@code ReplayToolGateway.record}
 * （同键异响应冲突拒绝），本类只做两件事：
 * <ul>
 *   <li>put：按 action_digest 首录即终版（on conflict do nothing）——即使应用层
 *       防线失效，DB 唯一键也兜底"冲突禁静默覆盖"；</li>
 *   <li>find：按 action_digest 精确等值查询，无记录返回空（绝不模糊匹配）。</li>
 * </ul>
 *
 * @author wanghua
 * @date 2026-09-05
 */
public class PostgresToolReplayStore implements ToolReplayStore {

    private static final String INSERT_SQL = """
            insert into rca_tool_replay(id, action_digest, tool_name, tool_version, response)
            values (:id, :digest, :tool, :version, :response)
            on conflict (action_digest) do nothing
            """;

    private static final String SELECT_SQL = """
            select action_digest, tool_name, tool_version, response
              from rca_tool_replay
             where action_digest = :digest
            """;

    private final JdbcClient jdbc;

    public PostgresToolReplayStore(JdbcClient jdbc) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
    }

    @Override
    public void put(ReplayRecord record) {
        Objects.requireNonNull(record, "record");
        Objects.requireNonNull(record.response(), "response");
        jdbc.sql(INSERT_SQL)
                .param("id", UUID.randomUUID())
                .param("digest", record.actionDigest())
                .param("tool", record.toolName())
                .param("version", record.toolVersion())
                .param("response", record.response())
                .update();
    }

    @Override
    public Optional<ReplayRecord> find(String actionDigest) {
        Objects.requireNonNull(actionDigest, "actionDigest");
        return jdbc.sql(SELECT_SQL)
                .param("digest", actionDigest)
                .query((rs, n) -> new ReplayRecord(rs.getString("action_digest"),
                        rs.getString("tool_name"), rs.getString("tool_version"),
                        rs.getBytes("response")))
                .optional();
    }
}
