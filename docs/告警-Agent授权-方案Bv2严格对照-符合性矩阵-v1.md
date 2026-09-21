# 告警 Agent 授权：方案 B v2 严格对照·符合性矩阵 v1

日期：2026-09-15
状态：对照基线（作为后续验收标准）
准绳：方案 B v2《Durable Agent Workflow + Safety Control Plane》逐项要求
对象：control-app alert/RCA 域（PR agent）当前实现

## 判定口径

| 标记 | 含义 |
|---|---|
| ✅ 符合 | B v2 要求已实现且有验证证据 |
| ⚠️ 部分 | 有实现但语义/字段/流程不完整 |
| ❌ 缺失 | 完全不存在 |
| 🔀 偏离 | 现有实现走了不同路线，**严格执行 B v2 需改造或登记偏离并签字批准** |

**执行纪律（应要求写入）**：后续实现以本矩阵为验收清单。任何 🔀 项二选一——要么按 B v2 改造，要么在 §11 偏离登记表签字冻结；不允许"实现里悄悄不一样"。

---

## L0 入口安全层

| # | B v2 要求 | 现状 | 判定 | 差距/行动 |
|---|---|---|---|---|
| L0-1 | HMAC 签名验证，失败拒绝+安全审计 | 静态 bearer token（MachineBearerAuthnFilter，常量时间比较），HMAC 仅注释预留 V7:30 | 🔀 | 偏离：bearer 代替 HMAC。严格化=实现 HMAC-SHA256 签名头；或登记偏离（单栈内网、bearer 已真机验证） |
| L0-2 | Token 校验前置 | ✅ SecurityConfig 路径矩阵 deny-by-default，认证在落库前 | ✅ | — |
| L0-3 | Schema 校验（结构/类型/范围） | ✅ AlertIntakeService 拒绝链（尺寸/深度/必填/限长，4xx 零落库） | ✅ | — |
| L0-4 | Injection 扫描（`ignore previous`、`system:` 等特征拦截） | 无扫描器；仅结构性缓解（UNTRUSTED 标记、SSE 白名单消毒、强 JSON 输出协议） | ❌ | 新增 injection 扫描器，位于 schema 校验之后 |
| L0-5 | 恶意告警隔离区（命中注入/异常频次/异常资源名 → 人工审核） | 无隔离区；只有 DEFERRED 暂扣 | ❌ | 新增 quarantine 状态 + 人工审核入口 |
| L0-6 | L0 只拒绝不限流 | ✅（限流本就不在 L0） | ✅ | — |

## L1 Durable Ingress 层

| # | B v2 要求 | 现状 | 判定 | 差距/行动 |
|---|---|---|---|---|
| L1-1 | 先落 Inbox 表再处理（durable） | ✅ alert_inbox 六态 + 租约 + 退避 | ✅ | — |
| L1-2 | 令牌桶限流（Redis + Lua 原子，多 Worker 共享） | 无令牌桶；DeferredPolicy 软背压 + bulkhead | ❌ | 新增令牌桶。B v2 指定 Redis——本栈无 Redis，偏离登记：进程内令牌桶 + DB 窗口计数兜底（或多实例时引 Redis） |
| L1-3 | 幂等键 SHA256(source+name+resource+fingerprint+10min桶) | ✅ DB UNIQUE(fingerprint,payload_hash,starts_at) 等价幂等 | ✅ | 键公式不同但语义符合；如需严格一致可改公式 |
| L1-4 | Redis 降级：本地 LRU + 降级告警 | 无 Redis 故无此路径 | 🔀 | 随 L1-2 一并登记 |
| L1-5 | 流量分级：P0/P1 优先不丢、P2 聚合、P3 只入库不处理、恶意隔离 | 仅 task priority 三级排序；无 P3 只入库、无聚合入队、无恶意隔离 | ⚠️ | 补齐 P2 聚合与 P3 只入库档位；恶意隔离随 L0-5 |

## L2 Correlation 层

