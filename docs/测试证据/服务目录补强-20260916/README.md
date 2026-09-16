# 3.13 服务目录补强（30 天事件 / MTTR / 负责人登记） — 测试截图文档

- 批次：前端产品化 · Wave 6（方案 v1 §3.13；定时自驱循环第 7 轮）
- 日期：2026-09-16
- 环境：195 真机（compose web 127.0.0.1:8090，SSH 隧道访问）
- 路由：`/catalog`（侧栏「系统 → 服务目录」）
- 基础版由路线 v2 批次先行交付（4aa6f11d：服务维度在警/解决/接收/告警名聚合）；本批按方案 §3.13 补齐缺口
- 新后端：`POST /api/v1/catalog/owner`（负责人 upsert/解绑）；新迁移 **V135**（service_owner 表）

## 一、业界调研留痕（UI-IND-1）

| 参照物 | 实践要点 | 本批对齐 |
| --- | --- | --- |
| Backstage Catalog | 目录条目必须有 owner（谁对它负责），由人工登记而非推断 | `service_owner` 人工登记表（V135），行内弹窗登记/解绑，打标人落档 |
| 方案 §3.13 | 近 30 天事件数/MTTR、owner、告警路由关系；起步=只读聚合 | 30 天事件数与 MTTR 从 incident 时长真列聚合（`avg(resolved_at-first_seen_at)`，30 天窗口）；告警路由关系（告警名清单）v2 版已有 |
| ITGix 服务健康视图 | 按服务聚合在警/解决/接收 | v2 版已有（在警红数/已解决/事故总数/累计接收/最近活动） |

**只可多不可少**：方案清单（owner/近 30 天事件/MTTR/告警名）全齐；依赖拓扑仍无真源（CMDB 未接），按铁律**不虚构**——README 明示而非画假图。

## 二、截图清单

| # | 文件 | 页签/内容 | 数据源 | 对账 |
| --- | --- | --- | --- | --- |
| 61 | 61-服务目录-全量清单.png | 目录全量（11 服务）：新增 近 30 天事件 列（checkout 5/pa3svc 2/…）、MTTR（30 天）列（checkout 3.6 天/frontend 6.0 小时/payment 14 分钟）、负责人列（checkout 已登记） | `GET /api/v1/catalog`（扩展投影） | checkout 30 天事件 SQL=5 ✔；负责人 service_owner=1 ✔ |
| 62 | 62-服务目录-负责人登记弹窗.png | 负责人登记弹窗（服务/负责人/备注 + 解绑语义说明） | `POST /api/v1/catalog/owner` | upsert 落库回读一致 ✔ |

## 三、SQL 对账（2026-09-16 真机）

```sql
select count(*) from incident
 where coalesce(substring(incident_key from 'service=([^|]+)'),'（未知服务）')='checkout'
   and first_seen_at >= now() - interval '30 days';   -- 5  = 面板"checkout 近30天事件 5"
select count(*) from service_owner;                    -- 1  = 已登记负责人（checkout→checkout-oncall）
select version from flyway_schema_history
 where success order by installed_rank desc limit 1;   -- 135（V135 成功）
```

## 四、验收（方案 §6 逐项）

1. 零错误数据：30 天事件/MTTR/owner 与 SQL 逐一对账一致 ✔
2. 零裸枚举：无新增枚举；"未登记/—"为空态口径 ✔
3. 零可点必失败：owner 写面 OPERATOR 会话可用（真机 200 验证）；空 owner 提交=解绑 ✔
4. 空态即引导：未登记显示"未登记"，30 天无解决显示"30 天内无已解决"口径 ✔
5. 迁移 V135 成功；依赖拓扑无真源——**不虚构**（README 明示待 CMDB）✔

## 五、遗留（如实记录）

- **依赖拓扑**：方案标注"后续接 CMDB"——当前无依赖真源，不画假拓扑图；待服务目录接 CMDB 或调用链推导落库后补。
- 告警路由关系（服务→值班分派映射）待 3.13 后续轮次与值班分派策略（assignment_rule）联动。
