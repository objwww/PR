# deploy/alert/ — AM0 手工配置回收（G0-09）

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
