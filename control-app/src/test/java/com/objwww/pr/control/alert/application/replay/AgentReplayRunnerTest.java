package com.objwww.pr.control.alert.application.replay;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.objwww.pr.control.alert.application.agent.ChangeAgent;
import com.objwww.pr.control.alert.application.agent.LogsAgent;
import com.objwww.pr.control.alert.application.agent.MetricsAgent;
import com.objwww.pr.control.alert.application.tool.ReplayToolGateway;
import com.objwww.pr.control.alert.application.tool.ToolGateway;
import com.objwww.pr.control.alert.application.tool.ToolRegistry;
import com.objwww.pr.control.alert.domain.agent.AgentProfile;
import com.objwww.pr.control.alert.domain.budget.BudgetKind;
import com.objwww.pr.control.alert.domain.evidence.EvidenceEnvelope;
import com.objwww.pr.control.alert.domain.evidence.EvidenceRepository;
import com.objwww.pr.control.alert.domain.tool.RcaToolInvocationLedger;
import com.objwww.pr.control.alert.domain.tool.ToolDefinition;
import com.objwww.pr.control.alert.domain.tool.ToolInvocationState;
import com.objwww.pr.control.alert.domain.tool.ToolReasonCode;
import com.objwww.pr.control.alert.domain.tool.ToolReplayStore;
import com.objwww.pr.control.infrastructure.tool.ReplayToolExecutor;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * Agent Replay Runner 单测（AM4 M4-33，TDD 先行）：候选侧固定链以真实 Agent 代码
 * 组合回放网关（mock 工具面），统计覆盖率与 Token。验收三态：全匹配 / 部分回放 /
 * 零覆盖；REPLAY_MISS 走模型可见族（Agent FAILED + 账本 FAILED 十码 REPLAY_MISS），
 * 绝不降级活执行。
 *
 * @author wanghua
 * @date 2026-09-05
 */
class AgentReplayRunnerTest {

    private static final UUID RUN_ID = UUID.randomUUID();
    private static final UUID TASK_ID = UUID.randomUUID();
    private static final UUID ATTEMPT_ID = UUID.randomUUID();
    private static final long CALL_SEQ = 1L;
    private static final long GENERATION = 3L;
    private static final long TIMEOUT_MILLIS = 4_000L;
    private static final long RESULT_LIMIT_BYTES = 65_536L;

    private static final String TIME_RANGE = "2026-09-05T07:50:00Z/2026-09-05T08:00:00Z";
    private static final com.objwww.pr.control.alert.domain.identity.InvestigationInputDigest
            SNAPSHOT = new com.objwww.pr.control.alert.domain.identity.InvestigationInputDigest(
                    "ab".repeat(32));
    /** 注册执行器只会返回的"活执行"字节：结构性不可达，误降级即暴露 */
    private static final byte[] LIVE_ONLY_RESPONSE =
            "{\"status\":\"live-only\"}".getBytes(StandardCharsets.UTF_8);
    private static final byte[] METRICS_RESPONSE = succeed("up");
    private static final byte[] LOGS_RESPONSE = succeed("error-log-line");
    private static final byte[] CHANGE_RESPONSE = succeed("deploy-record");

    private final MemReplayStore store = new MemReplayStore();
    private final MemLedger ledger = new MemLedger();
    private final MemEvidence evidence = new MemEvidence();

    @Test
    void fullMatchReplaysWholeChain() {
        AgentReplayRunner runner = runner();
        runner.record(metricsInvocation(), METRICS_RESPONSE);
        runner.record(logsInvocation(), LOGS_RESPONSE);
        runner.record(changeInvocation(), CHANGE_RESPONSE);

        runFixedChain(runner);

        assertThat(runner.stats().totalInvocations()).isEqualTo(3);
        assertThat(runner.stats().hits()).isEqualTo(3);
        assertThat(runner.stats().misses()).isZero();
        assertThat(runner.stats().coverage()).isEqualTo(1.0);
        assertThat(evidence.rows).hasSize(3);
    }

