# 195 磁盘紧急处置 + 195↔127 网络打通证据 —— 2026-09-09

> 背景：`docs/告警系统-演进方案可行性评审与详细改造计划.md` Phase 11 执行首日，MIG-01 基线采集时发现 195 磁盘 **56G/59G = 98%，余 1.4G**（评审文档原定"磁盘先到的瓶颈"当场升级为紧急项）。本文记录当日三项处置的证据：① 195 磁盘清理与异地归档；② WireGuard 隧道真相核查与验通；③ 双机 node_exporter 部署与网络负向验收。
> 执行面：`deploy/eval-host2/mig01-run.sh` + `mig01-host2-bootstrap.sh`（MIG-01 证据见 `mig01-20260909T061157Z/`）；磁盘与网络处置为脚本之外的即时操作，命令与结果逐条记录于下。
> 机器：195 = `146.56.195.225`（ssh `-i ~/.ssh/id_ed25519`）；127 = `117.72.208.68`（ssh `-i ~/.ssh/hotel_deploy`）。

## 1. 磁盘处置（195，/dev/vda1 59G）

| 时点 | 用量 | 余量 | 动作 |
|---|---|---|---|
| 处置前（MIG-01 基线） | 56G / 98% | 1.4G | — |
| 第一轮后 | ~91% | ~5G | `docker builder prune` 回收 7.94G；journald vacuum 至 600M；删 4 个 Exited 残留容器 |
| 第二轮后 | 43G / **74%** | 16G | 删 101 个未使用镜像（**跳过** `local/holmesgpt:am0`、`local/holmesgpt:am1-http`——本地构建、registry 不可重拉，须先异地归档） |
| 归档完成后（待补） | 预期 ~62% | 预期 ~22G | /opt/backups 6.4G 迁 127 校验后删源 + holmes 双镜像导出后 rmi（见 §5 收尾清单） |

关键纪律：

- **先归档后删除**：`/opt/backups`（6.4G，16 个 SQL/ tar 备份）逐文件生成 sha256 清单（195 侧 `/tmp/backups-sha256.manifest`，已随 rsync 投递），rsync 至 `127:/srv/backups-195/` 后**逐文件校验全等才删 195 侧源文件**。
- holmes 双镜像以 `docker save | gzip | ssh` 流式归档至 `127:/srv/backups-195/holmesgpt-images-20260909.tar.gz`，拿 sha256 后才 `docker rmi`。
- 备份通道：195→127 专用 SSH 密钥 `/root/.ssh/bk_to_127`（ed25519），公钥已授权 127；与两机管理密钥（id_ed25519 / hotel_deploy）分离。
- 带宽实测约 0.5～0.6 MB/s（127 为腾讯云 Lighthouse，入网限速），6.4G 需约 3 小时——**传输期间 195 磁盘水位不变，删除动作全部押后至校验通过**。
- 缓办项：195 未使用 docker volume 约 3.42G（37 个，可能含旧数据，逐个裁定后再清）；`/root` 下约 2.0G 未勘察。

## 2. WireGuard 隧道真相（推翻"需新建"的规划假设）

**隧道已存在且活跃，MIG-01 起不再做任何配置变更。**

