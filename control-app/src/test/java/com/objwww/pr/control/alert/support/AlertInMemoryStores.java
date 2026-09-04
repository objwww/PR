package com.objwww.pr.control.alert.support;

import com.objwww.pr.control.alert.domain.model.AlertEvent;
import com.objwww.pr.control.alert.domain.model.AlertInbox;
import com.objwww.pr.control.alert.domain.model.ExternalInvocation;
import com.objwww.pr.control.alert.domain.model.InboxDecision;
import com.objwww.pr.control.alert.domain.model.InboxState;
import com.objwww.pr.control.alert.domain.model.Incident;
import com.objwww.pr.control.alert.domain.model.RcaAttempt;
import com.objwww.pr.control.alert.domain.model.RcaReport;
import com.objwww.pr.control.alert.domain.model.RcaRun;
import com.objwww.pr.control.alert.domain.model.RcaRunState;
import com.objwww.pr.control.alert.domain.model.RcaTask;
import com.objwww.pr.control.alert.domain.model.RcaTaskState;
import com.objwww.pr.control.alert.domain.repository.AlertEventRepository;
import com.objwww.pr.control.alert.domain.repository.AlertInboxRepository;
import com.objwww.pr.control.alert.domain.repository.ExternalInvocationRepository;
import com.objwww.pr.control.alert.domain.repository.IncidentRepository;
import com.objwww.pr.control.alert.domain.repository.RcaAttemptRepository;
import com.objwww.pr.control.alert.domain.repository.RcaReportRepository;
import com.objwww.pr.control.alert.domain.repository.RcaRunRepository;
import com.objwww.pr.control.alert.domain.repository.RcaTaskRepository;
import com.objwww.pr.control.alert.domain.repository.SchedulerSlotRepository;
import com.objwww.pr.control.alert.domain.service.SlaPolicy;
import com.objwww.pr.shared.Digest;
import org.springframework.dao.DuplicateKeyException;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * 告警域 InMemory fake（L3 场景闭环用；旧线 InMemoryStores 同型）。
 *
 * <p>约束模拟对齐 PG 语义（T03 验收）：
 * <ul>
 *   <li>uq_alert_event_dedup / incident_key 唯一 / uq_rca_run_active_incident（部分唯一）/
 *       uq(run_id, task_key) / uq(task_id, attempt_no) → DuplicateKeyException；</li>
 *   <li>所有带 epoch 的写方法 = 栅栏（不匹配 0 行返回 false）；</li>
 *   <li>slot 原子领取（满/租约未过期 = empty）；task 领取按 SlaPolicy.claimOrder（§6.2 排序镜像）。</li>
 * </ul>
 * 单线程语义（ST 场景）；并发行为由 CT-A*（195 真 PG）覆盖，此处不模拟行锁。
 */
public final class AlertInMemoryStores {

    public final Inbox inbox = new Inbox();
    public final Events events = new Events();
    public final Incidents incidents = new Incidents();
    public final Runs runs = new Runs();
    public final Tasks tasks = new Tasks();
    public final Attempts attempts = new Attempts();
    public final Reports reports = new Reports();
    public final Invocations invocations = new Invocations();
    public final Slots slots = new Slots(2);

    // ------------------------------------------------------------------ alert_inbox

    public static final class Inbox implements AlertInboxRepository {
        private final Map<UUID, AlertInbox> rows = new LinkedHashMap<>();

        @Override
        public synchronized void insert(AlertInbox row) {
            rows.put(row.id(), row);
        }

        @Override
        public synchronized Optional<AlertInbox> claimNext(String owner, Instant now, Duration lease) {
            Optional<AlertInbox> candidate = rows.values().stream()
                    .filter(r -> r.state() == InboxState.RECEIVED || r.state() == InboxState.RETRY_WAIT)
                    .filter(r -> r.nextRetryAt() == null || !r.nextRetryAt().isAfter(now))
                    .min(Comparator
                            .comparing((AlertInbox r) -> r.nextRetryAt() == null ? Instant.EPOCH : r.nextRetryAt())
                            .thenComparing(AlertInbox::receivedAt));
            if (candidate.isEmpty()) {
                return Optional.empty();
            }
            AlertInbox r = candidate.get();
            AlertInbox claimed = new AlertInbox(r.id(), r.envelope(), InboxState.PROCESSING, r.decision(),
                    owner, now.plus(lease), r.leaseEpoch() + 1,
                    r.attemptCount() + 1, r.maxAttempts(), r.nextRetryAt(), r.lastError(),
                    r.receivedAt(), now, r.processedAt());
            rows.put(r.id(), claimed);
            return Optional.of(claimed);
        }

