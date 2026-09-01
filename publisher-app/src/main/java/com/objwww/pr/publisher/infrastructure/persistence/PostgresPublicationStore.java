package com.objwww.pr.publisher.infrastructure.persistence;

import com.objwww.pr.publisher.domain.model.ClaimedCommand;
import com.objwww.pr.publisher.domain.model.DependencyRow;
import com.objwww.pr.publisher.domain.model.DriftCheckTarget;
import com.objwww.pr.publisher.domain.model.RepairRequestDraft;
import com.objwww.pr.publisher.domain.model.RepairOutcomeTarget;
import com.objwww.pr.publisher.domain.model.SubjectCursor;
import com.objwww.pr.publisher.domain.port.ExecutionEventAppender;
import com.objwww.pr.publisher.domain.port.PublicationStore;
import com.objwww.pr.publisher.domain.port.StaleLeaseException;
import com.objwww.pr.publisher.domain.service.T3AContext;
import com.objwww.pr.publisher.domain.service.T3ADecision;
import com.objwww.pr.shared.CommandType;
import com.objwww.pr.shared.DependencyMode;
import com.objwww.pr.shared.Digest;
import com.objwww.pr.shared.ExecutionEvent;
import com.objwww.pr.shared.FenceMode;
import com.objwww.pr.shared.OperationId;
import com.objwww.pr.shared.OutboxState;
import com.objwww.pr.shared.PublicationResourceState;
import com.objwww.pr.shared.PublicationResourceType;
import com.objwww.pr.shared.RemoteIdentityType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Function;

/**
 * PublicationStore 的 Postgres 实现（JdbcClient + TransactionTemplate，无组件注解，
 * 唯一装配点在 config；Publisher 角色只有 outbox SELECT/UPDATE——本类不存在 INSERT
 * outbox_command 的 SQL，I10 由 DB 角色兜底）。
 *
 * <p>游标推进纪律（评审修正 #5，I8）：终态同事务 {@code last_resolved_sequence = seq}，
 * 且仅当 {@code seq == last_resolved_sequence + 1}（连续才推进；兜底扫描遇乱序时留给下轮，
 * 绝不跳号）；MANUAL 不推进。
 */
public class PostgresPublicationStore implements PublicationStore {

    private static final String PRODUCER = "publisher-app";

    private static final String CLAIM_SQL = """
            WITH ready AS (
                SELECT operation_id
                  FROM outbox_command
                 WHERE state IN ('PENDING', 'RETRY_WAIT')
                   AND (next_attempt_at IS NULL OR next_attempt_at <= now())
                 ORDER BY aggregate_key, aggregate_sequence
                 LIMIT :limit
                 FOR UPDATE SKIP LOCKED
            )
            UPDATE outbox_command o
               SET lease_owner = :owner,
                   lease_until = now() + make_interval(secs => :leaseSeconds),
                   lease_epoch = o.lease_epoch + 1,
                   state = CASE WHEN o.state = 'RETRY_WAIT' THEN 'PENDING' ELSE o.state END,
                   updated_at = now()
              FROM ready
             WHERE o.operation_id = ready.operation_id
            RETURNING o.*
            """;

    private final JdbcClient jdbc;
    private final TransactionTemplate tx;
    private final ExecutionEventAppender eventAppender;

    public PostgresPublicationStore(JdbcClient jdbc, TransactionTemplate tx,
                                    ExecutionEventAppender eventAppender) {
        this.jdbc = Objects.requireNonNull(jdbc);
        this.tx = Objects.requireNonNull(tx);
        this.eventAppender = Objects.requireNonNull(eventAppender);
    }

    // ---------- T-claim ----------

    @Override
    public List<ClaimedCommand> claim(String leaseOwner, Duration leaseDuration, int batchSize) {
        return tx.execute(status -> jdbc.sql(CLAIM_SQL)
                .param("owner", leaseOwner)
                .param("leaseSeconds", leaseDuration.toSeconds())
                .param("limit", batchSize)
                .query(this::mapRow)
                .list());
    }

    // ---------- T3-A ----------

    @Override
    public T3ADecision prepare(UUID operationId, long leaseEpoch,
                               Function<T3AContext, T3ADecision> decider) {
        return tx.execute(status -> {
            ClaimedCommand command = lockCommand(operationId);
            if (command.leaseEpoch() != leaseEpoch || command.state() != OutboxState.PENDING) {
                // 租约被收回/状态已被推进：回滚，由调用方放弃（B-2）
                throw new StaleLeaseException("T3-A 租约栅栏失效: " + operationId);
            }
            List<DependencyRow> deps = jdbc.sql("""
                            SELECT d.depends_on_operation_id, o.state, d.dependency_mode
                              FROM outbox_dependency d
                              JOIN outbox_command o ON o.operation_id = d.depends_on_operation_id
                             WHERE d.operation_id = :id
                            """)
                    .param("id", operationId)
                    .query((rs, n) -> new DependencyRow(
                            new OperationId(rs.getObject("depends_on_operation_id", UUID.class)),
                            OutboxState.valueOf(rs.getString("state")),
                            DependencyMode.valueOf(rs.getString("dependency_mode"))))
                    .list();
            SubjectCursor cursor = jdbc.sql("""
                            SELECT publication_epoch, last_resolved_sequence
                              FROM pr_subject WHERE id = :id FOR UPDATE
                            """)
                    .param("id", command.prSubjectId())
                    .query((rs, n) -> new SubjectCursor(
                            rs.getLong("publication_epoch"), rs.getLong("last_resolved_sequence")))
                    .single();

            T3ADecision decision = decider.apply(new T3AContext(command, deps, cursor));
            applyT3A(command, leaseEpoch, decision);
            return decision;
        });
    }

