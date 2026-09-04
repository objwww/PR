# 告警 Agent：WebSocket、人工控制与对账方案裁定 v1

> 日期：2026-09-04  
> 对象：外部反馈中的 WebSocket、ER/API 契约和 Reconciliation 方案  
> 基线：`架构设计-告警Agent-v1.2.md`、当前 `control-app` 代码、AM3 技术方案  
> 裁定方法：`PASS` = 可原义采用；`PASS_WITH_CHANGES` = 目标正确但实现必须改写；`REJECT` = 与当前边界或正确性冲突；`DEFER` = 未来满足前置条件再做。

---

## 1. 总结论

WebSocket 方案的产品目标是合理的：用户需要实时知道 RCA 做到哪一步，也需要取消、补充材料和纠错入口。但外部方案把 Redis、MQ、Pod 路由、内存 Context、WebSocket 消息归档和向量库串成了第二套控制平面，这与本项目已经冻结的“小体量 Docker Compose + PostgreSQL 唯一协调设施”直接冲突。

最终裁定是：

1. **PASS**：实时进度、断线游标、心跳、大证据摘要、关闭连接不取消 Run；
2. **修改后 PASS**：WebSocket 鉴权、跨实例唤醒、取消、Hint、Feedback、UNKNOWN 对账；
3. **REJECT**：Redis/MQ/Pod 广播、推送 Thought、直接取消内存 Context、直接蒸馏向量库、迟到结果补入旧报告、本地去重伪装 exactly-once；
4. **DEFER**：R2/R3 写动作自动对账，直到 Action Runner 同时具备幂等键、operation ID、状态查询 API 和执行回执。

一句话版本：

> PostgreSQL 是生命线，WebSocket 是显示器；Command 是控制按钮，LISTEN/NOTIFY 是门铃，Ledger 是账本。显示器、按钮或门铃坏了，都不能改变账本里的事实。

---

## 2. 当前代码事实

### 2.1 当前没有 WebSocket 实现

- `control-app/pom.xml:37` 只有 `spring-boot-starter-web`；没有 `spring-boot-starter-websocket`、STOMP 或 WebFlux；
- 当前只有 Alertmanager Webhook Controller，没有用户会话、WebSocket Handler、SSE Controller 或前端命令 API；
- 因此这部分是后续目标设计，不能在文档里写成“当前已经具备”。

### 2.2 当前 202 返回的是 Intake，不是 Session/Run

`AlertWebhookController.java:51-73` 的真实流程是：

```text
Alertmanager 请求
→ AlertIntakeService.store
→ 持久化成功
→ HTTP 202 + inboxId
```

此时异步 `AlertInboxProcessor` 尚未完成 Incident 聚合，更没有必然生成 `rca_run`。所以外部 API 示例中“入口立即返回 session_id，然后马上订阅 session”并不符合当前时序。

修正版使用：

```text
intake_id：入口事务完成后立即存在
incident_id：Projector 聚合后存在
run_id：满足 RCA 触发条件并铸造 Run 后存在
```

UI 可以把 Run 显示为“分析会话”，但数据库和 API 不能再创建一套 `t_sessions`。

### 2.3 当前已有完整的运行实体

`V7__am1_alert_domain.sql` 已经存在：

```text
alert_inbox
alert_event
incident
rca_run
rca_task
rca_attempt
rca_report
external_invocation_ledger
scheduler_slot
```

附件的 `t_sessions/t_audit_trails/t_outbox_events` 会与这些表重叠，并把已经分开的运行状态、调查结果、工具调用、审计和发布再次压回几个大 JSONB 表。

### 2.4 当前 UNKNOWN 的语义

`ExternalInvocationState` 是：

```text
STARTED → SUCCEEDED | FAILED | UNKNOWN
```

`HolmesInvestigationExecutor` 对明确收到的 HTTP/网络超时按错误分类形成 `FAILED`，由任务状态机决定是否重试。`RcaWorker.recoverExpired()` 只把 Worker 崩溃后超过 grace period 的悬挂 `STARTED` 标成 `UNKNOWN`。

