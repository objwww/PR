# PB-B4 195 部署与真机验证台账（2026-09-15）

批次：Phase B 第四片——Transactional Operation Outbox + Dispatcher + dry-run Runner（§2.8 R2）
部署机：195，OVERLAY 纪律，md5 a053d8af…（V117 批）/ 8de6dfc6…（V118 修正批），.env intact
结果：**V117/V118 真机应用 + 派发循环真机全链自动走通（PREPARED→COMPLETED 五事件落账+放锁）+ 回归 2099/2099 绿**

## 一、交付面

- **V117 operation_outbox**：派发指令账本——与 operation 1:1（unique(operation_id)；RETRYABLE 重派 = 同行回 PENDING）；租约领取面（PENDING 公平序 + SKIP LOCKED + lease_epoch，at-least-once）+ CLAIMED 租约回收面。
- **OperationPlanner（消费模板 B4 形态）**：§2.8 五步同事务——grant 验证/CAS 两步以 `DRY_RUN_SENTINEL` 事件锚占位（Phase C 换真锚）；INSERT operation(PREPARED) → 领锁（BUSY = 抛 Rollback 整体回滚）→ INSERT outbox → intent OPEN→PLANNED（CAS）→ OPERATION_PREPARED 事件。**任一步失利零残留**（DirectTx 单测钉"回滚点之后零发生"，行级原子性由真事务承担）。
- **OperationOutboxDispatcher**：claim → 幂等派发（当前态非 PREPARED/RETRYABLE = DISPATCH_SKIPPED 不重执）→ dry-run Runner 结局走状态机：EXECUTED → DISPATCHED→ACKNOWLEDGED→VERIFIED（释放闸放锁 + OPERATION_LOCK_RELEASED）→COMPLETED；TIMEOUT_UNKNOWN → UNKNOWN（**锁保持 BUSY，不猜 FAILED**，RECONCILING 归 B5）。interval=PT5S 随容器启停；生产 inert（planner 默认关，无行可派）。
- **DryRunActionRunner**：真实外部副作用物理不存在（A10）；非 dry_run operation 构造级拒绝；行为开关 `app.alert.mutation.runner.behavior`（SUCCEED/CHAOS_TIMEOUT）。
- compose 透传 `APP_ALERT_MUTATION_DRY_RUN_PLAN_ENABLED`（默认 false）/ `APP_ALERT_MUTATION_RUNNER_BEHAVIOR`（默认 SUCCEED）。

## 二、验证

- 新增 11 用例绿（Pb4OutboxChainTest 9：PLAN_DISABLED/NOT_RESOLVED/LOCK_BUSY 零残留/成功五步全落含 DRY_RUN_SENTINEL/CAS 失利回滚/成功全链含释放闸/CHAOS→UNKNOWN 锁保持/重投幂等跳过/租约回收重领 + V117 契约 2）。全量回归 **2099/2099 绿**（26 跳过=Docker IT）；架构门 21/21。
- **195 真机全链演示**（种子 PREPARED operation + PENDING outbox + HELD 锁 → 真实 dispatcher 自动走链，15 秒内完成）：
  - `op_status=COMPLETED`；`outbox=DISPATCHED`；`locks=0`（释放闸放行）；
  - 事件按 seq 落账：`OPERATION_DISPATCHED → OPERATION_ACKNOWLEDGED → OPERATION_VERIFIED → OPERATION_LOCK_RELEASED → OPERATION_COMPLETED`；
  - dispatcher 循环启动行在案（`interval=PT5S owner=control-1-dispatcher`）；flyway **118|true**；health 200；FAILED=0；演示行清场、OPERATION_* 事件保留（append-only 审计面，全链可从事件重建的活体样本）。

## 三、BA-147（当场暴露当场修）

V116 的 `ck_rca_operation_prepared` 恒等式两臂互换（PREPARED 态被要求 prepared_at 为空，与域 `prepare()` 铸造即盖时点反向）——V117 演示探针第一发即被拒，生产零影响（解封前 operation 行恒零）。V118 单向约束修正（`prepared_at is not null`），探针复跑全绿。预防措施入库：DDL 布尔恒等约束必须配真值表注释 + 新约束上线即配真机种子探针。

## 四、退出准则进度（Phase B §5）

- 「全链 dry-run 可从事件重建」：本波真机演示即活体样本（五事件序 + 三账本行状态一一对应）；B5 出重建断言测试收口。
- 「cancel/aborting 链与 reconcile 先于 reschedule」：B5。
