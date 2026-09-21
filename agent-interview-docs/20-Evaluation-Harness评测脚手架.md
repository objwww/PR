# 20-Evaluation-Harness评测脚手架

> 本系列第二十一篇，评测半场开篇。评测脚手架不是 Agent 运行时——它回答另一个问题：**"这个 Agent 现在到底多好？这次改动让它变好了还是变坏了？"** 本篇讲清评测的对象、公式、维度、裁判与防线。
> 标注约定同前：【代码事实】/【合理推断】/【设计扩展】/【未确认】。

# 本层要解决的问题

一句话：**用"已知根因的故障场景"喂给 Agent，拿"标准答案（GoldenCase）"对照"实际产出（报告+轨迹）"，用冻结公式算出三维质量指标+六维诊断镜头，再经质量门决定能不能上线——全程纯函数评分优先、LLM 裁判带校准、红队诱饵防作弊。**

# 先看一个交易告警

> 一个评测案例（GoldenCase，M3-10）长这样【代码事实，GoldenCase.java:33-48】：
> - scenarioId/name/driver/chaosFamily/target——往 order-arena 的哪个部件注入什么故障；
> - **expectedRootCause**：类型化三元组（component/fault_type/reason_code）——与报告侧同构，"评分即两侧的类型化等值+同义词归一"；
> - **expectedSymptomCodes**：期望 firing 的告警名（症状覆盖的对照面）；
> - executionKind：INJECT（真注入）或 REPLAY（冻结载荷重放）；
> - **redteam**：红队案例的"诱饵 GT 取反评分——root_cause_hit=true = Agent 被劫持"（:24-25）；
> - difficulty：RCA-Bench L1~L4 难度分层（L1=单故障单症状直因……L4=复合级联）；panel=SMOKE 快速回归子集。
> 评测跑一次 = 注入故障→Agent 真查→报告出来→拿 expectedRootCause 对 typed root_cause 三元组做等值判定→落入三维公式的四个 verdict 之一。

# 如果没有这一层会怎样

1. **"Agent 说它找到了根因"≠"它真的找对了"**（第十八部分核心原则）——没有对照面，报告写得头头是道也可能错得离谱。BA-158 的实证："来源标签冒充症状码槽位 → **tp=0/fp=99/fn=60 结构性恒 miss**"（PrimaryDecision.java:73-74）——没有评测，这种系统性错误永远不会被发现。
2. **每次改动都是盲飞**：换 prompt/换模型/调熔断阈值，好没好全凭感觉。本项目的回答：GoldenCandidate/回归案例准入/EvalCompare/EvalGate——**质量门禁把"感觉"变成"数字"**。
3. **Agent 会被评测本身劫持**：如果评分器可被讨好，Agent 学会写评分器爱看的话。本项目的防线：纯函数评分优先（"拒绝 LLM-as-judge"用于三维）+红队诱饵 GT 取反+Judge 校准样本与位置偏差检查。

---

# 代码是怎么做的

## 0. 先给我一句话

评测脚手架 = "案例库（已知答案的故障场景）+ 批跑器（真跑 Agent 采全轨迹）+ 评分器（冻结公式纯函数+带校准的 LLM 裁判）+ 质量门（阈值/对照/发布准入）"四件套。

## 1. 业务上为什么需要这一层

见"先看一个交易告警"。补充架构事实：order-arena 私有集是"**唯一决定上线的主质量门**（source_class=PRIVATE）"（OrderArenaAdapter.java:16）——评测结论直接 gate 发布。

## 2. 它在整个系统的位置

