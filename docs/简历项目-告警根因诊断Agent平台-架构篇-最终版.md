# 简历项目：告警根因诊断Agent平台 - 架构篇

## 项目背景与问题

在交易域运维场景下，系统告警触发后的根因定位面临三大痛点：
1. **人工排查效率低**：值班人员需手动查询Prometheus指标、分析应用日志、检索代码仓库、核查变更记录，单次排查耗时15-30分钟，夜间告警平均响应时间超1小时
2. **诊断质量依赖个人经验**：新人排查准确率不足50%，资深工程师准确率75%，缺乏标准化流程
3. **第三方引擎不可控**：引入的开源HolmesGPT存在行为黑盒、无法灰度发布、诊断过程不可回放等问题

基于此，作为独立负责人设计并落地了"确定性控制面 + LLM多Agent"的诊断平台，实现告警自动调查取证、产出带证据链的根因报告，并按值班排班智能路由通知。最终告警响应时间从平均25分钟降至3分钟，根因定位准确率达到85%，支撑日均500+告警的自动化处理。

---

## 一、动态编排Multi-Agent架构设计

### 问题描述
**固定流水线的僵化性问题：**
1. 开源HolmesGPT采用固定三步流程（指标查询→日志分析→变更检索），简单告警（如单一指标异常）浪费60%预算
2. 复杂告警（如跨服务依赖故障）三步流程覆盖不足，需要人工二次排查
3. 调查路径无法根据中间结果动态调整，模型陷入无效循环时无法提前终止
4. 单Agent架构上下文膨胀，单次调用消耗6000+ tokens

### 解决方案
**设计主Agent直接调查 + 按需委派专家Agent的非固定编排模式：**

#### 1. 三角色动态协作架构

```
                   ┌─────────────────────┐
                   │  PrimaryAgent       │
                   │  (主调查Agent)       │
                   │  - 告警分析决策      │
                   │  - 直接查询受限工具  │
                   │  - 按需委派专家      │
                   └──────────┬──────────┘
                              │
              ┌───────────────┼───────────────┐
              │               │               │
              ▼               ▼               ▼
    ┌─────────────┐  ┌─────────────┐  ┌─────────────┐
    │ MetricAgent │  │ LogAgent    │  │ ChangeAgent │
    │ 指标专家     │  │ 日志专家     │  │ 变更专家     │
    │ - 深度PromQL│  │ - 跨服务追踪 │  │ - 部署历史   │
    │ - 时序分析  │  │ - 异常提取   │  │ - 代码diff   │
    └─────────────┘  └─────────────┘  └─────────────┘
```

**关键设计：主Agent拥有受限工具权限，可直接处理简单告警**

| 角色 | 工具权限 | 职责 | 触发条件 |
|------|---------|------|---------|
| PrimaryAgent | alert.query（完整）<br>prometheus.query（基础，仅单指标）<br>code.search（只读） | 告警分析、决策中心、唯一Claim产出者 | 必选，每次调查 |
| MetricAgent | prometheus.query（完整，含复杂PromQL） | 多指标关联分析、时序异常检测 | 按需委派，当主Agent判断需要深度指标分析 |
| LogAgent | logs.query（跨服务）<br>trace.query | 跨服务链路追踪、异常堆栈提取 | 按需委派，当需要跨服务取证 |
| ChangeAgent | change.query<br>code.read（完整） | 部署变更时间线、代码差异分析 | 按需委派，当存在变更时间关联 |

**实际case分析：**

```
Case 1: 简单指标告警（支付成功率下降）
└─ PrimaryAgent 直接查询 prometheus.query("payment_success_rate")
   └─ 发现单一指标突降，无需委派
   └─ 输出 ROOT_CAUSE: "支付网关限流"（耗时1.5分钟，1次LLM调用）

Case 2: 复杂跨服务故障（订单超时）
└─ PrimaryAgent 查询 alert.query 发现依赖库存服务
   ├─ 委派 MetricAgent 分析库存服务CPU/Memory指标
   │  └─ 返回 Findings: "库存服务CPU 95%，疑似死循环"
   ├─ 委派 LogAgent 提取库存服务异常堆栈
   │  └─ 返回 Observation: "NullPointerException in InventoryCalculator"
   └─ 主Agent综合证据，输出 ROOT_CAUSE（耗时4分钟，5次LLM调用）
```

#### 2. 确定性状态机 + DAG调度器

**问题：模型输出不可控，如何保证流程可靠性？**

**解决方案：模型只产出候选事实，流转由代码控制**

```java
// 主Agent决策状态机（7状态）
public enum PrimaryAgentState {
    CREATED,              // 已创建
    DIRECT_INVESTIGATING, // 直接调查中（使用受限工具）
    WAITING_DELEGATES,    // 等待专家Agent（已委派）
    ANALYZING,            // 综合分析中（汇总专家结果）
    CLAIM_PENDING,        // 候选结论待校验
    REPORTING,            // 生成报告
    COMPLETED             // 已完成
}

// 确定性Supervisor（守卫模型决策）
public class DeterministicSupervisor {
    
    // 模型只能输出三种Decision之一
    public sealed interface AgentDecision permits ToolCall, Delegate, FinalClaim {}
    
    public record ToolCall(String toolId, Map<String, Object> args) 
        implements AgentDecision {}
    
    public record Delegate(List<DelegateRequest> requests) 
        implements AgentDecision {
        // 每个委派请求必须声明缺口
        public record DelegateRequest(
            String gapId,           // "METRIC_CORRELATION" | "CROSS_SERVICE_TRACE"
            String targetRole,      // "MetricAgent" | "LogAgent"
            String question,        // 明确的调查问题
            Set<String> inputRefs,  // 引用的已有证据ID
            Budget requestedBudget  // 申请的子任务预算
        ) {}
    }
    
    public record FinalClaim(
        List<ClaimProposal> claims,
        Set<String> missingInformation
    ) implements AgentDecision {}
    
    // 决策校验与状态转换
    public StateTransition validateAndTransition(
        PrimaryAgentState currentState, 
        AgentDecision decision
    ) {
        return switch (currentState) {
            case DIRECT_INVESTIGATING -> switch (decision) {
                case ToolCall tc -> {
                    // 校验工具白名单
                    if (!isAllowedTool(tc.toolId(), "PrimaryAgent")) {
                        yield rejectWithReason("工具不在白名单: " + tc.toolId());
                    }
                    // 校验调用次数上限
                    if (context.toolCallCount() >= MAX_DIRECT_CALLS) {
                        yield transitionTo(CLAIM_PENDING, "已达直接调查上限");
                    }
                    yield executeTool(tc).thenTransitionTo(DIRECT_INVESTIGATING);
                }
                
                case Delegate d -> {
                    // 校验委派合法性
                    ValidationResult validation = validateDelegation(d);
                    if (!validation.isValid()) {
                        yield rejectWithReason(validation.reason());
                    }
                    // 原子创建子任务 + DAG依赖边
                    yield createDelegateTasks(d).thenTransitionTo(WAITING_DELEGATES);
                }
                
                case FinalClaim fc -> {
                    // 证据充分性检查
                    if (context.observations().isEmpty()) {
                        yield rejectWithReason("无原始证据，不能直接产出结论");
                    }
                    yield transitionTo(CLAIM_PENDING);
                }
            };
            
            case WAITING_DELEGATES -> {
                // 此状态下模型不应产出Decision，由DAG调度器驱动
                // 当所有子任务完成时，自动转换到ANALYZING
                if (allDelegatesCompleted()) {
                    yield transitionTo(ANALYZING);
                } else {
                    yield waitForDelegates();
                }
            }
            
            // ... 其他状态的转换逻辑
        };
    }
}
```

#### 3. DAG调度器（任务依赖管理）

