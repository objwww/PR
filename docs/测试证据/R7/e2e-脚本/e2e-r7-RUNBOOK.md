# R7 真窗 E2E 断言包 RUNBOOK（备好未执行）

> 交付锚：R7 执行日志（`docs/告警-R7执行日志-20260911.md`）§11 遗留① ——
> "scripts/e2e 真窗断言包未备"。本包即该遗留的清偿物。
> **状态（2026-09-11）：备好未执行。包内零执行证据、零 scenario-results 记录；
> 全部脚本仅经语法校验与静态断言面核对，未触 195。**
> 约窗规则（用户裁定）：**不抢窗**——待合并窗统一约 195，按本 RUNBOOK §三串行执行。

## 一、交付物

| 文件 | 场景 | 对应账面 |
|---|---|---|
| `e2e-r7-common.sh` | 公共库（r7_ 前缀；脱敏/轮询/只读 SQL/HTTP/终态轮询/绑定快照/硬杀重启助手） | — |
| `e2e-r7-a0-provider-receipt-chain.sh` | A0 门：真实 checkout 现场→主 Agent 受限直查→带引用报告；零子 Agent；供应商回执链跨账对账 | 执行卡 #9；日志 §8 A0 195 面 |
| `e2e-r7-b-gate-three-arm.sh` | B 门三臂：armB（固定三角色 legacy）/ armC（主 Agent 动态委派）/ joint（汇总+盲评封存） | 执行卡 #10；§十四 B 门 |
| `e2e-r7-pg-atomicity-rx13-rd08.sh` | RX13/RD08：在飞 SIGKILL 硬杀×N 轮（提交前后档观测归档）+ 无半套图/不重复派发不变式 | §9 NOT_RUN 主责例 |
| `e2e-r7-worker-restart-rx02-rd09.sh` | RX02/RD09：WAITING_CHILDREN 时硬杀重启→绑定恒等/检查点续走/零重复执行 | §9 NOT_RUN 主责例 |

## 二、环境前置（操作员，窗口开始前）

1. **密钥纪律不变**：`AGENT_MODEL_API_KEY` 走容器 secret/env 注入；bearers 走操作员
   shell env（`R7_WEBHOOK_BEARER` / `R7_RELEASE_BEARER`）；`R7_PG_URL` 口令只在 600 权限
   env。任何密钥不入证据包（脚本内 `r7_redact` 兜底 + 逐场景密钥词形扫描）。
2. **公共注入面**（每次执行操作员 shell 预置）：
   `R7_CONTROL_URL`、`R7_WEBHOOK_BEARER`、`R7_RELEASE_BEARER`、`R7_PG_URL`、
   `R7_PSQL_CMD="docker exec deploy-postgres-1 psql"`、`R7_RUNS_DIR`。
3. **部署形态两态**（切姿态=操作员改 env 重启 control-app，重启时刻留证）：
   - 主模式 ON：`APP_ALERT_R7_PRIMARY_ENABLED=true`（或部署栈等价 env 映射
     `app.alert.r7.primary.enabled`）+ `app.alert.r7.primary.release-digest=<非空>`
     + `AGENT_MODEL` / `OPENAI_COMPAT_BASE_URL` / `AGENT_MODEL_API_KEY`（真供应商，
     litellm-am3 在网）。
   - 主模式 OFF（B 门 armB 用）：`app.alert.r7.primary.enabled=false`（缺省即 false，
     行为字节级等同 legacy）。
4. **破坏性场景闸**：atomicity / restart 两脚本必须显式
   `R7_ALLOW_DESTRUCTIVE=1`（对 control-app 做 docker kill/start；窗口内 195 独占，
   不与部署段其他验证并行）。
5. **服务面**：Prometheus 可达且 `checkout` 服务有真实指标（A0/B 门取数面；
   合成服务名会查空证据，脚本已用真实服务名锚定场景身份在 alertname 侧）。

## 三、执行顺序（合并窗串行；每步证据独立成 runs/<suite>/ 目录）

0. **语法终门**（195 宿主，10 秒）：`for f in e2e-r7-*.sh; do sh -n "$f" && echo "SYNTAX OK: $f"; done`
   ——本包在本仓库开发机上无 POSIX shell 解释器（无 bash/wsl/docker），已过 Python
   shlex（$() 子壳引号模型掩码）+ 块结构平衡静态检查并逐处人工复核，但最终解析
   门以 195 侧 `sh -n` 为准；任何 FAIL 先修再进后续步骤。
1. **A0**（主模式 ON，无破坏）：`sh e2e-r7-a0-provider-receipt-chain.sh`
2. **RX13/RD08**（主模式 ON，`R7_ALLOW_DESTRUCTIVE=1`，默认 3 轮硬杀）：
   `sh e2e-r7-pg-atomicity-rx13-rd08.sh`
3. **RX02/RD09**（主模式 ON，`R7_ALLOW_DESTRUCTIVE=1`）：
   `sh e2e-r7-worker-restart-rx02-rd09.sh`