    private void applyT3A(ClaimedCommand command, long leaseEpoch, T3ADecision decision) {
        UUID id = command.operationId().value();
        switch (decision.action()) {
            case PROCEED -> guardedUpdate("""
                            UPDATE outbox_command SET state = 'IN_FLIGHT', updated_at = now()
                             WHERE operation_id = :id AND lease_epoch = :le AND state = 'PENDING'
                            """, id, leaseEpoch);
            case MARK_SUPERSEDED -> {
                guardedUpdate("""
                                UPDATE outbox_command SET state = 'SUPERSEDED', last_error_code = :err, updated_at = now()
                                 WHERE operation_id = :id AND lease_epoch = :le AND state = 'PENDING'
                                """, id, leaseEpoch, nullableParams("err", decision.errorCode()));
                advanceCursor(command.prSubjectId(), command.aggregateSequence());
                cascadeSupersede(id);
            }
            case MARK_FAILED_TERMINAL -> {
                guardedUpdate("""
                                UPDATE outbox_command SET state = 'FAILED_TERMINAL', last_error_code = :err, updated_at = now()
                                 WHERE operation_id = :id AND lease_epoch = :le AND state = 'PENDING'
                                """, id, leaseEpoch, nullableParams("err", decision.errorCode()));
                advanceCursor(command.prSubjectId(), command.aggregateSequence());
                appendDecisionEvent(command, decision);
            }
            case DEFER -> releaseLease(id, leaseEpoch);
            case RECORD_GAP -> {
                appendDecisionEvent(command, decision);
                releaseLease(id, leaseEpoch);
            }
        }
    }

    private void releaseLease(UUID operationId, long leaseEpoch) {
        guardedUpdate("""
                        UPDATE outbox_command SET lease_owner = NULL, lease_until = NULL, updated_at = now()
                         WHERE operation_id = :id AND lease_epoch = :le
                        """, operationId, leaseEpoch);
    }

    // ---------- T3-B ----------

    @Override
    public void confirm(UUID operationId, long leaseEpoch, String remoteId, String remoteUrl,
                        PublicationResourceType resourceType, String marker, ExecutionEvent event) {
        tx.executeWithoutResult(status -> {
            guardedUpdate("""
                            UPDATE outbox_command
                               SET state = 'CONFIRMED', remote_id = :rid, remote_url = :rurl,
                                   confirmed_at = now(), updated_at = now()
                             WHERE operation_id = :id AND lease_epoch = :le AND state = 'IN_FLIGHT'
                            """, operationId, leaseEpoch,
                    nullableParams("rid", remoteId, "rurl", remoteUrl));
            ClaimedCommand command = lockCommand(operationId);
            advanceCursor(command.prSubjectId(), command.aggregateSequence());
            insertResource(command, resourceType, remoteId, remoteUrl, marker);
            eventAppender.append(event);
        });
    }

    @Override
    public void confirmRepairReplacement(UUID operationId, long leaseEpoch, UUID oldResourceId,
                                         String remoteId, String remoteUrl,
                                         PublicationResourceType resourceType, String marker,
                                         ExecutionEvent event) {
        tx.executeWithoutResult(status -> {
            guardedUpdate("""
                    UPDATE outbox_command SET state='CONFIRMED',remote_id=:rid,remote_url=:rurl,
                        confirmed_at=now(),updated_at=now()
                     WHERE operation_id=:id AND lease_epoch=:le AND state='IN_FLIGHT'
                    """, operationId, leaseEpoch, nullableParams("rid", remoteId, "rurl", remoteUrl));
            ClaimedCommand command = lockCommand(operationId);
            advanceCursor(command.prSubjectId(), command.aggregateSequence());
            jdbc.sql("""
                    INSERT INTO publication_resource (
                        id,resource_type,created_by_operation_id,pr_subject_id,
                        remote_id,remote_url,marker,state,replaces_resource_id,created_at,updated_at
                    ) VALUES (:newId,:type,:op,:subject,:rid,:url,:marker,'PRESENT',:oldId,now(),now())
                    ON CONFLICT (resource_type,remote_id) DO NOTHING
                    """).param("newId", UUID.randomUUID()).param("type", resourceType.name())
                    .param("op", operationId).param("subject", command.prSubjectId())
                    .param("rid", remoteId).param("url", remoteUrl).param("marker", marker)
                    .param("oldId", oldResourceId).update();
            jdbc.sql("""
                    UPDATE publication_resource SET state='REPAIRED',repaired_by_operation_id=:op,
                        updated_at=now() WHERE id=:oldId AND state IN ('MISSING','UNKNOWN')
                    """).param("op", operationId).param("oldId", oldResourceId).update();
            finishRepairRequest(operationId, "REPAIRED", null);
            eventAppender.append(event);
        });
    }

