# 195 内存水位实测基线（P7）—— 2026-09-05 12:40 CST 第三方独立复测版

> 任务：`docs/告警-并行任务-七项备料.md` **P7**。纯只读测量（`free -m` ×4 / `df -h` / `docker ps -a` / `docker stats --no-stream` ×2 采样 / `docker inspect` limits / `ps aux --sort=-rss` / Prometheus query API），零改动、零新增容器、零重启、零文件落 195。
> 版本说明：本路径当日已有 11:08 / 11:25 两版，本版为同日 12:40 由第三方执行 agent 独立重测重写。**三轮结论方向完全一致**（水位健康、AM2 已被基线包含、AM3 最坏可容纳、阈值对齐架构 §12.2 原文），数据以本版为准。相对 11:25 版的增量：chaos-admin 预热已满（135.7→154.6M，印证上版"按 ≤170M 封顶"的保守估计）；prometheus/postgres 回落属正常波动；新增发现 containerd-shim 每容器 ~8.7M 的附带成本（29 个 ≈250M）与 mvn-gate 一次性构建容器（exit 0，非残留）。
> 实测时点栈：AM0 告警栈（holmesgpt/control-app/postgres/prometheus/alertmanager）+ AM2 双容器（order-arena、chaos-admin，**均已在线**）+ arena-e2e-cli（e2e CLI 会话容器）+ OTel Demo 全家桶，共 **29 常驻容器**（另 3 个 Exited(0) 一次性任务：两个 flyway 迁移 + mvn-gate maven 构建，非残留）。docker server 26.1.4，up 36 天，4 vCPU，load 0.64/0.76/0.95（低）。

---

## 1. 总量水位（free -m，4 秒间隔 4 采样）

| 指标 | 实测 | 判读 |
|---|---|---|
| 内存 total / used / **available** | **7725 MiB** / 2953~2968 MiB / **4279~4293 MiB**（4293/4291/4291/4279，漂移 0.3%，稳态成立） | available ≈ **4.18 GiB** |
| Swap | 65.3 MiB / 4095 MiB | 几乎未用，无内存压力迹象 |
| 磁盘 `/` | **42G / 59G（74%），余 16G** | ⚠️ 比内存更先成为瓶颈（镜像累积：AM0 摆渡 1.6G 等） |
| 容器外核心守护 RSS | ≈ **272 MiB** | dockerd 131.9M + 腾讯云镜 YDService 85.9M + containerd 27.6M + journald 26.0M；另有 29 个 containerd-shim（每个 ~8.7M，合计 ~250M）属容器附带成本，计入下方 OS 保留额口径 |

## 2. 全容器实测表（docker stats 双采样一致，按组排列，单位 MiB）

**AM0 告警栈（5 容器，RSS 合计 902）**

| 容器 | RSS / limit | RSS 占 limit | 备注 |
|---|---|---:|---|
| holmesgpt-am1 | 345.2 / 1536 | 22.5% | 常驻 RCA 引擎，slot=2；ps 口径主进程 python server.py 361M 互证 |
| prometheus-am0 | 194.8 / **300** | **64.9%** | ⚠️ limit 低于架构预算 640M（见 §4-B3）；11:25 测 208.7M，波动区间内 |
| deploy-control-app-1 | 192.0 / 768 | 25.0% | Tool Gateway 内嵌其中 |
| deploy-postgres-1 | 153.6 / 512 | 30.0% | 当日三测 231→165→153.6，checkpoint 期波动，按 150~230M 区间看待 |
| alertmanager-am0 | 16.1 / **无限额** | — | ⚠️ 无 mem_limit（P2 bench 已报） |

**AM2（3 容器，RSS 合计 368）**

| 容器 | RSS / limit | RSS 占 limit | 备注 |
|---|---|---:|---|
| alert-order-arena-1 | 211.9 / 512 | 41.4% | Up 8h，健康；ps 口径 java 主进程 218M 互证 |
| alert-arena-chaos-admin-1 | 154.6 / 384 | 40.3% | 11:25 预热 22s 时 135.7M → 本测预热 1h **154.6M，预热已满**；上版"稳态 ≤170M"保守估计成立 |
| arena-e2e-cli（临时） | 1.7 / **无限额** | — | e2e CLI 会话容器，随用随启停 |