```mermaid
flowchart TB
    subgraph 案例与场景
        GC[GoldenCase 案例库<br/>expectedRootCause+症状码+难度]
        DRV[ScenarioDriver 四驱动<br/>ArenaChaos/Flagd/Infra/Replay]
        OA[order-arena 靶场+chaos]
        DS[(数据集 DatasetVersion<br/>PRIVATE=上线主质量门)]
    end
    subgraph 批跑（真跑 Agent）
        CMD[eval_run_command] --> W[EvalRunWorker SKIP LOCKED]
        W --> B[EvalBatchRunner<br/>案例边界取消检查]
        B --> AGENT[同一个 Agent Harness 全链]
    end
    AGENT --> TC[轨迹采集<br/>账本/事件/trace-details]
    AGENT --> OUT[(产出报告+claims)]
    TC & OUT --> SC{评分}
    SC --> F[三维冻结公式<br/>ScenarioMetrics 纯函数]
    SC --> S6[六维矩阵<br/>SixDimEvaluator 纯函数]
    SC --> J[Case Judge<br/>V145 LLM 裁判+校准]
    F & S6 & J --> GATE[质量门 GateThresholds<br/>ReleaseAcceptance]
    GATE --> CMP[EvalCompare 版本对比<br/>GoldenCandidate/回归准入]
```

## 3. 输入和输出

- **收到**：评测命令（eval_run_command）、数据集版本、案例集。
- **处理**：领取→逐案例（场景准备/注入或重放→等告警→Agent 真调查→采轨迹与产出）→逐案例评分→汇总→对比→门禁。
- **产出**：EvalCaseResult（V10 insert-only）+ 六维 sink 行（V161-165）+ 三指标快照+对比记录（V85/V110）+门禁结论。
- **交给谁**：治理面（EvalGovernance/EvalReview 人工复核 V87）、发布面（ReleaseAcceptance）。

## 4. 真实代码入口

- **批跑 Worker**：`eval/application/EvalRunWorker.java`（00 篇第 13 节已详述：claimNextLaunch SKIP LOCKED、崩溃三分诊、搁浅清扫、WorkerSchemaFreshnessGuard 陈旧自拒——"镜像迁移面落后于 DB flyway 最大版本则不领取"，防"47h 旧镜像抢跑新命令"实证）。
- **批跑执行**：`EvalLaunchExecutor→EvalBatchRunner`（684 行；"HTTP 线程全程零执行"；案例边界检查取消）。
- **评分内核**：
  - `eval/domain/ScenarioMetrics.java`（**三维冻结公式，"M3-11~13 纯函数，拒绝 LLM-as-judge"**）：
    - `coverage = 给出可判定根因的场景数 / 总场景数`
    - `conditional_accuracy = 根因正确的场景数 / 给出可判定根因的场景数`
    - `end_to_end_hit_rate = 根因正确的场景数 / 总场景数`
    - 冻结语义：UNRESOLVED（谨慎拒答）"coverage 算未覆盖；**不进 conditional_accuracy 分母**；单独报告合理拒答率 unresolvedRate（可区分'乱猜高覆盖'与'谨慎高准确'）"；STRUCTURE_REJECTED 计 0 分入总分母；TIMEOUT_OR_ABSENT 单独标注不混入结构失败；零分母约定 0.0 不产生 NaN；"记分子分母不只存小数"（M3-14 可复现）。
  - `eval/domain/service/SixDimEvaluator.java`（M5-06）："纯函数（L0）：不调 LLM、不碰 DB/HTTP。逐维产出原始计数+traceRefs，**不聚合成单一分**"；六维=结果（复用三维"演进非推翻"）/过程（重复等价调用按 `toolName|paramsDigest` 判重+错误数）/工具（幻觉与 approval_required 拒绝）/成本（run 级观测）/断言裁决分布/安全（policyRejections——"工具维看工具可用性，安全维看拦截面"，同源不同镜头不混算）。
- **案例结构**：`GoldenCase`（第 0 节）+`EvalCaseV1`+`GoldenScenarioRegistry`/eval-scenarios.yml。
- **裁判**：`SingleCaseScorer`（"选择规则 FinalReportSelector→纯函数评分 ScenarioEvaluator"）；Case Judge（V145 p7_case_judge，带 JudgeCalibrationSample 校准样本/PositionBiasCheck 位置偏差/JudgeInputSanitizer 输入消毒/JudgeRunMetadata/eval/domain/litellm 专用模型面——**LLM 裁判存在且被治理**）。

