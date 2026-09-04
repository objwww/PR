# 告警 AM0 部署验证设计（执行者用）v1.1

> **这份文档的唯一目的**：让执行者在 195 服务器上按步骤验证"告警平台路线"能否真实部署并跑通全链路。
> 它不是十二节技术方案（那是 G1 评审件，暂缓），而是**部署可行性的验证 Runbook**。
> 执行纪律：逐步执行、每步取证、失败即停并记录，不许跳步"凭经验修"。

---

## 0. 背景简报（执行者零上下文可读）

**项目**：本仓库正在从"AI Code Review Agent"转向"**交易域告警 RCA Agent**"——搭一条真实告警链路（故障注入 → 告警产生 → 聚合降噪 → Incident → AI 根因分析），后续里程碑再把自研 Java 控制面接进来。本次验证是这条线的第一个动作：不动任何代码，只把开源组件在真实服务器上装起来、串起来、打故障、看告警。

**为什么选这条链路**（执行者需要理解才能判断"什么算异常"）：

- 靶场用 **OpenTelemetry Demo（Astronomy Shop）**：开源微服务电商，自带 k6 持续购物流量和 feature flag 故障开关（如支付 50% 失败），故障是"真实"产生的，不是伪造告警文本。
- 告警规则用手写：**Sloth**（SLO-as-code 生成器）——我们只声明"checkout 服务可用性 99%"，它生成完整的多窗口 burn-rate 告警规则。Sloth 是一次性运行的二进制，不驻留服务器。
- 告警汇聚中台 A/B：**Alerta** 对 **Apache HertzBeat**——原先选定的 Keep 镜像只在 Google Artifact Registry（195 实测拉不通）已否决；对照组夜莺经交叉核查出局（它是告警引擎，收不了 Alertmanager webhook，详见 §4.3 判定表注）。Alerta 优势：docker.io 单容器、PostgreSQL 一等存储、内置 Alertmanager webhook 接收端点（`/api/webhooks/prometheus`，源码 `alerta/webhooks/prometheus.py` 已核）、去重/关联/静默内置、预估内存 100~250M；HertzBeat 优势：国产、UI 现代、分组收敛/抑制语义完整、自带 MCP（后置价值）。两者都有明确出局线，实测裁定。
- RCA 引擎：**HolmesGPT**（开源 SRE Agent），官方镜像同样在 GAR 拉不通，改用 `python:3.12-slim + pip install holmesgpt` 在 195 自建镜像；模型走阿里云百炼 OpenAI 兼容端点。
- RCA 引擎：**HolmesGPT**（开源 SRE Agent），官方镜像同样在 GAR 拉不通，改用 `python:3.12-slim + pip install holmesgpt` 在 195 自建镜像；模型走阿里云百炼 OpenAI 兼容端点。
- 195 的硬约束：CentOS 7 内核 3.10（一切依赖 eBPF/新内核的组件都没戏，Coroot 已因此被否）、内存 7.5G、公网 IP（端口不许裸暴露）。

**执行者的角色**：只执行、观测、取证。遇到与文档预期不符的情况，记录事实后停下来回报，**不自行修复、不改设计、不扩大范围**。

---

## 1. 范围边界

### 1.1 在本次范围内（可以做）

- 在 195 上新建并操作 `/opt/projects/alert_agent/` 目录（全部配置、证据、数据都在此目录树内）
- 拉取/构建 docker 镜像；新建独立 compose 项目（project 名 `alert`）与独立 bridge 网络
- 启停**本验证新建**的容器（otel-demo 系列、prometheus、alertmanager、hertzbeat、nightingale、echo-receiver、holmesgpt）
- 停启 `deploy-publisher-app-1`、`deploy-github-stub-1`（仅为腾内存，验证结束必须恢复）
- 在 OTel Demo 内注入故障（flagd 开关、`docker kill/start` demo 容器）
- 修改 HertzBeat/夜莺的初始默认口令（安全要求，新口令记入 195 的 `.env`，不进仓库不进证据）

