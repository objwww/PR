# PB-B1 195 部署与真机验证台账（2026-09-15）

批次：Phase B 第一片——Mutation Safety Plane 账本层（§1 执行链入口 + §2.11 状态机域钉）
部署机：195（146.56.195.225），OVERLAY 纪律，md5 3fe72d1e… 对拍 OK，.env intact（备份 /tmp/env-backup-b1-20260915T234037）
结果：**V114 迁移真机应用 + 账本形状全对 + 全量回归 2072/2072 绿 + 容器新镜像重启健康**

## 一、交付面

- **V114 迁移**：`action_intent`（意图台账，§2.1 digest 锚：action_digest 64 位 + risk 只收 R2/R3 + `(status='PLANNED')=(operation_id is not null)` 生命周期一致性）+ `rca_operation`（§2.11 全 11 态 check + **`ck_rca_operation_dry_run_phase check (dry_run)`——Phase B 真实执行面 DDL 钉死，A10 同族，Phase D 解锁日退役**）+ 非终态扫描索引（B5 reconcile/悬挂发现用）+ control_app 授权。
- **域模型**（domain/mutation）：`OperationStatus`（isTerminal 与 releasesResourceLock 两把正交判定——**ESCALATED 终态但锁保持至人工裁决**，§2.9 释放矩阵）；`OperationStateMachine`（合法边表：UNKNOWN 只能→RECONCILING，中间态显式穿越；终态零出边；越边抛 IllegalTransitionException，shared-kernel 增补 String 构造器）；`RcaOperation`（非 dry_run 构造即抛——域层第二道 A10 闸；withStatus 自动携带 dispatched/ack/verified/completed 时点）。
- **意图落账**：`ActionIntentLedger` 端口 + `PostgresActionIntentLedger`——意图行 INSERT 与意图事件 append **同一 REQUIRES_NEW 短事务**（行=投影，事件=授权事实读路径，B 组不变量）；ToolGateway 8 参形态接线（ledger 优先于独立事件路径；**ledger 与 events 双 null 才跳过**——修复首版 events 单独短路导致生产面意图永不落行的装配缺陷）；ReadOnlyToolFace 10 参透传；`am4ShadowToolFace` bean 接线。

## 二、验证

- 新增测试 9 用例全绿：OperationStateMachineTest 5（合法边 13 条逐条/越边+终态复活全拒含自环/锁释放矩阵 ESCALATED 不释锁/状态推进时点携带与越边抛/dry_run 铁律）+ ToolGatewayIntentLedgerTest 2（R2 调用行与事件同键 intent_id、台账未装配独立事件路径零漂移）+ Pb1MutationLedgersMigrationContractTest 2。
- 全量回归 **2072/2072 绿**（26 跳过=无 docker IT）；ControlArchitectureTest 21/21（分层：domain.mutation/tool、application/mutation 端口、infrastructure 实现全过）。
- **195 实证**：flyway **114|true**；两表在位、rca_operation 关键列（status/dry_run/resource_uid/resource_epoch/action_digest）与三约束（status/dry_run_phase/prepared）全对；`intent_rows=0` + `operation_rows=0` = 生产注册面全 R0 意图/操作零发生（诚实负证，真值随 R2 解封触发）；新镜像容器重启（Started 14.495s）、health 200、FAILED=0、ERROR=0。

## 三、部署过程登记

- 首次部署脚本漏 `docker compose build control-app` → `up -d` 未重建容器（旧镜像续跑，health 200 为旧容器）——补 build+up 后新镜像容器实证（部署脚本纪律：**凡代码变更波次必须显式 build 再 up**）。
- 本地构建环境：`mvn ... > log 2>&1` 重定向在本 shell 偶发"reactor 扫描失败"假错误（管道 findstr 正常）——后续 mvn 一律管道取结果；`install` 须先刷 shared-kernel 本地仓（NoSuchMethodError 定位到本地仓旧 jar）。

## 四、B 组不变量进度（Phase B 激活中）

| 不变量 | 本波状态 |
|---|---|
| hardline 任何路径不可执行 | 不变式既有（Hardline 随 Phase B 后续波入 DDL/策略面） |
| R2/R3 零副作用（A10） | **DDL+域双闸钉死**（dry_run check + 构造即抛） |
| 授权资源只信 Resolver | 账本 `resource_uid` 可空设计就位——B2 落 Resolver 后生效 |
| UNKNOWN/RECONCILING 锁不让渡 | 状态机+释放矩阵域钉——B3 Coordinator 落库面 |
| 授权事实只来自 rca_event 读路径 | 意图行与事件同事务语义落地 |
