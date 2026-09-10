# 告警-执行者改造技术方案 v2.0（A=Harness 地基 / B=真实工具 / C=产品与工程面）

> v2.0（2026-09-09 深夜，主会话）：按 `docs/告警-ABC与R7方案评审意见.md` 全面修订——P1-01~08 全采纳；A4 拆 A0/A4a/A4b 并前移；删除"无回执按幂等键重驱"；结果提交入所有权事务；认证/通知最小面前移；工期重核。
> 执行方式：A/B/C **一个执行者**按 §〇 卡序执行，主会话每日 21:43 检查点跟踪。
> 纪律：①每卡按 §六 统一模板交付；②`mvn test` 绿不算完——预算原子性/PG 竞争/事务回滚必须有具名 IT 并在支持数据库容器的环境跑 `mvn verify`（根 pom.xml:71~84 failsafe）；kill/restart 与真实模型验收另留证据；③新发现缺陷登记 BUGLOG（BA-64 起）；④迁移 SQL 编号以 rebase 时下一可用号为准。

---

## 〇、执行顺序与总工期（单执行者，v2.0 重核）

| 序 | 卡 | 内容 | 工期 | 门禁 |
|---|---|---|---|---|
| 1 | EX-A0 | 身份与执行契约冻结（F04/F14 三 digest/冻结时间窗/持久 attempt+call_seq/预算与结果提交接口） | 2~3d | 一切后续卡的契约前置 |
| 2 | EX-A1 | F15 预算接线（按 P1-02 真实 API 与多维准入） | 2~3d | 里程碑 A 硬前置 |
| 3 | EX-A2 | F10/F11/F12 租约·心跳·取消 + 提交栅栏（P1-04） | 2~3d | 里程碑 A/C |
| 4 | EX-A4a | 必要正确性：F05 快照成员/F13 活跃集合/F16 调用账本/F17 有界读取+F06 存储与快照契约 | 3~4d | **真实 LLM A 门之前必须绿** |
| 5 | EX-A3 | F08/F09 可恢复驱动（P1-03 四阶段恢复语义） | 2~3d | A 门 |
| 6 | EX-B1 | change_event 真实变更源（与生效同事务） | 2~3d | B 门硬前置（不阻塞 A 门） |
| 7 | EX-B2 | logs 真实源+LogQueryExecutor（含盘点门） | 4~7d | B 门硬前置 |
| 8 | EX-A4b | 剩余收口：F18/F19/F23/F24 | 3~4d | C 门 |
| 9 | EX-C3a | 认证最小闭环（先于一切生产写界面） | 3~4d | D 门；C1 生产 CRUD 的前置 |
| 10 | EX-C2a | 外部通知最小腿（应急通道+业务码+持久重试+断链演练） | 2~3d | 解除 Gatus 被页面工期阻塞 |
| 11 | EX-C1 | 前端三全局阻断+去 mock | 2~3d | D 门 |
| 12 | EX-C2 | AM7 完整产品面（含评审补六契约） | 6~9d | D 门 |
| 13 | EX-C4 | Gatus 部署+断链演练（MIG-02） | 1~2d | 依赖 EX-C2a（不再等 C2 全量） |

**合计 34~51 净开发日（v2.0 重核，v1.0 的 29~45 作废）**。每周 5 个净开发日，执行者单线 6.8~10.2 周——与主会话（评测拆管线+R7）形成两条开发线，**整体以 8~10 周为讨论用计划窗口，不作交付承诺**；A0/A1 完成与 B2 盘点门签字后按实际吞吐重估。

依赖逻辑（评审 §4.2 落卡）：A0 冻结契约 → A1/A2+A4a → A3 →（主会话 R7 适配并行）→ **A 门=单指标 Agent 真实模型闭环**（B1/B2 不阻塞首次看到真实模型决策）→ B1/B2 → R7b/c 三角色+补证 round → **B 门** → A4b+并发恢复矩阵 → **C 门**（认证/通知最小面按资源前移并行）→ AM7 完整面/真实前端/恢复演练 → **D 门**。

---

## 一、A 线：Harness 正确性地基

### EX-A0 · 身份与执行契约冻结（2~3d，**最先行**）

**为什么是第一卡**：F04/F14 决定调查的配置、输入、时间窗身份；A1 的预算预留键、A3 的恢复语义、R7 的模型上下文全都建立在这套契约上——契约后补=全线返工（评审 P1-01）。

