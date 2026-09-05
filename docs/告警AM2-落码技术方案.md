# 告警 AM2 订单靶场落码技术方案（执行者用）v2.0

> 定位：AM2 编码的**执行施工图**。任务编号严格对齐 `docs/告警Agent-增量实现任务拆解-v1.md` M2-01~28（唯一任务表）；设计依据 = `docs/告警AM2-技术方案.md` v3.0 + `docs/架构设计-告警Agent-v1.2.md`（FUT 系列）。
> v2.0：按评审重排任务编号（此前版本错号/并项/漏项，作废）+ 冻结 7 项设计裁定（§0）+ 测试映射全表（§各任务）+ 验收命令修正（§DoD）。
> v2.0r2 边界声明（评审校准）：**AM2 只负责"故障、真值、映射、恢复"**——F1/F2/F3 可重复注入与完整恢复、类型化 GT、ScenarioMap、每场景一次 Live E2E、供 M3-10 消费的版本化契约。**AM2 不提前实现**：EvidencePackage v2、GoldenCase 适配与评分、EvalRun 持久化、正式 eval-runner、5×2 批量评测、notify-app、LiteLLM、investigation/tool-call 落档（全部属 AM3 M3-01~30）。
> 执行纪律：按编号顺序；只执行与取证；设计问题回报主会话。

---

## 0. 编码前设计裁定（评审提出，全部冻结）

**C-1 订单模型**：三张业务单 = `oa_trade_order` / `oa_refund_order` / `oa_fulfillment_order`；**支付不是第四张业务单，但必须有支付事实流水 `oa_payment_record`**（attempt/result/金额/时间戳）——F2"PAID 但无支付事实"由 DomainProbe 靠它检出。先后关系冻结：`CREATE→资源扣减→ENABLE`（订单生效）在前，`pay()` 回调只能作用于 ENABLED 订单。**术语说明**：任务拆解 M2-11 写"业务单/支付单/履约单"，其"支付单"指一致性模型里的支付事实维度，与本裁定的 `oa_payment_record` 是同一事物——以本裁定为准（支付事实流水，非独立业务单）。

**C-2 幂等表与冲突语义**：`oa_idempotency_record` 字段 = `intent_id/request_digest/state(NEW/PROCESSING/CONSUMED/EXPIRED)/owner/lease_until/lease_epoch/result_order_id/response_digest/expires_at`。语义冻结：同 key 同 digest 且 CONSUMED → 重放原结果；同 key 同 digest 且 PROCESSING → **202 处理中**（带 Retry-After 提示）；**同 key 不同 digest → 409 冲突**；租约过期回收 PROCESSING。

**C-3 Chaos 管理面拓扑**：**拆独立服务 `arena-chaos-admin`**——独立容器、只加入 `eval-mgmt` 网络、持 `CHAOS_ADMIN_TOKEN`；业务 `order-arena` 容器只在 `alert-net`，对 `oa_chaos_session` **只读**。理由：一个容器加入两个网络后监听 0.0.0.0 的端口在两网均可达，"同容器分网监听"不成立。角色拆分：`arena_app`（业务表 RW + chaos session SELECT + GT 禁读）/ `chaos_admin_app`（session/event/GT 写）/ `eval_app`（GT 报告封存后读）/ control-app 与 Holmes（GT 与 chaos admin 均不可达）。

**C-4 故障恢复算法**（开关 off ≠ 自动修复，必须显式）：
- **F1**：选 canonical order（最早创建者保留），其余重复单 DISCARDED + 资源按台账反向补偿
- **F2**：按支付/履约/资源事实恢复合法状态组合；无法确定 → 废单终态 + 人工标记
- 修复动作幂等、失败可重试、受 lease epoch 栅栏；`RECOVERING→CLOSED` 仅当 Gauge 归零 + 台账清平

**C-5 Ground Truth 契约**（FUT-46）：字段 = `schema_version/dataset_version/scenario_id/activation_generation/config_digest/payload_digest/applicable_scope/valid_from/valid_until/review_status/created_at`；**append-only 禁 UPDATE**，纠错用新版本行。

**C-6 ScenarioMap 归属算法**（禁止时间窗猜测、禁止 scenario_id 进 Prometheus 标签）：激活时记录 alert identity/rule digest → 按冻结 label 集**预计算 fingerprint**（算法与 Alertmanager 一致）→ 等待匹配 fingerprint 的 incident → 事件游标 + incident generation CAS 回填 run/report id；**全局 Live E2E slot=1**（一次只跑一个真实故障场景）。

