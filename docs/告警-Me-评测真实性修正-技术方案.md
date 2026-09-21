# 告警 M-e：Agent/多Agent/Tool/MCP 评测真实性修正——技术方案 v1

> 2026-09-20 立项。调研与详细设计底稿 = `research/agent-eval-audit-20260920/REPORT.md`
> （含 10 项发现 F01–F10、修改域 D01–D09、测试 ID 矩阵、外部一手调研对照表；
> 152 项本地测试+4 项探针复现为证据基线）。本文档是任务拆解与验收冻结面，
> 详细修改步骤以 REPORT §四为权威，不复制粘贴。
> G1 门：用户 2026-09-20 指令「阅读这个方案，调研并执行」= 方案获准执行。

## 1. 核心问题

现有评测体系对 RCA 结果（根因三元组、症状 TP/FP/FN）已有扎实基础，但**行为面评测存在真实性缺口**：
MCP 结构化结果被误报为远程故障（F01，已复现）、安全评分只在"已产出报告"时生成且
`registered=true` 硬编码（F02）、六维门/质量门未接入主跑批链（F03）、缺 token 证据仍放行
金丝雀资格（F04，已复现）、pass@k 命名与通行定义相反（F09）。

北极星关系：北极星=评测真实命中率。若评测链本身会误报、漏评、假放行，则 0.92 类读数的
可信度没有地基——本阶段为"命中率数字可信"铺路，是北极星的度量基座工程。

## 2. 任务拆解（批次 A 先行）

| 任务 | 内容 | 对应 REPORT | 验收 | 依赖 |
|---|---|---|---|---|
| ME-T01 | D02 MCP 结果契约修复：structured payload 贯通、错误分类、超限口径、list_changed 接线核验 | §四 D02 | MCP-01～06 测试绿 | 无 |
| ME-T02 | D03 安全评分独立化+门贯通：终态收尾评分、三类拒绝消费、usageMissing→INCONCLUSIVE、EvalCompareService 单门权威 | §四 D03 | SAFE-01～09 测试绿 | 无 |
| ME-T03 | D01 可靠性指标命名修正：pass@k→pass^k/全轮成功率、planned vs completed 分母、micro/macro 分称 | §四 D01 | ST-01～05 测试绿+前端构建 | 无 |
| ME-T04 | D04 轨迹投影与证据真实性指标（BehaviorEvaluation 值对象） | §四 D04 | EV-01～06 | ME-T02 |
| ME-T05 | D05 死循环评测（无进展定义收紧+指标出数） | §四 D05 | LOOP 确定性子集 | ME-T04 |
| ME-T06 | D06 上下文漂移评测（事实表+语义检查） | §四 D06 | CTX 确定性子集 | ME-T04 |
| ME-T07 | D07 多 Agent 协作评测（三臂对照） | §四 D07 | MA 机制子集 | ME-T04 |
| ME-T08 | D08 行为数据集注册（agent_behavior_v1） | §四 D08 | TOOL-01～08 机制子集 | ME-T04 |
| ME-T09 | D09 裁判校准与统计纪律 | §四 D09 | JUDGE/STAT 子集 | ME-T04 |
| ME-T10 | 批次 D：行为/安全/质量门进入候选发布资格 | §六 | 发布验收四门 | ME-T01～09 |

执行序：ME-T01 → ME-T02 → ME-T03 →（批次 B/C 按序）。部署统一走部署窗
（当前 195 有 drill 结案守望与并行会话，部署前必查 `eval_run RUNNING` 无活批）。

## 3. 类设计（批次 A 增量，DDD 分层不变）

- `McpToolResult`（application/mcp）：`structuredPresent` 布尔 → 携带实际 structured payload；
  兼容现有 `text(...)` 工厂。
- `SdkMcpServerClientFactory`（infrastructure/mcp）：SDK 边界归一化 null/empty content，
  完整传出 structured。
- `McpToolInvoker`（application/mcp）：结果选择策略（结构化优先/文本投影/能力错误不伪装
  远程故障）；`isError=true` 分类落账；超限按真实下传字节计。
- `SingleCaseScorer`（eval/application）：安全观测移至案例终态收尾（无报告/超时/结构拒绝
  同评）；注册状态三态（已知注册/未知工具/证据缺失）。
- `QualityGate`（eval/domain/service）：`usageMissing=true` → 依赖费用的门 INCONCLUSIVE。
- `EvalCompareService`（eval/application）：readiness+安全+成本门单一消费入口。
- `EvalQueryService` + `EvalRunDetailView.vue`：pass^k 命名、planned/completed 分母、
  micro/macro 分称。

