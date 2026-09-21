# 11-Context上下文管理

> 本系列第十二篇。讲清楚"模型每一步到底看到什么"：**没有对话历史累积，只有从账本确定性重建的信封**——以及上下文长度治理的三种形态（裁剪/压缩/卸载）在本项目的真实对应物。
> 标注约定同前：【代码事实】/【合理推断】/【设计扩展】/【未确认】。

# 本层要解决的问题

一句话：**每一步模型调用的输入不是"聊到现在的历史"，而是从证据账本、检查点、裁决台账确定性重建的一封有界信封——同样的状态永远装配出同样的输入（stableDigest 可复现），大内容永远留在库外面信封里只有摘要和引用。**

# 先看一个交易告警

> createOrder 调查第 7 步，主 Agent 的模型输入长这样（`ContextAssembler.assemble` 产出的信封 JSON，:399-434）：
> - `alert`：告警名/服务/级别/摘要（"缺项如实 null——信封省略该键，不造数"）；
> - `objective`：调查目标；`budget`：步数/委派批余量；
> - `evidence`：**至多 20 条**证据有界摘要（时间倒序，第 21 条起进 `omittedRefs`——"仍可引用 valid_artifact_refs 不裁剪，只是无摘要"）；
> - `child_receipts`：本轮委派回执（≤8 条×各≤6 项）；`working_memory`：假设/已排除/缺口/反证四槽（≤10 项）；
> - `trajectory`：最近 8 步动作轨迹；`last_error`：上一步失败的修正指引；
> - `tool_schemas`：白名单工具的 JSON Schema **附中文用途描述**（"195 实证模型只认 logs/prometheus 族，change/alert.history 零调用——裸 Schema 只有形状没有'什么时候用'"，:428-431）；
> - 全文 = 角色 prompt + 信封 JSON + 输出协议后缀，sha256 即 stableDigest。
> 没有"之前 6 步的对话记录"——那些已固化为证据行、检查点和记忆槽。

# 如果没有这一层会怎样

1. **上下文随步数线性爆炸**。20 步调查每步带回全部历史，token 成本平方级增长，且早超出模型窗口。本项目用三个硬数字对冲：证据窗 ≤20、轨迹 ≤8、记忆槽 ≤10（ContextAssembler.java:63-65）。
2. **输入不可复现 = 故障没法回放**。如果信封里混入"当前时刻"的随机因素，同一次调查重放时模型输入对不上，任何坏 case 都无法复现。本层的 stableDigest 在注入 last_error **之前**计算（:438-439，"冻结面不含反馈——反馈每步可变，不是冻结输入"），同一状态必得同一摘要。
3. **装配时的副作用污染**。如果在装配里顺手写库，"读输入"和"模型是否真被调用"就耦合了——模型没跑成功也会留下记忆。本层的 CL-03 纪律："装配零写入——工作记忆只产候选行，append 副作用迁至提交事务（**模型未执行不落记忆**）"（:334-336）。

---

# 代码是怎么做的

## 0. 先给我一句话

上下文管理 = "装配器"（从账本确定性重建有界信封）+"压缩服务"（步边界上可选的 LLM 摘要，默认关）+"引用卸载"（大内容永远在库、信封只带摘要和 id）三件套。

## 1. 业务上为什么需要这一层

模型窗口有限、token 有价、输入必须可复现——三个约束决定了上下文不能是"自然累积的对话"，必须是"按需重建的有界投影"。

## 2. 它在整个系统的位置

```mermaid
flowchart TB
    subgraph 账本层（持久事实）
        EV[(rca_evidence 证据)]
        WM[(rca_working_memory 工作记忆)]
        CS[(rca_context_summary 摘要)]
        CK[(rca_primary_checkpoint 检查点)]
        TL[(工具/委派/回执台账)]
    end
    subgraph 装配器 ContextAssembler
        R1[单读证据池快照<br/>timeEnd 倒序]
        R2[确定性重建记忆候选]
        R3[轨迹≤8/回执≤8/材料≤10]
        ENV[信封 JSON 有界投影]
    end
    subgraph 压缩服务 ContextCompactionService（默认关）
        C1[五道零调用闸]
        C2[COMPACTION 模型调用<br/>动作序保留段 10^6]
        C3[候选四重校验]
        C4[CAS 提交+消费指针]
    end
    EV --> R1
    CK --> R2
    TL --> R3
    R1 & R2 & R3 --> ENV
    CS --> ENV
    ENV --> P[prompt=角色prompt+信封+协议后缀]
    P --> SD[stableDigest sha256<br/>不含 last_error]
    ENV -.步边界触发.-> C1 --> C2 --> C3 --> C4 -.-> CS
    C4 -.-> CK
    P --> M[模型]
```

