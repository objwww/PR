# 告警 Agent 增量实现任务拆解（v1.0）

> 文档日期：2026-09-04  
> 状态：待执行  
> 上位设计：`docs/架构设计-告警Agent-v1.2.md`  
> 原则：核心架构不再扩张；以小模块、短反馈、可回滚、可独立验证的方式逐层堆积，最终替换 HolmesGPT。

## 1. 当前起点

当前不是从零开发：

- `control-app` 已有 Alertmanager 入口、Inbox、Incident、Run/Task/Attempt、PostgreSQL 仓储、租约槽位、Holmes 执行器和结构验证；
- `shared-kernel` 已有 digest、重试头解析、状态迁移异常和安全归档工具；
- AM2、AM3 技术方案已经冻结为 v3.0，但尚未进入正式编码；
- AM4+ 的目标架构已在 v1.2 冻结，不能为了某个小任务顺手改变核心骨架。

2026-09-04 本地基线：

```text
mvn -q test
tests=215, failures=0, errors=0, skipped=2
```

本机 Docker/Testcontainers 不可用，所以这个结果只证明普通测试绿，不证明 PostgreSQL 集成测试或部署门已通过。凡任务验收写了 `IT`、`DP` 或 `E2E`，必须在可用 Docker 或 195 环境产生真实证据，跳过不能算绿。

当前必须先关闭的真实缺口：

1. compose 使用 `ALERT_WEBHOOK_BEARER`，应用读取 `ALERTMANAGER_WEBHOOK_BEARER_TOKEN`；
2. Holmes 配置读取 `app.alert.holmes.api-key`，但缺少 `HOLMES_API_KEY` 的显式桥接；
3. `rca_report` 是不可变表，但自检曾要求 UPDATE 权限；
4. `RcaRunStateMachine`、`RcaTaskStateMachine`、`InboxStateMachine` 仍存在生产路径绕过；
5. 告警域没有真正执行的 PostgreSQL IT；
6. Alertmanager → control-app webhook → Incident → Holmes → Report 的真实链路证据尚未完全闭合。

因此执行顺序固定为：**先 G0 收口 AM1，再 AM2 建靶场，再 AM3 建可评测基线，再 AM4 建 Native 多 Agent，最后 AM5/AM6 做发布治理与替换**。

## 2. 如何把大系统拆成小积木

### 2.1 单任务大小上限

一个任务原则上满足：

- 只改变一个主要行为或一个紧密相关的数据契约；
- 预计 0.5～2 人日；超过 2 人日必须继续拆分；
- 一个任务一个主要失败原因，不把“建表、业务逻辑、外部接入、E2E”塞进同一个提交；
- 生产代码和直接验证它的测试同任务提交；
- 数据库迁移只能前向追加，不修改已经执行过的历史迁移；
- 每个任务结束时，主分支仍可构建、可启动，已有路径不退化。

任务可以是横向基础件，但每个阶段必须以纵向闭环收口。例如 AM2 不是把所有表建完才验证，而是逐步形成：正常订单 → 一个故障 → 一条业务告警 → 一个 RCA → 一份可核对报告。

### 2.2 每个任务的固定交付物

每个任务必须同时交付：

```text
代码/配置
直接测试
受影响契约说明
执行命令与退出码
证据 manifest（文件、SHA-256、环境、版本）
必要时截图（仅 UI/部署/E2E；截图不是唯一证据）
```

### 2.3 四级验证

| 级别 | 验证内容 | 何时必须执行 |
|---|---|---|
| V0 静态 | 编译、格式、ArchUnit、配置键和迁移契约 | 每个任务 |
| V1 单元 | 纯函数、状态机、策略、序列化、错误分类 | 每个代码任务 |
| V2 组件 | Testcontainers PostgreSQL、WireMock、权限角色、崩溃窗口 | 涉及仓储/外部调用的任务 |
| V3 纵向 E2E | Docker 真栈或 195：注入 → 告警 → RCA → 报告/通知 | 每个里程碑收口 |

失败规则：

- V0/V1 红：不得合并；
- V2 因环境不可用被跳过：任务只能标记 `CODE_COMPLETE`，不能标记 `VERIFIED`；
- V3 失败：里程碑不能过 G2，但不回滚已经独立验证通过的小模块；
- 任何“人工看起来正常”都不能替代断言、数据库查询结果和 digest。

## 3. 总体堆积路径

