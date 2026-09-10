# EX-B1 · change_event 真实变更源（与生效同事务）契约

> 卡源：`docs/告警-执行者ABC-改造技术方案.md` §二 EX-B1（B 门硬前置，不阻塞 A 门）。
> 模板：同方案 §六。验收分层：§7.2（L0 本地全量 / L1 195 真 PG 具名 IT / 官方 verify）。
> 基准：HEAD `aedf1dd` + 工作树 EX-A0~A3 已交付面（未 commit，卡基准以本文件 §1 快照为准）。

## 1. 基准（文件+方法名）

| 面 | 现状（摸底 2026-09-10） |
|---|---|
| 激活事务边界 | `ConfigBundleService.movePointer()` → `PostgresConfigBundleRepository.activate()` 单语句 CAS（V24 `config_bundle_active`）；**全链零变更事实落档**——评审 B1 指出的"配置生效但证据缺失"真实存在（控制器 `audit()` 仅日志行） |
| change.query 工具 | `AlertAm4Config.am4ToolRegistry()`（现 L169-171）挂 `ReplayToolExecutor(fixtureBytes("am4/fixtures/change-query.json"))`——冻结 fixture 即数据面，fixture 在 **main** 资源（生产镜像带假件，P1-03 面） |
| change_event 表 | **不存在**（全仓 0 命中）；`ChangeQueryExecutor` 亦不存在。v1.0 暂定编号 V37 已被占用（V37=exa4a_claim_kind），**编号 rebase 冻结 → 本卡用 V40** |
| 消费方 | `ChangeAgent`（SingleToolEvidenceAgent 基座，evidence source=change）；`NativeInvestigationExecutor` 的 DAG `change@1` 任务 |

## 2. 前置输入契约

- **change_event 表（V40，append-only）**：`id uuid pk`、`deploy_id text not null`、`source check in ('config_activation','deployment')`、`action check in ('ACTIVATE','ROLLBACK','DEPLOY')`、`service/environment not null`、`image_digest/config_digest(char64)/commit_sha/rollback_of(char64) 可空`、`actor not null`、`started_at 可空`、`effective_at not null`、`status check in ('SUCCEEDED','FAILED')`。
  - 幂等锚：`unique (source, deploy_id)`——部署脚本重试 ON CONFLICT DO NOTHING，幂等重放不产生第二个"生效"事件。
  - 读路径索引：`(service, effective_at)`。
  - 列名偏离声明：v1.0 列名 `commit` 是 SQL 关键字 → 落 `commit_sha`；`deploy_id` 语义 = 部署事实身份（config 激活行 = 每次成功 CAS 一枚 UUID）。
- **写角色与读角色分离**：`control_app` = `select,insert`（仅激活事实同事务写入 + 工具读），`revoke update,delete`（append-only DB 面）；新增 **`deploy_app` NOLOGIN 角色**（迁移内幂等 DO 块创建）= 部署脚本写路径 `grant insert` + schema usage；`revoke all` from publisher/notify/eval/public。脚本经超级用户连接 `SET ROLE deploy_app` 写入（写路径身份可审计，非 control_app 凭证）。
- **工具契约（change.query v1）**：args `since`/`until`（必填，ISO-8601 Instant）+ `service`（可选，默认 `control-app`，必须在 allowlist）；语义约束 executor 域内判（同 PrometheusQueryExecutor 惯例）：窗幅 ≤ **900s**、limit **200** 行、流式序列化字节上限 `resultLimitBytes`（超 → RESULT_OVERSIZE）；空结果 → 模型可见 **NO_DATA**（枚举已存在）；响应形状沿 `{"status":"success","data":{"result":[...],"truncated":bool}}`（SingleToolEvidenceAgent 统一解析面）。

## 3. 事务/锁边界

