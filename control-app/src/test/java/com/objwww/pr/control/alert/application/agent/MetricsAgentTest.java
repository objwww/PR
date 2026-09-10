package com.objwww.pr.control.alert.application.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.objwww.pr.control.alert.application.tool.ToolExecutor;
import com.objwww.pr.control.alert.application.tool.ToolGateway;
import com.objwww.pr.control.alert.application.tool.ToolRegistry;
import com.objwww.pr.control.alert.domain.agent.AgentProfile;
import com.objwww.pr.control.alert.domain.budget.BudgetKind;
import com.objwww.pr.control.alert.domain.evidence.EvidenceEnvelope;
import com.objwww.pr.control.alert.domain.evidence.EvidenceRepository;
import com.objwww.pr.control.alert.domain.tool.ActionDigest;
import com.objwww.pr.control.alert.domain.tool.ActionEnvelope;
import com.objwww.pr.control.alert.domain.tool.RcaToolInvocationLedger;
import com.objwww.pr.control.alert.domain.tool.ToolControlPlaneException;
import com.objwww.pr.control.alert.domain.tool.ToolControlReason;
import com.objwww.pr.control.alert.domain.tool.ToolDefinition;
import com.objwww.pr.control.alert.domain.tool.ToolInvocationState;
import com.objwww.pr.control.alert.domain.tool.ToolModelVisibleException;
import com.objwww.pr.control.alert.domain.tool.ToolModelVisibleReason;
import com.objwww.pr.control.alert.domain.tool.ToolPolicy;
import com.objwww.pr.control.alert.domain.tool.ToolReasonCode;
import com.objwww.pr.control.alert.domain.tool.ToolRisk;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * MetricsAgent 单测（AM4 M4-27，TDD 先行，进程内无网络）：
 * 单一 R0 指标查询 → 证据落库 + 调用账本 PENDING 先行/终态 CAS；
 * NO_DATA 零证据；错误两族分岔（模型可见族→FAILED 可重试，终止族→重抛）。
 */
class MetricsAgentTest {

    private static final UUID RUN = UUID.randomUUID();
    private static final UUID TASK = UUID.randomUUID();
    private static final UUID ATTEMPT = UUID.randomUUID();
    private static final String TIME_RANGE = "2026-09-05T07:50:00Z/2026-09-05T08:00:00Z";
    private static final com.objwww.pr.control.alert.domain.identity.InvestigationInputDigest
            SNAPSHOT = new com.objwww.pr.control.alert.domain.identity.InvestigationInputDigest(
                    "fc" + "a".repeat(62));

    private final MemLedger ledger = new MemLedger();
    private final MemEvidence evidence = new MemEvidence();
    private final ObjectMapper mapper = new ObjectMapper();

    private MetricsAgent agent(ToolExecutor executor, ToolPolicy policy) {
        return agent(executor, policy, evidence, ledger);
    }

    /** EX-A3 次序钉形态：账本/证据仓可替换（观测 insert→markResultRef→succeed） */
    private MetricsAgent agent(ToolExecutor executor, ToolPolicy policy,
            EvidenceRepository evidenceStore, RcaToolInvocationLedger ledgerStore) {
        ToolRegistry registry = new ToolRegistry(List.of(new ToolRegistry.Registration(
                prometheusDefinition(), executor)));
        ExecutorService pool = Executors.newFixedThreadPool(2);
        ToolGateway gateway = new ToolGateway(registry, policy, pool,
                java.time.Clock.systemUTC(), null);
        return new MetricsAgent(profile(), registry, gateway, evidenceStore, ledgerStore,
                mapper);
    }

    /** EX-A1 全参形态（预算门 + 熔断门，无预算语义的旧 helper 走 permissive 缺省） */
    private MetricsAgent gatedAgent(
            com.objwww.pr.control.alert.application.RunBudgetGate gate,
            ToolExecutor executor) {
        return gatedAgent(gate,
                com.objwww.pr.control.alert.domain.budget.DoomLoopGuard.permissive(), executor);
    }