因此 `UNKNOWN` 不是普通超时的别名，而是“系统无法证明外部调用最终发生了什么”的诚实终态。

---

## 3. 逐项裁定

| 原建议 | 裁定 | 通过/不通过的核心理由 | 最终落法 |
|---|---|---|---|
| WebSocket 推送实时进度 | PASS | 用户体验需要，且不改变业务正确性 | PG 事件流的实时投影 |
| 连接关闭不取消 Agent | PASS | 页面刷新不等于业务取消 | 只移除连接，Run 继续 |
| 消息带 seq，断线后回放 | PASS_WITH_CHANGES | 必须来自唯一权威事件账本，不能依赖 Redis 内存归档或双写流表 | `rca_event(run_id,seq)` 的实时投影 |
| 心跳清理死连接 | PASS | 长连接需要发现中间代理/客户端断开 | 协议 Ping/Pong；参数压测校准 |
| 大证据只推摘要 | PASS | 防止前端与连接缓冲被大结果淹没 | 8KiB 摘要；详情经授权 HTTP/CAS |
| `thought` 消息 | REJECT | 违反不保存隐藏 Thought；还会泄露 Prompt/敏感材料 | 改成状态、ToolCall 和 Evidence 摘要 |
| 浏览器握手带 Authorization Header | PASS_WITH_CHANGES | 原生浏览器 WebSocket 无任意 Header 参数 | HTTP Principal 或一次性 stream ticket |
| `SessionID → 单 WebSocket` Map | PASS_WITH_CHANGES | 同一 Run 可能多标签页、多查看者；Session 与 Run 重复 | `run_id → Set<connection_id>` |
| Redis Pub/Sub 跨 Pod | REJECT | 项目不使用 Redis/K8s，且会产生第二事实源 | PG `LISTEN/NOTIFY` 只唤醒，表中回放 |
| Redis 不可用后广播 `/internal/push` | REJECT | 放大流量、扩大内部攻击面，且仍不持久 | NOTIFY 失败后周期查询追赶 |
| WebSocket cancel 直接 `Context.cancel()` | REJECT | 只能影响本进程；重启或换 Worker 后失效 | REST 幂等命令先落 PG，Context 只加速 |
| adjust 提升成 System Prompt | REJECT | 提权、Prompt Injection、破坏 config digest 可复现性 | 带来源的 `UNTRUSTED_OPERATOR_HINT` |
| Feedback 直接写向量库并蒸馏 | REJECT | 可造成知识投毒，且无法撤销错误样本 | 提交→复核→接受→新 Dataset 版本 |
| Redis 保存最近 1 万条/1 小时 | REJECT | 与 PG 唯一控制面冲突，重启/故障后语义复杂 | PG 事件保留策略 + cursor |
| 每秒超过 10 条就合并 | PASS_WITH_CHANGES | 可以批量传输，但不能合并掉语义与 seq | Envelope 批次，子事件各自保留 seq |
| 所有超时立即 UNKNOWN | REJECT | 当前代码将明确超时分类为 FAILED；UNKNOWN 表示不可证明 | 保持现有四态和错误分类 |
| UNKNOWN 后直接重发写动作 | REJECT | 前一次可能已成功，会造成重复副作用 | 有状态 API 才对账，否则转人工 |
| Reconciliation 把迟到结果补进旧证据 | REJECT | 会改变冻结快照，使报告不可复现 | 追加 Observation；需要时创建 Rerun |
| Outbox 本地 TTL 去重保证不重复 | REJECT | 无法封闭“远端已收、本地未记 SENT”的崩溃窗口 | 下游 event_id 幂等；否则承认可能重复 |
| 对账所有能力由一个协程负责 | REJECT | 六类问题的权限、终态和证明能力不同 | Lease/Usage/Read/Write/Outbox/Incident Source 分治 |
| 写动作自动对账 | DEFER | 当前 Agent 只有 R0/R1，且未具备 receipt/status API | Action Runner 落地时再实现 |

