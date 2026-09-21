# 国外大厂与学术界「告警 / AIOps 根因分析 Agent」评测与数据集调研

调研日期：2026-09-17。所有结论均追溯至一手来源（官方论文 arXiv/会议页、官方 GitHub 仓库 README、厂商官方工程博客/文档），每条关键结论附 URL。

---

## 1. Microsoft — AIOpsLab（及后继 SREGym）

### (a) 评测集构建方式与规模
- AIOpsLab 不是静态数据集，而是**活的评测框架**：编排器在 K8s 上部署微服务应用（DeathStarBench 的 SocialNetwork、HotelReservation，及 OpenTelemetry Astronomy Shop 等），用 `wrk2` 生成负载，用分层故障注入器（应用层/虚拟化层/K8s 层）注入真实故障，导出 metrics/logs/traces 遥测，再让 Agent 在交互式环境中完成任务。[GitHub README](https://github.com/microsoft/AIOpsLab)、[MLSys 2025 论文 arXiv:2501.06706](https://arxiv.org/abs/2501.06706)
- 每个 problem 由 5 个组件声明式组成：Application、Task（Detection/Localization/Analysis/Mitigation 四类）、Fault、Workload、Evaluator，可自助扩展。[README「How to add new problems」](https://github.com/microsoft/AIOpsLab/?tab=readme-ov-file#how-to-onboard-your-agent-to-aiopslab)
- 规模：论文版约 88 个任务（[AgenticOpsEval 论文 Table 1](https://arxiv.org/html/2606.29193v1) 记为 88 cases）。
- 后继工作 **SREGym**（同一学术脉络，2026）：90 个高保真 SRE 问题，增加多层故障、环境噪声（ambient noise）、亚稳态故障（metastable failures）、关联故障等复杂失效模式，模块化编排 fault/noise injector。[arXiv:2605.07161](https://arxiv.org/abs/2605.07161)

### (b) 覆盖范围
- 故障类型：K8s 误配置（targetPort misconfig 等）、应用层故障、虚拟化层故障；四类 AIOps 任务（检测、定位、根因分析、缓解）。SREGym 进一步覆盖亚稳态/关联故障与噪声对抗。

### (c) 指标
- Evaluator 分**定量（quantitative）与定性（qualitative）**两类，仓库内置 LLM-as-a-Judge 提示词（`aiopslab/orchestrator/evaluators/prompts.py`）；评测输入为 soln（提交答案）、trace（动作轨迹）、duration（耗时）三元组，即同时考核**最终正确性 + 过程轨迹 + 效率**。[README 项目结构](https://github.com/microsoft/AIOpsLab)

### (d) 训练集与否
- **零训练**：面向 LLM Agent 的评测框架，只提供评测环境，不提供训练集；Agent 由用户自行接入（GPT/Qwen/DeepSeek/vLLM 本地模型客户端）。

---

## 2. Microsoft — OpenRCA（含 ops-lite 核查结果）

### (a) 评测集构建方式与规模
- 335 个真实软件故障案例，来自三个企业级系统（**Telecom / Bank / Market**，Market 分两个 cloudbed），遥测总量 68.5+ GB（logs、metrics、traces）。每例给出自然语言故障描述（query.csv）+ 遥测，要求输出 ⟨故障发生时间, 根因组件, 根因原因⟩ 三元组。ICLR 2025 Datasets & Benchmarks 收录。[官方站点](https://microsoft.github.io/OpenRCA/)、[GitHub](https://github.com/microsoft/OpenRCA)、[OpenReview](https://openreview.net/forum?id=M4qNIzQYpd)
- 官方提供 **RCA-agent** 基线：用 Python 代码检索/分析遥测，避免把超长上下文塞给 LLM。另有 `main/generate` 工具可从遥测 + record 规范**再生成新任务**，相当于"数据集生成器"。[GitHub README Reconstruction 节](https://github.com/microsoft/OpenRCA)
- OpenRCA 2.0（2026，对应 FSE 2026 论文「Rethinking the Evaluation of Microservice RCA with a Fault Propagation-Aware Benchmark」）：自动化 benchmark 生成框架，**从 9,152 次故障注入中验证筛选出 1,430 个失败案例**，覆盖 6 大类 25 种故障、动态负载、分层 ground truth（服务级→代码级）；每个案例要求对用户侧 SLI 有可感知影响（SLI-threshold 过滤）才被收录。复评 11 个 SOTA 模型，Top@1 平均仅 0.21、最高 0.37——证明旧 benchmark 过于简单、高估了 RCA 方法性能。[arXiv:2510.04711](https://arxiv.org/abs/2510.04711)、[OpenRCA 站点 2.0 说明](https://microsoft.github.io/OpenRCA/)

### (b) 覆盖范围
- 模态：KPI 时序 + 依赖 trace 图 + 半结构化日志；网络类故障被官方标注为"仅靠 KPI 无法定位，需看父子 span 时延关系"——强制跨模态推理。[GitHub FAQ](https://github.com/microsoft/OpenRCA)

### (c) 指标
- 根因识别**准确率（三元组匹配）**；官方排行榜要求提交方自测 accuracy 并可附执行轨迹。[官方站点提交指南](https://microsoft.github.io/OpenRCA/)

### (d) 训练集与否
- **零训练**（评测-only）。官方 FAQ 明确审查自定义 agent 不得读取本地 records.csv 的 ground truth，防数据泄漏。

### ⚠ ops-lite 核查结果（针对任务书中"ops-lite 500 例"的说法）
- 公开渠道**未能找到**名为 "ops-lite" 的独立公开数据集/仓库（GitHub 全站检索、Google 检索均无匹配；本机 E:\kimiCode 代码库全文检索亦无）。
- 唯一一手出处：OpenRCA 官方排行榜页面提到「the latest ops-lite results」及脚注「Standard harness: using **DeepResearch** for OpenRCA 2.0 ops-lite」——即 **ops-lite 是 OpenRCA 2.0 的一个轻量子集/标准评测 harness 名称**，不是独立的 500 例数据集。[OpenRCA 站点 News 区](https://microsoft.github.io/OpenRCA/)
- 任务书中所述「500 例、manifest 驱动因果图 ground truth、12 个 Parquet 指标表、Train-Ticket/HotelReservation/OTel Demo 三测试床」等细节**无法从任何一手公开来源验证**，建议以"内部/未公开材料，细节待核实"对待，勿对外引用。

---

## 3. Microsoft — RCACopilot / AlertGuardian（模型类方法，含 XGBoost 对照）

- **RCACopilot**（EMNLP 2023 体系，arXiv:2305.15778）：微软内部上线 4 年+ 的 on-call RCA 系统。三阶段：按告警类型匹配 incident handler → 聚合诊断信息 → LLM（GPT-4 少样本）预测根因类别 + 生成解释。**评测对照组包含训练型模型：GPT-3.5 微调、XGBoost、FastText**（在微软内部真实工单历史上训练），指标为 F1 + 工程师人工评定的有用性；GPT-4 few-shot 优于训练型基线。[arXiv:2305.15778](https://arxiv.org/abs/2305.15778)、[综述中的对照描述 arXiv:2412.19823](https://arxiv.org/html/2412.19823v1)
- **AlertGuardian**（ASE 2025，"Company-X"大型云厂商）：告警全生命周期管理（告警风暴降噪+关联），4 个真实生产数据集，告警压缩率 93.82%–95.50%，故障诊断加速 90.5%。[arXiv:2601.14912](https://arxiv.org/html/2601.14912v1)

---

## 4. Google — SRE 告警实践（无公开 RCA 评测集）

- Google 未发布 RCA Agent 评测集。其对"评测"的贡献是**方法论层面**的告警设计原则，决定了评测集 ground truth 该怎么打：
  - **Alert on symptoms, not causes**：page 类告警应对准用户可感知症状，根因启发式留在白板/仪表盘中——对应评测集应以"用户侧 SLI 受影响"为收录门槛（OpenRCA 2.0 的 SLI 过滤正是此原则的工程化）。[SRE Book 第 6 章](https://sre.google/sre-book/monitoring-distributed-systems/)
  - 基于 SLO 的告警与燃烧率（burn rate）告警：给"何时该告警"提供了可计算的真值锚点。[SRE Workbook: Alerting on SLOs](https://sre.google/workbook/alerting-on-slos/)

---

## 5. IBM — ITBench（含 ITBench-AA）

### (a) 构建方式与规模
- ITBench（ICML 2025）：面向真实 IT 自动化任务的 Agent 基准框架，首发覆盖 **SRE / CISO / FinOps** 三个领域；SRE 场景在真实 K8s 环境注入事件，ground truth 含**故障传播链 + 修复步骤**（是同类中唯一部分标注推理过程的，AgenticOpsEval 论文 Table 1 记为 94 scenarios，partial reasoning label）。[arXiv:2502.05352](https://arxiv.org/abs/2502.05352)、[PMLR v267](https://proceedings.mlr.press/v267/jha25a.html)、[AgenticOpsEval Table 1](https://arxiv.org/html/2606.29193v1)
- **ITBench-AA**（Artificial Analysis × IBM，2026-05）：基于 ITBench 的独立实现，59 个 SRE 任务（40 公开 + 19 held-out 防过拟合），每任务提供离线事件快照（alerts/events/traces/metrics/logs/拓扑），要求识别根因实体（deployment/pod/namespace/network policy 等）。[Artificial Analysis](https://artificialanalysis.ai/evaluations/itbench-aa)

### (c) 指标
- 论文指标：**pass@1**（诊断/缓解成功率）+ **NTAM（Normalized Topology-Aware Match）**——拓扑感知的故障传播链匹配指标；ITBench-AA 实现版报告 **Average F1、Precision at Full Recall、Pass Rate、Average Turns**。当前最强前沿模型平均分不足 50%（Claude Opus 4.7 ≈ 46.7%），说明任务远未饱和。[arXiv:2502.05352](https://arxiv.org/abs/2502.05352)、[BenchmarkList ITBench-AA](https://benchmarklist.com/benchmarks/itbench_aa/)

### (d) 训练集与否
- **零训练**评测环境；19 个 held-out 任务用于防刷榜。

---

## 6. Datadog — Bits AI SRE（生产回流型持续评估，业界最完整公开工程实践）

官方工程博客（2026-04）详述了其自治 SRE Agent 的可回放评测平台，是"线上流量回流为评测用例"的一手范本：

### (a) 评测集构建方式与规模
- 评测单元称为 **label = ground truth 根因 + world-snapshot**（事发时刻可用信号的快照——保存遥测查询与信号结构而非原始数据，因遥测有 TTL 过期问题）。[Datadog 工程博客](https://www.datadoghq.com/blog/engineering/bits-ai-eval-platform/)
- 三步演进：**人工标注 → 产品内嵌标注**（用户对 Bits 调查结果的每次反馈自动生成候选 label，标注产能提升一个数量级）→ **Agent 辅助校验**（Bits 自己基于客户反馈补全因果链，人类只做确认/修订，单 label 校验时间一周下降 95%+）。
- **噪声注入（"Bring the noise"）**：快照必须包含与根因无关的背景服务/无关错误等红鲱鱼信号；收窄快照曾使评测通过率虚高，扩大噪声范围后通过率下降约 11%、废弃 35% 旧 label——但换来评测结果对生产行为的**预测性**。
- 每个生成 label 打置信分（thoroughness/specificity/accuracy 多维），低于阈值转人工；并与人工裁判做对齐研究（alignment studies）。

### (c) 指标
- 不只看最终结论，还评**轨迹**（离正确答案多近、调查是否够深、是否浮出了有价值遥测）；**pass@k**；按技术栈/问题类型/monitor 类型/难度分桶；每周跑全量集抓回归，偏差超阈值自动告警。

### (d) 训练集与否
- **零训练**（LLM Agent + 评测回流飞轮）。另有面向客户的 Bits Evals 产品，用生产信号生成 evaluator。[Bits Evals 博客](https://www.datadoghq.com/blog/bits-evals/)

---

## 7. AWS 与 Meta

### AWS
- Amazon DevOps Guru：ML 驱动的异常检测 + 根因分析托管服务（RDS 版结合 DB load 分析），**未公开任何评测集或评测方法**，仅有产品文档与 re:Invent 材料。[re:Invent DAT335 PDF](https://d1.awsstatic.com/events/reinvent/2021/New_Launch_Automatically_detect_and_resolve_issues_with_Amazon_DevOps_Guru_for_RDS_DAT335.pdf)

### Meta — DrP
- DrP（2025-12 官方博客）：Meta 规模化 RCA 平台，**playbook-as-code**（工程师把调查流程写成 analyzer，SDK 内置异常检测/时序相关/维度下钻等 ML 算法），300+ 团队、2000+ analyzer、每日 5 万次分析，MTTR 降 20–80%。
- 评测做法：**不是评测集，而是回测（backtesting）**——analyzer 上线前在 code review 工具里跑自动化回测保证质量；DrP Insights 周期性分析输出以排名告警主因。属"确定性 pipeline + 回测"路线，与 LLM Agent 评测集路线形成对照。[Meta 工程博客](https://engineering.fb.com/2025/12/19/data-infrastructure/drp-metas-root-cause-analysis-platform-at-scale/)

---

## 8. 学术基准 — AgenticOpsEval（AIOps2025 + RCA100）

### (a) 构建方式与规模
- 两个数据集共 **503 个专家标注失败案例、≈15.3 GB 多模态可观测数据**，经两场 2025 年全国级公开赛验证（CCF AIOps 挑战赛 561 队 / 阿里天池 5,532 队，合计 6,093 队）。[arXiv:2606.29193](https://arxiv.org/html/2606.29193v1)、[官方 GitLab](https://www.aiops.cn/gitlab/aiops-live-benchmark/agenticopseval)
- **AIOps2025**（400 例）：自建 HipsterShop（10 微服务×3 pod）+ TiDB + Redis 三层系统，8 台 VM 上 K8s 编排；Chaos-Mesh 在 service/pod/node 三层注入 9 大类 18 种故障；输入是开放式自然语言异常描述。标注管线为**算法初筛 + 三名 SRE 专家独立确认 + 资深专家仲裁**，且每种故障场景**重复注入多次**确认可观测后才收录（"标签反映专家应看到什么，而非注入命令做了什么"）。数据为 2,835 个 Parquet 文件、约 2.69 亿行、11.9 GB、18 个自然日，按阶段/日/模态分区、小时切片。
- **RCA100**（103 例）：阿里云 ACK 上的 OpenTelemetry Demo Store（+ 托管 RDS/Redis/MQ），OTel + ARMS Agent 混合采集，UModel 统一实体 ID；六模态（Metrics/Logs/Traces/**Events/Alerts/Topology**），≈3.4 GB；ground truth 为**四层因果链**：故障类别 + 根因实体 + 因果传播链 + 661 个证据检查点。

### (b) 覆盖范围
- 分层故障（service/pod/node）× 五族故障（资源/网络/运行时 JVM/中间件数据库/应用逻辑）；62.5% 案例需 ≥2 模态、31% 需全部三模态才能定位——在数据层强制跨模态融合。

### (c) 指标
- 确定性评分协议，三支柱加权 0-100 分：**Localization Accuracy**（0.4，组件名严格匹配，网络故障放宽到源/目的端任一）+ **Type Accuracy**（0.4，关键词命中 + embedding 相似度兜底，reason 字段截前 20 词防关键词堆砌）+ **Explainability**（0.1，key-metric/key-observation 证据点覆盖率，每条观测仅计前 20 字符）+ **Efficiency**（0.1，对 LA 正确的案例惩罚过长推理轨迹，防"答错但轨迹短"刷分）。

### (d) 训练集与否
- 面向零训练 LLM Agent 评测，但官方明确定位其细粒度因果证据标注可"support agent learning"（可作训练/RL 监督信号）。

---

## 9. 学术基准 — RCAEval / RCA-Bench 复用情况

### (a) 构建方式与规模
- RCAEval（ASE 2024 首发，WWW 2025 扩展；RMIT 等）：**9 个数据集、735 个真实失败案例**，三套 benchmark：RE1（375 例，纯指标）、RE2（270 例，metrics+logs+traces 多源）、RE3（90 例，代码级故障 f1–f5）；三个微服务系统 **Online Boutique / Sock Shop / Train Ticket**，11 种故障类型（cpu/mem/disk/delay/loss/socket + 代码级）；每例标注根因服务 + 根因指标/日志指示器；每故障-服务对重复注入 3–5 次。托管于 Hugging Face（Parquet，含 `cases.parquet` 索引）/Figshare/Zenodo。[GitHub README](https://github.com/phamquiluan/RCAEval)、[arXiv:2412.17015](https://arxiv.org/abs/2412.17015)

### (c) 指标
- **AC@k / Avg@k**（Top-k 根因命中率，源自 MicroCause 一脉的惯例指标）；2026-09 新增 **Chance@5 / Lift@5** 随机基线对照（把绝对分与随机排序器的地板分对比，防分数虚读）；同时报告执行速度。15 个可复现基线（BARO/RCD/CIRCA/MicroCause/CausalRCA/RUN 等）且接入 CI 复现论文数字。

### (d) 训练集与否
- 基线多为**逐案例的无监督因果发现/变点检测**（BARO、RCD、CIRCA 等），**不需要训练集**；少数学习方法（RUN、MSCRED）按案例内时间窗切分训练/正常段。RCAEval 是被复用最多的底座：AgenticOpsEval、OpenRCA 2.0（arXiv:2510.04711 的"过简 benchmark"批评对象之一）均以其为对照。

---

## 10. 学术 — DyAlert（按时间划分训练/测试集的做法）

- DyAlert（ASE 2023，复旦）：动态图神经网络做**告警关联预测**（判断多条告警是否由同一故障触发）。构建 AMDG（Alert-Metric Dynamic Graph）离散时间快照图，BERT 告警语义嵌入 + 异构 k-GNN（空间）+ GRU（时间）。[GitHub README](https://github.com/FudanSELab/DyAlert)、[论文 PDF](https://zhendong2050.github.io/res/ASE23.pdf)
- **训练/测试划分惯例的一手证据**：仓库中 `sliding_dataset.py` 明确"used for partitioning the dataset **based on the occurrence time of alerts**"——即按告警发生时间做滑动窗口切分（前段训练、后段测试），而非随机划分，避免时序泄漏。数据来自商业企业保密数据（未公开，仅提供随机生成器）。[GitHub README](https://github.com/FudanSELab/DyAlert)
- 这是**训练型（GNN）方法**：有显式 train/test，划分维度 = 时间。

---

## 11. LLM-as-a-Judge 与持续评估（Continuous Eval）官方方法论

### Anthropic
- 「Demystifying evals for AI agents」（2026-01）：**评分器三类组合**——code-based（字符串匹配/静态分析/结果态校验/工具调用校验/transcript 分析：快、便宜、可复现但脆）、model-based（rubric 打分/成对比较/参考对照/多 judge 合议：灵活但需与人校准）、human（SME 评审/抽样/A-B：金标准，用于校准模型评分器）；每个 task 可挂多个 grader，加权/二值/混合聚合。
- **capability eval（爬坡，低通过率起步）vs regression eval（≈100% 通过率的回归集）**双轨制；饱和的 capability 集"毕业"为持续运行的回归集。
- 非确定性用 **pass@k / pass^k** 刻画（前者测"至少一次成功"，后者测"每次都可靠"）；强调评 outcome（环境终态）而非只看 transcript 文本。[Anthropic 工程博客](https://www.anthropic.com/engineering/demystifying-evals-for-ai-agents)

### LangChain / LangSmith
- **人机对齐校准工作流（Align Evals）**：收集人类对 LLM judge 打分的纠错 → 把纠错样例做成 few-shot 注入 judge prompt → 持续跟踪 judge 与人专家的一致率；强 LLM judge 与人类约 80% 一致（≈人际一致水平，引 MT-Bench）。[LangChain 官方资源页](https://www.langchain.com/resources/llm-as-a-judge)
- **线上流量回流飞轮**：production traces → Insights 提炼模式 → 一键转入 dataset 成为回归用例 → 评估驱动改进；online evals 支持采样率/过滤规则控制成本；rubric 设计原则：**二值或低精度评分比细粒度数值更可靠**。[同上](https://www.langchain.com/resources/llm-as-a-judge)、[LangSmith Evaluation 产品页](https://www.langchain.com/langsmith/evaluation)
- **Cohen's Kappa 人机对齐**：学术界的标准做法示例——计算 GPT-4o 评审与人类评分的 Cohen's Kappa 系数以校准 judge（[arXiv:2601.12369 Table 5](https://www.arxiv.org/pdf/2601.12369)）；及三人标注多数投票构造人类参考标签后评估 judge 一致率（[OpenReview Gemma judge 评估](https://openreview.net/pdf?id=YWMnpol8i1)）。

### OpenAI / Braintrust
- OpenAI 官方 evals 指南：dataset/run/grader 为版本化组件，支持 grader 类型组合（代码/执行检查 + 模型评分 + rubric）。[platform.openai.com/docs/guides/evals](https://platform.openai.com/docs/guides/evals)
- Braintrust：**online scoring** 在生产 trace 落库时异步自动跑 scorer（heuristic 代码 + LLM-as-a-judge 混合），生产数据"无缝变成评估数据集"，按 trace 分类（Topics）触发不同 scorer 实现持续评估。[Braintrust docs: score-online](https://www.braintrust.dev/docs/evaluate/score-online)、[Braintrust 持续评估文章](https://www.braintrust.dev/articles/continuous-evaluation-ai-agents-trace-classifications-2026)

---

## 12. 训练集问题：哪些需要训练集，划分惯例是什么

- **零训练 LLM Agent 路线（只需评测集）**：AIOpsLab/SREGym、OpenRCA（RCA-agent）、ITBench、AgenticOpsEval、Datadog Bits AI。评测集即全部数据资产；防泄漏靠 held-out 任务（ITBench-AA 19 题）或官方审查（OpenRCA FAQ 禁读 records.csv）。
- **训练/微调路线（需要训练集）**：
  - RCACopilot 的对照实验证明：在内部历史工单上**微调 GPT-3.5 / 训练 XGBoost、FastText** 不如 GPT-4 few-shot——这是"LLM 免训练替代训练型分类器"的关键一手证据。[arXiv:2305.15778](https://arxiv.org/abs/2305.15778)
  - DyAlert（GNN）：**按告警发生时间做滑动窗口划分** train/test。[GitHub](https://github.com/FudanSELab/DyAlert)
  - 惯例总结（来自上述一手来源）：模型类 RCA/告警方法**首选按时间划分**（前段训练、后段测试，防时序泄漏）；跨系统泛化研究（如 RCAEval 的 RUN 类方法）则**按系统/集群划分**（在 A 系统训练、B 系统测试）；同一系统内逐案例的方法则**案例内切正常段/异常段**。
- **中间路线**：AgenticOpsEval 的细粒度证据标注（661 个证据检查点、因果链）官方定位为既可评测、也可作为 agent 学习/RL 的监督信号——"评测集反哺训练"是 2026 年的新趋势。[arXiv:2606.29193](https://arxiv.org/html/2606.29193v1)

---

## 13. 横向对比表

| 对象 | 规模 | 构建方式 | 故障/模态覆盖 | 核心指标 | 训练集? |
|---|---|---|---|---|---|
| Microsoft AIOpsLab | ~88 任务 | 活环境：K8s 部署+负载+分层故障注入 | 误配置/应用/虚拟化故障；M+L+T；4 类任务 | 定量+定性+LLM-judge；soln/trace/duration | 否 |
| SREGym | 90 问题 | 同上+噪声注入器 | 亚稳态/关联故障+环境噪声 | 端到端通过率 | 否 |
| Microsoft OpenRCA | 335 例 / 68.5GB | 三企业系统真实故障+遥测打包；可再生成 | 网络故障强制跨模态；M+L+T | ⟨时间,组件,原因⟩三元组准确率 | 否 |
| OpenRCA 2.0（FSE'26） | 1,430 例（9,152 注入筛出） | 自动化 benchmark 生成+SLI 影响过滤 | 6 类 25 故障，分层 GT 到代码级 | Top@k（SOTA 均值仅 0.21） | 否 |
| IBM ITBench / ITBench-AA | 94 场景 / 59 任务(19 held-out) | K8s 真实事件注入+离线快照 | 含故障传播链 GT（部分） | pass@1、NTAM；AA 版 F1/P@FR/PassRate/Turns | 否 |
| Datadog Bits AI | 未公开（周级全量回归） | 生产反馈回流+agent 辅助标注+噪声快照 | 全生产栈故障类型，刻意含红鲱鱼 | 轨迹级评分、pass@k、分桶、回归告警 | 否 |
| Meta DrP | 2000+ analyzer | playbook 回测（非评测集） | 告警触发自动分析 | MTTR 降 20–80%（业务指标） | 否（回测代替） |
| AgenticOpsEval (AIOps2025+RCA100) | 503 例 / 15.3GB | Chaos 注入+多专家三段标注+大赛验证 | 分层×五族故障；3–6 模态；过程标注 | LA 0.4+TA 0.4+Exp 0.1+Eff 0.1 | 否（可作训练信号） |
| RCAEval | 735 例 / 9 数据集 | 3 系统重复注入+逐例 GT | 11 故障类型；RE1 纯指标/RE2-3 多源 | AC@k/Avg@k + Chance@k 对照 + 速度 | 大多否（逐案例无监督） |
| DyAlert (ASE'23) | 企业保密数据 | AMDG 动态图快照 | 告警+指标 | 链接预测指标 | **是：按时间滑窗划分** |
| RCACopilot (MS) | 内部工单 | 历史事件+handler 遥测 | 多类云事件 | F1 + 人工有用性 | **是：微调/XGBoost 对照** |

**调研存疑点**：任务书中的 "ops-lite 500 例独立评测集" 未能在任何一手公开来源验证；公开记录中 ops-lite 仅作为 OpenRCA 2.0 官方排行榜的轻量评测 harness/子集名称出现（DeepResearch 标准脚手架）。其"manifest 因果图 GT / 12 Parquet 指标表 / 三测试床"细节应视为待核实的内部描述。