    private MetricsAgent gatedAgent(
            com.objwww.pr.control.alert.application.RunBudgetGate gate,
            com.objwww.pr.control.alert.domain.budget.DoomLoopGuard guard,
            ToolExecutor executor) {
        ToolRegistry registry = new ToolRegistry(List.of(new ToolRegistry.Registration(
                prometheusDefinition(), executor)));
        ExecutorService pool = Executors.newFixedThreadPool(2);
        ToolGateway gateway = new ToolGateway(registry, policyFor("prometheus.query"), pool,
                java.time.Clock.systemUTC(), null);
        return new MetricsAgent(profile(), registry, gateway, evidence, ledger, mapper, gate, guard);
    }

    // ------------------------------------------------------------------ 主路径

    @Test
    void producesEvidenceAndSettlesLedgerSuccess() throws Exception {
        MetricsAgent agent = agent(args -> fixtureBytes(), policyFor("prometheus.query"));

        MetricsAgent.AgentResult result = agent.investigate(
                context(), new MetricsAgent.MetricsQuery("cpu_usage_percent",
                        "1757059200", "1757059260", "30s"));

        assertThat(result.outcome()).isEqualTo(MetricsAgent.AgentOutcome.EVIDENCE_PRODUCED);
        assertThat(result.evidenceIds()).hasSize(1);
        assertThat(result.errorClass()).isNull();
        EvidenceEnvelope stored = evidence.rows.get(0);
        assertThat(stored.evidenceId()).isEqualTo(result.evidenceIds().get(0));
        EvidenceEnvelope.verify(stored); // 五步第④步：存储字节重算比对
        assertThat(stored.evidenceType()).isEqualTo("metrics.query_range");
        assertThat(stored.source()).isEqualTo("prometheus");
        assertThat(stored.observedGeneration()).isEqualTo(3L);
        assertThat(stored.scope()).containsEntry("investigation_input_digest", SNAPSHOT.hex());
        assertThat(stored.canonicalPayload()).contains("cpu_usage_percent");
        assertThat(ledger.rows).hasSize(1);
        assertThat(ledger.rows.get(0).state()).isEqualTo(ToolInvocationState.SUCCESS);
        assertThat(ledger.rows.get(0).actionDigest()).hasSize(64);
    }

    @Test
    void pendingLedgerOpensBeforeInvocation() {
        MetricsAgent agent = agent(args -> {
            assertThat(ledger.rows).hasSize(1); // PENDING 先行（账本纪律）
            assertThat(ledger.rows.get(0).state()).isEqualTo(ToolInvocationState.PENDING);
            return fixtureBytes();
        }, policyFor("prometheus.query"));

        agent.investigate(context(), query());
        assertThat(ledger.rows.get(0).state()).isEqualTo(ToolInvocationState.SUCCESS);
    }

    @Test
    void resultRefLandsBetweenInsertAndSucceed() {
        // EX-A3（F09）checkpoint 次序钉：insert → markResultRef → succeed——
        // 阶段③恢复以「SUCCESS 行 + result_ref 可解析证据」判定幂等收尾；
        // 引用晚于收尾落账 = 崩溃窗内恢复面永远读不到结果引用（恢复语义塌回重驱）
        List<String> events = new ArrayList<>();
        List<UUID> refs = new ArrayList<>();
        MemEvidence watchedEvidence = new MemEvidence() {
            @Override
            public void insert(EvidenceEnvelope envelope) {
                events.add("insert");
                super.insert(envelope);
            }
        };
        MemLedger watchedLedger = new MemLedger() {
            @Override
            public boolean markResultRef(UUID operationId, UUID evidenceId) {
                events.add("markResultRef");
                refs.add(evidenceId);
                return true;
            }

            @Override
            public boolean succeed(UUID operationId) {
                events.add("succeed");
                return super.succeed(operationId);
            }
        };
        MetricsAgent agent = agent(args -> fixtureBytes(), policyFor("prometheus.query"),
                watchedEvidence, watchedLedger);

        MetricsAgent.AgentResult result = agent.investigate(
                context(), new MetricsAgent.MetricsQuery("cpu_usage_percent",
                        "1757059200", "1757059260", "30s"));

        assertThat(result.outcome()).isEqualTo(MetricsAgent.AgentOutcome.EVIDENCE_PRODUCED);
        assertThat(events).containsExactly("insert", "markResultRef", "succeed");
        assertThat(refs).containsExactly(result.evidenceIds().get(0));
    }

