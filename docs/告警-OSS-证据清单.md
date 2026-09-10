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

## E-17 M5 发布门与运维源码级调研（2026-09-06，源码级）【AM5 设计素材】

- **报告**：`docs/告警-调研-M5发布门与运维-v1.md`（12 仓浅克隆逐仓源码核查，commit 经 git rev-parse 核实；细节留档 `var/m5-research/src-detail.md`，克隆仓 `var/m5-research/repos/` 约 880M 未提交 git；取证经 127.0.0.1:7890 代理）。
- 关键先例：Unleash murmur3(`groupId:id`) 稳定分桶 + flagd 无模偏公式 `(hash*totalWeight)>>32`（fractional.go:196-207）——**Unleash 无 stickiness key 时 random 回退是坑**（flexible-rollout-strategy.ts:26-28），本项目改拒绝放量；Inspect AI clustered SE 的 C/(C-1) 有限 cluster 修正（std.py:109-115）+ seed 仅部分 provider 支持须单列 provider fingerprint——【v1.1 澄清，2026-09-06 G1 评审】C/(C-1) 属 clustered SE 修正，cluster bootstrap 整组重采样不再套该修正，两算法不可混写；OPA bundle 单事务原子激活（plugin.go:607-660）+ decision_id 审计 + EventV1 决策日志带 bundle revision【v1.1：OPA 本体本期延期不引入，仅抄 ConfigBundle 四件套机制】；alerta ISA 18.2 action 驱动状态机（isa_18_2.py:99-140）+ keep (tenant,fingerprint) FOR UPDATE 幂等合并（db.py:5690-5706，但认领无并发保护须自加固 CAS）；PG NOTIFY 三边界（<8000B/8GB 队列/断连丢失）→ 只作唤醒、真身走表+after_seq 游标（Unleash delta API 回放先例）；`DETACH PARTITION CONCURRENTLY`+pg_partman keep_table 支撑"导出校验后 detach、失败不删热数据"，分区表唯一约束必须含分区键；Spring Cloud Config 与本项目 Boot 3.4.5 对应的 2024.0 release train 已退出 OSS 支持且模型不匹配，不引入【v1.1 措辞修正：产品仍在维护，非"已 EOL"】。
- 未核实澄清：~~**RCA-100 数据集不存在**（phamquiluan/RCAEval 实为 RE1/2/3 共 735 例，microsoft/RCAEval 404）~~【v1.1 推翻，2026-09-06 G1 评审 P0】三个项目被混淆：**RCA-100=阿里云 STAROps RCA-Bench**（103 例 Agentic Ops 故障，v1.1 现行，带 cause/boundary/process 评分协议，answer key 受控需授权核查，sls.aliyun.com/doc/starops/benchmark/rca/rca_benchmark_dataset.html）；**RCAEval=phamquiluan/RCAEval**（RE1/2/3 共 735 例，支持按 Case 下载 Parquet 子集）；**OpenRCA=microsoft/OpenRCA**（微软 LLM RCA Benchmark，不叫 RCAEval）。冻结架构 v1.2（line 761）本就写"RCAEval 小子集静态回放 + RCA-100 Adapter 外部一致性集"。AM5 数据集口径：订单域私有集=主质量门、RCA-100 v1.1 Adapter=辅助门（PUBLIC_BENCHMARK 不冒充 HOLDOUT）、RCAEval RE2/RE3 子集=辅助回归；中文分词 zhparser 现状、HNSW 内存需 195 实测定门。

## E-16 Harness 与 M4 机制源码级调研（2026-09-06，11 路并行 agent）【AM4 设计素材，源码级】