---

## 4. 为什么这些内容可以 PASS

### 4.1 实时进度推送为什么 PASS

RCA 是长时任务。入口 202 只说明任务已经持久化，不代表用户应该盲等几十秒。实时展示 `RUNNING/TOOL_CALL/EVIDENCE/REPORT_READY` 能让用户区分：

- 请求是否真的被受理；
- 正在排队还是已经执行；
- 下游工具慢还是模型慢；
- 已经拿到证据还是只在等待；
- 最终报告是否完成验证并封存。

这个能力只读取控制面已提交事件，不参加状态决策，因此即使 WebSocket 完全不可用，用户仍可用 REST 查询，RCA 仍会继续。它提高体验但不扩大正确性边界，所以可以直接通过。

### 4.2 断线不取消为什么 PASS

浏览器断开可能来自刷新、Wi-Fi 抖动、代理重启、电脑休眠或用户关闭标签页。这些行为都不能证明用户希望终止 RCA。若把 WebSocket close 等同于 cancel，会导致网络故障直接改变业务状态。

因此：

```text
关闭连接 = 停止实时观看
取消命令 = 经认证、授权、幂等、持久化的业务动作
```

两者必须分离。

### 4.3 seq 和回放为什么修改后 PASS

序列号的目标正确：WebSocket 是至少一次传输，网络重连可能重复或漏看消息，客户端必须能按游标补齐并去重。

但序列号只有在持久化后才有意义。若 seq 只在内存递增：

- 进程重启后可能从 1 重新开始；
- 多实例可能生成相同 seq；
- Redis 与 PG 提交顺序可能相反；
- 前端无法证明某条消息对应已提交状态。

二次深审后不再新增 `rca_stream_head/rca_stream_event`。全系统只写一份 `rca_event(run_id, seq, run_revision, observed_generation, ...)`；`rca_run.last_event_seq` 通过 `UPDATE ... RETURNING` 原子分配连续事件段，Agent 事件是视图，实时网关按 `seq > last_seq` 投影。状态事务需要写的审计事件仍原子提交，但 `NOTIFY` 由提交后 notifier 去抖发送，失败不回滚状态；网关靠周期查询补齐。这样既保留可恢复游标，又不把“显示器”焊成第二份业务日志。

### 4.4 心跳与背压为什么 PASS

WebSocket 是持久连接，必须防止死连接与慢客户端占满内存。OWASP 明确建议限制连接数、消息大小、消息速率、空闲时间并实现背压。Spring 直接使用 `WebSocketSession` 时还要求同步发送，官方提供 `ConcurrentWebSocketSessionDecorator` 的发送时间和缓冲限制。

所以心跳、摘要化和有界缓冲是必要能力。初值不是性能承诺，而是可配置起点：

```text
事件 16KiB
Evidence 摘要 8KiB
连接缓冲 256KiB
发送超时 5 秒
20 条或 50ms 一个传输批次
```

慢客户端溢出后关闭连接，通过 PG 游标恢复，比无限缓存或静默丢消息更诚实。

### 4.5 External Invocation Ledger 为什么 PASS

当前实现已经执行“先记 STARTED、再触网、最后写终态”：

```text
STARTED 写失败 = 零触网
SUCCEEDED/FAILED = 已知结果
UNKNOWN = 崩溃后无法证明结果
```

这能封闭最危险的“系统不知道自己是否调用过外部系统”问题。保留 Ledger 是正确的，但它只能记录事实，不能凭空让一个没有 status API 的外部请求变得可查询。

### 4.6 Outbox 为什么 PASS

报告落库与发布命令必须在一个本地事务里完成，否则会出现“报告已保存但通知永远没发”或“通知发出但报告事务回滚”。Transactional Outbox 正是解决这个双写问题。

它的真实保证是：

- 本地发布命令不丢；
- relay 可以重试；
- 通常是 at-least-once；
- relay 崩溃窗口可能产生重复。