```mermaid
flowchart LR
    G0[G0 AM1 收口<br/>可靠入口与单 Agent 基线]
    M2[AM2 订单靶场<br/>真实业务故障与 Ground Truth]
    M3[AM3 调查落档<br/>评测、通知、成本账]
    M4[AM4 Native 多 Agent<br/>DAG、工具、证据、Shadow]
    M5[AM5 发布治理<br/>质量门、人工台、归档、外部监控]
    M6[AM6 Holmes 退场<br/>Native Primary]

    G0 --> M2 --> M3 --> M4 --> M5 --> M6
```

每个阶段都会留下可运行产品：

| 阶段完成后 | 系统真实具备的能力 |
|---|---|
| G0 | Alertmanager 告警能可靠进入并由 Holmes 生成结构化报告 |
| AM2 | 能主动制造三类订单故障，并保存不可篡改 Ground Truth |
| AM3 | 每次成功或失败调查都有记录，可评分、可通知、可核算成本 |
| AM4 | Native 多 Agent 与 Holmes 使用同一证据契约并做 Shadow 对照 |
| AM5 | 候选版本受安全/质量/运行门控制，有人工升级和长期运维能力 |
| AM6 | Native 成为主路径，Holmes 只作回退，达标后退场 |

## 4. G0：关闭 AM1 缺口

> 目标：不增加新能力，只让现有单 Agent 基线成为可信地基。

| ID | 小任务 | 依赖 | 实现边界 | 单项验证 |
|---|---|---|---|---|
| G0-01 | 固化当前基线 manifest | 无 | 记录 commit、JDK/Maven、测试计数、跳过原因；不改业务逻辑 | `mvn test` 退出 0；报告计数与 surefire XML 一致 |
| G0-02 | 统一 Webhook 配置键 | G0-01 | `application*.yml`、compose、自检只保留 `ALERTMANAGER_WEBHOOK_BEARER_TOKEN` | 配置契约测试覆盖缺失、空值、正确值；`docker compose config` 可解析 |
| G0-03 | 补 Holmes API Key 显式桥接 | G0-02 | `app.alert.holmes.api-key: ${HOLMES_API_KEY:}`；禁止依赖模糊 relaxed binding | Spring context 测试证明环境变量能注入 Bean；缺失时 fail-fast |
| G0-04 | 修正不可变报告表权限自检 | G0-01 | `rca_report` 只验证 SELECT/INSERT；不授 UPDATE 迁就错误自检 | 自检 UT + 真实低权限 PG IT；UPDATE 必须被拒绝 |
| G0-05 | 接线 Inbox 状态机 | G0-01 | 所有 Inbox 状态更新必须先 `requireTransition` | 合法/非法迁移测试；生产调用点静态检查 |
| G0-06 | 接线 Run 状态机 | G0-05 | Orchestrator/Worker 不得直接绕过 Run 迁移规则 | 状态穷举 + 非法跃迁不落库 IT |
| G0-07 | 接线 Task 状态机 | G0-06 | claim/retry/finish/reap 全部经过 Task 状态机 | 租约过期、重试耗尽、旧 epoch 提交测试 |
| G0-08 | 告警仓储 PostgreSQL IT | G0-04～07 | 为 Inbox/Incident/Run/Task/Attempt/Report 增加真实数据库测试，不扩业务 | 唯一约束、claim、CAS、短事务、权限矩阵全绿；Testcontainers 未跳过 |
| G0-09 | Alertmanager webhook 配置入库 | G0-02 | 将 receiver、route、send_resolved 和 bearer 配置纳入 `deploy` 事实源 | `amtool check-config`；伪 token 401、合法 group 202 |
| G0-10 | AM1 单链路 E2E | G0-03、08、09 | 正常触发一条可控告警，走完整 Holmes 路径 | 入口 202、Incident/Run/Attempt/Report SQL、Holmes ledger、最终截图与 manifest |
| G0-11 | AM1 G2 复核 | G0-10 | 只做复核和证据封存，不顺带开发 AM2 | `mvn clean verify`、真栈 E2E、BA-09/10/11 全关闭 |

G0 的提交顺序不能颠倒：配置和权限问题先修，状态机按 Inbox → Run → Task 逐块接线，最后才做 PG IT 和真栈。

## 5. AM2：订单靶场与三类真实故障

> 目标：先得到“一个正常订单”，再逐个增加故障。三个故障各自都是独立小模块，不能一次性一起写。

### 5.1 工程和数据底座

