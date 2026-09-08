# M6-04 195 真机验证证据包（Native Primary fallback 链，V33 run_fallback + report_generation_winner；2026-09-09）

- **验证对象**：M6-04 run 级 fallback——NATIVE run 安全/运行故障**恰一次**铸 HOLMES RERUN（uq_rf_source 表级唯一占位 + 同事务铸造 + fallback_of 只作审计副本）+ 发布赢家 CAS（(incident_id,generation) 恰一发布者，败者报告诚实落档）。同步 HEAD：`0f390f1`（码）→ `ffddff4`（BA-55 修复）→ `6966990`（IT 种子链修复），三段递进全程留证。
- **同步配方**（两次）：`git -c core.autocrlf=false archive --format=tar.gz` → scp → **sha256 双侧一致**：首轮 `64140b56ae80eb7a…`（0f390f1）、BA-55 后 `917829780d10119e…`（ffddff4）→ 解包双树（部署 /opt/build/pr + IT /opt/projects/pr_agent_it）→ 抽查 blob sha256（`git hash-object` 对拍：V33 SQL `54b7dd18…`、FallbackService `aeba828a…`→修复后 `4cc69d27…`、PostgresRunFallbackIT `4e8d32cc…`→终版、Orchestrator `c3b220a1…`）+ `file` 确认 UTF-8/LF（BA-51 同律）。
- **变更前备份**（BA-34 纪律，/opt/backups）：`pre-m604-0f390f1-deploy-tree.tar.gz`（2,031,574,648 B）+ `pre-m604-0f390f1-it-tree.tar.gz`（136,299,523 B）。

## 迁移段（flyway one-shot）

- `Migrating schema "public" to version "33 - am6 run fallback"` → `now at version v33`，exit 0。
- 验表（`m6-verify-v33.sql` 八段输出）：
  - 历史尾两行 `33 | am6 run fallback | t`、`32 | am6 engine comparison | t`；
  - `run_fallback` 8 列 + 约束六面：`uq_rf_source UNIQUE (source_native_run_id)`（恰一次占位锚）、`run_fallback_depth_check CHECK (depth <= 1)`（V33 兜底，与引擎裁定双闸）、`fk_rf_fallback_run … DEFERRABLE INITIALLY DEFERRED`（占位先于 run 行落库——BA-53 同律）、双源 FK；
  - `report_generation_winner`：`PRIMARY KEY (incident_id, generation)`（CAS 唯一发布者锚）+ 三 FK；
  - 授权：两表 `control_app` **仅 INSERT/SELECT**；publisher/notify/eval/public **零授权**（计数 0）；序列 `run_fallback_id_seq` USAGE=t（BA-42①同律）；初始 0 行。

## 部署段（两次）

- 首次：宿主 maven package 15.3s → 镜像 `sha256:690f3f478cd9…` → `up -d` → health **200**（attempt 4）→ 崩溃循环检查 **0** bad patterns。
- BA-55 修复后重部署：package 15.9s → health 200（attempt 4）。

## 测试段（真 PG 全量，零跳过）

- `logs/m6-m604-verify.log`：`mvn clean verify` **BUILD SUCCESS**——control-app **surefire 1106 + failsafe 127，0F/0E/0 skipped**；shared-kernel 108、order-arena 30+58、arena-admin 14+8、notify 19+10 同绿。
- 定向 `logs/m6-m604-it.log`：`PostgresRunFallbackIT` **4/4 绿**——
  ①20 路并发对同一 NATIVE 源 run 铸 fallback：**恰 1 CAST + 19 ALREADY_CAST**（uq_rf_source ON CONFLICT DO NOTHING 在占位面收口）、run_fallback=1、rca_run=2、fallback_of 事件=1、fallback run 路由=HOLMES（DB 默认语义）、**重放（进程重启再触发）仍 ALREADY_CAST 不双铸**；
  ②depth=1 引擎裁定封死（fallback 产物自身失败 INELIGIBLE_ENGINE）+ 封闭错误类 fail-closed（ADAPTER_PACKAGE_REJECTED INELIGIBLE_ERROR_CLASS）；
  ③winner CAS 20 路竞速**恰 1 胜者**（复合 PK ON CONFLICT），败者报告诚实落档但夺不到赢家位；
  ④两表 insert-only 真库兜底（UPDATE/DELETE → DataAccessException，42501）。

## BA-55（195 首轮 IT 揪出，已关闭——三段递进的诚实记录）

