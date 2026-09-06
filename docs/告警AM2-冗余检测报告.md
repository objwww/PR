# 告警 AM2 冗余检测报告（ponytail 审计，只列不改）

> 文档信息：2026-09-05；状态 = **待用户裁决后执行**。
> 审计方法（三轮）：
> ① 第一轮 = ponytail 阶梯死代码审计（零调用方 = 冗余），全仓交叉引用 grep 取证；
> ② 第二轮 = **"能否一行表达"细检**——对每个多行结构自问"这个构造一行能否表达/本仓是否已有同款"，
>    能则登记为冗余（E 级），同时把设计冻结项放 D 级保留区；
> ③ 第三轮 = **消费面复核 + 设计文档对照**——对全部疑似项重 grep 调用点（修掉 `var` 声明遮蔽类型名导致的
>    两处漏判，见 C-3/C-6 修正），并逐项对照 `架构设计-告警Agent-v1.2.md`、`告警Agent-增量实现任务拆解-v1.md`、
>    `告警AM2-技术方案.md v3.0` 判定"可删 / 需裁决 / 保留"（结论：v1.2 不含 AM2 细节条款，AM2 细节
>    以 AM2 技术方案 v3.0 为准；跨文档与 `告警-冗余清理清单-v1.md` 的重复项已互链标注）。
> 逐行读毕 AM2 落码全部主源码与测试（order-arena 59 主源码类 + 16 测试、arena-chaos-admin 7 主源码类 + 4 测试、
> 7 个迁移 SQL、两模块 yml/Dockerfile/pom、deploy/alert compose 与全部 Prometheus 规则）。
> **本次未删除、未修改任何文件**——以下所有条目均为"待执行"建议，等用户逐项放行。
> 范围界定：AM2 落码 = `order-arena` + `arena-chaos-admin` + `deploy/alert`。control-app 内 AM3 在途改动（eval 域）、
> order-arena 内 V7 迁移与 ArenaEvalGtBarrierIT（AM3 M3-10 交付物）不在清理范围。

---

## 0. 结论摘要

| 级别 | 含义 | 项数 | 约计行数 |
|---|---|---:|---:|
| A | 整类删除（零调用方，设计无规定 → 可删） | 1 | ~16 |
| B | 方法/字段级死代码删除（零调用方；含二轮 B-15~17、三轮 B-18~20） | 20 | ~240 |
| C | 修改/简化（有调用但存在冗余面；C-6 经三轮复核**撤回**，生效 8 项） | 8 | ~55 |
| E | **一行化/去重细检**（第二轮：多行构造存在一行/同款表达） | 9 | ~70 |
| D | 审查过、判定**保留**（设计冻结或契约面，防止误删；三轮 +5） | 13 | — |

另附 4 条执行外观察（测试缺口、注释/文档漂移、可调键未列出），见 §5。

**规模口径说明**（回应"八万行"）：AM2 落码范围实测 = order-arena + arena-chaos-admin + deploy/alert
≈ **9,800 行**（99 个 Java 文件 7,411 行 + 迁移 SQL 7 件 + deploy/alert 配置与规则 2,348 行）。
全仓八万行口径含 control-app（AM0/AM1/AM3）与 shared-kernel——那些不在本报告范围；
control-app/shared-kernel 侧的冗余已由 `告警-冗余清理清单-v1.md` 独立登记（两文档互补不重叠，
唯 R-A09 死配置键与本报告 B-20 同项，执行时任择一处）。

---

## 1. A 级 —— 整类删除（零调用方）

### A-1 `application/chaos/NoFaultGate` 整类删除

- 位置：`order-arena/src/main/java/com/objwww/pr/arena/application/chaos/NoFaultGate.java`（全文 16 行）
- 证据：全仓 grep `NoFaultGate` 仅命中类自身 + `FaultGate.java:7` 的 javadoc 提及。
  主装配（ArenaConfig）恒定装配 `ChaosSwitchboard`（fail-closed 读 DB），"无 chaos 域时恒否"的
  阶段二过渡形态已无任何装配点；连测试都没有使用它。
- 同步修改：`FaultGate.java:7` javadoc 删去"此前链路以 NoFaultGate 装配（恒否）"一句。
- 删除理由（ponytail 阶梯第 1 档）：speculative need = skip。fail-closed 语义已由
  ChaosSwitchboard 的 catch → empty 保证，不需要"空实现类"来重复表达。

---

## 2. B 级 —— 方法/字段级死代码删除（全仓零调用方）

> 每项均给出"证据 = grep 无任何调用点"；标注〔测试同步〕的项需连同测试/假实现一起删，删后测试仍绿。

