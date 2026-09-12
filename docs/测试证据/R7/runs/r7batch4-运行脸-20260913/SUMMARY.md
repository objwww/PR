# R7 真窗第四批（2026-09-13）：O01 状态闭环 + B4 canary 运行脸 + L 模式八跑定谳

## 一、O01 状态闭环 PASS（真机）

resolved 注入 → **incident RESOLVED**（generation=0、received_count=31、resolved_at 落档）；
状态对账：alert_event 31 条、report_publication 183 行、近 2h 调查报告 7 份在档。
诚实边界：本轮调查产物为 NATIVE 确定性链 UNRESOLVED 报告（模型能力 blocker 见三），
状态机闭环与发布/事件计数面按设计工作。

## 二、B4 canary 运行脸 PASS（真机端到端首闭环）

1. **采集面**：NATIVE run 收尾链自动采集——8 次真模型跑的终态 run 全部自动落
   `canary_evidence_sample`（7 条 LIVE_CANARY，stickiness_key=incident_key，
   observed.failed/outcome 原始事实）——零脚本补数（INV-AM6-9 生产路径）。
2. **评窗面**：发布带 canary.window 段的 bundle（min_samples=2/consecutive=2/60min/
   0.5/0.2，EN-02 资格门 SQL 桥授予后 CAS 激活）→ worker 拍自动评窗 →
   `canary_window_verdict` 落行（window_seq=497010，**INCONCLUSIVE**，eligible=1，
   NO_CONTROL_COHORT）——Holmes 退场后无对照组的诚实面（INCONCLUSIVE=缺数不评判）。
3. 全链：采集→bundle 版本化→评窗→判定落库，零人工干预（worker 拍驱动）。

## 三、L 模式真模型全链（A0/RG01）：八跑定谳 FAIL——模型能力阻断

| 杠杆 | 结果 |
|---|---|
| 环境雷修复后（service 标签） | 证据生产打通（catalog/query_range 落行） |
| 协议硬契约补强（claim 必带非空 evidence_refs） | 模型开始携带引用（run3 首次带 ref 的 claim） |
| v3 发现式策略（catalog 先行） | 模型用 catalog+query_range（phase5 卡 allowlist 期望→放宽后卡 claim 面） |
| v4 双源策略 | logs.query INVALID_ARGS×2（时间格式混用） |
| v5 精确 arg 配方（两工具时间格式相反） | prometheus.query INVALID_ARGS×4（数字 vs 字符串面） |
| 反馈保真度修复（INVALID_ARGS 附执行器具体原因回喂） | qwen 仍单源收敛；glm-5 RESULT_OVERSIZE DEAD |

**结论**：双模型（qwen3-max-preview/glm-5）×三版策略 prompt×反馈保真修复，
八跑均无法在 8 步预算内完成多源取证收敛——RG01/R7-A1 的 blocker 定性为
**模型工具调用可靠性能力面**（与 09-11「第九跑」登记同族，本批为穷尽性定谳：
环境/prompt/反馈三面均已排除）。协议补强与反馈保真修复为真实产品改进保留
（run3 的带引用 claim 为历史最近距离）。 posture 已回滚 prompt 覆写、
AGENT_MODEL=glm-5（原 qwen3-max-preview 备份 /opt/build/.env.model-backup-20260913）。

## 四、连带裁定（阻断传导）

- **MC34 三臂 / MC05 / MC14**：根因正确性维度需带引用断言的基线 run——被同一模型
  blocker 阻塞（八跑定谳），不跑无信号对照（不伪对照）。
- **Skill 8 场景**：运行脸部分达成——采集门（SK-01/02 的原料侧=真实 run 样本
  自动落库）+PostgresSkillCandidateIT 195 真 PG 绿；候选链全 E 脚已绿。
- **B4 scored_json 回填 / B3 余链迁移+压测 / STOP admission / 浏览器 MC33**：
  排队下窗（B3 压测需余链迁移先行；浏览器需接入窗）。
