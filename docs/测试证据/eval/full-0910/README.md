# 全量批 full-0910 证据——prev_episode_resolved 修复后首批 5×2

- 时间：2026-09-09 16:29:03 ~ 16:43:36 UTC（批仅 14.5 分钟收束，异常快——9/10 Case 被门挡）
- 启动器：`nohup sh /tmp/cc-evalrun-glm5.sh deploy/alert/eval/eval-scenarios.yml full-0910 > /root/full-0910.log 2>&1 &`（195）
- eval_run：`fd72d103-10a4-4989-9c81-3fe88e965aa3`，registry=全量 5 场景 ×2 轮
- 评测镜像：`pr-agent/control-app:0.0.1-SNAPSHOT` sha256:3f56530f1b65…（含 prev_episode_resolved 修复，构建日志 /root/eval-fix-build-0910.log，定向测试 7/7 过）

## 批指标（01-eval-run.log 末行）

`coverage=0.1 conditional=0.0 e2e=0.0 unresolvedRate=0.0 tp=0 fp=0 fn=10`
reportDigest=c595bba3d8fc82e9a020cc590520ba8f0176ee5bb477301a61df1d3607e2e7f8
usage ledger：UNMATCHED / no_rows_under_run_key（NATIVE 零模型调用口径，同烟测）。

## 逐 Case（02-db-after.txt）

| Case | verdict | reason | 说明 |
|---|---|---|---|
| S1R1 | **DECIDABLE**（miss） | root_cause_miss_or_unresolved | 全链走通：flagd paymentFailure=50%（16:29 生效）→ checkout page firing（16:33:02）→ incident revive（gen 4）→ rca_run 57f00da5 INITIAL/NATIVE SUCCEEDED（16:33:34）→ 评分。fn=1：actualRootCause=NO_CONFIRMED_ROOT_CAUSE（Native 如实 UNKNOWN，同烟测口径） |
| S1R2 | TIMEOUT_OR_ABSENT | gate_blocked | S1R1 deactivate 的 `awaitAllResolved(600s)` 超时（16:33:36→16:43:36）→ 门关闭 |
| S2R1~S5R2（7 Case） | TIMEOUT_OR_ABSENT | gate_blocked | 门已关，全部落档不注入 |

## 根因（红直报）：registry 恢复等待预算缺陷，非本次修复引入的回归

1. **失败分类统计**：DECIDABLE 1（miss）；gate_blocked 9。无 prev_round_not_resolved、无 activate_failed、无 run_not_found。本次引入的轮前 incident RESOLVED 检查仅在 S1R1 执行过一次（incident checkout 上轮已 RESOLVED → 秒过，S1R1 得以注入）；S1R2 起在门检查处即被拦（EvalBatchRunner.java:110-118 先于 :119-131），该检查再未获得执行机会——其保护面（S3~S5 多轮）本批未触及。
2. **直接原因**：S1R1 复位后 checkout page 告警 600s 内未 resolved。复位实测正常（paymentFailure=off 已核认，5m 烧损率已归 0），但 page 规则是双分支（prometheus-rules-checkout.yml sloth-slo-alerts 组）：分支① 5m>0.144 AND 1h>0.144；分支② **30m>0.06 AND 6h>0.06**。批后实测 30m=0.0968、6h=0.0798——分支② 成立，告警持续 firing。30m 窗要等故障 burst 整体滑出（≈复位后 ~30min），6h 窗含当日更早的 checkout 错误存量（非本批注入）。
3. **registry 假设错误**：eval-scenarios.yml S1/S2 `max_resolved_wait_seconds: 600` 的注释依据"复位后 5m 窗稀释 ≤5m + 余量"——只覆盖分支①，漏算分支②的 30m 窗。既有缺陷，首批全量才暴露（烟测只跑 S3，arena 告警秒级 resolved 不踩此坑）。
4. **门行为本身符合冻结语义**：恢复未确认 → 关门 → 剩余轮 gate_blocked（EvalBatchRunner.deactivate:187-191 + FlagdScenarioDriver.deactivate:56-60 的 alerts_still_firing），失败不中断且落档——fail-closed 按设计工作。
5. 次要观察：S1 firing 比 registry 校准值（~17.3min）快得多（激活→firing ~4min），说明当前栈基线烧损偏热，与 6h 窗存量互为印证。

## 恢复侧/现场核查（批后）

- flagd：paymentFailure=off、paymentUnreachable=off（均复位，BA-19 未触碰）
- chaos 会话：non-CLOSED = 0（S3~S5 未激活过，无残留）
- Prometheus firing：仅 checkout page（预期内，随 30m 窗稀释自然 resolved，估 ~17:04 UTC 前后）
- incident `alertname=checkout|service=checkout`：FIRING（等 resolved webhook，自然收口）
- 195 资源：mem 7G/avail 3G，磁盘 59G/剩 25G——正常；无任何容器被重启

## 结论与建议

1. 本次修复（轮前 incident RESOLVED 等待）**未被本批证伪也未被证实**——它保护的场景（S3~S5 多轮）根本没被执行到。修复保留，无回退理由。
2. 建议下一步（主会话裁决）：registry 升 version 把 S1/S2 `max_resolved_wait_seconds` 600→2100（覆盖 30m 窗稀释 + AM resolved 链延迟 + 余量；registry 纪律"只增不改"= 递增 registry_version）；或在方案层面重审 S1/S2 恢复判据是否应只看分支①。预计批时长影响：S1/S2 每轮复位多等 ~25min，全批 ~4-5h。
3. 质量面不变：S1R1 再次证明链可通、Native 执行器根因面为零命中（基线口径如实）。
