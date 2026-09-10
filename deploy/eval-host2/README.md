# deploy/eval-host2 — HOST2（127 = 117.72.208.68）迁移执行面

依据文档：
- `docs/告警系统-演进方案可行性评审与详细改造计划.md` Phase 11（迁移至 127，部署与切换面）
- `docs/告警系统-2C4G评测机迁移实施方案.md`（评测管线拆分与 M0~M6 阶段）

## 机器与 SSH（2026-09-09 实测，勿凭记忆改）

| 机器 | IP | SSH |
|---|---|---|
| HOST2 / 127 | 117.72.208.68 | `ssh -i ~/.ssh/hotel_deploy root@117.72.208.68` |
| HOST1 / 195 | 146.56.195.225 | `ssh -i ~/.ssh/id_ed25519 root@146.56.195.225` |

两机密钥不同：127 用 `hotel_deploy`，195 用 `id_ed25519`。

## 当前内容

| 文件 | 用途 | 执行面 |
|---|---|---|
| `mig01-host2-bootstrap.sh` | MIG-01 基座：基线采集、四个专用 UID、/srv/alert-eval 目录树、systemd slice 权重、WireGuard 骨架、logrotate、node_exporter、验收 PASS/FAIL | 目标机本机（root），幂等 |
| `mig01-run.sh` | 从开发机经 SSH 驱动上面脚本并回收证据到 `docs/测试证据/HOST2-127/`；`--with-195` 连带在 195 装 node_exporter | 本机（Git Bash） |

## 快速开始

```bash
bash deploy/eval-host2/mig01-run.sh              # 只初始化 127
bash deploy/eval-host2/mig01-run.sh --with-195   # 同时在 195 补 node_exporter
```

**MIG-01 已执行完毕（2026-09-09）**：127 侧 15/15 PASS、195 侧 4/4 PASS，证据在
`docs/测试证据/HOST2-127/mig01-20260909T061157Z/`。重跑幂等，仅用于补装或核对。

**WireGuard 无需再配置**：隧道 2026-08-29 已预置且活跃（195=10.250.250.1、127=10.250.250.2，/30，
用户态 wireguard-go，内核 3.10 无内核模块；ELRepo kmod-wireguard 已不可装，不再尝试）。
bootstrap 只检测记录不重配。剩余人工步骤：

1. 195 Prometheus 加 scrape job（target `10.250.250.2:9100`，改 prometheus.yml 需重启/重载容器，有秒级监控间隙，单独确认后执行）。
2. 网络负向验收已过一次：195 的 5432/9090/8080 在隧道地址上均为 000（均 loopback 绑定，符合预期）。

## 纪律

- 脚本幂等，可重复执行；任何 FAIL 退出码非零，不得带着 FAIL 进入 MIG-03。
- 密钥/私钥不进仓库、不进证据日志（WireGuard 只记公钥）。
- `NODE_EXPORTER_IMAGE` 默认 tag 仅为起点，正式执行时核对并改 pin digest（与 Phase 1 镜像锁定纪律一致）。
- 证据即交付：每次执行产出 evidence-*.log + sha256，回收至 `docs/测试证据/HOST2-127/`。

## 后续（尚未落码，见迁移方案 §十一）

`compose.yml`（probe/runner/scorer 三 profile）、`evalctl`（资源准入 + 状态 CAS + one-shot 编排）属 MIG-03/04 阶段，待 MIG-01 验收通过、Gatus 六项验收全过后再建。
