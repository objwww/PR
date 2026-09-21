package com.objwww.pr.control.infrastructure.persistence;

import com.objwww.pr.control.alert.application.agent.CompactionConsumptionPort;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.util.Objects;
import java.util.UUID;

/**
 * {@link CompactionConsumptionPort} 的 Postgres 实现（ME-T12/D08，V164）。
 * insert-only append（无唯一键：同一 run 多次压缩逐次留行，评测侧取最新行）。
 * 写入身份 = control_app（alert 主链数据源，V164 授权面）。
 */
public class PostgresCompactionConsumptionPort implements CompactionConsumptionPort {

    /** 包内可见供 SQL 契约测试锁定形状（insert-only 纪律） */
    static final String SQL = """
            insert into rca_compaction_consumption (
                id, run_id, task_id, summary_digest, mode, summary_committed,
                consumer_invoked, consumed, policy_digest)
            values (:id, :runId, :taskId, :summaryDigest, :mode, :summaryCommitted,
                :consumerInvoked, :consumed, :policyDigest)
            """;

    private final JdbcClient jdbc;

    public PostgresCompactionConsumptionPort(JdbcClient jdbc) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
    }

    @Override
    public void record(UUID runId, UUID taskId, String summaryDigest, String mode,
                       boolean summaryCommitted, boolean consumerInvoked,
                       Boolean consumed, String policyDigest) {
        jdbc.sql(SQL)
                .param("id", UUID.randomUUID())
                .param("runId", runId)
                .param("taskId", taskId)
                .param("summaryDigest", summaryDigest)
                .param("mode", mode)
                .param("summaryCommitted", summaryCommitted)
                .param("consumerInvoked", consumerInvoked)
                .param("consumed", consumed)
                .param("policyDigest", policyDigest)
                .update();
    }
}
