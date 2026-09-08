# M6-05 195 真机验证证据包（Holmes 只读对照期，V34 holmes_shadow_work 持久影子工作面；2026-09-09）

- **验证对象**：M6-05 反向影子全链——NATIVE SUCCEEDED run 确定性抽样入队（sha256 mod sampleRate，封闭结局 8 值 + 24h 预算）→ Scheduler 单线程 SKIP LOCKED 批量认领（租约三元 + 有界重试 + EXHAUSTED）→ Worker 真 FK 锚影子执行（run RUNNING 自收终态/task DONE 零认领面/attempt STARTED→终态；**不铸生产 run 不调 finishTask**，C-65）→ 成功恰一次落 V32 engine_comparison + CALIBRATION 底噪校准行（noise_baseline jsonb，HOLMES vs HOLMES_CALIB）。同步 HEAD：`99040b0`（码）→ `0a67f9a`（BA-56 装配面修复）→ `037ec61`（BA-57 enqueue 契约 + IT 夹具修复），三段递进全程留证。
- **同步配方**（两次全量 + 两次定点）：`git -c core.autocrlf=false archive --format=tar.gz` → scp → **sha256 双侧一致**：`dc86b4c3b1106aa6…`（99040b0）、BA-56 后 `025f4f040f1e770d…`（0a67f9a）→ 解包双树（部署 /opt/build/pr + IT /opt/projects/pr_agent_it）→ 抽查 `git hash-object` blob 对拍 8 文件全符（V34 SQL `574942f1…`、Sampler `db5c48f7…`、Worker `2a81ecf5…`、Scheduler `50ba09eb…`、PostgresHolmesShadowWorkRepository `90ae169c…`、PostgresHolmesShadowIT `494e9b98…`、compose `b658e5d5…` 等）；BA-57 两文件定点同步 m6-605d-itfix2.tgz。
- **变更前备份**（BA-34 纪律，/opt/backups）：`pre-m605-99040b0-deploy-tree.tar.gz`（2,031,773,176 B）+ `pre-m605-99040b0-it-tree.tar.gz`（136,549,341 B）+ `pre-m605-dotenv-20260909054538.bak`（.env 开闸前备份）。

## 抽样闸与迁移段

- `.env` 幂等落 `APP_ALERT_SHADOW_HOLMES_ENABLED=true`（先备份；键出现次数=1 断言；值面抽样参数走 compose 默认 budget=20/rate=100/attempts=3/lease=PT15M/poll=PT30S/batch=2）。
- flyway one-shot：`Migrating schema "public" to version "34 - am6 engine shadow work"` → `now at version v34`，exit 0。
- 验表（`m6-verify-v34.sql` 八段输出）：
  - flyway 历史尾三行 `34|t`、`33|t`、`32|t`；
  - `holmes_shadow_work` 17 列（shadow_key NOT NULL UNIQUE、kind 默认 COMPARISON、租约三元可空、tokens_spent/last_error 审计面）；
  - 约束八面：`holmes_shadow_work_shadow_key_key UNIQUE`（确定性幂等锚）、kind/state 值域 CHECK、`ck_hsw_generation/attempts`、双 FK（native_run_id→rca_run、incident_id→incident）；
  - 索引三面：PK + 唯一键 + `ix_hsw_claim (state, created_at)`（认领序）；
  - **授权差异化（V34 与 V32/V33 的有意差异）**：control_app `SELECT=t INSERT=t UPDATE=t DELETE=f`——UPDATE 是租约/收口 CAS 语义，DELETE 拒（工作历史不可抹）；publisher/notify/eval 三角色零授权；序列 USAGE=t（BA-42①同律）；
  - 表注释在案（C-65 语义 + 编号顺延说明）。

## 部署段（三次）