| ID | 小任务 | 依赖 | 实现边界 | 单项验证 |
|---|---|---|---|---|
| M2-01 | 创建 `order-arena` Maven 模块 | G0-11 | 只建模块、健康端点、Dockerfile 和 512MiB 限额 | reactor 构建；容器 health 绿 |
| M2-02 | 建立 `deploy/alert` 唯一 compose | M2-01 | 回收告警栈配置；旧 compose 标记用途，不能双事实源 | `docker compose config --quiet`；服务/网络/卷清单快照 |
| M2-03 | 创建 arena/eval DB 角色 | M2-02 | `arena_app`、`eval_app`、schema 和最小授权 | 角色正反权限 IT；control/Holmes 不能读 GT |
| M2-04 | 订单基础表迁移 | M2-03 | order、payment、fulfillment、idempotency 相关表；只建约束 | migration contract + 重复/非法状态写入失败 IT |
| M2-05 | 补偿 Outbox 表迁移 | M2-04 | 只建 arena outbox、claim 索引和状态约束 | SKIP LOCKED 双 worker 测试；查询计划走索引 |
| M2-06 | ChaosSession/GT 表迁移 | M2-03 | `oa_chaos_session` 与 ground truth 权限边界 | 激活唯一约束、TTL、eval 可读/control 不可读 IT |

### 5.2 正常订单纵向切片

| ID | 小任务 | 依赖 | 实现边界 | 单项验证 |
|---|---|---|---|---|
| M2-07 | 订单状态机纯领域实现 | M2-04 | 只做状态、命令、迁移表，无 Spring/SQL | 合法边全绿、非法边全拒、ArchUnit |
| M2-08 | 幂等记录组件 | M2-04 | claim/replay/conflict 三种确定性结果 | 相同 key 同请求复用、不同请求冲突、并发唯一约束 IT |
| M2-09 | 两步创单第一步 | M2-07、08 | 创建业务单和支付意图，不做补偿 worker | 事务回滚、重复请求、并发请求 IT |
| M2-10 | 两步创单第二步 | M2-09 | 支付确认后推进业务单；不引入故障开关 | 成功/拒绝/超时结果映射 UT+IT |
| M2-11 | 三单一致性模型 | M2-10 | 业务单/支付单/履约单关系和不变量 | 三单正常状态组合与非法组合测试 |
| M2-12 | 补偿事件生产 | M2-11、M2-05 | 业务事务内只写 outbox，不执行外部补偿 | 崩溃窗口证明业务事实与事件同生共死 |
| M2-13 | 补偿 worker | M2-12 | claim、租约、幂等消费、终态；不加 MQ | kill/restart 后最终收敛 IT |
| M2-14 | 正常业务 API | M2-11 | create/pay/cancel/query；统一错误契约 | MockMvc 契约测试 + 真 PG 场景 |
| M2-15 | 有界流量发生器 | M2-14 | 可配置速率、并发和停止；不允许无界线程 | 并发上限、取消、过载拒绝、资源回收测试 |

### 5.3 故障逐个叠加

| ID | 小任务 | 依赖 | 实现边界 | 单项验证 |
|---|---|---|---|---|
| M2-16 | ChaosSession 仓储与 fail-closed 查询 | M2-06 | DB 是唯一开关；查询异常时故障关闭 | DB 不可用不注入；租户/场景隔离 IT |
| M2-17 | 私网 Chaos 管理 API | M2-16 | activate/deactivate/status；只监听 eval-mgmt | 业务网访问失败；eval 身份正向通过；审计字段完整 |
| M2-18 | F1 幂等失效注入 | M2-08、17 | 只实现 F1；激活时同时登记 GT | 无 session 零污染；激活后确定性复现；TTL 恢复 |
| M2-19 | F2 状态回跳注入 | M2-07、17 | 只实现 F2；不能破坏非目标订单 | 状态回跳可观测；目标选择准确；终止后恢复 |
| M2-20 | F3 超时结果未知状态机 | M2-10、17 | 独立持久状态机，不用 sleep 模拟 | timeout/late success/reconcile 三路径 IT |
| M2-21 | 业务 Gauge 指标 | M2-18～20 | 表示当前异常存量，支持恢复归零 | scrape 值与 SQL 真值一致；重启不重复累计 |
| M2-22 | 业务 Counter 指标 | M2-18～20 | 表示累计发生次数，与 Gauge 分开 | 重复 scrape 不增计数；故障事件只计一次 |
| M2-23 | Prometheus 告警规则 | M2-21、22 | 每个故障一条最小规则和 labels | `promtool check rules`；firing/resolved 实测 |
| M2-24 | ScenarioMap 显式映射 | M2-18～23 | scenario → alert → expected root cause/claim | 未知场景拒绝；映射版本/digest 稳定 |
| M2-25 | F1 单场景 E2E | M2-24 | 只跑 F1 完整链路 | GT、alert、Incident、Report 一一关联 |
| M2-26 | F2 单场景 E2E | M2-25 | 在已验证底座上增加 F2 | 同上，且不污染 F1 数据 |
| M2-27 | F3 单场景 E2E | M2-26 | 在已验证底座上增加 F3 | UNKNOWN/对账语义与报告一致 |
| M2-28 | AM2 G2 部署门 | M2-27 | 全量复跑、资源测量、证据归档 | 三场景、正常流量、TTL、权限、内存上限全部绿 |

