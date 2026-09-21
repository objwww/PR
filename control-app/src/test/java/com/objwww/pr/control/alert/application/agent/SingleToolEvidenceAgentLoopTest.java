package com.objwww.pr.control.alert.application.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.objwww.pr.control.alert.application.RunBudgetGate;
import com.objwww.pr.control.alert.application.tool.ToolGateway;
import com.objwww.pr.control.alert.application.tool.ToolRegistry;
import com.objwww.pr.control.alert.domain.agent.AgentProfile;
import com.objwww.pr.control.alert.domain.budget.BudgetKind;
import com.objwww.pr.control.alert.domain.budget.DoomLoopGuard;
import com.objwww.pr.control.alert.domain.evidence.EvidenceEnvelope;
import com.objwww.pr.control.alert.domain.evidence.EvidenceRepository;
import com.objwww.pr.control.alert.domain.tool.RcaToolInvocationLedger;
import com.objwww.pr.control.alert.domain.tool.ToolInvocationState;
import com.objwww.pr.control.alert.domain.tool.ToolPolicy;
import com.objwww.pr.control.alert.domain.tool.ToolReasonCode;
import com.objwww.pr.control.alert.infrastructure.FixtureSeededBudgetLedger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ME-T05（D05）在线收紧单测：F07「非空成功即进展」收紧为本 run 内容新鲜度
 * （LOOP-05 在线边界）；收紧后 A/B 换签名取同材料可被既有 ping-pong 承接
 * （LOOP-02 在线）；新结果/成功复用两分支调用计量（第 4 条，LOOP-06 在线）；
 * 修正重试不误判（LOOP-10 在线）。整案级 run 窗口的离线测量见
 * {@code LoopTraceEvaluatorTest}；在线 run 级守卫接入为如实遗留。
 */
class SingleToolEvidenceAgentLoopTest {

    private static final UUID RUN = UUID.randomUUID();
    private static final UUID TASK = UUID.randomUUID();
    private static final UUID ATTEMPT = UUID.randomUUID();
    private static final String WINDOW = "2026-09-10T00:00:00Z/2026-09-10T00:05:00Z";
    private static final byte[] BODY_X = """
            {"status":"success","data":{"resultType":"vector",
              "result":[{"metric":{"service":"checkout"},"value":[1757059260,"0.42"]}]}}
            """.getBytes(StandardCharsets.UTF_8);
    private static final byte[] BODY_ERROR = """
            {"status":"error","errorType":"bad_data","error":"invalid query"}
            """.getBytes(StandardCharsets.UTF_8);

    private final MemEvidence evidence = new MemEvidence();
    private final AtomicInteger executions = new AtomicInteger();
    private final List<SingleToolEvidenceAgent.CallObservation> observations =
            new ArrayList<>();

    private LoopTestAgent agent(RcaToolInvocationLedger ledger, DoomLoopGuard guard,
                                byte[] body) {
        ToolRegistry registry = new ToolRegistry(List.of(new ToolRegistry.Registration(
                DirectReadToolCatalog.prometheusInstant(4_000, 65_536),
                exec -> {
                    executions.incrementAndGet();
                    return body;
                })));
        ToolGateway gateway = new ToolGateway(registry,
                new ToolPolicy(Set.of(DirectReadToolCatalog.TOOL_INSTANT)),
                Executors.newFixedThreadPool(2), java.time.Clock.systemUTC(), null);
        return new LoopTestAgent(profile(), registry, gateway, evidence, ledger, guard,
                observations::add);
    }

    private static AgentProfile profile() {
        return new AgentProfile("loop-test", "1", "prompt", "am4-native-v1",
                Set.of(DirectReadToolCatalog.TOOL_INSTANT),
                Map.of(BudgetKind.TOOL_CALL, 20L), Map.of("type", "object"));
    }

    private static SingleToolEvidenceAgent.CallContext context(long callSeq) {
        return new SingleToolEvidenceAgent.CallContext(RUN, TASK, ATTEMPT, callSeq, 3L,
                null, WINDOW);
    }

    // ------------------------------------------------------------------ LOOP-05 在线

