package com.objwww.pr.control.it;

import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.Test;
import org.postgresql.util.PSQLException;

import java.sql.Types;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

/**
 * CT-29（docs/M2-技术方案.md §11 L2 表，回指 I21 / §4.3 审批幂等）。
 *
 * <p>场景：MANUAL 单的人工批准——一次批准、重复批准、并发批准各打一遍；缺 actor/reason
 * 的批批准；以 publisher 角色直连真库验证列级权限面。
 *
 * <p>断言：runbook 批准 SQL（{@code UPDATE ... WHERE id=:id AND state='PENDING'}）
 * 恰好一次生效——重复/并发批准 0 行、首个批准者不被覆盖、审计三列齐全；缺 actor/reason
 * 被 ck_repair_approval 拒批且状态停留 PENDING；publisher 可写状态列、不可写
 * policy_tier/审批列（42501），INSERT 越权列被列级授权拒（42501）、合列伪造 APPROVED
 * 被 trg_repair_insert_pending 拒（P0001）。
 *
 * <p>取证：repair_request.state / approved_by / approved_at / approval_reason /
 * policy_tier；两条拒绝路径的 SQLState。
 */
class CT29RepairApprovalIT extends PostgresITBase {

    private static final String APPROVE_SQL = """
            UPDATE repair_request SET state='APPROVED', approved_by=:actor, approved_at=now(),
                approval_reason=:reason, updated_at=now()
             WHERE id=:id AND state='PENDING'
            """;

    private int seq;

    private UUID newManualPending() {
        RepairSeed seed = seedRepairScope("ct29-" + (++seq));
        UUID op = seedConfirmedCommand(seed, "PUBLISH_REVIEW", "{\"body\":\"review body\"}");
        UUID resource = seedResource(seed, op, "REVIEW", "MISSING", "ct29-r" + seq);
        return seedRepairRequest(resource, "REVIEW", "MANUAL", "PENDING", 0, 5, 0);
    }

    private int approve(UUID id, String actor, String reason) {
        return controlJdbc.sql(APPROVE_SQL)
                .param("actor", actor, Types.VARCHAR)
                .param("reason", reason, Types.VARCHAR)
                .param("id", id).update();
    }

    @Test
    void approveOnceLandsWithFullAuditColumns() {
        UUID id = newManualPending();

        assertThat(approve(id, "op-1", "runbook-42")).isEqualTo(1);

        var row = auditRow(id);
        assertThat(row.state()).isEqualTo("APPROVED");
        assertThat(row.approvedBy()).isEqualTo("op-1");
        assertThat(row.approvedAt()).isNotNull();
        assertThat(row.approvalReason()).isEqualTo("runbook-42");
        // tier 不随批准改写（I21：批准=状态迁移+审计列，不是改档）
        assertThat(row.policyTier()).isEqualTo("MANUAL");
    }

    @Test
    void repeatedApprovalIsIdempotentAndFirstApproverWins() {
        UUID id = newManualPending();

        assertThat(approve(id, "op-1", "first")).isEqualTo(1);
        // 重复批准：WHERE state='PENDING' 不再匹配 → 0 行，现状不被覆盖
        assertThat(approve(id, "op-2", "second")).isZero();
        assertThat(approve(id, "op-2", "second")).isZero();

        var row = auditRow(id);
        assertThat(row.state()).isEqualTo("APPROVED");
        assertThat(row.approvedBy()).isEqualTo("op-1");
        assertThat(row.approvalReason()).isEqualTo("first");
    }

    @Test
    void concurrentApprovalExactlyOneWins() throws Exception {
        UUID id = newManualPending();
        CyclicBarrier barrier = new CyclicBarrier(2);
        ConcurrentLinkedQueue<Throwable> escaped = new ConcurrentLinkedQueue<>();
        AtomicInteger rowsA = new AtomicInteger(-1);
        AtomicInteger rowsB = new AtomicInteger(-1);

        Thread a = Thread.ofVirtual().name("ct29-approver-a").start(() -> {
            try {
                barrier.await(10, TimeUnit.SECONDS);
                rowsA.set(approve(id, "op-a", "race-a"));
            } catch (Throwable t) {
                escaped.add(t);
            }
        });
        Thread b = Thread.ofVirtual().name("ct29-approver-b").start(() -> {
            try {
                barrier.await(10, TimeUnit.SECONDS);
                rowsB.set(approve(id, "op-b", "race-b"));
            } catch (Throwable t) {
                escaped.add(t);
            }
        });
        a.join(30_000);
        b.join(30_000);

        assertThat(escaped).isEmpty();
        // 恰好一次 APPROVED（行锁串行化，后到者 WHERE 失配 0 行）
        assertThat(rowsA.get() + rowsB.get()).isEqualTo(1);
        var row = auditRow(id);
        assertThat(row.state()).isEqualTo("APPROVED");
        assertThat(row.approvedBy()).isIn("op-a", "op-b");
        assertThat(row.approvedAt()).isNotNull();
        assertThat(row.approvalReason()).isIn("race-a", "race-b");
    }

