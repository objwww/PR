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
    void F9配方_三单创加付_掉单症状面流量() {
        FakeChaosAdminClient client = new FakeChaosAdminClient();
        FakeTraffic traffic = new FakeTraffic();
        ArenaChaosScenarioDriver driver = driver(client, new FakeAlertProbe(), traffic);

        ActivationReceipt receipt = driver.activate(golden("S26", "F9"), 1);

        assertThat(receipt.scenarioId()).isEqualTo("chaos-eval-t01-s26-r1");
        assertThat(client.lastOnBody.get("scenarioId")).isEqualTo("chaos-eval-t01-s26-r1");
        // BA-178：F9 = ma-drill-s16.sh 195 实测配方——3 单创+付（capture 落库后故障点吞收口）
        assertThat(traffic.orders).containsExactly(
                "chaos-eval-t01-s26-r1-intent-1/chaos-eval-t01-s26-r1-1",
                "chaos-eval-t01-s26-r1-intent-2/chaos-eval-t01-s26-r1-2",
                "chaos-eval-t01-s26-r1-intent-3/chaos-eval-t01-s26-r1-3");
        assertThat(traffic.payments).containsExactly(
                "order-1/chaos-eval-t01-s26-r1-intent-1",
                "order-2/chaos-eval-t01-s26-r1-intent-2",
                "order-3/chaos-eval-t01-s26-r1-intent-3");
    }

    @Test
    void 未接线故障族如实抛错不空转() {
        ArenaChaosScenarioDriver driver = driver(new FakeChaosAdminClient(),
                new FakeAlertProbe(), new FakeTraffic());

        ActivationException ex = assertThrows(ActivationException.class,
                () -> driver.activate(golden("S99", "F99"), 1));

        // 会话已激活（回执身份随异常移交可恢复），流量零注入，原因如实
        assertThat(ex.receipt().scenarioId()).isEqualTo("chaos-eval-t01-s99-r1");
        assertThat(ex.getMessage()).contains("流量注入失败");
    }

    @Test
    void F10配方_25单仅创不付_支付悬挂积压() {
        FakeTraffic traffic = new FakeTraffic();
        ArenaChaosScenarioDriver driver = driver(new FakeChaosAdminClient(),
                new FakeAlertProbe(), traffic);

        driver.activate(golden("S17", "F10"), 1);

        // BA-179：F10 = ma-drill-s17.sh 实测配方——25 单仅创单不支付（积压 >20 触 ticket）
        assertThat(traffic.orders).hasSize(25)
                .startsWith("chaos-eval-t01-s17-r1-intent-1/chaos-eval-t01-s17-r1-1")
                .endsWith("chaos-eval-t01-s17-r1-intent-25/chaos-eval-t01-s17-r1-25");
        assertThat(traffic.payments).isEmpty();
    }

    @Test
    void F11配方_同单同correlationId重复支付() {
        FakeTraffic traffic = new FakeTraffic();
        ArenaChaosScenarioDriver driver = driver(new FakeChaosAdminClient(),
                new FakeAlertProbe(), traffic);

        driver.activate(golden("S18", "F11"), 1);

        // BA-179：F11 = ma-drill-s18.sh 实测配方——创+付+同单同 correlationId 再付
        assertThat(traffic.orders).containsExactly(
                "chaos-eval-t01-s18-r1-intent-1/chaos-eval-t01-s18-r1-1");
        assertThat(traffic.payments).containsExactly(
                "order-1/chaos-eval-t01-s18-r1-intent-1",
                "order-1/chaos-eval-t01-s18-r1-intent-1");
    }

    @Test
    void F12配方_一单创加付_对账不平数量差() {
        FakeTraffic traffic = new FakeTraffic();
        ArenaChaosScenarioDriver driver = driver(new FakeChaosAdminClient(),
                new FakeAlertProbe(), traffic);

        driver.activate(golden("S19", "F12"), 1);

        // BA-179：F12 = ma-drill-s19.sh 实测配方——1 单创+付（DISCOUNT 行被吞）
        assertThat(traffic.orders).containsExactly(
                "chaos-eval-t01-s19-r1-intent-1/chaos-eval-t01-s19-r1-1");
        assertThat(traffic.payments).containsExactly(
                "order-1/chaos-eval-t01-s19-r1-intent-1");
    }

    @Test
    void F13配方_一单创加付_库存超卖() {
        FakeTraffic traffic = new FakeTraffic();
        ArenaChaosScenarioDriver driver = driver(new FakeChaosAdminClient(),
                new FakeAlertProbe(), traffic);

        driver.activate(golden("S20", "F13"), 1);

        // BA-179：F13 = ma-t9-s20.sh 实测配方——1 单创+付（补插超额 INVENTORY 行）
        assertThat(traffic.orders).containsExactly(
                "chaos-eval-t01-s20-r1-intent-1/chaos-eval-t01-s20-r1-1");
        assertThat(traffic.payments).containsExactly(
                "order-1/chaos-eval-t01-s20-r1-intent-1");
    }

    @Test
    void F14配方_25单仅创_下单事件丢失履约未触发() {
        FakeTraffic traffic = new FakeTraffic();
        ArenaChaosScenarioDriver driver = driver(new FakeChaosAdminClient(),
                new FakeAlertProbe(), traffic);

        driver.activate(golden("S21", "F14"), 1);

        // BA-179：F14 = ma-t9-s21.sh 实测配方——25 单仅创单（履约行被吞，counter 差值 >10）
        assertThat(traffic.orders).hasSize(25)
                .startsWith("chaos-eval-t01-s21-r1-intent-1/chaos-eval-t01-s21-r1-1")
                .endsWith("chaos-eval-t01-s21-r1-intent-25/chaos-eval-t01-s21-r1-25");
        assertThat(traffic.payments).isEmpty();
    }

    @Test
    void F15配方_一单创加付_消息重复消费() {
        FakeTraffic traffic = new FakeTraffic();
        ArenaChaosScenarioDriver driver = driver(new FakeChaosAdminClient(),
                new FakeAlertProbe(), traffic);

        driver.activate(golden("S22", "F15"), 1);

        // BA-179：F15 = ma-t9-s22.sh 实测配方——1 单创+付（消费端重复插 attempt 行）
        assertThat(traffic.orders).containsExactly(
                "chaos-eval-t01-s22-r1-intent-1/chaos-eval-t01-s22-r1-1");
        assertThat(traffic.payments).containsExactly(
                "order-1/chaos-eval-t01-s22-r1-intent-1");
    }

    @Test
    void F16配方_25次创单突发_入口静默受理无单不支付() {
        FakeTraffic traffic = new FakeTraffic();
        traffic.nullOrders = true; // F16 入口静默：create 受理 accepted 但零落单（返回 null）
        ArenaChaosScenarioDriver driver = driver(new FakeChaosAdminClient(),
                new FakeAlertProbe(), traffic);

        driver.activate(golden("S24", "F16"), 1);

        // BA-179：F16 = ma-t9-s24.sh 实测配方——25 次创单突发全被吞（受理无单→不支付）
        assertThat(traffic.orders).hasSize(25)
                .startsWith("chaos-eval-t01-s24-r1-intent-1/chaos-eval-t01-s24-r1-1")
                .endsWith("chaos-eval-t01-s24-r1-intent-25/chaos-eval-t01-s24-r1-25");
        assertThat(traffic.payments).isEmpty();
    }

    @Test
    void F17配方_16单仅创_履约超时积压() {
        FakeTraffic traffic = new FakeTraffic();
        ArenaChaosScenarioDriver driver = driver(new FakeChaosAdminClient(),
                new FakeAlertProbe(), traffic);

        driver.activate(golden("S25", "F17"), 1);

        // BA-179：F17 = ma-t9-s25.sh 实测配方——16 单仅创单（履约停 CONFIRMING 积压 >15）
        assertThat(traffic.orders).hasSize(16)
                .startsWith("chaos-eval-t01-s25-r1-intent-1/chaos-eval-t01-s25-r1-1")
                .endsWith("chaos-eval-t01-s25-r1-intent-16/chaos-eval-t01-s25-r1-16");
        assertThat(traffic.payments).isEmpty();
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

    @Test
    void withRunTag拷贝_仅替换tag_原单例不改写() {
        // BA-190：批开始时 runner 以有效 run-tag（空 tag 按 evalRunId 派生）拷贝驱动——
        // 拷贝激活用新 tag 派生 scenario id，原单例（env 静态 tag "T01"）不被改写
        FakeChaosAdminClient client = new FakeChaosAdminClient();
        ArenaChaosScenarioDriver original = driver(client, new FakeAlertProbe(),
                new FakeTraffic());

        ScenarioDriver copy = original.withRunTag("refde9e17");

        assertThat(copy).isNotSameAs(original);
        ActivationReceipt receipt = copy.activate(golden("S3", "F1"), 1);
        assertThat(receipt.scenarioId()).isEqualTo("chaos-eval-refde9e17-s3-r1");
        assertThat(client.lastOnBody.get("scenarioId")).isEqualTo("chaos-eval-refde9e17-s3-r1");
        // 原单例 tag 不改写（eval-worker 与演练面共享 chaosAdminClient 传输面）
        ActivationReceipt originalReceipt = original.activate(golden("S3", "F1"), 1);
        assertThat(originalReceipt.scenarioId()).isEqualTo("chaos-eval-t01-s3-r1");
    }

    @Test
    void 非tag敏感驱动_withRunTag默认原样返回零行为变化() {
        ScenarioDriver infra = new InfrastructureScenarioDriver();

        assertThat(infra.withRunTag("refde9e17")).isSameAs(infra);
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
        final List<String> payments = new ArrayList<>();
        int failOnCall = -1; // 1-based；-1 = 不失败
        boolean nullOrders;    // true = 受理无单（F16 入口静默 accepted 零落单形态）

        @Override
        public String createOrder(String intentId, String correlationId, String sku) {
            orders.add(intentId + "/" + correlationId);
            if (orders.size() == failOnCall) {
                throw new IllegalStateException("arena 502");
            }
            return nullOrders ? null : "order-" + orders.size();
        }

        @Override
        public void payOrder(String orderId, String correlationId) {
            payments.add(orderId + "/" + correlationId);
        }
    }
}
