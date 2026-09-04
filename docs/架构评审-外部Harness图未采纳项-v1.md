# 外部 Harness 流程图未采纳项与 PASS 理由 v1

> 日期：2026-09-04  
> 状态：架构评审记录  
> 关联架构：`docs/架构设计-告警Agent-v1.2.md`  
> 说明：本文原有章节中的 **PASS-A/B/C** 表示“该建议不按原方案进入本项目架构”，不是说这个技术永远错误。第二/三轮新增反馈统一使用 **ACCEPT / ACCEPT_WITH_CHANGES / ACCEPT_WITH_GATES** 表示采纳，避免把“跳过”和“通过”混成一个词。每一项都说明当前为什么不采用、原样采用会造成什么后果、项目采用什么替代方案，以及未来什么条件下可以重新评估。

---

## 1. 先给结论

外部流程图的问题不是“技术名词不够多”，恰恰相反，是把一套面向 Kubernetes、多副本、大流量平台的通用想象，直接套在了当前单实例、两台小服务器、Docker Compose、PostgreSQL 控制面的项目上。

它提出的不少目标是对的：

- 入口要削峰；
- 任务要持久化；
- 工具要超时、重试、取消；
- 输出要验证；
- 多 Agent 要有 DAG；
- 要有 DLQ、Outbox、审计、预算和灾备。

但“目标正确”不等于“实现手段正确”。原图中存在五类根本问题：

1. **重复建设**：已有 PostgreSQL 任务/租约/Outbox，又加 MQ、Redis、Redlock，产生三份状态真相。
2. **协议说谎**：失败时固定返回 HTTP 200，让上游以为已经成功受理。
3. **安全倒置**：把调用方 Header 当作 Dry-Run 权限，把写工具也拿去 Shadow。
4. **竞态放大**：工具超时后继续后台执行，结果晚到后写回已经变化的 Agent 上下文。
5. **AI 错误治理错误**：用自评 confidence 投票、强行相信旧观点、保存 Thought、让 DLQ Agent 再编一个结论。

本项目选择的总原则是：

```text
少一个基础设施组件
不等于少一种可靠性能力；

可靠性来自持久化状态、唯一约束、租约、栅栏、类型化结果、证据链和恢复测试，
不是来自把 MQ、Redis、K8s、S3 四个名词同时画进图里。
```

## 2. 裁定等级

| 标记 | 含义 |
|---|---|
| `PASS-A` | 原方案存在语义或安全错误，永久不按原样采用 |
| `PASS-B` | 技术本身可用，但与当前规模和部署条件不匹配 |
| `PASS-C` | 目标采纳，但实现方式必须替换 |
| `DEFER` | 未来满足明确条件后再重新评估 |

## 3. 基础设施类未采纳项

### 3.1 PASS-B：把 MQ 作为告警入口的必选基础设施

**原建议**

告警进入后投递到 Kafka/RabbitMQ 等 MQ，立即返回 202，消费者再拉取任务；MQ 被描述为削峰和背压的必要组件。

**为什么听起来合理**

专业消息队列确实擅长高吞吐、消费者组、重放和跨服务解耦。大型平台拥有多个生产者、多个消费者、跨地域传输时，MQ 很常见。

**为什么当前 PASS**

本项目已经用 PostgreSQL 保存：

- `alert_inbox`；
- Incident；
- RCA Run/Task/Attempt；
- scheduler slot；
- external invocation ledger；
- report/outbox；
- DEAD/DLQ 状态。

如果再引入 MQ，会立刻出现“双真相”问题：

```text
MQ 说消息还在，PG 说任务已完成；
MQ 说已消费，PG 事务却回滚；
PG 已经创建 Run，消费者重投又试图创建第二个 Run。
```

为了修这些问题，还要新增 MQ message id 与 DB task id 对账、事务消息或额外 Outbox relay、消费者 offset 运维、broker 备份、鉴权和监控。对当前单实例、低吞吐告警项目，这些复杂度大于收益。