| # | 位置 | 删除内容 | 证据与说明 |
|---|---|---|---|
| B-1 | `application/OrderCreationSteps.java:168-171` | `deductions(UUID)` 方法 | javadoc 自称"编排层组装幂等重放摘要用"，但 TwoStepOrderService 重放路径（`replayOutcome`）只查订单表，从不调用。grep `\.deductions\(` 零命中 |
| B-2 | `domain/repository/IdempotencyRepository.java:30-33` + `infrastructure/persistence/PostgresIdempotencyRepository.java:130-137` | `release(intentId, epoch)` 接口方法 + 实现 | "处理失败释放 PROCESSING→NEW"不在 C-2 冻结语义内（C-2 = claim/replay/conflict + 租约过期回收）；主链路失败面靠租约过期自然回收。唯一调用方是它自己的测试。〔测试同步〕删 `PostgresIdempotencyRepositoryIT.java:90-97`（`releaseReturnsIntentToFresh` 整个测试方法） |
| B-3 | `domain/repository/CompensationOutboxRepository.java:19-20` + `PostgresCompensationOutboxRepository.java:44-50` | `countClaimable()` | 全仓（含测试）零调用 |
| B-4 | `domain/repository/CompensationOutboxRepository.java:39-41` + `PostgresCompensationOutboxRepository.java:125-137` | `findByOrder(UUID)` | 全仓（含测试）零调用 |
| B-5 | `domain/repository/PaymentRecordRepository.java:22-23` + `PostgresPaymentRecordRepository.java:58-65` | `findByOrder(UUID)` | javadoc 自称 F2 探测比对面，实际 F2 探测在 `PostgresProbeStore.stateViolations()` 内联 SQL 直查。全仓零调用 |
| B-6 | `infrastructure/persistence/PostgresTradeOrderRepository.java:120-123` | `normalizeAmount(BigDecimal)` 静态方法 | 注释自称"测试与自检用"，但 grep 全仓（含测试）零调用。numeric(12,2) 精度实际由 DB 列约束承担 |
| B-7 | `domain/repository/TradeOrderRepository.java:31-33` + `PostgresTradeOrderRepository.java:93-101` | `findByIntentId(String)` | javadoc 自称 F1 探测依据（INV-AM2-5），实际 F1 重复单分析走 `PostgresChaosInjectionStore.findF1DuplicateRows()` 独立 SQL。业务零调用。〔测试同步〕删 `OrderControllerTest.java:195-198`（`FakeTradeOrders.findByIntentId` 假实现） |
| B-8 | `domain/model/PaymentRecord.java:34-43` | `withResult(PaymentResult)` + `terminal()` 两个方法 | withResult 是 PayStateMachine 的 Java 侧门，但 result 迁移实际全走仓储 SQL CAS（`casResult`），领域对象迁移路径不存在。grep 零调用（control-app 的 `isTerminal` 是 DagTaskState，无关）。注意：**PayStateMachine 本身保留**（穷举测试 M2-07 断言对象，且 SQL CAS 的迁移矩阵以其为文档） |
| B-9 | `application/TrafficGenerator.java:34,87,128-130` | `submitted` AtomicLong 字段 + `submittedCount()` | 与 micrometer `arena_traffic_submitted_total` 计数器双重记账，且 getter 无任何调用方（测试只用 `rejectedCount()/activeJourneys()/availablePermits()`）。删字段时连带删 `submitOne()` 里的 `submitted.incrementAndGet()` 一行 |
| B-10 | `application/TrafficGenerator.java:120-122` | `concurrencyLimit()` | 零调用方（测试自行持有闸门常量 4） |
| B-11 | `domain/model/OrderSnapshot.java:20-27` | `amount()` + `createdAt()` 两个"兼容视图" | 调用面全部直接取 `tradeOrder().amount()`/`findById` 后的订单，快照便捷视图零调用。保留 `orderId()`（有调用） |
| B-12 | `domain/statemachine/TransitionTable.java:19,23-25,44-52` | `targets(S)` + `type()` + 仅服务于 type() 的 `type` 字段与构造参数 | 穷举测试（StateMachineExhaustiveTest）用 `table()/allowed()/requireTransition()`，从不用 targets()/type()。删后 Builder 仍用自己的 `type` 字段建 EnumSet，构造器签名相应简化 |
| B-13 | `arena-chaos-admin/.../PostgresChaosAdminStore.java:161-183` + `ChaosActivationService.java:115`（`reaperTick` 内 `int orphaned = store.reapStartupOrphans();` 一行及求和项） | `reapStartupOrphans()` 整个方法 | **死路径**：清扫 `PREPARED` 孤儿，但代码中不存在任何 PREPARED 写入方——`activate()` 显式插 `ACTIVE`，DB 的 `default 'PREPARED'` 永不生效（V3 迁移仅 CHECK 枚举含它）。且该方法的**事件插入 SQL 本身有错**：`WHERE ... id IN (SELECT session_id FROM oa_chaos_event WHERE event_type='STARTUP_REAPED')` 漏了 `NOT EXISTS`（对比 `reapExpired()`），一旦触发会给所有历史已清扫会话**重复**插 STARTUP_REAPED 事件。死代码 + 隐性 bug → 按 ponytail 删除而非修复。无测试引用（ChaosActivationLifecycleIT 不覆盖）。DB 侧不动（迁移为已部署事实源，CHECK 枚举与 default 留着无害）。三轮设计对照：AM2 技术方案 §6.5 激活事务原文即 `INSERT/UPDATE chaos_session→ACTIVE`——设计不要求 PREPARED 中间落库，删清扫器与设计一致（§3.1 的 PREPARED 仅是状态枚举起点） |

