package com.objwww.pr.control.drill.application;

import com.objwww.pr.control.drill.domain.model.DrillJob;
import com.objwww.pr.control.drill.domain.model.DrillTemplate;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * DR-02 预检诚实面：真实可查信号出 OK/FAIL（场景/靶场白名单/占位/可执行性），
 * control 读面无信号的项（靶场健康/资源水位/旧故障残留/管理面/恢复能力）如实
 * UNKNOWN 不造假；可执行性未交付 = FAIL（已知能力面不冒充 UNKNOWN，不开放假启动）。
 */
class DrillPrecheckTest {

    private static DrillTemplate template(boolean ready) {
        return new DrillTemplate("S3", "F1 幂等失效", "业务完整性", "AM2 靶场",
                "ArenaChaosScenarioDriver", "F1", "order-arena",
                List.of("ArenaDuplicateOrders"), "ArenaDuplicateOrders（page）", "影响面",
                new DrillTemplate.Timing(60, 600, 300, 600, 120),
                new DrillTemplate.ParamWhitelist(600, 60, 600, List.of("RECIPE"), true),
                new DrillTemplate.Execution(ready, ready ? null : "接线未交付"));
    }

    private static DrillJob occupant() {
        return DrillJob.queued(UUID.randomUUID(), "S3", "F1 幂等失效",
                "0".repeat(64), "arena-195", "op", "{}", "1".repeat(64),
                "key-1", Instant.now());
    }

    private static Map<String, DrillPrecheck.Status> byName(DrillPrecheck.Result r) {
        return r.checks().stream().collect(java.util.stream.Collectors.toMap(
                DrillPrecheck.Check::name, DrillPrecheck.Check::status));
    }

    @Test
    @DisplayName("全绿面（ready 模板 + 白名单靶场 + 无占位）：真值项 OK，五项如实 UNKNOWN，canLaunch")
    void allRealChecksPass() {
        DrillPrecheck.Result r = DrillPrecheck.run(template(true), "arena-195",
                List.of("arena-195"), null);
        Map<String, DrillPrecheck.Status> statuses = byName(r);
        assertThat(statuses.get("SCENARIO_KNOWN")).isEqualTo(DrillPrecheck.Status.OK);
        assertThat(statuses.get("TARGET_ENV_WHITELIST")).isEqualTo(DrillPrecheck.Status.OK);
        assertThat(statuses.get("ENV_OCCUPANCY")).isEqualTo(DrillPrecheck.Status.OK);
        assertThat(statuses.get("EXECUTION_READY")).isEqualTo(DrillPrecheck.Status.OK);
        assertThat(statuses.get("SYMPTOM_CLEAN")).isEqualTo(DrillPrecheck.Status.UNKNOWN);
        assertThat(statuses.get("TARGET_HEALTH")).isEqualTo(DrillPrecheck.Status.UNKNOWN);
        assertThat(statuses.get("RESOURCE_HEADROOM")).isEqualTo(DrillPrecheck.Status.UNKNOWN);
        assertThat(statuses.get("RESIDUAL_FAULT")).isEqualTo(DrillPrecheck.Status.UNKNOWN);
        assertThat(statuses.get("MGMT_PLANE")).isEqualTo(DrillPrecheck.Status.UNKNOWN);
        assertThat(statuses.get("RECOVERY_CAPABILITY"))
                .isEqualTo(DrillPrecheck.Status.UNKNOWN);
        assertThat(r.canLaunch()).isTrue();
    }

    @Test
    @DisplayName("UNKNOWN 不造假：六项 detail 明示无授权通路/未交付，不写成 OK")
    void unknownItemsHonest() {
        DrillPrecheck.Result r = DrillPrecheck.run(template(true), "arena-195",
                List.of("arena-195"), null);
        assertThat(r.checks())
                .filteredOn(c -> c.status() == DrillPrecheck.Status.UNKNOWN)
                .allSatisfy(c -> assertThat(c.detail()).isNotBlank())
                .hasSize(6);
    }

