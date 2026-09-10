# 告警R7-真LLM多Agent技术方案 v2.1（主Agent直接调查+按需委派+有界恢复）

> v2.1（2026-09-11）：依据用户要求及一手资料调研，取消默认固定三角色流水线。主Agent可直接使用受限工具完成简单告警，只在证据缺口、专业能力或上下文隔离需要时申请子Agent。原位修改角色、状态机、验收门与任务卡；新增§十六的行业依据和§十七的动态委派测试。本文是待实施设计，不代表能力已经实现、部署或测试通过。

> 历史：v2.0保留的核心是统一执行入口、单一预算所有者、来源分离和有界补证；原固定双调查根→诊断的拓扑被本版替代。
> 硬前置（落码闸）：EX-A0 契约冻结 → EX-A1 预算 / EX-A2 提交栅栏 / EX-A4a / EX-A3 绿。R7-A0指标冒烟不依赖EX-B1/B2；R7-A1日志+部署版本代码闭环依赖真实日志及新增代码工具；B门依赖EX-B1/B2与动态调度地基。R7-A0/A1不是EX-A0/A1任务卡。

---

## 一、目标与非目标

目标：真实checkout告警→主Agent依据观察选择直接查询、按需委派或结束→必要时汇总专业子Agent结果→主Agent提出有类型Claim→代码准入与报告。简单告警允许零子Agent；复杂告警按缺口逐步升级。全程预算可控、可取消、可恢复、可回放。
非目标：自由多轮对话 loop、模型写 SQL/URL/shell、多提供商路由、LLM Critic 推翻现场证据、里程碑 B 前的并行 fan-out。

## 二、模型网关 RCA 适配（F02，主会话 W2 先行；按评审 §4.2-3 修订）

1. **不新抽同名 ModelRouter 类**——直接复用现有 `ModelGateway` 的路由/错误分类/重试面，仅新增 RCA 适配层；旧 PR 路径行为不变。
2. `RcaModelCallContext`：runId/taskId/attemptId/actionSeq/roleId/roleVersion/roleDigest/roundId/configEpoch/releaseDigest/leaseEpoch/inputSnapshotDigest/runDeadline/taskDeadline/budgetReservationId；区分逻辑动作与物理请求序号，deadline=min(run, task, gateway)。旧agentId/version迁移映射到role身份，不重复维护两套语义。
3. `V__rca_model_call.sql`：外键全部落 RCA 域 + agent_id/agent_version/prompt_digest/model/provider_request_id/state(STARTED/SUCCESS/FAILED/UNKNOWN)/usage/cost/latency/created_at/settled_at。**账本不可写=零触网**；UNKNOWN 保守占预算、恢复时对账不盲重发（与 EX-A0 动作身份契约、EX-A3 恢复四阶段共用语义）。
4. **模型动作与工具动作同过一个执行入口（ActionGuard）**，各自校验模型路由或工具权限；每个物理重试单独预留单独计费；预算所有者全系统唯一（EX-A1 的 RunBudgetGate）。
5. usage 对账复用 `UsageLedgerReconciler` 三态口径。

验收：账本不可写零触网（单测计数+E2E受控端点计数）；缺usage不猜零。请求结果状态与费用对账状态分开：响应已成功但usage缺失，不应伪称响应失败，也不能免费结算；UNKNOWN请求是否已执行不确定时保守占用。真实触网验收必须在执行守护就绪后允许。

## 三、主Agent与可选专家：先解决最小问题

| 角色 | 类/演进落点 | 工具 allowlist | 输出 |
|---|---|---|---|
| 主调查与诊断，唯一必选 | 拟由`RcaDiagnosisAgent`职责演进为`PrimaryInvestigationAgent`，通过通用运行器执行 | 告警详情、受限`logs.query`、部署版本`code.search/code.read`、基础`prometheus.query`；按发布能力交集开放 | TOOL_CALL / DELEGATE / FINAL，唯一模型Claim提案出口 |
| 指标专家，按需 | `MetricInvestigationAgent` | `prometheus.query` | 原始Observation引用+派生Findings+缺口/停止原因 |
| 日志与变更专家，按需 | `LogChangeInvestigationAgent` | `logs.query`/`change.query` | 同上；适合跨服务或较长变更调查，不垄断简单日志查询 |

**默认只创建主任务，不预建两个调查根。** checkout告警若在当前部署版本日志中发现明确异常，主Agent可以查对应代码和必要反证后直接报告。只有还需要专业指标分析、跨服务取证或隔离大量上下文时才委派。代码存在异常分支不证明现场执行了该分支，仍需日志/trace等支持。无法定位时允许未决，不为展示多Agent而强派任务。

**有界AgentLoop**：固定版本上下文→模型ActionGuard→解析互斥Decision→执行对应受控动作→持久化结果→继续。主Agent建议初始max_steps=8，子Agent≤4；这些是待评测的上限，不是吞吐或质量承诺。主任务被唤醒后不重置steps，物理重试另计且仍受Run共享预算。不存隐藏思维链；存动作、委派理由、依据引用和状态。

主Decision的三个分支：`TOOL_CALL{tool_id,args}`直接取证；`DELEGATE{requests:[{gap_id,role_id,question,input_refs,scope,requested_budget}]}`提出一批有界委派；`FINAL{claims,missing_information}`提出报告。每次只能选一个分支。LLM选择是否委派以及专家身份，确定性Supervisor校验目录、权限、预算、去重和任务上限；不默默换成另一专家，拒绝后返回结构化原因。子Agent不具委派权限，首期父子深度为1；需要其他能力时返回缺口交给主Agent。直接工具查询不是一次子Agent委派。

