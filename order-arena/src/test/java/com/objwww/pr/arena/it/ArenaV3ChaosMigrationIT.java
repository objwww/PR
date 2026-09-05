package com.objwww.pr.arena.it;

import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * M2-06 chaos 域契约 IT（V3）：AM2 v3.0 §6.5 四约束逐项 + GT append-only（C-5）。
 * 部分唯一索引冲突 = 23505；ACTIVE 缺 GT = DEFERRED 触发器在事务提交点整单失败
 * （"三写任一点失败零可见"的数据库实证）。
 */
class ArenaV3ChaosMigrationIT extends ArenaPostgresITBase {

    private void seedGt(String scenarioId) {
        chaosAdminJdbc.sql("""
                INSERT INTO arena.ground_truth_scenario(id,schema_version,dataset_version,scenario_id,
                    activation_generation,config_digest,payload_digest,applicable_scope,valid_from,
                    review_status)
                VALUES (:id,1,'ds-am2-it',:sid,0,:cfg,:pay,'arena',now(),'CONFIRMED')
                """).param("id", UUID.randomUUID()).param("sid", scenarioId)
                .param("cfg", "c".repeat(64)).param("pay", "p".repeat(64))
                .update();
    }

    private void insertSession(String scenarioId, String faultType, String state,
                               int ttlSeconds, String expiresOffset) {
        chaosAdminJdbc.sql("""
                INSERT INTO arena.oa_chaos_session(id,scenario_id,fault_type,target,ttl_seconds,
                    operator,config_digest,state,generation,created_at,expires_at,updated_at)
                VALUES (:id,:sid,:ft,'chaos-f3',:ttl,'it',:cfg,:state,0,now(),
                    now() + :off::interval,now())
                """).param("id", UUID.randomUUID()).param("sid", scenarioId)
                .param("ft", faultType).param("ttl", ttlSeconds).param("cfg", "d".repeat(64))
                .param("state", state).param("off", expiresOffset).update();
    }

    // ---------------- 约束①：scenario_id 唯一 ----------------

    @Test
    void scenarioIdIsUnique() {
        seedGt("dup-scenario");
        insertSession("dup-scenario", "F1", "ACTIVE", 60, "1 minute");
        assertThatThrownBy(() -> insertSession("dup-scenario", "F2", "ACTIVE", 60,
                "1 minute"))
                .as("约束①：scenario_id 唯一（激活重放保护）")
                .isInstanceOf(DuplicateKeyException.class);
    }

    // ---------------- 约束②：同 fault_type+target 唯一 ACTIVE ----------------

    @Test
    void doubleActiveSameFaultTargetRejected() {
        seedGt("f3-a");
        seedGt("f3-b");
        insertSession("f3-a", "F3", "ACTIVE", 60, "1 minute");
        assertThatThrownBy(() -> insertSession("f3-b", "F3", "ACTIVE", 60, "1 minute"))
                .as("约束②：同 fault_type+target 第二个 ACTIVE 必败（INV-AM2-7 并发面）")
                .isInstanceOf(DuplicateKeyException.class);
    }

    @Test
    void differentFaultTypesCanCoexistAsActive() {
        seedGt("f1-x");
        seedGt("f3-x");
        insertSession("f1-x", "F1", "ACTIVE", 60, "1 minute");
        insertSession("f3-x", "F3", "ACTIVE", 60, "1 minute");
        assertThat(count("oa_chaos_session")).isEqualTo(2);
    }

    // ---------------- 约束③：TTL 上下界 ----------------

    @Test
    void ttlBelowLowerBoundRejected() {
        seedGt("ttl-low");
        assertThatThrownBy(() -> insertSession("ttl-low", "F3", "ACTIVE", 10, "10 seconds"))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("ck_chaos_ttl_bounds");
    }

    @Test
    void ttlAboveUpperBoundRejected() {
        seedGt("ttl-high");
        assertThatThrownBy(() -> insertSession("ttl-high", "F3", "ACTIVE", 8000, "2 hours"))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("ck_chaos_ttl_bounds");
    }

