# BUGLOG 与 B2 测试审查：统一收口技术方案 v1

2026-09-13。本次一次性收录所读 BUGLOG、CL/OP/OR 台账、B2/RR、MC/FO、SR/WC、A0 补充方案范围内的已知问题与剩余验收；不是全仓漏洞不存在的证明。本轮无部署、生产注入、机器时钟修改、线上取消或凭据操作。

## 1. 结论与证据范围

当前需要从“增加能力”转向“修正验收、验证真实语义、补齐运行证据”。已有实现不少，不能再按旧聊天重复建设：看门狗公平分页、Task→Run 锁序、命令事务、终态任务清理、粘性取消均已出现；日志 severity 和工具失败分类也在本轮工作区出现新实现。

最优先的问题是：**B2 验证器存在假绿、跨轮反证验收为空输入、发布 loser 没有绑定本 Run、取消计数不等于执行真正停止，以及 Claim 支持判定尚未完整贯通。**这几项不解决，继续跑出更多 SUITE PASS 也不能增加可靠性结论。

本轮基线 HEAD 为 `8f25e05261b8ce2de1ce871e0afa747713d3c1ea`，共享工作区持续存在未提交变更。所审文件摘要见 [源码与文档 SHA-256](b2-review-source-hashes.json)，不将 HEAD 当作完整部署身份。

证据清单：

- [BUGLOG 逐项索引](告警-BUGLOG逐项复核索引-20260913.md)：141 条记录、140 个唯一编号，BA-22 重复。保留所有原状态，没有批量重新关单。
- [B2 验证器离线复现](b2-verifier-audit-results.json)：四组受控输入；直接执行归档验证器，仅替换其数据库输出与临时文件操作，无连接 195。
- [复现脚本](review-tools/b2-verifier-audit.py)：空父集 PASS；digest 错误 FAIL 但 exit 0；反证丢失 FAIL 但 exit 0；父项顺序反转 PASS。
- [本轮定向测试](b2-buglog-review-targeted-tests.log)：46 项、0 失败/错误/跳过，包含 RunReconcilerFairnessTest、CommandServiceTest、ToolGatewayTest、WorkingMemoryTest、LokiAggregateExecutorTest。不是本轮真实 PG 或端到端验证。
- 对照材料：完成台账、B2 RR21～28 设计、B2 实际归档脚本、MC01～36、FO01～60、总方案 RR01～48、看门狗 v2、A0 补充。旧证据中的目标环境结果按“记录报告”引用，不冒充本轮复跑。

## 2. BUGLOG 怎么收口，哪些不该重复修

| 条目/主题 | 本次裁定 | 后续动作 |
|---|---|---|
| BA-01～107 的历史记录 | 已关闭项保留历史证据，不按旧症状重开；其中 BA-20/21/54 仍有开放标记 | BA-20 核当前 demo 基线；BA-21 明确 severity 是否参与 incident 身份；BA-54 核资源降级动作是否仍缺，归 U15。 |
| BA-108/109/110/112/113 | 标记仍含待真窗复核，后续成功案例不能自动证明每条失败分支 | 按各条触发条件映射确定性回归和适用构建，证据齐才补状态。 |
| BA-114/117/118/119/121～125 | 台账有修复/目标环境证据 | 不重复实现；对 BA-123 “jar 未含修复”这样的限定重新核实际镜像。 |
| BA-115 及定价相关 | 不靠 TRUE Claim 成功替代费用正确 | 缺定价、币种、usage 缺失按 FO53 验收，不允许 NULL→0。 |
| BA-116/134 | 同 episode 发布一次与探针假设问题同族 | U02 统一首次发布/重复发布两套判据，禁止仅搜 loser 词放行。 |
| BA-120 | 指定模型真实环境复核受密钥/可用性限制 | 离线协议与预算测试先完成；该模型不启用可 DEFERRED，已启用则保留 BLOCKED，不能默认算通过。 |
| BA-126/127/136 | Run 对账/影子收口/循环节律已修复记录在案，源码有对应实现 | 按现行 WC 补物理停止与资源对账，不再写“没有看门狗”。 |
| BA-128～132 | API 错误、授权与压缩状态修复已有记录 | 保留最小权限 PG 回归，不能仅测试超管。 |
| BA-133/135 | BUGLOG 仍待修，但工作区已有 severity 和 classifyFailure | 标为“新实现出现，待版本绑定与验收”，不要按旧清单重新写。 |
| BA-137 | 现场恢复已完成，但同类删除问题历史再犯 | U12 从物理目录和发布方式消除根因，不能只靠操作纪律关预防项。 |
| BA-138～140 | 有采集失败、取消注册竞态、PG 测试修复记录 | 保留修复；U05/U06 处理剩余生命周期，U01 补随机 UUID 的真实排序覆盖。 |

