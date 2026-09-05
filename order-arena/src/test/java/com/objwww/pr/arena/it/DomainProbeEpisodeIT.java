package com.objwww.pr.arena.it;

import com.objwww.pr.arena.application.DomainProbe;
import com.objwww.pr.arena.infrastructure.persistence.PostgresProbeStore;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * M2-21/C-7 探针 IT：episode 开/续/关/复发语义（同问题连续扫描只计一次、
 * 修复后复发重新计一次）、gauge=打开数、counter=累计、DB 失败保留末值 + probe_down。
 */
class DomainProbeEpisodeIT extends ArenaPostgresITBase {

    private record ProbeHarness(DomainProbe probe, SimpleMeterRegistry registry) {
    }

    private ProbeHarness harness() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        return new ProbeHarness(new DomainProbe(new PostgresProbeStore(arenaJdbc),
                0, registry), registry);
    }

    private double gauge(SimpleMeterRegistry registry, String name) {
        return registry.get(name).gauge().value();
    }

    private double counter(SimpleMeterRegistry registry, String name) {
        return registry.get(name).counter().count();
    }

    @Test
    void episode生命周期_开续关复发() {
        UUID stuckOrder = seedTradeOrder("intent-stuck-1", "live-1",
                "CREATED", "NOT_PAY", null, "sku-9", 999);
        ProbeHarness h = harness();

        // 开：首次扫描 = 新 episode
        h.probe().scanOnce();
        assertThat(gauge(h.registry(), "oa_stuck_orders_current")).isEqualTo(1.0);
        assertThat(counter(h.registry(), "oa_stuck_orders_detected")).isEqualTo(1.0);

        // 续：同问题连续扫描只计一次（不新增 episode）
        h.probe().scanOnce();
        assertThat(counter(h.registry(), "oa_stuck_orders_detected")).isEqualTo(1.0);

        // 关：事实修复 → episode 关闭，gauge 归零（真实归零，非失明）
        adminJdbc.sql("""
                UPDATE arena.oa_trade_order SET booking_status = 'ENABLED',
                    enabled_at = now() WHERE id = :id
                """).param("id", stuckOrder).update();
        h.probe().scanOnce();
        assertThat(gauge(h.registry(), "oa_stuck_orders_current")).isEqualTo(0.0);
        long open = adminJdbc.sql("""
                SELECT count(*) FROM arena.oa_probe_finding
                WHERE finding_type = 'STUCK_ORDER' AND resolved_at IS NULL
                """).query(Long.class).single();
        assertThat(open).isZero();

        // 复发（F2 回跳同一单）：新 episode_no，重新计一次
        adminJdbc.sql("""
                UPDATE arena.oa_trade_order SET booking_status = 'CREATED'
                WHERE id = :id
                """).param("id", stuckOrder).update();
        h.probe().scanOnce();
        assertThat(gauge(h.registry(), "oa_stuck_orders_current")).isEqualTo(1.0);
        assertThat(counter(h.registry(), "oa_stuck_orders_detected")).isEqualTo(2.0);
        Long episodeNo = adminJdbc.sql("""
                SELECT max(episode_no) FROM arena.oa_probe_finding
                WHERE finding_type = 'STUCK_ORDER' AND entity_id = :e
                """).param("e", stuckOrder.toString()).query(Long.class).single();
        assertThat(episodeNo).isEqualTo(2L);
    }

    @Test
    void 状态违规回跳签名与PAID无事实两路可检出() {
        UUID backjumped = seedTradeOrder("intent-bj", "live-2",
                "CREATED", "NOT_PAY", null, "sku-9", 0);
        adminJdbc.sql("""
                UPDATE arena.oa_trade_order SET enabled_at = now() WHERE id = :id
                """).param("id", backjumped).update();
        UUID paidNoFact = seedTradeOrder("intent-pnf", "live-3",
                "ENABLED", "PAID", null, "sku-9", 0);

        ProbeHarness h = harness();
        h.probe().scanOnce();
        assertThat(gauge(h.registry(), "oa_state_violations_current")).isEqualTo(2.0);

        // 消除一路（补支付事实），另一路保持
        adminJdbc.sql("""
                INSERT INTO arena.oa_payment_record(id, order_id, attempt_no, kind, result,
                    amount, initiated_at, settled_at)
                VALUES (:id, :order, 1, 'AUTH', 'SUCCEEDED', 100.00, now(), now())
                """).param("id", UUID.randomUUID()).param("order", paidNoFact).update();
        h.probe().scanOnce();
        assertThat(gauge(h.registry(), "oa_state_violations_current")).isEqualTo(1.0);
        assertThat(counter(h.registry(), "oa_state_violations_detected")).isEqualTo(2.0);
    }

    @Test
    void DB失败_保留末值_probe_down_绝不伪装恢复() {
        seedTradeOrder("intent-stuck-2", "live-4",
                "CREATED", "NOT_PAY", null, "sku-9", 999);
        SimpleMeterRegistry registry = new SimpleMeterRegistry();

        // 同一探针实例：先成功后失明（flaky store 第二轮起让 STUCK 查询失败）
        boolean[] tripped = {false};
        PostgresProbeStore flaky = new PostgresProbeStore(arenaJdbc) {
            @Override
            public java.util.List<Violation> stuckOrders(int olderThanSeconds) {
                if (tripped[0]) {
                    throw new org.springframework.dao.DataAccessResourceFailureException(
                            "simulated outage");
                }
                return super.stuckOrders(olderThanSeconds);
            }
        };
        DomainProbe probe = new DomainProbe(flaky, 0, registry);

        probe.scanOnce();
        assertThat(gauge(registry, "oa_domain_probe_up")).isEqualTo(1.0);
        assertThat(gauge(registry, "oa_stuck_orders_current")).isEqualTo(1.0);
        double lastSuccess = gauge(registry, "oa_domain_probe_last_success_timestamp");
        assertThat(lastSuccess).isGreaterThan(0);

        tripped[0] = true;
        var result = probe.scanOnce();
        assertThat(result.ok()).isFalse();
        // C-7 三连：probe_down、业务 gauge 保留末值（不伪装恢复）、成功时间戳停摆
        assertThat(gauge(registry, "oa_domain_probe_up")).isEqualTo(0.0);
        assertThat(gauge(registry, "oa_stuck_orders_current")).isEqualTo(1.0);
        assertThat(gauge(registry, "oa_domain_probe_last_success_timestamp"))
                .isEqualTo(lastSuccess);
    }
}
