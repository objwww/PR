# EX-A3 契约：F08/F09 可恢复驱动（P1-03 四阶段恢复语义）

> 卡源：docs/告警-执行者ABC-改造技术方案.md §EX-A3。驱动循环与所有权不变
> （推进→领取 READY→执行→回执→再推进至终态；driver task 独占 run 执行权）；
> **恢复语义整段替换——删除"无回执按幂等键重驱"**。实现即法，本文件为验收口径。

## 1. checkpoint 最少字段（P1-03）——逐项落点（不为省列而省信息）

| P1-03 字段 | 落点 | 性质 |
|---|---|---|
| run | rca_tool_invocation.run_id | 既有 |
| round / attempt | attempt_id（=worker 持久铸造的 RcaAttempt.id；round 序数即 rca_attempt.attempt_count） | 既有 |
| task | rca_tool_invocation.task_id | 既有 |
| action_seq | rca_tool_invocation.action_seq（EX-A4a 接线=open 时 call_seq 锚定） | 既有 |
| request_digest | rca_tool_invocation.action_digest（本侧预计算，崩溃悬挂可查） | 既有 |
| dispatch 状态 | rca_tool_invocation.state（PENDING/SUCCESS/FAILED/UNKNOWN 四态） | 既有 |
| **result_ref** | rca_tool_invocation.result_ref（uuid，指向 rca_evidence.id） | **新增 V38** |
| reservation_id | **可推导恒等**：ReservationKey(run_id, task_id, attempt_id, call_seq, TOOL_CALL)——PostgresRunBudgetLedger 以同组合键持久预留（PROVISIONAL 崩溃存续+对账面 M4-37 已建），无冗余列 | 既有（映射声明） |
| driver_epoch | rca_attempt.lease_epoch（经 attempt_id join） | 既有（映射声明） |

V38 迁移：`alter table rca_tool_invocation add column result_ref uuid;` +
`(run_id, task_id)` 恢复读索引。编号 rebase 冻结纪律同前卡。

## 2. 四阶段恢复语义（NativeInvestigationExecutor.drive 整段替换）

驱动前置状态分诊（逐 DAG 任务；**领养面：LEASED/RUNNING 孤儿不再跳过**）：

| 阶段 | 现场（checkpoint 判定） | 恢复行为 |
|---|---|---|
| ④ 任务与结果已提交 | task DONE | 跳过驱动（结论由既有 Claim/报告面重放读取） |
| ③ 结果已落库、任务未完成 | 存在 SUCCESS 行且 result_ref 指向在库证据（task 为 RUNNING 孤儿） | 从 result_ref 幂等收尾：RUNNING→DONE，**零触网零新账本行零新证据** |
| ② 已取得发送资格、结果未知 | 存在 PENDING 悬挂行（driver 崩溃于 invoke 在途） | 先 `fail(UNKNOWN, TRANSPORT_UNKNOWN)` 诚实归档（**预算占用不动**：reservation 保持 PROVISIONAL 等对账）；随后按**新物理请求**重驱——新 call_seq、新预算预留（承认可能重复消耗，不默认免费重发）；只读工具重查约束在 Run 冻结时间窗内、落**新观察记录**（新账本行+新证据） |
| ① 尚未取得发送资格 | 无任何在途/终态行（task READY；或 LEASED/RUNNING 且只有 FAILED/UNKNOWN 终态行） | 新 driver 重新经预算门/状态迁移后驱动；FAILED 终态回执的 RUNNING 孤儿=已知失败→RUNNING→DEAD（缺源降级，**不重复调用**） |

不变量：
- **无永久 RUNNING**：恢复驱动一遍后同 run 的 DAG 任务全部终态（DONE/DEAD）。
- **不重复调用**：阶段③/④ 恢复零新物理请求；阶段② 的重驱必留新行（账本可证：旧行 UNKNOWN+新行成对，不存在静默复用）。
- **call_seq 跨 attempt 单调**：本轮起始 = 该 run 既有最大 call_seq+1（checkpoint 排序面稳定）；阶段④ 已提交（DONE/DEAD）任务跳过不占号——call_seq 计物理请求，不计任务槽位。
- 上游 EX-A4a 账本纪律全数保留（insert 先 succeed 后/UNKNOWN 分岔/宽限回收扫描互补不冲突）。