所有未在上表单独展开的记录均保留在逐项索引，按原关闭状态、适用版本和变更影响决定回归，不默认成为新开发任务。BA-22 不直接重编号历史引用；新增稳定记录键（例如编号+首登记日期/原行锚），保留旧编号别名。

台账需修正三个口径：

1. CL-06 拆为“记忆结构 VERIFIED_TARGET”与“非空反证跨轮可达待验”，不能用一个整卡 VERIFIED_TARGET 掩盖明确残项。
2. OR-02 有 HOST2 部署记录，旧的“外部探针从未部署”说法已过时；但已部署不等于 RR05～08 值班通知链验证通过。本轮未直接探测 HOST2。
3. 台账顶端部署时间含 `2026-09-13T22:18Z`，附件另述 V101 为 09-12 22:18Z；必须用构建/启动证据校正 UTC 与本地时间，不能自行猜一天。CONFIGURED 等非枚举状态统一映射为 IMPLEMENTED_NOT_VERIFIED 加 capability 字段。

## 3. 统一实施清单：20 项，不再另起零散优化线

U 编号仅是本次执行分组，仍归既有 CL/OP/OR/BA。P1 表示阻断对应生产级声明；P2 是必做运行收口；条件项未启用可明确推迟。

| 项 | 优先级 | 改动位置/归属 | 应交付的变化 | 验收映射 |
|---|---|---|---|---|
| U01 验证器和 runner 可信 | P1 | B2 verify/driver/retry；OR-11 | 退出码、结构化结果、查询错误、空集及变异测试全闭环 | N01～08 |
| U02 发布仲裁精确关联 | P1 | B2/A0 phase7；BA-116/134 | winner 与 loser 按本 Run/报告/实际仲裁键核验，回执另验 | N09～12、RR27/28 |
| U03 记忆父链及锚点 | P1 | B2 phase9、WorkingMemory IT；CL-06 | 按 task/分支作用域和已提交父指针检查，不强制 checkpoint 连号 | N13～17、MC06/08 |
| U04 非空跨轮与动态角色 | P1 | ContextAssembler、B2 场景；CL-04/06、OP-06 | 非空反证先落库，再委派、恢复、实际入模；动态路由另评 | N18～21、MC05/07、FO41/42 |
| U05 在飞生命周期与静默 | P1 | ToolGateway/InFlightToolCancels、RunReconciler | 等待结束与执行体退出分离，修正 in-flight 及静默指标 | N22～25、WC-T20/21 |
| U06 跨进程停止与期限 | P1 | ExecutionControl/模型工具 Gateway | 等待中持久状态探测、最早截止、分类停止；注册表回收有界 | N26～28、WC-T14/17/18/23 |
| U07 工具语义收口 | P1 | LokiAggregateExecutor/PrimaryGatewayToolPort | 验已新增 severity/错误分类，并补装配契约和缺源口径 | N29/30、AS01～05、RR23 |
| U08 根因证据支持 | P1 | PrimaryClaimAdmission/Projector | 支持关系贯通、缺载荷/无效定位不能放行；计数器类型化 | N31～34、AS06～08 |
| U09 依赖故障有界 | P1 | RR21～24、网关/账本 | 分层超时、物理尝试计数、PG 零写时零新外联 | RR21～24 修订版 |
| U10 告警生命周期与积压 | P1 | RR25/26、入口/队列/通知 | 新 episode 身份正确、持久 ACK、有限负载和公平性 | RR25/26 修订版 |
| U11 评测结论与版本冻结 | P1 | eval runner/manifest；OP-02/09 | 固定次数全保留；工程通过与模型质量分开；门禁不可追改 | N35/36、FO51～54 |
| U12 发布、配置与演练恢复 | P1 | deploy/override/Drill；BA-137、OR-10/11 | 源码与秘密分离、实际配置对拍、租约与条件回滚 | N37/38、RR37～44 |
| U13 权限与外部输入 | P1（启用面） | Security/MCP/SSE；OR-04/05 | 补 RR14～20；最小权限、撤权、提示词注入及脱敏 | RR14～20、FO30 |
| U14 外部探针和备份恢复 | P2 必做 | HOST2/backup/restore；OR-02/03 | 核部署现状、独立探测失联、恢复到新库、RPO/RTO 实测 | RR05～12 |
| U15 资源、健康与长稳 | P2 必做 | metrics/readiness/limits；BA-54、OR-08/09 | 队列/连接/磁盘有界、无数据告警、资源压力退避不挤掉控制面 | RR29～36、N39 |
| U16 Prompt/Skill/MCP/RAG 版本 | P1（已启用面） | release/binding/热更新；CL-05、OP-08/09 | 发布版本、Run 实际采用、重启/撤权/回滚资格一致 | FO40/47～50/56/58 |
| U17 摘要与压缩 | 条件启用门 | CL-07/08 | 预留/在飞/UNKNOWN/校验/消费全验，原文对照保反证 | MC09～20/34、FO53 |
| U18 Skill 学习与质量反馈 | 条件启用门 | CL-09、OP-01～04 | 候选→审核→独立评测→发布→实际消费，禁止自动用自身结论当真值 | FO01～30/49/60 |
| U19 前端与可解释运行 | P2 必做 | CL-10、OP-05；Run/Eval/Drill 页面 | 未决/取消/版本/卡点/测试分母可见，布局与操作走查 | FO31～40/59、N40 |
| U20 台账、交接与退出门 | P2 必做 | BUGLOG/完成台账/runbook；OR-01/12 | 证据作用域、重复编号、失效资格、责任与剩余门一表管理 | RR01～04/45～48 |