    @Test
    void missingActorOrReasonIsRejectedByCheckConstraint() {
        UUID noActor = newManualPending();
        assertThatThrownBySqlState("23514", () -> approve(noActor, null, "reason"));
        assertThat(repairStateOf(noActor)).isEqualTo("PENDING");

        UUID noReason = newManualPending();
        assertThatThrownBySqlState("23514", () -> approve(noReason, "op-1", null));
        assertThat(repairStateOf(noReason)).isEqualTo("PENDING");

        // 直接置 APPROVED 不带审计列同样被 ck_repair_approval 拦下
        UUID bare = newManualPending();
        assertThatThrownBySqlState("23514", () -> controlJdbc.sql(
                "UPDATE repair_request SET state='APPROVED', updated_at=now() WHERE id=:id")
                .param("id", bare).update());
        assertThat(repairStateOf(bare)).isEqualTo("PENDING");
    }

    @Test
    void publisherColumnPrivilegesOnRepairRequest() {
        UUID id = newManualPending();
        UUID resourceId = adminJdbc.sql(
                "SELECT publication_resource_id FROM repair_request WHERE id=:id")
                .param("id", id).query(UUID.class).single();

        // 可写状态列（RepairOutcomeProjector 投影路径依赖此授权）
        assertThat(publisherJdbc.sql(
                "UPDATE repair_request SET state='RETRY_WAIT', next_attempt_at=now(), updated_at=now()"
                        + " WHERE id=:id").param("id", id).update()).isEqualTo(1);
        assertThat(repairStateOf(id)).isEqualTo("RETRY_WAIT");
        publisherJdbc.sql("UPDATE repair_request SET state='PENDING', next_attempt_at=NULL,"
                + " updated_at=now() WHERE id=:id").param("id", id).update();

        // 不可写 tier（列级授权先行拒绝，trg_repair_tier_immutable 为第二道）
        assertThatThrownBySqlState("42501", () -> publisherJdbc.sql(
                "UPDATE repair_request SET policy_tier='AUTO' WHERE id=:id")
                .param("id", id).update());
        // 不可写审批列
        assertThatThrownBySqlState("42501", () -> publisherJdbc.sql(
                "UPDATE repair_request SET approved_by='forged' WHERE id=:id")
                .param("id", id).update());
        assertThat(auditRow(id).approvedBy()).isNull();
        assertThat(repairStateOf(id)).isEqualTo("PENDING");

        // INSERT 越权列（approved_by 不在列级 INSERT 授权内）→ 权限拒
        assertThatThrownBySqlState("42501", () -> publisherJdbc.sql("""
                INSERT INTO repair_request(id,publication_resource_id,resource_type,policy_tier,state,
                    approved_by)
                VALUES (:id,:rid,'REVIEW','MANUAL','PENDING','forged')
                """).param("id", UUID.randomUUID()).param("rid", resourceId).update());
        // INSERT 合列但伪造 APPROVED 出生 → trigger 拒（RM2-02）
        assertThatThrownBySqlState("P0001", () -> publisherJdbc.sql("""
                INSERT INTO repair_request(id,publication_resource_id,resource_type,policy_tier,state)
                VALUES (:id,:rid,'REVIEW','MANUAL','APPROVED')
                """).param("id", UUID.randomUUID()).param("rid", resourceId).update());
        // INSERT 合列 PENDING → 铸单成功（DriftReconciler 路径）；同资源活跃单唯一索引
        // 已被 id 占用，这里换新资源验证
        RepairSeed seed2 = seedRepairScope("ct29-pub");
        UUID op2 = seedConfirmedCommand(seed2, "PUBLISH_REVIEW", "{\"body\":\"b2\"}");
        UUID resource2 = seedResource(seed2, op2, "REVIEW", "MISSING", "ct29-r-pub");
        UUID legit = UUID.randomUUID();
        publisherJdbc.sql("""
                INSERT INTO repair_request(id,publication_resource_id,resource_type,policy_tier,state)
                VALUES (:id,:rid,'REVIEW','MANUAL','PENDING')
                """).param("id", legit).param("rid", resource2).update();
        assertThat(repairStateOf(legit)).isEqualTo("PENDING");
    }

    private record AuditRow(String state, String policyTier, String approvedBy,
                            Object approvedAt, String approvalReason) {}

    private AuditRow auditRow(UUID id) {
        return adminJdbc.sql("""
                SELECT state, policy_tier, approved_by, approved_at, approval_reason
                  FROM repair_request WHERE id=:id
                """).param("id", id).query((rs, n) -> new AuditRow(rs.getString(1),
                rs.getString(2), rs.getString(3), rs.getTimestamp(4), rs.getString(5))).single();
    }

    private static void assertThatThrownBySqlState(String expected, ThrowingCallable call) {
        Throwable t = catchThrowable(call);
        assertThat(t).as("预期抛出 SQLException").isNotNull();
        Throwable root = t;
        while (root.getCause() != null) {
            root = root.getCause();
        }
        assertThat(root).isInstanceOf(PSQLException.class);
        assertThat(((PSQLException) root).getSQLState()).isEqualTo(expected);
    }
}