    @Test
    void partialReplayCountsMissesAndLedgerRecordsReplayMiss() {
        AgentReplayRunner runner = runner();
        // 基线只录了 metrics：logs/change 两次调用未命中
        runner.record(metricsInvocation(), METRICS_RESPONSE);

        List<MetricsAgent.AgentResult> results = runFixedChain(runner);

        assertThat(results.get(0).outcome())
                .isEqualTo(MetricsAgent.AgentOutcome.EVIDENCE_PRODUCED);
        assertThat(results.get(1).outcome()).isEqualTo(MetricsAgent.AgentOutcome.FAILED);
        assertThat(results.get(1).errorClass()).isEqualTo("REPLAY_MISS");
        assertThat(results.get(2).outcome()).isEqualTo(MetricsAgent.AgentOutcome.FAILED);
        assertThat(results.get(2).errorClass()).isEqualTo("REPLAY_MISS");

        assertThat(runner.stats().totalInvocations()).isEqualTo(3);
        assertThat(runner.stats().hits()).isEqualTo(1);
        assertThat(runner.stats().misses()).isEqualTo(2);
        assertThat(runner.stats().coverage()).isCloseTo(1.0 / 3, within(1e-9));
        // 未命中在账本按冻结十码 REPLAY_MISS 归因（模型可见族，可重试）
        assertThat(ledger.rows.stream().filter(r ->
                        r.state() == ToolInvocationState.FAILED
                                && r.reason() == ToolReasonCode.REPLAY_MISS).count())
                .isEqualTo(2);
    }

    @Test
    void zeroCoverageWhenNothingRecorded() {
        AgentReplayRunner runner = runner();

        runFixedChain(runner);

        assertThat(runner.stats().totalInvocations()).isEqualTo(3);
        assertThat(runner.stats().hits()).isZero();
        assertThat(runner.stats().coverage()).isZero();
        assertThat(evidence.rows).isEmpty();
    }

    @Test
    void tokensAccumulateAcrossReports() {
        AgentReplayRunner runner = runner();

        runner.reportTokens(10, 5);
        runner.reportTokens(1, 1);

        assertThat(runner.stats().inputTokens()).isEqualTo(11);
        assertThat(runner.stats().outputTokens()).isEqualTo(6);
    }

    // ------------------------------------------------------------------ 夹具

    /** 候选侧固定链：三证据 Agent 以真实代码组合回放网关（runner 即 ToolInvoker） */
    private List<MetricsAgent.AgentResult> runFixedChain(AgentReplayRunner runner) {
        MetricsAgent.CallContext ctx = new MetricsAgent.CallContext(RUN_ID, TASK_ID,
                ATTEMPT_ID, CALL_SEQ, GENERATION, SNAPSHOT, TIME_RANGE);
        MetricsAgent metrics = new MetricsAgent(metricsProfile(), registry(), runner,
                evidence, ledger, new ObjectMapper());
        LogsAgent logs = new LogsAgent(logsProfile(), registry(), runner,
                evidence, ledger, new ObjectMapper());
        ChangeAgent change = new ChangeAgent(changeProfile(), registry(), runner,
                evidence, ledger, new ObjectMapper());
        List<MetricsAgent.AgentResult> results = new ArrayList<>();
        results.add(metrics.investigate(ctx, new MetricsAgent.MetricsQuery(
                "cpu_usage_percent", "1757059200", "1757059260", "30s")));
        results.add(logs.investigate(ctx, new LogsAgent.LogsQuery("1757059200",
                "1757059260")));
        results.add(change.investigate(ctx, new ChangeAgent.ChangeQuery("1757059200",
                "1757059260")));
        return results;
    }

    private AgentReplayRunner runner() {
        return new AgentReplayRunner(new ReplayToolGateway(registry(), store));
    }

    private ToolRegistry registry() {
        ToolDefinition metrics = MetricsAgent.toolDefinition(
                TIMEOUT_MILLIS, RESULT_LIMIT_BYTES);
        ToolDefinition logs = LogsAgent.toolDefinition(TIMEOUT_MILLIS, RESULT_LIMIT_BYTES);
        ToolDefinition change = ChangeAgent.toolDefinition(TIMEOUT_MILLIS, RESULT_LIMIT_BYTES);
        return new ToolRegistry(List.of(
                new ToolRegistry.Registration(metrics, new ReplayToolExecutor(LIVE_ONLY_RESPONSE)),
                new ToolRegistry.Registration(logs, new ReplayToolExecutor(LIVE_ONLY_RESPONSE)),
                new ToolRegistry.Registration(change, new ReplayToolExecutor(LIVE_ONLY_RESPONSE))));
    }

    private ToolGateway.ToolInvocation metricsInvocation() {
        return new ToolGateway.ToolInvocation(
                RUN_ID, TASK_ID, ATTEMPT_ID, CALL_SEQ, MetricsAgent.TOOL_NAME,
                MetricsAgent.TOOL_VERSION, TIME_RANGE, MetricsAgent.argsOf(
                        new MetricsAgent.MetricsQuery("cpu_usage_percent", "1757059200",
                                "1757059260", "30s")), SNAPSHOT);
    }

