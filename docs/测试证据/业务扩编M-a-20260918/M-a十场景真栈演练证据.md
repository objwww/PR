# M-a 业务扩编十场景真栈演练证据（S16~S25）

> 日期：2026-09-18（UTC）／环境：195 生产靶场 alert compose（order-arena + arena-chaos-admin + Prometheus + Alertmanager）
> 门禁：工程门禁（单测+promtool 全绿，见方案 §4 T1~T6）+ 真栈门禁（本文档：每场景 激活→告警→恢复→resolved→SQL 对账 十步）
> 激活方式：`POST /chaos/{faultType}/on`（X-Admin-Token，flagd-admin 经 eval-mgmt 私网调用）；流量：REST 127.0.0.1:8082（chaos- 前缀）
> 演练脚本：research/alert-agent-eval-20260917/drills/ma-drill-s1{6,7,8,9}.sh、ma-t9-{run,s20,s21,s22,s24,s25}.sh

## 一、十场景闭环结论

| 场景 | 故障 | 告警（severity） | 损伤 SQL 对账 | 恢复动作 | resolved | 会话 |
|---|---|---|---|---|---|---|
| S16 掉单 | F9 capture 成功不收口 | ArenaPaymentOrderMismatch（page）gauge=3 | mismatch=3 | 重放 pay 同步 repaired=3 | ✓ | CLOSED |
| S17 支付悬挂 | F10 授权沉默 | ArenaPendingPaymentBacklog（ticket，for 5m）gauge=25 | stuck INITIATED=25 | 置 UNKNOWN=25→F3 对账收敛（24 废单，unsettled=0） | ✓ | CLOSED |
| S18 重复扣款 | F11 已支付闸失效 | ArenaDuplicatePayments（page）gauge=1 | 同单 CAPTURE×2 | 删多余保留最早 repaired=1 | ✓ | CLOSED |
| S19 对账不平 | F12 缺 DISCOUNT 扣减行 | ArenaReconciliationDiff（page）gauge=1 | 类型 3/4 | 补记账 repaired=1 | ✓ | CLOSED |
| S20 库存超卖 | F13 超额 INVENTORY 行 | ArenaOversell（page）gauge=1 | inv_rows=2 | 删超额行 repaired=1（残差 1） | ✓ | CLOSED |
| S21 事件丢失 | F14 履约行被吞 | ArenaFulfillmentGap（ticket）gap=25.6>10 | with_fulfillment=0/25 | 无（counter 窗口自然稀释 ~10min） | ✓ | CLOSED（TTL reaper） |
| S22 重复消费 | F15 消费幂等缺失 | ArenaDuplicateFulfillment（ticket）gauge=1 | attempt=2 | 删重复行 repaired=1（残差 1） | ✓ | CLOSED |
| S24 断流 | F16 入口静默（流量发生器临时停用，.env 备份纪律） | ArenaOrderZeroFlow（page，for 5m） | 断流窗落单=0 | off 后恢复落单 | ✓ | CLOSED |
| S25 履约积压 | F17 收口变慢 | ArenaFulfillmentSlaBreach（ticket）gauge=16>15 | CONFIRMING=16 | 收口 CONFIRMED repaired=16（残差 0） | ✓ | CLOSED |
| S23 KPI 下跌 | （复合低剂量） | ArenaOrderSuccessRateLow 规则在场 health=ok | — | — | — | **部分验证**：复合激活依赖 M-b C3 |

firing 取证样例（Prometheus）：`ALERTS{alertname="ArenaPaymentOrderMismatch",alertstate="firing"}` 等；长窗/快场景的 firing 均有 count_over_time 采样或 ALERTS 快照留痕（t9 系列日志）。

## 二、演练连带揭出并修复的缺陷（设计→落码→DB 约束→真机 四层不对齐）

| # | 缺陷 | 修复 | 捕获轮 |
|---|---|---|---|
| 1 | pay() attempt_no 按 kind 取号撞 uq_payment_attempt(order_id,attempt_no)——库内 160 万 AUTH/0 CAPTURE，支付成功路径自 M2 起从未跑通 | nextAttemptNo 去掉 kind 过滤，按订单全局取号 | S16 首跑 |
| 2 | V9 ck_chaos_fault_type 漏扩 F9~F18——激活 409 被"场景已存在"掩蔽 | 迁移扩 CHECK | S16 激活 |
| 3 | V10 ck_inj_fault_type 漏扩——恢复收口审计 INSERT 被拒，恢复循环每 5s 重试 | 迁移扩 CHECK | S16 恢复 |
| 4 | V11 探测面性能事故：百万行表全表扫（单查询 1.6~4.7s，一轮 ~4min）→探测节奏 30s 退化分钟级 | 6 索引 + 全部事实查询加 30min 回看窗（lookback-seconds） | S16 firing 缺失 |
| 5 | V12 ck_probe_finding_type 漏扩——业务 episode 开户被拒→C-7 失明→业务告警永不 firing | 迁移扩 CHECK | S16 firing 缺失 |
| 6 | V13 arena_app 缺 oa_payment_record/oa_resource_ledger/oa_fulfillment_attempt DELETE 权——恢复修复 42501 被映射成 bad SQL grammar | 迁移补授权（授权后卡死会话 30s 自愈） | S18 恢复 |
| 7 | V14 oa_fulfillment_order 缺 arena_app DELETE 权——F14 注入点 dropFulfillmentTx 失败：25 单 CREATED+履约在+零台账+幂等卡 PROCESSING | 迁移补授权 | S21 首跑 |
| 8 | 消费候选扫描无界：百万存量 CONFIRMED 单（attempt 台账上线前）使新单数小时不被消费 | 候选扫描加 30min 回看窗 | S22 首跑 |
| 9 | F14/F16 恢复分支无审计→会话 RECOVERING 永卡且占用 uq_chaos_one_active 靶位 | 恢复 switch 补审计分支（repaired=0 亦可收口），卡死旧会话部署后 30s 自愈 | S21 重跑 409 |

## 三、证据文件

- t9-编排器日志.log —— S20/S22(首跑)/S25/S21(首跑)/S24 全量输出 + S23 规则在场取证
- t9-S21S22重跑日志.log —— S21 首次重跑（409 掩蔽取证）与 S22 重跑闭环
- t9-S21终跑日志.log —— S21 终跑（FIRED=1、gap=25.6、窗口稀释 resolved）
- S16~S19 时间线：见 git 提交 087a90b4 / 6a36ebee 说明与上方表格（同版式脚本参数化复用）
- Prometheus 端点：/api/v1/rules（14 条 Arena 规则 health=ok）、ALERTS 序列快照、count_over_time firing 采样
