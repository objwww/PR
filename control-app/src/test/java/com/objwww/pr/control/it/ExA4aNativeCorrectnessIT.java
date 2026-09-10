package com.objwww.pr.control.it;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.objwww.pr.control.alert.application.agent.NativeRcaAgent;
import com.objwww.pr.control.alert.domain.claim.ClaimKind;
import com.objwww.pr.control.alert.domain.claim.ClaimProjection;
import com.objwww.pr.control.alert.domain.claim.ClaimReducer;
import com.objwww.pr.control.alert.domain.claim.ClaimStatus;
import com.objwww.pr.control.alert.domain.claim.ClaimStore;
import com.objwww.pr.control.alert.domain.claim.ClaimVerdict;
import com.objwww.pr.control.alert.domain.claim.EvidenceBasis;
import com.objwww.pr.control.alert.domain.evidence.EvidenceEnvelope;
import com.objwww.pr.control.alert.domain.evidence.EvidenceSnapshotRepository;
import com.objwww.pr.control.alert.domain.identity.EvidenceSnapshotDigest;
import com.objwww.pr.control.alert.domain.model.Incident;
import com.objwww.pr.control.alert.domain.model.IncidentStatus;
import com.objwww.pr.control.alert.domain.model.RcaAttempt;
import com.objwww.pr.control.alert.domain.model.RcaAttemptStatus;
import com.objwww.pr.control.alert.domain.model.RcaEngine;
import com.objwww.pr.control.alert.domain.model.RcaRun;
import com.objwww.pr.control.alert.domain.model.RcaRunRouting;
import com.objwww.pr.control.alert.domain.model.RcaRunState;
import com.objwww.pr.control.alert.domain.model.RcaTask;
import com.objwww.pr.control.alert.domain.model.RcaTaskState;
import com.objwww.pr.control.alert.domain.model.RunTrigger;
import com.objwww.pr.control.alert.domain.tool.RcaToolInvocationLedger;
import com.objwww.pr.control.alert.domain.tool.ToolInvocationState;
import com.objwww.pr.control.alert.domain.tool.ToolReasonCode;
import com.objwww.pr.control.infrastructure.persistence.PostgresClaimStore;
import com.objwww.pr.control.infrastructure.persistence.PostgresEvidenceRepository;
import com.objwww.pr.control.infrastructure.persistence.PostgresEvidenceSnapshotRepository;
import com.objwww.pr.control.infrastructure.persistence.PostgresIncidentRepository;
import com.objwww.pr.control.infrastructure.persistence.PostgresRcaAttemptRepository;
import com.objwww.pr.control.infrastructure.persistence.PostgresRcaEventAppender;
import com.objwww.pr.control.infrastructure.persistence.PostgresRcaRunRepository;
import com.objwww.pr.control.infrastructure.persistence.PostgresRcaTaskRepository;
import com.objwww.pr.control.infrastructure.persistence.PostgresRcaToolInvocationLedger;
import com.objwww.pr.shared.Digest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * EX-A4a 必要正确性 IT（L1 真 PG；本机无 docker 自动跳过，195 部署段实跑）：
 *
 * <p>F05 黑板契约——agent 只读冻结成员表：成员精确读取 + 双源归并、迟到证据
 * 结构性不可入黑板、旧快照不可追加成员（P1-05 freeze 幂等面）、成员行身份
 * 篡改 = 显式失败（不静默跳过）。
 *
 * <p>F13 活跃集语义——REPORTING 属活跃集：repo 活跃查询可见 + uq 活跃索引
 * 拒绝同 incident 第二活跃 run（23505 面与 Java/SQL/V12 三面统一）。
 *
 * <p>F16 调用账本——action_seq=open 时 call_seq 锚定、attempt_id=驱动方持久
 * attempt、悬挂 PENDING 单语句回收 → UNKNOWN/TRANSPORT_UNKNOWN（崩溃孤儿回执
 * 诚实归档），宽限内 PENDING 不动。
 *
 * <p>F06 四类型存储契约——kind 列落库/修订随写、compat 缺省 HYPOTHESIS 落库、
 * ck_rca_claim_kind 拒绝域外值。
 */
