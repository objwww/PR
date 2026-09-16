# 调查路由可视化 + 「有告警为什么没有 RCA」答疑闭环 — 测试截图文档

- 批次：前端产品化 · 用户验收反馈轮（定时自驱循环第 13 轮）
- 日期：2026-09-17
- 环境：195 真机（compose web 127.0.0.1:8090，SSH 隧道 18090 访问）
- 路由：`/config`（调查路由页签）、`/alerts/{id}`（详情页概览）
- 起因：用户验收提问「有了告警为什么没有进行根因分析？」——诊断结论为 **canary 粘性桶位放量路由**（SAFE 设计，非故障）：AI 自动调查按告警键哈希桶 vs 当前放量百分比受控灰度，决策全账本落账（canary_route_decision，23579 条真数据）；当前 FIRING 事件中 5 条 `WAITING_CAPABILITY`（桶内但降级观测/未放量），CheckoutRpcClientErrorRateHigh 在白名单内已调查 27 次
- 新后端：`GET /api/v1/routing/overview`（三段聚合：每键最新放量状态/最近决策流水/等待放量清单；零迁移零写面）

## 一、业界调研留痕（UI-IND-1）

| 参照物 | 实践要点 | 本批对齐 |
| --- | --- | --- |
| PagerDuty Event Orchestration | 路由规则可视化：哪些事件被自动化处理、为什么没有 | 「等待放量的事件」清单直答未 RCA 原因；每告警键最新放量与决策 |
| Datadog Bits AI 按服务开启 | 自动调查按受控范围开启，范围可见 | 放量百分比与决策全量可视化，说明条明示「放量由发布管线 bundle 激活驱动，告警域不设开关」 |
| Grafana 渐进放量（% rollout） | 灰度桶位/百分比直读 | 桶位 vs 放量% 并列展示；`桶内·降级观测`（BUCKETED_HOLMES）如实分态——桶位在放量内但原生执行面未就绪，降级观测不铸调查 |

## 二、交付清单

1. **配置中心第五页签「调查路由」**：等待放量事件清单（告警名/服务/原因/打开详情）+ 每告警键最新放量状态（放量%/决策徽章）+ 最近 20 条决策流水（桶位/放量/决策）。
2. **告警详情页等待原因卡**：无 run 且有 waitingReason 时显式说明「AI 尚未自动调查：等待路由放量」+ 机制解释 + 配置中心深链 + **「尝试立即发起根因分析」按钮**（复用 reinvestigate 同闸路径——放量内直接发起，未放量弹「路由未意愿」解释框，诚实拒绝不绕过 SAFE 设计）。
3. **顺手修复既有缺陷**：配置中心「告警分类」页签占比条 `barPct` 函数缺失（模板引用无定义，渲染即 TypeError——并行批次遗留），补函数后 3 条占比条正常渲染。
4. **美化**（tokens 内克制改法）：详情页/复盘页小节标题统一品牌左条；演练页摘要卡语义色（活动=品牌蓝、恢复异常>0 红）。

## 三、截图清单

| # | 文件 | 内容 | 数据源 | 对账 |
| --- | --- | --- | --- | --- |
| 76 | 76-配置中心-调查路由-等待清单与放量.png | 等待放量 5 条（全部 FIRING WAITING_CAPABILITY 事件）+ 每键最新放量（0% + 决策徽章） | `GET /routing/overview` | SQL：FIRING+waiting 5 条 ✔ |
| 77 | 77-配置中心-决策流水.png | 最近决策流水（桶位 60/45 vs 放量 0%，决策=桶内·降级观测） | 同上 | 决策值域 4 类中文渲染 ✔ |
| 78 | 78-告警详情-等待原因卡与发起入口.png | 详情页等待原因卡（机制说明+配置中心深链+发起按钮） | `/v1/incidents/{id}` 投影 | waiting_reason=WAITING_CAPABILITY ✔ |
| 79 | 79-告警详情-路由未意愿诚实解释.png | 点发起后弹「路由未意愿」解释框（未放量原因+自动补铸说明） | `POST /reinvestigate`（诚实拒绝） | redrive 同闸拒绝语义 ✔ |
| 80 | 80-复盘页-小节标题美化.png | 复盘页小节标题品牌左条美化效果 | 纯前端 | 视觉一致性 ✔ |

UI 断言：路由页签三表渲染、等待卡/拒绝弹窗文案精确命中、barPct 修复后**控制台零错误**。

## 四、SQL 对账（2026-09-17 真机）

```sql
select count(*) from incident where status='FIRING' and waiting_reason='WAITING_CAPABILITY';
-- 5 = 等待放量清单 5 行（InjScanAblation/E2EAblationFullFlow/Pa3InjProbe/Pa3CleanProbe/Pa1VerifyProbe）
select decision, count(*) from canary_route_decision group by 1;
-- BUCKETED_HOLMES 23522 / WHITELISTED 47 / CANARY_DISABLED 15 / BUCKETED_NATIVE 5（值域=字典四类）
select count(*) from rca_run r join incident i on i.id=r.incident_id
 where substring(i.incident_key from 'alertname=([^|]+)')='CheckoutRpcClientErrorRateHigh';
-- 27 = 白名单事件已被调查 27 次（放量内 vs 等待内的对照例证）
```

## 五、如实记录

- **演示口令**：应用户要求，operator 口令临时设为 `Demo#0917`（原 .env 各版本备份均在 195 /tmp/env-backup-*，`/tmp/userdemo-bk-path` 记录最近备份；并行会话 02:09 曾覆盖过一次，本轮已重设并烟测 200）。
- 放量百分比**不做页面开关**：由发布管线 bundle 激活驱动（版本中心域），告警域如实只读——不造假不越权。
- 并行会话同时段在活动（.env/构建现场有过交叉），本轮关键产物均经 class/chunk 复核后验收。

## 六、遗留

- 放量推进操作面（若需从告警域发起）属版本中心域增量，需与发布管线域共同裁定后另批交付。
