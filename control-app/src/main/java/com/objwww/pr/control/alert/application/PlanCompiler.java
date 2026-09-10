package com.objwww.pr.control.alert.application;

import com.objwww.pr.control.alert.application.agent.AgentRegistry;
import com.objwww.pr.control.alert.domain.agent.AgentProfile;
import com.objwww.pr.control.alert.domain.dag.DagCycleDetector;
import com.objwww.pr.control.alert.domain.dag.PlanProposal;
import com.objwww.pr.control.alert.domain.dag.TaskEdge;
import com.objwww.pr.control.alert.domain.model.RcaTask;
import com.objwww.pr.control.alert.domain.model.RcaTaskState;
import com.objwww.pr.control.alert.domain.model.TaskExecutionBinding;
import com.objwww.pr.control.alert.domain.repository.RcaTaskRepository;
import com.objwww.pr.control.alert.domain.repository.TaskEdgeRepository;
import com.objwww.pr.control.alert.domain.repository.TaskExecutionBindingRepository;
import org.springframework.transaction.support.TransactionOperations;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * PlanCompiler（AM4 M4-25，双设防第二设防）：LLM Planner 输出经 {@link PlanProposal}
 * 结构严格解析后，再做语义校验——注册表任务类型（name@version 全钉，未注册拒绝）、
 * ≤8 任务、深度 ≤3、无环（全图复判）、活跃 VERIFY ≤1、task inputs ⊆ 本 run 已知
 * artifact 集——全部通过后<b>单事务落 tasks+edges+bindings</b>。模型无调度权
 * （INV-AM4-2）：本类是提案到 DAG 的唯一落库路径；相同提案 → 相同任务图
 * （proposalDigest 稳定）。
 *
 * <p>边直接经 TaskEdgeRepository 落库：无环已在写前对提案全图判定（比逐边复判强），
 * DB 面 V8+V18 组合约束兜底存在性/同 run 域。
 *
 * <p>任务出生默认：BLOCKED（推进器负责 READY）/priority 5/maxAttempts 2/
 * deadlineAt=Instant.MAX（PG infinity，SLA 策略面归 M4-26 Supervisor 演进）。
 *
 * <p>R7-X1（v2.1 §十一.2/§十三）：任务的角色身份经 {@link TaskExecutionBinding}
 * 在<b>同一编译事务</b>内冻结落库（role 版本/digest、round、input refs、输出 schema
 * 冻结件）；幂等键 = (run, round, taskKey)——同轮重复提交撞唯一键显式失败，
 * 重入由 Supervisor 启动短路兜底（已落图不重编译）。
 *
 * <p>R7-X11（v2.1 §三/§十三）：{@link #compilePrimary} 为主模式 run 的唯一入口——
 * 初始只建主节点（PRIMARY_INVESTIGATE，round0），不预建任何调查专家；专家仅经
 * Supervisor 委派裁决按需出现。
 */
public class PlanCompiler {

    /** 验证型 Agent 的注册名（活跃数 ≤1；M4-26 Supervisor 链上 VERIFY 阶段同名） */
    public static final String VERIFY_AGENT = "verify";
    public static final int MAX_TASKS = 8;
    public static final int MAX_DEPTH = 3;

    private static final int DEFAULT_PRIORITY = 5;
    private static final int DEFAULT_MAX_ATTEMPTS = 2;

    private final AgentRegistry agents;
    private final RcaTaskRepository tasks;
    private final TaskEdgeRepository edges;
    private final TaskExecutionBindingRepository bindings;
    private final TransactionOperations tx;

    public PlanCompiler(AgentRegistry agents, RcaTaskRepository tasks,
            TaskEdgeRepository edges, TaskExecutionBindingRepository bindings,
            TransactionOperations tx) {
        this.agents = Objects.requireNonNull(agents);
        this.tasks = Objects.requireNonNull(tasks);
        this.edges = Objects.requireNonNull(edges);
        this.bindings = Objects.requireNonNull(bindings, "bindings");
        this.tx = Objects.requireNonNull(tx);
    }

    /**
     * @param plannerOutput   Planner 结构化输出（已解析 JSON 对象；形状由 PlanProposal 严拒）
     * @param knownArtifacts  本 run 已知 artifact 键集（Supervisor 由 run 输入面提供）
     */
    public PlanCompilation compile(UUID runId, Map<String, Object> plannerOutput,
            Set<String> knownArtifacts) {
        Objects.requireNonNull(runId, "runId");
        Objects.requireNonNull(plannerOutput, "plannerOutput");
        Objects.requireNonNull(knownArtifacts, "knownArtifacts");
        PlanProposal proposal = PlanProposal.parse(plannerOutput);

        if (proposal.tasks().size() > MAX_TASKS) {
            throw new IllegalArgumentException(
                    "提案任务数超上限 " + MAX_TASKS + ": " + proposal.tasks().size());
        }
        int verifyCount = 0;
        for (PlanProposal.PlanTask task : proposal.tasks()) {
            int at = task.type().indexOf('@');
            agents.require(task.type().substring(0, at), task.type().substring(at + 1));
            if (task.type().substring(0, at).equals(VERIFY_AGENT)) {
                verifyCount++;
            }
            for (String input : task.inputs()) {
                if (!knownArtifacts.contains(input)) {
                    throw new IllegalArgumentException(
                            "task 输入引用不属于本 run artifact: " + input
                                    + "（task=" + task.key() + "）");
                }
            }
        }
        if (verifyCount > 1) {
            throw new IllegalArgumentException("活跃 VERIFY 至多 1，实际 " + verifyCount);
        }

        List<TaskEdge> graph = proposal.edges().stream()
                .map(e -> new TaskEdge(e.from(), e.to(), e.dependency()))
                .toList();
        DagCycleDetector.findCycle(List.of(), graph).ifPresent(cycle -> {
            throw new IllegalArgumentException(
                    "提案成环: " + String.join(" -> ", cycle));
        });
        int depth = longestPath(proposal);
        if (depth > MAX_DEPTH) {
            throw new IllegalArgumentException(
                    "提案深度超上限 " + MAX_DEPTH + ": " + depth);
        }

        String digest = proposal.digest();
        Instant now = Instant.now();
        Map<String, UUID> taskIds = new LinkedHashMap<>();
        tx.execute(status -> {
            for (PlanProposal.PlanTask task : proposal.tasks()) {
                UUID id = UUID.randomUUID();
                taskIds.put(task.key(), id);
                tasks.insert(new RcaTask(id, runId, task.key(), RcaTaskState.BLOCKED,
                        DEFAULT_PRIORITY, now, now, Instant.MAX, null, null, 0,
                        0, DEFAULT_MAX_ATTEMPTS, now, now));
                bindings.insert(bindingOf(runId, id, task.key(), task.type(),
                        task.inputs(), now));
            }
            for (PlanProposal.PlanEdge edge : proposal.edges()) {
                edges.insert(runId, taskIds.get(edge.from()), taskIds.get(edge.to()),
                        edge.dependency());
            }
            return null;
        });
        return new PlanCompilation(runId, Map.copyOf(taskIds), digest);
    }

    /**
     * R7-X11：主模式编译——初始只建主节点（v2.1 §三 "默认只创建主任务，不预建两个
     * 调查根"）。主任务同样走冻结绑定（PRIMARY phase Profile），恢复不猜角色。
     */
    public PlanCompilation compilePrimary(UUID runId, AgentProfile primaryProfile,
            Set<String> knownArtifacts) {
        Objects.requireNonNull(runId, "runId");
        Objects.requireNonNull(primaryProfile, "primaryProfile");
        Objects.requireNonNull(knownArtifacts, "knownArtifacts");
        if (primaryProfile.phase() != com.objwww.pr.control.alert.domain.agent.AgentPhase.PRIMARY) {
            throw new IllegalArgumentException(
                    "主模式编译只接受 PRIMARY 阶段 Profile，实际: " + primaryProfile.name()
                            + " phase=" + primaryProfile.phase());
        }
        Instant now = Instant.now();
        UUID primaryTaskId = UUID.randomUUID();
        String type = primaryProfile.name() + "@" + primaryProfile.version();
        tx.execute(status -> {
            tasks.insert(new RcaTask(primaryTaskId, runId, RcaTask.PRIMARY_INVESTIGATE,
                    RcaTaskState.BLOCKED, DEFAULT_PRIORITY, now, now, Instant.MAX,
                    null, null, 0, 0, DEFAULT_MAX_ATTEMPTS, now, now, 0));
            bindings.insert(bindingOf(runId, primaryTaskId, RcaTask.PRIMARY_INVESTIGATE,
                    type, List.of(), now));
            return null;
        });
        return new PlanCompilation(runId, Map.of(RcaTask.PRIMARY_INVESTIGATE, primaryTaskId),
                new PlanProposal(PlanProposal.SCHEMA_VERSION,
                        List.of(new PlanProposal.PlanTask(RcaTask.PRIMARY_INVESTIGATE, type,
                                List.of())),
                        List.of()).digest());
    }

    /** 冻结绑定铸造（编译事务内；role 身份三 元组来自注册表 require 的 Profile） */
    private TaskExecutionBinding bindingOf(UUID runId, UUID taskId, String taskKey,
            String type, List<String> inputRefs, Instant now) {
        int at = type.indexOf('@');
        AgentProfile profile = agents.require(type.substring(0, at), type.substring(at + 1));
        return new TaskExecutionBinding(taskId, runId, 0, taskKey,
                profile.name(), profile.version(), profile.digest(),
                agents.releaseDigest().orElse(null), null,
                inputRefs, profile.outputSchema(), null, true,
                TaskExecutionBinding.FailurePolicy.DEAD_ON_FAILURE, now);
    }

    /** 最长路径边数（边松弛到不动点；解析面已拒环+全图复判，必终止） */
    private static int longestPath(PlanProposal proposal) {
        Map<String, Integer> depth = new LinkedHashMap<>();
        for (PlanProposal.PlanTask task : proposal.tasks()) {
            depth.put(task.key(), 0);
        }
        boolean changed = true;
        while (changed) {
            changed = false;
            for (PlanProposal.PlanEdge edge : proposal.edges()) {
                int candidate = depth.get(edge.from()) + 1;
                if (candidate > depth.get(edge.to())) {
                    depth.put(edge.to(), candidate);
                    changed = true;
                }
            }
        }
        return depth.values().stream().mapToInt(Integer::intValue).max().orElse(0);
    }

    /** 编译产物：key→taskId 映射 + 提案摘要（相同提案相同任务图的对账锚点） */
    public record PlanCompilation(UUID runId, Map<String, UUID> taskIds, String proposalDigest) {
        public PlanCompilation {
            Objects.requireNonNull(runId, "runId");
            taskIds = Map.copyOf(Objects.requireNonNull(taskIds, "taskIds"));
            Objects.requireNonNull(proposalDigest, "proposalDigest");
        }
    }
}