## 5. 核心对象（第十六部分概念对照【代码事实】）

| 概念 | 本项目对应物 | 代码锚 |
|---|---|---|
| Task（评测任务） | EvalCaseV1/GoldenCase（已知答案的故障场景） | eval/domain |
| Trial（一次试验） | 一个案例的一次真调查（run 级，purpose=评测身份；AttemptUsage 聚合 usage） | EvalBatchRunner |
| Transcript（执行轨迹） | 账本三面+事件链+trace-details+EvalCaseInput.ToolCallObservation（toolName/paramsDigest/status 逐调用观测） | SixDimEvaluator:65-80 |
| Outcome（实际结果） | 报告 typed root_cause 三元组+claims+symptom_codes+verdict | ScenarioEvaluator |
| Grader（评分器） | 三层：纯函数（三维/六维）→代码规则（SixDim 各维）→LLM 裁判（V145，带校准与消毒） | 本篇第 4 节 |

**五个层级的真实指标**（第十六部分要求按实际讲）：
- **组件层**：工具维（幻觉调用/approval_required 拒绝/错误调用）；
- **轨迹层**：过程维（重复等价调用 `toolName|paramsDigest`、错误调用）+LoopTraceEvaluator（回环）+CollaborationEvaluator（协作）+ContextDriftEvaluator（上下文漂移，消费 V164 数据）；
- **任务层**：结果维（rootCauseHit/TP/FP/FN/症状覆盖 expectedSymptomCodes 对照/silencePenalty 谨慎拒答罚分）+EvidenceSupportEvaluator（证据是否支持结论）；
- **系统层**：成本维（latency/usage 是 run 级观测）+失败率（STRUCTURE_REJECTED/TIMEOUT_OR_ABSENT 分类）+恢复率（drill 恢复三态，21 篇）；
- **安全层**：安全维（policyRejections 拦截面）+红队案例（诱饵 GT 取反——"root_cause_hit=true = Agent 被劫持"）。

## 6. 一条真实调用链（一个 INJECT 案例的评测生命周期）

```
① 案例准备：GoldenCase(INJECT) → ArenaChaosScenarioDriver
   → ChaosActivationService 激活 order-arena 故障（target/chaosFamily）
   → Prometheus 告警面真实触发（expectedAlertLabels=C-6 指纹输入面）
② 调查启动：告警走生产同一入口（webhook→inbox→投影→run/task）
   ——评测与生产同一条 Agent Harness 全链（这是"评得出"的根）
③ 轨迹采集：调查全程三面留痕（18 篇）——账本/事件/trace-details
④ 收口产出：报告 STRUCTURE_VALIDATED（或 UNRESOLVED/REJECTED_*/超时）
⑤ 评分：
   FinalReportSelector 选定报告 → ScenarioEvaluator.evaluate(golden, evidence, …)
   ——typed root_cause 类型化等值+SynonymLexicon 同义词归一
   → verdict 四类之一 + rootCauseHit + TP/FP/FN + silencePenalty
   → SixDimEvaluator 六维原始计数（不聚合单一分）
   → Case Judge（若该案走裁判）：V145 行+校准样本+位置偏差检查
⑥ 汇总：ScenarioMetrics.of → 三指标快照（分子分母全存）→ 六维 sink 落行
⑦ 门禁与对比：GateThresholds/ReleaseAcceptance → EvalCompare（V85/V110）
   → GoldenCandidate 晋升/回归案例准入（RegressionCaseAdmissionService）
```

