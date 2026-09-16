# 3.17 调查详情 · 调用链（Trace 瀑布）页签 — 测试截图文档

- 批次：前端产品化 · 波次4 残留（方案 v1 §3.17）
- 日期：2026-09-16（定时自驱循环第 1 轮）
- 环境：195 真机（146.56.195.225，compose web 127.0.0.1:8090，SSH 隧道访问）
- 样本 run：`786f1c55-d03c-48d0-acfd-0250b79b66d9`（SUCCEEDED，告警 5781021b）
- 接口：`GET /api/rca-runs/{runId}/trace`（本批新增，读侧真账本直出）
- 数据源：`rca_attempt` / `rca_model_call` / `rca_tool_invocation` 三账本 UNION 直出 + 事件锚点复用 `rca_event`（零新采集、零前端造数）

## 一、业界调研留痕（UI-IND-1）

| 参照物 | 实践要点 | 本页对齐 |
| --- | --- | --- |
| 观测云 Session Trace | 瀑布图铺开完整执行链路，每 Span 可见耗时/Token/状态 | 三层 span（任务尝试/模型调用/工具调用）同行呈现耗时+状态徽章，点击展开 Token 三元组 |
| Langfuse / LangSmith | 分层 span 瀑布 + 延迟分解 + 成本归因到调用 | 汇总行给调用数/Token 合计（已回报下限口径）/费用合计；明细行带单笔费用（V128/129 定价表真值） |
| Datadog APM Span Waterfall | 共享时间轴按起止比例定位、关键路径直观 | 全局时间窗（0→11.2 秒标尺）+ 三层同轴错落排布，12 次模型调用阶梯推进形态一眼可读 |
| 观测云/得物"事件时间轴叠加变更事件" | 在时间轴上叠加结论/证据/变更锚点 | 事件锚点行：结论（粉）/证据（绿）/失败·审控（红）三类 `rca_event` 刻度叠加（本样本 run 无这三类事件时该行不渲染，空态即口径） |
| AgentLens Trace 回放 | 只读回放为主，编辑回放为实验特性 | 本批交付只读瀑布回放；Time-travel 输入编辑对比列为后续增强（不造假） |

**只可多不可少**：业界瀑布要素（分层 span/耗时/Token/状态/事件叠加/成本）本页全有，且多出——哈希链事件账本锚（可信时间线）、角色（role_id）维度、微单位费用（定价表回算）、"Token 合计为已回报下限"的诚实口径提示。

## 二、截图清单（逐张标注页签/数据源/对账结果）

| # | 文件 | 页签/内容 | 数据源 | SQL 对账 |
| --- | --- | --- | --- | --- |
| 40 | 40-调用链批-摘要页签基准.png | 摘要页签（基准上下文：告警 5781021b、费用 ¥0.0211、任务 2/2） | `/api/rca-runs/{id}` 详情投影 | 基准帧，不涉及新面板 |
| 41 | 41-调用链-瀑布首屏-汇总时间轴模型.png | 调用链首屏：汇总行+时间标尺+任务尝试+模型调用 12 行 | `/trace`（rca_attempt/rca_model_call） | 任务尝试 1=1、模型 12=12、Token 26,114=26,114、窗口 11.2 秒≈11 秒 ✔ |
| 42 | 42-调用链-模型调用明细展开.png | 展开模型调用"序 0"：模型 qwen-plus、Token 1716/18/1734、费用 1,408 微单位 | rca_model_call 行 + usage jsonb + V129 成本回算 | 单笔费用与 cost_micros 一致 ✔ |
| 43 | 43-调用链-工具调用区与图例.png | 工具调用 12 行（prometheus.catalog 序 1-12，2-179 毫秒）+ 五色图例 | rca_tool_invocation（started/settled） | 工具 12=12 ✔ |
| 44 | 44-调用链-全页长图.png | 全页长图（完整瀑布证据） | 同上 | 汇总七项全对账 ✔ |

对账 SQL 与结果（2026-09-16 真机执行）：

```sql
select count(*) from rca_attempt a join rca_task t on t.id=a.task_id where t.run_id='786f1c55…';   -- 1
select count(*) from rca_model_call where run_id='786f1c55…';                                       -- 12
select count(*) from rca_tool_invocation where run_id='786f1c55…';                                  -- 12
select coalesce(sum((usage->>'total_tokens')::bigint),0) from rca_model_call
 where run_id='786f1c55…' and usage is not null;                                                    -- 26114
select count(*) from rca_model_call where run_id='786f1c55…'
 and (usage is null or usage->>'total_tokens' is null);                                             -- 0
select sum(cost_micros) from rca_model_call where run_id='786f1c55…';                               -- 21146
```

## 三、验收（方案 §6 生产就绪门槛逐项）

1. 零错误数据：七个面板数字与 SQL 逐一对账通过（见上）✔
2. 零裸枚举：span 状态经 `src/dict/zh.js`（ATTEMPT_STATE/LEDGER_STATE/spanStateTagType）渲染，界面全中文（任务尝试/模型调用/工具调用/成功/失败·可重试/待回执/结果未知）✔
3. 零可点必失败：页签懒加载，接口 404/失败显示中文错误+重试按钮；未知 run → 404 中文提示 ✔
4. 空态即引导：无账本行时 EmptyState 说明口径（"排队中尚未执行或旧版 run 无账本记录；本页只呈现真实账本数据"）✔（本样本有数据，空态由 41-44 截图外的代码路径保障）
5. 无新增迁移（读面零 DDL），flyway 停留 V131 ✔
6. 权限：`/api/rca-runs/**` OPERATOR 矩阵既有授权内；未登录 401 中文 ✔

## 四、测试过程记录

- 单测：`RunQueryServiceTest` 新增 2 例（trace 投影+汇总断言 / 无读面+未知 run 降级），`mvn -pl control-app test` 绿；`EventQueryControllerTest` 回归绿；`npm run build` 绿。
- 部署：overlay tar（7 文件）→ md5 对拷 → `.env` 备份 → 解包 → mvn package → compose build control-app+web → migrate（no-op）→ up -d → health 200。
- 烟测：登录后 `GET /api/rca-runs/786f1c55…/trace` 返回 200 与真实 span（首 span=NATIVE_INVESTIGATE 第 1 次尝试）。
- 截图方式：Playwright（msedge 无头）走 SSH 隧道 18090→8090，登录态真实操作逐页签截取。
- 口令纪律：截图用临时口令轮换（shot-swap 先例），验收完成后已从备份恢复原 `.env` 并重启验证 health=200。

## 五、遗留与后续

- Time-travel 输入编辑回放、MCP 协议显式追踪：业界实验特性，列为增强项（现阶段账本无 MCP 原生语义，不造假）。
- 事件锚点在"结论/证据/失败"事件较多的 run（如 4950d39b，78 条事件）上密度较高，后续可加锚点类型筛选。