缺数据源按工具能力逐项处理：日志与变更之一不可用时标记PARTIAL，能完成另一来源就继续；都不可用才SKIPPED/MISSING_SOURCE。required失败不能因全任务终态就变成根因成功。无信息增益以新证据身份/缺口变化衡量，不以调用次数衡量。

## 四、主任务等待与有界委派状态机

**承认并修改**：`DeterministicSupervisor.advance()` 全任务终态→REPORTING 的终态条件必须改（v1.0"不动已测核心"作废）。

```
创建主任务 → PRIMARY_READY（round 0）
  TOOL_CALL → 守卫/取证/冻结新输入快照 → PRIMARY_READY（不增加委派round）
  DELEGATE → Supervisor确定性校验
    批准 → 原子保存裁决+子任务+依赖+WAITING_CHILDREN（round n+1）
           → 子任务有界执行 → 汇总已准入结果/失败缺口 → PRIMARY_READY
    拒绝 → 保存原因 → 有界继续直接调查或输出未决
  FINAL → ClaimValidator及终态门 → REPORTING
进入REPORTING：主Agent已FINAL或代码已生成终止兜底，且有效子任务/补证/必要复核均已结清。
```

上述PRIMARY_READY/WAITING_CHILDREN是拟新增的主任务检查点阶段，不是声称现有Run枚举已有这些值。主任务逻辑等待时释放worker，靠持久化条件推进和幂等唤醒恢复，不能用一个线程无限等待子Agent。主任务在调用子任务期间并未完成，禁止先置DONE再往其终态DAG追加节点。

父子归属不是完成依赖边。子任务依赖的是主Agent已经提交的输入快照/委派事件，不能依赖“父任务DONE”，否则父等子、子等父会死锁。子批次内部保持DAG无环；父任务续跑由批次结清条件驱动，不给同一DAG添加子→父→子的循环边。若拆成续跑记录，仍绑定原主任务的累计steps、预算和身份，不能算新调查重置额度。

**必须持久化**：主任务检查点、决策序号、累计steps、输入快照digest、round_id、父请求id、去重键、批准/拒绝原因、子任务绑定、失败策略、deadline和唤醒条件。唯一键至少覆盖run+主任务+决策序号+请求序号。同一主任务只能有一个有效驱动所有者；恢复通过租约epoch和revision条件更新。重复完成事件不重复唤醒，不重复创建任务。子结果和父检查点不能靠进程内Future保存。

首期`max_delegation_batches=2`，初始round0，最多两批round1/2；每批至多2个专家请求，默认串行执行，C门通过后才开放并发2。旧`max_supplement_rounds`仅在旧版release恢复时保留原语义；新release使用新字段，禁止双字段叠加额度。Run累计任务上限8包含主任务、子任务与可选复核；角色上限、模型物理请求/token/费用/总时限分别约束。批次耗尽仍可在已有总预算内有限直接查询或结束，但不能用新task绕过委派限制。

终止兜底：诊断无法调用、输出反复非法、预算/时限耗尽时，代码生成带已有事实与缺口的确定性未决报告，不要求必须再花一次模型调用才能FINAL。若Run已取消则走取消终态；租约丢失由新driver恢复。区分“流程终止”与“根因确认”。有效待处理补证/复核未完成不得进入REPORTING。

## 五、Observation / Findings / Claim 三层来源分离（P1-06）

1. **Observation**=工具返回的原始现场证据（宿主赋予来源身份）；**Findings**=模型派生解释（明确类别+父引用+producer 身份，**不算独立现场来源**——两个 Agent 复述同一日志不是两份印证）；**Claim**=最终命题。可共用存储，类别/父引用/来源身份三字段必齐。
2. **单一模型出口**：主Agent负责提出Claim；`ClaimValidator`（代码，非模型）负责引用/类型/必要证据条件准入。原诊断职责合入主Agent，不在主Agent之后固定追加一个诊断LLM调用；专家不能直接发布最终Claim。
3. ROOT_CAUSE 准入=必要结构（机制陈述+引用）**且**通过可程序检查的场景证据条件清单；不满足→HYPOTHESIS 或"原因未确认"；反证与缺失必须保留；正确性最终由盲评核验，不由模型自填类型晋升。

## 六、ActionGuard 固定顺序（不变，组装件）

Run活跃→未取消→generation→leaseEpoch→deadline→角色版本/工具或模型路由获准→原子预留预算→写调用记录并取得发送资格→执行→有界读取→受所有权事务保护的结果准入→费用与任务结算。取消提交后不得取得新发送资格；此前在飞请求尽力中断，晚到结果仅作审计/费用对账，不承诺撤回已发送网络请求。角色新增不产生第二条执行入口。

## 七、里程碑验收门（P1-07 修订）

**R7-A0 = 主Agent指标冒烟**：真实checkout现场→主Agent选择受限指标查询→真实执行→带引用报告，允许未决；零子Agent。run/task/attempt与模型供应商回执关联完整。该门只证明受控模型循环，不证明日志+代码能力。

**R7-A1 = 简单告警完整验收**：真实日志+当前部署版本代码→主Agent直接调查→引用与必要反证→报告，全程零委派且无独立诊断模型。依赖EX-B2和R7-X10代码工具；数据不足明确未决。未接代码工具时此门NOT_READY，不能用A0冒烟冒充通过。