class ExA4aNativeCorrectnessIT extends PostgresITBase {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String POLICY = "policy-exa4a";
    private static final String BLACKBOARD_DIGEST =
            Digest.sha256Of("exa4a-blackboard").hex();

    private PostgresRcaRunRepository runs;
    private PostgresRcaTaskRepository tasks;
    private PostgresIncidentRepository incidents;
    private PostgresEvidenceRepository evidence;
    private PostgresEvidenceSnapshotRepository snapshots;
    private ClaimStore claims;
    private PostgresRcaToolInvocationLedger ledger;
    private JdbcClient jdbc;

    @BeforeEach
    void setUp() {
        jdbc = JdbcClient.create(controlDataSource());
        runs = new PostgresRcaRunRepository(jdbc);
        tasks = new PostgresRcaTaskRepository(jdbc);
        incidents = new PostgresIncidentRepository(jdbc);
        evidence = new PostgresEvidenceRepository(jdbc, controlTx, MAPPER);
        snapshots = new PostgresEvidenceSnapshotRepository(jdbc, controlTx);
        claims = claimStore();
        ledger = new PostgresRcaToolInvocationLedger(jdbc, controlTx);
    }

    // --------------------------------------------- F05：黑板 = 冻结成员表

    @Test
    void f05_blackboardReadsOnlyFrozenMembersAndRefreezeAppendsNothing() {
        UUID incidentId = insertIncident("f05");
        UUID runId = castNativeRun(incidentId);
        EvidenceEnvelope first = annotated(runId, "prometheus", "源一");
        EvidenceEnvelope second = annotated(runId, "logs", "源二");
        evidence.insert(first);
        evidence.insert(second);

        UUID snapshotId = UUID.randomUUID();
        assertThat(snapshots.freeze(snapshot(snapshotId, runId),
                membersOf(first, second))).isTrue();

        // P1-05：冻结后迟到证据不可入黑板——重冻结同 (run,digest) 幂等跳过，
        // 成员表不追加第三行
        EvidenceEnvelope late = annotated(runId, "change", "冻结后迟到");
        evidence.insert(late);
        assertThat(snapshots.freeze(snapshot(snapshotId, runId),
                membersOf(first, second, late)))
                .as("同 (run,digest) 重冻结 = 幂等跳过（旧快照不可追加成员）").isFalse();
        assertThat(snapshots.membersOf(snapshotId)).hasSize(2);
        assertThat(adminJdbc.sql("SELECT count(*) FROM rca_evidence_snapshot "
                + "WHERE run_id = :run").param("run", runId).query(Long.class).single())
                .isEqualTo(1);

        // 成员精确读取 + Reducer 双源佐证归并 → 1 条裁决；kind 走 compat 缺省
        NativeRcaAgent agent = new NativeRcaAgent(evidence, snapshots, claims,
                new ClaimReducer(java.util.Set.of(), POLICY));
        NativeRcaAgent.NativeResult result = agent.investigate(runId, null,
                new EvidenceSnapshotDigest(BLACKBOARD_DIGEST), 0L);

        assertThat(result.verdicts()).hasSize(1);
        assertThat(result.verdicts().get(0).kind()).isEqualTo(ClaimKind.HYPOTHESIS);
        assertThat(claims.findByRunId(runId)).hasSize(1);
        assertThat(adminJdbc.sql("SELECT kind FROM rca_claim WHERE run_id = :run")
                .param("run", runId).query(String.class).single())
                .as("F06：compat 缺省 HYPOTHESIS 落库").isEqualTo("HYPOTHESIS");
    }

    @Test
    void f05_memberIdentityTamperFailsClosedExplicitly() {
        UUID incidentId = insertIncident("f05b");
        UUID runId = castNativeRun(incidentId);
        EvidenceEnvelope first = annotated(runId, "prometheus", "源一");
        EvidenceEnvelope second = annotated(runId, "logs", "源二");
        evidence.insert(first);
        evidence.insert(second);
        UUID snapshotId = UUID.randomUUID();
        assertThat(snapshots.freeze(snapshot(snapshotId, runId),
                membersOf(first, second))).isTrue();

        // 成员行 payload_digest 篡改（evidence 原行不动）→ 成员↔证据身份断裂
        adminJdbc.sql("UPDATE rca_snapshot_member SET payload_digest = :tampered "
                        + "WHERE snapshot_id = :sid AND evidence_id = :eid")
                .param("tampered", Digest.sha256Of("tampered").hex())
                .param("sid", snapshotId).param("eid", first.evidenceId()).update();

        NativeRcaAgent agent = new NativeRcaAgent(evidence, snapshots, claims,
                new ClaimReducer(java.util.Set.of(), POLICY));
        assertThatThrownBy(() -> agent.investigate(runId, null,
                new EvidenceSnapshotDigest(BLACKBOARD_DIGEST), 0L))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("身份不符");
        assertThat(claims.findByRunId(runId)).as("fail-closed 零断言落库").isEmpty();
    }