**C-7 DomainProbe 失败语义**：DB 查询失败**保留上一份有效值**（严禁置零制造假恢复）；新增 `oa_domain_probe_up` / `oa_domain_probe_last_success_timestamp` 指标；数据超最大新鲜度触发 probe 自身告警；finding episode 语义 = 同一问题连续扫描只计一次，修复后复发重新计一次。

---

## 阶段一：工程底座（M2-01~06）

| 任务 | 内容 | 验收（测试映射） |
|---|---|---|
| M2-01 | order-arena 模块骨架（零注解惯例、ArenaConfig @Profile("docker") 装配、健康端点、**Dockerfile、内存限额 512MiB**） | 根 reactor 构建；可执行 jar；容器 health 绿 |
| M2-02 | deploy/alert compose：**arena + arena-chaos-admin 双服务**（C-3 拓扑）；arena 入 alert-net、chaos-admin 只入 eval-mgmt（internal） | `docker compose config --quiet`；安全项（non-root/read_only/cap_drop）与网络成员静态断言（进 CI/部署门） |
| M2-03 | DB 角色：`01-roles.sh` 扩展 `arena_app/chaos_admin_app/eval_app` + arena schema + 权限矩阵（C-3）。**注意：`notify_app` 不在此创建**——其创建与授权归 AM3 M3-03（V8 迁移），任务拆分原文如此 | 正反权限 IT：arena_app 写 GT 必败、control_app 读 GT 必败、eval_app 读 GT 成功、PUBLIC 零权限、default privileges 覆盖新表 |
| M2-04 | 订单基础表 V1 迁移：Trade/Refund/Fulfillment/ResourceLedger/IdempotencyRecord（C-2 全字段）+ **oa_payment_record**（C-1） | 迁移契约 IT（表/约束/索引/授权）；幂等表唯一键 |
| M2-05 | 补偿 outbox 表（八态 + 租约 + attempt） | SKIP LOCKED 双 worker 不重复领取 IT；租约过期回收 IT；旧 epoch 提交终态被拒 IT；`EXPLAIN` 命中 claim 索引 |
| M2-06 | `oa_chaos_session`（AM2 v3.0 §6.5 四约束）+ `ground_truth_scenario`（C-5 全字段，append-only）+ `oa_chaos_event` | 四约束逐项 IT（双 ACTIVE 23505、TTL 上下界、GT 缺失 ACTIVE 不可见）；GT UPDATE 必败 |

## 阶段二：正常订单纵向切片（M2-07~15）

| 任务 | 内容 | 验收 |
|---|---|---|
| M2-07 | **纯领域**：四台状态机（Booking/Refund/Fulfillment/Pay）迁移表 + ArchUnit | 反射穷举全绿；ArchUnit 零框架依赖 |
| M2-08 | **幂等机制**：claim/replay/conflict 全语义（C-2） | 同 key 同 digest 重放；同 key 不同 digest 409；PROCESSING 中重入 202；并发 32 请求只创一单；claim 后崩溃回收；旧 owner fenced |
| M2-09 | **创单第一步**：CREATE 不可见快照（订单+履约单单事务） | CREATE 后崩溃窗口 IT；CREATED 订单查询不可见 |
| M2-10 | **创单第二步**：逐资源扣减（台账逐笔）→ ENABLE；**支付结果映射**（成功/拒绝/未知/迟到成功四路径） | 每项资源扣减后与 ENABLE 前崩溃窗口 IT；支付四路径用例 |
| M2-11 | 三单一致性：取消/退款（BUYER/SUPPLIER 责任分支、金额约束） | pay 与 cancel 并发；支付回调重复；NO_ROOM 与支付并发；退款金额/责任方约束 |
| M2-12 | **补偿事件生产**（只写 outbox，与业务终态同事务） | 业务终态与 outbox 同生共死 IT（崩溃窗口两向） |
| M2-13 | **补偿 worker**（领取 + 反向回补 + 幂等） | 反向顺序断言；补偿后未 ack 崩溃重投幂等；毒事件进终态；旧 epoch 栅栏 |
| M2-14 | **正常业务 API**（OrderController 四端点） | 正常链 200；CREATED 404；非法状态迁移 409 |
| M2-15 | **有界流量发生器**（虚拟线程 + Semaphore，默认 4） | 并发上限断言；过载立即拒绝；停止后线程/许可归还；DB 池不超 8 |

## 阶段三：故障叠加（M2-16~24）

