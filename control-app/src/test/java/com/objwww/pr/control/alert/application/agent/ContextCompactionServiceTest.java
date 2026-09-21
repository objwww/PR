package com.objwww.pr.control.alert.application.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.objwww.pr.control.alert.application.agent.ContextCompactionService.Mode;
import com.objwww.pr.control.alert.domain.agent.PrimaryCheckpoint;
import com.objwww.pr.control.alert.domain.agent.RcaModelCallException;
import com.objwww.pr.control.alert.domain.agent.RcaModelOutcome;
import com.objwww.pr.control.alert.domain.budget.BudgetKind;
import com.objwww.pr.control.alert.domain.model.RcaTask;
import com.objwww.pr.control.alert.domain.model.RcaTaskState;
import com.objwww.pr.control.alert.domain.model.TaskExecutionBinding;
import com.objwww.pr.control.alert.domain.repository.ContextSummaryPort;
import com.objwww.pr.control.alert.domain.repository.PrimaryCheckpointRepository;
import com.objwww.pr.control.alert.support.AlertInMemoryStores;
import com.objwww.pr.shared.Digest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * R11 压缩生命周期单测（MA-04，§19.3~19.5；MC13/15/16/17/18/19 的 L0 面）：
 * 五道零模型调用闸（开关/软阈值/次数上限/无锚/同源幂等）、必需引用缺失整候选拒绝
 * （MC13）、模型失败与无节省的有界回退（MC15）、冻结区间增量拼接（MC16）、
 * 提交前检查点复验（MC17，费用已审计面）、动作序保留段与 CAS 提交（MC18）。
 * MC14（语义盲评）/MC20（保留面）/MC33（页面可查）/MC34（三臂对照）为 B/L 面，
 * 195/真窗补证——本类零网零真模。
 */
class ContextCompactionServiceTest {