## 3. 输入和输出

- **收到**：RoleDriveRequest + 检查点 + 委派批余量 + 证据池单读快照 + 告警材料单读投影（"快照/材料由调用方单次读库后传入——本方法零读库零触网"，:376-377）。
- **处理**：确定性投影成信封 JSON + 拼角色 prompt + 协议后缀。
- **产出**：`Assembly{prompt, snapshotDigest, approxTokens, includedRefs, omittedRefs, memory 候选}`（:201-203）。
- **交给谁**：BoundedLlmRoleRunner → RcaActionGuard → 模型；memory 候选随提交事务落库。

## 4. 真实代码入口

- 文件：`alert/application/agent/ContextAssembler.java`（1300 行）；`agent/ContextCompactionService.java`（522 行）。
- 方法：`assemble(...)`（:386-448）；`evidenceSnapshot`（:350-356，"timeEnd 倒序+evidenceId 倒序稳定排序，成员冻结"）；`ContextCompactionService.afterToolResults`（一步边界，javadoc :30-50）。
- 调用方：BoundedLlmRoleRunner.drive（每步装配；压缩在 maybeCompact :539-565，"异常不打断主路径——压缩是优化，有界回退=按原材料继续"）。
- **信封键全景**【代码事实，:399-434】：role / run_id / task_id / round_id / alert / objective / budget / evidence / child_receipts / operator_materials / skill（EN-08）/ root_cause_catalog / working_memory / trajectory / validated_summary（CL-08）/ tool_allowlist / tool_schemas / valid_artifact_refs / last_error（V88，最后注入）。
- 关键参数：`delegationBatchesRemaining` 与裁决同源（"禁自读配置致漂移"，:330-331）；`replaceOmittedSummaries`（JE-01 存根替换开关，:197-198）。

## 5. 核心对象

| 对象 | 是什么 | 生命周期 | 持久化 |
|---|---|---|---|
| `Assembly`（一步装配产物） | prompt+stableDigest+token 估算+进出引用集+记忆候选 | 单步即弃 | prompt 原文经 `RcaModelInputCapture`（V90）存档 |
| `WorkingMemory`（工作记忆） | 四槽：hypotheses（检查点 FINAL 提案史）/ruled_out（裁决拒绝史）/open_gaps/counter_evidence_refs；"[EX] "前缀=可信排除印记（"无前缀父项=规则前控制面拒绝污染，重建时不继承"） | run 级 append-only **深冻结**；"同修订重放返回既有行"（MC07 恢复读同快照） | PG（V91）；检查点只存 memoryId/digest 引用 |
| `ContextSummary`（摘要） | 压缩产物：冻结区间 [上一摘要 event_seq_to+1, 本步 decision_seq] 的摘要 | append；消费指针在检查点 current_summary_id | PG（V92） |
| `stableDigest` | 稳定面 prompt 的 sha256（**不含 last_error**） | 步级 | 回填 rca_model_call.input_snapshot_digest（G3 接线） |

**第十二部分概念对照表**【代码事实——这张表是本篇面试核心】：

| 概念 | 本项目对应物 | 存在吗 | ID | 生命周期 | 存储 | 跨进程/重启 | 谁修改 |
|---|---|---|---|---|---|---|---|
| Session（会话） | 无聊天会话概念 | ❌ | — | — | — | — | — |
| Conversation（对话历史） | **不存在**——被"检查点+确定性装配"替代 | ❌ | — | — | — | — | — |
| Investigation（调查） | `RcaRun` | ✅ | runId | 铸造→终态 | PG rca_run | ✅/✅ | 状态机迁移点 |
| AgentState（智能体状态） | `PrimaryCheckpoint` + `WorkingMemory` | ✅ | taskId/runId | 主任务全程 | PG 两表 | ✅/✅ | 唯一写口=CommitService |
| Context（上下文） | 每步装配的 `Assembly.prompt` | ✅（临时产物） | stableDigest | 单步 | prompt 原文存档 V90；本体不落业务表 | — | 装配器（只读投影） |
| Checkpoint（检查点） | `PrimaryCheckpoint` | ✅ | taskId | 同 AgentState | PG V47 | ✅/✅ | 同上 |

