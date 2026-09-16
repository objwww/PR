# M-a 里程碑方案：业务交易链路扩编（业务场景 5→15）

> 依据：`research/alert-agent-eval-20260917/故障场景扩展详细设计.md` §3.1/§4.1(C1b/C2b/C2c/C2d)/§4.2(R1)/§7(M-a)
> 日期：2026-09-17。性质：M-a 执行方案（milestone-workflow：方案 → 双门禁 → 最小实现 → 部署验证）
> 执行方式：定时自驱循环（30 分钟/轮）按 §4 工序表顺序推进，每轮完成一个工序（或子步骤）即提交并更新 §4 状态列

---

## 1. 目标与双门禁

**目标**：业务交易链路 10 个可见场景（S16~S25）的注入面、埋点面、告警面、注册面全链落地，业务场景 5→15。

**门禁一（工程门禁）**：
- order-arena / arena-chaos-admin 单测全绿（含新增探测/故障点单测）
- `promtool test rules` 全绿（arena-business.yml 10 条规则逐条单测）
- control-app / alert-web 构建不受影响（本轮不动它们）

**门禁二（真栈演练门禁）**：
- 10 个业务场景逐个真栈演练：chaos-admin 激活 → Prometheus 告警 firing → 关闭恢复 → resolved，全程记录时间线与 SQL 对账
- S23（复合低剂量）依赖 C3 复合激活（M-b）与 TrafficDriver（M-c）：M-a 内完成故障点参数位与规则，演练标注"部分验证（待 M-b 复合激活）"；S24 用 F16_INGRESS_SILENT 先行可全链验证
- 演练记录落 `docs/测试证据/业务扩编M-a-20260918/`

## 2. 现状架构事实（已侦察，定时轮次免重复侦察）

| 事实 | 位置 |
|---|---|
| FaultType 枚举仅 F1/F2/F3 | `order-arena/src/main/java/com/objwww/pr/arena/application/chaos/FaultType.java` |
| 注入判定=ChaosSwitchboard（oa_chaos_session DB 权威读，fail-closed，TTL 缓存；target=chaos- 前缀匹配） | 同目录 `ChaosSwitchboard.java`（FaultGate.active(type, correlationId)） |
| 业务链路注入点先例：TwoStepOrderService.create 的 F1（幂等跳过，`correlationId.startsWith("chaos-")` 双条件） | `application/TwoStepOrderService.java` |
| 支付网关=进程内 PaymentGatewaySimulator（authorize/capture → SUCCEEDED/DECLINED/UNKNOWN） | `application/PaymentGatewaySimulator.java` |
| **指标唯一来源纪律 INV-AM2-5：DomainProbe 探测产物（DB 扫描→Gauge oa_*_current + Counter oa_*_detected），注入点禁自报** | `application/DomainProbe.java` + `infrastructure/metrics/ArenaMetricsConfig.java`（/metrics，commonTags service=order-arena） |
| 告警规则范式 | `deploy/alert/prometheus/rules/arena.yml`（alert/expr/labels severity+service+fault_type/annotations.summary；C-6 指纹：alertname/fault_type/service 冻结） |
| 激活面校验：`ChaosActivationService.activate` L46 `faultType.matches("F[123]")` | `arena-chaos-admin/src/main/java/com/objwww/pr/arenaadmin/application/ChaosActivationService.java` |
| 部署：order-arena 与 arena-chaos-admin 都是 /opt/build/pr/deploy 的 alert compose 服务（alert-order-arena-1 / alert-arena-chaos-admin-1），构建方式与 control-app 同（docker compose build） | 195:/opt/build/pr/deploy |
| 场景注册契约：GoldenScenarioRegistry 期望面残缺即拒（只增不改+digest 锚定） | `deploy/alert/eval/eval-scenarios.yml`（18.7KB，新场景严格照既有字段契约） |
| chaos 会话表：arena.oa_chaos_session（fault_type 为 TEXT——枚举扩展无需迁移） | 195 DB |
| 流量前缀纪律：live-（生产面永不注入）/ chaos-（靶面） | correlationId 校验 |

