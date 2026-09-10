# EX-A1 · F15 预算接线（v1.0，2026-09-10）

> 执行者：EX 线单执行者。基准：EX-A0 已收口（`docs/告警-EXA0-身份与执行契约.md`）。
> 上游：`docs/告警-执行者ABC-改造技术方案.md` v2.0 §〇 EX-A1；评审意见 P1-02 全文。
> 契约相对 A0 §4.1：`RunBudgetGate` 唯一预算所有者地位不变，本卡补齐其**多维准入/未发出撤销/run 开局限额**三面，并落到生产消费点。

---

## 1. 基准与涉及文件

| 文件 | 方法/位置 | A1 动作 |
|---|---|---|
| `alert/application/RunBudgetGate.java` | `call` | 补三维：多维 all-or-nothing 准入重载 / `releaseOn` 未发出撤销面 / `Usage` 缺失不猜零；`openRun` run 开局限额；冻结的 4 参 `call(key, estimate, remote, usageExtractor)` 原签名保留并委托 |
| `alert/application/agent/SingleToolEvidenceAgent.java` | `investigate` | 消费点接线：doom 前置闸 → `gate.call(TOOL_CALL)` 包裹 open+invoke（预留成功后记录写失败走撤销）→ 后置 `record`；预算拒绝零触网零落账 |
| `alert/application/agent/MetricsAgent/LogsAgent/ChangeAgent.java` | 构造器 | 全参形态（+gate+guard）透传；6 参旧形态保留=无预算语义环境（单元测试假件），生产装配禁用 |
| `infrastructure/nativeexec/NativeInvestigationExecutor.java` | `execute` | run 开局 `gate.openRun(runId, limits)`（幂等）；构造器 +gate+limits |
| `infrastructure/config/AlertAm4Config.java` | bean 面 | `runBudgetGate` / `am4DoomLoopGuard` / `am4BudgetLimits` 三 bean（装配断点 `PersistenceConfig.java:121` 自述待接线由本卡落地，bean 放 AM4 装配=与消费面同 profile）；三 Agent bean 换全参形态 |
| `infrastructure/config/AlertFlowConfig.java` | `nativeInvestigationExecutor` | 注入 gate+limits（ObjectProvider，缺件走既有 NATIVE_DEFERRED 姿态） |
| `alert/infrastructure/InMemoryRunBudgetLedger.java` | 新增 | 无预算语义环境（测试/无 PG 面）的账本假件：同 PG 语义的内存实现（无限额行=不限） |

**不改**：`ReservationKey`（A0 §4.1 零改动承诺）；`RunBudgetLedger` 端口（仅 `PostgresRunBudgetLedger.markUnmatched` 的 from-态集合加 RESERVED——RESERVED 直达 UNMATCHED 的合法化，V13 ck 兼容）；`BudgetKind` 冻结六维。

## 2. 预算语义（P1-02 逐条对齐）

| P1-02 条目 | A1 落法 |
|---|---|
| 同动作读取已完成回执可复用身份 | 结构性落点：准入只挂在**物理请求边界**（agent 的 invoke 段）——恢复读回执路径（A3/A4a 的 result_ref 恢复，见 P1-03 表）不经过预算门，天然零预留零触网。本卡钉结构，全链 IT 随 A4a result_ref 落地 |
| 新物理请求单独追踪单独计费 | `ReservationKey` 含 (run,task,attempt,callSeq)——每次物理重试新键新预留新账（itE5 钉） |
| 多维一次准入或显式撤销 | `call(estimates, base, remote, usageFn, releaseOn)`：按序 reserve，任一维拒 → 已预留维逐个 `release` → 抛 `BudgetExhaustedException`，账面零残留（itE1 钉） |
| 不能只消费工具次数就称五维生效 | **本卡只声明 TOOL_CALL 一维强约束**（invoke 边界硬闸）；STEP/EVIDENCE/SUBTASK 限额行随 `openRun` 落账可观测但**未消费**；TOKEN/REPORT 归 R7（`ActionBudgetContext` 复用本门）。禁止范围外宣称 |
| A1 与 R7 唯一预算所有者 | 所有预留只经 `RunBudgetGate`；agent/R7 都不得直触 ledger 预留面 |

## 3. 异常三族与账面（发送资格三态，P1-03 对齐）

| 异常族 | 发送资格 | 账面 | 记录账（rca_tool_invocation） |
|---|---|---|---|
| `BudgetExhaustedException`（准入拒） | 从未取得 | 零预留零触网（部分已预留维已回滚） | 无行（open 在 remote 段内，未达）→ AgentResult FAILED `BUDGET_EXHAUSTED` |
| `ToolModelVisibleException` + 未知异常（发送后/未知） | 已发送或结果未知 | `provisional` 保守占用待对账（不免费重发） | FAILED + 模型可见原因码（既有映射） |
| `releaseOn` 命中（记录写失败/控制面拒绝=**确证未发出**） | 确证未取得 | 全维 `release` 全额退款，entry RELEASED 可核 | FAILED + 对应原因码 / 原样上抛 |
| 成功 + usage 在场 | — | `commit(actual)` 实扣平账 | SUCCESS |
| 成功 + usage 缺失 | — | `markUnmatched`（**不猜零**；RESERVED 直达 UNMATCHED） | SUCCESS（usage 面缺失不影响调用成功事实） |