### 1.2 明确不做（超出即越界）

- **不改任何 Java 代码、不动 git 仓库**（control-app 接入是后续里程碑，本期 webhook 落点用 echo 替身）
- **不碰存量数据库**：postgres 容器及其数据零接触；中台用各自内置存储（SQLite/H2），不在 PG 建库
- **不动 PR-Agent 存量栈的其他部分**：control-app、postgres 容器不停不改；`/opt/projects/pr_agent`、`/opt/build/pr` 只读
- 不引入文档外的新组件（缺什么记录需求，回报裁定）
- 不在公网暴露任何端口；不开防火墙新端口
- 不做性能压测、不做高可用、不做数据持久化方案（本期是可行性验证，不是生产部署）

### 1.3 禁区（任何情况下不许碰）

- 任何密钥的明文输出/落盘（`AGENT_MODEL_API_KEY`、中台口令）；日志取证前先 grep 确认无密钥
- 117 服务器（与本验证无关，且当前无访问权限）
- 宿主机的内核/系统参数修改（不需要，也不许）

---

## 2. 验证证据规范

### 2.1 存放与命名

- 根目录：`/opt/projects/alert_agent/smoke-evidence/`，**只增不删**
- 每步一个文件：`<步骤号>-<内容>-<YYYYMMDD-HHMMSS>.log`，如 `s1-holmesgpt-build-20260903-163000.log`
- 演练类证据按场景建子目录：`s8-payment-failure/`、`s8-container-kill/`

### 2.2 每条证据的要求

| 要求 | 说明 |
|---|---|
| 命令原文可见 | 证据文件头部附执行的完整命令（用 `script`/`tee` 或先 `echo` 命令再执行） |
| 原文输出 | 不截图转述、不"总结"输出；长输出完整保存 |
| 时间戳 | 每份证据带执行时间（`date` 开头） |
| 三态判定 | 每项判定只允许：✅ 通过（附证据路径）/ ❌ 失败（附报错原文路径）/ ⏭ 环境性跳过（写明原因） |
| 密钥检查 | 提交证据前 `grep -ri 'key\|token\|password' <文件>` 人工确认无泄漏 |

### 2.3 最终交付

- Go/No-Go 对照表（G1~G7 逐项三态 + 证据路径）——**这是路线生死的唯一判定依据**
- 中台 A/B 判定表（`s2-ab-verdict.md`）
- 实测数据清单：各容器 RSS、free/df 起止值、真实指标名、镜像 digest
- 证据自证：抽查命令可复现（主会会话随机抽 2 条证据要求重放）

---

## 0. 验证目标与判定

### 要验证的链路

```text
Astronomy Shop（flagd 故障注入）
  → otel-collector → Prometheus（Sloth 生成的 SLO 规则）
  → Alertmanager → HertzBeat（或夜莺，A/B 裁定）
  → webhook → echo-receiver
HolmesGPT（自建镜像）→ 百炼端点 → 对一条告警出 RCA 报告
```

### Go / No-Go 判定（全部 ✅ 才算路线成立）

| # | 判定项 | Go 标准 |
|---|---|---|
| G1 | 镜像供应链 | 所有镜像在 195 可拉/可构建（ghcr/docker.io 实测已过；HolmesGPT 自建镜像可构建） |
| G2 | 中台可用 | HertzBeat 或夜莺：稳态 RSS ≤ 800M、能收外部告警、能发 webhook |
| G3 | 靶场可用 | Astronomy Shop core 全容器 healthy，flagd 可开关故障 |
| G4 | 告警自动产生 | 开 `paymentFailure` 后，Sloth 规则 firing，无手写 PromQL 告警 |
| G5 | 全链路 | 告警 → Alertmanager → 中台 Incident → webhook 到 echo，自动完成 |
| G6 | RCA | HolmesGPT 经百炼端点对一条真实告警产出非空报告 |
| G7 | 内存 | 全栈稳态 `free -m` available ≥ 1G |

