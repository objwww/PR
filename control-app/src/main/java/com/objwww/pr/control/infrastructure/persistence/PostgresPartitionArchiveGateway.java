package com.objwww.pr.control.infrastructure.persistence;

import com.objwww.pr.control.ops.domain.repository.PartitionArchiveGateway;
import com.objwww.pr.shared.Digest;
import com.objwww.pr.shared.Digests;
import org.postgresql.PGConnection;
import org.postgresql.copy.CopyManager;
import org.springframework.jdbc.core.JdbcTemplate;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * 分区归档机械面的 PG 实现（M5-19）：COPY TO STDOUT 有序导出（keep_table 先例——
 * detach 后分区保留为独立表）+ DETACH PARTITION 单语句 autocommit。C-24：不用
 * CONCURRENTLY 形态——PG 禁止在含 default 分区的分区表上并发摘离（V28 恒有
 * rca_event_default），归档是运维时段低频操作，短 ACCESS EXCLUSIVE 锁可接受。
 * 分区名白名单校验 fail-closed（拼注入零容忍）。
 */
public class PostgresPartitionArchiveGateway implements PartitionArchiveGateway {

    private static final Pattern SAFE_IDENTIFIER = Pattern.compile("^[a-z_][a-z0-9_]*$");

    private final JdbcTemplate jdbc;

    public PostgresPartitionArchiveGateway(JdbcTemplate jdbc) {
        this.jdbc = Objects.requireNonNull(jdbc);
    }

    @Override
    public Snapshot snapshot(String partition) {
        requireSafeIdentifier(partition);
        Long rowCount = jdbc.queryForObject(
                "SELECT count(*) FROM " + partition, Long.class);
        byte[] dump = copyOut("COPY (SELECT * FROM " + partition + " ORDER BY 1) TO STDOUT");
        return new Snapshot(partition, rowCount, new Digest(Digests.sha256Hex(dump)), dump);
    }

    @Override
    public void detach(String partition) {
        requireSafeIdentifier(partition);
        String parent = jdbc.queryForObject("""
                SELECT p.relname
                  FROM pg_inherits i
                  JOIN pg_class c ON c.oid = i.inhrelid
                  JOIN pg_class p ON p.oid = i.inhparent
                 WHERE c.relname = ?
                """, String.class, partition);
        if (parent == null) {
            throw new IllegalArgumentException("分区不存在或已摘离: " + partition);
        }
        // C-24：default 分区在场禁 CONCURRENTLY；单语句 autocommit（失败即热数据原封）
        jdbc.execute("ALTER TABLE " + parent + " DETACH PARTITION " + partition);
    }

    private byte[] copyOut(String copySql) {
        return jdbc.execute((java.sql.Connection con) -> {
            CopyManager copy = con.unwrap(PGConnection.class).getCopyAPI();
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            try {
                copy.copyOut(copySql, out);
            } catch (IOException e) {
                throw new IllegalStateException("分区导出 COPY 失败", e);
            }
            return out.toByteArray();
        });
    }

    private static void requireSafeIdentifier(String name) {
        if (name == null || !SAFE_IDENTIFIER.matcher(name).matches()) {
            throw new IllegalArgumentException("非法分区标识符");
        }
    }
}