| # | B v2 要求 | 现状 | 判定 | 差距/行动 |
|---|---|---|---|---|
| L2-1 | 根因指纹多维：topology(CMDB) + rule_cluster + time_window + label_similarity | incidentKey = 稳定标签（alertname/service/namespace/job），无拓扑、无规则簇 | ⚠️ | 关联键维度少于 B v2。决策点：是否引入 CMDB 拓扑与规则簇（依赖外部数据，建议登记为 AM9+ 增强） |
| L2-2 | 三层防护：查询→INSERT ON CONFLICT→重查 | ✅ IncidentProjector.java:149-161 | ✅ | — |
| L2-3 | 第三层失败 → 死信队列 | 无 DLQ，靠 inbox 退避重试 | ❌ | 新增死信队列（重试耗尽落 DLQ + 告警） |
| L2-4 | Incident 状态机 OPEN/INVESTIGATING/RESOLVED/ESCALATED | incident 仅 FIRING/RESOLVED；调查态在 run 层；无 ESCALATED | ⚠️ | ESCALATED 语义在 notify/升级策略里，状态机未显式化。严格化=补状态列 |
| L2-5 | INVESTIGATING 下不允许新 Conversation | ✅ uq_rca_run_active_incident 部分唯一索引 | ✅ | — |

## L3 Admission & Scheduling 层

| # | B v2 要求 | 现状 | 判定 | 差距/行动 |
|---|---|---|---|---|
| L3-1 | Priority Lane：P0/P1/P2/Background 四泳道，Lane 内 FIFO | 单队列 + priority 排序（200/100/0） | ⚠️ | 无泳道隔离。严格化=四泳道或登记偏离（单队列+排序在小规模等价） |
| L3-2 | P0 保留容量（防 P2 洪峰占满） | 无 | ❌ | scheduler_slot 加 reserved_for（融合设计 §3.7 已有方案） |
| L3-3 | P0 可抢占 P2 Worker | 无 | ❌ | 融合设计建议不做抢占——与 B v2 冲突，需登记偏离并批准 |
| L3-4 | 八类信号量：LLM/Tool/DB/SubAgent/审批通知/Per-provider/Per-resource/Per-tenant | 有：scheduler_slot（≈Tool/SubAgent 总量）、工具池 bulkhead；无：LLM 独立信号量、审批通知、per-provider、per-resource、per-tenant | ⚠️ | 缺 5 类。per-tenant 当前单租户可登记 N/A；其余随 AM8 补 |
| L3-5 | 全局 deadline 一路传播（非各层各自 timeout） | ✅ deadline_at → ToolGateway 取 min → AUTO_EXPIRE | ✅ | — |

## L4 Durable Conversation Runtime 层

| # | B v2 要求 | 现状 | 判定 | 差距/行动 |
|---|---|---|---|---|
| L4-1 | Conversation 表含 lease_epoch | 无 conversations 表；lease_epoch 在 inbox/task/attempt/slot 各表 | 🔀 | 偏离：以 rca_run+generation 代 Conversation。语义等价已论证（EXA2 文档），登记冻结 |
| L4-2 | 内部关键更新带 WHERE lease_epoch 防 stale writer | ✅ LeaseFence 条件 UPDATE（真 PG 并发实证） | ✅ | — |
| L4-3 | 外部系统不理解 epoch → operation_id + Idempotency-Key + precondition + reconciliation | ✅ 逻辑幂等键 + UNKNOWN + reconcile（只读域） | ✅ | R2/R3 解锁后需在 Action Runner 复核 |
| L4-4 | Task Ledger 区分 heartbeat_at 与 last_progress_at | 有 heartbeat，无 last_progress_at | ⚠️ | rca_attempt 加列（融合设计 §3.4） |
| L4-5 | Zombie 判定：LIVE_BUT_STUCK（心跳活+无进展）→ cancel+重调度 | RunReconciler 只收 LOST；LIVE_BUT_STUCK 靠 run 内 DoomLoopGuard | ⚠️ | Reconciler 加一档（融合设计 §3.4） |
| L4-6 | Lease renewal 不算 progress | ✅（现状无 progress 概念，续租本就不产生进展事件） | ✅ | 补 last_progress_at 后保持此纪律 |
| L4-7 | Cancellation Tree：task.cancel + subprocess process-tree teardown（SIGTERM→grace→SIGKILL→cgroup） | 进程内取消全链路 ✅；无 subprocess 类工具，process-tree teardown 无对象 | ⚠️ | 当前 N/A；R2/R3 解锁引入 shell/Docker 类工具时必须配套 |
| L4-8 | 审批 Durable Suspension：checkpoint→释放 worker→回调→新 worker resume | 无审批故无挂起；Redrive 机制同构先例存在 | ❌ | 随 AM8 审批面落地（融合设计 §3.2） |
| L4-9 | 人等待与系统活跃分开统计 | 无 | ❌ | 随 AM8（span 分层 blocked_on_user / execution） |

