# 3.4 补强·诊断回答评价（thumbs 闭环） — 测试截图文档

- 批次：前端产品化 · 已收官页面缺口复盘（3.4 AI 诊断页增值补强；定时自驱循环第 11 轮）
- 日期：2026-09-17
- 环境：195 真机（compose web 127.0.0.1:8090，SSH 隧道 18090 访问）
- 路由：`/diag`（侧栏「工作台 → AI 诊断」）
- 缺口定位：方案 §3.4 无评价项，但回答满意度采集是 Bits AI/Ask AI 业界全员标配（且是采纳率/改进环的数据源）——按「只可多不可少」补齐
- 新后端：`POST /api/v1/incidents/{id}/diag/feedback`（upsert 改评）；history 投影带出评价态；stats 带好评分布。**新迁移 V137**

## 一、业界调研留痕（UI-IND-1）

| 参照物 | 实践要点 | 本批对齐 |
| --- | --- | --- |
| Datadog Bits AI | 每条 AI 回答 👍/👎；负反馈必选原因（不准确/不完整/其他）；满意度进指标 | 「有用/无用」按钮 + 无用原因四选（答非所问/事实不准确/信息不完整/其他）+ 好评率统计卡 |
| incident.io Ask AI | 一答一评、可改评（后评覆盖前评，不留双态） | `diag_session_feedback` 以 session_id 主键 upsert——改评覆盖，表内恒一答一态 |
| 观测云 Obsy AI / 得物 Troubleshooter | 满意度聚合看板（采纳率） | stats 端点带 upCount/downCount，好评率=有用/(有用+无用)，未评不计入分母 |

## 二、截图清单

| # | 文件 | 区块/内容 | 数据源 | 对账 |
| --- | --- | --- | --- | --- |
| 72 | 72-诊断页-评价按钮与好评率.png | 每条回答「有用/无用」评价行 + 统计行第 5 卡「回答好评率 0%（有用 0/无用 1）」 | `GET /diag`（JOIN feedback）+ `/v1/diag/stats` | SQL DOWN=1 ✔ |
| 73 | 73-诊断页-评有用成功联动好评率.png | 点「有用」→ 徽章「已评：有用」+ 好评率实时 0%→50%（1/2） | `POST /diag/feedback` + history/stats 联动刷新 | UP=1 ✔ |
| 74 | 74-诊断页-无用原因展开.png | 点「无用」展开原因下拉（答非所问/事实不准确/信息不完整/其他，可选）与提交按钮 | 纯前端交互 | 提交后见 73 尾条「已评：无用 · 答非所问（验收演示）」 |

UI 断言（Playwright 真机跑）：好评率 0%→50%→33% 三态联动全命中；控制台**零错误**。

## 三、SQL 对账（2026-09-17 真机终态）

```sql
select rating, reason, created_by from diag_session_feedback order by created_at;
-- DOWN | 答非所问（验收演示） | operator   ← 烟测改评（UP→DOWN upsert 覆盖，未堆历史）
-- UP   |                      | operator   ← 截图 73 UI 评价
-- DOWN |（空，原因可选）      | operator   ← 截图 74 流程提交
select count(*) from diag_session_feedback;   -- 3 = UI 已评态 3 条
select version from flyway_schema_history
 where success order by installed_rank desc limit 1;  -- 137（V137 成功）
-- stats 实测：total=7, upCount=1, downCount=2 → 好评率 33%（页面同值）
```

## 四、验收（方案 §6 逐项）

1. 零错误数据：评价行数/好评率与 SQL 逐一对账一致（0%→50%→33% 全链路）✔
2. 零裸枚举：UP/DOWN 经「有用/无用」中文渲染；原因经固定中文词表 ✔
3. 零可点必失败：评价写面 OPERATOR 会话真机 200；跨事件评价被 NOT_FOUND 拒绝（防锚错位）✔
4. 空态即引导：未评回答显示中性按钮，无评价时好评率「—」✔
5. 迁移 V137 成功；UPDATE 授权随建表同批授（V134 教训落实）✔

## 五、如实记录

- 验收演示评价数据（3 条）保留在真账本中，原因字段注明「验收演示」可溯源；不清理不造假。
- `diag_session` 原授权仅 SELECT/INSERT——评价独立成表而非原表加列，避免触碰问答账本不可变性。

## 六、遗留（如实记录）

- 全局 stats 的评价分布仅「有用/无用」两态；按事件/按问题类型的好评率下钻待 Analytics 域统一规划。
- 详情页「问一问」历史已同步透出评价徽章（只读），评价入口统一收在诊断页。
