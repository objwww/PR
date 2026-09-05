# deploy/policy —— docker compose 静态安全门（conftest / Rego）

> 并行备料任务 P3 产出。对 `docker compose config` 的 JSON 渲染物做静态策略检查，
> 作为 AM2/AM3 变更进栈前的静态门（与 docker-bench 运行时基线互补，见 P2 产出）。
> 本目录只含策略（`*.rego`）与本说明，不改任何既有文件；conftest 二进制不落仓库。

## 一、用法

```bash
# 1) 渲染 compose 为 JSON（:? 必填变量需在环境或 .env 中提供；策略只看安全字段，不关心取值）
docker compose -f deploy/alert/docker-compose.yml config --format json > render.json

# 2) 门禁（工作单原命令为管道形式；两种等价）
docker compose -f deploy/alert/docker-compose.yml config | conftest test -p deploy/policy/ -
conftest test -p deploy/policy/ render.json

# 3) 正反样本单元测试（无需 docker，离线可跑）
conftest verify -p deploy/policy/
```

**门禁语义**：任一 `deny` 违规 → conftest 退出码 1（管道/CI 据此拦截）；无违规 → 退出码 0。
P-00 是 `warn`，只提示不拦截。目录内所有策略同属 `package main`（conftest 默认 namespace）。

工具版本：conftest **0.69.0**（内置 OPA **1.19.0**，rego v1 语法）；渲染端 docker compose **v2.27.1**（195 实测）。

## 二、策略清单与意图

| ID | 文件 | 意图 | 判定要点 |
|---|---|---|---|
| P-00 | p00-input-sanity.rego | 输入体检（warn） | input 顶层无 `services` 时提示，防"非 compose 渲染物 → 假绿" |
| P-01 | p01-no-privileged.rego | 禁 privileged | `privileged: true` 一票否决，无豁免 |
| P-02 | p02-readonly-rootfs.rego | 必须 read_only | 未显式 `read_only: true`（缺失或 false）即违规；写路径走显式 tmpfs/卷 |
| P-03 | p03-cap-drop-all.rego | 必须 cap_drop ALL | `cap_drop` 缺失或不含 `ALL` 即违规；按需加回 |
| P-04 | p04-loopback-ports.rego | 禁公网端口绑定 | 长语法看 `host_ip`（缺省=0.0.0.0 视为违规）；短语法串按首个冒号段判；`network_mode: host` 绕过 ports 发布，一并否决；服务不带 ports 自然通过 |
| P-05 | p05-nonroot-user.rego | 必须 non-root | compose 层必须钉 `user: "UID[:GID]"`；缺失、空、`0/0:0/root/root:root` 等即违规 |
| P-06 | p06-memory-limit.rego | 必须内存限额 | `mem_limit` 或 `deploy.resources.limits.memory` 任一 > 0（渲染物中两者同现，互为等价） |
| P-07 | p07-no-new-privileges.rego | 必须 no-new-privileges | `security_opt` 含 `no-new-privileges(:true)`，封 setuid/setgid 提权路径 |

违规消息均带稳定 ID 前缀（`P-0x`），便于 CI 报表与豁免台账引用。

## 三、正反样本测试结果

单元测试在 `policy_test.rego`：以"完全加固服务"为正样本基线，逐条单点变异断言恰好触发/不触发，
防止策略间相互遮蔽。覆盖：privileged 真假、read_only 缺失/假、cap_drop 缺失/部分、
0.0.0.0 与内网 IP 与缺省 host_ip（长语法）、`8081:8080`（短语法通配）、`127.0.0.1:8081:8080`（短语法回环）、
user 缺失/root UID/root 名、mem_limit 缺失/为 0/deploy 限额兜底、security_opt 缺失/无关值/裸写法、
network_mode host、非 compose 输入的 P-00 提示。

```
$ conftest verify -p deploy/policy/
25 tests, 25 passed, 0 warnings, 0 failures, 0 exceptions, 0 skipped

$ type positive.json | conftest test -p deploy/policy/ -      # 正样本渲染物（加固 demo 服务）
10 tests, 10 passed, 0 warnings, 0 failures, 0 exceptions
```

## 四、真实告警栈实测（deploy/alert/docker-compose.yml）

渲染环境：195（CentOS 7 / docker 26 / compose v2.27.1），仓库版 compose 文件 scp 至临时目录渲染，
`:?` 变量以 **dummy 值**插值——安全字段结构真实、零真实密钥参与渲染；渲染物只落本机临时目录，不入仓库。