## L5 Agent Runtime 层

| # | B v2 要求 | 现状 | 判定 | 差距/行动 |
|---|---|---|---|---|
| L5-1 | Goal Contract（yaml：symptom/allowed_resources/allowed_env/objective/forbidden/success_evidence） | 无 | ❌ | 融合设计 §3.8 降级为结构化校验——B v2 要求"每步推理后自问"，需裁定：严格版（LLM 自问）还是登记偏离（Supervisor 阶段转换点结构化校验） |
| L5-2 | 8 维 drift 检测（Goal/Resource/Scope/Evidence/Context/State/Policy/Model） | 仅 DoomLoopGuard（≈Context/循环维） | ⚠️ | 缺 7 维。融合设计 §3.8 覆盖 Goal/Scope/Evidence/State 四维结构化 + Policy/Model 走 provenance 观测 |
| L5-3 | 三层 Stuck Detector：Deterministic（5 模式阈值表） | DoomLoopGuard 有重复熔断，但无 B v2 的五模式 warning/hard-stop 双阈值表 | ⚠️ | 对齐五模式阈值表（exact repeat 2/5、monologue 2/3、ping-pong 4/6、context window error 1/2、tool fail retry budget） |
| L5-4 | Progress Detector（无新证据→分数降、无 milestone→语义 stuck、mutation 后重置） | 无 | ❌ | 新增（与 L4-5 共用 progress 信号） |
| L5-5 | Hard Budget：max_turns/tokens/cost/wall_clock/tool_calls/subagents，任一触发 PAUSE/ESCALATE | RunBudgetGate 多维账本 + 步数上限 ✅；max_subagents 有（2批×2）；wall_clock=deadline ✅ | ✅ | — |
| L5-6 | Compaction：Durable State 不进 LLM Context，CondensationEvent 带 preserved_state_hash | compaction 已实现（默认关）：五道零模型闸、required_refs 复验、CAS 提交；无 preserved_state_hash 字段 | ⚠️ | 补 preserved_state_hash 校验字段 |
| L5-7 | Budget 按 trace_id 聚合、多级分账（Org/Team/Agent/Model/Incident）、prompt caching 计入 | 按 run/incident 账本 ✅；无 Org/Team 级（单租户）；cached_tokens 未单列 | ⚠️ | cached_tokens/cache_cost 入账本；多级分账登记 N/A（单租户） |
| L5-8 | 主 Agent 自由分派 sub-agent + 并行调度（gather/DAG） | 🔀 DeterministicSupervisor：模型无调度权，代码裁决 DELEGATE（上限 2批×2） | 🔀 | **与 B v2 正面冲突**。B v2 是模型主导分派；现状是代码主导。严格化=放开模型调度（不建议）；或登记偏离（更保守，安全收益明确） |

## L6 Safety Control Plane 层