排期约束：新增代码工具仍遵守增强线“EX收口且C门通过后编码”的既定硬门。因此R7-A1是C门后的能力补全验收，不作为核心B/C门前置；核心B门的简单零委派场景用当时已交付的真实日志/指标完成。交付说明必须分别列核心动态协作通过与日志+代码能力是否通过，不能隐去后者。这样既不绕过旧门，也不形成C门→代码工具→B门→C门的循环依赖。

**B门 = 动态协作RCA**：①简单场景零子Agent；②确有指标或日志/变更专业缺口时只调用相关专家；③两个独立缺口时有界委派两专家；④第二批补证或失败/预算不足后有界收敛。不能强制每次三角色全跑。冲突以反证保留，真实模型至少覆盖零委派与必要委派，E模式再精确覆盖全部边界。与主Agent单独处理、固定三角色方案做同预算配对盲评。

**测试纪律**：CI 用脚本化 stub 模型（确定性应答、不触网）；真实模型只在 195 验收窗；每场景附"不预灌答案"断言。

## 八、配置与指纹（不变）

ConfigBundle `llmRca` 段：enabled/模型路由/各角色max_steps/Run预算/角色集合与版本/能力映射/场景路由/最大补证与累计任务数。release固定Prompt、Skill、工具schema与Harness版本。只有实际走LLM的Run标记真实模型臂，保留确定性fallback的明确身份；不能仅改名称就退役确定性标记。与增强线§八/九版本热更新契约一致。

## 九、排期挂接（v2.1）

| 段 | 内容 | 窗口 | 前置 |
|---|---|---|---|
| R7a-1 | §二模型网关适配（隔离测试先行，真实触网等守护） | W2 起（主会话） | EX-A0 契约 |
| R7a-2 | ActionGuard+主Agent受限直接调用+R7-A0 | A1/A2/A4a/A3绿后 | 执行者前四卡 |
| R7增强补全 | 日志+部署版本代码直查+R7-A1 | EX收口且C门通过后，单独估时 | EX-B2、R7-X10、增强线编码硬门 |
| R7b/c | 动态委派/父任务恢复/ClaimValidator+B门 | 地基与真实工具就绪后 | EX-B1/B2、R7-X1～6、X11 |
| — | C 门（并发恢复矩阵） | EX-A4b 后 | 执行者 |

## 十、审查发现：三角色能跑与角色可扩展是两件事

核对日期2026-09-11，以下为当前工作区源码事实，不推断远端部署进度。路径相对`control-app/src/main/java/com/objwww/pr/control/`。

| 落点 | 当前事实 | 必须补的设计 |
|---|---|---|
| `alert/application/agent/AgentRegistry.java` | 启动构造不可变注册表，无运行时注册API | 继续保留不可变快照；后期通过版本化发布创建新快照，不直接修改共享Map |
| `alert/domain/agent/AgentProfile.java` | 已有prompt/allowlist/budget/schema与digest | 复用，不另建重复Profile；补输入契约、阶段、依赖能力与运行器类型 |
| 同上构造函数 | outputSchema仅Map.copyOf顶层复制 | 递归深冻结嵌套schema；外部修改不能让同一Profile的digest漂移 |
| `alert/application/PlanCompiler.java` | 校验task.type中的注册角色，但落RcaTask只有taskKey | 持久化task到roleId/version/digest、round、release的绑定，不能重启后猜角色 |
| `alert/domain/model/RcaTask.java` | 无显式角色绑定字段 | 扩task或建task_execution_binding，依照已有任务语义选一个，不重复建两份 |
| `infrastructure/nativeexec/NativeInvestigationExecutor.java:373`附近 | switch固定TASK_METRICS/LOGS/CHANGE | 改为从持久绑定解析role，再委托统一运行器；未知角色明确拒绝 |
| `infrastructure/config/AlertAm4Config.java:260`附近 | 硬编码三份旧Profile与三个Agent Bean | 区分兼容旧确定性路径与新LLM角色装配；不能仅注册新名字却无执行器 |
| `PlanCompiler.MAX_TASKS/MAX_DEPTH` | 固定8任务、3条边深度；verify按名称计数 | 首期保留上限，另设Run累计界限；验证角色按阶段与能力判定，不依赖名字verify |

最严重缺口是“配置已注册、编译通过、运行却无法分派”。角色扩展验收必须包含任意合法taskKey与重启恢复，不能只检查注册表all()多了一条。

## 十一、角色扩展的最小架构

### 11.1 六个概念要分开

Role是有明确职责、输入输出和权限的调查参与者；Task是某轮某角色的一次具体工作；Tool是访问现场的受控能力；Skill是角色采用的方法；Supervisor是确定性调度/预算/终态裁决代码；ClaimValidator是证据与类型校验代码。不能因为存在一段处理逻辑就全部包装成LLM角色。

建议新增通用`RoleRunner`契约，首先只实现受控的BoundedLlmRoleRunner；旧确定性执行器通过兼容适配接入或保留原路由，迁移后再去掉重复路径。RoleRegistry绑定“已发布Profile+已部署运行器”，不接受模型上传Java类或任意脚本。

常规新增角色只新增Profile、输出schema、工具allowlist与路由配置，由同一个LLM运行器执行。需要新协议/工具的角色仍要部署受审查代码；“配置热更新”不意味着Java实现也可热插拔。第三方框架不作为R7前置。

