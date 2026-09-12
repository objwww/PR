package com.objwww.pr.control.eval.application;

import com.objwww.pr.control.alert.domain.model.TypedRootCause;
import com.objwww.pr.control.eval.application.ArenaChaosScenarioDriver.ActivationException;
import com.objwww.pr.control.eval.application.ScenarioDriver.ActivationReceipt;
import com.objwww.pr.control.eval.application.ScenarioDriver.RecoveryReceipt;
import com.objwww.pr.control.eval.domain.GoldenCase;
import com.objwww.pr.shared.Digest;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * DR-04（docs/告警-前端逐页体验改造与后期优化方案.md §7.5/§7.4）三债务定向回归：
 * ①settle 中断即退出、零流量、已激活会话回执身份随异常移交；
 * ②on 回执先行成形，流量部分失败仍可凭 receipt 恢复；
 * ③恢复核验会话面探针查每轮派生的有效实例 id（多轮不串场），
 *   跨场景/前缀撞名的回执被映射核验拒入恢复通路。
 * settle 等待经六参构造置 0ms（生产装配走五参构造固定 3s）。
 */
class ArenaChaosScenarioDriverTest {

    private static GoldenCase golden(String scenarioId, String family) {
        return new GoldenCase(scenarioId, "n", "ArenaChaosScenarioDriver", family,
                "order-arena", new TypedRootCause("order-arena", "IDEMPOTENCY", "X"),
                List.of("ArenaOrderDuplicate"), Map.of(), null,
                new GoldenCase.Timing(1, 2, 3, 4, 5));
    }

    private static ArenaChaosScenarioDriver driver(FakeChaosAdminClient client,
                                                   FakeAlertProbe probe,
                                                   FakeTraffic traffic) {
        return new ArenaChaosScenarioDriver(client, probe, traffic, "ds-1", "T01", 0);
    }

    @Test
    void 激活回执先行成形_流量按故障族注入() {
        FakeChaosAdminClient client = new FakeChaosAdminClient();
        FakeTraffic traffic = new FakeTraffic();
        ArenaChaosScenarioDriver driver = driver(client, new FakeAlertProbe(), traffic);

        ActivationReceipt receipt = driver.activate(golden("S3", "F1"), 1);

        assertThat(receipt.scenarioId()).isEqualTo("chaos-eval-t01-s3-r1");
        assertThat(receipt.generation()).isEqualTo(7L);
        assertThat(client.lastOnBody.get("scenarioId")).isEqualTo("chaos-eval-t01-s3-r1");
        // F1 = 同 intent 三单（AM2 E2E 同款 recipe）
        assertThat(traffic.orders).containsExactly(
                "chaos-eval-t01-s3-r1-intent-1/chaos-eval-t01-s3-r1-a",
                "chaos-eval-t01-s3-r1-intent-1/chaos-eval-t01-s3-r1-b",
                "chaos-eval-t01-s3-r1-intent-1/chaos-eval-t01-s3-r1-c");
    }

    @Test
    void 债务1_settle中断即退出_零流量_回执身份随异常移交() {
        FakeChaosAdminClient client = new FakeChaosAdminClient();
        FakeTraffic traffic = new FakeTraffic();
        FakeAlertProbe probe = new FakeAlertProbe();
        ArenaChaosScenarioDriver driver = driver(client, probe, traffic);
        GoldenCase golden = golden("S3", "F1");

        ActivationException ex;
        Thread.currentThread().interrupt();
        try {
            ex = assertThrows(ActivationException.class, () -> driver.activate(golden, 1));
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
        } finally {
            Thread.interrupted(); // 清中断标记，不污染同线程后续测试
        }

        assertThat(traffic.orders).isEmpty();           // 中断后零流量
        assertThat(client.onCalls).isEqualTo(1);        // 会话已在 chaos 管理面激活
        assertThat(ex.receipt().scenarioId()).isEqualTo("chaos-eval-t01-s3-r1");
        assertThat(ex.getCause()).isInstanceOf(InterruptedException.class);

        // 恢复责任不丢：凭异常携带的 receipt 可走 off CAS + 探针核验
        RecoveryReceipt recovery = driver.deactivate(golden, ex.receipt());
        assertThat(recovery.criteriaMet()).isTrue();
        assertThat(client.offBodies).hasSize(1);
        assertThat(client.offBodies.getFirst().get("scenarioId"))
                .isEqualTo("chaos-eval-t01-s3-r1");
        assertThat(client.offBodies.getFirst().get("expectedGeneration")).isEqualTo(7L);
        assertThat(probe.sessionClosedIds).containsExactly("chaos-eval-t01-s3-r1");
    }

    @Test
    void 债务2_流量部分失败_异常携带回执与成功路径同身份_凭回执恢复() {
        FakeChaosAdminClient client = new FakeChaosAdminClient();
        FakeTraffic traffic = new FakeTraffic();
        traffic.failOnCall = 2; // F1 三单中第二单失败，第三单不再发
        ArenaChaosScenarioDriver driver = driver(client, new FakeAlertProbe(), traffic);
        GoldenCase golden = golden("S3", "F1");

        ActivationException ex = assertThrows(ActivationException.class,
                () -> driver.activate(golden, 1));

        assertThat(traffic.orders).hasSize(2);
        // 回执在流量阶段之前已成形：与无故障成功路径的回执同身份（digest/generation 一致）
        ActivationReceipt okReceipt = driver(new FakeChaosAdminClient(),
                new FakeAlertProbe(), new FakeTraffic()).activate(golden, 1);
        assertThat(ex.receipt()).isEqualTo(okReceipt);

        // 流量部分失败不丢现场：凭异常回执恢复成功
        RecoveryReceipt recovery = driver.deactivate(golden, ex.receipt());
        assertThat(recovery.criteriaMet()).isTrue();
        assertThat(recovery.unmetCriteria()).isEmpty();
        assertThat(client.offBodies.getFirst().get("scenarioId"))
                .isEqualTo("chaos-eval-t01-s3-r1");
    }

