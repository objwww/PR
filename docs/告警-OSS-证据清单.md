# 告警 Agent 线 OSS 证据清单 v1 —— 承重决策的开源先例与核查记录

> **用途**：告警 Agent 项目（AM0 起）的证据基线。旧 PR-Agent 线 `OSS-证据清单-v1.md` 已归档（`docs/archive/pr-agent-line-20260904.tar.gz`），本清单是唯一在册证据清单。
> **标注规则**：【明示】= 官方文档/源码/issue 直接写明；【推断】= 基于明示材料的同构性判断；【实测】= 本项目在 195 服务器/本机真实执行验证。
> **核对日期**：除另注外均为 2026-09-03。
> **来源线程**：E1=Sloth/SLO（用户外部调研粘贴，主会话抽核）；E2=Alertmanager（调度层调研附件，源码核查）；E3=HolmesGPT/Robusta（调度层调研附件，源码核查）；E4=Keep（部署调研 + 调度层调研）；E5=调度内核对比（调度层调研附件）；E6=OTel Demo（主会话官方仓库直查）；E7=HertzBeat（用户外部调研粘贴，待交叉核对）；E8=夜莺（用户外部调研粘贴，待交叉核对）；E9=Coroot（主会话官方文档直查）；E10=落选候选。

---

## E-1 Sloth（slok/sloth）——SLO-as-code 规则生成【采纳：AM0 告警规则生成器】

- `sloth generate` CLI 一次性生成完整 Prometheus recording rules + multi-window burn-rate 告警（page/ticket 双窗），生成结果含 5m/30m/1h/2h/6h/1d/3d 多窗口 error ratio 与 error budget 序列。
  来源：https://github.com/slok/sloth/blob/main/examples/_gen/getting-started.yml 【明示】
- Release 提供 linux-amd64 独立 binary——可不上服务器，本地生成规则文件 scp 部署，运行时零开销。
  来源：https://github.com/slok/sloth/releases 【明示】
- **适配性判断**：适合——声明/生成解耦，未来迁交易域只需改 SLO 声明（订单可用性/履约延迟），生成数学不变；与本项目"不手写易错告警数学"诉求一致。
- **引入代价**：SLO 声明中指标名依赖 OTel 语义约定版本，靶场起栈后需实测校正（已入 AM0-T05 验收）。

## E-2 Alertmanager 聚合语义【采纳：AM0 告警分组/去重】

- 聚合键 `aggrGroup`/`group_by` + `group_wait/group_interval/repeat_interval`；通知去重靠 `nflog.Entry` + `DedupStage`——重启后不重复通知已发告警。
  来源：prometheus/alertmanager 源码 `dispatch/dispatch.go`、`notify/dedup_stage.go`、`nflog/nflog.go`（调度层调研附件源码核查）【明示】
- **聚合键绝不能含 startsAt/endsAt/value/description 等瞬态标签**，否则一次事故拆成大量通知（→ INV-AM0-4）。
  来源：同上源码语义 + 调研结论 【明示+推断】
- `group_wait` 语义：首条告警到达开始等待，期间同组事件合并；持续告警不得反复后推聚合窗口。
  来源：同上 【明示】

## E-3 HolmesGPT / Robusta —— 调度与 RCA 机制（AM0 部署 HolmesGPT；AM1 设计母本）

- HolmesGPT Worker：对话先落库 pending，按空闲槽原子 claim 置 running（`claim_n_pending_conversations`）；对话池默认并发 5 与工具调用池默认并发 10 **分离**；生命周期 `pending→running→completed/failed/stopped/timeout`；`conversation_id + request_sequence` 拒绝旧 Worker 晚到回写。
  来源：holmes/core/conversations_worker/worker.py、tool_call_worker.py、models.py、supabase_dal.py（调度层调研源码核查）【明示】
- HolmesGPT 缺口：无优先级（oldest first）、无步骤级恢复（重启时在途任务标 timeout）——AM4 Java 替换的正当性论据。
  来源：同上 【明示】
- HolmesGPT 接 OpenAI 兼容端点：`OPENAI_API_BASE` + `OPENAI_API_KEY` + model 格式 `openai/<model>`。
  来源：https://holmesgpt.dev/ai-providers/openai-compatible/ 【明示】