        @Override
        public synchronized boolean complete(UUID id, long leaseEpoch, InboxDecision decision, Instant now) {
            AlertInbox r = rows.get(id);
            if (r == null || r.state() != InboxState.PROCESSING || r.leaseEpoch() != leaseEpoch) {
                return false;
            }
            rows.put(id, new AlertInbox(r.id(), r.envelope(), InboxState.PROCESSED, decision,
                    r.leaseOwner(), r.leaseUntil(), r.leaseEpoch(),
                    r.attemptCount(), r.maxAttempts(), r.nextRetryAt(), r.lastError(),
                    r.receivedAt(), now, now));
            return true;
        }

        @Override
        public synchronized boolean scheduleRetry(UUID id, long leaseEpoch, InboxDecision decision,
                                                  String lastError, Instant nextRetryAt, Instant now) {
            AlertInbox r = rows.get(id);
            if (r == null || r.state() != InboxState.PROCESSING || r.leaseEpoch() != leaseEpoch) {
                return false;
            }
            rows.put(id, new AlertInbox(r.id(), r.envelope(), InboxState.RETRY_WAIT, decision,
                    null, null, r.leaseEpoch(),
                    r.attemptCount() + 1, r.maxAttempts(), nextRetryAt, lastError,
                    r.receivedAt(), now, r.processedAt()));
            return true;
        }

        @Override
        public synchronized boolean markDeadLetter(UUID id, long leaseEpoch, String lastError, Instant now) {
            AlertInbox r = rows.get(id);
            if (r == null || r.state() != InboxState.PROCESSING || r.leaseEpoch() != leaseEpoch) {
                return false;
            }
            rows.put(id, new AlertInbox(r.id(), r.envelope(), InboxState.DEAD_LETTER, r.decision(),
                    null, null, r.leaseEpoch(),
                    r.attemptCount() + 1, r.maxAttempts(), r.nextRetryAt(), lastError,
                    r.receivedAt(), now, now));
            return true;
        }

        @Override
        public synchronized boolean markIgnored(UUID id, long leaseEpoch, Instant now) {
            AlertInbox r = rows.get(id);
            if (r == null || r.state() != InboxState.PROCESSING || r.leaseEpoch() != leaseEpoch) {
                return false;
            }
            rows.put(id, new AlertInbox(r.id(), r.envelope(), InboxState.IGNORED, r.decision(),
                    null, null, r.leaseEpoch(),
                    r.attemptCount(), r.maxAttempts(), r.nextRetryAt(), r.lastError(),
                    r.receivedAt(), now, now));
            return true;
        }

        @Override
        public synchronized long reclaimExpired(Instant now) {
            long n = 0;
            for (Map.Entry<UUID, AlertInbox> e : rows.entrySet()) {
                AlertInbox r = e.getValue();
                if (r.state() == InboxState.PROCESSING && r.leaseUntil() != null && r.leaseUntil().isBefore(now)) {
                    rows.put(e.getKey(), new AlertInbox(r.id(), r.envelope(), InboxState.RECEIVED, r.decision(),
                            null, null, r.leaseEpoch(),
                            r.attemptCount(), r.maxAttempts(), r.nextRetryAt(), r.lastError(),
                            r.receivedAt(), now, r.processedAt()));
                    n++;
                }
            }
            return n;
        }

        @Override
        public synchronized Optional<AlertInbox> findById(UUID id) {
            return Optional.ofNullable(rows.get(id));
        }

        public synchronized List<AlertInbox> all() {
            return List.copyOf(rows.values());
        }
    }

    // ------------------------------------------------------------------ alert_event

    public static final class Events implements AlertEventRepository {
        private final Map<UUID, AlertEvent> rows = new LinkedHashMap<>();

