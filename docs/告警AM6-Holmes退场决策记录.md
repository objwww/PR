# 告警 AM6 — Holmes 退场决策记录（M6-06，2026-09-09）

> 用途：Holmes 只读对照期（M6-05）结论 + 退场预演六面证据 → 裁定 **进入 M6-07 Holmes 生产路径下线**。
> 取数纪律：只录可追溯真值（每节附证据锚：脚本/日志/SQL/工件路径）；无数据写「无数据」；
> 不判"谁正确"——无 GT 只记 disagreement（架构 v1.2 :736/739）；本记录不创设新策略，
> retention/legal-hold 只复核不重裁（O-64；落码方案 :216）。
>
> - 决策日：2026-09-09（证据批次 2026-09-08 ~ 09-09）
> - 部署事实锚：control-app 镜像 `sha256:baaaa58ff68f…`（与 M6-05 证据包同锚）；195 schema v34（34 migrations）；active bundle=percent=0 安全锚
> - 退场姿态（已生效）：`APP_ALERT_SHADOW_HOLMES_ENABLED=false` + `APP_ALERT_FALLBACK_ENABLED=false`（双闸，.env 备份 `dotenv-pre-removal.bak` 600）；holmesgpt-am1 容器 **Exited(0)**；control-app health **200** 存续

---

## 1. 结论速览

| 项 | 裁定 |
|---|---|
| M6-05 只读对照期 | **关闭**——零发布面全程保持（shadow worker 不触 reports/publications/outbox/winner-claims），V34 工作面健康 |
| 六面依赖扫描 | **可退场**——生产运行面依赖集中在已知的 2 处装配 + compose 段 + 3 个密钥键，全部有 M6-07 处置；监控/灾备零命中 |
| drain barrier | **PASS（fail-closed 门打开）**——B1~B4 全 0，历史僵尸全在终态 run 下无活认领（§4 豁免台账） |
| 无 holmesgpt 存活 + 制品恢复 | **PASS**—— holmesgpt 停机后 control-app 存续 health 200；131 份历史 HOLMES 报告全可读，恢复复读 RTO=0s（§5） |
| retention/legal-hold 复核 | **与 M6-01 冻结一致**——框架级 V28/V29 策略链接管，五表 insert-only 无表级 TTL，应用角色无删除权；**无新裁定**（§8） |
| **总裁定** | **批准进入 M6-07**（Holmes 生产路径下线）；回退语义自 M6-07 起为**制品恢复 SLA**，不再宣称一键回切（C-69） |

## 2. M6-05 对照期证据摘要（差异报告口径，供永久引用）

数据源：E2E-AM6-05 真栈五相位演练 suite `20260908T222122Z`（证据 `docs/测试证据/AM6/e2e-证据/20260908T222122Z-am6-05/`，24 件）+ V34 八段验表 + 定向 IT 6/6。

### 2.1 对照速览（模板 §1 格位）

| 项 | 值 | 备注 |
|---|---|---|
| 对照工作行（COMPARISON） | 1，`SUCCEEDED`，attempts=1，lease_epoch=1 | `work-row.txt`；一次成功无重试 |
| V32 对照行落账 | 1（`shadow_exec_ref='holmes-shadow-worker'`） | `comparison-row.txt` 全文在档 |
| 含 disagreement 的对照行 | 1（100%），flags=`[result, latency]` 两维 | 见 2.3 |
| 底噪校准行（noise_baseline 非空） | 1；`disagree=true`，`dims=[result, cost, latency]` | `noise-baseline.txt`；同 snapshot 自比仍 disagree ⇒ 底噪非零 |
| 工作行 FAILED/EXHAUSTED | 0 / 0 | `face-health.txt`：`exhausted=0` |
| 影子 tokens 真值 | 5,843（holmes 单跑，`total_tokens`） | `external_invocation_ledger` 锚 run 同源 |

### 2.2 成本收益（绝对 SLO 面，E-20 禁 before/after）

- holmes 影子侧单跑：latency 9,392ms / 5,843 tokens（真栈 litellm→上游真值）。NATIVE 生产侧同对照：38ms（无 LLM 调用，proposal 直驱）。
- 判定：NATIVE 侧对绝对 SLO 的达标性由 M6-03 容量/延迟报告承接（不在此重裁）；holmes 影子侧成本恒为**纯增项**（零发布价值产出），退场即归零——**退场本身即最大成本收益**。
- 单价换算：无单价数据，本节留 tokens 绝对值，不臆造金额（模板口径）。

### 2.3 disagreement 分类台账（六维，无 GT 不判对错）

