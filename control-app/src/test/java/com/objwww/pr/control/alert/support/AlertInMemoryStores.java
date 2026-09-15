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
import java.util.concurrent.ConcurrentHashMap;

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
    public final Tasks tasks = new Tasks(runs);
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
    /** R7-X1：任务→角色冻结绑定假件（uq(run,round,task_key) 只增不改） */
    public final Bindings bindings = new Bindings();
    /** R7-X4：主任务检查点假件（task_id 幂等锚 + 相位 CAS） */
    public final Checkpoints checkpoints = new Checkpoints();
    /** R7-X4/X11：委派裁决台账假件（uq(run,gap) 冲突显式抛） */
    public final DelegationDecisions delegationDecisions = new DelegationDecisions();
    /** R10：工作记忆快照假件（append 幂等 = 同修订重放返回既有行） */
    public final WorkingMemories workingMemories = new WorkingMemories();
    /** R7a-1：RCA 模型调用账本假件（PENDING 先行 + 终态 CAS） */
    public final ModelCalls modelCalls = new ModelCalls();
    /** UX-01：分类写面假件（rule/override 两族列分离 + 生效面裁决 + 审计行） */
    public final Categories categories = new Categories();

    // ------------------------------------------------------------------ UX-01 分类面

    /**
     * 分类假件：镜像 V82 语义——rule_* 与 override_* 分离，生效面 = override ?? rule
     * ?? UNCLASSIFIED；CAS 以 override_revision 为锚；审计行按 (incidentId, key) 幂等。
     */
    public static final class Categories implements com.objwww.pr.control.alert.domain.repository.IncidentCategoryRepository {
        /** 每 incident 的分类态（未触碰 = 全 null，等价存量行） */
        public static final class State {
            public String ruleCategory;
            public String ruleId;
            public String ruleVersion;
            public Instant classifiedAt;
            public String overrideCategory;
            public String overrideActor;
            public String overrideReason;
            public Instant overrideAt;
            public int overrideRevision;

            public String effective() {
                if (overrideCategory != null) {
                    return overrideCategory;
                }
                return ruleCategory != null ? ruleCategory : "UNCLASSIFIED";
            }
        }

        private final Map<UUID, State> states = new HashMap<>();
        private final List<OverrideAuditRow> audit = new ArrayList<>();
        /** 规则重分类调用计数（单测断言"何时不重分类"用） */
        public int applyRuleCalls;

        public synchronized State state(UUID incidentId) {
            return states.computeIfAbsent(incidentId, k -> new State());
        }

        public synchronized List<OverrideAuditRow> auditRows(UUID incidentId) {
            return audit.stream().filter(r -> r.incidentId().equals(incidentId)).toList();
        }

        @Override
        public synchronized void applyRuleClassification(UUID incidentId,
                com.objwww.pr.control.alert.domain.classification.IncidentCategory category,
                String ruleId, String ruleVersion, Instant classifiedAt) {
            applyRuleCalls++;
            State s = state(incidentId);
            s.ruleCategory = category.name();
            s.ruleId = ruleId;
            s.ruleVersion = ruleVersion;
            s.classifiedAt = classifiedAt;
        }

        @Override
        public synchronized Optional<CategoryState> lockState(UUID incidentId) {
            State s = states.get(incidentId);
            if (s == null) {
                return Optional.empty();
            }
            return Optional.of(new CategoryState(s.ruleCategory, s.overrideCategory,
                    s.effective(), s.overrideRevision));
        }

        @Override
        public synchronized boolean setOverride(UUID incidentId,
                com.objwww.pr.control.alert.domain.classification.IncidentCategory category,
                String actor, String reason, Instant at, int expectedRevision) {
            State s = states.get(incidentId);
            if (s == null || s.overrideRevision != expectedRevision) {
                return false;
            }
            s.overrideCategory = category.name();
            s.overrideActor = actor;
            s.overrideReason = reason;
            s.overrideAt = at;
            s.overrideRevision++;
            return true;
        }

        @Override
        public synchronized boolean clearOverride(UUID incidentId, int expectedRevision) {
            State s = states.get(incidentId);
            if (s == null || s.overrideRevision != expectedRevision) {
                return false;
            }
            s.overrideCategory = null;
            s.overrideActor = null;
            s.overrideReason = null;
            s.overrideAt = null;
            s.overrideRevision++;
            return true;
        }

        @Override
        public synchronized void appendAudit(OverrideAuditRow row) {
            boolean dup = audit.stream().anyMatch(r -> r.incidentId().equals(row.incidentId())
                    && r.idempotencyKey().equals(row.idempotencyKey()));
            if (dup) {
                throw new DuplicateKeyException("uq_oco_idempotency");
            }
            audit.add(row);
        }

        @Override
        public synchronized Optional<OverrideAuditRow> findAuditByIdempotencyKey(
                UUID incidentId, String idempotencyKey) {
            return audit.stream().filter(r -> r.incidentId().equals(incidentId)
                    && r.idempotencyKey().equals(idempotencyKey)).findFirst();
        }
    }

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
        /** SR（V108）对账专用列镜像（不进 RcaRun 域记录；fake 与 PG 读写语义对齐） */
        private final Map<UUID, java.time.Instant> reconcileDeadlines = new LinkedHashMap<>();
        private final Map<UUID, java.time.Instant> reportingStarted = new LinkedHashMap<>();
        private final Map<UUID, Integer> recoveryAttempts = new LinkedHashMap<>();

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

        // -------------------------------------------------- SR 对账面（V108；与 PG 同语义）

        /** WC-4 §6.1 keyset 分页（与 PG 同语义：游标 (createdAt,id) 后取、稳定排序） */
        @Override
        public synchronized List<com.objwww.pr.control.alert.domain.repository.RcaRunRepository.ReconcileCandidate>
        findActiveForReconcileAfter(java.time.Instant afterCreatedAt, UUID afterId, int limit) {
            return rows.values().stream()
                    .filter(r -> r.state().isActive())
                    .filter(r -> afterCreatedAt == null
                            || r.createdAt().isAfter(afterCreatedAt)
                            || (r.createdAt().equals(afterCreatedAt)
                                && afterId != null && r.id().compareTo(afterId) > 0))
                    .sorted(Comparator.comparing(RcaRun::createdAt).thenComparing(RcaRun::id))
                    .limit(limit)
                    .map(r -> new com.objwww.pr.control.alert.domain.repository
                            .RcaRunRepository.ReconcileCandidate(
                            r.id(), r.incidentId(), r.state(), r.generation(), r.purpose(),
                            r.createdAt(), r.updatedAt(),
                            reconcileDeadlines.get(r.id()),
                            reportingStarted.get(r.id()),
                            recoveryAttempts.getOrDefault(r.id(), 0)))
                    .toList();
        }

        /** WC-4 §6.3 锁内复验面：现行 deadline 单列读（fake 与 PG 读写语义对齐） */
        @Override
        public synchronized Optional<java.time.Instant> reconcileDeadlineById(UUID id) {
            return Optional.ofNullable(reconcileDeadlines.get(id));
        }

        /** WC-5：最老活跃 Run createdAt（gauge 数据面，fake 与 PG 对齐） */
        @Override
        public synchronized Optional<java.time.Instant> oldestActiveCreatedAt() {
            return rows.values().stream()
                    .filter(r -> r.state().isActive())
                    .map(RcaRun::createdAt)
                    .min(Comparator.naturalOrder());
        }

        @Override
        public synchronized void markReportingStarted(UUID id, java.time.Instant now) {
            reportingStarted.putIfAbsent(id, now);
        }

        @Override
        public synchronized void fixReconcileDeadlineIfAbsent(UUID id, java.time.Instant deadline) {
            reconcileDeadlines.putIfAbsent(id, deadline);
        }

        @Override
        public synchronized void incrementRecoveryAttempts(UUID id) {
            recoveryAttempts.merge(id, 1, Integer::sum);
        }

        public synchronized java.time.Instant reconcileDeadlineOf(UUID id) {
            return reconcileDeadlines.get(id);
        }

        public synchronized int recoveryAttemptsOf(UUID id) {
            return recoveryAttempts.getOrDefault(id, 0);
        }

        public synchronized List<RcaRun> all() {
            return List.copyOf(rows.values());
        }

        /** SR：claim 谓词用（行不在 = LEGACY_UNKNOWN，与 SQL coalesce 同语义） */
        public synchronized com.objwww.pr.control.alert.domain.model.RunPurpose purposeOf(UUID id) {
            RcaRun run = rows.get(id);
            return run == null
                    ? com.objwww.pr.control.alert.domain.model.RunPurpose.LEGACY_UNKNOWN
                    : run.purpose();
        }
    }

    // ------------------------------------------------------------------ rca_task

    public static final class Tasks implements RcaTaskRepository {
        private final Map<UUID, RcaTask> rows = new LinkedHashMap<>();
        /** SR：claim 排除影子 Run（镜像 CLAIM_SQL coalesce(purpose,'LEGACY_UNKNOWN')<>'SHADOW' 谓词；可空=独立 fake 无 run 面） */
        private final Runs runsRef;

        public Tasks() {
            this(null);
        }

        public Tasks(Runs runsRef) {
            this.runsRef = runsRef;
        }

        @Override
        public synchronized void insert(RcaTask task) {
            boolean dup = rows.values().stream().anyMatch(t ->
                    t.runId().equals(task.runId()) && t.roundId() == task.roundId()
                            && t.taskKey().equals(task.taskKey()));
            if (dup) {
                throw new DuplicateKeyException("uq_rca_task_key 模拟 (run,round,task_key)");
            }
            rows.put(task.id(), task);
        }

        @Override
        public synchronized Optional<RcaTask> claimNext(String owner, Instant now, Duration lease) {
            Optional<RcaTask> candidate = rows.values().stream()
                    .filter(t -> t.state() == RcaTaskState.READY || t.state() == RcaTaskState.RETRY_WAIT)
                    .filter(t -> !t.availableAt().isAfter(now))
                    // C-70（M6-01）与 Postgres CLAIM_SQL 同语义：通用领取只认 driver task_key
                    // + SR §4.3 REPORT_FINALIZE 恢复 task（worker 领取后走 finalize 分派）
                    .filter(t -> t.taskKey().equals(RcaTask.HOLMES_INVESTIGATE)
                            || t.taskKey().equals(RcaTask.NATIVE_INVESTIGATE)
                            || t.taskKey().equals(RcaTask.REPORT_FINALIZE))
                    // SR §3.2：生产调度排除影子 Run（不以"临时占满槽位"为隔离机制）
                    .filter(t -> runsRef == null
                            || runsRef.purposeOf(t.runId()) != com.objwww.pr.control.alert
                                    .domain.model.RunPurpose.SHADOW)
                    .min(SlaPolicy.claimOrder(now));
            if (candidate.isEmpty()) {
                return Optional.empty();
            }
            RcaTask t = candidate.get();
            RcaTask claimed = new RcaTask(t.id(), t.runId(), t.taskKey(), RcaTaskState.LEASED,
                    t.priority(), t.availableAt(), t.readySince(), t.deadlineAt(),
                    owner, now.plus(lease), t.leaseEpoch() + 1,
                    t.attemptCount() + 1, t.maxAttempts(), t.createdAt(), now, t.roundId());
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
                        t.attemptCount(), t.maxAttempts(), t.createdAt(), now, t.roundId()));
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
                    t.attemptCount(), t.maxAttempts(), t.createdAt(), now, t.roundId()));
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
                    t.attemptCount(), t.maxAttempts(), t.createdAt(), t.updatedAt(), t.roundId()));
            return true;
        }

        @Override
        public synchronized int countQueued() {
            return (int) rows.values().stream()
                    .filter(t -> t.state() == RcaTaskState.READY || t.state() == RcaTaskState.RETRY_WAIT)
                    .count();
        }

        /**
         * WC-4 §5.3 清理通道 join 面（镜像 PG：非终态任务 × 终态 Run，(createdAt,id)
         * keyset）。独立 fake（无 runsRef）无 run 语义 → 空集。
         */
        @Override
        public synchronized List<RcaTaskRepository.OpenTaskRef>
        findOpenTasksUnderTerminalRunsAfter(Instant afterCreatedAt, UUID afterId, int limit) {
            if (runsRef == null) {
                return List.of();
            }
            return rows.values().stream()
                    .filter(t -> switch (t.state()) {
                        case READY, BLOCKED, RETRY_WAIT, LEASED, RUNNING -> true;
                        default -> false;
                    })
                    .filter(t -> {
                        RcaRun run = runsRef.findById(t.runId()).orElse(null);
                        return run != null && !run.state().isActive();
                    })
                    .filter(t -> afterCreatedAt == null
                            || t.createdAt().isAfter(afterCreatedAt)
                            || (t.createdAt().equals(afterCreatedAt)
                                && afterId != null && t.id().compareTo(afterId) > 0))
                    .sorted(Comparator.comparing(RcaTask::createdAt).thenComparing(RcaTask::id))
                    .limit(limit)
                    .map(t -> new RcaTaskRepository.OpenTaskRef(t.id(), t.runId(), t.createdAt()))
                    .toList();
        }

        /** WC-5：终态 Run 名下未决任务存量（gauge 数据面，fake 与 PG 对齐） */
        @Override
        public synchronized long countOpenTasksUnderTerminalRuns() {
            if (runsRef == null) {
                return 0;
            }
            return rows.values().stream()
                    .filter(t -> switch (t.state()) {
                        case READY, BLOCKED, RETRY_WAIT, LEASED, RUNNING -> true;
                        default -> false;
                    })
                    .filter(t -> {
                        RcaRun run = runsRef.findById(t.runId()).orElse(null);
                        return run != null && !run.state().isActive();
                    })
                    .count();
        }

        public synchronized List<RcaTask> all() {
            return List.copyOf(rows.values());
        }
    }

    // ------------------------------------------------------------------ rca_attempt

    public static final class Attempts implements RcaAttemptRepository {
        private final Map<UUID, RcaAttempt> rows = new LinkedHashMap<>();
        /** PA-A1 进度双列（V111；RcaAttempt 记录不承载，独立存） */
        private final Map<UUID, java.time.Instant> lastActivity = new LinkedHashMap<>();
        private final Map<UUID, java.time.Instant> lastMeaningful = new LinkedHashMap<>();

        @Override
        public synchronized void insert(RcaAttempt attempt) {
            boolean dup = rows.values().stream().anyMatch(a ->
                    a.taskId().equals(attempt.taskId()) && a.attemptNo() == attempt.attemptNo());
            if (dup) {
                throw new DuplicateKeyException("uq_rca_attempt 模拟");
            }
            rows.put(attempt.id(), attempt);
            // PA-A1：insert 即双列 = started_at（与 Postgres 实现同语义）
            lastActivity.put(attempt.id(), attempt.startedAt());
            lastMeaningful.put(attempt.id(), attempt.startedAt());
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

        @Override
        public synchronized boolean markActivityByTask(UUID taskId, java.time.Instant at) {
            boolean any = false;
            for (RcaAttempt a : rows.values()) {
                if (a.taskId().equals(taskId)
                        && a.status() == com.objwww.pr.control.alert.domain.model.RcaAttemptStatus.STARTED) {
                    lastActivity.put(a.id(), at);
                    any = true;
                }
            }
            return any;
        }

        @Override
        public synchronized boolean markMeaningfulProgressByTask(UUID taskId, java.time.Instant at) {
            boolean any = false;
            for (RcaAttempt a : rows.values()) {
                if (a.taskId().equals(taskId)
                        && a.status() == com.objwww.pr.control.alert.domain.model.RcaAttemptStatus.STARTED) {
                    lastActivity.put(a.id(), at);
                    lastMeaningful.put(a.id(), at);
                    any = true;
                }
            }
            return any;
        }

        @Override
        public synchronized java.util.Optional<AttemptProgress> findStartedProgressByTaskId(
                UUID taskId) {
            return rows.values().stream()
                    .filter(a -> a.taskId().equals(taskId)
                            && a.status() == com.objwww.pr.control.alert.domain.model
                                    .RcaAttemptStatus.STARTED)
                    .max(java.util.Comparator.comparing(RcaAttempt::startedAt)
                            .thenComparing(RcaAttempt::attemptNo))
                    .map(a -> new AttemptProgress(a.id(), a.taskId(), a.attemptNo(),
                            a.leaseEpoch(), a.startedAt(), lastActivity.get(a.id()),
                            lastMeaningful.get(a.id())));
        }

        /** 测试直写面：模拟存量行/滞后回写（NULL = 移除写点，判定回退 startedAt） */
        public synchronized void setProgress(UUID attemptId, java.time.Instant activityAt,
                java.time.Instant meaningfulAt) {
            if (activityAt == null) {
                lastActivity.remove(attemptId);
            } else {
                lastActivity.put(attemptId, activityAt);
            }
            if (meaningfulAt == null) {
                lastMeaningful.remove(attemptId);
            } else {
                lastMeaningful.put(attemptId, meaningfulAt);
            }
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
        public synchronized Optional<RcaReport> findById(UUID id) {
            return Optional.ofNullable(rows.get(id));
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

        /** EN-04：from-guard CAS（现态非 from = 0 行；WAITING 中转迁移防回退） */
        @Override
        public synchronized boolean advanceState(UUID id, OperatorCommand.State from,
                                                 OperatorCommand.State to,
                                                 Instant appliedAt) {
            OperatorCommand row = rows.get(id);
            if (row == null || row.state() != from) {
                return false;
            }
            rows.put(id, row.withState(to, appliedAt));
            return true;
        }

        /** EN-04 H14：WAITING 且 deadline 已过的命令行（CONFIG_SWITCH 专用） */
        @Override
        public synchronized java.util.List<OperatorCommand> findWaitingOverdue(
                Instant now) {
            return rows.values().stream()
                    .filter(r -> r.type() == OperatorCommand.Type.CONFIG_SWITCH)
                    .filter(r -> r.state() == OperatorCommand.State.WAITING_SAFE_POINT)
                    .filter(r -> r.payload().get("deadline") instanceof String deadline
                            && Instant.parse(deadline).isBefore(now))
                    .sorted(java.util.Comparator.comparing(OperatorCommand::createdAt))
                    .toList();
        }

        /** WC-2 恢复面：run 的某类型命令全量行（唯一候选身份裁决用） */
        @Override
        public synchronized java.util.List<OperatorCommand> findByRunAndType(
                UUID runId, OperatorCommand.Type type) {
            return rows.values().stream()
                    .filter(r -> r.runId().equals(runId) && r.type() == type)
                    .sorted(java.util.Comparator.comparing(OperatorCommand::createdAt))
                    .toList();
        }

        public synchronized List<OperatorCommand> all() {
            return List.copyOf(rows.values());
        }
    }

    // ------------------------------------------------------------------ rca_event 追加面（M5-14 命令生效事件）

    public record AppendedEvent(UUID runId, String eventType, String payloadJson) {
    }

    /** seq 简化为追加序（命令面 UT 只关心"是否追加/追加了什么"）；PA-A2 起 per-run
     * 哈希链与 PG 实现同构（append 即链写，verifyChain 重算比对；tamper 面供测试） */
    public static final class RcaEventLog implements RcaEventAppender {
        private final List<AppendedEvent> events = new ArrayList<>();
        private final Map<UUID, Long> lastSeqByRun = new LinkedHashMap<>();
        private final Map<UUID, String> lastHashByRun = new LinkedHashMap<>();
        private record ChainRow(long seq, String prevHash, String eventHash, String type,
                String digest) {
        }
        private final Map<UUID, List<ChainRow>> chains = new LinkedHashMap<>();

        @Override
        public synchronized long append(UUID runId, EventDraft draft) {
            long seq = lastSeqByRun.merge(runId, 1L, Long::sum);
            String digest = com.objwww.pr.shared.Digest.sha256Of(draft.payloadJson()).value();
            String prev = lastHashByRun.getOrDefault(runId, "GENESIS");
            String hash = com.objwww.pr.shared.Digest.sha256Of(prev + ":" + seq + ":"
                    + draft.eventType() + ":" + digest).value();
            lastHashByRun.put(runId, hash);
            chains.computeIfAbsent(runId, k -> new ArrayList<>()).add(
                    new ChainRow(seq, prev, hash, draft.eventType(), digest));
            events.add(new AppendedEvent(runId, draft.eventType(), draft.payloadJson()));
            return events.size();
        }

        @Override
        public synchronized long appendIndependent(UUID runId, EventDraft draft) {
            return append(runId, draft);
        }

        @Override
        public synchronized ChainReport verifyChain(UUID runId) {
            List<ChainRow> rows = chains.getOrDefault(runId, List.of());
            String expectedPrev = "GENESIS";
            long verified = 0;
            for (ChainRow row : rows) {
                String recomputed = com.objwww.pr.shared.Digest.sha256Of(expectedPrev + ":"
                        + row.seq() + ":" + row.type() + ":" + row.digest()).value();
                if (!row.prevHash().equals(expectedPrev)
                        || !row.eventHash().equals(recomputed)) {
                    return new ChainReport(runId, rows.size(), verified, row.seq());
                }
                expectedPrev = row.eventHash();
                verified++;
            }
            return new ChainReport(runId, rows.size(), verified, -1);
        }

        @Override
        public synchronized java.util.List<UUID> runIdsWithEvents() {
            return List.copyOf(chains.keySet());
        }

        /** 测试面：篡改最后一条事件的存储哈希（模拟库内改写，验链器应报 broken） */
        public synchronized void tamperLastHash(UUID runId) {
            List<ChainRow> rows = chains.get(runId);
            if (rows != null && !rows.isEmpty()) {
                int last = rows.size() - 1;
                ChainRow row = rows.get(last);
                rows.set(last, new ChainRow(row.seq(), row.prevHash(),
                        "tampered-" + row.eventHash(), row.type(), row.digest()));
            }
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

        /** WC-5 读面：run × 结算状态行数（fake 与 PG 对齐） */
        @Override
        public synchronized long countByRunAndState(UUID runId,
                com.objwww.pr.control.alert.domain.tool.ToolInvocationState state) {
            return rows.values().stream()
                    .filter(r -> r.identity.runId().equals(runId) && r.state == state)
                    .count();
        }
    }

    // --------------------------------- 任务→角色冻结绑定假件（R7-X1）

    /** V46 同构假件：只增不改，uq(run, round, task_key) 冲突显式抛 */
    public static final class Bindings implements
            com.objwww.pr.control.alert.domain.repository.TaskExecutionBindingRepository {
        private final Map<UUID, com.objwww.pr.control.alert.domain.model.TaskExecutionBinding> rows =
                new LinkedHashMap<>();

        @Override
        public synchronized void insert(
                com.objwww.pr.control.alert.domain.model.TaskExecutionBinding binding) {
            boolean dup = rows.values().stream().anyMatch(b ->
                    b.runId().equals(binding.runId()) && b.roundId() == binding.roundId()
                            && b.taskKey().equals(binding.taskKey()));
            if (dup) {
                throw new DuplicateKeyException("uq_rca_task_binding_key 模拟");
            }
            rows.put(binding.taskId(), binding);
        }

        @Override
        public synchronized java.util.Optional<com.objwww.pr.control.alert.domain.model.TaskExecutionBinding> findByTask(
                UUID taskId) {
            return java.util.Optional.ofNullable(rows.get(taskId));
        }

        @Override
        public synchronized List<com.objwww.pr.control.alert.domain.model.TaskExecutionBinding> findByRun(
                UUID runId) {
            return rows.values().stream()
                    .filter(b -> b.runId().equals(runId))
                    .sorted(java.util.Comparator.comparing(
                            com.objwww.pr.control.alert.domain.model.TaskExecutionBinding::taskId))
                    .toList();
        }
    }

    // --------------------------------- 主任务检查点假件（R7-X4）

    /** V47 同构假件：task_id 幂等锚 upsert + 相位 CAS（末写胜出 = 单写者纪律下的 PG 语义） */
    public static final class Checkpoints implements
            com.objwww.pr.control.alert.domain.repository.PrimaryCheckpointRepository {
        private final Map<UUID, com.objwww.pr.control.alert.domain.agent.PrimaryCheckpoint> rows =
                new LinkedHashMap<>();
        /** CL-01 动作身份（REPLAYED 判定锚，同构 last_action_* 两列） */
        private final Map<UUID, String> lastActionKeys = new LinkedHashMap<>();
        private final Map<UUID, String> lastActionDigests = new LinkedHashMap<>();

        @Override
        public synchronized void upsert(
                com.objwww.pr.control.alert.domain.agent.PrimaryCheckpoint checkpoint) {
            rows.put(checkpoint.taskId(), checkpoint);
        }

        @Override
        public synchronized java.util.Optional<com.objwww.pr.control.alert.domain.agent.PrimaryCheckpoint> findByTask(
                UUID taskId) {
            return java.util.Optional.ofNullable(rows.get(taskId));
        }

        @Override
        public synchronized com.objwww.pr.control.alert.domain.repository
                .PrimaryCheckpointRepository.CommitState findCommitStateForUpdate(UUID taskId) {
            com.objwww.pr.control.alert.domain.agent.PrimaryCheckpoint current = rows.get(taskId);
            return current == null ? null
                    : new com.objwww.pr.control.alert.domain.repository
                            .PrimaryCheckpointRepository.CommitState(current,
                            lastActionKeys.get(taskId), lastActionDigests.get(taskId));
        }

        @Override
        public synchronized long updateGuarded(
                com.objwww.pr.control.alert.domain.agent.PrimaryCheckpoint next,
                long expectedRevision, String actionKey, String actionDigest) {
            com.objwww.pr.control.alert.domain.agent.PrimaryCheckpoint current = rows.get(next.taskId());
            if (current == null || current.revision() != expectedRevision) {
                return 0;
            }
            // 镜像 PG SQL：revision=revision+1 随行落库
            rows.put(next.taskId(), next.withRevision(current.revision() + 1));
            lastActionKeys.put(next.taskId(), actionKey);
            lastActionDigests.put(next.taskId(), actionDigest);
            return 1;
        }

        @Override
        public synchronized boolean transitionPhase(UUID taskId,
                com.objwww.pr.control.alert.domain.agent.PrimaryCheckpoint.Phase from,
                com.objwww.pr.control.alert.domain.agent.PrimaryCheckpoint.Phase to) {
            com.objwww.pr.control.alert.domain.agent.PrimaryCheckpoint current = rows.get(taskId);
            if (current == null || current.phase() != from) {
                return false;
            }
            rows.put(taskId, current.withPhase(to, current.updatedAt()));
            return true;
        }
    }

    // --------------------------------- 委派裁决台账假件（R7-X4/X11）

    /** V47 同构假件：uq(run, gap) 冲突显式抛（调用方幂等短路依据） */
    public static final class DelegationDecisions implements
            com.objwww.pr.control.alert.domain.repository.DelegationDecisionRepository {
        private final Map<UUID, com.objwww.pr.control.alert.domain.agent.DelegationDecision> rows =
                new LinkedHashMap<>();

        @Override
        public synchronized void insert(
                com.objwww.pr.control.alert.domain.agent.DelegationDecision decision) {
            boolean dup = rows.values().stream().anyMatch(d ->
                    d.runId().equals(decision.runId()) && d.gapId().equals(decision.gapId()));
            if (dup) {
                throw new DuplicateKeyException("uq_r7_delegation_gap 模拟");
            }
            rows.put(decision.id(), decision);
        }

        @Override
        public synchronized java.util.Optional<com.objwww.pr.control.alert.domain.agent.DelegationDecision> findById(
                UUID id) {
            return java.util.Optional.ofNullable(rows.get(id));
        }

        @Override
        public synchronized java.util.Optional<com.objwww.pr.control.alert.domain.agent.DelegationDecision> findByRunAndGap(
                UUID runId, String gapId) {
            return rows.values().stream()
                    .filter(d -> d.runId().equals(runId) && d.gapId().equals(gapId))
                    .findFirst();
        }

        @Override
        public synchronized List<com.objwww.pr.control.alert.domain.agent.DelegationDecision> findByRunAndPrimaryTask(
                UUID runId, UUID primaryTaskId) {
            return rows.values().stream()
                    .filter(d -> d.runId().equals(runId)
                            && d.primaryTaskId().equals(primaryTaskId))
                    .sorted(java.util.Comparator.comparing(
                            com.objwww.pr.control.alert.domain.agent.DelegationDecision::roundId)
                            .thenComparing(
                                    com.objwww.pr.control.alert.domain.agent.DelegationDecision::seq))
                    .toList();
        }
    }

    // --------------------------------- RCA 模型调用账本假件（R7a-1）

    /** V48 同构假件：uq(run,task,attempt,action,physical) 冲突显式抛 + 终态 CAS */
    public static final class ModelCalls implements
            com.objwww.pr.control.alert.domain.agent.RcaModelCallLedger {

        /** 行投影（open 载荷 + 终态） */
        public record CallRow(OpenRow open, String state, UsageOutcome usage,
                String errorCode) {
        }

        private final Map<UUID, CallRow> rows = new LinkedHashMap<>();

        /**
         * EN-04 H04 epoch 闸测试面（runId→现行代际）：生产栅栏由
         * PostgresRcaModelCallLedger 的 SQL 子查询承载，假件经此视图镜像同契约；
         * 不注入 = 无栅栏（存量 R7 测试零感知）。
         */
        public final Map<UUID, Long> currentEpochView = new ConcurrentHashMap<>();

        public synchronized List<CallRow> all() {
            return List.copyOf(rows.values());
        }

        public synchronized CallRow byId(UUID id) {
            return rows.get(id);
        }

        @Override
        public synchronized void open(OpenRow row) {
            Long currentEpoch = currentEpochView.get(row.runId());
            if (row.configEpoch() != null && currentEpoch != null
                    && currentEpoch > row.configEpoch()) {
                throw new com.objwww.pr.control.alert.domain.agent.RcaModelCallFenceException(
                        "动作 configEpoch=" + row.configEpoch() + " 已落后于当前代际 "
                                + currentEpoch + "，零触网拒绝发送");
            }
            boolean dup = rows.values().stream().anyMatch(r ->
                    r.open().runId().equals(row.runId())
                            && r.open().taskId().equals(row.taskId())
                            && r.open().attemptId().equals(row.attemptId())
                            && r.open().actionSeq() == row.actionSeq()
                            && r.open().physicalSeq() == row.physicalSeq());
            if (dup) {
                throw new DuplicateKeyException("uq_rca_model_call_action 模拟");
            }
            rows.put(row.id(), new CallRow(row, "PENDING", null, null));
        }

        @Override
        public synchronized boolean succeed(UUID id, UsageOutcome usage) {
            CallRow current = rows.get(id);
            if (current == null || !"PENDING".equals(current.state())) {
                return false;
            }
            rows.put(id, new CallRow(current.open(), "SUCCESS", usage, null));
            return true;
        }

        @Override
        public synchronized boolean fail(UUID id, String errorCode) {
            CallRow current = rows.get(id);
            if (current == null || !"PENDING".equals(current.state())) {
                return false;
            }
            rows.put(id, new CallRow(current.open(), "FAILED", null, errorCode));
            return true;
        }

        @Override
        public synchronized boolean markUnknown(UUID id) {
            CallRow current = rows.get(id);
            if (current == null || !"PENDING".equals(current.state())) {
                return false;
            }
            rows.put(id, new CallRow(current.open(), "UNKNOWN", null, "TRANSPORT_UNKNOWN"));
            return true;
        }

        @Override
        public synchronized List<UnsettledRow> findUnsettledByRun(UUID runId) {
            return rows.values().stream()
                    .filter(r -> r.open().runId().equals(runId))
                    .filter(r -> "PENDING".equals(r.state()) || "UNKNOWN".equals(r.state()))
                    .sorted(java.util.Comparator.comparing(r -> r.open().actionSeq()))
                    .map(r -> new UnsettledRow(r.open().id(), r.open().taskId(),
                            r.open().actionSeq(), r.open().physicalSeq(), r.state(),
                            r.errorCode()))
                    .toList();
        }

        /**
         * R6/EV-06 读面镜像：已结算行（SUCCESS 带回报 / FAILED / UNKNOWN）→ CallUsage，
         * usage 缺失（usageMissing/FAILED/UNKNOWN）时 tokens/cost 为 null——按 attempt
         * 聚合语义（§6.6 冻结）由消费方 RcaAttemptUsage 承担。
         */
        @Override
        public synchronized List<CallUsage> listSettledUsageByRunId(UUID runId) {
            return rows.values().stream()
                    .filter(r -> r.open().runId().equals(runId))
                    .filter(r -> !"PENDING".equals(r.state()))
                    .sorted(java.util.Comparator.comparing((CallRow r) -> r.open().taskId())
                            .thenComparing(r -> r.open().actionSeq())
                            .thenComparing(r -> r.open().physicalSeq()))
                    .map(r -> {
                        boolean missing = !"SUCCESS".equals(r.state())
                                || r.usage() == null || r.usage().usageMissing();
                        UsageOutcome u = r.usage();
                        return new CallUsage(r.open().attemptId(), r.open().roleId(),
                                r.open().actionSeq(), r.open().physicalSeq(),
                                missing ? null : Integer.valueOf((int) u.promptTokens()),
                                missing ? null : Integer.valueOf((int) u.completionTokens()),
                                missing ? null : Integer.valueOf((int) u.totalTokens()),
                                missing ? null : u.costMicros(),
                                missing ? null : u.pricingVersion(),
                                missing ? null : u.currency(),
                                missing, r.state());
                    })
                    .toList();
        }
    }

    // --------------------------------- R2 输入捕获假件（append-only 同构）

    /** rca_model_input 同构假件：档位钉定 + failure 注入面（捕获写失败零触网测试） */
    public static final class InputCaptures implements
            com.objwww.pr.control.alert.domain.agent.RcaModelInputCapture {

        /** 注入即 capture 抛出（模拟 rca_model_input 不可写） */
        public volatile RuntimeException failure;

        private final com.objwww.pr.control.alert.domain.agent.RcaModelInputCapture.Level level;
        private final List<com.objwww.pr.control.alert.domain.agent.RcaModelInputCapture.CaptureRow>
                captured = new java.util.concurrent.CopyOnWriteArrayList<>();

        public InputCaptures(
                com.objwww.pr.control.alert.domain.agent.RcaModelInputCapture.Level level) {
            this.level = level;
        }

        @Override
        public com.objwww.pr.control.alert.domain.agent.RcaModelInputCapture.Level level() {
            return level;
        }

        @Override
        public void capture(com.objwww.pr.control.alert.domain.agent.RcaModelInputCapture.CaptureRow row) {
            RuntimeException boom = failure;
            if (boom != null) {
                throw boom;
            }
            captured.add(row);
        }

        public List<com.objwww.pr.control.alert.domain.agent.RcaModelInputCapture.CaptureRow> all() {
            return List.copyOf(captured);
        }
    }

    // --------------------------------- R10 工作记忆快照假件（append-only 同构）

    /** rca_working_memory 同构假件：同 (run,task,revision) 冲突返回既有行（MC07/MC08 幂等重放） */
    public static final class WorkingMemories implements
            com.objwww.pr.control.alert.domain.repository.WorkingMemoryPort {

        private final Map<UUID, com.objwww.pr.control.alert.domain.agent.WorkingMemory> rows =
                new LinkedHashMap<>();

        public synchronized List<com.objwww.pr.control.alert.domain.agent.WorkingMemory> all() {
            return List.copyOf(rows.values());
        }

        @Override
        public synchronized com.objwww.pr.control.alert.domain.agent.WorkingMemory append(
                com.objwww.pr.control.alert.domain.agent.WorkingMemory candidate) {
            return rows.values().stream()
                    .filter(r -> r.runId().equals(candidate.runId())
                            && r.taskId().equals(candidate.taskId())
                            && r.checkpointRevision() == candidate.checkpointRevision())
                    .findFirst()
                    .orElseGet(() -> {
                        rows.put(candidate.id(), candidate);
                        return candidate;
                    });
        }

        @Override
        public synchronized java.util.Optional<com.objwww.pr.control.alert.domain.agent.WorkingMemory>
                latestByTask(UUID runId, UUID taskId) {
            return rows.values().stream()
                    .filter(r -> r.runId().equals(runId) && r.taskId().equals(taskId))
                    .max(java.util.Comparator.comparingLong(
                            com.objwww.pr.control.alert.domain.agent.WorkingMemory
                                    ::checkpointRevision));
        }

        @Override
        public synchronized java.util.Optional<com.objwww.pr.control.alert.domain.agent.WorkingMemory>
                findById(UUID id) {
            return java.util.Optional.ofNullable(rows.get(id));
        }
    }

    // --------------------------------- R11 上下文摘要假件（不可变档同构）

    /** rca_context_summary 同构假件：同 (run,task,source) 冲突返回既有行（CAS 提交语义） */
    public static final class ContextSummaries implements
            com.objwww.pr.control.alert.domain.repository.ContextSummaryPort {

        private final Map<UUID, com.objwww.pr.control.alert.domain.agent.ContextSummary>
                rows = new LinkedHashMap<>();

        public synchronized List<com.objwww.pr.control.alert.domain.agent.ContextSummary>
                all() {
            return List.copyOf(rows.values());
        }

        @Override
        public synchronized java.util.Optional<com.objwww.pr.control.alert.domain.agent.ContextSummary>
                findById(UUID id) {
            return java.util.Optional.ofNullable(rows.get(id));
        }

        @Override
        public synchronized com.objwww.pr.control.alert.domain.agent.ContextSummary append(
                com.objwww.pr.control.alert.domain.agent.ContextSummary candidate) {
            return rows.values().stream()
                    .filter(r -> r.runId().equals(candidate.runId())
                            && r.taskId().equals(candidate.taskId())
                            && r.sourceSnapshotDigest()
                                    .equals(candidate.sourceSnapshotDigest()))
                    .findFirst()
                    .orElseGet(() -> {
                        rows.put(candidate.id(), candidate);
                        return candidate;
                    });
        }

        @Override
        public synchronized java.util.Optional<com.objwww.pr.control.alert.domain.agent.ContextSummary>
                findBySource(UUID runId, UUID taskId, String sourceDigest) {
            return rows.values().stream()
                    .filter(r -> r.runId().equals(runId) && r.taskId().equals(taskId)
                            && r.sourceSnapshotDigest().equals(sourceDigest))
                    .findFirst();
        }

        @Override
        public synchronized java.util.Optional<com.objwww.pr.control.alert.domain.agent.ContextSummary>
                latestByTask(UUID runId, UUID taskId) {
            return rows.values().stream()
                    .filter(r -> r.runId().equals(runId) && r.taskId().equals(taskId))
                    .max(java.util.Comparator.comparing(
                            com.objwww.pr.control.alert.domain.agent.ContextSummary
                                    ::createdAt));
        }

        @Override
        public synchronized long countByRun(UUID runId) {
            return rows.values().stream()
                    .filter(r -> r.runId().equals(runId)).count();
        }

        @Override
        public synchronized long countByTask(UUID runId, UUID taskId) {
            return rows.values().stream()
                    .filter(r -> r.runId().equals(runId) && r.taskId().equals(taskId))
                    .count();
        }
    }

    // --------------------------------- CL-07 压缩尝试台账假件（V102 同构）

    /** rca_compaction_attempt 同构假件：逻辑键 insert-if-absent + 状态 CAS 终态化 */
    public static final class CompactionAttempts implements
            com.objwww.pr.control.alert.domain.repository.CompactionAttemptPort {

        public final Map<UUID, com.objwww.pr.control.alert.domain.agent.CompactionAttempt>
                rows = new LinkedHashMap<>();

        @Override
        public synchronized com.objwww.pr.control.alert.domain.agent.CompactionAttempt
                insertIfAbsent(
                        com.objwww.pr.control.alert.domain.agent.CompactionAttempt candidate) {
            java.util.function.Predicate<
                    com.objwww.pr.control.alert.domain.agent.CompactionAttempt> sameKey =
                    r -> r.taskId().equals(candidate.taskId())
                            && r.sourceContextDigest()
                                    .equals(candidate.sourceContextDigest())
                            && r.policyDigest().equals(candidate.policyDigest())
                            && java.util.Objects.equals(r.configEpoch() == null ? -1L
                                    : r.configEpoch(),
                            candidate.configEpoch() == null ? -1L : candidate.configEpoch());
            return rows.values().stream().filter(sameKey).findFirst()
                    .orElseGet(() -> {
                        rows.put(candidate.id(), candidate);
                        return candidate;
                    });
        }

        @Override
        public synchronized boolean casState(UUID id, String fromState, String toState,
                String errorCode, UUID summaryId) {
            com.objwww.pr.control.alert.domain.agent.CompactionAttempt row = rows.get(id);
            if (row == null || !row.state().equals(fromState)) {
                return false;
            }
            rows.put(id, new com.objwww.pr.control.alert.domain.agent.CompactionAttempt(
                    row.id(), row.runId(), row.taskId(), row.sourceContextDigest(),
                    row.policyDigest(), row.owner(), row.leaseEpoch(), row.configEpoch(),
                    row.expectedRevision(), toState, row.logicalActionKey(), errorCode,
                    summaryId, row.createdAt(),
                    "RESERVED".equals(toState) || "IN_FLIGHT".equals(toState)
                            ? null : java.time.Instant.now()));
            return true;
        }

        @Override
        public synchronized java.util.Optional<
                com.objwww.pr.control.alert.domain.agent.CompactionAttempt> findById(UUID id) {
            return java.util.Optional.ofNullable(rows.get(id));
        }
    }

    // ------------------------------------------------------------------ OP-04 report_feedback

    public static final class Feedbacks implements
            com.objwww.pr.control.alert.domain.repository.ReportFeedbackPort {

        public final Map<UUID, com.objwww.pr.control.alert.domain.model.ReportFeedback>
                rows = new LinkedHashMap<>();

        @Override
        public synchronized com.objwww.pr.control.alert.domain.model.ReportFeedback insert(
                com.objwww.pr.control.alert.domain.model.ReportFeedback candidate) {
            // (author, idempotency_key) 幂等面
            for (var row : rows.values()) {
                if (row.author().equals(candidate.author())
                        && row.idempotencyKey().equals(candidate.idempotencyKey())) {
                    return row;
                }
            }
            // uq(supersedes_id)：同一前序只允许一条更正
            if (candidate.supersedesId() != null) {
                for (var row : rows.values()) {
                    if (candidate.supersedesId().equals(row.supersedesId())) {
                        return row;
                    }
                }
            }
            rows.put(candidate.id(), candidate);
            return candidate;
        }

        @Override
        public synchronized List<
                com.objwww.pr.control.alert.domain.model.ReportFeedback> findByReportId(
                        UUID reportId) {
            return rows.values().stream()
                    .filter(r -> r.reportId().equals(reportId)).toList();
        }

        @Override
        public synchronized Optional<
                com.objwww.pr.control.alert.domain.model.ReportFeedback> findById(UUID id) {
            return Optional.ofNullable(rows.get(id));
        }
    }

    // ------------------------------------------------------------------ OP-01 regression candidate

    public static final class RegressionCandidates implements
            com.objwww.pr.control.eval.domain.repository.RegressionCandidatePort {

        public final Map<UUID, com.objwww.pr.control.eval.domain.model.RegressionCandidate>
                rows = new LinkedHashMap<>();
        public final Map<UUID, com.objwww.pr.control.eval.domain.model.RegressionReview>
                reviewRows = new LinkedHashMap<>();

        @Override
        public synchronized com.objwww.pr.control.eval.domain.model.RegressionCandidate
                insertIfAbsent(
                        com.objwww.pr.control.eval.domain.model.RegressionCandidate candidate) {
            for (var row : rows.values()) {
                if (row.sourceDigest().equals(candidate.sourceDigest())
                        && row.caseKey().equals(candidate.caseKey())) {
                    return row;
                }
            }
            rows.put(candidate.id(), candidate);
            return candidate;
        }

        @Override
        public synchronized Optional<
                com.objwww.pr.control.eval.domain.model.RegressionCandidate> casState(
                        UUID id, String fromState,
                        com.objwww.pr.control.eval.domain.model.RegressionCandidate next) {
            var row = rows.get(id);
            if (row == null || !row.state().equals(fromState)) {
                return Optional.empty();
            }
            rows.put(id, next);
            return Optional.of(next);
        }

        @Override
        public synchronized Optional<
                com.objwww.pr.control.eval.domain.model.RegressionCandidate> findById(
                        UUID id) {
            return Optional.ofNullable(rows.get(id));
        }

        @Override
        public synchronized List<
                com.objwww.pr.control.eval.domain.model.RegressionCandidate> findByState(
                        String state) {
            return rows.values().stream()
                    .filter(r -> r.state().equals(state)).toList();
        }

        @Override
        public synchronized com.objwww.pr.control.eval.domain.model.RegressionReview
                insertReview(
                        com.objwww.pr.control.eval.domain.model.RegressionReview review) {
            for (var row : reviewRows.values()) {
                if (row.candidateId().equals(review.candidateId())
                        && row.reviewer().equals(review.reviewer())) {
                    return row;
                }
            }
            reviewRows.put(review.id(), review);
            return review;
        }

        @Override
        public synchronized List<
                com.objwww.pr.control.eval.domain.model.RegressionReview> reviewsOf(
                        UUID candidateId) {
            return reviewRows.values().stream()
                    .filter(r -> r.candidateId().equals(candidateId)).toList();
        }
    }

    // ------------------------------------------------------------------ AM4 evidence（OP-01 来源指纹输入）

    public static final class Evidences implements
            com.objwww.pr.control.alert.domain.evidence.EvidenceRepository {

        public final Map<UUID,
                com.objwww.pr.control.alert.domain.evidence.EvidenceEnvelope> rows =
                new LinkedHashMap<>();

        @Override
        public synchronized void insert(
                com.objwww.pr.control.alert.domain.evidence.EvidenceEnvelope envelope) {
            rows.put(envelope.evidenceId(), envelope);
        }

        @Override
        public synchronized Optional<
                com.objwww.pr.control.alert.domain.evidence.EvidenceEnvelope> findById(
                        UUID evidenceId) {
            return Optional.ofNullable(rows.get(evidenceId));
        }

        @Override
        public synchronized List<
                com.objwww.pr.control.alert.domain.evidence.EvidenceEnvelope> findByRunId(
                        UUID runId) {
            return rows.values().stream()
                    .filter(e -> e.runId().equals(runId)).toList();
        }
    }

    // ------------------------------------------------------------------ OP-03 rca_action_assessment

    public static final class ActionAssessments implements
            com.objwww.pr.control.ops.domain.repository.ActionAssessmentPort {

        public final Map<UUID, com.objwww.pr.control.ops.domain.model.ActionAssessment>
                rows = new LinkedHashMap<>();

        @Override
        public synchronized com.objwww.pr.control.ops.domain.model.ActionAssessment
                insertIfAbsent(
                        com.objwww.pr.control.ops.domain.model.ActionAssessment candidate) {
            for (var row : rows.values()) {
                if (row.runId().equals(candidate.runId())
                        && row.logicalActionKey().equals(candidate.logicalActionKey())
                        && row.assessorVersion().equals(candidate.assessorVersion())
                        && row.evidenceSnapshotDigest()
                                .equals(candidate.evidenceSnapshotDigest())) {
                    return row;
                }
            }
            rows.put(candidate.id(), candidate);
            return candidate;
        }

        @Override
        public synchronized List<
                com.objwww.pr.control.ops.domain.model.ActionAssessment> findByRun(
                        UUID runId) {
            return rows.values().stream()
                    .filter(r -> r.runId().equals(runId)).toList();
        }
    }

    // ------------------------------------------------------------------ AM5 dataset_version（OP-01 materialize 落点）

    public static final class Datasets implements
            com.objwww.pr.control.eval.domain.repository.DatasetVersionRepository {

        public final Map<UUID, com.objwww.pr.control.eval.domain.model.DatasetVersion>
                datasetRows = new LinkedHashMap<>();
        public final Map<UUID, com.objwww.pr.control.eval.domain.model.CaseVersion>
                caseRows = new LinkedHashMap<>();

        @Override
        public synchronized void insertDatasetVersion(
                com.objwww.pr.control.eval.domain.model.DatasetVersion version) {
            for (var row : datasetRows.values()) {
                if (row.name().equals(version.name())
                        && row.version().equals(version.version())) {
                    throw new org.springframework.dao.DuplicateKeyException(
                            "uq(dataset name,version): " + version.name());
                }
            }
            datasetRows.put(version.id(), version);
        }

        @Override
        public synchronized boolean insertCaseVersion(
                com.objwww.pr.control.eval.domain.model.CaseVersion version) {
            for (var row : caseRows.values()) {
                if (row.datasetVersionId().equals(version.datasetVersionId())
                        && row.caseKey().equals(version.caseKey())) {
                    return false;
                }
            }
            caseRows.put(version.id(), version);
            return true;
        }

        @Override
        public synchronized Optional<
                com.objwww.pr.control.eval.domain.model.DatasetVersion> findDataset(
                        String name, String version) {
            return datasetRows.values().stream()
                    .filter(d -> d.name().equals(name) && d.version().equals(version))
                    .findFirst();
        }

        @Override
        public synchronized List<com.objwww.pr.control.eval.domain.model.CaseVersion>
                findCasesValidAt(UUID datasetVersionId, Instant at) {
            return caseRows.values().stream()
                    .filter(c -> c.datasetVersionId().equals(datasetVersionId))
                    .filter(c -> !c.validFrom().isAfter(at)
                            && (c.validTo() == null || c.validTo().isAfter(at)))
                    .toList();
        }
    }
}
