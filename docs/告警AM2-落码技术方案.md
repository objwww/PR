# 告警 AM2（G1）订单靶场落码技术方案（执行者用）v1.0

> 定位：AM2 编码的**执行施工图**。设计依据 = `docs/告警AM2-技术方案.md` v3.0（语义权威）；任务编号对齐 `docs/告警Agent-增量实现任务拆解-v1.md` M2-01~28。
> 动工硬前提：**AM1 G0 收口完成且过 G2**（G0-11）。
> 执行纪律：按编号顺序；每任务带验收命令；只执行与取证；设计问题回报主会话。部署目标 = `deploy/alert/docker-compose.yml`（AM2 v3.0 §6.1 冻结）。

---

## 阶段一：工程底座（M2-01~06）

### M2-01 order-arena 模块骨架
- 新建 `order-arena/` 模块：`com.objwww.pr.arena.*`，Spring Boot 3.4.5 + Java 21 + JdbcClient + Flyway（`order-arena/src/main/resources/db/migration/`），入根 pom `<modules>`
- 沿用 control-app 惯例：实现类零 Spring 注解、唯一装配点 `infrastructure/config/ArenaConfig`（`@Profile("docker")`）、虚拟线程 worker 用 initMethod/destroyMethod
- 验收：`mvn -q test -pl order-arena` 绿；context smoke 通过

### M2-02 deploy/alert compose 扩展
- `deploy/alert/docker-compose.yml` 增 `order-arena` 服务（内存限额 512M、non-root、read_only、cap_drop ALL；业务网 `alert-net` + 管理网 `eval-mgmt` 双网络成员——管理网暂不连其他容器）
- `eval-mgmt` 网络定义（internal: true，无宿主端口）
- 验收：本机无法验证（无 docker）→ 195 起栈 healthy；`docker network inspect eval-mgmt` 成员正确

### M2-03 DB 角色与 schema
- 扩展 `deploy/db/01-roles.sh`：新增 `arena_app`、`eval_app`、`notify_app` 角色 + `arena` schema；权限矩阵按 AM2 v3.0 §6.1（control_app 禁读 ground_truth、eval_app 只读两边、Holmes 无凭证）
- 验收：195 执行后 `\du` 角色存在；`control_app SELECT ground_truth_scenario` 必败（权限断言）

### M2-04 订单基础表 V1 迁移
- `order-arena/src/main/resources/db/migration/V1__arena_schema.sql`：`oa_trade_order / oa_refund_order / oa_fulfillment_order / oa_resource_ledger / oa_idempotency_record`（字段见 AM2 v3.0 §3.1/§6.2；幂等表含 owner/lease_until/result_order_id/response_digest/expires_at）
- 验收：`ArenaMigrationContractTest`（表/约束/授权）绿

### M2-05 补偿 outbox 表
- 同上迁移含 `oa_compensation_outbox`（八态 + 租约三列 + attempt 计数，照 control-app outbox 范式）
- 验收：迁移契约覆盖

### M2-06 ChaosSession + ground truth 表
- `oa_chaos_session`（scenario_id 唯一 / 同 fault_type+target 最多一个 ACTIVE 部分唯一索引 / expires_at>created_at / 状态 PREPARED→ACTIVE→RECOVERING→CLOSED + generation CAS 列）
- `ground_truth_scenario`（arena schema，`arena_app` RW、`control_app` 禁读、`eval_app` SELECT）+ `oa_chaos_event` 流水
- 验收：四条 DB 约束各有 IT 断言（含并发双 ACTIVE 23505）

## 阶段二：正常订单纵向切片（M2-07~15）

### M2-07~08 状态机与幂等
- `domain/statemachine/`：Booking/Refund/Fulfillment/Pay 四台（显式迁移表 + IllegalTransitionException，照 shared-kernel 范式）；反射穷举测试
- `IdempotencyRecord` + check-and-mark 原子消耗（INSERT ... ON CONFLICT 语义）
- 验收：穷举全绿；幂等唯一约束 IT 23505

### M2-09~10 两步创单
- `TwoStepOrderService`：CREATE 不可见快照（单事务含履约单）→ 逐资源独立短事务扣减（ResourceLedger 逐笔）→ ENABLE 单事务；失败 → DISCARDED + 补偿 outbox 行同事务
- 崩溃窗口三处（CREATE 后/扣减中/ENABLE 前）补偿收敛 IT
- 验收：正常链全绿；三窗口 IT 断言补偿正确反向且幂等

### M2-11 三单一致性
- 取消/退款：用户取消（BUYER 责任，WAIT_SELLER_AGREE 分支）与确认无房（SUPPLIER 责任，自动全退分支）——RefundOrder 独立成单 1:N
- 验收：两分支 L3 用例

### M2-12 补偿 worker
- `CompensationService`：单长驻虚拟线程 claimer（PG outbox 领取 + 租约 + 反向回补 + 台账 refund 幂等跳过）
- 验收：乱序/重投/崩溃回收 IT