因此 AM3 当前“稳定 operation_id + 重复可检测”的表述正确。只有下游按 event_id 幂等时，业务效果才可以接近 effectively-once。

---

## 5. 为什么原实现不能 PASS

### 5.1 Redis/PubSub/HTTP 广播为什么不通过

本项目已经明确不用 Redis、MQ 和 Kubernetes。仅为了 WebSocket 路由引入 Redis，会带来：

- 新的部署、认证、备份和监控面；
- PG 状态已提交但 Redis 消息失败的双写问题；
- Redis Pub/Sub 本身不保存消息，仍需第二套归档；
- 所有实例广播 `/internal/push` 会放大流量和内部攻击面；
- 故障排查需要同时判断 PG、Redis、路由表和本地连接表。

PG 已经是所有实例共享的事实源，`LISTEN/NOTIFY + rca_event 权威账本` 足以支撑当前规模。通知丢了可轮询补齐，没必要新增中间件；也不需要再为 UI 建第二张持久事件表。

### 5.2 Thought 为什么不通过

隐藏推理文本不是可靠事实，也可能包含：

- System Prompt；
- 用户或告警中的敏感信息；
- 尚未验证的猜测；
- 工具参数或内部策略；
- 大量无助于复核的自然语言。

系统需要解释的是“调用了什么工具、观察到什么证据、形成什么 Claim、依据什么规则裁决”，而不是展示模型的隐藏思维。用结构化事件替代 Thought，既可审计又不会破坏安全边界。

### 5.3 直接 Context.cancel 为什么不通过

内存取消存在三个问题：

1. WebSocket 所在实例不一定是 Worker 所在实例；
2. Worker 可能已经重启或租约已经换主；
3. 进程在收到取消后、状态落库前可能崩溃。

因此取消必须先形成持久命令。命令提交后，调度器以 Run revision、task lease 和 epoch 判断能否应用。本地 Context 只负责让正在等待的 HTTP 调用更快结束。

### 5.4 System Prompt Hint 为什么不通过

用户提供“检查变更单 #1234”是有价值的，但它仍然是不可信输入。如果直接放到 System Prompt 顶部，用户就能用 Hint 改写工具权限、忽略安全规则或绕过 ConfigBundle。

正确做法是把它建模为具有 actor、时间、digest 和 scope 的操作员材料。Agent 可以引用、验证或拒绝它，但控制面权限和系统指令不受它影响。

### 5.5 反馈直接进向量库为什么不通过

反馈可能错误、冲突或恶意。一条“实际根因是 Redis”的错误纠正如果立即进入向量库，后续所有相似事故都可能被污染。删除向量记录也无法证明哪些历史报告曾受它影响。

因此反馈要经过身份、证据、复核和 Dataset 版本。线上知识库只读取已发布版本，不能读取待审核反馈。

### 5.6 UNKNOWN 自动重试为什么不通过

UNKNOWN 的关键含义是“可能成功，也可能失败”。对只读查询重新执行通常安全，但必须生成新 Attempt；对写动作重新执行可能重复删除、注入、重启或修改配置。

自动对账写动作必须满足四个条件：

```text
稳定幂等键
稳定 operation_id
外部状态查询 API
可验证执行回执
```

缺任何一个都转人工，不能把“不知道”当作“没执行”。

### 5.7 迟到结果补入旧报告为什么不通过

Run 报告必须绑定不可变 EvidenceSnapshot。若报告完成后又把迟到结果补进去：

- 相同 report_id 的证据内容随时间改变；
- Shadow 和 Replay 无法重现当时输入；
- 审计人员看到的证据不是模型当时看到的证据；
- 签名和 digest 失去意义。

后来查询到的结果只能形成 `ReconciliationObservation`。若需要重新分析，创建显式 Rerun 和新 Snapshot。

### 5.8 本地去重为什么不能变成 exactly-once

考虑这个崩溃窗口：

