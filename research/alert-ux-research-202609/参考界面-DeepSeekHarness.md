# 参考界面：DeepSeek Harness（dsh）Web UI

> 2026-09 调研。dsh 是 DeepSeek 官方开源 Agent 框架（一切皆插件，GitHub 15 万+ star），
> `npx @deepseek-ai/dsh web` 一键起本地 Web UI。它对我们最大的参考价值：
> **「每一次运行都有迹可循」——append-only 会话日志 + Trajectory 视图，与我们 rca_event 事件流同一思路，但它把"读日志"做成了产品界面。**

## 截图清单（本目录 screenshots/）

| 文件 | 内容 | 来源 |
|---|---|---|
| dsh-trajectory-view.png | 轨迹视图官网高清大图（左右分栏+甘特泳道+Inspector） | deepseek.com/harness |
| dsh-plugin-settings.png | 设置页：已安装插件列表+启用开关 | deepseek.com/harness |
| dsh-demo-poster.jpg | 官网演示视频封面 | deepseek.com/harness |
| dsh-web-home.png | 实机：首页（工作区/会话树/新会话输入框） | 本地 127.0.0.1:3080 |
| dsh-web-session2.png | 实机：会话对话视图（思考块/写入块/失败标记） | 本地 |
| dsh-web-trajectory.png | 实机：轨迹视图（三泳道时间线+轮次表格） | 本地 |

## 四个界面 & 抄什么

### 1. 会话列表（左侧栏）
工作区（可折叠树）→ 会话项（标题 + 相对时间「26天」）；顶部新建会话 + 搜索。
**抄**：RunsView 列表行加"相对耗时/等待时长"短标签；左侧树按 incident/service 分组的可能性。

### 2. 会话视图（对话 Tab）——最值得抄
消息流每条按类型分块，全部可点击展开：
- `思考 <一句话摘要>` —— 思维链折叠成一行摘要
- `写入 <文件路径> +42 -32` —— 工具调用带 diff 行数统计
- `读取 <文件路径>` / `编辑 +7 -7` / `Grep <pattern>` / `Pwsh <命令>`
- 失败的调用带红色「失败」标记
- 顶部：轮次导航（跳转第 1~5 轮）+「加载更早」（懒加载，不全量渲染）
- **底部状态条（全是我们要的指标）**：
  - 模型选择器：`DeepSeek-V4-Flash · 推理等级 High`（模型+推理等级可切换）
  - `上下文已用 22%`（上下文余量）
  - `5 轮 95 步 · 148 tok/s`
  - `13.3M tok · 缓存命中 99%`（总量+缓存命中率）

**抄到 RunDetailView**：
- 事件流(rca_event)渲染改"类型标签 + 摘要 + 可展开"，思考/工具结果默认折叠成一行
- 任务抽屉加 attempts/diff/token 数的紧凑统计行
- Run 头部加 `N 阶段 M 步 · X tok/s` 与 `总 tok · 缓存命中%`（model_call_ledger 已有数据）

### 3. 轨迹（Trajectory）Tab —— 卡点定位的答案
顶部工具栏：`时长(实际/逻辑切换) | ⊟轮次 | ⊟调用 | 搜索轨迹`
中部：**输入/模型/工具三泳道甘特时间线**（水平色块=每次调用时长，可拖动聚焦）
下方表格：轮次行分组 → 调用行（工具名+时间+时长），点击行 → 右侧 Inspector 详情面板
（官网大图版 Inspector 有五 Tab：Summary/Payload/Result/Schema/Timing，顶部还有 Duration/Turns/Calls 指标 + Export）

**抄到 RunDetailView「执行过程」Tab**：
- 把 DAG 图旁加一条"泳道甘特"：每 task 一行、READY→RUNNING→DONE 色块按真实时间铺开——**卡在哪、卡多久一眼看出**（我们已有 rca_task 的 lease/时间字段数据面）
- 事件行点击 → 右侧详情面板（替代现在的底部抽屉），Payload/Result 分 Tab
- 顶部加 搜索 + 收起全部 按钮与 Export

### 4. 设置页（插件）
左侧：会话/插件/设置/文档导航；插件列表：名称+版本+描述+启用开关+安装来源标签。
**抄**：以后 Prompt/Skill 模板版本管理页可直接套这个形态（清单+开关+版本）。

## 与我们系统的对应关系

| dsh 概念 | 我们已有对应 | 差距（要补的只是读面） |
|---|---|---|
| append-only 会话日志 | rca_event(payload jsonb) | 事件流渲染成"类型+摘要+展开"，而非原始 JSON |
| Trajectory 时间线 | rca_task 状态+时间字段 | 泳道甘特投影 + Inspector |
| 底部 token 统计条 | model_call_ledger 每调用 token/费用 | 聚合投影到 Run 头部 |
| 轮次导航/懒加载 | 事件流 SSE after_seq 游标 | UI 加"加载更早"即可 |
| 缓存命中 99% | pricingVersion/usageMissing 字段 | 聚合展示 |

**结论：dsh 证明了我们事件流的存储设计是对的；缺的全部是"读投影+渲染"，没有一项需要改存储或引擎。**

## 本地体验

dsh Web UI 仍在运行：`http://127.0.0.1:3080/?token=uXWzlxq5_2wgbMDmNSgFyynrMkLofy9ElIcetnmCFJs`
（工作区里已有历史会话可点开看轨迹视图；不用了 Ctrl+C 结束 npx 进程）

官方资源：
- 官网 https://www.deepseek.com/harness/
- GitHub https://github.com/deepseek-ai/deepseek-harness
- 开发者文档 https://deepseek-harness.github.io/deepseek-harness/guide/quickstart
