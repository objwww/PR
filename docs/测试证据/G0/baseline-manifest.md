# G0 基线 manifest（G0-01）

> 固化时点：2026-09-04 20:12（本地 mvn 全量跑完时刻）。G0 各任务改动前的事实快照，收口后与终态 diff 对照。

## 1. 代码基线

- 当前 commit：`5686d28ed156827e4eb2e5c2282bf8a41b365ecc`（feat: M4-A Sandbox 执行面……，main 分支）
- 工作区状态（`git status --short` 共 500 条路径）：
  - 已跟踪文件改动 51 个：+239 行 / −20254 行（AM1 T00 死代码清除的删除量占绝对主体）
  - 未跟踪新增：`control-app/src/main/java/.../alert/`（告警域全量）、`V7__am1_alert_domain.sql`、`docs/告警*`、`docs/架构*`、`docs/测试证据/`、`deploy/` 变更、`.scheduler-research/`、`var/`
  - `publisher-app/`、`sandbox-broker/` 仅剩 `target/` 构建残留（git 零跟踪，BA-09② 待清）

## 2. 测试基线（`mvn -s maven-settings-aliyun.xml -q test`，2026-09-04 20:12）

| 模块 | Tests run | Failures | Errors | Skipped |
|---|---:|---:|---:|---:|
| 全 reactor 合计 | **215** | **0** | **0** | **2** |

- 2 个 skipped = Testcontainers 类在本机（无 docker）自动跳过（`PostgresModelCallLedgerRepositoryTest` 等）
- 告警域测试类清单（29 个类中的告警线部分）：AlertInboxProcessorTest / RcaWorkerTest / AlertIdentityFactoryTest / DeferredPolicyTest / EvidencePackageValidatorTest / SlaPolicyTest / AlertStateMachineTest / AlertSelfCheckTest / AlertWebhookControllerTest / AlertInMemoryStoresTest / HolmesErrorClassifierTest / HolmesInvestigationExecutorWireMockTest / M7MigrationContractTest / ControlArchitectureTest / ControlContextSmokeTest / ControlSelfCheckTest
- 完整输出日志：`var/g0-baseline-test.log`（-q 模式，surefire 计数取自 `target/surefire-reports/*.txt` 聚合）

## 3. 迁移清单（Flyway，`control-app/src/main/resources/db/migration/`）

| 版本 | 文件 | 状态 |
|---|---|---|
| V1 | `V1__m0_schema.sql` | 已部署（195），不动 |
| V2 | `V2__grants.sql` | 已部署，不动 |
| V3 | `V3__m1_inbox_reconcile.sql` | 已部署，不动 |
| V4 | `V4__m2_checkpoint_repair.sql` | 已部署，不动 |
| V5 | `V5__m3_model_governance.sql` | 已部署，不动 |
| V6 | `V6__m4_sandbox.sql` | 已部署，不动 |
| V7 | `V7__am1_alert_domain.sql` | 未部署（本地落码，9 表） |

- G0-08 将新增 `V8__am1_dag_reserve.sql`（AM3 的 eval/notify 迁移顺延 V9）

## 4. 关键基线事实（G0 改动前的缺口快照，对照 BA-09~13）

1. `deploy/docker-compose.yml:108` 注入 `ALERT_WEBHOOK_BEARER`，`application.yml:32` 读 `ALERTMANAGER_WEBHOOK_BEARER_TOKEN`——名称漂移（BA-09①）
2. `application.yml` 无 `app.alert.holmes.*` 段，`AlertFlowConfig:123` 读 `${app.alert.holmes.api-key}` 无默认——docker profile 启动即 placeholder 解析失败（BA-10①）
3. `AlertSelfCheck` 把 rca_report 归入"需 UPDATE"组，V7 只授 SELECT/INSERT——真栈自检必红（BA-10②）
4. 三台状态机（Inbox/Run/Task）生产零调用；仅 IncidentStateMachine.nextGeneration 1 处（BA-11①）
5. `IncidentProjector:176-196` 无"同 episode 内 resolved 先于 firing"防御（BA-12①）
6. `HolmesClient` 响应体整包读 String，无受限读（BA-12②）
7. 超时/网络类错误账本终态记 FAILED 非 UNKNOWN（BA-12③）
8. 无 Hikari 显式配置（BA-12④）
9. `it/` 包 AM1 告警域 IT 为空（BA-09③）
