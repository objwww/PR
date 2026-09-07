package com.objwww.pr.control.it;

import com.objwww.pr.control.alert.application.RunBudgetGate;
import com.objwww.pr.control.alert.domain.budget.BudgetExhaustedException;
import com.objwww.pr.control.alert.domain.budget.BudgetKind;
import com.objwww.pr.control.alert.domain.budget.BudgetProbe;
import com.objwww.pr.control.alert.domain.budget.ReservationKey;
import com.objwww.pr.control.infrastructure.persistence.PostgresRunBudgetLedger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * M4-08 真 PG 预算账本（V13）——评审 7 条增补 IT 对应：
 * ①100 并发预留永不超扣 ②同业务键重试不重复预留（预算打满时重试也必须放行）
 * ③预留后进程崩溃可对账（stale 扫描发现→release 收口） ④发送前后取消不同结算路径
 * ⑤usage 缺失不按零释放（PROVISIONAL→UNMATCHED 账面不动） ⑥预算存储不可用零远程调用
 * （fail-closed） ⑦锁持有边界=网络调用期间并发 reserve 不被阻塞。
 * 附带：commit 退款算术与软超限、REPORT 报告专项预算哨兵键。
 */
class AlertRunBudgetLedgerIT extends PostgresITBase {

    private PostgresRunBudgetLedger ledger;

    @Override
    @BeforeEach
    void truncateAll() {
        adminJdbc.sql("""
                TRUNCATE pr_subject, pr_revision, review_run, run_step, work_item, step_attempt,
                    execution_event, outbox_command, outbox_dependency, publication_resource,
                    review_finding, artifact, webhook_inbox, step_checkpoint, repair_request,
                    model_call_ledger, tool_call, sandbox_job, artifact_grant,
                    alert_inbox, alert_event, incident, rca_run, rca_task, rca_attempt,
                    rca_report, external_invocation_ledger, rca_task_edge,
                    run_budget_state, run_budget_entry, incident_budget_entry
                RESTART IDENTITY CASCADE
                """).update();
        ledger = new PostgresRunBudgetLedger(controlJdbc, controlTx);
    }

