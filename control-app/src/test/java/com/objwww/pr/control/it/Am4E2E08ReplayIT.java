package com.objwww.pr.control.it;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.objwww.pr.control.alert.application.agent.ChangeAgent;
import com.objwww.pr.control.alert.application.agent.LogsAgent;
import com.objwww.pr.control.alert.application.agent.MetricsAgent;
import com.objwww.pr.control.alert.application.agent.NativeRcaAgent;
import com.objwww.pr.control.alert.application.replay.AgentReplayRunner;
import com.objwww.pr.control.alert.application.tool.ReplayToolGateway;
import com.objwww.pr.control.alert.application.tool.ToolExecutor;
import com.objwww.pr.control.alert.application.tool.ToolGateway;
import com.objwww.pr.control.alert.application.tool.ToolRegistry;
import com.objwww.pr.control.alert.domain.agent.AgentProfile;
import com.objwww.pr.control.alert.domain.budget.BudgetKind;
import com.objwww.pr.control.alert.domain.claim.ClaimReducer;
import com.objwww.pr.control.alert.domain.claim.ClaimStore;
import com.objwww.pr.control.alert.domain.claim.ClaimVerdict;
import com.objwww.pr.control.alert.domain.evidence.EvidenceRepository;
import com.objwww.pr.control.alert.domain.event.RcaEventAppender;
import com.objwww.pr.control.alert.domain.tool.RcaToolInvocationLedger;
import com.objwww.pr.control.alert.domain.tool.ToolControlPlaneException;
import com.objwww.pr.control.alert.domain.tool.ToolDefinition;
import com.objwww.pr.control.alert.domain.tool.ToolModelVisibleException;
import com.objwww.pr.control.alert.domain.tool.ToolReplayStore;
import com.objwww.pr.control.infrastructure.persistence.PostgresClaimStore;
import com.objwww.pr.control.infrastructure.persistence.PostgresEvidenceRepository;
import com.objwww.pr.control.infrastructure.persistence.PostgresRcaEventAppender;
import com.objwww.pr.control.infrastructure.persistence.PostgresRcaToolInvocationLedger;
import com.objwww.pr.control.infrastructure.persistence.PostgresToolReplayStore;
import com.objwww.pr.shared.Digest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * E2E-M4-08 精确回放对拍（M4-32/33 随件交付，REPLAY_ONLY——技术方案 §15.3/§15.4，
 * 不要求 195 部署栈；Testcontainers 真 PG）。三案语义：
 * <ul>
 *   <li>同一冻结 Snapshot 连续回放两轮：全字段相同 → 双轮 REPLAY_HIT、语义结果
 *       （证据 payloadDigest 序列 + Claim verdict）逐项一致；</li>
 *   <li>任一匹配字段（tool/version/args/timeRange/snapshot）变化 → REPLAY_MISS
 *       显式失败，绝不回退实时查询；</li>
 *   <li>零副作用：回放全程真实工具执行器零调用、回放账本零污染。</li>
 * </ul>
 * 扰动矩阵的穷举语义在 ReplayToolGatewayTest（UT）；本类证 PG 账本 + PG 证据/断言
 * 仓储 + 真实组件组装的全链对拍。
 */
class Am4E2E08ReplayIT extends PostgresITBase {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final byte[] FIXTURE =
            "{\"status\":\"success\",\"data\":{\"result\":[\"e08\"]}}"
                    .getBytes(StandardCharsets.UTF_8);
    private static final String SNAPSHOT =
            Digest.sha256Of("e08-frozen-snapshot").value();
    private static final String TIME_RANGE =
            "2026-09-05T07:50:00Z/2026-09-05T08:00:00Z";
    private static final long GENERATION = 0L;
    private static final long TOOL_TIMEOUT_MILLIS = 4_000L;
    private static final long TOOL_RESULT_LIMIT = 65_536L;
    private static final String POLICY_VERSION = "e08-policy";

