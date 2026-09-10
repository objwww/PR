# 告警系统：生产级多 Agent 与 Harness 源码审计

> 日期：2026-09-09。扫描基线：Git `aedf1dd` 加本地工作区；扫描期间 Gatus 等文件发生并行修改，已单独标明。目标：真正使用 LLM 的多 Agent RCA，达到明确负载和部署边界内的生产可靠性、安全性与可运维性；不要求百万告警，不以增加中间件数量衡量成熟度。
>
> 本轮读取源码、生产装配、数据库迁移、相关测试、前端与部署配置。没有读取用户禁止的三个文档，没有登录远端、发送真实通知、注入故障或修改业务实现。新增本地缺陷行为复现测试。本文不是依赖 CVE 全量扫描、渗透测试或生产压测证明。

## 一、结论：当前不能称为生产级多 Agent RCA

最重要的事实是：**当前 Native RCA 生产执行链没有调用 LLM。** 它读取配置里的固定 proposal，串行调用 metrics/logs/change，再从预先带有断言注记的 Evidence 推导 Claim。三个类叫 Agent，不等于三个具备观察、推理、选择动作能力的调查 Agent。

此前把“已有多 Agent 的组件基础”讲得接近“已实现多 Agent”，是不准确的。准确定位应是：**具备一部分治理组件的确定性调查流水线，LLM 调查、证据解释和可恢复的多 Agent 执行尚未接通。**

当前存在三类问题，必须区分：

1. **目标能力缺失**：Native 不调用模型，没有基于观察动态选择动作的循环，真实日志/变更源未接入。
2. **执行正确性缺陷**：证据和快照身份混用、DAG 单轮驱动、租约回收无条件覆盖、取消与状态迁移竞争、通知假成功。
3. **生产治理缺口**：Run 总预算没有接在实际调用之前，用户身份仍是共享 Bearer 加自报操作者，外部监控与恢复缺少完整运行验收。

这些问题在几个并发任务、一次重启或一条业务错误响应下就可能发生，不需要百万告警才触发。

### 风险分级

本文 P1 表示上线前必须修复或明确关闭相关能力；P2 表示在相应功能启用前补齐，或有可接受的临时边界。没有把所有问题都标成“远程可利用漏洞”：功能缺失、竞态、资源耗尽、鉴权风险和运维缺口分别说明。

## 二、生产调用链核查

实际路径是：RcaWorker 领取 NATIVE_INVESTIGATE → NativeInvestigationExecutor → 配置固定 proposal → DeterministicSupervisor/PlanCompiler → MetricsAgent、LogsAgent、ChangeAgent → ReadOnlyToolFace/ToolGateway → Evidence → NativeRcaAgent → ClaimReducer → ReportAssembler → NativeReportAdapter → 通知 Outbox。

没有从这条链路到 ModelGateway.complete 的调用。NativeInvestigationExecutor 的模型标记常量是 `native-deterministic-v1`；NativeRcaAgent 使用普通循环处理 scope 字段，没有 prompt、模型请求或模型响应解析。

| 核查项 | 当前事实 | 不能据此声称的能力 |
|---|---|---|
| 三个 Agent 类 | 每个类执行固定单工具查询 | 多个 LLM 自主排查 |
| AgentProfile | 有 prompt、allowlist、budget 元数据 | prompt 被模型使用、预算执行生效 |
| PlanCompiler | 能校验并保存 DAG | 生产 driver 能正确执行所有合法 DAG |
| ModelGateway | 有模型请求、路由、重试与账本代码 | Native RCA 已经调用模型 |
| EvidenceSnapshot | 存在冻结表与摘要 | RCA 只读取指定快照成员 |
| 全链测试 | 注入断言后能组装报告 | 从真实原始证据自主找到根因 |
| NativeCapabilityProbe | 检查组件对象和配置存在 | 数据源真实、LLM 可用、调查有效 |

## 三、逐项发现：能力与推理闭环

### F01 · P1 · Native 没有 LLM，也没有满足目标的多 Agent

**证据：**[NativeInvestigationExecutor](../control-app/src/main/java/com/objwww/pr/control/infrastructure/nativeexec/NativeInvestigationExecutor.java) 82、133～202、229～282 行；[NativeRcaAgent](../control-app/src/main/java/com/objwww/pr/control/alert/application/agent/NativeRcaAgent.java) 55～89 行。搜索 alert 和 nativeexec 生产包，没有 ModelGateway/ModelGatewayPort 调用。

**影响：**系统可以查询、记录、归并，但不能理解原始日志并提出新的排查假设。继续加 Agent 数量不会自动产生推理能力。

**改造：**先打通一个真实 RCA 模型调用，再接两个有明确职责、有限步骤和工具权限的调查 Agent，由诊断 Agent 提出可验证 Claim。调度、授权、预算与发布仍由确定性代码控制。

