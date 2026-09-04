package com.objwww.pr.control.it;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.objwww.pr.control.alert.application.AlertClock;
import com.objwww.pr.control.alert.application.IncidentProjector;
import com.objwww.pr.control.alert.application.ParsedAlert;
import com.objwww.pr.control.alert.domain.model.AlertFiringStatus;
import com.objwww.pr.control.alert.domain.model.Incident;
import com.objwww.pr.control.alert.domain.model.IncidentStatus;
import com.objwww.pr.control.alert.domain.model.RcaRun;
import com.objwww.pr.control.alert.domain.model.RcaRunState;
import com.objwww.pr.control.alert.domain.model.RcaTask;
import com.objwww.pr.control.alert.domain.model.RcaTaskState;
import com.objwww.pr.control.alert.domain.model.RunTrigger;
import com.objwww.pr.control.alert.domain.repository.AlertEventRepository;
import com.objwww.pr.control.alert.domain.repository.IncidentRepository;
import com.objwww.pr.control.alert.domain.repository.RcaRunRepository;
import com.objwww.pr.control.alert.domain.repository.RcaTaskRepository;
import com.objwww.pr.control.alert.domain.repository.SchedulerSlotRepository;
import com.objwww.pr.control.alert.domain.service.AlertIdentityFactory;
import com.objwww.pr.control.alert.domain.service.DeferredPolicy;
import com.objwww.pr.control.alert.domain.service.SlaPolicy;
import com.objwww.pr.control.alert.domain.model.AlertInbox;
import com.objwww.pr.control.alert.domain.model.AlertGroupEnvelope;
import com.objwww.pr.control.alert.domain.model.InboxState;
import com.objwww.pr.control.alert.domain.repository.AlertInboxRepository;
import com.objwww.pr.control.infrastructure.persistence.PostgresAlertEventRepository;
import com.objwww.pr.control.infrastructure.persistence.PostgresIncidentRepository;
import com.objwww.pr.control.infrastructure.persistence.PostgresRcaRunRepository;
import com.objwww.pr.control.infrastructure.persistence.PostgresRcaTaskRepository;
import com.objwww.pr.control.infrastructure.persistence.PostgresSchedulerSlotRepository;
import com.objwww.pr.control.infrastructure.persistence.PostgresAlertInboxRepository;
import com.objwww.pr.shared.Digest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * AM1 告警域调度核心真 PG 集成（G0-08，BA-09③ 收口）：
 * CT-A02 task 并发 claim 互斥、CT-A03 uq_rca_run_active_incident 23505 实证、
 * CT-A04 slot 原子领取、CT-A05 slot 租约过期回收、BA-12① 同 episode 乱序（投影器真栈路径）。
 *
 * <p>与单测（AlertInMemoryStores 约束模拟）互补：这里跑的是真实 SKIP LOCKED / 部分唯一索引 /
 * 角色授权。命名 *IT 由 failsafe 在 verify 阶段执行（本机无 docker 自动跳过，195 真跑）。
 */
class PostgresRcaTaskRepositoryIT extends PostgresITBase {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private RcaTaskRepository tasks;
    private RcaRunRepository runs;
    private IncidentRepository incidents;
    private SchedulerSlotRepository slots;
    private AlertEventRepository events;
    private AlertInboxRepository inbox;

    @BeforeEach
    void setUp() {
        JdbcClient jdbc = JdbcClient.create(controlDataSource());
        tasks = new PostgresRcaTaskRepository(jdbc);
        runs = new PostgresRcaRunRepository(jdbc);
        incidents = new PostgresIncidentRepository(jdbc);
        slots = new PostgresSchedulerSlotRepository(jdbc);
        events = new PostgresAlertEventRepository(jdbc, MAPPER);
        inbox = new PostgresAlertInboxRepository(jdbc, MAPPER);
        // TRUNCATE 清掉了迁移预置槽位——按 V7 同款补回（fixture restore，非业务写）
        adminJdbc.sql("INSERT INTO scheduler_slot(scope, slot_no) VALUES ('rca', 1), ('rca', 2)")
                .update();
    }

    // ------------------------------------------------------------------ CT-A02 并发 claim 互斥