- HolmesGPT 官方镜像仅在 GAR（`us-central1-docker.pkg.dev/genuine-flight-317411/devel/holmes`）；备选：GitHub Release `holmes-linux-amd64` 独立二进制（约 150MB）或 `pipx install holmesgpt`。
  来源：官方 docker-compose 与 GitHub Releases（部署调研核查）【明示】
- Robusta 反面教训：有界内存队列（maxsize=500）满时内部拒绝但 HTTP 入口仍可能返回成功——告警入口必须落库成功才返回 202，满了明确 429/503。
  来源：robusta src/robusta/utils/task_queue.py、runner/web.py（源码核查）【明示】

## E-4 Keep（keephq/keep）——汇聚层首选被否的证据【AM0 拒绝】

- 官方镜像仅发布在 GAR（`us-central1-docker.pkg.dev/keephq/keep/keep-api`、`keep-ui`），docker.io 无官方渠道。
  来源：部署调研核查官方 compose 【明示】+ 195 实测 GAR 不可达【实测】
- 开源版全自动 AI 关联（AI Correlation）⛔ 仅 Cloud/企业版；开源可用：AI 工作流步骤、接入/去重/规则关联/Incident/工作流。
  来源：https://docs.keephq.dev（部署调研核查）【明示】
- 告警事件工作流为进程内 list + ThreadPoolExecutor(20)，源码注释 "event workflows should be in DB"，Redis/ARQ 队列整段注释标 TODO——调度可靠性不足。
  来源：keep/workflowmanager/workflowscheduler.py、workflowmanager.py（源码核查）【明示】
- fingerprint（同一问题）vs alert_hash（内容是否变化）双哈希去重；Alert（历史）/LastAlert（当前态）分表——AM1 自实现 Incident 聚合时的设计母本。
  来源：keep/api/alert_deduplicator/alert_deduplicator.py、models/db/alert.py（源码核查）【明示】

## E-5 调度内核对比（AM1 调度层改造的依据）

- DBOS（Java）PG 队列核心：`SELECT ... WHERE status='ENQUEUED' ORDER BY priority ASC, created_at ASC FOR UPDATE SKIP LOCKED LIMIT ?`，claim 与选中同事务；优先级数值越小越高，**未设 priority 反而最高**（必须全员显式赋值）；步骤检查点 `operation_outputs` + `ON CONFLICT DO NOTHING`。
  来源：dbos-transact-java QueuesDAO/StepsDAO、docs.dbos.dev/java/tutorials/queue-tutorial（源码+文档核查）【明示】
- Temporal：Task Queue priority/fairness 在部分 SDK 仍标实验性；Event History 追加账本 + 重放恢复（本项目 execution_event 已有同构）。
  来源：docs.temporal.io、sdk-python worker/_interceptor.py（核查）【明示】
- Conductor Isolation Groups：任务类型+namespace+isolation group 编码为独立队列与线程池——AM1 执行池隔离的设计先例。
  来源：conductor-oss 官方文档 isolationgroups.html（核查）【明示】
- 调研总结论：Keep/HolmesGPT/Robusta 均未同时做好并发隔离+优先级+异步恢复；自研薄调度层（PG 任务表 + SKIP LOCKED + 租约）是正确取舍——**本仓库 control-app 的 WorkItemWorker 已是该形态**。
  来源：调度层调研附件总结论 【明示（针对三项目源码核查部分）+推断（取舍判断）】

## E-6 OpenTelemetry Demo（Astronomy Shop）——靶场【采纳：AM0 告警源】

- 分层 compose：`compose.yaml`（core/minimal）、`compose.full.yaml`（+Kafka/accounting/fraud）、`compose.observability.yaml`（+Jaeger/Prometheus/OpenSearch/Grafana/OpAMP）——AM0 只用 core + 裁取自 observability 的 collector Prometheus 导出配置。
  来源：https://github.com/open-telemetry/opentelemetry-demo/blob/main/compose.yaml（主会话直查）【明示】
- 主镜像 `ghcr.io/open-telemetry/demo:${DEMO_VERSION}-<service>`（`DEMO_VERSION=latest`，`IMAGE_VERSION=3.0.0`）；flagd/valkey 在 ghcr；jaeger/prometheus 在 quay.io；grafana/postgres 在 docker.io。
  来源：repo 根 `.env`（主会话直查）【明示】