**验收：**一次真实 checkout 事故产生关联 RCA run/task/attempt 的模型调用记录、实际请求结果、模型版本和 usage；现场证据由工具取得，模型输出改变后续调查动作；不得在测试或前置代码中预灌最终 Claim。

### F02 · P1 · 旧 ModelGateway 不能直接接入 RCA

**证据：**[ModelCallContext](../control-app/src/main/java/com/objwww/pr/control/domain/ai/ModelCallContext.java) 24～42 行要求 prRevisionId；[V5 模型账本](../control-app/src/main/resources/db/migration/V5__m3_model_governance.sql) 8～10 行外键指向 review_run、run_step、step_attempt；[ModelGateway](../control-app/src/main/java/com/objwww/pr/control/application/ModelGateway.java) 216 行附近仍写 reviewRunId/runStepId，决策事件也带 PR 身份。

**触发：**把 RCA 的 run/task/attempt UUID 直接填入旧上下文，账本 INSERT 将不满足旧域外键；随便造 PR 身份则污染审计关系。代码存在模型客户端不等于有可直接复用的 RCA 模型链。

**改造：**抽取可复用的请求、路由、错误分类逻辑，为 RCA 实现正确的调用上下文、事件和账本适配。可以新增 RCA 专用模型调用表，或审慎泛化所有者模型；不能用虚假外键或无操作账本绕过问题。

**验收：**新 Run 从创建到模型调用完整集成测试；模型账本不可写时零触网；实际 usage 与账本能对账；所有引用落在 RCA 域。

### F03 · P1 · 原始工具 Evidence 没有被转换为 Claim

**证据：**[SingleToolEvidenceAgent](../control-app/src/main/java/com/objwww/pr/control/alert/application/agent/SingleToolEvidenceAgent.java) 134～139、163～169 行只加入 time_range/input_snapshot_digest；NativeRcaAgent 66～68 行直接跳过没有 claim_key 的 Evidence。

**影响：**真实工具返回非空数据并成功存档，仍不会因此产生断言。这是当前根因推导断链，不是模型效果不好。

**改造：**引入明确的 Observation → Hypothesis/Claim 转换步骤，输入只能是允许访问的冻结证据，输出包含命题、类型、支持和反证引用、适用范围、时间窗。原始数据不由工具后端自带 claim_status 来决定真假。

**复现：**新增测试 `successfulToolProducesRawEvidenceButNoClaim`，成功工具确实产 Evidence，但 RCA verdicts 为空。

### F04 · P1 · 输入配置摘要与输出证据快照摘要混用

**证据：**NativeInvestigationExecutor 160 行把 configDigest 传进 investigate；该参数随后成为 CallContext.inputSnapshotDigest；161 行又冻结真正 EvidenceSnapshot。NativeRcaAgent 62～64 行拿证据中的 input_snapshot_digest 与新 snapshotDigest 比较。

**影响：**即使给新 Evidence 补 claim_key，只要保留现有 input_snapshot_digest，仍会被当成外来快照丢弃。单独修复 F03 不够。

**改造：**分别命名和保存 config_digest、investigation_input_digest、evidence_snapshot_digest。Claim 绑定实际输出快照；工具调用身份绑定调用前输入。不要要求调用之前就知道包含调用结果的快照摘要。

**复现：**`annotatedEvidenceWithInputDigestIsDroppedAgainstOutputSnapshotDigest` 证明带注记的证据仍被过滤。

### F05 · P1 · RCA 没有按冻结成员读取证据

**证据：**NativeRcaAgent 61 行使用 findByRunId；没有 EvidenceSnapshotRepository 依赖；scope 没有 input_snapshot_digest 时直接接受。

**触发：**冻结后同 Run 又写入一条带注记证据，或未来多个 Agent/重试并发落证据。RCA 可能使用不属于所声明快照的内容。

**改造：**通过 snapshot_id/member 表读取精确证据集合，校验成员完整性、generation 与 run；缺失成员导致明确失败或不足，不回退到 Run 全量查询。

**复现：**`annotationWithoutSnapshotBindingIsAcceptedWithoutMembershipLookup` 证明仅凭任意非空 snapshot 字符串就能接受未做成员核验的注记。测试证明应用读取缺口，不宣称外部匿名用户可写数据库。

### F06 · P1 · 症状被自动升级为根因

**证据：**[ReportAssembler](../control-app/src/main/java/com/objwww/pr/control/alert/domain/claim/ReportAssembler.java) 79 行附近把 confirmed 中任意 TRUE 当成根因；[NativeReportAdapter](../control-app/src/main/java/com/objwww/pr/control/alert/application/NativeReportAdapter.java) 的 rootCause 选择第一条 TRUE。Claim 没有可靠的“症状/原因/排除”语义门。

**触发：**指标和日志都确认 checkout 错误率升高。它们只证实症状，当前装配逻辑却足以产生“已确认根因”。存在多个 TRUE 时，排序靠前的命题可能成为根因，不是经过因果核验的命题。

