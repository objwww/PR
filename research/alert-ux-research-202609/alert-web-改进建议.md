# alert-web 现状盘点 × 业界对照改进建议

> 2026-09-11审查注：原文“准确度已达标”“数据全有，只缺读面”的判断撤回为待证。V5模型账本review_run_id是PR域，不能当evalRunId；现有评测列表11列也不宜继续默认叠加token列。新方案已明确列表减列、案例唯一键/null/请求竞态修复、实验启动、对比与资产版本评测，见[详细执行审查方案](../../docs/告警-评测中心与能力版本演进-审查及详细改造方案-v1.md)。

> 2026-09-11。基于对 alert-web 全部页面的代码走读 + 业界调研（Grafana / PagerDuty / Robusta-HolmesGPT / BigPanda / incident.io / Langfuse / Datadog LLM Obs，截图见本目录 `screenshots/`，调研结论见 `README.md`）。

## 总判断

前端骨架与业界同构度已经很高：状态分组队列（RunsView）、DAG 执行图、SSE 事件流、Claim 三层证据、准确率列（EvalRunsView）都能在 Langfuse/Datadog/Grafana 里找到对应物。**真正的差距集中在四处**：

1. **数据已有但没接**——后端每次模型调用都记了 token/成本账（`ModelCallLedgerEntry`），但读面没开，前端永远显示 `—`；
2. **报告/结论只在 Run 详情里**，告警详情页只有一个跳转按钮，业界做法是结论卡片直接内嵌；
3. **评测中心两个 tab 是「建设中」占位**（对比、成本），而数据源（LiteLLM spend / 模型调用账本）已存在；
4. **队列列表缺「一眼可见的进度」**——`rows[].progress` 字段后端已给，前端表格没渲染；且列表只有手动刷新。

---

## 一、队列进度（排队中 / 处理中 / 处理完）

### 现状

| 页面 | 已有 | 业界对照 |
|------|------|---------|
| RunsView（调查队列） | 三分组 tab（进行中/待干预/已结束）带计数、SLA 横幅、阶段筛选、耗时、卡点、行点击进详情（`RunsView.vue:140-147`） | PagerDuty 状态标签筛选 ✓ 同构 |
| RunDetailView 摘要 | 「任务 done/total/running/blocked」文本行（`RunDetailView.vue:70-77`）；DAG 节点实时变色 + SSE | Langfuse span 树 ✓ 同构 |
| MonitorView | 活跃调查 / 待审查 / 就绪任务积压（含最老等待）stat 卡，30s 自动刷新 | Datadog OOB 看板 ✓ 同构 |

### 差距与建议（按性价比排序）

1. **队列行渲染进度条（P0，纯前端）**
   后端契约 `rows[].progress: "done/total"`（`RunsView.vue:111` 注释已写明），但表格列（`RunsView.vue:58-92`）没渲染。建议「当前阶段」列下加 mini 进度条 `done/total`（运行中的任务跑蓝条，阻塞跑琥珀条）——对应 Grafana Alert List 的状态可视化 + Langfuse trace 进度。

2. **队列列表自动刷新（P0，纯前端）**
   RunsView 只有手动「刷新」按钮（`RunsView.vue:6`），MonitorView 已有 30s 轮询 + visibilitychange 暂停的成熟模式（`MonitorView.vue:214-225`）——直接复用到 RunsView/EvalRunsView。Grafana Play / PagerDuty 队列都是自动刷新的，值班场景手动刷新不可接受。

3. **排队时长独立成列（P1）**
   现在只有「耗时」一个语义模糊的列；业界（PagerDuty 创建时间、Grafana 持续时长）把「排队等待」与「处理耗时」分开。需要后端在 rows 投影补 `queuedAt/startedAt` 两个时间戳，前端拆成「等待 / 处理」两列，QUEUED 长等行给琥珀提示（对应 SLA 横幅已有数据 `oldestReadyWait`）。

4. **告警列表状态语义补全（P2）**
   AlertsView 状态只有 FIRING/RESOLVED（`AlertsView.vue:161`）。业界都有三态：Grafana Normal→Pending→Firing、PagerDuty Triggered→Acknowledged→Resolved。若 incident 投影能带上「调查中/待审查」派生状态（后端已有 run 状态），告警中心一行就能看到「告警→调查→结论」全链路进度，不用跳转。

## 二、处理完的报告与总结

### 现状

