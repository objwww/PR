# Gatus v5.17.0 三项契约冲突修正 + 镜像实测启动（MIG-02 前半）验收证据

- **日期**：2026-09-09（UTC 时间戳见各 log 首行）
- **主机**：127（117.72.208.68，2C4G 故障域）；**195 全程未触碰**
- **测试形态**：独立 compose project `gatus-contract-test`，目录 `/opt/gatus-contract-test/`，UI 端口 `127.0.0.1:8081`，捕获监听 `:18080`（宿主机 python3 http.server，POST/GET 全部落 `/tmp/gatus-hook.log`）
- **镜像**：`twinproduction/gatus:v5.17.0`，digest `sha256:a8c53f9e9f1a3876cd00e44a42c80fc984e118d5ba0bdbaf08980cb627d61512`（127 实拉一次成功，47.9MB），已 pin 进 `deploy/gatus/compose.gatus.yml`
- **生产纪律**：127 此前无任何 gatus 容器/镜像（先 `docker ps -a | grep -i gatus` 只读确认）；未删除任何既有文件/容器/镜像；所有改动留工作区未 commit

## 验收结论：五项全 PASS

| # | 验收项 | 结论 | 证据 |
|---|---|---|---|
| 1 | 启动日志明确读 /config/config.yml | **PASS** | `phase-firing.log` 行 8：`[config.LoadConfiguration] Reading configuration from configFile=/config/config.yml`（三次重启均有同款行） |
| 2 | statuses API 只含本项目 endpoint，无镜像示例 | **PASS** | `statuses-firing.json`（4 = 3 项目 endpoint + contract-probe）、`statuses-final.json`（恰 3 个：`RCA_SYSTEM_control_health`、`RCA_SYSTEM_alertmanager_health`、`business_canary_arena_frontend`）；镜像自带的 front-end/back-end/monitoring/nas/example-dns-query/icmp-ping/check-domain-expiration 全部未出现 |
| 3 | 探针失败达阈值触发 custom 告警（firing body 留档） | **PASS** | `phase-firing.log` 行 27：`Sending custom alert ... has been TRIGGERED`；`gatus-hook.log` 行 40-48 捕获 POST body（`"status": "TRIGGERED"`，6 占位符全部展开）。阈值 2 连败 × interval 10s ≈ 20s 触发，仅发 1 条（Send 200 后 Triggered 锁存，无重发） |
| 4 | 探针改指存活目标触发恢复（resolved body 留档） | **PASS** | `phase-resolved.log` 行 25：重启后 `Loaded 1 persisted triggered alerts`（sqlite 持久化生效）；行 41：2 连胜后 `Sending custom alert ... has been RESOLVED`；`gatus-hook.log` 行 72-80 捕获 `"status": "RESOLVED"` body |
| 5 | 删探针重启后终态只含 3 endpoint | **PASS** | `phase-final.log` 行 17-19：`Validated 3 endpoints` + `Deleted 1 endpoint statuses because their matching endpoints no longer existed`；`statuses-final.json` 恰 3 endpoint |

## 三项契约修正的最终写法（写法细节见 `deploy/gatus/gatus-config.yml` 注释）

1. **alerting.webhook → alerting.custom**：`url`（env 注入，指向值班接收器，永不指向 control-app）+ `method: POST` + `headers: Content-Type: application/json` + `body` JSON 模板。占位符实测可用全集 6 个：`[ALERT_TRIGGERED_OR_RESOLVED]`（TRIGGERED/RESOLVED）、`[ENDPOINT_NAME]`、`[ENDPOINT_GROUP]`、`[ENDPOINT_URL]`、`[ALERT_DESCRIPTION]`、`[RESULT_ERRORS]`。**无 `[ALERT_NAME]`**。
2. **storage.type: file → sqlite**（`path: /data/gatus.db`，compose 配 named volume `gatus-data`）。官方只认 memory/sqlite/postgres。
3. **每个 endpoint 补 `alerts:` 块**（`- type: custom`）：3 个项目 endpoint 继承 `default-alert`（failure-threshold 3 / success-threshold 2 / send-on-resolved true，理由：单抖动不扰值班、阈值内恢复同报）；临时探针 contract-probe 显式小阈值（2/2/true，interval 10s，~20s 出 firing 缩短验证窗口）。

## 第 4 个实测发现的坑（超出评审三项，已一并修正）