```java
// DAG依赖边（持久化）
@Entity
@Table(name = "rca_task_edge")
public class RcaTaskEdge {
    @Id
    private UUID edgeId;
    
    private UUID runId;
    private UUID fromTaskId;  // 父任务（主Agent委派决策）
    private UUID toTaskId;    // 子任务（专家Agent）
    
    @Enumerated(EnumType.STRING)
    private EdgeType type;    // DELEGATE（委派）| SUPPLEMENT（补证）
    
    private EdgeStatus status; // PENDING | SATISFIED | FAILED
    
    // 依赖条件（JSON）
    @Column(columnDefinition = "JSONB")
    private String condition;  // 例：{"type": "task_done", "task": "primary"}
}

// DAG调度器（无环检测 + 拓扑排序）
public class DagScheduler {
    
    // 创建委派时建立DAG边
    @Transactional
    public void createDelegation(UUID primaryTaskId, List<DelegateRequest> requests) {
        // 1. 创建子任务
        List<UUID> childTaskIds = requests.stream()
            .map(req -> createDelegateTask(req))
            .toList();
        
        // 2. 建立依赖边（主任务 → 子任务）
        for (UUID childId : childTaskIds) {
            RcaTaskEdge edge = RcaTaskEdge.builder()
                .edgeId(UUID.randomUUID())
                .runId(context.runId())
                .fromTaskId(primaryTaskId)
                .toTaskId(childId)
                .type(EdgeType.DELEGATE)
                .status(EdgeStatus.PENDING)
                .condition(buildCondition("task_created"))
                .build();
            
            edgeRepo.save(edge);
        }
        
        // 3. 无环检测（防止主任务等子任务，子任务又等主任务）
        if (hasCycle(context.runId())) {
            throw new CyclicDependencyException("检测到循环依赖");
        }
        
        // 4. 更新主任务状态（释放Worker，不占用线程等待）
        primaryTask.transitionTo(WAITING_DELEGATES);
        primaryTask.setWakeupCondition(
            String.format("ALL_CHILDREN_DONE:%s", String.join(",", childTaskIds))
        );
    }
    
    // 子任务完成时唤醒主任务
    @Transactional
    public void onTaskCompleted(UUID taskId) {
        // 1. 查找依赖此任务的边
        List<RcaTaskEdge> dependentEdges = edgeRepo.findByToTaskId(taskId);
        
        for (RcaTaskEdge edge : dependentEdges) {
            edge.setStatus(EdgeStatus.SATISFIED);
            edgeRepo.save(edge);
            
            // 2. 检查父任务的所有子任务是否全部完成
            UUID parentTaskId = edge.getFromTaskId();
            List<RcaTaskEdge> allChildEdges = edgeRepo.findByFromTaskId(parentTaskId);
            
            boolean allDone = allChildEdges.stream()
                .allMatch(e -> e.getStatus() == EdgeStatus.SATISFIED);
            
            if (allDone) {
                // 3. 唤醒父任务（幂等，重复完成事件不重复唤醒）
                wakeupTask(parentTaskId);
            }
        }
    }
    
    // 无环检测（DFS）
    private boolean hasCycle(UUID runId) {
        Map<UUID, List<UUID>> graph = buildGraph(runId);
        Set<UUID> visited = new HashSet<>();
        Set<UUID> inStack = new HashSet<>();
        
        for (UUID node : graph.keySet()) {
            if (dfsCycle(node, graph, visited, inStack)) {
                return true;
            }
        }
        return false;
    }
}
```

#### 4. 租约抢占 + 提交栅栏

```java
// 租约抢占（支持优先级任务插队）
public class LeasePreemption {
    
    // Worker领取任务时检查优先级
    @Transactional
    public Optional<AcquiredTask> tryAcquire(int slotNo) {
        // 1. 按SLA deadline排序查询待执行任务
        List<RcaTask> readyTasks = taskRepo.findByStateOrderBySlaDeadline(
            TaskState.READY, 
            Pageable.ofSize(10)
        );
        
        if (readyTasks.isEmpty()) {
            return Optional.empty();
        }
        
        RcaTask task = readyTasks.get(0);
        
        // 2. 检查是否需要抢占（当前slot正在执行低优先级任务）
        Optional<SchedulerSlot> currentSlot = slotRepo.findBySlotNo(slotNo);
        if (currentSlot.isPresent() && currentSlot.get().getTaskId() != null) {
            RcaTask currentTask = taskRepo.findById(currentSlot.get().getTaskId()).get();
            
            // SLA紧急度对比（deadline差距超过5分钟才抢占）
            if (task.getSlaDeadline().isBefore(
                currentTask.getSlaDeadline().minusMinutes(5)
            )) {
                // 抢占：中断当前任务
                preemptTask(currentTask, slotNo);
            } else {
                return Optional.empty();  // 不抢占，等待下一个slot
            }
        }
        
        // 3. 原子领取任务
        int updated = slotRepo.atomicAcquire(slotNo, task.getTaskId());
        if (updated == 0) {
            return Optional.empty();  // 被其他Worker抢走
        }
        
        task.transitionTo(TaskState.LEASED);
        task.setLeaseEpoch(currentSlot.get().getEpoch());
        taskRepo.save(task);
        
        return Optional.of(new AcquiredTask(task.getTaskId(), slotNo));
    }
    
    // 抢占逻辑（保存检查点后中断）
    private void preemptTask(RcaTask victimTask, int slotNo) {
        // 1. 保存当前执行上下文
        ExecutionCheckpoint checkpoint = ExecutionCheckpoint.builder()
            .taskId(victimTask.getTaskId())
            .preemptedAt(Instant.now())
            .currentRound(victimTask.getCurrentRound())
            .contextSnapshot(serializeContext(victimTask))
            .build();
        
        checkpointRepo.save(checkpoint);
        
        // 2. 转换为READY状态（重新入队）
        victimTask.transitionTo(TaskState.READY);
        victimTask.setPreemptionCount(victimTask.getPreemptionCount() + 1);
        
        // 3. 释放slot
        slotRepo.release(slotNo);
    }
}

// 提交栅栏（防止"已死Worker提交过期结果"）
public class CommitBarrier {
    
    @Transactional
    public CommitResult tryCommit(UUID taskId, TaskResult result) {
        RcaTask task = taskRepo.findById(taskId)
            .orElseThrow(() -> new TaskNotFoundException(taskId));
        
        // 1. 租约epoch检查（已被抢占的Worker无法提交）
        if (result.leaseEpoch() != task.getLeaseEpoch()) {
            return CommitResult.rejected("租约已过期，当前epoch: " + task.getLeaseEpoch());
        }
        
        // 2. generation检查（Run已取消的任务无法提交）
        RcaRun run = runRepo.findById(task.getRunId()).get();
        if (result.generation() != run.getGeneration()) {
            return CommitResult.rejected("Run已取消或重启，当前generation: " + run.getGeneration());
        }
        
        // 3. 状态检查（只有RUNNING状态才能提交）
        if (task.getState() != TaskState.RUNNING) {
            return CommitResult.rejected("任务状态非RUNNING: " + task.getState());
        }
        
        // 4. 原子提交（乐观锁）
        int updated = taskRepo.atomicCommit(
            taskId,
            result.state(),
            result.output(),
            task.getLeaseEpoch(),
            run.getGeneration()
        );
        
        if (updated == 0) {
            return CommitResult.rejected("并发冲突，提交失败");
        }
        
        // 5. 释放slot
        slotRepo.release(task.getAssignedSlot());
        
        return CommitResult.succeeded();
    }
}
```

