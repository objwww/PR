# 告警 RCA Agent — 主流开源 Agent Harness（执行壳）设计巧思调研 v3（源码级全量版）

> 日期：2026-09-06（所有来源核对日期均为 2026-09-06）。
> 地位：**本册取代 v1/v2 成为现役版本**；v1/v2 保留不删，仅作历史与推翻记录的对照底本。
> 标注约定：【源码】= 克隆/快照仓内 `路径:行号`；【文档】= 官方文档/仓内 README·AGENTS.md；【推断】/【未核实】如实标注。
> 取证说明：本轮网络降级——直连 github.com 与本机代理 127.0.0.1:7890（及 7897/1080/8888）均不通；源码经 gh-proxy.com 镜像浅克隆 / codeload.github.com tarball / FetchURL 抓 raw.githubusercontent.com 快照三种途径取得，各对象 commit 均已记录于 §17，可用 `var/m4-research/repos/<name>/` 本地复验。
> 留档说明：详细源码摘录留档于 `var/m4-research/harness-src/h01~h07`（7 份分册，每对象 1~2 个），本册为合并精编；分册中更长的代码摘录与次要细节不在此重复，需深挖时回查分册。
> 覆盖对象（13 个）：Claude Code、Codex CLI、Pi、DeepSeek dsh、OpenHands（SDK+主仓）、OpenClaw、HolmesGPT、Gemini CLI、OpenCode、Goose、Aider、Cline、Hermes Agent。

---

## §0 结论

**全 13 对象源码级调研后"最值得抄"清单（去重合并）：**

1. **三态决策 + 强制 reason + 结构化拒绝回喂模型自纠**：Codex `SafetyCheck{AutoApprove,AskUser,Reject{reason}}`（safety.rs:16-21，拒绝文案是编译期常量）、execpolicy `Decision{Allow,Prompt,Forbidden}`（decision.rs:9-16）、Gemini `ALLOW/DENY/ASK_USER` + denyMessage 回喂（scheduler/policy.ts:37-46）、OpenCode DeniedError 内含命中规则 JSON 回喂（v1/permission.ts:7-27）。→ M4-16/裁决层直接抄。
2. **决策权在确定性组件**（铁律获多端正反验证）：Claude Code 官方明文 "Permission rules are enforced by Claude Code, not by the model"；Codex `assess_patch_safety` 为纯函数、LLM 不参与（safety.rs:29-85）；OpenHands analyzer×policy 完全解耦；OpenClaw SECURITY.md 明文 "The model/agent is not a trusted principal"。
3. **声明式规则即数据 + 分档优先级 + deny 即隐藏**：Gemini TOML 规则五档 tier+priority/1000 防跨档溢出（toml-loader.ts:306-308）；OpenCode 配置键序即优先级、无匹配默认 ask（permission/index.ts:26-34）；两家均把 deny 的工具从模型上下文静态剔除（安全+省 token）。
4. **审批沉淀为确定性规则**：Codex `acceptWithExecpolicyAmendment`——人审一次即回写一条策略规则（app-server README:2013-2017），优于"写 JSON 文件"。→ M4 审批确认后写入 PG 规则表。
5. **工具结果双通道 + spill-to-disk 指针化**：Pi `content`(喂模型)/`details`(日志UI) 分离（types.ts:362-376）、bash 超限全文落盘仅回截断文本+fullOutputPath；HolmesGPT 超限落盘+预批准 `cat` 回读指令+预览截断（tool_context_window_limiter.py:33-144）。
6. **上下文预算公式化 + 防空转**：HolmesGPT 单工具 min(15%,25k)/压缩 95%/输出 max(64k,12%)；Goose 0.8 阈值双触发+反应式≤2 次；dsh 剪枝→摘要两段式+`replaceGeneration` 无进度不重试；OpenCode max-steps 到限注入"总结移交"提示词。
7. **确定性死循环/卡死熔断**：OpenCode doom_loop 同工具同入参 3 次 ask（零 LLM）；Gemini 确定性层 5 次+文本重复 10 次；OpenHands StuckDetector 5 模式+同参同错先 nudge 再判死。
8. **审批/异常一律 fail-closed**：dsh 无 answerer 即 fail closed（user-approval/index.ts:51-96）；Cline 无审批回调默认拒绝（agent-runtime.ts:1781-1784）；OpenHands 分析器异常按 HIGH 处理、UNKNOWN 系统性升级确认；Gemini/OpenCode 非交互 ask→deny。
9. **会话 = append-only 事件日志、可回放**：dsh 事件溯源+事件词汇表白名单+不变式插件；Pi JSONL 树（id/parentId，9 类 entry，版本 v3）；Codex rollout JSONL 全量录制可 jq 回放。→ PG 表落同款。
10. **注入面工程化**：OpenClaw 外部内容包裹+随机边界标记+特殊 token/零宽字符消毒（external-content.ts）；Hermes yolo 标志 import 时冻结防注入提权（approval.py:44-46）、LLM 守护者 "UNTRUSTED INPUT 包裹+ESCALATE 出口"（approval_smart.py）。
11. **确定性预索引检索**：Aider repo map——tree-sitter 抽符号+依赖图 PageRank+token 预算二分裁剪，零 embedding、完全可复现（repomap.py:279-703）。→ DomainProbe 告警域实体图预索引范式。
12. **HolmesGPT 调度与预算机制族**：双池分离（对话 5/工具 10）、按空闲槽 claim、assignee+request_sequence 拒旧回写、阈值公式族——M4 替换的逐条对照基线。
13. **Goose Operation 管线编排**：护栏（max_turns/压缩/审批）做成确定性前置 Operation，LLM 推理只是管线最后一步（agent.rs:1661-1740）——与"决策权在确定性组件"同构。

**"明确拒绝"清单（去重合并）：**

1. **LLM 审 LLM 的审批/分类**：Claude Code auto 分类器；Goose Smart Approval 源码级实锤把读/写放行判给 LLM（permission_judge.rs:145-185，判错即静默放行）；Cline 旧版模型自标 `requires_approval`（当前 main 已移除——业界自己也在撤退）。判定权在模型、评测不可复现。
2. **默认裸奔一族**：Pi YOLO by default（工具链零审批代码）；Goose Auto 默认；Cline yolo preset + editFiles/useMcp 默认 true；OpenCode 默认 `"*":"allow"`。默认安全必须显式经营。
3. **平台化/分布式/巨兽化架构**：OpenHands Agent Server 多 workspace；OpenClaw 多 channel 聊天入口（但注意：OpenClaw 本体实为单 gateway+单 SQLite，拒绝理由改为"TCB 内插件+聊天入口扩大注入面"）；Hermes 26 平台/88 万行单仓；Codex 100+ crate。单实例 PG 全家桶用不上，引入即多真相。
4. **hook 超时 fail-open**：Claude Code 明示 "don't count on a stalled hook to act as a gate"——本项目告警场景超时必须 fail-closed。
5. **Starlark 策略语言 / OS 沙箱（Landlock/seccomp/seatbelt）**：前者对 Java 栈过重（前缀表+三态即可）；后者 3.10 内核不可用且 R0/R1 只读工具用不到。
6. **Cordis 全插件运行时 + 事件词汇表/不变式纪律体系**：团队级工程投入，单实例过度设计（只取 waterfall 权威否决语义）。
7. **模型自扩工具面**：Goose Extension Manager 会话中动态发现/启用扩展。
8. **MCP 裸接不过滤**：Pi 实测 Playwright MCP 21 工具 13.7k token 常驻上下文。
9. **LLM 自主写持久记忆/技能**：Hermes background review 虽有 guards 链，仍与"LLM 只提建议"铁律张力大，M4 不做（留作后续阶段蓝本）。
10. **OpenHands 隐式确认**（重进 run() 即视为批准，无审批审计事件）：本项目 Ledger 必须显式记录审批行为。
11. **字符串匹配做错误分类**：HolmesGPT `"rate" in msg and "limit" in msg` 二分——反面教材，错误分类应结构化（异常类型+provider 码）。

---

## §1 Claude Code（Anthropic，闭源）

- 元数据：npm `@anthropic-ai/claude-code` latest **2.1.263**（2026-09-06），官方商业产品、周级发版。**无源码，机制结论均为【文档】级**，实现细节（去重、缓存键）不可证。
- Loop：经典 采样→并行工具批→回填→再采样；PreToolUse/PostToolUse 每工具调用触发，PostToolBatch 批后触发，Stop hook 可阻止结束继续会话（exit 2）【文档】code.claude.com/docs/en/hooks。
- 工具设计：只读免审批、Bash 需审批分级表；deny 裸工具名（`Bash`）把工具从模型上下文**整体移除**，带 pattern 的 `Bash(rm *)` 按调用拦截——"看不见"与"不许用"两级【文档】permissions 页。
- 上下文：ToolSearch 延迟加载默认开（仅工具名+server instructions 常驻，定义按需检索）；工具描述与 instructions 各截 2KB；MCP 输出 >10k tokens 告警【文档】mcp 页。
- 权限审批：deny>ask>allow 首匹配定案无例外洞；6 权限模式（default/acceptEdits/plan/auto/dontAsk/bypassPermissions）；5 级配置分层 managed>CLI>local>project>user，列表型 key 跨层**合并**；共享文件 allow 需 trust 后生效、deny/ask 立即生效；hook 的 allow/ask 不能越过规则，但 exit 2 阻断优先于 allow 规则【文档】permissions/settings 页。
- 可靠性：hook exit 0=成功、**exit 2=阻断且 JSON 无法翻案**、其他码以 JSON 决策为准；超时默认 command/http/mcp_tool 600s、agent 60s；**命令 hook 超时 fail-open（陷阱）**，只有 Agent SDK 回调 fail-closed；`updatedInput` 可改写入参后放行【文档】hooks 页。
- MCP：`mcp__<server>__<tool>` 命名、规则可按 server 通配；managed-mcp.json+allowed/deniedMcpServers 组织收口；发现请求重试≤3 次短退避，认证错误不重试【文档】mcp 页。
- 反模式：hook 超时 fail-open；`if` 匹配器 best-effort 兜底运行 hook（官方建议硬性 allow/deny 用权限系统而非 hook）。
- 适配性：**抄机制**——deny>ask>allow 求值、5 级合并+信任门控、PreToolUse I/O 契约（stdin JSON+exit code+JSON 决策+updatedInput）、"deny 裸名=移除可见性"两级。勿抄：hook 超时 fail-open、bypassPermissions。