## 3. 设计决策（定时轮次按此执行，不重新发明）

1. **C2d 埋点走 DomainProbe 扩展而非注入点自报**（INV-AM2-5）：新增业务对账扫描——支付成功记录 vs PAID 订单、订单创建 vs 履约发起、pending 支付年龄、重复支付、负库存、三方对账差。产出新 Gauge：
   `oa_payments_success_vs_orders_diff`（S16）、`oa_pending_payment_orders_current`（S17）、`oa_duplicate_payments_current`（S18）、`oa_recon_diff_current`（S19，C2c 对账作业产出）、`oa_inventory_negative_total`（S20）、`oa_orders_vs_fulfillments_diff`（S21）、`oa_duplicate_fulfillments_current`（S22）、`oa_fulfillment_overdue_current`（S25）。既有 4 gauge 不动。
2. **C2b 故障点挂接**（全部双条件：chaos- 前缀 + faultGate.active）：
   - F9 回调丢弃：pay()/capture 成功后跳过 markPaidTx（支付账面成功、订单 NOT_PAY）→ S16
   - F10 支付沉默：create 的 initiateAuthTx 后 gateway 调用直接返回 UNKNOWN 前的沉默路径（订单停 CREATED/待支付积压）→ S17
   - F11 重复扣款：pay() 重试路径跳过幂等（capture 重复成功）→ S18
   - F12 库存偏差：deductResourceTx 扣减与记录偏差（对账作业发现）→ S19
   - F13 竞态超卖：库存扣减跳过原子性（check-then-act 窗口）→ S20
   - F14 消息丢失：履约触发事件丢弃（订单创建后履约计数不增）→ S21
   - F15 ack 失败：履约重复执行 → S22
   - F16 入口静默：create 入口直接 no-op 返回（流量看似正常发出但零订单）→ S24
   - F17 履约变慢：履约处理延迟（SLA 超时积压）→ S25
   - F18 价格错算（H7 预留）：金额计算偏差（对账金额差），M-a 只注册类型不挂接演练
   - 履约/消息为靶场既有能力面——若 order-arena 无独立履约组件，按最小实现新增 `FulfillmentSimulator`（进程内、同 chaos 纪律），不引入真实 MQ
3. **C2c 对账作业**：`ReconciliationProbe`（照 DomainProbe 模式，订单-支付-库存三方 count/sum 对账 + 差异明细 JSON 端点 `GET /recon/diffs`）产出 `oa_recon_diff_current`。
4. **R1a 规则**：`deploy/alert/prometheus/rules/arena-business.yml` 10 条，严格照 arena.yml 范式（severity/fault_type 标签冻结，C-6 指纹）；单测照 `arena-rules-test.yml` 范式（promtool，文件放 rules/ 目录外）。
5. **E1a 注册**：eval-scenarios.yml 追加 S16~S25 十条（七要素契约照既有条目；S23 GT=COMPOSITE_LOW_DOSE 多因素、S24 教学前置 H6 备注；partition 按 §6.2：S16/S18/S20/S23=TUNING，S17/S19/S21/S22/S24/S25=VALIDATION）。**H7/H8 只写设计不注册实例**（HOLDOUT 纪律，M-e 处理）。
6. **确定性判据**：每场景演练验收=激活后 prometheus 查询告警 firing ≤ 规则窗、关闭后 resolved、DomainProbe/Recon 指标归零、SQL 对账（oa_chaos_session 会话、告警时间线）。

## 4. 工序表（定时轮次按此顺序执行；完成即把 ⬜ 改 ✅ 并提交）

