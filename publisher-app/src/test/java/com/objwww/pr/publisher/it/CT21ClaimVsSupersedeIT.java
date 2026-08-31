package com.objwww.pr.publisher.it;

import com.objwww.pr.control.domain.model.ReviewRun;
import com.objwww.pr.publisher.domain.model.ClaimedCommand;
import com.objwww.pr.publisher.domain.port.PublicationStore;
import com.objwww.pr.publisher.domain.port.StaleLeaseException;
import com.objwww.pr.publisher.domain.service.T3ADecision;
import com.objwww.pr.shared.CommandType;
import com.objwww.pr.shared.Digest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * CT-21 Claim 与换届 supersede 的 DB 级真并发（评审对账缺口）：同一条旧 epoch 命令，
 * 线程 A 走 claim→prepare（→IN_FLIGHT），线程 B 走 supersedeStaleEpoch（→SUPERSEDED），
 * CyclicBarrier 同时起跑 × 20 轮。断言每轮终态恰好是其一，绝不出现"既 IN_FLIGHT 又被
 * supersede 后仍被 claim 成功"的双生效。
 *
 * <p>结构说明（读码确认，非假设）：claim 只挂租约不改 state（仍是 PENDING），它本身不与
 * supersede 竞争终态——竞争终态的是 prepare(→IN_FLIGHT) 与 supersede(→SUPERSEDED) 两个
 * 单事务行锁翻转，因此 claim 在 barrier 前完成，barrier 后两侧各一次行锁roundtrip。
 * 互斥来源：两侧都在单事务内对同一行 {@code FOR UPDATE} 加锁后复核——prepare 要求
 * {@code lease_epoch 匹配且 state=PENDING}；supersedeStaleEpoch 锁内复核
 * {@code state IN (PENDING,RETRY_WAIT) 且 epoch 仍落后}，IN_FLIGHT 不级联（I7）。
 * 行锁把两条路径串行化，后到者复核必然看到前者的终态，双生效在结构上不可能。
 *
 * <p>观察记录（非缺陷）：CLAIM_SQL 的 WHERE 不含 epoch 守卫，旧 epoch 命令仍可被领取——
 * 但生产路径上租约栅栏 + T3-A epoch fence（I6）在触网前必然拦截；此处刻意绕过 gate 直调
 * prepare(PROCEED) 是为了把竞速压到 DB 原语层。
 */
class CT21ClaimVsSupersedeIT extends PostgresITBase {

    private static final int ROUNDS = 20;

    private ItHarness harness;
    private PublicationStore store;

    @BeforeEach
    void setUp() {
        // 纯 DB 竞速，不触网：GitHub stub 地址不被使用
        harness = new ItHarness(casDir, "http://localhost:9");
        store = harness.postgresStore;
    }

    private Map<String, Object> outboxRow(UUID operationId) {
        return adminJdbc.sql("SELECT state, last_error_code FROM outbox_command WHERE operation_id = :id")
                .param("id", operationId)
                .query((rs, n) -> Map.<String, Object>of(
                        "state", rs.getString("state"),
                        "last_error_code", rs.getString("last_error_code") == null
                                ? "" : rs.getString("last_error_code")))
                .single();
    }

    @Test
    void claimVsSupersedeNeverDoubleEffects() throws Exception {
        ReviewRun run = harness.runIntakeDirect(
                ItHarness.prEvent("ct21-d1", 1021L, "objwww/mall", 21,
                        "head" + "6".repeat(36), "opened"),
                Digest.sha256Of("ct21-diff"), Digest.sha256Of("ct21-snapshot"));
        UUID subjectId = harness.subjectRepo.findByRepositoryAndPrNumber(1021L, 21)
                .orElseThrow().getId();
        Map<String, Object> payload = Map.of("repo", "objwww/mall",
                "head_sha", "head" + "6".repeat(36), "name", "ai-code-review", "finding_count", 0);

        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            int inFlightWins = 0;
            int supersededWins = 0;
            for (int round = 0; round < ROUNDS; round++) {
                var seeded = harness.seedCommand(subjectId, run.getId(), run.getPrRevisionId(),
                        "pr:1021#21", CommandType.CREATE_CHECK, payload, List.of());
                UUID opId = seeded.operationId().value();
                // 换届：subject epoch 前进一格，命令即刻成为旧世代
                adminJdbc.sql("UPDATE pr_subject SET publication_epoch = publication_epoch + 1," +
                                " updated_at = now() WHERE id = :id")
                        .param("id", subjectId).update();
                // claim 先完成（只挂租约不改 state，不与 supersede 竞争终态）
                ClaimedCommand mine = store.claim("ct21-a", Duration.ofSeconds(30), 10).stream()
                        .filter(c -> c.operationId().value().equals(opId))
                        .findFirst().orElseThrow();

                CyclicBarrier barrier = new CyclicBarrier(2);
                AtomicBoolean aPrepared = new AtomicBoolean(false);
                // 线程 A：prepare(PROCEED→IN_FLIGHT)。绕过 gate 直调 DB 原语，把竞速压在行锁层
                Runnable raceA = () -> {
                    await(barrier);
                    try {
                        store.prepare(opId, mine.leaseEpoch(), ctx -> T3ADecision.proceed());
                        aPrepared.set(true);
                    } catch (StaleLeaseException e) {
                        // 输给 supersede：锁内复核看到非 PENDING，预期内放弃（B-2）
                    }
                };
                // 线程 B：换届级联 supersede（锁内复核 state + epoch）
                Runnable raceB = () -> {
                    await(barrier);
                    store.supersedeStaleEpoch(opId);
                };
                // 逐轮交替提交次序，避免固定调度偏向掩盖其中一条路径
                Future<?> first = pool.submit(round % 2 == 0 ? raceA : raceB);
                Future<?> second = pool.submit(round % 2 == 0 ? raceB : raceA);
                first.get();
                second.get();

                Map<String, Object> row = outboxRow(opId);
                if (aPrepared.get()) {
                    // A 先赢行锁：B 复核见 IN_FLIGHT 必须放弃（I7），终态纯粹 IN_FLIGHT
                    assertThat(row).containsEntry("state", "IN_FLIGHT")
                            .containsEntry("last_error_code", "");
                    inFlightWins++;
                } else {
                    // B 先赢：A 复核见 SUPERSEDED 抛 StaleLease，终态纯粹 SUPERSEDED
                    assertThat(row).containsEntry("state", "SUPERSEDED")
                            .containsEntry("last_error_code", "STALE_EPOCH");
                    supersededWins++;
                }
                // "supersede 后仍被 claim 成功" 永不成立：CLAIM_SQL 只选 PENDING/RETRY_WAIT
                assertThat(store.claim("ct21-verify", Duration.ofSeconds(30), 10))
                        .noneMatch(c -> c.operationId().value().equals(opId));
            }
            // 20 轮内两条路径都真实赢过（否则竞速没有被真正演练，测试失去意义）
            assertThat(inFlightWins).isPositive();
            assertThat(supersededWins).isPositive();
            assertThat(inFlightWins + supersededWins).isEqualTo(ROUNDS);
        } finally {
            pool.shutdownNow();
        }
    }

    private static void await(CyclicBarrier barrier) {
        try {
            barrier.await();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