- core 层内存 limits 加总约 3.9G，大头为预留：load-generator 1.5G（LOCUST_USERS 默认 5）、recommendation 500M（cache flag 场景）、otel-collector 400M——实测 RSS 预期 1.5~2.5G，AM0-T03 以 `docker stats` 为准。
  来源：compose.yaml deploy.resources.limits（主会话直查）【明示】+ RSS 预期【推断】
- 故障注入：内置 feature flags（flagd），含 paymentFailure（可配失败比例）/paymentUnreachable 等；官方清单以 `src/flagd/demo.flagd.json` 为准。
  来源：https://github.com/open-telemetry/opentelemetry-demo/blob/main/src/flagd/demo.flagd.json 【明示】
- OTel HTTP 指标语义：`http.server.request.duration` 带 `http.response.status_code`/`http.route` 等 attribute——SLO total/error 表达式的依据（具体指标名以起栈实测为准）。
  来源：https://opentelemetry.io/docs/specs/semconv/http/http-metrics/ 【明示】
- 已知坑：profiling 模式（eBPF profiler）对内核要求高，内核 3.10 禁用 profiling【推断】；otel-collector 挂 docker.sock（只读）属既有设计，知悉即可。

## E-7 HertzBeat（apache/hertzbeat）——汇聚层首选【采纳：AM0 A/B 主候选，待 T02 实证裁定】

- 外部告警接入：支持作为 Alertmanager 下游接收告警，也可直接接 Prometheus 告警（替代 Alertmanager 角色）。
  来源：https://hertzbeat.apache.org/zh-cn/docs/help/alert_integration/ 【明示，待交叉核对】
- 分组收敛内置：group labels、group wait（默认 30s）、group interval（默认 5m）、repeat interval（默认 4h）、时间窗去重——语义同构 Alertmanager aggregation group。
  来源：https://hertzbeat.apache.org/zh-cn/docs/help/alarm_group/ 【明示，待交叉核对】
- 告警抑制开源可用：主告警（如 Host Down）抑制二级告警（Redis Down 等）。
  来源：https://hertzbeat.apache.org/zh-cn/docs/help/alarm_inhibit/ 【明示，待交叉核对】
- 部署：docker.io `apache/hertzbeat` 单容器，内置存储无需外部 DB/TSDB；无 eBPF/内核门槛（agentless，走 HTTP/SSH/JMX/JDBC/SNMP/Prometheus 协议）；默认端口 1157，默认账号 admin/hertzbeat（部署后必须改）。
  来源：https://hertzbeat.apache.org/zh-cn/docs/start/docker-deploy/ 【明示，待交叉核对】
- 自带 MCP Server（`/api/mcp`），含 query_monitors/query_alerts/query_realtime_metrics/get_historical_metrics 等只读工具——后置作为 Agent 证据源（AM2+ 评估）。
  来源：https://hertzbeat.apache.org/zh-cn/docs/help/mcp_server/ 【明示，待交叉核对】
- **未核实项**：JVM 稳态 RSS（官方无数据，T02 实测，出局线 800M）；当前最新版本号与文档漂移度。

## E-8 夜莺 Nightingale（ccfos/nightingale）——出局【2026-09-03 交叉核查后降级】

- V8 存储支持 mysql/postgres/sqlite，快速体验默认 SQLite + miniredis，单二进制即可运行；官方首推二进制安装。
  来源：https://flashcat.cloud/docs/content/flashcat-monitor/nightingale-v8/install/configuration/ 【明示，待交叉核对】
- **聚合层角色出局（交叉核查推翻外部调研结论）**：夜莺是"数据源集成 + 自家规则评估"的告警引擎——拉 Prometheus `/api/v1/query` 自己判告警，**无 Alertmanager webhook 入站接收能力**；告警聚合/抑制在商业版。作为"告警汇聚/Incident 层"不成立。
  来源：交叉调研复核 + 公开资料检索（2026-09-03，夜莺全部集成为出站 callback/通知，无入站 AM 接收）【明示（引擎定位）+推断（无入站能力的反证检索）】
- PG 模式官方建议优先 MySQL（缺 PG 长期贡献者）；v8.5.1 曾出 PG migration 混入 MySQL mediumtext 致初始化失败（issue #3101）。
  来源：https://github.com/ccfos/nightingale/issues/3101 【明示】

## E-11 Alerta（alerta/alerta）——汇聚层 A/B 候选 1【采纳：AM0 A/B】

