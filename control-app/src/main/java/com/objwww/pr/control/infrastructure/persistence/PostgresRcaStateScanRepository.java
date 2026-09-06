package com.objwww.pr.control.infrastructure.persistence;

import com.objwww.pr.control.alert.domain.repository.RcaStateScanRepository;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.util.List;
import java.util.UUID;

/**
 * rca_task/rca_run 状态批扫描（M4-03）：键集分页（id 升序 + afterId 断点），
 * coalesce 定型避免 PG 对未类型化 null 参数的歧义。
 */
public class PostgresRcaStateScanRepository implements RcaStateScanRepository {

    private final JdbcClient jdbc;

    public PostgresRcaStateScanRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public List<StateRow> scanTaskStates(UUID afterId, int limit) {
        return jdbc.sql("""
                select id, state from rca_task
                where id > coalesce(:after, '00000000-0000-0000-0000-000000000000'::uuid)
                order by id
                limit :limit
                """).param("after", afterId).param("limit", limit)
                .query((rs, n) -> new StateRow(rs.getObject("id", UUID.class),
                        rs.getString("state")))
                .list();
    }

    @Override
    public List<StateRow> scanRunStates(UUID afterId, int limit) {
        return jdbc.sql("""
                select id, state from rca_run
                where id > coalesce(:after, '00000000-0000-0000-0000-000000000000'::uuid)
                order by id
                limit :limit
                """).param("after", afterId).param("limit", limit)
                .query((rs, n) -> new StateRow(rs.getObject("id", UUID.class),
                        rs.getString("state")))
                .list();
    }
}
