# R7 真窗第三批（2026-09-12）：RG01/R7-A1 A0 七跑 + 环境雷修复 —— 如实收官

> §0.1 纪律：FAIL 如实登记，不改脚本口径放行。本批七跑 A0 全部 phase5 FAIL，
> 与 2026-09-11 已登记的「A0 第九跑 phase5 FAIL（禁改口径待主会话裁定）」同族；
> 本批价值 = 复现定责 + **发现并修复一处真环境雷**（prometheus 标签断供）。

## 一、七跑矩阵（a0-20260912T111148Z ~ 114050Z，模型 qwen3-max-preview）

| 跑 | 姿态 | phase1-4 | phase5 失败形态 | 定责 |
|---|---|---|---|---|
| run1 | 原 prompt+宽 allowlist(15 工具) | PASS | 证据行恒零 | 环境：service 标签断供（见二） |
| run2 | 同上（指标修复尝试中） | PASS | 证据行恒零 | 同上+修复未生效时序 |
| run3 | 同上（修复已生效） | PASS | 证据行恒零 | 时序边缘：模型查询仍空选择器 |
| run4 | 同上 | PASS | 无带引用 ACTIVE TRUE Claim | 模型：仅 catalog 1 条证据即收敛 |
| run5 | 收窄 allowlist=A0 缺省(prom+logs) | PASS | 证据行恒零 | 模型：查询空选择器 |
| run6 | prompt 覆写 v1（宽匹配提示） | **FAIL**（PRIMARY DEAD=0，NATIVE 兄弟任务兜底 SUCCEEDED） | — | 模型：宽正则查询 → RESULT_OVERSIZE → DEAD（控制面拒绝即死，设计使然） |
| run7 | prompt 覆写 v2（单指标窄查询） | PASS | 无带引用 ACTIVE TRUE Claim | 模型：畸形 PromQL→HTTP 400→REMOTE_UNAVAILABLE ×3 + 零引用收敛 |

## 二、真环境雷（本批发现并修复）

**demo 指标管道 `service` 标签断供**：近窗序列只剩 order-arena（自带 service 标签），
checkout 等 otel-demo 全部服务序列携带 `service_name`（collector prometheus exporter
透出资源属性形状），工具面/告警材料/模型查询契约按 `service` 标签 → 一切 checkout
查询 NO_DATA。修复 = `deploy/alert/prometheus/prometheus.yml` otel-collector 抓取作业
加 metric_relabel_configs（service_name→service 映射，加法双标签）；重启 prometheus-am0
后 `count({service="checkout"})=444` 恢复，冻结窗内 query_range 实测有数据。
（otel-collector 重启无效在先，证明非进程态问题而是标签形状问题。）

## 三、平台诚实语义实证（真模型下的 red 面，全按设计工作）

- 零证据收敛 → NATIVE 确定性回退报告 UNRESOLVED（无假根因，INV 诚实失败）；
- 主 FINAL 提案零证据引用 → `PrimaryFinalClaimProjector` 拒绝成断言（"留检查点审计面"）；
- 宽正则查询超限 → RESULT_OVERSIZE 控制面拒绝 → 主任务 DEAD（fail-fast 无恢复路径）；
- 畸形 PromQL → HTTP 400 → REMOTE_UNAVAILABLE 分类（脱敏固定文案，底层不透传）；
- run 级 NATIVE/PRIMARY 双任务兜底结构：PRIMARY DEAD 后 NATIVE 兄弟任务照常 DONE，
  run 终态 SUCCEEDED（AM4 既有架构行为真机复证）。

## 四、裁定与遗留

- **RG01/R7-A1：FAIL 维持登记**——blocker 与 09-11 第九跑同族（模型行为），prompt
  策略覆写两版均未通过；姿态已回滚（APP_ALERT_R7_PRIMARY_PROMPT 覆写移除），
  主模式 enabled=true 保持。禁改口径裁定事项维持，归主会话。
- **MC34 三臂 / O01：NOT_RUN 维持**——三臂对照的根因正确性维度需要能产出带引用
  断言的基线 run，被同一模型行为 blocker 阻塞；在此之上跑三臂只产生
  UNRESOLVED vs UNRESOLVED 的无信号对照，不跑（不伪对照）。
- **X7/X8 / 浏览器 E2E：NOT_RUN 维持**（X7 需数据盘点选定扩展角色+同预算对照基线；
  浏览器面 web 端口 loopback-only，需接入窗）。
- 修复物：prometheus.yml 标签映射补丁（随本批提交进仓库）；姿态变更全程留证
  （.env 备份 /opt/build/.env.bak-a0prompt-20260912，重启时刻 docker events/日志）。
