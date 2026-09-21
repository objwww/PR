# 16-Tool-Runtime工具运行时

> 本系列第十七篇。06 篇讲了"一次工具调用怎么过闸"，本篇把工具当成**生产系统组件**讲全生命周期：工具目录的生产档案、执行器契约、语义约束的双源对齐、MCP 外挂生命周期、新增工具的演进流程——以及第十四部分的十问速答。
> 标注约定同前：【代码事实】/【合理推断】/【设计扩展】/【未确认】。

# 本层要解决的问题

一句话：**让"模型调用一个工具"具备生产系统组件的全部素质——有契约（Schema+语义约束+中文描述三方对齐）、有身份（schemaHash 进 digest）、有档案（账本+拒因明细）、有生命周期（注册 fail-fast/MCP 挂载禁用 drain/TTL）、有演进纪律（词典只增不改、描述与校验逐字对齐）。**

# 先看一个交易告警

> 主 Agent 想查"最近一小时日志量"——它在信封里看到的 `logs.aggregate` 描述是这样的：
> "日志聚合统计——按服务/级别统计冻结窗内日志量与错误占比。**必填 since/until（ISO-8601 或 epoch 秒，窗幅 ≤900 秒），service/severity 可选（severity 限 ALL/ERROR/WARN/INFO）**"。
> 这段话有三个身份：给**人**看（Prompt 工作台工具注册表的"用途"列）、给**模型**看（tool_schemas 的 description）、给**测试**看（与执行器语义校验"逐字对齐"——BA-183）。模型真传了窗幅 3600 秒？执行器按同一份约束拒绝，拒因经反馈环回喂——**描述、校验、反馈三处零漂移**。

# 如果没有这一层会怎样

1. **工具退化成"模型调的函数"**：没有注册表 fail-fast、没有风险声明、没有账本——工具的可用性、安全性、可审计性全部裸奔。
2. **MCP 外挂失控**：外部 server 挂了/被禁了/schema 换了，正在跑的调查拿着旧 schema 继续调用——本项目的防线："TTL 过期后旧 schema 不误用"（M05）、"disable 读侧立即生效……等在飞 drain"（范式 C，McpMountManager.java:30-31）。
3. **描述与行为漂移**：描述说窗幅 900 秒、执行器实际放行 3600 秒——模型学会信任描述就会被拒，学会试错就烧预算。本项目的纪律是"描述尾部约束与各 executor 语义校验面**逐字对齐**"（ToolDescriptionZh.java:12-13）。

---

# 代码是怎么做的

## 0. 先给我一句话

工具运行时 = 一张 fail-fast 的注册表 + 一条七步过闸流水线（06 篇）+ 一批"纯远程调用面"的执行器 + 一部只增不改的中文词典 + 一套 MCP 外挂的三范式生命周期。

## 1. 业务上为什么需要这一层

工具是 Agent 唯一的手——它的可靠性上限就是调查质量的上限。把工具当组件治理（而不是当函数调用），才能回答生产问题："这个工具什么时候会失败？失败了算谁的？换版本了老调查怎么办？外挂的第三方工具怎么信任？"

## 2. 它在整个系统的位置

```mermaid
flowchart TB
    subgraph 静态面（启动期固化）
        REG[ToolRegistry<br/>重名fail-fast·不可变·禁运行时注册]
        CAT[目录/词典<br/>DirectReadToolCatalog+MutationToolCatalog+ToolDescriptionZh]
        DEF[ToolDefinition<br/>schema归一+risk缺省从严R3]
    end
    subgraph 动态面（每次调用）
        GW[ToolGateway 七步过闸·06篇]
        VAL[Validator 形状校验]
        EXE[执行器族·纯远程调用面<br/>语义约束在执行器域内判]
    end
    subgraph 外挂面（MCP）
        MM[McpMountManager<br/>注册快照/TTL重校验/disable drain]
        MI[McpToolInvoker<br/>账本 mcp:server:tool]
    end
    subgraph 运营面
        LED[(账本+拒因明细)]
        MC[(McpMountController 挂载管理)]
    end
    CAT --> REG --> GW
    DEF --> REG
    GW --> VAL --> EXE
    GW --> MI --> MM
    EXE --> LED
    MI --> LED
    MC --> MM
```

## 3. 输入和输出