    @Override
    public void confirmRepairNoop(UUID operationId, long leaseEpoch, UUID oldResourceId,
                                  String remoteId, String remoteUrl, ExecutionEvent event) {
        tx.executeWithoutResult(status -> {
            guardedUpdate("""
                    UPDATE outbox_command SET state='CONFIRMED',remote_id=:rid,remote_url=:rurl,
                        confirmed_at=now(),updated_at=now()
                     WHERE operation_id=:id AND lease_epoch=:le AND state='IN_FLIGHT'
                    """, operationId, leaseEpoch, nullableParams("rid", remoteId, "rurl", remoteUrl));
            ClaimedCommand command = lockCommand(operationId);
            advanceCursor(command.prSubjectId(), command.aggregateSequence());
            jdbc.sql("""
                    UPDATE publication_resource SET state='PRESENT',repaired_by_operation_id=:op,
                        drift_detected_at=NULL,updated_at=now() WHERE id=:oldId
                    """).param("op", operationId).param("oldId", oldResourceId).update();
            finishRepairRequest(operationId, "REPAIRED", null);
            eventAppender.append(event);
        });
    }

    @Override
    public java.util.Optional<ClaimedCommand> findRepairOrigin(UUID oldResourceId) {
        return jdbc.sql("""
                        SELECT o.* FROM publication_resource r
                        JOIN outbox_command o ON o.operation_id=r.created_by_operation_id
                        WHERE r.id=:id
                """).param("id", oldResourceId).query(this::mapRow).optional();
    }

    @Override
    public java.util.Optional<UUID> findRepairResourceByOperation(UUID operationId) {
        return jdbc.sql("SELECT publication_resource_id FROM repair_request WHERE repair_operation_id=:op")
                .param("op", operationId).query(UUID.class).optional();
    }

    @Override
    public void reconcileConfirmRepairReplacement(UUID operationId, UUID oldResourceId,
                                                   String remoteId, String remoteUrl,
                                                   PublicationResourceType resourceType, String marker,
                                                   ExecutionEvent event) {
        tx.executeWithoutResult(status -> {
            int updated = jdbc.sql("""
                            UPDATE outbox_command SET state='CONFIRMED',remote_id=:rid,remote_url=:rurl,
                                confirmed_at=now(),updated_at=now()
                             WHERE operation_id=:id AND state='RECONCILING'
                            """)
                    .param("id", operationId)
                    .param("rid", remoteId)
                    .param("rurl", remoteUrl)
                    .update();
            if (updated == 0) {
                throw new StaleLeaseException("reconcileConfirmRepairReplacement 状态守卫失效: " + operationId);
            }
            ClaimedCommand command = lockCommand(operationId);
            advanceCursor(command.prSubjectId(), command.aggregateSequence());
            jdbc.sql("""
                    INSERT INTO publication_resource (
                        id,resource_type,created_by_operation_id,pr_subject_id,remote_id,remote_url,
                        marker,state,replaces_resource_id,created_at,updated_at)
                    VALUES (:id,:type,:op,:subject,:rid,:url,:marker,'PRESENT',:old,now(),now())
                    ON CONFLICT (resource_type,remote_id) DO NOTHING
                    """).param("id", UUID.randomUUID()).param("type", resourceType.name())
                    .param("op", operationId).param("subject", command.prSubjectId())
                    .param("rid", remoteId).param("url", remoteUrl).param("marker", marker)
                    .param("old", oldResourceId).update();
            jdbc.sql("UPDATE publication_resource SET state='REPAIRED',repaired_by_operation_id=:op,updated_at=now() WHERE id=:id")
                    .param("op", operationId).param("id", oldResourceId).update();
            finishRepairRequest(operationId, "REPAIRED", null);
            eventAppender.append(event);
        });
    }

    @Override
    public List<RepairOutcomeTarget> findRepairOutcomes(int limit) {
        return jdbc.sql("""
                SELECT rr.id,rr.repair_operation_id,rr.repair_run_id,o.state,o.pr_revision_id,
                       rr.publication_resource_id
                  FROM repair_request rr JOIN outbox_command o ON o.operation_id=rr.repair_operation_id
                 WHERE rr.state='DISPATCHED'
                   AND o.state IN ('CONFIRMED','FAILED_TERMINAL','SUPERSEDED','MANUAL')
                 ORDER BY rr.created_at LIMIT :limit
                """).param("limit", limit).query((rs,n) -> new RepairOutcomeTarget(
                        rs.getObject("id", UUID.class), rs.getObject("repair_operation_id", UUID.class),
                        OutboxState.valueOf(rs.getString("state")),
                        rs.getObject("repair_run_id", UUID.class),
                        rs.getObject("pr_revision_id", UUID.class),
                        rs.getObject("publication_resource_id", UUID.class))).list();
    }

