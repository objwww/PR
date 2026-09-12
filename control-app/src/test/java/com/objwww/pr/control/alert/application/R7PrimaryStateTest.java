package com.objwww.pr.control.alert.application;

import com.objwww.pr.control.alert.application.agent.AgentRegistry;
import com.objwww.pr.control.alert.domain.agent.AgentPhase;
import com.objwww.pr.control.alert.domain.agent.AgentProfile;
import com.objwww.pr.control.alert.domain.agent.DelegationDecision;
import com.objwww.pr.control.alert.domain.agent.PrimaryCheckpoint;
import com.objwww.pr.control.alert.domain.agent.PrimaryDecision;
import com.objwww.pr.control.alert.domain.agent.RoleRuntimeKind;
import com.objwww.pr.control.alert.domain.budget.BudgetKind;
import com.objwww.pr.control.alert.domain.model.RcaRun;
import com.objwww.pr.control.alert.domain.model.RcaRunState;
import com.objwww.pr.control.alert.domain.model.RcaTask;
import com.objwww.pr.control.alert.domain.model.RcaTaskState;
import com.objwww.pr.control.alert.domain.model.RunTrigger;
import com.objwww.pr.control.alert.domain.repository.TaskEdgeRepository;
import com.objwww.pr.control.alert.domain.dag.DependencyType;
import com.objwww.pr.control.alert.domain.dag.TaskEdge;
import com.objwww.pr.control.alert.support.AlertInMemoryStores;
import com.objwww.pr.shared.Digest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionOperations;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * R7-X4/X11 单测（v2.1 §三/§四，假件面）：互斥 Decision 严格解析、主模式启动幂等
 * （检查点不重置）、委派裁决上限/去重/目录校验（RD04/RD06/RD13 面）、幂等唤醒
 * （RD09 检查点面 + 轮次隔离）、RX07 轮次身份独立。
 * 真 PG 事务原子性（RD08 提交前后硬杀）由具名 IT 在 195 补证据。
 */
class R7PrimaryStateTest {

    private static final Instant NOW = Instant.parse("2026-09-11T08:00:00Z");

    private final AlertInMemoryStores stores = new AlertInMemoryStores();
    private final EdgeStore edges = new EdgeStore();
    private final AgentRegistry agents = new AgentRegistry(List.of(
            primaryProfile(), expertProfile("metrics-expert"), expertProfile("logs-expert")));
    private final AlertClock clock = () -> NOW;

    @BeforeEach
    void setUp() {
        stores.runs.insert(new RcaRun(runId, UUID.randomUUID(), 0, RunTrigger.RERUN,
                RcaRunState.QUEUED, Digest.sha256Of("material"), NOW, NOW, null,
                null, null));
    }

    private final UUID runId = UUID.randomUUID();

    private DeterministicSupervisor supervisor() {
        return new DeterministicSupervisor(
                new PlanCompiler(agents, stores.tasks, edges, stores.bindings, inPlaceTx()),
                new DagExecutionService(edges, stores.tasks),
                stores.runs, stores.tasks, stores.bindings, stores.checkpoints,
                stores.delegationDecisions, agents, inPlaceTx(), clock);
    }

    // ------------------------------------------------- X11 互斥 Decision 严格解析

    @Test
    void decisionParse两个分支并存_互斥违规拒绝() {
        Map<String, Object> mixed = new LinkedHashMap<>();
        mixed.put("tool_call", Map.of("tool_id", "logs.query", "args", Map.of()));
        mixed.put("final", Map.of("claims", List.of(), "missing_information", List.of()));

        assertThatThrownBy(() -> PrimaryDecision.parse(mixed))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("互斥");
    }