**改造：**区分 SYMPTOM、CAUSAL_HYPOTHESIS、ROOT_CAUSE、EXCLUSION；根因必须有因果解释和必要现场证据，无法核实时输出原因未确认。多源重复观察同一个错误不等于独立证明因果关系。

**复现：**`corroboratedSymptomIsTreatedAsConfirmedRootCause` 使用两条“checkout 错误率高”的症状断言，展示现有组装行为。

### F07 · P1 · 日志和变更仍是生产装配中的 fixture

**证据：**[AlertAm4Config](../control-app/src/main/java/com/objwww/pr/control/infrastructure/config/AlertAm4Config.java) 116～129 行；Logs/Change 绑定 ReplayToolExecutor。

**影响：**不同现场可能拿到相同回放数据。即使报告诚实 unknown，也不能宣称完成真实多源调查。NativeCapabilityProbe 检查 Bean 存在，不会识别这种能力差异。

**改造：**真实/回放 profile 硬隔离；未接日志后端时明确标记能力 ABSENT，让对应任务 SKIPPED/MISSING_SOURCE，不能用 fixture 伪装 LIVE。先接实际已有数据源，不为凑 Agent 数量引入虚构后端。

## 四、逐项发现：调度、恢复与 Harness

### F08 · P1 · 合法 DAG 不一定能被执行

**证据：**NativeInvestigationExecutor 237～247 行只枚举一次；253 行只接受 READY；supervisor.advance 在整轮之后才调用。数据库 findByRunId 按 UUID 排序，并不是拓扑顺序。

**触发：**A → B。startRun 仅放行 A；单次遍历跳过 BLOCKED 的 B；A 完成后结束整轮；advance 才把 B 变 READY，随后代码发现没有 REPORTING，就返回 REPORTING_NOT_ENTERED。B 没有第二轮执行机会。

另一个契约差异：PlanCompiler 按注册 Agent 校验任务，driver 却按三个固定 task_key 分派。合法的新 task_key 可能被静默转 DEAD。

**改造：**编译与执行使用同一注册契约；重复执行“推进 → 领取 READY → 执行 → 记录回执 → 再推进”，直到终态或明确预算/deadline。不要只增加线程。

**验收：**两级、三级链，两个根节点 join，required 失败传播，optional 失败继续，合法自定义任务键；乱序存储结果不影响执行。

### F09 · P1 · DAG 子任务崩溃后无法续跑

**证据：**driver 把子任务 READY → LEASED → RUNNING，却不建立独立 owner/epoch/lease_until；[PostgresRcaTaskRepository](../control-app/src/main/java/com/objwww/pr/control/infrastructure/persistence/PostgresRcaTaskRepository.java) 155 行只回收有过期租约的 LEASED，Native drive 仅驱动 READY。

**触发：**子任务置 RUNNING 后进程崩溃。driver 自己的租约回收不恢复这个 RUNNING 子任务；重试 driver 仍然跳过它，图无法收敛。

**改造：**明确选一种所有权：driver 独占整个 DAG，子步骤用持久 checkpoint 恢复；或每个子任务都有独立完整租约与 attempt。不要保留“两边各实现一半”的模型。

**验收：**在任务开始、请求已发出、证据已写、DONE 未写四个边界杀进程，恢复后有确定结果，不无限 RUNNING、不重复发布。

### F10 · P1 · 回收线程可能覆盖新租约或已完成状态

**证据：**[RcaWorker](../control-app/src/main/java/com/objwww/pr/control/alert/application/RcaWorker.java) recoverExpired 先查列表再 tasks.update；PostgresRcaTaskRepository 117～139 行 update 仅 WHERE id，无旧状态、epoch、owner、到期时间条件。

**具体交错：**回收器 A 读到 epoch=7 过期行；另一回收器回收，worker B 领取成 epoch=8；A 随后执行无条件 update，把新租约覆盖成 RETRY_WAIT 并写回 epoch=7。即使只有一个回收器，读完后旧 worker 刚完成，也可能把 DONE 覆盖回重试。

**改造：**专用 reclaimExpired 操作，数据库同一原子更新检查 state、observed epoch、owner 和 lease_until；命中 0 行就是竞态失败，不能继续覆盖。旧 epoch 永不减小。

**验证状态：**已从实际 SQL 和调用时序确认缺少防护；本轮没有 PostgreSQL 并发实测，必须补两连接 barrier 测试。

### F11 · P1 · 心跳参数没有形成持续续租与失租中断

**证据：**RcaWorker 保存 heartbeatInterval，但运行循环没有按它调度心跳；Native 仅在若干同步步骤完成后调用 heartbeat。heartbeat 回调忽略数据库更新是否命中，返回 void。

**影响：**一次慢工具或未来 LLM 调用跨越 taskLease 时，另一个 worker 可回收，而原 worker 仍然继续下一步。最终报告有 lease fence，不代表中途查询、预算和 Evidence 写入都已被阻断。