```
$ type render.json | conftest test -p deploy/policy/ -        # stdin 形式，退出码 1
FAIL - - main - P-02 read-only-rootfs: service "alertmanager" must set read_only: true ...
FAIL - - main - P-02 read-only-rootfs: service "arena-migrate" must set read_only: true ...
FAIL - - main - P-02 read-only-rootfs: service "holmesgpt" must set read_only: true ...
FAIL - - main - P-02 read-only-rootfs: service "prometheus" must set read_only: true ...
FAIL - - main - P-03 cap-drop-all: service "alertmanager" must set cap_drop: [ALL] ...
FAIL - - main - P-03 cap-drop-all: service "arena-migrate" must set cap_drop: [ALL] ...
FAIL - - main - P-03 cap-drop-all: service "prometheus" must set cap_drop: [ALL] ...
FAIL - - main - P-05 non-root: service "alertmanager" must pin a non-root user ...
FAIL - - main - P-05 non-root: service "arena-chaos-admin" must pin a non-root user ...
FAIL - - main - P-05 non-root: service "arena-migrate" must pin a non-root user ...
FAIL - - main - P-05 non-root: service "holmesgpt" must pin a non-root user ...
FAIL - - main - P-05 non-root: service "order-arena" must pin a non-root user ...
FAIL - - main - P-05 non-root: service "prometheus" must pin a non-root user ...
FAIL - - main - P-06 memory-limit: service "alertmanager" must set mem_limit ...
FAIL - - main - P-06 memory-limit: service "arena-migrate" must set mem_limit ...
FAIL - - main - P-07 no-new-privileges: service "alertmanager" must set security_opt ...
FAIL - - main - P-07 no-new-privileges: service "arena-migrate" must set security_opt ...
FAIL - - main - P-07 no-new-privileges: service "prometheus" must set security_opt ...
19 tests, 1 passed, 0 warnings, 18 failures, 0 exceptions
```

违规矩阵（18 条）：

| 服务 | P-01 | P-02 | P-03 | P-04 | P-05 | P-06 | P-07 |
|---|---|---|---|---|---|---|---|
| prometheus | ✅ | ❌ | ❌ | ✅ | ❌ | ✅(300m) | ❌ |
| alertmanager | ✅ | ❌ | ❌ | ✅ | ❌ | ❌ | ❌ |
| holmesgpt | ✅ | ❌ | ✅ | ✅(无ports) | ❌ | ✅(1536m) | ✅ |
| arena-migrate | ✅ | ❌ | ❌ | ✅(无ports) | ❌ | ❌ | ❌ |
| order-arena | ✅ | ✅ | ✅ | ✅(127.0.0.1) | ❌ | ✅(512m) | ✅ |
| arena-chaos-admin | ✅ | ✅ | ✅ | ✅(无ports) | ❌ | ✅(384m) | ✅ |

**解读**：
- P-01 / P-04 全绿——无 privileged、宿主端口全绑 127.0.0.1，与 INV-AM0-1 纪律及 P2 docker-bench
  实测（127.0.0.1 绑定达标）互证；门禁对"已守住的纪律"具备回归保护价值。
- 缺口集中在 P-02/03/05/06/07，与 P2 bench 的 compose 整改候选清单一致（alertmanager 缺 mem_limit、
  prometheus/alertmanager 未开 read_only/nnp、cap_drop 缺失等），可作 AM2/AM3 收口时的静态整改清单。
- P-05 六个服务全部未在 compose 层钉 `user`（AM2 两容器其余五项全过，补 `user:` 即全绿）。
- arena-migrate 为一次性 Flyway 任务，触发 5 条——**本策略集不内置豁免**；init/one-shot 容器的
  scope 豁免属设计裁定，留给主会话（建议以显式豁免台账 + CI 白名单实现，而非放松策略）。

## 五、已知边界

1. **P-05 静态局限**：镜像内置 `USER` 无法从渲染物验证（镜像层 root + compose 未钉 user = 运行时 root）。
   compose 层钉 user 是静态门能做到的全部；镜像层核查属 P2 bench（已报 holmesgpt 镜像 root）。
2. **渲染形态依赖**：策略按 compose v2 归一化输出编写（端口长语法含 host_ip、内存为整数字节）。
   低版本渲染器若保留短语法串，P-04 走字符串分支（仅认 IPv4 `127.0.0.1` 前缀；IPv6/端口段为边界外）。
3. **未覆盖面**（后续可作 P-08+ 扩展）：`pid_mode: host`、`devices`、`ulimits`、`privileged` 之外的
   capabilities 加回白名单核对。
4. 渲染 `:?` 变量必须可解析；CI 中应以 dummy/受控 env 渲染，避免真实密钥进入渲染物与日志。

## 六、复现步骤

```bash
# 工具（版本见上；不落仓库，装本机/CI 即可）
# https://github.com/open-policy-agent/conftest/releases

# 1) 离线单元测试（正反样本）
conftest verify -p deploy/policy/

# 2) 渲染 + 门禁（本机有 docker 时）
docker compose -f deploy/alert/docker-compose.yml config | conftest test -p deploy/policy/ -

# 3) 无本机 docker 时的等价路径（本任务实测路径）
#    scp deploy/alert/docker-compose.yml 至任一有 docker 的机器（如 195）临时目录，
#    以 dummy 值插值渲染 --format json，取回后：
conftest test -p deploy/policy/ render.json        # 或 type render.json | conftest test -p deploy/policy/ -
```

---

### 结论摘要（P3）

`deploy/policy/` 落码 7 条 deny + 1 条 warn 静态策略（P-01 禁 privileged、P-02 必须 read_only、
P-03 必须 cap_drop ALL、P-04 端口仅绑 127.0.0.1 且禁 network_mode host、P-05 必须 non-root user、
P-06 必须内存限额、P-07 必须 no-new-privileges；P-00 为非 compose 输入提示）。conftest 0.69.0
（OPA 1.19.0）下：单元正反样本 25/25 绿；正样本渲染物门禁退出码 0；195 渲染的真实告警栈 18 条
违规、退出码 1——P-01/P-04 全绿与 P2 bench 互证，缺口集中在 P-02/03/05/06/07，与 bench 整改候选
一致；arena-migrate 一次性任务的豁免 scope 留主会话裁定。渲染用 dummy 插值，证据经 grep 确认零密钥。
