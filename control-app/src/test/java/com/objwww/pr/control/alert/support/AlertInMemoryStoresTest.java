package com.objwww.pr.control.alert.support;

import com.objwww.pr.control.alert.domain.model.AlertEvent;
import com.objwww.pr.control.alert.domain.model.AlertFiringStatus;
import com.objwww.pr.control.alert.domain.model.InboxState;
import com.objwww.pr.control.alert.domain.model.Incident;
import com.objwww.pr.control.alert.domain.model.IncidentStatus;
import com.objwww.pr.control.alert.domain.model.RcaRun;
import com.objwww.pr.control.alert.domain.model.RcaRunState;
import com.objwww.pr.control.alert.domain.model.RcaTask;
import com.objwww.pr.control.alert.domain.model.RcaTaskState;
import com.objwww.pr.control.alert.domain.model.RunTrigger;
import com.objwww.pr.shared.Digest;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DuplicateKeyException;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * T03 本地验收：InMemory fake 的约束模拟与 PG 语义对齐
 * （唯一约束 → DuplicateKeyException / epoch 栅栏 0 行 / slot 原子领取 / SLA 领取排序）。
 * 并发行为（SKIP LOCKED 互斥等）由 CT-A* 在 195 真 PG 覆盖。
 */
class AlertInMemoryStoresTest {

    private static final Instant NOW = Instant.parse("2026-09-03T10:00:00Z");

    // ------------------------------------------------------------------ 事件去重

    @Test
    void eventDedupThrowsDuplicateKey() {
        AlertInMemoryStores store = new AlertInMemoryStores();
        UUID incident = UUID.randomUUID();
        Digest payload = Digest.sha256Of("p");
        Digest investigation = Digest.sha256Of("i");

        store.events.append(event(incident, "fp-1", payload, investigation, NOW));
        // 同 (fingerprint, payloadHash, startsAt) 二次追加 → uq_alert_event_dedup
        assertThatThrownBy(() -> store.events.append(
                event(incident, "fp-1", payload, investigation, NOW)))
                .isInstanceOf(DuplicateKeyException.class);
        // payload 变化（新一条）可追加
        store.events.append(event(incident, "fp-1", Digest.sha256Of("p2"), investigation, NOW));
        assertThat(store.events.all()).hasSize(2);
    }

    // ------------------------------------------------------------------ incident 唯一 + run 部分唯一

    @Test
    void incidentKeyUniqueAndRunPartialUnique() {
        AlertInMemoryStores store = new AlertInMemoryStores();
        Incident incident = incident(UUID.randomUUID(), "key-1");
        store.incidents.insert(incident);

        assertThatThrownBy(() -> store.incidents.insert(
                incident(UUID.randomUUID(), "key-1")))
                .isInstanceOf(DuplicateKeyException.class);

        // 活跃 run 部分唯一（23505 模拟）
        store.runs.insert(run(incident.id(), RcaRunState.QUEUED));
        assertThatThrownBy(() -> store.runs.insert(
                run(incident.id(), RcaRunState.RUNNING)))
                .isInstanceOf(DuplicateKeyException.class);

        // 终态后可再铸新 run
        RcaRun done = run(incident.id(), RcaRunState.SUCCEEDED);
        store.runs.insert(done);
        RcaRun active = store.runs.all().get(0);
        store.runs.update(new RcaRun(active.id(), active.incidentId(), active.generation(),
                active.trigger(), RcaRunState.SUCCEEDED, active.investigationHash(),
                active.createdAt(), NOW, active.startedAt(), NOW, null));
        store.runs.insert(run(incident.id(), RcaRunState.QUEUED));
        assertThat(store.runs.all()).hasSize(3);
    }

    // ------------------------------------------------------------------ task 领取排序 + epoch 栅栏

    @Test
    void taskClaimFollowsSlaOrderAndFenceBlocksStaleEpoch() {
        AlertInMemoryStores store = new AlertInMemoryStores();
        UUID runId = UUID.randomUUID();

        // critical（永不到期）与 warning（10min SLA，已过 30min → 到期越级）
        RcaTask critical = task(runId, "t-critical", 200, Instant.MAX, NOW.minusSeconds(20));
        RcaTask warningOverdue = task(runId, "t-warning", 100, NOW.minus(Duration.ofMinutes(20)), NOW);
        store.tasks.insert(critical);
        store.tasks.insert(warningOverdue);

        var claimed = store.tasks.claimNext("w1", NOW, Duration.ofMinutes(2)).orElseThrow();
        assertThat(claimed.id()).isEqualTo(warningOverdue.id());      // 到期越级在 §6.2 排序下先领
        assertThat(claimed.state()).isEqualTo(RcaTaskState.LEASED);
        assertThat(claimed.leaseEpoch()).isEqualTo(1);
        assertThat(claimed.attemptCount()).isEqualTo(1);

        // 旧 epoch（0）栅栏 0 行；正确 epoch（1）通过
        assertThat(store.tasks.requireCurrentLease(claimed.id(), "w1", 0)).isFalse();
        assertThat(store.tasks.requireCurrentLease(claimed.id(), "w1", 1)).isTrue();

        // 第二次领取拿到 critical
        var second = store.tasks.claimNext("w2", NOW, Duration.ofMinutes(2)).orElseThrow();
        assertThat(second.id()).isEqualTo(critical.id());
        // 两任务都 LEASED 后无单可领
        assertThat(store.tasks.claimNext("w3", NOW, Duration.ofMinutes(2))).isEmpty();
    }