```text
notify-app 把 event-123 发给群机器人
群机器人实际收到
notify-app 在更新 SENT 前崩溃
恢复后看到 event-123 仍是 PENDING
再次发送
```

本地去重表也不知道第一次是否到达，因此无法阻止重复。只有接收方记录 event_id 才能消除该窗口；接收方不支持时，系统只能承诺 at-least-once 和重复可检测。

---

## 6. 附件 ER/DDL 的具体问题

| 问题 | 为什么是问题 | 修正 |
|---|---|---|
| 声称“4 个核心库”，ER 实际列出更多表 | 概念与物理表混淆 | 按现有领域表增量设计 |
| `t_traces`、`t_rule_hits` 被关联但未定义 | ER 不完整，DDL 无法落地 | OTel Trace 不在业务库重复建表；Rule Hit 另定契约 |
| `t_feedback ||--o|| t_sessions` | 基数方向错误；一个 Run 可多次反馈 | report/run 一对多 feedback |
| `thought TEXT` | 违反 v1.2 安全边界 | ToolCall/Evidence/Claim/Event |
| `context_snapshot JSONB` | 大对象膨胀且重复状态 | CAS ref + digest + 类型化表 |
| `ON DELETE CASCADE` 删除审计/Outbox | 主记录删除会抹掉证据链 | `RESTRICT/NO ACTION` + 生命周期任务 |
| `TIMESTAMP` | 多主机/回放时区含义不稳定 | `timestamptz` |
| `INDEX idx...` 写在 `CREATE TABLE` 内 | 不是 PostgreSQL 的该类 DDL 语法 | 单独 `CREATE INDEX` |
| `t_config_rules.updated_at` | 原地 UPDATE 破坏不可变配置历史 | ConfigBundle + Activation Event + CAS pointer |
| `prompt_versions` 单独决定运行版本 | Prompt 只是 Bundle 一部分 | Run 绑定统一 `config_digest` |
| `trail_s3_url` | 当前架构没有冻结 S3 | CAS artifact ref |
| 自由文本 `root_cause/confidence` | 不适合确定性评分与验证 | 类型化 component/fault_type/reason_code + Evidence refs |

---

## 7. 对账职责最终划分

| Reconciler | 输入 | 输出 | 是否能改原记录 |
|---|---|---|---|
| Lease Reaper | 过期 task/slot lease | RETRY_WAIT、新 epoch、旧提交失效 | 只能按状态机 CAS |
| Model Usage Reconciler | control ledger + LiteLLM spend log | MATCHED/PARTIAL/UNMATCHED | 追加对账结果，不改调用回答 |
| Read Retry Coordinator | FAILED/UNKNOWN 只读调用 | 新 Attempt、新 Evidence | 不补写旧 Attempt |
| Action Reconciler | UNKNOWN 写动作 + operation receipt | SUCCEEDED/FAILED/NEEDS_REVIEW Observation | 不把 UNKNOWN 改写成“当时已知成功” |
| Delivery Reconciler | notify_outbox + delivery attempts | SENT/RETRY/DEAD、重复标记 | 按 epoch/状态机更新 Outbox；attempt 只增 |
| Incident Source Reconciler | 本地 FIRING + Prometheus 当前 alerts | SOURCE_ABSENT_AFTER_GRACE/DATA_UNAVAILABLE Observation | 不删除原 webhook；只由 Incident 状态机追加收敛事件 |

配置 digest 校验和预算汇总不塞进上述协程：它们分别是配置完整性检查和资源账本聚合，失败处理、权限和运行频率都不同。六类 Reconciler 均必须声明最大尝试、墙钟、subject age、积压年龄 SLO 和 Operator Case 升级策略，禁止无限对账。

---

## 8. 落地顺序

### 第一阶段：REST 可恢复基线

1. 统一 `intake_id→incident_id→run_id` 查询契约；
2. 增加 Run 状态、事件分页、报告查询接口；
3. 增加持久化 Cancel/Hint/Feedback；
4. 没有用户身份边界前，不开放浏览器控制 API。