    @Test
    @DisplayName("LOOP-05 在线：SUCCESS 非空但业务内容恒同——第二次起不再记进展（F07 收紧）")
    void sameBusinessContentIsNotProgress() {
        RecordingGuard guard = new RecordingGuard(DoomLoopGuard.permissive().policy());
        LoopTestAgent agent = agent(new MemLedger(), guard, BODY_X);

        // 换参数（不同签名、绕开复用面）取同一份材料
        assertThat(agent.investigate(context(1),
                Map.of("query", "up", "time", "1757059260")).outcome())
                .isEqualTo(SingleToolEvidenceAgent.AgentOutcome.EVIDENCE_PRODUCED);
        assertThat(agent.investigate(context(2),
                Map.of("query", "up", "time", "1757059320")).outcome())
                .isEqualTo(SingleToolEvidenceAgent.AgentOutcome.EVIDENCE_PRODUCED);

        assertThat(guard.progressedFlags)
                .as("首见内容=进展；同材料重取=无进展（重复内容不自动算进展）")
                .containsExactly(true, false);
        assertThat(evidence.rows).as("证据行仍落库（行存在≠内容新颖）").hasSize(2);
        assertThat(evidence.rows.get(0).payloadDigest())
                .isEqualTo(evidence.rows.get(1).payloadDigest());
        assertThat(observations).hasSize(2);
        assertThat(observations.get(0).newEvidenceContent()).isTrue();
        assertThat(observations.get(1).newEvidenceContent()).isFalse();
        assertThat(observations.get(1).physicalCall()).isTrue();
    }

    // ------------------------------------------------------------------ LOOP-02 在线

    @Test
    @DisplayName("LOOP-02 在线：A/B 交替取同材料——收紧进展后由既有 ping-pong 承接熔断，其后零触网")
    void abAlternationSameMaterialTripsExistingPingPong() {
        DoomLoopGuard guard = new DoomLoopGuard(new DoomLoopGuard.Policy(2, 5, 4, 6,
                "d05-ut", Set.of()));
        LoopTestAgent agent = agent(new MemLedger(), guard, BODY_X);
        Map<String, Object> argsA = Map.of("query", "up", "time", "1757059260");
        Map<String, Object> argsB = Map.of("query", "up", "time", "1757059320");

        assertThat(agent.investigate(context(1), argsA).outcome())
                .isEqualTo(SingleToolEvidenceAgent.AgentOutcome.EVIDENCE_PRODUCED); // 新内容=进展
        for (long seq = 2; seq <= 7; seq++) { // B,A,B,A,B,A 六次无进展交替
            agent.investigate(context(seq), seq % 2 == 0 ? argsB : argsA);
        }
        assertThat(guard.inPingPongWarningZone(TASK)).isTrue();

        SingleToolEvidenceAgent.AgentResult stopped =
                agent.investigate(context(8), argsB);
        assertThat(stopped.outcome()).isEqualTo(SingleToolEvidenceAgent.AgentOutcome.FAILED);
        assertThat(stopped.errorClass()).isEqualTo("DOOM_LOOP_TRIPPED");
        assertThat(executions.get()).as("熔断后同签名零触网").isEqualTo(7);
    }

    // ------------------------------------------------------------------ LOOP-06 在线（第 4 条计量）

    @Test
    @DisplayName("LOOP-06 在线：新结果与成功复用两分支都计量——复用物理 0、非新证据，逻辑重复仍计数")
    void telemetryMetersNewResultAndReuseBranches() {
        ReuseMemLedger ledger = new ReuseMemLedger();
        LoopTestAgent agent = agent(ledger,
                new DoomLoopGuard(new DoomLoopGuard.Policy(2, 5, 4, 6, "d05-ut", Set.of())),
                BODY_X);

        agent.investigate(context(1), Map.of("query", "up", "time", "1757059260"));
        agent.investigate(context(2), Map.of("query", " up \n", "time", " 1757059260 "));
        agent.investigate(context(3), Map.of("query", "up", "time", "1757059260"));

        assertThat(executions.get()).as("物理调用保持既有首调用次数").isEqualTo(1);
        assertThat(observations).as("每次逻辑调用恰一条计量").hasSize(3);
        assertThat(observations.get(0).physicalCall()).isTrue();
        assertThat(observations.get(0).newEvidenceContent()).isTrue();
        assertThat(observations.get(1).physicalCall()).as("复用=零新工具执行").isFalse();
        assertThat(observations.get(1).newEvidenceContent())
                .as("复用同一证据 UUID 不算新证据").isFalse();
        assertThat(observations.get(1).outcome())
                .isEqualTo(SingleToolEvidenceAgent.AgentOutcome.EVIDENCE_PRODUCED);
        assertThat(observations.get(2).physicalCall()).isFalse();
    }

    // ------------------------------------------------------------------ LOOP-10 在线