- **核心升级（评审 B1）**：激活/回滚事实与 pointer CAS **同一事务**——`PostgresConfigBundleRepository.activate(5参)` 在原 `tx.execute` 内先 CAS UPDATE，命中即 INSERT change_event；INSERT 失败整事务回滚 = 配置生效与证据同生死。
- **CAS 败者零事件**：CAS 0 行 → 事务内零 INSERT，返回 false（409 面）。
- **幂等重放零事件**：`movePointer()` 早退分支（current==target）不触 CAS，自然零事件；回滚后再激活同 digest = 新生效，产生新事件（deploy_id 新 UUID，锚不冲突）。
- 端口演进：`ConfigBundleRepository` 增 `ActivationFact(action, service, environment, rollbackOf)` + **default 5 参** `activate(..., fact)`（未升级实现退化为 4 参纯 CAS）；既有直调 4 参的 IT/fake 零改动。

## 4. 状态转换及拒绝原因

- executor：参数形状违约/窗幅超限/service 越出 allowlist → `ToolControlPlaneException(INVALID_ARGS)`（控制面终止族）；字节超限 → RESULT_OVERSIZE；空窗 → `ToolModelVisibleException(NO_DATA)`（模型可重试族）。
- change_event 无 UPDATE/DELETE 路径：`status` 落定即终态；失败部署记 FAILED 行（不回抹 SUCCEEDED）。
- fixture 退役：change 挂点换绑 `ChangeQueryExecutor`（真查 change_event）；`am4/fixtures/change-query.json` 迁 **test** 资源（生产镜像零 change 假件，P1-03）；logs fixture 留守（EX-B2 面）。

## 5. 持久身份

- config 激活事件：`id`=UUID、`deploy_id`=UUID 字符串、`source='config_activation'`、`config_digest`=目标 bundle digest、`rollback_of`（仅 ROLLBACK 行）= 回滚前生效 digest。
- 部署事件：`deploy_id` = 脚本生成 `am4-<UTC时间戳>`、`source='deployment'`、`action='DEPLOY'`、`config_digest`=部署时刻 active pointer、`status` 记成功/失败、`effective_at`=记录时刻（脚本健康验证完成后）。

## 6. 部署入口全清单（卡面硬要求：不只挂 deploy-am4.sh 一处）

| 入口 | 状态 | 处置 |
|---|---|---|
| `deploy-am4.sh`（根，AM4 195 全量部署） | **活** | 挂 record 助手：trap 失败记 FAILED + 成功记 SUCCEEDED |
| `m6-ev/m6-deploy-v3x.sh`、`m6-ev/m607-deploy*.sh` | 主会话工作台脚本（M5/M6 里程碑专用，一次性） | 不挂；已登记本表 |
| `tmp-195-redeploy.sh` / `tmp-195-resync.sh` | 临时手筋（已用毕） | 不挂；已登记 |
| `docs/测试证据/**/m6-deploy-*.sh` | 存档证据副本 | 非入口 |
| `deploy/db/01-roles.sh` | 角色引导（非应用部署） | 不挂 |
| 共享助手 `deploy/db/record-change-event.sh` | 新增 | 任何入口可调用（幂等锚防重）；EX-C4 断链演练复用 |

## 7. 正常/崩溃/竞争测试（具名 IT）

- **L0**：`ConfigBundleServiceTest`（fact 随 moved 指针传出/重放零 fact/ROLLBACK 带 rollback_of）、`ChangeQueryExecutorTest`（参数语义/窗幅/allowlist/NO_DATA/200 截断/字节截断静态面）、`AlertAm4ConfigTest`（生产 registry change 挂点 = ChangeQueryExecutor 类型钉，fixture 契约改测 test 资源）、`Am6MigrationContractTest`（V40 形状+授权矩阵钉）。
- **L1 具名 IT**：`ExB1ChangeEventIT`（195 真 PG）：①V40 schema/授权面（has_table_privilege：control_app insert=Y update=N delete=N；deploy_app insert=Y）②同事务激活记档+幂等重放零新行+CAS 败者零新行+回滚行 rollback_of ③executor 真查（deploy_app SET ROLE 插入→窗内可查/NO_DATA/allowlist 拒/201 行截 200）④ON CONFLICT 幂等重插。
- **195 部署演练**：`record-change-event.sh` 实跑 SUCCEEDED+FAILED 两行 + ON CONFLICT 重放零新增。

