# 告警-调研-无K8s替代-v1

> 面向纯 docker 单机环境（CentOS 7 / 内核 3.10 / docker 26，无 K8s 无 Helm）的替代方案调研。
> 所有事实性结论标注【明示】（一手资料直接写明）/【推断】（基于资料合理推断）/【未核实】，附一手 URL，核对日期统一为 2026-09-04。

## 结论摘要（200 字）

kagent-tools → 用 **prometheus/prometheus-mcp**（Prometheus 官方 MCP，ghcr 可拉，Go 单进程）或**继续用 HolmesGPT 自带 Prometheus/Docker toolset**（零新增内存，推荐作基线）。K8sGPT Analyzer → **docker 生态没有现成等价物**，扩展 AM2 自研 DomainProbe（docker inspect + events，可选 cAdvisor）。Kyverno/RBAC → **compose hardening 模板 + conftest（OPA）策略检查 + docker-bench-security 进部署门**即"穷人版 Kyverno"；RBAC 无细粒度等价物，靠 docker.sock 权限收敛 + 只读化。Helm → **env 文件 + compose override + profiles** 足够。gVisor 要求内核 5.6+，3.10 确认不可用。docker secrets 为 swarm 专属，compose 的 `secrets: file:` 可作退化替代。

---

## 1. Agent 工具层（替代 kagent-tools 的 Prometheus/容器工具）

### 1.1 Prometheus MCP server 候选逐个核查

#### 候选 A：prometheus/prometheus-mcp（原 tjhop/prometheus-mcp-server）★推荐

- **官方性**：仓库已从个人账号迁入 `prometheus` 官方组织（github.com/prometheus/prometheus-mcp），作者 tjhop 为 Prometheus 团队成员；使用 promu/Makefile.common 等 Prometheus 官方构建体系【明示】。
- **维护状态**：活跃。vibehackers 条目显示 v0.17.0【推断为近期版本】；README 含 runbooks/Agent Skills、TOON 编码等 2026 年新特性【明示】。
- **传输方式**：`--mcp.transport=stdio|http`，http 模式同时兼容 SSE（"Streamable HTTP transport (capable of SSE as well)"）【明示】。
- **只读性**：核心工具（query/range_query/series/label/metadata 等）全部只读；TSDB 管理类工具（delete_series/snapshot/clean_tombstones）默认禁用，需显式 `--dangerous.enable-tsdb-admin-tools`【明示】。注意：默认 `--mcp.tools=all` 会加载 `quit`/`reload` 管理端点工具，但它们只有在 Prometheus 开了 `--web.enable-lifecycle`（默认关）才生效；稳妥做法是用 `--mcp.tools` 白名单只放只读工具【明示+推断】。
- **能否 docker 跑**：能。`docker run --rm -i ghcr.io/tjhop/prometheus-mcp-server:latest --prometheus.url ...`【明示】。
- **分发渠道**：**ghcr.io**（`ghcr.io/tjhop/prometheus-mcp-server`）+ GitHub Releases 二进制 + deb/rpm 系统包。**不在 Google Artifact Registry**，195 可拉 ghcr【明示】。
- **预估 RSS**：Go 单二进制，推断 20–50MB【推断】。
- URL：https://github.com/prometheus/prometheus-mcp （核对 2026-09-04）

#### 候选 B：pab1it0/prometheus-mcp-server（社区，Python）

- **维护状态**：活跃（issue #103 于 2025-10 仍有交互；Helm chart v1.1.1）【明示】。
- **传输方式**：stdio / http / sse，默认 stdio（`PROMETHEUS_MCP_SERVER_TRANSPORT`）【明示】。
- **只读性**：工具仅 6 个（execute_query、execute_range_query、list_metrics、get_metric_metadata、get_targets、health_check），**全只读**，是三个候选里攻击面最小的【明示】。
- **分发渠道**：**ghcr.io**（`ghcr.io/pab1it0/prometheus-mcp-server`），另有 PyPI/uvx。非官方组织，供应链信任弱于候选 A【明示+推断】。
- **预估 RSS**：Python（FastMCP）进程，推断 80–150MB【推断】。
- URL：https://github.com/pab1it0/prometheus-mcp-server （核对 2026-09-04）

