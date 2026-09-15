# PC-C2 195 部署与真机验证台账（2026-09-16）

批次：Phase C 第二片——消费模板真锚接入 + Approval Shadow 全链（§2.8 步骤 1/2 落地）
部署机：195，OVERLAY 纪律，md5 727cd3d7… / 2b04aa6c… / 7d5f18a8… / 37392d3c…（四批迭代），.env intact
结果：**真锚模式端到端真机走通：plan→授权消费→PREPARED→dispatcher→COMPLETED→放锁→二次 NO_GRANT + 回归 2122/2122 绿**

## 一、交付面

- **OperationPlanner 真锚模式**（`app.alert.mutation.approval.enabled`，默认 false=哨兵兼容）：§2.8 五步的步骤 1/2 从哨兵占位升级为真实审批门——`findActiveGrant`（run+digest+快照锚+policy 四元匹配，**换代/撤销/过期/policy 漂移结构性作废**）→ `tryReserveGrantQuota` CAS → `insertAuthorization`（single-use ISSUED）→ 步骤 3/4 照旧 → `consumeAuthorization`（ISSUED→CONSUMED+operation_id 回填，同事务）→ OPERATION_PREPARED 事件携带 grant/authz 真锚（DRY_RUN_SENTINEL 字样消失）。显式拒绝族：`NO_GRANT` / `GRANT_NOT_RESERVABLE` / `AUTHZ_CAS_LOST`。
- **ApprovalPlannerGate** 窄端口（四方法）——PostgresApprovalStore 同体实现；**bean 返回具体类型**（教训见下）。
- **MutationOpsController**（POST /api/mutation/plan）：operator 角色防门 + 机器 bearer 线 CSRF 路径级豁免（config-bundles 同律）+ ObjectProvider 容错（默认 profile 无装配面显式 UNAVAILABLE）。

## 二、验证

- 新增 PlannerApprovalGateTest 2 用例（无 grant NO_GRANT 零写入 / 真锚全链+ONCE 二次 NO_GRANT）；全量回归 **2122/2122 绿**。
- **195 真机全链**（operator bearer 驱动真实 planner，env 键在位核验=1）：
  - `POST plan → {"status":"PLANNED","operation_id":"3b3602ad…","resource_epoch":2}`；
  - 15s 内 `op_status=COMPLETED`（dispatcher 走链）、`authz_state=CONSUMED + authz_op_backfilled=1`（single-use 恰一次）、`grant_state=EXHAUSTED`（ONCE 消费即尽）、`locks_left=0`（VERIFIED 释放闸）、`prepared_event_has_grant=true`（审计事件携真锚）；
  - 二次 plan → `{"status":"REJECTED","reason":"NO_GRANT"}`（耗尽结构性拒绝）；无凭据 401；FAILED=0。
  - 演示行保留为 Phase C 首条活体 shadow 审计记录（intent/request/decisions/grant/authz/operation 全链在档）。

## 三、部署翻车两连与教训（BA 级过程登记）

1. **bean 返回类型抽象藏实现接口**：approvalStore @Bean 声明返回 `ApprovalStore`，Spring 按工厂方法签名做注入类型预测 → ApprovalPlannerGate 注入失败 → 195 docker 启动失败。本地冒烟 profile 不载 AlertFlowConfig，全量 2122 绿也未拦截——**跨 profile 装配变更，本地绿 ≠ docker 面 OK**。修：返回具体类型（e65e5cf3）。
2. **compose 键未透传**：`.env` 加了 `APP_ALERT_MUTATION_APPROVAL_ENABLED` 但 compose 无映射——env 根本不入容器，app 恒跑哨兵模式。二次 INTENT_CAS_LOST + 事件无 grant 锚 + authz 零行三证定谳（24c8657）。修后全绿。
3. 复位语句漏清 operation_id 触发 `ck_action_intent_lifecycle`——反向证明 V114 生命周期一致性约束在真机活着。

## 四、C 组不变量进度

「每次副作用恰好消费一个 single-use OperationAuthorization」：**本波真机全链收口**（CONSUMED+回填恰一行，二次 NO_GRANT）。「grant 过期/撤销后授权不可签发」：四元匹配含过期/撤销态过滤。换代/policy 漂移作废：结构性（快照锚/policy 入匹配键）。剩：durable suspension + 双时钟（C3）、通知 fail-closed 链（AM8）。