- **Harness v3**（`docs/告警-调研-Harness设计-v3.md`，取代 v1/v2 为现役版）：13 对象源码级重调——Claude Code（闭源仅文档）/Codex/Pi/dsh/OpenHands/OpenClaw/HolmesGPT/Gemini CLI/OpenCode/Goose/Aider/Cline/Hermes Agent（NousResearch，实查 242k★，2025-07 创建——推翻此前"2026-02 发布十余万星"的传闻）。每条机制带 `路径:行号` 证据；含对 v1/v2 旧结论的印证/推翻总表（43 条）。分册留档 `var/m4-research/harness-src/h01~h07`。
- **M4 机制调研 v1**（`docs/告警-调研-M4机制调研-v1.md`）：工具/证据/裁决/预算四册约 50+ 项目源码级重证，M4-13~23、M4-08/09/37 逐条裁定。关键修正：LiteLLM 已演进为事前预留制 budget_reservation（旧"缓存检查+异步补账"结论只对降级模式成立）；smolagents/CrewAI/LangChain 三家超限后再调一次 LLM 收尾（M4-08 耗尽测试须断言零 LLM 调用）；三家规则/工具注册重名静默覆盖（M4-14 须 fail-fast）；三个 default-allow 陷阱（M4-16 空策略须拒绝启动）。分册留档 `var/m4-research/src-part1~4`。
- **取证降级声明**：本机 GitHub 直连与 127.0.0.1:7890 代理均不通（2026-09-06），全部仓库经 gh-proxy/codeload 镜像取源码快照，commit SHA 经 GitHub API 逐仓核实记录；星数经 API/shields.io。
- 关键源码证据示例：Codex `SafetyCheck` 三态（codex-rs/core/src/safety.rs:17）；Gemini TOML 五档优先级+deny 剔工具（packages/core/src/policy/）；OpenCode doom_loop 三连熔断（processor.ts:354-381）；HolmesGPT 双池分离 5/10（env_vars.py:221-234）、spill 双阈值 min(15%,25k)；Keep 双哈希三分支（alert_deduplicator.py:61-116）；Bucket4j PG FOR UPDATE 原子扣减（PostgreSQLSelectForUpdateBasedProxyManager.java:69）；in-toto Statement 三段式 + rekor digest 对 canonical 字节算（entries.go:184/352）；Iceberg snapshot 不可变+CAS（SnapshotProducer.java:480-536）。

## E-18 前端图形库与 UI 语言口径调研（2026-09-07；v1.1 同日评审修正引证与措辞）【前端设计素材】

- **DAG/图渲染选型：Vue Flow（`@vue-flow/core`）+ dagre 自动布局【采纳】**：Vue 3 原生组件、MIT、支持节点事件；维护者明确"布局不是内置能力"，需外接 dagre/ELK 等布局器，官方讨论区有 dagre 示例（discussions/1039）。本项目 DAG 是只读状态投影（节点色=任务状态、点击节点联动事件流），不需要图编辑能力。
  来源：https://vueflow.dev、https://github.com/bcakmakoglu/vue-flow/discussions/1039【明示】
  ~~（v1 曾引 React Flow auto-layout 页作组合证据）~~【v1.1 更正】该页是 **React Flow Pro 示例**，不作 Vue Flow 官方组合证据，仅作 xyflow 家族同族参考。
- **AntV X6【不采用】**：定位图编辑引擎（拖拽编辑/连线桩/插件体系），只读 DAG 投影场景能力过剩；供应链事件属实——2026-05-19 mini Shai-Hulud 攻击中 `@antv/x6` 恶意版本包括 **3.2.7 与 3.3.7**（CI/CD 凭据窃取）。
  来源：https://osv.dev/vulnerability/MAL-2026-3839、Microsoft 安全博客 2026-05-20（mini-shai-hulud-compromised-antv-npm-packages）【明示】
- **mermaid【不采用】**：~~点击交互做不到~~【v1.1 更正】官方支持节点 click callback；真实出局理由 = 状态频繁更新需重新生成或额外维护 SVG，与 Vue 状态、选中态、事件流联动不够自然。
  来源：https://mermaid.js.org/syntax/flowchart.html【明示】
- **cytoscape.js【不采用】**：~~偏科研分析所以做不了~~【v1.1 更正】其支持事件、运行时样式与多种布局；不选理由 = API 面与能力规模超出只读 DAG 需求，不为够用场景引入大依赖。
  来源：https://js.cytoscape.org/【明示】
