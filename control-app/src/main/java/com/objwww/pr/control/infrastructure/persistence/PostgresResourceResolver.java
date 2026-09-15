package com.objwww.pr.control.infrastructure.persistence;

import com.objwww.pr.control.alert.application.mutation.ResourceResolver;
import com.objwww.pr.control.alert.domain.mutation.ResolvedResource;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * V115 resource_inventory/resource_alias 的 Postgres 实现（PB-B2）：canonical 身份
 * 唯一来源。enabled=false 的资源不可解析（下线即 fail-closed）；labels jsonb 平铺为
 * 只读字符串视图（快照不携带 labels——canonical_env/team/kind/version 才是授权面）。
 */
public class PostgresResourceResolver implements ResourceResolver {

    private final JdbcClient jdbc;

    public PostgresResourceResolver(JdbcClient jdbc) {
        this.jdbc = Objects.requireNonNull(jdbc);
    }

    @Override
    public Optional<ResolvedResource> resolve(String requestedKey) {
        if (requestedKey == null || requestedKey.isBlank()) {
            return Optional.empty();
        }
        return jdbc.sql("""
                select i.resource_uid, i.canonical_env, i.canonical_team,
                       i.resource_kind, i.resource_version, i.labels
                  from resource_alias a
                  join resource_inventory i on i.resource_uid = a.resource_uid
                 where a.resource_key = :key and i.enabled
                """)
                .param("key", requestedKey.trim())
                .query((rs, n) -> new ResolvedResource(rs.getString("resource_uid"),
                        requestedKey.trim(), rs.getString("canonical_env"),
                        rs.getString("canonical_team"), rs.getString("resource_kind"),
                        rs.getLong("resource_version"), Map.of()))
                .optional();
    }
}
