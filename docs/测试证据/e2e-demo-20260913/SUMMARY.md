# 端到端验收演示证据包（2026-09-13 15:30~15:50，195 真机）

执行者：主会话。窗口独占（无其他执行者在跑）。代码态：main=5b85535（195 部署与其逐字节同源，67dc8ff AS 批含在内）。

## 一、演示问题：能不能端到端告警并根因分析？

**链路答案：能，全通且通知真实送达。根因质量答案：诚实收敛，ROOT_CAUSE 级确诊真模型下仍未达成。**

### 跑 A：run d122eb0b（复用 incident 141c5cb3，A0 v2 FAULT_POSITIVE）

| 环节 | 证据 | 判定 |
|---|---|---|
| 告警注入→原子对 | phase2 PASS（WHITELISTED 审计行 run_id 非空） | ✅ |
| run 终态 | SUCCEEDED（engine=NATIVE 主模式 ON） | ✅ |
| 模型在环 | rca_model_call 8 行 qwen3-max-preview 全 SUCCESS（见 inspect-run.out） | ✅ |
| 工具取证 | prometheus.query×3 + metric_value×3 + logs.query×1 全 SUCCESS | ✅ |
| Claim | HYPOTHESIS/UNKNOWN/ACTIVE，2 引用带 SUPPORTS 角色（协议 v2 采纳面）；statement 如实指出「UNKNOWN 错误主导、集中 PaymentService/Charge、缺乏日志面证据」 | ✅ 诚实收敛 |
| 报告 | STRUCTURE_VALIDATED（native-deterministic-v1） | ✅ |
| 发布 | report_publication_loser——输给 generation 1 赢家，落档不发布零外发 | ✅ **重复调查仲裁设计行为实证**（非缺陷） |

### 跑 B：run 8c0c30ef（新 incident a1da7b24，generation 0，唯一 alertname）

| 环节 | 证据 | 判定 |
|---|---|---|
| 告警→原子对→run SUCCEEDED | phase2/3 PASS | ✅ |
| 主链 | 恰 1 PRIMARY_INVESTIGATE DONE、零 DELEGATE、检查点 evidence_roles 齐（phase4 PASS） | ✅ 零委派直查 |
| 工具取证 | prometheus.query×4 + metric_value×4 全 SUCCESS | ✅ |
| Claim | 零提案（模型保守收敛，报告 UNRESOLVED 兜底） | ⚠️ 已登记模型行为面 |
| 首次发布 | report_generation_winner(winner_run_id=本 run) → publication SENT → **notify_outbox 双通道 SENT（oncall+test，07:33:57Z）** | ✅ **通知真实送达** |

- phase5 FAIL 性质：断言面白名单缺省（prometheus.query,logs.query）不含 metric_value——脚本参数与 jar 缺省 8 工具面不匹配，非系统越权（8 次调用全在 jar 白名单内）。FAULT_POSITIVE 的 TRUE Claim 门禁未进入判定。
- 演示告警已 resolved（HTTP 202），incident 留档。

## 二、前后端联通矩阵（fe-matrix.txt + 三轮复测）

登录全环真实通过：POST /auth/login 200 → /auth/me 200 → 登出 200 → 登出后 401。

**29 个 GET + 2 个 POST 端点全部核验**（经 127.0.0.1:8090 web 容器同源 /api 代理）：初测 5 个非 200 全部证实为**矩阵脚本参数形状错误**而非系统缺陷，逐一复测转正：

| 初测 | 真因 | 复测 |
|---|---|---|
| /api/metrics/query_range 400 | 我用了 ISO 时间戳+非白名单 query=up（白名单 5 项：host_cpu/mem/disk、alert_receive_rate、run_throughput） | epoch+host_cpu_usage → **200** |
| /api/rca-runs/{id}/events/stream-ticket 403 | 该面是 POST（换票=状态变更），我错用 GET | POST+XSRF → **200** |
| /api/eval/reviews/assignments 400 | scope 枚举是 mine/all（EV-08 契约），我错用 QUEUE | scope=mine → **200** |
| /api/duty/overrides 403 | GET 面不存在（读面经 schedule/snapshot 聚合，前端只 POST/DELETE）——denyAll 正确 | 设计正确 |
| /api/mcp-servers 403 | mount-enabled 默认关 bean 不注册→无映射→denyAll（EN-06 fail-closed 设计） | 设计正确 |

页面侧：SPA 入口/login/路由回退 200；未认证 API 一律 401。浏览器级 E2E 无设施（NOT_RUN 如实）。

## 三、过程中发现/处置

1. **CRLF 脚本坑**：e2e-r7-common.sh/v2 上 195 首跑即崩（`$'\r': command not found`）——Windows 提交带入 CRLF。195 副本已 sed 修复（md5 从此与仓库不同，登记在案）；**仓库侧 .sh 仍 CRLF，建议补 .gitattributes（eol=lf for *.sh）**。
2. **演示口令轮换窗**：为登录矩阵临时替换 AUTH_OPERATOR_PASSWORD_BCRYPT。第一轮被 compose .env 的 `$$` 转义规则吃掉（单 $ 值插值成残串），复测容器 env 发现后修正；矩阵完成后**原件恢复+重建+演示口令登录 401 验证失效**；pgcrypto 临时扩展已 DROP。
3. 操作员 env 文件权限 644→600 收紧（bearer 文件纪律）。

## 四、NOT_RUN / 诚实边界

- A0 v2 断言包全程绿未达成（phase5 参数面+FAULT_POSITIVE TRUE 门禁）——需专用姿态窗（allowlist 两工具+prompt 调优），归后续模型窗。
- MC34 三臂复测、AS-09/10/12 留出集、浏览器 E2E、Skill SC-1~3：维持登记。
- 根因正确性北极星未变：eval 三批 0 命中+真模型 L 模式定谳（模型工具调用可靠性能力面）。本次两跑均为诚实收敛（无假根因），这是平台语义正确的表现，不是根因能力的证明。
- run26 口径继续有效：结构合法≠根因证实。
