# 3.3 告警详情补齐（关联告警 + 合并时间轴） — 测试截图文档

- 批次：前端产品化 · 已收官页面缺口复盘（方案 v1 §3.3 Wave2 两缺口；定时自驱循环第 10 轮）
- 日期：2026-09-17
- 环境：195 真机（compose web 127.0.0.1:8090，SSH 隧道 18090 访问）
- 路由：`/alerts/{incidentId}`（侧栏「告警」→ 行详情）
- 缺口定位：§3.3 六项中「结论确认/驳回」「重新排查」「问一问」已由前批交付；本批核对确认 **关联告警（related）** 与 **合并时间轴（timeline-merged）** 两项后端无端点、前端无区块——本批补齐
- 新后端：`GET /api/v1/incidents/{id}/related`、`GET /api/v1/incidents/{id}/timeline-merged`（IncidentRelatedController，新文件）。**零迁移**（incident/alert_event/change_event/drill_job 全部既有表既有授权）

## 一、业界调研留痕（UI-IND-1）

| 参照物 | 实践要点 | 本批对齐 |
| --- | --- | --- |
| PagerDuty Related Incidents | 事件详情必列同签名历史与同服务并发，供批量静默/根因联想 | 同 incident_key 历史 episode（复发/重开史）+ 同服务近 24h 并发，行点击跳详情 |
| incident.io / Rootly Timeline Links | 时间轴不止告警：变更、演练等外部事件拉入同一轴，变更-告警同窗是根因第一线索 | 合并轴=告警（alert_event）+ 同服务变更（change_event，episode 窗口 ±1h）+ 演练（drill_job 关联本事故或同窗），时间归一排序 |
| Grafana Annotations | 变更/发布以注解层叠加监控视图 | 变更以「变更」类型徽章入轴，窗口口径在页签 meta 明示 |
| Moogsoft/得物 Troubleshooter | 同服务并发告警聚类归因 | sameService24h 以 substring(incident_key) 服务键归组，键缺失不放宽为全量 |

**只可多不可少**：§3.3 六项至此全齐（确认驳回/重查/时间轴合并/关联告警/问一问/证据链）；调查事件（rca_event）不入合并轴——已由「调查」页签与 3.17 Trace 页签承载，避免同信息三处重复（README 明示该取舍）。

## 二、截图清单

| # | 文件 | 区块/内容 | 数据源 | 对账 |
| --- | --- | --- | --- | --- |
| 69 | 69-告警详情-关联告警区块.png | 概览页签「关联告警」：同键历史（空态"同告警键无其他 episode"）+ 同服务 24h 并发（空态"无并发告警"）——该事故（ArenaOrderStuck @ order-arena）真实无同键复发与并发，空态口径如实 | `GET /related` | SQL 同键=0、同服务 24h=0 ✔ |
| 70 | 70-告警详情-合并时间轴.png | 时间线页签：筛选组（全部/告警/变更/演练）+ 窗口口径 meta + 6 条事件垂直轴（触发红/恢复绿，徽章中文） | `GET /timeline-merged` | count=6=SQL（3 触发+3 恢复）✔ |
| 71 | 71-告警详情-时间轴筛选变更空态.png | 切「变更」筛选：诚实空态（窗口内无同服务变更）——change_event 全部属 control-app 服务（135 条，最新 09-10）与本事故服务/窗口不交 | 同上 | 变更筛选 0 条=SQL ✔ |

UI 断言（Playwright 真机跑）：时间线 6 条、变更筛选 0、告警筛选 6、控制台**零错误**。

## 三、SQL 对账（2026-09-17 真机，INC=f4cf44b2…）

```sql
select count(*), count(*) filter (where status='firing') from alert_event
 where incident_id='f4cf44b2…' and starts_at is not null;      -- 6 | 3 = 合并轴 6 条（3 触发 3 恢复）
select count(*) from incident where incident_key =
 (select incident_key from incident where id='f4cf44b2…')
   and id <> 'f4cf44b2…';                                       -- 0 = 同键历史空态
select count(*) from incident where id <> 'f4cf44b2…'
   and substring(incident_key from 'service=([^|]+)')='order-arena'
   and first_seen_at >= now() - interval '24 hours';            -- 0 = 同服务并发空态
select count(*) from change_event where service='order-arena';  -- 0 = 变更组空态原因
select count(*) from drill_job;                                 -- 1（09-13，窗外）= 演练组空态原因
```

窗口口径实测：from=ep−1h、to=resolved+1h，端点应答 window 字段与 SQL 一致。

## 四、验收（方案 §6 逐项）

1. 零错误数据：合并轴条数/分组成与 SQL 对账一致；空态全部有 SQL 依据 ✔
2. 零裸枚举：事件类型经 zh.js `TIMELINE_KIND_ZH`；筛选按钮全中文 ✔
3. 零可点必失败：关联行点击跳详情；筛选按钮纯前端无失败面 ✔
4. 空态即引导：同键/并发/变更组空态均有口径文案（"无其他 episode"/"无并发告警"/窗口 meta）✔
5. 零迁移；写面零新增（全 GET）✔

## 五、如实记录（真数据铁律执行过程）

- 验收期间曾尝试走真接口发起一场演练以点亮合并轴 DRILL 组——被系统 **SAFE-04 安全闸如实拒绝**（`LAUNCH_DISABLED`：演练启动面已关闭，推进链未交付，需 `app.drill.launch-enabled` 显式重开）。未绕过、未造假；DRILL 组以空态交付，README 留痕。
- 同键复发史全网 0 条（无同 incident_key 多条）；同服务并发对（pa3svc 两条 09-15 13:44）均在 24h 窗外——空态皆因真实窗口未命中，非功能缺失。

## 六、遗留（如实记录）

- 合并轴的 **环比双窗**/变更自动关联评分（哪些变更"更可能"相关）待后续批次（纯 SQL/规则增量）。
- drill 启动面重开后（SAFE-04 交付），新演练落窗将自然点亮 DRILL 组。