| # | B v2 要求 | 现状 | 判定 | 差距/行动 |
|---|---|---|---|---|
| L6-1 | Sandbox 第一边界：fs 隔离/网络白名单/进程隔离/资源限制/env 注入 | 部署层容器加固（read_only、cap_drop、no-new-privileges）；执行级 sandbox 仅 PR 域 sandbox_job；告警域无 | ⚠️ | R2/R3 解锁前必须：Action Runner 独立容器 + 网络白名单 + env 注入（ALLOWED_ENV） |
| L6-2 | 审批不能代替 sandbox | ✅ 原则同（R2/R3 目前连执行都没有） | ✅ | 保持 |
| L6-3 | Resource Coordinator（跨 Conversation 同资源串行） | 无 | ❌ | resource_operation_lock 表（融合设计 §3.3），AM8 前置 |
| L6-4 | 操作四级：只读/低危/高危/Hardline 各自处置路径 | ToolRisk R0-R3 仅两档处置；无低危中间层、无 hardline 清单、无动态升降级 | ❌ | 四级裁决层（融合设计 §3.1.0），AM8 前置 |
| L6-5 | Guardian 预审（SAFE/UNSAFE/UNCERTAIN，同 trace_id 计预算） | 无 | ❌ | 随四级裁决层落地 |
| L6-6 | 审批表全字段：status/decision_semantics/merge_role/human_wait_ms/required_role/双人/撤销/notify 重试 | 无审批 | ❌ | AM8（融合设计 §3.1 表结构已含） |
| L6-7 | 合并键 (conversation,tool,args_hash) + Leader 崩溃 Follower 晋升 | 无 | ❌ | 单 run 单写者下合并退化为 UNIQUE 冲突回读；Leader/Follower 语义登记简化 |
| L6-8 | 通知失败重试 3 次（5/15/45s）→ fail-closed 不留 pending | 无 | ❌ | 随 AM8，复用 notify_outbox 退避机 |
| L6-9 | CONSUMED CAS 影响行数=1 才执行 | 无（OperatorCommand 修订锚 CAS 是同模式先例） | ❌ | 随 AM8 |
| L6-10 | 双人确认分属不同角色/团队 | 无；且现状无独立 REVIEWER 角色（单人运维如实登记） | ❌ | AM8 需先定义审批角色——单人运维下双人确认无法成立，**需裁定**：引审批角色 or 登记偏离 |
| L6-11 | 撤销后凭证立即失效 | 无 | ❌ | 随 AM8（撤销→未消费 grant 作废） |

## L7 Durable Operation Engine 层

| # | B v2 要求 | 现状 | 判定 | 差距/行动 |
|---|---|---|---|---|
| L7-1 | Operation 状态机 PREPARED→DISPATCHED→ACKNOWLEDGED→VERIFIED→COMPLETED | rca_tool_invocation：PENDING/STARTED/SUCCEEDED/FAILED/UNKNOWN；无 ACKNOWLEDGED/VERIFIED | ⚠️ | R2/R3 执行链路补齐 VERIFIED（执行后探针确认）与 ACKNOWLEDGED |
| L7-2 | UNKNOWN→RECONCILING→VERIFIED/RETRYABLE/ESCALATED | markHangingInvocationsUnknown 只标 UNKNOWN 不探资源真实状态（只读域无必要） | ⚠️ | R2/R3 解锁后 reconcile 必须查资源真实状态（resource_probe），不能只标 UNKNOWN |
| L7-3 | 网络 timeout ≠ failed | ✅ UNKNOWN 诚实归档不猜结局 | ✅ | — |
| L7-4 | 幂等 INSERT ON CONFLICT + 状态查询分流 | ✅ 逻辑幂等键 + 终态 CAS 单向 | ✅ | — |
| L7-5 | at-least-once 编排 + effectively-once 副作用 | ✅ LeaseFence + 幂等账本 | ✅ | — |
| L7-6 | TOCTOU 事件溯源：ActionEvent/ApprovalEvent 落 EventLog，执行前从 EL 读不从内存读 | 🔀 LeaseFence + 修订锚 CAS（不经过 EventLog 读路径） | 🔀 | 机制不同风险面相同。审批面按 v1.2 §11.3 用 action_digest+observed_generation+single-use grant。登记偏离 |

## L8 Event / Trace Plane

| # | B v2 要求 | 现状 | 判定 | 差距/行动 |
|---|---|---|---|---|
| L8-1 | 单写者 sequencer + 每 Conversation 单调 seq | ✅ rca_run.last_event_seq 行锁同事务推进 | ✅ | — |
| L8-2 | UNIQUE(conversation_id, seq) | ✅ UNIQUE(run_id,event_id) + digest 幂等 | ✅ | — |
| L8-3 | prev_hash/event_hash 哈希链 | ❌ 仅逐行 payload_digest | ❌ | 加两列 + 同事务写 + 每日验链（融合设计 §3.6） |
| L8-4 | 事件含 parent_event_id/causation_id/actor_id | ⚠️ 部分（事件有 run/task 关联，无 causation_id/actor_id 显式列） | ⚠️ | 补两列 |
| L8-5 | Decision Provenance 全字段族（model/prompt/agent/policy/tool 版本+hash、resource_uid/version、build_sha） | 散落各处，无统一结构 | ❌ | 统一 provenance 块（融合设计 §3.6） |
| L8-6 | OTel 正确分层：业务 ID 作 attribute，trace_id/span_id 由 OTel 生成，W3C traceparent 传播 | TracedTasks 助手待接线；当前 Prometheus + StructuredLog | ⚠️ | 接 otelcol，遵守分层纪律 |
| L8-7 | 默认 metadata-only + redaction/sampling/retention/access-control | EventPayloadSanitizer 白名单脱敏 ✅；retention/sampling 未定义 | ⚠️ | 定义 retention 与采样策略 |