**任一 No-Go**：记录事实证据（命令输出原文），执行 §7 对应降级路径；降级也不行则路线在该点终止，回报主会话。

---

## 1. 环境前提（已勘察事实，执行前重读）

- 服务器：`ssh -i ~/.ssh/id_ed25519 root@146.56.195.225`（下称 195）
- CentOS 7，内核 3.10.0，docker 26.1.4，内存 7.5G（可用约 5.7G），磁盘余 29G
- **公网 IP**：所有 Web 端口只绑 `127.0.0.1` 或 docker 内网（INV-AM0-1），本地访问走 SSH 隧道
- 存量容器：postgres（healthy，**不许动**）、control-app（不许动）、publisher-app + github-stub（**本验证期间可停**：`docker stop deploy-publisher-app-1 deploy-github-stub-1`，验证完可恢复）
- docker 已配镜像加速：daocloud / 腾讯 / dockerproxy（docker.io 实测可拉）
- **已实测**：ghcr.io ✅（demo manifest 200）、quay.io ✅、docker.io ✅、**us-central1-docker.pkg.dev（GAR）❌ 不通**
- 密钥纪律：`AGENT_MODEL_API_KEY`（百炼）只经 env 注入，永不写入文档/日志/镜像
- 工作目录：`/opt/projects/alert_agent/`（新建），证据归档 `/opt/projects/alert_agent/smoke-evidence/`

---

## 2. S0 预检（10 分钟）

```bash
mkdir -p /opt/projects/alert_agent/{smoke-evidence,prometheus,alertmanager,sloth,holmesgpt,hertzbeat}
free -m && df -h / | tail -1
docker stop deploy-publisher-app-1 deploy-github-stub-1   # 腾内存，可逆
```

**取证**：`free/df/docker ps` 输出存 `smoke-evidence/s0-baseline.log`。

**已知通过的项**（主会话 2026-09-03 已测，执行者复核即可）：

```bash
# ghcr 可达（预期 200）
TOKEN=$(curl -s "https://ghcr.io/token?scope=repository:open-telemetry/demo:pull" | grep -o '"token":"[^"]*' | cut -d'"' -f4)
curl -s -o /dev/null -w '%{http_code}\n' -H "Authorization: Bearer $TOKEN" \
  -H 'Accept: application/vnd.oci.image.index.v1+json' \
  https://ghcr.io/v2/open-telemetry/demo/manifests/latest-frontend
```

---

## 3. S1 HolmesGPT 自建镜像（一票否决项 #1，约 20 分钟）

官方镜像在 GAR（拉不通），自建：

```bash
cat > /opt/projects/alert_agent/holmesgpt/Dockerfile <<'EOF'
FROM python:3.12-slim
RUN pip install --no-cache-dir holmesgpt -i https://pypi.tuna.tsinghua.edu.cn/simple
ENTRYPOINT ["holmes"]
EOF
docker build -t local/holmesgpt:am0 /opt/projects/alert_agent/holmesgpt/
docker run --rm local/holmesgpt:am0 --help | head -20
```

**通过判据**：镜像构建成功且 `holmes --help` 正常输出。
**失败降级**：改用 GitHub Release 二进制（`holmes-linux-amd64-*.zip`）裸跑；注意 CentOS 7 glibc 2.17 可能跑不动 PyInstaller 二进制，若二进制也不通 → **G1 No-Go，停止并回报**（RCA 引擎无容器化路径）。
**百炼连通性**（本步一并验证，key 从 195 既有 `.env` 或用户处获取，勿打印）：

```bash
docker run --rm -e OPENAI_API_KEY="$AGENT_MODEL_API_KEY" \
  -e OPENAI_API_BASE="<百炼 OpenAI 兼容端点 URL>" \
  local/holmesgpt:am0 ask "hello" --model="openai/qwen-plus"
```