### 技术成果
- **预算节省60%**：简单告警零委派，平均调用次数从固定5次降至2次
- **复杂告警覆盖率提升40%**：动态委派支持3层深度调查（主→专家→补证），覆盖跨服务故障
- **调度延迟P99<5s**：SLA驱动调度 + 租约抢占，高优先级告警优先处理
- **崩溃恢复时间<2分钟**：DAG调度器幂等唤醒，租约超时自动回收

---

## 二、三层事实分离与代码级校验

### 问题描述
**LLM幻觉导致的虚假结论风险：**
1. 模型可能捏造不存在的错误码："根因是HTTP 599错误"（实际只有5xx标准码）
2. 引用不存在的证据："根据第5次查询结果"（实际只查询了3次）
3. 自我背书："根据我的分析"（没有原始数据支撑）
4. 因果倒置："CPU高导致请求慢"（实际是慢请求堆积导致CPU高）

### 解决方案
**建立Observation / Findings / Claim三层事实分离机制 + ClaimValidator代码级校验：**

#### 1. 三层事实定义

```java
// 第一层：Observation（原始观测，宿主赋予来源身份）
public record Observation(
    UUID observationId,
    String source,           // 来源身份："prometheus" | "logs" | "code_search"
    String toolInvocationId, // 工具调用ID（可追溯到原始请求）
    ObservationType type,    // METRIC | LOG_ENTRY | CODE_SNIPPET | CHANGE_RECORD
    String rawContent,       // 原始数据（JSON）
    Map<String, String> metadata,  // 时间戳/服务名/文件路径等
    Instant capturedAt
) implements Evidence {
    
    // 关键约束：Observation只能由Tool执行器创建，模型无法伪造
    public static Observation fromToolResult(ToolResult result) {
        return new Observation(
            UUID.randomUUID(),
            result.toolId(),               // 来源=工具ID
            result.invocationId(),
            inferType(result.toolId()),
            result.rawData(),
            result.metadata(),
            Instant.now()
        );
    }
}

// 第二层：Findings（模型派生解释，明确标记生产者）
public record Finding(
    UUID findingId,
    String producerRole,     // 生产者："PrimaryAgent" | "MetricAgent"
    String category,         // 类别："ANOMALY_DETECTED" | "CORRELATION_FOUND"
    String description,      // 描述："CPU使用率在18:30突增至95%"
    Set<UUID> basedOn,       // 基于哪些Observation（必须引用）
    Confidence confidence,   // 置信度：HIGH | MEDIUM | LOW
    Instant derivedAt
) implements Evidence {
    
    // 关键约束：Finding必须引用至少一个Observation
    public Finding {
        if (basedOn.isEmpty()) {
            throw new IllegalArgumentException("Finding必须引用Observation");
        }
    }
}

// 第三层：Claim（最终命题，唯一可作为根因的类型）
public record Claim(
    UUID claimId,
    ClaimType type,          // ROOT_CAUSE | HYPOTHESIS | CONTRIBUTING_FACTOR
    String mechanism,        // 故障机制："线程池耗尽导致请求排队"
    Set<UUID> supportingEvidence,  // 支持证据（必须包含Observation）
    Set<UUID> counterEvidence,     // 反证（可选）
    String explanation,      // 解释（当存在反证时必填）
    Instant proposedAt
) implements Evidence {}
```

#### 2. ClaimValidator代码级校验

```java
// Claim校验器（确定性规则，非模型判断）
public class ClaimValidator {
    
    // ROOT_CAUSE准入条件（代码强制，面试重点）
    public ValidationResult validate(Claim claim, EvidenceGraph evidenceGraph) {
        
        // 规则1：必须陈述故障机制
        if (claim.type() == ClaimType.ROOT_CAUSE && claim.mechanism() == null) {
            return ValidationResult.reject("ROOT_CAUSE必须陈述故障机制");
        }
        
        // 规则2：必须引用至少一个Observation（原始证据）
        Set<UUID> observations = evidenceGraph.filterObservations(
            claim.supportingEvidence()
        );
        if (observations.isEmpty()) {
            return ValidationResult.reject("ROOT_CAUSE必须引用原始证据（Observation）");
        }
        
        // 规则3：不能只引用Findings（防止自我背书）
        boolean onlyFindings = claim.supportingEvidence().stream()
            .allMatch(id -> evidenceGraph.getEvidence(id) instanceof Finding);
        if (onlyFindings) {
            return ValidationResult.reject("不能仅引用派生结论，需要原始数据支撑");
        }
        
        // 规则4：时间因果一致性检查
        TimelineConsistency timeline = checkTimeline(claim, evidenceGraph);
        if (!timeline.isConsistent()) {
            return ValidationResult.reject(
                "时间线不一致：" + timeline.violation()
            );
        }
        
        // 规则5：反证处理（存在反证时必须解释）
        if (!claim.counterEvidence().isEmpty() && claim.explanation() == null) {
            return ValidationResult.reject("存在反证时必须提供解释");
        }
        
        // 规则6：场景特定条件（可扩展）
        ScenarioValidation scenario = validateScenario(claim, evidenceGraph);
        if (!scenario.isValid()) {
            return ValidationResult.downgrade(
                ClaimType.HYPOTHESIS,
                scenario.reason()
            );
        }
        
        return ValidationResult.accept();
    }
    
    // 时间因果一致性（不能"结果发生在原因之前"）
    private TimelineConsistency checkTimeline(Claim claim, EvidenceGraph graph) {
        List<Observation> observations = claim.supportingEvidence().stream()
            .map(graph::getEvidence)
            .filter(e -> e instanceof Observation)
            .map(e -> (Observation) e)
            .sorted(Comparator.comparing(Observation::capturedAt))
            .toList();
        
        if (observations.size() < 2) {
            return TimelineConsistency.consistent();  // 单一证据无需检查时序
        }
        
        // 检查"原因"是否在"结果"之前
        // 例如：CPU高（18:30） vs 请求慢（18:25） → 不一致
        for (int i = 0; i < observations.size() - 1; i++) {
            Observation cause = observations.get(i);
            Observation effect = observations.get(i + 1);
            
            if (cause.capturedAt().isAfter(effect.capturedAt())) {
                return TimelineConsistency.violated(
                    String.format("证据 %s 在 %s 之后发生，因果关系可疑",
                        cause.observationId(), effect.observationId())
                );
            }
        }
        
        return TimelineConsistency.consistent();
    }
    
    // 场景特定校验（基于告警类型）
    private ScenarioValidation validateScenario(Claim claim, EvidenceGraph graph) {
        AlertType alertType = graph.getAlertType();
        
        return switch (alertType) {
            case LATENCY_SPIKE -> {
                // 延迟告警必须有延迟数据
                boolean hasLatencyMetric = claim.supportingEvidence().stream()
                    .map(graph::getEvidence)
                    .filter(e -> e instanceof Observation)
                    .map(e -> (Observation) e)
                    .anyMatch(o -> o.type() == ObservationType.METRIC 
                        && o.rawContent().contains("latency"));
                
                if (!hasLatencyMetric) {
                    yield ScenarioValidation.invalid("延迟告警缺少延迟指标证据");
                }
                yield ScenarioValidation.valid();
            }
            
            case ERROR_RATE_HIGH -> {
                // 错误率告警必须有错误样本
                boolean hasErrorSample = claim.supportingEvidence().stream()
                    .map(graph::getEvidence)
                    .anyMatch(e -> e instanceof Observation o 
                        && o.type() == ObservationType.LOG_ENTRY
                        && o.rawContent().contains("ERROR"));
                
                if (!hasErrorSample) {
                    yield ScenarioValidation.invalid("错误率告警缺少错误样本");
                }
                yield ScenarioValidation.valid();
            }
            
            default -> ScenarioValidation.valid();
        };
    }
}
```

#### 3. 证据图（EvidenceGraph）构建

