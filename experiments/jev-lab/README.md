# Jev 独立实验通道：运行、指标、修改步骤与验收

更新：2026-09-20。此目录已实现可启动的实验台，不是接入主链的设计草案。

它回答一个范围明确的问题：**面对同一份冻结证据，先用 Jev 筛选原文，是否改善同一诊断模型的最终症状 Recall / F1，以及全链路 Token 和费用？**

这是一条独立进程、独立页面、独立数据目录的离线实验路径。没有修改 `control-app`、`alert-web`、数据库迁移或现有调度。真实运行会向配置的模型供应商发送导入的可见证据，但不会创建线上告警、调用工具或执行处置。

## 1. 启动和体验

在仓库根目录运行，Python 3.11+，服务本身只使用标准库：

```powershell
python experiments/jev-lab/lab.py
```

打开 [独立实验台](http://127.0.0.1:8766)。点击“载入合成示例”，保持 DEMO，点击“运行 30 批配对实验”。

- 示例包含 6 个合成案例；30 批产生 180 对、360 次诊断尝试及 180 次筛选尝试。
- DEMO 全部在本机使用明确的规则模拟，不调用 Jev / LLM，不产生模型费用。示例刻意把部分线索放到窗口之后，只用于检查链路。
- 即使 DEMO 分数上升，最终结论固定为 `INCONCLUSIVE`，Token 与费用为未知，不能据此声称真实效果。
- 页面有实验历史、进度、A/B 指标、每批曲线、逐案例证据/概率/输出/错误审计、取消、完整 JSON 导出。
- 默认结果保存在 `E:/kimiCode/var/jev-lab/`，已受仓库现有 `var/` 忽略规则保护。导出 JSON 含导入的事件内容与人工标签，应按原数据权限保管。

可指定另一个本机端口和独立目录：

```powershell
python experiments/jev-lab/lab.py --port 8767 --data-dir var/jev-lab-other
```

服务只绑定 `127.0.0.1`，同目录使用进程文件锁。页面具备 Host / Origin 检查、POST 令牌和 CSP；这不等于面向公网的账号权限系统。共享给团队前应接已有鉴权代理，不要把监听地址直接改成公网地址。

## 2. 接入真实 Jev：具体步骤

1. 为实验台配置独立供应商配额，避免挤占现有调查模型的并发/额度。实验台本身串行执行，两条路径的先后顺序按固定随机种子打乱。
2. 在启动进程的环境中注入以下变量。服务不读取项目 `.env`，不读取现有主链凭据。不要把真实 Key 写入代码或提交仓库。
3. `JEV_LAB_LLM_URL` 是完整的诊断接口地址，包含 `/v1/chat/completions` 或供应商等价路径。需要兼容非流式 Chat Completions、`response_format=json_object`、`temperature=0`、`max_tokens=1024`。接口不兼容会明确失败，不会偷偷换模型/降级。
4. 重启实验台，在页面确认“真实 API 已配置”。这只表示必填配置齐全，不表示 Key 已经通过供应商认证。
5. 导入真实标注数据，先运行 1 批验证契约和标签；确认正确后，冻结参数运行 30 批。页面提交 LIVE 会调用真实 API 并产生费用。

```powershell
# 示例值；使用你们现有密钥管理方式在进程环境中注入实际凭据。
$env:JEV_API_KEY = '<实验用 TypeSafe Key>'
$env:JEV_LAB_LLM_URL = 'https://your-model-gateway.example/v1/chat/completions'
$env:JEV_LAB_LLM_API_KEY = '<实验用诊断模型 Key>'
$env:JEV_LAB_LLM_MODEL = '<固定模型或固定部署版本>'

# 可选：每百万 Token 的 USD 单价，来自你们实际套餐，不能照抄一个任意模型的报价。
# 未提供时仍然记录 usage，费用显示未知。
$env:JEV_LAB_LLM_INPUT_USD_PER_M = '<实际输入单价>'
$env:JEV_LAB_LLM_OUTPUT_USD_PER_M = '<实际输出单价>'
$env:JEV_LAB_LLM_CACHED_INPUT_USD_PER_M = '<实际缓存输入单价>'
python experiments/jev-lab/lab.py
```

不配置价格时，请完全省略那三个价格变量，不要把占位字符串当成数值。

Jev 固定为 `jev-1.13.0`，使用 TypeSafe 的 `POST /v1/systemone`，没有把它伪装成聊天模型。每条证据对应一个 Noul 问题，证据 ID 也写入问题内容，因为官方说明 question map 的 key 本身不参与模型推理。响应必须完整覆盖全部 ID，概率必须在 `[0,1]`；失败不会退回基线后仍标成 Jev 成功。[API 依据](https://docs.typesafe.ai/api)

配置中 Jev 输入单价是查阅于 2026-09-20 的 `$0.042 / 百万 Token`，输出免费；这是估算价格快照，实际账单以供应商为准。需要调价时修改 `config_from_env()` 中 `prices`，新实验会保存自己的单价快照。[模型与价格依据](https://docs.typesafe.ai/models)

## 3. 数据如何准备，避免“自己给自己打分”

直接复制 [example-dataset.json](E:/kimiCode/experiments/jev-lab/example-dataset.json) 的结构替换内容。不要保留示例中的“故障线索”标记作为真实任务特征；那是 DEMO 规则的显式触发词。

数据字段：

| 字段 | 修改步骤与约束 |
|---|---|
| `version` | 给本次冻结数据一个版本号；实验另保存完整内容 SHA256 |
| `synthetic` | 合成/演练生成样本设 true；经人工核对的真实历史事件设 false。仅改标记不能让合成数据成为真实证据 |
| `cases[].case_id` | 一条待诊断快照的唯一 ID |
| `cluster_id` | 原始独立事件 ID；同一次事故的多条告警、多个时间切片和重复运行使用同一个值 |
| `objective` | 实际告警目标/待解释问题，不能提前写入已知正确根因 |
| `protected_context` | 原始目标、租户、时间窗、权限和待解决约束，两组原样保留 |
| `symptom_catalog` | 任务通用的代码→描述词典，两组共用；不要为每个 case 只列出正确答案 |
| `root_cause_catalog` | 通用组件、故障类型、原因代码表，三个数组 `component/fault_type/reason_code`；不能从该案例 gold 临时生成候选集 |
| `evidence` | 已冻结、已做相同预处理的候选证据，按基线阅读顺序排列；每条包含 `id/text/required` |
| `required` | 在看实验结果之前标记的必保留证据，如关键反证和不可丢弃边界；不能根据 gold 自动标记所有正确证据 |
| `gold.symptom_codes` | 人工确认的完整症状集合，不能使用模型自己的预测代替 |
| `gold.evidence_ids` | 人工确认需要召回的相关证据集合，必须引用当前候选池中的 ID |
| `gold.root_cause` | 人工确认的规范化三元组；证据不足时三项均可写 `UNRESOLVED` |

导入范围：1–50 个 case，每个 1–80 条证据；可见 case 输入不超过 24KB，完整请求体不超过 2MB。拒绝缺失标签、重复 ID、无效引用、非整数批次和超预算必保留证据。字节限制是本实验的工程边界，不能替代供应商的 tokenizer/context 限制；供应商报 422 时保留失败并检查输入。

标签与模型输入分别处理：[public_case()](E:/kimiCode/experiments/jev-lab/lab.py:99) 只投影目标、保护上下文、通用词典与候选证据，`gold`、case ID、cluster ID 和其他导入元数据均不会发给供应商。两组模型看不到评分答案。

推荐的首批真实数据准备顺序：

1. 从已有导出结果或只读快照获得证据，不使用会重新投递告警的评测驱动。
2. 冻结和脱敏候选池；包含早期关键证据、重复日志、噪声、反证，以及中文和中英混合场景。
3. 两名复核者核对症状标签和证据相关性；有分歧的案例先解决标签分歧。
4. 同一事件变体共享 `cluster_id`。至少 5 个独立事件才输出 CI；5 是沿用项目的最低门槛，不表示样本量已足够。优先增加独立事件数量，再增加重复次数。
5. 用单独开发集调阈值和预算，然后固定参数跑验收集 30 批。不要一边看验收 F1 一边调阈值，最后只挑最好的一轮。

## 4. 30 批的准确含义与隔离边界

```text
同一份冻结数据（N 个案例，G 个独立事件）
  └─ 30 批，每批遍历全部 N 个案例
       └─ 每个案例配对
            A：必保留证据 + 按原顺序填充窗口 → 诊断模型
            B：Jev 打分 + 必保留证据 + 阈值/预算选择 → 同一诊断模型
                 ↓
          与同一人工 gold 比较 → 保存两边结果和调用账目
```

默认 `max_items=20`、`max_chars=8000`、`threshold=0.5`、`rounds=30`、`seed=20260920`。字符预算只覆盖证据正文，目标、词典和协议还有额外开销，因此必须以供应商 usage 衡量最终 Token。

基线是独立实验的“有界证据窗口”策略，**不是逐字节复制线上 ContextAssembler 的全部裁剪、轨迹、记忆、子 Agent 回执和工具协议**。保留条数 20 与当前代码常量一致；两组都使用相同的保护逻辑。Jev 筛选结果恢复原始证据顺序，避免同时引入阅读顺序变化。全文不生成摘要、不修改语义。

每对最多 3 次模型请求。例如 30 个案例 × 30 批 = 900 对，最多 2700 次请求。没有自动重试和线上工具回退。默认串行、2 小时运行时限、单次传输 socket timeout 最多 30 秒；时限/取消在调用边界检查，当前网络调用需要返回后才能结束，不承诺强杀已经发出的供应商请求。

状态与失败策略：

- 每次开始调用一侧前落盘 `IN_FLIGHT`，完成后落盘结果。调用中崩溃保留未知尝试，不显示为零费用。
- 取消后保留部分结果和完整计划分母；不能用已完成小部分代替 30 批的完整实验。
- 任意一侧连续 3 次失败，状态变为 `HALTED_ERRORS`，避免错误配置产生大量无效请求。
- 重启将残留运行状态标为 `INTERRUPTED`，不自动重复可能已经计费的请求。修复后手动创建一个有新 ID 的实验。
- 持久化为每个实验一个 JSON 文件、原子替换、单进程单 worker。适合当前最多 1500 对的本机实验；更多并发/更大历史量再迁移到现有评测存储与作业队列。

## 5. 指标的定义和结论规则

### 最终诊断：主结果

对每个案例，将最终模型的 `symptom_codes` 与 gold 做去重集合比较，先 trim / casefold：

```text
TP = |预测 ∩ gold|      FP = |预测 − gold|      FN = |gold − 预测|
Precision = TP / (TP + FP)
Recall    = TP / (TP + FN)
F1        = 2 TP / (2 TP + FP + FN)
```

两边都空时 P/R/F1=1；gold 非空、预测空时为 0；gold 空而预测非空时 P/F1=0、R=1。无正例案例应单独复核，不能靠大量“空事件”抬高召回。

- micro：先把同一组成对成功案例的 TP/FP/FN 相加，再计算 P/R/F1。
- macro F1：逐次案例 F1 均值。重复次数相同且全成功时，每个 case 权重相同。
- 根因准确率：三元组规范化后全部相等，包括正确的 UNRESOLVED；没有复刻主项目 `SynonymLexicon`，请在导入时把词典别名转成统一代码。
- 最终引用 F1：输出的 `evidence_ids` 与 gold 相关证据集合比较；另统计不在该侧输入中的引用，不把“有 ref”直接当成真正有据。

这些指标只在**两侧都成功的相同配对集合**展示，失败次数和有效配对数同时公开。任何缺失配对都使提升结论不可判定。

### 证据选择：中间结果

`selection_recall / selection_f1` 比较送入诊断模型的证据 ID 与 gold 相关证据 ID。它们用来解释“为什么最终结果改变”，不能替代最终诊断 F1。必保留但与本题 gold 无关的证据会降低选择 Precision，这是按当前定义真实计算的结果。

### 开销：全链路账目

页面展示诊断模型输入 Token、Jev 输入 Token、全部输入/输出和总 Token、缺失 usage 次数、估算费用、端到端 p50/p95 延迟、证据字符数。

```text
输入节省率 = 1 − B 诊断模型输入 Token / A 诊断模型输入 Token
总量节省率 = 1 − B（Jev + 诊断模型）的总 Token / A 诊断模型总 Token
费用节省率 = 1 − B 全链路估算费用 / A 全链路估算费用
```

开销包含所有已执行且已记录的尝试，包括输出解析失败的已计费调用。usage 不完整、实验不完整或基线分母为 0 时，节省率为未知。DEMO 不伪造 tokenizer 数字；HTTP/网络失败也不凭空记零。

不同供应商 Token 相加只是描述性总量，费用更适合跨模型比较。有缓存 usage 时按缓存单价计费；报告了缓存 Token 却没有缓存单价时费用未知。供应商没有报告缓存明细时按全部输入的配置单价估算，不能当成精确账单。没有计入供应商未暴露的额外费用；不要把字符减少比例称为 Token 节省。

### 配对统计：不能把 30 次重复当成 30 个事件

每个配对先计算 `B − A` 的 Recall / F1，按 `cluster_id` 求簇内均值，再对独立事件等权平均。对整个事件簇有放回抽样 1000 次，输出 95% nearest-rank percentile CI，随机种子记录在结果中。这里使用 Python RNG，并不承诺与 Java RNG 的 bootstrap 抽样序列逐位一致；估计目标与项目原统计约定相同，扩展到了连续 F1/Recall。

因此“事件等权 Δ F1”可以不同于“micro F1 的差”，页面分别标明，不能混用。

最终结论规则已实现：

| 状态 | 条件 |
|---|---|
| `IMPROVED` | LIVE、非合成、计划全部配对成功、独立事件 ≥5，并且 Recall 与 F1 的 CI 下界都 >0 |
| `REGRESSED` | 满足上述有效性前提，至少一个指标的 CI 上界 <0 |
| `NO_CLEAR_GAIN` | 有效实验，但上述两个规则都不成立 |
| `INCONCLUSIVE` | DEMO、合成数据、取消/中断/失败/配对缺失或独立事件不足 |

这只是实验结论，不自动切换任何线上配置，也不保证达到产品要求的实际提升幅度。投产前还要预先确定业务最小收益、尾延迟和安全回归阈值。

## 6. 以后怎么增加“主 Agent 选重点 → 摘要 → 注入”

不要把三个改动一次塞入当前 B 侧；那样无法归因。按以下步骤逐项新增实验版本：

1. **先完成当前 A/B**：验证 Jev 筛选原文，特别检查丢失反证、早期关键线索和中文语义。
2. **加入摘要作为新处理臂 C**：复用 B 的同一组选中证据，调用独立配置的生成式模型输出摘要和 source refs。把生成调用作为新的 ledger kind 记录 Token/费用/延迟，增加摘要校验失败计数。Jev 本身不负责生成摘要。
3. **给 C 加硬校验**：原始目标、边界、待办、关键反证的 protected refs 必须保留；每个 source ref 必须来自冻结池；摘要不允许改变否定、数值单位或时间方向。校验失败单独计失败，不能当作压缩成功。
4. **加入“主 Agent 选重点”作为新处理臂 D**：选重点模型只看同一公共投影，不看 gold；显式产生候选 ref，所有新增调用计入成本。保留独立的反证召回指标，防止主 Agent 初始猜测被重复强化。
5. **扩展 `run_arm()` 与 `summarize()`**：增加 C/D 成本路径和基线成对比较，不复用当前已冻结 A/B 的实验版本号。复用相同 dataset hash、批次和 cluster，比较 B→C、C→D 的增量。
6. **覆盖新增验收**：否定翻转、单位变化、摘要引入不存在 ref、重要约束缺失、摘要超预算、压缩模型超时、恢复旧快照、摘要开销大于主模型节省。
7. **单步离线成立后，再做完整 Agent 回放**：通过现有 [AgentReplayRunner](E:/kimiCode/control-app/src/main/java/com/objwww/pr/control/alert/application/replay/AgentReplayRunner.java) 和冻结工具录制，将选材结果接到实验 profile 的 ContextAssembler；继续禁止回放 miss 降级线上工具。加入工具调用次数、补查次数、重复调用、死循环和任务完成率。当前实验台尚未执行这一步。

当前单次诊断实验不能证明多 Agent 委派、MCP 调用质量、上下文长期漂移或整个任务成功率。这些仍按此前 [评测审计报告](E:/kimiCode/research/agent-eval-audit-20260920/REPORT.md) 的完整轨迹用例验证。

## 7. 测试与已完成的验收

```powershell
python -m unittest discover -s experiments/jev-lab -p test_lab.py -v
node --check experiments/jev-lab/app.js

# 可选浏览器验收：本机已有 Playwright + Chromium 才运行，不是服务运行依赖。
python experiments/jev-lab/smoke_browser.py
```

截至本次验证：25 项离线测试全部通过；Chromium 页面完成 30 批、180 对，桌面/窄屏截图、刷新恢复、审计点击和 JSON 下载通过，控制台错误 0。没有调用真实 Jev/诊断模型，没有形成真实 Recall/F1 或 Token 收益结论。

| 验收组 | 可运行用例与期望 |
|---|---|
| 数据契约 | 正常示例通过；重复 case/证据 ID、缺失 cluster、无效 gold 引用和错误类型拒绝 |
| 参数边界 | 31 批、小数批次、布尔批次、NaN 阈值和未知参数拒绝 |
| 保护约束 | Jev 全给低分时 required 仍保留；必保留证据超预算在任何 API 之前拒绝 |
| 标签隔离 | gold、cluster/case ID、额外私有字段不出现在供应商请求中 |
| 评分 | TP/FP/FN、去重、空集合约定、无效引用和根因匹配符合定义 |
| 统计 | 单事件重复 30 次仍只有 1 个 cluster，没有 CI；不平衡重复数仍按事件等权；固定 seed 可重现 |
| Jev 契约 | Noul 问题明确指向证据；模型版本不符、答案缺失、NaN 或负概率均失败且无静默回退 |
| 计费 | 缓存价格计算；usage 缺失不记 0；诊断 JSON 解析失败仍保留供应商返回的 usage |
| 调用边界 | 取消或到时后不再发起新的模型请求 |
| 跑批 | 30×6=180 个唯一配对、540 次模拟调用；外部修改原对象不改变冻结快照 |
| 结论约束 | DEMO 或缺失配对不能判为提升；连续失败停止但仍保留原计划分母 |
| 持久化 | 取消保留结果；重启未结束实验标中断；IN_FLIGHT 不显示零费用；同时启动另一个实验被拒绝 |
| 本机 API | 页面/资源可访问；配置不包含 Key；跨站、Host 不匹配和无 CSRF 令牌不能启动；UUID 路径穿越拒绝 |
| 浏览器 | 30 批进度、指标渲染、180 行明细、查看证据、导出、刷新恢复、390px 无整页横溢出、无控制台错误 |

测试实现见 [test_lab.py](E:/kimiCode/experiments/jev-lab/test_lab.py) 和 [smoke_browser.py](E:/kimiCode/experiments/jev-lab/smoke_browser.py)。本机浏览器实跑证据：

- [30 批 DEMO 完整结果](E:/kimiCode/var/jev-lab-browser/demo-30-batches.json)
- [桌面截图](E:/kimiCode/var/jev-lab-browser/desktop.png)
- [窄屏截图](E:/kimiCode/var/jev-lab-browser/mobile.png)

真实验收还需补跑：供应商实际 401/429/529/网络超时、中文语义筛选、真实人工 gold 集、实际账单与 usage 核对。已有对应错误处理/离线契约测试，不等于外部供应商联调已通过。

## 8. 设计依据与代码证据

| 决定 | 可核对的证据 |
|---|---|
| 独立运行，避免重复投递告警 | [ReplayScenarioDriver](E:/kimiCode/control-app/src/main/java/com/objwww/pr/control/eval/application/ReplayScenarioDriver.java:24) 的当前回放通路会调用 `/webhooks/alertmanager` |
| 首版比较有界证据窗口与 Jev 选材 | [ContextAssembler](E:/kimiCode/control-app/src/main/java/com/objwww/pr/control/alert/application/agent/ContextAssembler.java:63) 的 evidence limit 为 20；[窗口实现](E:/kimiCode/control-app/src/main/java/com/objwww/pr/control/alert/application/agent/ContextAssembler.java:446) 遍历前 N 条；独立实验不宣称完全复现该类 |
| 不能默认摘要一定省 Token | 当前 [putValidatedSummary](E:/kimiCode/control-app/src/main/java/com/objwww/pr/control/alert/application/agent/ContextAssembler.java:237) 是组装中的新增摘要面；原始内容是否被替代需要按实际模型输入核算 |
| 症状集合 TP/FP/FN，根因三维 | [ScenarioEvaluator](E:/kimiCode/control-app/src/main/java/com/objwww/pr/control/eval/application/ScenarioEvaluator.java:77)；实验侧保留集合语义，词典别名和 UNRESOLVED 细节已单独说明 |
| 不以重复轮次冒充独立样本 | [PairedTrialStats](E:/kimiCode/control-app/src/main/java/com/objwww/pr/control/eval/domain/service/PairedTrialStats.java:30) 采用事件簇等权 bootstrap 和最少 5 个 cluster；实验侧扩展为连续指标 |
| Jev HTTP 适配与 Noul 概率 | [TypeSafe API 官方文档](https://docs.typesafe.ai/api)，2026-09-20 核对 |
| 固定模型、独立账目、价格快照 | [TypeSafe Models 官方文档](https://docs.typesafe.ai/models)，2026-09-20 核对 |
| 算术留给代码；摘要另用生成模型；反证与注入场景要测 | [Jev 1.13 已知局限](https://docs.typesafe.ai/model-jaggedness/jev-1.13)，官方标注复核日 2026-09-17 |

这次只新增 `experiments/jev-lab/` 和研究文档索引，运行产物位于忽略目录 `var/`。无需 Java 服务部署、Maven 依赖、数据库迁移、MCP 服务器或现有前端重新构建。后续是否接入主链，应由真实、独立标注数据上的完整实验和 Agent 回放共同决定。