    @Test
    void itB01_hundredConcurrentReservesNeverOverDeduct() throws Exception {
        UUID run = UUID.randomUUID();
        ledger.ensureLimit(run, BudgetKind.TOKEN, 50);
        ExecutorService pool = Executors.newFixedThreadPool(32);
        try {
            AtomicInteger allowed = new AtomicInteger();
            CountDownLatch start = new CountDownLatch(1);
            List<java.util.concurrent.Future<?>> futures = new java.util.ArrayList<>();
            for (int i = 0; i < 100; i++) {
                int seq = i;
                futures.add(pool.submit(() -> {
                    start.await();
                    BudgetProbe probe = ledger.reserve(key(run, seq), 1);
                    if (probe.allowed()) {
                        allowed.incrementAndGet();
                    }
                    return null;
                }));
            }
            start.countDown();
            for (java.util.concurrent.Future<?> f : futures) {
                f.get(60, TimeUnit.SECONDS);
            }
            assertThat(allowed.get()).isEqualTo(50); // 恰好放行到上限，永不超扣
            assertThat(consumed(run)).isEqualTo(50);
            assertThat(count("run_budget_entry")).isEqualTo(50);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void itB02_sameKeyRetryReadsSameReservationEvenWhenBudgetFull() {
        UUID run = UUID.randomUUID();
        ledger.ensureLimit(run, BudgetKind.TOKEN, 10);
        ReservationKey key = key(run, 1);
        BudgetProbe first = ledger.reserve(key, 10);
        assertThat(first.allowed()).isTrue();
        assertThat(consumed(run)).isEqualTo(10);

        // 重试：预算已打满也必须放行（读同一笔，不重复扣）——若先扣后查幂等键，此处会误拒
        BudgetProbe replay = ledger.reserve(key, 10);
        assertThat(replay.allowed()).isTrue();
        assertThat(consumed(run)).isEqualTo(10);
        assertThat(count("run_budget_entry")).isEqualTo(1);
        // 换新键同量 → 才真正被拒
        assertThat(ledger.reserve(key(run, 2), 10).allowed()).isFalse();
    }

    @Test
    void itB03_crashedReservationIsReconcilableViaStaleScan() {
        UUID run = UUID.randomUUID();
        ledger.ensureLimit(run, BudgetKind.TOKEN, 5);
        ReservationKey key = key(run, 1);
        ledger.reserve(key, 1); // 进程在此刻崩溃：RESERVED 滞留
        assertThat(ledger.findStaleReservations(Instant.now())).contains(key);

        ledger.release(key); // Reconciler 收口（M4-37 接线）
        assertThat(ledger.findStaleReservations(Instant.now())).doesNotContain(key);
        assertThat(consumed(run)).isZero(); // 全额退款
    }

    @Test
    void itB04_cancelBeforeSendReleases_cancelAfterSendGoesProvisional() {
        UUID run = UUID.randomUUID();
        ledger.ensureLimit(run, BudgetKind.TOKEN, 100);
        // 发送前取消 → release 全额退款
        ReservationKey before = key(run, 1);
        ledger.reserve(before, 30);
        ledger.release(before);
        assertThat(consumed(run)).isZero();
        assertThat(entryState(before)).isEqualTo("RELEASED");
        // 发送后取消 → PROVISIONAL：不立即按 input floor 平账，账面不动等对账
        ReservationKey after = key(run, 2);
        ledger.reserve(after, 40);
        ledger.provisional(after);
        assertThat(consumed(run)).isEqualTo(40); // 预留保留，不退款
        assertThat(entryState(after)).isEqualTo("PROVISIONAL");
    }

    @Test
    void itB05_missingUsageNeverRefundsAsZero() {
        UUID run = UUID.randomUUID();
        ledger.ensureLimit(run, BudgetKind.TOKEN, 100);
        ReservationKey key = key(run, 1);
        ledger.reserve(key, 25);
        ledger.provisional(key);
        ledger.markUnmatched(key); // 对账判定 usage 永不到达：记 UNMATCHED，不伪造零退款
        assertThat(consumed(run)).isEqualTo(25);
        assertThat(entryState(key)).isEqualTo("UNMATCHED");
        // RELEASED/UNMATCHED 是终态：再 commit 落空显式报错
        assertThatThrownBy(() -> ledger.commit(key, 25))
                .isInstanceOf(IllegalStateException.class);
        assertThat(consumed(run)).isEqualTo(25);
    }

    @Test
    void itB06_budgetStorageUnavailableFailsClosedWithZeroRemoteCalls() {
        UUID run = UUID.randomUUID();
        ledger.ensureLimit(run, BudgetKind.TOKEN, 100);
        AtomicInteger remoteCalls = new AtomicInteger();
        RunBudgetGate gate = new RunBudgetGate(ledger);
        try {
            adminJdbc.sql("""
                    REVOKE SELECT, INSERT, UPDATE ON run_budget_state, run_budget_entry
                        FROM control_app
                    """).update();
            assertThatThrownBy(() -> gate.call(key(run, 1), 10,
                    () -> {
                        remoteCalls.incrementAndGet();
                        return "must-not-happen";
                    }, usage -> 1L))
                    .isInstanceOf(RuntimeException.class);
            assertThat(remoteCalls.get()).isZero(); // fail-closed：存储坏 → 零远程
        } finally {
            adminJdbc.sql("""
                    GRANT SELECT, INSERT, UPDATE ON run_budget_state, run_budget_entry,
                        incident_budget_entry TO control_app
                    """).update();
        }
    }

    @Test
    void itB07_noLockHeldAcrossRemoteCall() throws Exception {
        UUID run = UUID.randomUUID();
        ledger.ensureLimit(run, BudgetKind.TOKEN, 100);
        // gate 线程进入"网络调用"（阻塞在 latch）；主线程此刻并发预留必须立即完成——
        // 若账本在远程期间持锁，这里会死等超时
        CountDownLatch remoteDone = new CountDownLatch(1);
        AtomicReference<Throwable> gateFailure = new AtomicReference<>();
        Thread gateThread = new Thread(() -> {
            try {
                new RunBudgetGate(ledger).call(key(run, 1), 10, () -> {
                    try {
                        remoteDone.await(30, TimeUnit.SECONDS);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                    return 4; // 服务端 usage=4 < 预留 10
                }, usage -> usage);
            } catch (Throwable t) {
                gateFailure.set(t);
            }
        });
        gateThread.start();
        try {
            BudgetProbe concurrent = ledger.reserve(key(run, 2), 10); // 网络期并发预留
            assertThat(concurrent.allowed()).isTrue();
        } finally {
            remoteDone.countDown();
            gateThread.join(30_000);
        }
        assertThat(gateFailure.get()).isNull();
        assertThat(consumed(run)).isEqualTo(10 + 4); // key2 预留 10 + key1 实扣 4（退款 6）
    }

    @Test
    void itB08_commitReconcilesEstimate_allowsSoftOverage() {
        UUID run = UUID.randomUUID();
        ledger.ensureLimit(run, BudgetKind.TOKEN, 12);
        // 实扣 < 预留 → 退多余
        ReservationKey frugal = key(run, 1);
        ledger.reserve(frugal, 10);
        ledger.commit(frugal, 3);
        assertThat(consumed(run)).isEqualTo(3);
        // 实扣 > 预留 → 软超限（真实消耗不可撤）
        ReservationKey hungry = key(run, 2);
        ledger.reserve(hungry, 8); // consumed 3+8=11 ≤ 12
        ledger.commit(hungry, 14); // consumed 3+(14-8)=17 > 12
        assertThat(consumed(run)).isEqualTo(17);
        assertThat(entryState(hungry)).isEqualTo("COMMITTED");
        assertThat(count("run_budget_entry")).isEqualTo(2);
    }

    @Test
    void itB09_reportBudgetUsesNilUuidSentinelKey() {
        UUID run = UUID.randomUUID();
        ledger.ensureLimit(run, BudgetKind.REPORT, 1);
        ledger.ensureLimit(run, BudgetKind.TOKEN, 10); // 任务维度限额行（独立性对照面）
        ReservationKey report = ReservationKey.forReport(run);
        assertThat(report.taskId()).isEqualTo(new UUID(0L, 0L));
        assertThat(report.attemptId()).isEqualTo(new UUID(0L, 0L));
        assertThat(ledger.reserve(report, 1).allowed()).isTrue();
        // 报告专项预算独立于任务维度预算：第二个报告键被拒（限额 1）
        assertThat(ledger.reserve(ReservationKey.forReport(UUID.randomUUID()), 1).allowed())
                .isFalse(); // 别的 run 无限额行 → 条件 UPDATE 0 行，fail-closed 拒
        assertThat(ledger.reserve(key(run, 1), 1).allowed()).isTrue(); // 同 run 任务维度不受影响
    }

    // ------------------------------------------------------------------ 断言助手

    private ReservationKey key(UUID run, int seq) {
        return new ReservationKey(run, UUID.randomUUID(), UUID.randomUUID(), seq,
                BudgetKind.TOKEN);
    }

    private long consumed(UUID run) {
        return controlJdbc.sql("""
                select consumed_units from run_budget_state
                 where run_id = :run and budget_kind = 'TOKEN'
                """).param("run", run).query(Long.class).single();
    }

    private String entryState(ReservationKey key) {
        return controlJdbc.sql("""
                select state from run_budget_entry
                 where run_id = :run and task_id = :task and attempt_id = :attempt
                   and call_seq = :seq and budget_kind = :kind
                """)
                .param("run", key.runId()).param("task", key.taskId())
                .param("attempt", key.attemptId()).param("seq", key.callSeq())
                .param("kind", key.budgetKind().name())
                .query(String.class).single();
    }
}