**改什么**：
1. **三 digest 身份**（F04）：`config_digest`（配置版本）/ `investigation_input_digest`（调查输入：incident episode+告警窗口+服务范围+查询参数）/ `evidence_snapshot_digest`（输出证据集）。分列命名与持久化，消灭 `NativeInvestigationExecutor.java:160-161` 把 configDigest 当 inputSnapshotDigest 传、`NativeRcaAgent.java:62-64` 拿输入摘要比对输出快照的混用。F23 的新身份格式（canonical JSON/长度前缀）**在本卡一次定好**，不留给 A4b。
2. **冻结时间窗**（F14）：Run 创建时冻结服务范围/告警时间窗/配置 digest；执行期禁止静默改取"执行时最近十分钟"（`NativeInvestigationExecutor.java:208-218,232` 按 Run 冻结 digest 读提案，不查当前 activeDigest）。"现在是否恢复"作为新的显式动作，不移动原调查窗口。
3. **持久动作身份**：attempt/call_seq/action_seq 持久化（当前 attemptId 由 driver 随机创建无持久生命周期，F16 同源）；逻辑动作 vs 物理请求区分写进契约：同动作读已完成回执可复用身份，**新物理请求必须新身份单独计费**。
4. **预算与结果提交接口契约**：A1 的预留键结构（`ReservationKey` 现有字段 run/task/attempt/callSeq/budgetKind 直接沿用）、A2 的提交事务边界、R7 的 `ActionBudgetContext`——写成接口文档，三方签字后各自落码。

**验收**：契约文档+迁移（列级）；同一 JVM 测试中三 digest 各司其职、混用被类型拒绝；旧 Live E2E 行为零变化。

### EX-A1 · F15 预算接线（2~3d，按 P1-02 修订）

**现状**：`RunBudgetGate.java` 公开方法是 `call(ReservationKey, estimateUnits, remote, usageExtractor)`——v1.0 写的 `reserve(runId,costClass)` 是拟议接口，已更正。`ReservationKey` 含 run/task/attempt/callSeq/budgetKind，直接复用。装配断点同 v1.0（`PersistenceConfig.java:121` 注释自述待接线）。

**怎么改**：
1. 装配 `RunBudgetGate`/`DoomLoopGuard` Bean（接 `RunBudgetLedger`），消费点=`NativeInvestigationExecutor.java:230-248` 驱动循环**与未来 R7 模型/工具统一执行入口**——**全系统只有一个预算所有者**，A1 与 R7 不得双重扣账（R7 经 `ActionBudgetContext` 复用本卡）。
2. 多维预算一次准入全部成功，或部分预留失败时显式撤销已预留——不允许只消费工具次数就声称五维（工具次数/模型次数/token/费用/总时长）生效。
3. 每个**物理**重试单独预留、单独计费；同动作恢复读取已完成结果不再触网（接 A0 动作身份契约）。
4. UNKNOWN（请求已发出结果未知）保守占用待对账，不免费重发。

**验收**（评审 P1-02 全场景，具名 IT）：工具额度够但 token 不够→拒；两任务争最后一份预算→一得一拒；预留成功但调用记录写失败→预留撤销可核；模型返回结果但缺 usage→UNKNOWN 落账不猜零；同动作恢复读结果→零触网；真正重发→另记成本。

### EX-A2 · F10/F11/F12 租约·心跳·取消+提交栅栏（2~3d，按 P1-04 修订）

**怎么改**（v1.0 基础上三处升级）：
1. F10 原子 `reclaimExpired` 同 v1.0（四条件+命中 0 行=竞态失败；对照同文件 `requireCurrentLease` 105-113 已有纪律）。
2. F11 续租/失租中断同 v1.0，但**两次 LeaseFence 检查不够**（P1-04）：**结果的业务准入、任务状态、事件提交，必须放在受 owner/epoch/generation/Run 状态保护的同一事务内**——锁定所有权行后校验并写入，或等价条件写；影响行数=0 明确为失去提交权。远端迟到响应可作审计/对账资料保存，**不得进入有效证据快照**。
3. F12：`PostgresRcaRunRepository` 已暴露 `last_event_seq`——`updateIfRevision` 必须映射到现有命令版本机制，**不新造不相干 revision 列**；取消声明线性化点：取消事务成功后不得取得新动作发送资格；已获资格/在飞请求尽力取消，晚到结果隔离、费用仍对账；**不承诺 HTTP 请求与取消事务绝对同时**。
4. LeaseFence 作为独立类沉淀（R7 ActionGuard 的复用件）。