    @Override
    public boolean projectRepairOutcome(UUID requestId, String targetState, String error,
                                        ExecutionEvent event) {
        return Boolean.TRUE.equals(tx.execute(status -> {
            int n = jdbc.sql("""
                    UPDATE repair_request SET state=:state,last_error=:error,updated_at=now()
                     WHERE id=:id AND state='DISPATCHED'
                    """).param("state", targetState).param("error", error, java.sql.Types.VARCHAR)
                    .param("id", requestId).update();
            if (n == 1 && event != null) eventAppender.append(event);
            return n == 1;
        }));
    }

    private void finishRepairRequest(UUID operationId, String state, String error) {
        jdbc.sql("""
                UPDATE repair_request SET state=:state,last_error=:error,updated_at=now()
                 WHERE repair_operation_id=:op AND state='DISPATCHED'
                """).param("state", state).param("error", error, java.sql.Types.VARCHAR)
                .param("op", operationId).update();
    }

    @Override
    public void markReconciling(UUID operationId, long leaseEpoch, Instant reconcileAfter,
                                ExecutionEvent event) {
        tx.executeWithoutResult(status -> {
            guardedUpdate("""
                            UPDATE outbox_command
                               SET state = 'RECONCILING', reconcile_after = :after, updated_at = now()
                             WHERE operation_id = :id AND lease_epoch = :le AND state = 'IN_FLIGHT'
                            """, operationId, leaseEpoch, Map.of("after", Timestamp.from(reconcileAfter)));
            eventAppender.append(event);
        });
    }

    @Override
    public void markRetryWait(UUID operationId, long leaseEpoch, Instant nextAttemptAt, String errorCode) {
        tx.executeWithoutResult(status -> guardedUpdate("""
                        UPDATE outbox_command
                           SET state = 'RETRY_WAIT', attempt_count = attempt_count + 1,
                               next_attempt_at = :nextAt, last_error_code = :err,
                               lease_owner = NULL, lease_until = NULL, updated_at = now()
                         WHERE operation_id = :id AND lease_epoch = :le AND state = 'IN_FLIGHT'
                        """, operationId, leaseEpoch,
                nullableParams("nextAt", Timestamp.from(nextAttemptAt), "err", errorCode)));
    }

    @Override
    public void markSuperseded(UUID operationId, long leaseEpoch, String errorCode) {
        tx.executeWithoutResult(status -> {
            guardedUpdate("""
                            UPDATE outbox_command SET state = 'SUPERSEDED', last_error_code = :err, updated_at = now()
                             WHERE operation_id = :id AND lease_epoch = :le AND state = 'IN_FLIGHT'
                            """, operationId, leaseEpoch, nullableParams("err", errorCode));
            ClaimedCommand command = lockCommand(operationId);
            advanceCursor(command.prSubjectId(), command.aggregateSequence());
            cascadeSupersede(operationId);
        });
    }

    @Override
    public void markFailedTerminal(UUID operationId, long leaseEpoch, String errorCode,
                                   ExecutionEvent event) {
        tx.executeWithoutResult(status -> {
            guardedUpdate("""
                            UPDATE outbox_command SET state = 'FAILED_TERMINAL', last_error_code = :err, updated_at = now()
                             WHERE operation_id = :id AND lease_epoch = :le AND state = 'IN_FLIGHT'
                            """, operationId, leaseEpoch, nullableParams("err", errorCode));
            ClaimedCommand command = lockCommand(operationId);
            advanceCursor(command.prSubjectId(), command.aggregateSequence());
            if (event != null) {
                eventAppender.append(event);
            }
        });
    }

    @Override
    public void markManual(UUID operationId, long leaseEpoch, String errorCode) {
        tx.executeWithoutResult(status -> guardedUpdate("""
                        UPDATE outbox_command SET state = 'MANUAL', last_error_code = :err, updated_at = now()
                         WHERE operation_id = :id AND lease_epoch = :le AND state = 'IN_FLIGHT'
                        """, operationId, leaseEpoch, nullableParams("err", errorCode)));
        // MANUAL 不推进游标：阻塞同 PR 后续命令直到人工解决（保序 > 可用性）
    }

    // ---------- Reconciler 扫描 ----------

    @Override
    public List<ClaimedCommand> findExpiredInFlight(Instant now, int limit) {
        return jdbc.sql("""
                        SELECT * FROM outbox_command
                         WHERE state = 'IN_FLIGHT' AND lease_until IS NOT NULL AND lease_until < :now
                         ORDER BY lease_until LIMIT :limit
                        """)
                .param("now", Timestamp.from(now))
                .param("limit", limit)
                .query(this::mapRow)
                .list();
    }

    @Override
    public boolean toReconciling(UUID operationId, Instant now, Instant reconcileAfter,
                                 ExecutionEvent event) {
        return tx.execute(status -> {
            int updated = jdbc.sql("""
                            UPDATE outbox_command
                               SET state = 'RECONCILING', reconcile_after = :after,
                                   lease_owner = NULL, lease_until = NULL, updated_at = now()
                             WHERE operation_id = :id AND state = 'IN_FLIGHT' AND lease_until < :now
                            """)
                    .param("id", operationId)
                    .param("after", Timestamp.from(reconcileAfter))
                    .param("now", Timestamp.from(now))
                    .update();
            if (updated > 0) {
                eventAppender.append(event);
            }
            return updated > 0;
        });
    }