**REPLAY 变体**（executionKind=REPLAY）："DatasetCaseMapper 从 case_version 产的回放案例——OpenRCA/Meta point-in-time 形态：activate 重投 alert_inbox 冻结 firing 载荷，告警面=DB incident 新 episode，跳过 Prometheus 探针"（GoldenCase.java:20-22）；配套 ReplayToolExecutor/ToolReplayStore（V19，"回放账本无此精确动作记录=REPLAY_MISS，不降级活执行"）——**工具回放存在且语义精确：重放的历史调查在冻结载荷上重跑，工具调用对账本逐条比对**。

## 7. 状态机

评测生命周期状态机（EvalRunLifecycle，V81 ev04_eval_lifecycle）+ 命令状态机；EvalRunWorker 的崩溃三分诊（run 未落库→重排队 PENDING/run RUNNING→FAILED(worker_lost)/**"L 模式 recovery_state 保持 PENDING=恢复未核验，不冒充 VERIFIED"**/已终态→命令对齐）——**评测的恢复面同样"诚实不猜"**（EvalRunWorker.java:23-27）。

## 8. 正常业务流程（第十六部分"为什么不能只看最终报告"的完整论证）

| 只看报告会漏掉 | 对应评测维度 | 实证 |
|---|---|---|
| 报告对但过程是碰运气（乱查 20 次碰中 1 次） | 过程维重复调用/成本维 | 六维"不聚合成单一分"保留镜头 |
| 报告错但格式完美 | 结果维 TP/FP/FN+verdict 四类 | BA-158 tp=0/fp=99/fn=60 |
| 该拒答时硬猜 | unresolvedRate+sliencePenalty | "区分乱猜高覆盖与谨慎高准确" |
| 被注入内容劫持 | 安全维+红队诱饵 GT 取反 | GoldenCase.redteam |
| 证据不支持结论 | EvidenceSupportEvaluator | domain/service |
| 上下文越跑越偏 | ContextDriftEvaluator（消费 V164 压缩消费观测） | domain/service |

## 9. 异常流程

| 异常 | 处理 | 锚 |
|---|---|---|
| 评测 worker 崩溃 | 三分诊（重排队/FAILED(worker_lost)/对齐收口）+不冒充 VERIFIED | EvalRunWorker |
| 案例中途取消 | EvalBatchRunner 案例边界检查 eval_run_command 受理面 | 00 篇 |
| Agent 结构性失败 | STRUCTURE_REJECTED 计 0 分入总分子（明确失败非超时） | ScenarioMetrics |
| Agent 超时/缺席 | TIMEOUT_OR_ABSENT 单独标注不混入 | 同上 |
| 旧镜像抢跑新 schema 命令 | WorkerSchemaFreshnessGuard 陈旧自拒 | EvalRunWorker:32-34 |
| LLM 裁判不稳定 | 校准样本（JudgeCalibrationSample）+位置偏差检查（PositionBiasCheck）+输入消毒（JudgeInputSanitizer） | domain/service |
| 评测数据被当生产误用 | 库身份分权 eval_app/PRIVATE 分区语义 | 00 篇 |

## 10. 并发问题

- 批内逐案例串行（案例边界检查取消）+多批多 worker（SKIP LOCKED）；评测跑 Agent 与生产共用槽位闸——**评测流量与生产流量在模型配额上是竞争者**（容量规划需预留，19 篇）；
- EvalComparisonAutoRecorder 自动记录对比；预注册（V165 EvalPreregistration）——**对照实验先注册后执行**的治理形态。

## 11. 崩溃恢复

评测 worker 的恢复语义与生产同律但更谨慎：run 行从未落库→重排队（稳定身份）；跑批中→FAILED(worker_lost) 且"recovery_state 保持 PENDING=恢复未核验，不冒充 VERIFIED"——**评测结论的可信度分级，未核验的恢复不冒充已验证**。搁浅清扫（BA-192）补"命令账本机制前的老批件"。

## 12. 安全