- **中文显示口径【v1.1 升级为契约，原为经验现象】**：评审坐实现状——Holmes schema 仅有中文字段说明，硬指令只要求纯 JSON，未要求字段值中文（HolmesInvestigationExecutor.java:347 区域）。冻结为：① 机器码（event_type/reason_code/fault_type/canonical 根因码）英文不变（稳定契约，评分不受影响）；② UI 中文名来自版本化词典新增的 display_name_zh 字段；③ prompt 增加硬指令要求 summary/impact/remediation/evidence 字段值使用简体中文；④ 新增中文输出契约测试 + 非中文降级策略（检测违规→按预算重试一次→仍违规则原文展示并落 language 诊断事件）。

| 注册表 | 结果 | 证据 |
|---|---|---|
| docker.io | ✅ 通（daocloud/腾讯/dockerproxy 三加速已配；`prom/alertmanager:latest` 实拉成功） | ssh 实测 |
| ghcr.io | ✅ 通（`open-telemetry/demo:latest-frontend` manifest HTTP 200，1.5s） | ssh 实测 |
| quay.io | ✅ 通（/v2/ 返回 401 正常鉴权挑战） | ssh 实测 |
| us-central1-docker.pkg.dev（GAR） | ❌ 不通（连接超时无响应） | ssh 实测 |

> ~~待办：`docs/告警-调研-Keep替代-v1.md`（Alerta/Karma/Zabbix 等交叉核查）落地后，若与 E-7/E-8 冲突，以一手文档复核为准并在此留处置痕迹。~~
> **已处置（2026-09-03）**：交叉调研 `docs/告警-调研-Keep替代-v1.md` 落地。处置结果：① E-8 夜莺由"对照组"降级为"出局"（无 AM webhook 入站能力，主会话复核支持）；② 新增 E-11 Alerta 进入 A/B 候选 1；③ E-7 HertzBeat 保留为候选 2，其"收 AM 告警"两方结论一致，RSS 与 webhook-out 粒度仍待部署实测；④ Karma 出局（只读 dashboard 且仅 ghcr）；Keep 再核确认无 docker.io/ghcr 渠道、7.5G 机器上无法本地构建 UI（需 8G Node 堆）。A/B 从"HertzBeat vs 夜莺"更正为"Alerta vs HertzBeat"，已同步进 `docs/告警AM0-部署验证设计.md` v1.1。

## E-20 M6 渐进发布与引擎退场源码级调研（2026-09-08，源码级）【AM6 设计素材；原 E-19 与前端 UI 调研冲突，已纠号】

载体：`docs/告警-调研-M6渐进发布与引擎退场-v1.md`（9 对象，官方文档+源码全文级，来源清单见该文附录）。

- **晋升窗三段式结论（Success/Failed/Inconclusive 停档等人工）【采纳】**：Argo Rollouts analysis 机制骨架（窗口期+指标门+abort 即回退）；不抄其固定节奏自动升档（pause duration/stepWeight）——升档条件=证据达标而非时间到。
  来源：https://argoproj.github.io/argo-rollouts/features/analysis/【明示】
- **critical 一票否决 + scored 观察层 两层判定【采纳】**：Kayenta（Netflix ACA Judge）分类结构；不抄 Mann-Whitney 统计栈——1%/10% 档样本量撑不起检验功效，本项目 M5 已冻结 cluster bootstrap 口径。
  来源：https://github.com/spinnaker/kayenta（NetflixACAJudge.scala / MannWhitneyClassifier.scala）【明示】
- **flagd fractional 分桶契约同构确认【保持】**：`fractional.go` 全文核对——本项目 CanaryBucketer 与 flagd 同构（murmur3 seed=0 + 无模偏公式），且"缺 stickiness key 拒绝放量"比 flagd nil 兜底更严，保持不放宽；放量用 totalWeight=100 整数权重靠前缀单调性只进不出。
  来源：https://github.com/open-feature/flagd（core/pkg/evaluator/fractional.go）【明示】