- 首次（99040b0）：**启动即崩**——`holmesShadowWorker` 参数 7 无 EngineComparisonRecorder bean → APPLICATION FAILED TO START，健康 30 次全 000。
- BA-56 修复后（0a67f9a）：health **200**（attempt 4）→ 崩溃循环检查 0 bad patterns → `HolmesShadowScheduler 已启动 poll=PT30S lease=PT15M batch=2` 启动日志在案。
- BA-57 修复后（037ec61，test-what-you-ship 重部署）：package 16.1s → 镜像 `sha256:baaaa58ff68f…` → health 200（attempt 4）。

## 测试段（真 PG 全量，零跳过）

- `logs/m6-m605-verify.log`：`mvn clean verify` **BUILD SUCCESS**——control-app **surefire 1126 + failsafe 133，0F/0E/0 skipped**（较 M6-04 1106+127 增量=影子面新测试）；shared-kernel 108、arena 30+58、arena-admin 14+8、notify 19+10 同绿。
- 定向 `logs/m6-m605-it.log`：`PostgresHolmesShadowIT` **6/6 绿**——
  ①确定性 shadow_key 入队幂等（撞 key 返 false，恰 1 行）；
  ②20 路并发 claimBatch(10 行)：SKIP LOCKED 每行恰被一 worker 认领（无双领，attempts=1/epoch=1）；
  ③租约未过期不可重领 → 过期回收 attempts/epoch 双 +1 → 三次失败至 **EXHAUSTED 永不再入批**；
  ④epoch 栅栏 CAS：过期持有者 complete/markFailed 全 0 行、现持有者恰一胜出；
  ⑤授权面真库兜底：control_app UPDATE 放行（租约语义）、DELETE 拒（DataAccessException，工作历史不可抹）；
  ⑥worker 影子锚真 FK 链（run SUCCEEDED 自收/task DONE/attempt SUCCEEDED）+ V32 对照恰 1（holmes-shadow-worker）+ CALIBRATION 工作行幂等入队。

## BA-56 / BA-57（195 揪出，已关闭——递进实录）

- **BA-56**：docker-profile 装配缺口（EngineComparisonRecorder @Bean 挂在 am4-shadow-trigger 专属 profile）。本地默认 profile smoke 不加载 AlertFlowConfig（@Profile("docker")），**BA-45 同族第二例——本地绿对装配缺口系统性不可见，唯一证据面=195 真启动**。按 javadoc 既有预告上收公共装配面 + 反射钉住双面（AlertFlowConfig 恰一 @Bean、Am4 形状零）。
- **BA-57**：R1 6 error（IT 种子缺 run FK 锚 ×5 + 桩指纹空 Map 违 V23 键集 check ×1）→ R2 1 error（**enqueue 撞 key 抛异常非返 false——幂等锚漏 ON CONFLICT，产品缺陷真修**，V32 uq_ec_pair 同律）→ R3 6/6 绿。端口契约"返 false 不抛"必须在真库面落形；IT 桩 artifact 必须镜像生产序列化形状。

## E2E-AM6-05 全链演练（五相位 PASS，suite `20260908T222122Z`，证据 `../e2e-证据/20260908T222122Z-am6-05/`）

