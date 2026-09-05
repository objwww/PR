package com.objwww.pr.arena;

import com.objwww.pr.arena.application.CompensationWorker;
import com.objwww.pr.arena.application.DomainProbe;
import com.objwww.pr.arena.application.F3ReconcileService;
import com.objwww.pr.arena.application.OrderCreationSteps;
import com.objwww.pr.arena.application.PaymentGatewaySimulator;
import com.objwww.pr.arena.application.RefundChainService;
import com.objwww.pr.arena.application.TrafficGenerator;
import com.objwww.pr.arena.application.TwoStepOrderService;
import com.objwww.pr.arena.application.chaos.ChaosRecoveryService;
import com.objwww.pr.arena.application.chaos.ChaosSwitchboard;
import com.objwww.pr.arena.domain.model.IdempotencyClaim;
import com.objwww.pr.arena.infrastructure.persistence.PostgresChaosInjectionStore;
import com.objwww.pr.arena.infrastructure.persistence.PostgresCompensationOutboxRepository;
import com.objwww.pr.arena.infrastructure.persistence.PostgresFulfillmentOrderRepository;
import com.objwww.pr.arena.infrastructure.persistence.PostgresIdempotencyRepository;
import com.objwww.pr.arena.infrastructure.persistence.PostgresPaymentRecordRepository;
import com.objwww.pr.arena.infrastructure.persistence.PostgresProbeStore;
import com.objwww.pr.arena.infrastructure.persistence.PostgresRefundOrderRepository;
import com.objwww.pr.arena.infrastructure.persistence.PostgresResourceLedgerRepository;
import com.objwww.pr.arena.infrastructure.persistence.PostgresTradeOrderRepository;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import java.util.UUID;

/**
 * docker profile 装配（M2-01 约定：零注解领域 + 显式手工装配）。全部业务类为普通类，
 * 此处是它们唯一的 bean 化点。事务模板按消费者私有实例（OrderCreationSteps 构造期
 * 会把模板改为 REQUIRES_NEW，不与其他消费者共享同一实例）。
 */
@Configuration
@Profile("docker")
public class ArenaConfig {

    @Bean
    public org.springframework.jdbc.core.simple.JdbcClient arenaJdbcClient(DataSource ds) {
        return org.springframework.jdbc.core.simple.JdbcClient.create(ds);
    }

    // ---------- 仓储 ----------

    @Bean
    public PostgresTradeOrderRepository tradeOrderRepository(
            org.springframework.jdbc.core.simple.JdbcClient jdbc,
            PostgresFulfillmentOrderRepository fulfillmentOrders) {
        return new PostgresTradeOrderRepository(jdbc, fulfillmentOrders);
    }

    @Bean
    public PostgresFulfillmentOrderRepository fulfillmentOrderRepository(
            org.springframework.jdbc.core.simple.JdbcClient jdbc) {
        return new PostgresFulfillmentOrderRepository(jdbc);
    }

    @Bean
    public PostgresPaymentRecordRepository paymentRecordRepository(
            org.springframework.jdbc.core.simple.JdbcClient jdbc) {
        return new PostgresPaymentRecordRepository(jdbc);
    }

    @Bean
    public PostgresResourceLedgerRepository resourceLedgerRepository(
            org.springframework.jdbc.core.simple.JdbcClient jdbc) {
        return new PostgresResourceLedgerRepository(jdbc);
    }

    @Bean
    public PostgresRefundOrderRepository refundOrderRepository(
            org.springframework.jdbc.core.simple.JdbcClient jdbc) {
        return new PostgresRefundOrderRepository(jdbc);
    }

    @Bean
    public PostgresCompensationOutboxRepository compensationOutboxRepository(
            org.springframework.jdbc.core.simple.JdbcClient jdbc) {
        return new PostgresCompensationOutboxRepository(jdbc);
    }

    @Bean
    public PostgresIdempotencyRepository idempotencyRepository(
            org.springframework.jdbc.core.simple.JdbcClient jdbc) {
        return new PostgresIdempotencyRepository(jdbc);
    }

