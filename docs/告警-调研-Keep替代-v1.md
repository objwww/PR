# 告警汇聚 / Incident 管理层 —— Keep 替代者调研 v1

> 调研日期：2026-09-03（所有"维护状态/镜像渠道"结论均以当日实测为准）
> 标注约定：【明示】= 官方文档/仓库/镜像仓库直接写明；【推断】= 基于一手资料推断；【未核实】= 未能拿到一手证据

## 结论（约 200 字）

**推荐用 Alerta 替代 Keep。** Alerta 是本层职责（接收 Alertmanager webhook、去重/关联、告警生命周期、Web UI、webhook 下发）匹配度最高且唯一全过硬约束的候选：官方镜像在 docker.io（`alerta/alerta-web`，单容器含 API+Web UI）；PostgreSQL 是一等公民存储（官方称新功能先测 Postgres）；内置 `/api/webhooks/prometheus` 直接吃 Alertmanager webhook；去重（duplicate）与关联（correlate）为核心内置机制；内置 forwarder 插件可把告警/动作转发到任意 HTTP 端点（即 webhook-out 到 Java 控制面）；Apache-2.0；v9.1.0 于 2026-03-28 发布，维护活跃。代价：Alerta 没有独立"Incident"概念（只有告警状态机 open/ack/shelve/close），Incident 归并语义需由控制面或借助 `group`/`service` 字段近似。次选 HertzBeat（能收 AM 告警、支持 PG，但 Java 内存偏大、UI 偏监控采集）。若坚持 Incident 语义，则接受 Keep 镜像摆渡/自建成本。

---

## 背景：这一层的角色（本轮纠偏）

链路：OTel Demo 靶场 → Prometheus(Sloth 生成 SLO 规则) → Alertmanager → **【本层】** → webhook → Java 控制面 → HolmesGPT。

本层职责：接收 AM webhook、去重/聚合/归并、Incident 生命周期管理、UI、webhook-out。**AI 能力不作为筛选条件**（第一轮误杀纯告警管理类候选，本轮纠正）。

硬约束：① 镜像在 docker.io/ghcr.io/quay.io 或可低成本自建；② CentOS 7 / 内核 3.10，不得依赖 eBPF；③ 常驻内存最好 ≤500MB；④ 优先 PostgreSQL 16 / SQLite，强依赖 MySQL/Mongo/ES 扣分；⑤ 近半年有提交或 release，开源许可（AGPL 标注）。

---

## 1. Alerta —— 重点候选，**胜出**