    private PostgresToolReplayStore replayStore;
    private CountingExecutor executor;
    private ToolRegistry registry;
    private EvidenceRepository evidence;
    private ClaimStore claims;

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
                    run_budget_state, run_budget_entry, incident_budget_entry, rca_event,
                    rca_tool_invocation, rca_evidence, rca_evidence_snapshot,
                    rca_snapshot_member, rca_claim, rca_tool_replay
                RESTART IDENTITY CASCADE
                """).update();
        replayStore = new PostgresToolReplayStore(controlJdbc);
        executor = new CountingExecutor(FIXTURE);
        registry = new ToolRegistry(List.of(
                new ToolRegistry.Registration(
                        MetricsAgent.toolDefinition(TOOL_TIMEOUT_MILLIS, TOOL_RESULT_LIMIT),
                        executor),
                new ToolRegistry.Registration(
                        LogsAgent.toolDefinition(TOOL_TIMEOUT_MILLIS, TOOL_RESULT_LIMIT),
                        executor),
                new ToolRegistry.Registration(
                        ChangeAgent.toolDefinition(TOOL_TIMEOUT_MILLIS, TOOL_RESULT_LIMIT),
                        executor)));
        evidence = new PostgresEvidenceRepository(controlJdbc, controlTx, MAPPER);
        claims = new PostgresClaimStore(controlJdbc, controlTx, MAPPER, eventAppender());
    }

    @Test
    void itE08_1_两轮相同输入全命中且语义结果一致() {
        recordOnce();
        UUID runA = seedRun();
        UUID runB = seedRun();

        RoundResult roundA = replayRound(runA);
        RoundResult roundB = replayRound(runB);

        assertThat(roundA.runner().stats().hits()).isEqualTo(3);
        assertThat(roundA.runner().stats().misses()).isZero();
        assertThat(roundB.runner().stats().hits()).isEqualTo(3);
        assertThat(roundB.runner().stats().misses()).isZero();

        // 语义对拍：证据类型序列 + payload digest 逐项一致；Claim verdict 全字段一致
        // （fixture 证据无 claim_key 注记，verdicts 均为空也是确定性输出；
        //   注记 → 归并的对拍由 Am4ShadowFullChainG2Test 覆盖）
        List<String> signaturesA = signatures(runA);
        assertThat(signaturesA).hasSize(3);
        assertThat(signatures(runB)).isEqualTo(signaturesA);
        assertThat(roundB.verdicts()).isEqualTo(roundA.verdicts());

        // 零实时回退：两轮回放全程真实执行器零调用（结构保证的行为取证）
        assertThat(executor.calls).isZero();
    }

    @Test
    void itE08_2_任一匹配字段变化即MISS且零回退零账本污染() {
        recordOnce();
        long ledgerRowsBefore = count("rca_tool_replay");
        AgentReplayRunner runner = new AgentReplayRunner(
                new ReplayToolGateway(registry, replayStore));

        for (ToolGateway.ToolInvocation perturbed : perturbations()) {
            assertThatThrownBy(() -> runner.invoke(perturbed))
                    .isInstanceOf(ToolModelVisibleException.class)
                    .hasMessageContaining("REPLAY_MISS");
        }
        assertThat(runner.stats().misses()).isEqualTo(4);

        // toolVersion 轴特例："2" 不在 registry 白名单，digest 判定前即被
        // UNKNOWN_TOOL 拒绝（同样零执行）——拒绝面语义不同，单独断言
        UUID runId = seedRun();
        Map<String, Object> baseArgs = LogsAgent.argsOf(
                new LogsAgent.LogsQuery("1757059200", "1757059260"));
        assertThatThrownBy(() -> runner.invoke(invocation(runId, LogsAgent.TOOL_NAME,
                "2", TIME_RANGE, baseArgs, SNAPSHOT)))
                .isInstanceOf(ToolControlPlaneException.class)
                .hasMessageContaining("UNKNOWN_TOOL");

        assertThat(executor.calls).isZero();
        assertThat(count("rca_tool_replay")).isEqualTo(ledgerRowsBefore);
    }

    // ------------------------------------------------------------------ 组装

    /** 录制一遍三工具（回放账本的种子；录制不触执行器——record 直接写账本） */
    private void recordOnce() {
        ReplayToolGateway gateway = new ReplayToolGateway(registry, replayStore);
        UUID runId = seedRun();
        gateway.record(metricsInvocation(runId), FIXTURE);
        gateway.record(logsInvocation(runId, LogsAgent.TOOL_NAME), FIXTURE);
        // change 键的 args 与 logs 键刻意错开（until=…270）：否则扰动矩阵的 tool 名轴
        // （change + logs 同 schema args）会与 change 录制键完全同键 → HIT 而非 REPLAY_MISS
        gateway.record(invocation(runId, ChangeAgent.TOOL_NAME, ChangeAgent.TOOL_VERSION,
                TIME_RANGE, LogsAgent.argsOf(
                        new LogsAgent.LogsQuery("1757059200", "1757059270")), SNAPSHOT),
                FIXTURE);
    }

    /** 一轮完整回放：三 Agent 产证据 + Native RCA 出 Claim */
    private RoundResult replayRound(UUID runId) {
        AgentReplayRunner runner = new AgentReplayRunner(
                new ReplayToolGateway(registry, replayStore));
        RcaToolInvocationLedger ledger =
                new PostgresRcaToolInvocationLedger(controlJdbc, controlTx);
        MetricsAgent metrics = new MetricsAgent(metricsProfile(), registry, runner,
                evidence, ledger, MAPPER);
        LogsAgent logs = new LogsAgent(logsProfile(), registry, runner,
                evidence, ledger, MAPPER);
        ChangeAgent change = new ChangeAgent(changeProfile(), registry, runner,
                evidence, ledger, MAPPER);

        MetricsAgent.CallContext ctx = new MetricsAgent.CallContext(
                runId, UUID.randomUUID(), UUID.randomUUID(), 1L, GENERATION,
                SNAPSHOT, TIME_RANGE);
        metrics.investigate(ctx,
                new MetricsAgent.MetricsQuery("cpu_usage_percent", "1757059200",
                        "1757059260", "30s"));
        logs.investigate(ctx, new LogsAgent.LogsQuery("1757059200", "1757059260"));
        // 与 recordOnce 的 change 键同 args（until=…270）→ 回放 HIT
        change.investigate(ctx, new ChangeAgent.ChangeQuery("1757059200", "1757059270"));

        ClaimReducer reducer = new ClaimReducer(
                Set.of("prometheus", "logs-agent", "change-agent"), POLICY_VERSION);
        NativeRcaAgent rca = new NativeRcaAgent(evidence, claims, reducer);
        return new RoundResult(runner, rca.investigate(runId, SNAPSHOT, GENERATION)
                .verdicts());
    }

    /** run 语义指纹：证据（类型，payloadDigest）序列，按落库序 */
    private List<String> signatures(UUID runId) {
        return evidence.findByRunId(runId).stream()
                .map(envelope -> envelope.evidenceType() + "/"
                        + envelope.payloadDigest())
                .toList();
    }

    /**
     * 扰动矩阵（REPLAY_MISS 断言：任一匹配字段变化即查无此键）——tool 名/args/
     * timeRange/snapshot 各一。tool 名轴 = change 工具 + logs 的 args(260)：
     * change 键只录了 270 版，logs 键 tool 名不同——与任一录制键都不同键。
     * toolVersion 轴因触发 registry 白名单拒绝（UNKNOWN_TOOL）单独在测试体断言。
     */
    private List<ToolGateway.ToolInvocation> perturbations() {
        UUID runId = seedRun();
        Map<String, Object> baseArgs = LogsAgent.argsOf(
                new LogsAgent.LogsQuery("1757059200", "1757059260"));
        List<ToolGateway.ToolInvocation> list = new ArrayList<>();
        list.add(invocation(runId, ChangeAgent.TOOL_NAME, ChangeAgent.TOOL_VERSION,
                TIME_RANGE, baseArgs, SNAPSHOT));
        list.add(invocation(runId, LogsAgent.TOOL_NAME, LogsAgent.TOOL_VERSION,
                TIME_RANGE, LogsAgent.argsOf(
                        new LogsAgent.LogsQuery("1757059200", "1757059261")), SNAPSHOT));
        list.add(invocation(runId, LogsAgent.TOOL_NAME, LogsAgent.TOOL_VERSION,
                "2026-09-05T07:51:00Z/2026-09-05T08:00:00Z", baseArgs, SNAPSHOT));
        list.add(invocation(runId, LogsAgent.TOOL_NAME, LogsAgent.TOOL_VERSION,
                TIME_RANGE, baseArgs, Digest.sha256Of("e08-other-snapshot").value()));
        return list;
    }

    private ToolGateway.ToolInvocation metricsInvocation(UUID runId) {
        return invocation(runId, MetricsAgent.TOOL_NAME, MetricsAgent.TOOL_VERSION,
                TIME_RANGE, MetricsAgent.argsOf(new MetricsAgent.MetricsQuery(
                        "cpu_usage_percent", "1757059200", "1757059260", "30s")),
                SNAPSHOT);
    }

    private ToolGateway.ToolInvocation logsInvocation(UUID runId, String toolName) {
        return invocation(runId, toolName, LogsAgent.TOOL_VERSION, TIME_RANGE,
                LogsAgent.argsOf(new LogsAgent.LogsQuery("1757059200", "1757059260")),
                SNAPSHOT);
    }

    private ToolGateway.ToolInvocation invocation(UUID runId, String toolName,
            String toolVersion, String timeRange, Map<String, Object> args,
            String snapshotDigest) {
        return new ToolGateway.ToolInvocation(runId, UUID.randomUUID(),
                UUID.randomUUID(), 1L, toolName, toolVersion, timeRange, args,
                snapshotDigest);
    }

    private static AgentProfile profile(String name, String toolName) {
        return new AgentProfile(name, "1", "native-" + name, "e08-pv",
                Set.of(toolName),
                Map.of(BudgetKind.STEP, 8L, BudgetKind.TOOL_CALL, 4L),
                Map.of("type", "object"));
    }

    private static AgentProfile metricsProfile() {
        return profile("metrics", MetricsAgent.TOOL_NAME);
    }

    private static AgentProfile logsProfile() {
        return profile("logs", LogsAgent.TOOL_NAME);
    }

    private static AgentProfile changeProfile() {
        return profile("change", ChangeAgent.TOOL_NAME);
    }

    private RcaEventAppender eventAppender() {
        TransactionTemplate independent = new TransactionTemplate(
                new DataSourceTransactionManager(controlDataSource()));
        return new PostgresRcaEventAppender(controlJdbc, controlTx, independent);
    }

    private UUID seedRun() {
        UUID incident = UUID.randomUUID();
        controlJdbc.sql("""
                INSERT INTO incident(id, incident_key, status, generation, episode_started_at,
                    first_seen_at, last_event_at, created_at, updated_at)
                VALUES (:id, :key, 'FIRING', 0, now(), now(), now(), now(), now())
                """).param("id", incident)
                .param("key", "alertname=Replay|service=e08-" + incident).update();
        UUID run = UUID.randomUUID();
        controlJdbc.sql("""
                INSERT INTO rca_run(id, incident_id, generation, trigger_kind, state,
                    investigation_hash, created_at, updated_at)
                VALUES (:id, :inc, 0, 'INITIAL', 'QUEUED', :hash, now(), now())
                """).param("id", run).param("inc", incident)
                .param("hash", Digest.sha256Of("e08-" + run).value()).update();
        return run;
    }

    private record RoundResult(AgentReplayRunner runner, List<ClaimVerdict> verdicts) {
    }

    /** 计数执行器：任何一次真实调用都逃不过 calls 计数（零实时回退的行为取证） */
    private static final class CountingExecutor implements ToolExecutor {

        private final byte[] payload;
        private int calls;

        CountingExecutor(byte[] payload) {
            this.payload = payload;
        }

        @Override
        public byte[] execute(ToolExecutor.ToolExecution execution) {
            calls++;
            return payload;
        }
    }
}