| # | 工序 | 内容 | 验收判据 | 状态 |
|---|---|---|---|---|
| T1 | C1b 枚举与激活面 | FaultType 扩 F9~F17+F18（注释带场景映射）；chaos-admin 校验正则放宽 `F(1[0-8]|[1-9])`；arena-admin 单测更新 | 两服务单测绿 | ✅ 2026-09-17（chaos-admin 14 测试全绿；order-arena test-compile 绿——FaultType 扩展+ChaosRecoveryService 恢复 switch 补齐 F9~F18 无动作分支+激活正则放宽） |
| T2 | C2d+C2c 探测面 | ReconciliationProbe + DomainProbe 业务对账扩展（8 个新 Gauge + 差异明细端点 `/recon/diffs`）；DomainProbe 业务对账扩展（5 类 episode+5 Gauge 冻结名）+4 业务量 Counter 增量（失败窗口不推进）+ 对账差异明细端点 | 编译绿；S21/S22/S25 探测随 T4；IT 级验证随 T7 | ✅ 2026-09-17 |
| T3 | C2b 故障点（上） | F9/F10/F11/F12 挂接（支付回调/支付沉默/重复扣款/库存偏差）+ 各自单测 | 单测绿；chaos- 隔离单测（live 流量不受影响） | ⬜ |
| T4 | C2b 故障点（下） | F13/F14/F15/F16/F17 挂接（超卖/消息丢失/ack 失败/入口静默/履约变慢）+ FulfillmentSimulator 最小组件 + 单测 | 同上（T3 同轮交付） | ✅ 2026-09-17（30 测试全绿） |
| T5 | R1a 规则 | arena-business.yml 10 条规则 + promtool 单测文件；本地 promtool test rules 全绿 | promtool 全绿 | ✅ 2026-09-17（195 promtool v3.13.1 实跑 SUCCESS——11 条规则行/10 alertname，S17 双档 for 窗与 sum 聚合标签经测试修正） |
| T6 | E1a 注册 | eval-scenarios.yml 追加 S16~S25（七要素）；GoldenScenarioRegistry 解析通过（本地启动验证或既有注册表单测跑绿） | 注册解析零拒绝 | ✅ 2026-09-18（registry_version 2→3：15 场景全解析[七要素+partition/complexity/checkpoints 扩展键]；词库同轮扩容 lexicon_version 1→2——business.* fault_type 10 值 + reason_code 10 值，注册表 lexicon_binding 同步；jshell 真实文件装载零拒绝 + GoldenScenarioRegistryTest 4/4 + SynonymLexiconTest 5/5 全绿；H7/H8 未注册✓；S23 标注部分验证✓） |
| T7 | 构建+部署 195 | order-arena / arena-chaos-admin 镜像构建部署（compose alert 项目）；health 200；/metrics 新指标在场 | health 200 + 指标在场 | ✅ 2026-09-18（overlay tar+md5 同步→195 mvn package 双模块→compose build/up：双容器 healthy、V8 迁移 oa_fulfillment_attempt 落库、14 条 Arena 规则装载。**真机校准战果**：Micrometer/OpenMetrics 保留后缀变换致 4 条规则引用不存在的指标名（counter oa_orders_created_total 渲染成 oa_orders_total；gauge oa_inventory_negative_total 渲染成 oa_inventory_negative）——注册面/规则面/promtool 测试面/注册表注记四处统一到渲染真名，promtool 复跑 SUCCESS，Prometheus API 实查序列在场） |
| T8 | 真栈演练 A | S16→S17→S18→S19 逐个：激活→firing→恢复→resolved→SQL 对账，记录时间线 | 4 场景全链闭环 | ✅ 2026-09-18（演练脚本 drills/ma-drill-s1{6,7,8,9}.sh）**S16**：3 单损伤→firing[ArenaPaymentOrderMismatch page, gauge=3]→repaired=3→resolved→CLOSED。**S17**：25 单 AUTH 沉默→积压 gauge=25→ticket firing[firing 采样×12]→off→置 UNKNOWN=25 交 F3 对账→24 废单收敛/unsettled=0→resolved→CLOSED（F3 与 S5 鉴别对：S17 沉默无痕 vs S5 有 UNKNOWN 痕迹）。**S18**：同单二次扣款→firing[ArenaDuplicatePayments page]→删多余 capture repaired=1→resolved→CLOSED。**S19**：缺 DISCOUNT 扣减行(3/4 类型)→firing[ArenaReconciliationDiff page]→补记账 repaired=1→resolved→CLOSED→undertype 残差 0。四场景全部走完 激活→firing→off→恢复→resolved→SQL 对账 十步 |
| T9 | 真栈演练 B | S20→S21→S22→S24→S25 同上；S23 规则在场标注"部分验证（待 M-b 复合激活）" | 5 场景闭环 + S23 标注 | ✅ 2026-09-18（编排器 drills/ma-t9-run.sh 串行防交叉注入）。**S20** 超卖：超额 INVENTORY 行→firing[ArenaOversell]→删超额行 repaired=1→残差 1 行→CLOSED。**S21** 事件丢失：25 单履约被吞→counter 差值 gap=25.6>10→firing[ArenaFulfillmentGap ticket]→off→窗口稀释 resolved→TTL reaper 收口（首跑揭出 V14：F14 注入点 dropFulfillmentTx 缺 oa_fulfillment_order DELETE 授权——25 单卡 CREATED+履约在+零台账+幂等 PROCESSING；另修复消费候选扫描回看窗 30min——百万存量 CONFIRMED 单使新单数小时不被消费）。**S22** 重复消费：attempt×2→firing[ArenaDuplicateFulfillment]→删重复行 repaired=1→残差 1→resolved→CLOSED。**S24** 断流：.env 备份纪律停流量发生器→25 请求被入口静默（落单 0）→rate==0 for 5m→firing[ArenaOrderZeroFlow page]→off→恢复落单→resolved→.env 还原。**S25** 履约积压：16 单停 CONFIRMING→gauge=16>15→firing[ArenaFulfillmentSlaBreach]→收口 CONFIRMED repaired=16→残差 0→CLOSED。**S23** 规则在场 health=ok（部分验证标注，复合激活依赖 M-b）。连带修复：F14/F16 会话收口审计缺失（RECOVERING 永卡+占 uq 靶位）——恢复 switch 补审计分支，卡死旧会话部署后 30s 自愈 |
| T10 | 收官 | 演练记录落 docs/测试证据/业务扩编M-a-20260918/；双台账（差距分析文档 M-a 状态 + PROGRESS）；总提交 | 双门禁齐 | ✅ 2026-09-18（演练证据：M-a十场景真栈演练证据.md + t9 三份全量日志；差距分析 §P0 已标 M-a 完成——业务扩编超额落地 5→15 可见场景；PROGRESS.md 追加收官行；工程门禁[单测/promtool]+真栈门禁[十场景十步闭环]双齐） |