**镜像自带示例配置抢加载**：v5.17.0 镜像 FROM scratch，Dockerfile 第 16 行把示例 `config.yaml`（7 个示例 endpoint）烤入 `/config/config.yaml`；Gatus 加载顺序 = `GATUS_CONFIG_PATH` → `config/config.yaml` → `config/config.yml`。原 compose 挂载的是 `/config/config.yml`（.yml 后缀），不显式设 `GATUS_CONFIG_PATH=/config/config.yml` 时容器会加载镜像示例配置而弃用挂载文件。已在 `deploy/gatus/compose.gatus.yml` 补 `GATUS_CONFIG_PATH: /config/config.yml`（验收第 1 项的日志行即其生效证据）。

## 已知坑（实测证实，写入配置注释供 duty-adapter 参考）

`[RESULT_ERRORS]` 原样注入、不做 JSON 转义。连接类错误串自带双引号，firing body 因此**非严格 JSON**（见 `gatus-hook.log` 行 47：`"errors": "Get "http://127.0.0.1:1/": dial tcp ..."`）。duty-adapter 须容错解析：先读 `status` 字段判状态，`errors` 视为不透明文本。RESOLVED body（errors 空）为严格 JSON。

## 证据文件清单（本目录）

| 文件 | 内容 |
|---|---|
| `pull.log` | 127 上 `docker pull twinproduction/gatus:v5.17.0` 全输出 + `docker images --digests` + RepoDigests |
| `phase-firing.log` | 起 compose 后 50s：compose ps、启动日志（读配置行）、statuses API 全文、hook log（含 TRIGGERED body） |
| `statuses-firing.json` | firing 阶段 `/api/v1/endpoints/statuses` 原件（4 endpoint：3 项目 + 探针，探针 5 连败记录） |
| `phase-resolved.log` | 翻转探针目标 + 重启后：重启日志（Loaded 1 persisted triggered alerts）、hook log 尾部（含 RESOLVED body） |
| `statuses-resolved.json` | resolved 阶段 statuses 原件（探针转 success） |
| `phase-final.log` | 恢复终态配置 + 重启后：`Validated 3 endpoints`、`Deleted 1 endpoint statuses`、清理过程（down -v、镜像保留、hook 停止） |
| `statuses-final.json` | 终态 statuses 原件（恰 3 个项目 endpoint） |
| `gatus-hook.log` | 捕获监听全程原始日志（GET 探测 + firing/resolved 两次 POST body） |

## 127 终态

- 运行容器：无（`docker compose down -v`，测试 volume/网络一并删除；见 `phase-final.log`）
- 镜像：`twinproduction/gatus@sha256:a8c53f9e…d61512` 保留（后续 MIG-02 部署复用）
- 目录：`/opt/gatus-contract-test/`（config.yml 已恢复终态版、compose.yaml、config-ok.yml、config-final.yml、hook.py）
- 本仓库改动（未 commit）：`deploy/gatus/gatus-config.yml`（三项修正 + 终态 3 endpoint）、`deploy/gatus/compose.gatus.yml`（digest pin + GATUS_CONFIG_PATH）

## Deviations / 备注

1. 127 无既有 gatus 容器/镜像，未触发"独立 project 名隔离"应急预案（但测试仍用了独立 project 名 `gatus-contract-test`）。
2. UI 端口沿用 compose 原定 `127.0.0.1:8081`（127 实测空闲）；捕获监听用 `18080`（空闲）。
3. 官方文档站 gatus.io 为 JS 渲染，正文抓取受限；契约依据以 **tag v5.17.0 源码直查**为准（与 E-24 取证口径一致），文档页 URL（`gatus.io/docs/alerting-custom`、`gatus.io/docs/endpoints`）以站点 sitemap 与搜索快照核实存在，详见 `docs/告警-OSS-证据清单.md` E-23。
4. sqlite 落盘文件本身未直接 `ls` 验证（FROM scratch 镜像内无 `ls`/shell，`docker exec ls` 失败，见 `phase-final.log` 行 36）；改以行为证据代替：重启后 `Loaded 1 persisted triggered alerts`（memory 存储无持久化，唯 sqlite 可解释）+ `Deleted 1 endpoint statuses`。
5. 配置热重载（30s 轮询）未依赖，三次配置切换均用 `docker compose restart` 保证确定性；`cat > file` 原地写规避单文件 bind-mount inode 陷阱。
6. `.env.example` 未改动（env 契约不变：`GATUS_ONCALL_WEBHOOK_URL` 等 4 变量照旧）。
