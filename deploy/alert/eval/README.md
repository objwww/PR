# deploy/alert/eval —— 评测输入备料（同义词白名单 + 场景清单）

> 并行备料任务 P5 产出，服务 AM3 评测线（M3-10/M3-14/M3-16/M3-17 消费）。
> 本目录只含两个 yml 与本说明；**不改任何既有文件、不落任何代码**。
> 设计依据 = `docs/告警AM3-技术方案.md` §6.4（确定性评分/三指标）与 §6.7（五场景枚举写死），
> 字面对齐 `docs/告警AM2-落码技术方案.md` M2-24 的 **ScenarioMapExportV1** 交接契约。

## 一、两个文件各是什么

| 文件 | 一句话定位 | 直接消费方 |
|---|---|---|
| `synonym-lexicon-v1.yml` | 确定性评分的**匹配词典**：fault_type / reason_code / component 三个枚举 + 每条的同义词白名单 + 匹配语义规则 | M3-16 评分器（root_cause_hit）、M3-10 GT 对照侧；AM2 GT 行的同义词白名单登记面（AM2 §6.7 行 210） |
| `eval-scenarios.yml` | 首批 5 场景的**预登记注册表**：注入参数 / 期望症状 / 期望根因 / 复原判据 / 评测时间参数 + 与 ScenarioMapExportV1 的字段对齐表 | M3-17 ScenarioDriver 与批量编排、M3-10（ExportV1 预登记面） |

两者关系：场景文件里的 `expected_root_cause` 取值域就是词典文件的枚举；评分时按 scenario_id
绑定期望真值，模型 v2 类型化字段经词典匹配后对照。

## 二、`synonym-lexicon-v1.yml` 字段说明

顶层：`lexicon_version`（版本化白名单，迭代=出新版，只增不改）、`status`（人工复核校准前的
机器分不作结论依据，§6.4）、`matching_rules`（M-01~06，评分器实现必须逐条一致）。

| 段 | 字段 | 含义 |
|---|---|---|
| fault_types | `code` | 语义故障类型枚举值（EvidencePackage v2 `root_cause.fault_type` 的匹配值域） |
| | `chaos_family` | AM2 靶场故障族代号（arena.yml 告警标签 `fault_type` 面的 F1/F2/F3）；AM0 场景为 null。**与语义枚举是两个值域** |
| | `bound_scenario` | 首批绑定的场景号（S1~S5） |
| | `synonyms` | 同义词/近义表述白名单（中英）；评分匹配 = 规范化后全串精确等值 |
| | `negative_examples` | 必须保持 NO_MATCH 的反例，仅供评分器单测，不参与匹配 |
| reason_codes | `code` / `fault_type` | 机制级原因码，一 fault_type 一码；fault_type 反向圈定该码的合法取值面 |
| components | `code` / `synonyms` | `root_cause.component` 匹配值域（根因组件；注意与告警面 service 标签区分——S1/S2 根因组件是 payment，checkout 只是症状面） |
| negative_samples | （全局） | 任意维度都必须 NO_MATCH 的噪音表述，防跨类误收的回归面 |

**匹配语义（M-01~06 摘要）**：评分输入仅限 v2 类型化字段（v1 自由文本不做评分输入，§6.3）；
规范化 = 去首尾空白 + casefold；匹配 = 全串精确等值，禁子串/正则/模糊；白名单外 = NO_MATCH
（记 miss + 原值进失败样本清单，不抛错不猜测）；是否计入三指标分子分母由公式层决定，与本表无关。

## 三、`eval-scenarios.yml` 字段说明

顶层：`execution_contract`（串行、场景并发=1、每场景 2 轮、上轮 resolved 未归并禁入下轮、
失败落档不中断、chaos- 前缀隔离——全部来自 §6.7/§6.8/M3-17 冻结面）。

每场景的字段：

