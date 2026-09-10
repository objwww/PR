# EX-B2 · logs 真实源 + LogQueryExecutor（含盘点门）契约

> 卡源：`docs/告警-执行者ABC-改造技术方案.md` §二 EX-B2（B 门硬前置）。
> 模板：同方案 §六；验收分层 §7.2。基准：EX-B1 收官工作树（未 commit）。
> **盘点门**：卡面要求盘点表经本主会话签字后才写 executor——见 §1（2026-09-10 实探）。

## 1. 盘点表（195 真机实探 2026-09-10，证据 = m6-ev/exb2-probe-inventory.sh 输出）

| 服务 | 日志源 | collector 路径 | 存储 | 查询 API |
|---|---|---|---|---|
| control-app | stdout json-file（2×5M 轮转）+ OTLP logs → otelcol-control（logs pipeline: otlp→otlp/upstream） | upstream=`http://otel-collector:4317`（docker inspect env 实证）→ demo collector | **debug exporter = 无存储**（demo collector config logs.exporters=[debug]） | 无 |
| notify-app | stdout json-file（2×5M） | 无 OTLP 接线 | docker json-file | 无 |
| demo 业务服务（checkout/frontend/recommendation…） | stdout json-file（2×5M；实占 checkout 0B / frontend 26K / recommendation 1.7M）；OTLP logs 管道在位（demo collector logs pipeline）但**留存窗口 LogRecord 计数 = 0** | demo collector → debug | docker json-file + debug（丢弃） | 无 |
| postgres/中间件 | stdout json-file（2×5M） | 无 | docker json-file | 无 |
| 现有可查询来源 | **不存在**：docker logs 仅经 SSH 宿主面（非 HTTP API；executor 不可达，且不得给应用容器挂 docker.sock）；Loki/ES/ClickHouse 容器 = 零（docker ps -a 全扫 NONE） | — | — | — |

**签字结论（主会话，2026-09-10）**：全机无任何业务服务日志的可查询存储与 API；"control collector 自己有日志"不成立为充分源（demo collector logs pipeline exporters=[debug]=丢弃）。**缺来源 → 按卡面显式处理，禁止 fixture 顶替**（Phase 3 验收门第 1 条同步满足：production profile 不注册 ReplayToolExecutor 为 logs/change）。

## 2. 无数据三态语义（executor 输出契约）

| 态 | 判定 | 载体 | 模型面 |
|---|---|---|---|
| **EMPTY** | Loki 200 且窗内 0 条 | `ToolModelVisibleException(NO_DATA)` | 可见、正常空结果、可重试 |
| **SOURCE_UNAVAILABLE** | 连接拒绝/超时/IO 中断/5xx（新增枚举值，与 PROMETHEUS 面 REMOTE_UNAVAILABLE 分开记因，便于台账判源） | `ToolModelVisibleException(SOURCE_UNAVAILABLE)` | 可见、临时源故障、可退避重试 |
| **QUERY_FAILED** | Loki 对良构查询回 4xx（LogQL 拒收/版本语义差）——新增控制面枚举值 | `ToolControlPlaneException(QUERY_FAILED)` | 终止族（工具侧缺陷，不进重试循环） |

其余确定结局（卡面 3）：401 → `AUTH_FAILED`（控制面，既有）；429 → `RATE_LIMITED`（模型可见，既有）；超大响应 → 有界流读 `RESULT_OVERSIZE`（既有）；半包中断 = 读流 IOException → `SOURCE_UNAVAILABLE`（不投递半包）。

## 3. 设计决策（卡面 2：不预设实现）