    @Test
    void emptyResultIsNoDataWithoutEvidence() {
        MetricsAgent agent = agent(args -> """
                {"status":"success","data":{"resultType":"matrix","result":[]}}
                """.getBytes(StandardCharsets.UTF_8), policyFor("prometheus.query"));

        MetricsAgent.AgentResult result = agent.investigate(context(), query());

        assertThat(result.outcome()).isEqualTo(MetricsAgent.AgentOutcome.NO_DATA);
        assertThat(result.evidenceIds()).isEmpty();
        assertThat(evidence.rows).isEmpty();
        assertThat(ledger.rows.get(0).state()).isEqualTo(ToolInvocationState.SUCCESS);
    }

    // ------------------------------------------------------------------ 模型可见族（可重试）

    @Test
    void remoteUnavailableReturnsFailedAndFailsLedger() {
        MetricsAgent agent = agent(args -> {
            throw new ToolModelVisibleException(ToolModelVisibleReason.REMOTE_UNAVAILABLE,
                    "工具远端暂不可用（临时故障，可重试）");
        }, policyFor("prometheus.query"));

        MetricsAgent.AgentResult result = agent.investigate(context(), query());

        assertThat(result.outcome()).isEqualTo(MetricsAgent.AgentOutcome.FAILED);
        assertThat(result.errorClass()).isEqualTo("REMOTE_UNAVAILABLE");
        assertThat(evidence.rows).isEmpty();
        assertThat(ledger.rows.get(0).state()).isEqualTo(ToolInvocationState.FAILED);
        assertThat(ledger.rows.get(0).reason()).isEqualTo(ToolReasonCode.TRANSPORT_UNKNOWN);
    }

    @Test
    void timeoutMapsToTimeoutLedgerCode() {
        MetricsAgent agent = agent(args -> {
            throw new ToolModelVisibleException(ToolModelVisibleReason.TIMEOUT_RETRYABLE,
                    "工具调用超时（可重试）");
        }, policyFor("prometheus.query"));

        MetricsAgent.AgentResult result = agent.investigate(context(), query());

        assertThat(result.outcome()).isEqualTo(MetricsAgent.AgentOutcome.FAILED);
        assertThat(ledger.rows.get(0).reason()).isEqualTo(ToolReasonCode.TIMEOUT);
    }

    @Test
    void rateLimitedMapsToRateLimitedLedgerCode() {
        MetricsAgent agent = agent(args -> {
            throw new ToolModelVisibleException(ToolModelVisibleReason.RATE_LIMITED,
                    "指标源限流（可退避重试）");
        }, policyFor("prometheus.query"));

        assertThat(agent.investigate(context(), query()).outcome())
                .isEqualTo(MetricsAgent.AgentOutcome.FAILED);
        assertThat(ledger.rows.get(0).reason()).isEqualTo(ToolReasonCode.RATE_LIMITED);
    }

    // ------------------------------------------------------------------ 控制面终止族（重抛）

    @Test
    void policyDeniedRethrowsAndFailsLedger() {
        MetricsAgent agent = agent(args -> fixtureBytes(), policyFor("other.tool"));

        assertThatThrownBy(() -> agent.investigate(context(), query()))
                .isInstanceOf(ToolControlPlaneException.class)
                .hasMessageContaining("POLICY_DENIED");
        assertThat(evidence.rows).isEmpty();
        assertThat(ledger.rows.get(0).state()).isEqualTo(ToolInvocationState.FAILED);
        assertThat(ledger.rows.get(0).reason()).isEqualTo(ToolReasonCode.POLICY_DENIED);
    }

    @Test
    void argsMapMatchesToolSchemaExactly() {
        Map<String, Object> args = MetricsAgent.argsOf(query());

        assertThat(args).containsOnlyKeys("query", "start", "end", "step");
        assertThat(args).containsEntry("query", "cpu_usage_percent")
                .containsEntry("step", "30s");
    }

