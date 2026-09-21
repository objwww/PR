package com.objwww.pr.control.alert.application.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.objwww.pr.control.alert.application.RunBudgetGate;
import com.objwww.pr.control.alert.domain.agent.AgentPhase;
import com.objwww.pr.control.alert.domain.agent.AgentProfile;
import com.objwww.pr.control.alert.domain.agent.PrimaryCheckpoint;
import com.objwww.pr.control.alert.domain.agent.RcaJevSelection;
import com.objwww.pr.control.alert.domain.agent.RcaModelCallLedger;
import com.objwww.pr.control.alert.domain.agent.RoleRuntimeKind;
import com.objwww.pr.control.alert.domain.budget.BudgetKind;
import com.objwww.pr.control.alert.domain.budget.BudgetProbe;
import com.objwww.pr.control.alert.domain.budget.ReservationKey;
import com.objwww.pr.control.alert.domain.budget.RunBudgetLedger;
import com.objwww.pr.control.alert.domain.repository.RcaJevSelectionPort;
import com.objwww.pr.control.alert.domain.evidence.EvidenceEnvelope;
import com.objwww.pr.control.alert.domain.model.RcaRun;
import com.objwww.pr.control.alert.domain.model.RcaRunState;
import com.objwww.pr.control.alert.domain.model.RcaTask;
import com.objwww.pr.control.alert.domain.model.RcaTaskState;
import com.objwww.pr.control.alert.domain.model.RunTrigger;
import com.objwww.pr.control.alert.domain.model.TaskExecutionBinding;
import com.objwww.pr.control.alert.support.AlertInMemoryStores;
import com.objwww.pr.shared.Digest;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * JE-01：Jev 增强服务行为测试（假客户端 + 内存账本，零触网）——
 * 关闭零调用、冻结判据、冗余短路、保护项不可裁、有界回退、SHADOW 不换输入、
 * 复核缺口反馈与同签名放行。
 */
class JevEnhancementServiceTest {

    private static final Instant NOW = Instant.parse("2026-09-11T09:00:00Z");
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

    private final UUID runId = UUID.randomUUID();
    private final UUID incidentId = UUID.randomUUID();
    private final UUID taskId = UUID.randomUUID();

    private final AlertInMemoryStores.Runs runs = new AlertInMemoryStores.Runs();
    private final AlertInMemoryStores.Tasks tasks = new AlertInMemoryStores.Tasks();
    private final MemBudget budget = new MemBudget();
    private final MemLedger ledger = new MemLedger();

    // ------------------------------------------------------------- 夹具

    private JevEnhancementService service(JevEnhancementService.Mode mode,
            FakeJevClient client, boolean reviewEnabled) {
        return service(mode, client, reviewEnabled, 20, 20, null);
    }

    private JevEnhancementService service(JevEnhancementService.Mode mode,
            FakeJevClient client, boolean reviewEnabled, int minPoolSize,
            int maxSelected) {
        return service(mode, client, reviewEnabled, minPoolSize, maxSelected, null);
    }

    private JevEnhancementService service(JevEnhancementService.Mode mode,
            FakeJevClient client, boolean reviewEnabled, int minPoolSize,
            int maxSelected, FakeSelections selections) {
        return new JevEnhancementService(runs, tasks,
                new RunBudgetGate(budget), ledger, null, client, MAPPER, CLOCK,
                mode, "jev-1.13.0", 0.5, reviewEnabled, 0.042, minPoolSize,
                maxSelected, selections);
    }

    private void seedRunAndTask(boolean jevFlag) {
        runs.insert(new RcaRun(runId, incidentId, 0, RunTrigger.RERUN,
                RcaRunState.RUNNING, Digest.sha256Of("materials"), NOW, NOW,
                null, null, null));
        tasks.insert(new RcaTask(taskId, runId, RcaTask.PRIMARY_INVESTIGATE,
                RcaTaskState.READY, 5, NOW, NOW, Instant.MAX, null, null, 0, 0, 2,
                NOW, NOW, 0));
        if (jevFlag) {
            runs.markJevEnabled(runId, true);
        }
    }