- **收到**：过闸的 `ToolExecution{validatedArgs, deadlineEpochMillis, resultLimitBytes}`（ToolExecutor.java:17-20）。
- **处理**：执行器做纯远程调用（"不做策略/预算/校验——Gateway 唯一咽喉已做；须尽量在 deadline 前返回"）；语义约束（窗幅/步长/service 白名单）在执行器域内判。
- **产出**：原始响应字节（结果上限由 Gateway 统一裁断）；异常经 Gateway 映射为错误两族。
- **交给谁**：证据面（EvidencePackage）、账本（终态 CAS）、模型（下一步信封摘要）。

## 4. 真实代码入口

- **执行器契约**：`alert/application/tool/ToolExecutor.java`（21 行全文引完——"实现=**纯远程调用面**：不做策略/预算/校验……超时被中断。返回原始响应字节；结果上限由 Gateway 统一裁断"）。
- **中文词典**：`tool/ToolDescriptionZh.java`（BA-176/BA-183）：双职责（人读面+模型调用契约面）；四条口径纪律——时间格式写"ISO-8601 或 epoch 秒"（四 executor 双收对齐）；代码常量封闭值可写（随 executor 改即同步）；**service allowlist 成员是部署配置，不写进词典**（"只写'限部署白名单'，越界拒因经 V88 反馈环回喂"）；"未命中一律回退工具名本身（**绝不瞎编用途**）……词典只增不改"（:13-21）。
- **MCP 生命周期**：`application/mcp/McpMountManager.java`（435 行，EN-06 三范式组合，:22-37）：
  - **注册快照**：AtomicReference 不可变快照无锁读；网络校验在发布锁外、短临界区复核 revision 后原子换针，竞败者关闭未使用候选；"普通失败不替换健康快照"（M02）；
  - **会话策略**：短会话有界 TTL 重校验（M05）；list_changed 只触发候选重检（M04）；"旧快照引用不可变、旧 Run 不被静默换参"；
  - **下线范式 C**：disable 读侧立即生效（"独立生效，不等连通性"M06）→ 等在飞 drain 或 idle 超时 → delete+关连接（"引用释放后恰好关闭一次"M07）；
  - **M11**：stdio 仅运维预装固定命令允许清单——"发布前拒绝，零进程零连接零落库"；
  - **M12**：从 PG 恢复一致 revision（=max generation）；disabled 不复活；重复注册幂等顶替；
  - **"generation 与 driver leaseEpoch 分开保存（不共用，§2.3 竞态语义）"**（:37）——又一个身份分型案例。
- **语义约束双源**：schema pattern/maxLength（形状）+执行器域内判（语义：prometheus 窗幅≤3600s/step≤60s；logs 族窗幅≤900s；severity 封闭集）。

## 5. 核心对象：工具目录逐维生产档案【第十四部分要求，按族给出，代码事实+装配值标注】

**档案维度**：名字 / 用途 / Schema 要点 / 返回 / 超时与上限 / 风险 / 幂等 / 并行 / 权限 / 错误类型

| 族·工具 | 用途（词典原文摘录） | Schema/语义约束 | 风险 | 幂等/并行 |
|---|---|---|---|---|
| prometheus.query | "拉取冻结窗内的指标曲线——确认症状真实存在、幅度与起止点" | query≤512；start/end epoch 秒**窗幅≤3600s**；step≤60s | R0 | 只读幂等；账本幂等键防重复 |
| prometheus.instant / metric_value | "瞬时值（曲线查询的对偶）"/"按指标名+标签直接查，无需手写 PromQL" | time epoch 秒；metric 名 pattern（"服务端拼 selector 防注入"） | R0 | 同上 |
| prometheus.catalog / label_values / rules | "有哪些指标可查"/"标签全部取值"/"先看告警怎么定义再按表达式取证" | service 必填（"服务器拼选择器，模型零语法面"） | R0 | 同上 |
| logs.query / logs.aggregate | "找第一条异常与错误模式"/"统计日志量与错误占比——先聚合后读行，确定性零 LLM" | since/until **窗幅≤900s**；severity 封闭集 ALL/ERROR/WARN/INFO | R0（敏感读面按声明可 R1） | 同上 |
| change.query / change.diff | "告警前有没有人改过东西"/"这段时间具体改了什么（窗内变更+窗前基线）" | since/until/service；缺省 control-app | R0 | 同上 |
| alert.history | "触发/恢复时间线（是否复发/抖动）" | alertname/fingerprint≤256+窗 | R0 | 同上 |
| docker.ps / docker.inspect | 容器状态/详情 | container pattern+≤64 | R0 | 只读；**"env 只回键名不回值"** |
| runbook.catalog / fetch / rca_history.search | 登记条目元数据（列表零正文）/正文（双 digest 版本面）/历史判例（结构化过滤非语义相似） | runbook_id pattern "天然拒绝路径分隔符与 URL 语法" | R0 | 只读 |
| code.search / code.read | 部署版本源码行命中/行窗 | service 必在宿主绑定集；"字面量匹配，无正则面"；路径禁反斜杠+'..' 段沙箱复判 | R0 | 只读 |
| **service.restart / service.rollback** | 副作用工具（词典同格式登记） | args["service"]=请求资源键 | **R3** | **非幂等→VALIDATE_ONLY 零执行→04 篇审批链** |

