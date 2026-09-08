# AM5 E2E preflight 就绪面证据（195 部署段，2026-09-08）

## 面貌

195（146.56.195.225，CentOS 7 / 8G / 59G 盘 85% 用）真栈 preflight 首个**有效**就绪面
（首次跑受 BA-50 函数面缺失污染，本批为 8f16821 修复后复跑）。

- 线束版本：e2e-am5-common.sh **v2**（c7fc14e）+ preflight 自 source（8f16821）
- 探针结果：**8 OK / 2 MISS → FAIL_PRECONDITION（exit 3）——契约行为，不得换 mock**
- 环境账：`preflight.log`（40 行，am5_redact 过，口令泄漏扫描干净；磁盘 85% 为资源观察项）

## OK 面（8）

| 组件 | 实测端点 |
|---|---|
| docker | 宿主 docker 可用，容器面无重启环/OOM 迹象 |
| control-app | 127.0.0.1:8080 /actuator/health → 200（V20~V29 已迁移栈） |
| PG | 经 `AM5_PSQL_CMD='docker exec deploy-postgres-1 psql'` 容器内执行面可达 |
| 迁移面 | flyway_schema_history 可读（注：`max(version)` 是 VARCHAR 字典序返回 '9'，ordinal 真值 29/28/27） |
| LiteLLM | 127.0.0.1:4100 /health/liveliness → "I'm alive!"（litellm-am3 容器） |
| order-arena | 127.0.0.1:8082 /healthz → 200（自证面非 actuator） |
| Prometheus | 127.0.0.1:9090 /-/ready → 200 |
| Alertmanager | 127.0.0.1:9093 /-/ready → 200 |

## MISS 面（2，均为外部依赖缺口，非本会话可闭环）

1. **2C4G Gatus 未部署**：`deploy/gatus/.env` 缺 `GATUS_ONCALL_WEBHOOK_URL`（外部值班
   通道收口地址）。候选 sink：echo-receiver（127.0.0.1:9199）。且 Gatus 容器网络视角
   对 127.0.0.1 绑定的 control 不可达——部署时需 health URL 走可达面（同宿主 default
   bridge → 172.17.0.1 需 control 发布面放宽，或 gatus network_mode host，待裁定）。
2. **AM5_LLM_BUDGET_CAP 未设**：真栈 LLM 花费护栏值属用户裁定面；未设时 runall 被
   preflight 拦住是正确行为（无预算护栏不跑真 LLM 场景）。

## 后续激活路径（11 场景填实的前置清单）

- [ ] Gatus 栈部署（webhook 值 + 网络面裁定）→ AM5_GATUS_HOST
- [ ] AM5_LLM_BUDGET_CAP 设值（用户裁定）
- [ ] 场景 02 的 RCA-100 授权核查记录（BLOCKED_EXTERNAL 白名单唯一出口）
- [ ] 11 场景脚本真栈填实：00/01/02/10 为 M5-22 骨架（PENDING_DEPLOY_SEGMENT），
      03~09 为各任务随件骨架（探针序列注释态，依赖面已齐：M5-08 门禁记录表、
      eval_private REDTEAM 分区、arena-e2e-cli 驱动等——但均需真栈填实+夹具装载）
- [ ] 195 侧 env：`/opt/build/pr/deploy/.env-e2e-am5`（600，已建：PSQL shim +
      容器内视角 PG URL + 各端口覆盖；口令面永不入证据包）

## 复跑方式

```sh
# 195 上：
cd /opt/build/pr/e2e-am5
set -a; . /opt/build/pr/deploy/.env-e2e-am5; set +a
sh e2e-am5-preflight.sh /opt/build/pr/e2e-runs/preflight-$(date -u +%Y%m%dT%H%M%SZ)
```