**通过判据**：返回正常文本回答。**失败**：依次试 `openai/deepseek-v3`；都不行 → 记录报错原文回报（G6 受阻，但可继续后续步骤，RCA 环节留待解决）。
**取证**：构建日志、ask 输出存 `smoke-evidence/s1-*.log`（key 不入日志）。

---

## 4. S2 中台 A/B 实测（一票否决项 #2，约 1 小时）

> 候选更正（2026-09-03 交叉核查）：原对照组**夜莺出局**——一手资料与源码核查确认夜莺是"拉 Prometheus 数据评估自家规则"的告警引擎，**无 Alertmanager webhook 入站接收能力**，且告警聚合/抑制在商业版。对照组更换为 **Alerta**（证据：`alerta/webhooks/prometheus.py` 内置接收端点、PG 一等存储、docker.io 单容器在架）。

### 4.1 Alerta（候选 1）

```bash
docker pull alerta/alerta-web:latest   # 记录 digest
# Alerta 需要 PG/Mongo 存储：A/B 期间起一次性 PG 容器（禁区：不碰存量 PG 实例与数据）
docker network create alert-ab 2>/dev/null
docker run -d --name alerta-pg --network alert-ab -e POSTGRES_PASSWORD=alerta123 postgres:16-alpine
docker run -d --name alerta-ab --network alert-ab -p 127.0.0.1:8180:8080 \
  -e DATABASE_URL=postgres://postgres:alerta123@alerta-pg:5432/postgres \
  alerta/alerta-web:latest
sleep 30 && docker stats alerta-ab --no-stream --format '{{.MemUsage}}'
curl -s -o /dev/null -w '%{http_code}\n' http://127.0.0.1:8180/api/alerts   # API 可达
```

验证三项：
1. **稳态 RSS**：启动 5 分钟后 `docker stats`（含 alerta-pg 一并记录）；合计 > 800M 出局
2. **外部告警接入**：Alertmanager webhook 指向 `http://alerta-ab:8080/api/webhooks/prometheus`，发测试告警验证流入与去重（重复发同一条，应出 duplicate 计数而非新告警）
3. **webhook-out**：按官方文档配置 forwarder/插件把告警状态变化转发到 echo-receiver（§5）；若官方插件机制不通，记录事实（此为其已知弱项候选点）

### 4.2 HertzBeat（候选 2）

```bash
docker pull apache/hertzbeat:latest   # 记录实际 tag/digest
docker run -d --name hertzbeat-ab --network alert-ab -p 127.0.0.1:1157:1157 -p 127.0.0.1:1158:1158 apache/hertzbeat:latest
sleep 60 && docker stats hertzbeat-ab --no-stream --format '{{.MemUsage}}'
curl -s -o /dev/null -w '%{http_code}\n' http://127.0.0.1:1157/   # UI 可达
```

验证三项：
1. **稳态 RSS**：> 800M 出局（Spring Boot 应用，预估 400M~1G，是本候选最大风险点）
2. **外部告警接入**：查官方文档 `https://hertzbeat.apache.org/zh-cn/docs/help/alert_integration/` 配置 Alertmanager/Prometheus 告警接入（文档给接入端点与方式）；发测试告警验证流入与分组收敛
3. **webhook-out**：配置一条 webhook 通知到 echo-receiver（§5），触发测试告警验证到达

默认账号 `admin/hertzbeat`，**登录后立即改密**（INV-AM0-3）。

### 4.3 判定表（产出物，存 smoke-evidence/s2-ab-verdict.md）

| 维度 | Alerta | HertzBeat | 出局线 |
|---|---|---|---|
| 稳态 RSS（含存储） | ? | ? | >800M 出局 |
| AM webhook 入站 | ?（预期内置） | ?（预期官方支持） | 接不了出局 |
| 去重/关联/静默 | ?（预期内置 dup/correlate/blackout） | ?（预期分组收敛/抑制/静默） | 核心语义缺失降级 |
| webhook-out | ?（插件机制，待验） | ?（待验） | 发不了出局 |
| Incident 概念 | 无（只有告警状态机） | 无（有收敛事件） | 记录差距，AM1 控制面兜底 |
| 结论 | 胜者 = ? | | |