#### 候选 C：Grafana 官方 mcp-grafana

- **官方性/维护**：Grafana Labs 官方仓库，活跃【明示】。
- **传输方式**：stdio / sse / streamable-http【明示】。
- **分发渠道**：**Docker Hub**（`mcp/grafana`、`grafana/mcp-grafana`）+ 二进制【明示】。
- **只读性**：**不是只读**——含创建/更新 dashboard、管理 incident、创建告警规则等写工具，需用 `--enabled-tools` 裁剪【明示】。
- **适用性**：**要求 Grafana 9.0+ 实例**【明示】。我们栈里没有 Grafana，为它引入整个 Grafana 不合算。能力虽覆盖 Prometheus/Loki 数据源查询，但定位是"Grafana 全家桶入口"而非 Prometheus MCP。
- **预估 RSS**：Go 进程 30–60MB，**另需常驻 Grafana ≈ 150–300MB**【推断】。
- **结论**：不采用。
- URL：https://github.com/grafana/mcp-grafana （核对 2026-09-04）

#### 候选 D（现状基线）：HolmesGPT 自带 toolset

- `prometheus/metrics` toolset：8 个只读工具（instant/range query、metric/label/series/rules 发现），经 config.yaml 启用，随 HolmesGPT 进程内运行，**零新增常驻内存**【明示】。
- `docker/core` toolset：描述为 "Read access to Docker resources"，9 个只读 CLI 工具（docker ps/inspect/logs/events/top/diff/history/images）【明示】。
- URL：https://holmesgpt.dev/data-sources/builtin-toolsets/prometheus/ 、 https://raw.githubusercontent.com/robusta-dev/holmesgpt/master/holmes/plugins/toolsets/docker.yaml （核对 2026-09-04）

### 1.2 docker 容器观测的 MCP server

- **Docker 官方 MCP 生态**：核心是 `docker mcp` CLI 插件 / MCP Gateway（docker/mcp-gateway）。Docker MCP Catalog 提供 300+ 个以容器镜像分发的 MCP server，镜像托管在 **Docker Hub**（`mcp/...` 命名空间，可拉）【明示】。Gateway 可脱离 Docker Desktop 在 Linux/docker CE 上独立运行（设 `DOCKER_MCP_IN_CONTAINER=1`，`docker mcp feature enable profiles`）【明示】。
  - URL：https://github.com/docker/mcp-gateway 、 https://docs.docker.com/ai/mcp-catalog-and-toolkit/ （核对 2026-09-04）
  - 坑：面向"AI 客户端统一接入"场景，对我们（HolmesGPT/自研 control-app 做 client）属于额外中间层；catalog 中**未见 Docker 官方出品的"docker 引擎只读观测" server**【未核实，基于 catalog 文档浏览】。
- **社区 docker 观测 MCP**：多为个人项目、质量参差。只读取向的有 knutkirkhorn/docker-mcp-server（容器/镜像信息，Bun 运行时）和 Sorranop01/mcp-system-monitor（FastMCP，系统+docker 只读）；ofershap/mcp-server-docker 等则偏管理（含写操作）【明示为仓库自述】。均**非成熟生产级**，不建议引入。
  - URL：https://github.com/knutkirkhorn/docker-mcp-server 、 https://github.com/Sorranop01/mcp-system-monitor 、 https://github.com/ofershap/mcp-server-docker （核对 2026-09-04）

### 1.3 结论：AM4 期 Holmes 替换时工具层最务实组合

**推荐：自研靶场只读 API + Holmes 自带 toolset 为主，prometheus/prometheus-mcp 为可选升级项。**

