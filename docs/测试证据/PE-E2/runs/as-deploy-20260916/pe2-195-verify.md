# PE-E2 AM8 人工面 195 验收台账（2026-09-16）

批次：审批人工决策 / ESCALATED 人工裁决 / 隔离区人工放行三入口（零迁移）
部署机：195，OVERLAY 纪律，md5 b8cdba0f… / 46318e01… 对拍 OK
结果：**三入口真机全通 + 全量回归 2127/2127 绿 + 一条 PA-BUG（/error 重派发 401 化）登记**

## 一、交付面

- **POST /api/mutation/decide**：PENDING 审批的人类裁决入口——`guardian:` 前缀禁用（人类不得冒用机器审批身份）；IllegalState/IllegalArgument 就地转显式 REJECTED。
- **POST /api/mutation/operate**：ESCALATED operation 人工终裁（COMPLETED | FAILED_CONFIRMED）——状态机新增 ESCALATED 人工裁决专用双出边（终态复活的唯一合法通道），裁决后 `releaseOnTerminalState` 放锁 + OPERATION_RULING 事件（裁决人/结论/放锁事实入 run 事件账本）。
- **POST /api/inbox-admin/alerts/{id}/release**：QUARANTINED→RECEIVED 人工放行（PA-A3 状态机预留边的管理入口），放行人写回 last_error 审计列。
- 归 operator 角色 + 机器 bearer 线 CSRF 豁免（config-bundles 同律）。

## 二、195 真机验收实录

1. **人工双批 R3**：种子 R3 request（required=2）→ 第一票 PENDING → 冒用 `guardian:fake` → `REJECTED INVALID_APPROVER` → 第二票 distinct principal+role → **APPROVED**（two distinct principals 全链实证）。
2. **ESCALATED 人工裁决**：种子 ESCALATED op + HELD 锁 → operate FAILED_CONFIRMED → `op_after_ruling=FAILED_CONFIRMED`、`lock_released=true`、OPERATION_RULING 事件在案。
3. **隔离区人工放行**：A3 遗留 QUARANTINED 行 → release → `inbox_state=RECEIVED`、released_by 入审计（放行行将正常重驱投影）。
4. FAILED=0、health 200、全量回归 2127/2127 绿。

## 三、PA-BUG 登记（/error 重派发 401 化）

排查 401 假象时定位：控制器异常穿透 → Spring Boot /error ERROR dispatch **再次穿过安全过滤链**（此派发无认证上下文）→ anyRequest.denyAll + 匿名 → 认证入口点返回 401 `{"error":"unauthorized"}`——**任何未处理控制器异常在本应用都表现为 401**，掩盖真实错误（本轮真实异常 = releaseQuarantined 的 `jsonb_set` 打在标量 last_error 上，PG "cannot set path in scalar"）。修复：SQL 改 `jsonb_build_object` 整体重建（旧值入 previous_error，任意形状安全）+ 新控制器对可预期异常就地转显式 REJECTED。教训：写面控制器禁止让异常穿透到容器 /error。

## 四、AM8 人工面全景（本批后）

| 人工入口 | 状态 |
|---|---|
| 审批裁决（R3 双人/R2 单批，UNCERTAIN 转人工） | ✅ 本批 |
| ESCALATED operation 人工裁决（放锁收口） | ✅ 本批 |
| 隔离告警人工放行（QUARANTINED→RECEIVED） | ✅ 本批 |
| 通知链（决策推送值班）/Web 管理页 | 随 AM8 界面排期 |
