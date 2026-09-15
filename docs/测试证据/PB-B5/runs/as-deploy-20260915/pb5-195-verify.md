# PB-B5 195 部署与真机验证台账 + Phase B 结项（2026-09-15）

批次：Phase B 收官波——mutation 对账循环 + reschedule 闸 + 事件重建断言（§1/§2.8/§2.9/§3.3）
部署机：195，OVERLAY 纪律，md5 59099cd7… OK，.env intact（备份 /tmp/env-backup-b5-*）
结果：**UNKNOWN 对账链与悬挂 ESCALATE 真机双通 + Phase B 退出准则两条全达成 + 回归 2105/2105 绿**

## 一、交付面

- **OperationReconciler**（interval=PT1M 随容器启停）：① TTL 孤儿化驱动（HELD 过期→ORPHANED，只驱动不让渡）；② UNKNOWN→RECONCILING 显式穿越→裁决（VERIFIED=确认事实在档→释放闸放锁→COMPLETED；RETRYABLE=outbox 同行回 PENDING 重派、锁保持；ESCALATED=锁保持至人工裁决）；③ PREPARED 悬挂 ESCALATE（无 outbox 行或超阈值未派发 → PREPARED→ESCALATED——§2.8「不静默丢、不自动重执」）。裁决开关 `app.alert.mutation.reconcile-verdict`（默认 VERIFIED）。
- **MutationActiveGate**：「reconcile 先于 reschedule」闸口（§3.3 mutation 面同律）——run 存在 BUSY 态 operation 时重派/回收决策必须让位。Worker recoverExpired/RunReconciler 重派分支的实际接线登记 Phase D（首个真实 mutation 出现时，生产解封前 operation 行恒零）。
- **OperationTimelineRebuilder**：OPERATION_* 事件序 → 逻辑状态视图（状态机逐边重放）——事件缺环（跳越中间态/起点非 PREPARED）重建必抛 IllegalTransitionException = 审计不完整可检测。
- **OperationStateMachine** 增 `PREPARED→ESCALATED` 边（§2.8 悬挂出口）。

## 二、验证

- 新增 Pb5ReconcileRebuildTest 6 用例绿（VERIFIED 裁决放锁至 COMPLETED / RETRYABLE 同行重派锁保持 / 悬挂 ESCALATED 锁保持 / 闸口活跃拒+终态放 / 重建：成功全链与 UNKNOWN→RETRYABLE 重派走链自洽、缺环必炸）。全量回归 **2105/2105 绿**（26 跳过=Docker IT）。
- **195 真机双演示**（循环自动走链，非人工驱动）：
  - UNKNOWN 链：种子 UNKNOWN+HELD 锁 → 90s 内 `op_status=COMPLETED`、`locks=0`、事件序 `OPERATION_RECONCILING → OPERATION_RECONCILED_VERIFIED → OPERATION_LOCK_RELEASED → OPERATION_COMPLETED` 落账；
  - 悬挂链：种子 PREPARED（30 分钟前）无 outbox → `hanging_status=ESCALATED`、**esc_locks=1（ESCALATED 锁保持至人工裁决）**；
  - 循环启动行在案（`verdict=VERIFIED hanging=PT10M`）；health 200、FAILED=0；演示行清场（事件保留）。

## 三、Phase B 结项（§5 退出准则对账）

| 退出准则 | 状态 |
|---|---|
| 全链 dry-run 可从事件重建 | ✅ B4 真机演示（DISPATCHED→…→COMPLETED 五事件）+ B5 UNKNOWN 链（四事件）为活体样本；`OperationTimelineRebuilder` 缺环必炸断言钉审计完整性（pbT01/pbT02） |
| cancel/aborting 链与 reconcile 先于 reschedule 语义验证通过 | ✅ A11 既有闸 + `MutationActiveGate` 语义钉死（pbG01）；UNKNOWN/悬挂双链真机穿越；cancel→operation CANCELLED_BEFORE_DISPATCH 联动随 Phase C 审批撤销面（已登记） |

**Phase B 五波全部收官**：B1 账本层（V114）→ B2 权威解析（V115）→ B3 资源协调（V116）→ B4 Outbox/派发/dry-run Runner（V117/V118，BA-147）→ B5 对账/闸/重建。真实资源零 mutation（A10 dry_run DDL+域双闸全程未破）。

**遗留登记（随解锁窗口归位）**：① grant 验证/CAS/撤销联动随 Phase C 审批面换真锚；② RETRYABLE 真机重派演示随裁决开关翻转可复验；③ MutationActiveGate 接入 Worker/RunReconciler 重派分支随 Phase D；④ 真实 resource_probe 随 Phase D 替换 dry-run 裁决。