### 11.2 角色和任务字段

扩Profile或其关联manifest：role_id/version/digest、phase(PRIMARY/INVESTIGATE/REVIEW/POSTPROCESS，DIAGNOSE仅旧版兼容)、runtime_kind、input_schema_digest/output_schema_digest、prompt_digest、tool版本约束、required_capabilities、activation_selector、max_steps、budget_caps、termination_policy。

任务绑定：task_id、run_id、round_id、role_id/version/digest、release_digest/config_epoch、input_refs、expected_output_schema、parent_request_id、required/optional及失败策略。写入编译事务，并增加相关唯一键/FK。原taskKey仅为本轮业务实例标识，不再承担角色身份。

恢复只读取这个绑定与固定资产；已部署节点没有对应运行器/资源时返回CAPABILITY_UNAVAILABLE，不能选latest或同名版本顶替。旧Run未完成前保留其角色版本与schema。

### 11.3 协作通过工件和显式依赖

角色输入是Host构造的TaskEnvelope：服务范围、事故时间窗、待回答问题、冻结证据/Findings引用、反证、剩余预算和停止规则。不共享一个可变messages列表，也不默认把所有角色历史全部广播。

角色输出保留producer/role版本、原始来源与父refs。下游读取已准入的结构化工件；需要上游结果必须有依赖边。模型文本不是新的系统指令，跨角色消息不能扩大授权。

子Agent的EvidenceRequest仅声明缺口、假设、能力、范围/窗口与停止条件；主Agent据此决定直接查还是DELEGATE，并选择冻结目录中的role_id。Supervisor负责批准/拒绝，不承担LLM思考，也不默默换角色。去重首先基于Host校验的结构化gap_id、服务/窗口/能力和证据快照；无新增证据的重复动作由DoomLoopGuard限制。不能把不确定的文本语义判重当成唯一正确性保障。

### 11.4 动态选择不等于每次调用全部角色

首期即采用主Agent直接调查+按需委派，不保留默认三角色模板。先由代码按数据能力、权限、发布版本形成候选集合，再由主Agent跨多个决策步骤动态选择；无需另外增加一个只做分类的路由LLM。新专家默认optional，必要证据是否缺失独立判断，不能因专家optional而降低根因准入要求。

计划上限按每轮任务数、每Run累计任务数、角色激活数、补证数、模型物理请求数分别检查。不要为增加角色随意将MAX_TASKS设成无限；多个角色按需出现时仍可维持小DAG。

若并发放开：先最大2个独立调查任务，预算原子共享；工具池也设并发上限。收敛等待有界，optional失败可继续、required失败按策略降级/未决。预留诊断和报告所需额度，防调查角色吃光预算；预留仍计入总额，不另赠额度。

### 11.5 版本、热更新与前端

角色目录随release固定：新增角色发布后新Run可选，旧Run不自动增加节点。进行中Run若角色集合/DAG/权限变化，首期拒绝热迁移，创建关联新调查；兼容Prompt更新仅按增强线安全点流程进行。MCP tool list变化也不能偷偷变成新角色能力。

前端RunDag读取实际task与role metadata，角色名/阶段/状态/预算来自后端；未知展示类型使用通用节点，不能硬编码仅三张卡。展示“未启用/缺来源/已跳过/失败”差别；声明并行根不代表当前运行真的并发。不显示或伪造隐藏思维链。

## 十二、调研后建议增加哪些角色

### 12.1 一手资料与结论边界