    // ------------------------------------------------------------------ slot 原子领取 + 回收

    @Test
    void slotAcquireIsAtomicAndExpiredLeaseReclaimed() {
        AlertInMemoryStores store = new AlertInMemoryStores();   // 默认 2 槽
        UUID t1 = UUID.randomUUID();
        UUID t2 = UUID.randomUUID();
        UUID t3 = UUID.randomUUID();

        var s1 = store.slots.tryAcquire("rca", "w1", t1, NOW, Duration.ofMinutes(1)).orElseThrow();
        var s2 = store.slots.tryAcquire("rca", "w2", t2, NOW, Duration.ofMinutes(1)).orElseThrow();
        assertThat(s1.slotNo()).isNotEqualTo(s2.slotNo());
        assertThat(s1.leaseEpoch()).isEqualTo(1);
        assertThat(store.slots.occupiedSlots("rca")).hasSize(2);

        // 满槽：领取失败
        assertThat(store.slots.tryAcquire("rca", "w3", t3, NOW, Duration.ofMinutes(1))).isEmpty();

        // 租约过期后回收 → w3 可占
        Instant later = NOW.plus(Duration.ofMinutes(2));
        assertThat(store.slots.reclaimExpired(later)).isEqualTo(2);
        assertThat(store.slots.tryAcquire("rca", "w3", t3, later, Duration.ofMinutes(1))).isPresent();
    }

    // ------------------------------------------------------------------ inbox 六态流转

    @Test
    void inboxClaimCompleteWithEpochFence() {
        AlertInMemoryStores store = new AlertInMemoryStores();
        var inboxRow = com.objwww.pr.control.alert.support.TestFixtures.inboxRow(
                UUID.randomUUID(), "g-1", InboxState.RECEIVED);
        store.inbox.insert(inboxRow);

        var claimed = store.inbox.claimNext("p1", NOW, Duration.ofMinutes(1)).orElseThrow();
        assertThat(claimed.state()).isEqualTo(InboxState.PROCESSING);
        assertThat(claimed.leaseEpoch()).isEqualTo(1);

        // 旧 epoch 完成 = 0 行
        assertThat(store.inbox.complete(claimed.id(), 0,
                com.objwww.pr.control.alert.domain.model.InboxDecision.ACCEPTED, NOW)).isFalse();
        assertThat(store.inbox.complete(claimed.id(), 1,
                com.objwww.pr.control.alert.domain.model.InboxDecision.ACCEPTED, NOW)).isTrue();
        assertThat(store.inbox.findById(claimed.id()).orElseThrow().state())
                .isEqualTo(InboxState.PROCESSED);
    }

    // ------------------------------------------------------------------ 工厂

    private static AlertEvent event(UUID incidentId, String fingerprint, Digest payloadHash,
                                    Digest investigationHash, Instant startsAt) {
        return new AlertEvent(UUID.randomUUID(), UUID.randomUUID(), incidentId, 0,
                fingerprint, AlertFiringStatus.FIRING,
                java.util.Map.of("alertname", "A"), java.util.Map.of(),
                startsAt, null, payloadHash, investigationHash, NOW);
    }

    private static Incident incident(UUID id, String key) {
        return new Incident(id, key, IncidentStatus.FIRING, 0, NOW, NOW, null,
                null, null, 0, 0, 0, null, NOW, NOW, NOW, NOW);
    }

    private static RcaRun run(UUID incidentId, RcaRunState state) {
        return new RcaRun(UUID.randomUUID(), incidentId, 0, RunTrigger.INITIAL, state,
                Digest.sha256Of("material"), NOW, NOW, null, null, null);
    }

    private static RcaTask task(UUID runId, String key, int priority, Instant deadline, Instant createdAt) {
        return new RcaTask(UUID.randomUUID(), runId, key, RcaTaskState.READY,
                priority, Instant.EPOCH, Instant.EPOCH, deadline,
                null, null, 0, 0, 3, createdAt, createdAt);
    }
}