**改造：**独立续租调度或可中断等待；心跳返回明确所有权结果，失租触发取消；每个新动作前及结果准入时检查 Run 活跃、generation、owner/epoch、deadline。超时参数满足单次调用与续租窗口关系，并验证调度延迟。

### F12 · P1 · Cancel 的版本检查和状态写入不原子

**证据：**[CommandService](../control-app/src/main/java/com/objwww/pr/control/alert/application/CommandService.java) 84～114 行先读 Run/revision，再 runs.update 和 append event；[PostgresRcaRunRepository](../control-app/src/main/java/com/objwww/pr/control/infrastructure/persistence/PostgresRcaRunRepository.java) 109～122 行更新仅 WHERE id。装配未给整个 apply 包含锁或 CAS 的事务保护。

**触发：**Cancel 读到 RUNNING 后，报告线程完成并提交；Cancel 随后覆盖为 CANCELLED。也可能 Run 已改取消、事件未落，重试由于 revision 分支不能完整修复。进程内先检查状态机不能替代数据库条件更新。

**改造：**命令持久化与 apply 两阶段可保留，但第二阶段应在单事务中锁定 Run、核验版本、迁移状态、追加事件并结算命令；处理并发命令的幂等。结果 CAS 不成立必须显式返回。

另外，现有取消只改变状态，Native 没有在每个子工具前重新读取消标记。需要与 F11 的 ActionGuard 一起补齐，保证取消后不再开始新动作。

### F13 · P1 · 活跃 Run 查询遗漏 REPORTING

**证据：**PostgresRcaRunRepository 126～134 行 findActiveByIncidentId 只查 QUEUED/RUNNING；RcaRunState.isActive 和 V12 唯一索引均包含 REPORTING；IncidentProjector 247、276 行依赖该查询。

**影响：**报告正在生成时，投影器可能误判“无活跃 Run”，尝试插新 Run，被数据库部分唯一索引拒绝，导致投影事务失败/重试。SQL 与 Java 状态全集出现漂移。

**改造：**统一活跃谓词并增加 REPORTING 的真实 PG 集成用例，检查相关查询和所有新 Run 铸造路径，不只修一个调用点。

### F14 · P1 · 配置、时间窗没有冻结为真正的调查输入

**证据：**NativeInvestigationExecutor 138 行读取 Run 固定 configDigest，但 208～218 行 proposalOf 查询当前 activeDigest；232 行时间窗取执行时 clock.now，Incident 参数没有参与查询范围选择。

**触发：**Run 在配置 A 下创建，排队期间发布 B，实际执行 B 的提案却把快照标为 A。告警积压十几分钟后，查询最近十分钟可能完全错过异常；重试又变成另一个时间窗。

**改造：**按 Run 冻结 digest 读取提案；输入快照保存 incident episode、告警窗口、服务范围和查询参数。需要查“现在是否恢复”时作为新的明确动作，不能静默移动原调查窗口。

### F15 · P1 · Run 级预算、DoomLoopGuard 尚未接到执行链

**证据：**RunBudgetGate、DoomLoopGuard 有独立实现；生产搜索未找到 Native/Supervisor/ToolGateway 对它们的调用。AgentProfile.budgetLimits 也没有在 SingleToolEvidenceAgent 执行前扣减。ReadOnlyToolFace 只有进程内固定窗口调用限流。

**影响：**有预算表和配置不代表能限制一次事故的总费用。当前固定三步尚有限；接入 LLM、多轮和并行 Agent 后会直接成为费用和资源风险。

**改造：**Run 共享原子预算，动作执行前 reserve，完成后按实际 usage settle；UNKNOWN 保守占用待对账，重试不重置总预算。Agent 子预算不超过父预算。记录重复动作与无新增证据轮次，在 Gate 处阻断，而不是提示模型“请节约”。

旧 ModelGateway 每次 complete 新建局部预算，且 context.stepDeadline 没有实际参与该方法总 deadline 计算，也需要在复用前改为 min(run deadline, task deadline, gateway deadline)。

### F16 · P1 · 工具异常和结果提交有账本缝隙

**证据：**SingleToolEvidenceAgent 104～114 行的 catch 只覆盖 gateway.invoke；122 行 parsePayload 在外；128 行先 ledger.succeed，137 行再 evidence.insert。工具调用 attemptId 在 driver 中随机创建，没有对应完整持久化 attempt 生命周期。恢复扫描处理 external_invocation/investigation_result，没有 rca_tool_invocation 的 PENDING 回收。

**触发：**返回非 JSON 后调用账本永久 PENDING；返回 success 后进程在 Evidence 插入前崩溃，账本 SUCCESS 但没有可消费结果；子任务留 RUNNING。

**改造：**明确远端结果接收、持久化结果、证据准入与任务结算边界；所有异常都归入可审计状态。对成功响应先可靠保存结果引用，事务内结算可原子的部分；超时和悬挂调用可对账，不能盲目重发未知写动作。

**复现：**`malformedResponseLeavesOpenedLedgerUnsettled` 验证 PENDING 已打开，解析异常后没有 SUCCESS/FAILED 结算。