1. **基线（零成本）**：继续用 HolmesGPT 自带 `prometheus/metrics` + `docker/core` toolset，配合 order-arena 自研只读 API。无新增常驻内存，已验证可用。
2. **若 AM4 换 Agent 框架、需要框架无关的标准 MCP**：选 **prometheus/prometheus-mcp**（官方、ghcr 可拉、工具白名单可裁剪成纯只读、Go 低内存 20–50MB），以 HTTP transport 常驻；docker 层观测**不引入社区 MCP**，直接把 DomainProbe 的只读查询封装成自研 MCP/REST 工具。
3. **不推荐**：Grafana mcp-grafana（需引入 Grafana，+150–300MB）、社区 docker MCP（不成熟）、docker MCP Gateway（中间层收益不抵复杂度）。
4. 坑：prometheus-mcp 默认加载 `quit`/`reload` 工具，务必用 `--mcp.tools` 白名单裁剪；其 HTTP 端点"任何可达者都可用默认凭据查 Prometheus"（README 原话），须绑定内网/加 web.config 鉴权【明示】。

---

## 2. 确定性预诊断（替代 K8sGPT Analyzer 的角色）

### 2.1 docker 环境现成的"规则扫描容器常见问题"工具

- **结论先行：没有 K8sGPT Analyzer 在纯 docker 下的成熟等价物。** K8sGPT 的 analyzer 全部围绕 K8s 资源（Pod/Service/PVC…）建模，无 docker 后端【推断，其仓库与文档无 docker 支持表述】。
- 能找到的邻近物都不是"诊断"：
  - **docker-bench-security**：静态配置审计（CIS），查"配置安不安全"，不查"为什么反复重启"（见 §3.1）。
  - **Netdata / maintenant 等**：监控+告警（含重启循环检测），不是规则化根因诊断。
    - URL：https://kolapsis.github.io/maintenant/features/containers/ 、 https://www.netdata.cloud/guides/docker/docker-container-keeps-restarting/ （核对 2026-09-04）
- 容器反复重启/端口不通/挂载缺失/OOM 这类问题，诊断所需的全部原始信号都在 docker 自身 API 里（见下），生态里没人把它规则化成产品【推断】。

### 2.2 cAdvisor / docker events / docker inspect 作为 DomainProbe 数据源评估

| 数据源 | 能提供什么 | 成本 | 适合做 DomainProbe 数据源？ |
|---|---|---|---|
| `docker inspect` | 点态真相：State（OOMKilled、ExitCode、RestartCount、Health）、Mounts、PortBindings、资源限制、Cap/Privileged | 一次 API 调用，零常驻 | **是，首选**。挂载缺失/端口映射错/权限配置错全靠它【明示字段含义，推断为首选】 |
| `docker events` | 生命周期事件流：die/oom/kill/restart/health_status | 一条长连接，近零成本 | **是**。重启循环、OOM 时序的检测触发器【明示】 |
| `docker stats` / API | 实时 CPU/内存/IO | 按需调用 | 辅助（资源耗尽判定） |
| cAdvisor | 全量容器历史资源指标，喂给 Prometheus | **常驻 ~50–100MB**【推断】；`--docker_only=true` 可裁剪 | **可选**：若 DomainProbe 需要"告警前 30 分钟内存爬升曲线"这类历史证据再上；否则 inspect+events 已够 |

- cAdvisor URL：https://github.com/google/cadvisor ；资源占用参考 https://www.dash0.com/guides/cadvisor-docker-monitoring （核对 2026-09-04）
- docker events/inspect 属于 Engine API，也可直接走 `/var/run/docker.sock`（只读挂载给 probe 容器即可，注意 socket 读写权限语义见 §3.3 坑）。

### 2.3 结论：自研 DomainProbe 扩展 vs 引入现成工具

**扩展自研 DomainProbe**。理由：①现成工具不存在（不是"不好"，是"没有"）；②所需数据 docker API 全有，规则（RestartCount 超阈/OOMKilled/health unhealthy/挂载源不存在/端口绑定冲突/内存使用率触限）都是几十行确定性代码；③与 AM2 已建的 DomainProbe 架构一致，证据格式统一进 RCA 报告。建议数据源组合：**docker inspect + docker events 为必做，cAdvisor 视内存预算可选**（7.5G 总内存下 50–100MB 可承受，但非必需）。

---

## 3. 安全门禁（替代 Kyverno/RBAC/Helm 的 docker 等价物）

### 3.1 docker-bench-security

