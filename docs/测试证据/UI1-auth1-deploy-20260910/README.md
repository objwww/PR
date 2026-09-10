# UI-1 + AUTH-1 部署验证证据包（2026-09-10，195 真机全真数据）

## 部署方式

195 部署机制（实测确认）：源码树 `/opt/build/pr`（与本地仓库同构），compose 项目根
`/opt/build/pr/deploy`（项目名 `deploy`）；迁移由独立 one-shot 服务 `migrate`
（flyway/flyway:10-alpine，直挂 `../control-app/src/main/resources/db/migration`）执行，
**非应用启动自跑**（compose 显式 `SPRING_FLYWAY_ENABLED: "false"`）；web 镜像在 195 上
docker 内 `npm ci && npm run build`（alert-web/Dockerfile 两阶段）；control-app 镜像只封装
宿主机预构建的 `target/*-exec.jar`。

本轮步骤：
1. 本地打源码包 `var/ui1-sync.tar.gz`（pom.xml + shared-kernel + control-app + alert-web +
   deploy，排除 target/node_modules/dist/deploy/.env），scp 到 195 `/opt/build/pr/`；
   先 `cp -a` 备份到 `backups/ui1-pre-20260910/` 再解包。
2. 本地 `mvn -pl control-app -am package` 产物 `control-app-0.0.1-SNAPSHOT-exec.jar`
   直接 scp 到 195（服务器 maven 构建与本地构建均有先例，本轮选本地产物直传——最快可靠；
   jar sha256 与容器内 /app/app.jar 恒等，见下）。
3. `docker compose build control-app web`（web 镜像内 npm ci 重建 dist，vite 构建绿）。
4. `docker compose run --rm migrate` → V44 应用成功（见 ui1-flyway.txt：
   `44 | 44 | auth1 platform user | t`）。
5. `docker compose up -d control-app web`（只动这两个服务，其余服务未触碰）。

部署中实捕两个**仅真 PG 显形**的缺陷（本地 1104 测试全绿不可见），均已在现场修复、
重建、复验：

- **BUG-UI1-D1**：`GET /api/v1/incidents/summary` 的 mttr SQL `avg(...)` 返回 numeric，
  `rs.getObject("mttr", Double.class)` 被真驱动直拒（PSQLException: conversion to class
  java.lang.Double from numeric not supported），异常经 /error 二次派发后表现为
  会话 403 / bearer 401 的误导性应答。修复：`PostgresIncidentQueryReader.summary` 改取
  BigDecimal 再转 double（control-app `PostgresIncidentQueryReader.java:221-236`）。
  红证据：ui1-summary-red-stack.txt。
- **BUG-UI1-D2**：同方法 `.single()` 对 avg=null 行值直抛
  TypeMismatchDataAccessException（"诚实 null" 承诺在零 resolved 行时破约）。修复：
  改 `.optional().orElse(null)`。红证据：195 failsafe 首轮报告
  （summaryReturnsNullMttrWhenNoRecentResolved ERROR）；绿证据：ui1-it-green-report.txt
  （195 真 PG testcontainers，Tests run: 2, Failures: 0, Errors: 0）。

为防再漏，新增真 PG IT 钉 `control-app/src/test/java/com/objwww/pr/control/it/
PostgresIncidentQueryReaderIT.java`（两案：numeric→Double 映射 + 零 resolved 行 null），
195 上 `mvn verify -Dit.test=PostgresIncidentQueryReaderIT` BUILD SUCCESS。

## 验证命令与关键结果

全部经 195 本机 curl 打 `127.0.0.1:8080`（control-app 直连）或 `localhost:8090`
（web 反代，浏览器面）；完整日志 ui1-verify.log / ui1-verify2.log / ui1-verify3.log。

认证与账号（AUTH-1）：
- env operator 登录 200。**注意**：195 `.env` 的 `AUTH_OPERATOR_USERNAME` 实为 `test`
  （早前会话已把引导管理员配成用户指定的 test/12345678），明文不落 .env；
  本验证以 test/12345678 实证登录成功。