**双出局** → 降级：Alertmanager 直打 echo（链路不断，汇聚层空缺记入风险），继续后续步骤并回报。

---

## 5. S3 echo-receiver（5 分钟，webhook 落点替身）

control-app 的告警 webhook 是 AM1 才开发的，本期用 wiremock 替身：

```bash
docker run -d --name echo-receiver -p 127.0.0.1:9199:8080 wiremock/wiremock:3.9.1
curl -s http://127.0.0.1:9199/__admin/health   # 就绪
# 验证请求记录：curl -s http://127.0.0.1:9199/__admin/requests | head
```

---

## 6. S4~S6 靶场与告警产生（约 1.5 小时）

### S4 Astronomy Shop core 起栈

```bash
cd /opt/build && git clone --depth 1 https://github.com/open-telemetry/opentelemetry-demo.git
# github 不通的备选：本地下载 tarball 后 scp，或用加速前缀 https://ghproxy.com/ 类服务（时效性自负）
cd opentelemetry-demo
# 调整：LOCUST_USERS=3（改 .env）；不需要的服务可后续裁剪
docker compose -f compose.yaml up -d   # 仅 core 层，不叠 observability
watch docker ps   # 等全部 healthy
docker stats --no-stream   # 记录各容器 RSS
```

**通过判据**：core 全部 healthy；`ssh -L 8080:127.0.0.1:8080` 隧道后商城首页可打开、能下单。
**内存判据**：此刻 `free -m` available 应 ≥ 2G，否则记录并报备（G7 预警）。

### S5 精简 Prometheus（只叠这一个可观测组件）

不引入 demo 的 observability 全家桶。做法：参照 `compose.observability.yaml` 中 otel-collector 的 Prometheus 导出配置（`src/otel-collector/otelcol-config-observability.yml`），让 core 层 collector 暴露 Prometheus 指标端点；再起独立 Prometheus 抓它：

```yaml
# /opt/projects/alert_agent/prometheus/prometheus.yml（骨架，执行者按实测端口校正）
scrape_configs:
  - job_name: otel-collector
    static_configs:
      - targets: ['<otel-collector 地址>:<metrics 端口>']
rule_files:
  - /etc/prometheus/rules/*.yml
```

**通过判据**：Prometheus targets 全 UP；能查到 demo 服务的 HTTP 请求指标（指标名以实测为准，记录真实指标名——S6 的 SLO 声明要用）。

### S6 Sloth 规则生成（Sloth 不上 195）

在能访问 GitHub 的机器（开发机）：

```bash
# 下载 sloth linux binary：https://github.com/slok/sloth/releases
# slo-checkout.yml 骨架（指标名按 S5 实测结果填写）：
# version: prometheus/v1
# service: checkout
# slos:
#   - name: availability
#     objective: 99
#     sli:
#       events:
#         total_query: <total 请求表达式>
#         error_query: <错误请求表达式 status>=500>
./sloth generate -i slo-checkout.yml -o prometheus-rules-checkout.yml
scp prometheus-rules-*.yml root@146.56.195.225:/opt/projects/alert_agent/prometheus/rules/
```

195 上重启/热加载 Prometheus，`/api/v1/rules` 应见 Sloth 生成的多窗口 burn-rate 规则（page/ticket 双档）。

### S7 Alertmanager

```yaml
# /opt/projects/alert_agent/alertmanager/alertmanager.yml 骨架
route:
  group_by: ['alertname', 'service', 'severity']   # 只含稳定标签（INV-AM0-4）
  group_wait: 30s
  group_interval: 5m
  repeat_interval: 4h
  receiver: zhongtai
receivers:
  - name: zhongtai
    webhook_configs:
      - url: 'http://<A/B 胜者接入端点>'   # 按 S2 核查的官方文档填
```