Context 里各要素有没有（第十二部分清单）【代码事实】：原始告警（alert 槽✓）、服务信息（✓）、时间范围（run 冻结列+工具参数✓）、当前假设（working_memory hypotheses✓）、指标/日志/变更证据（evidence 窗✓，含来源标签）、Tool Result（evidence+trajectory✓）、子任务结果（child_receipts✓）、**消息历史（✗ 无）**、最近 Tool Call（trajectory✓）、审批信息（operator_materials✓，JUDGMENT 类"不构成证据引用"不入 validRefs——"无证判断不能绕过 Claim 准入"）、Token 信息（budget 槽✓）。

## 6. 一条真实调用链（第 7 步的输入装配）

```
BoundedLlmRoleRunner.drive 第④步
→ evidenceSnapshot(runId)（单读+稳定排序）/ alertMaterial(runId)（单读）
→ [JE-01 开启时] jev.selectContext（选材在全量池上做，先于窗口截断——
   "被窗口挤掉的早期反证才有机会被选回"，:237-239）
→ assemble(request, checkpoint, batchesRemaining, snapshot, material, selection)
   信封 18 键有序装配（LinkedHashMap）→ tool_schemas 附中文描述
   → stablePrompt = profile.prompt + jsonOf(envelope) + PROTOCOL_SUFFIX
   → snapshotDigest = sha256(stablePrompt)（last_error 尚未注入！）
   → 注入 last_error → 最终 prompt
   → approxTokens = length/2+1（"中文混合上限档"保守估算）
→ maybeCompact（步边界压缩，见下）
→ RcaActionGuard.guardedModelCall(prompt, stepMaxTokens, estimate)
   → 成功后 stableDigest 回填 rca_model_call.input_snapshot_digest
   → 记忆候选随 STEP_COMPLETED 提交事务 append（模型未执行不落记忆）
```

## 7. 状态机

上下文层自身无状态机；它的"状态"全部寄生在检查点（phase/计数器/currentSummaryId 消费指针）与记忆/摘要表（append-only）。与压缩相关的三个模式态【代码事实，CL-07 :84-88】：`OFF / SHADOW_GENERATE（只生成留档不换输入）/ CONSUME_VALIDATED（生成后经围栏把 current_summary_id 钉上检查点）`——"运行模式不是实验臂"，放量前提是 MC34 三臂对照（OFF/确定性/消费）证明收益。

## 8. 正常业务流程（一次压缩的完整生命周期）

| # | 业务动作 | 代码 | 效果 |
|---|---|---|---|
| 1 | 第 7 步工具结果入库 | maybeCompact 触发 | — |
| 2 | 五道零模型闸 | enabled/软阈值/次数上限/快照锚/同源幂等 | 任一不过=DISABLED 等诚实短路径 |
| 3 | 冻结压缩区间 | [上摘要 seq_to+1, 本步 decision_seq] | "摘要只覆盖冻结窗，禁混 digest"（MC16） |
| 4 | 生成 required_refs | 绑定 inputRefs ∪ 检查点终局 evidence_refs | "摘要模型不得删空" |
| 5 | 受守卫的压缩调用 | ActionGuard 同律+动作序占 10^6 保留段 | 费用/账本/栅栏与主循环同规 |
| 6 | 候选校验 | JSON/引用越界剥离/必需引用全覆盖（MC13 缺失即整候选拒）/有节省（MC15） | recall-first 协议："不得为达到压缩比移除反证" |
| 7 | 提交前复验 | MC17：检查点漂移→SUPERSEDED 不落行（费用已落账审计不丢） | 摘要不追旧状态 |
| 8 | CAS 提交 | uq(run,task,source)（MC18 并发同源双写一胜一拒） | 摘要落 V102 台账 |
| 9 | 消费 | CONSUME_VALIDATED 模式经围栏钉 current_summary_id | 下一步信封 validated_summary 槽生效 |