## 4. U01～U04：B2 不能仅“修到当前一跑全绿”

### 4.1 验证器的确定问题与修法

归档 `b2-cl06-verify.py` 尾部只打印 ASSERT-OVERALL，没有 `sys.exit(1)`。离线复现表明，反证丢失或 digest 错误的运行仍正常返回 0。父项顺序检查只有“正确时打印 PASS”，错误时没有将 ok 置 false。父集合全空时集合包含关系自然成立，不能证明反证保留。

不修改既有证据包里的脚本。将其复制到正式 `e2e-脚本` 目录为 v2，再修：

1. 数据库调用用结构化 JSON 输出，检查 returncode 与 stderr，解析错误就是 FAIL/ERROR；不按分隔符静默丢弃不合形行。
2. 从受控输入信封解析 working_memory，替代只支持平面对象的正则。保留嵌套、转义、缺失字段测试；完整 prompt 文本 digest 与解析对象 digest 不混算。
3. 检查明确的非空 witness：至少一条预先登记反证 ID 在跨轮前持久化、跨轮后真实输入仍可达。空集合返回 INCONCLUSIVE，不能算语义通过。
4. 不满足父项在前契约时明确 FAIL；若新策略不要求顺序，版本化删除该契约，不能只靠日志装作验证。
5. 写统一 `result.json`：suiteVersion、runId、status、assertions、coverage、configDigest、evidenceRefs；PASS=0，FAIL=1，ERROR=2，INCONCLUSIVE=3（建议新 runner 约定）。stdout 仅方便阅读。
6. suite 最终 PASS 必须依赖该验证器成功；不能 driver 先报 PASS，后面的 verify 另跑、结果不参与整体判定。

`b2-cl06-retry.sh` 也要改：当前靠 grep SUITE PASS 决定 break，全失败后仍 echo DONE；从全局目录找最新 run-id，会在共享环境串案。调用退出码与 result.json 双校验，当前 attempt 单独目录，Run ID 由本次 driver 明确返回，不使用 `ls -t`。构建/compose 管道也检查原命令返回码，不能让 tail 成功吞掉前面的失败。

