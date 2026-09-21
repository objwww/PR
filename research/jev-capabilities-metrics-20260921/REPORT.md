# Jev 能力全集、可接入环节与评测指标体系调研

调研日期：2026-09-21。标注约定：**[官方]** = TypeSafe 官方文档已证实；**[社区]** = 开源实现实测；**[推测]** = 本报告候选建议，未经官方或我们实证。

## 1. Jev 能力全集与限制

### 1.1 API 与三原语契约 [官方]

- 端点：`POST https://api.typesafe.ai/v1/systemone`，请求体 `{state, model, questions}`，返回 `{model, answers, usage{input_tokens, output_tokens}}`。state 可为字符串/JSON 对象/数组（仅文本，无图像音频）。来源：[API Reference](https://docs.typesafe.ai/api)、[Models](https://docs.typesafe.ai/models)。
- 三原语（可在一次调用中混合，所有 question 并行且相互独立地对同一 state 求值，"增加问题几乎不改变响应时间，也不会造成 context-rot"）：

| 原语 | 语义 | 返回 | 约束 |
|---|---|---|---|
| Choice | 从选项列表选其一 | `choice`、`probabilities`（和为1）、`confidence` | ≤255 个选项，每个选项可附 rubric 描述 |
| Score | 按 rubric 分级 | `score`（概率加权，可落在级别之间）、`legend`、`probabilities`、`confidence` | 2–10 个级别 |
| Noul | 命题是否为真 | `noul`（0–1，"是"的概率；无独立 confidence，单值即完整分布） | — |

来源：[Introduction](https://docs.typesafe.ai/introduction)、[Noul](https://docs.typesafe.ai/primitives/noul)、[API](https://docs.typesafe.ai/api)。
- `instructions` 支持字符串/对象/数组；对象形态可把被比较的数据（如候选记录、claim-evidence 对）放进字段、question 文本用反引号引用字段—— pairwise 判断（告警关联、claim-证据核对）的官方推荐形态。来源：[Noul § Structured instructions](https://docs.typesafe.ai/primitives/noul)。
- 设计哲学：原子问题+代码组合。一个问题只问一件事；多维判断拆成多个问题在代码里加权；阈值由代码按"犯错代价"设定（如 0.8/0.2 三区：执行/人工/放行）。来源：[Introduction](https://docs.typesafe.ai/introduction)、[Noul](https://docs.typesafe.ai/primitives/noul)。

### 1.2 jev-1.13.0 规格 [官方]

来源：[Models](https://docs.typesafe.ai/models)。
- 价格：$42/Btok = **$0.042/Mtok 输入**，**输出免费**。
- 限流：250k tokens/s、1200 req/min（官方声明动态调整中，429 退避重试）。
- 上下文：**单请求总量 64k tokens（state+全部 questions）；state+最长单个 question ≤32k**。
- 别名：`jev-latest` / `jev-preview` 目前均指向 `jev-1.13.0`；阈值调好后应 pin 版本号。
- 训练：RLCD 校准训练，全租户同权重，不做客户微调；领域适配走 state/instructions/criteria。
- 语言：英文最佳；**含 CJK 的其他语言"handled but not equally well"**，非英文负载须自测并依赖 confidence 路由。
- 数据：不用客户请求训练；企业版有 ZDR。

### 1.3 已知弱项（jaggedness，官方逐条列出）[官方]

来源：[Jev 1.13 jaggedness](https://docs.typesafe.ai/model-jaggedness/jev-1.13)（最近复核 2026-09-17）：

1. **字面理解**：按你写的问题回答，不按你的意图；边界情形要写进 criteria。
2. **数字/数学**：不可靠计数（误差随规模增长）；数值表示（hex/RGB）弱于语义表示；**Score 级别间数值标定弱，禁止用期望插值还原具体数值**。
3. **日期时间比较**：日期被当文本读，先后/间隔/窗口判断不可靠——抽取交给模型，算术留在代码。
4. **间接性/多跳**：双重否定、属性之属性、多跳推理掉准确率。
5. **大而杂的 state**：无关细节是干扰项，准确率随无关内容增长下降（context rot）——先检索过滤再发送。
6. **对抗输入**：state 不被默认视为敌意，注入指令/误导性框架/自辩文本可以搬动答案。
7. **instructions 与 criteria 矛盾**会混淆模型。
8. **结构不变量不成立**：同一命题的 Noul 与 yes/no Choice 数值不可互转；命题与其否定的两个 Noul 之和 ≠1（官方示例 0.72+0.47=1.19）；Noul 的阈值不能搬到 Choice 上。
9. **不支持生成**：不能当文本生成模型用；有界答案空间的抽取应转成 Choice。

官方总结"避免"清单：问代码能精确算的东西；一个问题藏多个判断；System Two（多层间接）任务；给 state 塞超过问题所需的内容。

## 2. 告警 RCA 场景可接入环节清单

除已有的证据池选材（jev-selector）与出错检查点复核（jev-reviewer）外：

| 环节 | 接入点 | 输入/输出形态 | 风险边界 | 优先级 |
|---|---|---|---|---|
| 工具候选缩小 | Tool-calling 前：候选工具集 → Jev 打分/选择 | state=当前任务+证据摘要；每工具一个 Noul（"该工具是否可能取得与假设 X 相关的证据"）或一个 Choice（≤255 工具）；输出概率排序，代码取 top-k | 只缩小候选、不执行工具；被裁工具须留审计；Noul 概率非证明（社区教训），关键只读工具可保底必跑 [推测+官方弱项#6] | 高 |
| 多 Agent 专家路由 | Supervisor 委派前 | Choice：专家名单为选项+rubric 描述（如"日志专家：日志类证据/时序异常"）；输出 choice+probabilities+confidence，低 confidence 走默认专家 | 路由错误代价低（可重委派）；但路由依据要落审计；不要让它生成委派指令文本 [推测] | 高 |
| 模型分级路由 | 每轮 LLM 调用前 | Score 三级难度 rubric（平凡/常规/复杂），阈值+confidence 门控切换便宜/强模型（pi-jev 实测方案：≤0.5 且 conf≥0.6 走便宜档，≥1.5 走强档，中间带不动） | 只切模型不改任务；切换决策与用户提示落日志；中间带/低置信保持现状 [社区] | 高 |
| 评测预筛（报告偏题/丢反证/claim-证据冲突） | RCA 报告产出后、人审/打分前 | 逐 claim 与证据池配对：structured-instructions Noul"claim 是否被 `evidence` 支持"；外加 Noul"报告是否回答了告警问题""是否存在被报告忽略的反证" | **预筛而非判决**：冲突项交人审或 reviewer LLM 复核；claim 中的数字/时间先在代码里校验再交 Jev 判语义（官方弱项#2/#3） [推测] | 高 |
| 上下文压缩/证据池裁剪 | 长会话/大证据池入模前 | 逐条证据两个 Noul（保留调用/保留全文）+ 可选 staleness Score 救援边界项；保留原文逐字，不做摘要改写 | 错误或畸形答案→保守保留；永不裁剪用户文本与写操作记录；"筛选与改写分离"（社区核心教训，见 §5） [社区] | 高 |
| 冲突线索预筛 | 证据汇总后、根因综合前 | 证据两两配对 Noul："A 与 B 是否相互矛盾"；命中对交给生成模型重点处理 | 只发现、不裁决；时间窗类矛盾（"重启在告警前"）必须代码判（官方弱项#3） [推测] | 中 |
| 告警关联/去重 | 新告警入库时 | 与候选历史 incident 逐一 structured Noul"是否同一根因事件"（官方 duplicate-record 模式的直接翻版）；输出逐对概率，代码阈值归并 | 归并动作要可逆、留人工拆分口；时间接近性由代码算好作为输入字段，不让 Jev 比时间 [推测+官方模式] | 中 |
| 语义无进展检测 | Agent 循环每轮后 | Noul："本轮新证据是否提供了此前没有的根因相关信息"；连续 k 轮低分→退出/换策略 | 只触发流程分支，不直接判任务失败；退出阈值用配对实验定 [推测] | 中 |
| 告警升级/严重度建议 | 分诊时 | Noul（是否需人工介入）或 Score（严重度 rubric） | 仅作建议信号；最终 page/升级阈值由代码按代价设定，中间带转人工（官方三区模式） [官方模式+推测] | 中 |

**绝不能交给 Jev（依据官方弱项清单）**：①根因报告等一切文本生成（弱项#9）；②SLO 计算、错误率/百分比/计数（#2）；③时间窗比较、时序先后（#3）；④写操作授权/审批最终闸——对抗输入可搬动答案（#6），且"概率不是证明"（社区）；⑤最终修复认定——验证需要确定性检查（对应 MAST 的 verification 失败族，见 §3），模型判断只能做预筛。

## 3. 评测指标体系（业界/学界口径）

| 指标 | 定义 | 计算口径 | 适用对象 | 来源 |
|---|---|---|---|---|
| 关键证据召回率 recall@k | 人工标注的关键证据集中，被保留/送入下游的比例 | per-incident 取均值；关键集由标注定 | 上下文选材 | RAG 评测通例 |
| 误删率（关键反证误裁率） | 被裁掉的条目中属于关键证据（尤其反证）的比例 | 误删关键数/被裁总数；反证单列 | 上下文选材（代价最不对称的指标） | [推测，对应 MAST verification 族] |
| Lost-in-the-middle 位置敏感性 | 同一关键证据放在上下文首/中/尾时的命中差 | 位置×命中率的 U 形曲线 | 选材后上下文排布 | [Liu et al., TACL 2024](https://arxiv.org/abs/2307.03172) |
| 根因命中 / P / R / F1 | 产出根因与标注根因的匹配（EM 或评分阈值） | per-incident，配对差值进统计检验 | 端到端 RCA | 通例 |
| pass^k | 同一任务跑 k 次全部成功的概率，衡量稳定性而非单次运气 | k 次独立重复的无偏估计 | Agent 工具使用可靠性 | [τ-bench (Yao et al. 2024)](https://arxiv.org/abs/2406.12045)、[τ²-bench](https://arxiv.org/abs/2506.07982) |
| 工具参数合法率 | 工具调用中参数 schema/取值合法的比例 | 合法调用/总调用 | 工具使用 | [AgentBench (Liu et al. 2023)](https://arxiv.org/abs/2308.03688) 类口径 |
| 无谓调用率 / 重复-死循环率 | 无新信息增益的调用占比；陷入重复步的轨迹占比 | MAST 失效率标注口径 | 工具使用 / Agent 循环 | [MAST (Cemri et al. 2025)](https://arxiv.org/abs/2503.13657)：14 种失败模式 3 大类（规约 41.8% / 协同 36.9% / 验证 21.3%），1600+ 轨迹标注 |
| Token 与费用账 | 每事故输入 token 费用 + Jev 侧费用 + 缓存影响 | Jev：$0.042/Mtok 输入、输出免费；主模型侧按缓存读/写分别计价（缓存写≈10×读，社区实测口径） | 全链路 | [Models](https://docs.typesafe.ai/models)、[pi-fast-jev-compaction 实测](https://github.com/QuentinDanblon/pi-fast-jev-compaction) |
| P95 延迟 | 单轮/单事故端到端延迟的 P95 | 分位数，区分 Jev 调用在关键路径 vs 后台 | 全链路 | 通例；社区：后台打分省约 20s/会话 |
| 上下文漂移 | 随轮次增加，模型对早期关键约束的遵循度衰减 | 固定探针问题按轮次重放 | 长会话 | 对应官方 context-rot 告诫（弱项#5） |
| Abstention 质量 | 低置信拒判（转人工）机制的覆盖率-准确率权衡 | selective accuracy vs coverage 曲线；AUC | 所有 Jev 环节 | 官方 confidence 三区模式 |
| 概率校准 | noul 概率的经验校准度 | ECE / Brier score，分桶可靠性图 | Jev 阈值设定 | [推测；官方宣称 RLCD 校准但须自测，尤其中文] |

## 4. 我们 A/B 对照实验的指标与统计方法建议

设计前提：同场景同轮次配对（A=无 Jev，B=有 Jev），配对单位=（事故 × 轮次）。

- **主指标**（二选一做主假设，其余做次要并做多重比较校正/Holm）：
  1. 关键证据召回差值 Δrecall（连续，配对）；
  2. 根因命中（二值）→ 用 **McNemar 检验**（只看 A 对 B 错 / B 对 A 错的不一致对）。
- **守门指标**：误删关键反证率（B 组必须不劣化，设非劣效界，如 +1pp）、P95 延迟增量、每事故费用增量。
- **统计方法**：
  - 连续配对差值：**Wilcoxon 符号秩**（不假设正态）为主，**符号检验**做稳健性复核；
  - 同一事故内多轮次相关 → **cluster bootstrap**，重抽样单位=事故簇（不是轮次），报 95% CI；
  - 效应量必报（中位差、McNemar odds ratio），不只看 p。
- **最小样本量建议**[推测，常规功效口径]：cluster bootstrap 稳定需 **≥50 个事故簇**；McNemar 要检出不一致对比例差，不一致对数 ≥25 才有基本功效——按经验根因命中率 ~60-80% 时，约需 **≥100 个配对事故** 才能以 80% 功效检出 10pp 的命中差；轮次级指标（召回差）每簇 ≥5 轮配对可显著摊薄方差。预实验 20 簇先估方差再定正式样本量。
- **混淆控制**：Jev 模型版本 pin `jev-1.13.0`（别名会漂移，官方告诫）；中英文场景分层（官方明示 CJK 较弱）；缓存命中状态记录进账（影响费用与延迟两侧）。

## 5. 社区实现借鉴要点

来源：[pi-jev (iefnaf)](https://github.com/iefnaf/pi-jev)、[pi-fast-jev-compaction (QuentinDanblon)](https://github.com/QuentinDanblon/pi-fast-jev-compaction)。

1. **筛选与改写分离**：不做摘要式压缩，只问"保留/截头/删除"，保留内容逐字——摘要会丢文件路径、确切报错等关键串。这是我们证据池选材应固守的原则。
2. **每条两个 Noul + 边界救援**：keepCall / keepResult 两个独立 Noul，加 staleness Score 救回边界项；阈值带（keepThreshold 0.5、borderline 0.1）可配置。
3. **失败保守化**：Jev 报错、答案畸形、超预算、配对校验失败→一律按原样保留/回退默认路径；缺 key 时静默 no-op。
4. **硬不变量在代码**：工具调用-结果配对、thinking 块不碰、写操作记录永不删（"模型看不到自己跑过 migration 就会再跑一遍"）、近端消息 pin 住。
5. **成本门控体系**（对我们费用账直接可抄）：决策按 (id, 输入hash, 结果hash) 缓存跨会话复用；触发分数、最小待裁字符、最小间隔；**缓存代价门**——裁剪改写 prompt 前缀使缓存读变写（≈10×价），只有释放份额 f 足够大才值得裁，实测无此门时多花 11× 请求且净收益更低。
6. **后台打分**：Jev 移出关键路径，决策晚一拍生效，省 ~20s/会话墙钟，净收益仅损 1.8pp。
7. **实测口径诚实**：net +31.8%/+37.7% 来自截图重会话；文本重会话未验证；绝对 token 数不确定、比例才可信——我们报 A/B 结论时同样只报配对比例。
8. **state 不能省**：`--state=8000` 把净收益从 33.6% 打到 14.0%（Jev 看不到历史就全保留）——选材问题必须给足上下文，但又不超 32k/64k 预算，靠渐进压缩 fitter。
9. **模型分级路由已产品化**：三级难度 Score + 双阈值 + confidence 门 + 中间带不动的策略，可直接移植。