        @Override
        public synchronized void append(AlertEvent event) {
            boolean dup = rows.values().stream().anyMatch(r ->
                    r.fingerprint().equals(event.fingerprint())
                            && r.payloadHash().equals(event.payloadHash())
                            && r.startsAt().equals(event.startsAt()));
            if (dup) {
                throw new DuplicateKeyException("uq_alert_event_dedup 模拟");
            }
            rows.put(event.id(), event);
        }

        @Override
        public synchronized boolean existsByDedup(String fingerprint, Digest payloadHash, Instant startsAt) {
            return rows.values().stream().anyMatch(r ->
                    r.fingerprint().equals(fingerprint)
                            && r.payloadHash().equals(payloadHash)
                            && r.startsAt().equals(startsAt));
        }

        @Override
        public synchronized List<AlertEvent> findByIncidentId(UUID incidentId) {
            return rows.values().stream().filter(e -> e.incidentId().equals(incidentId)).toList();
        }

        public synchronized List<AlertEvent> all() {
            return List.copyOf(rows.values());
        }
    }

    // ------------------------------------------------------------------ incident

    public static final class Incidents implements IncidentRepository {
        private final Map<UUID, Incident> rows = new LinkedHashMap<>();

        @Override
        public synchronized Optional<Incident> findByKeyForUpdate(String incidentKey) {
            return rows.values().stream().filter(i -> i.incidentKey().equals(incidentKey)).findFirst();
        }

        @Override
        public synchronized Optional<Incident> findByIdForUpdate(UUID id) {
            return Optional.ofNullable(rows.get(id));
        }

        @Override
        public synchronized Optional<Incident> findById(UUID id) {
            return Optional.ofNullable(rows.get(id));
        }

        @Override
        public synchronized void insert(Incident incident) {
            boolean dup = rows.values().stream()
                    .anyMatch(i -> i.incidentKey().equals(incident.incidentKey()));
            if (dup) {
                throw new DuplicateKeyException("incident_key 唯一模拟");
            }
            rows.put(incident.id(), incident);
        }

        @Override
        public synchronized boolean update(Incident incident) {
            if (!rows.containsKey(incident.id())) {
                return false;
            }
            rows.put(incident.id(), incident);
            return true;
        }

        @Override
        public synchronized int countActive() {
            return (int) rows.values().stream().filter(i -> i.status().name().equals("FIRING")).count();
        }

        public synchronized List<Incident> all() {
            return List.copyOf(rows.values());
        }
    }

    // ------------------------------------------------------------------ rca_run

    public static final class Runs implements RcaRunRepository {
        private final Map<UUID, RcaRun> rows = new LinkedHashMap<>();

        @Override
        public synchronized void insert(RcaRun run) {
            // 部分唯一索引语义：谓词只覆盖活跃行——新行非活跃时不参与唯一性
            boolean activeDup = run.state().isActive() && rows.values().stream().anyMatch(r ->
                    r.incidentId().equals(run.incidentId()) && r.state().isActive());
            if (activeDup) {
                throw new DuplicateKeyException("uq_rca_run_active_incident 模拟(23505)");
            }
            rows.put(run.id(), run);
        }

        @Override
        public synchronized Optional<RcaRun> findByIdForUpdate(UUID id) {
            return Optional.ofNullable(rows.get(id));
        }

        @Override
        public synchronized boolean update(RcaRun run) {
            if (!rows.containsKey(run.id())) {
                return false;
            }
            rows.put(run.id(), run);
            return true;
        }

        @Override
        public synchronized Optional<RcaRun> findActiveByIncidentId(UUID incidentId) {
            return rows.values().stream()
                    .filter(r -> r.incidentId().equals(incidentId) && r.state().isActive())
                    .reduce((a, b) -> b);
        }

        public synchronized List<RcaRun> all() {
            return List.copyOf(rows.values());
        }
    }

    // ------------------------------------------------------------------ rca_task

    public static final class Tasks implements RcaTaskRepository {
        private final Map<UUID, RcaTask> rows = new LinkedHashMap<>();

        @Override
        public synchronized void insert(RcaTask task) {
            boolean dup = rows.values().stream().anyMatch(t ->
                    t.runId().equals(task.runId()) && t.taskKey().equals(task.taskKey()));
            if (dup) {
                throw new DuplicateKeyException("uq_rca_task_key 模拟");
            }
            rows.put(task.id(), task);
        }