    // ------------------------------------------------------------------ Agent 契约

    @Test
    void allowlistMustPinExactlyTheSingleTool() {
        AgentProfile multi = new AgentProfile("metrics", "1", "prompt", "pv",
                Set.of("prometheus.query", "logs.query"), Map.of(), Map.of());
        ToolRegistry registry = new ToolRegistry(List.of(new ToolRegistry.Registration(
                prometheusDefinition(), args -> fixtureBytes())));
        ToolGateway gateway = new ToolGateway(registry, policyFor("prometheus.query"),
                Executors.newFixedThreadPool(1), java.time.Clock.systemUTC(), null);
        assertThatThrownBy(() -> new MetricsAgent(multi, registry, gateway, evidence,
                ledger, mapper))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("只做一种");
    }

    @Test
    void openDigestMatchesGatewayActionDigest() throws Exception {
        MetricsAgent agent = agent(args -> fixtureBytes(), policyFor("prometheus.query"));
        agent.investigate(context(), query());

        String schemaHash = prometheusDefinition().schemaHash();
        String expected = ActionDigest.of(new ActionEnvelope("rca", "prometheus.query", "1",
                schemaHash, MetricsAgent.argsOf(query()), TIME_RANGE, SNAPSHOT));        assertThat(ledger.rows.get(0).actionDigest()).isEqualTo(expected);
    }

    // ------------------------------------------------------------------ EX-A1 预算门（P1-02）

    @Test
    void budgetExhaustedRefusesCallWithZeroRemoteAndZeroLedger() {
        // P1-02：准入拒绝 = 零触网、零调用账本行（open 在 remote 段内未达）、FAILED BUDGET_EXHAUSTED
        com.objwww.pr.control.alert.infrastructure.InMemoryRunBudgetLedger budgetLedger =
                new com.objwww.pr.control.alert.infrastructure.InMemoryRunBudgetLedger();
        budgetLedger.ensureLimit(RUN, BudgetKind.TOOL_CALL, 0);
        java.util.concurrent.atomic.AtomicInteger remote = new java.util.concurrent.atomic.AtomicInteger();
        MetricsAgent agent = gatedAgent(
                new com.objwww.pr.control.alert.application.RunBudgetGate(budgetLedger),
                args -> {
                    remote.incrementAndGet();
                    return fixtureBytes();
                });

        MetricsAgent.AgentResult result = agent.investigate(context(), query());

        assertThat(result.outcome()).isEqualTo(MetricsAgent.AgentOutcome.FAILED);
        assertThat(result.errorClass()).isEqualTo("BUDGET_EXHAUSTED");
        assertThat(remote.get()).as("耗尽路径零远程（INV-AM4-9）").isZero();
        assertThat(ledger.rows).as("未达 open：零调用账本行").isEmpty();
        assertThat(budgetLedger.consumedOf(RUN, BudgetKind.TOOL_CALL)).isZero();
    }

    @Test
    void successfulCallReservesAndCommitsToolCallUnit() {
        com.objwww.pr.control.alert.infrastructure.InMemoryRunBudgetLedger budgetLedger =
                new com.objwww.pr.control.alert.infrastructure.InMemoryRunBudgetLedger();
        budgetLedger.ensureLimit(RUN, BudgetKind.TOOL_CALL, 4);
        MetricsAgent agent = gatedAgent(
                new com.objwww.pr.control.alert.application.RunBudgetGate(budgetLedger),
                args -> fixtureBytes());

        agent.investigate(context(), query());

        com.objwww.pr.control.alert.domain.budget.ReservationKey key =
                new com.objwww.pr.control.alert.domain.budget.ReservationKey(
                        RUN, TASK, ATTEMPT, 1, BudgetKind.TOOL_CALL);
        assertThat(budgetLedger.stateOf(key)).isEqualTo("COMMITTED");
        assertThat(budgetLedger.consumedOf(RUN, BudgetKind.TOOL_CALL)).isEqualTo(1);
    }