## 6. AM3：调查落档、通知与可计算基线

> 目标：不做 Native Agent，先把 Holmes 的每次成功、失败、工具调用、成本和通知变成稳定数据。

### 6.1 契约与持久化

| ID | 小任务 | 依赖 | 实现边界 | 单项验证 |
|---|---|---|---|---|
| M3-01 | 冻结 EvidencePackage v2 Java 契约 | M2-28 | 类型化 root_cause/claims；保留 v1 类 | JSON 正反样本、长度/枚举/schema 测试 |
| M3-02 | 实现 v1/v2 路由 Validator | M3-01 | 按 schema_version 路由；禁止猜版本 | v1 回归、v2 全绿、未知版本拒绝 |
| M3-03 | 创建 V8 数据迁移 | M3-01 | investigation_result、tool_call、report_publication、notify_outbox | migration contract、索引、FK、授权 IT |
| M3-04 | InvestigationResult 仓储 | M3-03 | 每个 attempt 恰有成功或失败结果 | 重复写幂等；REJECTED/UNRESOLVED 也落档 |
| M3-05 | Holmes 外层响应 Parser | M3-02 | 解析 body、usage、tool_calls、truncation；不保存 Thought | 固定 fixture、未知字段兼容、超限拒绝 |
| M3-06 | ToolCall Adapter | M3-05 | tool_call_id/name/args/result/status 映射内部契约 | SUCCESS/ERROR/NO_DATA/APPROVAL_REQUIRED 枚举测试 |
| M3-07 | ToolCall 仓储 | M3-03、06 | 直接保存 run/generation/schema/digest 栅栏 | FK、去重、跨 generation 拒绝 IT |
| M3-08 | Attempt 落档编排 | M3-04、07 | 一次调用在同一收尾事务关联 Result/ToolCall | 进程在各提交点崩溃的恢复测试 |
| M3-09 | 报告验证状态与发布状态分离 | M3-03、08 | report 不可变；publication 可重试 | UPDATE report 被拒；publication 状态机测试 |

### 6.2 评分与 Dataset 最小闭环

| ID | 小任务 | 依赖 | 实现边界 | 单项验证 |
|---|---|---|---|---|
| M3-10 | ScenarioMap → GoldenCase 适配器 | M2-24 | 只读 GT，产评测输入；生产 Agent 不可见 | 权限正反测试、digest 对齐 |
| M3-11 | coverage 纯函数 | M3-10 | 只实现覆盖率和原始计数 | 全矩阵穷举 |
| M3-12 | conditional_accuracy 纯函数 | M3-11 | UNRESOLVED 不进入分母但单列 | 边界/零分母测试 |
| M3-13 | end_to_end_hit_rate 纯函数 | M3-12 | 端到端命中；不能由前两者推测 | 原始计数和公式快照测试 |
| M3-14 | EvalRun 持久化 | M3-13 | 记录 dataset/config/model/sample 参数 | 同输入可复现；历史不可覆盖 IT |
| M3-15 | eval-runner 独立身份 | M3-14 | 独立 profile/进程/DB role；不成为生产 Bean | Spring context 隔离测试、DB 正反权限 |
| M3-16 | 单案例评分 CLI/API | M3-15 | 输入一个 case/run，输出机器可读结果 | golden fixture 一致 |
| M3-17 | 5×2 串行批量评测 | M3-16 | 每场景复原后才进入下一轮 | 10 次记录齐全、失败不中断结果落档 |
| M3-18 | 基线报告生成器 | M3-17 | 原始 TP/FP/FN、三指标、失败样本、版本 | 报告 schema 和 digest 测试 |

### 6.3 通知、成本和最小可观测性