        @Override
        public synchronized Optional<RcaTask> claimNext(String owner, Instant now, Duration lease) {
            Optional<RcaTask> candidate = rows.values().stream()
                    .filter(t -> t.state() == RcaTaskState.READY || t.state() == RcaTaskState.RETRY_WAIT)
                    .filter(t -> !t.availableAt().isAfter(now))
                    .min(SlaPolicy.claimOrder(now));
            if (candidate.isEmpty()) {
                return Optional.empty();
            }
            RcaTask t = candidate.get();
            RcaTask claimed = new RcaTask(t.id(), t.runId(), t.taskKey(), RcaTaskState.LEASED,
                    t.priority(), t.availableAt(), t.readySince(), t.deadlineAt(),
                    owner, now.plus(lease), t.leaseEpoch() + 1,
                    t.attemptCount() + 1, t.maxAttempts(), t.createdAt(), now);
            rows.put(t.id(), claimed);
            return Optional.of(claimed);
        }

        @Override
        public synchronized boolean requireCurrentLease(UUID id, String owner, long leaseEpoch) {
            RcaTask t = rows.get(id);
            return t != null && t.state() == RcaTaskState.LEASED
                    && owner.equals(t.leaseOwner()) && t.leaseEpoch() == leaseEpoch;
        }

        @Override
        public synchronized boolean update(RcaTask task) {
            if (!rows.containsKey(task.id())) {
                return false;
            }
            rows.put(task.id(), task);
            return true;
        }

        @Override
        public synchronized void heartbeat(UUID id, String owner, long leaseEpoch, Instant now, Duration extend) {
            RcaTask t = rows.get(id);
            if (t != null && t.state() == RcaTaskState.LEASED
                    && owner.equals(t.leaseOwner()) && t.leaseEpoch() == leaseEpoch) {
                rows.put(id, new RcaTask(t.id(), t.runId(), t.taskKey(), t.state(),
                        t.priority(), t.availableAt(), t.readySince(), t.deadlineAt(),
                        t.leaseOwner(), now.plus(extend), t.leaseEpoch(),
                        t.attemptCount(), t.maxAttempts(), t.createdAt(), now));
            }
        }

        @Override
        public synchronized List<RcaTask> findExpiredLeased(Instant now) {
            return rows.values().stream()
                    .filter(t -> t.state() == RcaTaskState.LEASED
                            && t.leaseUntil() != null && t.leaseUntil().isBefore(now))
                    .toList();
        }

        @Override
        public synchronized Optional<RcaTask> findById(UUID id) {
            return Optional.ofNullable(rows.get(id));
        }

        @Override
        public synchronized int countQueued() {
            return (int) rows.values().stream()
                    .filter(t -> t.state() == RcaTaskState.READY || t.state() == RcaTaskState.RETRY_WAIT)
                    .count();
        }

        public synchronized List<RcaTask> all() {
            return List.copyOf(rows.values());
        }
    }

    // ------------------------------------------------------------------ rca_attempt

    public static final class Attempts implements RcaAttemptRepository {
        private final Map<UUID, RcaAttempt> rows = new LinkedHashMap<>();

        @Override
        public synchronized void insert(RcaAttempt attempt) {
            boolean dup = rows.values().stream().anyMatch(a ->
                    a.taskId().equals(attempt.taskId()) && a.attemptNo() == attempt.attemptNo());
            if (dup) {
                throw new DuplicateKeyException("uq_rca_attempt 模拟");
            }
            rows.put(attempt.id(), attempt);
        }

        @Override
        public synchronized boolean update(RcaAttempt attempt) {
            if (!rows.containsKey(attempt.id())) {
                return false;
            }
            rows.put(attempt.id(), attempt);
            return true;
        }

        @Override
        public synchronized List<RcaAttempt> findByTaskId(UUID taskId) {
            return rows.values().stream().filter(a -> a.taskId().equals(taskId)).toList();
        }

        public synchronized List<RcaAttempt> all() {
            return List.copyOf(rows.values());
        }
    }

    // ------------------------------------------------------------------ rca_report

    public static final class Reports implements RcaReportRepository {
        private final Map<UUID, RcaReport> rows = new LinkedHashMap<>();

        @Override
        public synchronized void insert(RcaReport report) {
            rows.put(report.id(), report);
        }