- **对照期=Scientist 数据模型 + 异步执行【采纳，带红线】**：control/candidate 比对、mismatch 带 context 落库、ignore 白名单；红线=绝不同请求同步双跑（撞预算硬门），抽样率+spend limit 异步旁路为唯一合法形态。
  来源：https://github.com/github/scientist、https://github.blog（Scientist 1.0 / Move Fast and Fix Things）【明示】
- **噪声带判定（Diffy+Scientist 双源印证）【采纳】**："不劣化"=引擎间差异 − Holmes 自差异 ≤ 容忍带；先跑 Holmes 自比对校准底噪再定阈值。
  来源：https://github.com/twitter-archive/diffy【明示】
- **Strangler Fig 退场五拍子【采纳】**：依赖扫描→恢复演练→回滚制品→物理摘除→历史可读；删除独立成任务、soak 期回切开关全程保留。
  来源：https://learn.microsoft.com/en-us/azure/architecture/patterns/strangler-fig【明示】
- **SRE Workbook Ch.16 准则【采纳】**：档位按代表性 Run 数而非墙钟（墙钟只做下限保护）；看板 split by engine；一次一档；禁 before/after 时间对比；切流门指标个位数。
  来源：https://sre.google/workbook/canarying-releases/【明示】
- **LLM 评估事实标准三段式【串联采纳】**：offline 金标并排（=M5 HOLDOUT 配对试验已冻结）+ online reference-free 采样（sampling rate+spend limit=HolmesShadowSampler 形态）+ 问题 Run 回流金标（=M5-03 Golden Candidate 通道）。
  来源：https://docs.langchain.com/langsmith/evaluation-concepts、https://langfuse.com/docs/evaluation/overview【明示】
- **本体一律不引入【裁定】**：Argo/Flagger/Kayenta 需 K8s；Diffy 已归档且需代理层；LangSmith/Langfuse 为整栈平台（2C4G 承载不起）——只抄机制与数据模型，落码零新依赖。
- **v1.1 独立复核修正【采纳】**：Google SRE 明示真实生产流量可暴露人工测试遗漏，Argo dry-run 指标不影响 rollout，因此 DRILL/REPLAY 与 LIVE_CANARY 必须数据级隔离，E2E 注入不可作为晋升样本；SRE 同时要求绝对度量，故每窗既比同时段 Holmes control 又查 absolute SLO，防双侧共同恶化。
  来源：https://sre.google/workbook/canarying-releases/、https://argoproj.github.io/argo-rollouts/features/analysis/【明示】
- **退场回滚语义修正【采纳】**：Strangler Fig 把 legacy removal 定位为依赖迁完后的最终刻意步骤，完全移除会显著提高 restore/replay 风险；M6-06 增 drain barrier，M6-07 后只承诺经演练制品按 RTO/RPO 恢复，不再称“一键回切”。
  来源：https://learn.microsoft.com/en-us/azure/architecture/patterns/strangler-fig【明示】
- **Scientist 适配边界【采纳】**：只借 control/candidate、mismatch context 与 control-vs-control 底噪；其 README 明示 read-only 更安全且 candidate timeout 不受框架保护，本项目生产反向 Shadow 改用 PG 持久工作/租约/预算，不采用请求内双跑或一次性 runner。
  来源：https://github.com/github/scientist/blob/main/README.md【明示+项目适配推断】

## E-21 钉钉/企业微信群机器人 webhook 限流与业务码陷阱【采纳：AM7 值班通知增量 M7-14 投递纪律】

- 钉钉自定义机器人：每个机器人每分钟最多发送 20 条消息，超限限流 10 分钟；安全设置三选一（自定义关键词/加签/IP 白名单），加签 = HmacSHA256(timestamp+"\n"+secret) 后 URL 编码。
  来源：https://open.dingtalk.com/document/group/custom-robot-access【明示】（核对 2026-09-09）
- 企业微信群机器人：每个机器人发送的消息不能超过 20 条/分钟；失败可表现为 HTTP 200 + 响应体 errcode≠0（如 45009 限流），只判 HTTP 状态码会静默假成功。
  来源：https://developer.work.weixin.qq.com/document/path/91770【明示】+ 开发者社区限流算法确认 https://developer.work.weixin.qq.com/community/question/detail?content_id=16540540749918243124【明示】（核对 2026-09-09）
