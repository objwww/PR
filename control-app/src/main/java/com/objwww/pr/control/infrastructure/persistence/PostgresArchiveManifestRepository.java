package com.objwww.pr.control.infrastructure.persistence;

import com.objwww.pr.control.ops.domain.repository.ArchiveManifestRepository;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * V29 archive_manifest 的 Postgres 实现（M5-19）。advanceState = UPDATE ... WHERE
 * state = :from（单向序在调用面闭合：EXPORTED→VERIFIED→ARCHIVED，其余 0 行 false）。
 */
public class PostgresArchiveManifestRepository implements ArchiveManifestRepository {

    private final JdbcClient jdbc;

    public PostgresArchiveManifestRepository(JdbcClient jdbc) {
        this.jdbc = Objects.requireNonNull(jdbc);
    }

    @Override
    public void insertExported(UUID id, String partition, long rowCount, String digestHex,
                               String exportRef) {
        jdbc.sql("""
                INSERT INTO archive_manifest (
                    id, partition_name, row_count, content_digest, export_ref, state, created_at
                ) VALUES (:id, :partition, :rowCount, :digest, :exportRef, 'EXPORTED', now())
                """)
                .param("id", id)
                .param("partition", partition)
                .param("rowCount", rowCount)
                .param("digest", digestHex)
                .param("exportRef", exportRef)
                .update();
    }

    @Override
    public Optional<Row> findByPartition(String partition) {
        return jdbc.sql("""
                        SELECT partition_name, row_count, content_digest, export_ref, state
                          FROM archive_manifest
                         WHERE partition_name = :partition
                        """)
                .param("partition", partition)
                .query((rs, i) -> new Row(
                        rs.getString("partition_name"),
                        rs.getLong("row_count"),
                        rs.getString("content_digest"),
                        rs.getString("export_ref"),
                        rs.getString("state")))
                .optional();
    }

    @Override
    public boolean advanceState(String partition, String from, String to) {
        return jdbc.sql("""
                UPDATE archive_manifest SET state = :to
                 WHERE partition_name = :partition AND state = :from
                """)
                .param("to", to)
                .param("partition", partition)
                .param("from", from)
                .update() > 0;
    }
}
