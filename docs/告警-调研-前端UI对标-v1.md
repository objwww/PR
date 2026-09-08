# 告警 RCA Agent — 企业告警平台与告警 RCA Agent 产品前端 UI 对标调研 v1

> 编号：E-19。日期：2026-09-07（所有来源核对日期均为 2026-09-07）。
> 调研动机：线框图 `docs/告警-前端页面设计-wireframes-v1.html`（P0 登录 / P1 总览 / P2 告警中心 / P3 调查队列与审查 / P4 处置中心 / P5 评测中心 / P6 Agent 监控）被评审"布局太丑"，需在改版前对标真实企业产品的页面设计做法。
> 标注约定：【文档】= 官方文档站（含官方 changelog/what's-new）；【源码】= 开源仓库源码/README；【弱来源】= 官方文档对该维度着墨少，结论可信度降一档。禁止引用二手评测文章。
> 视角约束（贯穿全册的取舍标尺）：本项目是 **2C4G 单机部署的内部工具，Vue3 SPA**——可借鉴的是信息架构、布局模式、语义色惯例；不可借鉴的是重型交互（复杂画布编辑、多人实时协同、Slack 深度集成）。
> 取证说明：本轮全部通过官方文档页/官方 changelog/开源仓库 README 取证，未登录任何产品控制台实测；涉及"截图所示"的结论均来自官方文档对自家 UI 的文字描述。

---

## §0 总结论

1. **告警列表页的收敛形态是"左 facet 面板 + 顶搜索栏（带查询语法）+ 右表格 + 行多选批量操作"**——Datadog Monitor List、Keep、Alerta、PagerDuty Operations Console 四家独立收敛到同一形态；国内云（ARMS/AOM）则是"顶部表单式筛选区 + 表格"的变体。P2 告警中心应照此改版。
2. **详情页的收敛形态是"头部摘要条（状态/严重度/快捷操作）+ 主区 tab + 可选右侧上下文面板"**，tab 内首 tab 恒为"概览/详情"，时间线恒为独立 tab 或右栏。FireHydrant Command Center、Grafana 告警规则详情、ARMS 告警详情（详情/事件/活动三 tab）均如此。P2 的 Incident 详情与 P3 的 Run 详情都适用。
3. **Agent 过程可视化的行业标准解是"同一数据、多视图切换"**：Langfuse trace 详情页给 Tree / Timeline / Graph / Log 四个 tab（Graph 即聚合/展开双模式 DAG），而不是强推单一 DAG 图。P3 的"DAG 任务图/事件流"应做成 tab 切换而非并列堆叠。
4. **评测对比页的行业标准解是"实验为列、用例为行的对比矩阵 + 回归红/改进绿着色 + 可设基准实验"**（LangSmith comparison view），而非并排卡片。P5 实验比较照此。
5. **"AI/Agent 入口内嵌到告警通知与告警详情上"是 RCA 产品的通行做法**（Robusta 在 Slack 告警上放 "Ask Holmes" 按钮、Datadog Watchdog 把 root cause 内嵌进各 explorer），对应我们的 P4 站内通知应带"查看 Run"深链。
6. **severity 语义色四家以上趋同**：critical/firing=红、warning/acknowledged=橙/黄、normal/ok=绿、信息/无=蓝/灰；告警列表行整行着 severity 色（Alerta）或行首色条/badge（incident.io、Grafana）是两种主流表达，后者更适合高密度表格。

---

## §1 总览对比表（产品 × 维度）

表内为浓缩短语，详见 §2 各小节及所附来源 URL。

### 1.1 类一：企业告警/事件管理 SaaS

| 产品 | 一级导航 | 列表页解剖 | 详情页解剖 | 设计语言 | 对我们最有价值的一点 |
|---|---|---|---|---|---|
| PagerDuty | 顶部菜单（Incidents 为首屏） | 顶部计数卡（红=triggered/橙=ack）+ Assigned to me/All 切换 + 状态筛选 + 表格批量操作；Urgency 列定排序 | 点击标题进详情+incident log | 浅色、表格为主、密度中 | 登录首屏即"我的未结告警"行动首页（对标 P1） |
| PagerDuty Operations Console（AIOps 模块） | 同上，AIOps > Operations Console | 列选择器（增删/拖拽排序/调宽且记住）+ Saved views 可分享 + Live/Paused 实时开关（SSE） | 右侧边栏面板（可调宽、记住宽度），内含 alert grouping 细节 | 实时大屏取向 | P6 实时队列的 Live/Paused 开关；P2 侧栏预览模式 |
| incident.io | 左侧 sidebar（可折叠）+ 简化页头 | 首页用 incident **卡片**（hover 展开更多信息）；筛选 popover 用 checkbox 多选；Saved views 存筛选组合 | 状态/severity 用 badge+icon | 浅色、卡片+表格并存、面向全员扫读 | P1 总览用卡片而非表格；筛选项 checkbox 多选 popover |
| Rootly | 左侧导航 | （着墨少） | Incident 详情内 timeline=时间序 feed：可筛选系统/人员事件、星标、导出；事件带 作者/时间戳/来源/可见性 | 浅色、feed 流 | P3 事件流 tab 的"系统事件 vs 人工事件"过滤与星标 |
| FireHydrant | 顶部导航（常驻 "Declare Incident" 主按钮） | （着墨少） | Command Center=主区 tab + 右侧 Details 面板；顶部下拉直接改 Priority/Severity/Milestone；tab 含 Tasks/Summary(AI 摘要)/Status Pages | 浅色、主从分栏 | P2/P4 详情页"右上主操作按钮+头部状态下拉"布局；AI 摘要做独立 tab |
| BigPanda【弱来源】 | — | Incident Feed Views / Tag Views 可定制 | "Related Changes" tab 把变更事件并入 incident 上下文 | — | Incident 详情加"关联变更"tab（P2 可借） |

### 1.2 类二：可观测平台告警模块