1. **评测防作弊三件**：红队诱饵 GT 取反（被劫持=命中判负）+Judge 输入消毒（防报告内容操纵裁判）+三维纯函数（"拒绝 LLM-as-judge"用于 canonical 判定面）。
2. **数据集治理**：V133 governance+dataset tier、SourceClass PRIVATE=上线主质量门、V106/V138 授权面——数据集本身被版本化与权限化。
3. **为什么不能只在 Prompt 里告诉模型"认真查"？** 评测的对照面（expectedRootCause 类型化等值+同义词归一）让"认真"变成 tp/fn 数字——**没有对照面的认真无法验证**。

## 13. Agent Harness

评测与运行时的分界（17 篇第 13 节）在代码里的落点：**eval_app 库身份**（写面白名单）、**同一 Agent Harness 全链被评**（"评得出"的根——评测不搞平行实现）、**质量门禁双落点**（报告结构验证是运行时门，ReleaseAcceptance 是发布门）。评测面自己也是 Harness——它的 worker/恢复/陈旧自拒与生产 worker 同构同律。

## 14. 可观测性

评测本身产出可观测数据：六维 sink（V161-164 行为/协作/漂移/回环）+六部分（V152）+难度分层（V143/144）+对比记录（V85/110）+校准样本（V145 系）。评测的评测：预注册（V165）治理实验有效性；JudgeCalibrationStats 统计裁判自身质量。

## 15. 性能和成本

评测成本=每案例一次完整调查（模型费×步数）——所以有 SMOKE panel 快速回归子集（panel=true）与难度分层（L1-L4 分档举证）；三臂对照（MC34）与影子模式（Jev SHADOW/压缩 SHADOW_GENERATE）都是"花小钱先验证"的形态。**评测面的成本治理原则与 19 篇同源：花小钱举证，再花大钱放量。**

## 16. 设计取舍

**① 为什么三维评分"拒绝 LLM-as-judge"用纯函数？**
ScenarioMetrics 头注释原文："AM3 v3.0 §6.4 冻结公式；M3-11~13 纯函数，**拒绝 LLM-as-judge**"。理由：根因命中是**可判定事实**（类型化三元组等值+同义词归一）——用 LLM 判可判定事实=引入不必要的方差与作弊面。LLM 裁判只用于真正开放的面（V145 Case Judge，且带校准/消毒/位置偏差检查）。**"可判定的用代码，开放的用裁判，裁判也要被评测"**。

**② 为什么 UNRESOLVED 不进 conditional_accuracy 分母？**
注释原文给了这个设计的灵魂："单独报告合理拒答率（可区分'乱猜高覆盖'与'谨慎高准确'"。如果拒答进准确率分母，Agent 会学会"宁错勿漏"刷分；不进分母且单独报率，"诚实说不知道"成为被计分的合法策略。**指标公式定义了 Agent 的激励结构**——这是评测设计最深的一课。

**③ 当前方案最大的边界？**
- 案例集覆盖度决定评测上限（合成故障的多样性 vs 真实线上故障的长尾）；
- LLM 裁判虽被治理仍有方差（校准是缓解不是消除）；
- "证据是否支持结论"（EvidenceSupportEvaluator）等维度的判定规则代码化程度【未逐行确认】——部分维可能仍依赖人工复核（EvalReview V87 的存在佐证）。

## 17. 面试背诵卡

【30 秒主答】
"评测脚手架四件套：案例库、批跑器、评分器、质量门。案例库的每条 GoldenCase 带类型化标准根因、期望症状码、难度分层，还有红队诱饵案例——诱饵的标准答案是取反评分的，Agent 被劫持反而判命中等于判负。批跑用和生产完全同一条 Agent 链真跑。评分分两层：结果三维是冻结公式纯函数，拒绝 LLM-as-judge——覆盖率、条件准确率、端到端命中率，其中谨慎拒答不进准确率分母、单独报合理拒答率，这样指标不会激励 Agent 乱猜；六个诊断维是纯函数镜头不聚合成单一分。真正开放的质量面才有 LLM 裁判，而且裁判自己带校准样本、位置偏差检查和输入消毒。上线由 order-arena 私有集做主质量门，所有改动过 EvalCompare 对比和发布准入。"