    @Test
    void modelVisibleFailureProvisionalNotRefunded() {
        // 发送后失败 = 保守占用 PROVISIONAL 等对账（不免费重发，P1-03）
        com.objwww.pr.control.alert.infrastructure.InMemoryRunBudgetLedger budgetLedger =
                new com.objwww.pr.control.alert.infrastructure.InMemoryRunBudgetLedger();
        budgetLedger.ensureLimit(RUN, BudgetKind.TOOL_CALL, 4);
        MetricsAgent agent = gatedAgent(
                new com.objwww.pr.control.alert.application.RunBudgetGate(budgetLedger),
                args -> {
                    throw new ToolModelVisibleException(ToolModelVisibleReason.TIMEOUT_RETRYABLE,
                            "工具调用超时（可重试）");
                });

        MetricsAgent.AgentResult result = agent.investigate(context(), query());

        assertThat(result.outcome()).isEqualTo(MetricsAgent.AgentOutcome.FAILED);
        com.objwww.pr.control.alert.domain.budget.ReservationKey key =
                new com.objwww.pr.control.alert.domain.budget.ReservationKey(
                        RUN, TASK, ATTEMPT, 1, BudgetKind.TOOL_CALL);
        assertThat(budgetLedger.stateOf(key)).isEqualTo("PROVISIONAL");
        assertThat(budgetLedger.consumedOf(RUN, BudgetKind.TOOL_CALL)).isEqualTo(1);
    }

    @Test
    void recordWriteFailureReleasesReservation() {
        // P1-02 场景③：预留成功但调用记录写失败 → 预留撤销 RELEASED 可核、账面归零
        com.objwww.pr.control.alert.infrastructure.InMemoryRunBudgetLedger budgetLedger =
                new com.objwww.pr.control.alert.infrastructure.InMemoryRunBudgetLedger();
        budgetLedger.ensureLimit(RUN, BudgetKind.TOOL_CALL, 4);
        MemLedger brokenLedger = new MemLedger() {
            @Override
            public void open(InvocationIdentity identity) {
                throw new IllegalStateException("调用记录写失败（存储面故障模拟）");
            }
        };
        ToolRegistry registry = new ToolRegistry(List.of(new ToolRegistry.Registration(
                prometheusDefinition(), args -> fixtureBytes())));
        ToolGateway gateway = new ToolGateway(registry, policyFor("prometheus.query"),
                Executors.newFixedThreadPool(2), java.time.Clock.systemUTC(), null);
        MetricsAgent agent = new MetricsAgent(profile(), registry, gateway, evidence,
                brokenLedger, mapper,
                new com.objwww.pr.control.alert.application.RunBudgetGate(budgetLedger),
                com.objwww.pr.control.alert.domain.budget.DoomLoopGuard.permissive());

        assertThatThrownBy(() -> agent.investigate(context(), query()))
                .isInstanceOf(IllegalStateException.class);
        com.objwww.pr.control.alert.domain.budget.ReservationKey key =
                new com.objwww.pr.control.alert.domain.budget.ReservationKey(
                        RUN, TASK, ATTEMPT, 1, BudgetKind.TOOL_CALL);
        assertThat(budgetLedger.stateOf(key)).as("预留撤销可核").isEqualTo("RELEASED");
        assertThat(budgetLedger.consumedOf(RUN, BudgetKind.TOOL_CALL)).isZero();
    }