### F17 · P1 · 响应大小保护发生得太晚，调用池也没有队列上限

**证据：**[PrometheusQueryExecutor](../control-app/src/main/java/com/objwww/pr/control/infrastructure/tool/PrometheusQueryExecutor.java) 64 行使用 ofByteArray；ToolGateway 收到完整 byte[] 后才检查 limit；AlertAm4Config 162 行 newFixedThreadPool 使用默认无界等待队列。Metrics schema 只检查字符串形状，没有时间窗、step、查询开销限制。

**影响：**一个超大响应先消耗堆内存；慢调用占满工作线程后，新请求积压。虽然当前固定串行限制了触发范围，但多 Agent 开启后会扩大，外部数据源也可能无意返回巨量序列。

**改造：**有界流读取 resultLimit+1，超限立即关闭；客户端/服务端共同限时；按工具设置 bounded queue/bulkhead，队列满明确拒绝；服务范围、窗口、step、返回序列数做语义约束。不要靠扩大堆兜底。

## 五、逐项发现：告警事实、通知与安全

### F18 · P1 · 背压会阻断恢复事件，去重又忽略静态材料变化

**证据：**[IncidentProjector](../control-app/src/main/java/com/objwww/pr/control/alert/application/IncidentProjector.java) 101～121 行先判断背压再判重复与 resolved；每个非重复事件都增加本地 active/queued 估计，哪怕它是恢复事件。208 行附近重复分支提前返回；[AlertIdentityFactory](../control-app/src/main/java/com/objwww/pr/control/alert/domain/service/AlertIdentityFactory.java) 的 payloadHash 不含 annotations，而 investigationHash 包含静态 annotations。

**影响：**达到 active 上限后，能减少 active 的恢复事件本身也被延迟。另一个独立问题是静态排查材料更新被 payload 去重吞掉，无法触发重新调查。

**改造：**先确保事实去重、恢复状态和材料更新能进入正确性通道，再对新调查做 admission。阈值不只为高吞吐，测试用极小阈值也能复现。分别定义通知重复、事实变化、调查输入变化。

### F19 · P1 · 首见 Incident 并发冲突的恢复逻辑不适用于当前事务

**证据：**IncidentProjector 捕获 DuplicateKeyException 后在同事务重查；PostgresIncidentRepository.insert 使用普通 INSERT，没有 ON CONFLICT 或保存点。

**影响：**PostgreSQL 语句发生唯一冲突后，同事务后续查询不能像内存仓储那样正常继续。多 projector 首见同 key 时不是无损合并，而可能整组失败重试。

**改造：**使用 INSERT ON CONFLICT DO NOTHING 加读取并锁行，或正确的保存点策略；验证事务原子性。该项从 SQL 确认机制缺口，本轮未执行真实 PG 并发复现。

### F20 · P1 · 通知业务拒绝被记成成功

**证据：**[WebhookChannel](../notify-app/src/main/java/com/objwww/pr/notify/domain/channel/WebhookChannel.java) 49～52 行只传 HTTP status 和 Retry-After；[WebhookTransport](../notify-app/src/main/java/com/objwww/pr/notify/domain/channel/WebhookTransport.java) fromStatus 将所有 2xx 映射 Delivered，Response.body 未参与判定。

**影响：**平台 HTTP 200 但正文 errcode 非零，Outbox 仍可能 SENT，值班人员没有收到消息。该问题与 Gatus provider 是两条不同代码路径。

**改造：**按渠道解析业务成功条件；正文非法、缺必需成功字段、业务拒绝分别归类，错误响应限长脱敏。UNKNOWN 不等于失败可安全重发，应配合接收器幂等和人工对账策略。

**复现：**`NotificationAuditCharacterizationTest` 用无网络假 transport 返回 200 + errcode=40014，当前结果为 Delivered。

### F21 · P1 · “路由值班”在应用代码中只有日志，没有送达链

**证据：**[ControlAlertRouter](../control-app/src/main/java/com/objwww/pr/control/alert/application/ControlAlertRouter.java) 194～217 行写 PROCESSED/SUPPRESSED Inbox，打印 CONTROL_ALERT_ONCALL，返回 ROUTED_ONCALL。扫描 deploy 和 main Java 未找到该日志事件到独立接收器的消费实现。

**影响：**接口路径已经宣称 routed，但从仓库不能证明任何人会收到。若外部日志平台确实配置了规则，需要补实际配置与送达证据，而不能用日志存在代替。

**改造：**明确独立通知实现或受维护的外部日志告警规则，保存失败/重试/送达证据。控制面数据库不可用时还应有真正外部观察者。

### F22 · P1 · 操作者身份可以自报，生产用户权限未建立

**证据：**[OperatorApiController](../control-app/src/main/java/com/objwww/pr/control/ops/interfaces/OperatorApiController.java) 使用共享 Bearer，actor 来自 X-Operator-Id；RunCommandController 同样如此。ConfigBundleController actor 还是机器语义占位。[LoginView](../alert-web/src/views/LoginView.vue) 32～42 行是任意非空账号密码写 sessionStorage 的 mock。

