# Run停滞对账与影子执行收口方案 v1

> 2026-09-13 后续复核：当前工作区已出现 RunReconciler、REPORT_FINALIZE 与过期清理实现，下文“待实施”是初版时点状态。最新代码缺口、取消传播修复及 30 项增量测试以[看门狗与取消传播 v2](告警-看门狗与取消传播-代码复核及修复技术方案-v2.md)为准；尤其不能再把看门狗描述为完全不存在。锁序以 v2 的全链复核要求为准。

2026-09-13。待实施。归入[生产级总方案](告警-Agent生产级收口与后续优化总方案-v1.md)的CL-02恢复接线、OR-07积压与OR-09可观测性，不另开一条无关优化线。本轮只核对本地源码并登记方案，没有查询/修改195上的两个Run，也没有取消或删除历史材料。

## 1. 先修正诊断口径

Am4ShadowTrigger注释与实现确实有意停在REPORTING并保持零报告零发布。但是RcaRunState.isActive明确包含REPORTING，它只是该测试驱动的结束位置，不是系统终态。这样的设计会导致队列误显示，并保留数据库活跃资格。

V25索引为`(incident_id, engine) WHERE state IN ('QUEUED','RUNNING','REPORTING')`。影响应表述为同incident同engine的再准入，不能泛称所有引擎都被阻塞。实际部署是否还有后续迁移需要运行manifest核对。

REPORTING→CANCELLED已合法，CommandService有不依赖worker执行的取消路径。不能因列表长期显示REPORTING就认定“没有取消措施”。

RcaWorker.recoverExpired会回收过期任务；通用claimNext限定HOLMES_INVESTIGATE/NATIVE_INVESTIGATE driver。生产driver若仍有可回收租约，可以由现有路径恢复。因此“finishTask前崩溃必永久卡住”不能直接成立。本次确认的缺口是没有看到独立Run级停滞对账入口，尤其要覆盖driver不存在/已终态而Run仍活跃的孤立状态。事故复现需真实PG与硬杀证据。

两个历史incident的AM6归属来自用户提供的取证，本轮未独立连接数据库核验；不把名称前缀、创建同秒或零事件当作足以自动删除/豁免的身份依据。

## 2. 方案总裁定

分别修两件事：

1. 新影子执行必须结束为明确的非活跃结果，报告和发布政策独立于Run状态。
2. 生产增加RunReconciler，识别真实停滞，优先复用driver恢复；只有硬期限/不可恢复条件满足才按状态机终止。

禁止通过`UPDATE ... WHERE updated_at < now()-N`直接批量过期。updated_at可能被心跳刷新，或者合法长请求长期不刷新，单字段既会漏报也会误杀。

## 3. 影子执行：复用现有终态，明确purpose

### 3.1 新增可信身份

在Run准入身份增加purpose：PRODUCTION、SHADOW、EVAL或LEGACY_UNKNOWN，保存来源任务/测试批次与completionKind。初始默认不得把所有历史行标PRODUCTION；历史来源无法证明时LEGACY_UNKNOWN，禁止凭名字推断。

首期不增加SHADOW_COMPLETED这个全局状态，复用SUCCEEDED/FAILED/PARTIAL等现有终态，额外用completionKind=SHADOW_EVIDENCE_ONLY表达“影子取证结束，未生成正式报告”。原始Run状态、报告质量和发布策略三者分开。

### 3.2 改动文件与调用链

- RcaRun及repository/迁移：持久purpose、来源与completionKind；迁移号实施时分配，不修改V25等已应用迁移。
- Am4ShadowTrigger：开始前用受控入口写SHADOW身份；完成取证/对照后，事务校验状态与任务结果，推进非活跃终态并写SHADOW_COMPLETED事件。失败按实际错误结束，不吞异常留活跃行；允许缺源的既有测试契约要在completionKind/结果中保留，不能伪称根因已验证。
- ReportCompletedNotifier/报告发布准入：从可信Run身份判断SHADOW禁止正式报告/发布；不能只靠调用者“不调用notifier”。增加负向集成测试，防以后复用路径串入发布。
- worker领取：生产调度排除影子专属任务，明确由哪个执行器持有。不要继续以“临时占满生产槽位”作为新影子任务隔离机制。先限制新影子到test/eval入口或独立队列/slot scope，旧历史类是否仍需保留由引用检查决定。
- RunQueryService及页面：影子默认不占生产活跃列表；可在测试/影子筛选中查看“影子取证完成，未发布”，而不是永久“报告组装中”。隐藏不能代替后端收口。