**OTel Demo（21 容器，RSS 合计 1366 ≈ 1.33 GiB）**：load-generator 82.2/1500、frontend 92.1/250、cart 62.9/160、otel-collector 118.6/400、echo-receiver(wiremock) 192.6/**无限额**、flagd-ui 143.8/200、email 72.1/100、quote 24.2/40、recommendation 40.5/500、frontend-proxy 22.5/90、flagd 32.7/75、image-provider 7.2/120、telemetry-docs 6.4/100、astronomy-db 53.3/80、currency 5.4/20、shipping 6.0/20、valkey-cart 5.6/20、checkout 14.2/20、payment 103.2/140、ad **261.9/300（87.3%）**、product-catalog **16.7~19.3/20（83~96% 抖动，双采样实见）**。

**全部 29 容器 RSS 合计 ≈ 2636 MiB（2.57 GiB）；memory limits 合计 8167 MiB = 7.98 GiB > 物理内存 7725 MiB（超售 ×1.06）** —— limits 是护栏不是预留，容量评估以 RSS 为准，再次实证成立。无限额容器 3 个：alertmanager-am0、echo-receiver、arena-e2e-cli。

## 3. 与架构 v1.2 §12.2 HOST1 资源账本逐项对照

| 组件 | 架构初始上限 | 实际 limit | 实测 RSS | 校准结论 |
|---|---:|---:|---:|---|
| control-app | 768 MiB | 768M | 192.0M | ✅ 与预算一致，25% 水位 |
| PostgreSQL 16 | 512 MiB | 512M | 153.6M | ✅ 一致（波动区间 150~230M） |
| HolmesGPT | 1536 MiB | 1536M | 345.2M | ✅ 一致；slot=2 高峰未发生，预算充足 |
| Prometheus | 640 MiB | **300M** | 194.8M | ⚠️ limit 只有预算一半，先于预算触顶（64.9%） |
| Alertmanager | 128 MiB | **无限额** | 16.1M | ⚠️ 待补 mem_limit |
| notify-app | 384 MiB | 未部署 | — | AM3 |
| order-arena | 768 MiB | 512M | 211.9M | ✅ AM2 落码收紧为 512M，41% 水位 |
| Tool Gateway | +128 MiB | 内嵌 | — | ✅ 内嵌 control-app，未单独占 |
| OS+Docker+守护/SSH | ≥1.0 GiB 保留 | — | ≈0.27G 核心守护 + ~0.25G（29×shim）≈ **0.52G** | ✅ 仍低于 1.0G 保留额，但 shim 成本上版未单独列账 |

**最大偏差：账本头部假设 HOST1「可用约 5.7 GiB」，实测稳态 available 只有 4.18 GiB。** 差额 ≈1.5 GiB 的主因是**账本没有给 OTel Demo 记账**（21 容器 1.33 GiB RSS + buff/cache 归属），demo 是 P-23 双靶场中未入账的那一块。降级预案必须显式包含 demo 的取舍，否则三级闸的余量测算全是虚的。

## 4. 附带发现（只读观察，留主会话处置）

- **B1 node_exporter 缺失（P7 最重要发现，本测复现）**：`curl 127.0.0.1:9090 api/v1/query node_memory_MemAvailable_bytes` 返回空向量——现告警栈**采不到宿主内存指标**，§12.2 三级内存闸（按 MemAvailable 判定）目前无法落地评估。需先补 node_exporter（建议 ≤64M 限额、仅内网绑定），再建三条 rule。同轮 `up` 查询正常（order-arena up=1、otel-collector 采集在位），排除 Prometheus 本身故障。
- **B2 无内存限额容器 3 个**：alertmanager-am0、echo-receiver、arena-e2e-cli（P2/P3 已从策略面报告，此处为实测面印证）。
- **B3 prometheus-am0 limit 300M < 架构预算 640M**：本测 64.9% 水位（11:25 为 69.6%），短留存下已属偏高；若 AM3/AM4 加大查询或拉长留存，会先 OOM 于 300M 而非预算的 640M。要么对齐调到 640M，要么明确冻结留存参数。
- **B4 demo 贴顶容器**：product-catalog 83~96%/20M、ad 87.3%/300M、payment 73.7%、email 72.1%、flagd-ui 71.9%、checkout 70.8%、astronomy-db 66.6%/80M、quote 60.4%——demo 侧可能先于 flagd 剧本发生 OOM，AM3 评测解读故障归因时要先排除"内存顶死"假阳性。
- **B5 磁盘 74%（余 16G）**：先于内存成为瓶颈；AM3 开跑前建议镜像清理（`docker image prune`，留主会话执行）。
- **B6 Exited×3 均为 exit 0 一次性任务**：alert-arena-migrate / deploy-migrate（两个 flyway 迁移）+ mvn-gate（maven 构建，本测前 11 分钟刚退出，系并行会话所跑）——非残留垃圾，无需处置。
- **B7 containerd-shim 附带成本**：29 容器 × ~8.7M ≈ 250M，属 Docker 每容器固定开销，容量预算时应并入 OS/Docker 保留额口径（仍低于 §12.2 的 1.0G 保留）。

## 5. AM2 / AM3 加入后的余量评估

- **AM2：已在线，已被基线包含。** 实测 RSS 368M（chaos-admin 预热已满 154.6M；order-arena 211.9 + e2e-cli 1.7），当前 available 4.28G **已经扣除了 AM2**，无新增压力。
- **AM3（按工作单最坏情形全部落 195）**：
  | 组件 | limit（架构） | RSS 预估（按同类实测锚定） |
  |---|---:|---|
  | notify-app | 384M | 150~250M（同类 Spring Boot control-app 实测 192M，取 250M 封顶） |
  | litellm | 512M | 200~300M（Python 无状态代理，取 300M） |
  | eval-runner | 384M（§12.1 定位在 HOST2） | 间歇运行，取 250M；注意架构本意是放 2C4G 评测机，195 属超载假设 |
  | **合计最坏** | | **+800M RSS** |
- 最坏情形 after-AM3：available ≈ 4280 − 800 ≈ **3480 MiB（3.4 GiB）**，仍是一级闸 1.2 GiB 的 2.8 倍。若按架构本意 litellm/eval-runner 留 HOST2，195 只加 notify-app，available ≈ 4.0 GiB。
- **判定：AM3 可容纳，稳态余量充足。** 真正的风险组合是「demo 不砍 + AM3 三件全塞 195 + Holmes slot=2 高峰（345.2M→理论上限 1536M）」三事叠加，而非任何单项。

## 6. 三级降级阈值建议（对齐架构 §12.2 原文闸值，附实测倍数）

判据统一用 `MemAvailable`（不是 Java heap、不是 limits 加总）；恢复滞回：连续 10 分钟 >1.5 GiB 逐级恢复，每次降级落 `RESOURCE_MODE_CHANGED`。

| 级别 | 触发 | 当前水位倍数 | 动作（架构原文语义） |
|---|---|---:|---|
| L1 降级 | available < **1.2 GiB** 持续 5min | 3.5× | 停新 Live E2E/在线 Shadow；**关 order-arena**（回收 RSS 368M + limits 896M）；Holmes slot 2→1 |
| L2 严重 | available < **768 MiB** 持续 2min | 5.6× | 入口继续原子持久化；暂停领取新 RCA Task；触发独立值班告警 |
| L3 危急 | available < **512 MiB** 或 OOM/reclaim storm | 8.4× | readiness fail；只允许恢复任务与受控运维；不得用 LLM 逃生结果掩盖容量事故 |
| （缓冲档，P7 建议） | L1 触发后仍下行 | — | 依序砍：load-generator（82M）→ 非核心 demo 容器 → 整个 OTel Demo（回收 1.33G，P-23 双靶场取舍时 demo 让位给 arena） |

落地前置条件（对应 §4-B1）：先补 node_exporter，再以 `node_memory_MemAvailable_bytes` 建三条 rule；辅助信号建议 swap used >500M、容器 OOM kill 事件。

## 7. 证据命令（195 上复现）

```bash
free -m; swapon --show; df -h /                      # §1 总量
docker ps -a --format '{{.Names}}\t{{.Status}}\t{{.Image}}' | sort      # 29+3 清单
docker stats --no-stream --format '{{.Name}}\t{{.MemUsage}}\t{{.MemPerc}}\t{{.CPUPerc}}' | sort  # §2（×2 采样）
docker ps -q | xargs docker inspect --format '{{.Name}}\t{{.HostConfig.Memory}}' | sort  # limits
ps aux --sort=-rss | head -14                        # 容器内外进程 RSS（含 dockerd/YDService/shim）
curl -s 'http://127.0.0.1:9090/api/v1/query?query=node_memory_MemAvailable_bytes'  # §4-B1 空向量
```

全程只读：无 docker run/stop/rm/restart，无配置改动，无新增文件落 195。本测未接触任何密钥（纯容量测量，无模型调用），证据文件无 key/token 字面量。

---

## 结论摘要（约 200 字）

195 第三方独立复测（free×4/stats×2/inspect/ps，零改动零新增零重启）确认同日前两版结论：29 容器 RSS 合计 2.57G，宿主 available 稳态 4.28G（四采样漂移 0.3%），swap 仅 65M。AM2 双容器已在线且被基线包含（368M，chaos-admin 预热已满 154.6M）；AM3 最坏全落 195 约增 800M，余 3.4G 为一级闸 1.2G 的 2.8 倍，可容纳。关键校准：§12.2 账本假设可用 5.7G 高估 1.5G（OTel Demo 21 容器 1.33G 未入账）；limits 合计 7.98G 超物理内存，limits=护栏非预留。三级降级阈值对齐架构原文 1.2G/768M/512M+滞回，砍单顺序 load-gen→非核心 demo→整个 Demo。最大落地缺口仍是 node_exporter 缺失（MemAvailable 空向量，三级闸不可评估）；另 prometheus limit 300M<预算 640M、磁盘 74% 先于内存成瓶颈、新增 shim 每容器 8.7M 成本入账，均留主会话处置。