**边界：**这不是“匿名用户已能穿透后端”。共享 Bearer 仍有鉴权，Compose 也要求凭据必填。但持有共享凭据的人能够自报其他操作者；前端假登录不提供生产会话或角色授权。

**改造：**建立服务端认证主体，敏感操作按真实角色授权；actor 由认证结果产生，忽略客户端自报身份。用户会话与机器入口令牌分离。若有双人复核，不得仅比较两个客户端可伪造的名字。单人运维也要如实标成单人授权，不宣传不存在的身份隔离。

**附加风险：**部分控制器将空配置转为空字节且仅比较 Bearer 后缀；绕开 Compose 直接启动时需要服务启动级 fail-closed。当前未验证 HTTP 容器对空后缀头的处理，不作为已证实匿名利用结论。

### F23 · P2 · 身份序列化有歧义，证据摘要未覆盖全部语义

**证据：**AlertIdentityFactory 用 k=v 与分隔符拼接，未转义值。比如 {alertname=A, service=B} 与 {alertname=A|service=B} 在 incidentKey 上可能一致。标签内容允许包含这些字符时会误归并。

EvidenceSnapshotBuilder 只聚合 evidenceType/payloadDigest，未把 source、scope、时间窗纳入成员语义摘要；EvidenceEnvelope 有 rowDigest，但 PostgresEvidenceRepository 读路径只 verify payload。修改 scope 注记不改变 payload digest，后续 Claim 却可能改变。

**改造：**身份采用带版本的 canonical JSON 或长度前缀；迁移期间避免分裂现有事故。快照使用覆盖业务语义的不可变 envelope digest 并验证成员，结合最小 DB 写权限。普通 hash 不能证明拥有同等数据库权限的人没有同时改正文和 hash，不应宣传成强防篡改。

### F24 · P1 · 引擎退场后的无调查分支没有可靠恢复入口

**证据：**IncidentProjector 288～298 行，对路由结果 HOLMES 仅记录、不铸 Run/Task，注释写“等待下一次告警”；同样告警再次到来却可能在 duplicate 分支提前结束。

**影响：**Native 不就绪或配置限制导致本次不调查，恢复配置后，事故可能一直没有 RCA。不能把“没有可执行引擎”当成成功处理。

**改造：**为事故 admission 保存 WAITING_CAPABILITY/DEFERRED 等显式原因与重驱条件，恢复后主动扫描或事件触发；前端显示尚未调查。若当前决定 Native 是唯一引擎，退出兼容灰度逻辑也需明确策略，不保留假回退语义。

## 六、技术债与运维边界

### 1. 测试证明的是组件行为，不是目标能力

NativeInvestigationExecutorTest 只有三个测试。成功用例先 seedAnnotatedClaims；工具回放 MISS 后全 DEAD，也仍可利用预置 Claim 生成 CONFIRMED 报告。PlanCompiler 与 DagExecutionService 还分别使用两个不同 EdgeStore，空边提案不会暴露这一点；不能据此验证有依赖的 DAG。

这类测试可以保留为“报告出口测试”，但应更名并补真正链路用例。验收必须验证真实工具 → Observation → LLM → Claim → 引用校验 → 报告，不能只检查 engine=NATIVE、schema valid 或终态 SUCCEEDED。

本轮新增测试是**缺陷行为复现测试**：通过表示所描述的坏行为确实存在，不表示安全门通过。修复后应翻转为期望契约断言，不能把当前行为永久冻结成正确规格。

### 2. 生产与影子命名、历史域残留影响理解

am4ShadowToolFace/am4ShadowPool 实际被 Native 复用；ModelCallContext 仍描述 ReviewAgentLoop；Holmes 退场后有历史构造和枚举保留。历史兼容类型可以存在，但运行能力、类名、配置说明与日志必须区分。

建议保留必要数据读取兼容，删除或隔离已无生产用途的装配；列一张“活跃生产组件”表，用真实调用图证明。不要为保持历史字符串测试不变而保留误导性的行为名称。

### 3. 框架契约看起来丰富，实际消费不足

AgentProfile 的输出 schema 目前是宽泛 object，prompt/预算元数据没有完整消费者；CapabilityProbe 检查类与配置，不检查模型/数据/契约版本。未来应把静态 capability 与运行 readiness、质量能力状态分开。

AgentRegistry、ToolRegistry、PlanCompiler 和执行器的允许集合应一致。注册表只能说明“允许什么”，不能代替真实执行和输出校验。

### 4. SSE 和前端尚未完成生产集成

SseStreamService 的一次性票存在进程内 Map。多实例下换票和消费落到不同实例会失败；进程重启也使票失效。可先维持单 API 实例或使用会话粘性，正式横向部署再选择共享票或签名票加防重放状态，不需要为此立刻加入 Redis。

