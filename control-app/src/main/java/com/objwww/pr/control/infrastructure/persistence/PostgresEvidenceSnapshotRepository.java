package com.objwww.pr.control.infrastructure.persistence;

import com.objwww.pr.control.alert.domain.evidence.EvidenceSnapshotRepository;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionOperations;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * V16 快照仓储的 Postgres 实现（AM4 M4-20）。freeze = 快照行+成员行同短事务，
 * 快照行 ON CONFLICT DO NOTHING 判幂等（重冻结返回 false 不重复落成员）；
 * 无更新路径（迟到证据改不了旧快照）。
 */
public class PostgresEvidenceSnapshotRepository implements EvidenceSnapshotRepository {

    private final JdbcClient jdbc;
    private final TransactionOperations tx;

    public PostgresEvidenceSnapshotRepository(JdbcClient jdbc, TransactionOperations tx) {
        this.jdbc = Objects.requireNonNull(jdbc);
        this.tx = Objects.requireNonNull(tx);
    }

    @Override
    public boolean freeze(FrozenSnapshot snapshot, List<SnapshotMemberRow> members) {
        return Boolean.TRUE.equals(tx.execute(status -> {
            int inserted = jdbc.sql("""
                    insert into rca_evidence_snapshot(id, run_id, snapshot_digest,
                        observed_generation, config_digest, tool_registry_digest,
                        parent_snapshot_digest)
                    values (:id, :run, :digest, :gen, :config, :tools, :parent)
                    on conflict (run_id, snapshot_digest) do nothing
                    """)
                    .param("id", snapshot.snapshotId()).param("run", snapshot.runId())
                    .param("digest", snapshot.snapshotDigest())
                    .param("gen", snapshot.observedGeneration())
                    .param("config", snapshot.configDigest())
                    .param("tools", snapshot.toolRegistryDigest())
                    .param("parent", snapshot.parentSnapshotDigest())
                    .update();
            if (inserted == 0) {
                return false; // 同 (run,digest) 已冻结：幂等跳过（成员必已齐）
            }
            for (SnapshotMemberRow member : members) {
                jdbc.sql("""
                        insert into rca_snapshot_member(snapshot_id, evidence_id,
                            evidence_type, payload_digest)
                        values (:snapshot, :evidence, :type, :digest)
                        """)
                        .param("snapshot", snapshot.snapshotId())
                        .param("evidence", member.evidenceId())
                        .param("type", member.evidenceType())
                        .param("digest", member.payloadDigest())
                        .update();
            }
            return true;
        }));
    }

    @Override
    public Optional<FrozenSnapshot> find(UUID runId, String snapshotDigest) {
        Optional<FrozenSnapshot> snapshot = jdbc.sql("""
                select id, run_id, snapshot_digest, observed_generation, config_digest,
                       tool_registry_digest, parent_snapshot_digest
                  from rca_evidence_snapshot
                 where run_id = :run and snapshot_digest = :digest
                """)
                .param("run", runId).param("digest", snapshotDigest)
                .query((rs, n) -> mapSnapshot(rs))
                .optional();
        return snapshot;
    }

    @Override
    public List<SnapshotMemberRow> membersOf(UUID snapshotId) {
        return jdbc.sql("""
                select evidence_id, evidence_type, payload_digest
                  from rca_snapshot_member where snapshot_id = :id order by evidence_id
                """)
                .param("id", snapshotId)
                .query((rs, n) -> new SnapshotMemberRow(
                        rs.getObject("evidence_id", UUID.class),
                        rs.getString("evidence_type"),
                        rs.getString("payload_digest")))
                .list();
    }

    private FrozenSnapshot mapSnapshot(ResultSet rs) throws SQLException {
        return new FrozenSnapshot(
                rs.getObject("id", UUID.class),
                rs.getObject("run_id", UUID.class),
                rs.getString("snapshot_digest"),
                rs.getLong("observed_generation"),
                rs.getString("config_digest"),
                rs.getString("tool_registry_digest"),
                rs.getString("parent_snapshot_digest"));
    }
}
