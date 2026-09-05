# AM2 三场景 E2E 证据包（M2-25~28 验收）

**记录轮次**：attempt 8（run tag `0905040208`），2026-09-05T04:02:08Z ~ 04:08:43Z，195 真栈（146.56.195.225），slot=1 串行。
**结论**：`RESULT: ALL PASS`——preflight / F1 / F2 / F3（phase1+phase2）/ TTL 演练 / DP-C02 共 8 个阶段退出码全 0，日志零 `E2E|FAIL`、零 Traceback；落库终态快照随日志归档（会话全 CLOSED、审计语义全对、scenario_map v1/v2、incident 全 RESOLVED、rca_run 全 SUCCEEDED、rca_report 全 STRUCTURE_VALIDATED）。

## 归档清单

| 文件 | 说明 |
| --- | --- |
| `e2e-三场景-run.log` | attempt 8 全程日志（含部署门取证与落库取证） |
| `e2e-脚本/driver.py` | E2E 驱动（容器内执行；preflight/phase1/phase2/ttl/dpc02 五个子命令） |
| `e2e-脚本/e2e_runall.sh` | 编排器（F1→F2→F3 串行 + TTL + DP-C02 + 部署门 + 落库取证；退出码落盘判定，杜绝假绿） |
| `e2e-脚本/watch_incident.sh` | incident/rca_run/rca_report 三独立查询侦听器（不依赖 `incident.current_rca_run_id`——该列在 run 完成后会被清除） |
| `e2e-脚本/e2e_setup.sh` | 一次性环境准备（arena-e2e-cli 容器，eval-mgmt+alert-net 双网 + token） |
| `e2e-脚本/reset_arena.sh` | arena 域 13 表 TRUNCATE（轮次隔离，场景间零串数据） |

## DP-C01~07 部署门实例化

| 门 | 断言 | 证据（log 行） |
| --- | --- | --- |
| DP-C01 容器健康 | order-arena / arena-chaos-admin 均 healthy | L132-134（`Up ... (healthy)`） |
| DP-C02 正常流量面 | live- 流量计数增长（26177→26252，increase[2m]=120）、live- 创单 GET 可见、幂等重放返回原单（200 replayed=true，requestDigest 不含 correlationId）、探针自证 up | L122-129 |
| DP-C03 网络隔离（C-3） | arena 仅 alert-net；admin 仅 eval-mgmt；postgres=alert-net+deploy_internal+eval-mgmt；admin 宿主端口=0 | L135-143 |
| DP-C04 管理面鉴权 | 无 token 401 / 错 token 401（另有 503 未配 token 语义由单测覆盖） | L6-7 |
| DP-C05 指标/规则 | oa_domain_probe_up=1.0、三告警 firing 观测、指纹三重一致；promtool 双检 5 用例（另档） | L10、L26/58/90 |
| DP-C06 迁移 | flyway v1~v6 全 success=true | L144-151 |
| DP-C07 TTL 自愈 | ttl=30s 无操作员：TTL_EXPIRED→RECOVERING→CLOSED+RECOVERED，恢复语义成立，告警回落台账归零 | L112-120、L183-185 |

## manifest：GT/Alert/Incident/Run/Report 一一关联（实际 ID）

| 场景 | alertname | 冻结指纹 | incident_id | gen | run_id | report_id |
| --- | --- | --- | --- | --- | --- | --- |
| F1 重复单 | ArenaDuplicateOrders | `0d7404ae811ae84a` | 5552d241-77f4-4bcd-abeb-35f3f2f1a90d | 3 | c3bb1d72-99cb-4dbc-80b8-a6448919e3af | 9705938e-8e96-4c9c-85eb-0dd0885afc32 |
| F2 非法回跳 | ArenaIllegalTransitions | `653693464eea7e9b` | 58dfb3a4-8972-4111-ab11-868093aeb8fc | 0 | 7bc4f687-4cb4-43a3-8c92-59da12579194 | a619810c-47a8-4b30-97d5-3caec6291f25 |
| F3 卡单 | ArenaOrderStuck | `f95e79c26f0e7b4c` | 74c07688-1c28-41ee-afde-6819eaff2c4b | 0 | 3b5f107e-f8b2-403c-ad43-d21c414b7dcd | 2d40aa09-1d82-450d-8c5d-deaec2df88a1 |

指纹三重一致（激活响应 == AM API 观测 == 驱动按最终标签集重算）三场景全部成立（L26/58/90）。

## 诚实声明

1. **F2/F3 的 run/report 复用早轮生成**：控制面按 incident 身份（alertname+service+job）与 investigationHash 去重，同指纹重复 firing 不再新烧 LLM 调查。F1 在本轮拿到全新 gen=3 run（c3bb1d72）+ 全新报告（9705938e，STRUCTURE_VALIDATED），证明"告警→incident→run→报告"全链在本轮真跑仍成立；F2/F3 绑定的 run 同为 SUCCEEDED/STRUCTURE_VALIDATED 终态，链路四件套一一关联。
2. **attempt 1~7 为预热轮**（未归档，本地留 attempt 6 log 备查）：暴露并修复了驱动侧四处自伤（phase1/phase2 跨进程订单号传递、TTL 断言元组笔误、dpc02 漏 sku、幂等重放契约误设为 409——实测契约是 200 replayed=true 返回原单）、编排器假绿判定（只 grep FAIL 行不吃退出码，已改退出码落盘+Traceback 双查），以及两处产品缺陷（C-6 指纹算法 ground-truth 修正、F2 恢复配对），均已先修后重跑。
3. **落库取证在 TTL 轮之后执行**：log 中 incident 表快照为全部轮次结束后的终态（含 TTL 轮引发的 F1 代次），非三场景 phase2 刚结束时的中间态。
