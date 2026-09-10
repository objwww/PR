# 告警-增强线 Tool / MCP / RAG / Skill 技术方案 v1.0（2026-09-10）——**已被 v1.1 取代（`告警-增强线-Tool-MCP-RAG-Skill技术方案-v1.1.md`，调研重写版），本文仅留档**

> 定位：核心生产闭环（EX 线 + R7）之后的**能力增强层**。本文只出设计——**编码硬门：EX 线 13/13 卡收口且 C 门验收通过之前，本方案一行代码不落**（用户 2026-09-10 裁定："不收口不编码"）。
> 上游：`docs/告警系统-部署隔离与Tool-MCP-RAG-Skill演进方案.md`（调研与方向，本文不落码前以其为事实底）、主计划 v1.2 R8/R9/R14 行、ABC v2.0 §五（范围两分层）与 §七（测试分层与防假绿纪律，本方案全部继承）。

## 〇、四件事的一句话定义与先后逻辑

| 增强 | 一句话 | 依赖谁 | 主计划行 |
|---|---|---|---|
| **Tool 扩展** | 给 Agent 增加取证工具（更多 PromQL 模板、日志/变更查询面），走现有 ToolGateway 统一入口+账本 | 已有 ToolGateway/ActionEnvelope（EX-A0 类型化透传已落） | 随 R7/B 线自然长，不单列工期 |
| **RAG** | 把 runbook/历史报告/已结案 RCA 建成可检索索引，调查结果作为**参考证据**进 Agent 上下文，不替代当下现场证据 | pgvector（deploy/pgvector 已备）+ embedding 供应商（百炼 dashscope 在役）+ R7 LLM 在环 | R8（7~10d） |
| **Skill** | 把成功调查轨迹固化为版本化、可评测、可晋升/退役的调查方法 | R7 三角色真实成功轨迹 + 盲评门 | R9（8~12d） |
| **MCP** | 把现有 ToolGateway 按 MCP 协议对外适配，**不新建第二条绕开治理的工具路** | ToolGateway 稳定后 | R14（4~6d，spike，可选） |

依赖顺序即排期顺序：**Tool（随 R7 长）→ RAG（A 门后）→ Skill（B 门后）→ MCP spike（C 门后，可选）**。

## 一、Tool 扩展（不单独设卡，随 R7 生长）

- 新工具 = 实现 `ToolGateway` 端口 + 入 `infrastructure/tool/`（TOOL_DIGEST 自动覆盖）+ `ToolArgsValidator` 参数白名单 + 账本五缝语义（EX-A4a 已修）自动继承。
- **纪律**：任何新工具必须过 ActionGuard 同一入口（预算/租约/限长/准入），禁止旁路直连；fixture 实现只允许 test/eval profile，production 装配零假件（EX-B2 已立法）。
- 验收随宿主卡走，无独立门禁。

## 二、RAG 技术方案（R8，A 门后开工）

### 2.1 索引范围（三库分立，权限与新鲜度各异）

| 库 | 内容 | 来源 | 更新 |
|---|---|---|---|
| runbook 库 | 人工维护的排查手册 | docs/runbook/（待建，人工录入） | 人工提交触发重建 |
| 历史 RCA 库 | 已 RESOLVED incident 的最终报告+Claim 集（含 EXCLUSION） | PG rca_report/claim 投影 | incident 关闭时追加 |
| 判例库 | 评测中确认的根因-证据模式（盲评金标不含——防泄漏，HOLDOUT 永禁入库） | eval_case_result 中 hit=true 的 Case | 评测批次结束后追加 |

### 2.2 检索与消费

- 向量化：dashscope text-embedding（在役供应商，零新增依赖）；pgvector IVFFlat，维度按供应商定死进迁移。
- 消费点：R7 三角色的 prompt 上下文，以 `reference_evidence` 类型注入——**与现场证据分层**（Observation/Findings/Claim 三层不变，RAG 结果只进 Findings 参考区，永不允许单独支撑 ROOT_CAUSE）。
- 召回质量进评测矩阵（主计划 §四）：检索命中率作为模型×提示词×Skill 矩阵的一维。

### 2.3 验收

- 回放契约：同快照重放检索结果可复现（索引版本号入 Run 配置 digest）。
- 防假绿：检索为空如实报 ABSENT，禁止静默降级为无参考继续（与 B2 ABSENT 语义一致）。

## 三、Skill 技术方案（R9，B 门后开工）

### 3.1 版本域与状态机（沿用演进方案 §十，落为 schema）

```
DRAFT → EVALUATING → ACTIVE → DEPRECATED → RETIRED
         ↘ REJECTED ↗（盲评不通过）
```

- Skill = { id, version, 适用告警模式（selector）, 调查步骤 DAG（有界）, 产出契约, 来源轨迹引用 }；候选 Skill 只在 EVALUATING 态参评，**晋升 ACTIVE 必须过盲评门**（主计划 D5 行：不过盲评不得晋升）。
- 原料：R7 真实成功调查轨迹（root_cause_hit=true 且人工复核确认）——**没有真轨迹不造 Skill**，防"越用越自信"（演进方案 §十一 的考题照用）。

### 3.2 自动晋级的开放节奏

1. 第一阶段只支持人工晋级（EVALUATING→ACTIVE 人工签字+盲评证据）；
2. 自动晋级（连续 N 批参评达标自动升）在人工晋级跑满至少 20 个 Skill 版本后再评估——届时以盲评数据说话。

## 四、MCP spike（R14，C 门后，可选）

- 范围：现有 ToolGateway 的 MCP server 适配层（协议握手/工具清单/调用映射），**单点 spike 验证兼容性即可**；治理面（预算/租约/账本/准入）全部留在 ActionGuard 内侧，MCP 只是协议皮。
- 否决条件：spike 发现适配必须绕开治理才能工作 → 砍，记录理由，不硬上。
- 不卡"搭建完"DoD（主计划已标可选）。

## 五、排期与硬门

```
EX 线 13/13 收口 + C 门 ──硬门──▶ 本方案编码解禁
里程碑 A（LLM 入环）───────▶ RAG 落码窗口
里程碑 B（多 Agent 闭环）───▶ Skill 落码窗口
C 门之后 ────────────────▶ MCP spike（可选）
```

- 工期沿用主计划：R8 7~10d / R9 8~12d / R14 4~6d，EX 收口后按当时实情重估。
- 全程继承 ABC v2.0 §七：L0/L1 本地、L2 只能 195 真机、防假绿六条（含"SUCCEEDED≠绿""容器 up≠绿"）。

## 六、明确不做（防 scope 蔓延）

- 不引入 Redis/MQ（2026-09-10 已裁定：PG SKIP LOCKED+同事务够用，触发再评估的条件=多 worker 横扩且 PG 锁竞争实测成瓶颈，届时首选 Redis Stream 而非 RabbitMQ/Kafka）；
- 不新建向量库服务（pgvector 复用）；
- 不做 HOLDOUT 入库、不做绕过盲评门的 Skill 晋升通道、不做第二条工具调用路。