### B 级附：依赖清理

| # | 位置 | 删除内容 | 证据 |
|---|---|---|---|
| B-14 | `arena-chaos-admin/pom.xml:18-21` | `shared-kernel` 依赖 | 该模块全部 7 个主源码类 + 测试**零** `com.objwww.pr.shared` import（grep 证实）。order-arena 的同名依赖有真实消费（Digest、IllegalTransitionException），保留 |

### B 级补遗（第二轮"一行表达"细检发现，2026-09-05 追加）

| # | 位置 | 删除内容 | 证据与说明 |
|---|---|---|---|
| B-15 ⚠ | `domain/model/TradeOrder.java:37-46` | `withBookingStatus(next, reason)` | **零调用**（grep 仅命中定义与自身 javadoc）。⚠ 设计声明冲突：类 javadoc（:12）与技术方案 §3.1 声明"仓储层落库前必经此门"，但落码实现全部走仓储 SQL CAS（`casBookingStatus`，WHERE 兜底），领域门从未在运行时执行。删除 = 正式接受"SQL CAS 为唯一执行门、状态机矩阵 + 穷举测试为契约文档"这一落码偏差；替代方案（让仓储写路径改走领域对象）是更多代码，ponytail 反对。**与 B-16/17 同批，三选项正式化见 §3.7（一），等用户裁决** |
| B-16 ⚠ | `domain/model/TradeOrder.java:48-72` | `withPayStatus(next)` | 同 B-15：零调用；pay 维度"NOT_PAY→PAID 仅当 booking=ENABLED"（M2-11 不变量）实际由 `PostgresTradeOrderRepository.casPayStatus:88` 的 SQL 守卫（`:to <> 'PAID' OR booking_status='ENABLED'`）执行 |
| B-17 ⚠ | `domain/model/FulfillmentOrder.java:21-24` + `domain/model/RefundOrder.java:30-37` | 两处 `withState(next)` | 同 B-15 家族：均**零调用**（迁移实际走 `fulfillments.casState`/`refunds.casState`）；RefundOrder.withState 的"终态 settledAt"逻辑已由 `PostgresRefundOrderRepository.casState:41-42` 的 CASE 表达式等价承担 |
| B-18 | `order-arena/src/main/resources/db/migration/V2__am2_compensation_outbox.sql:31` | `last_error jsonb` 列 | 全仓 Java/yml 零引用（outbox 失败信息实际走 `oa_chaos_event` 风格的 detail？否——补偿失败无落错通道，该列从未被写入/读取）。**迁移不可变，DB 列保留**，此处仅登记"死列"声明，防止后人误以为有消费者 |
| B-19 | `domain/repository/CompensationOutboxRepository.java:39` + `PostgresCompensationOutboxRepository.casRetry` + `CompensationWorker.java:77` | `casRetry` 的 `int maxAttempts` 死参数 | 接口签名带参数、SQL 体**不使用它**（DEAD 判定用行内 `max_attempts` 列，V2:29 默认 5）；唯一调用方 Worker:77 传 `Integer.MAX_VALUE`——表面上"永不耗尽"，实际 DB 列 5 次即 DEAD，语义误导。设计对照：架构 v1.2:1730 的 max_attempts 是 AM4 Reconciler 护栏、与 AM2 outbox 无关；AM2 技术方案未规定该参数。删参数 = 签名改 `casRetry(id, epoch, backoff)`，SQL 与行为零变化 |
| B-20 | `order-arena/src/main/resources/application.yml:24,31` | 死配置键 ×2：`app.arena.probe.max-staleness-ms: 120000`、`app.arena.chaos.recovery-grace-ms: 15000` | 代码零 `@Value`/绑定引用。真实阈值：探针失明告警 = Prometheus 规则硬编码 180s（`deploy/alert/prometheus/rules/arena.yml` 的 `time() - oa_domain_probe_last_success_timestamp > 180`），恢复收敛 = 事实驱动无宽限常量。**与 `告警-冗余清理清单-v1.md` R-A09 同项**（该清单口径：真实现分别是 stuck-threshold-seconds、f2-batch），执行时任择一处、不重复执行。三轮设计对照：AM2 技术方案 §6.5/§7.2 均无"宽限期/新鲜度阈值"参数规定，删键无设计冲突 |