    @Test
    @DisplayName("LOOP-10 在线：首次失败后修正参数取得新证据——有界恢复成功，不累计成循环")
    void failedThenCorrectedRetryIsBoundedRecovery() {
        RecordingGuard guard = new RecordingGuard(new DoomLoopGuard.Policy(2, 5, 4, 6,
                "d05-ut", Set.of()));
        ToolRegistry registry = new ToolRegistry(List.of(new ToolRegistry.Registration(
                DirectReadToolCatalog.prometheusInstant(4_000, 65_536),
                exec -> {
                    executions.incrementAndGet();
                    return executions.get() == 1 ? BODY_ERROR : BODY_X;
                })));
        ToolGateway gateway = new ToolGateway(registry,
                new ToolPolicy(Set.of(DirectReadToolCatalog.TOOL_INSTANT)),
                Executors.newFixedThreadPool(2), java.time.Clock.systemUTC(), null);
        LoopTestAgent agent = new LoopTestAgent(profile(), registry, gateway, evidence,
                new MemLedger(), guard, observations::add);

        assertThat(agent.investigate(context(1),
                Map.of("query", "up{", "time", "1757059260")).outcome())
                .isEqualTo(SingleToolEvidenceAgent.AgentOutcome.FAILED);   // 首次失败
        assertThat(agent.investigate(context(2),
                Map.of("query", "up", "time", "1757059260")).outcome())
                .isEqualTo(SingleToolEvidenceAgent.AgentOutcome.EVIDENCE_PRODUCED); // 修正后新证据

        assertThat(guard.progressedFlags).containsExactly(false, true);
        assertThat(observations).hasSize(2);
        assertThat(observations.get(0).outcome())
                .isEqualTo(SingleToolEvidenceAgent.AgentOutcome.FAILED);
        assertThat(observations.get(1).newEvidenceContent()).isTrue();
    }

    // ------------------------------------------------------------------ 假件

    /** 带遥测的全参测试 Agent（生产装配同入口形态） */
    private static final class LoopTestAgent extends SingleToolEvidenceAgent {
        LoopTestAgent(AgentProfile profile, ToolRegistry registry, ToolGateway gateway,
                      EvidenceRepository evidence, RcaToolInvocationLedger ledger,
                      DoomLoopGuard guard, CallTelemetry telemetry) {
            super(profile,
                    DirectReadToolCatalog.spec(DirectReadToolCatalog.TOOL_INSTANT,
                            "metrics.instant", "prometheus"),
                    registry, gateway, evidence, ledger, new ObjectMapper(),
                    new RunBudgetGate(new FixtureSeededBudgetLedger()), guard, telemetry);
        }
    }

    /** 记录进展标志的熔断门（观测收紧语义） */
    private static final class RecordingGuard extends DoomLoopGuard {
        final List<Boolean> progressedFlags = new ArrayList<>();

        RecordingGuard(DoomLoopGuard.Policy policy) {
            super(policy);
        }

        @Override
        public boolean record(UUID taskId, String tool, String actionDigest,
                              boolean progressed) {
            progressedFlags.add(progressed);
            return super.record(taskId, tool, actionDigest, progressed);
        }
    }

    /** 简版账本（无复用读面——同签名成功也走物理路径，纯化收紧语义观测） */
    private static class MemLedger implements RcaToolInvocationLedger {
        @Override
        public void open(InvocationIdentity identity) {
        }

        @Override
        public boolean succeed(UUID operationId) {
            return true;
        }

        @Override
        public boolean fail(UUID operationId, ToolInvocationState terminal,
                            ToolReasonCode reasonCode) {
            return true;
        }
    }

    /** 支持成功复用读面的账本（MC24 同形：result_ref 随成功落档可查） */
    private static final class ReuseMemLedger implements RcaToolInvocationLedger {
        private final List<InvocationRecovery> successful = new ArrayList<>();

        @Override
        public void open(InvocationIdentity identity) {
            successful.add(new InvocationRecovery(identity.operationId(),
                    identity.callSeq(), identity.attemptId(), identity.actionDigest(),
                    ToolInvocationState.PENDING, null));
        }

        @Override
        public boolean markResultRef(UUID operationId, UUID evidenceId) {
            for (int i = 0; i < successful.size(); i++) {
                InvocationRecovery r = successful.get(i);
                if (r.operationId().equals(operationId)) {
                    successful.set(i, new InvocationRecovery(r.operationId(), r.callSeq(),
                            r.attemptId(), r.actionDigest(), r.state(), evidenceId));
                    return true;
                }
            }
            return false;
        }

        @Override
        public boolean succeed(UUID operationId) {
            for (int i = 0; i < successful.size(); i++) {
                InvocationRecovery r = successful.get(i);
                if (r.operationId().equals(operationId)) {
                    successful.set(i, new InvocationRecovery(r.operationId(), r.callSeq(),
                            r.attemptId(), r.actionDigest(), ToolInvocationState.SUCCESS,
                            r.resultRef()));
                    return true;
                }
            }
            return false;
        }

        @Override
        public boolean fail(UUID operationId, ToolInvocationState terminal,
                            ToolReasonCode reasonCode) {
            return true;
        }

        @Override
        public List<InvocationRecovery> findSuccessfulByRun(UUID runId) {
            return successful.stream()
                    .filter(r -> r.state() == ToolInvocationState.SUCCESS
                            && r.resultRef() != null)
                    .toList();
        }
    }

    /** 证据仓储内存件（内容新鲜度判定读面） */
    private static final class MemEvidence implements EvidenceRepository {
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