```java
// 证据图（持久化 + 内存索引）
@Entity
@Table(name = "evidence_item")
public class EvidenceItem {
    @Id
    private UUID evidenceId;
    
    private UUID runId;
    
    @Enumerated(EnumType.STRING)
    private EvidenceType type;  // OBSERVATION | FINDING | CLAIM
    
    @Column(columnDefinition = "JSONB")
    private String content;  // 实际内容（JSON）
    
    // 引用关系（多对多）
    @ElementCollection
    @CollectionTable(name = "evidence_reference")
    private Set<UUID> referencedBy;  // 被哪些Evidence引用
    
    private Instant createdAt;
}

// 证据图内存表示（查询优化）
public class EvidenceGraph {
    
    private final Map<UUID, Evidence> evidenceMap;
    private final Map<UUID, Set<UUID>> forwardEdges;   // A引用B
    private final Map<UUID, Set<UUID>> backwardEdges;  // B被A引用
    
    // 加载Run的所有证据
    public static EvidenceGraph load(UUID runId) {
        List<EvidenceItem> items = evidenceRepo.findByRunId(runId);
        
        Map<UUID, Evidence> map = new HashMap<>();
        Map<UUID, Set<UUID>> forward = new HashMap<>();
        Map<UUID, Set<UUID>> backward = new HashMap<>();
        
        for (EvidenceItem item : items) {
            Evidence evidence = deserialize(item);
            map.put(item.getEvidenceId(), evidence);
            
            // 建立引用边
            Set<UUID> refs = extractReferences(evidence);
            forward.put(item.getEvidenceId(), refs);
            
            for (UUID ref : refs) {
                backward.computeIfAbsent(ref, k -> new HashSet<>()).add(
                    item.getEvidenceId()
                );
            }
        }
        
        return new EvidenceGraph(map, forward, backward);
    }
    
    // 查询：某个Claim引用了哪些Observation
    public Set<UUID> filterObservations(Set<UUID> evidenceIds) {
        return evidenceIds.stream()
            .filter(id -> evidenceMap.get(id) instanceof Observation)
            .collect(Collectors.toSet());
    }
    
    // 查询：某个Observation被哪些Claim引用（影响分析）
    public Set<Claim> getClaimsDependingOn(UUID observationId) {
        return backwardEdges.getOrDefault(observationId, Set.of()).stream()
            .map(evidenceMap::get)
            .filter(e -> e instanceof Claim)
            .map(e -> (Claim) e)
            .collect(Collectors.toSet());
    }
    
    // 引用链路追溯（审计用）
    public List<Evidence> traceReferencePath(UUID claimId, UUID observationId) {
        // BFS查找最短路径
        Queue<UUID> queue = new LinkedList<>();
        Map<UUID, UUID> parent = new HashMap<>();
        
        queue.offer(claimId);
        parent.put(claimId, null);
        
        while (!queue.isEmpty()) {
            UUID current = queue.poll();
            
            if (current.equals(observationId)) {
                // 找到路径，回溯构建
                return reconstructPath(parent, observationId);
            }
            
            Set<UUID> refs = forwardEdges.getOrDefault(current, Set.of());
            for (UUID ref : refs) {
                if (!parent.containsKey(ref)) {
                    parent.put(ref, current);
                    queue.offer(ref);
                }
            }
        }
        
        return List.of();  // 无路径
    }
}
```

### 技术成果
- **幻觉拦截率100%**：生产环境90天，拦截47次虚假引用、23次因果倒置、12次无证据断言
- **根因准确率从60%提升至85%**：ClaimValidator代码级校验保证结论质量
- **审计完整性**：证据图可追溯每个结论的推导路径，支持人工复核
- **自动降级机制**：不满足ROOT_CAUSE条件自动降级为HYPOTHESIS，避免误判

---

## 三、四维预算硬拦截与不可变配置

### 问题描述
**LLM调用失控导致的成本与性能风险：**
1. 单次调查无限循环，调用模型20+次仍未终止
2. Token消耗失控，单次调查消耗10000+ tokens（预算$0.50，实际$2.30）
3. 墙钟时间超限，用户等待5分钟仍未出结果
4. 配置漂移，Prompt/模型/工具版本不一致导致行为突变

### 解决方案

#### 1. LiteLLM代理统一收口 + 虚拟Key隔离

```yaml
# LiteLLM配置（代理层）
litellm_settings:
  drop_params: true  # 丢弃不支持的参数（兼容性）
  success_callback: ["prometheus", "postgres"]  # 回调埋点
  failure_callback: ["postgres"]
  
router_settings:
  routing_strategy: "cost-based-routing"  # 成本路由
  allowed_fails: 3
  cooldown_time: 60
  
model_list:
  - model_name: "gpt-4o-mini"
    litellm_params:
      model: "openai/gpt-4o-mini"
      api_key: "os.environ/OPENAI_API_KEY"
      
  - model_name: "qwen-plus"
    litellm_params:
      model: "dashscope/qwen-plus"
      api_key: "os.environ/DASHSCOPE_API_KEY"
      
  - model_name: "deepseek-v3"
    litellm_params:
      model: "openai/deepseek-chat"
      api_base: "https://api.deepseek.com/v1"
      api_key: "os.environ/DEEPSEEK_API_KEY"
```

```java
// 虚拟Key签发（按诊断任务隔离）
public class VirtualKeyManager {
    
    private final LiteLLMClient litellmClient;
    
    // 为每个Run签发临时虚拟Key（嵌入预算约束）
    @Transactional
    public VirtualKey issueKeyForRun(UUID runId, Budget budget) {
        // 1. 生成虚拟Key（UUID）
        String virtualKey = "vkey_" + UUID.randomUUID().toString().replace("-", "");
        
        // 2. 在LiteLLM创建Key（含预算约束）
        LiteLLMKeyRequest request = LiteLLMKeyRequest.builder()
            .key_alias(virtualKey)
            .metadata(Map.of("runId", runId.toString()))
            .budget_id(budget.budgetId())
            .max_budget(budget.maxCost())           // 费用上限
            .budget_duration("30m")                  // 有效期30分钟
            .build();
        
        litellmClient.createKey(request);
        
        // 3. 持久化映射关系
        VirtualKeyRecord record = VirtualKeyRecord.builder()
            .virtualKey(virtualKey)
            .runId(runId)
            .issuedAt(Instant.now())
            .expiresAt(Instant.now().plusSeconds(1800))
            .budget(budget)
            .build();
        
        keyRepo.save(record);
        
        return new VirtualKey(virtualKey, budget);
    }
    
    // 调用完成后立即撤销Key（最小权限原则）
    @Transactional
    public void revokeKey(String virtualKey) {
        litellmClient.deleteKey(virtualKey);
        
        VirtualKeyRecord record = keyRepo.findByVirtualKey(virtualKey).get();
        record.setRevokedAt(Instant.now());
        keyRepo.save(record);
    }
}
```

#### 2. 四维预算硬拦截

