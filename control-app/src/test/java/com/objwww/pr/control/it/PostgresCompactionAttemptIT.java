package com.objwww.pr.control.it;

import com.objwww.pr.control.alert.domain.agent.CompactionAttempt;
import com.objwww.pr.control.alert.domain.repository.CompactionAttemptPort;
import com.objwww.pr.control.infrastructure.persistence.PostgresCompactionAttempt;
import com.objwww.pr.shared.Digest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * CL-07 压缩尝试台账真 PG 屏障（告警-Agent闭环修复 v1 §6.1/§6.4）：V102 唯一键
 * (task,source,policy,coalesce(config_epoch,-1)) insert-if-absent 恰一胜者（败者
 * 读胜者行）、状态 CAS 终态化不覆盖既成结果、CHECK 约束拒绝形状漂移（终态必带
 * settled_at、COMMITTED 必带 summary_id、状态封闭集）。并发以屏障+条件写裁决，
 * 不以 sleep 推测时序。
 */
class PostgresCompactionAttemptIT extends PostgresITBase {

    private static final Instant NOW = Instant.parse("2026-09-13T04:00:00Z");

    private CompactionAttemptPort attempts;
    private JdbcClient jdbc;

    @BeforeEach
    void setUp() {
        jdbc = JdbcClient.create(controlDataSource());
        attempts = new PostgresCompactionAttempt(jdbc);
    }

    private CompactionAttempt reserve(UUID runId, UUID taskId, String source) {
        return CompactionAttempt.reserve(UUID.randomUUID(), runId, taskId,
                source, Digest.sha256Of("policy").value(), "worker-1", 7L, 2L,
                11L, "compaction:" + taskId + ":" + source, NOW);
    }

    @Test
    @DisplayName("insert-if-absent：同逻辑键双预留恰一胜者，双方读同一胜者事实")
    void insertIfAbsentSingleWinner() {
        UUID runId = UUID.randomUUID();
        UUID taskId = UUID.randomUUID();
        CompactionAttempt first = attempts.insertIfAbsent(
                reserve(runId, taskId, "src-a"));
        assertThat(first.state()).isEqualTo(CompactionAttempt.RESERVED);

        CompactionAttempt loser = attempts.insertIfAbsent(
                reserve(runId, taskId, "src-a"));
        assertThat(loser.id()).as("败者读胜者行").isEqualTo(first.id());

        // config_epoch 空值以 -1 入唯一索引（无代际史 run 与有代际 run 不互撞）
        CompactionAttempt legacy = CompactionAttempt.reserve(UUID.randomUUID(),
                UUID.randomUUID(), UUID.randomUUID(), "src-b",
                Digest.sha256Of("policy").value(), null, null, null, 0L,
                "k2", NOW);
        assertThat(attempts.insertIfAbsent(legacy).id()).isEqualTo(legacy.id());
        // null epoch 同逻辑键 = 撞 uq 后回读胜者行（端口契约：收敛不抛），
        // 与 src-a 同式——不因 epoch 空值改变败者语义
        CompactionAttempt nullEpochLoser = attempts.insertIfAbsent(
                CompactionAttempt.reserve(UUID.randomUUID(), legacy.runId(),
                        legacy.taskId(), "src-b", Digest.sha256Of("policy").value(),
                        null, null, null, 0L, "k3", NOW));
        assertThat(nullEpochLoser.id())
                .as("null epoch 同逻辑键败者读胜者行").isEqualTo(legacy.id());
    }

    @Test
    @DisplayName("casState：IN_FLIGHT→终态恰一成功；终态行不可再迁移（不覆盖既成结果）")
    void casStateTerminalIsFrozen() {
        UUID runId = UUID.randomUUID();
        UUID taskId = UUID.randomUUID();
        CompactionAttempt row = attempts.insertIfAbsent(reserve(runId, taskId, "src-c"));
        assertThat(attempts.casState(row.id(), CompactionAttempt.RESERVED,
                CompactionAttempt.IN_FLIGHT, null, null)).isTrue();

        UUID summaryId = UUID.randomUUID();
        assertThat(attempts.casState(row.id(), CompactionAttempt.IN_FLIGHT,
                CompactionAttempt.COMMITTED, null, summaryId)).isTrue();
        assertThat(attempts.findById(row.id())).hasValueSatisfying(settled -> {
            assertThat(settled.state()).isEqualTo(CompactionAttempt.COMMITTED);
            assertThat(settled.summaryId()).isEqualTo(summaryId);
            assertThat(settled.settledAt()).isNotNull();
        });
        assertThat(attempts.casState(row.id(), CompactionAttempt.IN_FLIGHT,
                CompactionAttempt.FAILED, "LATE", null))
                .as("终态后的迟到 CAS 恒败").isFalse();
        assertThat(attempts.findById(row.id()).orElseThrow().state())
                .isEqualTo(CompactionAttempt.COMMITTED);
    }

    @Test
    @DisplayName("真并发双预留：屏障对齐后恰一 insert，双方回读同一行")
    void concurrentReserveExactlyOneWinner() throws Exception {
        UUID runId = UUID.randomUUID();
        UUID taskId = UUID.randomUUID();
        CyclicBarrier barrier = new CyclicBarrier(2);
        Callable<CompactionAttempt> racer = () -> {
            barrier.await(5, TimeUnit.SECONDS);
            CompactionAttempt stored = attempts.insertIfAbsent(
                    reserve(runId, taskId, "src-race"));
            return stored;
        };
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            List<Future<CompactionAttempt>> results = pool.invokeAll(
                    java.util.List.of(racer, racer));
            assertThat(results.get(0).get().id())
                    .as("双方（胜者与败者）读同一胜者行")
                    .isEqualTo(results.get(1).get().id());
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    @DisplayName("CHECK 约束拒绝形状漂移：非法状态/终态无 settled_at/COMMITTED 无 summary_id")
    void checkConstraintsRejectShapeDrift() {
        UUID runId = UUID.randomUUID();
        UUID taskId = UUID.randomUUID();
        assertThatThrownBy(() -> jdbcInsert(runId, taskId, "MAYBE", null, null))
                .as("状态不在封闭集").isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> jdbcInsert(runId, taskId, "REJECTED", null, null))
                .as("终态必须带 settled_at").isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> jdbcInsert(runId, taskId, "COMMITTED", NOW, null))
                .as("COMMITTED 必须带 summary_id")
                .isInstanceOf(DataAccessException.class);
        assertThat(jdbcInsert(runId, taskId, "IN_FLIGHT", null, null)).isTrue();
    }

    private boolean jdbcInsert(UUID runId, UUID taskId, String state,
            Instant settledAt, UUID summaryId) {
        return jdbc.sql("""
                insert into rca_compaction_attempt (id, run_id, task_id,
                    source_context_digest, policy_digest, owner, lease_epoch,
                    config_epoch, expected_revision, state, logical_action_key,
                    error_code, summary_id, created_at, settled_at)
                values (:id, :runId, :taskId, :source, :policy, 'w', 1, 1, 0, :state,
                    'k', null, :summaryId, :createdAt, :settledAt)
                """)
                .param("id", UUID.randomUUID())
                .param("runId", runId)
                .param("taskId", taskId)
                .param("source", Digest.sha256Of(state + settledAt + summaryId).value())
                .param("policy", Digest.sha256Of("policy").value())
                .param("state", state)
                .param("summaryId", summaryId)
                .param("createdAt", java.sql.Timestamp.from(NOW))
                .param("settledAt",
                        settledAt == null ? null : java.sql.Timestamp.from(settledAt))
                .update() == 1;
    }
}
