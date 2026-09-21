# 工具调用 / MCP / 单 Agent / 多 Agent 系统评测指标与方法调研

> 调研日期：2026-09-20
> 原则：所有关键论断追溯至一手来源（论文 arXiv/PMLR 页面、官方 leaderboard、官方工程博客、源码仓库），不引用二手转述；找不到一手来源的明确标注。
> 前置沉淀：本报告在 `research/alert-agent-eval-20260917/`（AIOps/告警 RCA 方向的国内外评测调研与差距分析）基础上扩展，**新增覆盖面是通用工具调用（function calling）、MCP 生态、通用单/多 Agent 基准**——前一目录聚焦 RCA 任务本身，本报告聚焦"工具用得好不好"与"Agent 系统怎么评"。

---

## 摘要

业界对"模型/Agent 用工具用得好不好"已经形成三条可量化的主流评测线：一是**逐调用静态判定**（BFCL 的 AST matching、Seal-Tools 的 Tool P/R/F1 与 Parameter P/R/F1、BFCL 的 relevance/irrelevance 幻觉检测），二是**可执行/终态判定**（BFCL executable test 与 state-based evaluation、τ-bench 的对话结束数据库终态比对 goal state），三是**可靠性刻画**（τ-bench 的 pass^k，揭示 SOTA 模型单次成功率与稳定性的巨大落差——gpt-4o 单次 <50%、pass^8 <25%）。MCP 生态 2025–2026 年出现了专门基准（MCPToolBench++、MCP-Atlas、MCPAgentBench）与工具描述质量研究（97.1% 的 MCP 工具描述存在至少一个 "smell"），核心发现是**失败大头在认知层而非工具调用层**（MCP-Atlas：63.3%）。单 Agent 的 outcome 型基准（AgentBench/WebArena/SWE-bench/GAIA/OSWorld/WorkArena）统一收敛到"execution-based 终态校验 + 人类基线对照"；多 Agent 评测的特有维度（协作协议拓扑、通信开销、角色分工、失败模式分类）由 MultiAgentBench 和 MAST（14 类失败模式、3 大类）奠基，而 multi-agent 相对 single-agent 的收益在多份一手研究中均被证明**有限且非单调**。对照之下，我们系统的指标体系骨架已达行业一线（见 0917 差距分析），缺的是**逐调用判定、终局状态一致性、pass^k、minefield（禁态）约束、claim-level 报告评分、工具 schema 质量门禁**六类机制，本报告给出 15 项建议指标的落地表与优先级。

---

## 一、工具调用与 MCP 的评测

### 1.1 BFCL（Berkeley Function Calling Leaderboard）——事实标准

