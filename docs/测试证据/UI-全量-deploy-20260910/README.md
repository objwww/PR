# UI 全量（UI-2~UI-6 + V45）部署验证证据包（2026-09-10，195 真机全真数据）

## 部署摘要

沿用上轮（UI1-auth1）既定机制：
1. 本地源码 tar `var/ui-full-sync.tar.gz`（pom.xml + shared-kernel + control-app +
   alert-web + deploy；排除 target/node_modules/dist/deploy/.env）scp 到 195 `/opt/build/pr/`。
2. **干净替换**：tar 解包不会删除已删文件——首轮解包后 195 上 `alert-web/src/mocks`
   仍残留（alerts.js）。改为先备份（`backups/uifull-pre-20260910/`）→ 暂存 deploy/.env →
   整目录删除四模块 → 重新解包 → 还原 .env。解包后 mocks 目录确认不存在。
3. 本地 `mvn -pl control-app -am package` 绿（EXIT=0），exec jar（44,216,210 B，
   2026-09-10 23:35 构建）直传 195。
4. `docker compose build control-app web`（web 容器内 npm ci + vite 构建绿）→
   `run --rm migrate` → `up -d control-app web`。**只重启这两个服务**。
5. web 新 dist 指纹确认：assets 为 `index-GyGdns0J.js`（1,666,586 B）/
   `index-CH7UPDtQ.css`（242,965 B），构建时间 2026-09-10 15:36 UTC，
   与上轮 `index-DvKZwnM9.js` 不同——确为新构建（uifull-web-assets.txt）。

## V45 迁移结果

`docker compose run --rm migrate` 输出：
`Migrating schema "public" to version "45 - ui5 eval readonly grants"` →
`now at version v45`。flyway_schema_history 第 45 位 success=t（uifull-flyway.txt）。

## 逐端点验证（195 本机 curl，完整日志 uifull-verify.log）

新端点（V45 只读投影面）：
- `GET /api/eval/runs` 200：23 个真实批次，**首行即 full-0910b**
  （runId 439f2cb2-5755-4068-8519-3324e44c2914，SUCCEEDED，coverage=1.0，fn=10；
  样本 uifull-eval-runs.json）。
- `GET /api/eval/runs/439f2cb2…` 200：全字段真实（registry/config digest、起止时间、
  caseCount=10；uifull-eval-run-detail.json）。
- `GET /api/eval/runs/439f2cb2…/cases` 200：S1 各 round 真实判定行（verdict/
  expectedRootCause/actualRootCause/latencyMs；uifull-eval-run-cases.json）。
- `GET /api/eval/datasets` 200 但 `{"items":[]}`——库内实证 `dataset_version` 表 0 行、
  `case_version` 表 0 行、`golden_candidate` 0 行：**诚实空态，非权限遮蔽**
  （权限缺失会报错而非返空）。RLS 策略（HOLDOUT 过滤）因 case_version 零行只能验证
  不报错，无法正向验证过滤效果——如实记录。
- `GET /api/agent-ops/summary` 200：activeRuns=2、awaitingReviewRuns=168、
  readyTasks=1、notifyOutboxFailed24h=6、llmCalls24h=0、tokens24h=null（诚实 null）、
  topTools24h=[]（uifull-agentops.json）。

回归（UI-1 面）：
- `/api/v1/incidents` 200；`/api/v1/incidents/summary` 200
  （firingTotal=59，mttr=11.77min——上轮两处真 PG 修复持续生效）；
  `/api/v1/overview/summary` 200；`/api/auth/me` 200 `{"name":"test"}`。

权限矩阵：
- 未认证 `/api/eval/runs` → 401；`/api/agent-ops/summary` → 401。
- test 平台账号登录 200（platform_user 路径，上轮已建号）。

## 截图清单与目视结论（Edge headless + CDP，localhost:8090 真登录 test，逐张目视）

| 文件 | 页面 | 结论 |
|---|---|---|
| p01-login.png | /login | UI-2 新登录卡（首拍 2.5s 空白系隧道首载慢，9s 补拍正常，零 console 错误） |
| p02-overview.png | /overview | 新 UI-2 总览：待处理告警 59/待审查 168/通知未读 14/当前值班 alice/24h 趋势图全真；上轮的设计说明黄条已消失 |
| p03-alerts.png | /alerts | 告警中 59、P1 47、24h 接收 34、平均恢复 12 分钟、facet 侧栏全真 |
| p04-incident-detail.png | /alerts/2c88e64b… | Kill9DrillProbe10 真实详情、属性全表、诚实"原因待确认" |
| p05-history.png | /history | 6 条已解决真实行，共 6 条与 facets RESOLVED:6 一致 |
| p06-runs.png | /runs | 调查队列：进行中 2/待干预 193/已结束 41，真实 run 行 |
| p07-run-detail.png | /runs/2a41f73a… | 待审查 run 详情：任务 2/4、引擎 NATIVE、"暂无待补证项/暂无事件"诚实空态 |
| p08-cases.png | /cases | 处置中心全 0 诚实空态（"筛选无结果"+"请选择左侧待办"） |
| p09-duty.png | /duty | 当前当班 alice、通道链 rl-bot→echo-bot→dead-bot、七天排班 alice/bob 全真 |
| p10-duty-chat.png | /duty/chat | 演练群真实消息（EXD1_VERIFY/GATUS 探针投递记录） |
| p11-notifications.png | /notifications | 值班收件箱未读 14 条真实行（探针直发/系统派发标注） |
| p12-monitor.png | /monitor | agent-ops 大盘：活跃调查 2、待审查 168、就绪积压 1（最老 73.6h）、通知 0/6、模型调用 0 与 token "—"诚实 null、24h 趋势图 |
| p13-eval-runs.png | /eval/runs | 23 批次真实列表，full-0910b 覆盖率 100% |
| p14-eval-run-detail.png | /eval/runs/439f2cb2… | full-0910b 详情：digest 全长、耗时 2h9m、TP/FP/FN 0/0/10 |
| p15-eval-datasets.png | /eval/datasets | 诚实空态"暂无评测数据集"（库内 dataset_version 0 行实证） |

全 15 页：无假数据、无设计说明占位文字、无报错页、空态均诚实。SSE 显示未连接
（headless 未换 stream ticket，不影响只读验证）。

## 发现的问题

1. **tar 增量同步不删已删文件**（mocks 残留）——本轮已改干净替换流程并复核；
   后续同步沿用此律。
2. RLS（case_version HOLDOUT 过滤）在 195 库零行条件下只能验证可用性，过滤效果
   待有 HOLDOUT 行时回归。
3. /api/eval/datasets 当前恒空（dataset_version 表无行）——数据生产面未接入，
   非本轮缺陷。
4. p01 /login 首拍空白：隧道首载 1.6MB JS 超过 2.5s 等待所致，非页面缺陷
   （补拍正常、console 零错误）。

## 遗留

- 本地工作区（V45、新端点、前端 13 页、本证据包）未提交 git，留主会话/用户复核。
- 195 `/opt/build/pr` 与本地同源；`backups/uifull-pre-20260910/` 为回滚锚。
- 验证脚本现场：/opt/build/pr/uifull-verify.sh（可留可清）。
