package com.objwww.pr.control.infrastructure.persistence;

import com.objwww.pr.control.ops.domain.repository.PartitionCatalog;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.util.List;
import java.util.Objects;

/**
 * 分区目录读面（M5-18）：pg_inherits 投影父表全部分区名（含 default——候选判定
 * 由 RetentionService 排除，目录只如实上报）。只读授权面内（catalog 可见性）。
 */
public class PostgresPartitionCatalog implements PartitionCatalog {

    private final JdbcClient jdbc;

    public PostgresPartitionCatalog(JdbcClient jdbc) {
        this.jdbc = Objects.requireNonNull(jdbc);
    }

    @Override
    public List<String> partitionsOf(String tableName) {
        return jdbc.sql("""
                        SELECT c.relname
                          FROM pg_inherits i
                          JOIN pg_class c ON c.oid = i.inhrelid
                          JOIN pg_class p ON p.oid = i.inhparent
                         WHERE p.relname = :table
                         ORDER BY c.relname
                        """)
                .param("table", tableName)
                .query(String.class)
                .list();
    }
}