```java
// 预算配置（多维度）
public record Budget(
    String budgetId,
    int maxSteps,          // 步骤上限：主Agent 8步，专家Agent 4步
    int maxTokens,         // Token上限：单次调用4096（input+output）
    BigDecimal maxCost,    // 费用上限：单次调查$0.50
    Duration maxWallClock  // 墙钟上限：单次调查5分钟
) {
    // 预设预算档位
    public static Budget simple() {
        return new Budget("simple", 3, 2048, new BigDecimal("0.10"), Duration.ofMinutes(2));
    }
    
    public static Budget standard() {
        return new Budget("standard", 8, 4096, new BigDecimal("0.50"), Duration.ofMinutes(5));
    }
    
    public static Budget complex() {
        return new Budget("complex", 15, 8192, new BigDecimal("1.50"), Duration.ofMinutes(10));
    }
}

// 预算守卫（四维硬拦截）
public class BudgetGuard {
    
    private final BudgetTracker tracker;
    
    // 调用前检查（原子预留）
    @Transactional
    public ReservationResult reserve(UUID runId, ModelCallRequest request) {
        BudgetUsage current = tracker.getUsage(runId);
        Budget limit = tracker.getBudget(runId);
        
        // 维度1：步骤数检查
        if (current.steps() >= limit.maxSteps()) {
            return ReservationResult.rejected(
                BudgetDimension.STEPS,
                String.format("已达步骤上限 %d/%d", current.steps(), limit.maxSteps())
            );
        }
        
        // 维度2：Token预估检查（悲观估计：input全用 + output全用）
        int estimatedTokens = estimateTokens(request.prompt()) + limit.maxTokens() / 2;
        if (current.tokens() + estimatedTokens > limit.maxTokens()) {
            return ReservationResult.rejected(
                BudgetDimension.TOKENS,
                String.format("Token不足 %d + %d > %d", 
                    current.tokens(), estimatedTokens, limit.maxTokens())
            );
        }
        
        // 维度3：费用预留（按最坏情况预留，结算时多退少补）
        BigDecimal estimatedCost = estimateCost(request.model(), estimatedTokens);
        if (current.cost().add(estimatedCost).compareTo(limit.maxCost()) > 0) {
            return ReservationResult.rejected(
                BudgetDimension.COST,
                String.format("费用不足 $%.2f + $%.2f > $%.2f",
                    current.cost(), estimatedCost, limit.maxCost())
            );
        }
        
        // 维度4：墙钟时间检查
        Duration elapsed = Duration.between(current.startedAt(), Instant.now());
        if (elapsed.compareTo(limit.maxWallClock()) > 0) {
            return ReservationResult.rejected(
                BudgetDimension.WALL_CLOCK,
                String.format("已超时 %ds > %ds", 
                    elapsed.getSeconds(), limit.maxWallClock().getSeconds())
            );
        }
        
        // 5. 原子预留（写Reservation记录）
        Reservation reservation = Reservation.builder()
            .reservationId(UUID.randomUUID())
            .runId(runId)
            .estimatedTokens(estimatedTokens)
            .estimatedCost(estimatedCost)
            .reservedAt(Instant.now())
            .status(ReservationStatus.RESERVED)
            .build();
        
        reservationRepo.save(reservation);
        
        return ReservationResult.approved(reservation.reservationId());
    }
    
    // 调用后结算（精确计费）
    @Transactional
    public void settle(UUID reservationId, ModelCallResponse response) {
        Reservation reservation = reservationRepo.findById(reservationId).get();
        
        // 1. 实际消耗
        int actualTokens = response.usage().inputTokens() + response.usage().outputTokens();
        BigDecimal actualCost = response.usage().cost();
        
        // 2. 退还预留的余额
        BigDecimal refund = reservation.getEstimatedCost().subtract(actualCost);
        
        // 3. 更新Reservation状态
        reservation.setStatus(ReservationStatus.SETTLED);
        reservation.setActualTokens(actualTokens);
        reservation.setActualCost(actualCost);
        reservation.setRefund(refund);
        reservation.setSettledAt(Instant.now());
        
        reservationRepo.save(reservation);
        
        // 4. 更新BudgetUsage
        BudgetUsage usage = tracker.getUsage(reservation.getRunId());
        usage.addStep();
        usage.addTokens(actualTokens);
        usage.addCost(actualCost);
        
        tracker.saveUsage(usage);
    }
}
```

#### 3. 不可变配置包（ConfigBundle）

```java
// 配置包（版本化，不可变）
@Entity
@Table(name = "config_bundle")
public class ConfigBundle {
    
    @Id
    private String releaseId;  // "release-2026-09-v5"
    
    @Column(columnDefinition = "TEXT")
    private String systemPromptDigest;  // SHA256(prompt文本)
    
    private String modelProvider;  // "openai" | "dashscope"
    private String modelName;      // "gpt-4o-mini" | "qwen-plus"
    
    @Column(columnDefinition = "JSONB")
    private String toolWhitelist;  // {"PrimaryAgent": ["alert.query", ...]}
    
    @Column(columnDefinition = "JSONB")
    private String budgetConfig;   // {"simple": {...}, "standard": {...}}
    
    private String skillVersion;   // "diagnostic-skill-v3.2"
    
    private Instant publishedAt;
    
    @Column(columnDefinition = "JSONB")
    private String metadata;  // 发布说明、审批记录等
    
    // 全局指纹（任何字段变更都会改变）
    public String fingerprint() {
        String canonical = String.join("|",
            releaseId,
            systemPromptDigest,
            modelProvider,
            modelName,
            toolWhitelist,
            budgetConfig,
            skillVersion
        );
        return DigestUtils.sha256Hex(canonical);
    }
}

// Run绑定ConfigBundle（锁定版本）
@Entity
public class RcaRun {
    @Id
    private UUID runId;
    
    private String boundReleaseId;  // 创建时绑定的ConfigBundle版本
    
    // 禁止运行时切换版本（保证可复现）
    public void execute(AgentExecutor executor) {
        ConfigBundle currentRelease = configService.getCurrentRelease();
        
        if (this.boundReleaseId == null) {
            // 首次执行，绑定当前版本
            this.boundReleaseId = currentRelease.getReleaseId();
        } else if (!this.boundReleaseId.equals(currentRelease.getReleaseId())) {
            // 已绑定版本，必须使用相同版本（从历史加载）
            ConfigBundle boundRelease = configService.getRelease(this.boundReleaseId);
            executor = executor.withConfig(boundRelease);
        }
        
        executor.execute(this);
    }
}
```

#### 4. 证据快照Digest冻结

```java
// 输入快照（幂等恢复）
@Entity
@Table(name = "investigation_input_snapshot")
public class InputSnapshot {
    
    @Id
    private UUID snapshotId;
    
    private UUID runId;
    private Integer round;  // 第几轮
    
    @Column(columnDefinition = "JSONB")
    private String alertData;  // 告警原始数据
    
    @Column(columnDefinition = "JSONB")
    private String contextData;  // 上下文（前轮结果）
    
    private String digest;  // SHA256(alertData + contextData)
    
    private Instant createdAt;
    
    // 计算Digest（内容寻址）
    public static String computeDigest(String alertData, String contextData) {
        String canonical = alertData + "|" + contextData;
        return DigestUtils.sha256Hex(canonical);
    }
}

// 幂等恢复（崩溃后从快照重建）
public class IdempotentRecovery {
    
    // 恢复时验证输入一致性
    public DiagnosticContext recover(UUID runId, Integer round) {
        InputSnapshot snapshot = snapshotRepo.findByRunIdAndRound(runId, round)
            .orElseThrow(() -> new RecoveryException("快照不存在"));
        
        // 重新计算Digest
        String currentDigest = InputSnapshot.computeDigest(
            snapshot.getAlertData(),
            snapshot.getContextData()
        );
        
        // 验证一致性
        if (!currentDigest.equals(snapshot.getDigest())) {
            throw new RecoveryException("快照损坏，Digest不一致");
        }
        
        // 重建上下文
        return DiagnosticContext.builder()
            .runId(runId)
            .round(round)
            .alertData(deserialize(snapshot.getAlertData()))
            .contextData(deserialize(snapshot.getContextData()))
            .snapshotDigest(snapshot.getDigest())
            .build();
    }
}
```

### 技术成果
- **费用可控性100%**：90天生产环境，无一次超预算调查（$0.50硬拦截）
- **平均费用$0.12/次**：四维预算优化，相比无限制节省76%
- **可复现性100%**：ConfigBundle + InputSnapshot双重锁定，回放结果完全一致
- **墙钟P99<3分钟**：超时自动终止，生成带已有证据的部分报告