- **维护状态**：docker 官方组织仓库，基于 CIS Docker Benchmark **v1.6.0**；commits 页面显示最近一次提交在 2026-06，仍在零星维护【明示】。注意：**Docker Hub 上的 `docker/docker-bench-security` 镜像已过期，README 明确要求从源码自行构建**【明示】。
- **能查出什么**：主机配置、daemon 配置（日志级别、icc、userns、SELinux/AppArmor、daemon.json 权限）、容器镜像与运行时配置（privileged、cap add、挂载敏感目录、端口映射、healthcheck、内存/CPU 限制、secret 在环境变量里等）数十项；支持 `-c`/`-e` 挑选检查项、`-i/-x` 按容器名过滤、输出 JSON 日志【明示】。
- **进部署门（DP 断言）方式**：一次性容器运行（非常驻，RSS 可忽略），解析 `docker-bench-security.log.json`，对选定的关键检查项（如 4.1 非 root、5.x privileged/cap/挂载/端口）做 PASS 断言。坑：该脚本需 `--net host --pid host` + 挂 `/etc`、`/var/lib`、docker.sock 等高权限挂载，**只能在部署门环节由 CI/运维身份运行，绝不能给 Agent**【明示+推断】。
- URL：https://github.com/docker/docker-bench-security （核对 2026-09-04）

### 3.2 conftest 给 docker-compose.yml 做策略检查

- **可行性结论：可行。** conftest 用 OPA Rego 对结构化配置（含 YAML）做测试，compose 文件解析为 `input.services.<name>` 后可写策略：禁 `privileged: true`、强制 `read_only: true`、禁 `0.0.0.0`/公网端口绑定、强制 `cap_drop: ALL`、强制 `no-new-privileges` 等【明示（conftest 能力）+推断（具体 Rego 写法）】。
- 工程化建议：先用 `docker compose config` 渲染（展开 env/override/extends）得到最终 YAML 再喂给 conftest，避免策略检查漏掉 override 引入的违规【推断，属通用实践】。conftest 为 CI 一次性 CLI，零常驻内存。
- 坑：Rego 有学习成本；策略集要小（5–8 条硬规则起步），别试图复刻 Kyverno 全量。
- URL：https://www.conftest.dev/ （仓库 open-policy-agent/conftest）（核对 2026-09-04）

### 3.3 docker 原生安全机制在内核 3.10 上的核对

| 机制 | 3.10 可用性 | 依据 |
|---|---|---|
| `cap_drop: ALL`（capabilities） | ✅ 完全可用。Docker 默认已用 allowlist 裁剪 capability | 【明示】https://docs.docker.com/engine/security/ |
| seccomp（默认 profile） | ✅ 可用。CentOS/RHEL 7 内核已 backport seccomp（`CONFIG_SECCOMP`）；注意默认 profile 在 <4.8 内核上对 `ptrace` 整体封禁（防绕过），属预期行为 | 【明示 ptrace 说明】https://docs.docker.com/engine/security/seccomp/ ；【推断 backport，RHEL7 事实标准】 |
| `no-new-privileges:true` | ✅ 内核 3.5+ 即支持 `no_new_privs`，3.10 满足 | 【明示】https://github.com/nestybox/sysbox/blob/master/docs/user-guide/security.md |
| `read_only` rootfs | ✅ 纯 docker 层特性，与内核版本无关 | 【明示】https://docs.docker.com/engine/security/ |
| docker secrets | ❌ **swarm 专属**，"only available to swarm services, not to standalone containers"。退化方案：compose 顶层 `secrets: file:` 会以文件挂载进容器 `/run/secrets/`，但**不落加密、不依赖 swarm**，本质是文件挂载+权限位 | 【明示】https://docs.docker.com/engine/swarm/secrets/ |
| userns-remap / rootless | ⚠️ 不推荐。RHEL7 的 user namespace 支持是裁剪版（需调 `user.max_user_namespaces`，且与 `--pid=host`/`--network=host`/`--privileged` 不兼容；开启后屏蔽 `/var/lib/docker` 既有镜像层，迁移成本高） | 【明示（限制清单）】https://docs.docker.com/engine/security/userns-remap/ ；【推断 RHEL7 可用性差】 |
| docker.sock 权限 | ⚠️ 无 RBAC 等价物：拿到 socket 即等价 root。约束手段=socket 不挂给 Agent 容器、对外暴露的 API 一律走 TLS/鉴权 | 【明示】https://docs.docker.com/engine/security/ |