- **定位与演进**：BFCL 由 UC Berkeley Gorilla 团队维护，ICML 2025 正式论文自述"已成为 function-calling 评测的事实标准（defacto standard）"。版本线：V1 单轮 AST 评估；V2 引入企业/社区贡献的 live 数据 + relevance/irrelevance + executable test；V3 引入 multi-turn/multi-step 与 **state-based evaluation**；V4 走向 agentic（跨会话记忆、multi-hop web search）。[ICML 2025 论文（PMLR v267 patil25a）](https://proceedings.mlr.press/v267/patil25a.html)、[BFCL 官方 leaderboard](https://gorilla.cs.berkeley.edu/leaderboard.html)
- **核心指标 1 — AST accuracy**：把模型输出解析为抽象语法树，与候选答案做子树匹配（函数名 + 参数名 + 参数值逐一比对），可扩展到数千函数、无需真实执行 API。同期配 **executable accuracy**（对可执行子集真实跑一遍校验）。[PMLR 摘要](https://proceedings.mlr.press/v267/patil25a.html)
- **核心指标 2 — relevance / irrelevance（幻觉工具检测）**：irrelevance 场景下所有候选函数都与 query 无关，正确行为是**拒绝调用**——直接度量"幻觉函数调用率"；relevance 场景只要求识别出应调用函数（不查参数）。[BFCL leaderboard 页说明](https://gorilla.cs.berkeley.edu/leaderboard.html)
- **核心指标 3 — state-based evaluation（V3 multi-turn）**：不再逐轮 AST 比对，而是**在模型跑完函数后校验 API 后端系统（文件系统、预订系统等）的实际状态**。数据集构成：Base 200 + 四个增强类各 200（Missing Parameters 缺参应追问、Missing Functions 缺函数应声明、Long-Context、Composite），共 1000 例；并引入 turn 级幻觉度量。[BFCL V3 官方博客](https://gorilla.cs.berkeley.edu/blogs/13_bfcl_v3_multi_turn.html)
- **结论中有迁移价值的点**：(a) "评估一个函数调用何时合法"本身就是难题，AST + 终态双轨是业界答案；(b) SOTA 模型单轮调用已很强，**memory、动态决策、长程推理仍是开放问题**（PMLR 摘要原话）；(c) 缺参数时"追问而非臆填"是被显式评测的行为——与我们"拿不准就老实说"的规约同向。

### 1.2 ToolBench / ToolLLM —— 大规模真实 API

- 16,464 个真实 RESTful API（RapidAPI Hub，49 类），指令由 ChatGPT 生成、解路径用 DFSDT（depth-first search-based decision tree）标注；评测器 **ToolEval** 两个指标：**pass rate**（限定推理步数内成功给出答案的比例）与 **win rate**（ChatGPT 对比两个解路径的偏好胜率）。[arXiv:2307.16789](https://arxiv.org/abs/2307.16789)
- 迁移价值：真实 API 噪声（超时、返回漂移）会进入评测；pass rate/win rate 依赖 ChatGPT 判定、非确定性，Seal-Tools 论文明确批评了这一点（见其 §3.2 对比段）——**确定性评分优先**的又一佐证。[arXiv:2405.08355](https://arxiv.org/abs/2405.08355)

### 1.3 API-Bank —— 能力分层评测

- 73 个可运行 API 工具，314 条标注对话 / 753 次调用，评估三个递进能力级：**Call（给定 API 会调）→ Retrieve+Call（会检索再调）→ Plan+Retrieve+Call（会规划）**；指标为 API 调用正确率（Accuracy）+ 回复质量 ROUGE-L。训练侧另有 1,888 条对话（Lynx 模型）。[arXiv:2304.08244](https://arxiv.org/abs/2304.08244)、[EMNLP 2023 论文页](https://aclanthology.org/2023.emnlp-main.187.pdf)
- 迁移价值：**按能力级拆开评**——"会不会选工具"与"会不会规划多步"分开计分，避免一个总分掩盖短板。

### 1.4 Seal-Tools —— 逐调用 P/R/F1 的样板

- self-instruct 生成 4,076 个工具、14,076 个实例，含 586 个**嵌套调用**实例（前一工具输出作后一工具入参，构成 DAG）。
- **三个指标，正是逐调用判定的标准范式**：
  - **Format ACC**：输出是否符合规定的 JSON 调用格式；
  - **Tool P/R/F1**：把工具名当信息抽取任务算 precision/recall/F1（选了不该选的扣 P，漏了该选的扣 R）；
  - **Parameter P/R/F1**：对选中工具的参数填充逐项算 P/R/F1。
  [arXiv:2405.08355（论文 §4.1 Evaluation Metric）](https://arxiv.org/pdf/2405.08355)
- 错误分析有迁移价值：参数错误中 7% 是漏填 required 参数、9% 是过度臆填未提及参数——与我们 judge rubric 里"结论有据率"的"臆造 vs 遗漏"二分同构。[同上，§4.3.2](https://arxiv.org/pdf/2405.08355)

### 1.5 ToolACE —— 数据合成侧（兼证 BFCL 的可用性）

- self-evolution 合成 26,507 个 API + multi-agent 对话生成 + 双层校验（rule-based + model-based）；训出的 8B 模型在 BFCL 上达到当时 SOTA、可比肩 GPT-4。ICLR 2025 收录。[arXiv:2409.00920](https://arxiv.org/abs/2409.00920)、[ICLR 2025 论文](https://proceedings.iclr.cc/paper_files/paper/2025/file/663865ea167425c6c562cb0b6bcf76c7-Paper-Conference.pdf)
- 迁移价值：对我们而言其意义在**评测集构造方法**——工具描述可"自我进化"扩写，多 agent 扮演用户/工具/校验员生成对话并双重校验，可用于给我们的 S1~S27 场景做受控扩编。

### 1.6 ToolSandbox —— 有状态、交互式、milestone 评估

- Apple 出品。区别于无状态 REST 评测：工具**有状态**（改世界状态）、工具间有**隐式状态依赖**、内置 **on-policy 用户模拟器**（用户会纠错、补充信息），评估策略为 **Milestone DAG + 动态轨迹评估**（中间里程碑 + 终态都可判分，且含 minefield 即禁态——轨迹触达禁止状态即扣分）。定义的难题类别：State Dependency、Canonicalization（同一实体多种说法归一）、Insufficient Information（信息不足应追问）。[arXiv:2408.04682](https://arxiv.org/abs/2408.04682)、[apple/ToolSandbox 仓库（"Evaluation criteria is defined with a Milestone DAG"）](https://github.com/apple/ToolSandbox)
- 迁移价值：这是我们"检查点覆盖率"指标的升级版——检查点不只判命中，还要判**顺序与禁态**。

### 1.7 τ-bench / τ²-bench —— 工具-Agent-用户三方交互

- **τ-bench**（Sierra，NeurIPS 2024 D&B）：语言模型模拟用户 + Agent 持有领域 API 与 policy 指南，在 airline / retail 两个域对话完成任务。**Reward = 对话结束时数据库终态与标注 goal state 比对**（不是比对话文本）；提出 **pass^k** 指标：对同一任务做 n 次独立试验，其中 c 次成功，则 pass^k = E_task[ C(c,k)/C(n,k) ]——度量"每次都可靠"而非"至少一次成功"。结果：gpt-4o 单任务成功率 <50%，retail 域 pass^8 <25%。[arXiv:2406.12045](https://arxiv.org/abs/2406.12045)
- **τ²-bench**：升级为 **dual-control**——用户也持有工具、与 Agent 共同改变共享世界（建模为 Dec-POMDP），新增 telecom 域；组合式任务生成器保证任务可验证；消融实验把错误**归因为推理错误 vs 通信/协调错误**两类。[arXiv:2506.07982](https://arxiv.org/abs/2506.07982)
- 迁移价值：(a) 终态比对是对"写类工具场景"最干净的评分方式；(b) pass^k 直接回应我们"30 轮 F1≥90%"目标里没覆盖的**稳定性**维度——单轮 90% 不等于可靠；(c) dual-control 对应我们的双人审批链：审批人也是"持工具的另一方"。

### 1.8 MCP 生态的评测实践

**基准类：**

- **MCPToolBench++**：截至 2025-07 基于 4,000+ 真实 MCP server（40+ 类目）构建，含单步与多步调用；论文明确指出 MCP 评测的三个独有难点——真实 MCP 工具成功率无保障、返回格式多样、**工具+参数描述 token 太长导致上下文装不下全部工具**。[arXiv:2508.07575](https://arxiv.org/abs/2508.07575)
- **MCP-Atlas**：1,000 条专家编写任务、36 个生产 MCP server、220 个工具；prompt 不提示该用哪个 server/工具/参数，混入语义相近的干扰工具。**评分用 claim-level rubric**：把标准答案拆成原子事实 claim，按 Agent 最终答案对 claim 的覆盖率打分——**只评答案、不评轨迹**，从而允许任何有效的替代工具调用路径得分。另配 11 类诊断 taxonomy，发现 **63.3% 的可诊断失败是认知失败（理解/综合/解析/过早停止）而非工具调用失败**；500 题公开 + 500 题私有防刷榜。[arXiv:2602.00933](https://arxiv.org/abs/2602.00933)
- **MCPAgentBench**：从真实渠道采集 MCP server 与工具定义，四步数据处理保证任务真实性、工具代表性、解唯一性。[arXiv:2512.24565](https://arxiv.org/abs/2512.24565)

**工具 schema 质量类：**

- 《MCP Tool Descriptions Are Smelly!》：实证 103 个 server / 856 个工具，从文献归纳工具描述的 6 个组成成分并建立 rubric；**97.1% 的工具描述至少含一个 smell，56% 没有说清用途**；补全描述后任务成功率中位数 +5.85pp，但执行步数 +67.46%——**描述质量与执行成本存在权衡**。[arXiv:2602.14878](https://arxiv.org/abs/2602.14878)
- **Anthropic 官方《Writing effective tools for agents》**（MCP 生态最权威的工程实践）：评测方法论=围绕真实用例生成评估任务 → 每个任务配**可验证的结果或终态**（精确匹配到 LLM judge 皆可，但避免过严 verifier）→ 除 top-level accuracy 外**必收运行时、工具调用总次数、token 消耗、工具错误数**四类过程指标 → 用 held-out test set 防过拟合 → 从 transcript 反推工具描述/schema 的问题（例：发现模型给 query 参数无谓追加 "2025"，靠改工具描述修复）。设计原则含 namespacing、工具应返回高信号上下文、token 效率优化。[Anthropic 工程博客](https://www.anthropic.com/engineering/writing-tools-for-agents)

### 1.9 指标定义速查（本节统一口径）

| 指标 | 定义 | 一手出处 |
|---|---|---|
| AST accuracy | 输出解析为 AST 后与候选答案逐节点匹配（函数名/参数名/参数值） | [PMLR v267 patil25a](https://proceedings.mlr.press/v267/patil25a.html) |
| Executable accuracy | 真实执行可执行子集，校验结果 | 同上 |
| State-based eval | 跑完后校验后端系统终态（multi-turn） | [BFCL V3 博客](https://gorilla.cs.berkeley.edu/blogs/13_bfcl_v3_multi_turn.html) |
| Irrelevance/relevance 检测 | 无适用工具时应拒调；有则应识别 | [BFCL leaderboard](https://gorilla.cs.berkeley.edu/leaderboard.html) |
| Tool P/R/F1 | 工具选择当抽取任务算 P/R/F1 | [Seal-Tools §4.1](https://arxiv.org/pdf/2405.08355) |
| Parameter P/R/F1 | 参数填充逐项算 P/R/F1 | 同上 |
| Format ACC | 输出格式合规率 | 同上 |
| pass rate / win rate | 限步内完成率 / ChatGPT 对比胜率 | [ToolLLM arXiv:2307.16789](https://arxiv.org/abs/2307.16789) |
| pass@k vs pass^k | ≥1 次成功 vs 全部 k 次成功（E[C(c,k)/C(n,k)]） | [τ-bench arXiv:2406.12045](https://arxiv.org/abs/2406.12045) |
| 终态 reward | 结束态 DB 与 goal state 比对 | 同上 |
| Milestone / minefield | 轨迹中间里程碑命中 + 禁态触碰惩罚 | [ToolSandbox](https://arxiv.org/abs/2408.04682)、[GitHub](https://github.com/apple/ToolSandbox) |
| Claim-level 覆盖 | 答案对原子事实 claim 的覆盖率，不评轨迹 | [MCP-Atlas](https://arxiv.org/abs/2602.00933) |
| 轮次效率 | 完成任务的步数/轮数（CCF AIOps 2025 用 exp(-(APL-5)/5) 衰减） | [CCF 2025 赛制 PDF](https://www.aiops.cn/wp-content/uploads/2025/06/01-2025-CCF国际AIOps挑战赛赛制介绍-聂晓辉.pdf)（引自 0917 调研） |

---

## 二、相关论文（每方向 2–4 篇，含迁移价值）

### 2.1 工具调用判定方法

1. **BFCL（Patil et al., ICML 2025）**——[PMLR v267](https://proceedings.mlr.press/v267/patil25a.html)。核心方法：AST + executable 双轨、relevance/irrelevance、V3 state-based。迁移点：**逐调用判定用确定性解析而非 LLM judge**；"缺参应追问"作为显式评测行为。
2. **Seal-Tools（Wu et al., NLPCC 2024）**——[arXiv:2405.08355](https://arxiv.org/abs/2405.08355)。核心方法：self-instruct 造 4,076 工具/14,076 实例（含嵌套 DAG 调用）；Format ACC + Tool P/R/F1 + Parameter P/R/F1。迁移点：**工具选择/参数填充的 P/R/F1 公式可直接搬**；嵌套调用（前输出作后入参）是难例方向。
3. **ToolSandbox（Lu et al., Apple, 2024）**——[arXiv:2408.04682](https://arxiv.org/abs/2408.04682)。核心方法：有状态工具 + 隐式状态依赖 + on-policy 用户模拟 + Milestone DAG。迁移点：过程评估从"命中检查点"升级为"DAG 顺序 + 禁态"。
4. **ToolACE（Liu et al., ICLR 2025）**——[arXiv:2409.00920](https://arxiv.org/abs/2409.00920)。迁移点：工具/对话合成 + 双层校验管线，可用于场景扩编。

### 2.2 交互式与终态评测

1. **τ-bench（Yao et al., NeurIPS 2024）**——[arXiv:2406.12045](https://arxiv.org/abs/2406.12045)。终态 DB 比对 + pass^k；SOTA 可靠性断崖（pass^8<25%）。迁移点：**单轮高分≠可靠**，30 轮目标需配 pass^k。
2. **τ²-bench（Barres et al., 2025）**——[arXiv:2506.07982](https://arxiv.org/abs/2506.07982)。dual-control Dec-POMDP；错误归因二分（推理 vs 通信）。迁移点：审批链=双方共同改变世界，应分别归因。
3. **API-Bank（Li et al., EMNLP 2023）**——[arXiv:2304.08244](https://arxiv.org/abs/2304.08244)。能力三级分层（Call/Retrieve+Call/Plan+Retrieve+Call）。迁移点：按能力级拆指标。

### 2.3 MCP 生态

1. **MCPToolBench++（Fan et al., 2025）**——[arXiv:2508.07575](https://arxiv.org/abs/2508.07575)。4k+ server 规模；真实 MCP 工具成功率无保障的警示。
2. **MCP-Atlas（Bandi et al., 2026）**——[arXiv:2602.00933](https://arxiv.org/abs/2602.00933)。claim-level rubric + 11 类失败 taxonomy；**63.3% 失败是认知层**。迁移点：报告评分用原子 claim 覆盖，失败归因区分"工具没调对"与"想错了"。
3. **MCP Tool Descriptions Are Smelly（Hasan et al., 2026）**——[arXiv:2602.14878](https://arxiv.org/abs/2602.14878)。97.1% 描述有 smell；描述补全的收益-步数权衡。迁移点：给我们 14 个工具描述做 smell lint。
4. **Anthropic《Writing effective tools for agents》**——[官方博客](https://www.anthropic.com/engineering/writing-tools-for-agents)。工具评测工程闭环：任务→可验证终态→过程指标→held-out 防过拟合→transcript 反哺 schema。

### 2.4 单 Agent 系统评测（outcome 型）

1. **AgentBench（Liu et al., ICLR 2024）**——[arXiv:2308.03688](https://arxiv.org/abs/2308.03688)。8 个环境（OS/DB/KG/卡牌/网页等），每环境各自指标（含 F1、成功率），总分跨环境归一；发现闭源与开源差距显著，长程推理/决策/指令遵循是主要障碍。
2. **WebArena（Zhou et al., ICLR 2024）**——[arXiv:2307.13854](https://arxiv.org/abs/2307.13854)。自托管真实网站（电商/论坛/GitLab/CMS），**functional correctness** 判定；GPT-4 agent 14.41% vs 人类 78.24%。
3. **SWE-bench（Jimenez et al., ICLR 2024）**——[arXiv:2310.06770](https://arxiv.org/abs/2310.06770)。2,294 个真实 GitHub issue，**执行测试判定 % resolved**；初版最好模型仅 1.96%——执行型终态校验的标杆。
4. **GAIA（Mialon et al., ICLR 2024）**——[arXiv:2311.12983](https://arxiv.org/abs/2311.12983)。466 题三级难度，人类 92% vs GPT-4+插件 15%；300 题答案私有保榜。
5. **OSWorld（Xie et al., NeurIPS 2024）**——[arXiv:2404.07972](https://arxiv.org/abs/2404.07972)。369 个真实电脑任务，每题配**自定义 execution-based 评估脚本**；最佳模型 12.24% vs 人类 72.36%。
6. **WorkArena（Drouin et al., ICML 2024）**——[arXiv:2403.07718](https://arxiv.org/abs/2403.07718)。ServiceNow 企业软件 33 任务 + BrowserGym；开源/闭源差距显著。
- 共同方法论：**execution-based 终态校验（非文本匹配）+ 人类基线 + 私有集防刷榜**。我们的"类型化根因三元组等值判定"与此同构，方向正确（0917 差距分析已确认）。

### 2.5 过程/轨迹评估与 LLM-as-judge 可靠性

1. **Judging LLM-as-a-Judge（Zheng et al., NeurIPS 2023）**——[arXiv:2306.05685](https://arxiv.org/abs/2306.05685)。系统刻画 position bias、verbosity bias、self-enhancement bias、推理能力限制；GPT-4 judge 与人类偏好一致率 >80%（≈人际一致水平）。迁移点：**judge 必须先做偏差审计与人机对齐校准再上岗**。
2. **AgentEval（Arabzadeh et al., EMNLP 2024）**——[arXiv:2405.02178](https://arxiv.org/abs/2405.02178)。CriticAgent 生成评估 criteria → QuantifierAgent 量化打分 → VerifierAgent 校验 criteria 鲁棒性（含噪声对抗样本区分度测试）。迁移点：**rubric 本身要被评测**（可区分噪声与正例才合格）——我们的 judge rubric 可做同样的对抗验证。
3. **ToolSandbox milestone 评估**（见 2.1.3）与 **BFCL V3 state-based**（见 1.1）：过程评估的两个确定性样板。

### 2.6 多 Agent 系统评测

1. **MAST（Cemri et al., 2025）**——[arXiv:2503.13657](https://arxiv.org/abs/2503.13657)。对 7 个主流 MAS 框架（AutoGen/ChatDev/CrewAI 等）的 200 条轨迹做 Grounded Theory 分析，构建 **Multi-Agent System Failure Taxonomy**：**14 种失败模式，3 大类——(i) 系统设计问题（角色/任务规约违反、缺终止条件）、(ii) agent 间错位（对话重置、上下文丢失、未完成任务即交接）、(iii) 任务验证缺失（过早终止、验证不完整）**；标注者一致性 kappa=0.88，并发布 1,600+ 标注轨迹数据集与 LLM 标注管线。开篇论断：**MAS 在流行基准上的收益常常微乎其微**。
2. **MultiAgentBench（Zhu et al., 2025）**——[arXiv:2503.01935](https://arxiv.org/abs/2503.01935)。评测协作与竞争双维度：**milestone-based KPI**（任务完成 + 协作质量）；对比 star/chain/tree/graph 四种**协调协议拓扑**与 group discussion、cognitive planning 策略（后者使 milestone 达成率 +3%）。迁移点：协作质量要独立于任务完成单独计分。
3. **Magentic-One（Fourney et al., MSR, 2024）**——[arXiv:2411.04468](https://arxiv.org/abs/2411.04468)。Orchestrator + 专职 agent 的通用多 agent 系统，在 GAIA/AssistantBench/WebArena 上与 SOTA **统计持平**；附 AutoGenBench（带重复运行与隔离控制的评测工具）。迁移点：多 agent 的收益体现在鲁棒性/可扩展性，不是准确率碾压。
4. **Are More LLM Calls All You Need?（Chen et al., NeurIPS 2024）**——[arXiv:2403.02419](https://arxiv.org/abs/2403.02419)。Vote/Filter-Vote 随调用次数**先升后降的非单调缩放**：简单查询越投越准、困难查询越投越差。迁移点：多 agent/多采样不是免费午餐，评测必须按难度分桶看。
5. **AutoGen（Wu et al., 2023）**——[arXiv:2308.08155](https://arxiv.org/abs/2308.08155)。多 agent 会话框架奠基作。

**multi-agent 特有评测维度汇总**（一手出处同上）：协作效率（milestone 达成率，MultiAgentBench）、通信开销（消息轮数/token，τ²-bench 的通信错误消融）、角色分工质量（MAST 的 role-specification violation）、涌现错误（MAST 14 模式中的 inter-agent misalignment）、冲突消解/验证（MAST 第三类 task-verification failure）。

---

## 三、我们该怎么评（落地建议）

### 3.0 现状速描（来自任务输入与 0917 摸底，不重复展开）

14 个工具（prometheus/logs/change/rca_history/runbook 等只读 R0 + service.restart/service.rollback 写类 R3 双人审批）；golden 场景 S1~S27；已有指标：根因命中率、症状 F1/P/R、三维评分（定因/路径/结论）、报告六要素完整度、judge rubric、过程指标（结构通过率/重复动作率/工具错误率/检查点覆盖率/结论有据率/延迟分位/token）；目标 30 轮 F1≥90%。0917 差距分析已指出：**六维过程指标有计数但未进判定**。

### 3.1 对照业界，我们缺什么

| # | 缺口 | 业界对标 | 性质 |
|---|---|---|---|
| N1 | **无逐调用判定**：工具选对没有、参数填对没有，目前只在场景终局间接体现 | BFCL AST、Seal-Tools Tool/Parameter P/R/F1 | 直接可搬 |
| N2 | **无幻觉工具率**：模型编造不存在的工具/参数没有被显式计数与进判定 | BFCL irrelevance detection | 直接可搬 |
| N3 | **无稳定性指标**：30 轮 F1 是均值口径，无法区分"次次 90%"与"一半 100% 一半 80%" | τ-bench pass^k | 直接可搬 |
| N4 | **写类工具场景无终态判定**：service.restart/rollback 目前判"动作是否发起"，没判"系统终态是否达到 goal state 且无副作用" | τ-bench 终态 reward、BFCL V3 state-based | 需改造（终态断言模板） |
| N5 | **检查点无顺序与禁态**：检查点覆盖率只数命中，不判顺序错乱和"未诊断先重启"这类禁态 | ToolSandbox Milestone DAG + minefield | 需改造 |
| N6 | **报告评分是整段 rubric，非原子 claim**：结论有据率按"要素"计，粒度粗 | MCP-Atlas claim-level rubric | 需改造（拆原子 claim） |
| N7 | **工具 schema 质量无门禁**：14 个工具描述从未按成分 rubric 体检 | MCP smell 研究、Anthropic 工具写作原则 | 直接可搬 |
| N8 | **失败无认知/工具归因**：bad case 不区分"工具没调对"与"想错了" | MCP-Atlas 11 类 taxonomy、τ² 推理/通信二分 | 需改造（归因标签） |
| N9 | **审批链（双人审批）未进评分**：审批是 dual-control 协作，误批/漏升级/绕过均无量化 | τ²-bench dual-control、ToolSandbox minefield | 需改造 |
| N10 | **judge rubric 自身未做对抗校验** | AgentEval VerifierAgent、Zheng judge 偏差审计 | 直接可搬 |

### 3.2 可直接搬、需改造、不必搬

- **直接搬**（公式原样适用）：Tool P/R/F1、Parameter P/R/F1、Format ACC（Seal-Tools）；幻觉工具率（BFCL irrelevance）；pass^k（τ-bench）；过程四计数（运行时/调用次数/token/工具错误，Anthropic 口径——我们已有，需进判定）；judge 人机一致率（Zheng >80% 基线 + Cohen's kappa 校准，0917 调研已建议）。
- **需改造**：终态一致性（我们终态=微服务集群状态+工单/审批记录状态，需为每个写类场景写 goal-state 断言模板，而非通用 DB 比对）；Milestone DAG（在 eval-scenarios.yml 的检查点上补 `order_constraints` 与 `forbidden_states`）；claim-level 报告评分（把六要素拆成原子 claim 集，按覆盖率计分，允许等效表述）；审批链评分（把审批人当 τ² 的"持工具用户"，评测 Agent 提交的审批材料质量 + 审批流合规）；失败归因 taxonomy（在 bad case 登记表上加"工具调用错/检索错/推理错/综合错/过早停止"标签）。
- **不必搬**：win rate / ROUGE-L（非确定性或文本相似度，与 0917"确定性评分为王、零 LLM judge 主判"纪律冲突——LLM judge 仅留作辅助且须校准）；ToolBench 式真实 API 池（我们的工具面固定 14 个，无需规模化 API 检索评测）。

### 3.3 工具层面还该补的指标（回答"逐调用判定"）

1. **工具选择 P/R/F1**：每个 golden 场景标注"该用的工具集合"（多数场景已有预期证据路径，可低成本派生）；判定口径同 Seal-Tools——多调扣 P、漏调扣 R。注意 MCP-Atlas 的教训：**允许等效替代路径**，标注应是"可接受工具集"而非唯一解。
2. **参数填充正确率**：对每次调用比对参数名/参数值；区分漏填 required（Seal-Tools 实测占错误 7%）与臆填未提及参数（占 9%）——后者与"结论有据率"共用"有据"哲学。
3. **幻觉工具率**：调用不存在工具/不存在参数的次数 ÷ 总调用；REDTEAM 可注入"诱导编造工具"用例。
4. **缺参追问正确率**（BFCL Missing Parameters 思路）：构造信息不足场景，正确行为是声明信息不足/追问，而非臆填——与我们置信度分级（中=先补取证）对齐。

### 3.4 多步轨迹效率怎么度量

- **轮次/步数**：完成场景的 Agent 步数（ITBench-AA 的 Average Turns 口径；CCF 2025 的 Efficiency=exp(-(APL-5)/5) 只对答对案例计，防"答错但步数少"刷分——这个"仅对正确案例惩罚长轨迹"的设计直接可抄）。
- **重复动作率**（已有）：保留并进判定。
- **信息增益效率**（进阶，P3）：每次工具调用带来的新证据量 ÷ token——业界无成熟一手标准，标为探索项。
- **预算超限率**：延迟/token/调用次数三预算的超限比例（Anthropic 口径），作为硬门槛而非软分。

### 3.5 写类工具（审批链）怎么纳入评分

把审批链建模为 **dual-control**（τ²-bench 框架）：Agent 与审批人共同改变世界。评分分四层：

1. **前置合规（minefield）**：未命中诊断检查点即发起 service.restart/rollback = 禁态，本场景直接判 fail 并计数"越级操作率"（ToolSandbox minefield）。
2. **审批材料质量（claim-level）**：提交审批的诊断报告必须覆盖该写操作的原子 claim（影响面、回滚预案、证据引用），按覆盖率计分。
3. **终态一致性**：执行后校验系统终态 = 预期恢复态（指标回落、无次生告警），且无预期外副作用（其他服务状态差分为零）——τ-bench 终态 reward 的改造版。
4. **升级正确率**：构造"不该动手"的场景（证据不足/根因未定），正确行为是**不发起写操作或升级人工**；误发起计 FN 方向的"轻率动手率"，漏发起计"该动不动率"。

### 3.6 建议指标落地表

| 指标名 | 定义 | 数据源 | 计算方式 | 优先级 |
|---|---|---|---|---|
| 工具选择 P/R/F1 | 该用工具集的抽取式 P/R/F1 | 轨迹工具调用序列 × 场景标注可接受工具集 | Seal-Tools 口径，按场景宏平均 | **P0** |
| 参数填充正确率 | 参数名/值逐项 P/R/F1，分漏填/臆填两类 | 轨迹调用参数 × 标注参数集 | Seal-Tools 口径 | **P0** |
| pass^k（k=5） | 同场景 n 次独立运行全对率 | 重复运行结果 | E[C(c,k)/C(n,k)]，τ-bench 公式 | **P0** |
| 终态一致性（写类场景） | 执行后系统态与 goal-state 断言匹配，无副作用 | 场景 goal-state 断言模板 + 环境探测面 | 确定性断言，逐条布尔 | **P0** |
| 禁态违规率（minefield） | 轨迹触达 forbidden state（未诊断先重启/绕过审批）次数 | 场景 forbidden_states 标注 × 轨迹 | 触达即场景 fail + 全局计数 | **P0** |
| 幻觉工具率 | 调用不存在工具/参数 ÷ 总调用 | 轨迹 × 工具 registry | 计数比；REDTEAM 加诱导用例 | P1 |
| 检查点顺序合规率 | 检查点不仅命中且满足 order_constraints | 场景 DAG 标注 × 轨迹 | DAG 拓扑校验 | P1 |
| 缺参追问正确率 | 信息不足场景下"声明不足/追问"而非臆填的比例 | 特设信息不足场景 | 行为分类判定 | P1 |
| 报告原子 claim 覆盖率 | 报告命中原子 claim 集的比例（六要素细化） | 场景 claim 集标注 × 报告文本 | 确定性匹配 + 已校准 judge 辅助 | P1 |
| 失败归因分布 | bad case 按 工具/检索/推理/综合/过早停止 五类归因 | bad case 登记表 + 人审 | 标签分布（MCP-Atlas taxonomy 精简版） | P1 |
| 审批材料 claim 覆盖 + 轻率动手率/该动不动率 | 见 §3.5 第 2/4 层 | 审批记录 + 特设场景 | 覆盖率 + 行为分类 | P1 |
| 轮次效率（仅正确案例） | 答对场景的平均步数惩罚分 | 轨迹步数 | CCF 口径 exp(-(APL-5)/5) 或简化线性 | P2 |
| 预算超限率 | 延迟/token/调用次数超预算的场景占比 | 运行遥测 | 阈值计数（已有雏形，进判定） | P2 |
| 工具描述 smell 数 | 14 个工具描述按 6 成分 rubric 体检 | 工具 schema | lint 脚本（可 LLM 辅助，人工复核） | P2 |
| judge 人机一致率（kappa） | judge rubric 与人审的 Cohen's kappa | 抽样双标 | kappa<0.7 触发 rubric 修订（0917 已定方向） | P2 |

### 3.7 与"30 轮 F1≥90%"目标的关系

F1≥90% 是**均值口径**，业界教训（τ-bench）是均值高而 pass^k 低=生产不可用。建议把目标改写为双门槛：**症状 F1≥90% 且 pass^5≥80%**；同时把 P0 五项（工具选择/参数填充/终态一致性/minefield/pass^k）作为 F1 之外的并列质量门，避免单指标刷分（OpenRCA 2.0 已证明单一榜单会饱和失真，见 0917 调研）。

---

## 四、单 Agent 与多 Agent 系统评测

### 4.1 Outcome 型基准的共同做法

六个基准的一手事实已在 §2.4 列出，此处提炼共性：

1. **execution-based 终态校验是唯一主流**：WebArena 判 functional correctness、SWE-bench 跑测试判 % resolved、OSWorld 每题自带评估脚本、τ-bench 比对 DB 终态。没有任何一个一线基准用"轨迹文本像不像标准答案"当主指标。
2. **人类基线是标配**：WebArena 78.24%、OSWorld 72.36%、GAIA 92%——锚定"差距还有多大"，也防分数虚读。
3. **私有集防刷榜**：GAIA 300/466 私有、MCP-Atlas 500/1000 私有、ITBench-AA 19 题 held-out（0917 调研）。我们 HOLDOUT 分区设计与此一致。
4. **任务远未饱和是常态**：这些基准发布时 SOTA 成功率普遍 <15%，其价值恰在于区分度——对应我们不应追求 golden 集"全绿"，而应保留爬坡型 capability 集 + 全绿回归集双轨（Anthropic 双轨制，见 0917 调研）。

### 4.2 过程/轨迹评估的做法谱系

- **确定性过程判定**：BFCL V3 state-based（查后端状态）、ToolSandbox Milestone DAG（里程碑+禁态）、CCF AIOps 2025 的证据点命中率（0917 调研）——这一支与我们"零 LLM judge 主判"纪律兼容。
- **LLM-as-judge 及其可靠性研究**：Zheng et al. 系统证明 GPT-4 judge 与人类 >80% 一致但存在 position/verbosity/self-enhancement 三类偏差，需缓解设计（交换顺序、长度归一等）。[arXiv:2306.05685](https://arxiv.org/abs/2306.05685) AgentEval 进一步要求**评估 criteria 本身通过鲁棒性验证**（能区分噪声对抗样本）。[arXiv:2405.02178](https://arxiv.org/abs/2405.02178)
- **对我们的约束**：judge rubric 只打辅助分，且上岗前必须做人机一致率校准与对抗样本区分度测试——这与 0917 差距分析 P3"维持拒绝 LLM judge 主判"一致，本调研未发现需要推翻该决定的一手证据。

### 4.3 多 Agent 特有维度的工作与指标

| 维度 | 代表工作 | 指标/做法 | 一手来源 |
|---|---|---|---|
| 失败模式分类 | MAST | 14 失败模式/3 大类（系统设计、agent 间错位、任务验证）；kappa=0.88；1,600+ 标注轨迹 | [arXiv:2503.13657](https://arxiv.org/abs/2503.13657) |
| 协作质量 | MultiAgentBench | milestone-based KPI，任务完成与协作质量分开计分 | [arXiv:2503.01935](https://arxiv.org/abs/2503.01935) |
| 协调协议拓扑 | MultiAgentBench | star/chain/tree/graph 四拓扑对比（graph 在研究场景最优） | 同上 |
| 通信开销/通信错误 | τ²-bench | dual-control 下把失败消融为推理 vs 通信/协调两类 | [arXiv:2506.07982](https://arxiv.org/abs/2506.07982) |
| 角色分工质量 | MAST | role-specification violation 为一等失败模式 | [arXiv:2503.13657](https://arxiv.org/abs/2503.13657) |
| 涌现错误 | MAST | inter-agent misalignment（对话重置、上下文丢失、未完成即交接） | 同上 |
| 验证/冲突消解 | MAST | 第三类 task-verification failure（过早终止、验证不完整） | 同上 |

### 4.4 Multi-agent vs Single-agent 的对比评测结论（一手证据）

1. **收益常常微乎其微**：MAST 论文摘要开篇即指出"MAS 在流行基准上的性能提升常常很小（often minimal）"，且失败多源于系统设计问题而非底层模型限制。[arXiv:2503.13657](https://arxiv.org/abs/2503.13657)
2. **通用多 agent 系统与 SOTA 仅统计持平**：Magentic-One 在 GAIA/AssistantBench/WebArena 上"statistically competitive"而非超越——其价值在模块化与鲁棒性。[arXiv:2411.04468](https://arxiv.org/abs/2411.04468)
3. **多调用/多投票非单调**：Vote/Filter-Vote 随调用次数先升后降——简单查询受益、困难查询受损。[arXiv:2403.02419](https://arxiv.org/abs/2403.02419)
4. **协作结构本身是可调变量且影响可测**：MultiAgentBench 证明拓扑与策略选择（graph 拓扑、cognitive planning +3% milestone）产生可区分的效果。[arXiv:2503.01935](https://arxiv.org/abs/2503.01935)

**对我们的含义**：本系统是单 Agent + 工具 + 双人审批的架构，上述证据支持"不急于多 agent 化"；若未来引入多 agent（如诊断/验证/报告分工），评测必须新增 MAST 式失败模式标注与通信开销指标，且按难度分桶看收益（防 §4.4.3 的非单调陷阱）。

---

## 五、参考来源清单（一手 URL）

**工具调用 / function calling：**
- BFCL 论文（ICML 2025）：https://proceedings.mlr.press/v267/patil25a.html
- BFCL 官方 leaderboard：https://gorilla.cs.berkeley.edu/leaderboard.html
- BFCL V3 multi-turn 官方博客：https://gorilla.cs.berkeley.edu/blogs/13_bfcl_v3_multi_turn.html
- ToolLLM/ToolBench：https://arxiv.org/abs/2307.16789
- API-Bank：https://arxiv.org/abs/2304.08244 ；EMNLP 2023：https://aclanthology.org/2023.emnlp-main.187.pdf
- Seal-Tools：https://arxiv.org/abs/2405.08355 ；PDF 全文：https://arxiv.org/pdf/2405.08355 ；代码：https://github.com/fairyshine/Seal-Tools
- ToolACE：https://arxiv.org/abs/2409.00920 ；ICLR 2025：https://proceedings.iclr.cc/paper_files/paper/2025/file/663865ea167425c6c562cb0b6bcf76c7-Paper-Conference.pdf
- ToolSandbox：https://arxiv.org/abs/2408.04682 ；代码：https://github.com/apple/ToolSandbox
- τ-bench：https://arxiv.org/abs/2406.12045
- τ²-bench：https://arxiv.org/abs/2506.07982

**MCP 生态：**
- MCPToolBench++：https://arxiv.org/abs/2508.07575
- MCP-Atlas：https://arxiv.org/abs/2602.00933
- MCPAgentBench：https://arxiv.org/abs/2512.24565
- MCP Tool Descriptions Are Smelly：https://arxiv.org/abs/2602.14878
- Anthropic《Writing effective tools for agents》：https://www.anthropic.com/engineering/writing-tools-for-agents
- Accenture mcp-bench（社区评测框架）：https://github.com/Accenture/mcp-bench

**单 Agent outcome 基准：**
- AgentBench：https://arxiv.org/abs/2308.03688 ；代码：https://github.com/THUDM/AgentBench
- WebArena：https://arxiv.org/abs/2307.13854
- SWE-bench：https://arxiv.org/abs/2310.06770
- GAIA：https://arxiv.org/abs/2311.12983
- OSWorld：https://arxiv.org/abs/2404.07972
- WorkArena：https://arxiv.org/abs/2403.07718

**judge / 过程评估：**
- Judging LLM-as-a-Judge（MT-Bench）：https://arxiv.org/abs/2306.05685
- AgentEval：https://arxiv.org/abs/2405.02178

**多 Agent：**
- MAST（Why Do Multi-Agent LLM Systems Fail?）：https://arxiv.org/abs/2503.13657
- MultiAgentBench：https://arxiv.org/abs/2503.01935
- Magentic-One：https://arxiv.org/abs/2411.04468
- Are More LLM Calls All You Need?：https://arxiv.org/abs/2403.02419
- AutoGen：https://arxiv.org/abs/2308.08155

**仓内前置沉淀：**
- `research/alert-agent-eval-20260917/国外与学术基准告警agent评测调研.md`（AIOpsLab/OpenRCA/ITBench/Bits AI/AgenticOpsEval/RCAEval 等 RCA 域基准）
- `research/alert-agent-eval-20260917/国内大厂告警agent评测调研.md`（RCA-Bench/CCF AIOps/美团/蚂蚁等）
- `research/alert-agent-eval-20260917/差距分析与落地建议.md`（项目评测链路现状摸底与既有建议）

---

## 诚实边界声明

- 所有基准的规模数字、指标公式、实验结果均引自上述一手页面的摘要/正文/官方说明；未逐篇核对论文全文表格的次要数字（如各模型具体得分）未写入本报告。
- Seal-Tools 的指标定义与错误分类比例（漏填 7%/臆填 9%）核对了 arXiv PDF 正文 §4；其余论文以摘要级内容为主，正文级引用处已注明小节。
- "MCP 官方自身的评测实践"：MCP 规范官方站点（modelcontextprotocol.io）未提供评测标准文档（本次调研未找到一手来源）；生态权威实践以 Anthropic 工程博客与上述学术基准为代表，已如实标注。
- BFCL 论文未见 arXiv 版本，一手出处为 PMLR/OpenReview 与官方博客，已标注。
