package com.objwww.pr.control.infrastructure.persistence;

import com.objwww.pr.control.alert.domain.agent.RcaModelInputReplayPort;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * R2 回放读面的 Postgres 实现：捕获行（rca_model_input）与账本行（rca_model_call）
 * join 一次取齐——捕获 digest ↔ 账本 digest 对账锚 + roleId/roleDigest 供 MC36
 * 版本反查（prompt 原文由 role_digest 扫 release_asset PROMPT 快照解析）。
 */
public class PostgresRcaModelInputReplay implements RcaModelInputReplayPort {

    private final JdbcClient jdbc;

    public PostgresRcaModelInputReplay(JdbcClient jdbc) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
    }

    @Override
    public Optional<ReplayRow> byModelCallId(UUID modelCallId) {
        List<ReplayRow> rows = jdbc.sql("""
                select i.model_call_id, i.capture_level, i.prompt_text,
                       i.prompt_digest as capture_digest,
                       c.prompt_digest as ledger_prompt_digest,
                       c.role_id, c.role_digest
                from rca_model_input i
                join rca_model_call c on c.id = i.model_call_id
                where i.model_call_id = :modelCallId
                """)
                .param("modelCallId", modelCallId)
                .query((rs, rowNum) -> new ReplayRow(
                        rs.getObject("model_call_id", UUID.class),
                        rs.getString("capture_level"),
                        rs.getString("prompt_text"),
                        rs.getString("capture_digest"),
                        rs.getString("ledger_prompt_digest"),
                        rs.getString("role_id"),
                        rs.getString("role_digest")))
                .list();
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }
}
