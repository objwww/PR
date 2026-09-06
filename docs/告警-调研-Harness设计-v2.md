# 告警 RCA Agent — 主流开源 Agent Harness（执行壳）设计巧思调研 v2

> 日期：2026-09-06（所有来源核对日期均为 2026-09-06，星数为 GitHub API `stargazers_count` 当日值）
> 关联：`docs/告警-调研-Harness设计-v1.md`（v1 覆盖 Claude Code/Codex CLI/Pi/dsh/OpenHands/OpenClaw/HolmesGPT）、`docs/架构设计-告警Agent-v1.2.md`
> 标注约定：【明示】= 一手资料明确写出；【推断】= 由一手资料合理推出；【未核实】= 未能在截止日期前用一手资料确认。
> 本批对象：Gemini CLI、OpenCode、Goose、Aider、Cline。与 v1 重复结论只简短印证，重点挖新机制。
> **仓库变更预警**：`sst/opencode` 已迁移至 `anomalyco/opencode`（同一 repo id）；`block/goose` 已迁移至 `aaif-goose/goose`（同一 repo id，旧仓库 2026-02-19 起停更）。引用旧地址的文档需同步更新。

---

## 0. 结论（300 字）

**本批最值得抄的 5 条：**

1. **声明式 Policy Engine（TOML 规则 + 分档优先级 + 三态判定 + denyMessage 回喂）**（Gemini CLI）。规则是数据不是代码：Admin/User/Default 分档、数值优先级、`allow/deny/ask_user` 三态、无交互模式 ask_user 自动降级为 deny（headless fail-safe）、deny 的全局规则直接把工具从模型上下文里剔除（既安全又省 token）、`denyMessage` 原文喂回模型。比我们现有 ToolPolicy R0R1 多想了一步。【落点：AM4 ToolPolicy 升级为规则文件】
2. **doom_loop 确定性循环检测**（OpenCode）：同一工具+完全相同入参连续 3 次即触发 ask。纯计数器，零 LLM，可直接并进 RunBudget。【落点：AM4 RunBudget】
3. **双层上下文压缩：自动 compaction 阈值 + 兜底策略**（Goose）：80% 阈值触发摘要（`GOOSE_AUTO_COMPACT_THRESHOLD` 可调/可关），仍超限再走 summarize/truncate/clear/prompt 四选一；旧工具输出后台摘要、近期全量（`GOOSE_TOOL_CALL_CUTOFF`）。【落点：AM4 上下文层】
4. **repo map = 确定性预排序检索**（Aider）：tree-sitter 抽符号 + 依赖图 PageRank 排序，塞进 `--map-tokens` 预算。给"告警域实体图预索引"提供现成范式——不用 embedding，用图排名。【落点：DomainProbe 预索引】
5. **无头模式的权限降级纪律**（Goose headless + Gemini 非交互 ask→deny + goose recipe 必须带 prompt）：事件驱动场景下"无法问人"时默认失败安全，而不是默认放行。告警 RCA 正是非交互场景。【落点：AM4 审批层】

**明确不适合我们的 3 条：**

1. **Cline 的"模型自标 `requires_approval` 标志"**——命令安全/危险由模型自己打标，harness 只认标志。这是 LLM 审 LLM 的轻量版，直接违反 harness 优先。
2. **Goose Smart Approval**——读/写分类官方自承"best effort…interpreted by your LLM provider"，且默认模式是 Completely Autonomous。判定权在模型，拒绝。
3. **Cline YOLO / Goose Autonomous 默认裸奔**——与 v1 拒绝 Pi YOLO 同理由。

---

## 1. Gemini CLI（google-gemini/gemini-cli，106,829★，TypeScript，Apache-2.0）