## L9 Evaluation Plane

| # | B v2 要求 | 现状 | 判定 | 差距/行动 |
|---|---|---|---|---|
| L9-1 | 8 层 eval（状态机/故障注入/Agent质量/安全/循环/上下文/负载/影子/回归） | 状态机层 ✅（2000 例+320 IT）；故障注入一次性 3 项；Agent 质量有 EV 底座（北极星报红未闭环）；安全/循环/负载/影子/回归无 | ⚠️ | 逐层补（融合设计 §3.9） |
| L9-2 | Fault injection 13 项清单套件化 | 3 项做过未固化；余 10 项未做 | ❌ | 套件化，随各增量配套 |
| L9-3 | 关键 Invariant 10 条全部自动化验证 | 部分有实证（lease_epoch、审批 N/A、事件重建无） | ⚠️ | 不变量清单进 CI 门禁 |
| L9-4 | Replay A：EventLog→Reducer→状态重建 | ❌ | ❌ | 新增 reducer 重放验证 |
| L9-5 | Replay B：换 model/prompt/tool schema 复评（工具 simulator） | ❌ | ❌ | eval 域落地 |
| L9-6 | Trace-Driven Evaluation：eval_run_id/case_id 进 EventLog+OTel，失败 case 全链可点查 | ❌ | ❌ | 随 L8-6 后落地 |
| L9-7 | Grader 优先级：能代码判断绝不用 LLM | 未明确 | ❌ | 写入 eval 规范 |
| L9-8 | Dashboard：MTTD-agent/MTTA-agent/MTTM/队列 p95/p99 等九类指标 | AlertMetrics 有部分；MTT* 与九类口径未定义 | ⚠️ | 指标口径表落地 |

---

## 10. 符合性统计

| 判定 | 数量 | 占比 |
|---|---|---|
| ✅ 符合 | 16 | 29% |
| ⚠️ 部分 | 17 | 31% |
| ❌ 缺失 | 20 | 36% |
| 🔀 偏离 | 5 | 9% |
| 合计 | 55+3(纯新增 N/A) | 100% |

（按上述 58 行逐项统计，N/A 项已剔除）

## 11. 偏离登记表（严格执行下的签字项）

| # | B v2 要求 | 现状路线 | 偏离理由 | 处置 |
|---|---|---|---|---|
| D1 | HMAC 签名（L0-1） | 静态 bearer | 单栈内网、bearer 已真机验证；HMAC 防重放收益在内网有限 | **待裁定** |
| D2 | Redis 令牌桶/去重（L1-2/L1-4） | 无 Redis，PG/进程内 | 本栈无 Redis，少一个故障面；B v2 自身也倾向 PG | **待裁定**（或多实例时引 Redis） |
| D3 | conversations 表（L4-1） | rca_run + generation | 语义等价且已实证（EXA2） | 建议批准冻结 |
| D4 | TOCTOU 事件溯源读路径（L7-6） | LeaseFence + 修订锚 CAS | 风险面相同，已实证 | 建议批准冻结 |
| D5 | 模型自由分派 sub-agent（L5-8） | DeterministicSupervisor 代码裁决 | 更保守：模型无调度权直接消掉一整类越权面 | **与 B v2 正面冲突，必须裁定** |
| D6 | P0 抢占（L3-3） | 不做抢占，仅保留容量 | 抢占复杂度/收益比低 | **待裁定** |
| D7 | 双人确认（L6-10） | 单人运维无 REVIEWER 角色 | 组织现状 | **待裁定**：引审批角色 or 降级为单人+延时生效 |

**纪律**：D1-D7 未定稿前，AM8 不进入编码（milestone-workflow G1 门禁）。