| 产品 | 一级导航 | 列表页解剖 | 详情页解剖 | 设计语言 | 对我们最有价值的一点 |
|---|---|---|---|---|---|
| Grafana Alerting | 左侧菜单 Alerts & IRM > Alerting；二级页：Alert rules / Silences / Notification configuration(Contact points 等 tab) / Notification policies / History | 规则行内操作（Pause/Silence/Export/Delete）；可展开看实例 | 详情页 breadcrumb=namespace/group（可点击过滤）；tab=Query and conditions（表达式中间值+图）/ Instances（label selector 搜索）/ History（按状态过滤）/ Details | 深色默认、密度高、表格为主 | P3/P2 的"面包屑即可点击过滤"；规则详情四 tab 划分直接可搬 |
| Datadog Monitors | 顶部主导航（Monitors 下分 List/Triggered/…） | **左 facet 面板 + 顶搜索栏（查询语法）**；勾选批量操作；hover 行尾出单条操作；Saved views；Triggered 页按"监控×group"一行一组 | Monitor status 页（着墨少） | 浅色、密度高 | P2 筛选区形态的行业标准答案 |
| SigNoz | 左侧导航；Alerts 页内三 tab | Alert Rules / Triggered Alerts / Configurations 三 tab 分流"配置"与"正在告警"；列含 Status/Severity/Labels/Firing Since；行尾 actions menu | 创建告警=向导（type→query→conditions→notifications） | 深色默认、密度高 | P2"规则管理"与"活跃告警"分 tab 而非分页面 |
| OpenObserve | 左侧导航（Alerts 页左侧 Folders 面板） | Folders 面板组织 + 搜索；有独立 Incidents 页（告警触发自动建 incident，跨告警关联） | 创建表单右侧常驻 **Live Preview + 自动生成的人类可读 Summary**（可点击跳回字段） | 浅色、密度中 | P5 发起评测的配置页可借"右栏实时摘要"；告警→Incident 自动建页的关系同我们 |
| Keep | 左侧导航 | 四件可定制：Alert table / **CEL 搜索栏（可存 Customized Presets）** / Facets / Columns and Time（按 preset 定制列与主题） | （着墨少） | 深色、密度高 | P2 历史检索页：搜索表达式可存为预设视图 |
| Alerta | 顶部导航（Web UI 极简） | 顶部 Environment tabs（由数据驱动）+ **ASI severity 计数卡（点击即过滤）** + 左 filter sidebar（Status/Service/Group/时间/Customer）+ 查询 DSL 搜索框；行按 severity 整行着色；密度 comfortable/compact 可切 | 详情=全属性/自定义属性/tags/**history 时间线（状态与 severity 变更带时间戳）**/notes/raw JSON | 浅色、密度可切、表格为主 | P2 列表顶部 severity 计数卡点击过滤；详情页 history tab 形态 |

### 1.3 类三：国内云厂商告警控制台

| 产品 | 一级导航 | 列表页解剖 | 详情页解剖 | 设计语言 | 对我们最有价值的一点 |
|---|---|---|---|---|---|
| 阿里云 ARMS 告警管理 | 左侧导航"告警管理 > 告警发送历史/告警事件历史"（层级用 > 展开） | **顶部表单式筛选区**（告警名称/状态：待认领·处理中·已解决/等级 P1–P5/处理人/通知策略/集成类型/创建时间段）+ 搜索按钮 | 三 tab：**详情 / 事件 / 活动**（活动 tab 内再带日志类型/对象/内容筛选）；事件详情=基本信息+**监控数据折线图（可选告警前后 6h/12h/1 天、框选缩放）**+扩展字段标签 | 浅色、密度中、表格+表单 | P2 中文筛选区字段集与"认领/指定处理人/修改等级"行内操作集；事件详情带指标曲线 |
| 腾讯云可观测平台 | 左侧目录"告警管理 > 策略管理 / 告警历史"，可三级（告警治理>告警历史>云产品监控） | 概览页"云服务健康状态"模块→点异常数量**自动带筛选跳列表页**→点对象 ID 下钻监控详情（三级下钻）；2025-04 产品动态：告警控制台筛选列表重构优化 | （着墨少） | 浅色、密度中 | P1 总览卡片数字必须可点击、带筛选参数跳到 P2 |
| 华为云 AOM | 左侧导航"告警中心 > 告警列表"，页内"告警/事件"两页签 | 级别**图形化统计（柱状图开关）**；搜索框统一高级过滤（级别：紧急/重要/次要/提示、资源类型、告警源、关键字模糊、自定义属性 k=v、企业项目）；**活动告警/历史告警切换**；头部时间范围+刷新频率（手动/1min）；导出 XLSX（上限 1 万条） | 告警信息页签（蓝色字段可下钻跳转）+**修复建议（所有告警都有）**；自定义属性值可"添加到查询"；操作列"查看执行历史"弹框（规则详情/执行历史两 tab） | 浅色、密度中 | P2"活动/历史"切换与刷新频率控件；详情页"修复建议"对应我们的人工处置建议区 |

### 1.4 类四：Agent / LLM 运维 UI

| 产品 | 一级导航 | Trace/Run 详情解剖（Agent 过程可视化） | 评测/实验对比页 | 设计语言 | 对我们最有价值的一点 |
|---|---|---|---|---|---|
| Langfuse | 左侧导航（Tracing/Evaluation/Prompts/…） | **Trace 详情多视图 tab：Tree / Timeline / Graph / Log**；Graph=Agent Graph：aggregated（同名步骤折叠计数）/ expanded（一 observation 一节点，happened-before 排序）双模式；ELK 布局跑 Web Worker；选中节点同步 URL `?observation=`；播放头高亮活跃节点 | Dataset 详情页发起 Experiment；Experiments 表聚合分数并排对比 | 深色默认、密度高 | P3 Run 详情的视图 tab 划分与"选中步骤进 URL"（可分享的深链） |
| LangSmith | 左侧导航 | 概念模型 Project > Trace > Run（=span）；tags/metadata 供 UI 过滤分组；feedback 可挂单个 run | **Datasets & Experiments → 勾选≥2 实验 → Compare**：实验为列、用例为行的矩阵；Compact/Full/Diff 三视图（Diff 限 2 实验，JSON/YAML 结构化 diff）；**回归红/改进绿 + 列头"多少条更好/更差"计数，点击列头只显示回归行**；可设 source experiment | 浅色、密度高 | P5 实验比较页的直接蓝本 |
| Arize Phoenix | 左侧导航（Tracing/Evals/Prompts/Datasets 四域） | Trace=逐步骤（model 调用/检索/工具调用/自定义逻辑）；**Human annotations 直接在 UI 给 trace 打 ground truth 标签**；Span Replay 用不同输入重放调试 | Datasets & Experiments：收集 trace 成数据集→不同版本重跑→对比评测结果 | 深色默认、密度高 | P3"人工干预/人工复核"作为 trace 上的 annotation 而非独立页面 |
| Jaeger | 顶部全局导航（Search/Compare/Monitor/Dependencies） | Trace 页=**头部 summary（可折叠）+ minimap 全局缩略 + 瀑布时间线**；Dependencies=服务依赖 DAG（节点>200 自动禁用）；embed 模式可隐藏导航嵌入第三方 | — | 浅色、密度高、工具感强 | P3 步骤轨迹用"summary+minimap+瀑布"三段式；DAG 设节点上限保护 |
| HolmesGPT / Robusta | Web UI/TUI/CLI/K9s 插件多形态 | agentic loop 调查过程产出=**根因+证据+修复建议**，双向写回告警源；Robusta 在 Slack 告警上放 "Ask Holmes" 按钮把 RCA 入口内嵌到告警通知 | 官方 Benchmarks 页公开 150+ 场景 LLM 对比 | 聊天/报告式 | P4 站内通知带"查看 Run 报告"深链；P3 报告页=根因/证据/建议三段式 |
| k8sgpt【弱来源】 | CLI 为主 | 逐 issue 输出诊断+AI explain，无复杂 UI | — | CLI | 反面参照：纯文本报告够用于小团队，不必过度建设可视化 |