## 9. 异常流程

| 异常 | 处理了吗 | 怎么处理 |
|---|---|---|
| 压缩模型调用失败 | ✅ | MODEL_FAILED→丢弃候选保留原快照——"无内联重试、无'摘要→摘要'递归——源锚恒为原始快照 digest"（:45-46） |
| 压缩候选漏了必需引用 | ✅ | REJECTED_MISSING_REQUIRED 整候选拒绝（MC13） |
| 压缩后没有变小 | ✅ | REJECTED_NO_SAVINGS 拒绝（MC15） |
| 压缩期间检查点被推进 | ✅ | SUPERSEDED 不落行，"费用已由守卫落 rca_model_call，审计面不丢"（MC17） |
| 压缩异常打断主路径？ | ✅ 不会 | maybeCompact 全 try-catch——"压缩是优化，有界回退=按原材料继续"（05 篇 :561-564） |
| 证据超 20 条 | ✅ | 时间倒序截取+omittedRefs 留痕（引用资格不裁剪） |
| 单项摘要超界 | ✅ | clip 截断+truncated=true 标注 |
| current_summary_id 指向行缺失 | ✅ | warn+"原材料继续——消费是增益不是依赖"（:264-268） |
| 记忆槽被脏数据污染 | ✅ | "[EX] "前缀可信印记——无前缀的排除项重建时不继承 |
| 上下文超模型能力 | ✅ | CAPABILITY_INPUT_TOO_LARGE 封闭码（账本可见）+装配面已前置有界 |
| 模型陷入长对话失控 | ➖ 结构上不可能 | 无对话累积——每步信封重建，历史只经摘要/记忆受控回喂 |

## 10. 并发问题

1. **压缩与主循环并发提交？** MC17 复验（检查点自冻结后推进→SUPERSEDED）+MC18 CAS（uq(run,task,source) 双写一胜一拒）——摘要永不与主推进冲突。
2. **压缩调用撞主循环账本唯一键？** COMPACTION 动作序占 ≥10^6 保留段（:56-58）——"与决策序不撞唯一键"，**用编号段位隔离两种调用的身份空间**。
3. **同修订重放记忆？** MC07："同修订重放返回既有行"——append 深冻结+幂等读，恢复重驱不产生第二份记忆快照。
4. **装配读取期间证据新增？** 单读快照（CL-03）——装配基于冻结成员表，新证据下一步才可见（一致性优于新鲜度，刻意选择）。

## 11. 崩溃恢复（kill -9 视角）

- **装配后、模型调用前被杀**：零残留（装配零写入——CL-03）；重驱重新装配，stableDigest 一致（确定性重建的收益）。
- **模型调用后、提交前被杀**：prompt 原文已在 V90 存档（对账可查）；检查点未推进→重驱同快照重跑该步；MC07 保证记忆候选不重复落。
- **压缩提交后、消费前被杀**：摘要行已在 V102 台账；消费指针未钉→下一步信封用原材料（增益丢失但不破坏）；CL-08 消费走围栏条件写可重入。

## 12. 安全

1. **人工材料不冒充证据**（MC31）：operator_materials 与实测证据严格分槽，JUDGMENT 类带标注且不入 validRefs——人类插进来的判断不能绕过 Claim 准入。
2. **反证不可被压缩丢弃**：recall-first 协议"不得为达到压缩比移除反证"+MC13 必需引用全覆盖校验——**压缩服务自己被约束不得"篡改调查记忆"**。
3. **十三部分机制对照表**（按实际实现讲，不硬套英文名）：

