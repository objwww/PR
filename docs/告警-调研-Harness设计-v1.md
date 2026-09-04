# 告警 RCA Agent — 主流 Coding/RCA Agent Harness（执行壳）设计巧思调研 v1

> 日期：2026-09-04（所有来源核对日期均为 2026-09-04）
> 关联：`docs/架构设计-告警Agent-v1.2.md`、`docs/架构评审-外部Harness图未采纳项-v1.md`
> 标注约定：【明示】= 一手资料明确写出；【推断】= 由一手资料合理推出；【未核实】= 未能在截止日期前用一手资料确认。
> 前置盘点：本仓库 `docs/pi-agent-source-study/` **不存在**（2026-09-04 核实），故 Pi agent 全部基于公开一手资料调研。

---

## 0. 结论（300 字）

**最值得抄的 5 条：**

1. **权限规则由 harness 强制执行，不靠模型自觉**（Claude Code 原话："Permission rules are enforced by Claude Code, not by the model"）。与我们的"harness 优先"基因完全一致——把它从口号落成机制：PreToolUse 式确定性拦截点 + 三态判定（放行/问人/拒绝）。【落点：AM4 Native 内核】
2. **拒绝/错误以结构化原因喂回模型让其自纠**（Codex `safety.rs` 的 `SafetyCheck::Reject{reason}`、Claude Code hook 退出码 2 把 stderr 喂回模型）。工具失败不是异常栈，是给模型看的下一步指令。【落点：AM4 工具执行层】
3. **MCP 工具治理三件套**：命名空间前缀（`mcp__server__tool`）、工具过滤（Codex 每 server `enabled_tools/disabled_tools` + 每 tool `approval_mode`）、延迟加载/按需披露（Claude Code ToolSearch，仅名字+server instructions 常驻，定义 2KB 截断）。【落点：MCP 工具层】
4. **单条工具结果 spill-to-disk 指针化**（HolmesGPT：超限结果落盘、会话里只留路径+预览，模型可 `cat` 回读）。我们已在用 HolmesGPT，此法直接补强上下文预算。【落点：AM4】
5. **工具宁少勿多、按工作流合并**（Anthropic 工具设计原则 + Pi 反证：4 个工具、系统提示+工具定义 <1000 token 照样上 Terminal-Bench 榜）。DomainProbe API 应设计成少数高信号聚合工具，而非端点包装。【落点：MCP 工具层 + AM3 评测】

**明确不适合我们的 3 条：**

1. **Pi 的 YOLO 无权限模式**——生产交易域不能裸奔；其"容器化兜底"思路可参考但非当前必需。
2. **Claude Code auto 模式用 LLM 分类器审 LLM**——与"决策权在确定性代码"冲突，且评测不可复现。
3. **OpenHands/OpenClaw 的平台化架构**（多 workspace、多 channel、Agent Server 分布式）——单实例 PG 全家桶用不上，引入即双真相。

---

## 1. Claude Code（Anthropic）