    @Override
    public List<ClaimedCommand> findDueReconciling(Instant now, int limit) {
        return jdbc.sql("""
                        SELECT * FROM outbox_command
                         WHERE state = 'RECONCILING' AND reconcile_after IS NOT NULL AND reconcile_after <= :now
                         ORDER BY reconcile_after LIMIT :limit
                        """)
                .param("now", Timestamp.from(now))
                .param("limit", limit)
                .query(this::mapRow)
                .list();
    }

    @Override
    public void reconcileConfirm(UUID operationId, String remoteId, String remoteUrl,
                                 PublicationResourceType resourceType, String marker,
                                 ExecutionEvent event) {
        tx.executeWithoutResult(status -> {
            int updated = jdbc.sql("""
                            UPDATE outbox_command
                               SET state = 'CONFIRMED', remote_id = :rid, remote_url = :rurl,
                                   confirmed_at = now(), updated_at = now()
                             WHERE operation_id = :id AND state = 'RECONCILING'
                            """)
                    .param("id", operationId)
                    .param("rid", remoteId)
                    .param("rurl", remoteUrl)
                    .update();
            if (updated == 0) {
                throw new StaleLeaseException("reconcileConfirm 状态守卫失效: " + operationId);
            }
            ClaimedCommand command = lockCommand(operationId);
            advanceCursor(command.prSubjectId(), command.aggregateSequence());
            insertResource(command, resourceType, remoteId, remoteUrl, marker);
            eventAppender.append(event);
        });
    }

    @Override
    public void reconcileRetryWait(UUID operationId, Instant nextAttemptAt) {
        tx.executeWithoutResult(status -> {
            int updated = jdbc.sql("""
                            UPDATE outbox_command
                               SET state = 'RETRY_WAIT', next_attempt_at = :nextAt,
                                   reconcile_not_found_count = 0, reconcile_after = NULL, updated_at = now()
                             WHERE operation_id = :id AND state = 'RECONCILING'
                            """)
                    .param("id", operationId)
                    .param("nextAt", Timestamp.from(nextAttemptAt))
                    .update();
            if (updated == 0) {
                throw new StaleLeaseException("reconcileRetryWait 状态守卫失效: " + operationId);
            }
        });
    }

    @Override
    public int reconcileUnknown(UUID operationId, Instant nextReconcileAfter) {
        Integer count = tx.execute(status -> jdbc.sql("""
                        UPDATE outbox_command
                           SET reconcile_not_found_count = reconcile_not_found_count + 1,
                               reconcile_after = :next, updated_at = now()
                         WHERE operation_id = :id AND state = 'RECONCILING'
                        RETURNING reconcile_not_found_count
                        """)
                .param("id", operationId)
                .param("next", Timestamp.from(nextReconcileAfter))
                .query(Integer.class)
                .optional()
                .orElse(null));
        if (count == null) {
            throw new StaleLeaseException("reconcileUnknown 状态守卫失效: " + operationId);
        }
        return count;
    }

    @Override
    public void reconcileManual(UUID operationId, String errorCode) {
        tx.executeWithoutResult(status -> jdbc.sql("""
                        UPDATE outbox_command SET state = 'MANUAL', last_error_code = :err, updated_at = now()
                         WHERE operation_id = :id AND state = 'RECONCILING'
                        """)
                .param("id", operationId)
                .param("err", errorCode)
                .update());
    }

    @Override
    public List<ClaimedCommand> findStaleEpoch(int limit) {
        return jdbc.sql("""
                        SELECT o.* FROM outbox_command o
                        JOIN pr_subject s ON s.id = o.pr_subject_id
                         WHERE o.state IN ('PENDING', 'RETRY_WAIT')
                           AND o.publication_epoch < s.publication_epoch
                         ORDER BY o.aggregate_key, o.aggregate_sequence
                         LIMIT :limit
                        """)
                .param("limit", limit)
                .query(this::mapRow)
                .list();
    }

    @Override
    public void supersedeStaleEpoch(UUID operationId) {
        tx.executeWithoutResult(status -> {
            // 事务内复核：状态仍可被 supersede 且 epoch 仍落后才生效（并发 fence，E1）
            Map<String, Object> row = jdbc.sql("""
                            SELECT o.pr_subject_id, o.aggregate_sequence, o.state,
                                   o.publication_epoch, s.publication_epoch AS current_epoch
                              FROM outbox_command o
                              JOIN pr_subject s ON s.id = o.pr_subject_id
                             WHERE o.operation_id = :id
                             FOR UPDATE OF o
                            """)
                    .param("id", operationId)
                    .query((rs, n) -> Map.<String, Object>of(
                            "pr_subject_id", rs.getObject("pr_subject_id", UUID.class),
                            "aggregate_sequence", rs.getLong("aggregate_sequence"),
                            "state", rs.getString("state"),
                            "publication_epoch", rs.getLong("publication_epoch"),
                            "current_epoch", rs.getLong("current_epoch")))
                    .optional()
                    .orElse(null);
            if (row == null) {
                return;
            }
            String state = (String) row.get("state");
            if (!state.equals("PENDING") && !state.equals("RETRY_WAIT")) {
                return; // IN_FLIGHT 不级联——先对账（I7）
            }
            if ((long) row.get("publication_epoch") >= (long) row.get("current_epoch")) {
                return;
            }
            jdbc.sql("""
                            UPDATE outbox_command SET state = 'SUPERSEDED', last_error_code = 'STALE_EPOCH',
                                   updated_at = now()
                             WHERE operation_id = :id
                            """)
                    .param("id", operationId)
                    .update();
            advanceCursor((UUID) row.get("pr_subject_id"), (long) row.get("aggregate_sequence"));
            cascadeSupersede(operationId);
        });
    }