- `POST /api/auth/users` 建平台账号：test/12345678（200）、ui1op（200）；
  重复建号 409；短密码 400；库表 platform_user 两行（role=OPERATOR，不出 hash）。
- **platform_user DB 登录路径实证**：ui1op（env 提供者不认识此名）登录 200，
  `GET /api/auth/me` 返回 `{"name":"ui1op"}`——证明 DB 主体生效。
- 错口令 401；不存在用户 401；停用后登录 401、复用恢复 200；防自锁（停用本人）400；
  重置密码后旧口令 401/新口令 200。
- 审计：auth_event 落 LOGIN_SUCCESS/LOGIN_FAILURE/LOGOUT 全量（ui1-verify2.log §14）。

查询投影（UI-1，真数据）：
- `GET /api/v1/incidents` 200：65 条 incident（Kill9DrillProbe 演练告警等真实行）；
  样本 ui1-incidents.json。
- `GET /api/v1/incidents/facets` 200：status{FIRING:59, RESOLVED:6}、severity、service 维。
- `GET /api/v1/incidents/summary` 200（修复后）：
  `{"firingTotal":59,"bySeverity":{"page":12,"warning":47},"unassigned":59,
   "stormReceived24h":35,"stormEvents24h":15,"mttrMinutes24h":11.7711369875}`。
- `GET /api/v1/overview/summary` 200：firingIncidents=59、runs/cases/notifications/duty、
  24h 趋势桶全量真实返回。
- `GET /api/v1/incidents/{id}` 200（真实 incident 2c88e64b…=Kill9DrillProbe10）：
  labels/annotations 全文 + timeline（alert_event 行），样本 ui1-detail.json。
- **数据溯源一致性**：summary.firingTotal=59 == 列表 `?status=FIRING` 的 total=59
  （CONSISTENT，ui1-verify2.log §13；v1 脚本误用小写 firing 得 400，v2 已纠正）。
- 列表 status 过滤参数大小写敏感（FIRING/RESOLVED 大写），facets 返回键亦大写。

## 截图索引（Edge headless + CDP，脚本 ui1-screenshot-cdp.mjs，http://localhost:8090 真登录 test）

- shot-1-overview.png —— /overview 新壳（侧栏分组、顶栏 test 用户、统计卡）
- shot-2-alerts.png —— /alerts 告警中心（统计条 告警中59/P1 47/24h接收35/平均恢复12分钟、
  facet 侧栏、真实列表）
- shot-3-history.png —— /history 历史档案（6 条已解决真实行，与 facets RESOLVED:6 一致）
- shot-4-incident-detail.png —— /alerts/2c88e64b…（Kill9DrillProbe10 详情：属性全表、
  影响与当前结论、页签 概览/调查/证据/时间线）

## 偏差与遗留

1. env operator 用户名是 test（非惯常 operator）——平台账号 test 的登录会被 env 提供者
   先行命中（同口令下行为一致）；DB 路径由第二账号 ui1op 实证。验证收尾已将 ui1op
   **停用**（active=false，防自锁面保留账号行）。
2. /overview 页「交接视图」段落内容疑似仍为静态占位（run#8c1d 等条目与接口返回无对应），
   本轮任务范围只要求"看新壳"；如要求该段全真化需另行立项。
3. 截图 SSE 显示"未连接"（headless 未换 stream ticket），不影响只读页面验证。
4. 本地修复后 `mvn -pl control-app -am package` 绿（EXIT=0）；IT 真跑在 195
   （本机无 docker，IT 本地自动跳过）。
5. 195 `/opt/build/pr` 源码树已含本轮全部改动（含修复与 IT）；本地工作区同内容未提交
   （git status 仍有大量 M/?——提交动作留给主会话/用户复核）。
6. 服务器残留验证脚本 /opt/build/pr/ui1-verify*.sh 与日志可留作现场，或后续清理。