| 机制 | 本项目对应物 | 形态 |
|---|---|---|
| 截断 | 证据窗≤20 时间倒序/轨迹≤8 保留最近/各摘要限长 clip | **Pruning（裁剪）** |
| 日志聚合去重 | logs.aggregate 先聚合后读行；payload_digest 重复内容不记进展 | 确定性裁剪前置 |
| Evidence Summary | evidence 槽的有界摘要（included/omitted 双集） | Pruning+投影 |
| LLM 摘要压缩 | ContextCompactionService（默认关，三模式灰度，MC34 三臂对照前置） | **Compaction（压缩）** |
| 大内容外置引用 | 证据本体/记忆深冻结/raw 原文 CAS 都在库，信封只有摘要+id（memoryId/currentSummaryId/rawRef） | **Offloading（卸载）** |
| first/middle/last 策略 | ❌ 无此命名——最接近的是"轨迹保留最近 8 步"（按实际实现解释） | — |
| 指标采样 | ❌ 无专门机制——时间窗/step 由调查冻结列固定（30s 步长） | — |

4. **为什么不能只在 Prompt 里说"请自己注意上下文长度"？** 长度治理的三刀全是代码：窗口截断是装配器算的，卸载引用是表结构定的，压缩闸门是七步流水线——模型对"自己看到多少"零控制权。

## 13. Agent Harness

| 机制 | 谁的 | 说明 |
|---|---|---|
| 记住什么（记忆槽内容） | Harness 重建（hypotheses=提案史、ruled_out=拒绝史） | 模型不写记忆——候选由宿主确定性生成 |
| 看到多少证据 | Harness | ≤20 窗口+omitted 留痕 |
| 压缩与否、压缩质量 | Harness 七步闸+四重校验 | 模型只是压缩执行者，产物要过宿主验收 |
| 反馈（lastError） | Harness 定稿 | V88 反馈环，成功步清空 |
| 输入可复现 | Harness | stableDigest 不含易变面 |

一句话：**模型的"记忆"和"看见"都是宿主的投影——它没有自己的上下文主权。**

## 14. 可观测性

- `rca_model_input_capture`（V90）：每步模型输入原文存档——出坏 case 可以逐字节复盘"模型当时看到了什么"；
- `rca_compaction_attempt`（V102）：每次压缩的冻结来源/策略/执行身份台账（"终态封闭不删行"）；
- `rca_compaction_consumption`（V164）：消费观测（mode/consumerInvoked/consumed/policyDigest）——"consumed=false 的候选不得计入'摘要消费后效果'"（D06）；
- token 估算的诚实边界（:118-125 注释原文）："tokenBefore/After 是 summaryText.length()/2 近似，**不能证明模型总输入下降**——真实发送面 token 统计归后续专项"。

## 15. 性能和成本

- **成本结构**：主循环每步输入≈prompt（角色）+信封（有界）+协议后缀；证据窗 20 条×摘要 200 字符是主项——**上下文治理直接决定每步单价**。
- **压缩的账**：一次压缩=一次额外模型调用（≤2048 输出 token）换后续若干步输入变小——所以默认关、要 MC34 三臂对照证明净收益才放量（"否则只保留确定性裁剪"）。
- **token 估算用 length/2**（中文混合保守档）——预算预留宁可高估。
- **QPS×10**【合理推断】：上下文层不是独立瓶颈；压缩若开启，其模型调用会与主循环共享模型网关配额——这也解释了为什么压缩要有独立动作序段位与次数上限闸。

## 16. 设计取舍

**① 为什么不用"滑动对话窗口"这种标准方案？**
对话窗口的前提是"历史=对话"，而本系统每步之间隔着工具执行、证据落库、可能还有委派——中间状态比"说过什么"重要得多。确定性重建让输入=状态投影（可复现、可对账、可裁剪），对话窗口则把这些埋进文本流。代价：装配器复杂（1300 行）——但复杂度集中在宿主，模型接口反而简单（永远一封自包含信封）。

**② 为什么压缩默认关、还要三臂对照？**
注释原文："放量前提是 MC34 三臂对照证明收益，否则只保留确定性裁剪（R1 界面，恒为第一刀，本服务永远在其之后）"。理由：压缩是**有损优化**——摘要可能丢反证（协议特意 recall-first 也堵不住所有情况）、多一次模型调用多一份成本与故障面。三臂对照（OFF/确定性/消费）让"开了压缩到底好多少"变成实验结论而不是信仰。**优化默认关闭，收益举证责任在优化一方**。