    // ---------- Drift 巡检（M1-T08 方案 §4.6；只 UPDATE 观测列——V3 列级授权，CT-20 顺带真实验证） ----------

    @Override
    public List<DriftCheckTarget> findDueForDriftCheck(int limit) {
        // 公平：最久未查先查（ORDER BY next_check_at + LIMIT 即 API 预算，E2E-15）；
        // MISSING 在扫描集内是为了低频复核（§4.6），重复扫描不重复发事件由 markMissing 守卫（ST-22）
        return jdbc.sql("""
                        SELECT r.id AS resource_id, r.resource_type, r.remote_id AS res_remote_id,
                               r.remote_url AS res_remote_url, r.marker, r.state AS resource_state,
                               r.check_error_count, o.*
                          FROM publication_resource r
                          JOIN outbox_command o ON r.created_by_operation_id = o.operation_id
                         WHERE r.state IN ('PRESENT', 'MISSING') AND o.state = 'CONFIRMED'
                           AND r.next_check_at <= now()
                         ORDER BY r.next_check_at
                         LIMIT :limit
                        """)
                .param("limit", limit)
                .query((rs, n) -> new DriftCheckTarget(
                        rs.getObject("resource_id", UUID.class),
                        PublicationResourceType.valueOf(rs.getString("resource_type")),
                        rs.getString("res_remote_id"),
                        rs.getString("res_remote_url"),
                        rs.getString("marker"),
                        PublicationResourceState.valueOf(rs.getString("resource_state")),
                        rs.getInt("check_error_count"),
                        mapRow(rs, n)))
                .list();
    }

    @Override
    public void markCheckedPresent(UUID resourceId, Duration interval) {
        jdbc.sql("""
                        UPDATE publication_resource
                           SET state = 'PRESENT', last_checked_at = now(),
                               next_check_at = now() + make_interval(secs => :secs),
                               check_error_count = 0, updated_at = now()
                         WHERE id = :id AND state IN ('PRESENT', 'MISSING')
                        """)
                .param("id", resourceId)
                .param("secs", interval.toSeconds())
                .update();
    }

    @Override
    public boolean markContentDrift(UUID resourceId, Digest observedDigest,
                                    Duration interval, ExecutionEvent event) {
        return Boolean.TRUE.equals(tx.execute(status -> {
            String old = jdbc.sql("SELECT content_drift_digest FROM publication_resource WHERE id=:id FOR UPDATE")
                    .param("id", resourceId).query(String.class).optional().orElse(null);
            int updated = jdbc.sql("""
                    UPDATE publication_resource SET state='PRESENT',content_drift_detected_at=now(),
                        content_drift_digest=:digest,last_checked_at=now(),
                        next_check_at=now()+make_interval(secs=>:secs),
                        check_error_count=0,updated_at=now()
                     WHERE id=:id AND state IN ('PRESENT','MISSING')
                    """).param("id", resourceId).param("digest", observedDigest.value())
                    .param("secs", interval.toSeconds()).update();
            boolean episode = updated == 1 && !observedDigest.value().equals(old == null ? null : old.trim());
            if (episode && event != null) eventAppender.append(event);
            return episode;
        }));
    }

    @Override
    public void clearContentDrift(UUID resourceId, Duration interval) {
        jdbc.sql("""
                UPDATE publication_resource SET state='PRESENT',content_drift_detected_at=NULL,
                    content_drift_digest=NULL,
                    last_checked_at=now(),next_check_at=now()+make_interval(secs=>:secs),
                    check_error_count=0,updated_at=now()
                 WHERE id=:id AND state IN ('PRESENT','MISSING')
                """).param("id", resourceId).param("secs", interval.toSeconds()).update();
    }

    @Override
    public boolean markMissing(UUID resourceId, Duration recheckInterval, ExecutionEvent event) {
        return Boolean.TRUE.equals(tx.execute(status -> {
            // 行锁读旧态：事件恰好一次的守卫在 DB 侧（ST-22），不依赖调用方看到的快照
            String oldState = jdbc.sql(
                            "SELECT state FROM publication_resource WHERE id = :id FOR UPDATE")
                    .param("id", resourceId)
                    .query(String.class)
                    .optional()
                    .orElse(null);
            if (oldState == null) {
                return false;
            }
            int updated = jdbc.sql("""
                            UPDATE publication_resource
                               SET state = 'MISSING',
                                   drift_detected_at = COALESCE(drift_detected_at, now()),
                                   last_checked_at = now(),
                                   next_check_at = now() + make_interval(secs => :secs),
                                   check_error_count = 0, updated_at = now()
                             WHERE id = :id AND state IN ('PRESENT', 'MISSING', 'UNKNOWN')
                            """)
                    .param("id", resourceId)
                    .param("secs", recheckInterval.toSeconds())
                    .update();
            if (updated == 0) {
                return false; // RETIRED/REPAIRED 不参与巡检（防御；扫描本不会选出）
            }
            if (!"MISSING".equals(oldState) && event != null) {
                eventAppender.append(event);
                return true;
            }
            return false; // 已 MISSING：低频复核只重排期，不重复发事件
        }));
    }