| 来源 | 可借鉴点 | 不直接照搬的部分 |
|---|---|---|
| [Anthropic Building effective agents](https://www.anthropic.com/engineering/building-effective-agents) | 简单组合、路由、独立并行及有明确标准的评估反馈 | 这是通用模式，不能证明本项目多角色一定提质 |
| [Anthropic多Agent研究系统](https://www.anthropic.com/engineering/multi-agent-research-system) | 明确委派任务边界、上下文与资源控制 | 研究任务的效果/成本不能外推到告警RCA |
| [OpenTelemetry Traces](https://opentelemetry.io/docs/concepts/signals/traces/) | 跨服务请求由span及关系关联 | 有trace不等于根因已确认，采样/缺span要披露 |
| [HolmesGPT runbooks，固定0.23.0参考](https://holmesgpt.dev/0.23.0/reference/runbooks/) | catalog匹配与fetch_runbook | 取runbook可直接做工具/Skill，不必先加“知识Agent” |

以下优先级是结合本项目数据面和职责缺口的设计判断，不是官方要求。角色上线前必须有真实数据和同预算消融证据。

### 12.2 候选角色清单

| 角色/建议优先级 | 何时需要 | 输入与只读能力 | 输出与边界 | 没准备好怎么办 |
|---|---|---|---|---|
| **TraceTopologyInvestigator 调用链与依赖调查，优先候选** | checkout异常但指标/日志不能定位上游或下游传播 | trace_id、冻结窗口、服务范围；trace查询与拓扑快照 | 异常span、传播路径、支持/反证refs；缺span不能推断服务正常 | 没有可检索trace和采样信息时不建角色，先接工具 |
| **ChangeInvestigator 独立变更调查，条件拆分** | 当前日志与变更角色因两类资料过长而遗漏变更核对 | 部署/config diff、实际生效时间、影响服务、旧新版本 | 时间相关性、机制假设和反证；最近发布不自动等于原因 | 保持合并角色；拆分后替换旧职责，避免两角色重复查同一面 |
| **CounterEvidenceReviewer 反证复核，按风险触发** | 已拟确认根因但证据冲突或关键影响较高 | 冻结Claim提案与原始refs；默认零工具，可提结构化补证 | 疑点、缺口、反证、是否建议重新诊断；不能多数投票推翻事实 | 先做代码规则和离线人工复核；只有可测减少错误确认时上线 |
| **RuntimeInfrastructureInvestigator 运行环境调查，按需** | 容器OOM/重启、资源压力、数据库锁等信号 | 受限docker事件、资源指标、只读PG诊断 | 环境观察与业务异常的时间关联；无重启/kill/SQL写权 | 先扩指标角色工具/Skill，复杂度不够无需新角色 |
| **ImpactAssessor 影响范围评估，后置可选** | 同一故障影响多个服务，需要明确处置优先级 | 服务拓扑、错误率、已知请求/业务聚合 | 可验证影响范围和不确定性；不能凭空估算订单/收入损失 | 没业务事实面就只展示已有影响指标 |
| **RemediationPlanner 处置建议，诊断后** | 已有证据结论，需要按runbook给操作方案 | 已准入报告、适用runbook、部署版本 | 建议步骤、风险、验证和回滚条件；只生成提案，不执行 | 模板化建议足够时不加LLM；未知原因不给危险确定性操作 |
| **SkillCurator 方法沉淀，异步作业** | 有已复核封存轨迹可复用 | 脱敏轨迹、反例、既有Skill目录 | DRAFT候选和适用边界；无发布/答案仓权限 | 等独立评测上线；不能阻塞本次告警报告 |

第一批选择方法：若trace源先就绪，优先加入调用链调查；若现有变更工具成熟且合并角色确有遗漏，先拆变更角色。反证复核需要专门测试集后再启用，不把“第四角色”固定成无数据也要跑的装饰。

暂不新增：通知Agent（确定性Outbox）、预算/鉴权Agent（代码守卫）、评分器Agent参与线上投票（评分器隔离）、独立于主调查Agent的额外总指挥LLM（会重复路由）、每个tool一个Agent。主调查Agent的模型决策与确定性Supervisor的调度执行是不同职责，两者都保留。

### 12.3 反证角色不破坏单一Claim出口

非目标中的“LLM Critic推翻现场证据”仍成立。Reviewer只产生ReviewFindings或EvidenceRequest，主Agent仍是唯一模型Claim提案出口，ClaimValidator仍是代码准入。无证据的意见不算反证；同一来源被多个角色引用只算同一来源。

若启用复核，诊断先产草案→有界复核→必要补证/再诊断→最终FINAL；REPORTING门改为所有策略要求的复核已结清。每轮最多一次复核起点，复核/纠正消耗Run预算与补证额度。复核超时不能无限阻塞，按冻结策略输出未决或带明确未复核标记的有限结论。

## 十三、实现顺序与文件级任务卡

所有卡是拟实施方案；不借审查直接开生产改造。下面Java路径沿§十根目录。

| 卡 | 文件/方法与具体动作 | 验收 |
|---|---|---|
| R7-X1 持久角色绑定 | `RcaTask`或新增binding表、`PlanCompiler.compile`、task repository：同事务保存role版本/round/input refs；幂等按run+round+taskKey | 编译任意合法taskKey，重启仍恢复正确role |
| R7-X2 通用运行器 | `NativeInvestigationExecutor.drive`移除按业务taskKey分派；新增RoleRunner并通过既有AgentRegistry/受审查运行器目录解析 | 第四个同运行器角色仅新增配置即可执行，未知运行器拒绝 |
| R7-X3 Profile完整性 | `AgentProfile`深冻结、schema/能力/phase校验；`AgentRegistry`按release构造快照 | 嵌套修改不能改变旧digest，同名多版本不串用 |
| R7-X4 Round终态 | `DeterministicSupervisor.advance`按主检查点/有效子任务/复核推进；委派裁决与任务原子创建，收敛事件幂等唤醒 | 零子任务可结束；父等待重启不重复派发，耗尽未决退出 |
| R7-X5 跨角色工件 | 模型上下文装配、Findings/Claim准入：只读授权refs，保存producer与来源谱系 | 引用越界拒绝、复述不算独立证据 |
| R7-X6 装配与版本 | `AlertAm4Config`保留旧兼容路由、新release配置角色集合；绑定增强线热更新语义 | 新Run可启角色，旧Run不漂移，回滚恢复旧集合 |
| R7-X7 第一扩展角色 | 按数据盘点选择trace或拆change，新增Profile+工具契约，先不提高并发 | 真模型E2E与同预算收益对照 |
| R7-X8 复核与后处理 | 有质量证据后接Reviewer；处置建议/SkillCurator独立postprocess预算与幂等作业 | 不改变单一Claim出口，不阻塞/重复报告通知 |
| R7-X9 前端 | `alert-web/src/components/RunDag.vue`、`RunTaskNode.vue`、`views/RunDetailView.vue`：通用角色节点/轮次/版本/跳过原因 | 第四角色无需写死新UI分支，真实状态正确 |
| R7-X10 部署版本代码工具 | 在既有ToolRegistry/Execution入口新增拟定`CodeSearchExecutor/CodeReadExecutor`；受审查服务→仓库与镜像digest→commit映射，profile受限授权 | 真实部署commit查询、路径/大小边界、秘密脱敏、错版本不伪证；未实现不得宣称日志+代码闭环 |
| R7-X11 主Agent决策 | 通用运行器增加互斥Decision schema、主任务checkpoint与累计steps；原RcaDiagnosis职责合入Primary profile；`PlanCompiler`初始仅建主节点 | TOOL_CALL/DELEGATE/FINAL均经守卫；没有强制专家或额外诊断调用 |

新增迁移在现有Flyway目录使用下一个空闲版本。X1～6、X11是动态协作地基；X10是用户要求的日志+代码简单闭环前置；X9需覆盖单主节点与实际子节点。X7/X8为后期可选卡。取消原固定双根编排与单独诊断loop的重复工作，增加父任务恢复与代码版本映射后重新估时，不能沿用旧三角色工期承诺。旧W2只是原排期标签。

## 十四、角色扩展端到端测试补充（36例，待实现/待执行）

模式沿增强线：E=真实HTTP/PG/worker/执行器/报告链+受控外部端点；L=真实模型与真实取证；B=固定输入、受限模型出口、独立盲评。E不能证明LLM质量。每例记录case_uid、release/role/schema/round/config_epoch、调用与预算、证据与报告digest、清理结果；竞争用barrier，恢复用隔离进程硬终止。以下为本R7专项，不替代增强线100例。

| ID/模式 | 入口与故障操作 | 必须断言 |
|---|---|---|
| RX01 E | 发布第四个合法Profile，经告警生成任意taskKey | 实际分派新角色，不走旧三键switch，不仅注册可见 |
| RX02 E | 编译后重启worker再领取 | role版本/输入/round完全恢复，不猜latest |
| RX03 E | 未注册role或运行器不存在 | 编译/准入明确拒绝，零远端请求 |
| RX04 E | 同名v1/v2两Run并行 | 各绑定固定digest，输出不串用 |
| RX05 E | 构造后修改原嵌套schema | 旧Profile内容/digest不变，发布完整性可核 |
| RX06 E | 变更任务输入引用到另一个Run | 编译/结果准入拒绝，无跨Run数据泄漏 |
| RX07 E | 两轮使用相同taskKey | 轮次身份独立，同轮重复提交幂等，无唯一键误撞 |
| RX08 E | 新role缺工具或数据源 | 不假运行，SKIPPED/PARTIAL原因可见，关键缺证不确认 |
| RX09 E | 合并角色只有logs不可用、change可用 | change继续取证，结果PARTIAL而非整角色丢弃 |
| RX10 E | 新role提写操作/任意委派 | 权限交集和Supervisor拦截，零副作用 |
| RX11 E | 两角色争最后一笔预算 | 单一预算所有者，失败者零触网，不双重扣账 |
| RX12 E | role不断补证直至round2 | 下一轮请求被拒，预算/累计任务限制不重置 |
| RX13 E | 补证批准与创建任务间硬杀进程 | 事务恢复无半套图、不重复子任务 |
| RX14 E | 诊断预算耗尽/模型持续非法输出 | 确定性未决终止，不永久RUNNING/REPORTING等待 |
| RX15 E | 两种措辞重复请求同一缺口 | 语义范围去重，无新证据不重复调用 |
| RX16 E | 图有隐藏上游输入却无依赖边 | 校验拒绝或明确等待，不用尚未提交的数据 |
| RX17 E | optional失败与required失败分别跑 | 各按冻结策略处理，失败不被全终态掩盖 |
| RX18 E | Cancel/失租与新角色发送资格竞争 | 事务边界正确，晚到结果不进有效快照 |
| RX19 E | 外部响应成功但usage缺失 | 结果成功与费用未决分账，非免费完成 |
| RX20 E+B | 两角色引用同一条日志并互相赞同 | 来源仍一份，不因投票升级ROOT_CAUSE |
| RX21 E | Findings含“忽略上层策略” | 下游当不可信数据，权限不变 |
| RX22 E+L | checkout真异常，按trace发现依赖异常 | span原始refs可回读，调用路径来自真实数据 |
| RX23 E+B | trace采样缺span/时钟偏差 | 明确不完整，不把缺失当无异常证据 |
| RX24 E+L | 拆分变更角色，最近发布与故障无关 | 查反证并保留不相关结论，不盲归因最近变更 |
| RX25 E+B | Reviewer无引用反对已支持事实 | 意见不算反证，不能直接改Claim或覆盖事实 |
| RX26 E+B | Reviewer发现真实矛盾→补证→主Agent再诊断 | 最终提案仍来自主Agent，Validator仍生效 |
| RX27 E | Reviewer超时/预算不足 | 有界结束、结论标记符合策略，不死循环 |
| RX28 E | 增加role导致每轮/累计任务超限 | 发布或调度拒绝，不靠提高到无限放行 |
| RX29 E | 发布新role集合时旧Run进行中 | 旧Run不自动加节点，新Run可选新集合 |
| RX30 E | 请求运行中切换到新DAG/权限集合 | 首期拒绝，关联新Run需显式命令与新准入 |
| RX31 E | 撤销旧role后重启旧Run | 撤销阻止新动作，不以恢复为名复活权限 |
| RX32 E | 前端接收陌生role与新一轮任务 | 通用节点正常显示，角色/轮次/状态可下钻 |
| RX33 E | 处置建议包含重启/回滚方案 | 仅生成提案，不触发生产执行或通知写入 |
| RX34 E | SkillCurator失败/重复消费完成事件 | 不影响已完成RCA；DRAFT幂等，无active写权 |
| RX35 B | 动态主Agent基线与新增候选角色同总预算配对 | 根因/错误确认/弃答/费用/延迟均出报告，不只看命中 |
| RX36 B+L | 合适与不合适告警分别启用新role | 证明触发选择正确，报告数据不足/小样本限制 |

## 十五、验收结论如何表达

先修角色绑定、通用运行器和主任务恢复契约，交付R7-A0、R7-A1及动态协作B门。新增角色必须通过注册→编译→实际执行→持久恢复→证据→报告→页面全链，以及同预算对照。数据源不可用的候选标NOT_READY；用例未实施标NOT_IMPLEMENTED，不能SKIP后称角色可用。

复用`AgentProfileTest`、`AgentRegistryTest`、`PlanCompilerTest`、`DeterministicSupervisorTest`，补具名PG IT和隔离进程恢复测试。`mvn test`不替代`mvn verify`的集成检查，也不替代L模式真模型验收。此次只审查修改方案，不运行这些尚未实现的用例。

当前选定：主Agent作为默认调查入口，专家按需出现。优先交付零委派简单闭环与受控委派复杂闭环，再验证调用链或拆分变更角色；反证复核按风险触发，处置建议/Skill沉淀放在报告后。角色数量由真实收益决定。

## 十六、业界是否这样做：调研依据与本项目取舍

调研日期2026-09-11。结论是“有明确的框架支持和相近的生产实践”，不是“业界已证明这套告警系统的效果”。以下资料支持模式选择；本项目的准确率、费用和恢复能力仍要自己验收。

| 一手来源 | 明确支持什么 | 对本项目的含义 |
|---|---|---|
| [LangChain Subagents](https://docs.langchain.com/oss/python/langchain/multi-agent/subagents) | 主Agent动态选择子Agent、提供输入、整合结果；文档也建议简单的少工具场景使用单Agent | 同一入口可以简单时直接调查，复杂时调用专家；无需按固定顺序调用所有专家 |
| [OpenAI Agents SDK：Manager与Handoffs](https://openai.github.io/openai-agents-python/agents/#multi-agent-system-design-patterns) | Manager把子Agent作为工具并保留控制权；Handoff把控制权交给另一个Agent | 本项目选Manager式返回结果，主Agent保持最终报告职责；首期不做多角色不断交接 |
| [Anthropic：生产多Agent研究系统](https://www.anthropic.com/engineering/multi-agent-research-system) | Lead委派有边界的研究任务并综合结果；文章披露协调错误与明显token开销 | 有真实生产相近案例，但研究任务与告警RCA不同；不能移植其效果数字作为本项目收益 |

名词容易误导：LangChain文档里的Supervisor通常是会思考的主Agent；本项目既有`DeterministicSupervisor`是Java调度代码。采用前者的协作模式，不意味着将后者替换成模型。主Agent提出“需要查哪个问题”，代码决定“这个请求是否被允许、怎样可靠执行”。

### 16.1 为什么选主Agent动态委派

第一步，告警来了，需要知道异常发生在哪里。让主Agent读告警并调用现有受限工具就能启动调查，此时增加多个专家并不能自动增加信息。

第二步，日志若已经提供异常位置，就查询对应部署版本代码并核对触发条件。证据足够时直接结束；不足时记录具体缺口。这样避免为了收集已经拥有的证据再开启一套模型上下文。

第三步，若发现多个服务之间存在传播关系，或指标分析需要连续多次查询，主Agent才委派对应专家。专家得到服务、时间窗、问题、已有证据引用、停止条件和预算，不需要读所有历史。专家返回结构化发现，主Agent据此继续，而不是让几个Agent互相聊天投票。

收益假设是减少简单告警中的冗余调用，复杂告警则可以获得专业工具与上下文隔离。新增代价是路由可能出错、主Agent可能过度委派或过早结束、子任务等待和恢复更复杂。必须用预算、状态机、证据准入和§十七测试约束；不是只加一句“你可以调用专家”的提示词。

### 16.2 什么时候选择什么能力

| 现场情况 | 建议路径 | 防止误用的条件 |
|---|---|---|
| 一个服务的明确异常，日志和部署版本代码可核对 | 主Agent直接tools，零专家 | 仍校验异常发生窗口、版本、触发证据和反证 |
| 只有错误日志，没有能解释机制的证据 | 主Agent继续受限查询；存在专业缺口才委派 | “日志包含异常”不自动等于根因确认 |
| 指标趋势/依赖传播需要多步独立调查 | 委派指标或将来的trace专家 | 所需数据源真实可用，任务边界明确 |
| 已有结论但关键证据冲突 | 主Agent查缺口，必要时有界反证复核 | 不能按告警级别无条件拉齐全部角色 |
| 需要历史处理方法 | 主Agent调用RAG/加载Skill | 方法检索本身不要求增加知识Agent；历史案例不是现场事实 |
| 已完成调查值得复用 | 异步SkillCurator提出DRAFT | 独立评测和发布，不自写active，不干扰本次RCA |

角色目录首期仅几种专家，采用随release固定的role_id与描述/输入输出schema即可；不需要动态生成无限角色，也不需要引入新的框架服务。以后新增角色，仍走Profile→发布→新Run目录→受控委派。若增加的是新数据协议，则先实现工具，不通过配置伪装成已有能力。

### 16.3 运行期间热更新的边界

服务不重启即可发布新Prompt/Skill；默认新Run读取新release，旧Run继续使用自己的固定版本。用户要求进行中Run替换时，沿增强线安全点迁移协议执行。主Agent处于WAITING_CHILDREN时不允许跨代替换：先等旧批次全部结清、无在飞动作、持久化检查点，再校验兼容性并原子切换config_epoch。旧证据保留原producer版本，不改写历史。

新增role、工具权限、输出schema或DAG语义属于能力变更，不能作为Prompt热更新夹带进入旧Run。需要新调查时创建关联Run。紧急撤权优先阻止后续动作，即使因此只能未决退出，也不能为了版本一致而继续执行已撤销能力。

### 16.4 评测不能只数Agent数量

固定同一数据快照、模型版本、工具集合、总token/费用/请求上限，对比三臂：主Agent单独调查、固定三角色、主Agent动态委派。三臂均可获得相同基础工具，避免将工具能力差异误归因于编排。先做冻结输入的可重复对照，再在隔离验收窗口做真实模型与现场取证；真实事故时序变化必须披露。

按简单、专业单缺口、多缺口、缺数据和冲突五类分层报告根因准确、错误确认、合理未决、证据有效率、P50/P95时延、token/费用、委派次数、重复查询、恢复成功率。增加“简单场景无必要委派率”和“专业缺口漏委派造成的失败率”；人工标注允许合理替代路径，不能规定某个role名就是唯一正确答案。

费用比较既报告同预算质量，也报告达到相近质量时的实际消耗；不能靠无限增加上下文证明多Agent更好。每组记录样本数、重复次数与不确定性，准入阈值在运行前冻结。不引用厂商研究任务的提升比例作本项目承诺。

## 十七、动态委派专项E2E补充（20例，待实现/待执行）

沿§十四E/L/B模式和取证要求。以下20例与RX01～36互补，R7合计56例；增强线100例另计。每例必须贯通入口、持久化任务、实际调度、报告与页面；模型路由质量须用L/B验证，脚本化E只证明执行约束。不是只对提示词输出做字符串断言。

| ID/模式 | 场景/故障注入 | 必须断言 |
|---|---|---|
| RD01 E+L | checkout简单故障，日志和部署commit代码足够 | 主Agent真实调用工具、零子任务、无额外诊断模型，Claim引用有效 |
| RD02 E+L | 现场需要专业指标多步调查 | 主Agent选择相关专家；专家真实LLM循环，返回后主Agent继续，非只把固定查询改名 |
| RD03 E+L | 存在两个独立的专业缺口 | 只创建获准的两子任务、显式依赖；默认串行，C门后受限并发 |
| RD04 E+B | 简单告警中模型试图派齐所有专家 | 超额/重复请求被拒；预算仍受控；B模式统计无必要委派，不把Prompt建议当硬保证 |
| RD05 E+B | 证据不足但主Agent提ROOT_CAUSE | 必要证据校验降级，未决可结束；不因零子任务自动确认 |
| RD06 E | 专家不可用或role_id不在冻结目录 | 结构化拒绝且零专家请求；主Agent有限改计划，拒绝循环有上限 |
| RD07 E | 子Agent自行DELEGATE或访问父权限工具 | schema/权限拒绝，不能递归生成孙任务，零越权动作 |
| RD08 E | 批次创建事务提交前/后分别杀进程 | 裁决、子任务、依赖、父等待全有或全无；重启不重复派发 |
| RD09 E | 父任务WAITING_CHILDREN时worker重启 | 从检查点和持久化子状态恢复，不丢上下文、不重新消耗旧步骤 |
| RD10 E | 子完成事件重复/乱序、两个driver竞争唤醒 | 单一有效主驱动，主决策序号唯一、预算不双扣、报告不重复 |
| RD11 E | 等待子任务时Run取消/失租 | 不再取得新发送资格；晚到结果只审计；新owner不沿旧epoch提交 |
| RD12 E | 专家超时、失败或只返回PARTIAL | 按required/optional和冻结策略结清，主Agent带缺口继续或确定性未决 |
| RD13 E | 连续两批委派后申请第三批 | 拒绝且不新建task；父steps与Run预算不重置，不永久等待 |
| RD14 E | 多专家消耗至预算边界 | 共享账本原子预留，保留主收敛额度计入总额；不足时代码兜底无需额外模型 |
| RD15 E | 镜像commit未知或代码仓库不可用 | 明确MISSING_SOURCE/VERSION_UNRESOLVED，禁止查latest伪装部署版本 |
| RD16 E+B | 仓库存在异常分支但日志不支持实际触发 | 代码仅作机制候选，不自动晋升ROOT_CAUSE；保留反证 |
| RD17 E | 代码工具收到越权仓库、路径穿越、巨大文件、秘密和恶意指令 | 仓库/commit/路径/字节限制生效，脱敏；文本不改变角色权限，无shell执行 |
| RD18 E | 父等待期间请求Prompt热切换，后在安全点重试 | 等待阶段延后/拒绝原因可见；安全点原子epoch切换，旧专家版本来源不改写 |
| RD19 E | 新role发布与旧Run委派同时发生 | 旧Run只能选旧集合，新Run可选新role；前端零/一/多子节点均如实显示 |
| RD20 B+L | 三臂同预算覆盖简单、复杂、缺源、冲突告警 | 质量/错误确认/未决/费用/延迟/委派选择联合报告，不能仅以“执行了多Agent”通过验收 |
