# deploy/duty-adapter — 127 独立故障域值班适配器部署面（AM7 M7-17/M7-18）

依据：`docs/告警AM7-值班通知与值班表增量技术方案.md` §3/§7（127 duty-adapter、
窄反向代理）、`docs/告警-执行者ABC-改造技术方案.md` §7.2（127 不进 EX 卡验收面——
行为证据归 EX-C4/MIG-02，M7-17/M7-18 只做代码+部署+195 侧可观测面）。

## 机器（2026-09-09 实测，同 eval-host2/README）

| 机器 | IP | SSH | 角色 |
|---|---|---|---|
| HOST2 / 127 | 117.72.208.68 | `ssh -i ~/.ssh/hotel_deploy root@117.72.208.68` | duty-adapter + Gatus（2C4G） |
| HOST1 / 195 | 146.56.195.225 | `ssh -i ~/.ssh/id_ed25519 root@146.56.195.225` | 主栈 + 窄反代 |

WG 隧道：195=10.250.250.1、127=10.250.250.2（/30，用户态 wireguard-go，已活跃）。

## 构成

| 文件 | 用途 |
|---|---|
| `compose.duty-adapter.yml` | 127 compose 单元（192MiB 限额；端口只绑 10.250.250.2:8090） |
| `.env.example` | 三 token 对齐说明 + 通道 webhook env 键名制示例 |
| `nginx-narrow-195.conf` | 195 宿主 nginx 窄反代（只放行两条 duty 路径；负向 404） |

## 凭证对齐表（部署前核对）

| 变量 | 127 .env | 195 deploy/.env | 语义 |
|---|---|---|---|
| Gatus→adapter 验签 | `DUTY_ADAPTER_TOKEN` | —（gatus .env 同名同值） | POST /hook/gatus bearer |
| 快照拉取 | `DUTY_SNAPSHOT_BEARER` | `APP_OPERATOR_API_BEARER` | GET /api/duty/**（op 机器线） |
| 台账回写 | `DUTY_WRITEBACK_BEARER` | `APP_DUTY_ADAPTER_BEARER` | POST /api/duty/notifications（duty-adapter 线） |

## 部署序列（M7-18 执行）

1. **195**：`.env` 增 `APP_DUTY_ADAPTER_BEARER`（:? 已上 compose）→ 重建 control-app；
   安装/核对宿主 nginx → `nginx-narrow-195.conf` 入 conf.d → reload；
   负向验收：隧道上 GET /api/rca-runs 404、GET /api/duty/schedule/snapshot 401（无凭证）。
2. **构建面**：`mvn -pl duty-adapter -am package -DskipTests`（UT 本地已绿）
   → jar 同步 127（tar，同 BA-18 复杂远操作纪律）。
3. **127**：`deploy/duty-adapter/` 同步（compose+.env）→
   `docker compose -f compose.duty-adapter.yml up -d --build` →
   RSS 实测 ≤192MiB；`curl 10.250.250.2:8090/health` 200（本机/隧道侧）。
4. **Gatus 接线**：127 gatus `.env` 增 `DUTY_ADAPTER_TOKEN` +
   `GATUS_ONCALL_WEBHOOK_URL=http://10.250.250.2:8090/hook/gatus` →
   `docker compose -f compose.gatus.yml up -d`（配置头注 Authorization 注入见
   gatus-config.yml）。
5. 行为证据（Gatus 六项验收/E2E）→ EX-C4/MIG-02 收口，不占 M7-18 验收面。

## 已知风险（部署时首先核对）

- **kernel 3.10 兼容**：127 为 CentOS 7（内核 3.10）。eclipse-temurin:21-jre 基于
  Ubuntu（自带 glibc），容器内用户态不依赖宿主 glibc；但 JDK21 对老内核的 syscall
  面未在本机实证——**up 后若容器 crash-loop，退路=宿主直装 JDK21（systemd 单元
  替代容器）**，决策记录回 BUGLOG。
- 隧道接口未就绪时端口 bind 失败 → restart 自愈（compose 头注）。
- adapter 自身故障=外部腿盲区（AM7 §8 登记）：靠 restart + fallback 通道独立性缓解。