| 字段 | 含义 | 依据 |
|---|---|---|
| `scenario_id` / `name` | 场景号（S1~S5，§6.7 枚举写死）与场景名 | §6.7 |
| `fault_source` / `scenario_type` | 故障源（AM0 flagd / AM2 靶场）与类型 | §6.7 表列 |
| `driver` | M3-17 统一 ScenarioDriver 三实现之一（Flagd / ArenaChaos / Infrastructure） | M3-17 |
| `chaos_family` | 靶场故障族 F1/F2/F3（告警标签面、C-6 指纹输入）；AM0 场景为 null | arena.yml 头注 |
| `target` | 注入/根因指向的对象 | ChaosSession.selector 语义 |
| `injection` | `mechanism`（激活机制）/ `flag`+`variant`（flagd 场景）或 `effect`+`selector_semantics`（靶场场景）/ `variant_space` / `baseline_variant` / `ttl` / `caution` / `token_discipline` | §4.2、M2-16/18/19/20、BA-19 |
| `expected_root_cause` | `{component, fault_type, reason_code}`，值域 = 词典枚举 | M2-24 ExportV1 / AM2 §6.7 |
| `expected_symptom_codes` | 期望 firing 的 alertname（编码规则 = 落码规则名，取值面 = deploy/alert/prometheus/rules/*.yml） | arena.yml / prometheus-rules-checkout.yml |
| `expected_alerts` | 告警身份细节：alertname/severity/firing 依据/`labels_frozen`（C-6 冻结标签集，digest 预计算输入） | arena.yml 头注、C-6 |
| `recovery` | `criteria`（复原判据逐条）+ `restore_probe`（场景专属恢复探针）；S1/S2 明确不复用 `*_current==0` | M3-17 / AM2 §6.7 |
| `timing` | 注入预热 / for 持续 / 最大 firing 等待 / 最大 resolved 等待 / 清理超时（秒） | M3-17"评测时间参数显式化" |

文末 `scenario_map_export_mapping` 把 M2-24 ExportV1 的 15 个字段分成四组：
**本注册表预登记**（scenario_id/target/expected_root_cause/expected_symptom_codes）、
**激活时产生**（activation_generation/payload_digest/created_at/alert_identity_digest/alert_fingerprint）、
**AM2 管线回填**（incident_id/incident_generation）、**AM2 可空**（run_id/report_id）。

## 四、五场景总览

| # | 注入 | 期望根因（component / fault_type / reason_code） | 期望症状 | 复原判据核心 |
|---|---|---|---|---|
| S1 | flagd `paymentFailure` → `"0.5"` | payment / BUSINESS_ERROR_RATE / PAYMENT_CHARGE_FAILURE | `checkout`（page，ticket 可同批） | flag 归位 `"0"` + 告警 resolved + 错误率回落预算内 |
| S2 | flagd `paymentUnreachable` → `"on"` | payment / DEPENDENCY_UNREACHABLE / PAYMENT_SERVICE_UNREACHABLE | `checkout` | flag 归位 `"off"` + 同上 |
| S3 | F1 幂等失效（chaos 流量跳过幂等） | order-arena / IDEMPOTENCY_BYPASS / DUPLICATE_CREATE_SAME_INTENT | `ArenaDuplicateOrders` | 修复算法 + `oa_duplicate_orders_current==0` + 无残留 firing + 台账清平 |
| S4 | F2 状态回跳（绕状态机直写目标订单） | order-arena / ILLEGAL_STATE_TRANSITION / STATE_ROLLBACK_DIRECT_WRITE | `ArenaIllegalTransitions` | 修复算法 + `oa_state_violations_current==0` + 同上 |
| S5 | F3 超时未知（pay 挂起、订单停 CREATED） | order-arena / UNCERTAIN_TIMEOUT / PAYMENT_OUTCOME_UNKNOWN | `ArenaOrderStuck` | 对账收敛 + `oa_stuck_orders_current==0` + 同上 |

注：`ArenaDomainProbeDown` 是探测面故障信号，不属于任何场景期望症状，不得计入 symptom_coverage。

## 五、诚实分级：哪些是硬依据、哪些是备料提案

**文档已定**（备料单/方案明示）：五场景与编号、flagd 两个 flag 名与变体空间（14-flag 清单源码
核实）、三个语义 fault_type（IDEMPOTENCY_BYPASS / ILLEGAL_STATE_TRANSITION / UNCERTAIN_TIMEOUT）、
S2 用 flagd 不用 docker kill（v3.0r1）、执行契约全套、arena 三个告警名/标签/指标名与 AM0 Sloth
告警名（均为落码文件原文）、复原判据框架、ExportV1 字段面（M2-24 原文）。

**P5 提案**（文档未钉，本备料新定，待首批评测人工复核 + 主会话确认）：
1. `BUSINESS_ERROR_RATE` / `DEPENDENCY_UNREACHABLE` 两个语义 fault_type（填备料单"…"扩展位，
   命名对齐 §6.7 类型列）；
2. 全部五个 reason_code 取值（各方案只定了字段名没定值域，按注入机制做机制级命名）；
3. component 枚举两值及其同义词；
4. 全部 timing 初值（S1 参照 G4 实测 3m17s firing 与 DP-B03 十分钟预算；靶场侧 15s 评估间隔
   推断，均需首批校准）；
5. ExportV1 顶层 `fault_type` 的值域裁定：本注册表取语义枚举、故障族代号单列 `chaos_family`
   （理由：S1/S2 无故障族，需与靶场场景同构；告警标签面消费的是 F1/F2/F3）。若主会话裁定
   ExportV1 顶层 fault_type = F1/F2/F3，只需调整映射表一行，场景数据不变。

## 六、给评分器实现的提醒

- 匹配语义逐条实现 matching_rules M-01~06，并用各条目 `negative_examples` 与全局
  `negative_samples` 做单测回归面（M-04：NO_MATCH 不抛错、原值留档）。
- 首批评测全量人工复核（§6.4）前，`status` 保持 DRAFT_PENDING_FIRST_BATCH_REVIEW；
  校准产出的新白名单 = 递增 `lexicon_version`，不得原地改 v1（评分可比性）。
- 场景文件迭代同理走 `registry_version` 递增；首轮跑批若发现 timing 不合适，在首批校准中
  一并定值并记录变更理由。

## 七、执行中发现并提请核对的分歧（不改既有文档，留给主会话）

1. **F2 指标名 文档-落码分歧**：AM2 技术方案 §6.6（行 202）写 `oa_illegal_transitions_current`，
   落码 arena.yml（M2-23）实际用 `oa_state_violations_current`。本备料**以落码为准**，
   场景 S4 的恢复判据与告警表达式均按落码值书写。
2. **ExportV1 顶层 fault_type 值域**：见 §五-5。
3. flagd 开关方式文档为"改 demo.flagd.json + 重启 flagd 或 configurator UI"（AM0 调研 v1），
   FlagdScenarioDriver 的执行面（API 热切换 vs 文件改写）属 M3-17 落码裁定，本备料只登记
   flag/variant 与 BA-19 精确改写纪律，不预设机制。

## 八、扩容政策（AM3 P-33 联动）

新增场景 = 递增 registry_version + 按需在词典**出新版**补 fault_type/reason_code/component
（首批刻意不预放"8 类故障"全集值——枚举写死是 §6.7 冻结面，未落场景的枚举值只会稀释
NO_MATCH 的判别力）。组合故障、负载类故障（emailMemoryLeak/kafkaQueueProblems 等余下 12 个
flagd 开关）进 AM3 后续批次另议。

---

### 结论摘要（P5）

`deploy/alert/eval/` 落两个 yml + README（零既有文件改动、零代码）。①同义词词典
v1：fault_type 5 值（3 个备料单明示 + BUSINESS_ERROR_RATE/DEPENDENCY_UNREACHABLE 补齐
S1/S2）、reason_code 5 值、component 2 值，各带中英同义词白名单与反例；匹配语义冻结为
规范化后全串精确等值，白名单外=NO_MATCH 留档不抛错。②场景清单 v1：S1~S5 注入参数/期望
症状/期望根因/复原判据/时间参数全预登记，症状编码=落码 alertname，执行契约对齐 M3-17，
字段面映射 ScenarioMapExportV1 四组（预登记/激活/回填/可空）。两文件均 YAML 解析校验通过。
诚实标注提案分级：reason_code 取值、AM0 两个 fault_type、timing 初值、ExportV1 顶层
fault_type 值域为本备料提案待主会话确认；另报 F2 指标名文档-落码分歧（oa_state_violations_
current vs 方案行 202，已按落码为准）。
