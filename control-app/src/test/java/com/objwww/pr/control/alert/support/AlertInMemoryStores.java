package com.objwww.pr.control.alert.support;

import com.objwww.pr.control.alert.domain.model.AlertEvent;
import com.objwww.pr.control.alert.domain.model.AlertInbox;
import com.objwww.pr.control.alert.domain.model.ExternalInvocation;
import com.objwww.pr.control.alert.domain.model.InboxDecision;
import com.objwww.pr.control.alert.domain.model.InboxState;
import com.objwww.pr.control.alert.domain.model.Incident;
import com.objwww.pr.control.alert.domain.model.OperatorCommand;
import com.objwww.pr.control.alert.domain.model.RcaAttempt;
import com.objwww.pr.control.alert.domain.model.RcaReport;
import com.objwww.pr.control.alert.domain.model.RcaRun;
import com.objwww.pr.control.alert.domain.model.RcaRunState;
import com.objwww.pr.control.alert.domain.model.RcaTask;
import com.objwww.pr.control.alert.domain.model.RcaTaskState;
import com.objwww.pr.control.alert.domain.event.RcaEventAppender;
import com.objwww.pr.control.alert.domain.repository.AlertEventRepository;
import com.objwww.pr.control.alert.domain.repository.AlertInboxRepository;
import com.objwww.pr.control.alert.domain.repository.ExternalInvocationRepository;
import com.objwww.pr.control.alert.domain.repository.IncidentRepository;
import com.objwww.pr.control.alert.domain.repository.OperatorCommandRepository;
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
    public final Investigations investigations = new Investigations();
    public final ToolCalls toolCalls = new ToolCalls();
    public final Publications publications = new Publications();
    public final Outboxes outboxes = new Outboxes();
    public final Commands commands = new Commands();
    public final RcaEventLog rcaEvents = new RcaEventLog();
    public final Cas cas = new Cas();
    public final Fallbacks fallbacks = new Fallbacks();
    public final Winners winners = new Winners();
    public final ShadowWorks shadowWorks = new ShadowWorks();
    /** EX-A4a（F16）：第一方工具账本假件（PENDING 悬挂回收面） */
    public final ToolLedger toolLedger = new ToolLedger();
    /** EX-A4a（F05）：证据快照假件（黑板=冻结成员面） */
    public final Snapshots snapshots = new Snapshots();

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
        public synchronized boolean insert(Incident incident) {
            // EX-A4b（F19）：对齐 PG ON CONFLICT DO NOTHING——冲突返回 false，不抛异常
            boolean dup = rows.values().stream()
                    .anyMatch(i -> i.incidentKey().equals(incident.incidentKey()));
            if (dup) {
                return false;
            }
            rows.put(incident.id(), incident);
            return true;
        }

        @Override
        public synchronized List<Incident> findWaitingForRedrive() {
            return rows.values().stream()
                    .filter(i -> "FIRING".equals(i.status().name()) && i.waitingReason() != null)
                    .toList();
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
        /** 路由四列读视图（insertRouted 录入；普通 insert 按 DB 默认 = HOLMES/null/null/null） */
        private final Map<UUID, com.objwww.pr.control.alert.domain.repository.RcaRunRepository.RoutingView>
                routings = new LinkedHashMap<>();
        /** EX-A2（F12）：修订锚镜像 last_event_seq——仅 updateIfRevision 推进（同 PG update 不推进） */
        private final Map<UUID, Long> runRevisions = new LinkedHashMap<>();

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
        public synchronized void insertRouted(RcaRun run,
                com.objwww.pr.control.alert.domain.model.RcaRunRouting routing) {
            insert(run);
            routings.put(run.id(), view(routing, null, null, null));
        }

        /** EX-A0：铸点身份三列随行录入（F14 冻结面；fake 与 Postgres 读写语义对齐） */
        @Override
        public synchronized void insertRouted(RcaRun run,
                com.objwww.pr.control.alert.domain.model.RcaRunRouting routing,
                com.objwww.pr.control.alert.domain.identity.InvestigationInputs inputs) {
            insert(run);
            routings.put(run.id(), view(routing, inputs.inputDigest().hex(),
                    inputs.windowStart(), inputs.windowEnd()));
        }

        private static com.objwww.pr.control.alert.domain.repository.RcaRunRepository.RoutingView
        view(com.objwww.pr.control.alert.domain.model.RcaRunRouting routing,
                String investigationInputDigest, java.time.Instant windowStart,
                java.time.Instant windowEnd) {
            return new com.objwww.pr.control.alert.domain.repository.RcaRunRepository.RoutingView(
                    routing.engine(),
                    routing.configDigest() == null ? null : routing.configDigest().hex(),
                    routing.stickinessKey(), routing.bucket(),
                    investigationInputDigest, windowStart, windowEnd);
        }

        @Override
        public synchronized Optional<RcaRun> findByIdForUpdate(UUID id) {
            return Optional.ofNullable(rows.get(id));
        }

        @Override
        public synchronized Optional<RcaRun> findById(UUID id) {
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

        /** EX-A2（F12）：与 Postgres CAS 同语义——修订+活跃态同 WHERE，胜出修订 +1 */
        @Override
        public synchronized boolean updateIfRevision(RcaRun run, long expectedRevision) {
            if (!rows.containsKey(run.id())
                    || runRevisions.getOrDefault(run.id(), 0L) != expectedRevision
                    || !rows.get(run.id()).state().isActive()) {
                return false;
            }
            rows.put(run.id(), run);
            runRevisions.put(run.id(), expectedRevision + 1);
            return true;
        }

        @Override
        public synchronized Optional<RcaRun> findActiveByIncidentId(UUID incidentId) {
            return rows.values().stream()
                    .filter(r -> r.incidentId().equals(incidentId) && r.state().isActive())
                    .reduce((a, b) -> b);
        }

        @Override
        public synchronized List<RcaRun> findAll() {
            return rows.values().stream()
                    .sorted(Comparator.comparing(RcaRun::createdAt).reversed()
                            .thenComparing(RcaRun::id))
                    .toList();
        }

        @Override
        public synchronized Optional<com.objwww.pr.control.alert.domain.repository.RcaRunRepository.RoutingView>
        findRoutingById(UUID id) {
            if (!rows.containsKey(id)) {
                return Optional.empty();
            }
            // 普通 insert 存量行按 DB 列默认投影（HOLMES/null/null/null，V25）
            return Optional.of(routings.getOrDefault(id,
                    new com.objwww.pr.control.alert.domain.repository.RcaRunRepository.RoutingView(
                            com.objwww.pr.control.alert.domain.model.RcaEngine.HOLMES,
                            null, null, null, null, null, null)));
        }

        @Override
        public synchronized java.util.OptionalLong currentRevision(UUID id) {
            // EX-A2：修订镜像计数器（仅 updateIfRevision 推进，镜像 PG last_event_seq 语义）
            return rows.containsKey(id)
                    ? java.util.OptionalLong.of(runRevisions.getOrDefault(id, 0L))
                    : java.util.OptionalLong.empty();
        }

        /** C-61 判定源镜像：routings 缺记录 = 普通 insert 存量行 = DB 默认 HOLMES */
        @Override
        public synchronized boolean existsNativeRunByIncidentId(UUID incidentId) {
            return rows.values().stream().anyMatch(r -> r.incidentId().equals(incidentId)
                    && routings.getOrDefault(r.id(),
                            new com.objwww.pr.control.alert.domain.repository.RcaRunRepository.RoutingView(
                                    com.objwww.pr.control.alert.domain.model.RcaEngine.HOLMES,
                                    null, null, null, null, null, null)).engine()
                            == com.objwww.pr.control.alert.domain.model.RcaEngine.NATIVE);
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
                    // C-70（M6-01）与 Postgres CLAIM_SQL 同语义：通用领取只认 driver task_key
                    .filter(t -> t.taskKey().equals(RcaTask.HOLMES_INVESTIGATE)
                            || t.taskKey().equals(RcaTask.NATIVE_INVESTIGATE))
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

        /** EX-A2（F10）：与 Postgres 四条件回收同语义（含 lease_until<now 复核） */
        @Override
        public synchronized boolean reclaimExpired(UUID id, long expectedEpoch, Instant now,
                                                   RcaTaskState target, Instant readyAt) {
            RcaTask t = rows.get(id);
            if (t == null || t.state() != RcaTaskState.LEASED
                    || t.leaseUntil() == null || !t.leaseUntil().isBefore(now)
                    || t.leaseEpoch() != expectedEpoch) {
                return false;
            }
            rows.put(id, new RcaTask(t.id(), t.runId(), t.taskKey(), target,
                    t.priority(), readyAt, readyAt, t.deadlineAt(),
                    null, null, t.leaseEpoch(),
                    t.attemptCount(), t.maxAttempts(), t.createdAt(), now));
            return true;
        }

        @Override
        public synchronized Optional<RcaTask> findById(UUID id) {
            return Optional.ofNullable(rows.get(id));
        }

        @Override
        public synchronized List<RcaTask> findByRunId(UUID runId) {
            return rows.values().stream()
                    .filter(t -> t.runId().equals(runId))
                    .sorted(Comparator.comparing(RcaTask::id))
                    .toList();
        }

        @Override
        public synchronized boolean transitionState(UUID id, RcaTaskState from, RcaTaskState to) {
            RcaTask t = rows.get(id);
            if (t == null || t.state() != from) {
                return false;
            }
            rows.put(id, new RcaTask(t.id(), t.runId(), t.taskKey(), to,
                    t.priority(), t.availableAt(), t.readySince(), t.deadlineAt(),
                    t.leaseOwner(), t.leaseUntil(), t.leaseEpoch(),
                    t.attemptCount(), t.maxAttempts(), t.createdAt(), t.updatedAt()));
            return true;
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

    // ------------------------------------------------------------------ rca_investigation_result（M3-04）

    /**
     * attempt_id 幂等锚 + STARTED→终态 CAS 模拟。generation 栅栏（SQL 内 EXISTS 判定）
     * 不在此模拟——栅栏语义由 PostgresIT 对真 PG 验证（M3-07/30）。
     */
    public static final class Investigations implements com.objwww.pr.control.alert.domain.repository.InvestigationResultRepository {
        private final Map<UUID, com.objwww.pr.control.alert.domain.model.InvestigationResult> rows =
                new LinkedHashMap<>();

        @Override
        public synchronized com.objwww.pr.control.alert.domain.model.InvestigationResult insertStartedIfAbsent(
                com.objwww.pr.control.alert.domain.model.InvestigationResult started) {
            com.objwww.pr.control.alert.domain.model.InvestigationResult existing = findByAttemptId(started.attemptId()).orElse(null);
            if (existing != null) {
                return existing;
            }
            rows.put(started.id(), started);
            return started;
        }

        @Override
        public synchronized boolean finishTerminal(
                com.objwww.pr.control.alert.domain.model.InvestigationResult terminal) {
            com.objwww.pr.control.alert.domain.model.InvestigationResult cur = rows.get(terminal.id());
            if (cur == null
                    || cur.executionStatus() != com.objwww.pr.control.alert.domain.model.ExecutionStatus.STARTED) {
                return false;
            }
            rows.put(terminal.id(), terminal);
            return true;
        }

        @Override
        public synchronized List<com.objwww.pr.control.alert.domain.model.InvestigationResult> findHangingStarted(
                Instant olderThan) {
            return rows.values().stream()
                    .filter(r -> r.executionStatus() == com.objwww.pr.control.alert.domain.model.ExecutionStatus.STARTED
                            && r.createdAt().isBefore(olderThan))
                    .toList();
        }

        @Override
        public synchronized Optional<com.objwww.pr.control.alert.domain.model.InvestigationResult> findByAttemptId(
                UUID attemptId) {
            return rows.values().stream()
                    .filter(r -> r.attemptId().equals(attemptId)).findFirst();
        }

        @Override
        public synchronized List<com.objwww.pr.control.alert.domain.model.InvestigationResult> findByRunId(UUID runId) {
            return rows.values().stream().filter(r -> r.runId().equals(runId)).toList();
        }

        public synchronized List<com.objwww.pr.control.alert.domain.model.InvestigationResult> all() {
            return List.copyOf(rows.values());
        }
    }

    // ------------------------------------------------------------------ rca_tool_call（M3-07）

    /** PK (investigation_result_id, tool_call_id) 去重；栅栏语义由 Postgres IT 覆盖 */
    public static final class ToolCalls implements com.objwww.pr.control.alert.domain.repository.RcaToolCallRepository {
        private final List<com.objwww.pr.control.alert.domain.model.RcaToolCall> rows = new ArrayList<>();

        @Override
        public synchronized int insertAll(List<com.objwww.pr.control.alert.domain.model.RcaToolCall> toolCalls) {
            int written = 0;
            for (com.objwww.pr.control.alert.domain.model.RcaToolCall call : toolCalls) {
                boolean dup = rows.stream().anyMatch(r ->
                        r.investigationResultId().equals(call.investigationResultId())
                                && r.toolCallId().equals(call.toolCallId()));
                if (!dup) {
                    rows.add(call);
                    written++;
                }
            }
            return written;
        }

        @Override
        public synchronized List<com.objwww.pr.control.alert.domain.model.RcaToolCall> findByResultId(
                UUID investigationResultId) {
            return rows.stream().filter(r -> r.investigationResultId().equals(investigationResultId)).toList();
        }

        @Override
        public synchronized List<com.objwww.pr.control.alert.domain.model.RcaToolCall> findByRunId(UUID runId) {
            return rows.stream().filter(r -> r.runId().equals(runId)).toList();
        }

        public synchronized List<com.objwww.pr.control.alert.domain.model.RcaToolCall> all() {
            return List.copyOf(rows);
        }
    }

    // ------------------------------------------------------------------ report_publication（M3-09）

    public static final class Publications implements com.objwww.pr.control.alert.domain.repository.ReportPublicationRepository {
        private final Map<UUID, com.objwww.pr.control.alert.domain.model.ReportPublication> rows =
                new LinkedHashMap<>();

        @Override
        public synchronized void insert(com.objwww.pr.control.alert.domain.model.ReportPublication publication) {
            boolean dup = rows.values().stream()
                    .anyMatch(p -> p.reportId().equals(publication.reportId()));
            if (dup) {
                throw new DuplicateKeyException("uq_report_publication_report 模拟");
            }
            rows.put(publication.id(), publication);
        }

        @Override
        public synchronized Optional<com.objwww.pr.control.alert.domain.model.ReportPublication> findByReportId(
                UUID reportId) {
            return rows.values().stream()
                    .filter(p -> p.reportId().equals(reportId)).findFirst();
        }

        public synchronized List<com.objwww.pr.control.alert.domain.model.ReportPublication> all() {
            return List.copyOf(rows.values());
        }
    }

    // ------------------------------------------------------------------ operator_command（M5-14）

    /** uq (run_id, command_type, idempotency_key) 模拟；updateState 只认 PERSISTED 行 */
    public static final class Commands implements OperatorCommandRepository {
        private final Map<UUID, OperatorCommand> rows = new LinkedHashMap<>();

        @Override
        public synchronized void insert(OperatorCommand command) {
            boolean dup = rows.values().stream().anyMatch(r ->
                    r.runId().equals(command.runId()) && r.type() == command.type()
                            && r.idempotencyKey().equals(command.idempotencyKey()));
            if (dup) {
                throw new DuplicateKeyException("uq_operator_command_idem 模拟");
            }
            rows.put(command.id(), command);
        }

        @Override
        public synchronized Optional<OperatorCommand> find(UUID runId, OperatorCommand.Type type,
                                                           String idempotencyKey) {
            return rows.values().stream()
                    .filter(r -> r.runId().equals(runId) && r.type() == type
                            && r.idempotencyKey().equals(idempotencyKey))
                    .findFirst();
        }

        @Override
        public synchronized boolean updateState(UUID id, OperatorCommand.State state,
                                                Instant appliedAt) {
            OperatorCommand row = rows.get(id);
            if (row == null || row.state().isTerminal()) {
                return false;
            }
            rows.put(id, row.withState(state, appliedAt));
            return true;
        }

        public synchronized List<OperatorCommand> all() {
            return List.copyOf(rows.values());
        }
    }

    // ------------------------------------------------------------------ rca_event 追加面（M5-14 命令生效事件）

    public record AppendedEvent(UUID runId, String eventType, String payloadJson) {
    }

    /** seq 简化为追加序（命令面 UT 只关心"是否追加/追加了什么"） */
    public static final class RcaEventLog implements RcaEventAppender {
        private final List<AppendedEvent> events = new ArrayList<>();

        @Override
        public synchronized long append(UUID runId, EventDraft draft) {
            events.add(new AppendedEvent(runId, draft.eventType(), draft.payloadJson()));
            return events.size();
        }

        @Override
        public synchronized long appendIndependent(UUID runId, EventDraft draft) {
            return append(runId, draft);
        }

        public synchronized List<AppendedEvent> all() {
            return List.copyOf(events);
        }
    }

    // ------------------------------------------------------------------ run_fallback 占位栅栏（M6-04）

    /** uq_rf_source(source_native_run_id) 模拟：同源重复占位 = 败者 false（ON CONFLICT DO NOTHING 同语义） */
    public static final class Fallbacks implements com.objwww.pr.control.alert.domain.repository.RunFallbackRepository {
        private final Map<UUID, com.objwww.pr.control.alert.domain.repository.RunFallbackRepository.OccupancyRow> rows =
                new LinkedHashMap<>();

        @Override
        public synchronized boolean insertOccupancy(
                com.objwww.pr.control.alert.domain.repository.RunFallbackRepository.OccupancyRow row) {
            return rows.putIfAbsent(row.sourceNativeRunId(), row) == null;
        }

        @Override
        public synchronized long countCreatedSince(Instant after) {
            return rows.values().stream().filter(r -> !r.createdAt().isBefore(after)).count();
        }

        @Override
        public synchronized Optional<com.objwww.pr.control.alert.domain.repository.RunFallbackRepository.OccupancyRow> findBySourceRunId(
                UUID sourceNativeRunId) {
            return Optional.ofNullable(rows.get(sourceNativeRunId));
        }

        public synchronized List<com.objwww.pr.control.alert.domain.repository.RunFallbackRepository.OccupancyRow> all() {
            return List.copyOf(rows.values());
        }
    }

    // ------------------------------------------------------------------ report_generation_winner（M6-04）

    /** PK (incident_id, generation) CAS 模拟：先插者赢，重插返回 false（非错误） */
    public static final class Winners implements com.objwww.pr.control.alert.domain.repository.ReportWinnerRepository {
        private record WinnerKey(UUID incidentId, int generation) {
        }

        private final Map<WinnerKey, UUID> rows = new LinkedHashMap<>();

        @Override
        public synchronized boolean claimWinner(UUID incidentId, int generation,
                UUID reportId, UUID runId, Instant decidedAt) {
            return rows.putIfAbsent(new WinnerKey(incidentId, generation), reportId) == null;
        }

        @Override
        public synchronized Optional<UUID> findWinnerReportId(UUID incidentId, int generation) {
            return Optional.ofNullable(rows.get(new WinnerKey(incidentId, generation)));
        }

        public synchronized int size() {
            return rows.size();
        }
    }

    // ------------------------------------------------------------------ holmes_shadow_work（M6-05）

    /**
     * V34 工作面模拟：shadow_key 唯一（putIfAbsent）、认领 = LEASED + attempts/epoch
     * 双 +1、(owner, epoch, LEASED) 三元 CAS 收口、EXHAUSTED 有界。行锁/SKIP LOCKED
     * 由 Postgres IT 覆盖，此处单线程语义。
     */
    public static final class ShadowWorks
            implements com.objwww.pr.control.alert.domain.repository.HolmesShadowWorkRepository {
        private final Map<String, com.objwww.pr.control.alert.domain.repository.HolmesShadowWorkRepository.ShadowWorkRow> rows =
                new LinkedHashMap<>();
        private long seq = 0;

        /** 入队时间戳（测试可拨动——预算窗/租约过期场景） */
        public volatile Instant now = Instant.now();

        @Override
        public synchronized boolean enqueue(
                com.objwww.pr.control.alert.domain.repository.HolmesShadowWorkRepository.ShadowWorkRow row) {
            var created = new com.objwww.pr.control.alert.domain.repository.HolmesShadowWorkRepository.ShadowWorkRow(
                    ++seq, row.shadowKey(), row.kind(), row.nativeRunId(), row.incidentId(),
                    row.generation(), row.snapshotDigest(), "QUEUED", 0, row.maxAttempts(),
                    null, null, 0, null, null, this.now, this.now);
            return rows.putIfAbsent(row.shadowKey(), created) == null;
        }

        @Override
        public synchronized long countCreatedSince(Instant after) {
            return rows.values().stream().filter(r -> !r.createdAt().isBefore(after)).count();
        }

        @Override
        public synchronized Optional<com.objwww.pr.control.alert.domain.repository.HolmesShadowWorkRepository.ShadowWorkRow> findByShadowKey(
                String shadowKey) {
            return Optional.ofNullable(rows.get(shadowKey));
        }

        @Override
        public synchronized List<com.objwww.pr.control.alert.domain.repository.HolmesShadowWorkRepository.ShadowWorkRow> claimBatch(
                String owner, Instant now, Duration lease, int limit) {
            List<com.objwww.pr.control.alert.domain.repository.HolmesShadowWorkRepository.ShadowWorkRow> claimable =
                    rows.values().stream()
                            .filter(r -> r.attempts() < r.maxAttempts())
                            .filter(r -> r.state().equals("QUEUED") || r.state().equals("FAILED")
                                    || (r.state().equals("LEASED") && r.leaseUntil() != null
                                            && r.leaseUntil().isBefore(now)))
                            .sorted(Comparator.comparing(
                                    com.objwww.pr.control.alert.domain.repository.HolmesShadowWorkRepository.ShadowWorkRow::createdAt))
                            .limit(limit)
                            .toList();
            List<com.objwww.pr.control.alert.domain.repository.HolmesShadowWorkRepository.ShadowWorkRow> out =
                    new ArrayList<>();
            for (var r : claimable) {
                var claimed = new com.objwww.pr.control.alert.domain.repository.HolmesShadowWorkRepository.ShadowWorkRow(
                        r.id(), r.shadowKey(), r.kind(), r.nativeRunId(), r.incidentId(),
                        r.generation(), r.snapshotDigest(), "LEASED", r.attempts() + 1,
                        r.maxAttempts(), owner, now.plus(lease), r.leaseEpoch() + 1,
                        r.tokensSpent(), r.lastError(), r.createdAt(), now);
                rows.put(r.shadowKey(), claimed);
                out.add(claimed);
            }
            return out;
        }

        @Override
        public synchronized int complete(long id, String owner, int leaseEpoch,
                Integer tokensSpent, Instant now) {
            var r = findById(id);
            if (r == null || !r.state().equals("LEASED") || !owner.equals(r.leaseOwner())
                    || r.leaseEpoch() != leaseEpoch) {
                return 0;
            }
            rows.put(r.shadowKey(), new com.objwww.pr.control.alert.domain.repository.HolmesShadowWorkRepository.ShadowWorkRow(
                    r.id(), r.shadowKey(), r.kind(), r.nativeRunId(), r.incidentId(),
                    r.generation(), r.snapshotDigest(), "SUCCEEDED", r.attempts(),
                    r.maxAttempts(), r.leaseOwner(), r.leaseUntil(), r.leaseEpoch(),
                    tokensSpent, r.lastError(), r.createdAt(), now));
            return 1;
        }

        @Override
        public synchronized int markFailed(long id, String owner, int leaseEpoch,
                String error, Instant now) {
            var r = findById(id);
            if (r == null || !r.state().equals("LEASED") || !owner.equals(r.leaseOwner())
                    || r.leaseEpoch() != leaseEpoch) {
                return 0;
            }
            String state = r.attempts() >= r.maxAttempts() ? "EXHAUSTED" : "FAILED";
            rows.put(r.shadowKey(), new com.objwww.pr.control.alert.domain.repository.HolmesShadowWorkRepository.ShadowWorkRow(
                    r.id(), r.shadowKey(), r.kind(), r.nativeRunId(), r.incidentId(),
                    r.generation(), r.snapshotDigest(), state, r.attempts(),
                    r.maxAttempts(), r.leaseOwner(), r.leaseUntil(), r.leaseEpoch(),
                    r.tokensSpent(), error, r.createdAt(), now));
            return 1;
        }

        private com.objwww.pr.control.alert.domain.repository.HolmesShadowWorkRepository.ShadowWorkRow findById(long id) {
            return rows.values().stream().filter(r -> r.id() == id).findFirst().orElse(null);
        }

        public synchronized List<com.objwww.pr.control.alert.domain.repository.HolmesShadowWorkRepository.ShadowWorkRow> all() {
            return List.copyOf(rows.values());
        }
    }

    // ------------------------------------------------------------------ CAS（M3-07 落档）

    /** 内容寻址内存 CAS：同 digest 幂等，put 返回与 LocalCasArtifactStore 同构的相对路径 */
    public static final class Cas implements com.objwww.pr.control.domain.port.ArtifactStore {
        private final Map<String, byte[]> blobs = new LinkedHashMap<>();

        @Override
        public synchronized String putIfAbsent(Digest digest, byte[] content) {
            blobs.putIfAbsent(digest.value(), content.clone());
            return digest.value().substring(0, 2) + "/" + digest.value();
        }

        @Override
        public synchronized boolean exists(Digest digest) {
            return blobs.containsKey(digest.value());
        }

        @Override
        public synchronized Optional<byte[]> get(Digest digest) {
            byte[] blob = blobs.get(digest.value());
            return blob == null ? Optional.empty() : Optional.of(blob.clone());
        }

        public synchronized int size() {
            return blobs.size();
        }
    }

    // ------------------------------------------------------------------ notify_outbox（M3-19 生产者面）

    /** 唯一键 (report_id, channel, template_version) → DuplicateKeyException（at-least-once 防重锚） */
    public static final class Outboxes implements com.objwww.pr.control.alert.domain.repository.NotifyOutboxRepository {
        private final List<com.objwww.pr.control.alert.domain.model.NotifyOutboxEntry> rows = new ArrayList<>();

        @Override
        public synchronized void insert(com.objwww.pr.control.alert.domain.model.NotifyOutboxEntry entry) {
            boolean dup = rows.stream().anyMatch(r ->
                    r.reportId().equals(entry.reportId())
                            && r.channel().equals(entry.channel())
                            && r.templateVersion().equals(entry.templateVersion()));
            if (dup) {
                throw new DuplicateKeyException("uq_notify_outbox_delivery 模拟");
            }
            rows.add(entry);
        }

        @Override
        public synchronized List<com.objwww.pr.control.alert.domain.model.NotifyOutboxEntry> findByPublicationId(
                UUID publicationId) {
            return rows.stream().filter(r -> r.publicationId().equals(publicationId)).toList();
        }

        public synchronized List<com.objwww.pr.control.alert.domain.model.NotifyOutboxEntry> all() {
            return List.copyOf(rows);
        }
    }

    // ------------------------------------ evidence_snapshot（EX-A4a F05 黑板面假件）

    /**
     * 快照假件（V16 同构：freeze 幂等——同 (run,digest) 返回 false 零成员写入；
     * membersOf evidence_id 序稳定；无更新路径）。P1-05 行为面与 PG 一致。
     */
    public static final class Snapshots implements
            com.objwww.pr.control.alert.domain.evidence.EvidenceSnapshotRepository {
        private final Map<UUID, com.objwww.pr.control.alert.domain.evidence.EvidenceSnapshotRepository.FrozenSnapshot>
                rows = new LinkedHashMap<>();
        private final Map<UUID, List<com.objwww.pr.control.alert.domain.evidence.EvidenceSnapshotRepository.SnapshotMemberRow>>
                members = new LinkedHashMap<>();

        @Override
        public synchronized boolean freeze(
                com.objwww.pr.control.alert.domain.evidence.EvidenceSnapshotRepository.FrozenSnapshot snapshot,
                List<com.objwww.pr.control.alert.domain.evidence.EvidenceSnapshotRepository.SnapshotMemberRow> memberRows) {
            boolean dup = rows.values().stream().anyMatch(r ->
                    r.runId().equals(snapshot.runId())
                            && r.snapshotDigest().equals(snapshot.snapshotDigest()));
            if (dup) {
                return false;
            }
            rows.put(snapshot.snapshotId(), snapshot);
            members.put(snapshot.snapshotId(), List.copyOf(memberRows));
            return true;
        }

        @Override
        public synchronized java.util.Optional<com.objwww.pr.control.alert.domain.evidence.EvidenceSnapshotRepository.FrozenSnapshot> find(
                UUID runId, String snapshotDigest) {
            return rows.values().stream()
                    .filter(r -> r.runId().equals(runId)
                            && r.snapshotDigest().equals(snapshotDigest))
                    .findFirst();
        }

        /** 成员清单 evidence_id 序稳定（PG 面同序契约） */
        @Override
        public synchronized List<com.objwww.pr.control.alert.domain.evidence.EvidenceSnapshotRepository.SnapshotMemberRow> membersOf(
                UUID snapshotId) {
            return members.getOrDefault(snapshotId, List.of()).stream()
                    .sorted(java.util.Comparator.comparing(
                            com.objwww.pr.control.alert.domain.evidence.EvidenceSnapshotRepository.SnapshotMemberRow::evidenceId))
                    .toList();
        }
    }

    // --------------------------------- 工具调用账本假件（EX-A4a F16 恢复扫描面）

    /**
     * V15 同构假件：PENDING 先行 → 终态 CAS 单向（首回执生效）；
     * reclaimPendingOlderThan 镜像 PG 单语句语义（PENDING + started_at &lt; cutoff →
     * UNKNOWN/TRANSPORT_UNKNOWN，返回收敛行数）。
     */
    public static final class ToolLedger implements
            com.objwww.pr.control.alert.domain.tool.RcaToolInvocationLedger {
        /** operationId → [identity, state, reason, startedAt, settledAt] */
        public final Map<UUID, Row> rows = new LinkedHashMap<>();

        public static final class Row {
            public final com.objwww.pr.control.alert.domain.tool.RcaToolInvocationLedger.InvocationIdentity identity;
            public com.objwww.pr.control.alert.domain.tool.ToolInvocationState state;
            public com.objwww.pr.control.alert.domain.tool.ToolReasonCode reason;
            public final Instant startedAt;
            public Instant settledAt;
            public UUID resultRef;

            Row(InvocationIdentity identity, Instant startedAt) {
                this.identity = identity;
                this.state = com.objwww.pr.control.alert.domain.tool.ToolInvocationState.PENDING;
                this.startedAt = startedAt;
            }
        }

        @Override
        public void open(InvocationIdentity identity) {
            rows.put(identity.operationId(), new Row(identity, Instant.now()));
        }

        @Override
        public boolean succeed(UUID operationId) {
            return settle(operationId,
                    com.objwww.pr.control.alert.domain.tool.ToolInvocationState.SUCCESS,
                    null);
        }

        @Override
        public boolean fail(UUID operationId,
                com.objwww.pr.control.alert.domain.tool.ToolInvocationState terminal,
                com.objwww.pr.control.alert.domain.tool.ToolReasonCode reasonCode) {
            return settle(operationId, terminal, reasonCode);
        }

        private synchronized boolean settle(UUID operationId,
                com.objwww.pr.control.alert.domain.tool.ToolInvocationState terminal,
                com.objwww.pr.control.alert.domain.tool.ToolReasonCode reasonCode) {
            Row row = rows.get(operationId);
            if (row == null || row.state
                    != com.objwww.pr.control.alert.domain.tool.ToolInvocationState.PENDING) {
                return false;
            }
            row.state = terminal;
            row.reason = reasonCode;
            row.settledAt = Instant.now();
            return true;
        }

        /** 打开时间回拨（测试用：把 PENDING 行造老） */
        public synchronized void agePending(UUID operationId, Instant startedAt) {
            Row row = rows.get(operationId);
            if (row != null) {
                rows.put(operationId, new Row(row.identity, startedAt));
            }
        }

        /** EX-A3（F09）：结果引用随账落档（CAS 锚 PENDING，succeed 前调用） */
        @Override
        public synchronized boolean markResultRef(UUID operationId, UUID evidenceId) {
            Row row = rows.get(operationId);
            if (row == null || row.state
                    != com.objwww.pr.control.alert.domain.tool.ToolInvocationState.PENDING) {
                return false;
            }
            row.resultRef = evidenceId;
            return true;
        }

        /** EX-A3（F08）：恢复读——按 (run,task) 取账本行，call_seq 序 */
        @Override
        public synchronized java.util.List<com.objwww.pr.control.alert.domain.tool.RcaToolInvocationLedger.InvocationRecovery> findRecoveryByTask(
                UUID runId, UUID taskId) {
            return rows.values().stream()
                    .filter(r -> r.identity.runId().equals(runId)
                            && r.identity.taskId().equals(taskId))
                    .sorted(java.util.Comparator
                            .comparingLong((Row r) -> r.identity.callSeq())
                            .thenComparing(r -> r.identity.operationId()))
                    .map(r -> new com.objwww.pr.control.alert.domain.tool.RcaToolInvocationLedger.InvocationRecovery(
                            r.identity.operationId(), r.identity.callSeq(),
                            r.identity.attemptId(), r.identity.actionDigest(),
                            r.state, r.resultRef))
                    .toList();
        }

        @Override
        public synchronized int reclaimPendingOlderThan(Instant cutoff) {
            int swept = 0;
            for (Row row : rows.values()) {
                if (row.state == com.objwww.pr.control.alert.domain.tool.ToolInvocationState.PENDING
                        && row.startedAt.isBefore(cutoff)) {
                    row.state = com.objwww.pr.control.alert.domain.tool.ToolInvocationState.UNKNOWN;
                    row.reason = com.objwww.pr.control.alert.domain.tool.ToolReasonCode.TRANSPORT_UNKNOWN;
                    row.settledAt = Instant.now();
                    swept++;
                }
            }
            return swept;
        }
    }
}