### 3.4 gVisor

- **确认不可用**：官方安装文档明示 "gVisor supports x86_64 and ARM64, and **requires Linux 5.6+**"。内核 3.10 无法运行 runsc，无降级方案【明示】。
- URL：https://gvisor.dev/docs/user_guide/install/ （核对 2026-09-04）

### 3.5 结论："穷人版 Kyverno"是否成立

**成立。** 组合 = ①compose hardening 模板（cap_drop ALL + no-new-privileges + read_only + 默认 seccomp + 禁 privileged，3.10 全可用）→ ②conftest 策略检查（静态门，部署前）→ ③docker-bench-security 选定检查项进 DP 断言（运行时门，部署后）→ ④socket/端口暴露收敛（替代 RBAC 的粗粒度但有效的现实）。缺失项（对照 Kyverno）：无运行时 admission controller（容器起来后改配置拦不住，只能靠 bench 巡检发现）、无细粒度权限模型。对我们单靶场场景足够。

---

## 4. 打包/部署（替代 Helm）

compose 环境的参数化最佳实践就是官方三件套，无需额外工具：**.env 文件**（`--env-file` / `env_file`，承载环境差异参数）+ **多 compose 文件叠加**（`docker compose -f base.yml -f prod.override.yml`，后者覆盖前者）+ **profiles**（可选服务开关）；发布前用 `docker compose config` 渲染出最终 YAML 做评审/归档/喂 conftest【明示】。kompose 是 compose→K8s 方向，与我们无关。
URL：https://docs.docker.com/compose/how-tos/multiple-compose-files/ 、 https://docs.docker.com/compose/how-tos/environment-variables/ （核对 2026-09-04）

**一句话结论：Helm 不需要替代——env 文件 + compose override + profiles + `compose config` 渲染物进 git，即是穷人版 Helm chart。**

---

## 5. K8s 概念 → docker 现实映射表

| K8s 生态组件 | docker 单机现实 | 推荐 | 常驻内存（预估） |
|---|---|---|---|
| kagent-tools（Prometheus/容器工具） | prometheus/prometheus-mcp（ghcr，可裁剪纯只读）或 HolmesGPT 自带 toolset | 基线用 Holmes 自带；AM4 换框架再上 prometheus-mcp | 0（Holmes 进程内）/ 20–50MB |
| K8sGPT Analyzer | **无现成等价物**；数据在 docker inspect/events/stats（+可选 cAdvisor） | 扩展 AM2 DomainProbe | 0–100MB（cAdvisor 可选） |
| Kyverno（策略/准入） | conftest（OPA Rego 查 compose）+ hardening 模板 + docker-bench-security 进 DP | 三层组合即"穷人版 Kyverno" | 0（均为一次性 CLI/容器） |
| RBAC | 无细粒度等价物；docker.sock=root 等价 | socket 不挂 Agent、API 端点加 TLS/鉴权 | 0 |
| Pod Security Standards | compose 模板：cap_drop ALL / no-new-privileges / read_only / seccomp（3.10 全支持） | 模板化+conftest 强制 | 0 |
| K8s Secrets | docker secrets 仅 swarm；退化用 compose `secrets: file:` | 接受退化方案，配文件权限 | 0 |
| 沙箱运行时（gVisor） | **不可用**，官方要求内核 5.6+ | 放弃，靠 cap/seccomp/userns 之外的常规硬化弥补 | — |
| Helm | env 文件 + compose override + profiles + `compose config` | 官方三件套，不引入新工具 | 0 |
| NetworkPolicy | docker networks 分段 + 不发布端口 + 宿主机 firewalld | 够用 | 0 |

**内存预算小结**（195 共 7.5G）：推荐方案新增常驻 = 0（DomainProbe 扩展走 docker API）至多为 prometheus-mcp 20–50MB + 可选 cAdvisor 50–100MB，全部推荐项合计 **<150MB**，安全。