- 内置 Alertmanager webhook 接收端点 `/api/webhooks/prometheus`（源码在库：`alerta/webhooks/prometheus.py`，主会话 2026-09-03 直查 GitHub 确认存在）。
  来源：https://github.com/alerta/alerta/blob/master/alerta/webhooks/prometheus.py 【明示】
- 官方镜像 docker.io `alerta/alerta-web` 单容器（API+UI），在架、pull 数百万级；Apache-2.0；v9.1.0（2026-03）维护活跃。
  来源：Docker Hub 页面（交叉调研核查）【明示】
- PostgreSQL 为一等存储（官方明示新功能先测 PG）——符合本项目 PG 惯例（A/B 期仍用一次性 PG 容器，不碰存量实例）。
  来源：交叉调研核查官方文档 【明示，待部署实测】
- 去重（duplicate 计数）、关联（correlate）、blackout 静默内置；forwarder 类插件可 webhook-out 到任意 HTTP 端点。
  来源：交叉调研核查官方文档 【明示，webhook-out 粒度待部署实测】
- **已知缺口**：无 Incident 一等概念，只有告警状态机（open/ack/shelve/close）；Incident 归并由 AM1 控制面兜底。
- **未核实项**：常驻内存无官方数据（估 100~250M【推断】，T02 实测，含存储出局线合计 800M）。

## E-9 Coroot——内核硬门槛出局【拒绝】

- 官方明确要求："Coroot relies heavily on eBPF, therefore, the minimum supported Linux kernel version is 5.1."；node-agent 需 privileged + host pid + 挂载 tracing/cgroup；不支持 Docker-in-Docker/WSL1。
  来源：https://docs.coroot.com/installation/requirements/（主会话直查）【明示】
- 195 实测内核 3.10.0-1160（`uname -r`）——不满足，出局。
  来源：195 实测 【实测】

## E-10 落选候选速记

| 候选 | 结论 | 关键证据 |
|---|---|---|
| SigNoz | 排除 | 需 4G+ClickHouse；告警仍需手写规则（https://signoz.io/docs/install/docker/）【明示】 |
| OpenObserve | 备选观察 | 单容器极轻，但告警仍需配置条件（https://openobserve.ai/docs/user-guide/analytics/alerts/）【明示】 |
| 一体化平台 | 不成立 | Keep AI 关联仅 Cloud；夜莺是告警引擎非汇聚层；Grafana OnCall OSS 已归档（部署调研核查）【明示】 |
| PrometheusAlert | 排除 | 仅通知转发中心，无聚合/Incident（github.com/feiyu563/prometheusalert）【明示】 |
| Netdata | 排除 | CentOS 7 已移出官方支持平台（learn.netdata.cloud 平台政策）【明示】 |
| OneUptime | 排除 | 自建最低 4C8G，超预算（oneuptime.com docs）【明示】 |

## E-12 AM1 G1 评审一手核查（2026-09-04，主会话直查）【全部采纳的证据基线】

- **Alertmanager webhook 重试语义**："Webhooks are assumed to respond with 2xx response codes on a successful request and 5xx response codes are assumed to be recoverable"；Notifier 调 `retrier.Check(resp.StatusCode, resp.Body)`——**响应头不传入**，Retry-After 头对 webhook 无效。
  来源：https://github.com/prometheus/alertmanager/blob/main/notify/webhook/webhook.go 【明示】
- **AM webhook 组协议**：`Message{Version:"4", GroupKey, TruncatedAlerts}` + `template.Data`（receiver/status/alerts/groupLabels/commonLabels/commonAnnotations）；`max_alerts`（truncateAlerts）与 `timeout` 配置存在。
  来源：同上 【明示】
- **HolmesGPT HTTP API**：端点仅 `/api/chat`、`/api/model`、`/api/admin/reload*`、healthz/readyz——**无按会话查询已完成调查的接口**；`HOLMES_API_KEY` 支持 X-API-Key/Bearer 两种头；`response_format` 支持 `json_schema` + 官方要求 `strict:true`（`analysis` 字段为 JSON 字符串需二次解析）；SSE `metadata.usage` 提供 prompt/completion/total tokens；**admin/reload 端点当前无鉴权，官方要求网络层限制**；`ENABLED_PROMPTS`/`behavior_controls` 可裁剪 prompt 段落降 token。
  来源：https://github.com/HolmesGPT/holmesgpt/blob/master/docs/reference/http-api.md 【明示】

