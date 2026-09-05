package com.objwww.pr.arena.it;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * M3-10 验收：GT 延迟授权 security-barrier（真 PG + 真实 eval_app 角色）。
 *
 * <p>答案泄漏防线三断言面：① eval_app 基表直接 SELECT 必败（V7 收回 V3 授予）；
 * ② 输出冻结前 eval_release_gt 一律空集（fail-closed，不泄露存在性）；
 * ③ 冻结后（run 终态 + report 在案）仅释放绑定场景的 GT。
 * public.rca_run/rca_report 在本容器以最小桩表代替（控制面 schema 不归 arena 迁移管；
 * 函数只依赖 id/state/run_id 三个 V7 冻结列）。
 */
class ArenaEvalGtBarrierIT extends ArenaPostgresITBase {

    private static final String SCENARIO = "S1";

    @BeforeEach
    void createControlStubsAndClean() {
        // 桩表只承载函数读取的冻结列（V7 注释点名的最小依赖面）
        adminJdbc.sql("""
                create table if not exists public.rca_run (
                    id uuid primary key,
                    state text not null
                )
                """).update();
        adminJdbc.sql("""
                create table if not exists public.rca_report (
                    id uuid primary key,
                    run_id uuid not null
                )
                """).update();
        adminJdbc.sql("delete from public.rca_report").update();
        adminJdbc.sql("delete from public.rca_run").update();
    }

    /** GT 行 + scenario_map 绑定行（chaos_admin 激活事务的产物面，admin 直插等价） */
    private UUID seedGt(String scenarioId) {
        UUID gtId = UUID.randomUUID();
        adminJdbc.sql("""
                insert into arena.ground_truth_scenario(id, schema_version, dataset_version,
                    scenario_id, activation_generation, config_digest, payload_digest,
                    applicable_scope, valid_from, review_status)
                values (:id, 1, 'it-ds', :sid, 0, repeat('a', 64), repeat('b', 64),
                        'arena', now(), 'CONFIRMED')
                """).param("id", gtId).param("sid", scenarioId).update();
        return gtId;
    }

    private void seedMap(String scenarioId, UUID runId) {
        adminJdbc.sql("""
                insert into arena.oa_scenario_map(id, scenario_id, mapping_version,
                    alert_fingerprint, alert_labels, rule_digest, run_id)
                values (:id, :sid, 1, '0123456789abcdef', '{}'::jsonb, :digest, :run)
                """).param("id", UUID.randomUUID()).param("sid", scenarioId)
                .param("digest", repeat('c', 64)).param("run", runId.toString()).update();
    }

    private static String repeat(char c, int n) {
        return String.valueOf(c).repeat(n);
    }

    private void seedRun(UUID runId, String state) {
        adminJdbc.sql("insert into public.rca_run(id, state) values (:id, :state)")
                .param("id", runId).param("state", state).update();
    }

    private void seedReport(UUID runId) {
        adminJdbc.sql("insert into public.rca_report(id, run_id) values (:id, :run)")
                .param("id", UUID.randomUUID()).param("run", runId).update();
    }

    private List<Map<String, Object>> release(UUID runId, String scenarioId) {
        return evalJdbc.sql("select * from arena.eval_release_gt(:run, :sid)")
                .param("run", runId).param("sid", scenarioId)
                .query()
                .listOfRows();
    }

    // ------------------------------------------------------------------ 权限正反

    @Test
    @DisplayName("权限反面：eval_app 基表直接 SELECT GT 必败（V7 收回；答案不可裸读）")
    void evalAppDirectGtSelectIsDenied() {
        seedGt(SCENARIO);

        boolean denied = chainContains(() -> evalJdbc
                        .sql("select count(*) from arena.ground_truth_scenario")
                        .query(Long.class).single(),
                "permission denied");
        assertThat(denied).isTrue();
    }

    @Test
    @DisplayName("权限正面：chaos_admin_app 激活写者的 GT 直接读不变；eval_app 可执行释放函数")
    void writerRoleUnaffectedAndFunctionExecutable() {
        seedGt(SCENARIO);

        // 激活事务写者面不受本迁移影响
        assertThat(chaosAdminJdbc.sql("select count(*) from arena.ground_truth_scenario")
                .query(Long.class).single()).isEqualTo(1L);

        // eval_app 函数执行权在（绑定/终态/冻结条件未满足时诚实返回空集而非报错）
        UUID runId = UUID.randomUUID();
        assertThat(release(runId, SCENARIO)).isEmpty();
    }

    // ------------------------------------------------------------------ 栅栏时序

    @Test
    @DisplayName("输出冻结前必败：run 活跃 / run 终态但报告未落，两态都拿不到 GT")
    void gtWithheldBeforeOutputFreeze() {
        seedGt(SCENARIO);
        UUID runId = UUID.randomUUID();
        seedMap(SCENARIO, runId);
        seedRun(runId, "RUNNING");

        assertThat(release(runId, SCENARIO)).isEmpty();

        // run 终态但输出快照未冻结（报告未落）——仍不给答案
        adminJdbc.sql("update public.rca_run set state = 'SUCCEEDED' where id = :id")
                .param("id", runId).update();
        assertThat(release(runId, SCENARIO)).isEmpty();

        // 报告落库（INSERT-only 冻结锚）→ 释放
        seedReport(runId);
        List<Map<String, Object>> rows = release(runId, SCENARIO);
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).get("scenario_id")).isEqualTo(SCENARIO);
        assertThat(rows.get(0).get("payload_digest")).isEqualTo(repeat('b', 64));
        assertThat(rows.get(0).get("review_status")).isEqualTo("CONFIRMED");
    }

    @Test
    @DisplayName("冻结后仅读绑定场景：未绑定/他场景一律空集（禁止顺带读其它答案）")
    void frozenRunReleasesOnlyBoundScenario() {
        seedGt("S1");
        seedGt("S2");
        UUID runId = UUID.randomUUID();
        seedMap("S1", runId);   // 只绑 S1
        seedRun(runId, "SUCCEEDED");
        seedReport(runId);

        assertThat(release(runId, "S1")).hasSize(1);
        assertThat(release(runId, "S2")).isEmpty();     // 未绑定场景
        assertThat(release(UUID.randomUUID(), "S1")).isEmpty();   // 未绑定 run
    }

    /** 断言辅助:执行应抛异常,且整条 cause 链文本包含预期片段 */
    private boolean chainContains(Runnable action, String fragment) {
        try {
            action.run();
        } catch (RuntimeException e) {
            StringBuilder chain = new StringBuilder();
            for (Throwable c = e; c != null; c = c.getCause()) {
                chain.append(c.getMessage()).append('\n');
            }
            return chain.toString().contains(fragment);
        }
        return false;
    }
}
