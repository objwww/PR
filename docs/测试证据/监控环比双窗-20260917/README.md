# 3.10 补强·性能概览环比双窗 + 通知/静默联动核对 — 测试截图文档

- 批次：前端产品化 · 已收官页面缺口复盘（收尾双项；定时自驱循环第 12 轮）
- 日期：2026-09-17
- 环境：195 真机（compose web 127.0.0.1:8090，SSH 隧道 18090 访问）
- 路由：`/monitor`（侧栏「系统 → 监控」）
- 新后端：`GET /api/agent-ops/perf-trend`（当前滚动 24h vs 上一 24h，窗口不重叠；零迁移）

## 一、业界调研留痕（UI-IND-1）

| 参照物 | 实践要点 | 本批对齐 |
| --- | --- | --- |
| Datadog LLM Observability | 调用/Token/成本/延迟/错误五指标 + 环比窗口对比 | 五指标双窗表：当前 vs 上一滚动 24h + 环比百分比 |
| Grafana/PagerDuty 事件复盘惯例 | 「变好还是变坏」以语义色表达（坏上升=红、改善=绿） | 环比语义色：成本/延迟/失败上升=红（--sev-p0）、下降=绿（--ok）；调用/Token 中性灰 |
| Langfuse Metrics | 失败单列计数不与延迟混算 | 失败次数独立行，当前窗有失败即红字提示（perf-bad） |

## 二、截图清单

| # | 文件 | 区块/内容 | 数据源 | 对账 |
| --- | --- | --- | --- | --- |
| 75 | 75-监控-性能概览环比双窗.png | 「模型调用 · 性能概览（近 24h）」：调用 19 vs 253（-92%）、Token 5.1 万 vs 74.9 万（-93%）、成本 0.3079 vs 4.1983 元（-93% 绿）、平均延迟 2.9 秒 vs 5.5 秒（-47% 绿）、失败 0 vs 12（-100% 绿）；同屏分层延迟/成本归因口径一致（19=19） | `GET /agent-ops/perf-trend` | SQL 双窗逐项一致（见下） |

UI 断言（Playwright 真机跑）：环比表 5 行渲染、控制台**零错误**。

## 三、SQL 对账（2026-09-17 真机）

```sql
-- 当前窗
select count(*), sum(cost_micros), count(*) filter (where state='FAILED'),
       round(avg(latency_ms)) from rca_model_call
 where created_at >= now() - interval '24 hours';
-- 19 | 307854 | 0 | 2868   = 端点 current 19/307854/0/2867（avg 差 1ms=round 口径）
-- 上一窗
select count(*), sum(cost_micros), count(*) filter (where state='FAILED'),
       round(avg(latency_ms)) from rca_model_call
 where created_at >= now() - interval '48 hours'
   and created_at < now() - interval '24 hours';
-- 253 | 4198350 | 12 | 5451 = 端点 previous 253/4198350/12/5450
```

## 四、通知渠道与静默规则联动核对（第二收尾项，核对结论：已覆盖，零开发）

- **静默窗口**：V125 `notify_silence` 真表 + 通知页「通知静默窗口」区块（创建表单：原因必填/生效窗口 + 生效中列表 + 空态口径）——PagerDuty maintenance window / Grafana silence 同律，早已落地。
- **静默执行链**：`alert_inbox` SUPPRESSED 状态 13 条真实数据，通知页投递状态渲染「已抑制」（info 徽章）——静默→抑制→不再通知的闭环真实在跑。
- **渠道管理**：配置中心「通知渠道」页签复用 `/api/duty/channels` 启停+测试发送（3.6 批交付）。
- 结论：**渠道管理↔静默规则↔通知抑制三面数据链已闭环，无缺口**；核对留痕即本节。

## 五、验收（方案 §6 逐项）

1. 零错误数据：双窗五指标与 SQL 逐一对账一致（avg 差 1ms=round 口径，已复核）✔
2. 零裸枚举：环比语义色+中文指标名，无枚举上屏 ✔
3. 零可点必失败：本批无新增按钮（纯读面）✔
4. 空态即引导：上一窗口无数据时环比「—」（分母 0 保护）✔
5. 零迁移 ✔

## 六、遗留（如实记录）

- 无新增遗留。环比窗口固定滚动 24h；自定义窗口选择器（7d/30d）待后续按需增量。
