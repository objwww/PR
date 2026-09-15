# PA-A4 195 部署与真机验证台账（2026-09-15）

批次：Phase A 第四片（B v2 L5-3 五模式双阈值表）——矩阵 L5-3 ⚠️ 收口
部署机：195（146.56.195.225），OVERLAY 纪律，md5 7930cf34… 对拍 OK，.env intact
结果：**部署成功 + 全量回归 2048/2048 绿 + 三层回环守卫全部在位**

## 一、五模式对照（B v2 表 → 实现）

| 模式 | B v2 阈值 | 实现 | 状态 |
|---|---|---|---|
| Exact same action/error | warn 2 / stop 5 | DoomLoopGuard v2 双阈值（`warnAfterNoProgress`/`stopAfterNoProgress`；inExactRepeatWarningZone 观测面） | ✅ 本波 |
| Same tool different args all fail | retry budget | 既有 TOOL_CALL 预算硬闸（RunBudgetGate，ExA1）——映射登记非新码 | ✅ 既有 |
| Monologue（连续独白无工具调用） | warn 2 / stop 3 | **新建 RoleLoopGuard**（轮次级）接 BoundedLlmRoleRunner：TOOL_CALL 分支重置、DELEGATE/FINAL/不可解析计步；STOP → deterministicFinal（LOOP_MONOLOGUE_STOP，零模型调用 PAUSE/ESCALATE 同族） | ✅ 本波 |
| Ping-pong（两动作交替） | warn 4 / stop 6 | DoomLoopGuard per-task 无进展签名序列，窗口交替检测（恰两签名+两两相邻不同）→ 双双熔断；三签名轮转不误伤 | ✅ 本波 |
| Context window error | warn 1 / stop 2 | BA-120 模型面同签名二次熔断（同 errorCode+稳定信封第 2 次 → 确定性 FINAL）——语义等价映射登记 | ✅ 既有 |

## 二、兼容性与配置

- DoomLoopGuard 旧 3 参 Policy 构造映射 warn=stop、ping-pong 关闭——**既有装配/测试语义零变化**；生产装配升 am4-doom-v2（warn 2/stop 5/ping 4/6，键 `app.alert.am4.doom-loop.*`）。
- RoleLoopGuard 配置 `app.alert.r7.primary.monologue-warn/stop`（2/3，版本 r7-monologue-v1），随 am4BoundedLlmRoleRunner bean 接线；旧 runner 构造 delegates permissive（既有测试零改动）。
- 阈值联合校验（1≤warn≤stop；4≤pingWarn≤pingStop）。

## 三、验证

- 新增 FivePatternLoopGuardsTest 9 用例全绿（exact 双阈值/进度重置/乒乓 4 警 6 停双熔断/三签名不误伤/兼容构造/非法阈值/monologue 2 警 3 停+工具重置/permissive/版本审计）。
- **全量 2048/2048 绿**（26 跳过=无 docker IT）；DoomLoopGuardTest 7、R7RoleRunnerTest 20、R7DelegationBatchesKnobTest 4、R7ActionGuardAdmissionTest 12 无回归。
- 195：health 200、FAILED=0、全循环启动正常、验链作业同容器共存全绿（runs=29）、PRIMARY_ENABLED 在位（monologue 守卫在主模式 runner bean 中生效）。
- 行为级证明说明：回环守卫为进程内观测+熔断面，真机"触发"需构造独白/乒乓模型行为（依赖真 LLM 输出不可控）——语义正确性由本波 9 用例+既有 31 例守卫测试承载，195 部署验证装配健康与共存性。

## 四、遗留

- 预警区（WARN）当前为观测面：DoomLoopGuard 预警查询已备，工具侧调用方（SingleToolEvidenceAgent）的 WARN 日志挂点与 Runner 的 monologue WARN 事件外发归下一波（不拦截语义不受影响）。