**验收**：两连接 barrier IT（A 回收/B 领取/A 提交=0 行）；失租 worker 结果写入=0 行+证据快照不含其结果；Cancel 与报告完成并发竞态结果一致；取消后零新动作资格。

### EX-A4a · 必要正确性四项+F06 契约（3~4d，**真实 LLM A 门之前必须绿**）

| 项 | 改什么 | 要点 |
|---|---|---|
| F05 | `NativeRcaAgent.java:61` | 按 snapshot 成员表精确读取+完整性校验；缺成员=明确失败/不足；旧快照**不可追加成员**（P1-05 联动） |
| F13 | `PostgresRcaRunRepository.java:126-134` | 活跃集合补 REPORTING；Java/SQL/V12 唯一索引统一+PG 集成用例 |
| F16 | `SingleToolEvidenceAgent.java:104-137` | 调用账本缝隙：catch 覆盖全段、先持久结果引用再 ledger.succeed、attempt 持久生命周期、恢复扫描补 PENDING 回收；UNKNOWN 语义对齐 EX-A0 契约 |
| F17 | `PrometheusQueryExecutor.java:64`、`ToolGateway`、`AlertAm4Config.java:162` | 有界流读 resultLimit+1 超限即断；bounded queue/bulkhead 满则明确拒绝；schema 加时间窗/step/序列数约束 |
| F06 契约 | `ReportAssembler.java:~79` 相关存储面 | A 线只交 Claim 类型存储与快照契约（四类型枚举+引用结构）；**类型准入与转换逻辑归 R7c 单一责任人**，双方不各写一套 Claim 转换（评审 P1-01 分工） |

### EX-A3 · F08/F09 可恢复驱动（2~3d，按 P1-03 重写恢复语义）

驱动循环与所有权同 v1.0（"推进→领取 READY→执行→回执→再推进"至终态；driver 独占；编译/分派注册契约统一）。**恢复语义整段替换——删除"无回执按幂等键重驱"**：

| 动作阶段 | 恢复行为 |
|---|---|
| 尚未取得发送资格 | 新 driver 重新核验预算/租约/取消后决定执行 |
| 已取得发送资格，结果未知 | 标记 UNKNOWN，**保留预算占用**；按供应商能力查询/对账，不默认免费重发 |
| 结果已落库、任务未完成 | 从 result_ref 恢复，幂等提交任务，不再触网 |
| 任务与结果已提交 | 重放读取既有结论 |

**checkpoint 最少字段**（评审 P1-03）：run/round/task/attempt/action_seq、request_digest、reservation_id、dispatch 状态、result_ref、driver_epoch——可复用现有表，但**必须列出实际新增字段与迁移，不为"不加表"省略必要持久信息**。网络与数据库之间不存在消除全部不确定窗口的本地事务；只读工具再查须约束原始时间窗并留新观察记录；模型有限重试须承认可能重复收费并预留新物理请求预算；未来写工具另需远端幂等契约。

**验收**：四边界杀进程（任务开始/请求已发出=UNKNOWN 保留占用/结果已写/已提交）恢复行为逐格命中上表；无永久 RUNNING；恢复不重复调用、不重复收费（账本可证）。

### EX-A4b · 剩余收口（3~4d，C 门之前）

F18（背压不阻恢复事件/材料变化不被去重吞）、F19（INSERT ON CONFLICT+锁行或保存点，PG 并发实测）、F24（WAITING_CAPABILITY/DEFERRED 显式态+重驱+前端可见）。F23 身份格式已在 EX-A0 一次定好，本卡只做存量迁移兼容。每项复现测试翻转为契约断言。

---

## 二、B 线：真实工具数据面（B 门硬前置，不阻塞 A 门）

### EX-B1 · change_event（2~3d，按评审修订"同事务"）

v1.0 基础上两处升级：
1. **配置激活/回滚事实与 pointer CAS 放同一事务**（`ConfigBundleService.movePointer()` 经 repository.activate 完成激活；控制器事后写事件失败=配置生效但变更证据缺失），或事务内写 outbox 再投影 change_event；CAS 败者与幂等重放**不产生新"生效"事件**。append-only 表本身不解决采集完整性。
2. 部署脚本记录 deployment_id/目标 digest/实际生效时间/成功/失败/回滚；**先核对当前实际部署入口全清单**（不得只挂 deploy-am4.sh 一处）；Agent 保持只读。
其余同 v1.0：迁移编号 rebase 冻结、写角色与读角色分离、`ChangeQueryExecutor`（allowlist/≤15min 窗/limit 200/流式截断/NO_DATA）、`AlertAm4Config.java:127-129` 换绑、fixture 迁 test/replay profile。