    @Test
    @DisplayName("BA-180 环境洁净门：期望症状残留 firing = FAIL 中文人话原因且 "
            + "canLaunch=false（拒绝注入）；空残留 = OK；无症状码模板 = OK 无判定面")
    void symptomCleanGate() {
        // 残留 firing：FAIL + 人话原因逐码列出
        DrillPrecheck.Result dirty = DrillPrecheck.run(template(true), "arena-195",
                List.of("arena-195"), null, true, List.of("ArenaDuplicateOrders"));
        DrillPrecheck.Check gate = dirty.checks().stream()
                .filter(c -> c.name().equals("SYMPTOM_CLEAN")).findFirst().orElseThrow();
        assertThat(gate.status()).isEqualTo(DrillPrecheck.Status.FAIL);
        assertThat(gate.detail()).contains("目标告警已在 firing 残留，环境不洁净")
                .contains("ArenaDuplicateOrders");
        assertThat(dirty.canLaunch()).isFalse();

        // 空残留（worker 实测洁净）：OK 不阻塞
        DrillPrecheck.Result clean = DrillPrecheck.run(template(true), "arena-195",
                List.of("arena-195"), null, true, List.of());
        assertThat(byName(clean).get("SYMPTOM_CLEAN")).isEqualTo(DrillPrecheck.Status.OK);
        assertThat(clean.canLaunch()).isTrue();

        // 无症状码模板：无判定面 OK（核验面同为空，不冒充有检查）
        DrillTemplate noSymptom = new DrillTemplate("T9", "无症状", "类型", "源",
                "FlagdScenarioDriver", null, "payment", List.of(), "",
                "影响面", new DrillTemplate.Timing(60, 600, 300, 600, 120),
                new DrillTemplate.ParamWhitelist(600, 60, 600, List.of("RECIPE"), true),
                new DrillTemplate.Execution(true, null));
        DrillPrecheck.Result noFace = DrillPrecheck.run(noSymptom, "arena-195",
                List.of("arena-195"), null, true, null);
        DrillPrecheck.Check skipped = noFace.checks().stream()
                .filter(c -> c.name().equals("SYMPTOM_CLEAN")).findFirst().orElseThrow();
        assertThat(skipped.status()).isEqualTo(DrillPrecheck.Status.OK);
        assertThat(skipped.detail()).contains("无 firing 残留判定面");
        assertThat(noFace.canLaunch()).isTrue();
    }

    @Test
    @DisplayName("可执行性未交付 = FAIL 且带真实原因（已知能力面，不冒充 UNKNOWN）")
    void executionNotReadyFails() {
        DrillPrecheck.Result r = DrillPrecheck.run(template(false), "arena-195",
                List.of("arena-195"), null);
        DrillPrecheck.Check readiness = r.checks().stream()
                .filter(c -> c.name().equals("EXECUTION_READY")).findFirst().orElseThrow();
        assertThat(readiness.status()).isEqualTo(DrillPrecheck.Status.FAIL);
        assertThat(readiness.detail()).isEqualTo("接线未交付");
        assertThat(r.canLaunch()).isFalse();
    }

    @Test
    @DisplayName("环境占用 = FAIL 带占用作业身份（§7.3 互斥，恢复异常占位同样阻止）")
    void occupantFails() {
        DrillJob active = occupant();
        DrillPrecheck.Result r = DrillPrecheck.run(template(true), "arena-195",
                List.of("arena-195"), active);
        DrillPrecheck.Check occupancy = r.checks().stream()
                .filter(c -> c.name().equals("ENV_OCCUPANCY")).findFirst().orElseThrow();
        assertThat(occupancy.status()).isEqualTo(DrillPrecheck.Status.FAIL);
        assertThat(occupancy.detail()).contains(active.id().toString());
        assertThat(r.canLaunch()).isFalse();
    }

    @Test
    @DisplayName("靶场越白名单 = FAIL（DU04 篡改面）；未知场景短路 FAIL")
    void whitelistAndUnknownScenario() {
        DrillPrecheck.Result offList = DrillPrecheck.run(template(true), "prod",
                List.of("arena-195"), null);
        assertThat(byName(offList).get("TARGET_ENV_WHITELIST"))
                .isEqualTo(DrillPrecheck.Status.FAIL);
        assertThat(offList.canLaunch()).isFalse();

        DrillPrecheck.Result unknown = DrillPrecheck.run(null, "arena-195",
                List.of("arena-195"), null);
        assertThat(unknown.checks()).hasSize(1);
        assertThat(unknown.checks().getFirst().status())
                .isEqualTo(DrillPrecheck.Status.FAIL);
        assertThat(unknown.canLaunch()).isFalse();
    }

    @Test
    @DisplayName("FUP-04 能力位入合取：launchEnabled=false → LAUNCH_ENABLED FAIL + "
            + "canLaunch=false（原因码 LAUNCH_DISABLED 与 create 409 同码）；开放面 OK")
    void launchEnabledCheck() {
        DrillPrecheck.Result closed = DrillPrecheck.run(template(true), "arena-195",
                List.of("arena-195"), null, false);
        DrillPrecheck.Check gate = closed.checks().stream()
                .filter(c -> c.name().equals("LAUNCH_ENABLED")).findFirst().orElseThrow();
        assertThat(gate.status()).isEqualTo(DrillPrecheck.Status.FAIL);
        assertThat(gate.detail()).contains("LAUNCH_DISABLED");
        assertThat(closed.canLaunch()).isFalse();

        DrillPrecheck.Result open = DrillPrecheck.run(template(true), "arena-195",
                List.of("arena-195"), null, true);
        assertThat(byName(open).get("LAUNCH_ENABLED")).isEqualTo(DrillPrecheck.Status.OK);
        assertThat(open.canLaunch()).isTrue();
    }
}