工程调试允许重试，但保存全部次数；质量验收固定 trial 数并计入所有有效运行，不能“最多 5 次，过一次即算稳定”。

### 4.2 phase7 存在跨 Run 假绿

B2 driver 当前在本 Run 没有 publication 时，搜索整个容器近 30 分钟的任意 `report_publication_loser`。其他 Run 的 loser 足以让当前断链 Run 被放行。

winner：按当前 Run 的 reportId 查询 publication、outbox，再查测试接收器持久回执。loser：查询本报告的仲裁结果、对应 winnerReportId、精确 incident/engine/generation 等**实际唯一键**；验证 winner 与当前报告关系，并断言 loser 零重复外发。字段以当前迁移为准，不从注释猜列名。

如果产品尚无持久 loser 关系，新增最小审计字段/事件来保存仲裁键与 winnerReportId；不能拿不带关联 ID 的日志充当数据库事实。旧 A0 与 B2 共用这段 checker，避免同一缺陷多份脚本各修一半。

### 4.3 phase9 仍有两类错误

**错误一：要求父 checkpoint revision=子 revision−1。**DELEGATE、FINAL、配置切换或其他检查点更新可能消耗 revision 而不写记忆，因此不止第一行可能跳号。父链应指向同任务/分支的前一条已提交记忆；revision 严格递增但允许间隙。使用 parent_memory_id 与按同作用域排序的 LAG(id) 对拍，另外检查跨 Run/Task 错连、孤儿和循环。

**错误二：锚点查询 INNER JOIN。**checkpoint.memory_id 为空或指向缺失记录时，行从 JOIN 消失，违规 count=0 可假绿。先要求期望 checkpoint 行数，再 LEFT JOIN，显式检查 `memory_id IS NULL`、`m.id IS NULL`、归属和 digest 的 NULL-safe 不等。不能只写 `<>`，因为 NULL 比较不会成为 true。

作用域按实际生产身份至少包含 runId/taskId；configEpoch 是否允许父链跨越需按明确热切策略处理，不能把本 Run 所有角色的最大 revision 当一个全局最新记忆。

### 4.4 非空跨轮并不一定需要两个委派批

附件说非空跨轮需要多委派批，这是“第一步强制 delegate”脚本造成的限制，不是产品必须如此。最小可重复结构是：

`主任务取证并提交非空反证 → delegate → 子回执 → 下一轮主 prompt 消费原反证`。

先用受控模型响应测试 Harness 顺序，再用真实模型冻结场景测试语义；强制 delegate 的场景只证明能力接线，不能代表动态路由质量。再补两批委派、进程重启、热切与取消交错，确认早期反证不因生命周期变化丢失。原始四槽不能一概永久求并集：open_gaps 解决后应按契约归档/标记解决；反证仍保留来源，假设可以修订。先区分“历史可达”与“当前待处理集合”。

## 5. U05～U08：新代码还要补哪些边界

### 5.1 工具等待结束不是执行体退出

`ToolGateway.executeWithDeadline` 在调用方 finally 里 unregister；Future 被取消后 get 可立即抛出，但底层工具可能忽略中断继续运行。因而 `InFlightToolCancels.inflightCount=0` 不能直接代表 LOCAL_QUIESCED。`RunReconciler.cleanupRun` 以任务状态清理时间上报 cancelToQuiesce，也只是逻辑清理时间。

修改为轻量执行句柄状态：QUEUED→RUNNING→EXITED，另记 stopRequested；等待方 finished 与执行体 finally 的 exited 分开。执行体 finally 才证明物理退出。尚未开始即取消由 CAS QUEUED→CANCELLED_BEFORE_START 收口，解决永远等不到执行体 finally 的情况。保持已经验证的 owner/epoch 释放条件；UNKNOWN 费用仍按账本处理。

注册表还需两项：stopCancelled 集合当前没有清理路径，长期取消会增长；register 与 unregister 的 computeIfAbsent/add、empty/remove 分离，存在取到集合后映射被移除的竞态。用每 Run 原子 compute 操作成员集合，Future cancel 在取出快照后执行；取消墓碑只有在执行体全退出、无注册中动作且持久终态复验后回收。缓存回收后新注册仍须查持久事实，不能用 TTL 换丢取消。

