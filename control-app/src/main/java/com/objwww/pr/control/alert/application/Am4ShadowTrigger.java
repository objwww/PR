package com.objwww.pr.control.alert.application;

import com.objwww.pr.control.alert.application.agent.ChangeAgent;
import com.objwww.pr.control.alert.application.agent.LogsAgent;
import com.objwww.pr.control.alert.application.agent.MetricsAgent;
import com.objwww.pr.control.alert.application.agent.NativeRcaAgent;
import com.objwww.pr.control.alert.application.agent.SingleToolEvidenceAgent;
import com.objwww.pr.control.alert.domain.dag.PlanProposal;
import com.objwww.pr.control.alert.domain.evidence.EvidenceEnvelope;
import com.objwww.pr.control.alert.domain.evidence.EvidenceRepository;
import com.objwww.pr.control.alert.domain.evidence.EvidenceSnapshotBuilder;
import com.objwww.pr.control.alert.domain.evidence.EvidenceSnapshotRepository;
import com.objwww.pr.control.alert.domain.model.RcaRun;
import com.objwww.pr.control.alert.domain.model.RcaRunState;
import com.objwww.pr.control.alert.domain.model.RcaTask;
import com.objwww.pr.control.alert.domain.model.RcaTaskState;
import com.objwww.pr.control.alert.domain.model.RunTrigger;
import com.objwww.pr.control.alert.domain.repository.RcaRunRepository;
import com.objwww.pr.control.alert.domain.repository.RcaTaskRepository;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Am4ShadowTrigger（E2E 执行者工具，195 部署配方 §5 方式 A）：按 G2 全链套件
 * 组装语义驱动一次 Native 影子 run——镜像 holmes run 身份（同 incident/
 * 同 generation/同 investigation hash = 同一 input snapshot）落影子 run 行 →
 * {@link DeterministicSupervisor#startRun} 固定提案落图 → 三调查任务（metrics
 * 真实 Prometheus + logs/change 冻结 fixture 回放，工具出口 = 影子面）逐个领取
 * 执行 → 冻结证据快照 → advance 全终态入 REPORTING → {@link NativeRcaAgent}
 * 黑板推导 Claim。stdout 打印 {@value #RUN_ID_MARKER}（E2E 脚本捕获锚点）与
 * 逐任务结局行（{@value #TASK_OUTCOME_MARKER}）。
 *
 * <p>纪律（AM4 技术方案 §2）：影子链<b>零报告零发布</b>（run 终于 REPORTING，
 * 与 G2 套件终态一致）；单任务 FAILED = 缺源降级续跑（任务 DEAD 终态，run 继续，
 * 对齐 ClaimReducer 降级白名单语义），缺源面由场景脚本按证据计数断言；本类不
 * 发明生产触发器——正式触发入口仍是 G2 终裁开放项（配方 §6.1），本类只调用
 * 组件公开入口，供 195 E2E 执行者一次性驱动。
 *
 * @author wanghua
 * @date 2026-09-05
 */
public class Am4ShadowTrigger {

    /** 影子 run id 输出标记（E2E 脚本捕获锚点） */
    public static final String RUN_ID_MARKER = "AM4_SHADOW_RUN_ID=";

    /** 逐任务结局输出标记（E2E 证据留痕面） */
    public static final String TASK_OUTCOME_MARKER = "AM4_SHADOW_TASK=";

    /** 固定提案任务键（PlanCompiler 落图后以 key 对位三 Agent） */
    static final String TASK_METRICS = "investigate-metrics";
    static final String TASK_LOGS = "investigate-logs";
    static final String TASK_CHANGE = "investigate-change";

    /** F1 症状指标（deploy/alert/prometheus/rules/arena.yml 冻结规则的真实数据源） */
    private static final String METRICS_EXPR = "oa_duplicate_orders_current{job=\"order-arena\"}";
    private static final long RANGE_WINDOW_SECS = 600L;
    private static final String STEP = "30s";

    /** 快照输入的执行者侧身份面（E2E 工具身份；生产 config/registry digest 归正式接线） */
    private static final String EXECUTOR_CONFIG_DIGEST = "am4-shadow-trigger:executor-v1";
    private static final String EXECUTOR_TOOL_REGISTRY_DIGEST =
            "am4-shadow-trigger:prometheus.query,logs.query,change.query";

    private final DeterministicSupervisor supervisor;
    private final RcaRunRepository runs;
    private final RcaTaskRepository tasks;
    private final EvidenceRepository evidence;
    private final EvidenceSnapshotRepository snapshots;
    private final MetricsAgent metricsAgent;
    private final LogsAgent logsAgent;
    private final ChangeAgent changeAgent;
    private final NativeRcaAgent nativeRcaAgent;
    private final AlertClock clock;

    public Am4ShadowTrigger(DeterministicSupervisor supervisor, RcaRunRepository runs,
            RcaTaskRepository tasks, EvidenceRepository evidence,
            EvidenceSnapshotRepository snapshots, MetricsAgent metricsAgent,
            LogsAgent logsAgent, ChangeAgent changeAgent, NativeRcaAgent nativeRcaAgent,
            AlertClock clock) {
        this.supervisor = Objects.requireNonNull(supervisor);
        this.runs = Objects.requireNonNull(runs);
        this.tasks = Objects.requireNonNull(tasks);
        this.evidence = Objects.requireNonNull(evidence);
        this.snapshots = Objects.requireNonNull(snapshots);
        this.metricsAgent = Objects.requireNonNull(metricsAgent);
        this.logsAgent = Objects.requireNonNull(logsAgent);
        this.changeAgent = Objects.requireNonNull(changeAgent);
        this.nativeRcaAgent = Objects.requireNonNull(nativeRcaAgent);
        this.clock = Objects.requireNonNull(clock);
    }

    /**
     * 驱动一次影子 run：holmes run 须已终态（活跃 run 撞
     * uq_rca_run_active_incident 唯一约束即失败——影子 run 与 holmes 同 incident）。
     *
     * @return 影子 run id（同时以 {@value #RUN_ID_MARKER} 打印 stdout）
     */
    public UUID trigger(UUID holmesRunId) {
        RcaRun holmes = runs.findById(holmesRunId)
                .orElseThrow(() -> new IllegalArgumentException("holmes run 不存在: " + holmesRunId));
        String snapshotDigest = holmes.investigationHash().hex();
        RcaRun shadow = new RcaRun(UUID.randomUUID(), holmes.incidentId(),
                holmes.generation(), RunTrigger.RERUN, RcaRunState.QUEUED,
                holmes.investigationHash(), clock.now(), clock.now(), null, null, null);
        runs.insert(shadow);
        DeterministicSupervisor.StartResult started = supervisor.startRun(shadow.id(),
                proposal(), Set.of());
        if (started.outcome() != DeterministicSupervisor.StartOutcome.STARTED) {
            throw new IllegalStateException("影子 run 启动失败: " + started.outcome()
                    + " reason=" + started.rejectReason());
        }
        investigate(shadow.id(), holmes.generation(), snapshotDigest);
        freezeSnapshot(shadow.id(), holmes.generation());
        supervisor.advance(shadow.id());
        nativeRcaAgent.investigate(shadow.id(), snapshotDigest, holmes.generation());
        System.out.println(RUN_ID_MARKER + shadow.id());
        return shadow.id();
    }

    // ------------------------------------------------------------------ 内部

    /**
     * 三调查任务逐个领取执行（READY→LEASED→RUNNING→DONE 状态机链）；
     * FAILED = 缺源降级续跑：任务 DEAD 终态、run 继续（结局行留痕 stdout）。
     */
    private void investigate(UUID runId, long generation, String snapshotDigest) {
        Instant end = clock.now();
        String endEpoch = Long.toString(end.getEpochSecond());
        String startEpoch = Long.toString(end.minusSeconds(RANGE_WINDOW_SECS).getEpochSecond());
        String timeRange = startEpoch + "/" + endEpoch;
        long callSeq = 0;
        for (RcaTask task : tasks.findByRunId(runId)) {
            callSeq++;
            transition(task.id(), RcaTaskState.READY, RcaTaskState.LEASED);
            transition(task.id(), RcaTaskState.LEASED, RcaTaskState.RUNNING);
            SingleToolEvidenceAgent.CallContext ctx = new SingleToolEvidenceAgent.CallContext(
                    runId, task.id(), UUID.randomUUID(), callSeq, generation,
                    snapshotDigest, timeRange);
            SingleToolEvidenceAgent.AgentResult result = switch (task.taskKey()) {
                case TASK_METRICS -> metricsAgent.investigate(ctx,
                        new MetricsAgent.MetricsQuery(METRICS_EXPR, startEpoch, endEpoch, STEP));
                case TASK_LOGS -> logsAgent.investigate(ctx,
                        new LogsAgent.LogsQuery(startEpoch, endEpoch));
                case TASK_CHANGE -> changeAgent.investigate(ctx,
                        new ChangeAgent.ChangeQuery(startEpoch, endEpoch));
                default -> throw new IllegalStateException("未注册的影子调查任务: " + task.taskKey());
            };
            if (result.outcome() == SingleToolEvidenceAgent.AgentOutcome.FAILED) {
                transition(task.id(), RcaTaskState.RUNNING, RcaTaskState.DEAD);
                System.out.println(TASK_OUTCOME_MARKER + task.taskKey()
                        + "=FAILED(" + result.errorClass() + ")");
                continue;
            }
            transition(task.id(), RcaTaskState.RUNNING, RcaTaskState.DONE);
            System.out.println(TASK_OUTCOME_MARKER + task.taskKey() + "=" + result.outcome());
        }
    }

    /** 冻结证据快照（M4-20）：成员 = 本 run 全部证据 (type,payload_digest)，代际参与输入 */
    private void freezeSnapshot(UUID runId, long generation) {
        List<EvidenceEnvelope> rows = evidence.findByRunId(runId);
        List<EvidenceSnapshotBuilder.Member> members = rows.stream()
                .map(e -> new EvidenceSnapshotBuilder.Member(e.evidenceType(), e.payloadDigest()))
                .toList();
        String digest = EvidenceSnapshotBuilder.digest(new EvidenceSnapshotBuilder.SnapshotInput(
                generation, EXECUTOR_CONFIG_DIGEST, EXECUTOR_TOOL_REGISTRY_DIGEST, members));
        snapshots.freeze(
                new EvidenceSnapshotRepository.FrozenSnapshot(UUID.randomUUID(), runId, digest,
                        generation, EXECUTOR_CONFIG_DIGEST, EXECUTOR_TOOL_REGISTRY_DIGEST, null),
                rows.stream().map(e -> new EvidenceSnapshotRepository.SnapshotMemberRow(
                        e.evidenceId(), e.evidenceType(), e.payloadDigest())).toList());
        System.out.println(TASK_OUTCOME_MARKER + "snapshot=" + digest);
    }

    /** 固定提案：三调查任务全并行（零边 = 全根任务，PlanCompiler 语义面合法） */
    private static Map<String, Object> proposal() {
        List<Map<String, Object>> tasks = List.of(
                Map.of("key", TASK_METRICS, "type", "metrics@1", "inputs", List.of()),
                Map.of("key", TASK_LOGS, "type", "logs@1", "inputs", List.of()),
                Map.of("key", TASK_CHANGE, "type", "change@1", "inputs", List.of()));
        return Map.of("schema_version", PlanProposal.SCHEMA_VERSION,
                "tasks", tasks, "edges", List.of());
    }

    private void transition(UUID taskId, RcaTaskState from, RcaTaskState to) {
        if (!tasks.transitionState(taskId, from, to)) {
            throw new IllegalStateException("任务状态迁移失败: " + taskId + " " + from + "->" + to);
        }
    }
}
