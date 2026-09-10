# 告警-EXA4a-必要正确性契约（F05/F13/F16/F17+F06）

> 卡源：`docs/告警-执行者ABC-改造技术方案.md` §EX-A4a（v2.0）。**真实 LLM A 门之前必须绿。**
> 执行纪律：马尾辫（最懒可行解）+ TDD 红绿 + §7.2 验收分层（L0 单测 / L1 真 PG IT / 195 官方 verify）。
> 状态：实现中（2026-09-10）。收口证据见 §7（实现完成后回填）。

---

## 1. F05 —— 快照成员精确读取（NativeRcaAgent）

### 1.1 现状缺陷

`NativeRcaAgent.investigate` 黑板 = `evidence.findByRunId(runId)` 全量证据按 scope 过滤。
冻结快照（V16）落表后，**迟到证据仍可漏入断言推导**——"固定 Snapshot 回放可比较"的
黑板契约被架空；且无任何完整性校验（成员行丢失/证据行缺席静默跳过）。

### 1.2 契约（实现即法）

1. **黑板 = 冻结成员，非全量证据**：`investigate` 先
   `snapshots.find(runId, snapshotDigest.value())`（缺快照行 = IllegalStateException
   fail-closed）→ `membersOf(snapshot.snapshotId())` → 逐成员 `evidence.findById`
   （读路径自带五步第④步 verify；缺证据行 = IllegalStateException
   **缺成员显式失败**，不静默跳过）。
2. **成员身份一致性校验**：证据行的 evidenceType/payloadDigest 必须与成员行相等
   （不等 = 快照完整性破坏，显式失败）。
3. **断言注记过滤不变**：scope 的 claim_key 缺席仍跳过（原始数据非断言）；
   inputDigest 比对输入（EX-A0 F04）不变。
4. **P1-05 旧快照不可追加成员**：`freeze` 幂等（同 (run,digest) 返回 false 且
   零成员写入）已有；行为钉 = IT 断言冻结后新增证据、同 digest 再冻结 false、
   `membersOf` 计数不变。
5. 顺序确定性：`membersOf` evidence_id 序稳定 → 断言输入序确定 → Claim 指纹可复现。

## 2. F13 —— 活跃集合补 REPORTING（唯一索引/Java/SQL 三面统一）

### 2.1 现状

Java `RcaRunState.isActive()` 与 V12 `uq_rca_run_active_incident` 谓词**均已含
REPORTING**；唯一掉队面 = `PostgresRcaRunRepository.findActiveByIncidentId` 的 SQL
`state IN ('QUEUED','RUNNING')`。fake（AlertInMemoryStores.Runs）走 isActive() 已正确。

### 2.2 契约

1. SQL 补 `'REPORTING'`——三面（Java/V12/SQL）唯一判定源一致。
2. 语义收益：REPORTING 期间 IncidentProjector/FallbackService 的"已有活跃 run 不再铸"
   守卫恢复有效（否则硬插第二活跃 run 撞 uq 索引 23505——守卫与约束打架）。
3. IT 案：REPORTING 态 run 被 `findActiveByIncidentId` 读到；终态 run 读不到；
   REPORTING 期间同 incident 再 insert 活跃 run 被 uq 索引拒（23505）。

## 3. F16 —— 调用账本五缝（SingleToolEvidenceAgent 全段纪律）

### 3.1 五缝与缝法

| 缝 | 缝法 |
|---|---|
| catch 覆盖全段 | investigate 重排单段 try：open→invoke→parse→series→insert→succeed 全在内；三 catch 分岔见 3.2 |
| 先持久结果引用再 ledger.succeed | `evidence.insert` 先行、`ledger.succeed` 后置；缝隙窗崩溃 = PENDING 悬挂 + 证据在账（UNKNOWN 回收后可对账，result_ref 恢复归 EX-A3） |
| attempt 持久生命周期 | DAG 驱动 attemptId = worker 持久铸造的 `RcaAttempt.id`（`NativeInvestigationExecutor.execute` 已收 `attempt` 参数直通）——账本行引用真实持久 attempt，零新表零随机 UUID |
| PENDING 回收扫描 | port 增 `reclaimPendingOlderThan(Instant cutoff)`（throwing default，假件零破坏）；PG = 单语句 `UPDATE ... WHERE state='PENDING' AND started_at < :cutoff` → UNKNOWN/TRANSPORT_UNKNOWN；挂在 `RcaWorker.markHangingInvocationsUnknown`（BA-13② 同律第三账本），cutoff=hangingGrace |
| UNKNOWN 语义（EX-A0 契约） | 未知 RuntimeException（非两族已分类）→ ledger **UNKNOWN**（结果未知不假 FAILED）+ 重抛；预算门对非模型可见族已全额退款（releaseOn 语义不变） |

### 3.2 错误两族分岔（重排后）