| 项 | 实测 |
|---|---|
| 地址 | 195 = `10.250.250.1/30`，127 = `10.250.250.2/30`（**非**早期规划的 10.77.0.0/24，该规划值废弃） |
| 预置时间 | 2026-08-29（双机同日均已装 wireguard-tools + `/usr/local/bin/wireguard-go` v0.0.20250522） |
| 实现方式 | **用户态 wireguard-go**：双机内核 3.10（CentOS 7）无 WireGuard 内核模块；ELRepo el7 的 kmod-wireguard 已停发，实测装不上，不再尝试。用户态性能对监控/scrape/备份量级足够 |
| 连通性 | 双向 ping 约 21ms、0% loss（2026-09-09 实测）；`wg show wg0 latest-handshakes` 持续刷新，transfer 计数双向增长 |
| 公钥 | 195 = `g4180v40WG0OsUoHscm8uNnNDLdmFGJE8P+FKM6RMGM=`；127 = `kUURj4MGEaDwgrDslEjolkcFyAFS0I37ejeHbRuVWXA=`（私钥仅在各自 /etc/wireguard，0600，不进仓库不进证据） |
| 配置持久化 | 两侧 `/etc/wireguard/wg0.conf` 已按 `wg showconf wg0` 输出恢复（含 Address 行，0600），与运行态一致 |
| 遗留核对 | 两侧重启自启方式待核对（`wg-quick@wg0` 单元或现有拉起脚本），不影响当前运行 |

## 3. node_exporter 与网络验收（2026-09-09 14:59 CST 快照）

| 项 | 195 | 127 |
|---|---|---|
| node_exporter | `prom/node-exporter:v1.9.1`（digest `sha256:d00a542e…442c8a`，64MiB 限额），绑 `127.0.0.1:9100` | 同镜像，已改绑隧道地址 `10.250.250.2:9100` |
| 经隧道采集 | — | 195 侧 `curl http://10.250.250.2:9100/metrics` → **200** ✅ |
| 隧道健康 | latest-handshake 秒级刷新 ✅ | 同 ✅ |

**负向验收（隧道地址上探测 195 敏感端口，必须失败）**：`10.250.250.1` 上 5432（PG）/ 9090（Prometheus）/ 8080（control-app）全部连接失败（curl 000）✅ —— 三者均 loopback 绑定，符合 Phase 11 §3 "不暴露 PG / 管理口"的隔离要求。

## 4. Prometheus scrape job 上线（2026-09-09，用户已批准容器重启）

- 配置：`/opt/build/pr/deploy/alert/prometheus/prometheus.yml` 新增 job `node-exporter-host2`（target `10.250.250.2:9100`），归入 scrape_configs 段；变更前备份 `prometheus.yml.bak-20260909T070619Z`（只增不删）。
- 过程发现 **BA-61**（单文件 bind mount 钉旧 inode，容器读 9-04 旧副本，容器内 promtool 校验假绿；且首次追加位置误落 alerting 块尾）——已记入 `docs/告警-BUGLOG.md`。
- 修复路径：job 归位 → 新文件 `docker cp` 进容器 promtool 校验真文件（SUCCESS）→ `docker restart prometheus-am0`（秒级间隙，已获批准）→ 容器内外 md5 一致（`dbd7435e…`）。
- 验收：`/api/v1/targets` 三 job 全 **up**（otel-collector / order-arena / node-exporter-host2）；`node_time_seconds{job="node-exporter-host2"}` 返回 127 实时值 ✅——127 机器指标正式纳入 195 监控面。

### 4.1 195 本机 node_exporter 入库（2026-09-09 16:0x CST，用户追加批准）

- node_exporter 重建为双绑定 `127.0.0.1:9100` + `10.250.250.1:9100`（镜像/参数/64MiB 限额原样，双 curl 200 实证）。
- prometheus.yml 再增 job `node-exporter-host1`（target `10.250.250.1:9100`），走 BA-61 修正流程（备份 `prometheus.yml.bak-20260909T073714Z` → docker cp 校验 → restart → md5 双端一致 `28dc1aba…`）。
- 验收：四 job 全 **up**；`node_memory_MemAvailable_bytes{job="node-exporter-host1"}` = 3,802,644,480 bytes（≈3.54GiB，与 `free -m` 互证）✅——**BA-54 的 Prom rule 数据源缺口（P7 B1）自此闭合，双机内存闸规则可写**。

## 5. 假绿排查：全量单文件 bind mount 审计（2026-09-09，用户发起）

