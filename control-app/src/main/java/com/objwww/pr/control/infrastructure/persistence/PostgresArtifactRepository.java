package com.objwww.pr.control.infrastructure.persistence;

import com.objwww.pr.control.domain.model.ArtifactRecord;
import com.objwww.pr.control.domain.repository.ArtifactRepository;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.sql.Timestamp;
import java.util.Objects;

/**
 * ArtifactRepository 的 Postgres 实现：digest 主键，ON CONFLICT DO NOTHING 保证登记幂等。
 *
 * <p>刻意不加 Spring 注解、本任务不接线不跑集成（默认 profile 无 DataSource 空跑约束）；
 * 集成验证随 T17 Testcontainers 套件统一做。
 */
public class PostgresArtifactRepository implements ArtifactRepository {

    private static final String SQL = """
            INSERT INTO artifact (digest, artifact_type, size_bytes, storage_path, created_at)
            VALUES (:digest, :type, :size, :path, :createdAt)
            ON CONFLICT (digest) DO NOTHING
            """;

    private final JdbcClient jdbc;

    public PostgresArtifactRepository(JdbcClient jdbc) {
        this.jdbc = Objects.requireNonNull(jdbc);
    }

    @Override
    public void register(ArtifactRecord record) {
        Objects.requireNonNull(record, "record");
        jdbc.sql(SQL)
                .param("digest", record.digest().value())
                .param("type", record.artifactType().name())
                .param("size", record.sizeBytes())
                .param("path", record.storagePath())
                .param("createdAt", Timestamp.from(record.createdAt()))
                .update();
    }
}