正式生产isActive与唯一索引规则先保留，不通过从索引删除REPORTING来绕开阻塞，那会让正在真实报告的Run失去并发保护。若未来要求同一incident同时运行生产和影子，单独设计purpose隔离的唯一约束、当前Run指针与领取规则，首期不顺手放开。

### 3.3 这两个历史Run

本次保持原样。短期可根据审核后的显式legacy-shadow分类改善展示，并标明原始state=REPORTING；该分类只用于历史展示，不能成为永久绕开生产看门狗的任意开关。

如需释放活跃位，由授权操作者走现有CANCEL，reason标明历史影子清理，保留事件和原始证据；若要将其迁移成影子完成态，必须在维护窗口保存前快照、核实来源、提交一次有审计的定向迁移并验证相关E2E基线。不能因为页面碍眼自动改历史。

## 4. RunReconciler：检测、恢复、终止分开

### 4.1 持久数据

复用现有Run配置/事件与任务表，增加缺失的deadline_at、last_progress_at/progress_seq或等效事件水位、reporting_started_at与recovery_attempts。deadline在准入按冻结政策固定，重启/重试不重置。旧Run无可信deadline时先ALERT_ONLY，不用当前配置追溯制造过期证据。

progress表示业务进展：任务终态、有效证据、新检查点、报告/发布提交；heartbeat单列表示执行者存活，不能算业务进展。无效重复模型请求也不能无限刷新进展水位。

扫描使用有索引、有批上限的候选查询，按状态/期限/进展筛选；worker拍内或独立定时任务短时运行，不与长外部调用共用阻塞线程。多实例通过Run条件更新/CAS取得对账资格，重复扫描不重复恢复任务或事件。

### 4.2 决策表

| 观察 | 判断 | 动作 |
|---|---|---|
| 合法driver租约有效，外部调用仍在deadline内 | 可能慢，不是孤儿 | 记录等待原因；超过软阈值报警，不抢租约 |
| driver租约过期且可恢复 | 已有恢复职责 | 调用/复用task reclaim路径，不另造第二driver |
| REPORTING，driver已结束但报告收尾未完成，持久材料完整 | 可重入收尾候选 | 创建唯一REPORT_FINALIZE恢复任务或复用等效任务，走同一发布赢家/事务 |
| Run活跃但driver缺失，尚未过deadline | 结构不一致 | 报警并按明确重建规则恢复；规则缺失则人工接管，不猜结果 |
| 到达硬deadline且取得Run终止资格 | 超期 | RUNNING/REPORTING可EXPIRED；QUEUED当前无EXPIRED边，首期按现有合法FAILED并附QUEUE_DEADLINE，若增边需Java/DB同步评审 |
| Run已终态，子任务/槽仍悬挂 | 清理不全 | 取消后续资格，晚到结果只审计，资源按租约/既有回收释放 |
| SHADOW新协议已完成或可信历史影子材料 | 非生产调查异常 | 走影子收口/历史展示流程，不发正式报告；影子未完成也需自身期限兜底 |

### 4.3 报告恢复不能重新乱跑调查

先核对当前报告材料是否完整持久化。若结果只在worker内存，先补一个“报告待收尾”的不可变材料引用，再允许恢复任务消费；不能凭task DONE和零report推断材料完整。

REPORT_FINALIZE只组装/验证既有材料，禁止隐式再调用LLM或重新查现场。以run+材料digest保证唯一任务，按task租约和Run围栏驱动；成功报告、publication、outbox沿现有同事务与发布赢家规则，不能由看门狗直接插通知。

若已有report/publication则读取并对账，不生成第二份；UNKNOWN发送保持原operationId。恢复次数上限持久化，失败进入明确终态或人工介入，不能永远自我重排。

### 4.4 过期事务

重新锁读Run、任务及相关指针，复验purpose、state、revision、deadline、业务进展、执行资格和恢复任务是否已提交；遵循项目统一锁序。只对观察版本仍成立的对象过期；finishTask/Cancel/Reconciler竞争仅一方成功。

同事务写终态、finished_at、reason与RUN_EXPIRED等审计事件。incident当前Run指针只能在仍等于该Run时清理，不能清掉新Run的指针。子任务后续动作资格撤销；在飞外部结果不当成未发送，账本保留UNKNOWN/实际结算。

触及恢复的Run在有效终止后才释放活跃唯一位；不能先绕过索引放新Run再慢慢清旧状态。失败时整个事务回滚并保留可重试对账线索。

## 5. 看门狗自己的运行方式

先启用ALERT_ONLY，输出分类、预计动作及误报样本；验证正常慢调用/等待配额不会被误判，再启用SAFE_RECOVER；AUTO_EXPIRE只对有可信deadline且硬条件成立的运行启用。模式固定在配置版本，不用任意全局阈值覆盖历史Run。