将指标拆为 `cancel_to_task_cleanup` 与 `cancel_to_execution_exit`；远端未知单列。单进程注册表读数只标本实例，不能作为多实例全局零在飞。

### 5.2 期限和停止类型仍要从入口贯通到等待

ToolGateway 当前整段 future.get 等到期限，没有在该等待段内周期调用持久控制探针；缺本地通知或由另一实例接取消时，需要明确短周期可中断等待或共享受限探针。复用 ExecutionControl，不再建一套状态机。

到期边界要记住谁是最早期限：Run/task 截止导致的 TimeoutException 应成为停止原因，而非统一 TIMEOUT_RETRYABLE。InterruptedException 也根据取消、失租、shutdown 区分；同 Run 不再 fallback。模型退避已有 callback 接线，HTTP 阻塞段仍须用可控慢响应测试证明，不能由回调代码存在推断在飞 HTTP 已停止。

### 5.3 工具新实现应收官，不再重复写

LokiAggregateExecutor 已增加 severity/filter/coverage；PrimaryGatewayToolPort 已增加 classifyFailure。当前优先补实测：ALL 计数不当错误、ERROR 标签缺失不当健康、unknown reason 不自动重试、预算与 DoomLoop 零外联、schema/allowlist/delegate 完整装配。将 BA-133/135 的状态与构建证据对齐后再关单。

### 5.4 Claim 准入在改，但授予支持仍有漏洞

本轮新 `PrimaryClaimAdmission` 已有 SUPPORTS/REFUTES/CONTEXT，不能说引用作用面完全不存在。但 `verdictFor` 的当前路径在 evidenceRow=null 时保留模型提议角色；locator 不可解析时只删 locator，仍可能保留 SUPPORTS。`forcedContextReason` 只覆盖 `_total` 后缀，不覆盖附件实际用的 RPC histogram `_count` 等累计面。Projector 仍可见按来源数设置一致性、按 kind 设置状态的旧逻辑，需要全链对拍。

修法：无法读取可信载荷、身份不匹配、定位失败或窗口不匹配，都不能授予 SUPPORTS；按明确策略降 CONTEXT/拒绝并留原因。用工具输出的 metricType/temporality/window/operator 元数据判断计数性质，不只靠名字；结构字段存在也不证明自然语言因果成立。Projector 只消费已验证支持/反证结果，保留类型版本，不把所有引用来源计为支持来源。复杂语义可用受限评审辅助，但无依据保持未决。

## 6. RR21～28 设计必须修订后再执行

以下替代原设计中的注入方法与断言，不在本轮执行。优先专用隔离栈/测试代理，不暂停共享 195 数据库、不修改共享主机时钟。