通用维度（全族一致）：**超时**=ToolDefinition.timeoutMillis（构造期强制为正；装配值【未确认】）；**结果上限**=resultLimitBytes（双层裁断）；**重试**=工具自身不重试（模型计步重驱是上层的决策，15 篇）；**并行**=单步单工具（无并行）；**权限**=ToolPolicy 白名单双闸+AgentProfile allowlist；**错误**=两族封闭枚举。

## 6. 一条真实调用链（"新增一个工具"的演进流程——设计视角走真实代码路径）

```
① 定义：DirectReadToolCatalog（或独立 Catalog）写 ToolDefinition 工厂
   ——schema properties/required/additionalProperties=false 归一、risk、超时、上限
② 实现：infrastructure/tool/ 新执行器 implements ToolExecutor
   ——纯远程调用面+域内语义校验（INVALID_ARGS 分支与词典约束逐字对齐）
③ 描述：ToolDescriptionZh 补条目（"新工具注册时同步补条目，词典只增不改"）
   ——用途一句话+调用约束（必填/时间格式/窗幅），人读模型读同源
④ 注册：装配面把 definition+executor 进 ToolRegistry（重名/空表启动即炸）
⑤ 白名单：装配进相应 AgentProfile.toolAllowlist（主任务/子角色各自的集）
⑥ 事件与账本：无需额外接线（账本/意图面自动覆盖——digest/幂等键通用）
⑦ 评测：进 eval 的工具调用统计（逻辑/物理调用/新证据率——CallTelemetry 口径）
   禁止项：运行时注册/网络下载插件（结构禁止+ControlArchitectureTest 断言）
```

## 7. 状态机

工具运行时的"状态机"是 **MCP server 的三态**（McpMountManager.ServerState：MOUNTED/DISABLED/FAILED）+ 账本调用状态机（06 篇第 7 节）：
- MOUNTED→DISABLED：管理面 disable（读侧立即生效）；
- DISABLED→MOUNTED：enable（重新走网络校验）；
- FAILED→MOUNTED：修复后重挂；
- M12："disabled 不复活（dormant 无连接）"——重启后禁用状态保持。
- 调用面：每次调用经 `ensureFresh`（TTL 过期→重校验→"旧 schema 不误用"）→资格领取（"与 disable 串行"M06）→在飞计数支撑 drain（M07）。

## 8. 正常业务流程（一次 MCP 外挂工具调用的完整生命周期）

| # | 环节 | 代码 | 保证 |
|---|---|---|---|
| 1 | 运维注册 server | McpMountController→发布锁外网络校验 | "零进程零连接零落库"直到校验过 |
| 2 | 原子换针入快照 | RegistrySnapshot.with | 读侧无锁；竞败候选被关闭 |
| 3 | 调查调用其工具 | McpToolInvoker.invoke→ensureFresh | TTL 内用快照；过期重校验 |
| 4 | schema 变了 | list_changed→候选重检→withTools 原子换针 | "旧 Run 不被静默换参" |
| 5 | 运维禁用 | disable | 读侧立即生效；在飞 drain/idle 超时 |
| 6 | 连接关闭 | 引用释放后恰好一次 | 不泄漏连接 |
| 7 | 进程重启 | M12 恢复 | revision=max(generation)；disabled 不复活 |

## 9. 异常流程（第十四部分十问速答——工具运行时定版【代码事实】）

