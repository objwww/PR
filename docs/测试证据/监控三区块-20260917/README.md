# 3.10 监控补齐（分层延迟·成本归因·风险审计） — 测试截图文档

- 批次：前端产品化 · Wave 6 尾（方案 v1 §3.10 Wave4 项 + §1 差距总表第 11 行缺口；定时自驱循环第 9 轮）
- 日期：2026-09-17
- 环境：195 真机（compose web 127.0.0.1:8090，SSH 隧道 18090 访问）
- 路由：`/monitor`（侧栏「系统 → 监控」）
- 缺口定位：监控页 stat 卡/趋势/主机三维/执行器/工具 TopN 已落地；§3.10 明确的「性能概览/分层延迟/成本归因/风险审计」中，`latency-layers` 端点波次1已建但**前端从未消费**，成本归因与风险审计两端缺失——本批补齐
- 新后端：`GET /api/agent-ops/costs`、`GET /api/agent-ops/risk-events`（新增）；`latency-layers` 扩任务层（三层→四层）。**零迁移**（全部既有账本表聚合）

## 一、业界调研留痕（UI-IND-1）

| 参照物 | 实践要点 | 本批对齐 |
| --- | --- | --- |
| Datadog LLMObs/Bits AI | Agent 可观测三板斧：分层延迟（端到端→每跳 p50/p95）、成本按模型归因、护栏审计流 | 四层延迟表（端到端/任务/模型/工具）；按模型成本占比条；风险审计流 |
| Langfuse | tracing 分层延迟 percentile + generation→model→cost 聚合 | p50/p95=percentile_cont SQL 直出；cost 按 requested_model 聚合（V128 定价回算真值） |
| 观测云 AI 可观测 | LLM 调用链分层视图（session→trace→span） | 任务层口径=终态就绪→落定（rca_task ready_since→updated_at），面板 meta 明示口径 |
| Rootly/incident.io 动作审计 | AI 每个动作有审计锚，可跳转原始轨迹 | risk-events 每行带 run 锚「查看 run」跳调查详情；无锚行显式「—」 |
| 得物 Troubleshooter/AegisOps | 危险操作人工闸门+风险分级审计 | Guardian 复核（裁决+理由）/审批拒绝（动作+风险级）/隔离与死信 三源统一流 |

**只可多不可少**：§3.10 Wave4 清单（性能概览=stat 卡已有/分层延迟/成本归因/风险审计/执行器活性已有）全齐；无价调用「不计入总额」口径、审批拒绝空态、run 锚深链为业界对齐增量。

## 二、截图清单

| # | 文件 | 区块/内容 | 数据源 | 对账 |
| --- | --- | --- | --- | --- |
| 66 | 66-监控-分层延迟与成本归因.png | 四层延迟表（端到端 2 次 16.6s/16.7s；任务 4 次 16.5s/16.7s；模型 18 次 1.5s/7.3s；工具 12 次 59ms/232ms）+ 成本归因（总额 0.3079 CNY、无定价 4 次明示、qwen3-max 占比条） | `GET /agent-ops/latency-layers` + `/costs` | SQL 见下节逐条 ✔ |
| 67 | 67-监控-风险审计流.png | 风险审计 10 行：Guardian 复核×4（裁决 SAFE/UNCERTAIN+理由）、隔离命中×1（注入特征告警未入调查流）、死信×5（投递终败），run 锚可点 | `GET /agent-ops/risk-events` | guardian 4/审批拒绝 0（空态如实）/隔离死信 6 ✔ |
| 68 | 68-监控-全页俯瞰.png | 监控页整体：stat 卡/趋势/主机/执行器/模型+工具/新三区块同屏 | 全端点 | 页面级无重复口径冲突 ✔ |

UI 断言（Playwright 真机跑）：四层延迟表 4 行、成本总额块、风险审计表渲染齐全；控制台**零错误**。

## 三、SQL 对账（2026-09-17 真机）

