package com.objwww.pr.control.infrastructure.persistence;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.objwww.pr.control.alert.domain.mcp.McpServerRegistryRepository;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * MCP server 注册表 Postgres 实现（EN-06，V64 mcp_server_registry）。
 * generation 由单语句 (SELECT COALESCE(MAX(generation),0)+1) 指派——全表单调；
 * 并发窗口由 uq(generation) 兜底（败者显式违约，管理面可重试）。单机控制面
 * （§2.3：不抄 Redis PubSub）正常路径经 McpMountManager 发布锁串行化。
 */
public class PostgresMcpServerRegistryRepository implements McpServerRegistryRepository {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final TypeReference<List<String>> ARGS = new TypeReference<>() {
    };

    private final JdbcClient jdbc;

    public PostgresMcpServerRegistryRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public List<ServerRecord> loadAll() {
        return jdbc.sql("""
                SELECT name, transport, endpoint, args, headers_ref, enabled,
                       generation, updated_at
                FROM mcp_server_registry
                ORDER BY generation
                """)
                .query((rs, i) -> new ServerRecord(
                        new ServerSpec(rs.getString("name"), rs.getString("transport"),
                                rs.getString("endpoint"), readArgs(rs.getString("args")),
                                rs.getString("headers_ref"), rs.getBoolean("enabled")),
                        rs.getLong("generation"),
                        rs.getObject("updated_at", OffsetDateTime.class).toInstant()))
                .list();
    }

    @Override
    public ServerRecord upsert(ServerSpec spec) {
        String argsJson;
        try {
            argsJson = JSON.writeValueAsString(spec.args() == null ? List.of() : spec.args());
        } catch (Exception e) {
            throw new IllegalArgumentException("MCP 注册 args 序列化失败", e);
        }
        return jdbc.sql("""
                INSERT INTO mcp_server_registry
                    (name, transport, endpoint, args, headers_ref, enabled, generation, updated_at)
                VALUES (:name, :transport, :endpoint, CAST(:args AS jsonb), :headers_ref,
                        :enabled, (SELECT COALESCE(MAX(generation), 0) + 1
                                   FROM mcp_server_registry), now())
                ON CONFLICT (name) DO UPDATE SET
                    transport = EXCLUDED.transport,
                    endpoint = EXCLUDED.endpoint,
                    args = EXCLUDED.args,
                    headers_ref = EXCLUDED.headers_ref,
                    enabled = EXCLUDED.enabled,
                    generation = (SELECT COALESCE(MAX(generation), 0) + 1
                                  FROM mcp_server_registry),
                    updated_at = now()
                RETURNING name, transport, endpoint, args, headers_ref, enabled,
                          generation, updated_at
                """)
                .param("name", spec.name())
                .param("transport", spec.transport())
                .param("endpoint", spec.endpoint())
                .param("args", argsJson)
                .param("headers_ref", spec.headersRef())
                .param("enabled", spec.enabled())
                .query((rs, i) -> new ServerRecord(
                        new ServerSpec(rs.getString("name"), rs.getString("transport"),
                                rs.getString("endpoint"), readArgs(rs.getString("args")),
                                rs.getString("headers_ref"), rs.getBoolean("enabled")),
                        rs.getLong("generation"),
                        rs.getObject("updated_at", OffsetDateTime.class).toInstant()))
                .single();
    }

    @Override
    public boolean delete(String name) {
        return jdbc.sql("DELETE FROM mcp_server_registry WHERE name = :name")
                .param("name", name)
                .update() > 0;
    }

    private static List<String> readArgs(String argsJson) {
        try {
            return JSON.readValue(argsJson, ARGS);
        } catch (Exception e) {
            throw new IllegalStateException("MCP 注册表 args 反序列化失败: " + argsJson, e);
        }
    }
}