---

## §2 分类详述

### 2.1 类一：企业告警/事件管理 SaaS

#### 2.1.1 PagerDuty——Incidents 页与 Operations Console

- **Incidents 页 = 登录首屏**，顶栏菜单 "Incidents" 进入；页面顶部是计数模块：**Your open incidents / All open incidents**，triggered 计数红色、acknowledged 橙色、无未结时蓝色【文档】(https://support.pagerduty.com/main/docs/navigate-the-incidents-page)。
- 计数模块下方：Assigned to me / All 视图切换；状态筛选 Open/Triggered/Acknowledged/Resolved/Any；行首 checkbox 多选后批量 Acknowledge/Reassign/Resolve/Snooze/Merge【文档】同上。
- **Urgency 列决定排序**：高紧急恒在表格顶部，acknowledged 后仍保持 urgency 排序【文档】同上。
- **Operations Console（AIOps add-on）**：实时事件表格的高度可定制形态——列选择器支持增删/拖拽排序/拖边调宽，**且记住用户设置**；最多 10 个自定义字段列；最多 3 列同时排序；视图可 Share 给团队；导出 CSV【文档】(https://support.pagerduty.com/main/docs/operations-console)。
- 点标题或 Alerts 列的计数打开**右侧边栏面板**（可横向拖宽、记住宽度），内含 snooze 时间、alert grouping 细节、alert 字段与 custom details——"不离开列表看上下文"的模式【文档】同上。
- **Live / Paused 更新开关**：默认 Live（SSE 持续推新告警），告警风暴时切 Paused（已有 incident 的更新仍显示，新增不进表）【文档】同上。
- View by Service Health：把活跃 incident 按服务聚合成图，点击下钻到 incident 列表【文档】同上。

可借鉴：P1 登录首屏即"我的未结告警+计数色卡"；P2 侧栏预览；P6 的 Live/Paused。不适合：Service Health 聚合图对单机内部工具过重。

#### 2.1.2 incident.io——卡片式首页与可折叠 sidebar

- 2024-06 UI 改版：app shell 重构，**sidebar 更省空间且可整体折叠**，每页 header 简化；状态与 severity 改用更清晰的 **badge + icon**；筛选 popover 内用 checkbox 多选【文档】(https://incident.io/changelog/ui-refresh-new-design-and-enhanced-user-experience)。
- 首页 incident **卡片**（非表格）面向工程师到管理层全员扫读；**hover 智能展开更多信息**，避免一次塞满【文档】同上。
- Saved views：把一组筛选保存为视图，从 filters 按钮进入【文档】(https://incident.io/changelog/saved-view-for-alerts-and-teams)。

可借鉴：P1 总览用卡片+hover 展开；筛选用 checkbox popover 而非裸表单。不适合：其 Slack-first 工作流深度（我们是 Web 内闭环）。

#### 2.1.3 Rootly——timeline 即权威记录

- Incident 详情页的 timeline = 时间序 feed，支持**筛选系统事件 vs 人员事件、星标关键事件、导出**；每条事件含：描述、操作者（人或系统）、时间戳、来源（Slack/Web/Email/API）、附件、可见性（internal/external）【文档】(https://docs.rootly.com/incidents/incident-timeline/incident-timeline)。
- 多渠道（Web/Slack/Email/API）写入同一 timeline；复盘直接拉 timeline 事件生成【文档】同上。

可借鉴：P3 Run 详情事件流 tab 的"事件来源徽标 + 系统/人工过滤 + 星标"；可见性字段对应我们报告的脱敏分级。不适合：多渠道汇入（我们只有站内渠道）。

#### 2.1.4 FireHydrant——Command Center 的主从分栏

- 每个 incident 有自己的主页+timeline；**顶导航常驻 "Declare Incident" 主按钮**；Command Center=主区 + 右侧 Details 面板（hover 出编辑铅笔）；**顶部下拉直接改 Priority/Severity/Milestone**，Resolve 按钮在右上；解决后该位置变为 Start retrospective【文档】(https://docs.firehydrant.com/docs/web-ui-responder-guide)。
- tab 划分：Tasks（+Follow-ups 同 tab 下方）、Status Pages、**Summary tab（AI 生成的 incident 摘要+milestone 视图+历史摘要汇编）**【文档】同上 + (https://docs.firehydrant.com/docs/ai-powered-incident-summaries)。
- timeline 事件可**星标**，星标事件在复盘页默认优先显示并随 PDF/Confluence 导出；Edit（改字段）与 Update（发正式公告）是两个概念【文档】responder guide 同上。

可借鉴：P2 Incident 详情"右上主操作按钮随状态机流转切换文案"；AI 报告/摘要做独立 tab；星标证据进复盘。不适合：Status Pages（对外状态页）与 Runbook 自动化编排 UI。

#### 2.1.5 BigPanda / Moogsoft（AIOps 关联压缩的 UI 表达）【弱来源】

- Incident 定义为"一条或多条告警关联出的高层级问题"；设置侧提供 **Incident Feed Views / Incident Tag Views**（可定制 feed 与标签视图）与 Automated Incident Analysis 配置【文档】(https://docs.bigpanda.io/docs/incidents)。
- Incident 上下文含 "Related Changes" tab，把变更事件并入根因分析视野【文档】（第三方引述官方功能，可信度弱，仅作方向参考）。

可借鉴：P2 Incident 详情加"关联变更/关联 Run"区。不适合：其企业级拓扑映射可视化。

### 2.2 类二：可观测平台告警模块

#### 2.2.1 Grafana Alerting——配置/通知/监控三段信息架构

- 信息架构：左侧菜单 Alerts & IRM > Alerting 下分 **Alert rules /（实例与 triage）/ Silences / Notification configuration（Contact points、Templates 等 tab）/ Notification policies / History / Active notifications**——"规则定义、抑制、通知路由、历史"分域【文档】(https://grafana.com/docs/grafana-cloud/observe-and-act/alert-and-measure-reliability/alerting/monitor-status/view-alert-state/) + (https://grafana.com/docs/grafana/latest/alerting/configure-notifications/manage-contact-points/)。
- Alert rules 列表：每行显示状态、summary 与**行内操作（Pause evaluation、Silence notifications、Export、Delete）**；点击展开看实例【文档】view-alert-state 同上。
- 规则详情页：**breadcrumb 显示 namespace/group 且可点击反向过滤**；内容分四 tab——**Query and conditions**（表达式每步中间值+时序图）/ **Instances**（label selector 搜索，如 `environment=production,severity!=warning`）/ **History**（按状态过滤）/ **Details**（元数据+注解）【文档】同上。
- Contact points tab：显示每个 contact point 被多少 notification policy 引用并可跳转、**最近投递状态**、Test 按钮、导出 JSON/YAML/Terraform【文档】manage-contact-points 同上。
- 面板层语义色：Alerting=破碎红心+红色注解线，Normal=绿心【文档】view-alert-state 同上。2025-12 新增专门的 firing/pending 告警 triage 可视化页【文档】(https://grafana.com/whats-new/2025-12-01-enhanced-alert-visualization-for-streamlined-triage/)。

可借鉴：P3 Run 详情面包屑可点击过滤；四 tab 划分（查询/实例/历史/元数据）可直接映射为（DAG/事件流/历史/配置快照）；"通知配置"域的投递状态显示可借鉴到 P4 站内通知。不适合：Notification policies 的树形嵌套路由编辑器（重交互）。

#### 2.2.2 Datadog Monitors——facet+搜索栏的列表范式与 Watchdog 内嵌 AI

- Monitor List：**左 facet 面板 + 顶部搜索栏**构造查询；勾选后右上方批量操作（Mute/Unmute/Resolve/Delete/Edit Recipients/Edit Tags/Edit Teams/Export to Terraform）；hover 行尾出单条 Edit/Clone/Mute/Delete；点击名称进 status 页【文档】(https://docs.datadoghq.com/monitors/manage/)。
- Triggered Monitors 页只显示触发态（Alert/Warn/No Data），**按"监控 × reporting source group"一行一组**（14 个 host 触发=14 行），支持按 group 维度过滤与批量 mute【文档】同上。Saved views 跨端同步【文档】(https://docs.datadoghq.com/mobile/)。
- **Watchdog（AI 引擎）**：免配置；产品形态上把 root cause / user impact / context insights **内嵌在各 explorer 页面内**，而非独立的"AI 页"【文档】(https://docs.datadoghq.com/watchdog/)。

可借鉴：P2 列表=facet+搜索栏；"按监控×实例展开成行"对应我们"告警实例 N/M 展开"；AI 结论内嵌到 P2 Incident 详情而非只在 P3。不适合：Export to Terraform 等 IaC 出口。

#### 2.2.3 SigNoz——三 tab 分流配置与活跃告警

- Alerts 页三 tab：**Alert Rules（配置管理）/ Triggered Alerts（正在 firing，实时）/ Configurations（routing policy + planned maintenance）**【文档】(https://signoz.io/docs/alerts-management/)。
- 规则表列：Status(OK/disabled)、Alert Name、Severity、Labels；右上角字段筛选（Created At/By 等）、搜索栏（name/severity/label）、行尾 actions menu（Enable/Edit/Clone/Delete）；Triggered tab 有 **Firing Since 列、Filter by Tags、Group by（alert name/severity）**【文档】同上。
- 告警创建=分步向导（alert type → query → conditions → notifications）【文档】(https://signoz.io/docs/alerts/)。

可借鉴：P2"告警规则管理"与"活跃告警（Firing Since）"分 tab；Group by severity/name 是低成本高分组的交互。不适合：无。

#### 2.2.4 OpenObserve——配置页右栏实时摘要

- 告警创建表单=**扁平两 tab（Alert Rules / Advanced）**，右侧面板常驻 **Live Preview（用近期数据试算会不会触发）+ 自动更新的人类可读 Summary（点击摘要段可跳回对应字段）**【文档】(https://openobserve.ai/docs/user-guide/alerts/)。
- Alerts 页左侧 Folders 面板组织告警；**独立 Incidents 页**：告警触发可自动建 incident，做跨告警关联跟踪【文档】同上。Composite alerts 用 AND/OR/NOT 布尔组合既有告警降噪【文档】同上。

可借鉴：P5"发起评测"配置页用"左表单 + 右实时摘要（可读语句）"；告警→Incident 自动建页与我们的 Prometheus 告警→Incident 关系同构。不适合：SQL/PromQL 全屏编辑器（重）。

#### 2.2.5 Keep——预设视图即一等公民

- Alert Management 页四件全部可定制：**Alert table / Search Bar（CEL 表达式，可存为 Customized Presets）/ Facets / Columns and Time（列与主题按 preset 定制）**【文档】(https://docs.keephq.dev/alerts/overview)。Incidents 同样有 facets（预定义属性类别用于过滤）【文档】(https://docs.keephq.dev/incidents/facets)。

可借鉴：P2 历史检索页"查询表达式 + 存为预设视图"；列配置按视图记忆。不适合：CEL 表达式对内部工具用户门槛高，可做"高级模式"开关。

#### 2.2.6 Alerta——severity 计数卡与整行着色

- 主屏=告警列表：**顶部 Environment tabs（Production/Development/ALL，由现有数据驱动生成）**；**ASI 面板=severity 计数卡，点击即过滤列表**；左 filter sidebar（Status/Service/Group/时间范围/Customer facets）；搜索框支持查询 DSL（`severity:critical AND status:open`）；**行按 severity 整行着色**；密度 comfortable/compact 可切；CSV 导出；列可由服务端 COLUMNS 配置【文档】(https://docs.alerta.io/webui/alerts.html)。
- 详情视图：全属性、自定义属性、tags、**history 时间线（状态与 severity 变更带时间戳）**、notes、raw JSON；操作 Open/Ack/Shelve/Close/Watch/Delete，多选批量执行【文档】同上。

可借鉴：P2 顶部 severity 计数卡点击过滤（P1 到 P2 的过渡也用它）；详情页 history tab 对应我们的"状态历史"。不适合：整行着色在高密度表格下偏噪，建议改为行首色条。

### 2.3 类三：国内云厂商告警控制台（中文企业控制台惯例）

#### 2.3.1 阿里云 ARMS 告警管理

- 导航：左侧导航"告警管理 > 告警发送历史 / 告警事件历史"，层级用 > 分隔【文档】(https://help.aliyun.com/zh/arms/alarm-operation-center/view-historical-alerts)。
- 列表页：**顶部表单式筛选区**（告警名称、状态=待认领/处理中/已解决、**等级 P1–P5**、处理人、通知策略、集成类型、创建时间段）+ 搜索按钮【文档】同上。
- 告警详情页三 tab：**详情 / 事件 / 活动**；活动 tab 内嵌二级筛选（日志类型/日志对象/日志内容）+ 通知发送记录（时间、策略、方式、对象、响应状态码）【文档】同上。
- 事件详情面板：基本信息区 + **监控数据折线图（可切告警前后 6h/12h/1 天、框选缩放、重置）** + 扩展字段标签区【文档】同上。
- 处置操作：认领（自设为处理人）/ 解决 / 指定处理人 / 修改等级 / 推送工单系统；多操作走"更多"下拉【文档】同上。

可借鉴：P2 中文筛选区字段集、P4"认领/指定处理人"操作集与按钮归组（主操作外露、次操作进"更多"）。惯例启示：**国内控制台=左导航+顶部表单筛选+中等密度表格**，与 Datadog 的 facet 面板形态不同但用户预期成熟。

#### 2.3.2 腾讯云可观测平台

- 导航：左侧目录"告警管理 > 策略管理 / 告警历史"，可三级（告警治理 > 告警历史 > 云产品监控）【文档】(https://github.com/tencentyun/qcloud-documents/blob/master/product/计算与网络/VPN连接/操作指南/设置告警.md)（官方文档源仓库）。
- **三级下钻路径**：监控概览页"云服务健康状态"模块 → 点击异常对象数量**自动带筛选条件跳转列表页** → 点击对象 ID 进监控详情【文档】(https://www.tencentcloud.com/zh/document/product/248/32831?lang=zh)。
- 持续打磨筛选体验：2025-04 产品动态"告警控制台筛选列表重构优化上线"【文档】(https://cloud.tencent.com/document/product/248/88467)。

可借鉴：P1 总览所有统计数字必须可点击、带筛选参数深跳到 P2 列表（这是"行动首页"成立的必要条件）。

#### 2.3.3 华为云 AOM

- 导航：左侧导航"告警中心 > 告警列表"，页内"告警 / 事件"两页签【文档】(https://support.huaweicloud.com/usermanual-aom2/mon_01_0011.html)。
- 列表页：**级别图形化统计（柱状图，可开关）**；搜索框统一高级过滤——级别（紧急/重要/次要/提示）、资源类型、告警源、关键字模糊、**自定义属性 k=v**、企业项目；**活动告警 / 历史告警切换**（超期自动清除转历史）；头部有时间范围选择+**刷新频率（手动/1 分钟）**；导出 XLSX（选中/全部，上限 1 万条）【文档】同上。
- 告警详情：告警信息页签（**蓝色字段可下钻跳转**到日志/指标/组件详情）+ **修复建议（所有告警都提供）**；自定义属性值可"复制"或**"添加到查询"**（回写到列表搜索框）；操作列"查看执行历史"弹框内含"告警规则详情 / 告警执行历史"两 tab【文档】同上。

可借鉴：P2"活动/历史"切换、头部刷新频率控件（对 P6 同样适用）；详情页字段值"添加到查询"是低成本高体验的筛选联动；**"修复建议"区直接对应我们 RCA 报告里的人工处置建议**；"执行历史"弹框对应 P3 的 Run 历史入口。

### 2.4 类四：Agent / LLM 运维 UI（对应 P3/P5/P6）

#### 2.4.1 Langfuse——trace 详情的多视图 tab 与 Agent Graph

- **Trace 详情页多视图 tab：Tree / Timeline / Graph / Log**（社区另有 Flame 火焰图提案，证实该 tab 序列为现行结构）【源码/官方 issue】(https://github.com/langfuse/langfuse/issues/16219)。
- **Graph=Agent Graph 视图**：aggregated 模式把同名重复步骤折叠计数；expanded 模式一个 observation 一节点、按 happened-before 兄弟排序表达 fork/join；两模式是画布上的模式切换【文档】(https://langfuse.com/docs/observability/features/agent-graphs)。实现上：ELK 布局跑在 **Web Worker**（慢布局不阻塞页面）、选中节点同步 URL `?observation=` 参数、**播放头（PlayheadContext）高亮活跃节点**表达实时/回放【源码】(https://github.com/langfuse/langfuse/tree/main/web/src/features/trace-graph-view)（该目录 README）。
- **Log View**：把整个 trace 的 observation 拼接成单页可滚动文本，便于通读 agent 每一步【文档】(https://langfuse.com/changelog/2025-11-05-langfuse-for-agents)。
- Sessions：sessionId 聚合多 trace，做整次会话的 session replay【文档】(https://langfuse.com/docs/observability/features/sessions)。
- Experiments via UI：从 **Dataset 详情页**发起（Start Experiment），配置 prompt/模型/评估器后跑批，Experiments 表看聚合分数并并排对比【文档】(https://langfuse.com/docs/evaluation/experiments/experiments-via-ui)。

可借鉴：P3 Run 详情直接搬"Tree/Timeline(Graph)/事件流(Log)/证据/报告"多 tab；选中 Task 进 URL 便于审查时互发链接；实时表达用"活跃节点高亮+播放头"而非闪烁动画。不适合：双模式 DAG 画布+ELK Worker 对 Vue3/2C4G 偏重，可降级为静态 SVG DAG（只画一次、不缩放）。

#### 2.4.2 LangSmith——实验对比矩阵

- 概念模型 Project > Trace > Run（run≈OTel span）；tags/metadata 供 UI 过滤与分组；feedback 可挂到单个 run（线上/离线/annotation queue 三来源）【文档】(https://docs.smith.langchain.com/observability/concepts)。
- **对比视图**：Datasets & Experiments → 选 dataset → Experiments tab → 勾选≥2 个实验 → Compare。**实验为列、用例为行**；右上切换 Compact/Full/**Diff**（Diff 限 2 实验，对 JSON/YAML 输出做结构化 diff 高亮）；**回归红、改进绿，列头显示"多少条更好/更差"，点击列头即只显示回归/改进行**；可设 source experiment（基准）；支持整表筛选+列级筛选；点行开详情面板（两实验并排+图表）【文档】(https://docs.langchain.com/langsmith/compare-experiment-results)。

可借鉴：P5 实验比较页照此矩阵做；"只看回归"过滤对发布门审查极关键；基准实验概念对应我们的"以上一发布版本为 baseline"。不适合：单元格内全文 diff（我们的输出是结构化结论，做字段级差异徽标即可）。

#### 2.4.3 Arize Phoenix——四域工作流与人工标注内嵌

- 功能四域：**Tracing / Evaluation / Prompt Engineering(Playground) / Datasets & Experiments**，对应"调试→打分→迭代提示词→系统化实验"工作流【文档】(https://arize.com/docs/phoenix)。
- **Human annotations：直接在 UI 里给 trace/span 打 ground truth 标签**；Span Replay 用不同输入重放 LLM 调用做调试【文档】同上。

可借鉴：P3"人工干预/人工复核"做成 trace 上的 annotation 层（评分、备注、修正标签），而非独立工单页；P5 逐案例诊断可从分析页跳回单条 Run 的 trace 视图。不适合：Prompt Playground（我们不做 prompt 在线调试）。

#### 2.4.4 Jaeger / Tempo——瀑布图的三段式与 DAG 规模上限

- Trace 页=**头部 summary（服务数/span 数等，可折叠）+ minimap 全局缩略图 + 瀑布时间线**三段式；Search 页=筛选表单+结果散点图（可关）+结果表；Dependencies=服务依赖 DAG，**节点数超 200 自动禁用**保护浏览器【文档】(https://www.jaegertracing.io/docs/2.11/frontend-ui/)。
- embed 模式（`uiEmbed=v0`）可隐藏导航把 trace 页嵌进第三方系统；trace ID 显示长度可配【文档】同上。

可借鉴：P3 步骤轨迹用"摘要条+缩略+瀑布"而非大画布；DAG/瀑布设节点上限，超限降级为列表。不适合：embed 模式无需求。

#### 2.4.5 HolmesGPT / Robusta——RCA 报告的呈现与入口内嵌

- 产品形态：CLI / **Web UI / TUI / K9s 插件**多前端；官方托管为 Robusta SaaS【文档】(https://holmesgpt.dev/) + (https://github.com/robusta-dev/holmesgpt/blob/master/docs/installation.md)。
- 调查模式=agentic loop 多源取数→产出**根因+证据+修复建议**；**双向告警集成**——从 AlertManager/PagerDuty/OpsGenie/Jira 拉告警，并把 findings 写回告警源；Robusta 在 Slack 告警消息上放 **"Ask Holmes" 按钮**，把 RCA 入口直接内嵌在告警通知上【源码/README】(https://github.com/robusta-dev/holmesgpt)。
- 官方 Benchmarks 页公开 150+ 场景的 LLM 表现对比【文档】holmesgpt.dev 同上。

可借鉴：P4 站内通知卡片带"查看 Run / 查看报告"深链（=Ask Holmes 按钮的站内版）；P3 报告页固定"根因 → 证据链 → 处置建议"三段式；P6/P5 可用公开 benchmark 页式的只读指标页。不适合：Slack/PR 自动化闭环。

#### 2.4.6 k8sgpt【弱来源】

- CLI 为主：逐 issue 输出诊断结果+AI explain，无复杂 UI【文档】(https://docs.k8sgpt.ai/)。

定位：反面参照——小团队内部工具用"结构化文本报告"已能解决 80% 问题，可视化投入应集中在 P2/P3 两个高频页。

---

## §3 对本项目 7 个页面逐页的"可借鉴布局模式清单"

### P0 登录
- 无对标必要；保持最小（单机内网工具）。唯一建议：登录后**默认落地 P1 行动首页**（PagerDuty 以 Incidents 页为登录首屏的先例，§2.1.1）。

### P1 总览（行动首页）
- 顶部计数卡用语义色区分急迫度：triggered/FIRING=红、处理中=橙、清零=蓝/绿（PagerDuty §2.1.1；Alerta ASI 计数卡 §2.2.6）。
- **所有数字可点击，带筛选参数深跳 P2 列表**（腾讯云概览→列表三级下钻 §2.3.2；Alerta 计数卡点击过滤 §2.2.6）。
- 进行中的 Incident/Run 用**卡片**呈现，hover 展开次要信息（incident.io §2.1.2），避免首页就上高密度表格。
- 页面只回答"现在需要我做什么"：待认领 Case、卡住/逾期 Run、待审查报告三组即够，全局图表下沉到 P6。

### P2 告警中心（列表 + Incident 详情 + 历史检索）
- 列表页主体=**左 facet 面板（状态/severity/服务/分类九视图/时间）+ 顶搜索栏 + 右表格**（Datadog §2.2.2；Keep §2.2.5；Alerta §2.2.6）；中文表单式筛选（ARMS §2.3.1）作为 facet 面板的等价替代亦可，但建议统一成 facet 以便与历史检索复用组件。
- 表格：行首 severity 色条+badge（incident.io §2.1.2；Alerta 整行着色的降噪版 §2.2.6）；行多选+顶部批量操作条（PagerDuty/Datadog/Alerta 四家一致）；hover 行尾出单条操作（Datadog §2.2.2）；高 urgency/severity 恒置顶（PagerDuty §2.1.1）。
- **活动/历史切换**与头部刷新频率控件（手动/1min）（华为 AOM §2.3.3）；规则管理 vs 活跃告警分 tab（SigNoz §2.2.3）。
- 历史检索页：查询表达式可**存为预设视图**，列配置按视图记忆（Keep §2.2.5；PagerDuty 列选择器记住设置 §2.1.1）。
- Incident 详情=**头部摘要条（severity badge+状态+右上主操作按钮随状态机切换文案）+ tab：详情/关联事件/时间线(Run 轨迹入口)/活动记录**（FireHydrant §2.1.4；ARMS 详情三 tab §2.3.1；Rootly timeline §2.1.3）。
- 事件/实例详情带**监控数据折线图（告警前后 6h/12h/1 天可选、框选缩放）**（ARMS §2.3.1；Grafana 详情首 tab 带时序图 §2.2.1）。
- 列表行点击先开**右侧抽屉预览**（可调宽、记住宽度），再进全页详情（PagerDuty Operations Console §2.1.1）。

### P3 调查队列与审查（Run 队列 + Run 详情）
- Run 详情=**多视图 tab：DAG 任务图 / 步骤瀑布(时间线) / 事件流(Log) / 证据 / 报告 / 干预**——同一份数据多视图切换，不要并列堆叠（Langfuse Tree/Timeline/Graph/Log §2.4.1）。
- 步骤轨迹用"**摘要条+minimap 缩略+瀑布**"三段式（Jaeger §2.4.4）；DAG 节点设上限、超限降级列表（Jaeger Dependencies>200 禁用先例 §2.4.4）。
- 实时表达=**活跃节点高亮+播放头**，事件流追加到底部并给"系统事件/人工事件"过滤与星标（Langfuse PlayheadContext §2.4.1；Rootly §2.1.3）；避免整页闪烁刷新。
- **选中 Task/事件写入 URL 参数**，审查沟通可互发深链（Langfuse `?observation=` §2.4.1）。
- 报告页固定三段式：**根因 → 证据链（可展开原始工具输出）→ 处置建议**（HolmesGPT §2.4.5；华为"修复建议"区 §2.3.3）。
- 人工干预做成 Run 上的 **annotation/操作层**（通过/打回/备注/修正标签），而非另起工单页（Phoenix human annotations §2.4.3）。
- Run 队列页复用 P2 列表范式：facet（状态/告警类型/耗时/预算消耗）+表格+批量操作（重试/取消）。

### P4 处置中心（OperatorCase + 站内通知）
- Case 详情沿用 P2 详情骨架（头部摘要+tab），处置操作集=**认领/指定处理人/改级/解决**，次操作收进"更多"（ARMS §2.3.1；FireHydrant 顶部下拉改 severity/milestone §2.1.4）。
- 站内通知卡片带**"查看 Run 报告 / 前往审查"深链按钮**（Robusta "Ask Holmes" 内嵌告警通知先例 §2.4.5）。
- Case 列表与通知列表分 tab 同页（SigNoz 三 tab 分流先例 §2.2.3），通知支持按已读/类型过滤。

### P5 评测中心
- 发起评测配置页=**左表单 + 右常驻"人类可读配置摘要"（点击跳回字段）**（OpenObserve Live Summary §2.2.4）。
- 批次列表=表格+状态 badge；**实验比较页=实验为列、用例为行的对比矩阵，回归红/改进绿、列头计数、只看回归过滤、可设基准实验**（LangSmith §2.4.2）——这是 P5 最重要的一条。
- 逐案例诊断从矩阵行点击进详情面板，可再跳单条 Run 的 P3 视图（LangSmith 详情面板 §2.4.2；Phoenix trace 联动 §2.4.3）。
- 数据集治理从 Dataset 详情页发起实验（Langfuse §2.4.1）；发布门结论页用"六维指标卡+INCONCLUSIVE 显著标识"的只读报告页形态（HolmesGPT benchmark 只读页 §2.4.5）。

### P6 Agent 监控（预算/队列积压/worker 饱和度）
- **Live/Paused 更新开关**（告警风暴/高峰期暂停推流，PagerDuty Operations Console §2.1.1；底层 SSE 与本仓 M5 调研的 after_seq 游标方案兼容）。
- 头部时间范围+刷新频率控件（华为 AOM §2.3.3；Grafana meta-monitoring 思路：把 Agent 自身当监控对象，§2.2.1 引用的 Grafana meta monitoring 页）。
- 三个指标域（预算/积压/饱和度）各一张卡片+小趋势图即可，不做可编排 dashboard（Grafana 的 panel 编辑体系对内部工具过重）。

---

## §4 共性设计规律总结

1. **列表页形态高度收敛**：国外=左 facet+顶搜索栏+表格（Datadog/Keep/Alerta/PagerDuty Console），国内=顶部表单筛选区+表格（ARMS/AOM/腾讯）——共同点是"筛选区与表格同页、筛选即所见、批量操作必备"。
2. **详情页=头部摘要条+tab+可选右栏上下文**，首 tab 恒为概览；主操作按钮放右上且文案随状态机流转切换（FireHydrant Resolve→Start retrospective；PagerDuty 顶部 Acknowledge/Resolve）。
3. **时间线/事件流是详情页一等 tab**：统一为"时间序 feed+来源徽标+系统/人工过滤+星标+导出"（Rootly/FireHydrant/Alerta history/ARMS 活动 tab 四家趋同）。
4. **severity 语义色趋同**：critical/firing=红、warning/ack=橙黄、normal=绿、info/none=蓝灰；表达上分"整行着色（Alerta，低密度适用）"与"行首色条+badge（incident.io/Grafana，高密度适用）"两派，我们取后者。
5. **状态色与 severity 色分离**：状态（open/ack/shelved/closed）用文字 badge，severity 用色条/色 badge——Alerta 即如此（行色=severity，状态=文字标签）。
6. **实时更新要可暂停**：Live/Paused（PagerDuty）、刷新频率下拉（AOM）是标准件；SSE 推送+断线游标回放与本仓既有后端方案一致。
7. **Agent 过程可视化的共识=多视图 tab 而非单一 DAG**：Tree（层级）、Timeline/瀑布（时间）、Graph/DAG（依赖）、Log（通读文本）各回答一个问题；Langfuse 四 tab 是最完整参照。
8. **评测对比的共识=矩阵+回归高亮+基准实验**（LangSmith）；没有任何一家用并排卡片做实验对比。
9. **AI 结论内嵌优于独立 AI 页**：Watchdog 嵌进 explorer、Ask Holmes 嵌进告警通知——我们的 RCA 摘要应同时出现在 P2 Incident 详情和 P4 通知里，P3 只是完整版。
10. **可配置但记住用户偏好**：列选择、面板宽度、密度、视图预设全部"改一次、记住"（PagerDuty/Alerta/Datadog saved views）——单机内部工具用 localStorage+用户偏好表即可低成本实现。
11. **图规模必须设上限**：Jaeger DAG>200 节点自动禁用——我们的 DAG/瀑布超阈值应降级为分组列表，保护 2C4G 机器的浏览器端。
12. **中文控制台惯例**：左导航+">"层级、表单式筛选、操作列归组（主操作外露+更多下拉）、导出 XLSX 限条数——面向国内运维用户时这些惯例的用户预期优先于国外 facet 形态。

---

## §5 来源清单（全为一手来源）

| # | 来源 | URL |
|---|---|---|
| 1 | PagerDuty：Navigate the Incidents Page | https://support.pagerduty.com/main/docs/navigate-the-incidents-page |
| 2 | PagerDuty：Operations Console | https://support.pagerduty.com/main/docs/operations-console |
| 3 | incident.io：UI refresh changelog | https://incident.io/changelog/ui-refresh-new-design-and-enhanced-user-experience |
| 4 | incident.io：Saved views changelog | https://incident.io/changelog/saved-view-for-alerts-and-teams |
| 5 | Rootly：Incident Timeline | https://docs.rootly.com/incidents/incident-timeline/incident-timeline |
| 6 | FireHydrant：Web UI Responder Guide | https://docs.firehydrant.com/docs/web-ui-responder-guide |
| 7 | FireHydrant：AI-Powered Incident Summaries | https://docs.firehydrant.com/docs/ai-powered-incident-summaries |
| 8 | BigPanda：Incidents | https://docs.bigpanda.io/docs/incidents |
| 9 | Grafana：View alert state（规则列表/详情四 tab/语义色） | https://grafana.com/docs/grafana-cloud/observe-and-act/alert-and-measure-reliability/alerting/monitor-status/view-alert-state/ |
| 10 | Grafana：Manage contact points | https://grafana.com/docs/grafana/latest/alerting/configure-notifications/manage-contact-points/ |
| 11 | Grafana：Enhanced Alert Visualization for Triage（what's-new） | https://grafana.com/whats-new/2025-12-01-enhanced-alert-visualization-for-streamlined-triage/ |
| 12 | Grafana：Alerting meta monitoring | https://grafana.com/docs/grafana/latest/alerting/monitor/ |
| 13 | Datadog：Monitor List | https://docs.datadoghq.com/monitors/manage/ |
| 14 | Datadog：Mobile（saved views 跨端） | https://docs.datadoghq.com/mobile/ |
| 15 | Datadog：Watchdog | https://docs.datadoghq.com/watchdog/ |
| 16 | SigNoz：Alert List Page | https://signoz.io/docs/alerts-management/ |
| 17 | SigNoz：Alerts overview | https://signoz.io/docs/alerts/ |
| 18 | OpenObserve：Alerts（创建表单布局/Live Preview/Incidents 页） | https://openobserve.ai/docs/user-guide/alerts/ |
| 19 | Keep：Alert Management overview | https://docs.keephq.dev/alerts/overview |
| 20 | Keep：Incidents facets | https://docs.keephq.dev/incidents/facets |
| 21 | Alerta：Alerts View（web console） | https://docs.alerta.io/webui/alerts.html |
| 22 | 阿里云 ARMS：查看告警发送历史（含详情三 tab） | https://help.aliyun.com/zh/arms/alarm-operation-center/view-historical-alerts |
| 23 | 腾讯云：设置告警（导航惯例，官方文档源仓库） | https://github.com/tencentyun/qcloud-documents/blob/master/product/计算与网络/VPN连接/操作指南/设置告警.md |
| 24 | 腾讯云：异常排障（概览→列表→详情下钻） | https://www.tencentcloud.com/zh/document/product/248/32831?lang=zh |
| 25 | 腾讯云：产品动态（告警筛选列表重构） | https://cloud.tencent.com/document/product/248/88467 |
| 26 | 华为云 AOM：查看告警或事件 | https://support.huaweicloud.com/usermanual-aom2/mon_01_0011.html |
| 27 | Langfuse：Observability overview | https://langfuse.com/docs/observability/overview |
| 28 | Langfuse：Agent Graphs | https://langfuse.com/docs/observability/features/agent-graphs |
| 29 | Langfuse：trace-graph-view 源码 README | https://github.com/langfuse/langfuse/tree/main/web/src/features/trace-graph-view |
| 30 | Langfuse：trace 详情 tab 序列佐证（官方 issue #16219） | https://github.com/langfuse/langfuse/issues/16219 |
| 31 | Langfuse：Log View changelog | https://langfuse.com/changelog/2025-11-05-langfuse-for-agents |
| 32 | Langfuse：Sessions | https://langfuse.com/docs/observability/features/sessions |
| 33 | Langfuse：Experiments via UI | https://langfuse.com/docs/evaluation/experiments/experiments-via-ui |
| 34 | LangSmith：Observability concepts | https://docs.smith.langchain.com/observability/concepts |
| 35 | LangSmith：Compare experiment results | https://docs.langchain.com/langsmith/compare-experiment-results |
| 36 | Arize Phoenix：Docs overview | https://arize.com/docs/phoenix |
| 37 | Jaeger：Frontend/UI Configuration（trace 页三段式/DAG 上限） | https://www.jaegertracing.io/docs/2.11/frontend-ui/ |
| 38 | HolmesGPT：GitHub README | https://github.com/robusta-dev/holmesgpt |
| 39 | HolmesGPT：官方文档站（Web UI/TUI 形态、Benchmarks） | https://holmesgpt.dev/ |
| 40 | HolmesGPT：installation.md（Robusta Web UI） | https://github.com/robusta-dev/holmesgpt/blob/master/docs/installation.md |
| 41 | k8sgpt：官方文档站 | https://docs.k8sgpt.ai/ |