## E-15 Harness 设计调研（2026-09-04，`docs/告警-调研-Harness设计-v1.md`）【AM4 Native 内核设计素材】

- **Claude Code**：权限规则由 harness 强制执行而非靠模型自觉——PreToolUse 确定性拦截 + 退出码阻断并回喂模型（harness 优先的机制化落地）→ AM4 工具执行层。
  来源：https://docs.anthropic.com/claude-code 【明示】
- **Codex CLI**：`safety.rs` 三态判定（AutoApprove/AskUser/Reject{reason}），拒绝原因结构化回喂让模型自纠 → AM4；MCP 治理 = `mcp__server__tool` 命名空间 + per-server 白/黑名单 + per-tool 审批。
  来源：https://github.com/openai/codex 【明示】
- **Claude Code ToolSearch**：工具延迟加载按需披露（大工具库不进上下文）→ MCP 接入设计要点。
  来源：同上 【明示】
- **HolmesGPT 补充**：单工具结果超限 spill-to-disk 指针化 + 阈值触发历史压缩 → AM4 ContextBudgetManager 参照。
  来源：holmes 源码（E-3 同线程）【明示】
- **工具宁少勿多按工作流聚合**：Anthropic 原则 + Pi（badlogic/pi-mono，已甄别非 Inflection）反证——4 工具 <1000 token 照样上 Terminal-Bench 榜 → DomainProbe API 形状设计依据。
  来源：Anthropic 官方博客 + pi-mono 仓库 【明示】
- **DeepSeek 官方 harness `dsh`**：事件双通道设计与本项目 PG 持久化 + 内存 loop 同构。【明示】
- **拒绝项**：Pi 的 YOLO 无权限模式（生产域不可裸奔）；Claude Code auto 模式 LLM 分类器代审（违反确定性决策、评测不可复现）；OpenHands/OpenClaw 平台化分布式架构（单实例 PG 全家桶引入即双真相）。【明示】

## E-14 无 K8s 替代方案调研（2026-09-04，`docs/告警-调研-无K8s替代-v1.md`）【docker 等价物证据基线】

- **prometheus/prometheus-mcp** 已迁入 Prometheus 官方组织（原 tjhop/prometheus-mcp-server）：活跃维护、stdio/http（兼容 SSE）、镜像在 ghcr.io（195 可拉）、Go 单进程约 20~50MB；核心工具全只读，TSDB 删除类默认禁用，但默认加载 `quit`/`reload`，须用 `--mcp.tools` 白名单裁剪。
  来源：https://github.com/prometheus/prometheus-mcp 【明示】
- **docker 观测 MCP**：docker 官方 MCP 生态（docker mcp Gateway/Catalog）可脱离 Desktop 在 docker CE 跑、镜像在 Docker Hub；但无官方"docker 引擎只读观测"server，社区项目不成熟。
  来源：https://github.com/docker/mcp-gateway 等官方文档 【明示+部分未核实（Catalog 300+ 条目未逐一翻）】
- **Holmes 自带 toolset 只读基线**：`prometheus/metrics` + `docker/core` 只读已核实，零新增内存——AM4 前工具层基线。【明示】
- **确定性预诊断**：docker 生态无 K8sGPT Analyzer 等价物（空白）；`docker inspect`（OOMKilled/RestartCount/挂载/端口）+ `docker events` 为 DomainProbe 首选数据源，零常驻；cAdvisor 50~100MB 可选（历史资源曲线）。【明示+推断】
- **docker-bench-security**：docker 官方零星维护（CIS v1.6.0），Docker Hub 镜像过期需源码构建；一次性容器运行出 JSON，可解析进 DP 断言；需 host 级权限，只在 CI/部署环节跑。
  来源：https://github.com/docker/docker-bench-security 【明示】
- **conftest** 查 compose 可行：`docker compose config` 渲染后喂 Rego 策略（禁 privileged/必须 read_only/禁公网端口绑定等）。【明示】
- **内核 3.10 安全机制核对**：cap_drop / 默认 seccomp / no-new-privileges(≥3.5) / read_only 全部可用；docker secrets 为 swarm 专属（compose `secrets: file:` 退化替代，不加密）；userns-remap 在 RHEL7 裁剪严重不推荐；**gVisor 官方明示要求 Linux 5.6+，3.10 不可用**。
  来源：https://gvisor.dev/docs/ 等官方文档 【明示】
