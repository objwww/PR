# PE-E1 195 验收台账（2026-09-16）——Phase E Limited Autonomy & 五阶段终章

批次：Guardian 预审 + 低危自动执行 + Hardline + 模型分派 eval 终裁钉面（§2.3/§2.0/§3.1/§5 Phase E）
部署机：195，OVERLAY 纪律，md5 cbefef14… OK，.env intact
结果：**Phase E 真机验收全绿 + 方案 B v2 五阶段（E0/A/B/C/D/E）全部收官 + 回归 2127/2127 绿**

## 一、交付面

- **MutationGuardian（§2.3 面体）**：确定性封闭三值——低危白名单工具 + 参数在预算内 = SAFE；参数超限 = UNSAFE；工具不在白名单/解析失败 = **UNCERTAIN 转人工**（绝不猜 SAFE）。**权限单调**：R3 一律 UNCERTAIN（Guardian 无升级放行权，R3 永远人工 two distinct principals）。GUARDIAN_REVIEWED 事件同事务留痕。
- **GuardianAutoDecisionService**：SAFE → 自动铸 Request + 自动批准（approver=`guardian:pb-prod-v1`、role=`GUARDIAN`——机器审批在 decisions 表显式可见，R2 单批即达 quorum → Grant 签发）→ 消费模板照常真锚；UNSAFE → 自动 DENIED；UNCERTAIN → 不铸不代决，人工 decide 面接管。
- **Hardline（§2.0 第一裁决层）**：planner 先于一切的绝对禁止清单（`app.alert.mutation.hardline-tools`）——命中即 REJECTED("HARDLINE")，解锁注册表/Guardian 都解不了锁。
- **模型分派 eval 终裁（§3.1）**：维持 DeterministicSupervisor——dispatch-intent capability 不开（`"capability exists + evaluated + policy decided deterministic orchestration is safer"` ✅ 形态），策略钉面写入本台账，重开须经 eval 数据支持另立评审。
- compose 透传：GUARDIAN_LOW_RISK_TOOLS / GUARDIAN_MAX_PARAM_CHARS / HARDLINE_TOOLS。

## 二、195 真机验收实录

1. Guardian 自动决策：`{"verdict":"SAFE","approval_state":"APPROVED","reason":"LOW_RISK_ALLOWLIST + PARAMS_IN_BUDGET"}`，decisions 行 = `guardian:pb-prod-v1/GUARDIAN/approved`（机器审批显式可见）。
2. plan → `PLANNED (epoch=4)` → `dry_run=false` 真执行铸造（端点未配走诚实 UNKNOWN 兜底——同 PD-D1）。
3. **Hardline 活体**：intent 换 `drop.database` → `{"status":"REJECTED","reason":"HARDLINE"}`——第一裁决层先于注册表与 Guardian。
4. `GUARDIAN_REVIEWED` 事件在案；shadow-summary：requests=2 / approved=2 / avg_decision_seconds=0.0116 / human_wait=20.026；FAILED=0、health 200。

## 三、五阶段终账（方案 B v2 主线全部收官）

| Phase | 状态 | 交付（迁移） |
|---|---|---|
| E0+A | ✅ | 不变量门 + 进度/哈希链/隔离/卫兵/溯源/OTel/HMAC（V111–V113） |
| B | ✅ | 意图→解析→协调→Outbox→对账/闸/重建（V114–V118） |
| C | ✅ 验收通过 | 审批四账本→真锚消费→挂起/双时钟/观测（V119–V120） |
| D | ✅ 解锁日 | 三元白名单 + prepareReal + HttpActionRunner（V121） |
| E | ✅ 本批 | Guardian 三值/自动执行 + Hardline + 模型分派钉面（零迁移） |

全量回归 **2127/2127 绿**；架构门全过；195 flyway 121|true、health 200、FAILED=0。

## 四、持续运行纪律（Limited Autonomy 的"有限"面）

- 解锁范围扩行须走注册表（三元显式行），Guardian 白名单与注册表独立配置——双闸不联动；
- R3 双人解锁按注册表模式扩行 + Guardian 永不介入；
- Hardline 清单为运维面终审配置，任何自动化不得改写；
- eval 持续跑批：Guardian SAFE 的正确率（UNCERTAIN 应转尽转、SAFE 零事故）进入回归观测，劣化即回收白名单行；
- 模型分派重开唯一路径 = eval 数据支持 + 另立评审。
