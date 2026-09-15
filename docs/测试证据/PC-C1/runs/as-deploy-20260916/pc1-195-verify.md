# PC-C1 195 部署与真机验证台账（2026-09-16）

批次：Phase C 第一片——审批四账本（§2.4 R1 / §2.5 R8 / §2.6 R7）
部署机：195，OVERLAY 纪律，md5 55107864… OK，.env intact
结果：**V119 真机应用 + 三类约束探针全中（UNIQUE 拒同人二票/check 拒无 decided_at/single-use CAS 二发 0 命中）+ 回归 2120/2120 绿**

## 一、交付面

- **approval_request**（§2.4）：绑定稳定事实——action_digest + observed_generation（rca_run.generation 快照，换代即作废）+ scope_snapshot_hash + policy_version + expires 300s；**零瞬时执行身份**（无 worker 属主/租约代次/attempt 列——契约测试钉列定义形态）。`ck_approval_request_lifecycle`：APPROVED/DENIED ⇔ decided_at。
- **approval_decisions**（§2.6）：`UNIQUE(request_id, approver_id)` 结构性消灭同人重复票与并发读改写丢更新；DecisionQuorum 域规则——双人 = two distinct principals 且 distinct roles（同人兼两角/两人同角色都仍 PENDING），任一 denied = DENIED 终态。
- **approval_grant**（§2.5）：可复用授权范围——ONCE/SESSION（`ck: ONCE ⇒ max=1`）；配额预留 CAS（ACTIVE 且未过期且未满才 +1，满即 EXHAUSTED）；四元查找面（run+digest+快照锚+policy）。
- **operation_authorization**（§2.5/§2.8）：single-use ISSUED→CONSUMED CAS + operation_id 消费回填——「每次副作用恰好消费一个」的落点。
- **ApprovalRequestService**（意图→请求，未解析 fail-closed；R2 单批/R3 双人）+ **ApprovalDecisionService**（决策→裁决→APPROVED 即签发 ONCE Grant + APPROVAL_APPROVED/GRANT_ISSUED 事件）+ **ApprovalSweepLoop**（PT30S 清扫：PENDING 超时→EXPIRED、ACTIVE grant 过期→EXPIRED + 事件——通知面接入 AM8 前的 fail-closed 兜底）。

## 二、验证

- 新增 15 用例绿：DecisionQuorumTest 7（双人批准/同人兼两角拒/两人同角色拒/denied 终态/不足额/R2 单批/非法 required）+ ApprovalFlowTest 6（铸造锚+双人策略/未解析 fail-closed/双人批准流（含同人换角色 UNIQUE 拒）/重复决策拒/终态拒新决策+过期清扫事件/single-use 消费恰一次）+ V119 契约 2。全量回归 **2120/2120 绿**。
- **195 真机探针**：flyway **119|true**；[a] 同人同审批第二票 → `duplicate key` UNIQUE violation；[b] APPROVED 无 decided_at → `violates check`；[c] single-use 授权消费后第二次 CAS 命中 `0`（已 CONSUMED）、SESSION grant max=3 形状在位；[d] 清场零残留；ApprovalSweepLoop 启动行在案（PT30S）；health 200、FAILED=0。

## 三、C 组不变量进度

| 不变量 | 状态 |
|---|---|
| 双人 = two distinct principals（一人兼两角色不满足） | ✅ 本波（域规则 + UNIQUE 双层） |
| 每次副作用恰好消费一个 single-use OperationAuthorization | CAS 面就位——C2 消费模板真锚接入后全链生效 |
| grant 过期/撤销后授权不可签发 | 配额 CAS 含过期判断 ✅；撤销链随 C2 作废矩阵 |
| 审批零绑定瞬时执行身份 | ✅ DDL 列形态契约测试钉死 |
| 换代/policy 变更 → 审批作废 | 锚已入 Request——C2 消费复核 + 清扫双保险 |
| 挂起不占 slot、system_active 冻结 wall 不冻结 | C3（durable suspension + 双时钟） |
| 通知失败 fail-closed | 300s 超时兜底在位；通知链随 AM8 |