推荐指标：active_run_oldest_age、stalled_run_count按有限状态/原因分类、reconcile_decision_total、recovery_failure_total、last_successful_reconcile_time。runId只进日志和详情。扫描失败/未运行由外部监控发现，不能让看门狗无声死亡。

页面展示“最后业务进展、当前等待原因、截止时间、恢复次数、对账动作”，并保留人工CANCEL。停滞是诊断标记，不直接新增一个持久Run状态污染主状态机。

## 6. 实施与测试（SR01～12，已全部执行——2026-09-13 收官）

| ID | 测试过程 | 必须断言 |
|---|---|---|
| SR01 | 新影子取证完成→重读Run/报告/通知 | 非活跃、有影子完成审计、零正式报告/发布 |
| SR02 | 用同incident同engine在影子结束后再次准入 | 新Run可按正式规则创建，历史仍可查；非跨engine假通过 |
| SR03 | 可信历史影子分类与普通同名前缀Run混合 | 不凭名称自动豁免/取消，生产缺口仍检测 |
| SR04 | 普通driver在REPORTING时崩溃，租约随后到期 | 复用现有reclaim恢复，不多造driver、不误EXPIRED |
| SR05 | 制造Run REPORTING但driver缺失/终态 | 对账识别孤立，告警并按材料完整性恢复或人工接管 |
| SR06 | 报告材料提交后、正式报告事务前杀进程 | 唯一finalizer恢复，报告/发布/outbox不重复 |
| SR07 | 模型/工具慢调用仍有有效租约、deadline未到 | 软停滞可警示但不误终止，不把heartbeat当新证据 |
| SR08 | finishTask、Cancel、到期对账同时提交 | 状态CAS/锁语义仅一胜，终态不覆盖，新incident指针不误清 |
| SR09 | deadline到期仍有外部在飞动作，之后迟到 | 停后无新动作，迟到只审计，UNKNOWN预算不返成可重花 |
| SR10 | 重复扫描、两个reconciler并行、恢复失败反复 | 唯一恢复任务/事件、次数有界，对账本身可恢复 |
| SR11 | QUEUED超期与旧Run无deadline | 状态机合法；无deadline仅警示，不凭updated_at误过期 |
| SR12 | 看门狗扫描报错、页面刷新、功能关回ALERT_ONLY | 外部可发现扫描失效，页面真实状态，关闭自动动作不删历史 |

顺序：先补失败用例和Run分类→修新影子收口→只读对账检测→确认报告持久材料→恢复任务接线→真实PG竞争→灰度过期。复用CL-02/OR-07/OR-09排期；不因这张缺陷卡重做整个Agent架构。

完成定义：旧材料未被误改（两历史 195 Run 原样未动、purpose 全 NULL 无误分类）；新影子不再留活跃位（SR01/SR02 实证 SHADOW_EVIDENCE_ONLY 收口释放槽位、V25 索引保留）；生产孤立Run能被发现并有确定恢复/过期出口（195 ALERT_ONLY 实测两历史 Run 持续产出 ORPHAN_MATERIALS_INCOMPLETE 决策零写入）；普通慢任务不误杀（SR04/SR07）；恢复与终止的账本、报告、通知及incident指针一致（SR06 唯一 finalizer 恰一发布、SR08/09 迟到拒绝零写入+指针保护）。**实施收官（2026-09-13）**：V108 迁移+影子收口链+RunReconciler（三档模式）+ReportFinalizeExecutor 重放收尾落码；UT 21/21（Am4ShadowTriggerTest 7 / RunReconcilerTest 11 / ReportFinalizeRecoveryTest 3）+ 全量回归 1874/1874 三轮 + 195 真 PG PostgresRunReconcilerIT 4/4 + 195 部署（health 200 / ERROR=0 / reconciler ALERT_ONLY 活跃）。真库独有发现：FK 顺序、timestamptz 微秒、finishTask×expireRun AB-BA 死锁（lockNonTerminalByRunIdForUpdate 统一 task→run 锁序修复）、ck 拒空指纹对象（finalize 重放 samplingFingerprint=null）。收官窗新卡 BA-136（巡逻热旋转：无条件 30s 拍修复，45 条/s→2 条/30s 实证）、BA-137（部署脚本 rsync --delete 误删 195 .env/模块，现场已完全恢复）。运营口径：ALERT_ONLY 观察误报后再启 SAFE_RECOVER/AUTO_EXPIRE；历史 Run 清理仅走有审计的定向 CANCEL。