    @Test
    void doomLoopTrippedSignatureFailsFastZeroRemote() {
        // P1-02/EX-A1 §4：阈值 1，一次 NO_DATA 即熔断；下一次同签名零触网直拒
        com.objwww.pr.control.alert.domain.budget.DoomLoopGuard guard =
                new com.objwww.pr.control.alert.domain.budget.DoomLoopGuard(
                        new com.objwww.pr.control.alert.domain.budget.DoomLoopGuard.Policy(
                                1, "doom-test", java.util.Set.of()));
        java.util.concurrent.atomic.AtomicInteger remote = new java.util.concurrent.atomic.AtomicInteger();
        MetricsAgent agent = gatedAgent(
                new com.objwww.pr.control.alert.application.RunBudgetGate(
                        new com.objwww.pr.control.alert.infrastructure.InMemoryRunBudgetLedger()),
                guard,
                args -> {
                    remote.incrementAndGet();
                    return """
                            {"status":"success","data":{"resultType":"matrix","result":[]}}
                            """.getBytes(StandardCharsets.UTF_8);
                });

        assertThat(agent.investigate(context(), query()).outcome())
                .isEqualTo(MetricsAgent.AgentOutcome.NO_DATA);   // 命中放行，record 触发熔断
        MetricsAgent.AgentResult second = agent.investigate(context(), query());
        assertThat(second.outcome()).isEqualTo(MetricsAgent.AgentOutcome.FAILED);
        assertThat(second.errorClass()).isEqualTo("DOOM_LOOP_TRIPPED");
        assertThat(remote.get()).as("熔断后零触网").isEqualTo(1);
        assertThat(ledger.rows).as("熔断直拒零账本行").hasSize(1);
    }

    @Test
    void doomLoopProgressResetsNoProgressCounter() {
        com.objwww.pr.control.alert.domain.budget.DoomLoopGuard guard =
                new com.objwww.pr.control.alert.domain.budget.DoomLoopGuard(
                        new com.objwww.pr.control.alert.domain.budget.DoomLoopGuard.Policy(
                                2, "doom-test", java.util.Set.of()));
        MetricsAgent agent = gatedAgent(
                new com.objwww.pr.control.alert.application.RunBudgetGate(
                        new com.objwww.pr.control.alert.infrastructure.InMemoryRunBudgetLedger()),
                guard,
                args -> fixtureBytes());

        // 三次物理请求（各自 callSeq——生产重试铸造新 callSeq；同参数=同 doom 签名跨重试稳定）
        assertThat(agent.investigate(context(1), query()).outcome())
                .isEqualTo(MetricsAgent.AgentOutcome.EVIDENCE_PRODUCED);   // 有进展
        assertThat(agent.investigate(context(2), query()).outcome())
                .isEqualTo(MetricsAgent.AgentOutcome.EVIDENCE_PRODUCED);
        assertThat(agent.investigate(context(3), query()).outcome())
                .isEqualTo(MetricsAgent.AgentOutcome.EVIDENCE_PRODUCED);
        assertThat(guard.trippedSignatures()).as("连续有进展不熔断").isEmpty();
    }

    // ------------------------------------------------------------------ EX-A4a（F16/F17）

    @Test
    void unknownSegmentFailureLedgersUnknownAndRethrows() {
        // F16：invoke 返回后的本地段（解析/落库/结算）未分类异常 = 结果未知 →
        // 账本 UNKNOWN（不假 FAILED）+ 原样重抛。远端异常经 Gateway 已脱敏为
        // 模型可见族（见 ToolGatewayTest 错误两族），不进本分岔。
        EvidenceRepository broken = new EvidenceRepository() {
            @Override
            public void insert(EvidenceEnvelope envelope) {
                throw new IllegalStateException("证据落库失败（存储面故障模拟）");
            }

            @Override
            public Optional<EvidenceEnvelope> findById(UUID id) {
                return Optional.empty();
            }

            @Override
            public List<EvidenceEnvelope> findByRunId(UUID runId) {
                return List.of();
            }
        };
        ToolRegistry registry = new ToolRegistry(List.of(new ToolRegistry.Registration(
                prometheusDefinition(), args -> fixtureBytes())));
        MetricsAgent agent = new MetricsAgent(profile(), registry,
                new ToolGateway(registry, policyFor("prometheus.query"),
                        Executors.newFixedThreadPool(2), java.time.Clock.systemUTC(), null),
                broken, ledger, mapper);

        assertThatThrownBy(() -> agent.investigate(context(), query()))
                .isInstanceOf(IllegalStateException.class);
        assertThat(ledger.rows.get(0).state()).isEqualTo(ToolInvocationState.UNKNOWN);
        assertThat(ledger.rows.get(0).reason()).isEqualTo(ToolReasonCode.TRANSPORT_UNKNOWN);
    }