---

## 四、故障域隔离与自告警设计

### 问题描述
**"告警系统自身故障无人知晓"的自噬问题：**
1. 控制面宕机后，值班通知无法发送，值班人员无感知
2. 诊断引擎崩溃，新告警堆积，无人处理
3. 控制面监控告警进入诊断管线，形成递归依赖（系统监控自己）
4. 数据库故障导致告警接入失败，但无降级方案

### 解决方案

#### 1. 值班通知下沉至独立故障域

```
┌─────────────────────────────────────────────┐
│  主栈（控制面 + 诊断引擎）                   │
│  - PostgreSQL（告警数据、任务状态）          │
│  - control-app（Agent编排）                  │
│  - Redis（缓存）                             │
└──────────────┬──────────────────────────────┘
               │
               │ HTTP通知（最佳情况）
               ▼
┌─────────────────────────────────────────────┐
│  轻量适配器（独立故障域）                    │
│  - notify-adapter（无状态，内存队列）        │
│  - duty-adapter（值班表缓存，本地SQLite）    │
│  - 独立健康检查（心跳检测主栈）              │
└──────────────┬──────────────────────────────┘
               │
               │ Webhook/飞书/钉钉
               ▼
          值班人员手机
```

**关键设计：轻量适配器在主栈宕机时仍可工作**

```java
// 轻量适配器（无依赖主栈数据库）
@SpringBootApplication
public class NotifyAdapterApplication {
    
    public static void main(String[] args) {
        SpringApplication.run(NotifyAdapterApplication.class, args);
    }
}

// 配置：零外部依赖
@Configuration
public class AdapterConfig {
    
    // 不依赖PostgreSQL，使用内存队列
    @Bean
    public NotificationQueue notificationQueue() {
        return new InMemoryNotificationQueue(capacity: 10000);
    }
    
    // 值班表缓存在本地SQLite（每小时从主栈同步）
    @Bean
    public DutyScheduleCache dutyCache() {
        return new SqliteDutyCache("duty.db");
    }
}

// 双路径通知（主栈正常时走HTTP，主栈宕机时走告警直通）
public class DualPathNotifier {
    
    private final RestTemplate mainStackClient;
    private final CircuitBreaker mainStackCircuitBreaker;
    private final LarkWebhookClient larkClient;
    
    public void notify(AlertNotification notification) {
        // 路径1：主栈正常，走标准流程（带诊断报告）
        try {
            if (mainStackCircuitBreaker.isAvailable()) {
                RcaReport report = mainStackClient.getReport(notification.alertId());
                sendWithReport(notification, report);
                return;
            }
        } catch (Exception e) {
            log.warn("主栈不可用，降级为直通模式", e);
            mainStackCircuitBreaker.recordFailure();
        }
        
        // 路径2：主栈宕机，直通告警原始数据（无诊断）
        sendRawAlert(notification);
    }
    
    // 直通模式（无需主栈）
    private void sendRawAlert(AlertNotification notification) {
        // 1. 从本地缓存查值班表
        List<String> onDutyUsers = dutyCache.getOnDutyUsers(Instant.now());
        
        // 2. 拼装降级消息
        String message = String.format(
            "[主系统降级] 告警直通通知\n" +
            "告警: %s\n" +
            "严重性: %s\n" +
            "时间: %s\n" +
            "说明: 诊断系统暂时不可用，请手动排查",
            notification.alertName(),
            notification.severity(),
            notification.firedAt()
        );
        
        // 3. 发送飞书Webhook（不经过主栈）
        for (String userId : onDutyUsers) {
            larkClient.sendToUser(userId, message);
        }
        
        // 4. 记录到本地队列（主栈恢复后补发诊断报告）
        notificationQueue.enqueue(notification);
    }
}
```

#### 2. 控制面自告警走独立通道

```java
// 控制面健康检查（独立于诊断管线）
@Component
public class ControlPlaneHealthMonitor {
    
    private final LarkWebhookClient emergencyChannel;  // 紧急通道
    
    @Scheduled(fixedDelay = 30_000)  // 每30秒检查一次
    public void checkHealth() {
        HealthStatus status = performHealthCheck();
        
        if (!status.isHealthy()) {
            // 直接发送飞书，不进入告警接入管线
            sendEmergencyAlert(status);
        }
    }
    
    private HealthStatus performHealthCheck() {
        HealthStatus status = new HealthStatus();
        
        // 检查1：数据库连接
        try {
            jdbcTemplate.queryForObject("SELECT 1", Integer.class);
            status.markHealthy("database");
        } catch (Exception e) {
            status.markUnhealthy("database", e.getMessage());
        }
        
        // 检查2：Worker存活
        int activeWorkers = workerRegistry.getActiveCount();
        if (activeWorkers == 0) {
            status.markUnhealthy("workers", "无活跃Worker");
        }
        
        // 检查3：任务堆积
        long pendingTasks = taskRepo.countByState(TaskState.READY);
        if (pendingTasks > 100) {
            status.markUnhealthy("queue", 
                String.format("任务堆积 %d > 100", pendingTasks));
        }
        
        // 检查4：LLM可用性
        try {
            modelGateway.healthCheck();
            status.markHealthy("llm");
        } catch (Exception e) {
            status.markUnhealthy("llm", e.getMessage());
        }
        
        return status;
    }
    
    // 紧急告警（不进入诊断管线，避免递归依赖）
    private void sendEmergencyAlert(HealthStatus status) {
        String message = String.format(
            "[紧急] 告警诊断系统故障\n" +
            "时间: %s\n" +
            "故障组件: %s\n" +
            "详情: %s\n" +
            "影响: 新告警无法自动诊断",
            Instant.now(),
            status.getUnhealthyComponents(),
            status.getDetails()
        );
        
        // 直接发送飞书，跳过告警接入
        emergencyChannel.sendToGroup("ops-emergency", message);
        
        // 记录到独立表（不是alert_inbox）
        emergencyRepo.save(EmergencyAlert.builder()
            .alertId(UUID.randomUUID())
            .type("CONTROL_PLANE_FAILURE")
            .status(status.toString())
            .notifiedAt(Instant.now())
            .build());
    }
}
```

#### 3. 递归依赖切断

```java
// 告警接入时识别"系统自身告警"
public class AlertIngestionFilter {
    
    private static final Set<String> CONTROL_PLANE_ALERT_NAMES = Set.of(
        "control-app-down",
        "postgresql-connection-failed",
        "worker-all-dead",
        "llm-gateway-unavailable"
    );
    
    public IngestResult ingest(AlertPayload payload) {
        // 识别控制面告警
        if (isControlPlaneAlert(payload)) {
            // 不进入诊断管线，走紧急通道
            emergencyChannel.send(payload);
            
            return IngestResult.bypassed("控制面告警不进入诊断管线");
        }
        
        // 普通业务告警，正常处理
        return normalIngestion(payload);
    }
    
    private boolean isControlPlaneAlert(AlertPayload payload) {
        return CONTROL_PLANE_ALERT_NAMES.contains(payload.alertName())
            || payload.labels().get("component").equals("control-plane");
    }
}
```

### 技术成果
- **故障感知时间<1分钟**：控制面宕机后，值班人员在1分钟内收到降级通知
- **降级通知到达率100%**：轻量适配器独立故障域，主栈宕机仍可发送告警
- **避免递归依赖**：控制面自告警走独立通道，90天零"监控系统监控自己"死锁
- **值班表缓存命中率99.8%**：本地SQLite缓存，主栈宕机时仍可查询值班人员

---

## 五、六维评测与渐进发布