> **B-15~17 删除后的语义兜底核对清单**（删前逐条查过）：booking 矩阵 = SQL CAS 的 from 匹配 + BookingStateMachine 穷举测试看护；
> pay 守卫 = SQL `:to <> 'PAID' OR booking_status='ENABLED'`；履约/退款 CAS = 仓储 WHERE state=:from；终态 settledAt = 仓储 CASE。
> 四条执行路径无一依赖被删方法——它们是"仪式层"，删的是死代码，不是守卫。

---

## 3. C 级 —— 修改/简化（有调用，但存在冗余面；动后需小改测试或装配）

> 这一级不是"死代码"，是 ponytail 第 2/6 档"本仓已有/可更短"。收益从高到低排列。

| # | 位置 | 问题 | 建议修改 | 波及面 |
|---|---|---|---|---|
| C-1 | `OrderCreationSteps.java:81-88 vs 128-135`、`90-93 vs 137-140` | `initiateAuthTx/initiateCaptureTx`、`resolveAuthTx/resolveCaptureTx` 两对方法主体逐字相同（仅 PaymentKind 与变量名不同） | 各合并为一个带 `PaymentKind` 参数的方法（`initiateTx(orderId, amount, kind)` / `resolveTx(paymentId, result)`），调用点 4 处改参数 | TwoStepOrderService 4 个调用点；无测试直呼这两对方法 |
| C-2 | `OrderCreationSteps.java:111-126 vs 149-166` | `discardTx/discardEnabledTx` 尾部 8 行（台账→计划→outbox 同事务插入）逐字重复 | 抽私有方法 `insertCompensationWithDiscard(...)` 或把"计划构建 + outbox 插入"提为私有步骤 | 仅本类内部 |
| C-3 | `application/chaos/FaultGate.java:13-23` + `ChaosSwitchboard.java:29-31` | ~~四字段中三个从未被读取~~ **三轮修正**：`PostgresChaosSwitchboardIT:34-35` 实际断言了 `scenarioId()`/`target()`（第一轮 grep 被 `hit.get().scenarioId()` 链式调用漏检）——真正零读取的只有 `ActiveFault.generation` 与 `SessionView.generation` 两个分量 | 收窄：两个 record 各删 `generation` 分量（DB `generation` 列仍是权威，CAS 语义不受影响）；`scenarioId/target` **保留** | 无测试读 generation；ChaosRecoveryService 不受影响。收益趋零（约 -4 行），可选低优先 |
| C-4 | `domain/model/CompensationEvent.java`（state 字段） + `PostgresCompensationOutboxRepository.java:73,131-135` | 记录里的 `state` 字段只写不读：insertPending 硬编码 'PENDING' 入库、claimNext 硬编码 CLAIMED，无任何消费者 | 从 record 删除 state 分量，DB 列仍是权威 | outbox 仓储两处构造改参；无测试断言该字段 |
| C-5 | `PostgresProbeStore.java:22-24,84-98` + `DomainProbe.java:113` | `OpenFinding(id, findingType, entityId, violationDigest)` 四字段只有 entityId 被消费（作 map key 判存在） | `openFindings` 改返回 `Set<String>`（实体号集合），record 删除 | DomainProbe.sync 改用 Set；DomainProbeEpisodeIT 走 scanOnce 不受影响 |
| C-6 | ~~`application/DomainProbe.java:39-41,84-101`~~ **三轮撤回，移入 §4 保留区** | ~~`ScanResult` 返回值无任何消费者~~ | **撤回依据**：`DomainProbeEpisodeIT:130-131` 实际消费——`var result = probe.scanOnce(); assertThat(result.ok()).isFalse();`（`var` 声明遮蔽类型名，第一轮 grep "ScanResult" 漏检）。失败语义断言依赖该返回值，`scanOnce()` 返回值**保留不动** | — |
| C-7 | `domain/model/IdempotencyClaim.java:17` + `PostgresIdempotencyRepository.java:78` | `Replay(resultOrderId, responseDigest)` 的 responseDigest 主链路从不读取（重放只返回订单号）。三轮设计注记：`response_digest` 是 AM2 技术方案 §3.1/§6.2 字段清单成员（"重放返回原结果"），**DB 列与数据契约必须保留**；本项只删 Java record 的未消费分量，不触碰契约 | 从 record 删 responseDigest；DB `response_digest` 列保留（C-2 表字段仍冻结）。〔测试同步〕`PostgresIdempotencyRepositoryIT.java:52` 等值断言改为 `new IdempotencyClaim.Replay(orderId)` | IT 一行 |
| C-8 | `application/PaymentGatewaySimulator.java:26-32` | `authorize/capture` 的 `amount` 参数在 `decide()` 中完全未参与判定（确定性只看 sku 后缀 + F3 命中） | 删去两方法的 amount 参数 | TwoStepOrderService 2 个调用点 |
| C-9 | `infrastructure/persistence/PostgresTradeOrderRepository.java:52-54,62-64,96-98` | 13 列 SELECT 列清单逐字重复 3 份 | 提为私有常量 `TRADE_ORDER_COLUMNS`（若 B-7 先删 findByIntentId 则只剩 2 份，仍值得） | 仅本类 |