| ID | 小任务 | 依赖 | 实现边界 | 单项验证 |
|---|---|---|---|---|
| M3-19 | NotifyOutbox 生产者 | M3-09 | 报告进入可发布状态时同事务写事件 | 报告+outbox 原子性 IT |
| M3-20 | Outbox Claimer | M3-19 | SKIP LOCKED、租约、退避和有限批量 | 双 worker、崩溃回收、索引计划 IT |
| M3-21 | 白名单通知渲染器 | M3-19 | 只渲染已批准字段和安全 URL | 注入字符串、凭据、超长字段测试 |
| M3-22 | Dry-run 渠道 Handler | M3-20、21 | 首个渠道写测试接收器，不接真实 PagerDuty | at-least-once、重复 event_id 可检测 |
| M3-23 | 通知失败与 DEAD 状态 | M3-22 | 429 Retry-After、5xx 退避、4xx 终态 | WireMock 时间控制测试 |
| M3-24 | LiteLLM 部署收口 | M2-02 | proxy、私网、密钥和模型路由；不改 Agent | health、直连模型被网络策略阻止 |
| M3-25 | LiteLLM usage 适配器 | M3-24、M3-05 | provider reported 与 preflight 分栏 | 1:1、1:N、缺 usage 三态测试 |
| M3-26 | Holmes 预算 feasibility spike | M3-25 | 验证是否能在每次真实模型调用前硬拦；只产结论 | 可重复实验记录；不能硬拦则标 BEST_EFFORT |
| M3-27 | 结构化 Logging 最小集 | M3-08 | session/run/step/decision/latency 进 JSON 字段 | 日志 capture 测试；无敏感正文 |
| M3-28 | Micrometer 最小指标 | M3-20、27 | run/tool/outbox/slot；禁止高基数 ID label | actuator 测试 + label allowlist 测试 |
| M3-29 | OTel Context 贯穿最小链路 | M3-27 | HTTP → PG task → Holmes → outbox 传播 trace context | 异步边界 span 关联测试 |
| M3-30 | AM3 G2 | M3-18、23、26、29 | 完整基线评测、通知和成本证据 | 5×2、失败落档、通知重发、账本对账全部绿 |

## 7. AM4：Native 多 Agent 的确定性执行链

> 目标：先搭持久 DAG 和统一工具边界，再写 Native Agent。不能先写一个会自由调用工具的“大脑”。

### 7.1 数据与状态底盘

| ID | 小任务 | 依赖 | 实现边界 | 单项验证 |
|---|---|---|---|---|
| M4-01 | 扩展 Task/Run 新旧状态契约 | M3-30 | Java 双读旧值，不先改 DB 约束 | 旧 fixture 回放、状态映射穷举 |
| M4-02 | 状态约束扩容迁移 | M4-01 | DB 先允许新旧状态；不做数据回填 | 新旧节点写入契约 IT |
| M4-03 | 状态数据回填作业 | M4-02 | 分批、可重入、记录进度 | 中断重跑、行数/digest 对账 |
| M4-04 | `rca_task_edge` 迁移与仓储 | M4-02 | 只增加边和唯一/自环约束 | FK、自环拒绝、重复边幂等 IT |
| M4-05 | DAG 环检测纯函数 | M4-04 | 调度前验证，无数据库副作用 | 空图、菱形、环、断点图测试 |
| M4-06 | READY/BLOCKED 推进器 | M4-05 | 仅入度满足任务变 READY | 并发前驱完成、可选前驱失败测试 |
| M4-07 | generation fence | M4-06 | claim/finish/merge 全部比较 observed_generation | 旧 generation 结果变 STALE，不能污染新 Run |
| M4-08 | RunBudget 账本 | M4-06 | step/subtask/tool/evidence/time 硬预算；Token 预留接口 | 并发扣减、终局保留、耗尽测试 |
| M4-09 | IncidentBudget 账本 | M4-08 | generation/累计 LLM/Token/费用窗口预算 | 跨 Run 累计、窗口滚动、耗尽后不派生 |
| M4-10 | 统一 `rca_event` 迁移 | M4-02 | 唯一 append-only 事件表和 last_event_seq | 原子分段 seq、并发无重复/无倒退 IT |
| M4-11 | EventAppender | M4-10 | 状态事实同事务写；进度事件独立短事务 | 回滚一致性、重复 event digest 测试 |
| M4-12 | 旧事件兼容视图 | M4-10 | `rca_agent_event` 只读视图，不保留双写 | 视图结果与权威表一致；写入被拒 |

### 7.2 工具与证据底盘