| RR | 原设计问题 | 修改后的执行过程与通过条件 |
|---|---|---|
| RR21 PG 不可写 | `default_read_only` 参数名不对；PG 无写时还要求产生 FAILED 账行不自洽；pause DB 扩大影响面 | 隔离 PG/专用应用实例设置 `default_transaction_read_only` 或受控拒写代理；先验证非临时测试表写入确被拒。发唯一告警，验证持久化失败不返回已接收，模型/工具新发送计数为零。DB 不可写时允许无法落失败行，以测试接收器计数+日志记录证据，恢复后同幂等键重投一次。另测提交成功但 ACK 丢失。 |
| RR22 429/超时 | 调配额可能测到鉴权/免费额度错误，不一定真实 429；数据库行数不能代表物理发送数 | 受控 HTTP 端点分别返回 429+Retry-After、慢首字节、慢 body、响应丢失。每层重试可观测，由端点记录物理请求，与动作/attempt/budget 对拍；最后一个失败后不偷偷切未授权路由。真实供应商小样本兼容测试另列。 |
| RR23 日志源失效 | NO_DATA 和 UNAVAILABLE 混合；硬要求双源缺一就不能 ROOT_CAUSE 不普适 | 注入源不可达、成功空、截断包、超时四案。不可用不能当零日志；无伪造日志引用；其他权威证据可支持有限结论，但日志相关结论必须标缺口。恢复重试有界，不强制所有失败都“最后成功”。 |
| RR24 TLS/DNS/时钟/模型 | 把四种错误合成一次；容器 date -s 不是安全局部时钟模拟；privilege 不足不是语义 N/A | 拆模型协议不兼容、TLS 证书错误、DNS 不可解析、应用 Clock 偏移四案。Clock 通过隔离实例注入，租约以 DB 时间/epoch 判断；基础设施时钟实验需专用 VM。记录每案分类、零越权 fallback、无旧 owner 覆盖，未能测为 NOT_RUN/BLOCKED。 |
| RR25 episode | 改 summary 并不必然产生新 episode | 同规范化身份/startsAt firing 重投→对应 resolved→之后新的 startsAt firing；另测乱序 resolved、重复 resolved、仅 summary/severity 变化。按项目 incidentKey/episode 规则断言 generation，不能硬编码“换文案就是新单”。 |
| RR26 积压 | Locust 用户数不等于告警入队速率；flagd 全开混淆诊断；4xx/202 简化口径错误 | 独立告警生成器控制事件速率/去重比例，受控慢工具使 worker 变慢；先基线再小阶梯。202 只有履行持久接收承诺才合理；明确限流状态、队列深度和最老年龄。用请求时延样本计算 API p95，Gatus 单点快照不能证明 p95。 |
| RR27 响应丢失 | 仅模拟 timeout 不证明接收方已产生效果 | 接收器先持久化操作与幂等键，再断响应；重试使用同一逻辑投递键。核多物理请求对应一次副作用；不支持幂等时记录可能重复，不承诺 exactly-once。 |
| RR28 失败后恢复 | “重投带新 id”可能破坏幂等；attempted_at 排序不证明业务顺序 | 同一逻辑通知重试保留幂等身份、每次 attempt 新身份；不同 firing/resolved 通知各有逻辑身份。断言同 incident/episode 的约定交付/替代规则及迟到通知策略，恢复后不让旧 firing 冒充新故障。 |