---

## 3.5 E 级 —— "能否一行表达"细检（第二轮新增，2026-09-05）

> 判据：对每个多行构造自问"一行/同款能否表达"；能则登记。全部已 grep/通读核实调用与等价性。

| # | 位置 | 冗余形态 | 一行化/去重方案 | 等价性核实 |
|---|---|---|---|---|
| E-1 | `interfaces/OrderController.java:83-88` | `get()` 内联 try/catch 解析 UUID（5 行），与同类已有私有 `parse()`（:170-176）重复 | `UUID orderId = parse(id); if (orderId == null) ...` —— 删内联副本 | 同类同款已有，纯去重 |
| E-2 | `application/PaymentGatewaySimulator.java:26-32` | `authorize/capture` 两方法同体（都转发 decide），且 amount 参数不参与判定（与 C-8 叠加） | 收敛为单个 `decide(correlationId, sku)` 公开方法 | 两者判定逻辑逐字相同 |
| E-3 | `infrastructure/persistence/PostgresChaosInjectionStore.java:107-119 vs 152-162` | `auditOrderRecovered` / `auditSessionRecovered` 两段 SQL 仅 order_id 是否为 NULL 之差 | 合一为 `auditRecovered(sessionId, faultType, orderId可空, detail)` | 两处 INSERT 逐字段对照一致 |
| E-4 | `infrastructure/persistence/PostgresCompensationOutboxRepository.java:139-166` | `serializePlan/parsePlan` 经 `List<Map<String,Object>>` 手工摆键 + Number 强转（~28 行）——Jackson 原生支持 record 序列化 | `MAPPER.writeValueAsString(plan)` / `readValue(json, new TypeReference<List<PlanEntry>>(){})`（~10 行） | 键名 = record component 名（resourceType/deductionSeq/quantity），payload 格式不变；仅本类自读自写 |
| E-5 | `arena-chaos-admin/.../PostgresChaosAdminStore.java:98-110` | `jsonOf` 手写 JSON 拼接 + 转义（13 行）——违反阶梯第 3/5 档（stdlib/既有依赖） | `new ObjectMapper().writeValueAsString(new TreeMap<>(labels))` 一行；jackson-databind 经 starter-web 已在 classpath | TreeMap 入参保序与手工实现一致；入库经 `cast(:labels as jsonb)`，jsonb 本身归一键序；指纹在 Java 侧已先算完，不依赖该 JSON |
| E-6 | `arena-chaos-admin/.../PostgresChaosAdminStore.java:272-287` | `backfillIncident` 查两次：先取 mapping_version（怪异 `long[]` 投影）再回查 fingerprint | 一条查询同取两列，删第二次 round trip 与 `long[]` 投影 | 同行数据，零语义变化 |
| E-7 | `application/TwoStepOrderService.java:150-163` | `driveCreation` 对同一 sealed 层级开两个平行 switch（consumedOrder + finalState 各一遍） | 合并为单 switch 返回 `(orderId, digestSeed)` 对 | 三分支枚举完全同构 |
| E-8 | `infrastructure/persistence/PostgresPaymentRecordRepository.java` | 8 列 SELECT/RETURNING 清单逐字重复 ×3（B-5 删 findByOrder 后） | 提私有常量（对齐 C-9 同款手法） | 纯字符串常量提取 |
| E-9 | `application/chaos/ChaosRecoveryService.java:113-125` | `compensateDuplicate` 的 switch + 空 default 注释（"DISCARDED 已在上面短路"）——早退后枚举只剩两态 | if/else 两分支（-4 行，删自注解式死分支） | CREATED/ENABLED 两态覆盖完备 |