## 3. 交付面

1. **port**（RcaToolInvocationLedger）：`record InvocationRecovery(operationId, callSeq, attemptId, actionDigest, state, resultRef)`；`findRecoveryByTask(runId, taskId)`（默认空表=无在途知识）；`markResultRef(operationId, evidenceId)`（默认 no-op false——生产 PG 实现必落；恢复语义测试的假件必须覆写）。
2. **PG 实现**：两方法落 V38 列；markResultRef 带 `where state='PENDING'` CAS（succeed 前调用，succeed 后不可改）。
3. **agent**（SingleToolEvidenceAgent）：`evidence.insert` 后 `ledger.succeed` 前调 `markResultRef`（缝隙窗=阶段②，诚实）。
4. **executor**：构造器增账本依赖（生产装配 AlertFlowConfig 单例 PG 账本直通）；investigate 逐任务先读 checkpoint 再分诊。
5. **范围外**：Am4ShadowTrigger（一次性影子触发，无恢复主张）；写工具远端幂等契约（未来卡）；预算对账 Reconciler 本身（M4-37 既有）。

## 4. 测试分层（§7.2）

| 层 | 内容 |
|---|---|
| L0 | NativeInvestigationExecutorTest 四边界杀进程恢复案（b1 开始即杀→重驱新行；b2 发出后杀→旧行 UNKNOWN+新物理请求成对+预算占用保留；b3 结果落库后杀→result_ref 幂等收尾零触网；b4 已提交→零动作）+ 无永久 RUNNING + FAILED 孤儿→DEAD 不重复调用 + call_seq 单调；MetricsAgentTest 次序钉扩展（insert→markResultRef→succeed） |
| L1 | 具名 IT `ExA3DriverRecoveryIT`（真 PG）：PG 账本+PG 预算真实行上四阶段逐格命中；result_ref FK 面；恢复不重复调用账本可证 |
| 195 | exa3-sync（LF+双 sha256 探针）→ targeted IT → 官方全量 `mvn verify` 零跳 |

## 5. 完成证据（收口回填，2026-09-10）

- L0：`m6-ev/exa3-l0-local5.log`——**1016 tests, 0 failures, 0 errors, 21 skip（无 docker 真 PG IT 惯例）BUILD SUCCESS**；红相留痕 local1/local2（编译）、local3（B-19 双红）、local4（预算钉红）
- L1 targeted：`m6-ev/exa3-l1-targeted3.log`——**ExA3DriverRecoveryIT 5/5 + ExA4aNativeCorrectnessIT 5/5 + Am6NativeFullChainIT 2/2，BUILD SUCCESS**（run1 2/5=B-20、run2 暴露 driver 重领取缺陷，三轮收敛留痕 targeted1/2/3）
- L1 官方：`m6-ev/exa3-l1-verify.log`——**UT 1017/0/0/0 零跳 + IT 159/0/0/0 零跳，shared 15/0/0/0，BUILD SUCCESS**（verify run1 抓获 B-21 → V39 修复 → verify2 全绿）
- 同步：`m6-ev/exa3-sync-list.txt`（13 文件 LF 零 CRLF）+ `m6-ev/exa3-sync-hashes.txt`（tar `ea247727…` + 探针双侧全等；B-20/驱动复用/V39 三次补送单文件探针全等）
- Bug 记录：执行日志 EX-A3 段——**B-19**（call_seq 计物理请求不计任务槽位：产品语义收窄 + 测试手术补全）、**B-20**（AgentReplayRunner REPLAY_MISS 绝不降级活执行，测试前提错）、**B-21**（V27 operator_command.state varchar(16) < REJECTED_FORBIDDEN 18 字符——潜伏产品缺陷，V39 拓宽修复）