参数依据：[PostgreSQL 客户端默认值](https://www.postgresql.org/docs/current/runtime-config-client.html#GUC-DEFAULT-TRANSACTION-READ-ONLY) 指定 `default_transaction_read_only`。它是新事务的默认值，不是不可绕过的全局拒写权限，因此测试必须验证应用实际写入被拒。Linux time namespace 不虚拟化 CLOCK_REALTIME，不能把容器 `date -s` 当作局部偏移实验，参见 [Linux time_namespaces](https://man7.org/linux/man-pages/man7/time_namespaces.7.html)。

每案恢复规则：记录 before 配置摘要与目标身份；注入动作有期限、owner 和排他窗口；恢复只在当前值仍是本案设置时执行。浏览器/SSH 断开不是清理完成，独立恢复者须核验故障解除。每项通过后再进入下一故障，不在一个大窗口同时翻多种旋钮。

## 7. 40 个补充测试规格，统一补进现有目录

以下都是增量规格，除 N01～04 的现行脚本离线复现外尚未执行；通过标准描述修复后的目标。与 MC/FO/RR/WC 重合的证据复用，不把 46 单测、历史 58、564 文件相加宣传覆盖数量。

| ID | 操作/前置 | 修复后的通过标准 |
|---|---|---|
| N01 | 跨轮前四槽全空 | 结构结果可过，语义覆盖 INCONCLUSIVE，不能整体 FULL PASS。 |
| N02 | 修改 prompt 字节但保留原 digest | FAIL 且非零退出；suite 发布资格拒绝。 |
| N03 | 删除跨轮后指定反证 | FAIL 且非零退出，指出丢失 witness。 |
| N04 | 打乱要求保留的父项顺序 | 按已冻结顺序契约失败，不能只不打印 PASS。 |
| N05 | SQL 失败/非 JSON/缺列/空输出 | ERROR，保留脱敏 stderr，不能静默少读记录。 |
| N06 | 5 次运行全部失败 | wrapper 非零退出，保留 5 次失败，不返回最近别人的 Run。 |
| N07 | 另一个进程创建更新 run-id 文件 | 本案始终使用 driver 返回的 Run ID。 |
| N08 | 嵌套记忆、转义字符串、缺字段 | 正确解析或显式错误，不用正则误抽子对象。 |
| N09 | 本 Run 无发布，另一 Run 有 loser 日志 | 本案 FAIL，不借别案证据。 |
| N10 | 真 loser 配同仲裁键的有效 winner | loser 关系通过且零重复外发。 |
| N11 | loser 声称 winner，但 winner 缺失/异 episode | FAIL，报告发布缺口。 |
| N12 | 首次发布 outbox 在但接收回执缺失 | 仅落库子门通过，送达门不得成功。 |
| N13 | 记忆 rev1→rev3，中间仅 checkpoint 更新 | 合法父链通过，允许非记忆修订间隙。 |
| N14 | cp.memory_id NULL/缺行/digest NULL | 显式失败，不由 JOIN/NULL 三值逻辑放行。 |
| N15 | 父指向别的 task/run | 拒绝跨作用域父链。 |
| N16 | 同 Run 两角色各有记忆链 | 各按自己的链和策略锚定，不比较全 Run 最大 revision。 |
| N17 | 父环/跳过已提交父版/损坏载荷 digest | 拒绝并定位损坏边。 |
| N18 | 非空反证先提交，再单批委派 | 后轮真实 prompt 或受控回读可达 witness。 |
| N19 | 记忆提交后、下一模型前杀隔离进程 | 接管恢复相同身份/反证，零预算重置。 |
| N20 | 解决一个 open_gap 后再次组装 | 按契约不再冒充未解决，历史来源仍可追溯。 |
| N21 | 简单/复杂留出告警固定 trials | 分别评估直查和动态委派；强制 delegate 不计路由正确率。 |
| N22 | 工具体忽略中断，调用方已经退出 | in-flight 仍表示执行未退出，不能报告 LOCAL_QUIESCED。 |
| N23 | 已排队尚未开始即取消 | CANCELLED_BEFORE_START 收敛，无永等 finally、零触网。 |
| N24 | register/unregister/取消三方 barrier 交错 | 不丢在飞句柄或取消，映射回收准确。 |
| N25 | 大量终态 Run 连续取消与清理 | 墓碑/句柄有界回收，旧取消不误作用新 Run。 |
| N26 | A 实例取消、B 阻塞等待 | B 通过持久探针在约定时限停止；不依赖 A 的本地表。 |
| N27 | Run 期限先于 tool timeout | 停止分类是 Run deadline，无工具重试/fallback。 |
| N28 | PG 失联时等待/新动作准入 | 新动作不无限放行；停止/UNKNOWN 和资源对账如实。 |
| N29 | ALL/ERROR 空窗、缺 severity 标签 | count 与 coverage 明确，源故障不变零错误。 |
| N30 | 预算/DoomLoop/未知工具/装配缺失 | 分别正确分类，零越权发送、纠错次数有界。 |
| N31 | ref 在允许集合但载荷丢失 | 不保留未经确认的 SUPPORTS。 |
| N32 | locator 不存在或数组索引越界 | 明确降级/拒绝，不仅删定位继续支持。 |
| N33 | `_total`、histogram `_count`、名称非典型 counter | 使用类型/时间语义，不以单后缀猜窗口增量。 |
| N34 | SUPPORTS 一条+CONTEXT 一条+真反证 | 不冒认多源支持，反证不被覆盖。 |
| N35 | 候选 5 次中仅 1 次成功 | 报告实际分母、失败和不确定性，不宣称稳定通过。 |
| N36 | 测后改阈值/prompt/schema/模型版本 | 原资格失效，创建新版本实验，不改写历史判定。 |
| N37 | 注入后有人合法改同一配置再恢复 | 条件回滚拒绝覆盖新值并明确冲突。 |
| N38 | 源码同步与容器重建 | 外置秘密不受同步删除；实际配置与预期 digest 一致。 |
| N39 | 采集停止、内存压力、慢 worker | no-data 可见，取消/探针资源不被评测耗尽。 |
| N40 | 1366×768、200%缩放下查失败评测与停止中的 Run | 核心状态/原因/下一步可见，无假零费用/假静默，截图与 API 一致。 |

对照层次：工程验证用确定性响应+真 PostgreSQL+受控 HTTP；真实模型验证用冻结场景及版本；模型质量用不参与调试的 holdout 和固定 trial。三种结果分列，不互相替代。

## 8. 其余运行与产品面怎么改，防止范围遗漏

**U13 权限。**复用现有 SecurityConfig 和角色矩阵。RR13 已有记录不重写认证层；补机器凭据撤旧、缺参启动、SSE 过期票/跨 Run 游标、MCP 地址与撤权、日志/RAG 注入的零实际越权。测试凭据样本不得进入正式证据包。

**U14 探针/备份。**HOST2 按台账为已配置；验证 195 不可达时通知链仍活着，并验证探针自身失联能被发现。备份只看生成成功不够，恢复到隔离空库与工件目录，重算依赖指纹并读一条真实报告/版本绑定；实测恢复耗时与数据缺口。当前展示规模先把现有备份恢复做实，不默认升级复杂 HA。

**U15 资源。**BA-54 先核现有资源指标与规则，再决定自动暂停低优先级评测/领取的最小动作。恢复需滞回，保留取消、健康和通知资源；压力不能靠把共享宿主内存压到 OOM 来测。长稳观测线程/连接/注册表/磁盘回落；不要求百万告警吞吐。

**U16 版本和热更新。**每个新 Run 绑定 prompt/skill/tool/MCP schema/RAG catalog/model/策略组合摘要；运行中热切只在安全点切换到经资格验证的新 epoch，旧在飞结果不能写新版本。Role descriptor 新增角色时带输入输出、工具权限、预算和兼容策略；不是每个角色必跑。版本页区分“候选可用、release 已采用、本 Run 已消费”。

**U17 压缩。**当前默认关闭的消费面继续关，直到原文/无压缩/受验证摘要三臂固定样本对照通过。保留摘要来源、反证、未决和权属；压缩自身失败/UNKNOWN/预算也要闭环。台账通过不等于摘要实际消费效果通过。

**U18 学习。**报告反馈→候选 Skill/RAG/案例→人工审查→独立评测→授权发布→新 Run 消费。当前 LLM_CURATE 若仍 501，就展示未启用，不以模板提炼冒充自进化。评测集与从同一报告生成的候选分开，防循环自证。

**U19 页面。**Run 页上部只放终态/未决、核心结论、下一步、版本；中部按需展开主子任务与证据；账本/原文放明细。Eval 页首屏显示完整分母、失败/未知、成本覆盖率和候选相对基线差异，再下钻单例；避免所有卡片平铺。Drill 页暴露模板、范围、时长、恢复状态，凭据留后端。按 FO31～40 实际浏览器走查，npm build 通过不算布局通过。

**U20 台账。**每项记录 implStatus、testStatus、runtimeStatus、coverageScope、snapshotDigest、时间与 remainingGate。证据未提交并不等于不可查，但必须保存不可变快照身份；单纯“文件很多”不代表覆盖多。后续相关实现变化只使受影响资格失效，不全表重跑。

## 9. 交付顺序与完成标准

| 批 | 内容 | 出口 |
|---|---|---|
| S0 验收可信 | U01/U02/U03/U20 | 新验证器变异测试能稳定拒绝坏输入，旧 PASS 的覆盖边界标清；冻结 schema 与 suite 版本。 |
| S1 正确性闭环 | U04～U08 | 非空跨轮、在飞真实退出、错误语义和 Claim 支持链通过定向测试与隔离 E2E。 |
| S2 依赖与通知 | U09/U10/U12/U13 | 修订 RR21～28 后逐项隔离注入，恢复验证与发布送达全闭环。 |
| S3 运行与体验 | U11/U14/U15/U19/U20 | 固定质量集、探针/恢复/长稳/浏览器证据齐，未决残项可查。 |
| S4 条件能力 | U16～U18 的未启用部分 | 有明确用途才启用；启用前版本、权限、评测和回滚门必过。已启用的部分不能推迟到此批逃避验收。 |

本次不预报没有工时依据的“几天生产级”。每批按上述用例和实现差异估时；并行开发可做，但共享部署/注入窗口必须串行持有环境租约，避免再次污染结果。

生产级收口的判据是：所有已启用能力的 P1 门通过、必做运行验收有可复核证据、条件功能明确状态、没有用空集/别案日志/单次侥幸掩盖未通过项。完成这个有限清单后转入缺陷与观测驱动维护；不会承诺以后再无优化点。