    @Bean
    public PostgresChaosInjectionStore chaosInjectionStore(
            org.springframework.jdbc.core.simple.JdbcClient jdbc) {
        return new PostgresChaosInjectionStore(jdbc);
    }

    @Bean
    public PostgresProbeStore probeStore(
            org.springframework.jdbc.core.simple.JdbcClient jdbc) {
        return new PostgresProbeStore(jdbc);
    }

    // ---------- 应用服务 ----------

    @Bean
    public ChaosSwitchboard chaosSwitchboard(
            org.springframework.jdbc.core.simple.JdbcClient jdbc,
            @Value("${app.arena.chaos.cache-ttl-ms:2000}") long cacheTtlMs) {
        return new ChaosSwitchboard(jdbc, Duration.ofMillis(cacheTtlMs));
    }

    @Bean
    public OrderCreationSteps orderCreationSteps(PlatformTransactionManager tm,
                                                 PostgresTradeOrderRepository tradeOrders,
                                                 PostgresFulfillmentOrderRepository fulfillments,
                                                 PostgresResourceLedgerRepository ledger,
                                                 PostgresPaymentRecordRepository payments,
                                                 PostgresCompensationOutboxRepository outbox) {
        TransactionTemplate requiresNew = new TransactionTemplate(tm);
        return new OrderCreationSteps(requiresNew, tradeOrders, fulfillments, ledger,
                payments, outbox);
    }

    @Bean
    public PaymentGatewaySimulator paymentGatewaySimulator(ChaosSwitchboard gate) {
        return new PaymentGatewaySimulator(gate);
    }

    @Bean
    public RefundChainService refundChainService(PlatformTransactionManager tm,
                                                 PostgresRefundOrderRepository refunds,
                                                 PostgresTradeOrderRepository tradeOrders) {
        TransactionTemplate template = new TransactionTemplate(tm);
        return new RefundChainService(action -> template.executeWithoutResult(
                status -> action.run()), refunds, tradeOrders);
    }

    @Bean
    public TwoStepOrderService twoStepOrderService(
            OrderCreationSteps steps,
            PaymentGatewaySimulator gateway,
            ChaosSwitchboard gate,
            PostgresTradeOrderRepository tradeOrders,
            RefundChainService refundChain,
            PostgresIdempotencyRepository idempotency,
            @Value("${app.arena.idempotency.owner:arena-app}") String idemOwner,
            @Value("${app.arena.idempotency.lease-seconds:30}") long idemLeaseSeconds,
            @Value("${app.arena.idempotency.ttl-hours:24}") long idemTtlHours) {
        TwoStepOrderService.IdempotencyOperations ops = new TwoStepOrderService.IdempotencyOperations() {
            @Override
            public IdempotencyClaim claim(String intentId, String requestDigest) {
                return idempotency.claim(intentId, requestDigest, idemOwner,
                        Duration.ofSeconds(idemLeaseSeconds),
                        Duration.ofHours(idemTtlHours));
            }

            @Override
            public boolean complete(String intentId, long leaseEpoch, UUID resultOrderId,
                                    String responseDigest) {
                return idempotency.complete(intentId, leaseEpoch, resultOrderId,
                        responseDigest);
            }
        };
        return new TwoStepOrderService(steps, gateway, gate, tradeOrders, refundChain, ops);
    }

    @Bean
    public CompensationWorker compensationWorker(
            PostgresCompensationOutboxRepository outbox,
            PostgresResourceLedgerRepository ledger) {
        return new CompensationWorker(outbox, ledger);
    }

    @Bean
    public F3ReconcileService f3ReconcileService(PostgresPaymentRecordRepository payments,
                                                 PostgresTradeOrderRepository tradeOrders,
                                                 OrderCreationSteps steps,
                                                 @Value("${app.arena.f3.lease-seconds:60}")
                                                 long leaseSeconds,
                                                 @Value("${app.arena.f3.unknown-older-than-ms:10000}")
                                                 long unknownOlderThanMs,
                                                 @Value("${app.arena.f3.batch:8}") int batch) {
        return new F3ReconcileService(payments, tradeOrders, steps, "arena-f3",
                Duration.ofSeconds(leaseSeconds), Duration.ofMillis(unknownOlderThanMs),
                batch);
    }

