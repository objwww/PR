# M6-03 195 真机验证证据包（50% 容量与回退演练；2026-09-08 UTC / 09-09 北京时刻）

- **验证对象**：M6-03「50% Native Canary（容量与演练，零新服务）」——容量报告脚本 + HOST1 三档内存闸演练 + 回退演练；代码面仅新增 `deploy/policy/m6-capacity-report.sh`（只读报告工具，无迁移/无类/无服务）。
- **单项验收标准对拍**（AM6 技术方案）：容量报告含 Native 实跑资源账 ✅；三档内存闸演练记录 ✅（tier1 实弹 + tier2/3 机制缺口如实留证 → BUGLOG BA-54）；回退演练全绿 ✅。

## 一、容量报告（`capacity-report.txt`，E2E-AM6-03 phase1 真栈执行）

- **§1 内存三档分类**：`mem_available_mb=3268 classified_tier=L0`（架构 :946-953 冻结阈值 L1<1229/L2<768/L3<512 MiB + 恢复滞回 1536MiB×10min）；对照 P7 基线（2026-09-05 available 4.28GiB）下降约 1GiB = AM5 期 notify-app/litellm/gatus 等入账，仍为 L1 阈值 2.66 倍。
- **§2 容器 RSS/limit**：docker stats 实测（control-app/postgres/holmesgpt/prometheus/alertmanager/notify/order-arena/chaos-admin）。
- **§3 引擎同窗对照**（复用 m6-engine-observation.sql，**双引擎同窗并列，禁 before/after**）：预算 HOLMES 586,615 tokens/81 报告 vs NATIVE usage_missing=1 诚实单列；延迟 HOLMES p50 13.3s/p95 120.8s vs NATIVE 196ms；错误率/对照进度（2 行 2 flagged 0 底噪）。
- **§4 积压**：`in_flight=2 oldest_ready_age=none`（该 2 行=M6-02 影子 run REPORTING 永续态，属设计内，非积压）。
- **§5 绝对 SLO**：SLO-1 内存 PASS；SLO-2 holmes p95=120,809.5ms vs LLM gateway 总时限 300,000ms（application.yml 冻结面）内；SLO-3 native p95 只报绝对值（无冻结阈值）；SLO-4/5 诚实边界（token per-report 无冻结值；node_exporter 缺失致三档闸持续窗无数据源）。

## 二、HOST1 三档内存闸演练（`m6-memory-gate-drill.log`）

- **安全裁定（先于演练）**：不在共享生产宿主人为压低 MemAvailable（OOM 危及 PG/控制面）；压力面受控演练待 node_exporter+rule 面落地后按 runbook 执行。
- **§A 分类**：available=3221MiB → **L0**。
- **§B tier1 动作面实弹**：order-arena（RSS 214.1MiB/512MiB limit）停止 → **available +258MiB（3221→3479）**、控制面全程 health 200 → 重启回 healthy——tier1「关 order-arena」动作面真栈可执行且不伤控制面。
- **§C slot 2→1 机制面**：V7 :337「扩容=新迁移加行，**不 DELETE**」——槽位为迁移面固定量，运行时无降槽旋钮；只读验证 RcaWorker :259 槽位上限=领取上限语义在位（slot_rows=2）。
- **§D/E tier2/3 缺口取证（四项现场证据）**：G1 `/actuator/health/readiness` **404**（probes 未启用，tier3 无表达面）；G2 主树无 MemAvailable/RESOURCE_MODE_CHANGED/内存闸组件（grep=0）；G3 Prometheus `node_memory_MemAvailable_bytes` **空向量**（node_exporter 缺失=P7 B1 复现，5min/2min 持续窗判定无数据源）；G4/G5 领取暂停无水位联动旋钮、独立值班告警 O-66 未收口。
- **结论**：架构 :946-953 的**自动触发面**（判定器/事件/联动）整体未落码——tier1 已有动作面（停 arena）实弹可执行；tier2/3 机制缺口如实登记 **BUGLOG BA-54**，修复需新增 MemoryGate 组件（新类，超出 M6-03「无新类」边界，留 G1 裁定排期）。

## 三、回退演练（E2E-AM6-03 五相位 PASS，suite 20260908T192428Z）

- **phase0 50% 姿态**：percent=50 bundle（whitelist 直达键+max_native_runs=50+proposal）发布/激活，status `"percent":50`，active=`7a83d05c…`。
- **phase1** 容量报告真栈执行留证（见上）。
- **phase2 白名单直达**：WHITELISTED → NATIVE run `46e22641` **在途即取证** config_digest=`7a83d05c…`（=D50，run 启动固定）→ 终态 SUCCEEDED 复验不变。
- **phase3 回退演练全绿（断言 SQL 随件）**：percent=0 rollback → 新注入键 `BUCKETED_HOLMES`+`percent=0`+`engine=HOLMES` → **在途/历史 NATIVE run config_digest 冻结不变**（`rollback-digest-frozen.txt`）→ status 回 0。
- **phase4 终态**：active=`852040c9…`（percent=0 安全态，验证后不留放量态）。

## 文件清单

| 文件 | 内容 |
| --- | --- |
| m6-memory-gate-drill.sh / m6-m603-memory-gate-drill.log | 三档内存闸演练脚本与 195 实测输出（含 arena 重启回健康事后核验行） |
| m6-capacity-report.sh | 容量报告脚本副本（正本随 `deploy/policy/` 入码） |
| m6-pack-m603.sh | 证据打包脚本（tgz sha256 双侧一致 9729be31…） |
| ../e2e-脚本/e2e-am6-03-capacity-rollback.sh | E2E-AM6-03 场景脚本 |
| ../e2e-证据/20260908T192428Z-am6-03/ | 五相位工件（status 面/capacity-report/rollback-digest-frozen/scenario-results 等） |
| ../e2e-证据/logs/m6-e2e-am6-03.log | 场景运行全日志（wrapper 脱敏流） |

密钥面：全件零密钥（bearer/PG 口令只经 195 shell 变量；入 git 前扫描零命中）。