| # | 问题 | 30 字答案（详版见各篇） |
|---|---|---|
| 1 | Tool Schema 为什么重要 | 三合一：模型接口说明书+执行面权限法典+digest 身份锚（schemaHash 进摘要） |
| 2 | 模型参数错了 | INVALID_ARGS+具体拒因回喂计步；未声明字段硬拒绝（裁字段撞 digest） |
| 3 | 工具超时 | 硬 deadline cancel(true) 迟到作废→TIMEOUT_RETRYABLE；外部期限过期=停止事实 |
| 4 | 返回 500 | REMOTE_UNAVAILABLE 脱敏固定文案；429 单独 RATE_LIMITED；原文不透传 |
| 5 | 返回空 | NO_DATA 正常非故障；"无故障不制造证据" |
| 6 | 返回特别大 | 执行器流式 limit+1 读到即断+Gateway RESULT_OVERSIZE 双层 |
| 7 | 模型选错工具 | 白名单拦非法；语义错靠反馈纠偏（NO_DATA/DOOM_LOOP 指引换向） |
| 8 | 两个工具都能解决 | 系统不仲裁——目录+描述给模型选，代价由预算约束；选贵路受预算面惩罚 |
| 9 | 执行成功但回写失败 | 账本 CAS+resultRef+PENDING 悬挂回收；evidence 在则 digest 复用不重打 |
| 10 | 高风险 Tool 怎么审批 | R3→VALIDATE_ONLY 零执行→意图→Guardian→双人→Grant→五步计划（04 篇） |

## 10. 并发问题

1. **MCP 快照读与发布写并发？** 无锁读（不可变快照）+短临界区原子换针+竞败者关候选——"普通失败不替换健康快照"。
2. **disable 与在飞调用并发？** 范式 C：读侧立即生效+在飞 drain/idle 超时+连接恰好关闭一次。
3. **TTL 重校验与调用并发？** ensureFresh 资格领取"与 disable 串行"——重校验期间不发放新资格。
4. **generation 与 leaseEpoch 分型**（:37）——MCP 域也有自己的身份分型纪律。

## 11. 崩溃恢复

- M12：重启从 PG 恢复一致 revision 的注册快照——"disabled 不复活"；
- 工具账本的 PENDING 悬挂由 reclaim 面 UNKNOWN 化（06/14 篇）；
- digest 复用让恢复重驱"重打只读工具"零账面重复；
- **执行器无状态**（每次调用自包含）——工具层崩溃恢复=调用重放的安全性由"只读+幂等键"预先保证。

## 12. 安全

1. **schema 即权限**+**语义即二闸**（形状在 Validator、语义在执行器域——双层）；
2. **风险声明不可伪造**（本地注册表声明，MCP annotation 不信任）；
3. **词典纪律是安全纪律**："service allowlist 成员不写进词典"——部署白名单成员是配置机密面，写进词典=泄漏给模型与一切能读信封的人；
4. **M11**：stdio MCP 仅运维预装固定命令——"发布前拒绝，零进程零连接零落库"（防任意进程拉起）；
5. **为什么不能只在 Prompt 里写"这些工具怎么用、别越界"？** 词典描述已经做了这件事（契约面），但越界拦截靠的是 pattern/语义校验/白名单三道代码闸——描述降低犯错率，闸门消灭犯错面。

## 13. Agent Harness

| 机制 | 谁的 | 说明 |
|---|---|---|
| 工具的用途理解 | 模型（从词典描述） | 描述是人读+模型读同源 |
| 语义约束的记忆与执行 | Harness | 描述与校验"逐字对齐"，模型忘了闸门记得 |
| 工具版本选择 | Harness | 注册表 name@version 精确解析，digest 对账 |
| MCP 信任 | Harness | annotation 不信、描述零提权、TTL/schema 校验 |

## 14. 可观测性

- 账本拒因明细（V155 reason_detail）——"INVALID_ARGS/控制面拒绝的 message 随账落档，事后可考"；
- CallTelemetry：逻辑调用/物理调用/是否带来新证据内容三计数——工具效率（复用率、空查率）可量化；
- 词典与 Prompt 工作台工具注册表同源（BA-176"人读面"）——运营看到的工具说明与模型拿到的逐字一致；
- MCP 挂载状态（MOUNTED/DISABLED/FAILED+reason+generation）可查。

## 15. 性能和成本

- **调用池隔离**（bulkhead）：工具调用不占调度线程；池满显式背压；
- **上限即成本**：resultLimitBytes 双层裁断直接决定证据面与下一步信封的体积；
- **复用收益**：MC24 同 digest 复用+payload_digest 去重——重试/重驱场景物理调用次数显著低于逻辑调用次数（CallTelemetry 可量化）；
- **QPS×10**【合理推断】：先坏数据源（Prometheus/Loki）——工具层每步一调用的放大有限；池容量是第二道闸（背压拒绝会转化为模型重试，间接转化为步数消耗——所以池容量要按"调查并发×1"规划）。