EventQueryController 单轮排水即关闭流，一次性票不能直接依赖 EventSource 用同 URL 无限重连。前端当前是 mock，接真实流时必须实现重新换票并显式携带游标，或者调整为可靠长连接协议。未完成接线不能宣称已有生产实时控制台。

### 5. Gatus 处于并行改造中，本报告不重复判旧问题

扫描后段本地 Compose 已增加 GATUS_CONFIG_PATH 与 digest pin，配置已改 SQLite/custom/endpoint alerts，因此不再把旧配置三项写成当前未修缺陷。

但新 body 直接插入 RESULT_ERRORS，并且注释承认其可能含未转义双引号。连接失败时形成非法 JSON，依赖接收器“容错解析”是脆弱契约。应选择安全编码机制、纯文本错误字段传输或移除动态错误文本，再由适配器构建严格 JSON；验收明确覆盖断连接错误。

工作区出现了新的 HOST2 相关修改说明，但本轮没有核对远端是否已部署、网络与真实值班通道是否可用。主机来源和外部健康探针现状不从注释自动推断。旧 Gatus 字符串测试还断言 type:file，需随契约同步更新并增加同版本运行测试。

### 6. 单机与备份是可用性边界，不是推理质量问题

用户此前确认运行面同机。即使修好所有代码，宿主机故障仍可能中断全部服务。生产验收应声明 RPO/RTO、异机备份、数据库与 CAS 一起恢复的步骤；本地 cas-data 不能直接给另一台 worker 读取。

可以先满足低并发、单机、接受人工恢复的明确服务等级，而不是立即建设多机 HA。但“接受人工恢复”也要有恢复演练证据，不能只有备份脚本。

### 7. 未证明的安全事项

本轮没有进行公网资产扫描、依赖漏洞数据库比对、密钥有效性探测和租户越权渗透。未发现公开攻击路径不等于系统无漏洞。生产发布前仍需版本固定、SBOM/依赖扫描、镜像扫描、网络暴露核验与真实认证集成测试；这属于验证边界，不是在本轮宣称存在某个未经证实的 CVE。

## 七、怎样做成真正多 Agent，而不是改几个类名

### 最小但完整的角色组合

建议先完成三种逻辑角色，可共用同一个模型，不要求三个模型提供商：

| 角色 | 输入与职责 | 输出 | 权限 |
|---|---|---|---|
| 指标调查 Agent | checkout 告警窗口、指标工具结果，决定是否追加查询 | 带时间窗和证据引用的观察/候选假设 | 指标只读工具 |
| 日志/变更调查 Agent | 服务、错误特征、日志或变更事实，验证候选原因 | 支持/反证、缺失信息、下一步建议 | 对应只读工具 |
| RCA 诊断 Agent | 冻结的跨源观察与反证 | 有类型的 Claim、原因解释、未决问题 | 默认无直接执行权限；需补证则向 Supervisor 提议 |

Supervisor/Harness 负责确定性调度、依赖、预算、取消和状态转移。Verifier 可先用代码检查引用、适用窗口、源独立性和输出 schema；如果后来加 LLM Critic，它也只是一个被预算与输出规则约束的建议者，不能凭分数推翻现场证据。

多 Agent 不要求同时运行很多线程。先完成两个调查角色各自的受限 LLM 循环与共享证据协议，再开启有上限的并行。若一个角色只执行固定工具，界面应称它为 Collector/Tool Task，避免继续误导能力。

### 一轮调查的执行契约

1. 创建 Run 时冻结事故范围、时间窗、配置和工具/Agent 版本。
2. Planner 或规则选择有限任务；PlanCompiler 校验后保存。
3. 每个 Agent 读取自己的上下文与允许的 Evidence，不共享可随意修改的消息列表。
4. 模型只能输出 ActionProposal 或 Findings。ActionProposal 含已注册工具与参数，不能直接生成 SQL、任意 URL 或自由 shell 执行。
5. ActionGuard 原子检查租约、取消、generation、deadline、预算；通过后写调用记录再执行。
6. 结果限长、脱敏、保存，更新 checkpoint；证据引用校验后进入下一轮。
7. 达到足够证据、步数上限、无新信息或无法获取来源时停止，明确完整/部分/未决状态。
8. 汇总阶段只读取冻结成员；Claim 区分症状、原因和反证，报告发布继续复用事务 Outbox。

需要持久化的是执行状态、动作提案、可解释的结论依据和引用，不需要保存或展示模型隐藏思维链。重启恢复不能靠重新读一整段对话并重新猜测哪些动作执行过。

### 上下文和消息协议应包含什么

每条跨 Agent 结果至少携带 run_id、task_id、attempt_id、generation、input_snapshot_digest、evidence_refs、schema_version、producer、proposed_claims、missing_information。Evidence 由宿主赋予真实来源身份，不能让模型自称第二个独立来源。