- **Helm 替代**：不需要——`.env` + compose override + profiles + `docker compose config` 渲染物进 git 即可。【推断】
- 内存预算：推荐方案新增常驻 <150MB（prometheus-mcp 50MB + cAdvisor 100MB 最坏情况）。

## E-13 架构 v2 外部调研一手核查（2026-09-04，主会话）【两批架构调研采纳的证据基线】

- **Spring AI 版本**：`ToolCallingAdvisor` 自动注册全链路 tool-call 循环是 **Spring AI 2.0**（2026-06-12 GA，面向 Spring Boot 4）；**Spring AI 1.1 保持兼容 Boot 3.4/3.5**——本项目 Boot 3.4.5 + Spring AI 1.0.0，Holmes 替换内核评估从 1.1 起，2.0 绑定 Boot 4 升级届时裁定。
  来源：https://spring.io/blog/2026/06/12/spring-ai-2-0-0-GA-available-now、https://www.baeldung.com/spring-ai-recursive-advisors 【明示】
- **kagent/K8sGPT 环境不匹配**：kagent 为 K8s purpose-built（非 K8s 环境非支持部署模型）；K8sGPT Analyzer 需 K8s API——本项目靶场为 docker 单机，**二者均不适用**，仅"确定性预诊断"思想可移植（RunLore 同理，pre-1.0 只抄机制）。
  来源：https://github.com/kagent-dev/kagent、https://github.com/k8sgpt-ai/k8sgpt 【明示+推断】
- **agentgateway**：standalone docker 形态存在（v1.4.1，2026-06）；MCP 授权支持 CEL 按工具名+参数细粒度控制；**风险**：2026 年持续 breaking change 与安全修复（pin digest、不追 latest）；**空授权规则=全放行**（默认非 deny，配置必须正反而测）。
  来源：https://github.com/agentgateway/agentgateway/releases、https://agentgateway.dev/docs/ 【明示】
- **K8s Conditions / LangGraph interrupt / in-toto / networknt / OTel GenAI Development / Prometheus 高基数 / PG jsonb 不保序 / Conductor 状态分类与 isolation group**：与既有核查一致或属官方文档常识，采信为架构 AA-16~24 的依据。
  来源：kubernetes.io、docs.langchain.com、github.com/in-toto/attestation、github.com/networknt/json-schema-validator、opentelemetry.io、prometheus.io/docs/practices/naming、postgresql.org、docs.conductor-oss.org 【明示】
- **HolmesGPT 工具审批**（`enable_tool_approval`/approval_required/恢复需重交 history）：适合交互客户端，不适合作审批事实源——审批必须落控制面（AA-19）。
  来源：http-api.md（E-12 同文档）【明示】

## 195 注册表实测记录（2026-09-03，主会话执行）

| 注册表 | 结果 | 证据 |
|---|---|---|
| docker.io | ✅ 通（daocloud/腾讯/dockerproxy 三加速已配；`prom/alertmanager:latest` 实拉成功） | ssh 实测 |
| ghcr.io | ✅ 通（`open-telemetry/demo:latest-frontend` manifest HTTP 200，1.5s） | ssh 实测 |
| quay.io | ✅ 通（/v2/ 返回 401 正常鉴权挑战） | ssh 实测 |
| us-central1-docker.pkg.dev（GAR） | ❌ 不通（连接超时无响应） | ssh 实测 |

> ~~待办：`docs/告警-调研-Keep替代-v1.md`（Alerta/Karma/Zabbix 等交叉核查）落地后，若与 E-7/E-8 冲突，以一手文档复核为准并在此留处置痕迹。~~
> **已处置（2026-09-03）**：交叉调研 `docs/告警-调研-Keep替代-v1.md` 落地。处置结果：① E-8 夜莺由"对照组"降级为"出局"（无 AM webhook 入站能力，主会话复核支持）；② 新增 E-11 Alerta 进入 A/B 候选 1；③ E-7 HertzBeat 保留为候选 2，其"收 AM 告警"两方结论一致，RSS 与 webhook-out 粒度仍待部署实测；④ Karma 出局（只读 dashboard 且仅 ghcr）；Keep 再核确认无 docker.io/ghcr 渠道、7.5G 机器上无法本地构建 UI（需 8G Node 堆）。A/B 从"HertzBeat vs 夜莺"更正为"Alerta vs HertzBeat"，已同步进 `docs/告警AM0-部署验证设计.md` v1.1。