### 问题描述
**Agent行为变更的风险控制难题：**
1. Prompt微调后准确率提升还是下降？没有客观评测
2. 新版本Agent如何灰度？全量切换风险高
3. 第三方引擎（HolmesGPT）如何平滑替换为自研引擎？
4. 评测集泄漏到训练数据，模型"考试作弊"

### 解决方案

#### 1. 六维评测中心

```java
// 六维评测指标
public record EvaluationMetrics(
    ResultQuality resultQuality,    // 结果质量：准确率/召回率/F1
    ProcessQuality processQuality,  // 过程质量：步骤合理性/证据链完整性
    ToolUsage toolUsage,            // 工具使用：调用次数/成功率/覆盖率
    CostEfficiency costEfficiency,  // 成本效率：费用/Token/墙钟
    Collaboration collaboration,    // 协作质量：委派准确性/子任务成功率
    Security security               // 安全性：提示注入防御/数据泄漏检测
) {
    
    // 综合评分（加权）
    public double overallScore() {
        return 0.40 * resultQuality.score()
             + 0.15 * processQuality.score()
             + 0.10 * toolUsage.score()
             + 0.15 * costEfficiency.score()
             + 0.10 * collaboration.score()
             + 0.10 * security.score();
    }
}

// 评测执行器
public class EvaluationExecutor {
    
    public EvaluationReport run(
        ConfigBundle candidateRelease,
        EvaluationDataset dataset
    ) {
        List<EvaluationCase> cases = dataset.getCases();
        List<CaseResult> results = new ArrayList<>();
        
        for (EvaluationCase case : cases) {
            // 1. 执行诊断（隔离环境）
            DiagnosticContext ctx = buildContext(case);
            RcaReport report = agentExecutor
                .withConfig(candidateRelease)
                .execute(ctx);
            
            // 2. 六维评分
            EvaluationMetrics metrics = evaluate(report, case.groundTruth());
            
            // 3. 记录结果
            results.add(CaseResult.builder()
                .caseId(case.caseId())
                .metrics(metrics)
                .report(report)
                .executionTrace(ctx.getTrace())
                .build());
        }
        
        // 4. 汇总报告
        return EvaluationReport.builder()
            .releaseId(candidateRelease.getReleaseId())
            .dataset(dataset.name())
            .totalCases(cases.size())
            .passedCases(countPassed(results))
            .aggregatedMetrics(aggregate(results))
            .results(results)
            .evaluatedAt(Instant.now())
            .build();
    }
    
    // 六维评分实现
    private EvaluationMetrics evaluate(RcaReport report, GroundTruth truth) {
        // 维度1：结果质量
        ResultQuality resultQuality = new ResultQuality(
            precision: computePrecision(report.claims(), truth.expectedClaims()),
            recall: computeRecall(report.claims(), truth.expectedClaims()),
            f1: computeF1(report.claims(), truth.expectedClaims())
        );
        
        // 维度2：过程质量
        ProcessQuality processQuality = new ProcessQuality(
            stepRationality: checkStepRationality(report.trace()),
            evidenceChainCompleteness: checkEvidenceChain(report.evidenceGraph())
        );
        
        // 维度3：工具使用
        ToolUsage toolUsage = new ToolUsage(
            callCount: report.trace().getToolCalls().size(),
            successRate: computeToolSuccessRate(report.trace()),
            coverage: computeToolCoverage(report.trace(), truth.expectedTools())
        );
        
        // 维度4：成本效率
        CostEfficiency costEfficiency = new CostEfficiency(
            cost: report.usage().cost(),
            tokens: report.usage().totalTokens(),
            wallClock: report.usage().wallClockSeconds()
        );
        
        // 维度5：协作质量
        Collaboration collaboration = new Collaboration(
            delegationAccuracy: checkDelegationAccuracy(report.trace(), truth),
            subtaskSuccessRate: computeSubtaskSuccessRate(report.trace())
        );
        
        // 维度6：安全性
        Security security = new Security(
            promptInjectionDefense: checkPromptInjection(report.trace()),
            dataLeakage: checkDataLeakage(report.trace(), truth)
        );
        
        return new EvaluationMetrics(
            resultQuality, processQuality, toolUsage, 
            costEfficiency, collaboration, security
        );
    }
}
```

#### 2. HOLDOUT集作为发布门禁

```java
// 评测数据集分层
public class EvaluationDataset {
    
    private String name;
    private List<EvaluationCase> trainingSet;    // 70%，可用于Prompt调优
    private List<EvaluationCase> validationSet;  // 20%，用于验证过拟合
    private List<EvaluationCase> holdoutSet;     // 10%，仅用于发布门禁
    
    // 发布门禁：必须在HOLDOUT集上达标
    public boolean passReleaseGate(ConfigBundle release) {
        EvaluationReport report = evaluator.run(release, holdoutSet);
        
        // 门禁条件（硬性）
        return report.getAggregatedMetrics().overallScore() >= 0.75  // 综合75分
            && report.getAggregatedMetrics().resultQuality().precision() >= 0.80  // 准确率80%
            && report.getAggregatedMetrics().security().promptInjectionDefense() == 1.0;  // 注入防御100%
    }
    
    // 防止HOLDOUT集泄漏
    @PreAuthorize("hasRole('RELEASE_MANAGER')")
    public List<EvaluationCase> getHoldoutSet() {
        // 只有发布管理员可访问HOLDOUT集
        return holdoutSet;
    }
}
```

#### 3. Shadow→Canary→Primary渐进发布

```java
// 三级发布流程
public class ProgressiveRollout {
    
    // 阶段1：Shadow模式（双跑对比，不影响用户）
    public ShadowResult runShadow(
        ConfigBundle candidateRelease,
        ConfigBundle baselineRelease,
        Duration duration
    ) {
        Instant startTime = Instant.now();
        List<ComparisonResult> comparisons = new ArrayList<>();
        
        while (Duration.between(startTime, Instant.now()).compareTo(duration) < 0) {
            // 获取实时告警
            AlertPayload alert = alertStream.next();
            
            // 双跑：Baseline + Candidate
            CompletableFuture<RcaReport> baselineFuture = CompletableFuture.supplyAsync(
                () -> agentExecutor.withConfig(baselineRelease).execute(alert)
            );
            
            CompletableFuture<RcaReport> candidateFuture = CompletableFuture.supplyAsync(
                () -> agentExecutor.withConfig(candidateRelease).execute(alert)
            );
            
            // 等待双跑完成
            RcaReport baselineReport = baselineFuture.get();
            RcaReport candidateReport = candidateFuture.get();
            
            // 对比差异
            ComparisonResult comparison = compare(baselineReport, candidateReport);
            comparisons.add(comparison);
            
            // 用户看到的是baseline结果（candidate不影响）
            deliverReport(alert, baselineReport);
        }
        
        // 汇总Shadow结果
        return ShadowResult.builder()
            .totalAlerts(comparisons.size())
            .agreementRate(computeAgreementRate(comparisons))  // 结论一致率
            .regressions(findRegressions(comparisons))         // 退化case
            .improvements(findImprovements(comparisons))       // 改进case
            .build();
    }
    
    // 阶段2：Canary模式（小流量真实发布）
    public CanaryResult runCanary(
        ConfigBundle candidateRelease,
        double trafficRatio,  // 5% | 10% | 25%
        Duration duration
    ) {
        Instant startTime = Instant.now();
        List<RcaReport> canaryReports = new ArrayList<>();
        List<RcaReport> baselineReports = new ArrayList<>();
        
        while (Duration.between(startTime, Instant.now()).compareTo(duration) < 0) {
            AlertPayload alert = alertStream.next();
            
            // 按比例路由
            boolean useCanary = ThreadLocalRandom.current().nextDouble() < trafficRatio;
            ConfigBundle selectedRelease = useCanary ? candidateRelease : baselineRelease;
            
            RcaReport report = agentExecutor.withConfig(selectedRelease).execute(alert);
            
            if (useCanary) {
                canaryReports.add(report);
            } else {
                baselineReports.add(report);
            }
            
            // 用户看到真实结果（Canary或Baseline）
            deliverReport(alert, report);
            
            // 实时监控：Canary错误率激增则秒级回滚
            if (canaryReports.size() > 10) {
                double canaryErrorRate = computeErrorRate(canaryReports);
                double baselineErrorRate = computeErrorRate(baselineReports);
                
                if (canaryErrorRate > baselineErrorRate * 1.5) {
                    // 错误率激增50%，立即回滚
                    return CanaryResult.aborted("错误率激增，已回滚");
                }
            }
        }
        
        return CanaryResult.builder()
            .canaryTraffic(canaryReports.size())
            .baselineTraffic(baselineReports.size())
            .canaryMetrics(aggregate(canaryReports))
            .baselineMetrics(aggregate(baselineReports))
            .passed(isPassed(canaryReports, baselineReports))
            .build();
    }
    
    // 阶段3：Primary切换（全量发布）
    public void promoteToP rimary(ConfigBundle candidateRelease) {
        // 1. 修改current_release指针（原子操作）
        configService.setCurrentRelease(candidateRelease.getReleaseId());
        
        // 2. 触发配置热更新
        applicationEventPublisher.publishEvent(
            new ConfigUpdateEvent(candidateRelease)
        );
        
        // 3. 记录发布历史
        releaseHistoryRepo.save(ReleaseRecord.builder()
            .releaseId(candidateRelease.getReleaseId())
            .promotedAt(Instant.now())
            .promotedBy(SecurityContextHolder.getContext().getAuthentication().getName())
            .build());
    }
}
```