    // --------------------------------------------- F13：REPORTING ∈ 活跃集

    @Test
    void f13_reportingRunIsActiveAndIndexRejectsSecondActive() {
        UUID incidentId = insertIncident("f13");
        UUID runId = castNativeRun(incidentId);
        adminJdbc.sql("UPDATE rca_run SET state = 'REPORTING' WHERE id = :id")
                .param("id", runId).update();

        // F13 修复面：REPORTING 在 repo 活跃查询可见（修复前 SQL 漏 REPORTING → 不可见）
        assertThat(runs.findActiveByIncidentId(incidentId))
                .hasValueSatisfying(run -> assertThat(run.state())
                        .isEqualTo(RcaRunState.REPORTING));

        // V12 uq_rca_run_active_incident 谓词与 Java/SQL 统一：REPORTING 期间
        // 第二活跃 run 在索引面被拒（23505），不是投影面才撞
        RcaRun intruder = new RcaRun(UUID.randomUUID(), incidentId, 1, RunTrigger.RERUN,
                RcaRunState.QUEUED, Digest.sha256Of("intruder"), Instant.now(),
                Instant.now(), null, null, null);
        assertThatThrownBy(() -> runs.insertRouted(intruder,
                new RcaRunRouting(RcaEngine.NATIVE, Digest.sha256Of("bundle"),
                        "alertname=HighErrorRate|service=f13", 42, "BUCKETED_NATIVE")))
                .as("REPORTING 占住活跃位 → uq 索引拒绝第二活跃 run")
                .isInstanceOf(DuplicateKeyException.class);

        // run 终态后活跃位释放，新 run 可铸（活跃集收口不泄漏）
        adminJdbc.sql("UPDATE rca_run SET state = 'FAILED', finished_at = now() "
                        + "WHERE id = :id").param("id", runId).update();
        assertThat(runs.findActiveByIncidentId(incidentId)).isEmpty();
        runs.insertRouted(new RcaRun(UUID.randomUUID(), incidentId, 2, RunTrigger.RERUN,
                        RcaRunState.QUEUED, Digest.sha256Of("after"), Instant.now(),
                        Instant.now(), null, null, null),
                new RcaRunRouting(RcaEngine.NATIVE, Digest.sha256Of("bundle"),
                        "alertname=HighErrorRate|service=f13", 42, "BUCKETED_NATIVE"));
        assertThat(runs.findActiveByIncidentId(incidentId)).isPresent();
    }

    // --------------------------------------------- F16：调用账本锚定与回收

