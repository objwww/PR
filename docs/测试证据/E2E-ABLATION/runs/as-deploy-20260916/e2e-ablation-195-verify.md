# E2E 全流程 + 消融实验 195 验收台账（2026-09-16）

批次：E2E 端到端走查 + 组件消融（有/无对比）+ 每项截图 + 评测指标与页面
部署机：195（web 127.0.0.1:8090 经 SSH 隧道 → 本地浏览器截图；control 127.0.0.1:8080）
结果：**E2E 链路真机闭环 + A–E 五项消融全部真机完成且环境逐项还原 + 19 张截图证据**

## 一、E2E 全流程（告警 → incident → run → 调查 → 报告 → 页面）

- 注入：AM 信封载荷（version 4/groupKey/alerts[]）直打 `/webhooks/alertmanager`（bearer 运行时提取，不落文档）→ 202 `{"status":"accepted","inboxId":"a8c8dd00…"}`。
- 路由事实：非白名单 alertname → canary `BUCKETED_HOLMES` 永不铸 run（M6-07 二引擎退场），incident 记 `WAITING_CAPABILITY`——这本身是路由纪律的活体证据。
- 铸 run：白名单内 `alertname=CheckoutRpcClientErrorRateHigh|service=checkout` 注入、材料独特化 → FIRING 无活跃 run → **RERUN 铸 NATIVE run `82cf4cbf`**，秒级 SUCCEEDED（2 任务 2 完成 / 7 模型调用 / 6 工具调用 / 6 证据行）。
- 报告：报告 ID `c89f5c9c`，schema v2，`STRUCTURE_VALIDATED`，模型 native-deterministic-v1，结论 UNRESOLVED（confirmed=0）如实呈现。
- 页面截图：01 总览 / 02 告警中心 / 03 告警详情 / 04 调查队列 / 05–07 run 详情（摘要·执行过程 DAG·任务抽屉）/ 08 报告页签。
- 系统页：09 监控（活跃调查/待审查/主机指标）/ 10–12 评测中心（实验列表·对比工作台·实验详情指标）/ 13 处置中心（case 时间线）。

## 二、消融矩阵（每项：有组件 vs 无组件，真机对照 + 截图）

| 消融 | 组件 | 有 | 无 | 证据 |
|---|---|---|---|---|
| A | Guardian 低危白名单 | SAFE→APPROVED（guardian:pb-prod-v1 落 decisions，grant ACTIVE） | UNCERTAIN→PENDING_HUMAN（零请求零代签） | 15 / evidence-html/ablation-a.html |
| B | 入口注入扫描 | 注入载荷→QUARANTINED（patterns 落 last_error） | PROCESSED 直通→incident FIRING（危害入模型上下文） | 16 / ablation-b.html |
| C | 资源互斥锁 | `REJECTED / LOCK_BUSY`（整体回滚） | PLANNED→COMPLETED→放锁（14s 全链） | 17 / ablation-c.html |
| D | 事件哈希链 | 全链重算 `brokenAtSeq=34 verified=77/78` | 无链对照=行内自洽不可见（payload↔digest 一致） | 18 / ablation-d.html |
| E | 真执行对账闭环 | UNKNOWN→RECONCILING→ESCALATED→人工 FAILED_CONFIRMED→放锁（seq52–57） | 无闭环=只能猜 FAILED（误杀）或猜 SUCCESS（假终态） | 19 / ablation-e.html |

方法要点：
- A/C 同意图同资源仅切换单一变量；B 同一注入载荷仅切换扫描开关；D 采用"备份→篡改（payload+digest 同步重算的高级攻击者姿态）→双检查→字节级还原→复验 78/78 全绿→删备份表"，验链公式与 `PostgresRcaEventAppender.verifyChain` 完全一致现场复算；E 复用 PE-E3 真靶全生命周期账行（run 4950d39b seq52–57）。
- shadow-summary（5 requests/5 approved/5 grants/2 consumed/avg 0.046s/human_wait 20.026s）与评测中心指标页（端到端命中率/条件准确率/根因可判定覆盖率/未决率/错误确认率）见 12/14。

## 三、环境还原与纪律

- 临时换号（截图用 operator/operator dev-default bcrypt）已还原：AUTH_OPERATOR_* 两行 md5 对拍 `1b1543a9…` OK，容器重建 health=200。
- 消融 A/Guardian 白名单还原 `chaos.resolve`；B/compose override 已删除；D/78 行字节级还原且复验全绿；C/E 演示行保留为 shadow 审计（合法账行，零清场需求）。
- bearer/密码全程运行时提取，零落文档；`docker-compose.override.yml` 无残留。
- FA（失败）注记：无。过程 SQL 曾打在 legacy 行（run cacdd4c5 全 prev_hash=NULL 链段外）→ 换 4950d39b 全链行重做，教训=验链演示须先验 `count(prev_hash)=count(*)`。

## 四、结论

方案 B v2 的五层安全组件（Guardian/注入扫描/资源锁/哈希链/对账闭环）在 195 真机上"有/无"差异全部显性化：每个组件的缺席都对应一类具体事故（越权自动放行、上下文投毒、并发双写、审计不可信、假终态），在场时这些事故分别被三值裁决、隔离区、BUSY、断链报红、人工终裁结构性消灭。