### 是什么
Python(Flask) 写的告警汇聚与去重控制台，定位即"consolidate and de-duplicate alerts from multiple sources"【明示，[docker-alerta README](https://github.com/alerta/docker-alerta)】。单镜像 `alerta/alerta-web` 内含 API 服务 + Web UI【明示，同上】。

### 接收 Alertmanager 告警的方式
内置 Prometheus/Alertmanager webhook 接收器：源码 `alerta/webhooks/prometheus.py` 解析 AM v4 webhook 格式（`status`/`labels`/`annotations`/`startsAt` 等），firing→按 `severity` label 定级、resolved→映射为 normal 级别【明示，[prometheus.py @ master](https://github.com/alerta/alerta/blob/master/alerta/webhooks/prometheus.py)】。
- URL 格式：`POST http://<alerta-host>:8080/api/webhooks/prometheus`【明示，BASE_URL 默认 `/api`，见 [Configuration](https://docs.alerta.io/configuration.html)】
- 鉴权：API Key（HTTP header `Authorization: Key <api-key>`）；也可关闭认证【明示，[API Reference](https://docs.alerta.io/api/reference.html)】

### 去重 / 聚合 / Incident 能力
- **去重（duplicate）内置**：`environment`-`resource`-`event` 三元组唯一，重复告警计 `duplicateCount` 不新增条目【明示，[API Reference — Create an alert](https://docs.alerta.io/api/reference.html)】
- **关联（correlate）内置**：`correlate` 字段声明相关事件名，firing/resolved 事件互相关联、收到 ok 自动关单；Prometheus webhook 支持从 label `correlate` 读取【明示，同上 + prometheus.py】
- **静默/屏蔽**：内置 blackout 插件（按属性 blackout 周期）【明示，[Configuration — Blackout Plugin](https://docs.alerta.io/configuration.html)】
- **生命周期**：告警状态机 open/ack/shelve/close/expired，支持 assign、tag、note【明示，API Reference】
- **Incident 概念：无**。Alerta 模型只有 Alert，没有"多个告警归并为一个 Incident"的一等对象；可用 `group`/`service` 标签做视图级归并【明示（模型中无 incident 实体），推断其影响】

### webhook-out（通知下游）
内置 **forwarder 插件**：`FWD_DESTINATIONS` 可把 alerts/actions 转发到任意 URL（官方示例含 httpbin.org 这类通用 HTTP 端点），支持 BasicAuth/API Key/HMAC/Bearer；动作粒度可选 `alerts`/`open`/`ack`/`close`/`*` 等【明示，[Configuration — Forwarder Plugin](https://docs.alerta.io/configuration.html)】。contrib 仓库另有 amqp/sns/slack/pagerduty 等 30+ 出站插件【明示，[alerta-contrib/plugins](https://github.com/alerta/alerta-contrib/tree/master/plugins)】。
→ 给 Java 控制面发 webhook：用 forwarder 指向控制面接收端点即可，零代码。

### 存储依赖
**PostgreSQL 或 MongoDB 二选一；官方明示"新功能先对 Postgres 测试，再移植到 Mongo"**【明示，[Configuration — Database Settings](https://docs.alerta.io/configuration.html)】。复用项目已有 PG 16 无悬念。无强制 Redis。

### 镜像渠道
`docker.io/alerta/alerta-web`，官方 automated build，累计 pull ~958 万，最近更新 2026-03-28【明示，[Docker Hub API](https://hub.docker.com/v2/repositories/alerta/alerta-web/)，2026-09-03 实测 HTTP 200】。

### 内存
官方未给数据【未核实】。Python + gunicorn/uwsgi + nginx 单容器，同类部署常驻 RSS 通常 100–250MB【推断】；500MB 约束内大概率安全，部署后实测确认。

### 维护状态
v9.1.0 发布于 2026-03-28；仓库最近 push 2026-06-19；未 archived；许可 **Apache-2.0**【明示，[GitHub API](https://api.github.com/repos/alerta/alerta)，2026-09-03】。

### 胜出理由
六项硬约束全过；本层五项职责中"接收 AM webhook / 去重聚合 / 生命周期 / UI / webhook-out"全部内置开箱即用。唯一缺口是无 Incident 一等概念——按本层角色定义（Incident 管理主要靠归并+生命周期呈现），用 `service`/`group` 归并视图 + 控制面侧聚合可接受。

---

## 2. 夜莺 Nightingale —— 出局（强依赖 MySQL+Redis，且不是"接收外部告警"的定位）

### 是什么
国产开源告警管理系统，定位是**告警引擎**：自己连时序库（Prometheus/ES/Loki/CK 等数据源）跑告警规则、生成告警事件、做屏蔽/订阅/通知【明示，[README](https://github.com/ccfos/nightingale)】。

### 接收 Alertmanager 告警的方式
官方文档与 README 均无"接收 Alertmanager webhook 告警"的集成方式；其工作模型是"我替你评估规则"，对标 Prometheus 告警引擎 + Alertmanager 的组合而非 AM 下游【明示（无此功能），[README](https://github.com/ccfos/nightingale)；推断：社区文章均描述其取代 AM 而非接收 AM】。链路中已有 Sloth→Prometheus→Alertmanager 产出告警，夜莺无法承接为下游。

### 去重/聚合/Incident
有屏蔽规则、订阅规则、事件流水线（event pipeline）、通知规则，v8+ 告警治理能力较强【明示，[n9e 文档站](https://n9e.github.io/zh/docs/prologue/introduction/)】；Incident 一等概念无【推断】。

### 存储依赖
**强依赖 MySQL + Redis**（配置/规则存 MySQL，target/心跳等存 Redis）【明示，多篇官方博客，如 [夜莺 v6 机器失联告警设计](https://flashcat.cloud/blog/nightingale-v6-host-nodata/)】；无官方 PostgreSQL 替代路径【明示（文档仅 MySQL）】。

### webhook-out
支持回调（Webhook）通知渠道，告警事件 JSON 可推自定义地址【明示，[夜莺 v7.7 手册 — 回调推送](https://www.bookstack.cn/read/nightingale-7.7-zh/c987b606fafe8672.md)】。

### 镜像渠道
`docker.io/flashcatcloud/nightingale` 存在且活跃（最近 tag 推送 2026-08-18，v9.1.1）【明示，Docker Hub API 实测，2026-09-03】。

### 内存
n9e 本体为 Go 单进程（镜像 ~271MB 压缩）【明示（镜像大小），Docker Hub API】；但需配套 MySQL(~300–400MB)+Redis → **整栈远超 500MB**【推断】。

### 维护状态
v9.1.1 发布于 2026-08-18，仓库 push 2026-09-03（当天），Apache-2.0【明示，GitHub API，2026-09-03】。

### 出局理由
① 定位错配：它是"告警引擎"而非"AM 下游的告警汇聚层"，不能接收 Alertmanager webhook；② 强依赖 MySQL+Redis，违反存储约束且内存超标。若要它发挥作用，需要把整条 Sloth→Prometheus 告警链路拆掉换成夜莺自己评估——超出本层职责范围。

---

## 3. HertzBeat（Apache） —— 次选（能力匹配但内存/定位有风险）

### 是什么
Apache 孵化器毕业的开源监控系统（采集+告警一体），近期版本内置"告警中心/告警集成"模块【明示，[告警集成文档](https://hertzbeat.apache.org/zh-cn/docs/help/alert_integration/)】。

### 接收 Alertmanager 告警的方式
**官方明示支持**："Alertmanager：支持将 Prometheus AlertManager 的告警发送到 HertzBeat 告警平台"；另有通用 Webhook 接入自定义格式【明示，同上】。

### 去重/聚合/Incident
官方明示的告警处理机制：**分组收敛（按标签分组、时间段内重复告警去重）、抑制、静默**【明示，同上】。Incident 一等概念无，以"告警"为主体【推断】。UI 为完整 Web 控制台（默认 admin/hertzbeat）【明示，[Docker 部署文档](https://hertzbeat.apache.org/docs/start/docker-deploy/)】。

### webhook-out
支持 webhook 等多种告警通知渠道（钉钉/飞书/邮件/webhook 等）【明示，告警通知文档体系；具体配置页未逐条核对 → 粒度【未核实】】。

### 存储依赖
默认内置 **H2**（文件库，挂载 volume 持久化即可，无外部依赖）；**官方文档支持用 PostgreSQL 替换 H2**（jdbc:postgresql，生产推荐）【明示，[Docker 部署](https://hertzbeat.apache.org/docs/start/docker-deploy/)、[PostgreSQL 替换 H2](https://hertzbeat.apache.org/zh-cn/docs/start/postgresql-change/)】。时序库（VictoriaMetrics 等）仅在需要历史图表时才配置，纯告警用途可不配【明示，部署 FAQ】。

### 镜像渠道
`docker.io/apache/hertzbeat` 官方镜像，最近更新 2026-09-03（当天）【明示，Docker Hub API 实测】；另有 `quay.io/tancloud/hertzbeat` 备用渠道【明示，部署文档】。

### 内存
官方未给【未核实】。Spring Boot Java 单体，同类应用常驻 400MB–1GB，可用 `-Xmx` 压到 ~400MB【推断】；**能否稳在 500MB 以内需要实测，是主要风险点**。

### 维护状态
v1.8.0 发布于 2026-01-31，仓库 push 2026-09-01，Apache-2.0（Apache 基金会项目）【明示，GitHub API，2026-09-03】。

### 落选理由
能力面（收 AM 告警+收敛+静默+UI+PG）其实够用，但：① 它的重心是"监控采集"，告警中心是子模块，UI 里大量采集相关噪音；② Java 内存占用在 7.5G 整机上是现实风险；③ 去重/抑制粒度官方文档描述较简，复杂归并规则能力弱于 Alerta 的 correlate 模型【推断】。作为 Alerta 万一验证不过的备选保留。

---

## 4. Karma —— 出局（只读 dashboard，无 Incident、无 webhook-out）

### 是什么
Alertmanager 的告警 dashboard（Go 单二进制），作者自述定位："Alertmanager UI 适合浏览告警和管理静默，但作为 dashboard 不足，karma 填这个空"【明示，[README](https://github.com/prymitive/karma)】。

### 能力边界（Karma + Alertmanager 组合）
- 聚合/去重：AM 本身已有 grouping/dedup/silence/inhibition【明示，[AM 官方文档](https://prometheus.io/docs/alerting/latest/alertmanager/)】；Karma 可聚合多个 AM 实例的告警并去重展示【明示，README】
- 静默：Karma 可管理 AM silence【明示，README】
- **它是只读视图**：数据来自 AM API，不存储、无自己的状态
- **无 Incident 概念**：没有告警生命周期（ack/close/assign）管理，没有"事件单"
- **无 webhook-out**：不给下游发任何东西；webhook-out 仍是 AM receiver 的职责

### 镜像渠道
**不在 docker.io**（`prymitive/karma` 实测 404）；在 **ghcr.io/prymitive/karma**（tags v0.74–v0.132 实测可列）【明示，ghcr v2 API 实测，2026-09-03】。ghcr 在目标服务器可拉性需实测【未核实】。

### 内存 / 维护
Go 小程序，常驻几十 MB【推断】；v0.132 发布于 2026-08-05，Apache-2.0【明示，GitHub API】。

### 出局理由
Karma+AM 组合能覆盖"聚合/静默/UI"的展示面，但本层要求的"告警生命周期/Incident 管理"和"汇聚后再发 webhook 给控制面（按处理后状态触发）"两项核心职责完全缺失。AM 直打控制面（webhook receiver）+ 控制面自己做 Incident，等于不要这一层——见第 7 节方案 C。

---

## 5. Zabbix / Checkmk / Icinga —— 快速过筛，全部出局

| 项目 | 能否收外部告警 webhook | 资源/依赖 | 结论 |
|---|---|---|---|
| **Zabbix** | webhook 是**出站**媒介类型（告警发出去），无内置"通用入站 webhook 告警接收"；被动接收要靠 trapper/HTTP agent item 迂回【明示，[Zabbix 文档 — Webhook media](https://www.zabbix.com/documentation/current/en/manual/config/notifications/media/webhook)】 | server+DB(支持 PG)+前端 PHP，常驻远超 500MB【推断】 | 出局：定位是全栈监控，不是告警汇聚层 |
| **Checkmk** | Event Console 接收 syslog/SNMP trap，非 HTTP webhook 告警模型【明示，[Checkmk 文档 — Event Console](https://docs.checkmk.com/latest/en/ec.html)】 | 站点式重型部署（Apache/Python/自带动静），远超预算【推断】 | 出局 |
| **Icinga 2** | REST API 可提交 passive check result（`process-check-result`），语义是"检查结果"而非告警/Incident；Icinga Web 是监控视图【明示，[Icinga 2 API](https://icinga.com/docs/icinga-2/latest/doc/12-icinga2-api/)】 | IDO 需 MySQL 或 PG，全套（core+web+DB）偏重【推断】 | 出局：无告警汇聚/Incident 语义 |

三者共同问题：都是"自己采集/自己评估"的监控系统，外部告警接入要么没有要么语义不符，且体量与 500MB/7.5G 约束冲突。

---

## 6. Keep 再核查

### 镜像渠道
- 官方 docker-compose 明确使用 `us-central1-docker.pkg.dev/keephq/keep/keep-ui` 与 `us-central1-docker.pkg.dev/keephq/keep/keep-api`【明示，[docker-compose.yml @ main](https://github.com/keephq/keep/blob/main/docker-compose.yml)，2026-09-03 拉取源码核实】
- docker.io 上 `keephq/keep`、`keephq/keep-backend` 均不存在（Docker Hub API 404/object not found）【明示，实测 2026-09-03】
- ghcr.io 上 `keephq/keep`、`keephq/keep-ui` 均 DENIED（无公开包）【明示，ghcr v2 API 实测】
- 第三方 mirror：Docker Hub 搜索未发现可信镜像【未核实充分，搜索接口当日限流/不稳定】
- websocket 组件 `quay.io/soketi/soketi` 在 quay.io 可公开拉【明示（compose 引用 quay 官方镜像）】

→ 结论维持：官方镜像**只在 Google Artifact Registry**，docker.io/ghcr 无渠道。

### 自建成本评估
- 仓库自带 `docker/Dockerfile.api`（python:3.13-alpine + poetry + 大量编译依赖 gcc/postgresql-dev/mysql-client）和 `docker/Dockerfile.ui`（node:20-alpine，`npm ci` + `npm run build`，**构建时需 `NODE_OPTIONS=--max-old-space-size=8192`，即 8G 堆**）【明示，[docker/Dockerfile.api](https://github.com/keephq/keep/blob/main/docker/Dockerfile.api)、[docker/Dockerfile.ui](https://github.com/keephq/keep/blob/main/docker/Dockerfile.ui)】
- 评估：UI 构建在 7.5G 目标机上**不可行**（Node 构建就要 8G 堆），须在本地/CI 构建后推送私有 registry 或 save/Load 摆渡；API 镜像 pip 编译依赖多，构建估 10–30 分钟【推断】
- 运行时 3 容器（keep-api FastAPI + keep-ui NextJS + soketi），合计常驻大概率 >500MB【推断】

### 存储
默认 SQLite（`DATABASE_CONNECTION_STRING=sqlite:///...`）【明示，[docker-compose.common.yml](https://github.com/keephq/keep/blob/main/docker-compose.common.yml)】——存储约束其实能过。

### 许可
核心代码 **MIT**，`ee/` 目录为企业版许可【明示，[LICENSE](https://github.com/keephq/keep/blob/main/LICENSE)】；GitHub 标记 NOASSERTION（因混合许可）。

### 维护
v0.54.2 发布于 2026-07-13，push 2026-09-02【明示，GitHub API】。

### 判定
Keep 能力仍最贴（有 Incident 一等概念），若 AM0 必须有 Incident 语义：可行路径是"本地 docker build + docker save/摆渡"（非 Google AR 直连），成本为一次性 1–2 小时 + 每次升级的重复摆渡【推断】。否则用 Alerta 更省事。

---

## 7. 其他候选/方案

- **Cabot**（arachnys/cabot）：最后 push 2023-09-10，超两年无提交【明示，GitHub API】→ 出局（维护约束）。
- **Alertmanager 自身能力边界**：grouping/dedup/silence/inhibition 内置，webhook receiver 可直打控制面【明示，[AM 配置文档](https://prometheus.io/docs/alerting/latest/configuration/)】。AM 缺的正是本层职责的另一半：无持久化告警台账、无生命周期操作 UI、无跨次归并。→ "不要这一层"是可行简化方案（方案 C），代价是 Incident 归并和台账全部落到 Java 控制面自研。
- **Grafana（OSS）**：OSS 版内置告警即 Alertmanager 语义；Grafana Incident 为 Cloud 商业功能，OSS 自建无 Incident 管理【未核实其 2026 年最新产品政策，建议不依赖】→ 出局。
- **Netflix Dispatch**：定位 incident 管理流程（协调人、文档、任务），非告警汇聚入口，且依赖 PG+多个服务，体量重【推断】→ 出局。

---

## 8. 对比表

| 候选 | 收 AM webhook | 去重/聚合 | Incident | webhook-out | 存储 | 镜像渠道 | 内存(估) | 维护 | 许可 | 结论 |
|---|---|---|---|---|---|---|---|---|---|---|
| **Alerta** | ✅ 内置 | ✅ dup+correlate+blackout | ⚠️ 无，状态机代替 | ✅ forwarder 插件 | **PG 一等** / Mongo | docker.io 官方 | 100–250MB【推断】 | 2026-03 release | Apache-2.0 | **胜出** |
| HertzBeat | ✅ 官方支持 | ✅ 分组收敛/抑制/静默 | ⚠️ 无 | ✅ webhook 通知 | H2 默认，可换 **PG** | docker.io + quay | 400MB–1G【推断，风险】 | 2026-01 release | Apache-2.0 | 次选 |
| Nightingale | ❌ 定位不符 | ✅（自家引擎内） | ⚠️ 无 | ✅ 回调 | **强依赖 MySQL+Redis** | docker.io | 整栈>700MB【推断】 | 活跃(当天 push) | Apache-2.0 | 出局 |
| Karma(+AM) | —（读 AM API） | ⚠️ 展示级 | ❌ | ❌ | 无存储 | ghcr.io | 几十 MB | 活跃 | Apache-2.0 | 出局 |
| Zabbix/Checkmk/Icinga | ❌/语义不符 | — | — | — | 重 | — | 超重 | — | — | 出局 |
| Keep | ✅ | ✅ | ✅ 一等概念 | ✅ workflow | SQLite 默认 | **仅 Google AR** | >500MB(3 容器)【推断】 | 活跃 | MIT+ee | 需摆渡/自建 |
| Cabot | — | — | — | — | — | — | — | 停更 2023 | — | 出局 |

---

## 9. AM0 链路图修改建议（选 Alerta 方案）

原链路（Keep 版）：

```
Prometheus → Alertmanager → Keep(Google AR 镜像×3 + SQLite) → webhook → Java 控制面 → HolmesGPT
```

改为（Alerta 版）：

```
Prometheus → Alertmanager ──webhook──> Alerta(alerta/alerta-web 单容器, docker.io)
                                           │  存储: 复用现有 PostgreSQL 16 (DATABASE_URL)
                                           │  去重/关联/静默: 内置 dup/correlate/blackout
                                           │  UI: http://<host>:8080/ (告警台账/ack/shelve)
                                           └── forwarder 插件 FWD_DESTINATIONS ──webhook──> Java 控制面 → HolmesGPT
```

Alertmanager 侧只需新增一个 receiver：

```yaml
receivers:
  - name: alerta
    webhook_configs:
      - url: http://alerta:8080/api/webhooks/prometheus
        http_config:
          authorization:
            type: Bearer            # 或 header: Authorization: Key <api-key>
            credentials: <api-key>  # 【推断】具体 header 形式以 Alerta 认证配置为准
```

注意点：
- Alerta 无 Incident 实体，AM0 的"Incident 归并"语义改为：Alerta 负责告警级去重/关联/静默，Incident 聚合（多告警→单事件单）在 Java 控制面实现，或短期用 `service`/`group` 标签视图近似。
- 若评审坚持 Incident 一等概念，则退回 Keep：本地构建 keep-api/keep-ui 镜像（UI 构建需 ≥8G 内存机器，不能在目标机上构建）→ docker save → 目标机 load。