| 维度 | 标记行数 | 本窗双侧值（摘录） |
|---|---|---|
| result | 1 | native：root_cause={unknown, unresolved, NO_CONFIRMED_ROOT_CAUSE}+0 claims；holmes：{am6e2e05-shadow-…, high_error_rate, INVESTIGATION_INCOMPLETE}+1 claim(metric_anomaly, UNKNOWN)。标注：**同源异断**（DRILL 注入同一故障，holmes 调查未收口而 native 诚实未决） |
| latency | 1 | 38ms vs 9,392ms（holmes 真 LLM 多步 vs native 本地proposal，量纲差为架构性） |
| cost | 0（对照行未标） | 底噪行标记了 cost 维（见 2.4） |
| schema | 0 | 双侧均 STRUCTURE_VALIDATED 结构面通过 |
| tool_legality | 无数据（缺观察面，恒缺数不标记） | — |
| safety_violation | 无数据（缺观察面，恒缺数不标记） | — |

### 2.4 底噪校准判读

- 底噪行（holmes-vs-holmes 同 snapshot 自比）：`disagree=true`，dims=`[result, cost, latency]`——**holmes 对自身在三维上即不可复现**，即对照期观察到的 result/latency disagreement 无法与引擎非确定性噪声区分。
- 校准局限（必填项如实声明）：底噪样本 n=1，**不足以分辨任何比例阈值以下的差异**；且自比不含模型代际漂移。⇒ 对照期差异面**不构成**"差异收敛"的量化证明。
- **决策基础换轨（诚实声明）**：本退场裁定的依据**不是**对照差异收敛（样本量不支持该断言），而是**架构性退场条件全部成立**：①Native 已 Primary（M6-04 真栈 fallback 链实证、M6-03 容量过门）；②holmes 面只读、摘除不损失任何生产事实（零发布面）；③历史 HOLMES 报告为永久留档事实（§5、§8），退场不触碰读面；④ holmes 影子成本为纯增项。此口径与 G1 P0⑦/Strangler 退场设计一致。

### 2.5 工作面健康度（V34 运行证据）

| 项 | 值 |
|---|---|
| EXHAUSTED 工作行 | 0（有界重试未耗尽过） |
| work_rows_24h / daily_budget | 6 / 20（`face-health.txt`） |
| 租约过期回收（epoch>1 的 SUCCEEDED） | 0（本窗 SUCCEEDED 行 epoch=1） |
| INCIDENT_BUSY 诚实失败 | 无数据（本窗未遭遇同 incident 活跃 HOLMES run 冲突） |
| metrics 面 | `rca_holmes_shadow_sample_total`/`rca_holmes_shadow_work_total` outcome 分桶由 IT+E2E 断言覆盖（PostgresHolmesShadowIT + e2e phase5） |

## 3. 六面依赖扫描结果与处置裁定

扫描件：`scripts/m6-dependency-scan.sh`（日志 191 行，`M6_DEPENDENCY_SCAN_OK`，证据包随件 `m6-606-scan.log`）。