方法：`docker exec <容器> cat <路径> | md5sum`（容器内字节视图）对拍宿主机 md5；**禁用 docker cp**（BA-62 实测：对 ro bind mount 返回宿主源文件内容，会假绿）；distroless 容器无 coreutils 时先 `command -v` 探测（BA-62 假红教训）。

| 容器 | 挂载文件 | 结论 |
|---|---|---|
| prometheus-am0 | prometheus.yml | MATCH（BA-61 修复后） |
| deploy-postgres-1 | 01-roles.sh | MATCH |
| astronomy-db | init.sql | MATCH |
| deploy-otelcol-control-1 | otelcol-config.yml | MATCH（distroless，cp 临时目录+diff 复核） |
| otel-collector | otelcol-config.yml / extras.yml | MATCH ×2（同上） |
| product-catalog | otel-config.yml | MATCH（同上） |
| alertmanager-am0 | alertmanager.yml | **DIVERGED：纯 CRLF 行尾漂移**（去 CR 后逐行语义零差异），9-09 08:46 宿主被同步配方刷成 LF，容器跑 9-08 CRLF 副本；零功能影响，重启即可清漂移 |
| litellm-am3 | config.yaml | **DIVERGED：语义漂移**——运行副本 `model_name: qwen3.7-plus`（/v1/models 实测只认此名）vs 宿主新版 `glm-5`（eval 指纹 `litellm:glm-5@dashscope` 已指向新名）→ 已立 BA-63，待用户确认重启应用 |
| 127 全机 | — | 零单文件 bind mount，天然无此风险 |

补充实测（BA-63 关联）：控制面生产模型流量**不经 litellm**——`external_invocation_ledger` 最近调用为 Native 直打百炼 `openai/deepseek-v3`（9-08 22:22 UTC 起 150 笔 SUCCEEDED）；litellm 近 200 行日志全为健康检查、零模型流量。故漂移无在途影响，但 eval 路径此刻调 litellm 必 404，属待清定时炸弹。

## 6. 收尾清单（传输完成后执行并回填本节）

- [ ] rsync 完成 → 127 侧逐文件 `sha256sum -c` 对照 manifest → 全等后删 195 `/opt/backups`（回收 6.4G）
- [ ] holmes 双镜像归档重传（首次流传输因带宽让路被取消，截断文件已删）→ 拿 sha256 → 195 `docker rmi local/holmesgpt:am0 local/holmesgpt:am1-http`（回收 ~1.66G）
- [ ] 记录最终 `df -h /` 与 `free -m`
- [ ] 回填本表与 Phase 11 §4 第 1 步状态
- [ ] BA-63 处置：用户确认后重启 litellm-am3（应用 glm-5）+ 顺带重启 alertmanager-am0（清 CRLF 漂移）→ 重启后 mount 审计复跑零 DIVERGED

## 7. BA-63 关单 + dangling 卷清理（2026-09-09 17:4x~18:0x CST，用户批准）

- litellm-am3 重启应用 glm-5：healthy → `/v1/models` 只含 glm-5 → 真实 chat 请求经 litellm→上游成功（Pong）→ BA-63 关闭；alertmanager-am0 顺带重启清 CRLF 漂移，`/-/healthy` 200；mount 审计复跑**双 MATCH，195 单文件 bind mount 全量零 DIVERGED**。
- dangling 卷清理（依据 `docs/测试证据/容量/195-disk-deep-dive-20260909.md` 裁定表）：B 类 21 卷先 tar 留档 `/opt/vol-archive-20260909/`（238M，`tar -t` 抽查 3/3 通过）后 `docker volume rm` 逐个点名删 21/21；A 类空卷 16/16 直删；m2repo（358M）按裁定"待问"保留。磁盘 74% → **69%（余 18G）**。
- 待办：`/opt/vol-archive-20260909/` 随迁 127（排在主 rsync 之后）；m2repo 与 /opt 下 node16、jdk21.tar.gz 等待问项回用户裁定。