**E 级并入保留区的反向结论**（检查过、判为一行化失败/不值得）：`RefundChainService.refundPaid` 的五行 `txFacade.inTx(...)`——每行已是"一个独立短事务"的最短表达，合并反而违反 M2-11 崩溃窗口设计；`CompensationWorker` 的 try/catch 三分支——各自对应不同终态语义，无同款可复用。

---

## 3.7 待裁决设计事项（非冗余清理，属设计决定；三轮设计文档对照产出）

> 两项都源于"冻结矩阵/声明"与"运行时流程"的冲突或空白，删除代码无法消除问题本身，需要用户在三个选项里裁定方向后再动手。

### （一）B-15~17 领域迁移守卫四方法：删、接线、还是原样

- 事实：`TradeOrder.withBookingStatus/withPayStatus`、`FulfillmentOrder.withState`、`RefundOrder.withState` 全部零调用；实际执行门 = 仓储 SQL CAS。类 javadoc"必经此门"与事实不符。
- 设计依据对照：AM2 技术方案 §3.1 只规定 4 台状态机"迁移表"存在（未规定写路径必须经领域对象）；任务拆解 M2-07 交付边界"状态、命令、迁移表"中"命令"未明确到方法签名。即：**删除不违反设计文字，但正式放弃"领域门"这一实现立场**。
- 选项：
  - **甲（推荐，ponytail 立场）**：删 B-15~17 四方法 + 修正 `TradeOrder` javadoc 为"SQL CAS 为唯一执行门，矩阵 + 穷举测试为契约文档"。零行为变化，测试保持绿。
  - **乙**：保留四方法，仓储写路径改走领域对象门（先 load → withX → requireTransition → 落库）。守卫前移到领域层但每次写多一次读 + 对象组装，代码量净增。
  - **丙**：原样保留，仅改 javadoc 撤销"必经此门"表述。最小改动但死代码留仓。

### （二）`discardEnabledTx` 的 CONFIRMED→CANCELLED 迁移违反冻结矩阵

- 事实链：`OrderCreationSteps.enableTx:100-101` 把履约单推到 CONFIRMED；取消 ENABLED 订单（M2-11 cancel 路径）/F1 补偿时 `discardEnabledTx:157-159` 读当前履约状态后 `casState(当前态→CANCELLED)`。`PostgresFulfillmentOrderRepository.casState:48-55` 的 SQL 只有 `WHERE state=:from`，**无矩阵守卫**——真实流程中该 CAS 会静默成功，把 CONFIRMED 履约行改成 CANCELLED。
- 冲突：`FulfillmentStateMachine:6-8`（引 AM2 v3.0 §3.1）声明 "CONFIRMED/CANCELLED 终态"，矩阵无 CONFIRMED→CANCELLED 边。运行时行为与冻结矩阵不一致。
- 测试盲区：ChaosRecoveryIT/F3ReconcileIT 播种订单均无履约行（`orElse(CONFIRMING)` 落空成 no-op），该边从未被任何 IT 观察到。
- 设计依据对照：AM2 技术方案 §4.1 时序图对"取消 ENABLED 单时履约单去向"无规定；§3.1 只列状态枚举。属**设计空白**，不是落码走样。
- 选项：
  - **甲（推荐）**：确认"废单必须同步取消履约"为正确业务语义（与 `discardTx` 的"DISCARDED + 履约 CANCELLED 同生共死"对称），给矩阵补 `CONFIRMED→CANCELLED` 边（改 `FulfillmentStateMachine` + javadoc；穷举测试自动覆盖）。属解冻 §3.1 声明的设计变更，需用户批准。
  - **乙**：判定矩阵正确（CONFIRMED 终态不可逆），删 `discardEnabledTx:157-159` 三行——履约单保持 CONFIRMED，废单语义由 booking=DISCARDED + 补偿计划表达。代价：DISCARDED 订单挂 CONFIRMED 履约行的状态分裂，且与 discardTx 不对称。
  - **丙**：维持现状，仅记录偏差（"SQL 为执行门、矩阵为文档"立场的延伸后果）。最懒但债留给孩子。