| ID | 小任务 | 依赖 | 实现边界 | 单项验证 |
|---|---|---|---|---|
| M4-13 | ToolDefinition 契约 | M4-01 | name/version/schema/risk/timeout/result limit | schema hash 稳定、非法定义拒绝 |
| M4-14 | ToolRegistry | M4-13 | 启动期注册和冲突检测；禁止运行时网络下载插件 | 重名/版本冲突/未知工具测试 |
| M4-15 | canonical args 与 action digest | M4-13 | JSON 规范化、scope/time_range 纳入摘要 | 字段顺序无关、范围变化摘要必变 |
| M4-16 | ToolPolicy R0/R1 | M4-14、15 | 当前只允许读；R2/R3 意图仅记录不执行 | 权限矩阵正反测试 |
| M4-17 | ToolGateway 超时/取消 | M4-16 | 硬 deadline、结果上限、取消；不晚到补旧 Snapshot | WireMock timeout/cancel/oversize 测试 |
| M4-18 | 只读调用 Ledger Adapter | M4-17 | PENDING→SUCCESS/FAILED/UNKNOWN；唯一 operation_id | 网络断开、重复调用、UNKNOWN 测试 |
| M4-19 | Evidence 数据契约与仓储 | M4-10、18 | provenance、scope、time range、digest、generation | 篡改检测、跨 generation 拒绝 IT |
| M4-20 | EvidenceSnapshot Builder | M4-19 | 冻结排序、裁剪和 snapshot digest | 同事实同 digest；迟到证据不改变旧快照 |
| M4-21 | Claim 契约与仓储 | M4-19 | claim→evidence 引用；unsupported 不可验证通过 | 缺引用、冲突 scope、版本错误测试 |
| M4-22 | Claim Reducer | M4-21 | 规则驱动消重/冲突/覆盖；不做置信度投票 | 冲突、支持、反证、NEEDS_REVIEW 矩阵 |
| M4-23 | Report Assembler | M4-20～22 | 只从已冻结 Snapshot 和已裁决 Claim 组装 | 无证据不产确认根因；PARTIAL/UNRESOLVED 测试 |

### 7.3 多 Agent 与新旧对照

| ID | 小任务 | 依赖 | 实现边界 | 单项验证 |
|---|---|---|---|---|
| M4-24 | AgentProfile 契约和注册表 | M4-14 | 固定 prompt/tool allowlist/budget/schema；不可自由生 Agent | 未注册 Agent 拒绝；digest 稳定 |
| M4-25 | Planner 结构化输出 | M4-24、M4-05 | 只输出受限 DAG 提案，不执行工具 | JSON schema、环、未知任务类型测试 |
| M4-26 | Deterministic Supervisor | M4-25、M4-06 | Java 验证/落库/推进 DAG；模型无调度权 | 相同提案相同任务图；恢复测试 |
| M4-27 | Metrics Agent | M4-17、24 | 只做一种 R0 指标查询并产 EvidencePackage | replay fixture + WireMock/Prometheus 契约 |
| M4-28 | Logs Agent | M4-17、24 | 只做一种 R0 日志查询并产 EvidencePackage | 同上；与 Metrics 工具集隔离 |
| M4-29 | Change Agent | M4-17、24 | 只读变更记录；无写权限 | 无权限工具调用被控制面拒绝 |
| M4-30 | Native RCA Agent | M4-20～29 | 消费结构化黑板，提出 Claim；不直接发布报告 | 固定 Snapshot 回放可比较 |
| M4-31 | Holmes Baseline Adapter | M3-08、M4-20 | Holmes 输出转成相同 EvidencePackage/Claim 边界 | 相同输入契约，Adapter 失败可见 |
| M4-32 | REPLAY_MOCK 精确匹配 | M4-17、20 | tool/version/args/scope/time/snapshot 全相同才回放 | 任一字段不同返回 REPLAY_MISS |
| M4-33 | Agent Replay Runner | M4-30～32 | 候选模型可调用 mock 工具；统计覆盖率和 Token | 全匹配/部分回放/零覆盖测试 |
| M4-34 | Snapshot Shadow Router | M4-30、31 | Holmes/Native 读同一 Snapshot，互不影响生产结论 | 同 snapshot_digest、独立预算、Candidate 失败隔离 |
| M4-35 | Online Read Shadow 隔离 | M4-34 | 仅 R0/R1、独立 slot/限流；REDTEAM 物理禁入 | 网络/身份正反验证 |
| M4-36 | Incident Source Reconciler | M4-09、19 | 低频核对当前 alerts，只追加 Observation | 单次缺席不 resolved；多次+宽限窗才收敛 |
| M4-37 | 六类 Reconciler 预算统一件 | M4-18、36 | attempts/墙钟/积压年龄/退避/升级终态 | 每类耗尽后停止自循环并生成事件 |
| M4-38 | AM4 G2 | M4-37 | Native Shadow 全链路但不切主 | 崩溃恢复、预算、DAG、证据、对账、Holmes 隔离全绿 |

## 8. AM5：评测发布门与长期运维

> 目标：让“哪个版本能上线”由证据决定，并把人工、可观测性和数据生命周期收口。

