package com.objwww.pr.arena.it;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * M2-03 权限正反矩阵 IT（C-3 冻结裁定的实证面，真角色真授权；AM3 V7 更新 eval_app 面）：
 * <ul>
 *   <li>arena_app 写 GT 必败（INV-AM2-6）；chaos_admin_app 写业务表必败；</li>
 *   <li>control_app 读 GT 必败（GT 对告警链路不可见 = 硬指标）；</li>
 *   <li>eval_app 直读 GT 必败 + 门禁函数三条件（绑定/终态/冻结）齐备才释放
 *       （AM3 V7 security-barrier：直接授权收回，读面只走 arena.eval_release_gt）；</li>
 *   <li>PUBLIC 零权限（一次性探针角色）；</li>
 *   <li>default privileges 覆盖新表（eval_app 自动可读、arena_app 不自动可写）。</li>
 * </ul>
 */
class ArenaPermissionMatrixIT extends ArenaPostgresITBase {

    private void seedGt(String scenarioId) {
        adminJdbc.sql("""
                INSERT INTO arena.ground_truth_scenario(id,schema_version,dataset_version,scenario_id,
                    activation_generation,config_digest,payload_digest,applicable_scope,valid_from,
                    review_status)
                VALUES (:id,1,'ds-am2-it',:sid,0,:cfg,:pay,'arena',now(),'CONFIRMED')
                """).param("id", UUID.randomUUID()).param("sid", scenarioId)
                .param("cfg", "c".repeat(64)).param("pay", "p".repeat(64))
                .update();
    }

    @Test
    void arenaAppCannotReadGroundTruth() {
        seedGt("perm-it-1");
        assertThatThrownBy(() -> arenaJdbc.sql(
                        "SELECT count(*) FROM arena.ground_truth_scenario").query(Long.class).single())
                .as("arena_app 读 GT 必败（INV-AM2-6：靶场进程禁见答案）")
                .satisfies(t -> assertThat(t.getCause()).hasMessageContaining("permission denied"));
    }

    @Test
    void controlAppCannotReadGroundTruth() {
        seedGt("perm-it-2");
        assertThatThrownBy(() -> {
            try (var conn = PG.createConnection("?")) {
                var st = conn.prepareStatement(
                        "set role control_app; select count(*) from arena.ground_truth_scenario");
                st.execute();
            }
        }).as("control_app 读 GT 必败（INV-AM2-6 反面硬指标）")
                .hasMessageContaining("permission denied");
    }

    @Test
    void evalAppReadsGroundTruthOnlyThroughBarrier() {
        String scenarioId = "perm-it-3";
        seedGt(scenarioId);

        // ① 基表直接 SELECT 必败（AM3 V7 收回 V3 直接授权——输出冻结前 GT 不可见）
        assertThatThrownBy(() -> evalJdbc.sql(
                        "SELECT count(*) FROM arena.ground_truth_scenario")
                .query(Long.class).single())
                .as("eval_app 直读 GT 基表必败（V7 security-barrier：读面只走门禁函数）")
                .satisfies(t -> assertThat(t.getCause()).hasMessageContaining("permission denied"));

        // ② 门禁 fail-closed：未绑定/未终态/未冻结 → 空集（不抛错、不泄露存在性）
        assertThat(evalJdbc.sql("SELECT count(*) FROM arena.eval_release_gt(:run, :sid)")
                .param("run", UUID.randomUUID()).param("sid", scenarioId)
                .query(Long.class).single()).isZero();

        // ③ 绑定 + 终态 + 冻结三条件齐备 → GT 释放
        UUID runId = seedBarrierChain(scenarioId);
        assertThat(evalJdbc.sql("SELECT count(*) FROM arena.eval_release_gt(:run, :sid)")
                .param("run", runId).param("sid", scenarioId)
                .query(Long.class).single()).isEqualTo(1L);
    }