        @Override
        public synchronized List<RcaReport> findByRunId(UUID runId) {
            return rows.values().stream().filter(r -> r.runId().equals(runId)).toList();
        }

        public synchronized List<RcaReport> all() {
            return List.copyOf(rows.values());
        }
    }

    // ------------------------------------------------------------------ external_invocation_ledger

    public static final class Invocations implements ExternalInvocationRepository {
        private final Map<UUID, ExternalInvocation> rows = new LinkedHashMap<>();

        @Override
        public synchronized void insertStarted(ExternalInvocation invocation) {
            if (invocation.state() != com.objwww.pr.control.alert.domain.model.ExternalInvocationState.STARTED) {
                throw new IllegalArgumentException("insertStarted 只接受 STARTED");
            }
            rows.put(invocation.id(), invocation);
        }

        @Override
        public synchronized boolean finish(ExternalInvocation invocation) {
            ExternalInvocation cur = rows.get(invocation.id());
            if (cur == null || cur.state() != com.objwww.pr.control.alert.domain.model.ExternalInvocationState.STARTED) {
                return false;
            }
            rows.put(invocation.id(), invocation);
            return true;
        }

        @Override
        public synchronized List<ExternalInvocation> findHangingStarted(Instant olderThan) {
            return rows.values().stream()
                    .filter(v -> v.state() == com.objwww.pr.control.alert.domain.model.ExternalInvocationState.STARTED
                            && v.startedAt().isBefore(olderThan))
                    .toList();
        }

        @Override
        public synchronized List<ExternalInvocation> findByRunId(UUID runId) {
            return rows.values().stream().filter(v -> v.runId().equals(runId)).toList();
        }

        public synchronized List<ExternalInvocation> all() {
            return List.copyOf(rows.values());
        }
    }

    // ------------------------------------------------------------------ scheduler_slot

    /** 固定槽位（默认 2，与 V7 预置一致；测试可自定义） */
    public static final class Slots implements SchedulerSlotRepository {
        private static final class Slot {
            String owner;
            Instant until;
            long epoch;
            UUID taskId;
        }

        private final Map<Integer, Slot> slots = new HashMap<>();

        public Slots(int total) {
            for (int i = 1; i <= total; i++) {
                slots.put(i, new Slot());
            }
        }

        @Override
        public synchronized Optional<AcquiredSlot> tryAcquire(String scope, String owner, UUID taskId,
                                                              Instant now, Duration lease) {
            for (Map.Entry<Integer, Slot> e : slots.entrySet()) {
                Slot s = e.getValue();
                if (s.until == null || !s.until.isAfter(now)) {
                    s.owner = owner;
                    s.until = now.plus(lease);
                    s.epoch = s.epoch + 1;
                    s.taskId = taskId;
                    return Optional.of(new AcquiredSlot(e.getKey(), s.epoch));
                }
            }
            return Optional.empty();
        }

        @Override
        public synchronized boolean release(String scope, int slotNo, String owner, long leaseEpoch) {
            Slot s = slots.get(slotNo);
            if (s == null || !owner.equals(s.owner) || s.epoch != leaseEpoch) {
                return false;
            }
            s.owner = null;
            s.until = null;
            s.taskId = null;
            return true;
        }

        @Override
        public synchronized void heartbeat(String scope, int slotNo, String owner, long leaseEpoch,
                                            Instant now, Duration extend) {
            Slot s = slots.get(slotNo);
            if (s != null && owner.equals(s.owner) && s.epoch == leaseEpoch) {
                s.until = now.plus(extend);
            }
        }

        @Override
        public synchronized long reclaimExpired(Instant now) {
            long n = 0;
            for (Slot s : slots.values()) {
                if (s.owner != null && s.until != null && s.until.isBefore(now)) {
                    s.owner = null;
                    s.until = null;
                    s.taskId = null;
                    n++;
                }
            }
            return n;
        }

        @Override
        public synchronized List<Integer> occupiedSlots(String scope) {
            List<Integer> out = new ArrayList<>();
            for (Map.Entry<Integer, Slot> e : slots.entrySet()) {
                if (e.getValue().owner != null) {
                    out.add(e.getKey());
                }
            }
            return out.stream().sorted().toList();
        }

        @Override
        public synchronized int totalSlots(String scope) {
            return slots.size();
        }
    }
}