- **适配性判断**：值班通知天然低频（Alertmanager 聚合兜底），20/min 足够；业务码陷阱直接命中本项目 WebhookChannel 现状（只判 HTTP 状态）——AM7 M7-14 必须补响应体 errcode 判定，否则 Gatus/值班通道误报成功。
- **引入代价**：投递端需按平台解析响应体；限流退避已有 notify_outbox RETRY_WAIT 范式承接，零新机制。

## E-22 Grafana OnCall / PagerDuty 值班排班模型【采纳：AM7 值班通知增量 M7-12 DutyResolver 分层轮换语义】

- Grafana OnCall 排班：同一 layer 内的 rotations 共享值班时间；更高 layer 的值班时间**覆盖**低层；overrides（临时换班）直接在日历上创建、优先级最高。
  来源：https://grafana.com/docs/grafana-cloud/observe-and-act/respond-to-incidents/on-call-schedules/create-schedules/【明示】（核对 2026-09-09）
- PagerDuty 风格 schedule：timezone + layers[]，每层 rotation_type（daily/weekly）+ 起始锚点 + 成员序列 + restrictions（时段限制）；层间不互相感知，交叠靠 override 解决。
  来源：https://www.pagerduty.com/resources/incident-management-response/learn/call-rotations-schedules/ + PagerDuty 社区官方答复（层间不互相感知，2 layers + override 处理交叠）https://community.pagerduty.com/ask-a-product-question-2/pagerduty-oncall-schedule-scenario-111【明示】（核对 2026-09-09）
- **适配性判断**：两家语义一致（override > 高层 layer > 低层 layer），可直接抄；轮换数学用 anchor_date + 周期取模即可纯函数化，不引入 cron 排班（表达力过剩）。
- **引入代价**：仅需三张表（layer/layer_member/override）承载语义；不实现 PagerDuty 的 restrictions 时段裁剪（本项目 7×24 单班制起点，后续有早晚班需求再加）。

## E-23 Gatus v5.17.0 三项契约冲突修正依据【采纳：MIG-02 前半，deploy/gatus 配置修正并 127 实测通过】

- **storage.type 合法值仅 memory/sqlite/postgres**，`file` 非法（`ValidateAndSetDefaults` 直接报错，启动即败）；sqlite 必须给非空 path，memory 不允许 path。
  来源：https://github.com/TwiN/gatus/blob/v5.17.0/storage/type.go + https://github.com/TwiN/gatus/blob/v5.17.0/storage/config.go 【明示】（核对 2026-09-09）
- **告警接收器合法 key 为 `custom`（`webhook` 不是合法 provider）**：`url` 必填、`method` 缺省 GET、`headers` map、`body` 模板占位符全集 6 个（明细归 E-24 第 1 节，不重复）；endpoint 侧必须声明 `alerts:`（`- type: custom`）才接收告警，未显式填的阈值/恢复项由 provider `default-alert` 合并（Alert 结构 type/enabled/failure-threshold/success-threshold/send-on-resolved/description，缺省 3/2/false）。
  来源：https://github.com/TwiN/gatus/blob/v5.17.0/alerting/provider/custom/custom.go + https://github.com/TwiN/gatus/blob/v5.17.0/alerting/alert/alert.go + https://github.com/TwiN/gatus/blob/v5.17.0/config/endpoint/endpoint.go + 官方文档页 https://gatus.io/docs/alerting-custom 、https://gatus.io/docs/endpoints 【明示】（核对 2026-09-09）
- **配置支持 `${ENV_VAR}` 替换**：解析前全文 `os.ExpandEnv`，`$$` 转义为字面 `$`——占位 webhook 地址留 `${GATUS_ONCALL_WEBHOOK_URL}` 等变量写法成立；注意 env 缺失时 custom provider 的 url 展开为空串，Gatus 仅告警 "url not set" 并忽略该 provider 继续启动（**不是** fail-closed，值班通道失明需靠探针自身兜底）。
  来源：https://github.com/TwiN/gatus/blob/v5.17.0/config/config.go（parseAndValidateConfigBytes/validateAlertingConfig）【明示】（核对 2026-09-09）