一手来源：
- [docs/reference/policy-engine.md](https://github.com/google-gemini/gemini-cli/blob/main/docs/reference/policy-engine.md)
- [docs/reference/tools.md](https://github.com/google-gemini/gemini-cli/blob/main/docs/reference/tools.md)
- [docs/tools/mcp-server.md](https://github.com/google-gemini/gemini-cli/blob/main/docs/tools/mcp-server.md)
- [docs/cli/checkpointing.md](https://github.com/google-gemini/gemini-cli/blob/main/docs/cli/checkpointing.md)
- [docs/cli/gemini-md.md](https://github.com/google-gemini/gemini-cli/blob/main/docs/cli/gemini-md.md)
- [docs/reference/commands.md](https://github.com/google-gemini/gemini-cli/blob/main/docs/reference/commands.md)
- [docs/core/index.md](https://github.com/google-gemini/gemini-cli/blob/main/docs/core/index.md)
- [GitHub API](https://api.github.com/repos/google-gemini/gemini-cli)

### Loop 结构
- 标准 agentic loop（core 包负责"解释模型的工具请求→执行→结果回喂"），`enter_plan_mode`/`exit_plan_mode` 本身被做成工具，模型可主动申请切模式。【明示】
- 停止判定与预算：有 hooks 体系（`/hooks` 管理生命周期拦截点）与每 agent 的 "execution limits"（`/agents config`）。【明示】（具体 max_turns 旋钮未在已读文档中确认【未核实】）
- 可恢复性：所有会话自动保存（`/resume` 打开会话浏览器，可按内容搜索）；`/chat save <tag>` 手动 checkpoint；`/rewind`（Esc×2）可"仅回退历史 / 仅回退文件 / 两者"三选。【明示】

### 工具设计
- 内置工具带 `Kind` 枚举分类：`Read/Search/Edit/Execute/Fetch/Communicate/Think/Plan/Other`，分类是权限与 UI 的公共词汇表。【明示】
- 工具结果分离形态：MCP 工具响应处理为 `llmContent`（给模型）与 `returnDisplay`（给人看）两份——与 Pi 的 details 分离同思路，再次印证。【明示】
- 多模态工具结果：MCP 工具的 text/image/audio/resource 块会被拆开打包进模型上下文。【明示】

### 上下文工程
- 压缩：接近 token 上限时自动压缩历史（"designed to be lossless"），`/compress` 手动触发。【明示】（具体保留策略/阈值字段未逐条核实【未核实】）
- GEMINI.md 三层注入：全局 → 工作区/祖先目录 → **JIT 层（工具访问某目录时自动扫该目录及祖先的 GEMINI.md）**；支持 `@file.md` import 模块化；`/memory show` 可查注入原文。【明示】
- `@路径` 引用走 `read_many_files` 且 git-aware 过滤（gitignore/.env/.git 默认排除）。【明示】

### 权限与审批（本批最强参考）
- **声明式 Policy Engine**：TOML 规则（toolName/argsPattern/commandPrefix/mcpName/subagent/toolAnnotations/modes/interactive），决策三态 `allow/deny/ask_user`，优先级数值 0–999 叠加 tier base（Default=1 < Extension=2 < Workspace=3（当前禁用，官方承认 bug）< User=4 < Admin=5），高者优先。【明示】
- **deny 的额外语义**：无 argsPattern 的全局 deny 会把工具从模型可见集中完全剔除（"more secure and saves context window space"）——拒绝不仅是拦截，还是上下文治理手段。【明示】
- **`denyMessage` 自定义拒绝原因，回喂模型和用户**，解释为什么被拒。【明示】
- 模式分层 `plan < default < autoEdit < yolo`；"Allow for all future sessions" 持久化时**按模式层级向下流动**（plan 里批的=全局信任；yolo 里批的仅 yolo 有效），保证只读模式的纯净性不被更宽松模式的授权污染。【明示】
- **非交互模式 ask_user 一律视为 deny**——headless 场景失败安全。【明示】
- 规则可按 `interactive` 布尔、subagent 名、`toolAnnotations.readOnlyHint` 匹配；shell 重定向默认需单独授权（`allowRedirection`）。【明示】
- Admin 档目录有文件系统权限校验（属主 root、组/他人不可写，Windows 须 ProgramData），不满足则整个 admin 目录被忽略——防提权。【明示】

### 可靠性
- Checkpointing：文件修改类工具获批后，先在 `~/.gemini/history/<project_hash>` 的 **shadow git** 提交快照 + 存会话历史 JSON + 存待发工具调用；`/restore` 回退文件、回退会话、并**重新提议原工具调用**（可再执行/修改/忽略）。默认关闭。【明示】
- hooks 可挂生命周期事件，可全局/按名启停。【明示】
- `/quit --delete` 退出时删除会话历史与临时文件（chat recording、tool outputs）——隐私兜底。【明示】

### MCP 姿势
- 命名空间：强制 FQN `mcp_<server>_<tool>`；**警告：server 名禁带下划线**（解析器按 `mcp_` 后第一个下划线切分，带下划线会导致通配/安全规则静默失效）。【明示】
- 过滤：`includeTools`/`excludeTools`（exclude 恒优先）；扩展提供的 server 被本地覆盖时**取最严交集**（exclude 并集、include 交集），"扩展无法重新启用你已禁用的工具"。【明示】
- 超时：per-server `timeout`（默认 600s）；`trust:true` 跳过该 server 全部确认。【明示】
- **环境消毒**：spawn MCP 进程时默认抹掉宿主机敏感环境变量（`*TOKEN*/*SECRET*/*PASSWORD*/*KEY*` 等模式），显式 env 声明才传递——"知情同意才共享"。【明示】
- 连接失败处理：启动期后台静默（只提示一次），但模型真要用该 server 的工具时重新启用详细诊断——不静默缺失。【明示】
- 审批三选+允许列表：Proceed once / Always allow this tool / Always allow this server / Cancel。【明示】

### 反模式（官方承认）
- Workspace 档策略当前不生效（官方文档明示引用 issue #18186）。【明示】
- server 名带下划线导致策略静默失效（官方 WARNING）。【明示】
- `trust:true` 文档自标 "Use cautiously"；OAuth 在无头/容器环境不可用。【明示】

---

## 2. OpenCode（anomalyco/opencode，204,865★，TypeScript，MIT；原 sst/opencode）

一手来源：
- [opencode.ai/docs/permissions](https://opencode.ai/docs/permissions/)
- [opencode.ai/docs/agents](https://opencode.ai/docs/agents/)
- [opencode.ai/docs/mcp-servers](https://opencode.ai/docs/mcp-servers/)
- [opencode.ai/docs/rules](https://opencode.ai/docs/rules/)
- [GitHub API](https://api.github.com/repos/anomalyco/opencode)（sst/opencode 已 301 至此）

### Loop 结构
- **每 agent 可配 `steps`（max steps）**：到限后注入特殊系统提示，强制 agent 以文本总结收工并列出剩余任务——预算耗尽的退出姿势是"总结移交"而非硬截断。【明示】
- 停止判定：模型自己停或用户打断；子代理产生 child session，可父子导航。【明示】

### 工具设计
- 内置工具粒度对齐权限键：read/edit（涵盖 write/edit/apply_patch）/glob/grep/list/bash/task/webfetch/websearch/lsp/skill/question/todowrite。【明示】
- 隐藏系统 agent 承担维护性 LLM 调用：`compaction`（压缩长上下文）、`title`（生成会话标题）、`summary`，对 UI 不可见、自动运行——杂活与主 loop 分离。【明示】

### 上下文工程
- compaction 由隐藏 agent 自动执行；规则注入：AGENTS.md 项目/全局两层 + CLAUDE.md 兼容回退（可用 env 关闭），每类取第一个匹配文件；`instructions` 配置可挂额外文件/远程 URL（5s 超时）。【明示】
- AGENTS.md 里教模型"按需 Read 外部规则文件"实现懒加载——官方文档直接给出这种模式写法。【明示】

### 权限与审批（本批第二强参考）
- 三态 `allow/ask/deny`，规则= glob 模式→动作，**同键内"最后匹配者胜"**（与 Gemini 数值优先级相反的哲学；`*` 兜底放前面）。【明示】
- 审批 UI 三选：`once`/`always`（按工具建议的安全前缀模式放行，本会话内有效）/`reject`。【明示】
- **`doom_loop`：同一工具+完全相同入参重复 3 次触发 ask**——确定性死循环熔断器，默认 ask。【明示】
- **`external_directory`：任何工具触碰工作目录外路径即触发，默认 ask**；支持 `~` 展开白名单。【明示】
- **`.env` 默认 deny**（`*.env`/`*.env.*` 拒读，`*.env.example` 放行）——harness 内置的秘密保护，不依赖模型自觉。【明示】
- `--auto` 模式：非显式 deny 的一律放行，显式 deny 仍强制。【明示】
- per-agent 权限覆盖（agent 规则优先于全局）；`permission.task` 用 glob 控制可调度哪些子代理，deny 时把子代理从 Task 工具描述里删掉（模型根本不知道它存在）。【明示】

### 可靠性
- 权限/agent 规则可 JSON 或 Markdown frontmatter 声明，放 `~/.config/opencode/` 或项目 `.opencode/`。【明示】
- 会话存储/崩溃恢复格式细节未在已读文档确认。【未核实】

### MCP 姿势
- 官方 Caveat 明示："MCP server 会加上下文，工具一多就爆；GitHub MCP 这类 server 很容易超上下文限制"——与 Pi 对 MCP token 开销的警告互相印证。【明示】
- 治理：MCP 工具走 `tools` 配置按 glob 开关（`my-mcp*`），支持"全局禁用+按 agent 启用"的按需披露姿势。【明示】
- per-server `timeout`（默认 5s，指 tools/list 发现超时）；`enabled:false` 保留配置但停用；组织可通过 `.well-known/opencode` 下发默认 server 集，本地 `enabled:true` 选择性启用。【明示】
- OAuth 自动发现（RFC 7591 动态注册），token 存 `~/.local/share/opencode/mcp-auth.json`。【明示】

### 反模式（官方承认/文档明示）
- 官方直接点名 MCP 上下文膨胀。【明示】
- 默认宽松（大多 allow），安全护栏只默认了 doom_loop/external_directory/.env 三处——说明"默认安全"需要显式经营。【明示】

---

## 3. Goose（aaif-goose/goose，53,955★，Rust，Apache-2.0；原 block/goose 30,659★ 已停更）

一手来源：
- [goose-docs.ai/docs/guides/goose-permissions](https://goose-docs.ai/docs/guides/goose-permissions/)
- [goose-docs.ai/docs/guides/sessions/smart-context-management](https://goose-docs.ai/docs/guides/sessions/smart-context-management/)
- [goose-docs.ai/docs/tutorials/headless-goose](https://goose-docs.ai/docs/tutorials/headless-goose/)
- [goose-docs.ai/docs/getting-started/using-extensions](https://goose-docs.ai/docs/getting-started/using-extensions/)
- [GitHub API（aaif-goose/goose）](https://api.github.com/repos/aaif-goose/goose)、[GitHub API（block/goose 旧址）](https://api.github.com/repos/block/goose)

### Loop 结构
- **`GOOSE_MAX_TURNS`（默认 1000）**：连续无人工输入轮数上限，到限后停下来问 "I've reached the maximum number of actions… Would you like me to continue?"，肯定则再跑一轮上限——防失控与成本爆炸的官方明示理由。【明示】
- 会话自动压缩循环见上下文节。停止=模型自主或 max turns 熔断。【明示】

### 工具设计
- 扩展即 MCP server：内置 Developer（默认启用）/Computer Controller/Memory 等，平台扩展 Todo/Summon/Extension Manager 等。"内置扩展本身就是 MCP server，可被任何其他 agent 复用"。【明示】
- **Extension Manager：会话中由 agent 动态发现/启用扩展**（Smart Extension Recommendation），动态启用仅当次会话有效。【明示】

### 上下文工程（双层设计，新机制）
- **第一层 auto-compaction**：默认 80% token 上限触发摘要，`GOOSE_AUTO_COMPACT_THRESHOLD` 可调、设 0 关闭；压缩提示词模板 `compaction.md` 可自定义。【明示】
- **第二层 Context Limit Strategies**（压缩关闭或压缩后仍超限的兜底）：summarize / truncate（删最旧消息）/ clear（清空）/ prompt（问用户四选一）；交互式默认 prompt，headless 默认 summarize。【明示】
- **工具输出后台摘要**：旧 tool call 输出在后台被摘要、近期保持全量，cutoff 由模型上下文上限×压缩阈值推算，`GOOSE_TOOL_CALL_CUTOFF` 可手调。【明示】
- 上下文上限解析优先级链：env 覆盖 > 显式模型配置 > provider 运行时探测 > 模型元数据 > 默认 128k。【明示】

### 权限与审批
- 四模式：Completely Autonomous（**默认**，免审）/ Manual Approval / Smart Approval（风险分级自动放行低风险）/ Chat Only（只聊不动手）。【明示】
- **官方自承：读/写分类是 best effort，"This is interpreted by your LLM provider"**——判定依赖模型/提供商，不是纯确定性规则。【明示】（对我们=反例锚点）
- headless 模式权限纪律：无法弹审批，"uses configured defaults or fails safely"；官方要求预配 `GOOSE_MODE=auto` 或具体工具权限。【明示】
- **扩展治理**：激活前自动做已知恶意软件扫描，命中即阻断；`GOOSE_ALLOWLIST=<url>` 企业白名单控制可装扩展。【明示】
- 与 Claude Code 等 CLI provider 集成时，把对方的权限请求透传进 goose 审批 UI（复用 Claude Agent SDK 机制）。【明示】

### 可靠性
- 会话持久化格式细节未在已读文档确认。【未核实】
- 额度监控：HTTP 402 时提示 "Insufficient Credits"，上下文不丢，充值后重发继续。【明示】
- 实时成本估算（OpenRouter 定价数据本地缓存）。【明示】

### MCP 姿势（extensions）
- per-extension timeout 配置；支持 MCP Roots（roots-aware 扩展自动看到会话工作目录）；deeplink 安装；`--with-extension/--with-builtin` 会话级启用不落配置。【明示】
- planner 双模型：`GOOSE_PLANNER_PROVIDER/MODEL` 单独配规划模型（lead/worker 雏形）。【明示】

### 反模式（官方承认/明示风险）
- 默认 Autonomous 官方自挂 warning。【明示】
- headless 官方明示五大限制：不能问澄清/审批、recipe 必须带 `prompt` 否则执行失败、上下文策略自动应用可能丢上下文、需调用方脚本做错误兜底。【明示】
- 社区 issue：截断失败时 "Unable to truncate messages to stay within context limit" 导致会话不可恢复（[aaif-goose/goose#8406](https://github.com/aaif-goose/goose/issues/8406)，未深入核实修复状态）。【未核实】

---

## 4. Aider（Aider-AI/aider，48,780★，Python，Apache-2.0）

一手来源：
- [aider.chat/docs/repomap](https://aider.chat/docs/repomap.html)
- [aider.chat/docs/usage/lint-test](https://aider.chat/docs/usage/lint-test.html)
- [aider.chat/docs/config/options](https://aider.chat/docs/config/options.html)
- [aider.chat/docs/usage/watch](https://aider.chat/docs/usage/watch.html)
- [GitHub API](https://api.github.com/repos/Aider-AI/aider)

### Loop 结构
- 非通用 agentic loop，是 **edit→lint/test→reflect** 流水线：每次 AI 编辑后自动 lint（`--auto-lint` 默认开）、可配自动测试（`--test-cmd`+`--auto-test`），**非零退出码+stdout/stderr 输出即触发修复循环**——验证信号契约就是进程退出码。【明示】
- `--message`/`--message-file` 单发即退（headless 雏形）；`--test` 跑测试修错后退出。【明示】
- architect/editor 双模型：`--architect` 用主模型出方案、editor-model 落编辑；`--auto-accept-architect` 默认 True。【明示】

### 工具设计
- 没有"工具集"概念：模型产出编辑指令（多种 edit format），harness 负责应用、提交、验证。工具面=聊天命令（/add /drop /run /test /lint /undo…）。【明示】
- `/read` 声明只读文件、`--file` 声明可编辑文件——编辑范围由用户显式圈定，模型不能越界写未加进 chat 的文件。【明示】

### 上下文工程（repo map，本批最独特机制）
- **repo map**：tree-sitter 抽全仓库符号与签名，文件为节点、依赖为边建图，**图排名算法（PageRank 式）选出当前 chat 状态下最重要的符号**，塞进 `--map-tokens`（默认 1k）预算；chat 无文件时预算乘数放大（默认 ×2）尽量覆盖全库。【明示】
- 这是**确定性预计算检索**，不用 embedding、不靠模型挑——与 Claude Code "不做预索引"的路线形成明确分岔。【明示】
- `--max-chat-history-tokens` 软上限，超过则开始摘要历史；摘要可用 weak-model 省成本。【明示】
- map 刷新策略 `--map-refresh`（auto/always/files/manual）。【明示】

### 权限与审批
- 极简：无分层审批矩阵。`--yes-always` 对所有确认说 yes；shell 命令建议 `--suggest-shell-commands`（默认开，执行仍需确认）。【明示】
- 对我们的启示是反向的：aider 的信任边界靠 git（每次变更自动提交）+ 显式文件圈定，不靠工具审批。【推断】

### 可靠性（git 即 checkpoint，最朴素的崩溃恢复）
- **`--auto-commits` 默认开**：每次 AI 编辑立即 git commit（可带 Co-authored-by 归因），`/undo` 回滚——checkpoint 不用 shadow git，直接用仓库自己的 git。【明示】
- 会话历史落盘 `.aider.chat.history.md`（Markdown 格式），`--restore-chat-history` 恢复；LLM 全量往返日志可另存。【明示】
- hook 点：`--lint-cmd`/`--test-cmd`/`--notifications-command`/`--commit-prompt` 外部命令注入点。【明示】

### MCP 姿势
- 无 MCP 集成（已读文档未见）。【未核实】（aider 的扩展哲学是 lint/test/shell 命令契约，非协议化工具）

### 反模式与坑（官方承认）
- formatter 当 linter 会因"修改了文件返回非零"被误判为 lint 错误，官方文档给出跑两遍的包装脚本规避。【明示】
- watch-files 的 AI 注释触发（`--watch-files` 扫 `AI!`/`AI?` 注释，保存文件即触发）是**事件驱动 agent 触发的现成范式**——与告警事件驱动同构；但它是新奇功能非默认。【明示】

---

## 5. Cline（cline/cline，67,549★，TypeScript，Apache-2.0；前身 Claude Dev）

一手来源：
- [docs.cline.bot/features/auto-approve](https://docs.cline.bot/features/auto-approve)
- [docs.cline.bot/features/checkpoints](https://docs.cline.bot/features/checkpoints)
- [docs.cline.bot/features/plan-and-act](https://docs.cline.bot/features/plan-and-act)
- [docs.cline.bot/mcp/configuring-mcp-servers](https://docs.cline.bot/mcp/configuring-mcp-servers)
- [docs.cline.bot/prompting/understanding-context-management](https://docs.cline.bot/prompting/understanding-context-management)
- [cline.bot 博客：Context Window Progress Bar](https://cline.bot/blog/understanding-the-new-context-window-progress-bar-in-cline)
- [GitHub API](https://api.github.com/repos/cline/cline)

### Loop 结构
- Plan/Act 双模式：Plan 只读探索（不能改文件/执行命令），Act 执行；上下文跨模式保留，可来回切换；Plan 和 Act 可配**不同模型**（强模型规划、快模型执行）。【明示】
- `/deep-planning` 深度规划斜杠命令；`/new` 开干净上下文。【明示】

### 工具设计
- 工具按审批类别分组：read/edit/execute/browser/mcp，每类独立开关。【明示】

### 上下文工程
- Context Window Progress Bar 把不可见的上下文占用可视化；超限时三选一：自动 compact（可选开启）/ 报错建议新开任务 / 截断旧消息（带警告）。【明示】
- 官方文档自承 "effective window 通常是标称的 50–70%"。【明示】
- 内部截断算法细节（滑动窗口/二分定位）在已读一手文档中未展开。【未核实】

### 权限与审批
- Auto-Approve 分层开关：**依赖继承**——"Read all files"（工作区外）只在 "Read project files" 开启时生效；"Execute all commands" 依赖 "Execute safe commands"。基础档关掉，扩展档无效。【明示】
- **⚠ 命令安全/危险由模型自标 `requires_approval` 标志**，文档明示 "Cline does not use a fixed allowlist"——无确定性白名单，分类全信模型。【明示】（对我们的 harness 优先锚点是明确反例）
- YOLO 模式：勾选即全自动（文件/命令/浏览器/MCP/模式切换全免审），官方警告 "This is dangerous"。【明示】
- 通知：需审批时/自动批准命令跑满 30s 时发 OS 通知——长任务可观测性小设计。【明示】

### 可靠性（checkpoint 三态恢复，新机制）
- **Shadow git 快照（默认开）**：每次工具使用后提交到独立 shadow 仓库，不污染项目 git，能抓到 git 未跟踪文件，跨编辑器会话持久。【明示】
- **恢复三选**：Restore Files（只回文件留会话）/ Restore Task Only（只删消息留文件）/ Restore Files & Task（双回退）；消息编辑可联动 "Restore All" 后重发。比 Gemini/Cline 的"一体回退"粒度细。【明示】
- 大仓库下 checkpoint 有性能开销，官方建议可关。【明示】

### MCP 姿势
- 配置带 **per-server `autoApprove` 数组**（工具级白名单写进 server 配置），官方安全建议 "Limit autoApprove to safe tools"。【明示】
- server 管理：启用/禁用/重启无响应 server/请求超时；STDIO/Streamable HTTP/SSE(legacy)。【明示】
- 无命名空间/延迟加载机制的一手证据。【未核实】

### 反模式（官方承认/社区公认）
- 模型自标风险标志 = 审批判定权让渡给模型（见上）。【明示】
- YOLO 官方自挂危险警告，建议配 git/checkpoint 兜底——再次印证 v1"无兜底不裸奔"。【明示】
- 官方已知上下文窗口假设错误会"永远不知道该 compact、会话损坏"（[cline#4035](https://github.com/cline/cline/issues/4035)）。【明示】（issue 为官方仓库一手记录）

---

## 6. 适配性评估表

| # | 巧思 | 来源对象 | 适合？ | 锚点过滤理由 | 落点 |
|---|------|---------|--------|-------------|------|
| 1 | 声明式 TOML 权限规则+分档优先级+三态+denyMessage 回喂 | Gemini CLI | ✅ | harness 优先：规则由确定性引擎求值，模型只是触发者 | AM4 ToolPolicy 升级为规则文件 |
| 2 | deny=工具从模型上下文剔除（安全+省 token 双收益） | Gemini CLI / OpenCode task deny | ✅ | 与 v1 工具过滤互补，是过滤的新语义 | ToolRegistry 可见性层 |
| 3 | 非交互模式 ask_user→deny 失败安全 | Gemini CLI / Goose headless | ✅ | 告警事件驱动=非交互，直接适用 | AM4 审批层 |
| 4 | doom_loop 同参重复 3 次熔断 | OpenCode | ✅ | 纯确定性计数器，零 LLM | RunBudget 第六维 |
| 5 | .env 默认 deny + external_directory 默认 ask | OpenCode | ✅ | harness 内置秘密/越界护栏 | ToolPolicy 默认规则 |
| 6 | 双层上下文压缩（阈值 auto-compact + 四策略兜底）+旧工具输出后台摘要 | Goose | ✅ | 与我们 EvidenceSnapshot/预算互补 | AM4 上下文层 |
| 7 | repo map：tree-sitter+图排名+token 预算的确定性检索 | Aider | ✅ | 告警域实体图可同款：不依赖 embedding/模型挑选 | DomainProbe 预索引 |
| 8 | edit→lint/test 非零退出码即修复的验证循环契约 | Aider | ✅ | 探针契约可同款：exit code+stdout 即信号 | 探针执行约定 |
| 9 | git auto-commit 即 checkpoint、/undo 回滚 | Aider | 部分 | 单实例 PG 全家桶下可落库替代 git，思路可抄 | EvidenceSnapshot |
| 10 | checkpoint 恢复三态（仅文件/仅会话/双回退） | Cline / Gemini /rewind | ✅ | 评审回放与纠错需要细粒度回退 | 会话/证据恢复 |
| 11 | 模式层级授权向下流动（plan 批的=全局，yolo 批的仅 yolo） | Gemini CLI | ✅ | 防止宽松模式污染只读模式 | 审批持久化设计 |
| 12 | MCP 环境变量消毒（默认抹 TOKEN/SECRET 模式） | Gemini CLI | ✅ | MCP server 是不信任进程 | MCP 工具层 |
| 13 | per-server MCP timeout + autoApprove 数组 + 最严交集合并 | Gemini/Cline/Goose | ✅ | 印证 v1 MCP 三件套并补充超时/合并语义 | MCP 工具层 |
| 14 | 扩展恶意软件扫描+企业 allowlist | Goose | 参考 | 我们 MCP server 自研为主，接三方时适用 | 部署安全 |
| 15 | watch-files AI 注释事件触发 | Aider | 参考 | 事件驱动范式与告警触发同构，但形态不直接抄 | 触发层思路 |
| 16 | 模型自标 requires_approval 决定审批 | Cline | ❌ | 判定权在模型，LLM 审 LLM | — |
| 17 | Smart Approval 读/写分类交给 LLM provider | Goose | ❌ | 同上，官方自承 best effort | — |
| 18 | YOLO/Autonomous 默认裸奔 | Cline/Goose | ❌ | 与 v1 拒绝 Pi YOLO 同 | — |
| 19 | agent 会话中动态启用扩展（模型自选工具源） | Goose | ❌ | 工具面应白名单静态化，模型自扩攻击面不可接受 | — |

## 7. 明确拒绝项+理由

1. **Cline 模型自标风险标志**：无固定白名单，命令安全性由要打命令的模型自己判断——审批形同虚设，评测不可复现。【明示：auto-approve 文档】
2. **Goose Smart Approval / 默认 Autonomous**：分类靠 LLM provider best effort；默认免审违反"写操作走审批"。【明示】
3. **Goose 动态扩展启用**：agent 运行中自扩工具集，破坏"工具面静态可审计"。【明示】
4. **Cline YOLO**：官方自标 dangerous，生产告警域不适用。【明示】
5. **Gemini CLI 下划线 server 名陷阱**这类隐式解析约定：抄机制时 FQN 分隔符要选自包含格式（如 `mcp__server__tool` 双下划线），避免静默失效。【明示+推断】

## 8. 与 v1 的印证/冲突记录

**再次印证：**
- v1"权限规则由 harness 强制执行"→ Gemini Policy Engine 是最完整实现（声明式+分档+非交互降级）；OpenCode 三态+默认 deny .env 同向。【明示】
- v1"三态判定+结构化回喂"→ Gemini `denyMessage` 回喂模型、OpenCode once/always/reject 三选。【明示】
- v1"MCP 三件套（命名空间/过滤/延迟披露）"→ 本批全部支持命名空间与过滤；新增超时（Gemini 600s 默认、OpenCode 5s 发现超时、Goose per-extension）与 env 消毒、最严交集合并两个新维度。【明示】
- v1"工具宁少勿多"→ OpenCode 官方 Caveat 点名 MCP 上下文膨胀、Aider 干脆不要工具集靠 repo map。【明示】
- v1"拒绝 YOLO"→ Cline/Goose 均自带裸奔模式且官方自挂警告，反向印证。【明示】

**冲突/分叉（不是推翻，是路线分岔）：**
- v1 Claude Code"不做 embedding 预索引、just-in-time 检索" vs **Aider repo map 做确定性预计算索引**。两者可共存：文件级事实 JIT，域实体图预排序。【推断】
- 规则排序哲学分岔：Gemini 数值优先级高者胜 vs OpenCode 同键后写者胜。我们二选一即可，建议数值优先级（可解释、可审计）。【推断】
- checkpoint 载体分岔：shadow git（Gemini/Cline）vs 项目 git（Aider）vs 我们的 PG 落库。【推断】
- Goose/Cline 把读/写分类或风险分类交给模型——与 v1 拒绝 auto 模式同源，本批再添两例。【明示】

## 9. 来源清单表 + 未核实事项

| 对象 | 仓库（2026-09-06 星数，GitHub API 实时） | 主要一手来源 |
|------|------|------|
| Gemini CLI | google-gemini/gemini-cli，106,829★ | 见 §1 清单（policy-engine/tools/mcp-server/checkpointing/gemini-md/commands/core-index） |
| OpenCode | anomalyco/opencode，204,865★（sst/opencode 301 迁移） | opencode.ai docs：permissions/agents/mcp-servers/rules |
| Goose | aaif-goose/goose，53,955★（block/goose 30,659★ 停更于 2026-02） | goose-docs.ai：goose-permissions/smart-context-management/headless-goose/using-extensions |
| Aider | Aider-AI/aider，48,780★ | aider.chat docs：repomap/lint-test/options/watch |
| Cline | cline/cline，67,549★ | docs.cline.bot：auto-approve/checkpoints/plan-and-act/configuring-mcp-servers/understanding-context-management；cline#4035 issue |

**未核实事项：**
1. Gemini CLI 的 max_turns 类硬预算旋钮、chat 压缩的保留策略细节（文档只到"接近上限自动压缩"层面）。
2. OpenCode 会话存储格式与崩溃恢复机制。
3. Goose 会话持久化格式（已知有 `--no-session` 与 session 命名开关，存储格式未核）。
4. Goose issue #8406（截断失败致会话不可恢复）的修复状态。
5. Cline 内部截断算法（滑动窗口/二分定位）细节、MCP 命名空间与延迟加载有无。
6. Aider 无 MCP 集成——仅基于已读文档未见，未穷尽检索。