    @Bean
    public ChaosRecoveryService chaosRecoveryService(
            ChaosSwitchboard switchboard,
            PostgresChaosInjectionStore injectionStore,
            PostgresTradeOrderRepository tradeOrders,
            PostgresPaymentRecordRepository payments,
            OrderCreationSteps steps,
            RefundChainService refundChain,
            F3ReconcileService f3Reconcile,
            @Value("${app.arena.chaos.f2-batch:16}") int f2Batch) {
        return new ChaosRecoveryService(switchboard, injectionStore, tradeOrders, payments,
                steps, refundChain, f3Reconcile, f2Batch);
    }

    @Bean
    public DomainProbe domainProbe(PostgresProbeStore probeStore,
                                   MeterRegistry registry,
                                   @Value("${app.arena.probe.stuck-threshold-seconds:60}")
                                   int stuckThresholdSeconds) {
        return new DomainProbe(probeStore, stuckThresholdSeconds, registry);
    }

    @Bean
    public TrafficGenerator trafficGenerator(TwoStepOrderService orders,
                                            MeterRegistry registry,
                                            @Value("${app.arena.traffic.concurrency:4}")
                                            int concurrency,
                                            @Value("${app.arena.traffic.interval-ms:1000}")
                                            long intervalMs) {
        // 正常流量旅程（live- 专供）：创单 → 成功即支付 → 支付即取消（确定性轻负载）
        TrafficGenerator.Journey journey = correlationId -> {
            var outcome = orders.create(correlationId, correlationId, "buyer-traffic",
                    "sku-std", 1, new BigDecimal("10.00"));
            if (outcome instanceof TwoStepOrderService.CreateOutcome.Created c) {
                var paid = orders.pay(c.orderId(), correlationId);
                if (paid instanceof TwoStepOrderService.PayOutcome.Paid) {
                    orders.cancel(c.orderId(), "traffic-journey",
                            com.objwww.pr.arena.domain.model.RefundParty.BUYER);
                }
            }
        };
        return new TrafficGenerator(journey, concurrency, intervalMs, registry);
    }

    // ---------- 运行时（六循环 + 流量发生器） ----------

    @Bean
    public ArenaRuntime arenaRuntime(CompensationWorker worker,
                                     ChaosRecoveryService recovery,
                                     F3ReconcileService f3,
                                     DomainProbe probe,
                                     TrafficGenerator traffic,
                                     @Value("${app.arena.compensation.poll-interval-ms:2000}")
                                     long compensationIntervalMs,
                                     @Value("${app.arena.chaos.scan-interval-ms:5000}")
                                     long chaosIntervalMs,
                                     @Value("${app.arena.f3.reconcile-interval-ms:5000}")
                                     long f3IntervalMs,
                                     @Value("${app.arena.probe.interval-ms:30000}")
                                     long probeIntervalMs,
                                     @Value("${app.arena.traffic.enabled:false}")
                                     boolean trafficEnabled) {
        var runtime = new ArenaRuntime(List.of(
                new ArenaRuntime.Loop("compensation", () -> worker.processOne("arena-comp",
                        Duration.ofSeconds(30), Duration.ofSeconds(5)),
                        compensationIntervalMs),
                new ArenaRuntime.Loop("chaos-recovery", recovery::scanOnce, chaosIntervalMs),
                new ArenaRuntime.Loop("f3-reconcile", f3::reconcileOnce, f3IntervalMs),
                new ArenaRuntime.Loop("domain-probe", probe::scanOnce, probeIntervalMs)));
        if (trafficEnabled) {
            runtime.withStartable(traffic);
        }
        return runtime;
    }
}
