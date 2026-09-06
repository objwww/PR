package com.objwww.pr.control.infrastructure.persistence;

import com.objwww.pr.control.alert.domain.dag.DependencyType;
import com.objwww.pr.control.alert.domain.dag.TaskEdge;
import com.objwww.pr.control.alert.domain.repository.TaskEdgeRepository;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.util.List;
import java.util.UUID;

/**
 * rca_task_edge 仓储（M4-04）：插入 + 按 run 载边。
 * 约束面（uq/自环/dep 枚举/V18 同 run 组合 FK）全部由 DB 强制。
 */
public class PostgresTaskEdgeRepository implements TaskEdgeRepository {

    private final JdbcClient jdbc;

    public PostgresTaskEdgeRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void insert(UUID runId, UUID fromTaskId, UUID toTaskId, DependencyType dependencyType) {
        jdbc.sql("""
                insert into rca_task_edge(id, run_id, from_task_id, to_task_id, dependency_type)
                values (:id, :run, :from, :to, :dep)
                """).param("id", UUID.randomUUID()).param("run", runId)
                .param("from", fromTaskId).param("to", toTaskId)
                .param("dep", dependencyType.name()).update();
    }

    @Override
    public List<TaskEdge> findByRunId(UUID runId) {
        return jdbc.sql("""
                select from_task_id, to_task_id, dependency_type from rca_task_edge
                where run_id = :run
                order by from_task_id, to_task_id
                """).param("run", runId)
                .query((rs, n) -> new TaskEdge(
                        rs.getObject("from_task_id", UUID.class).toString(),
                        rs.getObject("to_task_id", UUID.class).toString(),
                        DependencyType.valueOf(rs.getString("dependency_type"))))
                .list();
    }
}