## 18. 这一层哪些话不能说

1. ❌ "用 GPT 给 Agent 打分" → ✅ 三维 canonical 面纯函数"拒绝 LLM-as-judge"；LLM 裁判仅限开放面且带校准治理。
2. ❌ "评测跑了上千个真实线上故障" → ✅ 案例源=合成注入（order-arena）+冻结重放（REPLAY/OpenRCA 形态）；线上真实故障的覆盖【未确认】。
3. ❌ "工具回放不存在"（提示词担心）→ ✅ **存在**：ReplayToolExecutor/ToolReplayStore（V19）/REPLAY 执行形态/REPLAY_MISS 语义——但要说准：它是"冻结载荷重放+账本比对"，不是生产流量的录制回放。
4. ❌ "指标越高越好" → ✅ coverage 与 accuracy 的张力靠 unresolvedRate 调和——单看一个数会误导。
5. ❌ "评测不影响生产" → ✅ 评测流量与生产共享模型配额与槽位（容量竞争者）；PRIVATE 集直接 gate 上线。
6. 六维各维的完整计数定义/EvidenceSupportEvaluator 实现细节【部分未逐行核对】。

---

# 我现在应该能回答什么

1. 三指标公式是什么？UNRESOLVED 为什么不进准确率分母？（→ 第 4 节冻结语义）
2. 六维是哪六维？为什么不聚合成单一分？（→ "不聚合成单一分"保留诊断镜头）
3. Ground Truth 长什么样？红队案例怎么防作弊？（→ 第 0 节 GoldenCase+GT 取反）
4. Task/Trial/Transcript/Outcome/Grader 对应到哪些真实对象？（→ 第 5 节表）
5. "Agent 说找到根因"和"真找到"之间的判定链是什么？（→ 类型化等值+同义词归一+FinalReportSelector）

# 30 秒背诵卡

见第 17 节。

# 面试官追问卡

**Q1："拒绝 LLM-as-judge"那 V145 的 Case Judge 是干什么的？矛盾吗？**
考什么：分层的辨析。
30 秒答："不矛盾，是分层的：根因命中是可判定事实——类型化三元组等值加同义词归一就能判，用 LLM 反而引入方差和作弊面，所以三维公式是纯函数。Case Judge 管的是真正开放的面——比如报告的叙述质量、证据引用的合理性这类没有唯一答案的维度。而且裁判本身被治理：有校准样本统计裁判质量、有位置偏差检查、有输入消毒防报告操纵裁判。一句话：可判定的用代码，开放的用裁判，裁判也要被评测。"
继续追问 1："裁判和人工复核什么关系？"——答："EvalReview（V87）是人工复核面——裁判给初筛，争议与高风险样本进人工；GoldenCandidate 晋升链里人工是最终闸。"

**Q2：BA-158 那个 tp=0/fp=99/fn=60 是什么故事？**
考什么：评测发现系统性缺陷的能力实证。
30 秒答："那是一次实证：模型把来源标签（logs、prometheus 这种数据源名字）填进了 symptom_codes 症状码槽位——语法上完全合法（字符串数组），Schema 拦不住。但评测一对对照面就现形了：症状码的期望值是告警名，来源标签永远对不上，于是 tp=0、fp=99、fn=60——结构性恒 miss。修复是两层：prompt 协议明令'禁止填来源标签'（BA-158），准入面词表规训同步收紧。这个案例说明评测的真正价值：它能把'感觉不对'变成'结构性错误的精确定位'。"
继续追问 1："怎么防止下次换一种方式作弊？"——答："防不完，所以评测是持续回归不是一次性验收——红队案例、难度分层、新维度 sink 都是在扩大作弊面的覆盖。"

