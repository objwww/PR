package com.objwww.pr.arena.it;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * M2-03 权限正反矩阵 IT（C-3 冻结裁定的实证面，真角色真授权）：
 * <ul>
 *   <li>arena_app 写 GT 必败（INV-AM2-6）；chaos_admin_app 写业务表必败；</li>
 *   <li>control_app 读 GT 必败（GT 对告警链路不可见 = 硬指标）；</li>
 *   <li>eval_app 读 GT 成功（报告封存后读的正面）；</li>
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
    void evalAppReadsGroundTruth() {
        seedGt("perm-it-3");
        Long n = evalJdbc.sql("SELECT count(*) FROM arena.ground_truth_scenario")
                .query(Long.class).single();
        assertThat(n).isEqualTo(1L);
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