| 任务 | 内容 | 验收 |
|---|---|---|
| M2-16 | ChaosSwitchboard 仓储（fail-closed，C-3/激活事务） | DB 不可用/超时一律不注入；on/off 重放；错 digest/generation 拒绝；TTL 到期自动 CLOSED；三写任一点失败零可见 |
| M2-17 | arena-chaos-admin 管理 API | eval-mgmt 正向可达；alert-net/Holmes 容器/宿主端口全部不可达（195 断言） |
| M2-18 | F1 幂等失效 + **F1 恢复算法**（C-4） | 同 intent 并发重放确定性产生重复；live 零污染；off 后执行修复（canonical 保留+其余废单+补偿）并归零 |
| M2-19 | F2 状态回跳 + **F2 恢复算法**（C-4） | 精确目标订单回跳、非目标不变；Probe 不复用状态机；修复后合法组合恢复；无法确定→废单终态 |
| M2-20 | F3 超时未知（持久化 UNKNOWN→RECONCILING→ENABLED/DISCARDED） | timeout/late-success/reconcile 三路径；双 reconciler 互斥；SIGKILL 后续跑；**全程禁用固定 sleep 表达状态** |
| M2-21 | DomainProbe + **Gauge** 族（含 C-7 失败语义与 episode） | Gauge 与 SQL 真值一致；查询失败保留旧值+probe_up 指标；超新鲜度触 probe 自身告警；同问题连扫计一次、复发新 episode |
| M2-22 | **Counter** 族（首次发现去重） | 重复 scrape 不增 Counter；并发 Probe 只计一次；重启语义稳定 |
| M2-23 | **Prometheus 告警规则**（arena.yml 入库） | `promtool check rules` 通过；`promtool test rules` 覆盖 pending/firing/resolved/no-data；arena/probe down 不得伪装业务恢复 |
| M2-24 | ScenarioMap（C-6 归属算法）+ **ScenarioMapExportV1 交接契约**（供 AM3 M3-10 直接消费，不靠自由文本/时间窗猜）：字段 = `schema_version/scenario_id/activation_generation/fault_type/target/expected_root_cause{component,fault_type,reason_code}/expected_symptom_codes[]/alert_identity_digest/alert_fingerprint/incident_id/incident_generation/run_id（AM2 可为空）/report_id（AM2 可为空）/payload_digest/created_at` | fingerprint 与 AM 算法一致实测；未知 scenario 拒绝；旧 generation 迟到事件不串场；版本/digest 改变 CAS 失败；**契约测试**：digest 稳定、字段顺序不影响 canonical digest、未知 fault_type/reason_code 拒绝、eval_app 可 SELECT 不可 UPDATE、control/Holmes 不可 SELECT |

## 阶段四：三场景 E2E + G2（M2-25~28）

| 任务 | 内容 | 验收 |
|---|---|---|
| M2-25~27 | F1/F2/F3 顺序端到端（Live E2E slot=1，C-6）；**每场景跑通一次即可**——5×2 串行批量评测属 AM3 M3-17，AM2 不越界 | 每场景：注入→指标→告警→incident→Holmes 报告→off→修复→归零；GT/Alert/Incident/Report 一一关联（manifest 记录实际 run_id/report_id）；live/chaos 并行隔离；场景间零串数据；失败路径仍执行恢复；完整 AA-26 证据包 |
| M2-28 | AM2 G2 部署门（DP-C01~07）+ 测试进程崩溃 TTL 清理演练 | 全绿；BA 入账；主会话复审 → 用户终审 |

---

## DoD（验收命令修正版）

1. M2-01~28 单项验收全过；每任务验收项有明确测试映射（不以"IT 类数量"作质量指标）
2. 快速单测：`mvn -q -pl order-arena -am test` 绿；**完整门：`mvn -q clean verify` 绿且 Failsafe 实际执行数非零**（IT 由 failsafe 在 verify 阶段运行）
3. 部署静态门：`docker compose -f deploy/alert/docker-compose.yml config --quiet` + 网络成员/安全项断言
4. 规则门：`promtool check rules` + `promtool test rules` 全过
5. 真栈门：195 上 M2-25~28 E2E 全绿 + 证据包归档 `docs/测试证据/AM2/`
6. INV-AM2-1~7 实证；文档三件套同步；无新增设计文档

## 修订记录

| 版本 | 变更 |
|---|---|
| v1.0 | 初版（任务编号错位，作废） |
| v2.0 | 按评审重排为权威任务编号 M2-01~28；冻结裁定 C-1~C7（订单模型含支付事实流水/幂等冲突语义/chaos-admin 独立服务与角色拆分/F1·F2 恢复算法/GT append-only 契约/ScenarioMap fingerprint 归属算法/DomainProbe 失败语义）；测试映射全表；DoD 验收命令修正（verify/promtool/compose config/真栈门） |
| v2.0r1 | 主会话自查纠偏：亲自逐行核对任务拆分原文后修两处——M2-01 补 Dockerfile 与 512MiB 限额（原文明确）；C-1 补术语说明（拆解 M2-11"支付单"= 支付事实维度，以 C-1 裁定为准）。其余逐条核对一致 |