- 模型可见族（ToolModelVisibleException）→ ledger FAILED + 十码归因 + doom.record(false)
  + AgentResult(FAILED, 归因码名)。
- 控制面终止族（ToolControlPlaneException，含 VALIDATE_ONLY 不可达/序列数超限）→
  ledger FAILED + 归因 + doom.record(false) + **原样重抛**（drive() 降级 DEAD）。
- 预算拒（BudgetExhaustedException）→ open 未发生（准入先于 remote），无账本行，
  返回 FAILED/BUDGET_EXHAUSTED（不变）。
- NO_DATA：调用成功零序列 → ledger.succeed（回执=成功）→ NO_DATA（不变）。

### 3.3 action_seq 接线

`ledger.open` 落 `action_seq = call_seq` 同值（EX-A0 契约本阶段：一逻辑动作=一物理
请求；EX-A3 恢复语义引入重驱分叉时再拆两列语义，迁移已备 V36 可空列）。

### 3.4 序列数上限（与 F17 同族）

`dataSeries` 后 `series.size() > 1000` → ToolControlPlaneException(RESULT_OVERSIZE)
——终止族与字节上限同族（drive() 降级 DEAD 续跑），不进模型重试循环。

## 4. F17 —— 有界读取与 bulkhead（三面）

### 4.1 有界流读（PrometheusQueryExecutor）

- `ofByteArray()`（全量进内存）→ `ofInputStream()`：**200 才读体**；按 8KiB 缓冲读
  至多 `resultLimitBytes+1` 字节，读到 limit+1 即断（关闭流、不继续消费连接）→
  ToolControlPlaneException(RESULT_OVERSIZE)。
- executor 收到的 `execution.resultLimitBytes()` 从"无视"变消费源（Gateway 统一裁断
  语义不变：Gateway 收尾仍对 limit 内字节复核一次）。
- 语义约束（executor 域内校验）：start/end 必须为整数 epoch 秒且
  `end-start ≤ 3600s`；step ≤ 60s（`InvestigationInputs.STEP=30s` 合法）。超限 →
  ToolControlPlaneException(INVALID_ARGS)——schema 声明形状、executor 判语义。

### 4.2 bounded queue（AlertAm4Config + ToolGateway）

- `am4ShadowPool`：`Executors.newFixedThreadPool`（无界队列）→ `ThreadPoolExecutor`
  (core=max=poolSize, `ArrayBlockingQueue(16)`，Abort 策略满即拒)。
- ToolGateway `executeWithDeadline` 显式 catch `RejectedExecutionException` →
  ToolModelVisibleException(REMOTE_UNAVAILABLE, "工具调用通道拥塞（背压拒绝，可稍后重试）")
  ——模型可见族、账本 TRANSPORT_UNKNOWN，"满则明确拒绝"有具名文案不靠兜底映射。

### 4.3 schema 约束（ToolArgsValidator + MetricsAgent）

- 通用校验器增 JSON-Schema 关键字：`maxLength`（string 长度）、`pattern`（string 正则
  全匹配）。既有 schema 无此关键字 → 行为零变化。
- MetricsAgent schema：query maxLength=512；start/end `^\d{1,10}$`（epoch 秒）；
  step `^\d{1,4}(ms|s|m|h)$`。

## 5. F06 —— Claim 四类型存储与快照契约（A 线只交存储，转换归 R7c）

### 5.1 四类型语义（照主计划 §8.4，冻结）

| kind | 语义 | 核验条件 |
|---|---|---|
| SYMPTOM | 已观察异常 | 指标/日志等交叉确认即可成立 |
| HYPOTHESIS | 待验证原因 | 提出的候选，未满足 ROOT_CAUSE 条件前的一切解释 |
| ROOT_CAUSE | 根因 | 因果机制解释 + 必要现场证据（类型准入归 R7c 语义门） |
| EXCLUSION | 已排除方向 | 排除性证据成立 |

### 5.2 交付面（A 线）

1. `ClaimKind` 枚举（domain.claim，四值 + 上述语义 javadoc）。
2. `ClaimVerdict` 增 `kind` 组件：canonical 12 参；**11 参 compat 构造默认
   HYPOTHESIS**（无类型断言的保守形态——结构性禁止默认 ROOT_CAUSE，即 F06
   "症状自动升级为根因"的消灭面；存量调用点零改动）。
3. **双哈希不含 kind**：fingerprint/contentHash 维持 M4-21/22 契约（回放比对稳定）；
   kind 是准入元数据非内容身份——R7c 落类型门时如需入哈希由它升版本，A 线不预动。
4. V37：`rca_claim` 加可空 `kind varchar(16)` + check 约束（四值或 null——存量行
   null=未定型）；`PostgresClaimStore` insert/revise 落 kind（写路径完整）。