- **优先复用已可查询来源** → §1 证明不存在。
- **Loki 试验（本卡选定）**——写成**试验资源上限与准入条件**而非容量承诺：
  - 准入：`mem_limit ≤ 512MiB`（实测 195 available 3544MiB，配 384MiB）；镜像版本 pin；retention ≤3d；单二进制 filesystem 存储。
  - 注入路径：**官方当前推荐 otlphttp → Loki 原生 OTLP 入口**（`/otlp/v1/logs`）；控制面 collector（本仓 `deploy/alert/otelcol/`，contrib 0.104 含 docker_logs receiver）加 `docker_logs` receiver（allowlist 容器名过滤）→ logs pipeline 双 exporter（`otlp/upstream` + `otlphttp/loki`）。
  - 标签面：Loki OTLP 默认仅 service.* 进 label；`container.name` 提升为 label `container_name` 走 **Loki 侧 otlp_config attributes_config**（单一事实点）；executor 选择器 `{container_name="<service>"}`；**部署时 curl 实证标签面后冻结**（卡面：按现有镜像版本验证）。
- **PG 全文兜底成本核算（未选）**：采集管道工作同量级（docker_logs 照旧要加）；落 PG 则写路径与告警主库竞争（单 PG 512M 内存帽、M6 记忆门在册）、text 索引膨胀、retention 清理自维护——"已有 PG"≠最轻，判定不如独立资源帽的 Loki。
- **显式降级路径**：试验不达标（内存超帽/查询不可用）→ 摘 Loki 容器，executor 保持真查 → 稳定回 `SOURCE_UNAVAILABLE`（不回 fixture、不回假数据）；logs 工具是否保留在 default allowlist 由配置裁决。

## 4. executor 契约（与 change.query 同 v1.0 纪律）

- args：`since`/`until`（必填 ISO-8601 Instant，语义域内判 0 ≤ 窗幅 ≤ **900s**）+ `service`（可选，缺省 `control-app`，必须在 allowlist）；allowlist 配置键 `app.alert.am4.logs.service-allowlist`（默认 `control-app,checkout,frontend,recommendation`）。
- 行顶 **200**（Loki `limit=201` 探针，201 → truncated=true 交 200）；字节上限 `resultLimitBytes` 流式即断；响应形状沿统一面 `{"status":"success","data":{"result":[{ts,service,line}…],"truncated":bool}}`（Jackson 流式 render，同 ChangeQueryExecutor 纪律）。
- 部署链：compose 增 `loki` 服务（资源帽+版本 pin+retention）；otelcol-control 服务加 `/var/run/docker.sock:ro` 挂载与 otlphttp/loki exporter 配置。回滚 = 摘 Loki 服务（§3 显式降级）；无 DB 迁移面。

## 5. 测试（具名）

- **L0**：`LogQueryExecutorTest`（args 语义/allowlist fail-closed/构造次序/Loki 响应→统一形状 render/201 截断/字节超限/EMPTY 三态判）；`AlertAm4ConfigTest`（logs 挂点 = LogQueryExecutor 类型钉，logs fixture 退役钉）。
- **L1 具名 IT**：`ExB2LogQueryIT`（Testcontainers 真实 Loki：OTLP push 灌真行 → executor query_range 真查/EMPTY/SOURCE_UNAVAILABLE（停容器）/200 截断）。
- **L2 真机**：195 部署后真查业务容器日志证据（checkout/frontend 行回读 + 标签面 curl 实证）。

## 6. 完成证据

## 6. 完成证据（2026-09-10 回填）

