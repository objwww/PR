# PB-B3 195 部署与真机验证台账（2026-09-15）

批次：Phase B 第三片——Resource Coordinator（§2.9 R3 首日正确）
部署机：195，OVERLAY 纪律，md5 60886510… OK，.env intact
结果：**V116 真机应用 + 释放闸/孤儿化/代数单调四类真机探针全中 + 回归 2088/2088 绿**

## 一、交付面

- **V116**：`resource_mutation_lock`（行存在即 BUSY，HELD/ORPHANED 同为不让渡；orphaned_at⇔state 一致性 check）+ `resource_mutation_counter`（resource_epoch 独立成表——锁释放删行后代数仍单调；与 run lease_epoch 无关的命名澄清成文）。
- **PostgresResourceLockStore**（语义 SQL 化）：acquire = counter 行锁内领代（同资源并发串行化）+ `ON CONFLICT DO NOTHING`（任何已存在行=BUSY，唯一约束兜底并发窗）；releaseOnTerminalState = DELETE WHERE EXISTS 绑定 Operation 释放集 `{VERIFIED,COMPLETED,FAILED_CONFIRMED,CANCELLED_BEFORE_DISPATCH}`——**状态闸在 SQL 层不靠调用方自觉**；markOrphanedExpired = HELD 且 TTL 过期 → ORPHANED（锁行保留）。
- **ResourceCoordinator** 门面：B4 消费模板同事务先领锁（BUSY=显式拒绝，整个消费事务回滚）；TTL 仅孤儿化驱动 reconcile。配置 `app.alert.mutation.lock-ttl:PT10M`。

## 二、验证

- 新增 6 用例绿（ResourceCoordinatorTest 4：BUSY 显式拒绝+代数单调/UNKNOWN 拒释放终态放/TTL 孤儿化只标记不让渡/非法 TTL 装配拒 + V116 契约 2）。全量回归 **2088/2088 绿**。
- **195 真机探针实录**（探针资源 res://probe/db，事前建 UNKNOWN 态 operation+锁，事后清场）：
  - [a] **UNKNOWN 态释放试删 → deleted=0**（锁保持——side-effect zombie 关闭的核心半边）；
  - [b] TTL 已过 → `state=ORPHANED`，**行仍存 → orphan_period=BUSY**（不让渡）；
  - [c] Operation 置 COMPLETED → **deleted=1**（释放集放行）；
  - [d] counter upsert 原句连发两次 → **1 → 2**（代数单调；事务回滚不留痕）；
  - flyway **116|true**、新镜像容器 Up、health 200、FAILED=0、ERROR=0。

## 三、B 组不变量进度

| 不变量 | 状态 |
|---|---|
| UNKNOWN/RECONCILING 期间 resource lock 不让渡（无 external side-effect zombie） | ✅ 本波收口（SQL 闸 + 真机探针 a/b/c + 代数 d） |
| 其余 B 组项 | Resolver/扩张锚已收（B2）；hardline 随 hardline 落地面；事件读路径已备 |