    private static final Instant NOW = Instant.parse("2026-09-11T09:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    /** 软阈值 0.7 × V=1000 = 700：装配估算按 800 起步越线 */
    private static final int V = 1000;
    private static final int APPROX_OVER = 800;

    private final UUID runId = UUID.randomUUID();
    private final UUID taskId = UUID.randomUUID();
    private final AlertInMemoryStores stores = new AlertInMemoryStores();
    private final AlertInMemoryStores.ContextSummaries summaries =
            new AlertInMemoryStores.ContextSummaries();
    private final ContextAssemblerTest.MemEvidence evidence =
            new ContextAssemblerTest.MemEvidence();
    private final ScriptedModel model = new ScriptedModel();

    private ContextCompactionService service;
    private ContextCompactionService enabledService;

    @BeforeEach
    void setUp() {
        service = serviceOf(false);
        enabledService = serviceOf(true);
        stores.checkpoints.upsert(PrimaryCheckpoint.initial(taskId, runId, 0, NOW)
                .withStepAdvanced("src-digest-1", null, null, null, NOW));
    }

    private ContextCompactionService serviceOf(boolean enabled) {
        return new ContextCompactionService(model, summaries, stores.checkpoints,
                evidence, MAPPER, CLOCK, enabled, 0.7, 0.55, 2, V);
    }

    private ContextCompactionService.CompactionOutcome attempt() {
        return enabledService.afterToolResults(request(), checkpoint(), assembly());
    }

    /** 宿主必需引用 = 绑定 inputRefs ∪ 终局 evidence_refs——候选 refs 必须覆盖 */
    private String requiredRef() {
        return "req-ref-1";
    }

    private ContextAssembler.Assembly assembly() {
        return new ContextAssembler.Assembly("material-prompt", "src-digest-1",
                APPROX_OVER, List.of(), List.of(), null);
    }

    private PrimaryCheckpoint checkpoint() {
        return stores.checkpoints.findByTask(taskId).orElseThrow();
    }

    private RoleRunner.RoleDriveRequest request() {
        RcaTask task = new RcaTask(taskId, runId, RcaTask.PRIMARY_INVESTIGATE,
                RcaTaskState.READY, 5, NOW, NOW, Instant.MAX, null, null, 0, 0, 2, NOW, NOW, 0);
        TaskExecutionBinding binding = new TaskExecutionBinding(taskId, runId, 0,
                RcaTask.PRIMARY_INVESTIGATE, "primary", "1",
                Digest.sha256Of("primary").hex(), null, null,
                List.of(requiredRef()),
                Map.of("type", "object"), null, true,
                TaskExecutionBinding.FailurePolicy.DEAD_ON_FAILURE, NOW);
        return new RoleRunner.RoleDriveRequest(task, binding,
                new com.objwww.pr.control.alert.domain.agent.AgentProfile("primary", "1",
                        "p", "pv", Set.of(), Map.of(BudgetKind.STEP, 8L),
                        Map.of("type", "object"), Map.of(),
                        com.objwww.pr.control.alert.domain.agent.AgentPhase.PRIMARY,
                        com.objwww.pr.control.alert.domain.agent.RoleRuntimeKind.BOUNDED_LLM,
                        Set.of(), 8, "single-pass"),
                new SingleToolEvidenceAgent.CallContext(runId, taskId,
                        UUID.randomUUID(), 0, 0, null, "1757574000/1757577600"),
                "1757574000", "1757577600");
    }

    private static String candidateJson(String summaryText, List<String> refs) {
        return "{\"summary\":\"" + summaryText + "\",\"refs\":"
                + refs.stream().map(r -> "\"" + r + "\"").toList() + "}";
    }

    // ------------------------------------------------------------- 零模型调用闸

    @Test
    @DisplayName("默认关：零模型调用零落档（确定性裁剪恒为第一刀，R1 界面已生效）")
    void disabledByDefaultMeansZeroCallsAndZeroRows() {
        ContextCompactionService.CompactionOutcome outcome =
                service.afterToolResults(request(), checkpoint(), assembly());

        assertThat(outcome.kind()).isEqualTo(ContextCompactionService.OutcomeKind.DISABLED);
        assertThat(model.calls).isZero();
        assertThat(summaries.all()).isEmpty();
    }

    @Test
    @DisplayName("软阈值未达（approx < 0.7×V）：零调用")
    void belowSoftThresholdSkipsCompaction() {
        ContextAssembler.Assembly small = new ContextAssembler.Assembly("p", "src-digest-1",
                100, List.of(), List.of(), null);

        ContextCompactionService.CompactionOutcome outcome =
                enabledService.afterToolResults(request(), checkpoint(), small);

        assertThat(outcome.kind())
                .isEqualTo(ContextCompactionService.OutcomeKind.BELOW_THRESHOLD);
        assertThat(model.calls).isZero();
    }

    @Test
    @DisplayName("MC19：达次数上限（2 次/run）→ 拒绝新增摘要调用，零调用零新档")
    void limitReachedRefusesNewSummaryCall() {
        seedSummary("src-digest-0", 0, 0);
        seedSummary("src-digest-0b", 1, 0);

        ContextCompactionService.CompactionOutcome outcome = attempt();

        assertThat(outcome.kind())
                .isEqualTo(ContextCompactionService.OutcomeKind.LIMIT_REACHED);
        assertThat(model.calls).as("上限后零模型调用").isZero();
        assertThat(summaries.countByRun(runId)).isEqualTo(2);
    }

    @Test
    @DisplayName("无快照锚（首步前未冻结）：NO_SOURCE 零调用")
    void missingSnapshotAnchorIsNoSource() {
        stores.checkpoints.upsert(PrimaryCheckpoint.initial(taskId, runId, 0, NOW));

        ContextCompactionService.CompactionOutcome outcome = attempt();

        assertThat(outcome.kind()).isEqualTo(ContextCompactionService.OutcomeKind.NO_SOURCE);
        assertThat(model.calls).isZero();
    }

    @Test
    @DisplayName("同源已压缩：ALREADY_COMPACTED 幂等零调用")
    void sameSourceAlreadyCompacted() {
        seedSummary("src-digest-1", 1, 0);

        ContextCompactionService.CompactionOutcome outcome = attempt();

        assertThat(outcome.kind())
                .isEqualTo(ContextCompactionService.OutcomeKind.ALREADY_COMPACTED);
        assertThat(model.calls).isZero();
    }

    // ------------------------------------------------------------- 候选校验（MC13/MC15）

    @Test
    @DisplayName("MC13：必需引用缺失 → 整候选拒绝，旧快照不被替换（零落档）")
    void missingRequiredRefsRejectsWholeCandidate() {
        model.script.add(candidateJson("摘要", List.of("unknown-ref")));

        ContextCompactionService.CompactionOutcome outcome = attempt();

        assertThat(outcome.kind())
                .isEqualTo(ContextCompactionService.OutcomeKind.REJECTED_MISSING_REQUIRED);
        assertThat(outcome.detail()).contains(requiredRef());
        assertThat(summaries.all()).as("候选不落档=旧快照不被替换").isEmpty();
    }

    @Test
    @DisplayName("MC15a：模型失败 → 有界回退单次调用，无内联重试，零落档")
    void modelFailureFallsBackBoundedWithoutRetry() {
        model.failure = new RcaModelCallException("PROTOCOL_ERROR", "空内容", false, true, null);

        ContextCompactionService.CompactionOutcome outcome = attempt();

        assertThat(outcome.kind()).isEqualTo(ContextCompactionService.OutcomeKind.MODEL_FAILED);
        assertThat(outcome.detail()).isEqualTo("PROTOCOL_ERROR");
        assertThat(model.calls).as("无内联重试（有界回退）").isEqualTo(1);
        assertThat(summaries.all()).isEmpty();
    }

    @Test
    @DisplayName("MC15b：候选无节省（摘要比原文还长）→ 拒绝，不无限重压缩")
    void noSavingsCandidateRejected() {
        model.script.add(candidateJson("长".repeat(APPROX_OVER * 2),
                List.of(requiredRef())));

        ContextCompactionService.CompactionOutcome outcome = attempt();

        assertThat(outcome.kind())
                .isEqualTo(ContextCompactionService.OutcomeKind.REJECTED_NO_SAVINGS);
        assertThat(summaries.all()).isEmpty();
    }

    @Test
    @DisplayName("MC15c：候选不可解析（非 {summary,refs} JSON）→ 拒绝零落档")
    void unparseableCandidateRejected() {
        model.script.add("自然语言长篇大论，不是 JSON");

        ContextCompactionService.CompactionOutcome outcome = attempt();

        assertThat(outcome.kind())
                .isEqualTo(ContextCompactionService.OutcomeKind.REJECTED_UNPARSEABLE);
        assertThat(summaries.all()).isEmpty();
    }

    // ------------------------------------------------------------- 提交面（MC16/MC17/MC18）

    @Test
    @DisplayName("提交：字段全落档，omitted=值域全集−保留集，digest=正文 sha256")
    void committedSummaryCarriesHonestFields() {
        UUID evidenceId = UUID.randomUUID();
        evidence.rows.add(com.objwww.pr.control.alert.domain.evidence.EvidenceEnvelope
                .create(evidenceId, runId, taskId, "logs.aggregate",
                        com.objwww.pr.control.alert.domain.evidence.EvidenceEnvelope
                                .SCHEMA_VERSION,
                        0, "loki", Map.of(), NOW.minusSeconds(60), NOW, Map.of("k", "v")));
        model.script.add(candidateJson("压缩后的调查上下文",
                List.of(requiredRef(), evidenceId.toString(), "越界引用剥离")));

        ContextCompactionService.CompactionOutcome outcome = attempt();

        assertThat(outcome.kind()).isEqualTo(ContextCompactionService.OutcomeKind.COMMITTED);
        var row = summaries.all().get(0);
        assertThat(row.sourceSnapshotDigest()).isEqualTo("src-digest-1");
        assertThat(row.eventSeqFrom()).isZero();
        assertThat(row.eventSeqTo()).isEqualTo(checkpoint().decisionSeq());
        assertThat(row.schemaVersion())
                .isEqualTo(ContextCompactionService.SCHEMA_VERSION);
        assertThat(row.requiredRefs()).containsExactly(requiredRef());
        assertThat(row.omittedRefs()).as("值域全集−保留集如实留痕").isEmpty();
        assertThat(row.summaryDigest())
                .isEqualTo(Digest.sha256Of(row.summaryText()).value());
        assertThat(row.validationResult()).isEqualTo("REFS_VALIDATED");
        assertThat(row.tokenAfter()).isEqualTo(row.summaryText().length() / 2 + 1);
        assertThat(row.tokenBefore()).isEqualTo(APPROX_OVER);
        assertThat(row.tokenAfter()).isLessThan(row.tokenBefore());
    }

    @Test
    @DisplayName("MC16：冻结区间增量拼接——第二份摘要 from=上一份 to+1，只覆盖新区间")
    void frozenRangeSplicesIncrementally() {
        // 检查点推进到 decision_seq=3，上一份摘要覆盖 [0,2] → 本份应拼 [3,3]
        stores.checkpoints.upsert(checkpoint()
                .withStepAdvanced(null, null, null, null, NOW)
                .withStepAdvanced(null, null, null, null, NOW));
        seedSummary("src-digest-0", 2, 0);
        model.script.add(candidateJson("增量摘要", List.of(requiredRef())));

        ContextCompactionService.CompactionOutcome outcome = attempt();

        assertThat(outcome.kind()).isEqualTo(ContextCompactionService.OutcomeKind.COMMITTED);
        assertThat(outcome.summary().eventSeqFrom()).isEqualTo(3);
        assertThat(outcome.summary().eventSeqTo()).isEqualTo(checkpoint().decisionSeq());
    }

    @Test
    @DisplayName("MC17：压缩期间检查点推进 → SUPERSEDED 不落行（费用已由守卫落账审计）")
    void checkpointAdvanceSupersedesCandidate() {
        model.script.add(candidateJson("压缩摘要", List.of(requiredRef())));
        model.onCall = () -> stores.checkpoints.upsert(checkpoint()
                .withStepAdvanced("src-digest-1", null, null, null, NOW));

        ContextCompactionService.CompactionOutcome outcome = attempt();

        assertThat(outcome.kind()).isEqualTo(ContextCompactionService.OutcomeKind.SUPERSEDED);
        assertThat(summaries.all()).as("候选作废不落行").isEmpty();
        assertThat(model.calls).as("模型已调用=费用已落 rca_model_call 审计面").isEqualTo(1);
    }

    @Test
    @DisplayName("MC18：动作序占保留段（≥10^6）不撞决策序；同源 CAS 双写一胜一拒")
    void reservedActionSeqNamespaceAndCasAppend() {
        model.script.add(candidateJson("摘要一", List.of(requiredRef())));
        ContextCompactionService.CompactionOutcome first = attempt();
        assertThat(first.kind()).isEqualTo(ContextCompactionService.OutcomeKind.COMMITTED);
        assertThat(model.lastAction().actionSeq())
                .as("COMPACTION 保留段动作序（决策序首期为两位数量级）")
                .isGreaterThanOrEqualTo(
                        ContextCompactionService.COMPACTION_ACTION_SEQ_BASE);

        // 同源第二次：服务闸 ALREADY_COMPACTED；绕闸直写 → port CAS 返回既有行
        ContextCompactionService.CompactionOutcome replay = attempt();
        assertThat(replay.kind())
                .isEqualTo(ContextCompactionService.OutcomeKind.ALREADY_COMPACTED);
        var drifted = com.objwww.pr.control.alert.domain.agent.ContextSummary.of(
                UUID.randomUUID(), runId, taskId, 1, "src-digest-1", 0, 9, "pd", "m",
                500, 10, List.of(requiredRef()), List.of(), "漂移候选", "REFS_VALIDATED",
                "test", null, NOW);
        var existing = summaries.append(drifted);
        assertThat(existing.id()).as("同源 CAS：候选丢弃返回既有行")
                .isEqualTo(first.summary().id());
        assertThat(summaries.countByTask(runId, taskId)).isEqualTo(1);
    }

    // ------------------------------------------------------------- CL-07 尝试台账与消费面

    @Test
    @DisplayName("CL-07：COMMITTED 全程 RESERVED→IN_FLIGHT→COMMITTED(summary_id) 终态留痕")
    void attemptLedgerCommittedLifecycle() {
        AlertInMemoryStores.CompactionAttempts ledger =
                new AlertInMemoryStores.CompactionAttempts();
        ContextCompactionService svc = serviceOf(Mode.SHADOW_GENERATE, ledger, null);
        model.script.add(candidateJson("台账摘要", List.of(requiredRef())));

        ContextCompactionService.CompactionOutcome outcome =
                svc.afterToolResults(request(), checkpoint(), assembly());

        assertThat(outcome.kind())
                .isEqualTo(ContextCompactionService.OutcomeKind.COMMITTED);
        assertThat(ledger.rows).hasSize(1);
        var row = ledger.rows.values().iterator().next();
        assertThat(row.state()).isEqualTo("COMMITTED");
        assertThat(row.summaryId()).isEqualTo(outcome.summary().id());
        assertThat(row.settledAt()).as("终态必带 settled_at").isNotNull();
        assertThat(row.sourceContextDigest()).isEqualTo("src-digest-1");
        assertThat(row.expectedRevision()).isEqualTo(checkpoint().revision());
        assertThat(row.logicalActionKey())
                .contains(taskId.toString()).contains("src-digest-1");
    }

    @Test
    @DisplayName("CL-07：FAILED/REJECTED 终态带 error_code 不删行（预算/校验拒绝不静默消单）")
    void attemptLedgerFailedAndRejectedTerminalStates() {
        AlertInMemoryStores.CompactionAttempts failedLedger =
                new AlertInMemoryStores.CompactionAttempts();
        ContextCompactionService failed = serviceOf(Mode.SHADOW_GENERATE, failedLedger, null);
        model.failure = new RcaModelCallException("PROTOCOL_ERROR", "空内容", false, true, null);
        failed.afterToolResults(request(), checkpoint(), assembly());
        var failedRow = failedLedger.rows.values().iterator().next();
        assertThat(failedRow.state()).isEqualTo("FAILED");
        assertThat(failedRow.errorCode()).isEqualTo("PROTOCOL_ERROR");

        model.failure = null;
        AlertInMemoryStores.CompactionAttempts rejectedLedger =
                new AlertInMemoryStores.CompactionAttempts();
        ContextCompactionService rejected =
                serviceOf(Mode.SHADOW_GENERATE, rejectedLedger, null);
        model.script.add(candidateJson("长".repeat(APPROX_OVER * 2), List.of(requiredRef())));
        rejected.afterToolResults(request(), checkpoint(), assembly());
        var rejectedRow = rejectedLedger.rows.values().iterator().next();
        assertThat(rejectedRow.state()).isEqualTo("REJECTED");
        assertThat(rejectedRow.errorCode()).isEqualTo("REJECTED_NO_SAVINGS");
        assertThat(rejectedRow.settledAt()).isNotNull();
    }

    @Test
    @DisplayName("CL-07：SUPERSEDED 终态留痕；同逻辑动作重驱读胜者行收敛（一动作一尝试）")
    void attemptLedgerSupersededAndSingleWinner() {
        AlertInMemoryStores.CompactionAttempts ledger =
                new AlertInMemoryStores.CompactionAttempts();
        ContextCompactionService svc = serviceOf(Mode.SHADOW_GENERATE, ledger, null);
        model.script.add(candidateJson("压缩摘要", List.of(requiredRef())));
        model.onCall = () -> stores.checkpoints.upsert(checkpoint()
                .withStepAdvanced("src-digest-1", null, null, null, NOW));

        ContextCompactionService.CompactionOutcome superseded =
                svc.afterToolResults(request(), checkpoint(), assembly());
        assertThat(superseded.kind())
                .isEqualTo(ContextCompactionService.OutcomeKind.SUPERSEDED);
        assertThat(ledger.rows.values().iterator().next().state())
                .isEqualTo("SUPERSEDED");

        model.onCall = null;
        model.script.add(candidateJson("再试摘要", List.of(requiredRef())));
        ContextCompactionService.CompactionOutcome replay =
                svc.afterToolResults(request(), checkpoint(), assembly());
        assertThat(replay.kind())
                .isEqualTo(ContextCompactionService.OutcomeKind.ALREADY_COMPACTED);
        assertThat(replay.detail()).contains("SUPERSEDED");
        assertThat(ledger.rows).as("同逻辑动作不二次预留").hasSize(1);
        assertThat(model.calls).as("台账收敛后零模型调用").isEqualTo(1);
    }

    @Test
    @DisplayName("CL-07：CONSUME_VALIDATED 提交后经消费口钉指针（围栏身份齐备）；SHADOW 不消费")
    void consumeValidatedInvokesConsumerShadowDoesNot() {
        List<String> consumeCalls = new ArrayList<>();
        ContextCompactionService consuming = serviceOf(Mode.CONSUME_VALIDATED,
                new AlertInMemoryStores.CompactionAttempts(),
                (r, t, owner, leaseEpoch, configEpoch, expectedRevision, actionKey,
                        summaryId) -> {
                    consumeCalls.add(actionKey + "|" + expectedRevision + "|" + summaryId
                            + "|" + owner + "|" + leaseEpoch);
                    return true;
                });
        model.script.add(candidateJson("消费摘要", List.of(requiredRef())));

        ContextCompactionService.CompactionOutcome outcome =
                consuming.afterToolResults(request(), checkpoint(), assembly());

        assertThat(outcome.kind())
                .isEqualTo(ContextCompactionService.OutcomeKind.COMMITTED);
        assertThat(consumeCalls).hasSize(1);
        assertThat(consumeCalls.get(0))
                .contains("summary-consumed:" + outcome.summary().id())
                .contains(String.valueOf(checkpoint().revision()));

        ContextCompactionService shadow = serviceOf(Mode.SHADOW_GENERATE,
                new AlertInMemoryStores.CompactionAttempts(),
                (r, t, owner, leaseEpoch, configEpoch, expectedRevision, actionKey,
                        summaryId) -> {
                    consumeCalls.add("shadow 不应消费");
                    return true;
                });
        model.script.add(candidateJson("影子摘要", List.of(requiredRef())));
        stores.checkpoints.upsert(checkpoint()
                .withStepAdvanced("src-digest-2", null, null, null, NOW));
        shadow.afterToolResults(request(), checkpoint(), assembly());
        assertThat(consumeCalls).as("SHADOW_GENERATE 只生成不消费").hasSize(1);
    }

    @Test
    @DisplayName("CL-07：消费口围栏拒绝（false）→ 保留旧指针不打断主路径（仍 COMMITTED）")
    void consumeFenceRejectionKeepsOldPointer() {
        ContextCompactionService rejected = serviceOf(Mode.CONSUME_VALIDATED,
                new AlertInMemoryStores.CompactionAttempts(),
                (r, t, owner, leaseEpoch, configEpoch, expectedRevision, actionKey,
                        summaryId) -> false);
        model.script.add(candidateJson("拒消费摘要", List.of(requiredRef())));

        ContextCompactionService.CompactionOutcome outcome =
                rejected.afterToolResults(request(), checkpoint(), assembly());

        assertThat(outcome.kind()).as("消费拒绝不打断生成主路径")
                .isEqualTo(ContextCompactionService.OutcomeKind.COMMITTED);
    }

    @Test
    @DisplayName("反证保留（消费面）：记忆槽累计反证=必需引用，摘要候选漏反证整案拒绝")
    void counterEvidenceRefsAreRequiredAndCannotBeOmitted() {
        Map<String, List<String>> slots = new java.util.LinkedHashMap<>();
        slots.put("hypotheses", List.of());
        slots.put("ruled_out", List.of());
        slots.put("counter_evidence_refs", List.of("e-counter-1"));
        slots.put("open_gaps", List.of());
        ContextAssembler.Assembly withCounterRefs = new ContextAssembler.Assembly(
                "material-prompt", "src-digest-1", APPROX_OVER, List.of(), List.of(),
                com.objwww.pr.control.alert.domain.agent.WorkingMemory.ofV2(
                        UUID.randomUUID(), runId, taskId, 0, slots, null, null, NOW));
        model.script.add(candidateJson("漏反证摘要", List.of(requiredRef())));

        ContextCompactionService.CompactionOutcome outcome =
                enabledService.afterToolResults(request(), checkpoint(), withCounterRefs);

        assertThat(outcome.kind())
                .isEqualTo(ContextCompactionService.OutcomeKind.REJECTED_MISSING_REQUIRED);
        assertThat(outcome.detail()).as("反证引用在必需集").contains("e-counter-1");
        assertThat(summaries.all()).as("漏反证候选不落档").isEmpty();
    }

    private ContextCompactionService serviceOf(Mode mode,
            com.objwww.pr.control.alert.domain.repository.CompactionAttemptPort ledger,
            ContextCompactionService.SummaryConsumer consumer) {
        return new ContextCompactionService(model, summaries, stores.checkpoints,
                evidence, MAPPER, CLOCK, mode, 0.7, 0.55, 2, V, ledger, consumer);
    }

    // ------------------------------------------------------------- 夹具

    private void seedSummary(String source, long eventSeqTo, int minutesAgo) {
        summaries.append(com.objwww.pr.control.alert.domain.agent.ContextSummary.of(
                UUID.randomUUID(), runId, taskId, 1, source, 0, eventSeqTo,
                "seed-prompt-digest", "seed-model", 600, 100,
                List.of(), List.of(), "种子摘要", "SEED", "seed", null,
                NOW.minus(Duration.ofMinutes(minutesAgo + 1))));
    }

    /** 脚本化压缩模型口（零真网）：可注入失败/调用期副作用 */
    private static final class ScriptedModel
            implements ContextCompactionService.CompactionModelPort {
        final java.util.Queue<String> script = new java.util.ArrayDeque<>();
        final List<RcaActionGuard.ModelAction> actions = new ArrayList<>();
        volatile RuntimeException failure;
        volatile Runnable onCall;
        int calls;

        RcaActionGuard.ModelAction lastAction() {
            return actions.get(actions.size() - 1);
        }

        @Override
        public RcaModelOutcome call(RcaActionGuard.ModelAction action, String prompt,
                int maxTokens) throws RcaModelCallException {
            calls++;
            actions.add(action);
            RuntimeException boom = failure;
            if (boom != null) {
                throw (RcaModelCallException) boom;
            }
            Runnable hook = onCall;
            if (hook != null) {
                hook.run();
            }
            String content = script.poll();
            if (content == null) {
                throw new IllegalStateException("脚本空跑");
            }
            return new RcaModelOutcome(UUID.randomUUID(), content, "model-rca", 30,
                    false, "route-rca", UUID.randomUUID());
        }
    }

    // ------------------------------------------------------------- 资产钉版面（EN-02/MC36）

    @Test
    @DisplayName("资产钉版：directiveTemplate = kind/schema/输出协议/策略的稳定登记内容")
    void directiveTemplateIsStableAssetContent() {
        Map<String, Object> template = service.directiveTemplate();

        assertThat(template.get("kind")).isEqualTo("context-compaction-directive");
        assertThat(template.get("schema_version"))
                .isEqualTo(ContextCompactionService.SCHEMA_VERSION);
        assertThat((String) template.get("output_protocol"))
                .as("输出协议与 compactionPrompt 同源（OUTPUT_PROTOCOL 常量）")
                .contains("\"summary\"").contains("\"refs\"");
        assertThat((String) template.get("messages_template"))
                .as("PROMPT kind 契约：模型可见指令面非 blank（ReleaseAsset.of 校验）")
                .isNotBlank().contains("COMPACTION").contains("\"summary\"")
                .contains("{{source_snapshot_digest}}").contains("{{required_refs}}");
        @SuppressWarnings("unchecked")
        List<Object> variables = (List<Object>) template.get("variables_schema");
        assertThat(variables)
                .as("PROMPT kind 契约：variables_schema 非空且覆盖模板全部 {{var}} 占位符")
                .isNotEmpty()
                .containsExactlyInAnyOrder("source_snapshot_digest", "event_seq_from",
                        "event_seq_to", "required_refs");
        // 注册门直证：登记内容过 ReleaseAsset.of 的 PROMPT 形状校验（P02）——
        // 启动期登记失败不阻断但必须不发生，此断言即"登记必成"的 L0 锚。
        com.objwww.pr.control.release.domain.model.ReleaseAsset.of(
                com.objwww.pr.control.release.domain.model.ReleaseAsset.KIND_PROMPT,
                template, "test", NOW);

        @SuppressWarnings("unchecked")
        Map<String, Object> policy = (Map<String, Object>) template.get("policy");
        assertThat(policy)
                .containsEntry("mode", "OFF")
                .containsEntry("soft_threshold", 0.7)
                .containsEntry("target_ratio", 0.55)
                .containsEntry("max_per_run", 2)
                .containsEntry("max_input_tokens", V);
    }

    @Test
    @DisplayName("资产钉版：policyView 与运行时旋钮同源（enabled 双构造各映模式）")
    void policyViewMirrorsRuntimeKnobs() {
        assertThat(service.policyView().get("mode")).isEqualTo("OFF");
        assertThat(enabledService.policyView().get("mode")).isEqualTo("SHADOW_GENERATE");
        assertThat(enabledService.policyView().get("summary_max_tokens"))
                .isEqualTo(ContextCompactionService.SUMMARY_MAX_TOKENS);
        assertThat(service.policyView().get("chars_per_token_estimate"))
                .isEqualTo(ContextCompactionService.CHARS_PER_TOKEN);
    }

    // ------------------------------------------------------------- D06 观测面（CTX-12）

    @Test
    @DisplayName("D06/CTX-12：CONSUME 成功——观测面记录实际被消费（consumed=true）与策略指纹")
    void consumptionObservationRecordsConsumedAndPolicy() {
        ContextCompactionService consuming = serviceOf(Mode.CONSUME_VALIDATED,
                new AlertInMemoryStores.CompactionAttempts(),
                (r, t, owner, leaseEpoch, configEpoch, expectedRevision, actionKey,
                        summaryId) -> true);
        model.script.add(candidateJson("消费观测摘要", List.of(requiredRef())));

        ContextCompactionService.CompactionOutcome outcome =
                consuming.afterToolResults(request(), checkpoint(), assembly());

        assertThat(outcome.kind()).isEqualTo(ContextCompactionService.OutcomeKind.COMMITTED);
        var obs = outcome.consumption();
        assertThat(obs.mode()).isEqualTo("CONSUME_VALIDATED");
        assertThat(obs.consumerInvoked()).isTrue();
        assertThat(obs.consumed()).isTrue();
        assertThat(obs.policyDigest()).as("策略指纹同源 V102 台账，非空").isNotBlank();
    }

    @Test
    @DisplayName("D06/CTX-12：SHADOW 只生成不消费——consumerInvoked=false，不得计入消费效果")
    void shadowObservationRecordsNotConsumed() {
        ContextCompactionService shadow = serviceOf(Mode.SHADOW_GENERATE,
                new AlertInMemoryStores.CompactionAttempts(),
                (r, t, owner, leaseEpoch, configEpoch, expectedRevision, actionKey,
                        summaryId) -> true);
        model.script.add(candidateJson("影子观测摘要", List.of(requiredRef())));

        ContextCompactionService.CompactionOutcome outcome =
                shadow.afterToolResults(request(), checkpoint(), assembly());

        assertThat(outcome.kind()).isEqualTo(ContextCompactionService.OutcomeKind.COMMITTED);
        assertThat(outcome.consumption().mode()).isEqualTo("SHADOW_GENERATE");
        assertThat(outcome.consumption().consumerInvoked())
                .as("SHADOW_GENERATE 只留档不换输入（REPORT 步骤 7）").isFalse();
        assertThat(outcome.consumption().consumed()).isNull();
    }

    @Test
    @DisplayName("D06/CTX-12：生成成功但消费围栏拒绝——consumed=false 不计入消费效果，原路径继续成本仍计")
    void consumeFenceRejectionNotCountedAsConsumedEffect() {
        ContextCompactionService rejected = serviceOf(Mode.CONSUME_VALIDATED,
                new AlertInMemoryStores.CompactionAttempts(),
                (r, t, owner, leaseEpoch, configEpoch, expectedRevision, actionKey,
                        summaryId) -> false);
        model.script.add(candidateJson("拒消费观测摘要", List.of(requiredRef())));

        ContextCompactionService.CompactionOutcome outcome =
                rejected.afterToolResults(request(), checkpoint(), assembly());

        assertThat(outcome.kind()).as("候选已提交，原路径继续")
                .isEqualTo(ContextCompactionService.OutcomeKind.COMMITTED);
        assertThat(outcome.consumption().consumerInvoked()).isTrue();
        assertThat(outcome.consumption().consumed())
                .as("KEPT_OLD_POINTER：不得计入摘要消费后效果（CTX-12）").isFalse();
        assertThat(model.calls).as("摘要成本仍计入").isEqualTo(1);
    }

    @Test
    @DisplayName("D06：非提交路径无消费观测（闸/拒绝/作废 consumption=null）")
    void nonCommittedOutcomesCarryNoConsumptionObservation() {
        ContextCompactionService.CompactionOutcome disabled =
                service.afterToolResults(request(), checkpoint(), assembly());
        assertThat(disabled.consumption()).isNull();

        model.script.add(candidateJson("长".repeat(APPROX_OVER * 2), List.of(requiredRef())));
        ContextCompactionService.CompactionOutcome noSavings = attempt();
        assertThat(noSavings.kind())
                .isEqualTo(ContextCompactionService.OutcomeKind.REJECTED_NO_SAVINGS);
        assertThat(noSavings.consumption()).isNull();
    }

    @Test
    @DisplayName("D06：策略指纹随旋钮变化（同旋钮同指纹——模式/预算即逻辑动作身份）")
    void policyDigestTracksKnobs() {
        ContextCompactionService shadow = serviceOf(Mode.SHADOW_GENERATE,
                new AlertInMemoryStores.CompactionAttempts(), null);
        ContextCompactionService other = new ContextCompactionService(model, summaries,
                stores.checkpoints, evidence, MAPPER, CLOCK, Mode.SHADOW_GENERATE,
                0.8, 0.55, 2, V, new AlertInMemoryStores.CompactionAttempts(), null);
        model.script.add(candidateJson("指纹摘要一", List.of(requiredRef())));
        String digestA = shadow.afterToolResults(request(), checkpoint(), assembly())
                .consumption().policyDigest();

        stores.checkpoints.upsert(checkpoint()
                .withStepAdvanced("src-digest-2", null, null, null, NOW));
        model.script.add(candidateJson("指纹摘要二", List.of(requiredRef())));
        String digestB = other.afterToolResults(request(), checkpoint(), assembly())
                .consumption().policyDigest();

        assertThat(digestA).as("软阈值不同即不同策略指纹").isNotEqualTo(digestB);
    }
}