5. **ClaimRow 不动**：行读面无消费者不建（BA-41 同律）；R7c 落转换时随消费扩面。
6. **类型准入与转换逻辑零实现**：Observation→Hypothesis/Claim 转换、类型语义门、
   Holmes 输出→typed Claim 转换，全部归 R7c 单一责任人（评审 P1-01 分工——
   双方不各写一套 Claim 转换）。

## 6. 测试分层（§7.2）

| 层 | 内容 |
|---|---|
| L0 | NativeRcaAgentTest（成员精确读/缺成员显式失败/迟到证据不可入黑板/成员行篡改显式失败）；SingleToolEvidenceAgent 族（UNKNOWN 语义/insert 先 succeed 后次序/序列数上限/控制面重抛）；ToolArgsValidatorTest（maxLength/pattern）；PrometheusQueryExecutorTest（超限断读/窗口步长语义）；ToolGatewayTest（背压拒绝显式文案）；ClaimVerdictTest（kind 默认/哈希不含 kind） |
| L1（具名 IT `ExA4aNativeCorrectnessIT`，真 PG） | F05 冻结→成员读→P1-05 再冻结幂等+成员数不变→缺成员显式失败；F13 REPORTING 活跃+uq 索引拒第二活跃；F16 账本行 action_seq 落值+attempt=持久铸造 id+PENDING 回收扫描 UNKNOWN 化；F06 kind 落列 roundtrip |
| 195 | exa4a-sync（LF 恒等+双 sha256 探针）→ targeted IT → 官方全量 `mvn verify` 零跳 |

## 7. 完成证据（收口后回填）

- **L0 本地全量**：`m6-ev/exa4a-l0-local3.log`——**1010 tests, 0 failures, 0 errors, 21 skip（无 docker 真 PG IT 惯例跳过），BUILD SUCCESS**；触及类细绿 `m6-ev/exa4a-l0-fix.log`（50/50）。红相留痕：`exa4a-l0-local.log`（编译：Row 字段访问面）、`exa4a-l0-local2.log`（B-16 五案 ERROR + B-17 一案 FAILURE——F05 fail-closed 首功）。
- **L0 新案清单**：NativeRcaAgentTest 4（缺快照行/缺成员行/成员身份不符/迟到不可入黑板）+ kind 断言；ClaimVerdictTest 2（compat 默认 HYPOTHESIS+null 拒 / kind 不入双哈希）；MetricsAgentTest 2（本地段 UNKNOWN 语义 / 序列 >1000 终止族）；ToolGatewayTest utW11（bulkhead 满模型可见背压文案）；ToolArgsValidatorTest utV06/07（maxLength/pattern）；PrometheusMetricsWireMockTest 3（limit+1 有界断读 / 窗幅与 step 语义前置校验）；RcaWorkerTest 1（PENDING 悬挂回收 UNKNOWN 化、宽限内不动）；HarnessAuditCharacterizationTest 按 F16 契约改写（insert 先 succeed 后 inOrder 钉）。
- **L1 具名 IT（真 PG，ExA4aNativeCorrectnessIT 五案）**：
  - run1 `/tmp/exa4a-it-targeted.log`（195）：**UT 1010/0/0/0 零跳过**（21 个 docker 守卫 UT 真跑）+ IT 5 案 4 绿 1 ERROR（B-18：F16 夹具随机 UUID 撞 `fk_rca_tool_invocation_run`——假件无 FK 面真 PG 才可见）。
  - run2 `/tmp/exa4a-it-targeted2.log`（195）：F16 夹具改真实 run/task/attempt 三件后复跑——**IT 5/5 绿，BUILD SUCCESS**。
  - 案面：F05 黑板=冻结成员表+重冻结幂等不可追加（P1-05）+成员篡改 fail-closed；F13 REPORTING 活跃可见+uq 索引拒第二活跃+终态释放；F16 action_seq=call_seq 锚定+attempt 持久+悬挂回收 UNKNOWN；F06 kind 落库/compat 缺省 HYPOTHESIS/REVISED 随写/ck 域外拒绝。
- **L1 官方全量 verify（零跳）**：195 `mvn verify`——**control-app UT 1010/0/0/0 + IT 154/0/0/0 零跳过，shared 15/0/0/0，MVN_RC=0 BUILD SUCCESS**（Total time 50.3s，热 target）；本地留档 `m6-ev/exa4a-l1-verify.log`（targeted2 留档 `m6-ev/exa4a-l1-targeted2.log`）。
- **同步**：`m6-ev/exa4a-sync-list.txt`（36 文件，LF 零 CRLF）+ tar sha256 `0a071a4e…` 双侧全等 + 文件级探针（V37/Am4ShadowTrigger/ExA4aIT）双侧全等；B-18 单文件补送探针 sha256 `082c68a5…` 双侧全等。
- **缺陷台账**：B-16（**新产品缺陷**：Am4ShadowTrigger 快照 digest 混用，F05 fail-closed 抓获）、B-17/B-18（测试面）；明细见 `docs/告警-EX执行日志-20260910.md` EX-A4a 段。