    /** pool 条证据：timeEnd 递减（rows.get(0) 最新；rows.get(pool-1) 最旧=窗口外） */
    private List<EvidenceEnvelope> pool(int count) {
        List<EvidenceEnvelope> rows = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            UUID id = UUID.randomUUID();
            rows.add(EvidenceEnvelope.create(id, runId, taskId, "logs.query",
                    EvidenceEnvelope.SCHEMA_VERSION, 0, "loki", Map.of(),
                    NOW.minusSeconds(120 - i), NOW.minusSeconds(60 - i),
                    Map.of("data", Map.of("result", List.of(Map.of(
                            "ts", "2026-09-11T08:59:00Z", "service", "checkout",
                            "line", "noise line " + i))))));
        }
        return rows;
    }

    private RoleRunner.RoleDriveRequest request(List<String> inputRefs) {
        RcaTask task = new RcaTask(taskId, runId, RcaTask.PRIMARY_INVESTIGATE,
                RcaTaskState.READY, 5, NOW, NOW, Instant.MAX, null, null, 0, 0, 2,
                NOW, NOW, 0);
        TaskExecutionBinding binding = new TaskExecutionBinding(taskId, runId, 0,
                RcaTask.PRIMARY_INVESTIGATE, "primary", "1",
                Digest.sha256Of("primary").hex(), null, null, inputRefs,
                Map.of("type", "object"), null, true,
                TaskExecutionBinding.FailurePolicy.DEAD_ON_FAILURE, NOW);
        AgentProfile profile = new AgentProfile("primary", "1", "prompt", "pv",
                Set.of("logs.query"), Map.of(BudgetKind.STEP, 8L),
                Map.of("type", "object"), Map.of(), AgentPhase.PRIMARY,
                RoleRuntimeKind.BOUNDED_LLM, Set.of(), 8, "single-pass");
        return new RoleRunner.RoleDriveRequest(task, binding, profile,
                new com.objwww.pr.control.alert.application.agent
                        .SingleToolEvidenceAgent.CallContext(runId, taskId,
                        UUID.randomUUID(), 0, 0, null, "1757574000/1757577600"),
                "1757574000", "1757577600");
    }

    private JevEnhancementPort.SelectionInput selectionInput(
            List<EvidenceEnvelope> rows, List<String> inputRefs) {
        return new JevEnhancementPort.SelectionInput(request(inputRefs),
                PrimaryCheckpoint.initial(taskId, runId, 0, NOW),
                new ContextAssembler.EvidenceSnapshot(rows),
                new ContextAssembler.AlertMaterial("HighErrorRate", "checkout",
                        "P1", "支付错误率骤增"));
    }

    // ------------------------------------------------------------- 关闭与短路

    @Test
    void OFF模式零调用_零选材() {
        seedRunAndTask(true);
        FakeJevClient client = new FakeJevClient(Map.of());
        JevEnhancementService off = service(JevEnhancementService.Mode.OFF, client,
                false);
        assertThat(off.selectContext(selectionInput(pool(25), List.of()))).isNull();
        assertThat(client.calls).isEmpty();
    }

    @Test
    void run未冻结开关_零调用() {
        seedRunAndTask(false);
        FakeJevClient client = new FakeJevClient(Map.of());
        assertThat(service(JevEnhancementService.Mode.SELECT, client, false)
                .selectContext(selectionInput(pool(25), List.of()))).isNull();
        assertThat(client.calls).isEmpty();
    }

    @Test
    void 池无冗余_短路不调用() {
        seedRunAndTask(true);
        FakeJevClient client = new FakeJevClient(Map.of());
        assertThat(service(JevEnhancementService.Mode.SELECT, client, false)
                .selectContext(selectionInput(pool(20), List.of()))).isNull();
        assertThat(client.calls).isEmpty();
    }

    @Test
    void 触发线旋钮_调低后小池真实触发_默认零漂移() {
        seedRunAndTask(true);
        FakeJevClient client = new FakeJevClient(Map.of());
        // 默认 20：池 10 不触发（既有语义零漂移）
        assertThat(service(JevEnhancementService.Mode.SELECT, client, false)
                .selectContext(selectionInput(pool(10), List.of()))).isNull();
        assertThat(client.calls).isEmpty();
        // 触发线调到 5：池 6 真实触发（195 生产池常见 6~11）
        FakeJevClient lowLine = new FakeJevClient(Map.of());
        ContextAssembler.EvidenceSelection selection = service(
                JevEnhancementService.Mode.SHADOW, lowLine, false, 5, 10)
                .selectContext(selectionInput(pool(6), List.of()));
        assertThat(selection).isNotNull();
        assertThat(selection.apply()).isFalse(); // SHADOW 只记档
        assertThat(lowLine.calls).hasSize(1);
    }

    // ------------------------------------------------------------- 选材与保护

    @Test
    void SELECT_高分候选入选_保护引用恒入窗() {
        seedRunAndTask(true);
        List<EvidenceEnvelope> rows = pool(25);
        EvidenceEnvelope oldest = rows.get(24);
        EvidenceEnvelope newest = rows.get(0);
        // 绑定承诺引用 = 保护项（Jev 无权裁，JEV-02）
        List<String> protectedRef = List.of(newest.evidenceId().toString());
        // 最旧一条（默认 20 条窗口外）给高分——JEV-01：早期关键证据被选回
        FakeJevClient client = new FakeJevClient(Map.of(
                oldest.evidenceId().toString(), 0.95d));
        ContextAssembler.EvidenceSelection selection = service(
                JevEnhancementService.Mode.SELECT, client, false)
                .selectContext(selectionInput(rows, protectedRef));
        assertThat(selection).isNotNull();
        assertThat(selection.apply()).isTrue();
        assertThat(selection.pinnedRefs())
                .contains(newest.evidenceId().toString())
                .contains(oldest.evidenceId().toString());
        assertThat(selection.pinnedRefs().size()).isLessThanOrEqualTo(20);
        // 低分候选不入选
        assertThat(selection.pinnedRefs().size())
                .isEqualTo(1 + 1);
        // 账本：一次 Jev 调用 = 一行 jev-selector PENDING→SUCCESS + TOKEN 实扣
        assertThat(ledger.rows).hasSize(1);
        assertThat(ledger.rows.get(0).roleId()).isEqualTo("jev-selector");
        assertThat(ledger.rows.get(0).actionSeq()).isGreaterThanOrEqualTo(2_000_000L);
        assertThat(ledger.states.get(ledger.rows.get(0).id())).isEqualTo("SUCCESS");
        assertThat(budget.committed).hasSize(1);
        assertThat(client.calls).hasSize(1);
    }

    @Test
    void SHADOW_只评分不替换窗口() {
        seedRunAndTask(true);
        FakeJevClient client = new FakeJevClient(Map.of());
        ContextAssembler.EvidenceSelection selection = service(
                JevEnhancementService.Mode.SHADOW, client, false)
                .selectContext(selectionInput(pool(25), List.of()));
        assertThat(selection).isNotNull();
        assertThat(selection.apply()).isFalse();
        assertThat(client.calls).hasSize(1);
    }

    @Test
    void 契约失败_有界回退现有窗口_账本落FAILED() {
        seedRunAndTask(true);
        FakeJevClient client = new FakeJevClient(Map.of());
        client.failure = new JevClient.JevClientException(
                JevClient.JevClientException.CONTRACT, "缺答案", false);
        assertThat(service(JevEnhancementService.Mode.SELECT, client, false)
                .selectContext(selectionInput(pool(25), List.of()))).isNull();
        assertThat(ledger.rows).hasSize(1);
        assertThat(ledger.states.get(ledger.rows.get(0).id())).isEqualTo("FAILED");
        // 发送后失败 = provisional 保守占用（不假退款）
        assertThat(budget.provisionals).hasSize(1);
    }

    // ------------------------------------------------------------- 复核

    @Test
    void 选材决策落审计台账_含选中与被裁清单() {
        seedRunAndTask(true);
        List<EvidenceEnvelope> rows = pool(8);
        String highScoreId = rows.get(0).evidenceId().toString();
        FakeJevClient client = new FakeJevClient(Map.of(highScoreId, 0.95d));
        FakeSelections audit = new FakeSelections();
        ContextAssembler.EvidenceSelection selection = service(
                JevEnhancementService.Mode.SELECT, client, false, 5, 10, audit)
                .selectContext(selectionInput(rows, List.of()));
        assertThat(selection).isNotNull();
        assertThat(audit.rows).hasSize(1);
        RcaJevSelection row = audit.rows.get(0);
        assertThat(row.mode()).isEqualTo("SELECT");
        assertThat(row.applied()).isTrue();
        assertThat(row.poolRefs()).hasSize(8);
        assertThat(row.selectedRefs()).containsExactly(highScoreId);
        assertThat(row.omittedRefs()).hasSize(7);
        assertThat(row.modelCallId()).isNotNull();
        assertThat(row.latencyMs()).isNotNull();
        assertThat(row.probabilities()).isNotEmpty();
    }

    @Test
    void 台账缺席_选材照常_降级不破主链() {
        seedRunAndTask(true);
        FakeJevClient client = new FakeJevClient(Map.of());
        assertThat(service(JevEnhancementService.Mode.SHADOW, client, false, 5, 10)
                .selectContext(selectionInput(pool(8), List.of()))).isNotNull();
        assertThat(client.calls).hasSize(1);
    }

    @Test
    void 复核发现缺口_反馈带机器签名() {
        seedRunAndTask(true);
        FakeJevClient client = new FakeJevClient(Map.of());
        JevEnhancementService selectMode = service(
                JevEnhancementService.Mode.SELECT, client, true);
        List<Map<String, Object>> claims = List.of(Map.of(
                "claim_key", "c1", "kind", "ROOT_CAUSE",
                "statement", "PaymentGateway 依赖故障",
                "evidence_refs", List.of("e1")));
        Optional<String> feedback = selectMode.reviewFinal(
                new JevEnhancementPort.ReviewInput(request(List.of()),
                        PrimaryCheckpoint.initial(taskId, runId, 0, NOW), claims,
                        List.of()));
        assertThat(feedback).isPresent();
        assertThat(feedback.get()).startsWith(JevEnhancementService.REVIEW_FEEDBACK_SIG);
        assertThat(ledger.rows.get(ledger.rows.size() - 1).roleId())
                .isEqualTo("jev-reviewer");
    }

    @Test
    void 同签名缺口第二次_放行本次final() {
        seedRunAndTask(true);
        FakeJevClient client = new FakeJevClient(Map.of());
        JevEnhancementService selectMode = service(
                JevEnhancementService.Mode.SELECT, client, true);
        List<Map<String, Object>> claims = List.of(Map.of(
                "claim_key", "c1", "kind", "ROOT_CAUSE",
                "statement", "PaymentGateway 依赖故障", "evidence_refs", List.of()));
        PrimaryCheckpoint checkpoint = PrimaryCheckpoint.initial(taskId, runId, 0, NOW);
        Optional<String> first = selectMode.reviewFinal(
                new JevEnhancementPort.ReviewInput(request(List.of()), checkpoint,
                        claims, List.of()));
        assertThat(first).isPresent();
        PrimaryCheckpoint fedBack = checkpoint.withLastError(first.get(), NOW);
        Optional<String> second = selectMode.reviewFinal(
                new JevEnhancementPort.ReviewInput(request(List.of()), fedBack,
                        claims, List.of()));
        assertThat(second).isEmpty();
    }

    @Test
    void 复核未启用或未冻结_放行() {
        seedRunAndTask(true);
        FakeJevClient client = new FakeJevClient(Map.of());
        List<Map<String, Object>> claims = List.of(Map.of(
                "claim_key", "c1", "kind", "ROOT_CAUSE", "statement", "s",
                "evidence_refs", List.of()));
        // 复核未启用（flag 在）→ 放行零调用
        assertThat(service(JevEnhancementService.Mode.SELECT, client, false)
                .reviewFinal(new JevEnhancementPort.ReviewInput(request(List.of()),
                        PrimaryCheckpoint.initial(taskId, runId, 0, NOW), claims,
                        List.of()))).isEmpty();
        // flag 关 → 放行零调用
        runs.markJevEnabled(runId, false);
        assertThat(service(JevEnhancementService.Mode.SELECT, client, true)
                .reviewFinal(new JevEnhancementPort.ReviewInput(request(List.of()),
                        PrimaryCheckpoint.initial(taskId, runId, 0, NOW), claims,
                        List.of()))).isEmpty();
        assertThat(client.calls).isEmpty();
    }

    // ------------------------------------------------------------- 假件

    /** 可编程假 Jev 客户端：answers 缺省 0.1（低分），指定 id 覆盖 */
    static final class FakeJevClient implements JevClient {
        final List<JevRequest> calls = new ArrayList<>();
        final Map<String, Double> answers;
        RuntimeException failure;

        FakeJevClient(Map<String, Double> answers) {
            this.answers = new LinkedHashMap<>(answers);
        }

        @Override
        public JevAnswer score(JevRequest request) {
            calls.add(request);
            if (failure != null) {
                throw failure;
            }
            Map<String, Double> result = new LinkedHashMap<>();
            request.questions().keySet()
                    .forEach(id -> result.put(id, answers.getOrDefault(id, 0.1d)));
            return new JevAnswer(result, request.model(),
                    new JevUsage(100L, 0L, 100L, false));
        }
    }

    /** 选材审计台账假件：记录 append 行 */
    static final class FakeSelections implements RcaJevSelectionPort {
        final List<RcaJevSelection> rows = new ArrayList<>();

        @Override
        public void append(RcaJevSelection row) {
            rows.add(row);
        }

        @Override
        public List<RcaJevSelection> findByRun(UUID runId, int limit) {
            return rows;
        }
    }

    /** 预算账本假件：reserve 恒准入，记 commit/provisional */
    static final class MemBudget implements RunBudgetLedger {
        final List<ReservationKey> committed = new ArrayList<>();
        final List<ReservationKey> provisionals = new ArrayList<>();

        @Override
        public void ensureLimit(UUID runId, com.objwww.pr.control.alert.domain.budget.BudgetKind kind,
                long limitUnits) {
        }

        @Override
        public BudgetProbe reserve(ReservationKey key, long units) {
            return BudgetProbe.allowed(0, Long.MAX_VALUE);
        }

        @Override
        public void commit(ReservationKey key, long actualUnits) {
            committed.add(key);
        }

        @Override
        public void release(ReservationKey key) {
        }

        @Override
        public void provisional(ReservationKey key) {
            provisionals.add(key);
        }

        @Override
        public void markUnmatched(ReservationKey key) {
        }

        @Override
        public List<ReservationKey> findStaleReservations(Instant olderThan) {
            return List.of();
        }
    }

    /** rca_model_call 假账本：记录 open 行与终态 */
    static final class MemLedger implements RcaModelCallLedger {
        final List<OpenRow> rows = new ArrayList<>();
        final Map<UUID, String> states = new LinkedHashMap<>();

        @Override
        public void open(OpenRow row) {
            rows.add(row);
            states.put(row.id(), "PENDING");
        }

        @Override
        public boolean succeed(UUID id, UsageOutcome usage) {
            // Map.replace(K, old, new) 返回是否替换成功：true 即 CAS 成立
            return states.replace(id, "PENDING", "SUCCESS");
        }

        @Override
        public boolean fail(UUID id, String errorCode) {
            return states.replace(id, "PENDING", "FAILED");
        }

        @Override
        public boolean markUnknown(UUID id) {
            return states.replace(id, "PENDING", "UNKNOWN");
        }

        @Override
        public List<UnsettledRow> findUnsettledByRun(UUID runId) {
            return List.of();
        }

        @Override
        public List<CallUsage> listSettledUsageByRunId(UUID runId) {
            return List.of();
        }
    }
}