## 16. 设计取舍

**① 为什么执行器是"纯远程调用面"，策略校验全在 Gateway？**
关注点分离的硬边界：执行器作者只需要会调数据源，不需要懂权限/预算/熔断——新增工具的认知成本降到最低；而所有横切纪律集中在一处（Gateway）演进。代价：执行器必须信任传入的 validatedArgs/deadline（契约由类型保证）。**把纪律放咽喉，把自由放末端。**

**② 为什么描述词典"只增不改"？**
描述同时是模型的调用契约面——改描述=改契约=正在跑的 run 的行为漂移。只增不改（新条目新增、错条目新增更正版）让历史 run 的可解释性保持。和 catalog 的"词典只增不改"同律（RootCauseZhDictionary 回退纪律同族）。

**③ 当前方案最大的边界？**
- 窗幅/步长等语义常量散在执行器内（词典靠人工同步"随 executor 改即同步"）——双源一致性是纪律不是机制【已识别，改进方向见 Q6】；
- 单步单工具的串行上限（结构代价，多次提及）；
- MCP 外挂的 schema 质量依赖外部 server（TTL/资格领取是防线，质量不是）。

## 17. 面试背诵卡

【30 秒主答】
"工具运行时把工具当生产组件治理。静态面：注册表启动期 fail-fast，重名即炸、构造后不可变、结构上禁止运行时下载插件；每个工具的 ToolDefinition 有 schema、风险、超时、结果上限，风险缺省从严到 R3。动态面：Gateway 七步过闸，执行器是纯远程调用面——策略校验预算全在咽喉，末端只管调数据源。外挂面：MCP 三范式——不可变快照无锁读加原子换针、TTL 重校验旧 Run 不被静默换参、disable 读侧立即生效加在飞 drain。还有一部中文词典身兼两职：人读的用途表和模型的调用契约同源，描述尾部的约束和执行器校验逐字对齐，service 白名单成员不写进词典。新增工具七步走完，运行时注册是结构禁止的。"

## 18. 这一层哪些话不能说

1. ❌ "工具支持热插拔" → ✅ 注册表启动期固化+结构禁止运行时注册；MCP 外挂的"挂载"有完整生命周期但也在管控内。
2. ❌ "工具描述是给文档看的" → ✅ 词典是模型调用契约面（description 同源），漂移=行为漂移。
3. ❌ "执行器里也做了权限校验" → ✅ 执行器纯远程调用面，权限在咽喉（这是分层不是缺失）。
4. ❌ "MCP 工具声明只读就可信" → ✅ annotation 不信任（E-16），风险只认本地声明。
5. ❌ "服务白名单在工具描述里" → ✅ 只写"限部署白名单"，成员是配置机密面（BA-182）。
6. 窗幅/超时数值中：3600s/60s/900s 为代码常量已确认；timeoutMillis 装配值【未确认】。

---

# 我现在应该能回答什么

1. 执行器契约是什么？"纯远程调用面"的边界为什么这么划？（→ 第 4/16 节取舍①）
2. 中文词典的双重职责是什么？四条口径纪律是什么？（→ 第 4 节）
3. MCP 外挂从注册到下线的完整生命周期？（→ 第 8 节七步表）
4. 新增一个工具要走哪七步？哪一步是结构禁止绕过的？（→ 第 6 节）
5. 十问速答（第十四部分）。（→ 第 9 节表）

# 30 秒背诵卡

见第 17 节。

# 面试官追问卡

**Q1：为什么语义约束（窗幅 900s）要写三处（词典/schema pattern/执行器校验）？不嫌冗余吗？**
考什么：双源对齐的必要性论证。
30 秒答："三处各有不可替代的角色：词典约束是给模型的（它只读描述不读代码）；schema pattern 是给校验器的（形状第一闸）；执行器域内判是给语义的（'schema 管形状，此处管语义'）。冗余是真的，但方向是'描述对齐校验'而非各自为政——BA-183 的纪律是逐字对齐。真正想消灭的冗余是 service 白名单成员——它被刻意排除在词典外（部署配置不进模型上下文）。冗余经过设计=容错，未经设计=腐化。"
继续追问 1："描述和校验漂移了怎么办？"——答："漂移的表现是模型按描述传参被校验拒——拒因经 V88 反馈环回喂，模型会按拒因（权威源）修正；同时拒因落账 reason_detail，漂移可被统计发现（某工具 INVALID_ARGS 突增=描述过时信号）。"