    private ToolGateway.ToolInvocation logsInvocation() {
        return new ToolGateway.ToolInvocation(
                RUN_ID, TASK_ID, ATTEMPT_ID, CALL_SEQ, LogsAgent.TOOL_NAME,
                LogsAgent.TOOL_VERSION, TIME_RANGE, LogsAgent.argsOf(
                        new LogsAgent.LogsQuery("1757059200", "1757059260")), SNAPSHOT);
    }

    private ToolGateway.ToolInvocation changeInvocation() {
        return new ToolGateway.ToolInvocation(
                RUN_ID, TASK_ID, ATTEMPT_ID, CALL_SEQ, ChangeAgent.TOOL_NAME,
                ChangeAgent.TOOL_VERSION, TIME_RANGE, ChangeAgent.argsOf(
                        new ChangeAgent.ChangeQuery("1757059200", "1757059260")), SNAPSHOT);
    }

    private static AgentProfile metricsProfile() {
        return new AgentProfile("metrics", "1", "prompt-metrics", "pv",
                Set.of(MetricsAgent.TOOL_NAME), Map.of(BudgetKind.TOOL_CALL, 4L),
                Map.of("type", "object"));
    }

    private static AgentProfile logsProfile() {
        return new AgentProfile("logs", "1", "prompt-logs", "pv",
                Set.of(LogsAgent.TOOL_NAME), Map.of(BudgetKind.TOOL_CALL, 4L),
                Map.of("type", "object"));
    }

    private static AgentProfile changeProfile() {
        return new AgentProfile("change", "1", "prompt-change", "pv",
                Set.of(ChangeAgent.TOOL_NAME), Map.of(BudgetKind.TOOL_CALL, 4L),
                Map.of("type", "object"));
    }

    private static byte[] succeed(String marker) {
        return ("{\"status\":\"success\",\"data\":{\"result\":[\"" + marker + "\"]}}")
                .getBytes(StandardCharsets.UTF_8);
    }

    /** 回放账本内存件（哑存储，同 M4-32 测试） */
    static final class MemReplayStore implements ToolReplayStore {

        private final Map<String, ReplayRecord> records = new LinkedHashMap<>();

        @Override
        public void put(ReplayRecord record) {
            records.put(record.actionDigest(), record);
        }

        @Override
        public Optional<ReplayRecord> find(String actionDigest) {
            return Optional.ofNullable(records.get(actionDigest));
        }
    }

    /** 账本内存件：open/终态 CAS 语义与 V15 一致（同 ReplayAgentFixtures 形状） */
    static final class MemLedger implements RcaToolInvocationLedger {
        record Row(UUID operationId, String toolName, ToolInvocationState state,
                ToolReasonCode reason) {
        }

        final List<Row> rows = new ArrayList<>();

        @Override
        public void open(InvocationIdentity identity) {
            rows.add(new Row(identity.operationId(), identity.toolName(),
                    ToolInvocationState.PENDING, null));
        }

        @Override
        public boolean succeed(UUID operationId) {
            return settle(operationId, ToolInvocationState.SUCCESS, null);
        }

        @Override
        public boolean fail(UUID operationId, ToolInvocationState terminal,
                ToolReasonCode reasonCode) {
            return settle(operationId, terminal, reasonCode);
        }

        private boolean settle(UUID id, ToolInvocationState state, ToolReasonCode reason) {
            for (int k = 0; k < rows.size(); k++) {
                Row row = rows.get(k);
                if (row.operationId().equals(id) && row.state() == ToolInvocationState.PENDING) {
                    rows.set(k, new Row(id, row.toolName(), state, reason));
                    return true;
                }
            }
            return false;
        }
    }

    /** 证据仓储内存件 */
    static final class MemEvidence implements EvidenceRepository {
        final List<EvidenceEnvelope> rows = new ArrayList<>();

        @Override
        public void insert(EvidenceEnvelope envelope) {
            rows.add(envelope);
        }

        @Override
        public Optional<EvidenceEnvelope> findById(UUID id) {
            return rows.stream().filter(e -> e.evidenceId().equals(id)).findFirst();
        }

        @Override
        public List<EvidenceEnvelope> findByRunId(UUID runId) {
            return rows.stream().filter(e -> e.runId().equals(runId)).toList();
        }
    }
}