    @Test
    void f16_ledgerAnchorsActionSeqAttemptAndReclaimsStalePending() {
        // FK 面：账本行引用 run/task/attempt——真 PG 上必须先落真实三件（假件无 FK 不可见）
        UUID incidentId = insertIncident("f16");
        UUID runId = castNativeRun(incidentId);
        UUID taskId = tasks.findByRunId(runId).get(0).id();
        RcaAttempt attempt = new RcaAttempt(UUID.randomUUID(), taskId, 1, 0, "it-worker",
                RcaAttemptStatus.STARTED, null, null, null, Instant.now(), null, null);
        new PostgresRcaAttemptRepository(jdbc).insert(attempt);
        UUID attemptId = attempt.id();

        UUID stale = UUID.randomUUID();
        ledger.open(new RcaToolInvocationLedger.InvocationIdentity(stale, runId, taskId,
                attemptId, 7, "prometheus.query", "1",
                Digest.sha256Of("action").hex()));

        Map<String, Object> row = adminJdbc.sql("""
                        SELECT state, call_seq, action_seq, attempt_id
                          FROM rca_tool_invocation WHERE id = :id
                        """).param("id", stale)
                .query((rs, i) -> Map.<String, Object>of(
                        "state", rs.getString("state"),
                        "call_seq", rs.getLong("call_seq"),
                        "action_seq", rs.getLong("action_seq"),
                        "attempt_id", rs.getObject("attempt_id", UUID.class))).single();
        assertThat(row.get("state")).isEqualTo("PENDING");
        assertThat(row.get("call_seq")).isEqualTo(7L);
        assertThat(row.get("action_seq")).as("open 时 action_seq=call_seq 锚定")
                .isEqualTo(7L);
        assertThat(row.get("attempt_id")).as("attempt = 驱动方持久 attempt")
                .isEqualTo(attemptId);

        // 悬挂回收：宽限外 PENDING → UNKNOWN/TRANSPORT_UNKNOWN（孤儿回执诚实归档）
        adminJdbc.sql("UPDATE rca_tool_invocation SET started_at = started_at "
                + "- interval '1 hour' WHERE id = :id").param("id", stale).update();
        java.time.Instant cutoff = Instant.now().minusSeconds(1_800);
        assertThat(ledger.reclaimPendingOlderThan(cutoff)).isEqualTo(1);
        assertThat(adminJdbc.sql("""
                        SELECT state, reason_code, settled_at FROM rca_tool_invocation
                         WHERE id = :id
                        """).param("id", stale)
                .query((rs, i) -> rs.getString("state") + "|"
                        + rs.getString("reason_code") + "|"
                        + (rs.getTimestamp("settled_at") != null)).single())
                .isEqualTo("UNKNOWN|TRANSPORT_UNKNOWN|true");

        // 宽限内在途 PENDING 不动，且 CAS 终态通路仍开放（回收不封死正常回执）
        UUID fresh = UUID.randomUUID();
        ledger.open(new RcaToolInvocationLedger.InvocationIdentity(fresh, runId, taskId,
                attemptId, 8, "prometheus.query", "1",
                Digest.sha256Of("action2").hex()));
        assertThat(ledger.reclaimPendingOlderThan(cutoff))
                .as("宽限内在途不被误杀").isZero();
        assertThat(ledger.succeed(fresh)).isTrue();
        assertThat(adminJdbc.sql("SELECT state FROM rca_tool_invocation WHERE id = :id")
                .param("id", fresh).query(String.class).single()).isEqualTo("SUCCESS");
    }

    // --------------------------------------------- F06：kind 存储契约