## §2 Codex CLI（openai/codex，Rust）

- 元数据：**121,857★** / forks 18,701，Apache-2.0，pushed_at 当日；commit `ac192cd7937b0d73edc6dffe009940ae53782dd4`（gh-proxy 浅克隆）。极活跃（日更），crate>100，远超"轻量 CLI"自述。
- Loop：turn 主循环 turn.rs:334 `loop {`，每轮先 drain input_queue 再采样；**turn 循环与 stream 重试循环分离**（:1443 独立 loop+retry state）；regular/compact/review 均为 SessionTask 变体驱动【源码】core/src/session/turn.rs、tasks/mod.rs。
- 工具设计：工具治理下沉独立 crate（codex-mcp/codex-tools），工具目录带 revision 缓存（tool_catalog.rs:266-271）；`mcp__` 前缀已是 legacy 可配置去除（tools.rs:22、features/lib.rs:222）；`tool_output_token_limit` 独立预算（config/mod.rs:876）。
- 上下文：仓内 AGENTS.md:93-100 军规——不改写历史、一切注入有界硬顶、单条≤10K tokens、prompt-cache 友好；remote auto-compact 任务+窗口推进（compact_remote.rs:53,270）。
- 权限审批：**三态 SafetyCheck 纯函数判定 LLM 不参与**（safety.rs:16-21,29-85）；审批策略 4 枚举+Granular 5 开关（protocol.rs:984-1023）；exec policy 引擎为 Starlark 方言（execpolicy/parser.rs:3-13），`Prompt` 在 never/Granular 关闭时降级 Forbidden（exec_policy.rs:411-424，审批通道可焊死）；**`acceptWithExecpolicyAmendment`=批准同时回写策略规则**（app-server README:2013-2017）。
- 可靠性：rollout 全程 JSONL 落盘可回放（rollout/recorder.rs:1）；MCP 启动超时报错附配置修复示例（startup.rs:123-128）；输出预算取历史最小值 min 合并（mcp_types.rs:90-91）。
- MCP：allow/deny 双名单过滤（tools.rs:87-95，先 allow 后 deny），过滤在目录装配处统一应用；每 server 级审批模式+按工具覆盖。
- 反模式：safety.rs:47-48 作者自留 TODO（"I'm not sure this is actually correct?"，UnlessTrusted 分支存疑）；Landlock 沙箱需内核≥5.13，本项目 3.10 不可用【推断】——所幸 R0/R1 只读不需要 OS 沙箱。
- 适配性：**抄抽象不抄实现**——三态+强制 reason、审批 4 枚举+Granular、MCP 双名单、输出预算 min 合并、rollout JSONL、审批沉淀为规则。不抄：Starlark（前缀表即可）、OS 沙箱、平台化功能。

## §3 Pi agent（现 earendil-works/pi，原 badlogic/pi-mono）

- 元数据：102,246★ / 12,759 forks，TypeScript，MIT；commit `9767ba275f3e9a5ee0f5c5342249b629ab1b2282`（gh-proxy tarball）。**仓库已迁移/更名**（API 重定向确认），新维护者含 mitsuhiko，极活跃。结构：ai→agent（loop 内核）→coding-agent（产品壳）。
- Loop：双层 while、**无内置 max steps 上限**（全仓 grep 无配置项）；终止仅四类：stopReason error/aborted、无工具调用且无 follow-up、`shouldStopAfterTurn` 钩子、整批结果 terminate【源码】agent-loop.ts:171-272。truncated 工具调用**整批拒执行**回错误让模型重发（:230-233,379-404）；逐工具 executionMode，任一 sequential 整批串行否则并行（:417-424,476-545）。
- 工具设计：**双通道结果实锤**——content（回喂模型）+details（"for logs or UI rendering"，不进模型）+terminate【源码】types.ts:362-376；bash 超限 2000 行/50KB **全文落盘**，details 带 truncation+fullOutputPath（bash.ts:234,254-272）；参数校验=TypeBox Compile（**已无 AJV**）+宽松纠偏管线，失败文案含完整参数回显回喂模型（validation.ts:317-349）；schema 即提示词（description 内嵌截断策略+constrainedSampling 结构化输出）。
- 上下文：compaction 触发=确定性算术 `contextTokens > contextWindow - reserveTokens`（默认 reserve 16384/keepRecent 20000），token 计数优先用真实 usage【源码】compaction.ts:132-148；挂载 prepareNextTurn，切割点只在 turn 边界，增量更新式摘要（:369-454,500-537）；系统提示按可用工具去重拼接（system-prompt.ts:28,81-94）；溢出识别做成正则规则库+非溢出黑名单（overflow.ts:30-88）。
- 权限审批：**YOLO by default 源码级印证且更强**——工具执行路径零审批代码，仓内文档明示 "intentionally does not include … permission popups"（usage.md:309）；唯一拦截面是扩展钩子 beforeToolCall。
- 可靠性：abort 全链路（AbortController 穿到每个工具，bash abort 杀整棵进程树）；会话=append-only JSONL 树，9 类 entry、id/parentId 可分支、`CURRENT_SESSION_VERSION=3`；持久层内建双通道（custom 不进上下文 vs custom_message 进上下文）【源码】session-manager.ts:30-156。
- MCP：**明示拒绝**（README.md:499 + 作者博客），但已为 provider 原生延迟工具加载留 `addedToolNames` 口子——反 MCP 不反渐进披露【源码】types.ts:460-465。
- 反模式（仓内佐证）：无内置 to-do、无 subagent 工具（内置仅 read/bash/powershell/edit/write/find/grep/ls）。
- 适配性：**抄机制**——①loop 终止外置成钩子（对应"决策权在确定性组件"）；②双通道+落盘指针；③校验错误回喂格式；④compaction 阈值算术。不可抄：YOLO 姿态、无步数保险丝。

## §4 DeepSeek Harness dsh（deepseek-ai/deepseek-harness）