- RunDetailView **报告 tab 是占位**：「未生成——调查完成并经人工复核后在此发布」（`RunDetailView.vue:266-271`）。
- RunDetailView 摘要 tab 已有「当前结论 + 待补证」骨架（`RunDetailView.vue:43-67`）——这就是 Robusta 结论卡片的雏形。
- IncidentDetailView 概览只有一条 alert 横幅「调查已完成，结论见调查报告」+ 跳转按钮（`IncidentDetailView.vue:26-36`），**结论本身不在告警页**。
- 后端已有 `RcaReport` 领域模型 + `PostgresRcaReportRepository`，E2E 里有「报告/发布/外发」全链路——**报告数据存在，前端没渲染**。

### 业界做法

- Robusta/HolmesGPT：结论先行卡片——一句话根因 + 资源定位（pod/容器名）+ 证据引用（见 `screenshots/robusta-holmes-investigation.png`）。
- BigPanda：AI Incident Assistant 摘要卡直接嵌在事件详情顶部。
- PagerDuty AI incident summary：事件页第一屏就是 AI 摘要。

### 建议

1. **报告 tab 落地真渲染（P0）**：`GET /api/rca-runs/{id}` 或新端点读 `RcaReport`，按 Robusta 卡片结构渲染：结论一句话（大字）→ 根因 → 影响资源 → 证据引用（复用现有 Claim 证据组件 `RunDetailView.vue:253-258`）→ 建议动作。报告未生成时保留现有占位文案。
2. **告警详情内嵌结论卡（P1）**：IncidentDetailView 概览的「影响与当前结论」卡片里，把 run 的当前结论/最终报告摘要直接拉进来（`d.run` 已在投影里，加一个 claims/report 摘要字段即可），而不是让用户跳走。对照 `screenshots/bigpanda-ai-incident-assistant.png`。
3. **结论可信度标注（P2）**：Robusta/Langfuse 都在结论旁挂置信度/评分标签；我们已有 Claim verdict（CONFIRMED 等），渲染成小徽章即可。

## 三、评测中心：token / 准确度（用户问题：「这些是不是都要在评测展示」）

### 现状

- **准确度已达标**：EvalRunsView 有覆盖率 / 条件准确率 / 端到端命中 / TP-FP-FN（`EvalRunsView.vue:31-42`），EvalRunDetailView 概览复述全字段 + 案例 tab 有 verdict/根因命中/期望 vs 实际根因/失败样本展开（`EvalRunDetailView.vue:74-96`）——这套比很多业界产品的评测列表还细。
- **token/成本完全缺位**：实验列表无 token 列；实验详情「对比」「成本」两个 tab 是 `EmptyState` 占位（`EvalRunDetailView.vue:111-117`）。
- token 只在 MonitorView 出现一个全局数：`tokens24h` 总量（`MonitorView.vue:73-83`）。

### 业界做法

- Langfuse：评分挂在每条 trace 上（`screenshots/langfuse-session-tokens.png` 里的 accuracy/hallucination 标签），另有 Score Analytics 页看趋势和 A/B 对比（`screenshots/langfuse-score-analytics-*.png`）。
- Datadog LLM Obs：开箱看板把 token（prompt/completion 拆分）、错误率、成功率、模型占比放一页（`screenshots/datadog-llmobs-dashboard.png`）。
- **业界共识：token/成本与准确度放在同一中心，不是分开的监控页**——回答用户问题：是的，应该进评测展示，但形态是「每次运行的明细 + 聚合看板」两层。

### 建议

1. **实验列表加「Token 消耗」列（P0）**：总数 + 每案例均值两列。数据源：`eval/domain/litellm/SpendRecord` + `UsageLedgerReconciler`（LiteLLM 用量账本已在 reconciling），缺一个按 evalRunId 聚合的读端点。
2. **成本 tab 落地（P0）**：实验详情成本 tab 三块——总 token/总成本、按模型拆分（prompt/completion 分列，对照 Datadog dashboard 图）、每案例 token TopN（识别吞 token 的场景）。`ModelCallLedgerEntry` 已带 `reviewRunId`（评测 run），按它聚合即得。
3. **准确率趋势图（P1）**：实验列表上方加一张「条件准确率/端到端命中 随时间（按实验）」折线图，对标 Langfuse Score Analytics——评测的核心问题是「改了 prompt/模型后有没有变好」，没有趋势图就只能逐行肉眼比。
4. **对比 tab（P1）**：选两个实验 run 并排 diff：准确率各维 + token/成本 + 失败案例交集。对标 Langfuse compare 模式（`screenshots/langfuse-score-analytics-compare.png`）。
5. **案例行加 token/耗时预算比（P2）**：案例 tab 现有 `latencyMs` 列，补 token 列后失败样本展开里再放「该案例各环节 token 分解」（见下节）。