    @Override
    public boolean markMissingWithRepair(UUID resourceId, Duration recheckInterval,
                                         ExecutionEvent driftEvent, RepairRequestDraft repair,
                                         ExecutionEvent repairEvent) {
        return Boolean.TRUE.equals(tx.execute(status -> {
            String oldState = jdbc.sql(
                            "SELECT state FROM publication_resource WHERE id = :id FOR UPDATE")
                    .param("id", resourceId).query(String.class).optional().orElse(null);
            if (oldState == null) return false;
            int updated = jdbc.sql("""
                            UPDATE publication_resource
                               SET state = 'MISSING',
                                   drift_detected_at = COALESCE(drift_detected_at, now()),
                                   last_checked_at = now(),
                                   next_check_at = now() + make_interval(secs => :secs),
                                   check_error_count = 0, updated_at = now()
                             WHERE id = :id AND state IN ('PRESENT', 'MISSING', 'UNKNOWN')
                            """)
                    .param("id", resourceId).param("secs", recheckInterval.toSeconds()).update();
            if (updated == 0 || "MISSING".equals(oldState)) return false;
            eventAppender.append(driftEvent);
            int inserted = jdbc.sql("""
                            INSERT INTO repair_request (
                                id, publication_resource_id, resource_type, policy_tier, state,
                                attempt_count, max_attempts, created_at, updated_at
                            ) VALUES (:id, :resourceId, :type, :tier, 'PENDING',
                                      0, :maxAttempts, :createdAt, :createdAt)
                            ON CONFLICT (publication_resource_id)
                                WHERE state IN ('PENDING','APPROVED','DISPATCHED','RETRY_WAIT')
                            DO NOTHING
                            """)
                    .param("id", repair.id()).param("resourceId", repair.publicationResourceId())
                    .param("type", repair.resourceType().name()).param("tier", repair.policyTier().name())
                    .param("maxAttempts", repair.maxAttempts())
                    .param("createdAt", Timestamp.from(repair.createdAt())).update();
            if (inserted == 1 && repairEvent != null) eventAppender.append(repairEvent);
            return true;
        }));
    }

    @Override
    public void markUnknown(UUID resourceId, ExecutionEvent event) {
        tx.executeWithoutResult(status -> {
            int updated = jdbc.sql("""
                            UPDATE publication_resource
                               SET state = 'UNKNOWN', last_checked_at = now(), updated_at = now()
                             WHERE id = :id AND state = 'PRESENT'
                            """)
                    .param("id", resourceId)
                    .update();
            if (updated > 0 && event != null) {
                eventAppender.append(event); // 权限告警与状态翻转同事务（E2E-18）
            }
        });
    }

    @Override
    public int markCheckError(UUID resourceId, Duration backoff) {
        Integer count = tx.execute(status -> jdbc.sql("""
                        UPDATE publication_resource
                           SET check_error_count = check_error_count + 1,
                               next_check_at = now() + make_interval(secs => :secs),
                               updated_at = now()
                         WHERE id = :id AND state IN ('PRESENT', 'MISSING')
                         RETURNING check_error_count
                        """)
                .param("id", resourceId)
                .param("secs", backoff.toSeconds())
                .query(Integer.class)
                .optional()
                .orElse(null));
        return count == null ? 0 : count;
    }

    // ---------- 内部 ----------

    /** 级联 supersede：REQUIRE_* 依赖方在 PENDING/RETRY_WAIT 时同事务连锁（E3；OPTIONAL 不级联；IN_FLIGHT 不级联，I7） */
    private void cascadeSupersede(UUID supersededOperationId) {
        Deque<UUID> queue = new ArrayDeque<>();
        queue.add(supersededOperationId);
        while (!queue.isEmpty()) {
            UUID current = queue.poll();
            List<Map<String, Object>> dependents = jdbc.sql("""
                            SELECT d.operation_id, o.aggregate_sequence, o.pr_subject_id
                              FROM outbox_dependency d
                              JOIN outbox_command o ON o.operation_id = d.operation_id
                             WHERE d.depends_on_operation_id = :op
                               AND o.state IN ('PENDING', 'RETRY_WAIT')
                               AND d.dependency_mode <> 'OPTIONAL'
                             ORDER BY o.aggregate_sequence
                            """)
                    .param("op", current)
                    .query((rs, n) -> Map.<String, Object>of(
                            "operation_id", rs.getObject("operation_id", UUID.class),
                            "aggregate_sequence", rs.getLong("aggregate_sequence"),
                            "pr_subject_id", rs.getObject("pr_subject_id", UUID.class)))
                    .list();
            for (Map<String, Object> dependent : dependents) {
                UUID depId = (UUID) dependent.get("operation_id");
                int updated = jdbc.sql("""
                                UPDATE outbox_command SET state = 'SUPERSEDED', last_error_code = 'CASCADE_SUPERSEDED',
                                       updated_at = now()
                                 WHERE operation_id = :id AND state IN ('PENDING', 'RETRY_WAIT')
                                """)
                        .param("id", depId)
                        .update();
                if (updated > 0) {
                    advanceCursor((UUID) dependent.get("pr_subject_id"),
                            (long) dependent.get("aggregate_sequence"));
                    queue.add(depId);
                }
            }
        }
    }