    @Test
    void 债务3_恢复核验多轮不串场_会话面探针查有效实例id() {
        FakeChaosAdminClient client = new FakeChaosAdminClient();
        FakeAlertProbe probe = new FakeAlertProbe();
        ArenaChaosScenarioDriver driver = driver(client, probe, new FakeTraffic());
        GoldenCase golden = golden("S3", "F2");

        ActivationReceipt r1 = driver.activate(golden, 1);
        ActivationReceipt r2 = driver.activate(golden, 2);
        RecoveryReceipt recovery2 = driver.deactivate(golden, r2);
        RecoveryReceipt recovery1 = driver.deactivate(golden, r1);

        // 会话收口探针查每轮派生的有效实例 id（逆序解除也不串场）
        assertThat(probe.sessionClosedIds)
                .containsExactly("chaos-eval-t01-s3-r2", "chaos-eval-t01-s3-r1");
        // off CAS 本体同样按有效实例 id
        assertThat(client.offBodies).extracting(b -> b.get("scenarioId"))
                .containsExactly("chaos-eval-t01-s3-r2", "chaos-eval-t01-s3-r1");
        // 告警面探针契约 = 注册表模板键（PrometheusAlertProbe 经模板解析期望
        // alertname 集；有效实例 id 不在注册表；告警查询面无 per-round 场次维度）
        assertThat(probe.allResolvedIds).containsExactly("S3", "S3");
        assertThat(recovery1.criteriaMet()).isTrue();
        assertThat(recovery2.criteriaMet()).isTrue();
        assertThat(recovery1.scenarioId()).isEqualTo("S3");
    }

    @Test
    void 债务3_跨场景与前缀撞名回执被映射核验拒入恢复通路() {
        FakeChaosAdminClient client = new FakeChaosAdminClient();
        FakeAlertProbe probe = new FakeAlertProbe();
        ArenaChaosScenarioDriver driver = driver(client, probe, new FakeTraffic());
        GoldenCase golden = golden("S3", "F2");

        RecoveryReceipt foreign = driver.deactivate(golden,
                new ActivationReceipt("chaos-eval-t01-s4-r1", "d", 9L, "fp"));
        assertThat(foreign.criteriaMet()).isFalse();
        assertThat(foreign.unmetCriteria())
                .containsExactly("receipt_scenario_mismatch:chaos-eval-t01-s4-r1");

        // 前缀撞名（S3 是 XS3 的后缀子串）同样拒入
        RecoveryReceipt spoof = driver.deactivate(golden,
                new ActivationReceipt("chaos-eval-t01-xs3-r1", "d", 9L, "fp"));
        assertThat(spoof.criteriaMet()).isFalse();
        assertThat(spoof.unmetCriteria().getFirst())
                .startsWith("receipt_scenario_mismatch:");

        // 拒入即零副作用：不发 off、不探会话、不探告警
        assertThat(client.offBodies).isEmpty();
        assertThat(probe.sessionClosedIds).isEmpty();
        assertThat(probe.allResolvedIds).isEmpty();
    }

    // ---------------- 假件（真栈行为归 195 门） ----------------

    private static final class FakeChaosAdminClient implements ChaosAdminClient {
        int onCalls;
        Map<String, Object> lastOnBody;
        final List<Map<String, Object>> offBodies = new ArrayList<>();

        @Override
        public Activation activate(String faultType, Map<String, Object> body) {
            onCalls++;
            lastOnBody = body;
            return new Activation("sess-fixed",
                    String.valueOf(body.get("scenarioId")), 7L, "fp-fixed");
        }

        @Override
        public boolean deactivate(String faultType, Map<String, Object> body) {
            offBodies.add(body);
            return true;
        }

        @Override
        public SessionStatus status(String scenarioId) {
            return new SessionStatus("CLOSED", 7L);
        }
    }

    private static final class FakeAlertProbe implements AlertProbe {
        final List<String> sessionClosedIds = new ArrayList<>();
        final List<String> allResolvedIds = new ArrayList<>();

        @Override
        public boolean awaitAllFiring(String scenarioId, int maxWaitSeconds) {
            return true;
        }

        @Override
        public boolean awaitAllResolved(String scenarioId, int maxWaitSeconds) {
            allResolvedIds.add(scenarioId);
            return true;
        }

        @Override
        public boolean awaitSessionClosed(String scenarioId, int cleanupTimeoutSeconds) {
            sessionClosedIds.add(scenarioId);
            return true;
        }

        @Override
        public Digest ruleDigest(String alertname) {
            return Digest.sha256Of("rule=" + alertname);
        }
    }

    private static final class FakeTraffic implements ArenaTrafficClient {
        final List<String> orders = new ArrayList<>();
        int failOnCall = -1; // 1-based；-1 = 不失败

        @Override
        public void createOrder(String intentId, String correlationId, String sku) {
            orders.add(intentId + "/" + correlationId);
            if (orders.size() == failOnCall) {
                throw new IllegalStateException("arena 502");
            }
        }
    }
}