    @Test
    void decisionParse零分支_拒绝() {
        assertThatThrownBy(() -> PrimaryDecision.parse(Map.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("恰好一个分支");
    }

    @Test
    void decisionParse未声明字段_拒绝不静默裁字段() {
        assertThatThrownBy(() -> PrimaryDecision.parse(Map.of(
                "tool_call", Map.of("tool_id", "logs.query", "args", Map.of()),
                "extra", 1)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("未声明字段");

        assertThatThrownBy(() -> PrimaryDecision.parse(Map.of(
                "tool_call", Map.of("tool_id", "logs.query", "args", Map.of(), "tool", "x"))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("未声明字段");

        assertThatThrownBy(() -> PrimaryDecision.parse(Map.of("delegate", Map.of(
                "requests", List.of(Map.of("gap_id", "g", "role_id", "metrics-expert",
                        "question", "q", "input_refs", List.of(), "scope", Map.of(),
                        "requested_budget", 5, "bonus", true))))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("未声明字段");
    }

    @Test
    void decisionParse三分支合法形状_各自就位() {
        PrimaryDecision tool = PrimaryDecision.parse(Map.of(
                "tool_call", Map.of("tool_id", "logs.query", "args", Map.of("q", "err"))));
        assertThat(tool.branch()).isEqualTo(PrimaryDecision.Branch.TOOL_CALL);
        assertThat(tool.toolCall().toolId()).isEqualTo("logs.query");
        assertThat(tool.toolCall().args()).containsEntry("q", "err");

        PrimaryDecision delegate = PrimaryDecision.parse(Map.of("delegate", Map.of(
                "requests", List.of(
                        Map.of("gap_id", "gap-1", "role_id", "metrics-expert",
                                "question", "指标异常是否传播",
                                "input_refs", List.of("snapshot:r0"),
                                "scope", Map.of("window", "5m"),
                                "requested_budget", 4)))));
        assertThat(delegate.branch()).isEqualTo(PrimaryDecision.Branch.DELEGATE);
        assertThat(delegate.delegate()).hasSize(1);
        assertThat(delegate.delegate().get(0).gapId()).isEqualTo("gap-1");
        assertThat(delegate.delegate().get(0).requestedBudget()).isEqualTo(4L);

        PrimaryDecision fin = PrimaryDecision.parse(Map.of("final", Map.of(
                "claims", List.of(Map.of("claim_key", "c1", "kind", "ROOT_CAUSE",
                        "statement", "s", "evidence_refs", List.of("logs:a"))),
                "missing_information", List.of("m1"))));
        assertThat(fin.branch()).isEqualTo(PrimaryDecision.Branch.FINAL);
        assertThat(fin.finalAnswer().claims()).hasSize(1);
        assertThat(fin.finalAnswer().missingInformation()).containsExactly("m1");
    }

    @Test
    void decisionParse必填字段缺失_拒绝() {
        // delegate request 缺 question
        assertThatThrownBy(() -> PrimaryDecision.parse(Map.of("delegate", Map.of(
                "requests", List.of(Map.of("gap_id", "g", "role_id", "metrics-expert",
                        "input_refs", List.of()))))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("question");
        // delegate 空批
        assertThatThrownBy(() -> PrimaryDecision.parse(
                Map.of("delegate", Map.of("requests", List.of()))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("至少一个");
    }

    // ------------------------------------------------- X4/X11 主模式启动

    @Test
    void startPrimary只建主节点_检查点就绪_推进后可领取() {
        DeterministicSupervisor.StartResult r =
                supervisor().startPrimary(runId, primaryProfile(), Set.of("snapshot:r0"));

        assertThat(r.outcome()).isEqualTo(DeterministicSupervisor.StartOutcome.STARTED);
        List<RcaTask> tasks = stores.tasks.findByRunId(runId);
        assertThat(tasks).hasSize(1);
        assertThat(tasks.get(0).taskKey()).isEqualTo(RcaTask.PRIMARY_INVESTIGATE);
        assertThat(tasks.get(0).roundId()).isZero();

        // 主任务同样走冻结绑定（恢复不猜角色）
        assertThat(stores.bindings.findByTask(tasks.get(0).id())).isPresent();
        assertThat(stores.bindings.findByTask(tasks.get(0).id()).orElseThrow().roleDigest())
                .isEqualTo(primaryProfile().digest());

        // 检查点初始态 PRIMARY_READY/零计数
        PrimaryCheckpoint cp = stores.checkpoints.findByTask(tasks.get(0).id()).orElseThrow();
        assertThat(cp.phase()).isEqualTo(PrimaryCheckpoint.Phase.PRIMARY_READY);
        assertThat(cp.stepsUsed()).isZero();
        assertThat(cp.batchesUsed()).isZero();

        supervisor().advance(runId);
        assertThat(stores.tasks.findByRunId(runId).get(0).state()).isEqualTo(RcaTaskState.READY);
    }

    @Test
    void startPrimary重驱动幂等_检查点计数不重置() {
        DeterministicSupervisor supervisor = supervisor();
        supervisor.startPrimary(runId, primaryProfile(), Set.of("snapshot:r0"));
        RcaTask primary = stores.tasks.findByRunId(runId).get(0);
        // 模拟已推进 2 步
        stores.checkpoints.upsert(new PrimaryCheckpoint(primary.id(), runId, 0,
                PrimaryCheckpoint.Phase.PRIMARY_READY, 2, 2, 0, "digest-x",
                null, null,
                List.of(), List.of(), null, NOW));

        DeterministicSupervisor.StartResult again =
                supervisor.startPrimary(runId, primaryProfile(), Set.of("snapshot:r0"));

        assertThat(again.outcome())
                .isEqualTo(DeterministicSupervisor.StartOutcome.ALREADY_STARTED);
        assertThat(stores.tasks.findByRunId(runId)).as("零重复任务").hasSize(1);
        assertThat(stores.checkpoints.findByTask(primary.id()).orElseThrow().stepsUsed())
                .as("检查点不重置").isEqualTo(2);
    }

    @Test
    void startPrimary非PRIMARY阶段Profile_拒绝且failClosed() {
        DeterministicSupervisor.StartResult r = supervisor().startPrimary(runId,
                expertProfile("metrics-expert"), Set.of("snapshot:r0"));

        assertThat(r.outcome()).isEqualTo(DeterministicSupervisor.StartOutcome.PROPOSAL_REJECTED);
        assertThat(stores.tasks.findByRunId(runId)).as("零落图").isEmpty();
        assertThat(stores.runs.findById(runId).orElseThrow().state())
                .isEqualTo(RcaRunState.FAILED);
    }

    // ------------------------------------------------- X4 委派裁决

    @Test
    void adjudicate获批批_原子建READY子任务加绑定加台账_检查点进WAITING_CHILDREN() {
        UUID primaryId = startPrimary();

        PrimaryDecision decision = PrimaryDecision.parse(Map.of("delegate", Map.of(
                "requests", List.of(
                        request("gap-1", "metrics-expert"),
                        request("gap-2", "logs-expert")))));
        DeterministicSupervisor.Adjudication result =
                supervisor().adjudicateDelegation(runId, primaryId, decision);

        assertThat(result.batchAccepted()).isTrue();
        assertThat(result.decisions()).allSatisfy(d ->
                assertThat(d.status()).isEqualTo(DelegationDecision.Status.APPROVED));

        // 子任务出生 READY（父子归属不是完成依赖边——不建 BLOCKED 等待父）
        List<RcaTask> children = stores.tasks.findByRunId(runId).stream()
                .filter(t -> !t.taskKey().equals(RcaTask.PRIMARY_INVESTIGATE))
                .toList();
        assertThat(children).hasSize(2);
        assertThat(children).allSatisfy(t -> {
            assertThat(t.state()).isEqualTo(RcaTaskState.READY);
            assertThat(t.roundId()).isEqualTo(1);
        });
        assertThat(edges.rows).as("父子归属不落完成依赖边").isEmpty();

        // 每个子任务带冻结绑定（含 parent_request_id = 裁决行 id）
        for (RcaTask child : children) {
            assertThat(stores.bindings.findByTask(child.id())).isPresent();
            assertThat(stores.bindings.findByTask(child.id()).orElseThrow().parentRequestId())
                    .isNotNull();
        }

        // 检查点推进：round+1、batches+1、WAITING_CHILDREN
        PrimaryCheckpoint cp = stores.checkpoints.findByTask(primaryId).orElseThrow();
        assertThat(cp.phase()).isEqualTo(PrimaryCheckpoint.Phase.WAITING_CHILDREN);
        assertThat(cp.roundId()).isEqualTo(1);
        assertThat(cp.batchesUsed()).isEqualTo(1);
        assertThat(cp.decisionSeq()).isEqualTo(2);
    }

    @Test
    void adjudicate批形状超限_整批拒绝零子任务_状态不动() {
        UUID primaryId = startPrimary();

        PrimaryDecision decision = PrimaryDecision.parse(Map.of("delegate", Map.of(
                "requests", List.of(
                        request("gap-1", "metrics-expert"),
                        request("gap-2", "logs-expert"),
                        request("gap-3", "metrics-expert")))));
        DeterministicSupervisor.Adjudication result =
                supervisor().adjudicateDelegation(runId, primaryId, decision);

        assertThat(result.batchAccepted()).isFalse();
        assertThat(result.decisions()).allSatisfy(d -> {
            assertThat(d.status()).isEqualTo(DelegationDecision.Status.REJECTED);
            assertThat(d.rejectReason())
                    .isEqualTo(DeterministicSupervisor.REJ_BATCH_SHAPE);
        });
        assertThat(stores.tasks.findByRunId(runId)).hasSize(1);
        PrimaryCheckpoint cp = stores.checkpoints.findByTask(primaryId).orElseThrow();
        assertThat(cp.phase()).isEqualTo(PrimaryCheckpoint.Phase.PRIMARY_READY);
        assertThat(cp.batchesUsed()).isZero();
    }

    @Test
    void adjudicate未注册role_结构化拒绝零子任务_RD06() {
        UUID primaryId = startPrimary();

        PrimaryDecision decision = PrimaryDecision.parse(Map.of("delegate", Map.of(
                "requests", List.of(request("gap-x", "ghost-role")))));
        DeterministicSupervisor.Adjudication result =
                supervisor().adjudicateDelegation(runId, primaryId, decision);

        assertThat(result.batchAccepted()).isFalse();
        assertThat(result.decisions().get(0).rejectReason())
                .isEqualTo(DeterministicSupervisor.REJ_ROLE_UNKNOWN);
        assertThat(stores.tasks.findByRunId(runId)).as("零专家任务").hasSize(1);
    }

    @Test
    void adjudicate批内同gap与跨决策同gap_分别拒绝_RD04() {
        UUID primaryId = startPrimary();

        // 批内重复 gap
        DeterministicSupervisor.Adjudication first = supervisor().adjudicateDelegation(
                runId, primaryId, PrimaryDecision.parse(Map.of("delegate", Map.of(
                        "requests", List.of(
                                request("gap-1", "metrics-expert"),
                                request("gap-1", "logs-expert"))))));
        assertThat(first.decisions())
                .extracting(DelegationDecision::status)
                .containsExactly(DelegationDecision.Status.APPROVED,
                        DelegationDecision.Status.REJECTED);
        assertThat(first.decisions().get(1).rejectReason())
                .isEqualTo(DeterministicSupervisor.REJ_DUPLICATE_GAP_IN_BATCH);

        // 等待期结束（手动唤醒）后再提同一 gap：台账已裁定 → 拒
        finishChildren(primaryId, 1);
        supervisor().wakePrimary(runId, primaryId);
        DeterministicSupervisor.Adjudication second = supervisor().adjudicateDelegation(
                runId, primaryId, PrimaryDecision.parse(Map.of("delegate", Map.of(
                        "requests", List.of(request("gap-1", "metrics-expert"))))));
        assertThat(second.decisions().get(0).rejectReason())
                .isEqualTo(DeterministicSupervisor.REJ_GAP_ALREADY_ADJUDICATED);
    }

    @Test
    void adjudicate第三批被拒_计数不重置_不永久等待_RD13() {
        UUID primaryId = startPrimary();
        DeterministicSupervisor supervisor = supervisor();

        for (int batch = 0; batch < 2; batch++) {
            supervisor.adjudicateDelegation(runId, primaryId, PrimaryDecision.parse(
                    Map.of("delegate", Map.of("requests", List.of(
                            request("gap-" + batch + "-a", "metrics-expert"),
                            request("gap-" + batch + "-b", "logs-expert"))))));
            finishChildren(primaryId, batch + 1);
            supervisor.wakePrimary(runId, primaryId);
        }
        int tasksBefore = stores.tasks.findByRunId(runId).size();
        int stepsBefore = stores.checkpoints.findByTask(primaryId).orElseThrow().stepsUsed();

        DeterministicSupervisor.Adjudication third = supervisor.adjudicateDelegation(
                runId, primaryId, PrimaryDecision.parse(Map.of("delegate", Map.of(
                        "requests", List.of(request("gap-3", "metrics-expert"))))));

        assertThat(third.decisions().get(0).rejectReason())
                .isEqualTo(DeterministicSupervisor.REJ_DELEGATION_BUDGET_EXHAUSTED);
        assertThat(stores.tasks.findByRunId(runId)).as("不新建 task").hasSize(tasksBefore);
        PrimaryCheckpoint cp = stores.checkpoints.findByTask(primaryId).orElseThrow();
        assertThat(cp.batchesUsed()).isEqualTo(2);
        assertThat(cp.stepsUsed()).as("父 steps 不重置").isEqualTo(stepsBefore);
        assertThat(cp.phase()).as("全拒不进等待，主循环可继续收尾")
                .isEqualTo(PrimaryCheckpoint.Phase.PRIMARY_READY);
    }

    @Test
    void adjudicateRun任务上限_越限请求拒RD28面() {
        UUID primaryId = startPrimary();
        // 预置非 driver 任务到 7 个（primary 已是 1），上限 8：批内第 2 个越限
        for (int i = 0; i < 6; i++) {
            stores.tasks.insert(new RcaTask(UUID.randomUUID(), runId, "filler-" + i,
                    RcaTaskState.DONE, 5, NOW, NOW, Instant.MAX, null, null, 0, 0, 2,
                    NOW, NOW, 0));
        }

        DeterministicSupervisor.Adjudication result = supervisor().adjudicateDelegation(
                runId, primaryId, PrimaryDecision.parse(Map.of("delegate", Map.of(
                        "requests", List.of(
                                request("gap-a", "metrics-expert"),
                                request("gap-b", "logs-expert"))))));

        assertThat(result.decisions())
                .extracting(DelegationDecision::status, DelegationDecision::rejectReason)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple(
                                DelegationDecision.Status.APPROVED, null),
                        org.assertj.core.groups.Tuple.tuple(
                                DelegationDecision.Status.REJECTED,
                                DeterministicSupervisor.REJ_RUN_TASK_CAP));
    }

    @Test
    void adjudicate等待期拒绝再裁决_相位守卫拒() {
        UUID primaryId = startPrimary();
        supervisor().adjudicateDelegation(runId, primaryId, PrimaryDecision.parse(
                Map.of("delegate", Map.of("requests", List.of(
                        request("gap-1", "metrics-expert"))))));

        assertThatThrownBy(() -> supervisor().adjudicateDelegation(runId, primaryId,
                PrimaryDecision.parse(Map.of("delegate", Map.of("requests",
                        List.of(request("gap-2", "logs-expert")))))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("PRIMARY_READY");
    }

    // ------------------------------------------------- X4 幂等唤醒（RD09 检查点面）

    @Test
    void wake子任务全终态才唤醒_含死亡也是终态_有界恢复() {
        UUID primaryId = startPrimary();
        DeterministicSupervisor supervisor = supervisor();
        supervisor.adjudicateDelegation(runId, primaryId, PrimaryDecision.parse(
                Map.of("delegate", Map.of("requests", List.of(
                        request("gap-1", "metrics-expert"),
                        request("gap-2", "logs-expert"))))));
        List<RcaTask> children = stores.tasks.findByRunId(runId).stream()
                .filter(t -> !t.taskKey().equals(RcaTask.PRIMARY_INVESTIGATE)).toList();

        // 一个仍 RUNNING → 不唤醒
        stores.tasks.transitionState(children.get(0).id(), RcaTaskState.READY,
                RcaTaskState.RUNNING);
        assertThat(supervisor.wakePrimary(runId, primaryId))
                .isEqualTo(DeterministicSupervisor.WakeOutcome.STILL_WAITING);

        // 死亡也是终态（DEAD_ON_FAILURE 由主 Agent 下一步看见，不复活）
        stores.tasks.transitionState(children.get(0).id(), RcaTaskState.RUNNING,
                RcaTaskState.DEAD);
        assertThat(supervisor.wakePrimary(runId, primaryId))
                .isEqualTo(DeterministicSupervisor.WakeOutcome.STILL_WAITING);

        finishChildren(primaryId, 1);
        assertThat(supervisor.wakePrimary(runId, primaryId))
                .isEqualTo(DeterministicSupervisor.WakeOutcome.WOKEN);
        assertThat(stores.checkpoints.findByTask(primaryId).orElseThrow().phase())
                .isEqualTo(PrimaryCheckpoint.Phase.PRIMARY_READY);
        // 幂等：再唤醒 = NOT_WAITING
        assertThat(supervisor.wakePrimary(runId, primaryId))
                .isEqualTo(DeterministicSupervisor.WakeOutcome.NOT_WAITING);
    }

    @Test
    void wake轮次隔离_旧轮子任务终态不误醒新轮等待() {
        UUID primaryId = startPrimary();
        DeterministicSupervisor supervisor = supervisor();
        // 第一批（round1）
        supervisor.adjudicateDelegation(runId, primaryId, PrimaryDecision.parse(
                Map.of("delegate", Map.of("requests", List.of(
                        request("gap-1", "metrics-expert"))))));
        finishChildren(primaryId, 1);
        supervisor.wakePrimary(runId, primaryId);
        // 第二批（round2）
        supervisor.adjudicateDelegation(runId, primaryId, PrimaryDecision.parse(
                Map.of("delegate", Map.of("requests", List.of(
                        request("gap-2", "logs-expert"))))));

        // round2 子任务未终态：即使 round1 全终态也不唤醒（只复判当前轮）
        assertThat(supervisor.wakePrimary(runId, primaryId))
                .isEqualTo(DeterministicSupervisor.WakeOutcome.STILL_WAITING);

        finishChildren(primaryId, 2);
        assertThat(supervisor.wakePrimary(runId, primaryId))
                .isEqualTo(DeterministicSupervisor.WakeOutcome.WOKEN);
    }

    // ------------------------------------------------- RX07 轮次身份独立

    @Test
    void rx07同taskKey跨轮独立_同轮重复撞唯一键() {
        // 跨轮：同 key 落两个 round 合法（轮次身份独立）
        stores.tasks.insert(new RcaTask(UUID.randomUUID(), runId, "DELEGATE-gap-k",
                RcaTaskState.DONE, 5, NOW, NOW, Instant.MAX, null, null, 0, 0, 2, NOW, NOW, 0));
        stores.tasks.insert(new RcaTask(UUID.randomUUID(), runId, "DELEGATE-gap-k",
                RcaTaskState.READY, 5, NOW, NOW, Instant.MAX, null, null, 0, 0, 2, NOW, NOW, 1));
        assertThat(stores.tasks.findByRunId(runId)).hasSize(2);

        // 同轮重复：唯一键显式拒绝（不静默覆盖）
        assertThatThrownBy(() -> stores.tasks.insert(new RcaTask(UUID.randomUUID(), runId,
                "DELEGATE-gap-k", RcaTaskState.READY, 5, NOW, NOW, Instant.MAX,
                null, null, 0, 0, 2, NOW, NOW, 1)))
                .isInstanceOf(org.springframework.dao.DuplicateKeyException.class);
    }

    // ------------------------------------------------- 夹具

    private UUID startPrimary() {
        supervisor().startPrimary(runId, primaryProfile(), Set.of("snapshot:r0"));
        return stores.tasks.findByRunId(runId).stream()
                .filter(t -> t.taskKey().equals(RcaTask.PRIMARY_INVESTIGATE))
                .findFirst().orElseThrow().id();
    }

    /** 当前轮子任务全部置 DONE（模拟执行器收官，供唤醒复判） */
    private void finishChildren(UUID primaryId, int round) {
        for (RcaTask child : stores.tasks.findByRunId(runId)) {
            if (!child.taskKey().equals(RcaTask.PRIMARY_INVESTIGATE)
                    && child.roundId() == round) {
                stores.tasks.transitionState(child.id(), child.state(), RcaTaskState.DONE);
            }
        }
    }

    private static Map<String, Object> request(String gapId, String roleId) {
        Map<String, Object> req = new LinkedHashMap<>();
        req.put("gap_id", gapId);
        req.put("role_id", roleId);
        req.put("question", "q-" + gapId);
        req.put("input_refs", List.of("snapshot:r0"));
        req.put("scope", Map.of());
        req.put("requested_budget", 4);
        return req;
    }

    private static AgentProfile primaryProfile() {
        return new AgentProfile("primary", "1", "prompt-primary", "pv",
                Set.of(), Map.of(BudgetKind.STEP, 8L), Map.of("type", "object"),
                Map.of(), AgentPhase.PRIMARY, RoleRuntimeKind.BOUNDED_LLM,
                Set.of(), 8, "single-pass");
    }

    private static AgentProfile expertProfile(String name) {
        return new AgentProfile(name, "1", "prompt-" + name, "pv",
                Set.of(), Map.of(BudgetKind.STEP, 4L), Map.of("type", "object"),
                Map.of(), AgentPhase.INVESTIGATE, RoleRuntimeKind.DETERMINISTIC_SINGLE_TOOL,
                Set.of(), 1, "single-pass");
    }

    private static TransactionOperations inPlaceTx() {
        return new TransactionOperations() {
            @Override
            public <T> T execute(TransactionCallback<T> action) {
                return action.doInTransaction(null);
            }
        };
    }

    /** 最小边仓储内存件（委派路径断言零依赖边） */
    private static final class EdgeStore implements TaskEdgeRepository {
        final List<TaskEdge> rows = new ArrayList<>();

        @Override
        public void insert(UUID runId, UUID fromTaskId, UUID toTaskId,
                DependencyType dependencyType) {
            rows.add(new TaskEdge(fromTaskId.toString(), toTaskId.toString(), dependencyType));
        }

        @Override
        public List<TaskEdge> findByRunId(UUID runId) {
            return rows.stream().toList();
        }
    }
}