**③ 当前方案最大的边界？**
- token 估算是字符近似（/2），与真实计费口径有偏差（注释自己承认不能证明总输入下降）；
- 压缩只覆盖冻结区间，"全量旧正文替换消费待 MC34 后启用"——当前消费是附加注入/存根替换，不是完整替换；
- 记忆槽 ≤10 项：长调查的假设/排除史可能溢出（溢出即截断，无优先级淘汰）。

## 17. 面试背诵卡

【30 秒主答】
"上下文管理的核心决策是：没有对话历史，每步输入都是从账本确定性重建的信封。装配器把证据、记忆、轨迹、回执投影成一封有界 JSON——证据最多 20 条时间倒序、轨迹最多 8 步、超界的进 omitted 引用集，引用资格不裁剪只是不给摘要。prompt 加信封做 sha256 得到 stableDigest——刻意在注入错误反馈之前算，保证冻结面可复现，它还是同签名熔断的比较键。长度治理三刀：裁剪是确定性窗口；压缩是独立服务，默认关，要三臂对照证明收益才放量，且压缩产物要过四重校验、反证不许丢；卸载是证据本体永远在库、信封只带摘要和引用。装配全程零写入——记忆候选要等模型真执行了才随提交事务落库。"

## 18. 这一层哪些话不能说

1. ❌ "多轮对话上下文/聊天记忆" → ✅ 无对话累积，信封每步确定性重建（本轮头号红线）。
2. ❌ "自动上下文压缩" → ✅ 压缩默认关、三模式灰度、三臂对照前置——说"确定性裁剪恒为第一刀"。
3. ❌ "模型管理自己的上下文" → ✅ 装配/裁剪/压缩/消费全是宿主代码，模型零控制权。
4. ❌ "用了 first/middle/last 等经典策略" → ✅ 无此命名，轨迹是"保留最近 8 步"——按实际实现讲。
5. ❌ "压缩节省了 X% token" → ✅ 估算口径 length/2，注释承认不能证明总输入下降。
6. 记忆槽/信封键的具体字段名【已核对主要键；个别槽内部结构以代码为准】。

---

# 我现在应该能回答什么

1. 模型第 N 步的输入由哪些槽构成？各槽上界是多少？（→ 第 4 节信封键全景+界限）
2. 为什么没有对话历史？中间状态去哪了？（→ 证据行/检查点/记忆槽三处固化；十二部分对照表）
3. stableDigest 为什么在 last_error 之前算？（→ 冻结面可复现+同签名熔断比较键）
4. 压缩服务为什么默认关？开了怎么保证不丢反证？（→ 三臂对照前置；recall-first+MC13 必需引用全覆盖）
5. "模型未执行不落记忆"是什么纪律？（→ CL-03 装配零写入，候选随提交事务落库）

# 30 秒背诵卡

见第 17 节。

# 面试官追问卡

**Q1：确定性重建输入，那模型第 3 步"想过什么"岂不是丢了？下一步它不记得自己的推理了？**
考什么：对"推理外置"的理解。
30 秒答："模型本步的'推理'就是这个决策 JSON 本身——它落检查点（decisionSeq）和轨迹（trajectory 槽最近 8 步），下一步以轨迹形式回喂。对话式系统的'我记得我说过什么'在这里变成'宿主记得你做过什么'——而且是结构化的：做过什么动作、产了什么证据、什么被拒绝了。丢失的只有自由文本的思考过程——而我们的协议本来就禁止它输出思考过程（05 篇），所以没有东西可丢。"
继续追问 1："轨迹只有 8 步，第 1 步做过什么忘了怎么办？"——答："动作的事实载体是证据行和账本，不是轨迹——轨迹只是'最近动作的快捷视图'。第 1 步的证据还在证据窗（若未出窗）或 valid_artifact_refs（引用资格永不裁剪），假设与排除进记忆槽跨轮继承。"

