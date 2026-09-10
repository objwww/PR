# EX-A2 契约：F10/F11/F12 租约·心跳·取消 + 提交栅栏（P1-04）

卡源：`docs/告警-执行者ABC-改造技术方案.md` §〇 EX-A2；本文件为该卡的实现契约与验收对照。

## 1. 摸底结论（2026-09-10 实测）

| 面 | 现状 | 与卡的差距 |
|---|---|---|
| F10 回收 | `RcaWorker.recoverExpired`：`findExpiredLeased`（读）→ 逐 task `tasks.update`（**无前置条件的整行覆盖**） | 竞态窗口：读后原 worker 心跳续租/他人重领，覆盖写照落——活租约被夺走。需四条件同语句原子回收，0 行=竞态失败 |
| F11 提交栅栏 | `finishTask` 收尾单事务：`requireCurrentLease`（epoch 条件 UPDATE，行锁持至提交）→ 读 fresh → run `FOR UPDATE` → generation fence → 全部落档——P1-04"同事务准入"已具形；但栅栏语义散落在编排器内 | 需沉淀为独立 `LeaseFence` 类（R7 ActionGuard 复用件）；"影响行数=0=失去提交权，晚到结果不得入有效证据快照"成文 |
| F12 取消 | `CommandService`（M5-14）：CANCEL 两阶段（先持久化再生效），revision 锚 = `rca_run.last_event_seq`；但 phase2 的"revision 读→run.update"是 **check-then-act**（`runs.update` 仅 WHERE id，无条件写） | 竞态：finishTask 先提交 SUCCEEDED（`update` 不推进修订号），cancel 照样覆盖 CANCELLED——两竞态结果不一致。需 `updateIfRevision`（CAS）映射既有 `last_event_seq`（**不新造 revision 列**）；markRunRunning 同理需条件化（取消后 worker 迟到的 QUEUED→RUNNING 会复活终态 run） |
| 状态机 | `RcaRunStateMachine` 三活跃态均可→CANCELLED（V7 ck 九态含 CANCELLED） | 零迁移 |
| claim 栅栏 | `CLAIM_SQL`/fake 同语义：只领活跃 run（QUEUED/RUNNING/REPORTING）任务 | 取消后零新领取资格已在列级成立（IT 钉证） |

## 2. 实现契约

### 2.1 F10 原子回收（`RcaTaskRepository.reclaimExpired`）

新端口方法，四条件同语句：`id` + `state='LEASED'` + `lease_until < :now` + `lease_epoch = :expectedEpoch`；
SET target（RETRY_WAIT=活跃 run 重排 / STALE=死 run 不复活，M4-07）+ 退避 available_at/ready_since + 清租约列；
**影响行数 0 = 竞态失败**（原 worker 心跳已续 lease_until / 已被重领 epoch+1 / 他回收者已收敛态），调用方零补救不计数。
`RcaWorker.recoverExpired` 改走该条件写（run 活跃性判定仍回收前读——误判窗口由下次回收循环自愈，与现状等价）。

### 2.2 F11 LeaseFence（提交权栅栏，独立类）

`com.objwww.pr.control.alert.domain.lease.LeaseFence`：`acquire(tasks, taskId, owner, epoch)` →
`Optional<LeaseFence>`（empty = 失去提交权）。P1-04 成文：requireCurrentLease 条件 UPDATE
取得的行锁持有至当前事务提交——同一事务内"结果业务准入、任务状态、事件落库"全部处于
owner/epoch/run 状态保护下；empty 时调用方**一行不写**，远端迟到响应只能作审计/对账资料，
不得进入有效证据快照。`RcaRunOrchestrator.finishTask` 改用 LeaseFence（行为不变，契约成文+复用件沉淀）。

### 2.3 F12 取消线性化（`RcaRunRepository.updateIfRevision`）

新端口方法（CAS）：`UPDATE rca_run SET 状态列…, last_event_seq = last_event_seq + 1
WHERE id = :id AND last_event_seq = :expectedRevision AND state IN ('QUEUED','RUNNING','REPORTING')`。
修订锚 = 既有 `last_event_seq`（M5-14 命令机制，零新列）；活跃态守卫把 finishTask 类
非事件型状态推进也纳入栅栏。影响行数 0 = 并发已先胜 = 失去提交资格。