`releaseOn` 语义：调用方给"确证未发出"的异常判别式；agent 侧固定为 `!(e instanceof ToolModelVisibleException)`——记录写失败（DataIntegrity 族）与控制面拒绝属未发出，其余（含未知）一律保守 provisional。冻结的 4 参 call 缺省 `e -> false`（全保守，既有 utG03 不变）。

## 4. DoomLoopGuard 消费

- 前置闸 `isOpen(taskId, tool, actionDigest)`：false = 该签名已熔断 → **零预算预留、零触网、零调用账本行**，AgentResult FAILED `DOOM_LOOP_TRIPPED`（熔断放行次语义：命中那次已放行，下一次起零调用）。
- 后置 `record(taskId, tool, actionDigest, progressed)`：progressed = `EVIDENCE_PRODUCED`（产出新证据才算进展；NO_DATA/FAILED 均计无进展——重复空查询/重复故障正是熔断对象）。
- 轮询豁免/阈值/版本化复用 `Policy`（配置 `app.alert.am4.doom-loop.max-consecutive-no-progress:5`，version `am4-doom-v1`）。

## 5. run 开局限额（openRun）

- `execute()` 入口一次 `gate.openRun(runId, am4BudgetLimits)`：`app.alert.am4.budget.*` 四维（STEP 8 / TOOL_CALL 4 / EVIDENCE 8 / SUBTASK 1，既有键既有缺省）幂等 upsert `run_budget_state` 限额行。
- 部署缝隙（执行早于 openRun 不可能——同方法序惯）；重复执行幂等。

## 6. 迁移与回滚

零新迁移（V13 两表全量在场）。`PostgresRunBudgetLedger.markUnmatched` from-态集合扩一行属代码面修正，schema 不动；回滚 = revert 代码，无库变更。

## 7. 测试分层（§7.2）

| 层 | 环境 | 内容 |
|---|---|---|
| L0 | 本机 `mvn test` | ①gate 多维准入/部分撤销/releaseOn 撤销/缺 usage 不猜零/openRun（`RunBudgetGateTest` 扩展）；②agent 预算拒绝零触网零落账、doom 熔断零触网、成功预留实扣（`MetricsAgentTest` 扩展）；③executor openRun 接线（`NativeInvestigationExecutorTest` 扩展） |
| L1 | 195 真 PG `mvn verify` | 具名 IT `ExA1BudgetAdmissionIT`（真 `PostgresRunBudgetLedger`）：itE1 多维 all-or-nothing 可核撤销 / itE2 两任务争最后一份一得一拒 / itE3 预留成功+记录写失败→RELEASED 可核 / itE4 缺 usage→UNMATCHED 不猜零 / itE5 物理重试另记成本两笔 COMMITTED。P1-02 场景⑤（同动作恢复读结果零触网）结构面已钉（§2），全链断言随 EX-A4a result_ref 落地——**前向依赖登记，非跳过** |
| L2 | 195 部署窗 | docker profile 启动后 native 链预算行可见（run_budget_state 有 run 限额行、rca_tool_invocation 正常）——随下个部署窗验证 |

## 8. 完成证据

- 契约：本文；L0/L1 日志：`m6-ev/exa1-*.log`；BUGLOG：本卡摸底未发现新产品缺陷（Gate/Guard 零消费属既有装配断点，PersistenceConfig:121 自述在案）。

### 收口证据（2026-09-10 实测）

| 层 | 结果 | 证据 |
|---|---|---|
| L0 聚焦 | gate 10/10 + agent 16/16 绿 | `m6-ev/exa1-l0-focused.log`（会话内聚焦跑） |
| L0 全量（本机） | 991/0/21 BUILD SUCCESS（EX-A0 时 979 → +12 新案；21 跳过=真 PG 守卫类） | `m6-ev/exa1-l0-local.log` |
| L1 targeted（195 真 PG） | 991 UT + IT 5/5（ExA1BudgetAdmissionIT itE1~itE5 全绿）零跳过，MVN_RC=0 | `m6-ev/exa1-l1-targeted.log` / `-wrap.log` |
| L1 官方全量（195 真 PG） | 15 + 991 + **144** IT 全零跳过 BUILD SUCCESS，MVN_RC=0（EX-A0 时 139 → +5 新 IT 案） | `m6-ev/exa1-l1-verify.log` / `-wrap.log` |
| 同步恒等 | 17 文件 LF 恒等 tar；双侧 sha256 探针恒等（ExA1BudgetAdmissionIT `e57918cc…`、RunBudgetGate `d616c9b8…`） | `m6-ev/exa1-sync-list.txt` / `exa1-sync.tar.gz` |

实现对照：§2 三族异常分账（BudgetExhausted 零触网零账面 / releaseOn 命中全额 RELEASED / 其余 PROVISIONAL 保守占用）、§3 doom 消费（isOpen 预闸 FAILED "DOOM_LOOP_TRIPPED"、record 后置 progressed=EVIDENCE_PRODUCED）、§4 openRun（execute 入口幂等 ensureLimit，四键限额）、§5 零迁移——逐项落地；P1-02 场景⑤为前向依赖（EX-A4a result_ref）非跳过。附带：`PostgresITBase.ALL_TABLES` 补 V13 两表（BA-41 同律，EX-A1 起真 PG 消费者在场）。