一手来源：
- [Writing effective tools for AI agents — Anthropic Engineering](https://www.anthropic.com/engineering/writing-tools-for-agents)
- [Effective context engineering for AI agents — Anthropic Engineering](https://www.anthropic.com/engineering/effective-context-engineering-for-ai-agents)
- [Claude Code Hooks reference](https://docs.anthropic.com/en/docs/claude-code/hooks)
- [Claude Code Permissions](https://docs.anthropic.com/en/docs/claude-code/permissions)
- [Claude Code Subagents](https://docs.anthropic.com/en/docs/claude-code/sub-agents)
- [Claude Code MCP](https://docs.anthropic.com/en/docs/claude-code/mcp)

### Loop 结构
- agentic loop：gather context → take action → verify → repeat，模型决定何时停止；SDK 层暴露 `max_turns`/`maxBudgetUsd` 作为硬停止条件。【明示】（hooks 文档列出 `Stop`/`StopFailure` 等每轮事件；context engineering 文定义 agent = "LLMs autonomously using tools in a loop"）
- 停止判定不在模型自觉：hooks 的 `Stop` 事件可拦截"模型想停"的时刻，子代理也有 `SubagentStop`。【明示】

### 工具设计
- **工具是"确定性系统与非确定性 agent 之间的契约"**，不是 API 端点包装。【明示】
- 按工作流合并工具：`schedule_event` 替代 `list_users+list_events+create_event`；`search_logs` 替代 `read_logs`；`get_customer_context` 一次聚合。【明示】
- 命名空间分组（`asana_search`/`jira_search` 按服务、`asana_projects_search` 按资源）；前缀 vs 后缀命名对评测有非平凡影响，需自行评测选定。【明示】
- 返回值要高信号：避免 uuid 等低层标识，可用 `response_format: concise|detailed` 让模型自选详略；工具描述 2KB 截断，关键信息放前面。【明示】
- 工具要用评测打磨：生成真实任务评测集 + held-out 测试集，让 agent 自己分析 transcript 改进工具描述（官方承认 web search 工具曾靠改描述修掉模型乱加 `2025` 的毛病）。【明示】

### 上下文工程
- 核心原则：**最小高信号 token 集**；context rot 使长上下文边际收益递减。【明示】
- compaction：窗口将满时把历史交给模型摘要，保留架构决策/未解 bug/实现细节，丢弃冗余工具输出，恢复时附**最近访问的 5 个文件**。【明示】
- 最轻量压缩是**清掉旧 tool call/result**（tool result clearing）。【明示】
- 混合检索策略：CLAUDE.md 启动即注入（前置），glob/grep 按需检索（just-in-time），不做 embedding 预索引。【明示】
- 子代理返回 1–2k token 蒸馏摘要，细节留在子代理上下文里。【明示】

### 权限与审批
- 分层：只读工具（工作目录内）免审；Bash（除内置只读命令白名单）、文件修改、WebFetch/WebSearch 需审；"不再询问"规则持久化到 `.claude/settings.local.json`（按仓库+命令/域名粒度）。【明示】
- 模式：`default`（人工）、`acceptEdits`、`plan`（只读探索）、`bypassPermissions`、`auto`（LLM 分类器代审）、`dontAsk`；管理员可用 managed settings 禁用 bypass/auto。【明示】
- **"Permission rules are enforced by Claude Code, not by the model."**——prompt/CLAUDE.md 只影响模型想做什么，不改变 harness 允许什么。【明示】
- 人审插入点是确定性的：PreToolUse hook 退出码 2 = 阻断并把错误信息喂回模型；官方示例用 hook 给"只读子代理"做硬 backstop（子代理试图写时收到 `Blocked: Write operations not allowed. Use SELECT queries only.`）。【明示】

### 可靠性
- hooks 覆盖全生命周期（SessionStart/PreToolUse/PostToolUse/PreCompact/Stop/StopFailure…），可审计、改写、归档；`StopFailure` 专门处理 API 错误结束的轮次。【明示】
- 会话可恢复（resume）、 compaction 前后有 PreCompact/PostCompact 钩子可落档。【明示】

### MCP 姿势（对我们最有参考价值的一节）
- 工具命名：`mcp__<server>__<tool>`；插件捆绑的 server 再加一层作用域 `mcp__plugin_<plugin>_<server>__<tool>`，权限规则/hook matcher 必须用全名。【明示】
- **ToolSearch 延迟加载（默认开启）**：会话开始只加载工具名 + server instructions，工具定义按需搜索注入；定义总量 <10% 上下文窗口时可阈值模式全量前置（`ENABLE_TOOL_SEARCH=auto:N`）；`alwaysLoad` 可给高频 server/单工具豁免延迟。【明示】
- 连接治理：server 后台连接不阻塞启动；`tools/list` 等发现请求瞬时错误重试 3 次（认证错误/4xx/超时不重试）；连接失败的 server 会**如实告知模型**（经 ToolSearch 结果），而不是静默缺失；需要 OAuth 的 server 在无头模式下告知模型"该 server 工具暂不可用，需用户授权"。【明示】
- 组织级管控：`allowedMcpServers/deniedMcpServers`、managed-mcp.json 固定 server 集。【明示】

### 反模式（官方承认）
- "bloated tool sets 覆盖太多功能或产生歧义决策点"是最常见失败模式——人都说不清该用哪个工具时，agent 更不行。【明示】
- auto 模式用分类器代审，官方同时提供 `disableAutoMode` 给组织关闭它——说明"LLM 审 LLM"本身是被当作可选风险项管理的。【明示】

---

## 2. Pi agent（badlogic/pi-mono，Mario Zechner）

> **甄别**：此 Pi 是 Mario Zechner（badlogic）的极简终端 coding agent（pi.dev，npm 包现名 `@earendil-works/pi-coding-agent`，仓库 `badlogic/pi-mono`），**不是** Inflection 的 Pi 聊天助手，也不是其他同名项目。判断依据：本任务上下文为 coding agent harness，且 pi-mono 是当前讨论"极简 harness"的代表作。

一手来源：
- [What I learned building an opinionated and minimal coding agent — Mario Zechner 博客](https://mariozechner.at/posts/2025-11-30-pi-coding-agent/)
- [badlogic/pi-mono GitHub README](https://github.com/badlogic/pi-mono)
- [pi.dev 官方文档](https://pi.dev/docs)

### Loop 结构
- 极简 loop：处理用户消息 → 执行工具 → 结果回喂 → 重复，直到模型产出无工具调用的响应。**故意不提供 max steps 旋钮**（"The loop just loops until the agent says it's done"）。【明示】
- 全链路可中断：abort 贯穿整个 pipeline 包括工具调用，abort 后仍返回 partial results。【明示】
- 每轮结束后通过回调注入排队消息（one-at-a-time / all-at-once 两种模式）。【明示】

### 工具设计
- **只有 4 个工具**：read / bash / edit / write；系统提示+工具定义合计 <1000 token。默认不挂 grep/find/ls（只读场景可用 `--tools read,grep,find,ls` 切换）。【明示】
- 工具参数用 TypeBox schema + AJV 校验，校验失败返回**详细错误信息**给模型自纠。【明示】
- **结构化分离的工具结果**：同一次调用返回给 LLM 的文本块和给 UI 的结构化 `details` 分开——不强迫 UI 去解析给模型看的文本。【明示】
- 作者承认的反模式：很多 harness 的工具结果只有"给模型看"一种形态，UI 想展示还得反向解析。【明示】

### 上下文工程
- 设计第一原则："context engineering is paramount. Exactly controlling what goes into the model's context yields better outputs"——harness 不背着你注入东西。【明示】
- AGENTS.md 分层加载（全局→项目）；系统提示可整体替换。【明示】
- 官方文档有独立的 Compaction 章节（上下文压缩与会话分支摘要）。【明示】（pi.dev/docs 目录页；细节未逐条核实【未核实】）

### 权限与审批
- **YOLO by default，无内置权限系统**：作者明示论证——能写代码+能跑代码+能联网三者并存时，权限提示是"security theater"；真要边界就容器化（官方文档给 Gondolin micro-VM / Docker / OpenShell 三种模式）。【明示】
- 对我们的意义是反面的：告警域工具集**不含任意代码执行**，攻击面远小于 coding agent，因此"分层权限"对我们依然成立（与 Pi 的处境不同）。【推断】

### 可靠性
- 会话为**有文档的 JSONL 格式**（Session 文档列出 entry types 与 SessionManager API），支持分支、树状导航、HTML 导出、RPC/JSON 事件流无头模式。【明示】
- 供应链硬化：依赖精确锁定、`min-release-age=2`、shrinkwrap、lifecycle script 白名单。【明示】

### MCP 姿势（反面教材，值得记录）
- **明示拒绝 MCP**：Playwright MCP 21 个工具 13.7k token、Chrome DevTools MCP 26 个工具 18k token，"7–9% 的上下文窗口还没开工就没了"。替代方案：CLI 工具 + README，agent 需要时才读 README（渐进披露），用 bash 调用；必须用 MCP 时经 mcporter 包成 CLI。【明示】
- 对我们的含义：MCP 不是不能接，而是**不能裸接**——必须配工具过滤/延迟披露（见 Claude Code/Codex 的做法），且自研 DomainProbe 优先考虑"少数聚合工具 + 文档化"。【推断】

### 反模式（作者明示）
- 内置 to-do list"让模型更糊涂"，改用外部 TODO.md 文件。【明示】
- 并行 subagent 群实现多功能是反模式（"codebase devolves into a pile of garbage"）；subagent 黑盒+上下文传递差，他用"bash 里 spawn 自己（可在 tmux 里全观察）"替代。【明示】
- "会话中为了省上下文而开 subagent 做调研 = 没提前规划"——调研应在独立会话做，产出 artifact 供后续会话用。【明示】
- benchmark 佐证：极简配置在 Terminal-Bench 2.0 与 Codex/Cursor/Windsurf 同场竞技成绩不差（作者自跑 5 trials 并提交 leaderboard）。【明示】（成绩本身以作者博客为准，未独立复核【未核实】）

---

## 3. DeepSeek 系（DeepSeek API + DeepSeek Harness `dsh`）

一手来源：
- [DeepSeek API 文档首页](https://api-docs.deepseek.com/)
- [deepseek-ai/deepseek-harness GitHub README](https://github.com/deepseek-ai/deepseek-harness)
- [dsh Agent 轮次与步骤生命周期（官方中文文档）](https://github.com/deepseek-ai/deepseek-harness/blob/master/docs/agent-lifecycle.zh.md)

> 注：DeepSeek 早期只有 function-calling 指南（OpenAI 兼容格式）；2026 年 8 月发布官方开源 harness `dsh`（developer preview，明示会有破坏性变更）。原 `api-docs.deepseek.com/guides/function_calling` 已 404。

### 架构与 Loop
- **everything-is-a-plugin**，构建在 Cordis 上（"spatiotemporal composability"编程范式）；会话内几乎所有环节都是 waterfall 事件钩子（`agent/pre-step`、`system-prompt/assemble`、`agent/request`、`llm/stream`、`agent/turn-stopping`…），监听器可"authoritative reject"或改写进入下一步的消息。【明示】
- **双通道状态分离**：持久可回放事实存 `session/event`（turn/start、step/start、user/message、assistant/message、tool/call、tool/result、step/end、turn/end），实时控制与协调状态走 `agent/*`；SDK/UI 消费回放流，harness 内部消费实时流。【明示】
- turn/step 两级生命周期：一个 turn 认领"pending next-step input + 一条排队 prompt"，每个 step 内做 prompt 装配→LLM 流式请求→工具执行；自然停止且 inbox 为空才触发 `agent/turn-stopping` 终末检查点。【明示】
- 工具按 `executionMode` 分类调度，带 barrier 和有界滚动池（bounded rolling pool），启动前重新分类；pre/post 有序、execute 并发。【明示】

### 错误与上下文
- LLM 请求失败走 `agent/request-error` waterfall，监听器可返回"重试动作"或保留原始错误。【明示】
- compaction（`dsh-compaction-basic`）在 `agent/pre-step` 处理上下文压力：先做**工具结果剪枝**再摘要；只有当剪枝/摘要真正推进了 "surface replacement generation" 才开新重试轮次，否则维持原始错误——防止压缩空转死循环。【明示】

### 其他
- 权限策略存在（"如果根据当前权限策略某项操作需要审批，Web UI 会先询问"）。【明示】细节未深入核实【未核实】。
- API 侧：思考/非思考双模式、tool calls、1M 上下文、缓存命中计费——长上下文+缓存对我们 PG 证据回放场景有成本意义。【明示】

### 对我们的意义
- dsh 的"事件双通道（回放事实 vs 实时控制）"与我们 PG 里 Run/Task/Attempt 持久化 + 内存调度 loop 的划分同构，可作为 AM4 事件建模的参照。【推断】
- Cordis 全插件化对我们是过度设计（Java 栈+单实例不需要插件运行时），只取"hook 有权威否决权"一点。【推断】

---

## 4. OpenHands 与 OpenClaw

### 4.1 OpenHands（现 Agent Canvas + software-agent-sdk）

一手来源：
- [OpenHands/OpenHands README（Agent Canvas）](https://github.com/All-Hands-AI/OpenHands)
- [OpenHands software-agent-sdk README](https://github.com/OpenHands/software-agent-sdk)
- [SDK Architecture & Core Concepts](https://docs.openhands.dev/sdk/arch/overview)
- 技术报告：[arXiv 2511.03690](https://arxiv.org/abs/2511.03690)（引用信息见 SDK README）

- **事件流是唯一真相**：typed event framework（action / observation / user message / state update），agent loop = reasoning-action 循环消费事件流；组件无状态、不可变、Pydantic 类型化。【明示】
- **工具三件套模式**：所有工具遵循 Action/Observation/Executor 模式，内置校验、错误处理与安全；执行前有 action risk assessment（安全分析器）。【明示】
- **Condenser**：专门的会话历史压缩组件做 token 管理。【明示】（压缩算法细节未逐条核实【未核实】）
- **Workspace 抽象**：同一 agent 代码换 workspace 类型即可从 LocalWorkspace → DockerWorkspace → RemoteAPIWorkspace；沙箱执行通过 Agent Server（REST/WebSocket）在容器内跑。【明示】
- 仓库边界明确：SDK 拥有 agents/tools/conversations/events，Canvas 是前端，automation 管调度——"agent 是 SDK 不是应用"。【明示】
- MCP 集成进 Tool System。【明示】
- 反模式教训：OpenHands 经历 V1（大单体 OpenDevin 遗产）→ V2（SDK 拆分），官方把"statelessness, composability, clear boundaries between research and deployment"列为重构动因——大单体 harness 演化的坑他们用一次重写确认过。【明示】

### 4.2 OpenClaw（甄别：Peter Steinberger 的个人 AI 网关，非 Claude Code 克隆）

一手来源：
- [openclaw/openclaw GitHub README](https://github.com/openclaw/openclaw)

- **甄别**：OpenClaw = Clawdbot → Moltbot → OpenClaw，定位是"住在你聊天软件里的个人助理网关"（WhatsApp/Telegram/Slack…），不是 Claude Code 的开源克隆。【明示】
- 架构原则一句话：**"trusted gateway, untrusted execution, deterministic policy"**——Gateway 是本地控制面（会话/工具/事件/channel 连接），执行侧不可信，策略必须确定性。【明示】
- **入站消息视为不可信输入**：陌生发送者默认进入配对（pairing）流程，需 `openclaw pairing approve` 显式批准。【明示】
- 工具默认在宿主机跑，官方 README 直接警告"接入其他用户或对外暴露 Gateway 前先读 security/exposure/sandboxing 指南"——该项目爆火后大量安全争议正是源于默认宿主执行+聊天入口注入面。【明示】（具体 CVE/事件细节属二手报道【未核实】）
- 心跳调度器（heartbeat）让它无人触发也能自跑——这是"主动型助理"设计，与我们"告警事件驱动"不同，不需要。【明示，二手来源佐证】

### 对我们的意义
- OpenHands：Condenser 与 risk-assessment-before-execution 两个组件概念可取；Agent Server/多 workspace 不可取。【推断】
- OpenClaw：唯一值得抄的是"**入站告警内容 = 不可信输入**"的默认姿态——告警文本可能携带注入内容，harness 要在控制面（确定性代码）做策略裁决，而不是指望模型自觉。这与我们 harness 优先基因互为印证。【推断】

---

## 5. OpenAI Codex CLI

一手来源：
- [openai/codex 仓库 `codex-rs/core/src/safety.rs`（源码）](https://raw.githubusercontent.com/openai/codex/main/codex-rs/core/src/safety.rs)
- [Codex MCP 配置文档](https://developers.openai.com/codex/mcp)
- [Codex CLI 命令与 flag 参考](https://developers.openai.com/codex/cli/slash-commands)
- [openai/codex `docs/config.md`](https://raw.githubusercontent.com/openai/codex/main/docs/config.md)

### Loop 结构
- 标准 agent loop（input → prompt → inference → tool call → execute → append → repeat）；`/compact` 手动摘要压缩，`/clear` 全新开始，`/status` 展示剩余上下文容量。【明示】
- 会话可 `resume`/`fork`/`archive`，支持 remote app-server（WebSocket/Unix socket）。【明示】

### 权限与审批（最值得逐行读的部分）
- **三态安全判定**（`safety.rs`）：`SafetyCheck = AutoApprove | AskUser | Reject { reason }`。【明示，源码】
- 审批策略 `AskForApproval`：`Never | OnRequest | UnlessTrusted | Granular(...)`；沙箱模式 `read-only | workspace-write | danger-full-access`（`--sandbox` flag）。【明示，源码 + CLI 参考】
- **关键巧思：拒绝原因结构化回喂模型**。写操作越界时返回固定文案如 `"writing is blocked by read-only sandbox; rejected by user approval settings"`，模型读到后自行调整策略（换路径/改为只读方案），而不是当异常崩溃。【明示，源码常量】
- patch 目标全部落在 writable roots 内则即使 `on-request` 也 AutoApprove；但官方注释承认 hard link 可绕过 writable-root 检查，因此 apply_patch 仍在沙箱内执行——**策略判定与沙箱执行两层叠加，互不信任**。【明示，源码注释】
- 管理员可 `allow_managed_hooks_only = true` 只放行托管 hooks。【明示，docs/config.md】

### 工具设计
- exec/shell 命令沙箱化执行；`--dangerously-bypass-approvals-and-sandbox`（--yolo）明示标注"仅限外部硬化环境"。【明示，CLI 参考】
- MCP 工具可逐个配置 `output_token_limit`（覆盖模型默认的输出截断预算，另有 20% 序列化余量）——**单工具输出预算**思想与 HolmesGPT spill 机制同源。【明示】

### MCP 姿势（客户端设计的细颗粒度样板）
- 每 server：`enabled`（不删配置地停用）、`required`（初始化失败则启动失败）、`startup_timeout_sec`（默认 10s）、`tool_timeout_sec`（默认 60s）。【明示】
- **工具过滤**：`enabled_tools` 白名单 + `disabled_tools` 黑名单（黑名单在白名单之后应用）。【明示】
- **工具审批分级**：`default_tools_approval_mode = auto | prompt | writes | approve`，其中 `writes` = 只对未标记 read-only 的工具询问；可用 `tools.<tool>.approval_mode` 单工具覆盖。【明示】
- 启动宽限：可选 server 构建初始工具目录只等 `mcp_optional_startup_grace_ms`（默认 1000ms），`required` server 才用完整启动超时。【明示】
- server instructions 字段被当作跨工具的工作流/约束/限流指南，**前 512 字符要自包含**。【明示】

### 反模式
- 源码注释里的 `TODO(ragona): I'm not sure this is actually correct`——官方自己的审批路径也有存疑分支，提醒我们审批逻辑必须有针对性测试。【明示，源码注释】
- hard link 绕过 writable-root 的注释同上：路径白名单不是安全边界，执行层沙箱才是。【明示，源码注释】

---

## 6. HolmesGPT harness 手法补充（我们已在用，查漏补缺）

一手来源：
- [HolmesGPT Context Management 官方文档](https://holmesgpt.dev/latest/reference/context-management/)
- [HolmesGPT Custom Toolsets 官方文档](https://holmesgpt.dev/latest/data-sources/custom-toolsets/)
- [HolmesGPT Data Sources（含 MCP Servers 支持）](https://holmesgpt.dev/latest/data-sources/)

### 上下文预算（两道闸门，管道不同位置）
- **闸门 1：单条工具结果 spill-to-disk**。`spill_oversized_tool_result()` 在工具返回后、入会话前执行：超过 `max_token_count_for_single_tool`（`TOOL_MAX_ALLOCATED_CONTEXT_WINDOW_PCT` 配置）就把全文落盘，会话里替换为**指针消息（路径+预览+"可 cat 回读 / read_image_file 读图"的指引）**；磁盘不可用则丢弃并要求模型缩小查询。【明示】
- **闸门 2：会话历史 compaction**。每次 LLM 调用前检查 `(total_tokens + max_output_tokens) > context_window * threshold_pct`，超则 LLM 摘要，替换为 system prompt + 摘要 + 最后一条 user message；`ENABLE_CONVERSATION_HISTORY_COMPACTION` 可控（默认开），成本计入 RequestStats。【明示】
- **显式输出预算**：每个请求强制 `max_tokens = max(64000, 12% × 上下文窗口)`（受模型上限钳制），防止 provider 默认 4096 截断长答案。【明示】

### 工具/toolset 设计
- toolset = 一组模板化 shell 命令工具；参数 `{{ var }}` 由 LLM 按上下文推断，`${VAR}` / `{{ env.X }}` 为环境变量**对 LLM 不可见**——密钥天然不进上下文。【明示】
- toolset 有 `prerequisites` 与 `tags`（core/cluster/monitoring/networking/storage）分类；改配置后 `holmes toolset refresh`。【明示】（prerequisites 可作为命令探测可用性、不满足则禁用 toolset 的机制存在于仓库实现中【未核实：本次只核实到文档中 prerequisites 字段的存在】）
- 数据源接入四种形态：内置 toolset / 自定义 toolset / HTTP connector / **MCP servers**——我们后续 MCP 接入与 HolmesGPT 自身的 MCP 客户端可以复用同一套工具治理思路。【明示】
- Operator mode：后台 24/7 健康检查主动发现问题——定位与我们的告警驱动不同，但其"健康检查结果仍走同一调查管线"的复用方式可参考。【明示】

---

## 7. 适配性评估表

四个锚点：**harness 优先**（决策权在确定性代码）、**单实例**、**PG 全家桶**、**Java 栈（Spring Boot + 后续 Spring AI advisor）**。

| # | 巧思 | 来源对象 | 适合？ | 锚点过滤理由 | 落点 |
|---|---|---|---|---|---|
| 1 | 权限规则由 harness 强制执行、不靠模型自觉 | Claude Code | ✅ | 与 harness 优先基因完全同构 | AM4 Native 内核（工具调度前置守卫） |
| 2 | 三态判定（放行/问人/拒绝）+ 拒绝原因结构化回喂模型自纠 | Codex safety.rs | ✅ | 确定性代码即可实现，Java switch/sealed 天然表达 | AM4 工具执行层；AM1 人工审批接入点 |
| 3 | 确定性生命周期钩子（PreToolUse 阻断+回喂、PreCompact 落档、Stop 拦截） | Claude Code hooks / dsh waterfall | ✅ | 正是"决策权在代码"的机制化；可用 Spring AI advisor 链或自研拦截器实现 | AM4 advisor 体系设计参照 |
| 4 | 会话状态双通道：持久可回放事件流 vs 实时协调状态 | dsh `session/event` vs `agent/*` | ✅ | 与 PG Run/Task/Attempt 持久化 + 内存 loop 同构，互为印证 | AM1 控制面事件建模 |
| 5 | 单工具结果 spill-to-disk 指针化 + 阈值触发历史 compaction + 显式输出预算 | HolmesGPT | ✅ | 已在用 HolmesGPT，直接补强；PG/文件系统都能当 spill 目的地 | AM4 上下文预算 |
| 6 | 工具按工作流聚合、命名空间分组、高信号返回、concise/detailed 双格式 | Anthropic 工具设计原则 | ✅ | 与栈无关的设计纪律 | MCP 工具层（DomainProbe API 形状）；AM3 评测任务生成 |
| 7 | MCP 客户端三件套：`mcp__server__tool` 命名空间、per-server 工具白/黑名单、per-tool 审批与 output_token_limit | Claude Code + Codex | ✅ | 我们"后续接 MCP"的直接施工图；Java 侧 Spring AI MCP client 上自建 | MCP 工具层 |
| 8 | 工具延迟加载/按需披露（ToolSearch；定义 2KB 截断；alwaysLoad 豁免） | Claude Code | ✅（简化版） | 我们工具数量少，不需要搜索引擎式发现；但"仅名字+说明常驻、定义按需注入"的预算思路可取 | MCP 工具层（先做静态白名单+预算，不做 ToolSearch） |
| 9 | 工具结果 LLM 文本 vs 展示结构化数据双通道 | Pi pi-ai | ✅ | 报告渲染与模型上下文解耦，避免互相迁就 | AM4 工具结果模型 |
| 10 | 工具参数 schema 校验失败返回详细错误给模型 | Pi / Codex | ✅ | 与 #2 同理 | AM4 工具执行层 |
| 11 | 入站内容默认不可信 + 确定性策略裁决 | OpenClaw | ✅（姿态层） | 告警文本即潜在注入载体；策略在控制面 | AM1 接入层/告警清洗 |
| 12 | 极简工具集反证（4 工具 <1000 token 可打榜） | Pi | ✅（方向佐证） | 支持"DomainProbe 少数聚合工具"而非端点大杂烩 | MCP 工具层 + AM3 评测 |
| 13 | 子代理隔离上下文、返回 1–2k 蒸馏摘要 | Claude Code / Anthropic | ⚠️ 小用 | 单次 RCA 调查体量小，暂不需要；若 AM4 出现多线并行取证再评估 | DEFER → AM4 |
| 14 | Condenser 独立压缩组件 | OpenHands | ⚠️ 概念可取 | 与 #5 合并为"上下文预算组件"即可，不单独立项 | AM4（并入 #5） |
| 15 | auto 模式：LLM 分类器代替人审 | Claude Code | ❌ | 违反 harness 优先；评测不可复现；官方自己也提供禁用开关 | 拒绝 |
| 16 | YOLO 无权限 + 容器化兜底 | Pi | ❌ | 生产交易域不可接受；容器兜底在当前双机 Docker Compose 已有等效隔离 | 拒绝 |
| 17 | Agent Server / 多 workspace / RemoteWorkspace 分布式执行 | OpenHands | ❌ | 单实例 PG 全家桶，引入即多真相 | 拒绝 |
| 18 | 多 channel 聊天入口 + heartbeat 自唤醒 | OpenClaw | ❌ | 告警事件驱动已有确定触发源；聊天入口扩大注入面 | 拒绝 |
| 19 | Cordis 全插件化运行时 | DeepSeek dsh | ❌ | Java 栈不需要插件时空组合运行时；只取"hook 有权威否决权" | 拒绝（取概念不取实现） |
| 20 | 内置 to-do/plan mode 等模型侧状态 | Pi 明示反对；Claude Code 提供 | ❌（Pi 理由成立） | 任务状态我们已在 PG 状态机里，比两者都强；模型侧再加一份状态只会打架 | 拒绝（维持 PG 状态机为唯一真相） |

---

## 8. 明确拒绝项 + 理由

1. **LLM 审 LLM 的审批分类器（Claude Code auto mode）**
   理由：与"LLM 只提候选结论，决策权在确定性代码"直接冲突；分类器判断不可复现，AM3 评测无法对其做确定性断言；Anthropic 官方也提供 `disableAutoMode` 供组织关闭，说明其风险等级自知。我们的写工具审批一律走 AM1 人审或确定性规则。

2. **YOLO/无权限默认（Pi）**
   理由：Pi 的论证前提是 coding agent 必然拥有"读数据+执行代码+联网"三件套；我们的 RCA Agent 工具面是只读探针为主、写操作走审批，攻击面不同，分层权限对我们依然有效且必要。

3. **平台化/分布式执行架构（OpenHands Agent Server、多 workspace；OpenClaw 多 channel）**
   理由：单实例、双机、Docker Compose、PG 全家桶。引入 Agent Server 或 channel 网关会产生第二份会话/任务真相（与《架构评审-外部Harness图未采纳项-v1.md》PASS-B 的 MQ 论证同型）。

4. **MCP 工具裸接（不过滤、不限预算）**
   理由：Pi 实测 Playwright MCP 21 工具 13.7k token / Chrome DevTools MCP 26 工具 18k token 常驻上下文；Anthropic 官方也把 bloated toolset 列为最常见失败模式。接 prometheus-mcp 时必须配 per-server 工具白名单 + 单工具输出预算 + 命名空间前缀（按 Codex/Claude Code 的客户端做法），自研 DomainProbe 优先聚合工具形态。

5. **会话级内存态为唯一状态（各 CLI 的 session JSONL 模式）**
   理由：交互式 CLI 可以丢会话重开，告警 RCA 不行。我们的真相在 PG（Run/Task/Attempt/租约/epoch 栅栏），harness 内存态只是缓存——这一点我们已强于所有调研对象，不降级。

6. **并行 subagent 群作业（Claude Code 通用能力、Pi 明示反模式）**
   理由：Mario 明示"并行 subagent 实现多功能 = codebase 变垃圾堆"；我们的调查是单线取证+证据链，若未来需要并行取证，子代理仅用于**只读取证的上下文隔离**（Claude Code Explore 式），且产出必须落 PG 证据表而非口头摘要。

7. **heartbeat/自主持续运行（OpenClaw operator 模式）**
   理由：告警域有确定的事件触发源（告警接入层），自唤醒循环只会制造无证据行动。HolmesGPT Operator mode 同理不采纳。

---

## 9. 对"后续接入 MCP"的客户端设计要点汇总（从各对象提炼）

1. **命名空间**：所有 MCP 工具以 `mcp__<server>__<tool>` 形式进入工具表，权限规则、hook matcher、审计日志一律用全名（Claude Code 明示；插件场景还要再加一层作用域）。
2. **工具过滤**：每 server 配 `enabled_tools` 白名单 + `disabled_tools` 黑名单，黑名单后应用（Codex 明示）；组织级再有 `allowedMcpServers/deniedMcpServers`（Claude Code 明示）。
3. **按需披露**：会话开始只加载工具名+server instructions；工具定义按需注入；描述/instructions 截断（Claude Code 2KB、Codex 建议 instructions 前 512 字符自包含）；高频工具 `alwaysLoad` 豁免。
4. **输出预算**：每工具 `output_token_limit`（Codex 明示）+ 超限 spill-to-disk 指针化（HolmesGPT 明示），两道都要。
5. **失败透明**：server 连接失败/需授权时**如实告知模型**哪个 server 不可用及原因，不让模型以为工具不存在（Claude Code 明示）；瞬时错误重试 3 次、认证错误不重试（Claude Code 明示）；可选 server 短宽限、必需 server 完整超时（Codex 明示）。
6. **审批分级**：per-tool `approval_mode`（auto/prompt/writes/approve），`writes` = 只问非只读工具（Codex 明示）——与我们的只读探针/写操作审批二分天然契合。
7. **自研 DomainProbe 的形状**：少数聚合工具（`get_transaction_context` 式）优于端点包装（Anthropic 明示）；返回高信号字段、避免裸 uuid、支持 concise/detailed（Anthropic 明示）；密钥走 env 不进上下文（HolmesGPT 明示）。

---

## 10. 来源清单（核对日期均为 2026-09-04）

| 对象 | 来源 | 性质 |
|---|---|---|
| Claude Code 工具设计 | https://www.anthropic.com/engineering/writing-tools-for-agents | 官方工程博客【明示】 |
| Claude Code 上下文工程 | https://www.anthropic.com/engineering/effective-context-engineering-for-ai-agents | 官方工程博客【明示】 |
| Claude Code hooks | https://docs.anthropic.com/en/docs/claude-code/hooks | 官方文档【明示】 |
| Claude Code 权限 | https://docs.anthropic.com/en/docs/claude-code/permissions | 官方文档【明示】 |
| Claude Code 子代理 | https://docs.anthropic.com/en/docs/claude-code/sub-agents | 官方文档【明示】 |
| Claude Code MCP | https://docs.anthropic.com/en/docs/claude-code/mcp | 官方文档【明示】 |
| Pi 设计复盘 | https://mariozechner.at/posts/2025-11-30-pi-coding-agent/ | 作者博客【明示】 |
| Pi 仓库/文档 | https://github.com/badlogic/pi-mono 、https://pi.dev/docs | 官方仓库/文档【明示】 |
| DeepSeek API | https://api-docs.deepseek.com/ | 官方文档【明示】 |
| DeepSeek Harness | https://github.com/deepseek-ai/deepseek-harness 及 docs/agent-lifecycle.zh.md | 官方仓库【明示】 |
| OpenHands | https://github.com/All-Hands-AI/OpenHands 、https://github.com/OpenHands/software-agent-sdk 、https://docs.openhands.dev/sdk/arch/overview | 官方仓库/文档【明示】 |
| OpenClaw | https://github.com/openclaw/openclaw | 官方仓库【明示】 |
| Codex CLI | https://github.com/openai/codex （codex-rs/core/src/safety.rs、docs/config.md）、https://developers.openai.com/codex/mcp 、https://developers.openai.com/codex/cli/slash-commands | 官方源码/文档【明示】 |
| HolmesGPT | https://holmesgpt.dev/latest/reference/context-management/ 、https://holmesgpt.dev/latest/data-sources/custom-toolsets/ 、https://holmesgpt.dev/latest/data-sources/ | 官方文档【明示】 |

**未核实事项**：
- HolmesGPT toolset prerequisites 的"命令探测+不满足则禁用"实现细节（只核实到文档字段存在）。
- Pi 的 Terminal-Bench 成绩（作者自报，未独立复核）。
- OpenClaw 具体安全事件/CVE（仅二手报道）。
- DeepSeek Harness 权限策略细节（developer preview，迭代快）。