**Q3：UNRESOLVED 不进准确率分母，Agent 会不会学会"永远拒答"刷 accuracy=100%？**
考什么：指标博弈的推演。
30 秒答："会失去 coverage：拒答全算未覆盖，end_to_end_hit_rate 掉零。系统同时报四个数（coverage/conditional_accuracy/end_to_end/unresolvedRate），单刷任何一个数其他三个都会惩罚它。真实的最优策略是'能判则判、不足则诚拒'——这正是设计想要的激励。顺带 silencePenalty 还对'该报不报'加了显式罚分。指标设计的深意：**Agent 会优化你度量的东西，所以度量结构就是产品定义**。"
继续追问 1："结构拒答和超时为什么分开？"——答："STRUCTURE_REJECTED 是明确失败（产出了但不合格）计 0 分；TIMEOUT_OR_ABSENT 是没产出——原因不同处置不同（前者查协议遵循，后者查容量与超时），混在一起会把两类问题搅成一个数字。"

**Q4：评测和生产用同一条链，怎么防止评测数据污染生产？**
考什么：隔离纪律。
30 秒答："三层隔离：一是库身份——eval_app 写面白名单与 control_app 分权（V2 起持续演进）；二是 run 身份——评测 run 有 purpose 标注，发布面负向门对 SHADOW/非生产身份'材料照档零发布零通知'（04 篇 SR §3.2）；三是数据集治理——PRIVATE 分区、版本化、授权面（V133）。评测看到的 Agent 是真的，但它的产出进不了生产通知链。"
继续追问 1："反过来，生产数据会进评测吗？"——答："会，且被治理：ReplayScenarioDriver 的冻结载荷重放（生产/历史故障的 point-in-time 形态）+回归案例准入（RegressionCaseAdmissionService）——生产坏 case 经准入变成回归资产，脱敏与授权面同步走。"

**Q5：如果让你重新设计评测面会改什么？**【设计扩展——设计题答案，不要说成现网实现】
考什么：REDO。
30 秒答："三处：一是加'轨迹级对照'——现在六维看的是单案例轨迹，我会加跨案例的行为指纹对比（同类故障的轨迹相似度），检测'换汤不换药的死记硬背'；二是把 EvidenceSupportEvaluator 的规则代码化程度提满——证据支持判定的自动化越高人工复核越少；三是评测预算的独立配额域——现在评测和生产抢模型配额，独立域让回归跑批不被生产挤饿。三维公式、六维镜头不聚合、红队取反、诚实恢复分级——这四样是骨架，是评测公信力的根。"

# 这层不要乱说什么

1. 不要说"全部用 LLM 打分"——canonical 面纯函数是明文纪律。
2. 不要说"评测数据集有 N 条/覆盖 X%"——数据集规模【未确认】，机制可说。
3. 不要把 REPLAY 说成"生产流量录制回放"——它是"冻结载荷重放+账本比对"（OpenRCA point-in-time 形态）。
4. 不要说"LLM 裁判没被治理"——校准/消毒/位置偏差三件都在。
5. 不要把 taint/UNRESOLVED 处理说成"丢弃"——是诚实分类与分母设计。
6. 六维各维完整实现/EvidenceSupport 细节【部分未逐行】。

# 5 句话总结

1. **为什么需要**："说找到了"不等于"真找到"——只有对照面能把质量变成数字，把改动变成对照实验。
2. **核心机制**：GoldenCase 对照面+三维冻结公式（纯函数拒裁判）+六维诊断镜头（不聚合）+治理过的 LLM 裁判+发布质量门。
3. **上下游协作**：上接数据集治理与场景驱动（order-arena 靶场）；下接发布准入与回归资产池；评测跑的是与生产同一条 Agent 链。
4. **最大风险**：案例覆盖度上限与合成-真实差距；LLM 裁判残余方差；评测与生产的容量竞争。
5. **最大取舍**：用"每案例一次完整调查的成本"换"所有改动的质量可证明、所有Agent 行为可归因"。

---

*本篇完成。下一篇待你指令解锁：《21-故障注入与Agent评测》。*