- **实测第 4 个坑（评审三项之外）**：v5.17.0 镜像 FROM scratch 且 Dockerfile 第 16 行把示例配置烤入 `/config/config.yaml`（7 个示例 endpoint）；配置加载顺序 `GATUS_CONFIG_PATH` → `config/config.yaml` → `config/config.yml`——不显式设 `GATUS_CONFIG_PATH=/config/config.yml` 时，挂载的 config.yml（.yml 后缀）永远不会被读，容器跑的是镜像示例。
  来源：https://github.com/TwiN/gatus/blob/v5.17.0/Dockerfile + https://github.com/TwiN/gatus/blob/v5.17.0/config/config.go（LoadConfiguration）【明示】+ 127 实测启动日志【实测】（核对 2026-09-09）
- **127 实测全链路通过【实测】**：独立 project `gatus-contract-test`，sqlite 持久化生效（重启后 `Loaded 1 persisted triggered alerts`）；custom 告警 firing（`"status": "TRIGGERED"`）与翻转目标后 resolved（`"status": "RESOLVED"`）双 body 捕获留档；删探针重启终态 `Validated 3 endpoints` + `Deleted 1 endpoint statuses`。镜像 digest `sha256:a8c53f9e9f1a3876cd00e44a42c80fc984e118d5ba0bdbaf08980cb627d61512` 已 pin 入 `deploy/gatus/compose.gatus.yml`。
  证据：`docs/测试证据/HOST2-127/gatus-v5170-契约修正/`（README + pull.log + 三阶段 log + 三份 statuses JSON + hook 原始日志）
- **与 E-24 分工**：E-24 管 duty-adapter 侧语义（Send 判定 >399、lazy retry、配置分层分流），本条管 `deploy/gatus` 配置契约本身；两侧对 `[RESULT_ERRORS]` 原样注入无 JSON 转义的结论一致（firing body 非严格 JSON，adapter 须容错解析）。

## E-24 M7-17 duty-adapter 预备调研：Gatus custom webhook 语义 + 企微/钉钉机器人约束 + 运行时内存形态【备料：AM7 增量 G1 待定，只调研不动码】

> 取证说明（2026-09-09）：gatus.io 官方文档站为 JS 渲染，正文抓不到，占位符/默认值/重试语义全部降级到 **tag v5.17.0 源码直查**（快照经 gh-proxy codeload 镜像取得，留档 `var/m7-research/gatus-5.17.0/`）；钉钉开放平台文档站同为 JS 渲染，正文直读受限，其数字来自官方域页面/搜索快照，标注待实测终核。本机 GitHub 直连不通（E-16 同口径）。

### 1) Gatus alerting.custom（v5.17.0 源码级，核对 2026-09-09）

- 占位符全集（body 与 url 同串替换，`strings.ReplaceAll` 直替无转义）：`[ALERT_DESCRIPTION]`、`[ENDPOINT_NAME]`、`[ENDPOINT_GROUP]`、`[ENDPOINT_URL]`、`[RESULT_ERRORS]`（`result.Errors` 逗号 join）、`[ALERT_TRIGGERED_OR_RESOLVED]`（字面 `TRIGGERED`/`RESOLVED`，可经 `placeholders.ALERT_TRIGGERED_OR_RESOLVED.<状态>` 自定义映射）。**v5.17.0 不存在 `[ALERT_NAME]` 占位符**（AM7 方案若引用须改用 endpoint name/description 表达）。
  来源：https://github.com/TwiN/gatus/blob/v5.17.0/alerting/provider/custom/custom.go（buildHTTPRequest）【明示】+ 官方文档页 https://gatus.io/docs/alerting-custom 【明示】（核对 2026-09-09）
