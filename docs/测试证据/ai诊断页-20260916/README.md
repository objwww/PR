# 3.4 AI 对话/诊断页 — 测试截图文档

- 批次：前端产品化 · 波次4 残留（方案 v1 §3.4；定时自驱循环第 2 轮）
- 日期：2026-09-16
- 环境：195 真机（compose web 127.0.0.1:8090，SSH 隧道访问）
- 路由：`/diag`（侧栏「工作台 → AI 诊断」）
- 说明：诊断会话后端（v1 引用式五问 + v2 自由问答 + diag_session 落库）由路线 v2 批次（64a1e686/63c9a1ca）先行落地；本批交付**独立诊断页前端** + 统计读面 + 三处真数据修复

## 一、业界调研留痕（UI-IND-1）

| 参照物 | 实践要点 | 本页对齐 |
| --- | --- | --- |
| Datadog Bits AI「Ask AI」 | 告警详情内嵌问 AI 卡，引用式回答（附证据来源） | 保留详情页问一问卡；独立页每条回答标注来源类型（快捷问=真源 SQL 组装 / 自由问=真模型+账本锚），meta 行显示提问人/时间 |
| 观测云 Obsy AI / Agent 进群 | 告警上下文自动带入 AI 分析；回答=结构化结论而非数据 dump | 左栏上下文卡（严重级/状态/服务/最近事件/接收次数）随选中事件切换；答案=人读结论句 |
| Bonree Sage AI 诊断统计 | 诊断触发量/成功率/模式分布量化 AI 运营 | 顶栏统计行：累计问答/涉及事件/今日新增/自由问占比（`/api/v1/diag/stats` 真账本聚合） |
| 虾评/PromptLayer 版本可追溯 | 问答留痕可回放 | 全部落库 diag_session（哈希账本纪律同源），刷新即回放历史 |

**只可多不可少**：五词表快捷问 + 自由追问 + 会话回放 + 统计行四件套齐备，且比业界参照多出"拒绝原因中文化透出"（如"该事件尚无调查记录，自由问暂不可用——先完成一次调查以建立账本锚"）。

## 二、截图清单

| # | 文件 | 页签/内容 | 数据源 | 对账 |
| --- | --- | --- | --- | --- |
| 45 | 45-ai诊断页-首屏-统计上下文历史.png | 统计行（3/2/3/0%）+ 事件上下文卡（P0 告警中 InjScanAblation）+ 空态口径 | `/api/v1/diag/stats` + `/api/v1/incidents` | 见下 SQL ✔ |
| 46 | 46-ai诊断页-快捷问实时问答.png | 点击快捷问「这个告警影响什么？」→ 实时问答入流（统计 3→4） | `POST /diag`（真源 SQL 组装） | 答案与服务/状态/接收次数与 incident+labels 一致 ✔ |
| 47 | 47-ai诊断页-自由问输入与历史流.png | 自由问输入（500 字上限）+ 快捷问按钮行 | —（输入态帧） | — |

## 三、SQL 对账（2026-09-16 真机）

```sql
select count(*) from diag_session;                                  -- 4  = 面板"累计问答 4"
select count(distinct incident_id) from diag_session;               -- 3  = 面板"涉及事件 3"
select count(*) from diag_session
 where created_at >= date_trunc('day', now());                      -- 4  = 面板"今日新增 4"
select count(*) from diag_session where question_key = 'FREE';      -- 0  = 面板"自由问占比 0%"
```

## 四、本批三处真数据修复（对既有诊断面的审计结论）

1. **坏 SQL 修复（生产 bug）**：`DiagSessionController` 的 impact 问与自由问事实块引用了不存在的 `incident.alertname/service` 列（BadSqlGrammarException 经 /error 转发被 `denyAll` 映射成 403 forbidden，掩盖了真实错误）——按 `IncidentQueryReader` 契约改为从最新一条 `alert_event.labels` jsonb 提取（service 兼容 service/service_name 两键）。修复后 impact 问返回真值："服务=ablation-b；状态=FIRING；首次发生=2026-09-16 04:52；累计接收 1 次"。
2. **history 字段透出**：问答历史补 `question_key`/`answer_refs` 两字段（前端类型徽章与模型/Token 引用需要）。
3. **列表主键对齐**：诊断页事件选择器改用 `/api/v1/incidents` 行的真实主键 `incidentId`。

## 五、验收（方案 §6 逐项）

1. 零错误数据：统计四项与 SQL 对账一致；快捷问答案与库内 incident/alert_event 真值一致 ✔
2. 零裸枚举：问答类型/拒绝原因经 `zh.js`（DIAG_TYPE/DIAG_REJECT）渲染 ✔
3. 零可点必失败：未选事件时快捷问与提问按钮禁用；REJECTED 原因中文化提示 ✔
4. 空态即引导：无历史时空态写明"点击下方快捷问开始……问答落库后可回放" ✔
5. 无新增迁移；权限沿 `/api/v1/**` OPERATOR 既有矩阵 ✔

## 六、遗留（如实记录，不造假收官）

- **自由问真模型链路**：请求已能到达模型调用点，但账本写入被拒（`MODEL_CALL_FAILED:LEDGER_WRITE_FAILED`——诊断问答的 run/task/attempt 归属锚与 rca_model_call 落账键位冲突）。这是路线 v2 台账明示的"v2 接模型待 run_id 归属评审"事项，属架构裁定，待评审定锚后即可点亮；页面已如实渲染该拒绝原因。