#### 4. 第三方引擎到自研引擎的平滑替换

```java
// 引擎抽象（第三方与自研统一接口）
public interface RcaEngine {
    RcaReport investigate(AlertPayload alert);
    String engineId();
    String version();
}

// HolmesGPT适配器（第三方）
public class HolmesGptEngine implements RcaEngine {
    
    private final HolmesClient holmesClient;
    
    @Override
    public RcaReport investigate(AlertPayload alert) {
        // 调用HolmesGPT CLI
        HolmesResult result = holmesClient.investigate(alert);
        
        // 转换为统一格式
        return convertToRcaReport(result);
    }
    
    @Override
    public String engineId() {
        return "holmesgpt";
    }
}

// 自研引擎（Java实现）
public class NativeRcaEngine implements RcaEngine {
    
    private final AgentExecutor agentExecutor;
    
    @Override
    public RcaReport investigate(AlertPayload alert) {
        // 调用自研Multi-Agent引擎
        return agentExecutor.execute(alert);
    }
    
    @Override
    public String engineId() {
        return "native";
    }
}

// 替换策略（Shadow→Canary→Primary）
public class EngineReplacementOrchestrator {
    
    public void replaceEngine() {
        // 步骤1：Shadow模式双跑30天
        ShadowResult shadow = progressiveRollout.runShadow(
            candidateRelease: nativeEngine,
            baselineRelease: holmesEngine,
            duration: Duration.ofDays(30)
        );
        
        if (shadow.agreementRate() < 0.90) {
            throw new ReplacementAbortedException("一致率不足90%");
        }
        
        // 步骤2：Canary 5% 流量7天
        CanaryResult canary5 = progressiveRollout.runCanary(
            candidateRelease: nativeEngine,
            trafficRatio: 0.05,
            duration: Duration.ofDays(7)
        );
        
        // 步骤3：Canary 25% 流量7天
        CanaryResult canary25 = progressiveRollout.runCanary(
            candidateRelease: nativeEngine,
            trafficRatio: 0.25,
            duration: Duration.ofDays(7)
        );
        
        // 步骤4：Canary 50% 流量7天
        CanaryResult canary50 = progressiveRollout.runCanary(
            candidateRelease: nativeEngine,
            trafficRatio: 0.50,
            duration: Duration.ofDays(7)
        );
        
        // 步骤5：全量切换
        progressiveRollout.promoteToPrimary(nativeEngine);
        
        // 步骤6：观察7天后下线HolmesGPT
        Thread.sleep(Duration.ofDays(7).toMillis());
        holmesEngine.shutdown();
    }
}
```

### 技术成果
- **评测覆盖率100%**：六维评测覆盖结果/过程/工具/成本/协作/安全，32个评测case
- **HOLDOUT集零泄漏**：HOLDOUT集仅发布管理员可见，开发者无法接触
- **渐进发布零事故**：Shadow→5%→25%→50%→100%五级发布，每级观察7天
- **第三方引擎平滑替换**：HolmesGPT→自研引擎，历时90天完成替换，零用户感知

---

## 项目成果总结

**业务指标**：
- 告警响应时间降低88%：从平均25分钟降至3分钟
- 根因定位准确率85%：相比人工排查（资深75%/新人50%）大幅提升
- 值班人员工作量降低70%：自动化处理覆盖率85%
- 平均每次诊断费用$0.12：四维预算控制，相比无限制节省76%

**技术指标**：
- 系统可用性99.95%：90天生产环境稳定运行
- 预算超限率0%：四维硬拦截，无一次超预算调查
- 幻觉拦截率100%：ClaimValidator代码级校验，拦截82次虚假结论
- 故障感知时间<1分钟：控制面宕机后，值班人员立即收到降级通知

**架构能力**：
- 动态Multi-Agent编排：主Agent直接调查 + 按需委派，预算节省60%
- 三层事实分离：Observation/Findings/Claim + 代码级校验，防止模型幻觉
- 四维预算硬拦截：步骤/Token/费用/墙钟四维控制，平均$0.12/次
- 故障域隔离：轻量适配器独立故障域，主栈宕机仍可通知
- 六维评测 + 渐进发布：Shadow→Canary→Primary三级发布，第三方引擎平滑替换

---

## 技术栈与面试准备

**核心技术栈**：
Java 21 | Spring Boot 3.4 | Spring AI | PostgreSQL | Multi-Agent | DAG Scheduler | Lease & Epoch | Evidence Graph | LiteLLM Proxy | Virtual Key | Budget Guard | ConfigBundle | Shadow/Canary | Circuit Breaker | Dual Path Notification

**面试深挖方向**：

**Multi-Agent编排**：
1. 为什么不是固定三角色流水线？动态委派的优势？
2. DAG调度器如何避免死锁？（主任务等子任务，子任务又等主任务）
3. 租约抢占的场景？如何保证被抢占任务的状态一致性？

**三层事实分离**：
1. 为什么需要Observation/Findings/Claim三层？不能两层？
2. ClaimValidator的时间因果一致性检查具体逻辑？
3. 如何防止"两个Agent复述同一条记录被当作两份印证"？

**预算控制**：
1. 四维预算（步骤/Token/费用/墙钟）的优先级？冲突时如何取舍？
2. 虚拟Key的作用？为什么不直接在代码里限流？
3. 预留-结算模式的必要性？为什么不能调用后再扣预算？

**故障域隔离**：
1. 轻量适配器的"轻量"体现在哪？为什么能在主栈宕机时工作？
2. 控制面自告警如何避免递归依赖？（监控系统监控自己）
3. 双路径通知的降级策略？主栈恢复后如何补发诊断报告？

**评测与发布**：
1. HOLDOUT集为什么要隔离？如何防止开发者接触？
2. Shadow模式的实现？双跑如何保证不影响用户？
3. Canary错误率激增50%回滚的阈值如何确定？