| ID | 小任务 | 依赖 | 实现边界 | 单项验证 |
|---|---|---|---|---|
| M5-01 | DatasetVersion/CaseVersion 迁移 | M4-38 | insert-only 版本；不实现评分 | 历史不可覆盖、适用期测试 |
| M5-02 | TUNING/VALIDATION/HOLDOUT/REDTEAM 权限 | M5-01 | schema/RLS/角色物理隔离 | Agent/RAG 对 HOLDOUT/GT 查询为 0 |
| M5-03 | Golden Candidate 工作流 | M5-01 | 人工纠错只进候选，双人复核后发布 | 同人不能双签、拒绝/撤回/发布状态机 |
| M5-04 | 模型采样指纹 | M4-33 | temperature/top_p/seed 支持/provider fingerprint/trial | 缺字段不可进入正式门禁 |
| M5-05 | 配对重复试验与方差 | M5-04 | 重复次数、翻转率、bootstrap 区间 | 固定样例统计测试 |
| M5-06 | 六维 Evaluator | M5-02、05 | 结果/过程/工具/成本/协作/安全分别计算 | 每项原始计数可追溯到 event/evidence |
| M5-07 | 硬安全门 | M5-06 | schema、越权、注入、跨租户、写意图 | 任一失败 fail-closed |
| M5-08 | 质量门与运行门 | M5-06 | 阈值版本化，不用单一 F1 决定一切 | 小样本不自动放行；门禁解释完整 |
| M5-09 | ConfigBundle 发布/回滚 | M5-08 | Git 编辑源、PG immutable bundle、active pointer CAS | 老 Run 固定旧 digest；回滚不改历史行 |
| M5-10 | Canary Router | M5-09 | session/tenant 稳定分桶、爆炸半径上限 | 比例、黏性、立即回 Holmes 演练 |
| M5-11 | OperatorCase 状态机 | M4-37 | UNRESOLVED/NEEDS_REVIEW/预算耗尽幂等合并 | 连续三次、一小时聚集、SLA 升级测试 |
| M5-12 | Operator API 最小集 | M5-11 | list/claim/ack/resolve；先 API 后 UI | RBAC、并发认领、审计测试 |
| M5-13 | `rca_event` 查询/SSE | M4-10、11 | after_seq 回放、脱敏、背压；断线不取消 Run | 断线重连、慢客户端、权限、游标过期测试 |
| M5-14 | Cancel/Hint/Feedback 命令 | M5-12、13 | 先持久化再生效；Hint 是 UNTRUSTED | 幂等命令、旧 revision、越权测试 |
| M5-15 | 三支柱完整化 | M3-27～29 | Collector 独立出口、低基数指标、风险 trace 保留 | 遥测出口故障不影响业务状态 |
| M5-16 | 控制面防自噬路由 | M5-15 | RCA_SYSTEM 告警直达独立值班通道 | 合成控制面告警不创建 Incident/Run |
| M5-17 | 2C4G Gatus 外部探针 | M5-16 | 黑盒 health + canary 告警；不承载 RCA | 195/control 挂时仍能独立告警 |
| M5-18 | RetentionPolicy 与月分区 | M5-01 | 先按月 RANGE；租户用索引/RLS | 分区路由、跨月查询、legal hold 测试 |
| M5-19 | 冷归档 manifest | M5-18 | 导出、条数/digest 校验后 detach；失败不删热数据 | 损坏包、冷层不可用、恢复查询演练 |
| M5-20 | 历史假设检索 FTS 实验 | M5-02、06 | 只产 UNTRUSTED_HYPOTHESIS；默认关闭 | 跨租户/HOLDOUT 为 0；无收益保持关闭 |
| M5-21 | pgvector 可行性门 | M5-20 | 仅 FTS 不足且 A/B 有价值时实验 | recall/内存/延迟/备份数据齐全才接受 |
| M5-22 | AM5 G2 | M5-10～21 | Shadow→Canary 前正式评审 | 安全、质量、运行、人工、归档、回退演练全绿 |

## 9. AM6：逐步替换 HolmesGPT

| ID | 小任务 | 依赖 | 实现边界 | 单项验证 |
|---|---|---|---|---|
| M6-01 | 1% Native Canary | M5-22 | 只读 RCA；Holmes 保持回退 | 连续窗口门禁不劣、无安全事件 |
| M6-02 | 10% Native Canary | M6-01 | 扩大流量，不扩权限 | 预算、延迟、错误率和人工分歧受控 |
| M6-03 | 50% Native Canary | M6-02 | 验证容量和资源账 | HOST1 低水位/回退演练 |
| M6-04 | Native Primary | M6-03 | 新 Run 默认 Native，Holmes fallback | 一键切回、在途 Run 不换 engine digest |
| M6-05 | Holmes 只读对照期 | M6-04 | 不参与最终报告，只抽样 Shadow | 差异报告与成本收益确认 |
| M6-06 | Holmes 退场决策 | M6-05 | 删除前先证明无恢复依赖和专属数据契约 | 依赖扫描、灾备演练、回滚制品可用 |
| M6-07 | Holmes 生产路径下线 | M6-06 | 移除容器/密钥/路由；保留历史审计可读 | compose、密钥扫描、历史报告回放全绿 |