### 第二阶段：实时投影

1. 给统一 `rca_event` 增加 Run 级 seq、可见性与回放索引；兼容期的 `rca_agent_event` 只做视图；
2. 状态变更与事件同事务提交；
3. 首版可用 SSE 验证事件语义；
4. 确实需要双向长连接时再接 WebSocket；业务命令仍复用 Command Service。

### 第三阶段：安全与压力验收

1. Origin、未认证、越租户、过期 ticket、ticket 重放；
2. 超大消息、消息洪泛、慢客户端、并发发送；
3. NOTIFY 断开、网关重启、PG 短时不可用；
4. 断线重连、重复投递、游标过期和报告已终态；
5. Cancel 与 Worker 换租约并发；
6. Feedback 数据污染与复核权限。

### 第四阶段：对账扩展

1. 先完成 AM3 的 LiteLLM 一对多用量对账；
2. 通知 Outbox 做发送 attempt 和 lag 监控；
3. AM4 增加 Incident Source Reconciler，覆盖 resolved webhook 丢失窗口；
4. R2/R3 Action Runner 进入范围后，才增加状态 Probe 和 Action Reconciler。

---

## 9. 外部证据

- 浏览器 `WebSocket()` 仅接受 URL 与 subprotocol 参数：<https://developer.mozilla.org/en-US/docs/Web/API/WebSocket/WebSocket>
- Spring WebSocket 握手、Origin 和并发发送约束：<https://docs.spring.io/spring-framework/reference/web/websocket/server.html>
- Spring Security WebSocket 的 HTTP Principal 与消息授权：<https://docs.spring.io/spring-security/reference/servlet/integrations/websocket.html>
- OWASP WebSocket 鉴权、Origin、大小、限流、背压和审计：<https://cheatsheetseries.owasp.org/cheatsheets/WebSocket_Security_Cheat_Sheet.html>
- Spring `ConcurrentWebSocketSessionDecorator` 的发送时间与缓冲限制：<https://docs.spring.io/spring-framework/docs/current/javadoc-api/org/springframework/web/socket/handler/ConcurrentWebSocketSessionDecorator.html>
- PostgreSQL `LISTEN` 的启动竞态与正确初始化顺序：<https://www.postgresql.org/docs/current/sql-listen.html>
- PostgreSQL `NOTIFY` 的提交后投递、顺序、payload 限制和队列监控：<https://www.postgresql.org/docs/current/sql-notify.html>
- PostgreSQL 索引与 partial unique index：<https://www.postgresql.org/docs/current/sql-createindex.html>
- AWS Transactional Outbox 的重复投递与接收端幂等要求：<https://docs.aws.amazon.com/prescriptive-guidance/latest/cloud-design-patterns/transactional-outbox.html>
- Prometheus Alerts API 的 firing/resolved 重发期望：<https://prometheus.io/docs/alerting/latest/alerts_api/>
- Prometheus 当前 Alerts HTTP API：<https://prometheus.io/docs/prometheus/latest/querying/api/#alerts>
- Spring MVC `SseEmitter`：<https://docs.spring.io/spring-framework/reference/web/webmvc/mvc-ann-async.html>
- HTTP `If-Match` 防止 lost update：<https://www.rfc-editor.org/rfc/rfc9110.html>

---

## 10. 最终裁定

本轮不是否定实时交互和对账，而是把二者放回正确的分层：

- WebSocket/SSE：体验层；
- REST Command：可重试的人机控制入口；
- PostgreSQL Event：实时回放事实；
- Run/Task/Attempt：工作流事实；
- External Invocation Ledger：外部调用事实；
- Reconciliation Observation：后来观测到的新事实；
- Outbox：可靠发布命令；
- CAS：大证据与原始 artifact。

按此裁定，系统即使实时连接全部断开，也不会丢任务、错取消、漏报告或篡改证据；恢复连接后只需要按 Run 游标重新读取 PostgreSQL。
