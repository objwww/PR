# M6-02 195 真机验证证据包（V32 engine_comparison 观察面成账；2026-09-08 UTC / 09-09 北京时刻）

- **验证对象**：M6-02 引擎对照观察面——V32 `engine_comparison` + `EngineComparisonRecorder` + 引擎维度指标 + Am4ShadowTrigger 对照落账接线，同步 HEAD = `9d0e7d5`。
- **同步配方**：`git -c core.autocrlf=false archive --format=tar.gz -o m6-ev\m6-602-tree.tgz HEAD` → scp → **sha256 双侧一致** `83c3e01d78d4fecc327e81826e2d33b31d9c66450d2394aabed5e30f76e63cc7` → 解包 `/opt/build/pr`（部署树）与 `/opt/projects/pr_agent_it`（IT 树）→ 抽查 blob sha256 对拍（V32 SQL `ef0b38b9…`、Recorder `c1c07917…`、台账 SQL `6730aab1…`）+ `file` 确认 UTF-8/LF 无 CRLF 击穿（BA-51 同律）。
- **变更前备份**（BA-34 纪律，195 实际备份根 `/opt/backups`）：`pre-m602-9d0e7d5-deploy-tree.tar.gz`（2,030,763,187 B）+ `pre-m602-9d0e7d5-it-tree.tar.gz`（114,483,864 B）。

## 迁移段（flyway one-shot）

- `docker compose up migrate`：`Migrating schema "public" to version "32 - am6 engine comparison"` → `Successfully applied 1 migration, now at version v32`，exit 0。
- 验表（`m6-verify-v32.sql` 四段输出）：
  - 迁移历史尾两行 `32 | am6 engine comparison | t`、`31 | ba52 canary decision fk deferred | t`；
  - `engine_comparison` 11 列形态与 V32 DDL 一致（id/native_run_id/comparison_key char(64)/shadow_exec_ref/snapshot_digest char(64)/holmes_outcome jsonb/native_outcome jsonb/disagree_flags jsonb NOT default '[]'/noise_baseline jsonb/cost_compare jsonb/created_at）；
  - 唯一锚 `uq_ec_pair UNIQUE (native_run_id, comparison_key)`；**无 FK**（观察面结论不绑 run 生命周期，契约测试 doesNotContain("references rca_run") 同律）；
  - `control_app` 授权**仅 INSERT/SELECT**（append-only）+ 序列 USAGE = t；初始 0 行。

## 部署段

- package：宿主 maven `-pl control-app -am package -DskipTests`（15.0s）→ `docker compose build control-app` 镜像 `sha256:8fb9ae43370f…` → `up -d`。
- 健康：`/actuator/health` **200**（attempt 4，约 15s 内恢复）；`docker logs --since 5m` 零 `APPLICATION FAILED|PlaceholderResolution`。

## 测试段（真 PG 全量，零跳过）

- `logs/m6-m602-verify.log`：`mvn clean verify` **BUILD SUCCESS**——control-app **surefire 1096 + failsafe 123，0F / 0E / 0 skipped**（order-arena 30+58、notify 19+10、arena-admin 14+8、shared-kernel 108 同绿）。
- 定向 `logs/m6-m602-it.log`：`PostgresEngineComparisonIT` **2/2 绿**——①append 幂等（同对照重放 false、count=1）+ jsonb round-trip 保形 + noise_baseline null；②control_app 角色 UPDATE/DELETE 被拒（append-only 真库实证）。

## E2E-AM6-02 观察面成账（真栈全链；证据 `../e2e-证据/20260908T185858Z-am6-02/`）

- **phase0**：nativeReady=true + percent=0 姿态；注入合成告警 → `BUCKETED_HOLMES` 主路径 run `05b199f4` 真 LLM 驱动至 **SUCCEEDED + 报告在场**（holmes 侧结论源）。
- **phase1**（配方 §5 方式 A 一次性 runner：`docker compose run --rm --no-deps control-app --spring.profiles.active=docker,am4-shadow-trigger --spring.main.web-application-type=none --am4.shadow-trigger.holmes-run-id=…`）：影子 run `37b3ef3e` 镜像 holmes 身份（同 incident/同 generation/同 investigation_hash `5623b764…`），三调查任务全 EVIDENCE_PRODUCED，终态 **REPORTING**。
- **phase2 对照行**（`comparison-row.txt` 全文）：恰 1 行——`shadow_exec_ref='am4-shadow-trigger'`、`snapshot_digest=holmes investigation_hash`；holmes 侧 `STRUCTURE_VALIDATED` + total_tokens 6113 + root_cause 三元组；native 侧 unresolved `NO_CONFIRMED_ROOT_CAUSE`；`disagree_flags` 仅 `result` 维且只携 `{dim,holmes,native}` 原始值（**零 winner/correct/verdict 判语**——§736 无 GT 只记 disagreement）；影子 run 零 `rca_report`/`report_publication`/`notify_outbox`（对照行不是报告/发布，零报告纪律不变）。
- **phase2b 二次触发停发**：exit 非 0 + `engine_comparison` 仍恰 1 行。
- **phase3 四维台账**（`observation-ledger.txt`，`deploy/policy/m6-engine-observation.sql` 真数据可执行）：预算（HOLMES 586,615 tokens/81 报告 vs NATIVE usage_missing=1 诚实单列）、延迟（HOLMES p50 13,317ms vs NATIVE 196ms 同窗并列，禁 before/after）、错误率 + 对照落账进度（2 行/2 flagged/0 noise_baseline）、人工分歧明细 2 条（均 result 维）。
- **phase4 放量姿态**：percent=10 bundle 激活 → status `"percent":10` → 回退 percent=0 → status 回 0 + active=`bbba4e43…`（零代码放量，验证后不留放量态）。

## 两则真栈事实（诚实边界）

1. **影子 run `rca_run.engine` 列=DB 默认 HOLMES**（不经 CanaryRouter，M5-10/O-3 既定）→ C-61（`engine='NATIVE'` 判定面）不拦影子重复触发；真实停发面 = `uq_rca_run_active_incident`（REPORTING 属活跃态，二次 insert 撞约束）。两守卫皆合规拒绝路径，E2E 断言两取一（首轮 185601Z 误断 C-61，如实留档后修正；真值见 `shadow-trigger-c61.log`）。
2. **缺数≠差异纪律的实证**：影子 run 终于 REPORTING 无 finished_at → `latency_ms=null` → latency 维不标；native 无 token usage → `cost_compare` 只收非空键。NATIVE 预算行 usage_missing=1 单列，不补零。

## 文件清单

| 文件 | 内容 |
| --- | --- |
| m6-602-sync.sh | 双树同步+备份+抽查脚本（195 侧执行） |
| m6-deploy-v32.sh | package→build→up→health 轮询+崩溃循环检查 |
| m6-verify-v32.sql / m6-verify-v32.sh | V32 验表四段（历史/列/约束/授权/序列/行数） |
| m6-full-verify-v32.sh / m6-run-it-v32.sh | 195 全量 verify / 定向 IT 执行脚本 |
| m6-verify-summary.sh | verify 日志汇总行提取 |

（E2E 场景脚本与两 suite 工件见 `../e2e-脚本/`、`../e2e-证据/`；日志见 `../e2e-证据/logs/`。所有密钥/Bearer/PG 口令只经 195 shell 变量与 600 权限 env，绝不入证据包——入 git 前已做密钥字样扫描零命中。）