## 10. 并行规则

可以并行的只有无共享迁移、无共享核心类的任务。例如：

- M2 的正常流量发生器可与 Chaos 管理 API 并行；
- M3 的通知支线可与 LiteLLM spike 并行；
- M4 的不同只读 Agent 可在 ToolGateway/AgentProfile 冻结后并行；
- M5 的外部探针可与 Dataset 工作流并行。

禁止并行：

- 同一 Flyway 版本或同一状态枚举的两个任务；
- 状态迁移生产接线和仓储 CAS 语义同时由不同任务修改；
- Evidence/Claim 契约未冻结前并行开发 Native Agents；
- Quality Gate 未冻结前切 Canary；
- 在前一里程碑 G2 未过时启动依赖其真值/契约的下一里程碑。

## 11. 每个任务的状态机

```text
TODO
  -> READY              依赖已满足，验收样例已写清
  -> IN_PROGRESS        只允许一个负责人修改主边界
  -> CODE_COMPLETE      V0/V1 通过
  -> VERIFIED           所需 V2/V3 也真实执行通过
  -> REVIEWED           代码与规范双轴评审通过
  -> DONE               证据归档、进度总账更新

任一步失败 -> BLOCKED（记录原因和下一动作）
```

不能从 `CODE_COMPLETE` 直接跳 `DONE`。需要 Docker/195 的任务，环境不可用时必须停在 `CODE_COMPLETE`。

## 12. 证据目录约定

```text
docs/测试证据/
  G0/<task-id>/
  AM2/<task-id>/
  AM3/<task-id>/
  AM4/<task-id>/
  AM5/<task-id>/
  AM6/<task-id>/
```

每个目录至少包含：

```text
manifest.json          task_id、commit、环境、开始/结束时间、命令、退出码
checksums.sha256       所有证据文件摘要
test.log               可重放命令的完整输出
assertions.json        机器可读关键断言
before-after.sql       涉及状态/迁移时保存脱敏查询
*.png                  仅在 UI/部署/E2E 需要时提供
```

## 13. 第一批实际执行队列

现在只释放以下任务，不同时开 AM2：

```text
G0-01 基线 manifest
  ↓
G0-02 Webhook 配置键
  ↓
G0-03 Holmes key 桥接
  ├─ G0-04 报告权限自检
  └─ G0-05 Inbox 状态机
       ↓
     G0-06 Run 状态机
       ↓
     G0-07 Task 状态机
       ↓
     G0-08 PostgreSQL IT
       ↓
     G0-09 Alertmanager 配置
       ↓
     G0-10 E2E
       ↓
     G0-11 G2
```

第一刀应是 G0-01，不是直接修改业务代码。它建立可比较基线；随后 G0-02、G0-03 用配置契约测试关闭已知部署阻断。只有这三项完成，后续 E2E 的失败才不会被配置漂移污染。

## 14. 明确不进入本任务树的内容

- 不引入 Kubernetes、Helm、Redis、Kafka/RabbitMQ；
- 不恢复旧 PR review、publisher-app 或 sandbox-broker 业务；
- 不保存或展示模型私有 Thought；
- 不用相似告警直接复用历史根因；
- AM5 前不执行 R2/R3 写动作；
- 不把 2C4G 机器描述成灾备或生产温备；
- 不为了追求任务完成率而跳过 Testcontainers/E2E；
- 不在实现阶段继续添加未经评审的新架构组件。

## 15. 任务完成后的总体验收

整个任务树完成不以“所有 checkbox 勾完”为准，而必须同时满足：

1. 任意告警能追溯到 Intake、Incident、Run、Task、Attempt、Evidence、Claim、Report 和 Publication；
2. 同一 Incident 的重复、乱序、rerun、预算耗尽和 resolved 丢失都有确定性路径；
3. Holmes 与 Native 使用同一冻结输入和输出契约，差异可计算；
4. Native 只有通过 HOLDOUT、安全门、运行门、Canary 和人工批准后才能成为 Primary；
5. 写动作默认不存在；未来启用时也必须由策略、审批和独立 Action Runner 控制；
6. 日志、指标、Trace、审计、对账和冷热归档均能独立证明系统发生过什么；
7. 停掉 Holmes 后历史报告仍可读、在途任务有明确处置、回退制品仍可恢复。

