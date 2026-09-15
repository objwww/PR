# PC-C3 + PD-D1 195 验收台账（2026-09-16）——Phase C 收官 & Phase D 解锁

批次：C3 durable suspension + 双时钟（V120）；D1 R2 极小解锁 + 真执行面（V121）
部署机：195，OVERLAY 纪律，md5 f352bfcb… / 1f6542be… 对拍 OK
结果：**Phase C 退出验收通过 + Phase D 首个真执行铸造真机实证 + 回归 2124/2124 绿**

## 一、PC-C3（Phase C 收官波）

- **V120 approval_suspension**：同 run 至多一个 SUSPENDED（唯一部分索引）；`human_wait_seconds = wall clock 差`单列（§2.10 双时钟：人的等待不进系统耗时，wall clock 永不冻结）。
- **RunReconciler 16 参豁免面**：挂起 run 豁免 AUTO_EXPIRE / LIVE_BUT_STUCK 两条自动终止路径（旧构造 null=关闭零漂移；service 缺席装配自动降级）。
- **三端点**：POST /api/mutation/suspend、/resume（落 human_wait）、GET /shadow-summary（§5 观测清单投影：requests/approved/denied/expired/grants/consumed_authorizations/avg_decision_seconds/挂起计数/human_wait 合计）。
- **195 实证**：flyway 120|true；挂起→20.026s 后恢复→`human_wait_seconds=20.026` 精确落列；shadow-summary 挂起前后反映全链（C2 消费记录 requests=1/approved=1/consumed=1 全部可解释）；FAILED=0。
- **Phase C 退出验收**：影子指标达标（观测面在位、全链记录可解释）+ B 组不变量全绿（B 阶段全数落定）→ **Phase C 通过**。

## 二、PD-D1（Phase D 解锁日）

- **V121**：`ck_rca_operation_dry_run_phase` DDL 闸退役（A10 退役日，§5 纪律）；`mutation_unlock_registry` scoped 白名单——tool × resource_uid × canonical_env 三元全匹配且 enabled 才真执行（默认封死，不通配不前缀）。种子一行：chaos.resolve @ res://demo/checkout @ demo。
- **域闸改写**：`RcaOperation` 非 dry_run 构造不再抛（opM05 测试按解锁日纪律改写为 prepareReal 语义断言）；`prepareReal` 工厂 = 唯一真执行铸造入口。
- **Planner 分流**：注册表匹配 → `prepareReal`（dry_run=false）+ 事件 `unlocked:true`；不匹配 → 永远 dry-run。人工审批 mandatory 不变（grant 消费前置在解锁判定之前）。
- **HttpActionRunner（真执行面）**：POST 派发载荷（operation_id/action_id/digest/resource_uid/params）到配置端点；非 2xx/异常 = `TIMEOUT_UNKNOWN`（timeout≠failed，不猜 FAILED）；端点缺席 = dispatcher 分流兜底 UNKNOWN（note=REAL_EXECUTOR_ABSENT）→ reconcile ESCALATED。
- **195 实证**：flyway 121|true；注册表行在位；intent 工具切 chaos.resolve 后 plan → `PLANNED (epoch=3)` → operation **`dry_run=false`**（系统首个真执行铸造）→ dispatcher 分流 → 真执行端点未配置走诚实 UNKNOWN 兜底；shadow-summary 同步；FAILED=0。
- **边界声明**：195 演示未配 executor 端点——真派发的最终一跳（对 arena-chaos-admin 等 demo 靶的真实 HTTP 副作用）按配置即插即用；本批验收覆盖解锁判定、真执行铸造、分流与诚实兜底全链。

## 三、四阶段总账（E0+A/B/C/D 主线）

| Phase | 状态 | 关键交付 |
|---|---|---|
| E0/A | ✅ | 回归不变量门 + 进度面/哈希链/注入隔离/循环卫兵/溯源/OTel/HMAC（V111–V113） |
| B | ✅ | 意图/操作账本→权威解析→资源协调→Outbox 派发→对账/闸/重建（V114–V118） |
| C | ✅ | 审批四账本→真锚消费→挂起/双时钟/观测（V119–V120） |
| D | ✅ 极小解锁 | 注册表三元白名单 + prepareReal + HttpActionRunner（V121） |

全量回归 2124/2124 绿（26 跳过=Docker IT）；架构门全过；195 flyway 121|true、health 200、FAILED=0。
遗留：真派发端点配置即通（Phase E 前）；SESSION grant/通知 fail-closed 链/Worker 挂起检查点全量接线随 AM8/Phase E 排期；R3 双人解锁按同一注册表模式扩行即可。