    @Test
    void seriesOverCapIsTerminalOversizeWithLedgerFailed() {
        // F17：序列数超上限 = 查询过宽，控制面终止族——账本 FAILED 后原样重抛（drive() 降级 DEAD）
        StringBuilder series = new StringBuilder("[");
        for (int i = 0; i <= SingleToolEvidenceAgent.MAX_SERIES; i++) {
            if (i > 0) {
                series.append(',');
            }
            series.append("{\"metric\":{\"s\":\"").append(i).append("\"},\"values\":[]}");
        }
        series.append(']');
        String body = "{\"status\":\"success\",\"data\":{\"resultType\":\"matrix\","
                + "\"result\":" + series + "}}";
        MetricsAgent agent = agent(args -> body.getBytes(StandardCharsets.UTF_8),
                policyFor("prometheus.query"));

        assertThatThrownBy(() -> agent.investigate(context(), query()))
                .isInstanceOf(ToolControlPlaneException.class)
                .hasMessageContaining("RESULT_OVERSIZE");
        assertThat(evidence.rows).as("超宽查询零证据落库").isEmpty();
        assertThat(ledger.rows.get(0).state()).isEqualTo(ToolInvocationState.FAILED);
        assertThat(ledger.rows.get(0).reason()).isEqualTo(ToolReasonCode.TRANSPORT_UNKNOWN);
    }

    // ------------------------------------------------------------------ 夹具

    private static MetricsAgent.CallContext context() {
        return context(1);
    }

    private static MetricsAgent.CallContext context(long callSeq) {
        return new MetricsAgent.CallContext(RUN, TASK, ATTEMPT, callSeq, 3L, SNAPSHOT, TIME_RANGE);
    }

    private static MetricsAgent.MetricsQuery query() {
        return new MetricsAgent.MetricsQuery("cpu_usage_percent",
                "1757059200", "1757059260", "30s");
    }

    private static ToolPolicy policyFor(String tool) {
        return new ToolPolicy(Set.of(tool));
    }

    private static AgentProfile profile() {
        return new AgentProfile("metrics", "1", "prompt-metrics", "pv",
                Set.of("prometheus.query"), Map.of(BudgetKind.TOOL_CALL, 4L),
                Map.of("type", "object"));
    }

    private static ToolDefinition prometheusDefinition() {
        Map<String, Object> properties = new java.util.LinkedHashMap<>();
        properties.put("query", Map.of("type", "string"));
        properties.put("start", Map.of("type", "string"));
        properties.put("end", Map.of("type", "string"));
        properties.put("step", Map.of("type", "string"));
        Map<String, Object> schema = new java.util.LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", properties);
        schema.put("required", List.of("query", "start", "end", "step"));
        return new ToolDefinition("prometheus.query", "1", schema, ToolRisk.R0, 2000, 65536);
    }

    private static byte[] fixtureBytes() {
        try (InputStream in = MetricsAgentTest.class
                .getResourceAsStream("/fixtures/prometheus/query-range-f1-cpu.json")) {
            return in.readAllBytes();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    /** 账本内存件：open/终态 CAS 语义与 V15 一致（PENDING→终态单向单次） */
    private static class MemLedger implements RcaToolInvocationLedger {
        record Row(UUID operationId, UUID runId, UUID taskId, UUID attemptId, long callSeq,
                String toolName, String toolVersion, String actionDigest,
                ToolInvocationState state, ToolReasonCode reason) {
        }

        final List<Row> rows = new ArrayList<>();

        @Override
        public void open(InvocationIdentity identity) {
            rows.add(new Row(identity.operationId(), identity.runId(), identity.taskId(),
                    identity.attemptId(), identity.callSeq(), identity.toolName(),
                    identity.toolVersion(), identity.actionDigest(),
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
                    rows.set(k, new Row(row.operationId(), row.runId(), row.taskId(),
                            row.attemptId(), row.callSeq(), row.toolName(), row.toolVersion(),
                            row.actionDigest(), state, reason));
                    return true;
                }
            }
            return false;
        }
    }

    /** 证据仓储内存件（EX-A3 次序钉需匿名子类观测 insert，故不 final） */
    private static class MemEvidence implements EvidenceRepository {
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