## 8. 生产配置迁移与回滚

- 迁移：V40 单文件（建表+角色+授权，两语句回滚：`drop table change_event;` + `drop role deploy_app;`）。
- 配置键：`app.alert.am4.change.service-allowlist`（默认 `control-app`）；无新增环境变量。
- 回滚：代码面回退到 fixture 挂点（git 工作树回退）；DB 面表保留无害（append-only 事实）。

## 9. 完成证据（收口回填 2026-09-10）

| 层 | 结果 | 证据 |
|---|---|---|
| L0 本地全量 | mvn test 1027 绿 0F 0E（21 IT 跳过=本机无 Docker 惯例） | `m6-ev/exb1-l0-local3.log` |
| L1 195 真 PG 具名 IT | `ExB1ChangeEventIT` 4/4 + `PostgresConfigBundleRepositoryTest` 4/4（run3，46.7s） | `m6-ev/exb1-l1-targeted3.log` |
| 官方 verify | BUILD SUCCESS / MVN_RC=0：UT 1027 + IT 163 **全量零跳过**（Testcontainers 1.20.4 真跑） | `m6-ev/exb1-verify.log`（sha256 `edc505a9…`，Total 51.7s） |
| 部署演练（drill4） | `DEPLOY_AM4_DONE (deploy_id=am4-20260909T223502Z)`：change_event 落 SUCCEEDED 行（config_digest=99f1e6cf…=真 active pointer）；同 deploy_id 重放锚 1→1 零增（B-31 修复后两态契约真值：SKIPPED_IDEMPOTENT）；FAILED 行落档（trap 语义证据）；control-app 重建 `{"status":"UP"}` | `m6-ev/exb1-deploy4.log` + `m6-ev/exb1-drill-evidence.sh` 输出 |
| 迁移 | 195 真 DB flyway V35→V36~V40 全 success；V40 checksum 1431144471 | `m6-ev/exb1-probe-grants.sh` 输出 |

- 同步完整性：源 tar 球 sha256 `c42e2ed5…` 双侧全等（球内 V40/ChangeQueryExecutor/change-query.json 探针在册）；修复文件逐次重探（IT `ff28e859…`、record 脚本终态 `0737fa9d…`、deploy-am4.sh `bc485024…`）。旧球备份 `am4-src.tmp.tar.gz.am4bak`。
- 缺陷轨迹（10 记，详见执行日志 B-22~B-31）：产品面 B-23（Jackson codec）/B-24（fail-closed 次序）/B-25（ON CONFLICT 目标列需冲突列 SELECT）；脚本面 B-28（docker exec 不转发 stdin → 谎报成功）/B-29（该 psql `-c` 不做 `:'var'` 插值）/B-30（INSERT..RETURNING 需 SELECT，insert-only 角色被拒——B-25 同律第二面）/B-31（事后 count 无法区分新插入与已存在）；测试面 B-22/B-26/B-27。
- 脚本两态输出契约（B-28~B-31 收敛终态）：`CHANGE_EVENT_RECORDED`（前 0 后 1，确有一行落库）/`CHANGE_EVENT_SKIPPED_IDEMPOTENT`（前 1 后 1，锚命中零新增）/其余 VERIFY_FAILED 非零退出；SQL 走 `psql -c` argv 面（零 stdin 依赖），值 shell 侧内联 + `''` 翻倍转义，`set role deploy_app; insert on conflict do nothing; reset role; count 回读` 单事务串。
