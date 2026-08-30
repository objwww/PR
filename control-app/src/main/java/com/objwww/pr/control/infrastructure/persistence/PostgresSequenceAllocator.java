package com.objwww.pr.control.infrastructure.persistence;

import com.objwww.pr.control.domain.service.SequenceAllocator;
import com.objwww.pr.control.domain.service.SequenceLease;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.util.Objects;
import java.util.UUID;

/**
 * SequenceAllocator 的 Postgres 实现：对 pr_subject 行 UPDATE ... RETURNING 原子领取。
 * 行锁持有至调用方事务结束（T2），sequence 与 epoch 同锁取出（v2.2 §3-3，I8）。
 *
 * <p>刻意不加 Spring 注解：默认 profile 无 DataSource，组件扫描装配会破坏空跑；
 * 接线（@Configuration + docker profile）属后续任务。CT-01 并发测试在服务器侧统一跑。
 */
public class PostgresSequenceAllocator implements SequenceAllocator {

    private static final String SQL = """
            UPDATE pr_subject
               SET next_outbox_sequence = next_outbox_sequence + 1,
                   updated_at = now()
             WHERE id = :id
            RETURNING next_outbox_sequence - 1 AS sequence,
                      publication_epoch      AS epoch
            """;

    private final JdbcClient jdbc;

    public PostgresSequenceAllocator(JdbcClient jdbc) {
        this.jdbc = Objects.requireNonNull(jdbc);
    }

    @Override
    public SequenceLease allocate(UUID prSubjectId) {
        return jdbc.sql(SQL)
                .param("id", Objects.requireNonNull(prSubjectId))
                .query((rs, rowNum) -> new SequenceLease(rs.getLong("sequence"), rs.getLong("epoch")))
                .single();
    }
}