- `CommandService` CANCEL phase2：`runs.update` → `runs.updateIfRevision`；CAS 败者 →
  `REJECTED_STALE`，**零事件零状态变更**（事件只在 CAS 胜出后落）——取消线性化点 = CAS 成功的提交；
- `markRunRunning` 条件化：worker 在领取事务内读 `currentRevision` 随 `ClaimedWork` 携带，
  `markRunRunning(run, expectedRevision, now)` 走 `updateIfRevision`——取消在领取与开跑之间
  落地时 CAS 败（run 保持 CANCELLED 不复活）；worker 继续执行的晚到结果由 finishTask
  generation fence 收敛 STALE（已获资格尽力取消、晚到结果隔离、费用照对账，P1-04）；
- 不承诺 HTTP 请求与取消事务绝对同时（命令两阶段语义不变，FUT-33）。

## 3. 零迁移声明

`last_event_seq`（V14 在列）、CANCELLED（V7 九态在列）、claim 活跃 run 谓词（V12 uq 同集）全部在场。
回滚 = revert 代码，无库变更。

## 4. 测试分层（§7.2）

| 层 | 环境 | 内容 |
|---|---|---|
| L0 | 本机 `mvn test` | ①`RcaWorkerTest`：reclaim 条件语义（心跳已续不回收/死 run→STALE/竞态败者不计数）；②`CommandServiceTest`：双 cancel（异幂等键同 revision）第二者 REJECTED_STALE 零事件；markRunRunning CAS 败者 run 不复活；③编译红 = 新端口 API 缺失 |
| L1 | 195 真 PG `ExA2LeaseCancelFenceIT` | ①F10 三案：回收胜（RETRY_WAIT+清租约+epoch 不动）/心跳先续→0 行/epoch 已进→0 行；②两连接序：A 回收→B 重领→A 提交=LEASE_REJECTED 零落档（结果/报告零行）；③Cancel vs finishTask 真并发（双线程栅栏）：结果一致（FAILED+REJECTED_STALE ∨ CANCELLED+STALE_GENERATION 二选一，无中间态）；④取消后 claimNext 零资格；⑤双 cancel CAS 一真一拒 |

## 5. 前向依赖

R7 ActionBudgetContext/ActionGuard 复用 LeaseFence（卡片登记，本卡不实现）。

## 6. 完成证据（2026-09-10 实测）

| 层 | 结果 | 证据 |
|---|---|---|
| L0 聚焦 | CommandServiceTest 10/10 + RcaWorkerTest 16/16（含 5 新案） | 本机聚焦跑（52/52 全绿） |
| L0 全量（本机） | 996/0/21 BUILD SUCCESS | `m6-ev/exa2-l0-local.log` |
| L1 targeted（195 真 PG） | 996 UT + 5/5 IT 零跳 MVN_RC=0（run1 抓 B-15 修订锚误标定，run3 全绿） | `m6-ev/exa2-l1-targeted3.log` / `-wrap.log`（run1 留档 `exa2-l1-targeted-run1.log`） |
| L1 官方全量（195 真 PG） | **15+996+149 全零跳** BUILD SUCCESS MVN_RC=0（IT 144→149） | `m6-ev/exa2-l1-verify.log` / `-wrap.log` |
| 同步恒等 | 12 文件 LF 恒等 tar；双侧 sha256 探针恒等（ExA2LeaseCancelFenceIT `8bb96cbf…`、RcaRunOrchestrator `fcdc34eb…`） | `m6-ev/exa2-sync-list.txt` / `exa2-sync.tar.gz` |

验收四项逐条对照见 `docs/告警-EX执行日志-20260910.md` §EX-A2 收口对照——两连接 barrier（A 回收/B 领取/A 提交=0 行）、失租结果零落档、Cancel-vs-finish 真并发一致性、取消后零新动作资格全部真 PG 实证。零新产品缺陷（B-13/B-14/B-15 均测试面）。
