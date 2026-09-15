# PB-B2 195 部署与真机验证台账（2026-09-15）

批次：Phase B 第二片——Resource Resolver 权威解析 + Scope Snapshot + 授权扩张（§2.2 R6）
部署机：195，OVERLAY 纪律，md5 47cd6d3a… OK，.env intact
结果：**V115 真机应用 + resolver join 语义探针全中 + 无锚扩张试写被 PG 现场拒绝 + 回归 2082/2082 绿**

## 一、交付面

- **权威清单**（V115）：`resource_inventory`（resource_uid PK + canonical_env/team/kind + resource_version 漂移锚 + enabled 下线即不可解析）+ `resource_alias`（多请求键同源一 uid）。信任根：告警标签/业务 payload 零授权效力，身份只出自本清单（DDL 注释成文）。
- **ScopeSnapshot**（域）：七问锚定子集（requested_key/resource_uid/env/team/kind/version/policy_version）canonical JSON 固定键序 + sha256——审批批快照、B4 消费同事务复核锚；任一授权事实漂移（env/team/version/policy/请求键）→ 锚变化 → 审批期作废语义可测。
- **意图解析推进**：`IntentResourceResolver`（服务）+ `ActionIntentStore.markResolved`（CAS `resolved_resource_uid IS NULL`，恰一赢家）+ 事件同事务（INTENT_RESOURCE_RESOLVED / INTENT_RESOLVE_FAILED）。**fail-closed**：Resolver miss 零身份产出、快照不落、事件留痕 RESOLVER_MISS；已解析禁重复（锚不可覆盖）。
- **授权扩张**：`ScopeExpansionService.propose`（miss 即抛，提案不存在）→ `scope_expansion` PENDING_APPROVAL（锚位 approval_id 留空 Phase C 回填）+ SCOPE_EXPANSION_PROPOSED 事件；**`ck_scope_expansion_anchor`（APPROVED ⇔ approval_id）** 让"无锚放行"在 DDL 层物理不可能；B4 消费只认 `hasApprovedExpansion`。
- 装配：5 bean 入 AlertFlowConfig（policy-version 键 `app.alert.mutation.policy-version:pb-prod-v1`）；demo 种子 3 资源 4 别名（canonical_env=demo 显式非生产）。

## 二、验证

- 新增 10 用例绿：ScopeSnapshotTest 3（确定性/键序/漂移敏感性）+ IntentResourceResolverTest 5（成功落锚+事件/miss fail-closed 零产出/重复解析+CAS 失利全拒/扩张 PENDING 无锚/miss 提案不存在+空理由拒）+ V115 契约 2。全量回归 **2082/2082 绿**（26 跳过=Docker IT）。
- **195 实证**：flyway **115|true**；inventory=3 / alias=4 种子在位；resolver join SQL 探针——`checkout` → `res://demo/checkout|demo|payments` 命中、`checkout`/`checkout-api` 同 uid（distinct=1）、`ghost-key` miss=0；**无锚 APPROVED 试写 → `violates check constraint "ck_scope_expansion_anchor"` → expansion_rows=0**（B 组不变量"scope 扩张必有新审批锚定"真机 DDL 级实证）；新镜像容器 Up、health 200、FAILED=0、ERROR=0。

## 三、B 组不变量进度

| 不变量 | 状态 |
|---|---|
| 授权资源/env 只来自 Resolver canonical identity | ✅ 本波落定（解析服务 fail-closed + 清单唯一来源） |
| scope 扩张必有新审批锚定 | ✅ 本波 DDL 级钉死（195 现场拒绝实证） |
| UNKNOWN/RECONCILING 锁不让渡 | B3（Coordinator 落库面） |
| hardline 不可执行 | 随 hardline 清单落地面 |
| 授权事实只来自 rca_event 读路径 | 解析/扩张事件同事务在位 |
