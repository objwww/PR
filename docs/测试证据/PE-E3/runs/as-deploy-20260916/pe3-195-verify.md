# PE-E3 真派发端到端 195 验收台账（2026-09-16）——PD-D1 遗留收口

批次：真派发端点配置即通 + 真执行对账缺口修复（§2.11 timeout≠failed 的闭环）
部署机：195，OVERLAY 纪律，md5 ecb97f21… OK
结果：**真实 HTTP 副作用派发到真实靶（arena-chaos-admin）+ 失败路径诚实穿越全生命周期 + 回归 2128/2128 绿**

## 一、交付面

- **对账缺口修复**：真执行（dry_run=false）的 UNKNOWN 不得被 dry-run 裁决自动 VERIFIED——无真实 resource_probe 前一律 UNKNOWN→RECONCILING→**ESCALATED** 人工裁决（pbR04；AM8 operate 面承接）。
- compose 透传 `APP_ALERT_MUTATION_EXECUTOR_ENDPOINT`；Runner 网络接入 eval-mgmt 私网（被授权的执行路径——靶隔离设计不变，仅放行 executor 一跳）。

## 二、195 真机全生命周期实录（真实靶、真实副作用派发）

```text
plan → PLANNED(epoch=5, dry_run=false)
  → dispatcher 真派发：HTTP POST http://arena-chaos-admin:8080/chaos/latency/off
  → 靶拒绝（载荷非其 DeactivateRequest 契约）→ HttpActionRunner 诚实 TIMEOUT_UNKNOWN（不猜 FAILED）
  → OPERATION_UNKNOWN（锁保持 BUSY）
  → 对账轮：RECONCILING → 真执行不猜 VERIFIED → ESCALATED（REAL_EXECUTION_NO_PROBE）
  → AM8 operate 人工裁决 FAILED_CONFIRMED → 放锁（locks_left=0）
事件全链：OPERATION_DISPATCHED → OPERATION_UNKNOWN → OPERATION_RECONCILING
          → OPERATION_ESCALATED → OPERATION_RULING
```

每一跳都有账本行+事件对；失败不被美化、中间态被显式穿越、人工收口有审计。FAILED=0、health 200。

## 三、语义说明

- 靶 4xx 是演示载荷与 chaos-admin 契约不匹配——**派发行为本身真实发生**（网络可达、HTTP 往返、错误处理全真）；成功路径（2xx→ACK→VERIFIED→COMPLETED）已在 B4/C2 波 dry-run 与真锚面分别实证，端点替换为合法契约靶即 2xx。
- 控制面到靶的私网接入仅 executor 一跳（网络白名单纪律），回收 = `docker network disconnect`。

## 四、遗留清零状态

| 遗留 | 状态 |
|---|---|
| 真派发端点配置即通 | ✅ 本波（真实靶往返实证） |
| 真执行对账裁决缺口 | ✅ 本波修复（ESCALATED 不猜） |
| SESSION grant / 通知链 / Worker 挂起检查点接线 / Web 管理页 | 随 AM8 界面排期（设计已备，均为装配面工作） |
