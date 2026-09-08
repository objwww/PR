# deploy/alert/ — AM0 手工配置回收（G0-09）

> **M6-07 Holmes 退场（2026-09-09）**：`holmesgpt` 服务与 `holmesgpt/` 构建目录已
> 从本目录摘除（服务块 + 195 容器 holmesgpt-am1 + alert-net 别名 holmes）；
> `litellm`/`litellm-bootstrap`/prometheus/alertmanager 全部保留（C-63：模型出口
> 不属退场范围）。本文余下对 holmesgpt 的描述为 **AM0~M6-06 历史记录**，重部署时
> 以 `docker-compose.yml` 现行文件为准；回滚制品=git tag `pre-holmes-removal` +
> 195 `/opt/backups/pre-m607-holmes-removal/`（镜像 digest + compose/.env 封存）。
> 依赖顺序末段"→ `holmesgpt`"自退场起不存在，终点=`litellm`。

AM0（告警链路底座：独立 Prometheus + Alertmanager + HolmesGPT）部署验证期在
195 上以散置手工文件拉起（`/opt/projects/alert_agent/`）。本目录把其中**配置真源**
回收入 git，结束"只在服务器上、不在仓库里"的状态。

## 回收清单与实机哈希（SHA-256，逐一 diff 一致 = G0-09 验收）

| 仓库文件 | 195 实机路径 | SHA-256（前 16 位） |
|---|---|---|
| `prometheus/prometheus.yml` | `/opt/projects/alert_agent/prometheus/prometheus.yml` | `61e3dbec7d71791e` |
| `prometheus/rules/prometheus-rules-checkout.yml` | `/opt/projects/alert_agent/prometheus/rules/prometheus-rules-checkout.yml` | `f8d987549a26cb4a` |
| `prometheus/rules/empty-groups.yml` | `/opt/projects/alert_agent/prometheus/rules/empty-groups.yml` | `761adf8d97e15214` |
| `alertmanager/alertmanager.yml` | `/opt/projects/alert_agent/alertmanager/alertmanager.yml` | `06625eba5b5b8468` |
| `holmesgpt/Dockerfile` | `/opt/projects/alert_agent/holmesgpt/Dockerfile` | `a0d82955537f516d` |
| `otelcol/otelcol-config-extras.yml` | `/opt/projects/alert_agent/otelcol-config-extras.yml` | `4ebc7cbbe868cf6e` |
| `otelcol/compose.am0-override.yaml` | `/opt/projects/alert_agent/compose.am0-override.yaml` | `fbab6770bf547455` |

> 校验命令（195）：`sha256sum /opt/projects/alert_agent/{...}` 与本地
> `Get-FileHash -Algorithm SHA256` 对拍。回收时间：2026-09-04。

## 说明

- **sloth 规则**：`prometheus-rules-checkout.yml` 是 Sloth v0.16.0 生成的
  checkout 可用性 SLO（objective 99%，page/ticket 双窗口烧损告警），文件头
  标注 DO NOT EDIT——源头是 sloth SLO spec，不在本次回收范围（AM0 手工生成）。
- **otelcol 两个文件**：Prometheus 的指标来源是 otel demo 栈 collector 加装的
  prometheus exporter（9464），`otelcol-config-extras.yml` 是其配置增量、
  `compose.am0-override.yaml` 是 demo 栈端口锁 127.0.0.1 的 override
  （INV-AM0-1 公网零暴露）。它们不属于告警栈本身，但没有它们 Prometheus 抓
  不到指标、安全姿态不可复现，故一并回收。
- **`.env.holmes`（195）不回收**：内含 `AGENT_MODEL_API_KEY` 等真实密钥，
  按密钥纪律永不入 git/文档/日志。本地用 `deploy/.env.example` 同名键。
- **alertmanager webhook 现状**：指向 `echo-receiver:8080/am0-webhook`
  （AM0 钻探用的回声接收器）。G0-10 把它切到 control-app 的
  `/api/webhook/alerts` 并带 `Authorization: Bearer ${ALERTMANAGER_WEBHOOK_BEARER_TOKEN}`。
- **holmes 容器形态**：AM0 实测为常驻空闲容器（sleep 循环 + docker exec CLI）。
  control-app 走 HTTP `/api/chat`（技术方案 §6.5），G0-10 部署时按方案调整
  启动命令，本骨架先忠实记录实测参数。

## AM3：LiteLLM proxy 收口（M3-24）

模型调用统一收口 `litellm` 容器（§6.6，P-12 账本盲区）：holmes 的
`OPENAI_API_BASE=http://litellm:4000/v1`，**proxy 不可达即调查失败（fail-closed），
无直连百炼回退路径**（E2E-M3-06 断言面）。镜像/坑位/预算硬拦证据：
`docs/测试证据/AM3/spike-holmes-budget/`（holmes 镜像内装 litellm[proxy] 缺 Prisma
起不来 → 官方镜像 `litellm/litellm:1.89.0`，与 holmes 内 litellm 客户端同版）。

**.env 新增键**（全部仅 env 注入，INV-AM3-3 同纪律）：

| 变量 | 消费方 | 说明 |
|---|---|---|
| `LITELLM_MASTER_KEY` | litellm | proxy 管理面（`/key/generate`、`/spend/logs`） |
| `LITELLM_DB_PASSWORD` | litellm-bootstrap / litellm | litellm 台账库（SpendLogs + 虚拟 key，Prisma 自建表） |
| `LITELLM_RUN_KEY` | holmesgpt | per-EvalRun 虚拟 key（见下） |

**per-EvalRun 虚拟 key 流程**（对账链② + 预算硬拦；holmes 是常驻单容器，metadata/
key 都是实例级静态注入——spike §二.3，换 run 必须重灌 key 重启 holmes）：

```bash
# 1) 以 master key 铸 run key（alias = eval run id；max_budget = 单 run 硬拦预算）
curl -s -X POST http://127.0.0.1:4000/key/generate \
  -H "Authorization: Bearer $LITELLM_MASTER_KEY" -H 'Content-Type: application/json' \
  -d '{"key_alias":"<eval_run_id>","max_budget":0.5,"metadata":{"purpose":"am3-eval-run"}}'
# 2) .env 更新 LITELLM_RUN_KEY=<上步 key>，然后：
docker compose up -d --force-recreate holmesgpt
# 3) 跑批结束后 eval-runner 出三态对账账（配置 app.alert.eval.litellm.*，见 control-app）
```

**日志卷 600 纪律**：`litellm-logs` 卷（`/app/logs`）可能含请求面痕迹（proxy 日志敏感面，
AM3 残余风险③）——宿主侧以 `chmod 600` 管控，禁止宽松挂载目录；proxy 保持默认
INFO 级，**不开 verbose 请求体日志**。

**依赖顺序**：主栈 postgres → alert 栈 `litellm-bootstrap`（幂等 psql 建角色/库）→
`litellm`（首启 Prisma migrate，start_period 120s）→ `holmesgpt`。
