# PA-A1 195 部署与真机验证台账（2026-09-15）

批次：Phase A 第一片（V111 进度双列 + LIVE_BUT_STUCK 档）+ 伴生 bug 修复（BA-146 critical 铸 run 死信）
部署机：195（146.56.195.225），/opt/build/pr OVERLAY 纪律（无 --delete，.env 两次备份 intact=OK）
结果：**部署成功 + PA-A1 真机行为验证通过 + 伴生 P0 bug 当场修复并实证**

## 一、部署时间线

| 时刻(UTC) | 动作 | 结果 |
|---|---|---|
| 08:55 | V111 migrate + 双 jar 部署（md5 f4255faa… 对拍 OK） | `flyway 111\|true`；rca_attempt 双列 `timestamp with time zone\|YES` ×2；`ck_rca_run_completion_kind` 含 `LIVE_BUT_STUCK`；存量 66 行 attempt 双列全 NULL（迁移语义正确）；Started 14.7s、health 200、FAILED=0 ERROR=0 |
| 08:58 | 探针1 warning 非白名单 | PROCESSED；路由 BUCKETED_HOLMES 不铸 run（设计行为） |
| 09:12 / 09:22 / 09:34 / 09:36 / 09:38 | 探针2-6 **critical** 白名单/复燃 | **全部 DEAD_LETTER**：`attempt-exhausted: db-error: DataIntegrityViolationException`（5 次重试全败） |
| 09:4x | PG 日志定位：`timestamp out of range: "169104627-12-11 19:08:16+00 BC"`（重试节奏 10s 与死信窗口完全吻合） | 根因=BA-146（见下） |
| 09:46 | 修复部署（md5 0b7c3956…，单文件 PostgresRcaRunRepository） | Started 14.1s、health 200、FAILED=0 |
| 09:48 | 探针7 critical 复燃（修复后同路径） | **PROCESSED → run 铸成 → SUCCEEDED → 报告在档（1）**；attempt `st=09:48:14.419 act=09:48:33.057 prog=09:48:14.419` |
| 09:51 | resolved 收口注入 | 202 受理 |

## 二、BA-146：critical 告警铸 run 必死信（P0，2026-09-08 起潜伏）

- **现象**：severity=critical 的告警投影铸 run 时事务五次重试全败 → alert_inbox DEAD_LETTER，**最重要的告警永远不会被调查**。195 历史死信同因：2026-09-08 17:08、2026-09-09 01:02（部署前已存在，非本轮回归）。
- **根因**：`SlaPolicy.priority("critical")=PRIORITY_CRITICAL → sla.deadline=Instant.MAX`；`IncidentProjector:359 / IncidentWaitingRedrive:120 / RcaRunOrchestrator:368` 调 `fixReconcileDeadlineIfAbsent` 直接 `Timestamp.from(Instant.MAX)` 绑定 → micros 溢出环绕成负值 → PG `timestamp out of range`。`PostgresRcaTaskRepository#deadlineParam`（line 325-334，注释自称"195 真跑实测"）早已为 rca_task 建立同律映射，但 rca_run 侧漏配。
- **修复**：`PostgresRcaRunRepository` 写侧 `deadlineParam`（MAX → PG 原生 'infinity' 字面量 + CAST timestamptz，镜像任务仓储纪律）；读侧 `instantOf` 将 pgjdbc infinity 哨兵（Long.MAX_VALUE 毫秒）归一化回 `Instant.MAX`。三个调用方同咽喉一次修复。
- **实证**：修复前同 payload 五重试死信；修复后同路径 2 秒铸 run、run SUCCEEDED、报告 1 份在档。
- **预防**：任何 `Instant` 绑定 PG timestamptz 的新仓储语句必须过 MAX→infinity 映射；此律已写进两处仓储 javadoc 互相引用。

## 三、PA-A1 真机行为证据（修复后 run 0c5ee6e1，2026-09-15 09:48）

| 语义列 | 值 | 证明 |
|---|---|---|
| started_at | 09:48:14.419 | attempt 铸造 |
| last_activity_at | 09:48:24.351 → 09:48:33.057 | **心跳回写实机生效**（act > st，且随时间推进；attempt 终态后停止——`WHERE status='STARTED'` 条件写正确） |
| last_meaningful_progress_at | 09:48:14.419 = started_at | insert 即初始化；本次 run 19 秒快速完成、未产生检查点 APPLIED 提交 → prog 不动（**语义诚实：没有检查点推进就没有"有效进展"**，这正是 R5 评审要的行为——LLM/快速路径不虚报进展）；检查点驱动 run 的推进归单元测试 RunReconcilerLiveButStuckTest + 后续主模式 run 观测 |
| 终态后 | act/prog 冻结在终态前值 | 终态 attempt 不再被心跳改写 |

LIVE_BUT_STUCK 档装配面：195 现行 mode=ALERT_ONLY（.env 无 RECONCILE 键），新档只分类告警不终止；`RUN_LIVE_BUT_STUCK` 终止路径另受 AUTO_EXPIRE 门（本轮代码收紧：与 HARD_DEADLINE_EXPIRED 同线灰度）。

## 四、如实登记（NOT_RUN / 遗留）

- 本机无 Docker：Testcontainers 真 PG IT（PostgresRunReconcilerIT 增量）NOT_RUN，本台账以 195 真机行为验证替代（更强）。
- meaningful progress 的真机推进证据待主模式（PRIMARY enabled）长 run 观测；本轮 19s 快速 run 不产生检查点提交属预期。
- 探针遗留：09:12-09:38 五条 DEAD_LETTER 行保留作 BA-146 证据（合成探针，不清理）；ArenaOrderStuck/Pa1VerifyProbe 两个合成 incident 为 FIRING 遗留（order-arena 一个属白名单演示族）。
- log_statement 已还原 'none'（诊断窗临时开启）。