---

## 4. D 级 —— 审查过、判定保留（防误删清单）

| 项 | 看似冗余的理由 | 保留理由 |
|---|---|---|
| `FulfillmentState.NO_ROOM`、`RefundState.REJECTED/FAILED/CANCELLED`、`OutboxState.CANCELLED` | 现有代码**无任何写入路径**（NO_ROOM：扣减失败在写履约单前就抛异常；REJECTED/FAILED/CANCELLED：RefundChainService 只走四步主链；CANCELLED：worker 只落 SUCCEEDED/SKIPPED/DEAD） | AM2 技术方案 §3.1 与落码方案 M2-05"八态冻结"/M2-11 状态机冻结明文包含这些状态；穷举测试把它们当契约看护。删状态 = 违反冻结设计，收益仅几个枚举常量 |
| `TwoStepOrderService.IdempotencyOperations`、`StartStoppable`、`TransactionFacade` | 三者都是"单实现接口"（ponytail 反对项） | 各自是装配期绑定配置值/生命周期的薄缝（IdempotencyOperations 绑 owner/lease/ttl 三个 @Value；StartStoppable 是 ArenaRuntime 的启停扩展点；TransactionFacade 让 RefundChainService 不依赖 Spring 类型，与 OrderCreationSteps 直用 TransactionTemplate 的不对称可辩护为测试缝）。合计 ~30 行，删除需动 ArenaConfig 装配且无净收益。若坚持极简可并入，列为可选不推荐 |
| `oa_chaos_session` 的 DB `default 'PREPARED'`、CHECK 枚举含 PREPARED/STARTUP_REAPED（V3 迁移） | Java 侧删除 reapStartupOrphans 后更"无用" | 迁移是已部署事实源（V3 起追加纪律）；CHECK 放宽枚举无害，改迁移反而制造漂移 |
| `ChaosAdminController` 的 `/chaos/status` 与 `/chaos/scenario-map/backfill` | AM2 主链路（E2E）之外 | M2-17/M2-24 的交付端点，AM3 eval-runner 的消费面（C-6 回填契约）；postgre store 的 `findSession/auditSummary/backfillIncident` 均有真实调用 |
| order-arena 与 arena-chaos-admin 各有一份 `HealthController` | 代码重复 | 两个独立部署单元（C-3 拓扑），共享反而制造跨模块耦合 |
| `TransitionTable` 与 control-app 告警域 TransitionTable 同构 | 跨仓重复 | 类头注释已声明"刻意复制而非跨模块共享"（进程隔离决策），且各自有穷举测试看护 |
| `OrderController.pay/cancel` 里 `request == null` 判断 | @RequestBody 默认 required=true，缺体到不了方法体，看似死防御 | 信任边界的输入校验属 ponytail"不许懒"豁免区；两行换边界稳健性，保留 |
| `ChaosAdminConfig.chaosReaperLifecycle` 匿名 SmartLifecycle（~50 行循环） | 与 ArenaRuntime 的循环骨架同构，看似可抽公共件 | 跨模块共享需引入模块依赖，违背 C-3 进程隔离与"靶场不依赖控制面"惯例（TransitionTable 刻意复制同款理由）；且 admin 侧仅此一个循环，抽象无第二消费者 |
| `DomainProbe.ScanResult`（C-6 撤回归档） | 主链路 ArenaRuntime 忽略返回值，看似可改 void | `DomainProbeEpisodeIT:130-131` 消费 `result.ok()` 断言失败语义（三轮复核发现，第一轮 grep 被 `var` 遮蔽误判） |
| `ActiveFault.scenarioId()/target()`（C-3 收窄后保留面） | 主链路只判存在性 | `PostgresChaosSwitchboardIT:34-35` 断言两访问器值；删它们要改测试且收益归零 |
| `deploy/alert/prometheus/rules/prometheus-rules-checkout.yml` | alert 栈里多一份非 AM2 规则 | AM0 Sloth 烧损率规则落码面（`deploy/alert/eval/eval-scenarios.yml:25` 注明），设计保留 |
| `deploy/alert/prometheus/rules/empty-groups.yml` | 空 groups 占位、无显式引用 | `prometheus.yml` 的 `rule_files: /etc/prometheus/rules/*.yml` glob 实际加载它；且为 195 外部栈宿主侧配置的 SHA-256 对拍存档（`告警-冗余清理清单-v1.md` D 组同裁定），删了破坏镜像可复现性 |
| order-arena 与 arena-chaos-admin 各一份 Postgres IT 基座（同构复制） | ~100 行近重复 | `ChaosAdminPostgresITBase:19-21` 注释已声明"刻意复制而非共享测试夹具——两模块各自独立成镜像，测试自治是边界的一部分"，与 TransitionTable 同款决策 |