### EX-B2 · logs 真实源（4~7d，按评审修订）

1. **盘点门升级**：追到 checkout 等**业务服务**的日志源/collector/存储/查询 API，不只验证 control collector 自己有日志；无数据区分 EMPTY / SOURCE_UNAVAILABLE / QUERY_FAILED，禁止 fixture 顶替。盘点表主会话签字后才写 executor。
2. **不预设实现**：Loki ≤512MiB 写成**试验资源上限与准入条件**而非容量承诺；官方当前推荐 `otlphttp` exporter 向 Loki 原生 OTLP 入口发送（评审所引 Grafana 官方文档），**不直接指定旧 loki exporter**，最终按现有镜像版本验证；PG 全文兜底须核算采集/索引/保留清理/主库竞争成本，"已有 PG"≠最轻；优先复用已可查询来源，缺来源显式降级。
3. executor 契约与换绑同 v1.0（统一 schema/allowlist/≤15min/limit 200/resultLimit+1 流式截断/超大慢 401 429 5xx 半包中断各有确定结局；docker production profile 不得注册 ReplayToolExecutor 为 logs/change——Phase 3 验收门第 1 条）。

---

## 三、C 线：产品与工程面（最小面前移版）

### EX-C3a · 认证最小闭环（3~4d，**先于一切生产写界面**）

评审口径：开发联调可临时隔离，但"手工配发 bearer 放浏览器+自报 X-Operator-Id"不能作为生产验收状态（v1.0 该过渡方案作废）。
1. **先选清机制**：服务端会话 **或** 标准 token（JWT 一类成熟实现），二选一——禁止自建"token 表+HMAC"模糊协议；沿 Spring 栈成熟机制做。
2. `AuthnFilter extends OncePerRequestFilter` 统一验签，替换 5 个 Controller 手抄 `authorized()`；actor 从认证上下文生成，**忽略客户端 X-Operator-Id**；机器凭证与浏览器用户身份分离。
3. 注销/过期/读写权限（operator vs release 角色）首版即含；审计结构化落表；单人运维如实标单人授权。
4. LoginView 接真实登录端点；mock 登录删除。

### EX-C2a · 外部通知最小腿（2~3d，从 AM7 拆出前移）

固定应急通道（1~2 个值班 webhook）+ 业务码判定（F20：HTTP 200+errcode≠0 不标 SENT；UNKNOWN 记录语义+是否允许重复+审计——评审 AM7 契约 2）+ 持久重试（最长通知期限，不无限退避）+ 195 断链演练（Gatus 先行依赖此腿，不再被排班 CRUD/消息列表工期阻塞）。回执语义写明：机器人接收≠值班员阅读。

### EX-C1 · 前端三全局阻断（2~3d）

同 v1.0（vite.config.js:10 删 rewrite——**修路由无需等待认证**；client.js 拦截器+方法扩展；docker profile 联调 runbook；nginx 配置补入库；按 P4 盘点逐端点去 mock）。**生产 CRUD 页面开放在 EX-C3a 之后**（评审：身份最小闭环先于生产写界面）。

### EX-C2 · AM7 完整产品面（6~9d，**工期三处统一为 6~9**：AM7 §10 的 3~4 与主计划 4~6/5~8 作废）

按 AM7 方案执行，并补评审六契约：①事件身份加资源指纹+稳定 episode 标识（分钟桶会合并不同实例故障；firing/resolved 关系明确，恢复通知不被故障通知去重）；②UNKNOWN 送达语义+重复策略+审计（epoch 不能撤回群消息）；③降级并发唯一键+条件推进+最长通知期限；④已读≠接单（READ 不冒充 ACK）；⑤快照陈旧语义（schedule_version/有效区间/首启无快照/损坏过期 fallback+持久排班规则与独立应急通道）；⑥探针自身故障盲区（同机 adapter 循环依赖——独立心跳接收/死信号通道或明确可用性承诺边界）。§3 时序图补两条腿故障边界说明。

### EX-C4 · Gatus 部署+断链演练（1~2d，依赖 EX-C2a 而非 C2 全量）

同 v1.0（Phase 1 修正版配置、digest pin、六项验收+MIG-02 断链演练；审计 §六.5 custom body 非法 JSON 改安全编码）。

---

## 四、R6 规则统一（评审 §4.2 落锤）