| 面 | 命中 | 处置裁定 |
|---|---|---|
| ① 代码引用（main 树 *.java 含 holmes） | 54 文件；生产装配线：`HolmesClient`、`HolmesInvestigationExecutor`、`AlertFlowConfig` 两 bean + `executors.put(RcaEngine.HOLMES,…)` 铸造分支、shadow worker（M6-05 自带） | 装配线+铸造分支=**删（M6-07）**；`RcaEngine.HOLMES` 枚举=**留+deprecated**（历史读面，C-62）；shadow worker+V34 面=随 M6-05 交付留档（闸门 false 已失活；表/代码留作对照期历史证据） |
| ② compose 服务/network/volume | alert 项目 `holmesgpt` 服务（镜像 local/holmesgpt:am1-http，容器 holmesgpt-am1）+ holmes 卷 + alert-net；deploy compose `HOLMES_BASE_URL`/`HOLMES_API_KEY:?`/`APP_ALERT_HOLMES_MODEL` 映射（130-133） | 服务/卷段=**删（M6-07，先归档已毕 §6）**；litellm=**留**（Native 模型调用同经 litellm，C-63）；deploy compose 三映射=**删** |
| ③ 密钥与 env 键（只列键名，值零回显） | deploy .env：`HOLMES_API_KEY`、`HOLMES_BASE_URL`、`APP_ALERT_HOLMES_MODEL`、`APP_ALERT_SHADOW_HOLMES_ENABLED`、`APP_ALERT_FALLBACK_ENABLED`；holmesgpt 容器 env 键 12 个（GPG_KEY/HOLMES_API_KEY/HOLMES_PORT/MODEL/OPENAI_API_BASE/OPENAI_API_KEY/OVERRIDE_MAX_OUTPUT_TOKEN/PROMETHEUS_URL 等） | 前三键=**删+回收**（M6-07；litellm 面上游键另行管理不在本键）；`APP_ALERT_SHADOW_HOLMES_ENABLED`=**删**（承载面随 M6-05 worker 退役语义化关闭）；`APP_ALERT_FALLBACK_ENABLED`=**移交 M6-07 裁定**（其铸造目标引擎为 HOLMES，执行器摘除后闸门语义随之改写，不得留死闸） |
| ④ 监控仪表盘/告警规则 holmes 维度 | 0（prometheus/alertmanager 零命中） | 零命中=零处置（LeaveAsIs） |
| ⑤ 灾备/恢复脚本 holmes 依赖 | 0（/opt/backups/*.sh 零命中） | 零命中=零处置；制品恢复路径按 §5 实跑记录 |
| ⑥ 文档锚点 | 172 文件（方案/台账/证据/模板） | **留**（豁免：历史事实不改写）；决策记录=本件（扫描后产出） |

## 4. Drain barrier（M6-07 fail-closed 前置门）

SQL：`m6-ev/m6-606-drain.sql`（v2 修正版：CANCELLED 计终态），195 真 PG 重放（`m6-606-capture.log` §B）：

| # | 屏障 | 值 | 判定 |
|---|---|---|---|
| B1 | 真在途 run（非终态 ∧ 任一 task 未 DONE） | 0 | PASS |
| B2 | 真在途 task（挂非终态 run ∧ 未 DONE） | 0 | PASS |
| B3 | V34 影子工作未收口（QUEUED/LEASED/FAILED） | 0 | PASS |
| B4 | fallback 未收口 | 0 | PASS |
| B5 | 豁免台账（历史僵尸 task 分组，无活认领面） | 5 组 35 行：SUCCEEDED/RUNNING 1、FAILED/DEAD 24、CANCELLED/DEAD 6、CANCELLED/RUNNING 3、SUCCEEDED/READY 1 | **豁免留痕** |

B5 裁定依据：全部 35 个非终态 task 挂在**终态** run 下（run 终态即无 worker 认领面，fence 恒阻断），M6-02 Am4ShadowTrigger E2E 影子 run 停在 REPORTING 为零发布端点设计位（其 tasks 全 DONE）。⇒ **barrier 判 PASS，M6-07 放行**。

## 5. 恢复演练（无 holmesgpt 存活 + 旧路径制品恢复实跑）

脚本：`m6-ev/m6-606-drill.sh` + `m6-606-restore.sh`（输出 `m6-606-capture.log` §C/§D，195 宿主实证）：

1. **kill-switch 双闸先行**（审批步骤①）：shadow=false + fallback=false → compose up control-app → health 200。
2. **holmesgpt 停机**：`docker stop holmesgpt-am1`（Exited (0)）→ 30s/60s 两轮 health 存活检查 200；截至本记录取证时停机 9 分钟+ health 仍 200（存续为**持续性事实**非瞬时采样）。
3. **旧路径制品恢复实跑**：最旧 HOLMES SUCCEEDED 报告（run `9b7b51c6…` / report `d86f99fd…`，2026-09-04，pkg 467B）真读出 + sha256；**RTO=0s**（DB 读路径即时）。全量历史面：**131 份 HOLMES 报告 0 份空包，382~3470B** 全可读。
4. **RPO=0 论证**：holmesgpt 为无状态 HTTP 服务（不持久化任何调查数据）；RCA 制品单一事实源=PostgreSQL（rca_report/rca_run/engine_comparison 等），下线容器不产生任何数据丢失窗。
5. **服务级恢复语义**：holmesgpt 容器级回滚**不属于**承诺面——M6-07 后回退=制品恢复 SLA（C-69），即"历史报告可读 + 代码/镜像/compose 制品可重建"，不宣称一键回切。

## 6. 回滚制品封存清单

| 制品 | 位置 | 指纹/规格 |
|---|---|---|
| git tag | `pre-holmes-removal`（**本地打标，不 push**，推远端需用户授权） | 打在 M6-06 证据提交上 |
| holmesgpt 镜像 digest | `/opt/backups/pre-m607-holmes-removal/holmesgpt-image-digest.txt` | `sha256:d0a0518d734c…268d8062583` |
| 旧 compose 归档 | 同目录 `alert-compose-pre-removal.yml`（13,896B，仍含 6 处 holmesgpt 锚）+ `deploy-compose-pre-removal.yml`（13,170B，含 HOLMES 三映射） | 195 真 .env 同步备份 `dotenv-pre-removal.bak`（600 权限） |
| 代码树回滚锚 | `/opt/backups/pre-m605-99040b0-{deploy,it}-tree.tar.gz` | M6-05 前双树 |

## 7. V30~V34 全量证据引用（退场决策的证据底座）

| 表 | 里程碑 | 证据包 |
|---|---|---|
| V30 canary_evidence_sample/canary_route_decision/canary_window_verdict | M6-01 晋升证据 + BA-40 修复 | `docs/测试证据/AM6/M6-01-195真机验证-20260908/`；E2E-AM6-00/01 suites |
| V31 canary_decision FK DEFERRABLE | BA-53 真栈缺陷修复（75c9298） | BUGLOG BA-53；M6-02 证据包 |
| V32 engine_comparison | M6-02 只读对照成账 | `docs/测试证据/AM6/M6-02-195真机验证-20260909/` + suite 20260908T222122Z |
| V33 run_fallback + report_generation_winner | M6-04 Native Primary fallback | `docs/测试证据/AM6/M6-04-195真机验证-20260909/`（IT 4/4 + E2E 五相位） |
| V34 holmes_shadow_work | M6-05 只读对照期 | `docs/测试证据/AM6/M6-05-195真机验证-20260909/`（IT 6/6 + E2E 五相位 + 本记录 §2） |

## 8. V30~V33 retention/legal-hold 复核（只复核，不新裁）

**冻结值回溯**：G1 复审 + 落码方案 O-64 冻结="建表前随 M6-01 冻结，M6-06 只复核"（PROGRESS :546/:617）。M6-01 建表时实际执行的冻结形态（`V30__am6_canary_window_verdict.sql` 原文逐条核验，V32/V33 同律）：

1. **无表级 retention/TTL 条款**——五表（canary_evidence_sample、canary_route_decision、canary_window_verdict、engine_comparison、run_fallback+report_generation_winner）DDL 零 retention 字样；保留语义**上收框架级** V28/V29 RetentionPolicy（insert-only 策略链）+ legal_hold（released_at 面）+ archive_manifest（M5-18 交付）。
2. **应用角色无删除权**——`revoke update, delete on <表> from control_app` 逐表在位（V30/V32/V33 原文）；即**rows 即永久事实账**，任何清理必须走策略链裁决面而非应用直删。V34 同律（control_app delete=f，195 八段验表实证）。
3. **legal-hold 适用性**——hold 语义按 scope 域作用于报告/事件族（V28/V29 面）；五表为发布/审计事实，默认不可删，与冻结一致。

**复核结论**：V30~V34 与 M6-01 冻结值**一致**；M6-02~M6-05 期间无任何表级 retention 条款混入、无删除权扩权。**M6-06 不做任何新的保留期裁定**（方案 :216 合规）。

## 9. M6-07 审批步骤（放行清单）

- [ ] 本记录 §4 barrier B1~B4 全零复验（执行日重放，非引用本日数字）；
- [ ] kill-switch 双闸确认 false 后方可动 compose/代码（先备份后变更）；
- [ ] compose 摘除 + `AlertFlowConfig` bean/分支摘除后，195 真启动 health 200 + 崩溃循环 0（本机绿不算装配面证据，BA-45/BA-56 同律）；
- [ ] 密钥回收（`HOLMES_API_KEY`/`HOLMES_BASE_URL`/`APP_ALERT_HOLMES_MODEL` 摘除）+ 全库密钥扫描零命中（键名列清单）；
- [ ] 历史报告回放套件 + 审计查询全绿；全量回归无跳过；
- [ ] `APP_ALERT_FALLBACK_ENABLED` 语义随执行器摘除同步裁定（不留死闸，§3③移交项）；
- [ ] 制品核封：§6 四类制品在位指纹对拍。

## 10. 移交清单（模板 §6 口径）

- [x] §2 数字与 `docs/测试证据/AM6/e2e-证据/20260908T222122Z-am6-05/`（24 件）对拍一致；
- [x] §2.4 底噪判读完成——真差异候选清单：**无**（n=1 不足以分辨，按"架构性条件"换轨裁定，已声明）；
- [x] §2.2 绝对 SLO 口径如实（无单价不臆造金额）；
- [x] 未决项登记：`APP_ALERT_FALLBACK_ENABLED` 语义（M6-07 裁定，§9）；
- [x] 本记录已入 `docs/`，BUGLOG/PROGRESS 台账按编号接续（本轮无新缺陷；台账改动只编辑不 stage 留复核）。