PostgreSQL 官方明确指出，`SKIP LOCKED` 可以用于多个消费者访问 queue-like table 时避免锁竞争，因此 PG 持久队列不是“拿数据库硬装 MQ”，而是数据库支持的合理小规模模式：[PostgreSQL SELECT / SKIP LOCKED](https://www.postgresql.org/docs/current/sql-select.html)。

**原样采用的事故场景**

1. Webhook 先投 MQ 成功，写 PG 失败：上游收到 202，但消费端没有幂等事实。
2. 写 PG 成功，ACK MQ 前进程崩溃：消息重投，重复创建 Run。
3. MQ 恢复、PG 未恢复：消费者不断拉取，但每条都失败，形成重试风暴。

**替代方案**

入口单事务写 `alert_inbox + audit event`，提交后才返回 202；Projector 使用 `FOR UPDATE SKIP LOCKED` 领取；通过 `available_at/deadline_at/priority` 实现延迟、优先级和背压。

**何时重新评估 MQ**

只有同时出现以下至少两项：

- 持续吞吐超过 PG 队列的压测上限；
- 多种非 Java 消费者需要独立订阅完整告警流；
- 需要跨地域事件复制；
- 需要保留海量事件供多团队长期重放；
- PG 队列争用已经成为可测量瓶颈。

### 3.2 PASS-B：把 Redis 作为检查点、限流、锁、黑板和缓存的共同骨骼

**原建议**

Redis 同时承担检查点、滑动窗口限流、Redlock、Agent 黑板、Intent 公告板、语义缓存和 Pub/Sub。

**为什么听起来合理**

Redis 延迟低、数据结构丰富，适合缓存和临时协调。多 Pod 系统里也经常使用。

**为什么当前 PASS**

本项目没有 Kubernetes 多 Pod，且所有关键状态要求重启后可恢复、可审计、可关联事务。把这些状态拆进 Redis 会带来：

- Redis 和 PG 谁是权威的问题；
- TTL 到期导致检查点静默消失；
- Redis 已更新但 PG 事务失败；
- Redis 快照恢复点与 PG WAL 恢复点不一致；
- 业务审计需要跨两套存储拼接；
- 2C4G 评测机资源被基础设施占用。

“以后可能水平扩展”也不能证明 Redis 是必需的。PG 行锁、唯一约束、lease owner、epoch fence 和 `SKIP LOCKED` 同样支持多个 worker；多实例首先需要解决的是状态机和所有权，而不是增加一个缓存服务器。

**原样采用的事故场景**

Agent 检查点已经写入 Redis，但 PG attempt 尚未提交；进程崩溃后恢复者加载了一个数据库并不承认的未来状态，形成“时间穿越”。

**替代方案**

- 权威状态：PostgreSQL；
- 大对象：CAS + SHA-256；
- 进程缓存：只缓存不可变 ConfigBundle、Tool Descriptor、已封存 Evidence；
- 缓存丢失：只影响性能，不影响正确性；
- 限流：当前单实例本地 bulkhead + 持久化 scheduler slot；未来多实例可用 PG 配额行或再评估 Redis。

**何时重新评估 Redis**

多实例限流精度经过压测证明 PG/本地组合不够，且 Redis 故障模式、持久化、监控、恢复和双写一致性都有明确方案时。

### 3.3 PASS-A：把 Redis Redlock 当作写工具幂等保证

**原建议**

获得 Redlock 才执行故障注入；锁失败直接跳过；锁不可用时写操作进入人工处理。

**为什么不够**

锁只能回答“此刻是否有人持锁”，不能回答：

- 前一个执行者有没有把请求发到远端；
- 远端已经成功，但响应是否丢失；
- 锁过期后第一个执行者是否仍在运行；
- 第二个执行者是否会再次执行相同副作用；
- 重启后如何知道上次动作结果。

因此，即使 Redlock 完美工作，也不能提供副作用幂等。

**事故场景**

```text
执行者 A 获得锁
→ 请求已到达 Chaos Controller
→ 网络断开，A 没收到响应
→ 锁超时释放
→ 执行者 B 再次获得锁并重复注入
```

**替代方案**

```text
operation_id
+ action_digest
+ observed_generation
+ DB 唯一约束
+ external_invocation_ledger(PREPARED/SENT/CONFIRMED/UNKNOWN)
+ 远端按 operation_id 幂等（若支持）
+ UNKNOWN reconciliation
+ lease epoch fence
```

这套模型既限制并发，也能处理“请求可能成功但响应丢失”。

### 3.4 PASS-B：把 Kubernetes/RBAC/Pod 作为当前架构前提

**原建议**

K8s API Server、Pod 重调度、Pod Label、RBAC、跨 AZ Harness。

**为什么当前 PASS**

这是用户已经明确排除的运行环境。当前服务器是 Docker Compose，195 还是 CentOS 7 / 3.10 内核；为展示“云原生”而引入 K8s，会把项目从 RCA Agent 变成集群搭建项目。

**替代方案**

- Docker network 分段；
- 独立容器身份和 API Key；
- PG role/schema 权限矩阵；
- compose `read_only/cap_drop/no-new-privileges`；
- Action Runner 独立凭证；
- 不开放 Docker TCP 2375。

K8s 只保留为未来迁移知识，不进入当前代码、Compose 和验收门。

### 3.5 PASS-B：把 Apollo/Nacos 配置中心直接塞进当前两机部署

**原建议**

用 Apollo/Nacos 长轮询热更新规则、Prompt 和 RAG Top-K，实现零重启回滚。

**认可的目标**

新配置只影响新 Session，旧 Session 固定旧版本；配置可快速回滚。这个目标已经采纳。

**为什么原实现 PASS**

Nacos 官方把 standalone 快速启动定位为测试用途，生产建议集群并启用认证；官方快速开始还建议至少 2C4G。备用服务器本身只有 2C4G，并已经承担 Candidate、LiteLLM、eval-runner 和 OTel，把整套 Nacos/Apollo 放进去会破坏资源预算。[Nacos Docker Quick Start](https://nacos.io/en/docs/latest/quickstart/quick-start-docker/)

**替代方案**

Git 保存配置源文件，PG 保存不可变 `ConfigBundle` 和 active 指针，应用缓存已验证的 bundle。PG `NOTIFY` 只做缓存失效提示，周期对账负责可靠收敛。PostgreSQL 官方也建议 `NOTIFY` 传键、实际数据放表里：[PostgreSQL NOTIFY](https://www.postgresql.org/docs/current/sql-notify.html)。

如果以后确有大量服务、多环境配置和专职运维，再评估配置中心。

## 4. HTTP、入口和错误语义类未采纳项

### 4.1 PASS-A：全局 Panic 后固定返回 HTTP 200 + 内部 code=500

**原建议**

捕获所有异常，对外永远返回 HTTP 200，Body 内写 `code=500`，避免错误继续传播。

**为什么这是协议说谎**

HTTP 200 属于成功响应。RFC 9110 明确区分：2xx 表示请求已成功接收、理解和接受；5xx 表示服务器未能完成一个看起来有效的请求。[RFC 9110 HTTP Semantics](https://www.rfc-editor.org/rfc/rfc9110.html)

如果系统还没把告警可靠落库就返回 200：

- Alertmanager 认为发送成功，不再重试；
- 告警永久丢失；
- 指标看到 100% HTTP 成功，但数据库中没有对应告警；
- 运维误以为入口健康。

这不是“阻断错误传播”，而是“掩盖错误并阻断上游重试”。

**正确语义**

| 时点 | 返回/处理 |
|---|---|
| 认证失败 | 401/403 |
| 结构或内容非法 | 400 |
| 请求过大 | 413 |
| 限流且未受理 | 429 + Retry-After |
| DB 提交失败、尚未受理 | 503 |
| 已可靠持久化 | 202 + session_id |
| 202 后异步失败 | HTTP 状态已经结束，通过任务状态、通知和审计表达 |

AWS 的异步通信指导也明确建议：在对象被持久化之前，不应发送受理确认。[AWS Asynchronous Communication](https://docs.aws.amazon.com/prescriptive-guidance/latest/modernization-integrating-microservices/asynchronous.html)

Panic 中间件仍保留，但职责是脱敏日志、Trace 关联、状态收尾和正确 HTTP 映射，不是把一切伪装成成功。

### 4.2 PASS-C：把完整安全护栏放在限流之前

**原建议**

先运行 Prompt Injection/高危指令检测，再进行全局限流。

**问题**

认证、Content-Type 和 Content-Length 是廉价检查，必须在最前面；但深度 JSON Schema、Unicode 归一化、正则/分类器扫描可能消耗 CPU。如果所有复杂护栏都放在限流之前，攻击者可以用大量合法大小的复杂 Payload 打满护栏本身。

**替代顺序**

```text
Transport Gate：认证、类型、请求体上限
→ Rate Limit
→ 有界解析、Schema、字段限制、内容风险
→ 原子持久化
```

OWASP 也将输入验证、结构化 Prompt、最小权限工具、输出/动作检查和人工审批视为纵深防御，而不是依赖一个入口检测器：[OWASP LLM Prompt Injection Prevention](https://cheatsheetseries.owasp.org/cheatsheets/LLM_Prompt_Injection_Prevention_Cheat_Sheet.html)。

### 4.3 PASS-A：429 后转入等待队列

同一份建议中同时出现“返回 429 不处理”和“返回 429 后进入等待队列”。两种语义不能共存。

- 429 表示本次请求没有被接受，调用方按 Retry-After 重试；
- 如果服务已经把消息可靠放入等待队列，就应该返回 202，并给出追踪 ID；
- 不能既让调用方重试，又偷偷排队，否则会造成重复告警。

本项目冻结：**429 不落业务队列；202 必须已经持久化。**

### 4.4 PASS-C：一分钟 + 故障域 + 告警类型的“因果指纹”

**原建议**

相同分钟、可用区、告警类型的 1000 条告警合成一个代表 Agent，其余全部继承结论。

**问题**

时间接近、位置相同、名字相同只代表“相关性可能高”，不代表因果相同。例如同一分钟两个 payment 实例都超时，一个是数据库连接池耗尽，另一个是实例 GC；直接继承结论会传播错误。

Alertmanager 本身已经提供 grouping、deduplication、inhibition 和 `group_wait/group_interval`，官方明确把大规模故障时的数百条相似告警合并为一个通知作为核心用途：[Alertmanager 官方说明](https://prometheus.io/docs/alerting/latest/alertmanager/)。

**替代方案**

```text
Alertmanager group = 投递信封
alert fingerprint = 单条事实幂等边界
incident_key = 领域关联边界
incident generation = RCA Run 边界
```

其他告警作为 Incident member 和 Evidence，不是盲目继承代表结论。

## 5. 调度、线程和检查点类未采纳项

### 5.1 PASS-A：固定大小线程池 = CPU 核数，并在队列满后再挂入等待队列

**原建议**

子 Agent 都作为 Callable 进入固定线程池；池大小等于 CPU 核数；有界 BlockingQueue 满后再放入等待队列挂起。

**为什么不适合本项目**

Agent 工作主要等待 LLM、Prometheus、日志和数据库 HTTP/I/O，不是长期 CPU 运算。用 CPU 核数限制线程，会让大量时间花在“线程正在等网络”，吞吐反而很低。

Java 21 官方说明虚拟线程适合大部分时间阻塞等待 I/O 的任务；它们提升吞吐而非降低单次延迟。官方也明确建议通过 Semaphore 限制稀缺服务的并发，而不是把虚拟线程做成固定池：[Oracle Java 21 Virtual Threads](https://docs.oracle.com/en/java/javase/21/core/virtual-threads.html)。

“队列满后再放入等待队列”本质上是把有界队列变成无界队列，只是换了名字。等待对象仍占内存，系统仍会在更晚的时候崩溃。

**替代方案**

- 每个阻塞任务一个虚拟线程；
- PG 是唯一持久等待队列；
- Holmes/Candidate/Action 使用持久化 slot；
- HTTP 和 Hikari 连接池限制物理连接；
- deadline 过期的低价值任务不再执行；
- 不在 JVM 堆里积累十万 Callable。

### 5.2 PASS-C：读工具超时立即返回 pending，原请求继续后台跑，成功后补写上下文

**原建议的优点**

不让一个慢请求长期占据平台线程，这个目标正确。

**为什么原方案危险**

超时后请求继续执行，会继续占用：

- HTTP 连接；
- 对端查询资源；
- Prometheus/日志系统并发额度；
- 本地回调对象和内存。

更严重的是，晚到结果可能属于旧 generation。Agent 已经完成、取消、重试或被其他 Worker 接管时，异步回调把旧数据写回当前上下文，会污染新的调查。

Java HttpClient 官方明确说明 `cancel(true)` 只是尽力取消，不能保证请求没有发送或对端没有继续处理：[Java 21 HttpClient](https://docs.oracle.com/en/java/javase/21/docs/api/java.net.http/java/net/http/HttpClient.html)。所以“释放线程”不等于“操作已经停止”。

**替代方案**

1. 调用前写 `external_invocation_ledger=PREPARED`；
2. 发起后写 `SENT`；
3. deadline 到达时尽力取消；
4. 能确定失败则 `FAILED_RETRYABLE/TERMINAL`；
5. 不能确定则 `UNKNOWN`；
6. Reconciler 用 invocation_id 查询结果；
7. 写回前校验 attempt owner、lease epoch 和 observed_generation；
8. 旧结果只归档，不进入新上下文。

### 5.3 PASS-A：Redis 检查点“损坏后加载摘要版并继续”

**问题一：坏数据不能静默降级成好数据**

如果检查点 digest、Schema 或 generation 不合法，系统不知道摘要是否也来自同一有效状态。继续推理可能比停止更危险。

**问题二：覆盖式检查点丢失历史**

`SET session_id = new_context` 会覆盖上一版。临时 key + rename 只能保证单 key 原子替换，不能保证它与 PG task/attempt 状态处于同一事务。

**替代方案**

- Run/Task/Attempt 为结构化状态；
- Agent Event append-only；
- Evidence/Claim 单独保存；
- 原始响应和大上下文进入 CAS；
- 恢复时从最后一个通过 Schema、digest、owner epoch 的事件重建；
- 摘要可以是一个新 artifact，但必须保留来源和 digest，不能冒充原文。

### 5.4 PASS-C：所有失败都“Fail Silent”

错误隔离是对的，错误静默是错的。

如果权限拒绝、Schema 错误、工具超时、证据缺失全部不抛出也不形成类型化终态，外部看到的只是“没有结果”，无法区分：

- 系统没执行；
- 执行失败；
- 数据不存在；
- 权限不足；
- Agent 主动 UNRESOLVED。

本项目的原则是 **Fail Contained + Fail Explicit**：错误不能拖垮别的任务，但必须落成明确的 `TypedOutcome/reason_code/audit event`。

### 5.5 PASS-C：Go 风格全局 `Context.Done()` 直接照搬进 Java

取消传播的目标采纳，但本项目是 Java 21，不直接照抄 Go API。

Java 落地为：

- Run/Task 持久化 `cancellation_requested_at`；
- Worker 在步骤边界检查；
- 虚拟线程 `interrupt`；
- HTTP future 尽力 `cancel(true)`；
- 写操作即使取消也进入 UNKNOWN/reconciliation；
- 任何晚到完成都要经过 epoch/generation fence。

取消不是数据库回滚，也不能假设远端副作用被撤销。

## 6. LLM 输出、推理和多 Agent 类未采纳项

### 6.1 PASS-A：格式修复失败后伪造一个“低置信度根因”

**原建议**

构造固定伪返回：当前轮无法解析，但基于已有证据推测根因 X，低置信度，正常跳出循环。

**为什么不通过**

解析失败意味着系统无法证明模型实际输出了哪些合法 Claim。控制面不能替模型补写根因，更不能把一个未验证字符串包装成成功报告。

**正确行为**

- 原始响应脱敏后进入 CAS；
- InvestigationResult 记录 `REJECTED_SCHEMA/REJECTED_SIZE/...`；
- 若此前已有通过验证的 Claim，可由确定性 Assembler 生成 PARTIAL；
- 没有有效 Claim，输出 UNRESOLVED；
- 失败同样参与评测分母。

### 6.2 PASS-C：不存在的工具名作为 Observation 再喂给 LLM 无限自纠

工具注册表拦截是正确的，但不能让 Agent 无限尝试。

正确流程：

```text
第一次未知工具 → TOOL_REJECTED(UNKNOWN_TOOL) → 消耗一次纠错预算
第二次仍未知   → FAILED_TERMINAL 或 PARTIAL/UNRESOLVED
```

未知工具执行率必须为 0，未知工具提议率进入评测指标。控制面不创建一个假的工具执行结果。

### 6.3 PASS-A：“观点漂移检测后优先相信上一轮”

**为什么危险**

RCA 的正常过程就是随着证据增加不断修正假设。最先形成的结论往往最不可靠。规则如果偏爱旧观点，会制造确认偏误：Agent 为了维护旧答案而解释掉新证据。

**替代方案**

- Claim 使用 TRUE/FALSE/UNKNOWN；
- 每次修订产生 `CLAIM_REVISED`；
- 必须说明新增/失效的 evidence refs；
- Reducer 检查数据源权威性、时间窗、新鲜度和双源佐证；
- 无法裁决时 VERIFY 一次，仍冲突则 NEEDS_REVIEW。

### 6.4 PASS-A：置信度加权投票

**原建议**

三个 Agent 各报 0~100 confidence，最高分领先 20 分就采信，否则人工。

**为什么不通过**

没有校准的数据中，80 分和60分不具有可比较的概率含义。不同 Prompt、模型和 Agent 角色对数字尺度的理解不同。两个共享同一错误前提的 Agent 也可能都报 95 分，多数和高分都不能制造真相。

本项目 confidence 只用于候选排序和评测校准，不参与最终事实裁决。最终裁决依靠 Evidence provenance、Claim 类型的权威源规则和 ground truth/人工结案。

### 6.5 PASS-A：固定优先级 `Metrics > Logs > Traces`

没有全局正确的数据源排名：

| Claim | 更合适的权威来源 |
|---|---|
| 当前配置值是什么 | 配置库/部署清单 |
| 某个请求经过哪些服务 | Trace |
| 某时间窗错误率是否升高 | Metrics |
| 某进程抛出了什么异常 | 原始结构化日志 |
| 订单是否支付成功 | 业务台账/数据库事实 |

因此必须按 `claim_type` 配置权威源，不能一条规则覆盖全部问题。

### 6.6 PASS-C：关键词式 Anchor Guard

**原建议**

每两轮检查工具调用是否包含“变更单”等关键词；三轮没出现就强制插话。

**问题**

关键词出现不代表完成了验证，不出现也可能是因为数据源不可用或已有充分反证。它容易被表面文本欺骗。

**替代方案**

Task 契约包含：

```text
goal
required_claims
allowed_tools
required_sources
acceptance_criteria
budget
```

控制面检查是否产生对应 Claim/Evidence 或明确的 DATA_UNAVAILABLE，而不是搜索自然语言关键词。

### 6.7 PASS-C：Redis 公告板 + 固定等待 100ms 去重

**问题**

100ms 是魔法常数。Prometheus 查询可能 20ms，也可能 5s；固定等待既可能白等，也可能等不够。Redis pending key 若执行者崩溃，还会留下悬挂意图。

**替代方案**

工具调用幂等键：

```text
sha256(tool_version + canonical_args + scope + time_range + snapshot_digest)
```

同 Run 已完成则复用 artifact；正在执行则建立 DAG 依赖；执行者崩溃后按 lease 回收。无需固定等待时间。

### 6.8 PASS-C：根据错误结论引用比例自动降低 Agent 健康分

Provenance 必须保留，但不能仅凭“错误报告引用了谁的证据最多”就处罚谁。

一个 Metrics Agent 可能准确报告 CPU 高，但 Reducer 错误地把“伴随症状”判成根因。此时证据贡献多不等于 Agent 犯错。

纠错后应分类：

- PLANNING：任务拆错或遗漏；
- TOOL_INPUT：工具/参数/时间窗选错；
- TOOL_OUTPUT：Adapter 解析错；
- EVIDENCE：证据本身不完整或过期；
- REDUCER：证据正确但因果裁决错；
- REPORT：冻结 Claim 正确，文字报告表述错。

只有分类和样本量足够后，健康分才可作为路由参考，不能直接自动降权。

### 6.9 PASS-A：Agent 之间传递“完整黑匣子上下文”

外部建议一处主张 Redis 结构化黑板，另一处又说父 Agent 把上下文写 Redis给子 Agent 继续执行。这两种说法互相冲突。

本项目采用结构化黑板：Agent 只传 `EvidenceRef/ClaimRef/ArtifactRef` 和受限摘要，不传完整自然语言会话。原始数据留在 CAS，避免上下文呈平方级膨胀。

## 7. 工具执行与副作用类未采纳项

### 7.1 PASS-A：由 `X-Dry-Run: true` Header 决定工具不执行

**为什么危险**

Header 是调用方输入。如果外部调用方能够改变执行模式，就会产生：

- 本应真实执行的恢复动作被伪装成 Dry-Run；
- 本应 Dry-Run 的测试因 Header 丢失变为 LIVE；
- 审批绑定的是一种模式，实际执行变成另一种模式；
- 重放代理或网关可能删除/复制 Header。

**替代方案**

执行模式属于持久化 `AgentTask/ToolPolicy`，并进入 task input digest 或 action digest。Header 最多表达偏好，控制面重新裁决后落库才生效。

### 7.2 PASS-A：Dry-Run 返回伪造 mock_data 并标记 success

Dry-Run 没访问真实系统，就不能声称工具成功，也不能把随意 mock 的数据当 Evidence。

正式语义：

- `VALIDATE_ONLY`：参数、权限、预算、目标和动作摘要合法，但未执行；
- `REPLAY_MOCK`：数据来自带版本和 digest 的 Dataset；
- `SHADOW_READ`：真实只读调用，但结果不进入生产上下文；
- `LIVE`：真实执行。

### 7.3 PASS-A：写工具进行 1% 影子真实调用

读工具的影子查询最多增加负载；写工具的“影子”依然是真实副作用。网络延迟、磁盘 IO 注入、删容器等操作不存在无害的“结果不写上下文”。

R2/R3 只允许：

```text
VALIDATE_ONLY
→ 生成 canonical action plan
→ policy
→ approval(action_digest)
→ LIVE
→ verify
→ cleanup
```

只读 R0/R1 才允许 `SHADOW_READ`，并有单独 QPS/timeout/result-size 预算。

### 7.4 PASS-C：所有工具统一指数退避 2s/4s/8s

指数退避 + jitter 的方向正确，但不能给所有错误和所有工具统一套三次。

- 401/403：配置或权限错误，不重试；
- 400/422：参数或查询错误，不重试；
- 404：可能是资源不存在，不能一律当 API 版本问题；
- 429：尊重 Retry-After；
- 5xx/连接失败：只读操作可按预算重试；
- UNKNOWN 写操作：先 reconciliation，不能直接重试；
- irreversible 写操作：默认零自动重试。

退避时间写入 `available_at`，不 `sleep` 占线程和 slot。

### 7.5 PASS-A：锁失败后直接跳过写操作

锁失败只能说明另一个执行者可能正在处理，不能说明处理必然成功。“跳过”会让失败的第一执行者无人接管。

正确处理是让第二个任务关联同一个 `operation_id`，等待/查询权威 invocation ledger：

- CONFIRMED：复用成功；
- FAILED_TERMINAL：明确失败；
- UNKNOWN：对账；
- lease 过期且远端确认未执行：再由新 epoch 接管。

### 7.6 PASS-C：API 版本嗅探后动态加载插件

能力探测和契约测试采纳，运行时下载插件不采纳。

动态下载意味着生产进程执行一个未经构建、签名、SBOM、安全扫描和回归测试的代码包，供应链风险远大于“快速兼容”。

正式策略：

- 镜像中预装并登记受支持 Adapter；
- Tool Descriptor 带 `tool_version/input_schema_hash/output_schema_hash/adapter_version`；
- 启动探测选择已登记 Adapter；
- 不兼容时工具进入 DEGRADED；
- 新 Adapter 走构建、签名、Shadow Read 和发布流程。

Prometheus 官方承诺 major 版本内 `/api/v1` HTTP API 稳定，因此本项目更应 pin major + 契约测试，而不是为假设的 `v1beta3` 动态下载插件：[Prometheus API Stability](https://prometheus.io/docs/prometheus/latest/stability/)。

## 8. 数据、审计和缓存类未采纳项

### 8.1 PASS-A：保存完整 Thought/推理链到 S3，并提供“查看推理过程”

**为什么不通过**

完整隐藏推理不是可靠证据，还可能包含：

- 系统 Prompt；
- Secret 或认证信息；
- 未脱敏日志；
- 用户隐私；
- 模型未经验证的猜测；
- 大量无法稳定复现的自然语言。

把这些内容长期保存会扩大泄漏面、存储成本和审计歧义。用户真正需要的是“结论依赖了哪些可验证事实”，不是模型脑内散文。

**替代证据链**

```text
TASK_STARTED
TOOL_REQUESTED / REJECTED / SUCCEEDED
EVIDENCE_PRODUCED
CLAIM_PRODUCED / REVISED / CONSUMED
REPORT_ASSEMBLED / VALIDATED
TASK_FINISHED
```

再关联 Tool 参数/结果 digest、CAS 原文、时间窗、Agent/Task ID 和裁决 reason code。这样能复核、能统计，也更容易脱敏。

### 8.2 PASS-A：语义向量相似度 >0.95 就直接复用历史根因

**为什么危险**

文本相似不等于故障原因相同：

```text
“payment 延迟升高”
可能是数据库慢、连接池耗尽、GC、网络抖动、限流或发布回归。
```

即使 Embedding 相似度是 0.99，也只能说明描述相似，不能证明环境、时间窗、版本和证据相同。直接复用根因会把旧事故答案扩散成新事故的伪真值。

**替代方案**

- 只有 `incident generation + evidence_snapshot_digest + config_digest` 全相同才允许精确复用已验证结果；
- 语义检索未来只能提供历史案例候选，不得跳过当前 Evidence 验证；
- 所谓“节省 70%~90%”在本项目没有实测，不写入架构承诺。

### 8.3 ACCEPT_WITH_GATES：RAG 拆成“检索候选”与“复用根因”分别裁定

第二轮治理评审指出原裁定把三件不同的事绑在一起。复核后修正为：

1. 独立 Redis/向量数据库成为新基础设施：继续 DEFER；
2. 相似度超过阈值直接复用根因：继续 PASS-A（明确拒绝）；
3. 从已验证历史事故/Runbook 检索**待验证假设**：AM5 `ACCEPT_WITH_GATES`。

第三项可以通过，是因为它不改变事实边界：检索结果的地位与告警正文里的线索相同，都是 `UNTRUSTED_HYPOTHESIS`；只有本次 Incident 时间窗内的 Metrics/Logs/Traces/变更证据才能把它升级为 Claim。pgvector 是 PostgreSQL 扩展，不需要再运维一套服务，但仍会增加镜像、升级、索引内存和备份验证，因此不是“零成本”。小规模先用 PG 全文/精确搜索，A/B 证明收益后再启用。

启用时必须包含：

```text
knowledge_item_id
source_revision
valid_from / valid_to
service_topology_version
embedding_model_version
content_digest
retrieval_policy_version
```

历史案例只作为建议，仍需当前事实验证；SQL 权限层必须排除别的租户、`eval_private`、HOLDOUT 和待审核 Feedback。若有/无检索的配对 HOLDOUT 评测没有显著提升 Top-1，或 unsupported claim/成本/延迟恶化，则保持 `rag_policy_version=NONE`。

### 8.4 PASS-C：模型/工具/事件版本指纹注入每轮 System Prompt

全链路版本指纹必须保存，这一目标已经采纳；但不必每轮把完整 hash 塞进 Prompt：

- 浪费 Token；
- 模型不需要理解 64 位 digest；
- digest 不是推理指令；
- Prompt 内文本仍可能被模型复述到输出。

版本指纹保存在 Attempt 元数据、审计事件和 evidence manifest。模型真正需要知道的规则版本只传简短的受控字段。

### 8.5 PASS-C：使用廉价 LLM 总结历史对话作为唯一压缩结果

模型摘要可能遗漏否定词、时间戳、数量级和已排除条件。如果直接替换原始历史，摘要错误会成为后续唯一事实。

本项目按数据类型确定性压缩：

- Metrics：服务端聚合、极值、变化点和窗口 coverage；
- Logs：模板聚类、计数、首尾样本，原文入 CAS；
- Trace：关键路径、错误 span 和延迟贡献；
- Agent 历史：结构化 Claim/Evidence 状态，不压缩成散文。

如使用小模型生成可读摘要，只能作为派生 artifact，必须保留原始 refs，且不得替换权威证据。

## 9. Outbox、DLQ 和降级类未采纳项

### 9.1 PASS-A：Outbox 可以保证“不重复”

Transactional Outbox 解决“业务数据写成功但通知没发出”的双写问题，不天然保证下游只看到一次。

典型窗口：

```text
Worker 调用 IM 成功
→ 在把 outbox 标为 SENT 之前崩溃
→ 恢复后重新发送
```

AWS 官方明确提醒 Outbox 处理可能产生重复消息，消费方需要幂等：[AWS Transactional Outbox](https://docs.aws.amazon.com/prescriptive-guidance/latest/cloud-design-patterns/transactional-outbox.html)。

本项目承诺的是：本地命令不丢、单时刻单执行者、at-least-once、重复可检测可审计；不虚假承诺群机器人 exactly-once。

### 9.2 PASS-A：Outbox 积压后绕过 Outbox 直接调用备用 Webhook

这会重新引入 Outbox 原本要解决的双写问题：主渠道状态和备用直发没有统一事务、唯一键和审计顺序。

正确处理：

- 监控 `oldest_ready_age`、ready 数量、发送失败率；
- 必要时人工/策略提高 notify slot；
- 备用渠道同样生成独立 outbox command；
- 每个渠道使用 `(report_id, channel, template_version)` 唯一键；
- 不绕过账本。

单实例 Docker Compose 不承诺自动扩容；扩容也不是修复渠道 429 的办法。

### 9.3 PASS-A：毒消息、权限失败、格式失败全部进入同一个 DLQ

DLQ 只处理“已经受理、确实应该执行，但在允许的重试预算内无法完成”的任务。

| 情况 | 正确去向 |
|---|---|
| Webhook Schema 非法 | HTTP 400 + SECURITY_REJECTED 审计 |
| Webhook 无认证 | HTTP 401/403 |
| Task 数据因代码 Bug 无法解析 | FAILED_TERMINAL + defect alarm，可进入 DEAD 视图 |
| Agent 输出 Schema 失败 | InvestigationResult=REJECTED，不代表输入消息是毒消息 |
| 工具暂时 5xx 且预算耗尽 | DEAD/DLQ |
| 高危动作无权限 | POLICY_REJECTED，不自动 Replay |

把所有错误放进同一个 DLQ，会让运维无法判断是修数据、修代码、修权限还是等依赖恢复。

### 9.4 PASS-A：DLQ 看门狗 Agent 自动编一条兜底根因

进入 DLQ 的任务往往正是输入损坏、契约不兼容或依赖长期失败。再启动一个“不调工具、只凭经验”的 Agent，只会把无法分析包装成看似完整的答案。

DLQ 的自动动作只允许：

- 生成确定性摘要；
- 标出失败阶段和 reason code；
- 给出人工处理 Runbook；
- 修复后创建新 generation Replay；
- 保留原任务不可变。

### 9.5 PASS-C：四级逃生通道直接输出根因

精确缓存、规则和静态模板可以用于降级，但它们只能产出 `DEGRADED_TRIAGE/UNRESOLVED`，除非缓存键同时匹配 Incident generation、EvidenceSnapshot 和 ConfigBundle，且原报告已经通过验证。

“CPU 告警 → CPU Limit 太低”只是常见模式，不是必然根因。规则命中应表述为“建议优先检查”，不能表述为“已确定根因”。

## 10. 灾备类未采纳项

### 10.1 PASS-B：当前直接建设跨 AZ Warm Standby

**原建议**

每五分钟把 Redis 活跃检查点备份到 S3，备用 Harness 启动后先加载最近一小时快照。

**为什么不能恢复本系统**

本系统的权威状态包括：

- Incident generation；
- Run/Task/Attempt 状态；
- lease owner/epoch；
- external invocation ledger；
- Approval/action digest；
- report/outbox；
- Evidence/CAS digest。

只恢复 Redis 检查点，无法恢复这些事实，反而可能让一个旧上下文在没有合法 lease 的情况下继续执行。

**替代方案**

- PostgreSQL base backup + WAL continuous archive/PITR；
- CAS 按 digest 异机复制；
- 配置 Git/Dataset 异机备份；
- 恢复后从 PG 状态重建 Worker；
- 每月从零恢复演练；
- 依据实际演练填写 RPO/RTO。

PostgreSQL 官方说明 base backup 与连续 WAL 可以恢复到指定时间点，也可作为构建 standby 的基础：[PostgreSQL PITR](https://www.postgresql.org/docs/current/continuous-archiving.html)。项目优先复用 pgBackRest，而不是自行实现 Redis→S3 检查点协议：[pgBackRest User Guide](https://pgbackrest.org/user-guide.html)。

备用 2C4G 当前承担评测隔离；在没有额外容量、自动切换、脑裂防护和演练证据前，不能称为 Warm Standby。

## 11. 图本身的表达问题

### 11.1 Mermaid 围栏错误

原文把 `flowchart TD` 放在代码围栏外，又用 ` ```rust` 和 ` ```lua` 包裹 Mermaid 内容。标准 Markdown 渲染不会把它识别成 Mermaid。

正确格式：

````text
```mermaid
flowchart TD
    A --> B
```
````

### 11.2 同图混用“接口层、存储层、执行层”的 L3 编号

编号不是严重运行错误，但会让评审误以为层级存在从属关系。架构图应区分：

- Ingress Plane；
- Control Plane；
- Investigation Plane；
- Action Plane；
- Evaluation Plane；
- Data/Audit Plane。

这些是职责边界，不只是从上到下的代码包层级。

### 11.3 把基础设施框整体连向所有层

`L4_Infra -.-> L1/L2/L3` 无法表达谁能访问谁，反而掩盖权限边界。例如 Holmes 应能访问 Prometheus，但不能访问 PG、通知和 Chaos Admin。

正式图必须画出具体身份与方向，不能用“整个基础设施依赖所有层”的大虚线代替安全设计。

## 12. 最终裁定汇总

| 外部建议 | 裁定 | 本项目替代 |
|---|---|---|
| MQ 入口队列 | PASS-B | PG inbox + SKIP LOCKED |
| Redis 全局状态 | PASS-B | PG 权威状态 + CAS + 本地可丢缓存 |
| Redis Redlock 幂等 | PASS-A | operation_id + ledger + unique + reconciliation |
| K8s/RBAC/Pod | PASS-B | Docker network + PG role + 独立凭证 |
| Apollo/Nacos | PASS-B | Git + immutable ConfigBundle + PG active pointer |
| Panic 固定 200 | PASS-A | 提交前正确 4xx/5xx，提交后状态机收尾 |
| 429 后仍排队 | PASS-A | 429 不受理；202 已持久化 |
| 分钟哈希代表 Agent | PASS-C | Alertmanager group + incident_key/generation |
| CPU 大小固定线程池 | PASS-A | 虚拟线程 + slot/semaphore + 连接池 |
| 超时后后台补录上下文 | PASS-C | cancel + UNKNOWN ledger + fenced reconciliation |
| Redis 覆盖式检查点 | PASS-A | Task/Attempt/Event + CAS |
| Fail Silent | PASS-C | Fail Contained + Typed Outcome |
| 格式失败伪造根因 | PASS-A | REJECTED/PARTIAL/UNRESOLVED |
| 观点漂移优先旧答案 | PASS-A | CLAIM_REVISED + Evidence Reducer |
| confidence 加权投票 | PASS-A | 权威源规则 + 双源佐证 + Verify/HITL |
| Metrics 永远高于 Logs | PASS-A | 按 claim_type 定义权威源 |
| 关键词 Anchor Guard | PASS-C | required_claims + acceptance criteria |
| Redis 公告板等 100ms | PASS-C | Tool Invocation 幂等键 + DAG 依赖 |
| 自动 Agent 健康扣分 | PASS-C | 错误阶段分类后再统计 |
| Header 控制 Dry-Run | PASS-A | 持久化 ToolPolicy |
| Dry-Run 伪造 mock success | PASS-A | VALIDATE_ONLY / REPLAY_MOCK 分离 |
| 写工具 1% Shadow | PASS-A | 写操作 plan→policy→approval→live |
| 所有工具统一重试 | PASS-C | 按错误类别、副作用和预算决定 |
| 动态下载 Adapter | PASS-A | 镜像内 pin Adapter + 契约测试 |
| 完整 Thought 存 S3 | PASS-A | 可审计 Event/Evidence/Claim 链 |
| 语义缓存直接返回根因 | PASS-A | 同 Snapshot 精确复用；语义结果只作候选 |
| RAG/pgvector | ACCEPT_WITH_GATES | AM5 仅作 UNTRUSTED 历史假设；相似度直接复用根因仍拒绝 |
| 指纹写入每轮 Prompt | PASS-C | Attempt 元数据 + manifest |
| LLM 摘要替换历史事实 | PASS-C | 类型化确定压缩 + 原文 CAS |
| Outbox exactly-once | PASS-A | at-least-once + 可检测重复 |
| 积压时绕过 Outbox | PASS-A | 备用渠道也必须走 Outbox |
| 所有错误进同一 DLQ | PASS-A | 按入口拒绝/调查拒绝/任务 DEAD 分类 |
| DLQ Agent 编兜底根因 | PASS-A | 确定性失败摘要 + 人工/Replay |
| Redis 快照温备 | PASS-B | PG WAL/PITR + CAS 备份 + 恢复演练 |

## 13. 这套 PASS 决策实际保护了什么

### 13.1 防止状态分裂

不引入 MQ+Redis 作为第二、第三权威状态，崩溃恢复只需要回答“PG 中的事实是什么、CAS digest 是否存在”。

### 13.2 防止协议层丢告警

只有 durable commit 后返回 202；没有提交就返回正确错误码，上游仍有机会重试。

### 13.3 防止副作用重复

不把锁误当成幂等，使用 operation ledger 处理最难的“远端可能成功、但本地不知道”场景。

### 13.4 防止 AI 错误被包装成成功

格式失败就是 REJECTED，证据不足就是 PARTIAL/UNRESOLVED，冲突就是 NEEDS_REVIEW；不会为了“每条告警都有答案”而制造假根因。

### 13.5 防止评测和生产互相污染

Shadow 只能使用只读工具；Mock 必须来自 Dataset；Dry-Run 不能伪造 Evidence；Candidate 不持生产写权限。

### 13.6 防止项目被基础设施反客为主

系统依然具备认证、验证、限流、幂等、重试、DLQ、生命周期、审计、回放和灾备，但维护者不需要同时运维 K8s、Redis 集群、MQ 集群、配置中心和对象存储集群。

## 14. 第二/三轮治理反馈与“最后一公里”裁定

### 14.1 本轮“采纳”的判断标准

本轮不是“评审提了就加”。一项建议只有同时满足以下四条才可进入 v1.2：

1. **问题真实存在**：能给出具体失败窗口，而不是只说“业界都这么做”；
2. **不破坏冻结边界**：不新增第二权威状态，不把权限交给 LLM/Header，不让评测流量越过生产边界；
3. **语义可执行**：有状态、契约、预算、幂等和失败终态，不停留在组件名；
4. **可以验收**：能写成自动化断言、压测或 E2E 证据，而不是靠截图或主观描述。

裁定汇总：

| 反馈项 | 裁定 | 一句话理由 |
|---|---|---|
| Incident 源状态对账 | ACCEPT | resolved webhook 可能丢，本地投影需要与上游当前事实低频对账 |
| Incident 跨 generation 预算 | ACCEPT | Run 各自不超预算仍可能靠无限新 Run 烧穿总额 |
| HOST1 资源账 | ACCEPT | 真正生产关键路径不能只给评测机算内存 |
| 模型随机性控制 | ACCEPT_WITH_CHANGES | 固定参数和重复试验必要，但不能承诺 temperature=0 后绝对确定 |
| `rca_agent_event + rca_stream_event` 双日志 | ACCEPT（采纳合并） | UI 投影不应制造第二份事实和同步双写 |
| Dataset Holdout | ACCEPT | 同一 Case 同时调优和打分会产生乐观偏差 |
| EvidencePackage 回补 | ACCEPT | 输入 Snapshot 与输出 Package 是两个对象，不能混名 |
| Top-3 | ACCEPT_WITH_CHANGES | 只有 v3 排序候选契约可算；AM3 单根因只算 Top-1 |
| 报告状态统一 | ACCEPT | 验证与发布是两种故障域，采用两轴状态最清楚 |
| AM1 六态→目标全集 | ACCEPT | 显式迁移映射防止 AM4 改枚举破坏旧状态机测试 |
| AM3 generation/schema 直接栅栏 | ACCEPT | 结果和 ToolCall 是高价值边界记录，直接列比多级 join 更安全 |
| Candidate 数据路径分离 | ACCEPT | 红队 Replay 与在线只读 Shadow 的权限目的完全不同 |
| Holmes 实时成本闸 | ACCEPT_WITH_CHANGES | 硬闸要下沉 LiteLLM，但必须验证当前版本/持久预算后端，不凭功能列表宣称完成 |
| IncidentIdentityPolicy | ACCEPT | 一个“等”字无法决定服务级合并与实例级拆分 |
| 人工升级/SLA | ACCEPT_WITH_CHANGES | 需要确定性 Operator Case；不强绑定 PagerDuty 和魔法阈值 |
| Harness 三支柱 | ACCEPT_WITH_CHANGES | JSON/Micrometer/OTel 必需；Filebeat 只是可替换采集器，审计不是普通日志 |
| 热冷分离 | ACCEPT_WITH_CHANGES | 协议必须先冻结；7/24/30 天只是初值，分区应由容量证明触发 |
| Ground Truth 生命周期 | ACCEPT_WITH_CHANGES | 版本/有效期/隔离都通过；架构升级不能自动废掉所有样本 |
| pgvector 历史检索 | ACCEPT_WITH_GATES | 仅作待验证假设且 AM5 A/B 达标后启用；相似度直接当根因仍拒绝 |

### 14.2 为什么 Incident 源状态对账可以 PASS

现有五路对账覆盖了“任务有没有失主、钱是否对上、工具是否可重查、写动作是否生效、通知是否送达”，但漏了最上游的事实：Incident 现在到底还 firing 吗。

失败窗口很具体：

```text
Prometheus/Alertmanager 已 resolved
    -> resolved webhook 发送期间 control-app 不可达
    -> 后续不再有新通知
    -> 本地 Incident 永久 FIRING
```

因此增加 Source State Reconciler 是补完整性，不是发明第二控制面。它只读上游当前 alerts，把“后来看到上游已连续缺席”追加为 `ReconciliationObservation`，再交给状态机推进。本地原始 webhook 和历史 firing 事实不被覆盖。一次 API 失败/无数据绝不能当成 resolved。

为什么不让它高频跑：这是兜底对账，不是新告警入口。低频、独立 slot、最大尝试和积压年龄监控即可；否则对账器自身会变成 Prometheus 压力源。

### 14.3 为什么 Incident 预算与 HOST1 资源账可以 PASS

Run 预算解决“一次调查别无限循环”，没有解决“同一 Incident 能无限创建新调查”。标签抖动、firing/resolved 翻转或 investigation hash 变化都可能推动 generation。每个 Run 都守规矩，总账仍可能无限。因此必须在创建 Run 前原子预留 Incident 窗口预算，耗尽后继续保存告警事实，但停止派生新 RCA 并升级人工。

HOST1 资源账同理：2C4G 有预算不代表生产主机安全。195 同时跑 control、Holmes、PG、Prometheus、Alertmanager、notify 和按需 order-arena；这台机器的低水位动作必须比“Candidate 停回放”更明确。v1.2 已补成三档：先停 Live E2E/Shadow、再降 Holmes slot、再暂停领取新 RCA；入口仍能持久化。这样容量故障表现为延迟和可见告警，而不是 OOM 后状态不明。

这里没有直接把评审里的 RSS 估算当真值。容器 limit、JVM heap 和进程 RSS 不是同一个数，最终门槛必须由一周真栈 `docker stats + RSS + MemAvailable` 证据校准。

### 14.4 为什么采样控制只能“修改后 PASS”

评审说“固定 temperature/seed”方向对，但“固定后即可复现”过度承诺。提供商未必支持 seed；即使 temperature=0，推理服务实现和数值路径仍可能残余非确定。

正式落法：

- 记录 temperature/top_p/max tokens/tool choice/seed 请求与是否生效/provider fingerprint；
- 把它们放进 execution fingerprint；
- Baseline/Candidate 做同 Case 配对重复；
- 报告原始结果、翻转率、方差和 bootstrap 区间；
- seed 不支持时写 `UNSUPPORTED`，不伪造一个 seed 值；
- AM3 的 5×2 只是冒烟，不作为发布门。

通俗说：我们能把“骰子怎么扔”记清楚、让双方尽量按同样方式扔，并多扔几次看分布；不能宣称云端模型从此不再是骰子。

### 14.5 为什么统一事件账本可以 PASS

原设计同时有 `rca_agent_event` 和 `rca_stream_event`，两者都 append-only 且都记录 Tool/Evidence/Report 事件。这会产生三个问题：

1. 一边写成功一边失败，审计与 UI 对不上；
2. 为 WebSocket 额外锁 `stream_head`，显示器可能拖慢业务提交；
3. 两份 Schema 会独立演进，半年后没人知道哪份是事实。

最终只保留 `rca_event`。状态推进所需的事件与业务事实原子提交；`rca_agent_event` 是筛选视图，WebSocket/SSE 是按 seq 的读投影。`NOTIFY` 由提交后去抖 notifier 发送，只起“叫醒”作用；通知失败时轮询账本补齐。这样“UI 坏了不影响 RCA”成为事务边界，而不是口号。

PostgreSQL 官方明确说明 NOTIFY 在事务提交后才交付、结构化数据应放表中、长事务监听者会阻碍通知队列清理。因此只传 `run_id/last_seq`，监控 `pg_notification_queue_usage()`，不把每条正文或业务提交成败绑定给通知队列。

### 14.6 为什么 Holdout 与 Ground Truth 生命周期可以 PASS

Prompt、规则、同义词表、检索策略虽然不是传统模型权重，但反复根据同一 Golden Set 调整它们，本质仍是“用测试集做模型选择”。这样算出的准确率只证明记住了题库。

所以 Dataset 分成：

```text
TUNING      可调 Prompt/规则
VALIDATION  迭代检查
HOLDOUT     只做发布门和对外数字
REDTEAM     单独算安全与误拒绝
```

Ground Truth 放 `eval_private`，control/Holmes/Candidate/RAG 的 PG 角色在数据库权限层不可见。人工纠错先成为候选，复核后发布新 DatasetVersion。样本内容 INSERT-only，纠错通过 supersedes 关联旧版本。

评审原建议“架构大版本升级时自动把旧样本 valid_until=NOW()”不能原样通过。升级不等于每个事实都失效，例如网络超时、连接池耗尽仍可能有效。正确动作是把受影响样本置 `REVIEW_REQUIRED`，复核确认语义过期后再关闭有效期。否则一次版本升级会把 HOLDOUT 清空，发布门反而失明。

“分布偏差 >20%”也只作为补样复核触发器，不直接自动改题库；小样本的百分比波动很大，仍要看 support 和业务风险。

### 14.7 为什么 EvidencePackage、Top-3 与报告两轴状态需要一起裁定

`EvidenceSnapshot` 是模型运行前冻结的输入；`EvidencePackage` 是 Holmes 运行后的六段式输出；`AgentResult` 是 Holmes/Native 共用的外层信封。把 Package 从契约表漏掉，会让 AM3 实际运行的 Validator 无目标可验，因此回补是必需修正。

Top-3 不能靠“把置信度最高的三个 Claim 当根因”拼出来，因为 Claim 是事实主张，根因候选是解释事故的候选，两者语义不同。AM3 v2 只有单根因，诚实结果就是只算 Top-1。AM4+ 的 v3 增 `candidate_root_causes[]{rank, component, fault_type, reason_code, supporting/contradicting claim refs}` 后，Top-3 才可启用。rank 不等于概率，也不会复活已拒绝的置信度加权投票。

报告状态采用两轴：

- `rca_report.validation_status` 回答“内容是否通过结构/证据验证”；
- `report_publication.delivery_status` 回答“通知是否已送达”。

一个内容正确但渠道暂时 500 的报告不能被标为验证失败；一个已经发送但后来发现证据不足的报告也不能靠改 Outbox 状态修复。拆轴是为了让失败归到正确责任域。

### 14.8 为什么状态迁移和 AM3 直接栅栏可以 PASS

AM1 当前代码的 Task 六态是 `READY/LEASED/RETRY_WAIT/DONE/CANCELLED/DEAD`，目标架构是更完整的十二态。只说“AM1 是子集”不够，因为 `RETRY_WAIT` 与 `FAILED_RETRYABLE`、`DONE` 与 `SUCCEEDED` 名称不同。v1.2 已写明逐态映射与数据库迁移顺序：先放宽 CHECK、双读、回填、新写、回放，再删除旧值。

AM3 的 InvestigationResult/ToolCall 是评测和审计高频读取的边界记录。虽然可通过 run 外键 join 得到 generation，但写入时直接携带 observed_generation/schema_version/digest 能形成更明确的新鲜度栅栏，也满足自动契约校验。这里是“目标迁移要求”，不是声称当前 V7/V8 已经落码。

### 14.9 为什么 Candidate 数据路径必须分开

“Candidate 读取同一冻结 Snapshot”与“Candidate 实时调用 Tool Gateway”能做的实验不同：

- Replay/Red-team 要验证推理和安全，必须 `REPLAY_MOCK`，绝不能触达生产 Prometheus；
- Snapshot Shadow 要公平比较同证据结论，不允许 Candidate 偷偷补查；
- Online Read Shadow 要评估实时工具选择，只能走独立凭据、allowlist、限流和 slot 的 R0/R1 端点，而且不能承载红队 Case。

如果混成一条 WireGuard 路径，一个对抗样本就可能把恶意查询送到 195；如果全部冻结，又永远测不到 Candidate 的实时工具参数质量。分模式是正确答案，不是二选一。

### 14.10 为什么 Holmes 成本闸只能“修改后 PASS”

control-app 一次 `/api/chat` 对应 Holmes 内部多次模型调用，外层只能事后对账，确实无法单独实现 per-run 费用硬闸。把 admission control 下沉 LiteLLM 是正确的，因为代理能看到每一次真实模型请求。LiteLLM virtual key 支持预算、TPM/RPM 和并发限制。

但不能因此直接写“已经不可透支”：

- 预算依赖代理的持久 spend 后端和具体版本；
- key 预算粒度是否能稳定绑定 run/attempt 要实测；
- 单次请求的最终 Token 仍可能超过发起前估计；
- proxy 故障时必须明确 fail-closed 还是 BEST_EFFORT。

因此 v1.2 要求 control 预留 + LiteLLM 调用前门禁 + provider usage 冲销 + spend 对账四段式。当前版本达不到就降低最大步骤/输出并标 `BEST_EFFORT`，不能用事后账单冒充实时护栏。

### 14.11 为什么 IncidentIdentityPolicy 可以 PASS

`alertname + service 等` 不是公式。“等”取不取 pod/instance 会直接改变成本与正确率：取了会把服务故障拆成 N 个 RCA，不取会把同服务不同实例的 GC/DB 故障并在一起。

正式做法是按告警族冻结版本化 label allowlist：服务级告警默认不含 instance，实例级 JVM/进程告警必须含 instance，节点级告警含 node，severity/value/timestamp 永不参与身份。缺少必选字段时进入 `IDENTITY_INCOMPLETE`，不能偷偷降级成更粗 Key。若同一父 Incident 的权威证据显示多个互斥作用域，创建子 `InvestigationScope`，而不是强迫单一根因解释全部成员。

这部分必须自研，因为只有项目自己知道订单域和故障域；Alertmanager 负责 group，不知道“哪些症状应该共享一个 RCA”。

### 14.12 为什么人工介入机制可以 PASS，但不绑定 PagerDuty

“连续三次 UNRESOLVED”“一小时 NEEDS_REVIEW 超过五条”是合理的初始触发器，解决了 AI 不确定后无人接盘的问题。通过的不是 PagerDuty 这个品牌，而是完整的 Operator Case：幂等创建/合并、优先级、负责人、ACK/处置 SLA、升级、证据和终态。

阈值进入版本化策略而不是 Java 常量。通知通过 escalation outbox 发到 PagerDuty、企业 IM、短信或现有工单系统。这样私有化部署没有 PagerDuty 也不缺能力；渠道失败还能重试/审计。

人工纠错不会直接“训练 Agent”。它先形成 `CORRECTION_DIVERGENCE`，保留原报告不变，经过证据和双人复核后进入 Golden Candidate。系统可以不确定，但“不确定的处理路径”必须确定。

### 14.13 为什么三支柱可以 PASS，但 Filebeat 不是必选

Logging/Metrics/Tracing 都是必要的：没有结构日志无法查单次失败，没有指标无法看队列/slot/Outbox 趋势，没有 Trace 无法拆分入口、排队、LLM、工具和发布耗时。

最终落法是：JSON stdout；Micrometer `/actuator/prometheus`；OpenTelemetry Trace/Context。项目已有 OTel Collector 规划，若环境已有 Filebeat 可以接 JSON stdout，但为此再常驻一个 Filebeat 会重复采集职责并消耗两台小机器的预算，所以不设为硬依赖。

更重要的是区分：

```text
审计账本 = 不采样、状态正确性的组成部分
普通日志 = 诊断，可受限/轮转
Metrics  = 聚合趋势，只用低基数 label
Trace    = 因果与时延，可按策略采样
```

trace_id 必须由 OTel SDK 生成并跨异步任务传播，不能只“自己造一个 UUID 就算分布式追踪”。控制面自身告警走独立 receiver 和 2C4G 黑盒探针，不进入 RCA，防止系统给自己看病。

### 14.14 为什么冷热分离可以 PASS，但不能照抄固定天数和租户子分区

任务、调用账本、Outbox、审计会增长，先定义归档协议是必要的；但“task 7 天、ledger 24 小时、audit 30 天”没有法规和容量实测支撑，只能当初始基线。`RetentionPolicyV1` 必须可版本化，并允许租户合同、合规和 legal hold 覆盖默认值。

同样，按 `(year_month, tenant_id)` 给每个租户建立物理子分区在小体量时可能造成分区爆炸。正式方案先按月 RANGE，tenant 建索引/RLS；达到容量阈值再决定子分区。

“搬到 archive_schema”若仍在同一磁盘，只是改名字，不是冷存储。真正可通过的归档必须有 manifest、行数、主键范围、Schema 版本、文件 digest、重读校验、删除宽限和恢复演练。校验失败绝不删热数据；冷层不可用返回 `ARCHIVE_UNAVAILABLE`，不能假装历史不存在。

### 14.15 继续明确拒绝的部分

本轮吸收反馈后，以下红线没有变化：

- 不因人工审批通过就允许生产 R3 chaos；
- 不让 Header/浏览器决定 LIVE/SHADOW/DRY-RUN；
- 不把 `confidence`、向量相似度或多数票当根因真值；
- 不把 Prompt/Thought/完整工具结果写进日志、WebSocket 或 metric label；
- 不让红队/Replay 访问生产 Tool Gateway；
- 不把遥测/NOTIFY/实时 UI 变成第二权威状态；
- 不让 DLQ Agent、静态模板或历史案例把失败包装成“已确认 RCA”；
- 不把配置、归档、评测阈值和 SLA 的初值硬编码成永远正确的行业常数。

## 15. 外部依据

1. [RFC 9110 — HTTP Semantics](https://www.rfc-editor.org/rfc/rfc9110.html)：2xx/4xx/5xx 与 202 的协议含义。
2. [AWS — Asynchronous communication](https://docs.aws.amazon.com/prescriptive-guidance/latest/modernization-integrating-microservices/asynchronous.html)：异步 ACK 应在消息可靠持久化之后发送。
3. [AWS — Transactional outbox](https://docs.aws.amazon.com/prescriptive-guidance/latest/cloud-design-patterns/transactional-outbox.html)：Outbox 解决双写，但仍可能重复，消费端需幂等。
4. [PostgreSQL — SELECT / SKIP LOCKED](https://www.postgresql.org/docs/current/sql-select.html)：支持 queue-like table 的并发消费者。
5. [PostgreSQL — Constraints](https://www.postgresql.org/docs/current/ddl-constraints.html)：唯一约束用于从存储层强制幂等不变量。
6. [Oracle — Java 21 Virtual Threads](https://docs.oracle.com/en/java/javase/21/core/virtual-threads.html)：阻塞 I/O 任务与虚拟线程、Semaphore 的适用边界。
7. [Oracle — Java 21 HttpClient](https://docs.oracle.com/en/java/javase/21/docs/api/java.net.http/java/net/http/HttpClient.html)：取消为尽力而为，不能假设远端未执行。
8. [Resilience4j — CircuitBreaker](https://resilience4j.readme.io/docs/circuitbreaker)：CircuitBreaker 与并发 Bulkhead 的职责不同。
9. [Prometheus — Alertmanager](https://prometheus.io/docs/alerting/latest/alertmanager/)：告警 grouping、deduplication、inhibition。
10. [Prometheus — API stability](https://prometheus.io/docs/prometheus/latest/stability/)：稳定 `/api/v1` API 与 major 版本边界。
11. [OWASP — LLM Prompt Injection Prevention](https://cheatsheetseries.owasp.org/cheatsheets/LLM_Prompt_Injection_Prevention_Cheat_Sheet.html)：输入、Prompt、工具权限、输出和 HITL 的纵深防御。
12. [Nacos — Docker Quick Start](https://nacos.io/en/docs/latest/quickstart/quick-start-docker/)：standalone 的测试定位和生产集群/鉴权建议。
13. [PostgreSQL — NOTIFY](https://www.postgresql.org/docs/current/sql-notify.html)：通知用于变更提示，结构化权威数据应放在表中。
14. [PostgreSQL — Continuous Archiving/PITR](https://www.postgresql.org/docs/current/continuous-archiving.html)：base backup、WAL archive、PITR 和 standby 基础。
15. [pgBackRest User Guide](https://pgbackrest.org/user-guide.html)：成熟 PostgreSQL 备份、WAL、恢复、加密和异机仓库能力。
16. [Prometheus 当前 Alerts HTTP API](https://prometheus.io/docs/prometheus/latest/querying/api/#alerts)、[Alertmanager Alerts API](https://prometheus.io/docs/alerting/latest/alerts_api/) 与 [Alertmanager configuration](https://prometheus.io/docs/alerting/latest/configuration/)：当前 active alerts 查询、firing/resolved 重发与 `send_resolved` 边界；只支持低频交叉核验，不支持把一次缺席直接判为 resolved。
17. [PostgreSQL NOTIFY](https://www.postgresql.org/docs/current/sql-notify.html)：事务提交后通知、payload/队列/长事务限制，支持“表是事实、NOTIFY 只唤醒”。
18. [PostgreSQL Table Partitioning](https://www.postgresql.org/docs/current/ddl-partitioning.html)：分区收益、detach/drop 与冷介质迁移，同时说明应由表规模决定是否启用。
19. [OpenTelemetry Context Propagation](https://opentelemetry.io/docs/concepts/context-propagation/) 与 [Messaging Spans](https://opentelemetry.io/docs/specs/semconv/messaging/messaging-spans/)：异步边界需要传播 Context 才能关联生产者和消费者。
20. [OpenTelemetry Logs](https://opentelemetry.io/docs/specs/otel/logs/)：TraceId/SpanId 与日志关联。
21. [Spring Boot Metrics](https://docs.spring.io/spring-boot/reference/actuator/metrics.html)：Micrometer 与 `/actuator/prometheus` 的官方落法。
22. [Prometheus Metric/Label Naming](https://prometheus.io/docs/practices/naming/)：每个标签组合都会产生时间序列，禁止把无界 ID 放 label。
23. [scikit-learn Common Pitfalls](https://scikit-learn.org/stable/common_pitfalls.html) 与 [Cross-validation](https://scikit-learn.org/stable/modules/cross_validation.html)：测试集不可参与模型选择、应保留 holdout，并显式控制随机性。
24. [LiteLLM Virtual Keys](https://github.com/BerriAI/litellm-docs/blob/main/docs/proxy/virtual_keys.md)：key 预算、TPM/RPM、最大并发和预算后端边界。
25. [pgvector](https://github.com/pgvector/pgvector)：PostgreSQL 内精确/近似向量检索、索引内存与 recall 取舍。

---

## 16. 一句话评审话术

> 我们没有因为系统小就删除可靠性能力，而是删除了重复的基础设施。入口、队列、检查点、租约、幂等、重试、DLQ 和 Outbox 全部以 PostgreSQL 为一个权威状态机；CAS 保存大证据。LLM 和工具失败必须显式落成类型化终态，不能用 HTTP 200、低置信度假根因或 DLQ Agent 把错误包装成成功。等吞吐、多订阅者或跨地域需求真的超过这套边界，再用压测证据决定是否引入 MQ、Redis或 Warm Standby。