- 出站请求形态：`url` 必填；`method` 未配置默认 GET；`body` 未配置为空串（**无默认 body 模板**）；`headers` 逐条 Set。Send 成败判定：传输错误或 **HTTP 状态码 >399** 即失败（错误信息带响应体），≤399 一律成功——**HTTP 200 + errcode≠0（企微/钉钉假成功）Gatus 记为已发送**，业务码校验只能由 127 duty-adapter 承接（呼应 E-21，Gatus 侧 body 模板解决不了）。
  来源：https://github.com/TwiN/gatus/blob/v5.17.0/alerting/provider/custom/custom.go（Send）【明示】（核对 2026-09-09）
- 发送次数与重试语义：连续失败 ≥ `failure-threshold`（默认 3）才发第 1 条（resolved=false）；已 Triggered 的告警后续评估直接跳过（不重复通知）；发送失败 → Triggered 保持 false → 下个 endpoint `interval`（默认 60s）整条重试（源码注释自称 lazy retry：无退避、无次数上限，直到成功或恢复）；恢复侧连续成功 ≥ `success-threshold`（默认 2）即解除 Triggered 并删持久化记录（无论 resolved 是否发送成功）；`send-on-resolved` 默认 false，为 true 时每个事件最多再发 1 条 resolved，发送失败**不重试**（alert.go Triggered 字段注释明示该取舍）。
  来源：https://github.com/TwiN/gatus/blob/v5.17.0/watchdog/alerting.go + https://github.com/TwiN/gatus/blob/v5.17.0/alerting/alert/alert.go + https://github.com/TwiN/gatus/blob/v5.17.0/config/endpoint/endpoint.go【明示】（核对 2026-09-09）
- 配置分层：provider 级 `DefaultConfig` + group `overrides`（按 endpoint group 匹配）+ per-alert `provider-override`，合并顺序 group override → alert override——127 单 URL 承接多 endpoint 时可用 group override 分流企微/钉钉通道。
  来源：https://github.com/TwiN/gatus/blob/v5.17.0/alerting/provider/custom/custom.go（GetConfig/Merge/Override）【明示】（核对 2026-09-09）

### 2) 企微/钉钉 markdown 消息约束（核对 2026-09-09）

- 企业微信群机器人：`{"msgtype":"markdown","markdown":{"content":"…"}}`，content UTF-8 **≤4096 字节**；语法子集：1~6 级标题（# 后须空格）、加粗、链接、行内代码（不跨行）、引用、仅 3 种内置字体色（info/comment/warning）、`<@userid>`/`@all`；**不支持斜体与跨行代码块**（markdown_v2 才支持部分扩展且不支持字体色/@成员）；频控 20 条/分钟（E-21 已录）。
  来源：https://developer.work.weixin.qq.com/document/path/91770 【明示】（核对 2026-09-09）
- 钉钉自定义机器人：`{"msgtype":"markdown","markdown":{"title":"…","text":"…"}}`，title=首屏会话透出的展示内容（会话列表预览），text=markdown 正文；官方消息类型页标注 markdown 消息**最大不超过 5000 字符**；安全设置三选一（E-21 已录频控 20/min、超限限流 10 分钟）对 payload 的影响：**自定义关键词**（最多 10 个）要求消息至少含其中 1 个关键词才可发送；**加签**只改 URL（拼 `&timestamp=…&sign=…`，HmacSHA256(timestamp+"\n"+secret)→Base64→urlEncode）不改 payload；IP 白名单不改请求内容。
  来源：https://open.dingtalk.com/document/isvapp/custom-bot-access-send-message + https://open.dingtalk.com/document/development/message-types-and-data-format + https://open.dingtalk.com/document/robots/customize-robot-security-settings + https://open.dingtalk.com/document/isvapp/customize-robot-security-settings-1 【明示（官方域页面/搜索快照；站体 JS 渲染直读受限，127 实发一条终核）】（核对 2026-09-09）
- **一行结论**：同为"标题+正文"模型，企微单 content 字段 4096 字节 vs 钉钉 title+text 两字段 5000 字符——模板须按平台分支拼装；钉钉关键词模式匹配"消息"（title/text 均计入），模板里固定放业务关键词（如"值班"）最稳；两平台频控同为 20/min。

### 3) duty-adapter 运行时内存形态对比（核对 2026-09-09）