    /** 连续才推进（I8）：seq 恰为游标 +1 才 UPDATE，否则留给前序命令先解决 */
    private void advanceCursor(UUID prSubjectId, long sequence) {
        jdbc.sql("""
                        UPDATE pr_subject
                           SET last_resolved_sequence = :seq, updated_at = now()
                         WHERE id = :id AND last_resolved_sequence = :seq - 1
                        """)
                .param("id", prSubjectId)
                .param("seq", sequence)
                .update();
    }

    private void insertResource(ClaimedCommand command, PublicationResourceType resourceType,
                                String remoteId, String remoteUrl, String marker) {
        jdbc.sql("""
                        INSERT INTO publication_resource (
                            id, resource_type, created_by_operation_id, pr_subject_id,
                            remote_id, remote_url, marker, state, created_at, updated_at
                        ) VALUES (
                            :id, :type, :opId, :subjectId, :rid, :rurl, :marker, 'PRESENT', now(), now()
                        )
                        ON CONFLICT (resource_type, remote_id) DO NOTHING
                        """)
                .param("id", UUID.randomUUID())
                .param("type", resourceType.name())
                .param("opId", command.operationId().value())
                .param("subjectId", command.prSubjectId())
                .param("rid", remoteId)
                .param("rurl", remoteUrl)
                .param("marker", marker)
                .update();
    }

    private void appendDecisionEvent(ClaimedCommand command, T3ADecision decision) {
        if (decision.eventType() == null) {
            return;
        }
        eventAppender.append(new ExecutionEvent(UUID.randomUUID(),
                command.reviewRunId(), command.prRevisionId(), null, null,
                decision.eventType(), 1, null, command.reviewRunId(), PRODUCER,
                decision.eventPayload(), Instant.now()));
    }

    private ClaimedCommand lockCommand(UUID operationId) {
        return jdbc.sql("SELECT * FROM outbox_command WHERE operation_id = :id FOR UPDATE")
                .param("id", operationId)
                .query(this::mapRow)
                .optional()
                .orElseThrow(() -> new IllegalStateException("outbox_command 不存在: " + operationId));
    }

    private void guardedUpdate(String sql, UUID operationId, long leaseEpoch) {
        guardedUpdate(sql, operationId, leaseEpoch, Map.of());
    }

    /** 允许 null 值的参数表（Map.of 不接受 null） */
    private static Map<String, Object> nullableParams(String k1, Object v1) {
        Map<String, Object> params = new java.util.HashMap<>();
        params.put(k1, v1);
        return params;
    }

    private static Map<String, Object> nullableParams(String k1, Object v1, String k2, Object v2) {
        Map<String, Object> params = nullableParams(k1, v1);
        params.put(k2, v2);
        return params;
    }

    private void guardedUpdate(String sql, UUID operationId, long leaseEpoch,
                               Map<String, Object> extraParams) {
        var spec = jdbc.sql(sql).param("id", operationId).param("le", leaseEpoch);
        for (Map.Entry<String, Object> entry : extraParams.entrySet()) {
            spec = spec.param(entry.getKey(), entry.getValue());
        }
        if (spec.update() == 0) {
            throw new StaleLeaseException("租约栅栏失效（UPDATE 0 行）: " + operationId);
        }
    }

    private ClaimedCommand mapRow(ResultSet rs, int rowNum) throws SQLException {
        String artifactDigest = rs.getString("payload_artifact_digest");
        return new ClaimedCommand(
                new OperationId(rs.getObject("operation_id", UUID.class)),
                rs.getObject("pr_subject_id", UUID.class),
                rs.getObject("review_run_id", UUID.class),
                rs.getObject("pr_revision_id", UUID.class),
                rs.getString("aggregate_key"),
                rs.getLong("aggregate_sequence"),
                rs.getLong("publication_epoch"),
                FenceMode.valueOf(rs.getString("fence_mode")),
                CommandType.valueOf(rs.getString("command_type")),
                OutboxState.valueOf(rs.getString("state")),
                rs.getString("policy_version"),
                artifactDigest == null ? null : new Digest(artifactDigest),
                new Digest(rs.getString("payload_hash")),
                RemoteIdentityType.valueOf(rs.getString("remote_identity_type")),
                rs.getLong("lease_epoch"),
                rs.getInt("attempt_count"),
                rs.getInt("max_attempts"),
                rs.getInt("reconcile_not_found_count"));
    }
}