| 验收层 | 内容 | 证据 |
|---|---|---|
| L0 本地 | mvn test BUILD SUCCESS：LogQueryExecutorTest 8/8 + AlertAm4ConfigTest 2/2，全量 1032 零跳过 | m6-ev/exb2-l0-local2.log |
| L1 真栈 IT（195） | ExB2LogQueryIT 4/4（Testcontainers grafana/loki:3.4.2：OTLP push→executor 同形查询/EMPTY→NO_DATA/停容器→SOURCE_UNAVAILABLE/201 行截 200+truncated），Loki 容器 16.4s 就绪，BUILD SUCCESS 59.5s | m6-ev/exb2-l1-targeted3.log（run1/2 红为 B-33/B-34 证据） |
| 官方全量 verify（195） | UT 1032 + IT 167 全零跳过 BUILD SUCCESS 01:08；日志 sha256 前 16 = 0e5c6c169b6386f5（双侧全等） | m6-ev/exb2-verify.log |
| drill5 部署 | DEPLOY_AM4_DONE deploy_id=am4-20260909T233220Z；CHANGE_EVENT_RECORDED status=SUCCEEDED（change_event 落档守卫通过）；部署球 830256ee25d76c6f | /tmp/exb2-deploy5.log |
| L2 真机日志源 | **checkout 真实行**（"order confirmation email sent"，label service_name=checkout，executor 同形选择器 `{service_name=\"checkout\"}`）；control-app 探针行（collector→Loki 链路证明，B-35 后应用自身无 OTLP logs 出口见 §7）；ghost-svc 零行（EMPTY 面）；`/ready` = ready | m6-ev/exb2-l2-evidence.log（exb2-l2.sh） |
| 容器面 | deploy-loki-1 Up（384m/read_only/cap_drop ALL/零宿主端口/deploy_internal+opentelemetry-demo 双内网）；otelcol-control Up 零错误；control-app Up 健康 200 | exb2-l2.sh §1/§2 输出 |
| 同步完整性 | 14 文件双侧 sha256 前 16 全等（exb2-remote-probe.sh）；fixture 删除面 `control-app/src/main/resources/am4` 双侧不存在 | /tmp/exb2-remote-probe.sh 输出 |

Phase 3 门禁第 1 条同步满足：production registry 三工具全真源（logs=LogQueryExecutor/change=ChangeQueryExecutor/metrics=PrometheusQueryExecutor），ReplayToolExecutor 与 fixtureBytes() 整体退役，main 资源 am4/ 目录删除（AlertAm4ConfigTest 文件面钉）。


## 7. B-35 链路修正裁定（2026-09-10，L2 首轮实捕后）

- **docker_logs 计划面作废**：otelcol-contrib 从未有 docker_logs receiver（0.104.0 valid-list + 0.103.0/0.100.0 components 三版实测均无；filelog+container 解析器只能从 k8s pod 路径提容器名，docker json-file 扁平 ID 路径无名字面）。otelcol-control 的 docker.sock 挂载与 docker_logs receiver 整体摘除，恢复 M5-15 原形态。
- **注入链路修正（双路 OTLP，均零路径解析零名字映射）**：①otelcol-control logs 管道 exporters=[otlp/upstream, otlphttp/loki]（control 平面 OTLP logs）；②demo collector `otelcol-config-extras.yml` 加 `otlphttp/loki`（endpoint http://loki:3100/otlp）+ logs pipeline exporters=[debug, otlphttp/loki]——业务服务（checkout 等）**自带 OTLP logs 且 resource.service.name 应用侧声明**，经 demo 外部网落 Loki；改前面备份于 `otelcol-config-extras.yml.exb2bak`，数组整体替换语义复用该文件 AM0 prometheus 先例。loki 服务加接 opentelemetry-demo 外部网（仍零宿主端口）。
- **control-app 自身日志行为零行（如实登记）**：类路径遥测 = micrometer-tracing-bridge-brave（pom.xml:85），无 OTLP logs exporter，compose 的 OTEL_EXPORTER_OTLP_ENDPOINT 对 logs 仅声明面。应用侧 exporter 接线为后续卡（非本卡范围）；当前 control-app 查询 = EMPTY/NO_DATA 确定结局。
- **trial watch-item**：demo 行 resource attrs（observed_timestamp 等）进 Loki label 面，流基数观察项——超帽即按 §3 显式降级路径摘 Loki，不做静默扩容。
- 标签契约实证：`/loki/api/v1/label/service_name/values` 返回 13 业务值（checkout/cart/payment/product-catalog/quote/shipping/frontend-proxy/load-generator/…）。