**Q2：stableDigest 不含 last_error，那熔断"同签名"比较的是什么？**
考什么：两个易变面的分离。
30 秒答："签名=errorCode+snapshotDigest（05 篇 Q3）：errorCode 是模型面失败原因，snapshotDigest 是不含反馈的冻结输入。设计意图：'同签名'要等价于'同参数同输入再发一次必然同败'——反馈（last_error）恰恰是每步可变的，把它算进摘要，两次失败永远不同签名，熔断永不触发。所以冻结面/易变面的切分线就是可复现性：能复现的进 digest，不能复现的（反馈）走信封旁路。"
继续追问 1："反馈不是也影响模型行为吗？不进 digest 怎么对账？"——答："反馈注入前后的两份 prompt 都在 V90 存档——对账用存档，身份用 digest，两个用途分开。"

**Q3：记忆槽的"[EX] 前缀可信印记"防的是什么？**
考什么：记忆污染防御。
30 秒答："防'规则前时代的脏排除'继承到新规则时代。ruled_out 槽里只有带 [EX] 前缀的项被视为已验证业务排除并跨轮继承；无前缀的父项=规则前控制面拒绝的污染，重建时不继承（旧快照行原样留库供审计）。本质是给记忆内容加'可信供数者'水印——记忆的继承是有谱系的，不是无脑累积。"
继续追问 1："谁有权打这个前缀？"——答："确定性代码（裁决拒绝史投影）——模型输出的排除建议没有直接进槽的通道，要经过裁决和准入。"

**Q4：压缩产物如果漏掉了某个关键反证，调查会错吗？**
考什么：有损优化的防御纵深。
30 秒答："四层防线：一是协议层 recall-first——压缩提示明令'不得为达到压缩比移除反证'；二是校验层 MC13——required_refs（绑定输入∪检查点终局证据）必须全覆盖，缺了整候选拒绝；三是提交层 MC17——检查点漂移候选作废；四是兜底层——任何拒绝都回退原材料继续，压缩永远不是必经路径。而且当前消费模式是附加注入/存根替换，不是删原文——原证据行还在库，引用资格不受压缩影响。最坏情况：一次压缩白花钱，不会让调查失忆。"
继续追问 1："那为什么还要做压缩？"——答："长调查的输入成本是实打实的，确定性裁剪（窗口截断）是粗刀，压缩是细刀——细刀要在三臂对照证明净收益后才上，这个决策框架本身就是答案。"

**Q5：如果让你重新设计上下文层会改什么？**【设计扩展——设计题答案，不要说成现网实现】
考什么：REDO。
30 秒答："三处：一是 token 估算换成真实 tokenizer（现在 length/2 是保守近似，预算面宁高估但压缩收益评估就失真了）；二是记忆槽加优先级淘汰（现在溢出即截断，老假设和旧排除一视同仁）；三是把 tool_schemas 的中文描述做成策略资产走发布面灰度——现在它焊在装配器，调一个描述要发版。信封确定性重建、stableDigest 冻结面、装配零写入这三个不变量不动——它们是可复现性的根。"

# 这层不要乱说什么

1. "对话历史/聊天记录"是本篇头号禁词——说"信封/状态投影/确定性重建"。
2. 不要说"自动摘要压缩已上线"——默认关+三臂对照前置（SHADOW/CONSUME 是灰度模式）。
3. 不要说"模型记得之前的思考"——思考过程协议层面就不存在。
4. 不要把 omittedRefs 说成"丢弃"——是"无摘要但仍可引用"。
5. 不要引用具体 token 节省比例——估算口径的诚实边界（注释原文）。
6. 信封各槽的内部字段【主键已核对；细节以装配器代码为准】。

# 5 句话总结

1. **为什么需要**：模型窗口有限、token 有价、输入必须可复现——上下文必须是"按需重建的有界投影"而非"累积的对话"。
2. **核心机制**：装配器确定性重建 18 键有界信封（stableDigest 冻结面）+三刀治理（裁剪/压缩/卸载）+装配零写入纪律。
3. **上下游协作**：读账本（证据/记忆/检查点/回执）；产物进模型；记忆候选与摘要消费随提交事务和围栏落回。
4. **最大风险**：压缩是有损优化（默认关、三臂对照前置）；token 估算是近似（诚实边界已登记）。
5. **最大取舍**：用"装配器 1300 行的宿主复杂度"换"模型接口永远一封自包含、可复现、有界的信封"。

---

*本篇完成。下一篇待你指令解锁：《12-Session与State状态管理》。*