### M2-13 业务 API
- `OrderController`：POST /orders（X-Correlation-Id）/ GET /orders/{id}（CREATED 不可见返回 404）/ POST /{id}/pay / POST /{id}/cancel
- 验收：CREATED 不可见断言；正常链 200

### M2-14~15 有界流量发生器
- `TrafficGenerator`：虚拟线程 + Semaphore 闸门（默认 4 ≤ arena Hikari 余量 8）；`live-` 前缀正常流量，速率可配；周期覆盖下单/支付/取消
- 验收：连续 10 分钟运行零错误（无注入时）；并发闸门生效（DB 连接不超配）

## 阶段三：故障叠加（M2-16~24）

### M2-16 ChaosSwitchboard（fail-closed）
- 判定 = 读"已提交且未过期的 ACTIVE 场景"（DB 判定）；内存只做可丢缓存；重启扫描悬挂/过期场景默认关闭 + 审计
- 验收：无 ACTIVE 行 = 不注入（IT）；重启默认关闭断言

### M2-17 ChaosController（eval-mgmt 私网）
- 仅加入 eval-mgmt 网络监听管理端口；`CHAOS_ADMIN_TOKEN` env 注入；请求绑定 scenario_id/action_digest/expected_generation/TTL
- **激活事务**：单事务 INSERT ground_truth + INSERT chaos_session(ACTIVE) + INSERT chaos_event，COMMIT 后才可见
- 验收：无 token/错 digest/错 generation 拒绝且零写入；非 eval-mgmt 成员不可达（195 断言）

### M2-18 F1 幂等失效
- chaos- 前缀流量跳过 check-and-mark → 同 intentId 重复创单/重复扣减
- DomainProbe 扫描同 intentId 多订单 → `oa_duplicate_orders_current/detected_total`
- 验收：注入→指标变化→off 后 current 归零

### M2-19 F2 状态回跳
- chaos- 流量绕过状态机直接 UPDATE 状态字段；DomainProbe 独立校验"状态 × 台账事实"合法性 → `oa_illegal_transitions_current/detected_total`
- 验收：同上；探测逻辑不复用状态机（防循环论证）

### M2-20 F3 超时未知
- ENABLE 前挂起/超时 → 订单卡 CREATED + 资源悬挂；`F3ReconcileService` 持久化状态机 UNKNOWN→RECONCILING→ENABLED/DISCARDED
- 验收：卡单指标 `oa_stuck_orders_current` 上升；SIGKILL 后 RECONCILING 续跑

### M2-21 DomainProbe 与双族指标
- Gauge（`*_current`）/ Counter（`*_detected_total`）双族；首次发现去重键 `(finding_type, entity_id, violation_digest)`；标签无高基数
- 验收：同坏数据不重复计数；恢复后 `*_current==0`

### M2-22 Prometheus 接入
- `deploy/alert/prometheus/rules/arena.yml`（阈值规则，版本管理）；scrape 配置接入 arena `/metrics`
- 验收：注入后规则 firing；AM 收到

### M2-23 告警链联通
- 业务告警经 AM → control-app incident（依赖 AM1 G0 完成）
- 验收：incident 含 arena 标签；firing/resolved 归并正确

### M2-24 ScenarioMap
- `oa_scenario_map`（scenario_id/fault_type/alert_fingerprint/incident_id/generation/run_id/report_id 回填列）
- 验收：注入后 fingerprint/incident 关联落库（run/report 回填属 AM3 eval-runner）

## 阶段四：三场景 E2E + G2（M2-25~28）

### M2-25~27 F1/F2/F3 各自端到端
- 每场景：注入（脚本经 eval-mgmt 调 ChaosController，本阶段可用 curl 模拟 eval-runner）→ 指标 → 告警 → incident → HolmesGPT 报告归档 → off → 复原确认（`*_current==0` + 无残留 firing）
- 证据按 AA-26 契约归档 `docs/测试证据/AM2/<场景>/`
- 验收：全自动完成；人工初判报告与 ground truth 对照记录

### M2-28 AM2 G2 部署门
- DP-C01~C07（AM2 v3.0 §12）：arena healthy/内存水位/正常流量/三故障链/复原/映射核验/GT 权限断言
- 验收：全绿；BA 新缺陷入 BUGLOG；主会话复审 → 用户确认进 AM3

---

## 交付物与 DoD

1. M2-01~28 全部单项验收过；证据落 `docs/测试证据/AM2/`
2. `mvn -q test` 绿；order-arena IT（Testcontainers PG）≥ 4 个类，195 `mvn verify` failsafe 计数含 arena
3. INV-AM2-1~7 全部实证（fail-closed、GT 权限、正常流量零污染为硬指标）
4. AM0 手工配置已全部入 `deploy/alert/` 且与 195 实机一致（承接 G0-09）
5. 文档三件套同步（PROGRESS/BUGLOG/证据清单）；无新增设计文档（定格纪律）