    @Test
    void expiresAtMustBeAfterCreatedAt() {
        seedGt("ttl-order");
        assertThatThrownBy(() -> insertSession("ttl-order", "F3", "ACTIVE", 60,
                "-1 minute"))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("ck_chaos_ttl_positive");
    }

    // ---------------- 约束④：ACTIVE 必须有 ground truth ----------------

    @Test
    void activeWithoutGroundTruthIsInvisibleAtCommit() {
        // 不种 GT：激活事务在提交点被 DEFERRED 触发器整单拒绝
        assertThatThrownBy(() -> insertSession("gt-less", "F2", "ACTIVE", 60, "1 minute"))
                .as("约束④：ACTIVE 缺 GT 提交即败（§6.5 激活事务）")
                .hasMessageContaining("cannot be ACTIVE without ground truth");
        Long n = adminJdbc.sql("SELECT count(*) FROM arena.oa_chaos_session WHERE scenario_id='gt-less'")
                .query(Long.class).single();
        assertThat(n).as("失败的激活零残留（事务整单回滚）").isZero();
    }

    @Test
    void activeWithGroundTruthCommits() {
        seedGt("gt-full");
        insertSession("gt-full", "F3", "ACTIVE", 60, "1 minute");
        assertThat(count("oa_chaos_session")).isEqualTo(1);
    }

    // ---------------- C-5：GT append-only ----------------

    @Test
    void groundTruthUpdateIsRejected() {
        seedGt("gt-immutable");
        UUID id = adminJdbc.sql("SELECT id FROM arena.ground_truth_scenario WHERE scenario_id='gt-immutable'")
                .query(UUID.class).single();
        assertThatThrownBy(() -> adminJdbc.sql(
                        "UPDATE arena.ground_truth_scenario SET review_status='DRAFT' WHERE id=:id")
                .param("id", id).update())
                .as("C-5：GT 禁 UPDATE（append-only 触发器连 owner 一并拦死）")
                .hasMessageContaining("append-only");
    }

    @Test
    void groundTruthDeleteIsRejected() {
        seedGt("gt-immutable-del");
        UUID id = adminJdbc.sql("SELECT id FROM arena.ground_truth_scenario WHERE scenario_id='gt-immutable-del'")
                .query(UUID.class).single();
        assertThatThrownBy(() -> adminJdbc.sql(
                        "DELETE FROM arena.ground_truth_scenario WHERE id=:id")
                .param("id", id).update())
                .hasMessageContaining("append-only");
    }

    // ---------------- 会话生命周期与事件 ----------------

    @Test
    void sessionCasTransitionAndEventAudit() {
        seedGt("cas-flow");
        insertSession("cas-flow", "F1", "ACTIVE", 60, "1 minute");
        UUID sessionId = chaosAdminJdbc.sql(
                        "SELECT id FROM arena.oa_chaos_session WHERE scenario_id='cas-flow'")
                .query(UUID.class).single();

        int off = chaosAdminJdbc.sql("""
                UPDATE arena.oa_chaos_session
                SET state='RECOVERING', generation=generation+1, updated_at=now()
                WHERE id=:id AND state='ACTIVE' AND generation=:gen
                """).param("id", sessionId).param("gen", 0L).update();
        assertThat(off).as("off = CAS ACTIVE→RECOVERING（state+generation 栅栏）").isEqualTo(1);

        // 旧 generation 的迟到 CAS 拒写
        int stale = chaosAdminJdbc.sql("""
                UPDATE arena.oa_chaos_session
                SET state='RECOVERING', generation=generation+1, updated_at=now()
                WHERE id=:id AND state='ACTIVE' AND generation=:gen
                """).param("id", sessionId).param("gen", 0L).update();
        assertThat(stale).isZero();

        chaosAdminJdbc.sql("""
                INSERT INTO arena.oa_chaos_event(id,session_id,event_type,detail,occurred_at)
                VALUES (:id,:s,'DEACTIVATED','{"by":"it"}'::jsonb,now())
                """).param("id", UUID.randomUUID()).param("s", sessionId).update();
        assertThat(count("oa_chaos_event")).isEqualTo(1);
    }
}