    @Test
    void f06_claimKindRoundTripsReviseAndCheckConstraint() {
        UUID incidentId = insertIncident("f06");
        UUID runId = castNativeRun(incidentId);

        ClaimVerdict rootCause = new ClaimVerdict("k-root", "svc-a", "2026-09-05/1h", 0L,
                BLACKBOARD_DIGEST, ClaimStatus.TRUE, EvidenceBasis.SINGLE_SOURCE,
                List.of("prometheus"), "因果机制成立", List.of("ev-1"), POLICY,
                ClaimKind.ROOT_CAUSE);
        assertThat(claims.append(runId, rootCause).outcome())
                .isEqualTo(ClaimProjection.Outcome.CREATED);
        assertThat(kindOf(runId, "k-root")).isEqualTo("ROOT_CAUSE");

        // 11 参 compat（存量调用面）→ HYPOTHESIS 落库
        claims.append(runId, new ClaimVerdict("k-hyp", "svc-a", "2026-09-05/1h", 0L,
                BLACKBOARD_DIGEST, ClaimStatus.UNKNOWN, EvidenceBasis.SINGLE_SOURCE,
                List.of("logs"), "待验证", List.of("ev-2"), POLICY));
        assertThat(kindOf(runId, "k-hyp")).isEqualTo("HYPOTHESIS");

        // 同身份异内容 → REVISED：修订 UPDATE 随写 kind（k-root → EXCLUSION）
        ClaimStore.ClaimAppendResult revised = claims.append(runId, new ClaimVerdict(
                "k-root", "svc-a", "2026-09-05/1h", 0L, BLACKBOARD_DIGEST,
                ClaimStatus.TRUE, EvidenceBasis.SINGLE_SOURCE, List.of("prometheus"),
                "复核后改判排除", List.of("ev-1"), POLICY, ClaimKind.EXCLUSION));
        assertThat(revised.outcome()).isEqualTo(ClaimProjection.Outcome.REVISED);
        assertThat(kindOf(runId, "k-root")).isEqualTo("EXCLUSION");

        // ck_rca_claim_kind：域外值在 DB 约束面被拒
        assertThatThrownBy(() -> adminJdbc.sql(
                        "UPDATE rca_claim SET kind = 'BOGUS' WHERE run_id = :run")
                .param("run", runId).update())
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    private String kindOf(UUID runId, String claimKey) {
        return adminJdbc.sql("SELECT kind FROM rca_claim WHERE run_id = :run "
                        + "AND claim_key = :key").param("run", runId)
                .param("key", claimKey).query(String.class).single();
    }

    // ------------------------------------------------------------------ 种子

    private EvidenceSnapshotRepository.FrozenSnapshot snapshot(UUID snapshotId,
            UUID runId) {
        return new EvidenceSnapshotRepository.FrozenSnapshot(snapshotId, runId,
                BLACKBOARD_DIGEST, 0L, "it-config", "it-tools", null);
    }

    private List<EvidenceSnapshotRepository.SnapshotMemberRow> membersOf(
            EvidenceEnvelope... envelopes) {
        return java.util.Arrays.stream(envelopes)
                .map(e -> new EvidenceSnapshotRepository.SnapshotMemberRow(
                        e.evidenceId(), e.evidenceType(), e.payloadDigest()))
                .toList();
    }

    /** 双源断言注记证据（同 claim_key 异 source → Reducer 双源佐证归并面） */
    private EvidenceEnvelope annotated(UUID runId, String source, String reason) {
        Map<String, Object> scope = new LinkedHashMap<>();
        scope.put("claim_key", "cpu_saturation");
        scope.put("claim_status", "TRUE");
        scope.put("reason", reason);
        scope.put("scope", "svc-a");
        scope.put("time_range", "09:50/10:00");
        return EvidenceEnvelope.create(UUID.randomUUID(), runId, UUID.randomUUID(),
                "metrics.query_range", EvidenceEnvelope.SCHEMA_VERSION, 0L, source,
                scope, null, null,
                Map.of("status", "success", "data", Map.of("result", List.of("x"))));
    }

    private UUID insertIncident(String tag) {
        UUID id = UUID.randomUUID();
        Instant now = Instant.now();
        incidents.insert(new Incident(id, "alertname=HighErrorRate|service=" + tag,
                IncidentStatus.FIRING, 0, now.minus(Duration.ofMinutes(5)),
                now.minus(Duration.ofMinutes(5)), null, null, null, 0, 0, 0, null,
                now.minus(Duration.ofMinutes(5)), now.minus(Duration.ofMinutes(5)),
                now, now));
        return id;
    }

    /** 铸 NATIVE run + driver 任务（生产铸造点形态；F05/F06 的 run/claim FK 依赖） */
    private UUID castNativeRun(UUID incidentId) {
        UUID runId = UUID.randomUUID();
        Instant now = Instant.now();
        runs.insertRouted(new RcaRun(runId, incidentId, 0, RunTrigger.INITIAL,
                        RcaRunState.QUEUED, Digest.sha256Of("material-" + runId), now,
                        now, null, null, null),
                new RcaRunRouting(RcaEngine.NATIVE, Digest.sha256Of("bundle"),
                        "alertname=HighErrorRate|service=checkout", 42,
                        "BUCKETED_NATIVE"));
        tasks.insert(new RcaTask(UUID.randomUUID(), runId,
                RcaTask.taskKeyFor(RcaEngine.NATIVE), RcaTaskState.READY, 5, now, now,
                now.plusSeconds(600), null, null, 0, 0, 3, now, now));
        return runId;
    }

    private ClaimStore claimStore() {
        PostgresRcaEventAppender events = new PostgresRcaEventAppender(
                JdbcClient.create(controlDataSource()), controlTx,
                new TransactionTemplate(
                        new DataSourceTransactionManager(controlDataSource())));
        return new PostgresClaimStore(JdbcClient.create(controlDataSource()), controlTx,
                MAPPER, events);
    }
}
