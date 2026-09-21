# 国内大厂告警 / AIOps 根因分析 Agent 评测调研

> 调研日期：2026-09-17。原则：尽量引用一手来源（官方论文、官方技术博客、官方 GitHub/数据集说明）；个别只有二手报道的结论已显式标注。每个对象按四个问题组织：(a) 评测集/数据集构建方式；(b) 覆盖面；(c) 测试指标；(d) 训练集概念。

---

## 1. 阿里云：RCA-Bench / RCA-100（重点）

阿里云 2026 年发布 RCA Benchmark，自称"业界首个面向 Agentic Ops 的根因分析开源基准体系"，由运行环境、结构化样本集、评估协议三模块组成；联合信通院、中科院软件所/计算机网络信息中心、清华、复旦、南开共建。([阿里云官方博客](https://www.alibabacloud.com/blog/alibaba-cloud-releases-rca-benchmark-the-industrys-first-open-source-root-cause-analysis-benchmark-system-for-agentic-ops_603252))

### (a) 构建方式：故障注入为主，仿真环境产出真实故障信号
- **底座**：部署在 K8s 集群上的电商微服务仿真系统，40+ 业务服务、调用链最深 7 层，覆盖同步 RPC、异步消息、数据库、缓存、消息队列、网关；**不使用合成数据**，通过持续注入差异化背景流量（昼夜波动、业务高峰、定时批处理）建立故障前后对照基线。([官方博客](https://www.alibabacloud.com/blog/alibaba-cloud-releases-rca-benchmark-the-industrys-first-open-source-root-cause-analysis-benchmark-system-for-agentic-ops_603252))
- **故障注入**：四条注入通道——混沌工程工具、K8s 原生运维操作、开关配置、阿里云服务 API。([官方博客](https://www.alibabacloud.com/blog/alibaba-cloud-releases-rca-benchmark-the-industrys-first-open-source-root-cause-analysis-benchmark-system-for-agentic-ops_603252))
- **规模**：当前实例化数据集 **RCA-100**：103 条故障用例、6 大类故障目录、28 类独立故障类型；可观测原始数据 3.46 GB、1.136 亿条观测记录。另累计 200+ 合规样本，分 L1–L4 四级难度，L2/L3 为核心。([RCA-Bench 官方文档](https://sls.aliyun.com/doc/starops/benchmark/rca/rca_benchmark_dataset.html))
- **基线真值**：放弃单一根因标签，采用**四层结构化真值**（故障类型、归一化根因实体、因果传播链、关键证据点）；配套 GSTO 四层质量门（结构规范、信号有效性、时间窗口、开放适应性）过滤"故障链混淆"的无效样本。([官方博客](https://www.alibabacloud.com/blog/alibaba-cloud-releases-rca-benchmark-the-industrys-first-open-source-root-cause-analysis-benchmark-system-for-agentic-ops_603252))
- **防污染**：保留非公开测试样本与合规门，防止数据污染、保证榜单公平。([官方博客](https://www.alibabacloud.com/blog/alibaba-cloud-releases-rca-benchmark-the-industrys-first-open-source-root-cause-analysis-benchmark-system-for-agentic-ops_603252))

### (b) 覆盖面
- 故障域：应用层、中间件层、容器平台层、云资源层 6 大类 40+ 故障类型；公开的 40 例对照集分布为节点/基础设施 4 例、资源耗尽 12 例、流量/调度 12 例、数据库/缓存 6 例、代码/应用 5 例、网络 1 例。([评测案例对照](https://sls.aliyun.com/doc/starops/benchmark/rca/cases_compare.html))
- 模态：7 类观测数据——指标、日志、Trace、告警、资源拓扑、K8s 事件、性能 profiling，通过 UModel observability MCP server 供 Agent 在线查询（强调"主动观测"，与 OpenRCA 的静态样本形态形成差异）。([RCA-Bench 官方文档](https://sls.aliyun.com/doc/starops/benchmark/rca/rca_benchmark_dataset.html))
- **UModel 统一实体模型**：21 实体类型、4 关系类型，单用例中位 227 实体、258 边，解决 APM/K8s/ECS 跨域实体标识割裂问题。([RCA-Bench 官方文档](https://sls.aliyun.com/doc/starops/benchmark/rca/rca_benchmark_dataset.html))

### (c) 测试指标：图距离为核心的确定性评分
- 综合分 `O = 0.40·E + 0.30·F + 0.30·P`：**E 定界**（根因实体匹配，基于 UModel 实体拓扑图距离）、**F 定因**（故障类型匹配，基于故障类型语义图距离）、**P 过程**（推理过程评估，LLM 仅辅助判定调查方向与证据充分性）。约 70%（官方技术文章中口径为 ~82%）分数由确定性规则计算，LLM 评判只占辅助地位，保证跨厂商、跨评分器可复现。([评测案例对照](https://sls.aliyun.com/doc/starops/benchmark/rca/cases_compare.html)、[STAROps 技术文章](https://www.cnblogs.com/alisystemsoftware/p/22574458))
- 已公开横评结果：同一 40 例注入故障上 STAROps RCA 平均 72.1 vs ReAct×PaaS 31.9 vs OpenClaw×PaaS 32.9；30 例分层评测集上调优配置（deepseek-v4-pro）综合分 62.10 vs 默认开源配置 48.74。([评测案例对照](https://sls.aliyun.com/doc/starops/benchmark/rca/cases_compare.html)、[RCA-Bench 官方文档](https://sls.aliyun.com/doc/starops/benchmark/rca/rca_benchmark_dataset.html))

### (d) 训练集概念
纯评测基准，评测对象是**零训练的 LLM Agent**；不存在训练/测试划分。迭代靠线上 Bad Case 回流为回归样本（STAROps 把低分任务归因到对象识别/数据获取/调查方向/证据质量/结论五类再回归验证）。([STAROps 技术文章](https://www.cnblogs.com/alisystemsoftware/p/22574458))

### 补充：CloudRCA（CIKM 2021，阿里巴巴集团）
早期的监督式 RCA 框架（KPI+日志+拓扑，KHBN 贝叶斯网络），用阿里三个云平台的真实事故数据做实验，对比 baseline 根因定位效果；属于"训练模型"路线，与 RCA-Bench 的 Agent 评测定位不同。([AMiner 论文页](https://www.aminer.cn/pub/61850e9691e01121084ca0e9))

---

## 2. 腾讯（蓝鲸 / WeAIOps 等）

**结论：未找到腾讯公开的告警 RCA 评测集或标准化评测协议。** 可查的一手材料：

### (a)(b) 实践与验证方式
- **MicroDig**（南开大学 + 腾讯视频业务）：异构图微服务性能问题诊断，在**腾讯视频真实生产系统**（8000+ 微服务、日 10 亿+ 请求）上验证，评测基于真实 SLO 性能问题案例而非公开基准。([论文 PDF（南开 iops 站点）](https://nkcs.iops.ai/wp-content/uploads/2024/12/Diagnosing_Performance_Issues_for_Large-Scale_Microservice_Systems_With_Heterogeneous_Graph.pdf))
- 蓝鲸体系（嘉为蓝鲸）公开的是告警治理、智能根因定位的**场景实践文章**，无评测集规模、划分方式、指标口径等细节。([腾讯云开发者社区·嘉为蓝鲸](https://cloud.tencent.com/developer/article/2436877))
- 常被误认为腾讯成果的 DiagFusion 实为南开大学主导（合作方为微软、蚂蚁旗下网商银行、清华），见第 6 节。

### (c) 指标
未找到公开的量化评测口径。

### (d) 训练集
MicroDig 属传统图方法；未见腾讯公开 LLM RCA Agent 训练/评测数据。

---

## 3. 华为（NAIE / iMaster / 盘古）

**结论：华为公开的重心在电信网络告警压缩与故障定位数据集及现网指标，未找到面向 LLM Agent 的 RCA 评测基准。**

### (a) 构建方式：真实现网数据 + 工单标注
- 2020 年发布**网络 AI 十大公开数据集**：告警基于无线基站、动环、PTN/微波设备拓扑关联，**用工单数据完成根因告警标注**；如"无线&PTN 故障数据集"覆盖 48 种故障类型、100w+ 样本、61 维特征；"无线&微波"覆盖 29 种故障类型、100w+ 样本、46 维特征。([发布报道（二手）](https://www.smartone-ai.com/news/detail/5361))
- iMaster NAIE：自动挖掘故障传播图，多维汇聚（时间、拓扑、业务）降噪，运营商现网案例。([华为 HC2020 官方材料 PDF](https://telcloud-public.obs.cn-north-1.myhuaweicloud.com/运营材料/HC2020/网络AIOps使能新基建运维智能化转型.pdf))

### (b) 覆盖面
电信网络域为主：无线接入、动环、PTN、微波、PON 光路等；模态以告警 + 工单 + KPI 为主，任务为告警压缩与根因告警识别（非 metrics/logs/traces 多模态微服务 RCA）。

### (c) 指标
- 官方口径：**压缩比**（根源告警数/原始告警数）、**精确率**、**召回率**（根源告警口径）；现网案例值：根因识别准确率 85%+、无效上站减少 60%、运维效率提升 15%。([华为 HC2020 官方材料](https://telcloud-public.obs.cn-north-1.myhuaweicloud.com/运营材料/HC2020/网络AIOps使能新基建运维智能化转型.pdf)、[华为运营商官网 AI in Network 报告](https://carrier.huawei.com/~/media/cnbgv2/download/products/wireless-network/ai-in-network.pdf))
- 研究侧（华为专利）：MMHC 因果网络 + GAT 注意力网络做告警根因识别，与现存算法对比准确率。([专利 CN112217674A](https://patentimages.storage.googleapis.com/e1/e8/ff/37d4953c8094fa/CN112217674A.pdf))

### (d) 训练集
传统监督学习/GNN 路线（工单标注数据即训练集）；未见 LLM Agent 零训练评测实践。

---

## 4. 字节跳动（ByteBrain / 火山引擎）

**结论：ByteBrain 官方确认"根因分析"为其 AIOps 功能模块之一，但未找到字节公开的告警 RCA 评测集或根因定位基准。**

- ByteBrain 定位：AI for Infra 平台，方向为 AIOps、AI4DB、运筹优化、LLM4Infra，功能模块含异常检测、**根因分析**、慢 SQL 优化等。([火山引擎官方开发者社区](https://developer.volcengine.com/articles/7521389510563725375))
- 公开的评测实践集中在相邻任务：
  - **ByteBrain-LogParser**（日志解析云服务）：在多个公开日志数据集上评估解析准确率与吞吐（22 万条/秒），属日志模板解析评测而非 RCA。([arXiv 2504.09113](https://arxiv.org/abs/2504.09113))
  - **ByteRobust**（万卡 LLM 训练基础设施）：核心是故障诊断与容错，以 ETTR（有效训练时间比率）等业务指标衡量收益，非标准化 RCA 评测集。([arXiv 2509.16293](https://arxiv.org/abs/2509.16293))
  - **ChatTS**（ByteBrain×清华，VLDB25）：时序多模态大模型，构建"属性驱动的合成时序-文本对齐数据 + 进化式 QA 生成"解决训练数据稀缺，是"造训练集"路线的代表。([博客园转载官方分享（二手）](https://www.cnblogs.com/wanning-1209/articles/18964145.html))

(a)–(d) 总结：告警 RCA 评测集**查不到公开一手资料**；已公开的做法偏"合成数据训练单点模型 + 业务指标验收"。

---

## 5. 美团（图灵 Agent 评测体系 / Horae AIOps）

### (a) 构建方式：从生产 Bad Case / Good Case 沉淀评测集
- 冷启动：人工生产少量种子评测集 + AI 辅助生成/扩写；之后靠线上抽样、Bad Case（暴露能力边界，价值高于 Good Case）、Good Case（定义"好"的范式）转成标准评测样本，形成"采集→清洗→评测→质检→分析归因"五环数据飞轮。案例：履约数字站长从 20 多个指标起步，一年扩展到近 200 个。([美团技术团队·Agent评测漫谈](https://tech.meituan.com/2026/08/07/Agent-Evaluation.html))

### (b) 覆盖面
- 评测对象 = "模型 + 系统 + 工具 + 流程"整体；覆盖**结果层、过程层（规划/步骤稳定性）、效率层（耗时/Token/工具调用次数）、风险层（越权/误操作）**四层。([Agent评测漫谈](https://tech.meituan.com/2026/08/07/Agent-Evaluation.html))

### (c) 指标：桥梁指标 + Rubric 二元化 + 人机一致率
- **桥梁指标**：业务结果指标与模型能力指标之间必须有面向任务系统的桥梁指标层（例：AI 搜索的业务层 DAU/留存、系统层召回率/点击率、Agent 层意图识别准确率）。([Agent评测漫谈](https://tech.meituan.com/2026/08/07/Agent-Evaluation.html))
- **主观评测对齐**：模糊指标下钻为 Rubric 并尽量二元化（是/否/未知），以 unknown 占比反查 Rubric 合理性；目标人人一致率/人机一致率达 85–90%+；案例：Beam 人机一致率 62%→92%，数字站长 99%。([Agent评测漫谈](https://tech.meituan.com/2026/08/07/Agent-Evaluation.html))
- AIOps 事件管理侧：根因匹配度、告警匹配度等事件级指标。([美团技术团队·AIOps事件管理篇](https://tech.meituan.com/2023/12/22/AIOps-Based-Incident-Management.html))

### (d) 训练集
以零训练 LLM Agent + 评测驱动迭代为主；明确"垂域语料匮乏时引入行业专家知识补足模型能力"（类比字节 Xpert 众包专家标注平台）。([Agent评测漫谈](https://tech.meituan.com/2026/08/07/Agent-Evaluation.html))

---

## 6. 蚂蚁 / 京东 / 百度

### 蚂蚁集团（含网商银行）
- **DiagFusion**（WWW 2023，南开主导，蚂蚁旗下网商银行专家参与）：(a) 两个数据集——D1 来自仿真环境故障注入（10 微服务 + MySQL/Redis + 5 主机，基于 GAIA 数据构造思路），D2 来自**顶级商业银行管理系统真实故障**（保密不公开）；真值由运维人员事后复盘标注（根因实例 + 故障类型二元组）；**按故障发生时间先后划分训练/测试集**防数据泄漏；训练 160/80 案例并用数据增强平衡故障类型。(b) 覆盖高内存、错误释放、代码 bug、误配置、网络中断等真实故障类型；模态为 trace+metric+log 多模态。(c) 指标：根因实例定位 **AC@k 与 Avg@5**（0.75/0.76），故障类型判定 **F1**（0.84/0.80），相对基线提升 20.9%–368%/11.0%–169%。(d) **监督训练 GNN**，是"训练集"路线的典型。([arXiv 2302.10512](https://arxiv.org/abs/2302.10512))
- AntMonitor（内部可观测平台）与 HoloInsight（开源）公开的是平台能力，无 RCA 评测集细节。([SOFAStack 官方博客](https://www.sofastack.tech/blog/ant-intelligent-monitoring/))

### 京东
- 京东科技公开实践：多维指标明细根因下钻（按省份/运营商/机房/机柜/主机维度）、蒙特卡洛树根因定位、告警共性聚类推荐根因等；无公开评测集与指标口径细节。([dbaplus 社群·京东科技全链路故障诊断实践（二手）](https://dbaplus.cn/news-134-5262-1.html)、[京东科技 AIOps 实践 PPT](https://aidd.vip/resources/upload/a7844a45d8ab55e/file/张静-借助AIOps算法提升业务可观测性在京东科技的实践之路.pdf))

### 百度
- **未找到**百度公开的告警/AIOps 根因分析评测集、数据集构建方法或量化评测指标的一手资料。

---

## 7. 国内常用的公开微服务 RCA 基准数据集

### CCF 国际 AIOps 挑战赛（清华裴丹团队等发起，2018 起）
- **历届数据集构建**（官方赛制材料）：2018 KPI 异常检测（5 家互联网公司真实监控指标）→ 2019 多维指标异常定位 → **2020 微服务故障发现与根因定位（运营商准生产环境真实数据，指标+调用链，141 队）**→ **2021 云环境商业银行系统故障实时检测与根因定位（两个银行真实系统，发布指标+调用链+日志，建行云在线评测，248 队）**→ 2022 K8s 电商微服务故障识别与分类（多层级故障类型）→ 2023 开放式 → 2024 RAG 运维知识问答。([2025 赛制介绍 PDF（官方）](https://www.aiops.cn/wp-content/uploads/2025/06/01-2025-CCF国际AIOps挑战赛赛制介绍-聂晓辉.pdf))
- **2025 赛道一"基于大模型智能体的微服务根因定位"**：(a) 数据开源，**共 400 个故障案例分两阶段发布（每次 200）**，来自中科院计算机网络信息中心提供的微服务系统；初赛结果评测、线上自动评分，限制每队最多提交 350 次防刷榜，TOP20 需提交容器镜像审核（排除人工标注/抄袭/背答案）。(b) 故障类型覆盖 Service/POD/Node 的 JVM、数据库、网络、资源、变更（代码、配置错误）等；模态为指标+日志+调用链。(c) **评分：Final Score = 0.40×LA + 0.40×TA + 0.10×Efficiency + 0.10×Explainability**——LA=正确预测组件数/总样本数，TA=正确根因数/总样本数，Efficiency=exp(-(APL-5)/5)（APL 为正确结果的平均推理步数，步数越多分越低），Explainability=推理中观察到的正确关键信息数/关键证据点总数。(d) 纯零训练 LLM Agent 评测。([2025 赛制介绍 PDF](https://www.aiops.cn/wp-content/uploads/2025/06/01-2025-CCF国际AIOps挑战赛赛制介绍-聂晓辉.pdf)、[官方赛题页](https://challenge.aiops.cn/home/competition/1920410697896845344)、[参赛开源方案 MicroRCA-Agent（佐证输出含 component/reason/reasoning_trace 结构）](https://github.com/tangpan360/MicroRCA-Agent))
- 2020 届数据集特征（学术引用）：电商微服务私有云环境、22 个机器节点、集成 Oracle/Redis/Docker，日均 1.2GB，含服务调用指标、业务功能指标、平台性能指标及部署文档；同文献还给出 Sock-shop 故障注入 + 人工标注构建验证集的做法。([软件学报 AmazeMap](https://www.jos.org.cn/jos/article/html/7104))

### Train-Ticket（复旦 CodeWisdom）
41 个微服务的火车票订票基准系统；国内研究常用"模拟故障注入实验（TTFI）+ 按注入类型划分"构造评测数据（如调用链异常检测、Eadro、InstantOps 等的评测底座），指标为 AC@k/Avg@k、F1。([TTFI 用法示例（专利文献引述）](https://www.xjishu.com/zhuanli/55/202211098648.html))

### GAIA（云智慧 CloudWise 开源）
MicroSS 业务仿真系统（扫码登录场景）连续两周采集：6500+ 指标、700 万+ 日志、详细 trace；**通过控制用户行为与模拟错误操作注入异常，并提供全部异常注入记录供公平评测**；Companion Data 另含 406 条指标异常检测/预测数据（279 条带标注）与约 21.9 万条日志解析/异常检测/NER 数据。([GAIA GitHub 官方 README](https://github.com/CloudWise-OpenSource/GAIA-DataSet))

### OpenRCA（港中文深圳 + 微软 + 清华裴丹，ICLR 2025）
首个公开 LLM RCA 基准：335 个真实场景故障 case、68GB 多模态遥测（metric/trace/log）、来自 3 个企业系统，故障记录经人工对齐；定义任务建模与评估方法，结论是当时 LLM（含 Claude 3.5）根因定位能力很弱。([OpenReview 论文](https://openreview.net/pdf?id=M4qNIzQYpd)、[GitHub microsoft/OpenRCA](https://github.com/microsoft/OpenRCA))

### 其他参照（国内团队主导）
- Eadro（CUHK）公开数据集（Zenodo），基于 Train-Ticket/Online Boutique 混沌注入。([Zenodo 记录（经 arXiv 2510.04711 引用）](https://arxiv.org/pdf/2510.04711))
- OpenTelemetry Demo / Online Boutique 式电商底座被国内评测广泛复用：AIOps 2025 赛道一与阿里云 RCA-100 的案例命名（frontend/checkoutservice/cart/product-catalog 等）均为此类电商微服务架构变体。
- 阿里 RCA-Bench 官方文档将 AIOpsLab（MSR, MLSys 2025）、OpenRCA（ICLR 2025）、Cloud-OpsBench、SREGym 列为同代主对照基准，并给出 13 维方法学对比。([RCA-Bench 官方文档](https://sls.aliyun.com/doc/starops/benchmark/rca/rca_benchmark_dataset.html))

---

## 8. 横向对比表

| 对象 | 评测集/数据集构建方式 | 覆盖面 | 测试指标 | 训练集概念 |
|---|---|---|---|---|
| 阿里云 RCA-Bench/RCA-100 | 电商微服务仿真（40+ 服务）+ 四通道混沌注入；103 用例/28 类故障；四层结构化真值；L1–L4 难度；保留非公开样本防污染 | 6 大类故障（节点/资源/流量/数据库/代码/网络）；指标+日志+Trace+告警+拓扑+K8s 事件+profiling 七模态；UModel 实体归一 | O=0.40·定界+0.30·定因+0.30·过程；定界/定因用 UModel 图距离确定性评分（~70–82%），LLM 仅辅助过程分 | 零训练 Agent；Bad Case 回流为回归样本 |
| CCF AIOps 挑战赛（2025 赛道一） | 中科院微服务系统；400 故障案例分两阶段（200+200）开源；350 次提交上限+代码审核防作弊 | Service/POD/Node 的 JVM、数据库、网络、资源、变更（代码/配置）；指标+日志+调用链 | 0.40·组件准确率 LA + 0.40·根因准确率 TA + 0.10·效率（推理步数 exp 衰减）+ 0.10·可解释性（关键证据点命中率） | 零训练 LLM Agent |
| CCF AIOps 挑战赛（2020/2021 等历届） | 运营商准生产/银行真实系统数据，在线评测 | 微服务故障发现与根因定位；指标+调用链（+日志） | Top-K 根因命中率、检测准确率/召回（历届口径不同） | 训练模型为主（传统算法赛） |
| 腾讯（MicroDig 等） | 腾讯视频真实生产系统验证（8000+ 微服务）；无公开评测集 | 微服务性能问题诊断 | 未公开统一口径 | 传统图方法；未见 LLM 评测实践 |
| 华为（NAIE/十大网络数据集） | 电信现网告警+工单标注根因；48/29 类故障、100w+ 样本 | 无线/PTN/微波/PON 告警压缩与故障定位 | 压缩比、精确率、召回率；现网根因识别准确率 85%+ | 监督训练（MMHC+GAT 等），工单标注即训练集 |
| 字节 ByteBrain | 无公开告警 RCA 评测集；相邻任务用公开日志数据集/合成时序数据 | 日志解析、LLM 训练故障诊断、时序 QA | 解析准确率/吞吐、ETTR 等任务指标 | 偏"合成数据训练单点模型"路线 |
| 美团（图灵评测） | 生产 Bad/Good Case 持续沉淀；人工种子集+AI 扩写冷启动 | 结果/过程/效率/风险四层；通用 Agent（非告警专用） | 桥梁指标分层；Rubric 二元化；人机一致率（目标 85–90%+） | 零训练 Agent + 评测驱动迭代；专家语料补垂域 |
| 蚂蚁（DiagFusion 等） | 仿真故障注入 D1 + 商业银行真实故障 D2（事后复盘标注）；按时间切分训练/测试 | 真实故障类型（内存/配置/代码 bug/网络等）；trace+metric+log 多模态 | 根因实例 AC@k、Avg@5（0.75/0.76）；故障类型 F1（0.84/0.80） | 监督训练 GNN（有训练集） |
| 京东 | 实践分享（多维下钻/蒙特卡洛树）；无公开评测集 | Web 场景多维指标根因 | 未公开统一口径 | 未公开 |
| 百度 | **未找到公开一手资料** | — | — | — |
| GAIA（云智慧） | MicroSS 仿真系统+异常注入记录；6500+ 指标/700w+ 日志/2 周 trace | 异常检测、日志分析、故障定位 | 供第三方以 AC@k 等评测 | 支撑训练与评测两用 |
| OpenRCA（港中深+微软+清华） | 3 个企业系统 335 个真实故障 case、68GB 遥测、人工对齐 | 静态观测样本（无主动观测） | 根因定位正确率（LLM 普遍很低） | 零训练 LLM |

### 跨对象要点
1. **两条路线分化明显**：传统 AIOps（华为/蚂蚁 DiagFusion/历届挑战赛）走"真实故障 + 人工/工单标注 + 监督训练"，指标用 AC@k/Avg@k/F1/压缩比；2025 年后 Agent 评测（阿里 RCA-Bench、AIOps 2025 赛道一、美团）走"故障注入仿真 + 结构化真值 + 零训练 LLM Agent"，指标转向**组件准确率、根因（定因）准确率、图距离定界、推理效率、证据链可解释性**的加权综合分。
2. **防止"猜中表象"成为共识设计**：阿里用因果传播链真值 + 图距离区分"命中根因/中间节点/告警点"；AIOps 2025 用关键证据点命中率（Explainability）约束推理过程；美团用过程层评测 + 人机一致率。
3. **真实 vs 注入的取舍**：真实故障（OpenRCA、银行/运营商数据）真实性强但标注贵、规模小（百级）；故障注入（RCA-100、GAIA、AIOps 2025）可规模化、真值精确，但需质量门过滤"不像真实故障"的样本。