- **首轮 4 error**：三案 `insert into rca_event … invalid input syntax for type json`（FallbackService.eventPayload 用 Map.toString() 落 json 列）+ 一案 attemptNo 违 ≥1 守卫。**本机 982 绿不能发现**：in-memory 假件 payloadJson 不透明存放，列类型契约零覆盖。
- **修复 `ffddff4`**：InternalCanonicalJsonV1.canonicalize（ToolGateway 同律）+ 本地 JSON 形断言（startsWith `{` + `"source_run_id"` 带引号）补红绿面。
- **第二轮 2 error**：IT 自身种子链缺陷——winner 行真 FK（winner_report_id/winner_run_id references rca_report/rca_run，V33 设计面）要求 claim 指真实行，随机 UUID 撞库（生产面在 reports.insert 后调用故 FK 恰当，非产品缺陷）；`6966990` seedWinnerChain 全链种子 + Optional.contains 断言 → **4/4 绿**。

## E2E-AM6-04 全链演练（五相位 PASS，suite `20260908T204318Z`，证据 `../e2e-证据/20260908T204318Z-am6-04/`）

- **phase0 DRILL 姿态**：percent=100 bundle **无 native.proposal** 发布/激活（active=`f5e09807…`），status `"percent":100`；预演锚 active=`852040c9…`（percent=0，回滚靶）。
- **phase1 触发链**：注入告警 → `BUCKETED_NATIVE` → NATIVE run `88106da4` 终态 **FAILED（last_error~PROPOSAL_MISSING，封闭错误类真栈触发）**→ 恰一 fallback：run_fallback 恰 1 行（`depth=1`、`error_class=PROPOSAL_MISSING`）、fallback_of 事件恰 1、fallback run `92d8f5e2` **engine=HOLMES / trigger_kind=RERUN / 同 incident 同 generation=0**、incident.current_rca_run_id 上移（`fallback-chain.txt` 全文）。
- **phase2 收尾链**：fallback run **真 LLM** 驱动至 SUCCEEDED → rca_report `cb782e7f` 恰 1 → report_generation_winner 恰 1（winner_report/winner_run 指向）→ report_publication 恰 1 → notify_outbox **rows=1 distinct_channels=1**（每渠道恰一，uq_notify_outbox_delivery 防重）+ **NATIVE 源 run 零报告**（fail-closed 不产半成品）（`winner-publication.txt` 全文含双 run 终态表与赢家行表）。
- **phase3 封死与切回**：fallback run 自身零第二级占位行（depth=1 结构封死实证）；**一键切回 0s**（rollback → status percent=0 观测，`date +%s` 秒级分辨率即 <1s，`drill-timing.txt`）。
- **phase4 终态**：active=`852040c9…`（percent=0 预演靶，验证后不留放量态）。
- **IT/演练分工（如实记账）**：演练要求"20 路并发+进程重启只铸一个 fallback"由 `PostgresRunFallbackIT` 真 PG 实证承担（并发恰一 + 重放 ALREADY_CAST）；E2E 证生产面全链——worker 失败路径→fallback 铸造→holmes 真 LLM→赢家唯一发布/每渠道恰一。

## 一键切回的两级闸（对齐 M6-06/07）

- **本 E2E 计时的切回** = CanaryRouter bundle rollback（percent=100→0），运行时生效零重启——流量面开关。
- `app.alert.fallback.enabled=false`（APP_ALERT_FALLBACK_ENABLED）= fallback 铸造面 kill-switch（DISABLED 裁定，UT 锚定），env 面重启生效——M6-06/07 退场预演/下线回归的 sanctioned 闸。

## 文件清单

| 文件 | 内容 |
| --- | --- |
| m6-604-sync.sh / m6-604b-sync.sh | 双树同步+备份+blob 抽查脚本（195 侧执行，两轮） |
| m6-migrate-v33.sh / m6-verify-v33.sql | flyway one-shot + V33 验表八段 |
| m6-deploy-v33.sh | package→build→up→health 轮询+崩溃循环检查 |
| m6-full-verify-v33.sh / m6-run-it-v33.sh | 195 全量 verify / 定向 IT 执行脚本 |
| m6-redeploy-reit-v33.sh | BA-55 修复后重部署+复跑 IT |
| ../e2e-脚本/e2e-am6-04-fallback-drill.sh | E2E-AM6-04 场景脚本 |
| ../e2e-证据/20260908T204318Z-am6-04/ | 五相位工件（fallback-chain/winner-publication/drill-timing/status 面/scenario-results/resource.log 等 19 件） |
| ../e2e-证据/logs/{m6-e2e-am6-04,m6-m604-it,m6-m604-verify}.log | 演练全日志（wrapper 脱敏流）/定向 IT/全量 verify |

密钥面：全件零密钥（bearer/PG 口令只经 195 shell 变量与 600 权限 env；入 git 前密钥字样扫描零命中）。
