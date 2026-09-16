# 3.15 复盘补强（整改项清单 + 复盘闭环派生） — 测试截图文档

- 批次：前端产品化 · Wave 6（方案 v1 §3.15；定时自驱循环第 8 轮）
- 日期：2026-09-16
- 环境：195 真机（compose web 127.0.0.1:8090，SSH 隧道 18090 访问）
- 路由：`/postmortems`（侧栏「系统 → 复盘」）
- 基础版此前已交付（复盘材料自动汇编：影响/时间线/断言/调查/费用 + ITSM 工单草稿）；本批按方案 §3.15 补齐缺口：**整改项清单（action_items）+ 复盘闭环状态**
- 新后端：`GET/POST /api/v1/postmortems/{id}/action-items`、`POST .../action-items/{itemId}/toggle`；新迁移 **V136**（action_items 表）

## 一、业界调研留痕（UI-IND-1）

| 参照物 | 实践要点 | 本批对齐 |
| --- | --- | --- |
| Rootly Postmortem | 复盘必产 action items，逐项有 owner 与完成态，全部完成即复盘闭环 | `action_items`（V136）人工登记/勾选闭环；闭环徽章「整改中 x/y → 复盘已闭环」 |
| incident.io Postmortem | action items 闭环为派生态，不另设"复盘已闭环"开关（避免双真源） | `closed = count>0 && 全部 DONE` 服务端派生（`GET action-items` 返回 `doneCount/closed`），前端只渲染 |
| 方案 §3.15 | 事件 RESOLVED→材料自动汇编→人工编辑→整改项清单→闭环状态 | 汇编/清单/闭环全链路真数据；根因文本仍人工补写（系统不代拟，纪律保留） |

**只可多不可少**：方案清单（整改项清单/闭环状态）全齐；另有真实调查口径修复与工单草稿清单修复（见「四、本批顺带修复」）。

## 二、截图清单

| # | 文件 | 页签/内容 | 数据源 | 对账 |
| --- | --- | --- | --- | --- |
| 63 | 63-复盘-整改项清单-整改中.png | 整改项清单 2 条（1 DONE 1 OPEN）+「整改中 1/2」徽章；调查与费用=「调用 31 次：成功 31，失败 0 ｜ 模型费用 0.6394 CNY」（单事故口径） | `GET /api/v1/postmortems/{id}` + `/action-items` | SQL 对账 DONE=1/OPEN=1 ✔；31 次调用全部归属该事故 ✔ |
| 64 | 64-复盘-闭环达成.png | 勾选第二条后徽章变「复盘已闭环」（绿色），完成项划线态 | `POST .../{itemId}/toggle`（UI axios 真 CSRF 链路） | doneCount=2/2 → closed=true ✔ |
| 65 | 65-复盘-工单草稿与整改项.png | 复原 1/2 后点「生成工单草稿」：工单清单出真数据（[P2] [告警] ArenaOrderStuck @ order-arena —— DRAFT） | `POST /api/v1/itsm/incidents/{id}/draft` + `GET /api/v1/itsm/tickets` | itsm_ticket 落库 1 行 DRAFT ✔ |

UI 断言（Playwright 真机跑）：`整改中 1/2 → 复盘已闭环 → 复原 1/2` 徽章三态全部命中；控制台**零错误**。

## 三、SQL 对账（2026-09-16 真机）

```sql
select state, count(*) from action_items
 where incident_id = 'f4cf44b2-…' group by state;      -- DONE=1, OPEN=1  = 面板"整改中 1/2"
select created_by, owner, done_at is not null
  from action_items order by created_at;               -- operator / checkout-oncall / t（会话真值+done_at 随态落真值）
select count(*) from rca_model_call m join rca_run r on r.id = m.run_id
 where r.incident_id = 'f4cf44b2-…';                   -- 31  = 面板"调用 31 次"
select state, count(*) from rca_model_call group by 1; -- SUCCESS=258, FAILED=14（真值域）
select version from flyway_schema_history
 where success order by installed_rank desc limit 1;   -- 136（V136 成功）
```

## 四、本批顺带修复（三例跨轮真实缺陷，均属掩蔽型 403/口径家族）

1. **复盘「调查与费用」口径缺陷**：原查询无 `join rca_run` 过滤——统计的是**全局**模型调用却显示在单事故复盘下；且 state 值域写错（'SUCCEEDED'，真值域为 'SUCCESS'/'FAILED'）致成功/失败恒 0。修复后与 SQL 逐一对账一致。
2. **`GET /api/v1/itsm/tickets` 坏 SQL → 403**（复盘页工单清单一直空+控制台 403）：`coalesce(i.alertname,'')` 引用不存在的列（alertname 应从 incident_key 提取）→ 控制器异常 → 转发 /error → `anyRequest().denyAll()` → 403。修复后 200。
3. **PostmortemView 缺 `ElMessage` 导入**：提示路径 latent ReferenceError，补齐。

## 五、验收（方案 §6 逐项）

1. 零错误数据：整改项/闭环派生/调查口径与 SQL 逐一对账一致 ✔
2. 零裸枚举：OPEN/DONE 经勾选框与徽章渲染，无枚举直上屏 ✔
3. 零可点必失败：登记/切换/工单草稿三写面真机会话全通（curl 与 UI 双路）✔
4. 空态即引导：无整改项时提示「复盘闭环要求整改项全部完成」✔
5. 迁移 V136 成功（表级授权随建表同批授，V134 教训落实）✔

## 六、遗留（如实记录）

- **根因文本**：仍为人工区（方案 §3.15 明确系统不代拟根因），非缺口。
- **ITSM 真推送**：待外部系统配置（app.itsm.base-url），当前 DRAFT 导出语义如实。
- **CSRF 轮换纪律（脚本侧教训入档）**：登录成功即轮换并清空 XSRF-TOKEN cookie——脚本必须在每次 POST 前**重新 GET /api/auth/csrf 铸新令牌**（仅从 jar 现取会取到被清空的行）；浏览器 axios 自动重铸不受影响。
- 复盘→评测候选提名区块（latestReportId）为路线 v2 批次并行交付，本批合流部署一并带上并验证可用。
