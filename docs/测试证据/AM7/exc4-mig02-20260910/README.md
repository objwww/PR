# EX-C4 / MIG-02 后半：Gatus 上 127 真部署 + 六项验收 + 断链演练证据

- **日期**：2026-09-10（UTC 时间戳见各文件）
- **前半**（契约修正+镜像实测五项，打捕获监听）：`../HOST2-127/gatus-v5170-契约修正/`
- **本目录 = 后半**：接收器换成 duty-adapter 的真部署面 + 断链演练 + ⑥业务码 E2E

## 六项验收（全 PASS）

| # | 验收项 | 结论 | 证据 |
|---|---|---|---|
| 1 | 启动日志读 /config/config.yml | PASS | gatus-log-excerpts.log 行 1~2：Reading configuration from configFile=/config/config.yml + Validated 5 endpoints |
| 2 | 无镜像示例 endpoint | PASS | statuses-final.json 恰 5 项目 endpoint；镜像自带 front-end/back-end/monitoring/nas/example-dns-query/icmp-ping/check-domain-expiration 零出现 |
| 3 | 失败告警达 adapter | PASS | 09:14:58 真 watchdog TRIGGERED（control_health+duty_chain_snapshot 双 502 三败）→ sink 两对 firing POST；Phase A hook 试射 202 delivered:true |
| 4 | 恢复回执 | PASS | 09:17:58 RESOLVED 双端点（sink 147B/141B）+ 195 台账 resolved 行（firing/resolved 各自成行=契约①） |
| 5 | 探针死亡告警 | PASS（边界如实） | 09:21:29 adapter_health 3 败 TRIGGERED 尝试投递 → `connect: connection refused` 留 gatus 日志；v5.17.0 触发投递失败不锁存→恢复无配对 RESOLVED（无假回执）；兜底=docker restart=unless-stopped 自动拉起（09:24:00 health 复 200 实测） |
| 6 | 业务码失败不被误判成功 | PASS | rl-bot HTTP 200 + errcode_45009 → DUTY_CHANNEL_FAILED detail=errcode_45009:api rate limited → 降级 echo-bot 送达 202；负样本 env_missing 确定性失败 |

## 断链演练时序（MIG-02，UTC）

| 时刻 | 事件 |
|---|---|
| 09:13:51 | 195 `docker stop deploy-control-app-1` |
| 09:14:58 | Gatus 双探针（control_health/duty_chain_snapshot）3 败 TRIGGERED → adapter → rl(45009 判败)→echo 送达（sink 145B/139B 两对）；**回写失败→spool 落盘** |
| 09:16:54 | 195 `docker start deploy-control-app-1` |
| 09:17:31 | adapter spool 重放，两 firing 行落 195 台账（送达 09:14:58 ≠ 落账 09:17:31，时间线自洽） |
| 09:17:58 | Gatus 2 胜 RESOLVED 直写（sink + 台账 resolved 行） |
| 09:20:03 | 127 `docker stop duty-adapter`（⑤探针死亡） |
| 09:21:29 | adapter_health TRIGGERED，投递 connection refused（自指边界）；09:24:00 restart 自动拉起复 200，无假回执 |

## 文件清单

| 文件 | 内容 |
|---|---|
| `README.md` | 本文件 |
| `statuses-final.json` | 五 endpoint 终态 statuses 原件（22.6KB，/api/v1/endpoints/statuses） |
| `gatus-log-excerpts.log` | 配置读取/Validated 5/各 TRIGGERED/RESOLVED/投递失败行摘录 |
| `sink.log` | 演练 sink 全程 POST 日志（/rl 45009 沉淀 + /ok 送达沉淀） |
| `ledger-gatus.txt` | 195 duty_notification source=GATUS 行速查（firing/resolved） |
| `m719-proxy.log` | 195 窄反代八探针矩阵 + rl-bot 种子（含 uq_duty_channel_priority 腾位序） |
| `m719-127.log` | 127 sink/adapter/Gatus 部署日志 + B-58 rebind 修正 |
| `m719-drill127.log` | Phase A hook 试射（首射 503 链尽=B-58 现场；重射 202 全绿） |

## 部署终态（127）

- `gatus-gatus-1`：twinproduction/gatus@sha256:a8c53f9e…d61512（digest pin），UI
  127.0.0.1:8081，sqlite /data（named volume gatus-data）
- `duty-adapter-duty-adapter-1`：+K_DUTY_RL_WEBHOOK/K_DUTY_TEST_WEBHOOK env（指 sink）
- sink（python3，10.250.250.2:18080）：D01 测试机器人替身在岗；真机器人 URL 注入=运维面
- 195 窄反代：恰 2 duty 路径 + 3 精确 GET 健康路径；负向 /api/rca-runs=404 复验