    /** V7 门禁三条件种子：scenario_map 绑定行 + 终态 run（finished_at）+ 冻结报告锚 */
    private UUID seedBarrierChain(String scenarioId) {
        UUID runId = UUID.randomUUID();
        // a. 场景绑定在案（run_id 为 text 面；fingerprint/labels/rule_digest 为 NOT NULL 面）
        adminJdbc.sql("""
                INSERT INTO arena.oa_scenario_map(id, scenario_id, mapping_version,
                    alert_fingerprint, alert_labels, rule_digest, run_id)
                VALUES (:id, :sid, 1, '0123456789abcdef', '{}'::jsonb, :rd, :run)
                """).param("id", UUID.randomUUID()).param("sid", scenarioId)
                .param("rd", "r".repeat(64)).param("run", runId.toString()).update();
        // b. 绑定 Run 已终态（QUEUED/RUNNING 不释放）
        adminJdbc.sql("INSERT INTO public.rca_run(id, state, finished_at) "
                + "VALUES (:id, 'SUCCEEDED', now())").param("id", runId).update();
        // c. 输出快照已冻结（rca_report 行存在 = INSERT-only 冻结锚）
        adminJdbc.sql("INSERT INTO public.rca_report(id, run_id) VALUES (:id, :run)")
                .param("id", UUID.randomUUID()).param("run", runId).update();
        return runId;
    }

    @Test
    void chaosAdminAppCannotWriteBusinessTables() {
        assertThatThrownBy(() -> chaosAdminJdbc.sql("""
                        INSERT INTO arena.oa_trade_order(id,intent_id,correlation_id,buyer_id,sku,
                            quantity,amount,created_at,updated_at)
                        VALUES (:id,'i','live-1','b','s',1,1,now(),now())
                        """).param("id", UUID.randomUUID()).update())
                .as("chaos_admin_app 写业务表必败（C-3：管理面无业务权限）")
                .satisfies(t -> assertThat(t.getCause()).hasMessageContaining("permission denied"));
    }

    @Test
    void arenaAppBusinessCrudPositive() {
        UUID id = UUID.randomUUID();
        arenaJdbc.sql("""
                INSERT INTO arena.oa_trade_order(id,intent_id,correlation_id,buyer_id,sku,quantity,
                    amount,booking_status,pay_status,created_at,updated_at)
                VALUES (:id,'perm-it','live-perm','b','s',1,10.00,'ENABLED','NOT_PAY',now(),now())
                """).param("id", id).update();
        String state = arenaJdbc.sql(
                        "SELECT booking_status FROM arena.oa_trade_order WHERE id=:id")
                .param("id", id).query(String.class).single();
        assertThat(state).isEqualTo("ENABLED");
    }

    @Test
    void publicRoleHasZeroPrivileges() {
        adminJdbc.sql("""
                do $$
                begin
                    if not exists (select from pg_roles where rolname = 'arena_public_probe') then
                        create role arena_public_probe login password 'probe-pass';
                    end if;
                end
                $$;
                """).update();
        assertThatThrownBy(() -> {
            try (var conn = PG.createConnection("?")) {
                var st = conn.prepareStatement(
                        "set role arena_public_probe; select count(*) from arena.oa_trade_order");
                st.execute();
            }
        }).as("PUBLIC/未授权角色在 arena 表零权限")
                .hasMessageContaining("permission denied");
    }

    @Test
    void defaultPrivilegesCoverNewTablesInSafeDirection() {
        adminJdbc.sql("CREATE TABLE arena.tmp_dp_probe(id integer)").update();
        try {
            Long visible = evalJdbc.sql("SELECT count(*) FROM arena.tmp_dp_probe")
                    .query(Long.class).single();
            assertThat(visible).isZero();
            assertThatThrownBy(() -> arenaJdbc.sql(
                            "SELECT count(*) FROM arena.tmp_dp_probe").query(Long.class).single())
                    .as("default privileges：新表对 arena_app 不自动可读（漏授可见不静默）")
                    .satisfies(t -> assertThat(t.getCause()).hasMessageContaining("permission denied"));
        } finally {
            adminJdbc.sql("DROP TABLE arena.tmp_dp_probe").update();
        }
    }
}