- **phase0 自包含姿态**：先发布/激活 percent=0 安全锚（active=`43f23ecc…`，自包含恢复位——前任 suite 中断遗留放量态的教训收编）→ percent=100 且带 native.proposal（active=`2e97307e…`，NATIVE 必成功姿态）。
- **phase1 NATIVE 主路径零惊扰**：注入告警 → `BUCKETED_NATIVE`（run=`45101729…`，incident=`992a5928…`，generation=0）→ **真 agent 全链 SUCCEEDED** + rca_report 生产收尾链照常（对照期开启不影响主路径）。
- **phase2 抽样→影子→对照**：holmes_shadow_work 恰 1 行（snapshot_digest=native investigation_hash）→ 影子执行恰一次（**attempts=1，tokens_spent=5843**）→ 影子 run `c27ff17e…`（engine=HOLMES DB 默认/trigger=RERUN/终态 SUCCEEDED 自收 finished_at）→ V32 对照行恰 1（shadow_exec_ref=`holmes-shadow-worker` + holmes 侧 validation_status 非 report_missing——真 LLM 执行 + native 侧 root_cause 三元组）（`work-row.txt`/`comparison-row.txt`）。
- **phase3 零发布面**：影子 run 的 rca_report / report_publication / notify_outbox / report_generation_winner = **0|0|0|0**；incident.current_rca_run_id 未被影子 run 占据（195 实证：NATIVE 成功收尾后指针保持 null——**finishTask 不移 incident 指针，指针移动仅 FallbackService 面**，修正线束初版的错误假设）。
- **phase4 底噪校准（首份真实底噪数据）**：`holmes-calib:<native_run>` CALIBRATION 行 SUCCEEDED → 校准对照行恰 1（noise_baseline 六键在场：native_run_id/baseline_run_id/calibration_run_id/snapshot_digest/disagree/dims；双侧 engine 标签 HOLMES vs HOLMES_CALIB；校准 run `219bbd6a…` 同快照 investigation_hash）——**disagree=true，dims=[result, cost, latency]**（六维中 schema/tool_legality/safety_violation 零分歧；结论供 M6-06 差异报告判读：差异−底噪后查绝对 SLO）。
- **phase5 健康与切回**：EXHAUSTED=0、24h 入队 **6/20**（三 suite×2 行：含一个中途弃案 suite 的影子工作在无驱动下由 scheduler 自主完成——**调度面自治的意外实证**）→ rollback → active=`43f23ecc…` percent=0（验证后不留放量态）。
- **IT/演练分工（如实记账）**：并发恰一/租约回收/CAS 栅栏/授权面归 PostgresHolmesShadowIT 真 PG 实证；E2E 证生产面全链（抽样挂钩→真 LLM 影子执行→对照+底噪→零发布面→预算）。
- **线束修正三笔（scenario 侧，非产品缺陷）**：①phase3 指针断言按错误的 finishTask 语义编写（改为"指针不被影子 run 占据"+记录实证）；②service 名补 per-suite 后缀（同 key 二跑被去重/generation 栅栏挡 → BUCKETED_NATIVE 120s 超时）；③route 行 read-back 首读偶发空（poll 判可见后紧邻 SELECT 返 0 行的可复现时序窗）→ 带 3s 步进的重试取回 + 留痕。

## 195 终态

- active=`43f23ecc…`（percent=0 安全锚）；control-app health 200（镜像 `baaaa58f…` = 0a67f9a+037ec61）；schema v34；抽样闸 enabled=true 常开（对照期语义，M6-06 预演时按决策记录处置）。

## 文件清单

| 文件 | 内容 |
| --- | --- |
| m6-605-sync.sh / m6-605b-sync.sh | 双树同步+备份+blob 抽查脚本（195 侧执行，两轮） |
| m6-env-shadow.sh | .env 备份 + 抽样闸幂等开启 |
| m6-migrate-v34.sh / m6-verify-v34.sql | flyway one-shot + V34 验表八段 |
| m6-deploy-v34.sh | package→build→up→health 轮询+崩溃循环检查 |
| m6-run-it-v34.sh / m6-full-verify-v34.sh | 定向 IT（-am 修正版）/ 195 全量 verify |
| ../e2e-脚本/e2e-am6-05-holmes-shadow.sh | E2E-AM6-05 场景脚本（含三笔线束修正） |
| ../e2e-证据/20260908T222122Z-am6-05/ | 五相位工件（work-row/noise-baseline/comparison-row/face-health/status 面/scenario-results/resource.log 等 24 件） |
| ../e2e-证据/logs/{m6-e2e-am6-05,m6-m605-it,m6-m605-verify}.log | 演练全日志（wrapper 脱敏流）/定向 IT/全量 verify |

密钥面：全件零密钥（bearer/PG 口令只经 195 shell 变量与 600 权限 env；入 git 前密钥字样扫描零命中）。