    @Test
    void concurrentClaimOfSingleTaskIsMutuallyExclusive() throws Exception {
        UUID incident = insertIncident("claim-" + UUID.randomUUID());
        UUID run = insertRun(incident);
        UUID taskId = insertTask(run, "claim-task");

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            Future<Optional<RcaTask>> first = pool.submit(
                    () -> claimAfterLatch(start, "worker-a"));
            Future<Optional<RcaTask>> second = pool.submit(
                    () -> claimAfterLatch(start, "worker-b"));
            start.countDown();

            Optional<RcaTask> a = first.get(10, TimeUnit.SECONDS);
            Optional<RcaTask> b = second.get(10, TimeUnit.SECONDS);

            // 恰一个领到：SKIP LOCKED 语义下另一个看到空
            assertThat(a.isPresent() ^ b.isPresent()).as("两 worker 并发领同一 task 恰一个成功").isTrue();
            RcaTask claimed = a.orElseGet(() -> b.orElseThrow());
            assertThat(claimed.id()).isEqualTo(taskId);
            assertThat(claimed.state()).isEqualTo(RcaTaskState.LEASED);
        } finally {
            pool.shutdownNow();
        }
    }

    private Optional<RcaTask> claimAfterLatch(CountDownLatch start, String owner) {
        try {
            start.await(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
        return tasks.claimNext(owner, Instant.now(), Duration.ofMinutes(5));
    }

    // ------------------------------------------------------------------ CT-A03 唯一活跃 run 23505

    @Test
    void secondActiveRunForSameIncidentHitsUniqueIndex() {
        UUID incident = insertIncident("uq-" + UUID.randomUUID());
        insertRun(incident);

        try {
            insertRun(incident);
            throw new AssertionError("期望 DuplicateKeyException（23505）未发生——uq_rca_run_active_incident 未生效");
        } catch (DuplicateKeyException expected) {
            // uq_rca_run_active_incident 生效（INV-AM1-2）
        }
    }

    // ------------------------------------------------------------------ CT-A04/A05 slot 原子领取与过期回收

    @Test
    void slotAcquireIsAtomicAndExpiredLeaseIsReclaimed() throws Exception {
        // 只留一个空闲槽:tryAcquire 是"领任意空闲槽"语义,两个空闲槽会让并发双成功——
        // CT-A04 的互斥断言针对"并发抢同一个槽"(195 真跑首发现的两槽双成功)
        adminJdbc.sql("DELETE FROM scheduler_slot WHERE scope = 'rca' AND slot_no = 2").update();

        Instant now = Instant.now();
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            Future<Optional<SchedulerSlotRepository.AcquiredSlot>> a = pool.submit(
                    () -> acquireAfterLatch(start, "slot-a"));
            Future<Optional<SchedulerSlotRepository.AcquiredSlot>> b = pool.submit(
                    () -> acquireAfterLatch(start, "slot-b"));
            start.countDown();
            Optional<SchedulerSlotRepository.AcquiredSlot> first = a.get(10, TimeUnit.SECONDS);
            Optional<SchedulerSlotRepository.AcquiredSlot> second = b.get(10, TimeUnit.SECONDS);

            assertThat(first.isPresent() ^ second.isPresent()).as("并发领同槽恰一个成功").isTrue();
            SchedulerSlotRepository.AcquiredSlot winner =
                    first.orElseGet(() -> second.orElseThrow());
            assertThat(winner.leaseEpoch()).isEqualTo(1);

            // CT-A05：租约过期回收后可重领，epoch 递增（旧持有者被栅栏拒）
            adminJdbc.sql("UPDATE scheduler_slot SET lease_until = now() - interval '1 minute'")
                    .update();
            assertThat(slots.reclaimExpired(Instant.now())).isEqualTo(1);
            Optional<SchedulerSlotRepository.AcquiredSlot> reclaimed =
                    slots.tryAcquire("rca", "slot-c", null, Instant.now(), Duration.ofMinutes(5));
            assertThat(reclaimed).isPresent();
            assertThat(reclaimed.orElseThrow().leaseEpoch())
                    .as("重领后 epoch 递增拒旧提交").isGreaterThan(winner.leaseEpoch());
        } finally {
            pool.shutdownNow();
        }
    }

    private Optional<SchedulerSlotRepository.AcquiredSlot> acquireAfterLatch(CountDownLatch start,
                                                                            String owner) {
        try {
            start.await(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
        return slots.tryAcquire("rca", owner, null, Instant.now(), Duration.ofMinutes(5));
    }

    // ------------------------------------------------------------------ BA-12① 同 episode 乱序（投影器真栈）

    @Test
    void staleFiringAfterResolvedKeepsIncidentResolvedOnRealPg() {
        FixedClock clock = new FixedClock(Instant.parse("2026-09-03T10:00:00Z"));
        IncidentProjector projector = new IncidentProjector(events, incidents, runs, tasks,
                new AlertIdentityFactory(), new DeferredPolicy(1000), SlaPolicy.defaults(), clock);
        UUID inboxId = insertInboxRow();

        projector.project(inboxId, List.of(alert(AlertFiringStatus.FIRING, "2026-09-03T09:00:00Z")));
        clock.now = Instant.parse("2026-09-03T11:00:00Z");
        projector.project(inboxId, List.of(alert(AlertFiringStatus.RESOLVED, "2026-09-03T09:30:00Z")));
        clock.now = Instant.parse("2026-09-03T11:30:00Z");
        // 迟到 firing：episode 水印内（>= 09:00）但早于 resolve 处理时刻（11:00）
        projector.project(inboxId, List.of(alert(AlertFiringStatus.FIRING, "2026-09-03T10:30:00Z")));

        Incident incident = incidents.findByKeyForUpdate(
                "alertname=HighErrorRate|service=checkout").orElseThrow();
        assertThat(incident.status()).as("迟到 firing 不得复活 incident").isEqualTo(IncidentStatus.RESOLVED);
        assertThat(incident.generation()).isZero();
        assertThat(incident.receivedCount()).isEqualTo(3);
        assertThat(count("rca_run")).as("零新 run").isEqualTo(1);
        assertThat(count("alert_event")).as("event 仍追加").isEqualTo(3);
    }

    // ------------------------------------------------------------------ 种子（control 角色经仓储实体）

    private static final class FixedClock implements AlertClock {
        volatile Instant now;

        FixedClock(Instant start) {
            this.now = start;
        }

        @Override
        public Instant now() {
            return now;
        }
    }

    private ParsedAlert alert(AlertFiringStatus status, String startsAt) {
        return new ParsedAlert(status, "fp-it-1",
                Map.of("alertname", "HighErrorRate", "service", "checkout", "severity", "warning"),
                Map.of("summary", "it 材料"), Instant.parse(startsAt), null);
    }

    private UUID insertInboxRow() {
        UUID id = UUID.randomUUID();
        byte[] raw = "{\"groupKey\":\"it-episode\"}".getBytes();
        Instant now = Instant.now();
        inbox.insert(new AlertInbox(id, new AlertGroupEnvelope(
                "4", "control-app", "it-episode", Map.of("alertname", "HighErrorRate"),
                Map.of(), Map.of(), "http://am.local", AlertFiringStatus.FIRING, 0, 1,
                raw, Digest.sha256Of("it-episode")),
                InboxState.RECEIVED, null, null, null, 0, 0, 5, null, null,
                now, now, null));
        return id;
    }

    private UUID insertIncident(String key) {
        UUID id = UUID.randomUUID();
        Instant now = Instant.now();
        incidents.insert(new Incident(id, "alertname=HighErrorRate|service=" + key,
                IncidentStatus.FIRING, 0, now.minus(Duration.ofMinutes(5)), now.minus(Duration.ofMinutes(5)),
                null, null, null, 0, 0, 0, null,
                now.minus(Duration.ofMinutes(5)), now.minus(Duration.ofMinutes(5)), now, now));
        return id;
    }

    private UUID insertRun(UUID incidentId) {
        UUID id = UUID.randomUUID();
        Instant now = Instant.now();
        runs.insert(new RcaRun(id, incidentId, 0, RunTrigger.INITIAL, RcaRunState.QUEUED,
                Digest.sha256Of("run-" + id), now.minus(Duration.ofMinutes(4)), now, null, null, null));
        return id;
    }

    private UUID insertTask(UUID runId, String key) {
        UUID id = UUID.randomUUID();
        Instant now = Instant.now();
        tasks.insert(new RcaTask(id, runId, RcaTask.HOLMES_INVESTIGATE, RcaTaskState.READY,
                100, now.minus(Duration.ofMinutes(1)), now.minus(Duration.ofMinutes(3)),
                now.plus(Duration.ofMinutes(10)), null, null, 0, 0, 3,
                now.minus(Duration.ofMinutes(3)), now));
        return id;
    }
}