- Java/Spring Boot 3（最小形态：1 个 webhook POST 入口 + 业务码校验 + 拼 markdown + 出站 POST + 读快照）：基础 Boot 应用（内嵌 Tomcat）启动即 ~100~150MB（社区口径）；JVM 官方从不发布 RSS 数字（RSS=堆+Metaspace+线程栈+CodeCache+DirectBuffer，官方方法论用 `-XX:MaxMetaspaceSize` 封顶 + `-XX:NativeMemoryTracking=summary` 核账）。堆 -Xmx128m~256m + metaspace 封顶 128m 下，**稳态 RSS 可靠区间 ~150~400MB**（瘦依赖+小堆压榨态 150~250MB，常规余量态 250~400MB）。
  来源：https://www.baeldung.com/spring-boot-memory-usage-optimization（基础应用 ~150MB）+ https://www.javacodegeeks.com/memory-usage-optimization-in-spring-boot.html（~100MB）+ https://spring.io/blog/2015/12/10/spring-boot-memory-performance（官方调优方法论）【明示（社区实测口径+官方方法论；127 实测后定门）】（核对 2026-09-09）
- Go（同功能 net/http 单二进制）：hello world HTTP 服务 RSS ~4MB 量级（128MB MIPS 设备实测帖）；实用小服务稳态 **~10~30MB**（go vs java 对比 ~25MB；容器口径讨论同量级）；静态单二进制 ~2MB，`GOOS=linux GOARCH=amd64` 本机交叉编译，scp+systemd 或 FROM scratch 镜像均可。注意 Go VSS（虚拟内存）虚高是 arena 预留常态，报数以 RSS 为准。
  来源：https://groups.google.com/g/golang-nuts/c/FCMPvaBMaMg/m/jEYVs_5GDAAJ（~4MB RSS）+ https://medium.com/deno-the-complete-reference/go-vs-java-native-http-server-performance-comparison-for-hello-world-case-2e30b5ec18ec（~25MB）+ https://news.ycombinator.com/item?id=31322073【明示（社区实测口径）】（核对 2026-09-09）
- 中间态 GraalVM native-image（Spring Boot 3 官方支持路径）：RSS **~50~100MB**；额外代价=native 构建耗时与构建机内存（构建期比运行期贵一个量级）、反射/资源额外配置、与团队现有 Spring 调试/热部署习惯割裂。
  来源：https://docs.spring.io/spring-boot/docs/3.2.3/reference/html/native-image.html（官方明示 smaller memory footprint）+ https://www.graalvm.org/jdk24/reference-manual/native-image/guides/optimize-memory-footprint/ + 社区口径 JVM 200~500MB vs native 10~100MB（javacodegeeks 2025-10）【明示】（核对 2026-09-09）

**对比结论（两列代价如实并列，不下死命令，供 G1 权衡）**：

| 维度 | Spring Boot 3（JVM） | Go net/http | （中间态）GraalVM native |
|---|---|---|---|
| 稳态 RSS | ~150~400MB | ~10~30MB | ~50~100MB |
| 占 127 available ~2.8G | ~5%~14% | ~0.4%~1.1% | ~2%~4% |
| 构建/部署面 | 需 JRE 基础镜像（127 有 docker 可跑） | 本机交叉编译单二进制，零镜像也可 systemd 直跑 | 栈不变，但需 native 构建链与额外配置 |
| 团队栈匹配 | 全栈 Java/Spring，零新栈 | 新增构建链+运维技能+与 control-app 双栈并存 | 栈不变，构建/调优面变化 |

- 若 Go 形态内存优势显著（本对比约一个数量级：10~30MB vs 150~400MB），其代价是引入新栈（Go 构建链、运维技能、双栈维护）；而 Spring Boot 3 常规小堆形态在 2.8G 余量下占 ~5%~14%，属可承受量级。duty-adapter 具体技术形态由 AM7 增量方案（G1 评审）裁定，本条仅备料。
- **未核实项**：钉钉 5000 字符上限在 title/text 间的精确归属（官方站正文不可直读）；两平台真实 RSS 上限数字（均无官方发布，127 实测后定门）。