4. **B 门 armC**（主模式 ON）：`sh e2e-r7-b-gate-three-arm.sh armC`
5. **切姿态 OFF**（操作员步骤，留重启证据）
6. **B 门 armB**：`R7_BGATE_SESSION=<同一步 4 的 session>` `sh e2e-r7-b-gate-three-arm.sh armB`
7. **B 门 joint**：`sh e2e-r7-b-gate-three-arm.sh joint`
8. 窗后：把各 `scenario-results.json` 与关键证据回填 R7 执行日志 §8/§9（状态表
   RX02/RX13/RD08 与 A0/B 门行改 PASS 或如实记失败）；新缺陷当场开 BUGLOG BA-NN 卡。

**通过判据**：每个执行的脚本 `scenario-results.json` 全 PASS；
`BLOCKED_EXTERNAL` 只允许出现在 B 门臂A（旋钮缺件，见 §四.2）；
出现 FAIL 时按脚本 phase 号定位证据文件，取证后修复重跑，禁止改脚本口径放行。

## 四、风险与语义登记（窗口前必读）

1. **⚠ RCA→平台账本 FK 面（静态推演，真窗首验）**：
   `RcaModelGateway.call` 以 `(ctx.runId(), ctx.runId(), ctx.taskId(), ctx.attemptId())`
   构造平台上下文（RcaModelGateway.java:87），`ModelGateway` 照抄为
   `model_call_ledger.review_run_id / run_step_id / attempt_id`（ModelGateway.java:221），
   而这三列 FK 指向 PR 域 `review_run / run_step / step_attempt`（V5）——rca_run id
   在 review_run 无行，**23503 面静态上必然违反**。
   历史未实证原因：legacy 三角色路径是确定性 handler 驱动（SingleToolRoleRunner，
   零模型调用），AM6 真窗的全链绿不覆盖本面；R7 单测/IT 全在 fake/in-memory 账本上。
   **预期失败签名**：rca_model_call 全 UNKNOWN + error_code=LEDGER_WRITE_FAILED +
   control-app 日志「账本 STARTED 写失败，零触网（D5）」计数 ≥1（A0 phase8 已把该
   计数落证）。若实证成立：这是 R7 线真缺陷（非脚本缺陷），修复责任 R7 线，
   候选形态（rca 侧独立账本 / FK 豁免裁定）评审后定，修完重跑 A0。
2. **臂A（单主 Agent 零委派）= BLOCKED_EXTERNAL**：零委派需
   `max_delegation_batches=0` 旋钮，当前为 `DeterministicSupervisor.MAX_DELEGATION_
   BATCHES=2` 编译常量（无运行时旋钮）。旋钮化登记为 B 门执行前置债；禁止以臂C
   冒充臂A，joint 报告已将臂A 如实落 BLOCKED_EXTERNAL。
3. **臂B 语义偏差**：legacy 固定三角色 = 确定性 handler 面（零模型调用、零 token），
   三臂对比实为「主模式 vs 确定性工程基线 vs 主模式+委派」；非方案原文的"LLM 三
   角色"。joint-report 已带 `armB_semantics_note`，盲评结论表述时必须携带该口径。
4. **四案场景语义弱区分**：B 门四案 = 同一真实服务四个独立 episode，场景意图经
   annotations.summary 差异注入（零委派/必要委派/双专家/有界收敛）；注入面不保证
   模型行为分化，语义打分归独立评分者（盲评封存面见 §五）。armC 全程零委派时
   脚本如实 FAIL（取证后调案/prompt 再跑）。
5. **硬杀档位=观测归档**：真模型时序不可控，RD08 提交前/后两档由杀时快照观测
   （`rounds.txt` bracket 列），非精确注入；全轮都杀在已终态时脚本如实 FAIL
   （调 `R7_KILL_DELAYS` 延迟档重跑）。

## 五、证据布局（runs/ 下，全部脱敏落盘）

```
runs/a0-<ts>/            health.resp、bundle/publish/activate、alert body/resp、
                         phase4-binding / phase4-checkpoint / phase5-tools /
                         phase6-model-calls / phase7-package-head、control-app.log、
                         phase8-ledger-fail-count、scenario-results.json、resource.log
runs/atomicity-<ts>/     bundle、rounds.txt（轮|run|在飞|延迟|bracket|终态|不变式行）、
                         kill-restart.log、control-app 日志面、scenario-results.json
runs/restart-<ts>/       bindings-pre/post.txt + bindings.diff、checkpoint-pre/post.txt、
                         decisions-pre.txt、kill-restart.log、control-app.log、
                         scenario-results.json
runs/r7bgate-<session>/  armB-runs.txt、armC-runs.txt、joint-report.json、
                         blind-cases.json（评分者面，无臂标签）、arm-map.json（600，
                         操作员保管至评分完毕）、scenario-results.json
```

盲评打分归独立评分者（用户/评测线）：`blind-cases.json` 只含报告 sha256 与 run id；
`arm-map.json` 由操作员 600 保管至打分结束——脚本不出质量结论，只封存执行面。

## 六、诚实声明

- 本包**未执行**：截至 2026-09-11，五个脚本无任何 195 上的运行记录；
  `runs/` 下无本包产物；上述全部断言面（表/列/路由形态/HTTP 面）系对 main@4a12bcd
  迁移与代码的静态核对，与 AM6 真窗已验证的公共面（health/webhook/bundle/psql-ro
  助手）同构复用。
- 首跑预期不保证全绿：§四.1 的 FK 面若实证成立，A0 当窗即红——这是断言包的
  设计目的（把"从未实证"变成"有证据的缺陷卡"），不是包的失败。