- 元数据：213,560★ / 25,112 forks，TypeScript，MIT；commit `d347e703908d0406b7a7ef80e3a0e594d86b2215`（release dsh@0.1.3-alpha.1，gh-proxy tarball）。**developer preview**（明示破坏性变更），但仓内带完整 docs/ 与 .agents/notes 设计档案。Cordis 内核 vendored 于 vendor/cordis。
- Loop：turn/step 两级；`agent/pre-step` 以 **waterfall 派发**，任一监听器返回 `{kind:'reject'}`→turn 置 blocked 且整轮不消耗模型调用——权威否决路径实锤【源码】agent.ts:246-254,279-282；cordis events.ts:224-243 "不调 next() 即否决链上其余含内建行为"。无 max steps，靠结构化 TurnEndReason+guard 插件保险丝。
- 工具设计：executionMode 分类**失败封闭**——仅 `isConcurrencySafe(args)` 精确 true 才 parallel，未声明/抛错一律 exclusive barrier【源码】tools/index.ts:1260-1276；bounded rolling pool 滚动补位+补位前重新分类+按模型序 commit+abort 合成错误结果（tool-calls.ts:89-100,147-161,199-246）；tool/call、tool/result 即时落 durable 日志带 seq 引用链。
- 上下文：两段式——先**免模型**工具结果剪枝（head+marker+tail，Unicode 安全，先 append 影子计价事件再 surface replace），重测仍超才 LLM 摘要【源码】compaction-basic/index.ts:279-313、pruner/index.ts:83-184；**防空转**：CONTEXT_WINDOW_EXCEEDED→compactIfNeeded→**只有 replaceGeneration 真前进才 retry**，maxOverflowRetries 上限+成功即重置（index.ts:168-224）；摘要是 durable bracket 事务、复用会话前缀保 KV cache。
- 权限审批（上轮"未核实"本轮补齐）：会话级策略 ask/never，切换落 durable `approval/policy`（模型不可见），问询/裁决落 `approval/asked`/`approval/decided`；**无可用 answerer 时 fail closed**【源码】user-approval/index.ts:51-96。
- 可靠性：**双通道分离实锤**——durable 事实=session.append() 事件日志+firehose 广播，实时控制=agent/* 经 cordis dispatch（session/index.ts:49-73 vs agent.ts:99-101）；事件词汇表白名单，表外类型无 ignorable 标记直接拒绝解析（known-event-types.ts:1-30）；invariant 伴生插件做持久日志契约测试；guard 插件族（repeat-tool-reminder/timeout-policy）替代 max-steps 保险丝。
- MCP：与 pi 相反，官方 mcp-client 插件 opt-in per server，只桥接 Tools capability【仓内文档】packages/mcp/README.md。
- 适配性：**抄机制**——①waterfall 权威否决=审批门语义；②剪枝→摘要+无进度不重试；③并发分类简化为"只读并行/写串行"两档；④approval 三事件落 PG 审计。不可抄：Cordis 插件内核、事件不变式体系。

## §5 OpenHands（SDK + 主仓）

- 元数据：SDK（software-agent-sdk）1,058★，commit `fe91d7dfc94d299e3751acb2b0c80ccbc582623c`；主仓（OpenHands/OpenHands）86,307★，commit `f7fb0c4b21f5ed726edbba8a6309634ef434b004`（FetchURL raw 快照）。**主仓已更名 Agent Canvas 变纯前端/控制面**，agent 内核全部下沉 SDK——V1 大单体→V2 拆分完成态【源码】README:5-7,139-48。
- Loop：显式状态机+while True（local_conversation.py:1908,1936）；FINISHED 先问 Stop hook（可拒绝停止注入 feedback）；**WAITING_FOR_CONFIRMATION 重进 run() 即隐式翻成 RUNNING**（:1982-89，危险语义见反模式）；双上限——钱预算+迭代上限 max_iteration_per_run=500（:217,702-03,2017-37）；step() 先执行 pending_actions 再采样，condensation 是 loop 内一等公民（agent.py:637-687）。
- 工具设计：Schema/Action/Observation/ToolExecutor 四件套+create() 工厂；执行收口处**统一掩密**（"Every tool's output funnels through here"，tool.py:607-643）；下发 schema 动态注入 security_risk（仅非只读工具）+summary（全工具）并排最前；DeclaredResources 声明资源键，未声明则并行时互斥串行。
- 上下文：不可变 Event 日志（frozen、SourceType 四类、ROOT_PARENT_ID）+View 只读投影+Condenser HARD/SOFT 分级（SOFT 失败回落原 view，HARD 失败 hard_context_reset 兜底）；LLMSummarizingCondenser 用**独立 LLM** 摘要，遗忘区间对齐工具调用原子边界【源码】event/base.py、view.py:22-144、condenser/base.py、llm_summarizing_condenser.py。
- 权限审批：**评估与策略完全解耦**（confirmation_policy.py:9-55）；UNKNOWN 系统性兜底——无 analyzer 时忽略 LLM 自报 risk 一律 UNKNOWN（agent.py:1056-89），UNKNOWN 不入风险排序、涉比较即抛错（risk.py:8-10,95-96）；**分析器异常按 HIGH 处理**（analyzer.py:106-09）；Ensemble 取各子分析器最高严重度、子分析器抛错记 HIGH、可配任一 UNKNOWN 即整体 UNKNOWN（ensemble.py:78-101）。
- 可靠性：StuckDetector 扫最近 20 事件、5 种卡死模式、同参同错先注入 nudge 而非判死（stuck_detector.py:24-218，**scenario 5 是 TODO**）；迭代+钱双上限正交；HEAD commit 自证"取消杀不死阻塞同步代码"（xfail 测试）。
- MCP：300s 超时、断连重连一次、**错误全转 is_error Observation 回喂模型不抛出**；secret 只从 registry 展开（`check_env=False`）；深拷贝原始 inputSchema 避免 pydantic 丢嵌套【源码】mcp/tool.py:43-201。
- 反模式：隐式确认（重进 run() 即批准，无审计事件）**不可抄**；UNKNOWN 不参与排序使策略漏配即语义空洞；摸客户端私有字段 `_closed` 升级脆。
- 适配性：**抄机制**——analyzer×policy 解耦+UNKNOWN 升级确认+异常=HIGH 两条直接进 M4-16；执行收口统一掩密；StuckDetector+nudge 用于 Reconciler 防死循环；迭代/钱双上限正交。**不可抄**：隐式确认。

## §6 OpenClaw（openclaw/openclaw，个人 agent 网关）

- 元数据：389,000★ / 81,735 forks，TypeScript，License NOASSERTION；commit `1f36040186a753a352ebb58bdc507966c9e298ac`（FetchURL raw 快照）。注意：**实为单 gateway+单 SQLite 架构**（非分布式），运行时状态一律 SQLite、写事务同步【仓内文档】AGENTS.md:52-54。
- 信任模型（三原则官方明文）：模型不是可信主体（"Security boundaries come from host/config trust, auth, tool policy, sandboxing, and exec approvals"）；控制面/执行面分离但同信任域；exec approvals 是操作员护栏非多租户授权边界【仓内文档】SECURITY.md:229-241。触发授权与上下文可见性显式分离；sandbox 默认 off、gateway 默认绑 loopback。
- 配对=默认准入闸：陌生 DM 发送者默认进 pairing（dm-policy-shared.ts:137）；决策三态 allow/block/pairing；配对码 8 位去歧义字符集、TTL 1h、pending 上限 3；approve=true 把 sender 写入 allowFrom 白名单；全部读写走 SQLite 事务【源码】pairing-store.ts:25-44,272-440。
- 注入面防护：注入正则集**只记录不拦截**（检测是观测不是防线）；核心防护=**包裹+警示**——`<<<EXTERNAL_UNTRUSTED_CONTENT id="随机hex">>>` 边界+警示文本；三道消毒（LLM 特殊 token 替换、全角/零宽折叠防伪造边界、元数据去换行防标头注入）【源码】external-content.ts:24-157,382-455。
- 真相存储：node:sqlite 单一真相库，schema 版本化迁移+STRICT typing；**写所有权围栏**——BEGIN IMMEDIATE 后重查所有权，违反抛错；写事务异常视为腐坏、驱逐句柄、quarantine 须 `doctor --fix` 解锁【源码】openclaw-state-db.ts:163-715；live authority 纪律明文（"HMAC verification, TTL, and matching identifiers do not establish live authority"）【仓内文档】gateway/AGENTS.md:25-36。
- 设备配对：`oc-pair://` setup code 携带 url+token+tlsFingerprint；ws:// 明文仅允许 localhost/私网；非全 TLS 路由自动降级 bootstrap profile【源码】setup-code.ts:112-536。
- 反模式（官方自陈）：exec approvals 绑"精确 command+文件快照"，非完整语义模型；注入正则只告警不拦截；单 gateway 不做多租户；各 channel 上下文过滤不统一（**上下文过滤不能当授权用**）。
- 适配性：抄——"入站内容=不可信输入"包裹范式（告警文本同构）、陌生来源默认进待批准队列（映射 webhook 来源白名单）、单库版本化+腐坏隔离、写事务内重查所有权（PG 版=`SELECT FOR UPDATE` 后重校验）。pairing 形态本身不直接适用（告警无"发送者"）。

## §7 HolmesGPT（HolmesGPT/holmesgpt，M4 替换对象，最深挖）

- 元数据：3,213★，pushed_at 2026-09-06 活跃；commit `5e983c17f30e93099c7d775167266d4cd1d586c4`（gh-proxy 浅克隆）。本 HEAD Worker/会话体系绑 **Supabase+Realtime**，机制可抄代码不可抄。
- Worker 调度：**对话池（默认 5）与工具调用池（默认 10）分离**，独立 Executor/claim 线程【源码】worker.py:243-246、tool_call_worker.py:164-167、env_vars.py:221-234；按空闲槽 claim（`claim_n_pending_conversations`，oldest first、**无优先级**，原子性在 DB RPC、SQL 本体在闭源仓【未核实】）；生命周期 pending→running→completed/failed/stopped/timeout；**conversation_id+request_sequence 拒旧回写**，mismatch 抛错永不重试（supabase_dal.py:1538-1636）；无步骤级恢复（SIGTERM 在途全标 timeout，重启不续跑）。
- 工具：prerequisites 四类真实执行+快慢分离懒初始化（tools.py:954-1109）；**env 变量对 LLM 不可见**——schema 仅 name/description/parameters，env 经 Jinja 服务端注入（tools.py:346-351,565-577，约定式非强制）；参数 shlex.quote 净化+coerce 纠偏；**审批 invoke 前置拦截**，未批准返回 APPROVAL_REQUIRED 不执行（tools.py:353-377）。
- 上下文预算：**单工具结果 spill-to-disk**——超 min(窗口×15%, 25000) 全文落盘，指针消息含路径+预批准 cat 指令+预览（错误预览压 500 字符），存储不可用降级丢数据+报错【源码】tool_context_window_limiter.py:33-144、llm.py:327-338；历史压缩阈值 `(total+max_output) > 窗口×95%` 每轮迭代前检查，压后仍超抛错开新会话（input_context_window_limiter.py:94-186）；**显式 max_tokens=max(64k, 窗口×12%) 每次必发**防默认 4096 静默截断（llm.py:703-823）。
- HTTP API：/api/chat、/api/model、/api/info、/api/oauth/callback、/api/checks/execute、/api/admin/reload*（默认关闭）、healthz/readyz；**无会话查询接口**；鉴权=HOLMES_API_KEY 全局中间件、豁免判定用 ASGI scope["path"] 防 Host 头伪造（引 CVE-2026-48710）【源码】server.py:421-970。
- 错误分类（反面教材）：请求终态五分类（success/approval_required/error/rate_limited/aborted），但 **LLM 异常仅 rate_limit/other 二分、靠 message 子串匹配**——"LLM 错误五分类"不成立【源码】usage_recorder.py:170-184,569-576。
- 适配性：**抄机制不抄代码**——双池+按槽 claim（PG SKIP LOCKED 等价，补优先级）、request_sequence 拒旧回写（PG 行版本号）、spill 指针化+阈值公式族+显式 max_tokens、env 服务端注入、审批前置拦截。反面：Supabase 绑定、无优先级无恢复、LLM 错误字符串分类、鉴权默认关。

## §8 Gemini CLI（google-gemini/gemini-cli）

- 元数据：106,829★ / 14,532 forks，TypeScript，Apache-2.0，pushed_at 当日；commit `85aca163f6c73ac6ce380b5447359146b8adcae4`（codeload tarball）。
- 权限审批（本项目最强参考，全源码级）：**三态 PolicyDecision=ALLOW/DENY/ASK_USER**+四模式排序（policy/types.ts:10-14,48-65）；**声明式 TOML 规则+Zod 强校验**，priority 强制 0–999 防跨档溢出（toml-loader.ts:39-70）；**五档分档** `tier + priority/1000`（default/extension/workspace/user/admin），首匹配者胜（:306-308,196-205；policy-engine.ts:666-684）；argsPattern 过 isSafeRegExp 防 ReDoS（:502-533）；**denyMessage 拼进 POLICY_VIOLATION 回喂模型**（scheduler/policy.ts:37-46,659-675）；**deny 的工具静态剔除出 function declarations**（policy-engine.ts:966-1042）；**非交互 ask→deny 三重兜底**（构造期 defaultDecision+interactive=false 规则+scheduler throw，:290-293、scheduler/policy.ts:94-101）；**yolo 也是规则集**（yolo.toml 通配 allow 998，ask_user 仍 999），可被 admin `disableYoloMode` 拒绝启动；shell 子命令 tree-sitter 逐段 check、重定向降级 ask、越出 workspace 强制 ask（policy-engine.ts:351-586）。
- Loop：递归式 turn loop，**MAX_TURNS=100 硬顶**（client.ts:79,910-960）；无工具调用时 checkNextSpeaker 额外 LLM 判 next_speaker 续跑。
- 死循环检测**双层**：确定性层（同调用连续 5 次/文本重复 10 次）+30 轮后 LLM 语义判环（confidence>0.9、动态间隔）【源码】loopDetectionService.ts:27-115。
- 上下文：压缩阈值=上限×0.5、保留最近 30%；Reverse Token Budget——function response 预算 50k，从新到旧累计，超预算旧输出截断最后 30 行存临时文件【源码】chatCompressionService.ts:41-52,126-135。
- MCP：excludeTools 优先于 includeTools；逐工具 try/catch 不拖垮整 server；trust/readOnlyHint 注解进策略匹配；目录不受信时 stdio server 拒绝启动；规则匹配结构化 serverName 优先、FQN 兜底（server 名含下划线有已知坑）【源码】mcp-client.ts:1295-2466、policy-engine.ts:599-613。
- 反模式：LLM 参与 loop 检测与 next-speaker（有确定性兜底）；TOML 加载错误只记 errors 不 fail-fast。
- 适配性：**抄机制**——TOML 规则+tier+priority/1000（Java 侧 `tier*1000+priority`）、三态、denyMessage 回喂、deny 静态剔除、非交互三重兜底，全部可移植到 AM4 ToolPolicy；不抄代码。

## §9 OpenCode（anomalyco/opencode，原 sst/opencode）

- 元数据：204,868★ / 26,721 forks，TypeScript(Effect)，MIT，pushed_at 当日；commit `337fd144d2ba144743368f78d9579a99cce175bd`（codeload tarball）。**sst/opencode 已 301 迁移至 anomalyco/opencode**（同一 repo id）。
- 权限审批：**三态+最后匹配者胜**（`findLast`，**无匹配默认 ask**——兜底问人不放行，permission/index.ts:26-34）；配置即规则、键序即优先级；内置护栏：`"*":"allow"`+doom_loop:"ask"+external_directory ask+question:"deny"+plan_enter/exit:"deny"+**read:.env 是 ask（旧报告"默认 deny"已失效）**（agent/agent.ts:105-232）；ask 时逐 pattern 求值、任一 deny 抛 DeniedError（含规则 JSON **喂回模型**）；reply once/always（沉淀会话级 approved）/reject（**级联取消同 session 全部 pending**，可带 feedback 回喂）【源码】permission/index.ts:66-152；**deny 即隐藏**（工具与子代理从可见集剔除，:181-196）；非交互 `opencode run`：--auto 自动 once，**否则自动 reject（fail-closed）**（cli/cmd/run.ts:801-821）。
- 护栏两件套（确定性）：**doom_loop 熔断**——同工具+JSON.stringify 完全相同入参连续 3 次即 ask（零 LLM，processor.ts:31,354-381）；**external_directory**——触碰工作区外路径即 ask，shell 用 tree-sitter 提取涉及目录；命令前缀 arity 表做 always 放行最小粒度（external-directory.ts:17-44、shell.ts:263-291、arity.ts）。
- Loop：runLoop while(true)；**maxSteps 到限注入 MAX_STEPS_PROMPT**——"总结移交"（禁止工具调用、只输出已完成/剩余/建议）而非硬截断【源码】prompt.ts:1178-1336、max-steps.ts 全文；deny 后默认 break。
- 上下文：usable()=min(20k,maxOutput) 预留 buffer；overflow→auto compaction→prune 保护最近 40k；overflow 错误且 auto 关闭时直接 error 停（不静默重试）【源码】overflow.ts、compaction.ts:28-29,271-284。
- 可靠性：重试 5 次指数+jitter、尊重 retry-after 头、**ContextOverflowError 永不重试**（retry.ts:26-105）；JSON 文件存储+TxReentrantLock+版本化迁移；权限 pending 用 Deferred、实例销毁 finalizer 全 reject 不悬挂。
- MCP：连接超时 30s；单 server 失败不影响其他；ToolListChangedNotification 热刷新；工具名 sanitize；每个 MCP 工具执行前走统一权限管道；resources 包成 list/read 内置工具【源码】mcp/index.ts、catalog.ts、tools.ts:343-409。
- 反模式：doom_loop 的 JSON.stringify 比较**键序敏感会漏检**；默认 `"*":"allow"` 宽松、`--auto` 把 ask 全变 once；arity 表由 LLM 生成覆盖率依赖词表。
- 适配性：**抄机制**——默认 ask 兜底、reject 级联取消、doom_loop（改规范化 JSON 比较）、max-steps 总结移交、DeniedError 规则回喂、deny 即隐藏；不抄代码。

## §10 Goose（aaif-goose/goose，原 block/goose）

- 元数据：53,955★ / 6,187 forks，Rust，pushed_at 当日；commit `5e90925962f05acf8e255032de44d16c4a7768a2`（codeload tarball）。**block/goose 已 301 至 aaif-goose/goose**。结构预警：compaction 拆出独立 crate，agent loop 重写为 **state machine+Operation 管线**。
- Loop：**Operation 管线+末尾推理步**——steer→max_turns→bang_shell→compaction→tool_pair_compaction→tool_approval→…→Step::Inference，每 Operation 返回 applied/not_applicable/yielded，职责单一可独立测试【源码】agent.rs:1661-1740；MAX_TURNS 默认 1000（GOOSE_MAX_TURNS 可调），到限固定文案暂停等人（agent.rs:90、ops_maxturns.rs:14）；turn 预算过半注入 `<turn-budget>` 回喂模型（ops_maxturns.rs:20-28）。
- 上下文：**80% 阈值自动压缩**（lib.rs:32，可关）；**双触发**——预防式（user 消息后超阈值）+反应式（ContextLengthExceeded 兜底，**最多连续 2 次**防死循环，ops_compaction.rs:24,230-254）；**压缩=摘要+可见性位翻转不删消息**（agent_visible=false，用户仍可见），保留最近纯文本 user 消息+continuation 提示词（context_mgmt/mod.rs:33-172）；TOOL_CALL_CUTOFF 旧工具输出 `tokio::spawn` 后台摘要，cutoff 公式 `(3×limit/20000).clamp(10,500)`、批大小 10、保护本轮工具对（:367-590）；结构化摘要 prompt（`<analysis>`+JSON 字段）。
- 权限审批（关键安全结论）：四模式 Chat/Auto/Approve/SmartApprove；判定阶梯：用户显式权限→read_only_hint 注解→扩展管理强制审批→**兜底给 LLM 判**→未知默认要审批（permission_inspector.rs:159-194）；**Smart Approval 读/写分类由 LLM 完成**（permission_judge.rs:145-185 调 provider.complete）——虽有 untrusted-data 前缀+"判不出当非只读"fail-closed 缓解，**判错为只读即静默放行，违反铁律，只能当反例**；security 检查员 Deny 可覆盖、无权限结果默认 needs_approval（fail-closed，:93-131、tool_inspection.rs:170-260）。
- MCP/扩展：MCP client 统一 McpClientTrait，超时默认 300s；OAuth step-up 重试；扩展恶意软件扫描；read_only_hint 注解参与权限判定可借鉴【源码】extension_manager.rs:39-961。
- Recipe：YAML 声明式任务模板（instructions/prompt 二选一必填+参数注入+输出 schema+有害内容扫描）——**告警诊断 playbook 可直接借用形态**【源码】recipe/mod.rs:42-414。
- 可靠性：反应式压缩≤2 次、失败建议新会话而非死循环；provider 自管上下文时 goose 侧跳过（防双重管理）。
- 适配性：**抄机制**——①Operation 管线（Java 责任链+applied/skipped）；②双触发+反应式 2 次上限；③摘要+可见性位+保留最近 user 消息；④工具对摘要 cutoff 公式；⑤recipe=playbook。**不可抄**：Smart Approval LLM 分类（反例锚点，源码级）、Auto 默认、动态扩展体系。

## §11 Aider（Aider-AI/aider）

- 元数据：48,781★ / 4,923 forks，Python；commit `5dc9490bb35f9729ef2c95d00a19ccd30c26339c`（codeload tarball）。**pushed_at 2026-05-22，约 3.5 个月无新提交、1,854 open issues——维护明显放缓**。
- Loop（edit→lint/test 验证循环）：reflection 循环 `max_reflections=3` 硬上限到限 warn 停止（base_coder.py:101,924-944）；edit→lint 默认开，有错 confirm_ask 后回喂（:1599-1696）；edit→test **默认关**，**非零退出码才把输出回喂**（:1616-1623、commands.py:993-1048）。**关键差异：修复循环要人确认，headless 场景断在等人**——本项目需改策略驱动。
- 上下文（repo map 完整链路）：tree-sitter tags.scm 抽 def/ref（repomap.py:279-336）→MultiDiGraph 依赖图+**PageRank**，边权重启发式乘积（被提及 ×10、长标识符 ×10、`_` 前缀 ×0.1、泛型名 ×0.1、chat 文件引用方 ×50，防高频主导加 sqrt 降频）→personalization（chat 文件获个性化分）→排名摊到定义→**token 预算二分裁剪**（误差<15% 即收，:666-703）；tags 有 sqlite/mtime 缓存；PageRank 失败优雅降级不崩会话。
- 工具/编辑：按模型能力选 edit_format（whole/diff/udiff）；**diff 失败处理**——SEARCH 块不匹配则尝试应用到其他文件、仍失败拼错误文案（含"reply with fixed versions"指令）**回喂 LLM 重试**，不抛异常中断（editblock_coder.py:41-130）；udiff 分 hunk 报不同错误、部分成功明示。
- 成本：每轮 litellm 计费+失败自算兜底，逐轮落账打印；/tokens 逐消息估算+给 /drop、/clear 建议；聊天历史超阈值**后台线程**摘要（头摘要+尾原文，失败保留全量）【源码】base_coder.py:2037-2060、history.py:8-98。
- 适配性：**抄机制**——①repo map 三段式迁移为"告警域实体图预索引"（边权重启发式可作初值）；②非零退出码才回喂+reflection ≤3（confirm_ask 换策略裁决）；③编辑失败反馈文案模板（LLM 输出契约容错姿势可用于 RCA 结构化输出）。**不适用**：whole/udiff 编辑格式、交互式 CLI 壳；维护放缓，不可依赖上游演进。

## §12 Cline（cline/cline）

- 元数据：67,551★，TypeScript monorepo（apps/vscode+sdk/packages），Apache-2.0；commit `dac3b35ba485`（2026-09-04，gh-proxy 浅克隆）。
- Loop：SDK 无状态 while 循环+maxIterations（agent-runtime.ts:727-733）；无工具调用且有完成提醒则注入 reminder 续跑，否则 finishRun；有"完成工具"直接结束 run；**maxIterations 超限直接 throw**——硬熔断非优雅降级（:871）。
- 工具设计：preset 五档（act/plan/search/minimal/yolo）组装工具（presets.ts:24-120）；策略=名字键控表 `ToolPolicy{enabled?, autoApprove?}`（shared/llms/tools.ts:7-18）。
- 权限审批（核心结论，三段全确定性）：①策略短路——enabled=false 跳过、autoApprove=false 才走回调、**无审批回调默认拒绝**（agent-runtime.ts:1753-1784）；②宿主类别映射——UI 五开关映射工具名类别（readFiles/editFiles/executeSafeCommands/useBrowser/useMcp 全局一刀切）；③审批 UI 阻塞挂 Promise。**关键修正：模型自标 requires_approval 已移除**（全仓 grep 仅命中未接线的遗留 XML 文案），替换为"工具名类别开关+plan 模式确定性黑名单"；但 **act 模式开 Execute 开关后无内容级检查**。命令内容检查只在 plan 模式：command-guard.ts——黑名单命令集+文本掩码（heredoc/引号/注释）+重定向拦截（拒 `..` 走私）+wrapper 穿透（sudo/env/xargs），注释自承兜不住 `python -c`（:23-298）。
- 检查点（非 shadow git）：**直接操作用户仓库 git**——stash create+第三父捕获 untracked，写私有 ref `refs/cline/checkpoints/...`（对用户 stash list 不可见）；触发点 beforeModel 且 iteration==1；恢复先验证 HEAD 指向基底再 stash apply+reset hard，files/task 可分别恢复【源码】checkpoint-hooks.ts:110-478、checkpoint-restore.ts:99-425。
- 上下文：触发式 compaction（≥maxInputTokens×比率）；两策略——agentic（LLM 摘要）与 basic（**确定性折叠**：typed prompt 必保、最新 turn 保留、旧 turn 只留结尾 assistant、不拆 tool_use/result 对）；**溢出恢复强制走 basic 确定性策略**（"recovery must not depend on another successful LLM request"），每 run 只允许一次（compaction.ts:42-47,290-330、agent-runtime.ts:961-1019）。
- 可靠性：maxConsecutiveMistakes 默认 6 熔断；重复工具调用检测对输入做 **key 排序 JSON 签名**（loop-detection.ts:26-70，比 OpenCode 的裸 stringify 强）；审批缺失 fail-closed。
- 反模式：yolo preset 一键全放权；act 模式命令自动批无内容检查；**maxRequests 预算限制已移除**（AutoApprovalSettings.ts:10-12 注释，字段仅兼容保留）——反证预算层要自己保住。
- 适配性：**抄机制**——①ToolPolicy 表+回调 fail-closed 移植 Java；②plan 模式命令黑名单（掩码/重定向/wrapper 穿透）是未来加 shell 的现成蓝本；③"溢出恢复必须确定性"同构"评测确定性可复现"；④检查点"持久化防重启覆盖"信号设计（不抄实现）。

## §13 Hermes Agent（NousResearch/hermes-agent）

- 元数据：**242,179★**（Python，CLI+gateway+TUI+桌面），MIT；commit `0a195aa46364`（gh-proxy 浅克隆）。**证伪旧报告**：实际 2025-07-22 创建（非 2026-02）、24.2 万星（非数万至十余万）。单仓 88 万行级、26 平台接入——巨兽化。
- Loop：同步 while（`api_call_count < max_iterations and iteration_budget.remaining > 0`，max_iterations 默认 500）；实现拆 facade+turn 相位文件（preflight/api_call/overflow/truncation/context_compaction/recovery/stop_gates/finalizer）【源码】agent/AGENTS.md:25-36、目录实测。
- 权限确认（与铁律最对齐）：**确定性双层模式表**——HARDLINE_PATTERNS 无条件封锁、**位于 yolo 之下**（"a floor below yolo"，只收无恢复路径的）；DANGEROUS_PATTERNS 需审批（含敏感写目标 ~/.ssh、.env、config.yaml、shell rc、.pgpass；sudo -S stdin 提权检测）【源码】approval_detection.py:19-399；**yolo 防注入冻结**——`_YOLO_MODE_FROZEN` import 时冻结，注释明示"per-call 读取会让任何 skill 设该变量绕过全部审批（prompt-injection escalation path）"（approval.py:44-46）；**守护者 LLM 只作附加层**——剥 shell 注释+`<command>` 分隔+UNTRUSTED INPUT 声明+输出 APPROVE/DENY/**ESCALATE（不确定升级给人）**（approval_smart.py:1-11）——"正则表是地板与闸门，LLM 评审是闸门之后的可旁路增强"；用户永久 allowlist/deny 规则。
- Skills 自动生成/自改进（核心机制，M4 后续蓝本）：每轮交付后 **fork 继承父运行时的 AIAgent daemon 线程回放会话快照**问"该不该存/改 skill 或 memory"（turn_finalizer.py:603-617；cron 场景默认关闭，~30K tokens/event）；提炼 prompt 重工程（CLASS-LEVEL 伞形技能、禁事件叙述入库、四级偏好顺序、DO_NOT_CAPTURE 块）；**防护闸**——pinned/external/非 agent 创建的技能不可写、read-before-write 强制、dispatch 侧工具白名单（skill_manager_guards.py:57-209、background_review.py:917-943）；curator 后台巡检遥测，stale→archive **永不删除**。
- 记忆双层：文件层 MEMORY.md/USER.md 会话启动冻结快照进 system prompt（**会话中写盘不改 prompt，前缀缓存不破**，字符上限 2200/1375）；SQLite 层 SessionDB（WAL、FTS5、压缩会话 parent 链）【源码】memory_tool.py:1-56、hermes_state.py:1-5。
- Gateway/审批：26 平台文件；**审批跨 channel 一致**——agent 线程阻塞等 `/approve`、`/deny <reason>` 自由文本回传 agent 自适应（approval_gateway_wait.py:1-40）。
- MCP：stdio/HTTP/SSE 三传输+统一 registry；**供应链预检**——spawn 前对 npx/uvx/pipx 包做 OSV 恶意包检查（12s 上限、超时 fail-open）【源码】mcp_tool.py:1-52。
- 可靠性：turn 相位化；compaction 双层（gateway 85%+agent 50%）；后台 review 有界等待 2s 超时不阻塞用户 turn；~39k tests。
- 适配性：**抄机制**——①hardline/dangerous 双层正则表+敏感写目标清单直译 Java；②yolo/配置冻结-at-import 防注入；③LLM 守护者"UNTRUSTED INPUT 包裹+ESCALATE 出口"安全姿势；④任务后提炼全链路（fork 回放+guards+curator 只归档）留作 M4 之后"经验沉淀"蓝本，当前不做。**不抄**：巨兽化体量、LLM 自主写记忆（与铁律冲突）。

---

## §14 对 v1/v2 旧结论的印证/推翻总表

各分册末节推翻记录汇总去重（本轮核心价值之一）：

| 旧结论（出处） | 裁定 | 依据 |
|---|---|---|
| Codex 三态 SafetyCheck 及判定输入（v1/旧 part1 §5） | **印证** | safety.rs:16-21,29-85 逐字段核对一致 |
| `mcp__<server>__<tool>` 命名约定（v1） | **印证但补注** | 已是 legacy 前缀可配置去除（tools.rs:22 常量名 LEGACY；features/lib.rs:222） |
| "声明式 `.codexpolicy` 规则文件 allow/ask/deny"（旧 part1 §5） | **推翻两点** | 全仓无 `.codexpolicy` 引用；策略语言实为 **Starlark 方言**（parser.rs:3-13），决策枚举 allow/prompt/forbidden（decision.rs:19-26）非 ask |
| PreToolUse exit 2 阻断+permissionDecision+updatedInput（旧 part1 §6） | **印证** | hooks 文档 exit-code 节；补充：exit 2 不可被 JSON 翻案 |
| "hook 独立 timeout 默认 60s"（旧 part1 §6） | **推翻** | 现 command/http/mcp_tool 默认 **600s**、agent 类 60s；且**超时 fail-open**（旧版关键遗漏） |
| "配置在会话启动时快照防篡改"（旧 part1 §6） | **未核实/疑似过时** | 现 settings 页明示运行时监听文件变更热加载（ConfigChange hook） |
| 多级配置分层+deny/ask 立即生效 allow 需 trust（旧 part1 §6） | **印证** | settings 页 precedence 节原文 |
| ToolSearch 延迟加载（旧 part1 §6） | **印证并补强** | 默认开启、仅载工具名+instructions、描述 2KB 截断（mcp 页） |
| Pi 故意无 max steps（v1 §2） | **印证（源码级）** | agent-loop.ts:171-272 四类终止；src 内无步数配置 |
| Pi YOLO by default 无权限系统（v1 §2） | **印证（源码级）** | 工具链零审批代码；usage.md:309 明示 |
| Pi 拒绝 MCP（v1 §2） | **印证** | README.md:499+作者博客；但已留 addedToolNames 口子（types.ts:460-465），"反 MCP 不反渐进披露" |
| Pi 工具结果双通道（v1 §2） | **印证** | types.ts:362-376；bash details 带 truncation+fullOutputPath |
| Pi "TypeBox+AJV 校验"（v1 §2） | **需修正** | 现为 TypeBox `Compile` 无 AJV，新增校验前宽松纠偏管线（validation.ts:317-349） |
| Pi 会话 JSONL+SessionManager（v1 §2） | **印证且演进** | 9 类 entry、id/parentId 树、版本号 v3 |
| Pi 反模式内置 to-do/subagent（v1 §2） | **印证（仓内佐证）** | 无内置 todo/subagent；usage.md:309 |
| dsh Cordis 插件内核（v1 §3） | **印证且定位** | vendored 于 vendor/cordis |
| dsh waterfall 钩子可 authoritative reject（v1 §3） | **印证（源码级）** | events.ts:224-243；agent.ts:246-254,279-282 |
| dsh session/event vs agent/* 双通道（v1 §3） | **印证（源码级）** | session/index.ts:73,735-740 vs agent.ts:99-101 |
| dsh compaction 剪枝→摘要→replaceGeneration 推进才重试（v1 §3） | **印证（逐行核到）** | compaction-basic/index.ts:192-223 |
| dsh executionMode+bounded rolling pool（v1 §3） | **印证（源码级）** | tools/index.ts:1267-1276（fail-closed）；tool-calls.ts:199-246 |
| dsh "权限策略存在，细节未核实"（v1 §3） | **已核实** | user-approval/index.ts：ask/never、fail-closed、approval/* 三事件 |
| v1 引用 `badlogic/pi-mono` 地址 | **需修正** | 已迁移 `earendil-works/pi`，维护者含 mitsuhiko |
| OpenHands SecurityAnalyzer×ConfirmationPolicy 解耦、UNKNOWN 默认确认（旧 part1 §14） | **印证（升级源码级）** | confirmation_policy.py:43-61、agent.py:1049-63 逐行吻合 |
| Ensemble 取最高严重度（旧 part1 §14） | **印证并补强** | ensemble.py:78-101 max+fail-closed HIGH+propagate_unknown |
| OpenHands "30k 字符扫描预算""execute_tool 绕过检查"（旧 part1 §14） | **未核实** | 本轮快照未含 defense_in_depth 子包，沿用需复查 |
| OpenHands max_iterations=100、max_budget_per_task（v1/旧 part4 §7） | **印证设计+修正数值** | 新 SDK：max_iteration_per_run=500（:217）、max_budget_per_run（:235） |
| OpenHands V1→V2 SDK 拆分（v1 §4.1） | **印证（完成态）** | 主仓=Agent Canvas 纯前端，无 pyproject；内核在 SDK |
| 主仓 pyproject pin openhands-sdk==1.29.0（早前抓取） | **推翻/已过时** | 现主仓无 pyproject.toml（Python 已迁出） |
| OpenClaw 陌生发送者默认配对（v1 §4.2） | **印证（升级源码级）** | dm-policy-shared.ts:137+pairing-store.ts:402/421 |
| OpenClaw "入站内容=不可信输入"（v1 §4.2，原【推断】） | **印证并升级** | SECURITY.md:229-30 明文+external-content.ts 全套实现 |
| "OpenHands/OpenClaw 平台化分布式引入即双真相"（v1 §4） | **部分推翻** | OpenClaw 实为单 gateway+单 SQLite；拒绝结论不变，理由改为"TCB 内插件+聊天入口扩大注入面" |
| HolmesGPT 双池 5/10 分离（E-3） | **印证** | env_vars.py:221-234+两个 Executor |
| HolmesGPT claim/生命周期/拒旧回写/无优先级无恢复（E-3） | **印证** | supabase_dal.py:1357-1636；models.py:7-18；worker.py:764-847（原子性 SQL 未核实） |
| HolmesGPT 鉴权双头/response_format/SSE usage（E-12） | **印证** | utils/auth.py:5-16；stream.py:84-131 |
| HolmesGPT "admin/reload 无鉴权"（E-12） | **部分推翻** | 已有 ENABLE_ADMIN_API 默认关+API key 中间件（server.py:421-470）；文档滞后于代码 |
| HolmesGPT 端点清单（E-12） | **需修正** | 新增 /api/info、/api/oauth/callback、/api/checks/execute；"无会话查询接口"仍成立 |
| HolmesGPT spill+阈值压缩（E-15） | **印证** | tool_context_window_limiter.py:33-144；input_context_window_limiter.py:94-96 |
| HolmesGPT 仅 step+token 二维预算（旧 part4） | **印证并补充** | tool_calling_llm.py:1154；补充第三维：显式 max_tokens+末轮 tools=None 收束 |
| "HolmesGPT LLM 错误五分类"（隐含旧认知） | **推翻** | 五分类是请求终态；LLM 异常仅 rate_limit/other 二分+字符串匹配 |
| Gemini/OpenCode：TOML 规则、三态、ask→deny、denyMessage、deny 剔除、doom_loop、external_directory、last-match-wins、deny 子代理、MCP 统一权限管道（v2 §1/§2） | **印证（全部源码级复证）** | 详见 h05 分册 §3 |
| OpenCode ".env 默认 deny"（v2 §2） | **推翻** | 现源码为 `read:{"*.env":"ask"…}`，是 ask 不是 deny（agent/agent.ts:119-125）；AM4 若硬保秘密应自设 deny |
| "Gemini max_turns 未核实"（v2 §1） | **修正** | MAX_TURNS=100（client.ts:79） |
| "Gemini workspace 档策略不生效（issue #18186）"（v2 §1） | **维持未核实** | 分档代码存在（toml-loader.ts:201），端到端生效链路未追到 |
| OpenCode question/plan_enter/plan_exit 默认 deny | **新增发现** | agent/agent.ts:114-116；向用户提问的工具在非交互场景默认被禁，与告警无人值守直接相关 |
| Goose 仓库迁移 block→aaif-goose（v2 §3） | **印证** | 同一 repo id 301，pushed_at 当日 |
| Goose 80% 阈值+TOOL_CALL_CUTOFF+MAX_TURNS=1000（v2 §3） | **印证** | lib.rs:32；context_mgmt/mod.rs:367-590；agent.rs:90 |
| Goose "仍超限走 summarize/truncate/clear/prompt 四选一兜底枚举"（v2 §3） | **部分推翻** | 现源码无此枚举（grep ContextStrategy 零匹配）；实为 summarize 单路+反应式≤2 次，clear 仅是 slash 命令；AM4 不应再引用"四选一" |
| Goose "Smart Approval 读写分类由 LLM 解释"（v2 §3，文档级） | **印证升级（源码级实锤）** | permission_judge.rs:145-185 调 provider.complete 判只读；反例锚点地位不变且更硬 |
| Aider repo map=tree-sitter+PageRank+token 预算（v2 §4） | **印证并补齐** | 补二分裁剪（repomap.py:666-703）与边权重启发式细节 |
| Aider 维护状态 | **新增发现** | pushed_at 2026-05-22、1,854 open issues，维护放缓 |
| goose#8406（截断失败会话不可恢复）修复状态 | **未核实** | 本轮未追 issue |
| Cline "模型自标 requires_approval，harness 只认标志"（v2 §5） | **推翻（已过时）** | 现 main 工具 schema 无此参数，审批按工具名类别+plan 确定性黑名单；act 模式自动批命令无内容检查的风险以新形态存在 |
| Cline checkpoint 恢复三选（v2 §5） | **印证** | restoreType 三态仍透传；实现从 shadow git 变为用户仓库 stash+私有 ref（补充） |
| "Cline YOLO 默认裸奔"（v2 §5） | **部分修正** | yolo 是显式 opt-in preset 非默认；真正宽松默认是 editFiles/useMcp 默认 true（AutoApprovalSettings.ts:33-42） |
| Hermes "2026-02 发布、数万至十余万星"（旧任务书描述） | **推翻** | 实际 2025-07-22 创建、242,179★【API】 |

---

## §15 适配性评估表

四锚点不变：**harness 优先**（决策权在确定性代码）、**单实例**、**PG 全家桶**、**Java 栈**。

| # | 巧思 | 来源对象 | 适合？ | 锚点过滤理由 | 落点 |
|---|---|---|---|---|---|
| 1 | 三态决策（ALLOW/REQUIRE_APPROVAL/DENY(reason)）+拒绝结构化回喂模型自纠 | Codex/Gemini/OpenCode/Claude Code | ✅ | 确定性代码即可实现，reason 强制非空 | AM4 ToolPolicy/Gateway |
| 2 | 权限规则由 harness 强制执行不靠模型自觉（官方明文+纯函数双证） | Claude Code/Codex/OpenHands/OpenClaw | ✅ | 铁律的直接工业化参照 | AM4 Native 内核 |
| 3 | 声明式规则即数据+五档 tier+priority/1000 分档 | Gemini CLI | ✅ | 规则是数据可审计；Java 侧 tier*1000+priority | AM4 ToolPolicy 规则文件 |
| 4 | 无匹配默认 ask 兜底+键序即优先级 | OpenCode | ✅ | 兜底问人不放行 | AM4 ToolPolicy |
| 5 | deny 的工具/子代理从模型上下文静态剔除 | Gemini/OpenCode/Claude Code（deny 裸名） | ✅ | 安全+省 token 双收益 | ToolRegistry 可见性层 |
| 6 | denyMessage/命中规则 JSON 回喂模型 | Gemini/OpenCode | ✅ | 模型看得到为什么被拒 | AM4 Gateway |
| 7 | 审批沉淀为确定性规则（acceptWithExecpolicyAmendment） | Codex | ✅ | 人审一次入库，优于写 JSON | PG 规则表 |
| 8 | 非交互 ask→deny 三重兜底/auto-reject | Gemini/OpenCode | ✅ | 告警事件驱动=非交互 | AM4 审批层 |
| 9 | 审批 fail-closed（无回调/无 answerer/分析器异常=HIGH） | Cline/dsh/OpenHands | ✅ | 审批通道故障默认拒绝 | 裁决层设计 |
| 10 | UNKNOWN≠安全、系统性升级确认 | OpenHands | ✅ | 缺省安全是错觉 | M4-16 |
| 11 | doom_loop 同工具同参 3 次熔断（规范化 JSON 比较） | OpenCode（修正后）/Gemini | ✅ | 纯计数器零 LLM | RunBudget 一维 |
| 12 | StuckDetector 卡死模式+同错先 nudge 再判死 | OpenHands | ✅ | 防 LLM 死循环 | Reconciler |
| 13 | 工具结果双通道（content/details）+spill-to-disk 指针化 | Pi/HolmesGPT/dsh | ✅ | 报告渲染与模型上下文解耦 | AM4 工具结果模型+上下文预算 |
| 14 | 校验错误先纠偏后报错+完整参数回显回喂 | Pi | ✅ | 模型自纠的工程化格式 | AM4 工具执行层 |
| 15 | 上下文预算公式族（单工具 min(15%,25k)/压缩 95%/输出 max(64k,12%)/显式 max_tokens） | HolmesGPT | ✅ | 公式直接搬，默认值按本项目模型重校 | AM4 上下文预算 |
| 16 | 双触发压缩+反应式≤2 次+可见性位翻转不删消息+保留最近 user 消息 | Goose | ✅ | 防死循环+利于审计回放 | AM4 上下文层 |
| 17 | 剪枝→摘要两段式+replaceGeneration"无进度不重试" | dsh | ✅ | 压缩防空转的进度证明 | AM4 上下文层 |
| 18 | 溢出恢复强制确定性（不依赖再发一次 LLM） | Cline | ✅ | 与评测确定性同构 | 预算层降级路径 |
| 19 | max-steps 到限"总结移交"提示词 | OpenCode | ✅ | 耗尽退出姿势非硬截断 | RunBudget 到限行为 |
| 20 | 会话 append-only 事件日志/JSONL 全量录制可回放 | dsh/Pi/Codex | ✅ | PG 表替代文件即可 | AM1/AM4 事件建模 |
| 21 | Operation 管线：护栏前置、LLM 推理最后一步 | Goose | ✅ | 与"决策权在确定性组件"同构 | AM4 loop 编排 |
| 22 | 外部内容=不可信输入：包裹+随机边界+特殊 token 消毒 | OpenClaw | ✅ | 告警文本即潜在注入载体 | AM1 接入层/告警清洗 |
| 23 | hardline/dangerous 正则双层+yolo 冻结-at-import+敏感写目标清单 | Hermes | ✅（未来加 shell 时） | 确定性地板在人审与 LLM 之下 | 裁决层/工具策略 |
| 24 | LLM 守护者"UNTRUSTED INPUT 包裹+ESCALATE 出口" | Hermes | ✅（附加层） | LLM 评审组件的安全姿势 | LLM 评审组件 |
| 25 | repo map：tree-sitter+PageRank+预算二分的确定性预索引 | Aider | ✅ | 告警域实体图同款，零 embedding | DomainProbe 预索引 |
| 26 | recipe=诊断 playbook（声明式+参数注入+输出 schema） | Goose | ✅ | 确定性骨架 | RCA playbook |
| 27 | 双池调度+按空闲槽 claim+request_sequence 拒旧回写 | HolmesGPT | ✅ | PG SKIP LOCKED/行版本号等价，补优先级 | M4 调度层 |
| 28 | MCP 客户端治理：双名单过滤/per-server 超时/env 消毒/错误回喂模型不崩 loop | Codex/Gemini/Claude Code/OpenHands | ✅ | 后续接 MCP 的施工图 | MCP 工具层 |
| 29 | OSV 恶意包预检（MCP spawn 前） | Hermes | 参考 | 接三方 MCP server 时适用 | 部署安全 |
| 30 | 任务后经验沉淀（fork 回放+guards+curator 只归档不删除） | Hermes | ⚠️ DEFER | 与评测确定性张力大，M4 不做 | M4 之后阶段 |
| 31 | LLM 语义判环/next-speaker 续跑 | Gemini | ⚠️ 观察 | 只做熔断不做决策，风险低；先看确定性层够不够 | M4 暂不上 |
| 32 | 子代理隔离上下文+蒸馏摘要 | Claude Code | ⚠️ DEFER | 单次 RCA 体量小暂不需要 | AM4 多线并行时再评估 |
| 33 | LLM 审 LLM 审批/分类（auto 分类器/Smart Approval/模型自标） | Claude Code/Goose/Cline（旧） | ❌ | 判定权在模型、评测不可复现；业界自己在撤退 | 拒绝 |
| 34 | YOLO/裸奔默认一族 | Pi/Goose/Cline/OpenCode | ❌ | 生产交易域不可接受；默认安全需显式经营 | 拒绝 |
| 35 | 平台化分布式/多 channel/巨兽化 | OpenHands/OpenClaw（入口面）/Hermes | ❌ | 单实例 PG 用不上，引入即多真相/扩大注入面 | 拒绝 |
| 36 | hook 超时 fail-open | Claude Code | ❌ | 告警场景超时必须 fail-closed | 拒绝（反向修正） |
| 37 | Starlark 策略语言/OS 沙箱 Landlock/seccomp | Codex | ❌ | 过重；3.10 内核不可用；只读工具用不到 | 拒绝 |
| 38 | Cordis 全插件运行时+事件不变式体系 | dsh | ❌ | 团队级工程投入，只取 waterfall 否决语义 | 拒绝（取概念不取实现） |
| 39 | 模型自扩工具面（Extension Manager 动态启用） | Goose | ❌ | 工具面应白名单静态化 | 拒绝 |
| 40 | MCP 裸接不过滤 | Pi 反证 | ❌ | 21 工具 13.7k token 常驻上下文 | 拒绝 |
| 41 | LLM 自主写持久记忆/技能 | Hermes | ❌ | 与铁律冲突 | 拒绝（留蓝本） |
| 42 | 隐式确认（重进 run() 即批准，无审计事件） | OpenHands | ❌ | Ledger 必须显式记录审批 | 拒绝 |
| 43 | 会话级内存态为唯一真相 | 各 CLI session JSONL 模式 | ❌ | 告警 RCA 真相必须在 PG | 拒绝（维持 PG 唯一真相） |
| 44 | heartbeat 自唤醒/Operator 主动巡检 | OpenClaw/HolmesGPT | ❌ | 告警域有确定触发源，自唤醒制造无证据行动 | 拒绝 |
| 45 | 字符串匹配做错误分类 | HolmesGPT | ❌ | 脆弱；应结构化（异常类型+provider 码） | 拒绝（反面教材） |

---

## §16 明确拒绝项+理由

1. **LLM 审 LLM 的审批/风险分类**：Claude Code auto 分类器、Goose Smart Approval（permission_judge.rs 源码级实锤：LLM 判错为只读即静默放行）、Cline 旧版模型自标 requires_approval（当前 main 已移除——业界自己也在撤退）。与"LLM 只提建议、决策权在确定性代码"直接冲突，评测不可复现。LLM 输出只能作为**建议**进入审批 UI（Hermes 守护者附加层是唯一可接受形态：确定性正则地板+人审闸门+LLM 评审可旁路增强+ESCALATE 出口）。
2. **默认裸奔一族**：Pi YOLO by default（工具链零审批代码）、Goose Auto 默认、Cline yolo preset+editFiles/useMcp 默认 true、OpenCode `"*":"allow"` 宽松默认。Pi 的论证前提（coding agent 三件套）不适用于只读探针为主的 RCA Agent；OpenCode 源码证明"默认安全"只在 doom_loop/external_directory/.env(ask) 三处显式经营。
3. **平台化/分布式/巨兽化架构**：OpenHands Agent Server 多 workspace、OpenClaw 多 channel 聊天入口、Hermes 26 平台 88 万行、Codex 100+ crate。单实例、双机、Docker Compose、PG 全家桶用不上；引入即多真相或扩大注入面。注意 v1 拒绝理由需修正：OpenClaw 本体实为单 gateway+单 SQLite，不是分布式双真相架构，拒绝理由改为"TCB 内插件+聊天入口扩大注入面"。
4. **hook 超时 fail-open**：Claude Code 明示 "don't count on a stalled hook to act as a gate"。告警场景的审批/拦截 hook 超时必须 fail-closed（超时=拒绝并记 Ledger）。
5. **Starlark 策略语言与 OS 沙箱**：Starlark 解释器对 Java 栈过重，前缀模式表+三态即可覆盖告警只读场景；Landlock 需内核≥5.13（本项目 3.10 不可用），且 R0/R1 只读工具不需要 OS 级沙箱。
6. **Cordis 全插件运行时与事件不变式纪律体系**（dsh）：40+ 包插件内核对单实例 PG 是重型过度设计；只取 waterfall 权威否决、durable 事件日志、guard 保险丝三个语义。
7. **模型自扩工具面**：Goose Extension Manager 会话中动态发现/启用扩展——工具面应白名单静态化，模型自扩攻击面不可接受。
8. **MCP 裸接不过滤**：Pi 实测 Playwright MCP 21 工具 13.7k token、Chrome DevTools 26 工具 18k token 常驻上下文；接入必须配 per-server 白名单+输出预算+统一权限管道，自研 DomainProbe 优先聚合工具形态。
9. **LLM 自主写持久记忆/技能**：Hermes background review 虽有完整 guards 链（pinned 不可写/read-before-write/白名单/curator 只归档），仍与"LLM 只提建议"张力大、每轮 ~30K token 成本；M4 不做，全链路留作后续"经验沉淀"阶段蓝本。
10. **隐式确认**：OpenHands 重进 run() 即视为批准，审批动作不产生审计事件——本项目 Ledger 必须显式记录"谁在何时批的"。
11. **会话级内存态为唯一真相**：交互式 CLI 可丢会话重开，告警 RCA 不行；真相在 PG（Run/Task/Attempt/租约/epoch 栅栏），harness 内存态只是缓存。
12. **heartbeat 自唤醒/Operator 主动巡检**（OpenClaw/HolmesGPT Operator mode）：告警域有确定事件触发源，自唤醒循环只会制造无证据行动。
13. **字符串匹配做错误分类**（HolmesGPT `"rate" in msg`）：反面教材——错误分类应结构化（异常类型+provider 码），请求终态与 LLM 异常分类分层建模。

---

## §17 来源清单

| # | 对象 | 仓库 | commit | 星数（2026-09-06） | 取证方式 |
|---|---|---|---|---|---|
| 1 | Claude Code | 闭源（npm @anthropic-ai/claude-code 2.1.263） | — | — | 官方文档 code.claude.com/docs 经 FetchURL 抓全文+npm 元数据 |
| 2 | Codex CLI | github.com/openai/codex | `ac192cd7937b0d73edc6dffe009940ae53782dd4` | 121,857 | gh-proxy 浅克隆（depth 1） |
| 3 | Pi agent | github.com/earendil-works/pi（原 badlogic/pi-mono，301 迁移） | `9767ba275f3e9a5ee0f5c5342249b629ab1b2282` | 102,246 | gh-proxy tarball（HEAD.commit 记录 SHA） |
| 4 | DeepSeek dsh | github.com/deepseek-ai/deepseek-harness | `d347e703908d0406b7a7ef80e3a0e594d86b2215` | 213,560 | gh-proxy tarball |
| 5 | OpenHands SDK | github.com/OpenHands/software-agent-sdk | `fe91d7dfc94d299e3751acb2b0c80ccbc582623c` | 1,058 | FetchURL 抓 raw.githubusercontent 落盘快照 |
| 6 | OpenHands 主仓（Agent Canvas） | github.com/OpenHands/OpenHands | `f7fb0c4b21f5ed726edbba8a6309634ef434b004` | 86,307 | 同上 |
| 7 | OpenClaw | github.com/openclaw/openclaw | `1f36040186a753a352ebb58bdc507966c9e298ac` | 389,000 | 同上 |
| 8 | HolmesGPT | github.com/HolmesGPT/holmesgpt | `5e983c17f30e93099c7d775167266d4cd1d586c4` | 3,213 | gh-proxy 浅克隆（depth 1） |
| 9 | Gemini CLI | github.com/google-gemini/gemini-cli | `85aca163f6c73ac6ce380b5447359146b8adcae4` | 106,829 | codeload tarball（22.8MB 完整校验） |
| 10 | OpenCode | github.com/anomalyco/opencode（原 sst/opencode，301 迁移） | `337fd144d2ba144743368f78d9579a99cce175bd` | 204,868 | codeload tarball（81.5MB）+个别文件 FetchURL 交叉核对 |
| 11 | Goose | github.com/aaif-goose/goose（原 block/goose，301 迁移） | `5e90925962f05acf8e255032de44d16c4a7768a2` | 53,955 | codeload tarball（SHA 取自 pax header） |
| 12 | Aider | github.com/Aider-AI/aider | `5dc9490bb35f9729ef2c95d00a19ccd30c26339c` | 48,781（维护放缓，pushed_at 2026-05-22） | codeload tarball |
| 13 | Cline | github.com/cline/cline | `dac3b35ba485`（2026-09-04） | 67,551 | gh-proxy 浅克隆，commit 与 API 交叉核对 |
| 14 | Hermes Agent | github.com/NousResearch/hermes-agent | `0a195aa46364`（2026-09-06） | 242,179 | gh-proxy 浅克隆，commit 与 API 交叉核对 |

（对象 5/6/7 同列于 h03 分册，故实际调研对象 13 个、来源行 14 行。）

**星数/commit 元数据获取**：api.github.com 本机 IP 限流，经 FetchURL 出口取得；h03 三仓快照取证未经 git 克隆（FetchURL 落盘后 Grep/Read 取行号），其余均为本地完整源码精读，无 README-only 结论。

**遗留未核实事项**：
- Claude Code 全部机制为【文档】级（闭源无源码），实现细节不可证。
- HolmesGPT claim 原子性 SQL（`FOR UPDATE SKIP LOCKED` 类）在闭源仓 robusta-storage，未核实。
- OpenHands "30k 字符扫描预算防 ReDoS""execute_tool() 绕过检查"（旧 part1 §14）本轮快照未含 defense_in_depth 子包，维持未核实。
- Gemini workspace 档策略端到端生效链路（issue #18186）维持未核实。
- goose#8406（截断失败会话不可恢复）修复状态未追。
- OpenHands SDK 单次 cost 计价实现文件本地快照缺失，【未核实】。