上下文按任务最小化，日志和历史文档作为不可信数据。RAG 是辅助找资料，Skill 是版本化排查方法；二者都不授予工具权限，也不替代当前证据。先修 F01～F17，再引入它们，才能避免把断链自动化得更复杂。

## 八、建议的修复顺序：按生产闭环交付

### 第一批：修正确性地基

优先 F04/F05/F08～F16/F18～F20：冻结身份、快照成员、DAG 恢复、租约 CAS、取消 CAS、活跃谓词、预算入口、调用结算与通知业务成功。每个修复附一项能复现竞态或崩溃的测试。F22 用户身份在任何真实多人使用前完成。

### 第二批：兑现 LLM RCA 与多 Agent

完成 F02 的 RCA 模型账本和上下文，随后接实际模型与两个真实数据来源。完成 F03 的证据解释、F06 的因果语义与跨源核验，彻底消除 LIVE fixture。先串行验证闭环，再并行 2 个调查任务，不能仅把现有方法放进 CompletableFuture 就宣布多 Agent 完成。

### 第三批：生产运维和产品体验

打通真实身份与前端、可靠事件流、外部通知、探针、备份恢复、运行指标和灰度回滚。Capability 页面明确显示 LIVE/REPLAY/ABSENT、LLM 最近成功调用、当前预算与失效原因。不要把静态展示数据混进真实运行状态。

### 暂不安排的工作

百万告警容量改造、先拆多个 Maven 微服务、上 Kubernetes、引入 RabbitMQ/Redis、全量向量库建设均不解决本轮核心问题。保持 PostgreSQL 与现有模块结构，先把每个动作执行正确、能恢复、可证明。生产级不等于高规模，但要求已承诺的能力可信。

## 九、生产准入验收：没有这些证据就不宣称完成

| 验收门 | 必须看到的结果 |
|---|---|
| 真 LLM | Native RCA 有真实模型请求与结果，账本、usage、模型版本关联到正确 RCA 身份 |
| 真多 Agent | 至少两个调查角色依据观察选择受限动作，任务与消息可追踪，诊断角色汇总并引用证据 |
| 真来源 | LIVE 不使用 fixture，工具异常明确缺源，无法查询时不编造正常结论 |
| 因果质量 | 症状不会变根因，冲突保留反证，根因无法确认时允许 unknown |
| DAG | 多级依赖、join、required/optional 失败和任务乱序均正确 |
| 并发恢复 | 两个 worker 竞争、租约跨期、暂停进程后恢复，旧 owner 不能执行新动作或覆盖新结果 |
| 崩溃恢复 | 各动作提交边界杀进程，无永久 RUNNING/PENDING，未知结果有对账策略 |
| 取消 | 与报告完成竞态结果一致，取消后不启动新工具/模型动作 |
| 预算 | 两个 Agent 共享总量，重试不刷新预算；费用不足零触网 |
| 快照 | 冻结后新增 Evidence 不改变同快照回放，配置热切不改变已创建 Run |
| 通知 | 200 业务失败不标 SENT；接收器有真实收据；不确定投递进入可操作状态 |
| 权限 | actor 来源可信，越权操作拒绝；日志提示注入不能改变工具或模型权限 |
| 运维 | 外部观察者失联可见，独立通知闭环，数据库和 CAS 在干净环境恢复成功 |

本轮不承诺准确率数字、恢复秒数或并发容量。它们需在实际机器、模型、数据源和约定 SLO 下测量。结构化 JSON 合法、单测绿、报告有 NATIVE 标记，都不能替代以上验收。

## 十、本轮验证与交付范围

已运行原有五组核心测试：NativeRcaAgentTest 6 项、DeterministicSupervisorTest 11 项、RcaWorkerTest 13 项、ToolGatewayTest 10 项、NativeInvestigationExecutorTest 3 项，共 43 项通过。

新增 [HarnessAuditCharacterizationTest](../control-app/src/test/java/com/objwww/pr/control/alert/application/agent/HarnessAuditCharacterizationTest.java)，验证解析异常账本悬挂、原始证据不出 Claim、摘要混用过滤、缺少快照成员检查、症状被当根因。新增 [NotificationAuditCharacterizationTest](../notify-app/src/test/java/com/objwww/pr/notify/domain/channel/NotificationAuditCharacterizationTest.java)，验证业务失败误记 Delivered。它们均不调用外部服务。

新增复现测试最终 6 项全部通过（Harness 5 项、通知 1 项），表示上述缺陷行为已被复现。运行命令与输出保存在根目录 audit-harness-tests.log、audit-harness-probes.log、audit-notification-probe.log。初次新增测试有一处复现夹具缺少合法 scope，补齐夹具后重跑；这不是修复业务代码。

未运行全量测试、Testcontainers PostgreSQL 集成测试、真实 LLM/通知测试和远端故障演练。本机未发现可调用 Docker 命令。没有修改原生产配置或实现；Gatus 的并行改动不属于本轮。报告的 SQL 竞态项标明了仍需真实数据库验证，不能把静态交错分析包装成已完成演练。