主计划 §三"R6 G2 前不得写 R7 实现"与 §8.5"R6 与 A/B 并行、B 后启用"矛盾，统一为一条：**R6 分两层——开发验证用最小无泄漏回放契约尽早提供（线1 M1/M2 交付即够用）；完整盲评（秘密 HOLDOUT+三臂 MATCHED）是质量结论与发布晋升的硬前置，不阻塞 R7 落码与 A/B 门验收。** 主计划 §三/§8.5 以此为准修订。

## 五、范围两分层（评审 §4.3 落锤）

- **核心生产闭环**（本方案+主会话线1/R7）：真实智能、持久执行、安全边界、真实通知、恢复演练、可量化 SLO。
- **后续增强**：RAG/Skill/MCP/额外 HA/容量能力。
- HA 口径：暂不交付主库 HA，则如实声明单主故障的 RPO/RTO 与恢复步骤，**不叫完整高可用**；容量验收须选定合理入口速率/并发调查上限/排队时延（用户不要求百万告警，但要有数）。

## 六、统一任务卡模板（评审 §5，每卡必填）

基准 commit+文件+方法名；前置输入契约；事务/锁边界；状态转换及拒绝原因；持久身份；正常/崩溃/竞争测试（具名 IT，`mvn verify`）；生产配置迁移与回滚步骤；完成证据（目录+关键输出）。行号仅辅助定位。**每卡完成证据必须按 §七 标明测试层与环境，缺层视同未完成。**

## 七、测试环境分层与防假绿纪律（2026-09-09 补，BA-61~64 四连假绿立法）

### 7.1 环境现状（写死，变化必须回本节）

| 机 | 角色 | 现状（2026-09-09 核实） |
|---|---|---|
| 195（146.56.195.225） | **唯一全栈真机**：OTel 靶场、control-app、notify-app、postgres、prometheus-am0、alertmanager-am0、litellm、order-arena/chaos-admin、eval 驱动 | 内存紧张（available ~3.3GiB），磁盘已清至 55% |
| 127（117.72.208.68） | 评测迁移目标机 | **只有 node-exporter + /srv/alert-eval 空目录树**（MIG-01 备好 UID/目录，15/15+4/4 PASS），无任何评测服务在跑；3.7G 内存/35G 余盘 |
| 开发机（本地） | 构建与 L0/L1 | Windows+mvn |

### 7.2 分层测试矩阵

| 层 | 跑在哪 | 内容 | 通过标准 |
|---|---|---|---|
| L0 单元 | 开发机 | `mvn test` | 绿且不缩断言圈 |
| L1 集成 IT | 开发机/构建环境 | `mvn verify`（failsafe 具名 IT：真 PG/事务/竞争/崩溃） | 卡面列名 IT 全绿；任何跳过必须明示理由 |
| L2 真机 E2E | **只能 195**（靶场/告警链/PG 全在 195） | 部署后全链行为：webhook→incident→RCA→报告→通知 | 真实行为证据：DB 行变化、日志、指标真值回读、真实请求回执 |
| L3 评测批 | 现状 195；MIG-02 迁移后 127 | eval-runner 批量 | 四指标+逐 Case 表+usage ledger 对账 |

- EX 线每卡交付 = L0/L1 绿 +（涉及部署的卡）L2 真机证据；**127 不出现在 EX 卡验收面**。
- 127 的验收只属于评测迁移任务（MIG-02+），迁移完成的定义 = **195+127 联合把全流程走一遍**（用户裁定），不是"文件传完/目录备好"。

### 7.3 防假绿纪律（每条有 BUGLOG 出处，违反即登记新 BA）

1. 容器内文件视图以 `docker exec cat | md5sum` 流为权威；**禁 `docker cp` 对拍 ro bind mount**（BA-62：docker cp 返回宿主源文件内容）。
2. mount 审计覆盖**目录挂载内容**：非空断言 + 以容器运行身份逐文件可读（BA-64：secrets 空目录审计漏面致通知链断 3 小时）。
3. 配置同步 = 文件落盘 + **受影响容器重启清单** + 审计零 DIVERGED（BA-61/63：单文件 bind mount inode 定格，不重启=不变更）。
4. 部署门禁含**出口行为断言**：notify 无失败日志、指标/DB 行真值回读；**"容器 up、健康检查 200、进程在跑"一律不算绿**。
5. 批 `state=SUCCEEDED` ≠ 绿：必须核对 coverage/tp/fn 与 incident/rca_run/rca_report 底数变化（glm5-full-0909：SUCCEEDED 但四指标全 0）。
6. 测试不预灌答案、不缩断言圈、不为过率改阈值；发现假绿当场登记 BUGLOG 并转化为可执行门禁断言。