不新建跑批平台；`EvalGateRunner` 若无真实调用方则标明仅领域组件，不强接。

## 4. 实现方式要点

- 统一检查状态机：`PASS / FAIL / NOT_ASSESSED / NOT_APPLICABLE / ERROR`——缺证据与
  真实零违规必须区分；计数带分子/分母/未评数/版本。
- 幂等：同一动作重放收尾两次不重复累加（SAFE-09）。
- 门规则升版：旧 evaluation_record/comparison/review 不原地重写，历史批保持未评。
- MCP 基线锁定 2025-11-25 + 现 SDK 版本；可选能力未实现登记 NA 不冒充 PASS。

## 5. 数据流

批次 A 无新表（D04 若需 `eval_case_behavior` 表再启用实际空闲 Flyway 版本，不预占编号）。
安全观测数据源：rca_tool_invocation + policy 拒绝事件 + 工具注册版本（只读投影）。

## 6. 边界条件与不变量（诚实清单）

- 不变量：确证越权执行/跨租户读写 = REJECT，不可被根因命中或均分抵消（SAFE-05）。
- 不变量：缺 token/费用证据不得 ELIGIBLE（SAFE-06，探针已复现旧行为）。
- 不变量： trace 不完整 → NOT_ASSESSED/INCONCLUSIVE，不显示"零违规通过"（SAFE-07）。
- 残余风险：F05/F07/F08（评分语义能力、无进展定义、摘要语义校验）属批次 B，本批不修。
- 残余风险：工作树含 BA-190/191/192 已部署未提交脏集——本批编辑必须在脏集之上增量，
  禁止回退/格式化重排他会话文件。

## 7. 设计原因

方法论全部来自一手来源（REPORT §三已逐条核对）：Anthropic agent evals（结果/行为/安全/
成本分列）、τ-bench（冻结 k、pass@1/pass@k/pass^k 分称）、MCP 官方 conformance 与
2025-11-25 规范（结构化输出契约）、BFCL V3（参数语义+状态检查）、MAST（失败分类）。
OSS 证据登记随批次 B 补入 `docs/告警-OSS-证据清单.md`。

## 8. 问题与压力点

1. 批次 A 只修"评测会说谎"的缺口，不提升模型本身能力——命中率读数可能因诚实化而**下降**
   （如安全面从"只看成功报告"变为全案评），这是预期且正确的方向。
2. EvalGateRunner 去留需用户裁定（保留=补真实调用链+贯通测试；废弃=标注领域组件）。
3. 批次 C 真实模型三臂对照需要专项 token 预算，排夜间档。

## 9. 实际后果记录

- F01 复现：MCP 结构化结果被包装 REMOTE_UNAVAILABLE/NPE，账本 FAILED/TRANSPORT_UNKNOWN——
  线上会诱导 Agent 无效重试。
- F04 复现：usageMissing=true 仍 ELIGIBLE_FOR_CANARY（独立门内，未证生产放行）。
- 历史假绿批（efde9e17/44f220ef，BA-190 已修）证明"流程 SUCCEEDED≠质量通过"必须分展。

## 10. 技术债分析

不修：MCP 契约缺陷随 MCP 工具面扩大而放大每一次调用；安全面漏评使"红队批 0 违规"类
结论永久不可信；pass@k 误称会持续误导版本对比决策。修复成本随调用面扩大单调上升。

## 11. 测试用例设计

权威矩阵 = REPORT §四各 D 域测试 ID 表（MCP-01～10、SAFE-01～09、ST-01～05、EV/LOOP/
CTX/MA/TOOL/JUDGE/STAT）。批次 A 落点：`McpToolInvokerTest`（探针迁移为正确行为断言）、
`En06McpSdkSpikeTest`、`SingleCaseScorerTest`、`SafetyGateTest`、`QualityGateTest`、
`EvalBatchRunnerTest`、`EvalCompareServiceTest`、`EvalQueryServiceTest`+前端构建。
纪律：每层失败必须修复后重跑；全量 control-app 回归绿才算任务关单。

## 12. 验收标准（DoD）

- 批次 A：MCP-01～06、SAFE-01～09、ST-01～05 全绿；control-app 全量回归绿；前端
  `npm run build` 绿；探针中 F01/F04 两项迁移为正确行为回归断言。
- 部署门：部署窗执行（无 RUNNING 活批、drill 守望结清），195 冒烟=评测读面端点 200。
- 质量指标面（G2 必填）：北极星现值、LLM 在环状态、对"LLM 可信上场"的收敛说明。
