# E2E + 消融实验计划（执行状态锚，2026-09-16）

目标：195 端到端全流程走查 + 组件消融（有/无对比）+ 每项截图 + 评测指标与页面。

## 环境事实（已验证）
- 195 = root@146.56.195.225（默认 key 可登录）；web 前端 bind 127.0.0.1:8090（deploy-web-1）；control 127.0.0.1:8080。
- 截图管线：本地 `ssh -N -L 9090:127.0.0.1:8090` 隧道 → 本地浏览器 http://localhost:9090 → browser-use 截图存 docs/测试证据/E2E-ABLATION/images/。
- 登录：/api/auth/login 表单；凭据在 deploy/.env 的 AUTH_OPERATOR_USERNAME / AUTH_OPERATOR_PASSWORD（值不回显文档）。
- docker exec deploy-postgres-1 psql -U postgres -d pr_agent -tA -c "SQL" = SQL 探针。
- operator bearer：docker exec deploy-control-app-1 sh -c 'echo $APP_OPERATOR_API_BEARER'（curl 用，不落文档）。
- mvn 在本 shell 必须管道取结果（>重定向会触发 reactor 假错）。
- 现状：flyway 121|true；demo 资产在库（intent bbbb1111…/request bbbb2222…/grant bbbb3333…003 已消费）；Guardian 白名单=chaos.resolve；Hardline=drop.database；executor endpoint=arena-chaos-admin off；QUARANTINED 行已被放行（inbox 全 RECEIVED+）。

## 消融矩阵（每项：有组件 vs 无组件，各一截图）
- A Guardian：白名单含 chaos.resolve（auto-decide→SAFE→APPROVED）vs 移除（UNCERTAIN→PENDING_HUMAN）。切换=改 .env GUARDIAN_LOW_RISK_TOOLS + recreate。
- B 注入扫描：injection-scan.enabled=true（注入 payload→QUARANTINED）vs false（直通 PROCESSED）。切换=ALERT_INJECTION_SCAN_ENABLED env（确认键名）或默认开关。
- C 资源锁：有锁=第二 mutation BUSY（B3 probe 复刻 SQL）vs 无锁行=双双放行。SQL 层演示。
- D 哈希链：一次性 demo run 上 UPDATE 一条 payload → verifyChain brokenAtSeq 报红 → 截图后清场（消融=无链时篡改不可见）。
- E 真执行对账：已证（UNKNOWN→ESCALATED→人工），补页面/截图。
- F Outbox/消费原子性：引用 B4 证据 + 补一张 shadow-summary/事件截图。

## E2E 全流程
POST /webhooks/alertmanager（bearer+真实 AM 载荷，service=checkout 白名单）→ incident→run→模型调查→报告 → 页面截图：告警列表/incident 详情/run 时间线/监控页/评测页。

## 评测指标与页面
- web 已有评测页（eval 对比工作台）+ /api/eval/** 投影。
- shadow-summary（mutation 面）+ shadow 页面截图。
- 产出 HTML 汇总报告（图片嵌入）入库。

## 状态
- [x] 计划落盘
- [x] 隧道+登录（operator 临时换号，事后已还原）
- [x] E2E（webhook 202 → incident → RERUN 铸 NATIVE run 82cf4cbf → SUCCEEDED → 报告 STRUCTURE_VALIDATED；非白名单=WAITING_CAPABILITY 活体证据）
- [x] 消融 A–E（A Guardian 15 / B 注入扫描 16 / C 资源锁 17 / D 哈希链 18 / E 对账闭环 19；环境逐项还原）
- [x] 指标页（09 监控 / 10–12 评测中心 / 13 处置中心 / 14 shadow-summary）
- [x] 汇总提交（E2E-消融汇总报告.html + 台账 runs/as-deploy-20260916/e2e-ablation-195-verify.md + images/01–20）
