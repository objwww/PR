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

沿§十四E/L/B模式和取证要求。以下20例与RX01～36互补，本阶段累计56例；加入§二十一MC01～36后，R7共92例规格，增强线100例另计。每例必须贯通入口、持久化任务、实际调度、报告与页面；模型路由质量须用L/B验证，脚本化E只证明执行约束。不是只对提示词输出做字符串断言。

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

## 十八、多Agent完整性审查：统一能力边界（2026-09-11增补）

本次将上下文压缩和相关能力一次纳入统一规格，避免每个新问题各造一套机制。完整性是覆盖需求、实现落点、失败策略和验收，不是保证以后不会发现缺陷。以下是当前单靶场、个人展示规模但追求生产工程质量的能力基线；不以百万告警、无限递归或跨公司Agent互联作为目标。

### 18.1 一手调研与采用边界

| 资料 | 借鉴点 | 本项目取舍 |
|---|---|---|
| [Anthropic上下文工程](https://www.anthropic.com/engineering/effective-context-engineering-for-ai-agents) | 按需加载、压缩、结构化记录和子Agent隔离；压缩会丢信息 | 先保证观察真实进入上下文，再做分层压缩，原始证据不改写 |
| [LangGraph持久化](https://docs.langchain.com/oss/python/langgraph/persistence) | 检查点、历史状态与恢复 | 复用PG检查点，不引入第二套编排框架；持久化不自动保证外部副作用恰好一次 |
| [LangGraph中断](https://docs.langchain.com/oss/python/langgraph/interrupts) | 人工中断与恢复需要持久化，恢复过程中重执行需注意幂等 | 人工介入用具名命令与CAS，不能通过任意聊天文本改状态 |
| [Anthropic Agent评测](https://www.anthropic.com/engineering/demystifying-evals-for-ai-agents) | 评测任务、轨迹和结果，多种评分器与环境可靠性 | 不强制唯一工具路线；恢复结果、错误确认和评测环境失败分别记录 |
| [LangChain子Agent模式](https://docs.langchain.com/oss/python/langchain/multi-agent/subagents) | 主Agent控制、子任务上下文隔离 | 延续本方案按需委派，不改变首期深度1和有界批次 |

这些资料证明模式有依据，不代表本项目接线或效果已达标。下列规格为项目设计判断，数值初值须评测，不能把厂商示例直接当容量保证。

### 18.2 当前实现的新增证据，覆盖旧盘点时间点

本轮本地已存在`RcaModelCallContext`、`RcaModelGateway`、`BoundedLlmRoleRunner`、`PrimaryCheckpoint`及Repository。§十保留为较早审查快照，不再据此宣称这些能力完全未实现。本轮是针对性增量阅读，不是完整生产部署验收。

| 核对位置 | 读到的事实 | 下一步须核对/补齐 |
|---|---|---|
| `BoundedLlmRoleRunner.promptOf` | 组装role、窗口、剩余步数、工具schema、valid_artifact_refs和last_error | 此方法未组装每条证据内容摘要、累计假设和反证；追踪整条调用路径确认是否另有注入，增加真实模型输入捕获断言。仅有UUID不等于模型读到观察 |
| 同类`validRefsOf` | 收集binding输入及本Run所有证据ID | 同Run不等于同角色全部授权；检查角色范围与冻结快照，不能使用持续变化的全集作为模型输入 |
| `PrimaryCheckpoint` | 有阶段、计数、快照digest、最终提案与lastError | 扩关联工作记忆/压缩记录；不把finalClaims当整个调查记忆；map深不可变需要验证 |
| `PrimaryCheckpointRepository` | upsert契约注释以单写者解释安全，另有phase CAS | 检查实现是否同事务验证owner/epoch/revision；旧worker失租后迟到写不能靠“通常单写”防住 |
| `RcaModelGateway.call` | 本地已有RCA独立账本入口，open physical_seq=1后调用底层gateway | 核对物理重试是否逐次记录和预算覆盖、供应商回执是否补齐；不能仅凭逻辑动作一行声称每次物理请求可对账 |

若这些位置已被其他工作修复，执行卡以最新源码和E2E证据关闭，不重复改造。BA-114/115/116/120也按真实代码追踪，不凭聊天中的候选方案当已裁定实现。

### 18.3 能力总表：已有约定、需补规格、后期条件

P0=首期正确性或上线收口必需，P1=有收益证据后增强，P2=当前不做。这里的P0不是绕过既有EX/C门的编码许可。

| 能力 | 优先级 | 现有覆盖与本次补齐 |
|---|---|---|
| 01 主Agent直查/动态委派 | P0 | §三/四；简单零委派，拒绝和无进展反馈有界 |
| 02 角色与工具发现 | P0 | §十一；固定授权目录，新增配置不等于运行器已部署 |
| 03 任务拆分与验收条件 | P0 | 子任务必须有问题、范围、产物和停止条件，不能只传“去查日志” |
| 04 消息/工件契约 | P0 | 版本化信封、因果ID、重复/乱序/迟到准入，见§二十 |
| 05 观察回灌 | P0 | 真实内容摘要+refs入模，不只给ID；读回断言 |
| 06 上下文窗口管理 | P0 | 输入预算、工具结果预处理、token估算，见§十九 |
| 07 长期摘要压缩 | P0规格/P1模型摘要 | 先确定性选择和检查点；只有轨迹确实过长才启用LLM压缩 |
| 08 工作记忆与来源 | P0 | 事实/假设/反证/缺口分离，记忆不是新证据 |
| 09 主子上下文隔离 | P0 | 最小TaskEnvelope，结果归并不广播messages |
| 10 证据时间/部署版本一致 | P0 | 绑定incident generation、窗口和部署commit，旧数据不装新事实 |
| 11 冲突与不确定性 | P0 | 反证保留，信息不足未决，重复来源不投票 |
| 12 持久化与失租恢复 | P0 | 检查点CAS、外部UNKNOWN对账、恢复不重置额度 |
| 13 取消和孤儿任务清扫 | P0 | 向子任务传播截止/取消；父死后子任务有归属和停止规则 |
| 14 并发/背压/公平性 | P0 | Run预算外另有worker/供应商/工具池容量；恢复动作不能饿死 |
| 15 模型能力适配 | P0 | 输出schema、推理/输出额度、上下文、错误分类和能力探针 |
| 16 重试/降级/熔断 | P0 | 瞬时与确定性错误分开；统一重试次数，角色降级可见 |
| 17 工具/MCP执行边界 | P0 | 参数、返回、外联、权限、数据隔离与撤权，增强线共用 |
| 18 人工介入 | P0 | 取消、补充受控观察、要求重查和未决交接；权限写操作仍不自动执行 |
| 19 报告/通知终态 | P0 | 只有主Agent提案出口，报告固定版本，Outbox去重；闭环不等于根因确认 |
| 20 全链路观测 | P0 | phase/lastProgress/inFlight/预算/摘要版本/子任务等待原因可查 |
| 21 回放与复现 | P0 | 事件/输入/版本可追溯；回放禁止真实外部副作用，无法固定的来源标明 |
| 22 评测与故障演练 | P0 | 质量、协议、竞争、恢复、GT隔离及真实场景，沿EU/RD/DU矩阵 |
| 23 Prompt/Skill/策略版本 | P0 | 冻结manifest、定向回归、灰度、撤权与回滚，不只保存一个version字符串 |
| 24 MCP/RAG版本演进 | P0规格 | schema/语料/授权快照和真实收益评测，不能借热更新扩权 |
| 25 Skill自沉淀 | P1 | 封存已复核轨迹→DRAFT→反例测试→发布，禁止自写active |
| 26 共享历史记忆 | P1 | 经审查摘要按服务/版本/时间检索，不共享本次可变工作内存 |
| 27 缓存与重复取证复用 | P1 | 仅同授权/工具版本/参数/窗口/数据新鲜度复用；不缓存未知失败为成功 |
| 28 数据保留和删除 | P0 | 活跃Run引用资产保护，过期/撤销可见，不让摘要引用静默悬空 |
| 29 身份和敏感数据保护 | P0 | 人/角色/worker/供应商身份分开，凭证不进Prompt、摘要、页面和导出 |
| 30 发布/运行能力准入 | P0 | 真实模型与工具预检；缺能力不可READY；单机失联不能自证恢复 |
| 31 无限递归/A2A/群体投票 | P2 | 当前不引入；只有多团队独立服务边界时再评估跨系统Agent协议 |
| 32 自主生产修复 | P2 | 当前只生成建议；未来另建权限、审批、幂等和补偿契约，不与只读调查混开 |

## 十九、上下文与压缩：可直接实施的契约

### 19.1 先解决“看到了什么”，再解决“放不下”

每个模型动作只读取一个冻结ContextSnapshot。组成：宿主策略与角色/输出schema、告警目标与当前generation、当前步骤任务、授权工具目录、最近有效观察、工作记忆、证据索引、剩余预算和停止条件。模型需要知道证据讲了什么，不能只获得允许引用的UUID；有效引用目录与实际可读取内容必须一致。

按需读取接口返回原始证据的有界片段，带source、服务/时间窗、部署版本、digest、截断和脱敏标记。代码与日志先以确定性规则筛选、去重、聚合；重复日志保留次数与首次/末次时间，不能把十次发生聚成一次而丢频率。大结果先存储再返回摘要和分页引用，不把全文件塞进Prompt。

模型输入构造顺序稳定，有助于复现和可用缓存；但缓存命中不是正确性条件。工具调用与结果如果采用messages协议必须成对保留，不能删掉tool_call却留下对应tool_result；当前字符串信封适配也要保存对应动作身份。证据、工具输出、子Agent解释都是数据，不得进入宿主指令层。

### 19.2 三层存储，不另建一套聊天数据库

| 层 | 保存内容 | 谁能改 |
|---|---|---|
| 不可变事实/执行记录 | 原始观察、动作/费用、Findings与来源、冻结输入索引 | 宿主准入后追加；原文不被摘要覆盖 |
| 工作记忆快照 | facts/hypotheses/counterEvidence/openGaps/completedActions/nextQuestions | 模型可提更新，宿主校验身份、refs和版本后条件提交 |
| 本次模型输入 | 选择后的内容、当前约束、记忆摘要、证据索引 | 宿主按固定策略生成并记录digest |

现有PrimaryCheckpoint关联current_memory_id/context_snapshot_id即可；不在检查点复制所有历史。facts也是“带支持引用的判断”，与原始Observation保持类别区分；已推翻假设标REJECTED并保留理由，不能直接删除造成下轮重新猜。预算/租约/权限始终从权威状态读取，不能相信模型摘要写的“还剩很多预算”。

### 19.3 输入额度与触发条件

按模型能力配置C=contextWindow，R=本次输出预留，H=不可裁宿主/schema/任务开销，M=token估算与供应商封装安全余量；可变材料上限V=C-R-H-M。若V≤0立即能力配置失败，不触网。C/R语义以供应商适配确认，不能拿字符数当精确token。

初始软阈值设已用可变token达到V的70%，裁剪目标降到50%～60%；这是待评测参数，不是行业标准。每次工具结果入库后、下一模型发送前重新估算；接近硬限仍超出则强制重新选材或未决结束，不能先发再无限重试context_length错误。tokenizer不可用时保守估计并记录估算方法/误差；输入和输出预算独立控制。

压缩顺序：去重复与无关大字段→将旧工具正文替换为有界摘要+refs→从源记录重建结构化工作记忆→必要时调用专用摘要Prompt。优先保留当前范围/部署版本、关键支持与反证、未决缺口、已失败/已做动作、待子任务与最近有效结果。不得为了达到压缩比移除停止条件和反证。

### 19.4 LLM压缩的事务与失败处理

摘要动作也是受ActionGuard控制的模型调用，purpose=COMPACTION，拥有独立逻辑actionId/physicalSeq并计入Run总模型请求/token/费用；压缩次数另设上限，例如首期2次。它不增加委派round，也不重置主Agent steps。不能复用主决策序碰撞原账本唯一键，具体迁移须统一动作身份契约。

流程：冻结sourceSnapshot及覆盖eventSeq范围→预留额度→生成候选摘要→结构/引用/覆盖校验→在同事务验证owner、leaseEpoch、configEpoch、revision及sourceSnapshot仍有效→写不可变摘要并CAS更新当前指针。允许子任务产生新事件，但当前摘要仅覆盖冻结范围，后续增量必须单独拼入；禁止混入一半新证据却沿用旧digest。

保存summaryId、schemaVersion、sourceSnapshotDigest、覆盖区间、summaryPromptDigest、model、token估算前后值、requiredRefs、omittedRefs、validationResult、producer、configEpoch。requiredRefs由宿主任务及已知反证策略生成，不能由摘要模型自己决定全部删空。代码可检验引用存在和必需字段覆盖，语义是否失真仍需B/L对照，不以schema通过证明总结准确。

摘要失败、无明显节省或缺关键引用时丢弃候选，保留原快照，采取确定性选材；若仍无法在预算内继续则输出带缺口的未决结果。禁止“摘要→摘要→摘要”无限递归；达到累计压缩上限时停止或使用已有受控记忆。每次重建尽量回到原始工件，避免长期只总结上一版摘要造成漂移。

### 19.5 主子隔离、热更新与保留

每个子任务的上下文只含授权范围、具体问题、已有发现/反证引用和停止条件，不复制父messages；子任务结果必须有status、findings、supportRefs、counterRefs、missingInformation。主Agent只合入已准入摘要及引用，不把子Agent全文上升为系统指令。子任务可使用同一上下文选择器，各自摘要受同一Run预算，不各赠额度。

压缩策略/摘要Prompt也作为release资产钉版并参加回归。兼容热切发生在无在飞相关动作且检查点提交的安全点；若context schema/角色授权改变，首期关联新Run，不强改旧记忆。重启加载已提交的同一摘要，不无故重新调用模型生成另一个记忆。主动撤权优先，读取旧摘要也必须重新核对其数据权限。

原始证据和旧摘要按既有RetentionService策略保留；活跃Run、未完评测和待审计引用有明确保护期限。删除后保留最小tombstone说明不可回读，相关重放标不完整，不能继续宣称完全可复现。敏感信息撤除传播到派生摘要/缓存，保留必要审计而不保留被撤除正文。

### 19.6 与BA-120的区别

上下文压缩主要解决输入超长/注意力污染，不解决推理输出烧光max_tokens。模型能力manifest需分别声明输入窗、最大输出、推理参数是否支持、tool/schema支持与价格状态。空content要结合finishReason/截断标记/提供商响应分类为输出额度耗尽、拒答、协议不兼容或未知，不能一律PROTOCOL_ERROR并盲重试。提高单步额度必须受Run上限，降级模型也必须已授权且满足输出契约。

## 二十、其余能力的收口契约

### 20.1 委派信封和结果合并

TaskEnvelope至少带taskId/runId/parentRequestId/messageId/causationId、role/version、inputSnapshotDigest、目标服务/时间/版本、问题、requiredOutput、授权refs、deadline和预算上限。消息最大字节/字段数量均设界限；可信身份由Host赋予，不允许模型冒充其他role或修改parentId。

结果按messageId/动作身份幂等准入。旧epoch/已取消/超出当前任务范围的结果仅审计，不合入有效记忆；任务失败也必须返回结构化缺口而不是空字符串。总Run费用按真实物理调用记账，子结果内自报token不能直接覆盖账本。

两个专家结论相反时保留各自证据来源和时间，主Agent查具体冲突；不能以“二比一”确认根因。同一原文被多次摘要仍是一份来源。新的调查方法可以来自Skill，但当前证据窗口和服务范围不能被Skill默认值偷偷替换。

### 20.2 新告警/恢复事件在调查期间到达

当前Run固定触发时的incident generation和观察窗口，新增恢复或再触发事件由Host记录。恢复不自动证明根因正确，是否继续调查按冻结策略；重新触发形成新episode时关联新Run，不将旧证据伪装成新一轮。决定扩窗时创建新的具名快照并计入查询/时间预算，保留原窗，禁止每轮任意读最新现场而无法复盘。

重复查询去重键包括tool/schema版本、参数规范化、服务/租户范围、时间窗和快照/新鲜度。相同查询但现场已经变化可能有必要重查；“换个措辞”但数据范围未变不代表新增信息。先依赖确定性键和预算/无进展保护，不把语义相似度当唯一正确性闸门。

### 20.3 多层容量、停止和降级

Run预算之外明确worker槽位、每模型并发/RPM/TPM、每工具并发、每角色deadline和队列等待上限。请求只有取得所有必需资源后才能发出；等待配额不能一直持有会饿死其他任务的资源。任务/批次和Run deadline取最小，父任务已终止后孤儿子任务有清扫记录。

全局启动/停止是控制命令，恢复和费用对账保留容量，不排在耗时模型队列后面。首期无需复杂调度算法，有限并发+按创建时间的有界公平队列即可；新实验不能无限抢占值班查询资源。依赖不可用时展示SOURCE_UNAVAILABLE/CAPABILITY_UNAVAILABLE，取消无用任务，输出未决，不编造“已查无异常”。

重试只有一层总账：网关内部和driver外部不能各自重试N次造成N²。参数非法、鉴权失败、schema不支持和固定输出额度耗尽不做同参数盲重试。瞬时失败可有界退避，熔断按已存在ModelGateway能力复用并验证；切模型/工具需能力兼容和明确事件，不自动改变权限。UNKNOWN外部结果不承诺exactly-once，用稳定idempotencyKey、查询对账和保守结算解决。

### 20.4 人工介入不是任意聊天改状态

首期支持取消、查看缺口、提交具名补充观察、要求关联新调查。人工材料也有actor、来源、时间和可信等级，不能直接覆盖原始证据或把“我觉得是数据库”当确诊。修改运行范围、预算或版本通过独立CAS命令；等待人工有截止时间与默认未决策略，不无限占worker。

后续如果开放修复动作，单独增加动作级审批：批准绑定操作者、目标、参数digest、版本和有效期，参数或scope改变必须重新准入；本次规格不开放生产写工具。仿真聊天与真实操作域继续隔离。

### 20.5 观测、评测和回放

统一关联experiment/case/drill/incident/run/task/attempt/action/physicalRequest与摘要id、release/configEpoch。页面显示数据更新时间、当前阶段、等待原因、已知/待对账费用；不以完整Prompt或隐藏思维链充当可观测性。错误有reasonCode和可行动说明，消息回传保存命中规则/拒绝原因，避免模型重复撞同一错误。

新增监测：输入占窗比例、压缩次数和节省量、必需引用保留率、无信息增益步数、无必要委派、子任务失败/孤儿数、排队/执行/收敛时延、未知费用与协议错误率。指标标签避免以runId作为全量Prometheus高基数标签，详细身份保留事件/trace。

回放使用已固定工件和受控边界，不重新向生产发送工具动作；真实L模式另行标识。压缩质量比较“同输入不压缩/确定性选择/LLM压缩”三臂，报告关键事实/反证遗漏、错误确认、重复调用、合理未决、token/费用和延迟。长轨迹中放置早期反证与后期新证据，测试摘要不是只记最后一句。恢复实验要硬杀进程，不能只抛一个可捕获异常冒充宕机。

## 二十一、统一实施卡与验收（36例，待实施/待执行）

### 21.1 实施卡：复用已有落点，不另建平台

| 卡 | 具体修改位置（control-app Java根包下） | 验收门 |
|---|---|---|
| MA-01 真实上下文 | `alert/application/agent/BoundedLlmRoleRunner.promptOf`与RoleRunner输入，抽最小ContextAssembler；按task授权和冻结快照装实际观察 | MC01～04；B门前先证明模型读到证据 |
| MA-02 工作记忆 | `PrimaryCheckpoint`关联不可变记忆/上下文快照，复用EvidenceRepository；补最小表/字段与深冻结 | MC05～08；不另存完整聊天广播 |
| MA-03 输入限额 | RcaModelGateway发送前检查模型能力/token估算/工具结果界限 | MC09～12；无压缩也须有硬限和兜底 |
| MA-04 压缩生命周期 | ContextAssembler关联摘要记录，ActionGuard统一计费，主检查点repository条件提交，RetentionService引用保留 | MC13～20；LLM摘要按长轨迹收益后启用 |
| MA-05 消息与提交 | Delegation/RoleRunner结果解析及Supervisor；版本化信封、scope/epoch/revision/重复事件准入 | MC21～24；与RX/RD已有恢复卡合并 |
| MA-06 模型/工具可靠性 | 现有ModelGateway/RcaModelGateway、工具执行入口；模型能力、物理重试账、UNKNOWN对账 | MC25～28；衔接BA-115/120，不猜定价或输出参数 |
| MA-07 调度与介入 | Worker/Supervisor及命令服务；容量/截止、父取消、孤儿清扫、人为补充材料审计 | MC29～32；保留恢复/对账通路 |
| MA-08 页面与评测 | RunQueryService、RunDetailView、EV案例轨迹/资产页 | MC33～36；能看摘要与版本/卡点，GT隔离 |

先完成MA-01/02/03和MA-05/06/07的首期正确性，再在长轨迹评测证明必要时启用MA-04模型压缩。MA-08随读面同步接。工作卡与EX、R7-X、增强线EN、评测EV按修改方法去重估时；不把本次列表各加一次工期，也不称已有类存在就验收通过。

### 21.2 端到端矩阵

E/L/B沿§十四定义。本节描述新增验收契约，不代表相关基础能力未实现；R7恢复、准入、反馈环及增强线热更新等已有实现和测试，应先复用。逐例实现状态、存量回归入口、前置条件、操作步骤及断言见[存量回归及增量验收用例](告警-R7与增强线-存量回归及增量验收用例.md)。本轮新增完整场景均未执行，历史PASS保留原版本和场景范围。本节MC36例与原RX36/RD20合计R7目录92例，不等于92项均已实现；与增强线/EU/DU交叉覆盖只关联，不重复报通过数。

| ID/模式 | 测试动作 | 必须断言 |
|---|---|---|
| MC01 E+L | 日志中有可识别新观察，下一步模型调用 | 输入含实际有界观察与refs，不能只有UUID，模型可据此选下一动作 |
| MC02 E | 同Run不同角色权限不同 | 不能通过validRefs全集读取超scope证据 |
| MC03 E | 并发新增证据时组装输入 | 每次快照成员固定、digest可复核，新事件走下次输入 |
| MC04 E | 工具结果含注入指令/伪role身份 | 仍是数据，不改变Host策略或委派身份 |
| MC05 E+B | 早期反证、已推翻假设在后续多步 | 工作记忆保留，不重新确诊已排除方向 |
| MC06 E | 外部修改嵌套map或记忆候选 | 已提交快照/digest不漂移 |
| MC07 E | 检查点提交后硬杀worker | 原记忆/摘要/步骤恢复，不额外生成另一版摘要 |
| MC08 E | 旧owner迟到upsert | epoch/revision条件拒绝，不覆盖新记忆与FINAL |
| MC09 E | 工具超大结果/重复日志洪泛 | 截断与频次保留、输入不超限，原始有界证据可追溯 |
| MC10 E | 工具schema本身超过输入额度 | 发送前能力错误，零远端请求 |
| MC11 E | 中文/代码混合及tokenizer不可用 | 保守估算和余量生效，不将字符长度称精确token |
| MC12 E | messages工具调用/结果裁剪 | 配对合法，引用不悬空，协议序列可接受 |
| MC13 E+B | 摘要遗漏必需反证refs | 候选拒绝，旧快照不被替换 |
| MC14 E+B | 摘要refs正确但把假设写成事实 | 语义盲评失败，格式正确不能通过质量门 |
| MC15 E | 摘要超时/费用不足/没有节省 | 有界回退确定性选材或未决，不无限重压缩 |
| MC16 E | 压缩期间子任务完成/新证据到达 | 仅覆盖冻结区间，增量不丢，不混digest |
| MC17 E | 压缩结果与取消/失租/热切竞争 | 旧epoch不更新当前指针，费用仍审计 |
| MC18 E | 主/多个子任务同时压缩 | 共享预算原子约束，action身份不碰撞 |
| MC19 E | 达压缩次数上限再次申请 | 拒绝新增摘要调用，Run预算和steps不重置 |
| MC20 E | 源资料过期/撤权/删除 | 摘要关联可见缺失，权限重检，回放标不完整 |
| MC21 E | 子任务重复/乱序/超大回执 | 幂等且有界，不重复唤醒和合入 |
| MC22 B | 两专家同源复述、第三专家有反证 | 不按投票升级根因，来源去重和反证保留 |
| MC23 E | 调查中resolved后又新episode | 旧窗口/generation不冒充新现场，关联新Run |
| MC24 E | 相同查询换措辞/相同查询新现场 | 规范化去重与新鲜度区分，禁止无意义重复又不漏必要重查 |
| MC25 E | 模型reasoning耗尽而content空 | 按提供商终止信号分类；不反复同参数PROTOCOL_ERROR |
| MC26 E | 网关重试与driver重试同时开启 | 统一物理请求上限和账本，不能N²放大 |
| MC27 E | on/工具已生效但响应未知 | 稳定动作身份对账，不生成新ID盲重放 |
| MC28 E | fallback缺schema/定价未知 | 能力拒绝或明确降级；未知费用非0，不能伪称对账完整 |
| MC29 E | 工具/模型配额耗尽同时提交停止 | 等配额不饿死取消/恢复，不无限持worker槽 |
| MC30 E | 父任务终止但子任务仍在飞 | 孤儿清扫和截止生效，晚到结果不进入有效报告 |
| MC31 E | 人工补充材料含无引用的根因判断 | 标来源与可信度，不覆盖事实或直接确诊 |
| MC32 E | 人工等待超时或两人同revision修改 | 有界未决/CAS冲突，不无限挂起 |
| MC33 E | 页面看压缩前后和子任务卡点 | 可查摘要版本/覆盖范围/节省量/等待原因，不曝光秘密与隐藏思维链 |
| MC34 B+L | 长轨迹三臂压缩对照 | 事实/反证召回、错误确认、重复工具、费用/延迟联合报告 |
| MC35 E | 回放误接生产工具/评分GT可检索 | 回放外联禁用，调查Agent无法读答案仓 |
| MC36 E+B | 更新摘要Prompt/Skill/MCP组合并回滚 | 精确manifest资格失效与回归，旧Run固定版本，依赖缺失回滚拒绝 |

## 二十二、完成定义：以后按能力表收口

统一用NOT_IMPLEMENTED、IMPLEMENTED_NOT_VERIFIED、VERIFIED_LOCAL、VERIFIED_TARGET标能力证据阶段，并额外记录部署版本/验收日期，不把写了方案或单元测试通过称生产就绪。P0能力必须具备实现、异常路径和E2E证据；P1有明确收益门和进入条件；P2不占首期工期。

本次已补齐当前范围内32项能力的设计检查表、上下文详细契约、8张去重实施卡和36例专项验收。首期最重要的顺序是：模型看得到真实观察→工作记忆可靠→任务和外部动作可恢复→预算/权限可约束→结果可解释和评测→按证据扩展模型压缩与Skill演进。后续新需求进入同一能力表更新，不再只在聊天里零散追加。