## 四、Token 粒度：每个环节 + 每个任务总量

### 现状（关键发现：数据全有，读面没开）

后端**每次模型调用**都落了账：`ModelCallLedgerEntry`（`control-app/.../domain/ai/ModelCallLedgerEntry.java:30-38`）含 `promptTokens/completionTokens/totalTokens/costMicros/pricingVersion/latencyMs/requestedModel`，且带 `runStepId`（=DAG 任务/环节）、`attemptId`、`reviewRunId`（=run）三个归并键，由 `PostgresModelCallLedgerRepository` 持久化。Run/Incident 两级还有 `RunBudgetLedger` / `IncidentBudgetLedger` 预算账本。

但读面是关的：`RunQueryService.java:118` `head.put("budget", null)`（注释：「预算账本无读面」）→ 前端 RunDetailView 头部「预算 token」永远 `—`（`RunDetailView.vue:19`），meta tab 预算分项永远显示「预算账本无读面，投影未提供」（`RunDetailView.vue:280-283`）。DAG 任务抽屉也只有状态/优先级/lease/attempts，无 token（`RunDetailView.vue:143-151`）。

### 业界做法（Langfuse 三级模型）

span（环节）→ trace（一次运行）→ session（任务）逐级汇总 token/成本：trace 详情里每个 span 标延迟与 token（`screenshots/langfuse-trace-detail.png`），session 汇总（`screenshots/langfuse-session-tokens.png`），看板按模型/日期聚合（`screenshots/langfuse-cost-tracking-dashboard.png`）。

### 建议（对应关系：task=span、run=trace、incident=session）

1. **开后端读面（P0，本节一切的前提）**：`RunQueryService` 详情投影补三块——`run.budget`（RunBudgetLedger 汇总：总 token/已用/上限）、`tasks[].tokens`（ModelCallLedger 按 `runStepId` 聚合的 prompt/completion/total + cost + latency + 次数）、`run.cost`（按 `reviewRunId` 聚合总成本）。一个 SQL 聚合端点，无需新采集。
2. **Run 头部总览（P0）**：`RunDetailView.vue:19` 的「预算 token」接真值，旁边加「成本 ¥xx / 预算 ¥xx」——对标 Langfuse trace 头。
3. **DAG 节点/任务抽屉显示环节 token（P1）**：抽屉（`RunDetailView.vue:143-151`）加「Token：prompt 12.3k / completion 0.8k（2 次调用）｜成本 ¥0.42｜延迟 8.2s」；DAG 图例/节点 hover 同步。这就是 Langfuse span 视图的直接对标，「每个环节花费的 token」诉求的落点。
4. **告警任务总量（P1）**：IncidentBudgetLedger 已按 incident 记账——IncidentDetailView 概览「调查运行状态」卡（`IncidentDetailView.vue:58-67`）加「该告警累计 token/成本（含重试 run）」一行，对标 Langfuse session 总量。
5. **MonitorView 模型调用卡升级（P2）**：现有两个大数字（调用数/token 数，`MonitorView.vue:73-83`）升级为 Datadog 式看板：token 按 prompt/completion 拆分堆叠、按模型 TopN 占比、7 天趋势。数据同源（ModelCallLedger 按时间窗聚合）。

---

## 优先级汇总

| 优先级 | 项 | 改动面 |
|-------|----|-------|
| P0 | 队列行进度条（progress 字段已投影未渲染） | 纯前端 RunsView |
| P0 | 队列/实验列表 30s 自动刷新（复用 Monitor 轮询模式） | 纯前端 |
| P0 | run 预算/token 读面接通（`head.put("budget", null)` → 真聚合） | 后端 RunQueryService + 前端已就位 |
| P0 | 评测实验 token 列 + 成本 tab 落地 | 后端聚合端点 + EvalRunDetailView |
| P0 | 报告 tab 渲染真报告（RcaReport 已持久化） | 后端读端点 + RunDetailView 报告 tab |
| P1 | DAG 任务抽屉 token/cost（按 runStepId 聚合） | 后端聚合 + RunDetailView 抽屉 |
| P1 | 告警详情内嵌 AI 结论卡 | IncidentDetailView + 投影补字段 |
| P1 | 准确率趋势图 + 实验对比 tab | EvalRunsView/EvalRunDetailView |
| P1 | 队列「等待/处理」双时长列 | 后端投影补时间戳 |
| P2 | 告警任务累计 token（IncidentBudgetLedger 读面） | IncidentDetailView |
| P2 | Monitor token 拆分/按模型/趋势 | MonitorView |
| P2 | 告警列表派生调查状态 | 投影 + AlertsView |