**Q2：MCP 的 disable 为什么要"读侧立即生效"，不等在飞调用结束？**
考什么：下线语义的设计。
30 秒答："因为 disable 的动机往往是安全事件（外部 server 行为异常）——等 drain 就是等更多调用打到可疑源上。所以读侧立即生效：新调用瞬间拿不到资格（与 disable 串行的资格领取）；已出发的调用由 drain 收口——在飞计数清零或 idle 超时后关闭连接，'引用释放后恰好关闭一次'不泄漏。立即生效+优雅收尾，两半都要。"
继续追问 1："drain 等多久？"——答："idle 超时兜底（配置值）——不会无限等；超时强关的代价是那些调用以 UNKNOWN 收场，账本诚实记录。"

**Q3："未命中一律回退工具名本身（绝不瞎编用途）"——这个纪律防什么？**
考什么：描述系统的诚实性。
30 秒答："防描述系统自己变成幻觉源。如果一个描述框架在缺条目时生成'大概是用来查什么的'，模型会把编造的描述当真并据此调用——错误描述比没有描述危险得多。回退工具名本身=如实告知'我不知道它干嘛，只知道它叫这个'，模型会倾向不调它。这条与 RootCauseZhDictionary 的回退纪律同族：**知识面的边界必须诚实**。"
继续追问 1："那新工具岂不是没人用？"——答："所以纪律的另一条是'新工具注册时同步补条目'——补描述是注册流程的一部分（第 6 节七步的③），漏了会在评测的调用分布里现形（零调用工具）。"

**Q4：工具复用（同 digest 不重打）会不会导致"过期数据当证据"？**
考什么：复用的正确性边界。
30 秒答："digest 里钉了 timeRange 和调查输入身份——复用的前提是'完全同一语义查询'，而调查窗口是铸造时冻结的，run 内不存在'过期'问题：同一窗口同一查询，数据源返回本就该一致。跨 run 不复用（新 run 新窗口新 digest）。要新鲜数据不叫重查，叫 RERUN——那是 run 级决策不是工具级决策。复用的正确性依赖'冻结窗'这个上游设计——上下文、证据、复用三条线在'冻结'这个点上汇合。"
继续追问 1："数据源在窗口内回填了历史数据？"——答："与 11 篇边界同款：run 内看到的是首次查询快照。RCA 要证据一致性不要实时性。"

**Q5：如果让你重新设计工具运行时会改什么？**【设计扩展——设计题答案，不要说成现网实现】
考什么：REDO。
30 秒答："三处：一是把语义约束提升为 ToolDefinition 声明字段（语义Semantics{maxWindow, maxStep...}），词典与校验从它生成——消灭三处对齐的人工纪律；二是单步内允许声明式多只读工具扇出（并行取证），熔断键按工具独立；三是 MCP 增加影子期——新 server 先 REPLAY/影子模式跑 N 次验证结果质量再转正。七步过闸、纯调用面执行器、词典只增不改这三样骨架不动。"

# 这层不要乱说什么

1. 不要说"工具是微服务"——它们是同进程内的执行器类+注册表。
2. 不要说"MCP 工具和本地工具待遇不同"——账本/错误两族/上限同构；差异只在信任起点（annotation 不信）。
3. 不要把"词典只增不改"说成"描述永不更新"——是新增更正、不改历史条目。
4. 不要说"执行器自己做超时"——deadline 由 Gateway 下传，执行器"尽量在 deadline 前返回"+socket 兜底，硬切断在 Gateway。
5. 不要报错 window 常量：prometheus 3600s/60s、logs 族 900s 是代码常量；其余执行器的具体数值【未核对不报】。
6. 词典全部条目数【未逐一清点】——抽样引用有锚即可。

# 5 句话总结

1. **为什么需要**：工具是 Agent 唯一的手——按组件治理（契约/身份/档案/生命周期/演进）才能回答生产问题。
2. **核心机制**：fail-fast 注册表+七步过闸+纯调用面执行器+只增不改的双职责词典+MCP 三范式生命周期。
3. **上下游协作**：上接模型决策与白名单；下连数据源与证据面；横切账本与审批链。
4. **最大风险**：语义约束的三处对齐靠人工纪律；MCP 外部 schema 质量不可控。
5. **最大取舍**：把所有纪律集中在 Gateway 咽喉、把执行器降到最低认知成本——新增工具便宜，纪律永不旁路。

---

*本篇完成。下一篇待你指令解锁：《17-Agent-Harness运行脚手架》。*