```sql
-- 分层延迟（截图 66 当窗）
select count(*) from rca_run where finished_at is not null
 and created_at >= now() - interval '24 hours';        -- 2  = 面板"端到端 完结数 2"
select count(*) from rca_task where state in ('DONE','DEAD','CANCELLED')
 and ready_since is not null and updated_at >= now() - interval '24 hours';
                                                        -- 4  = 面板"任务 完结数 4"
select count(*) from rca_tool_invocation where started_at >= now() - interval '24 hours'
 and settled_at is not null;                           -- 12 = 面板"工具 完结数 12"
select round(percentile_cont(0.5) within group (
 order by extract(epoch from (updated_at - ready_since)) * 1000))
  from rca_task where state in ('DONE','DEAD','CANCELLED')
 and ready_since is not null and updated_at >= now() - interval '24 hours';
                                                        -- 16452 = 面板"任务 P50 16.5 秒"
-- 成本归因
select sum(cost_micros) from rca_model_call
 where created_at >= now() - interval '24 hours' and cost_micros is not null;
                                                        -- 307854 微元 = 面板"0.3079 CNY"
select count(*) from rca_model_call
 where created_at >= now() - interval '24 hours' and cost_micros is null;
                                                        -- 2（截图时点 2→截图 66 时窗滚动至 4，两端一致）
-- 风险审计
select count(*) from rca_event where event_type='GUARDIAN_REVIEWED'
 and created_at >= now() - interval '7 days';          -- 4 = Guardian 复核 4 行
select count(*) from approval_decisions where decision='REJECTED'
 and decided_at >= now() - interval '7 days';          -- 0 = 审批拒绝空态（如实 0 行）
select count(*) from alert_inbox where state in ('QUARANTINED','DEAD_LETTER')
 and received_at >= now() - interval '7 days';         -- 6 = 隔离 1 + 死信 5（7 天窗内）
```

## 四、本批顺带修复与教训（掩蔽型 403 第五例）

1. **costs 端点首发 403**：SQL 写 `(usage->>'total_tokens')::long`——**PostgreSQL 无 `long` 类型**（MySQL 习惯污染，`type "long" does not exist` 异常 → /error 转发 → denyAll → 403，无业务日志可直接可见性差）。修复为 `::bigint` + 正则守卫（非数字 usage 行不炸 cast）。教训入档：**PG 数值 cast 只用 bigint/int/smallint**；新端点烟测必须 GET/POST 全打一遍。
2. **并发构建撞车**：首轮修复后运行 jar 仍是旧字节码（镜像 Created 已更新但 COPY 内容旧）——与并行会话同时各自 mvn/compose build 互踩 target/ 与层缓存。收敛流程=**源码 md5 → mvn → target jar class 串验尸 → build → 镜像 ID → 容器 class 复核 → 烟测** 七步逐环验证（本批全部命中后通过）。

## 五、验收（方案 §6 逐项）

1. 零错误数据：延迟四层/成本/风险审计与 SQL 逐一对账一致（P50 毫秒级 1ms 差为 round vs 截断口径，已复核）✔
2. 零裸枚举：风险类型经 zh.js `RISK_KIND_ZH` 渲染；层名/单位全中文 ✔
3. 零可点必失败：run 锚仅在有 runId 行渲染，点击跳 `/runs/{id}` ✔
4. 空态即引导：审批拒绝 0 条=流中不出现（其余类型在列）；成本无有价行时显式空态 ✔
5. 零迁移（全部既有账本聚合），无 .env 结构变更 ✔

## 六、遗留（如实记录）

- **环比上一窗口**（§3.10「环比对比」）：当前 24h 单窗，环比需双窗聚合，留待监控页下一批（数据源同表，纯 SQL 增量）。
- **工具成本**：内部工具无定价语义，成本归因仅模型维度（与 V128 定价表面一致）。
- 死信事件（alert_inbox DEAD_LETTER）的**人工介入动作**属值班/通知域，不在监控页承载。