**通过判据**：手工构造一条测试告警（或直接进行 S8 演练）→ Alertmanager 分组 → 中台收到。

---

## 7. S8 端到端演练 ×2（Go/No-Go 的 G4/G5/G6）

### 演练 1：业务链路故障

```bash
# 开 flag：ssh 隧道访问 flagd-ui（或改 src/flagd/demo.flagd.json 重启 flagd）
# paymentFailure = 50%
```

预期链：payment 半数失败 → checkout 错误率升 → Sloth burn-rate 告警 firing（5 分钟内）→ Alertmanager 30s 聚合 → 中台 Incident → webhook 到 echo → HolmesGPT 对这条告警出报告（报告应指向 payment 方向）。

### 演练 2：基础设施故障 + 恢复

```bash
docker kill payment
# 观察：可用性告警 + SLO 告警在中台被归并/抑制为一个 Incident
docker start payment
# 观察：resolved 到达，Incident 自动关闭
```

### 演练 3（降级项，可选）：HolmesGPT RCA

```bash
docker run --rm --network host \
  -e OPENAI_API_KEY="$AGENT_MODEL_API_KEY" -e OPENAI_API_BASE="<百炼端点>" \
  local/holmesgpt:am0 ask "为什么 checkout 服务错误率升高？" --model="openai/qwen-plus"
# 进阶：配置 prometheus toolset 后让其自助查询
```

**取证**：每次演练存 各层 API 查询输出 + echo 的 `__admin/requests` + HolmesGPT 报告原文 → `smoke-evidence/s8-<场景>/`。

---

## 8. 故障排查与降级速查

| 症状 | 先查 | 降级路径 |
|---|---|---|
| 镜像拉不动 | `docker pull` 报错原文；确认走了加速（`docker info` 看 Mirrors） | ghcr 走代理预拉 + `docker save/load` 摆渡 |
| demo 容器反复重启 | `docker logs <c>`；多数是内存不够 | `LOCUST_USERS=2`；裁 telemetry-docs；停夜莺对照组 |
| Prometheus 无指标 | collector 配置是否启用 prometheus exporter；targets 状态 | 直接抓 demo 服务自带 prometheus 端口（ad 服务 9465 等） |
| Sloth 规则不 firing | 指标名/标签与实测不符（OTel 语义版本差异） | 用 S5 记录的真实指标名修正 SLO 声明重新生成 |
| 中台 webhook 不通 | docker 网络：中台与 echo 是否同网络；用容器名互联 | 同 network 启动或 `--network host`（仅验证期） |
| HolmesGPT 调百炼失败 | 报错原文（401=key/端点；400=model 名格式；超时=网络） | 换 deepseek 系；再不通记录留 AM0 后续 |
| 内存 available < 1G | `docker stats` 找大户 | 停 load-generator 降流量；中台二选一后立即停掉败者 |

---

## 9. 清理与还原（验证结束后执行）

```bash
# 无论成败，靶场与验证容器全部可留可清，由主会话裁定；
# 必须动作：
docker start deploy-publisher-app-1 deploy-github-stub-1   # 还原存量栈（除非主会话另行指示）
# 证据目录保持不动，只增不删
```

---

## 10. 执行者交付物清单

1. `smoke-evidence/` 全目录（每步日志原文）
2. `s2-ab-verdict.md`（中台 A/B 判定表）
3. 每步 Go/No-Go 对照表（G1~G7 逐项 ✅/❌ + 证据路径）
4. 实测数据：各容器 RSS、free/df 终态、真实指标名清单、镜像 digest 清单
5. 所有失败点的报错原文（不许只写"失败了"）

> 执行者只执行与取证，不修复、不改设计；路线性问题（双出局/No-Go）回报主会话裁定。
