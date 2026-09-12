package com.objwww.pr.control.alert.application.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
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
}