---

## 5. 执行外观察（不属冗余，仅记录）

1. **测试缺口①（对应任务拆解 M2-12/M2-13 验收）**：`CompensationWorker` 在本仓**零测试引用**——任务拆解 M2-13 验收"kill/restart 后最终收敛 IT"、AM2 技术方案 §12 L2"两步创单三崩溃窗口补偿收敛"、L4"注入中途容器重启"目前无专门 IT（F3ReconcileIT 的租约过期重领 + ChaosRecoveryIT 的 F1 补偿只覆盖其中部分窗口）。建议补 Testcontainers IT（与本次清理独立推进）。
2. **测试缺口②（关联 §3.7（二））**：取消/F1 补偿命中"已 CONFIRMED 履约行"的路径无任何 IT 观察过（播种单均无履约行）——矩阵缺口因此长期不可见。
3. **注释/文档漂移 ×3**：a) `deploy/alert/docker-compose.yml:88` arena-migrate 注释写"V1~V5"，迁移目录已到 V7；b) AM2 技术方案 §6.6 指标名 `oa_illegal_transitions_current` vs 代码/规则实际 `oa_state_violations_current`（代码 + 规则自洽，纯文档漂移）；c) 下次触碰相关文件时顺手改。
4. **可调键未在 yml 列出**（有代码默认值，非死键，仅可发现性微项）：`idempotency.owner/lease-seconds/ttl-hours`、`api.retry-after-seconds`、`probe.stuck-threshold-seconds` 等既有默认值但未出现在 `application.yml`，排障时不易发现。

---

## 6. 建议执行顺序与验证（等用户放行后执行）

1. **先裁决 §3.7 两项**（B-15~17 三选项、CONFIRMED→CANCELLED 矩阵缺口三选项）——甲/乙/丙的选择决定 B-15~17 与 `discardEnabledTx` 是否动手，其余条目不受影响。
2. 无争议批：A-1 + B-1~14、B-18（仅登记不改迁移）+ B-19 + B-20（与清单 v1 R-A09 同项，任择一处执行）——B-2/B-7 连同其 IT 测试方法/假实现一起删；A-1 连带 FaultGate javadoc 一句。
3. E 级按 E-1 → E-2 → E-3 → E-8 → 其余顺序做（前四项纯去重/常量提取零语义变化）。
4. C 级按 C-1 → C-2 → C-9 → 其余顺序做（前三项纯结构收敛零语义变化；C-3 已收窄为仅删两个 generation 分量，可选）。
5. 每批次跑：`mvn -q -pl order-arena,arena-chaos-admin -am test`（快速单测绿）；
   全部完成后跑完整门：`mvn -q clean verify`（Failsafe IT 实际执行数非零）。
6. 预期净效果：删除 ~240 行死代码 + 一行化/去重 ~70 行 + 简化 ~55 行，
   **零语义变化、零契约变化**（§3.7 若选甲/乙属设计变更，另行验证），全部测试保持绿。
7. 本文档随执行结果回填"已执行/已放弃"标记，并按仓库惯例入 git commit。

## 7. 修订记录

| 日期 | 版本 | 变更 |
|---|---|---|
| 2026-09-05 | v1.0 | 初版：AM2 落码全量 ponytail 审计（A×1 / B×14 / C×9 / D×6 + 观察×2），未动任何代码 |
| 2026-09-05 | v1.1 | 第二轮"能否一行表达"细检：新增 B-15~17（领域迁移守卫四方法零调用，⚠ 设计声明冲突待裁决）、E 级×9（一行化/去重）、D 级×2（信任边界豁免、reaper 跨模块不共享）；摘要与净效果同步更新。仍未动任何代码 |
| 2026-09-05 | v1.2 | 第三轮消费面复核 + 设计文档对照（架构 v1.2 / 任务拆解 / AM2 技术方案 / 清单 v1）：**C-6 撤回**（ScanResult 被 IT 消费，var 遮蔽误判）、**C-3 收窄**（仅 generation 零读取）、新增 B-18~20（死列/死参数/死配置键）、新增 §3.7 两项待裁决设计事项（B-15~17 三选项正式化 + CONFIRMED→CANCELLED 矩阵缺口）、D 级 +5、观察扩为 4 条（M2-12/13 IT 缺口、指标名文档漂移等）、规模口径说明（AM2 ≈ 9,800 行 ≠ 全仓八万行）。仍未动任何代码 |