## 5. 定时轮次协议（每 30 分钟一轮）

1. 读本方案 §4 工序表，取第一个 ⬜ 工序（多步工序可跨轮，用子标记记录进度）
2. 实现 → 本地验证（mvn 单测 / promtool）→ 显式路径 git 提交（`feat(arena): M-a T# 工序名`）
3. 195 部署验证按工序要求（T1~T6 本地验证为主；T7 起部署 195）
4. 更新本表状态列 + 遇阻如实记录 §6（不造假推进；阻塞写清原因与所需决策）
5. 纪律：不碰 control-app/alert-web 前端产品化成果；并行会话可能同域活动——提交前 `git pull --rebase` 或按显式路径避开；**不启用 HOLDOUT**；chaos- 前缀与 fail-closed 纪律任何时刻不可破

## 6. 阻塞与决策记录

| 日期 | 阻塞/决策 | 状态 |
|---|---|---|
| 2026-09-18 | T8 执行要点（已完成侦查）：激活 API = `POST /chaos/{faultType}/on`（body: scenarioId/target/ttlSeconds/operator/configDigest/groundTruth{schemaVersion,datasetVersion,payloadDigest,applicableScope}/alertLabels/ruleDigest + header X-Admin-Token）→ 201 {sessionId,generation,alertFingerprint}；关闭 = `POST /chaos/{faultType}/off`（scenarioId+expectedGeneration，CAS 409 面）；状态 = `GET /chaos/status?scenarioId=`。alertLabels 用 arena-business.yml 冻结面（alertname/service/fault_type/severity）。admin 服务无宿主端口（eval-mgmt 私网）——从同网现成容器（flagd-admin python）内 urllib 调用，token 从 195 deploy/alert/.env 读、不落日志 | 待 T8 使用 |
