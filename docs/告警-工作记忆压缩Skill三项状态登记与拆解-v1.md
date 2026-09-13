# 工作记忆 / 压缩消费 / Skill 提炼：三项准确状态登记与拆解 v1

2026-09-13。按主会话复核口径登记（替代此前「统一解释为有意挂门」的不准确表述），供执行者领任务。

## ① 工作记忆 ruled_out 污染 —— 真实缺陷，**本轮已完整修复（UT 面）**

**缺陷全貌**：`ContextAssembler.rebuildMemorySlots` 把全部 REJECTED 委派裁决灌进业务排除槽；拒绝原因封闭集六类（批形状/委派配额/批内重复 gap/已有台账/角色不可委派/DAG 任务上限）全是控制面，无一是证据排除；且 `mergeAccumulated` 全槽父链继承，旧污染逐轮传播。

**本轮修复（四点对应）**：

| 要求 | 修法 |
|---|---|
| 停止新增污染 | `ruled_out` 供数改为已验证业务排除：`kind=EXCLUSION ∧ evidence_roles 含 REFUTES ∧ 引用非空` 的提案行（与投影 v2 EXCLUSION→FALSE 判据同构）；拒绝史留在 DelegationDecision 行（轨迹面仍可达） |
| 历史污染 | `ruled_out` 改**可信印记重建槽**：仅继承带 `[EX] ` 印记的父项（`ContextAssembler.TRUSTED_EXCLUSION_MARK`）；无印记父项=规则前污染，重建时不继承；旧快照行原样留库（append-only 父链，零篡改），审计可溯 |
| 排除来源验证 | 无 evidence_roles 的 v1 旧行、零引用行、仅 CONTEXT 行都不入槽（支持关系未确认≠排除）——排除语义与投影判定共享同一判据，不复制裸 statement |
| 产生时机 | FINAL 落检查点后下一步信封即见（candidateMemory 每步重建）；中途排除的更优来源=子任务回执业务判定——当前子任务只产证据行不产 claim，**登记为后续增强**（需子任务结论能力，见 ③ 类似的分期思路） |

**验收（用户指定四测，全部落锚）**：旧污染继承=`v3旧污染继承_无印记父项不跨轮传播_带印记合法排除保留`；无证据 EXCLUSION=`v3无证据EXCLUSION_零引用或零REFUTES不入排除槽`；非空排除跨轮保留=`v3非空排除跨轮保留_新FINAL丢弃旧EXCLUSION行不丢已入槽项`；拒绝反馈仍可达=既有 `ba119委派全拒_拒绝码与修正指引写lastError回喂`（裁决当步 lastError 面回喂）+ supervisor 确定性再拒绝。ContextAssemblerTest **19/19 绿**。

**如实边界**：lastError 是"当步反馈+确定性再拒"的事件驱动通道，不是持久拒绝历史——模型在拒绝发生步与再提议步可见，中间成功步清空。这是记录在案的取舍（信息由 DB 裁决史承担审计、由确定性闸承担执行），非"信息完全不丢"的旧说法。

## ② LLM 压缩消费 —— **部分实现、消费效果待验收（本轮补齐消费面隔离测试）**

准确状态（替代"只生不消"）：已有最小消费路径（CL-07 台账+围栏指针+CL-08 受控槽注入）；默认 OFF（`app.alert.r7.compaction.enabled:false`，true 也只到 SHADOW_GENERATE）；**完整替换策略及效果验收未完成**。

本轮推进（用户列四项的可行部分）：

| 项 | 状态 |
|---|---|
| 隔离测试 | 补 `counterEvidenceRefsAreRequiredAndCannotBeOmitted`：记忆槽累计反证纳入 requiredRefs（宿主生成面扩：绑定承诺 ∪ 终局引用 ∪ **工作记忆反证**），漏反证候选整案拒绝零落档。ContextCompactionServiceTest **21/21 绿** |
| 反证保留 | 上条即为消费面反证保留缺口的本轮修复（此前 requiredRefs 不含记忆槽反证，摘要可静默删"已推翻方向"） |
| 恢复测试 | 既有锚：`consumeFenceRejectionKeepsOldPointer`（围栏拒绝保旧指针）+ `appliedThenReplayedConverges`（崩溃重驱 REPLAYED 收敛不双推进） |
| 预算测试 | 压缩模型调用生产装配走 `RcaActionGuard::guardedModelCall`（TOKEN 记账继承 guard 面，R7ActionGuardAdmissionTest 覆盖 provisional 语义）；服务级测试用脚本桩不经 guard——按装配继承登记，不重复建测 |
| MC34 三臂 | **NOT_RUN，归模型窗**；按主会话裁定：历史 blocker 是否仍成立须按当前版本（含 evidence_roles 协议 v2+错误分类修复）复测，不得引用旧失败永久停工 |

生产启用条件不变：MC34 三臂（OFF/确定性/CONSUME_VALIDATED）出收益与反证保留证据后，才切替换式消费。

## ③ Skill 提炼 —— **模板路径已交付；LLM 提炼与自动触发未交付（分期拆解）**

现状：`SkillCurationController` TEMPLATE 同步提炼可用；mode=LLM_CURATE 返回 501=该能力未交付（诚实面，非完成态）。

**持久任务设施复用排查（先做项，结论）**：

| 现有设施 | 形态 | 可否复用 |
|---|---|---|
| drill_job/drill_event（V86） | 持久 job+状态机+原子占位+幂等键 | 否——eval/drill 域绑定（同靶场占位、§7.4 CLOSED 双向钉） |
| report_publication（V9） | lease 认领+attempt 退避 | 否（发布域专用），但**认领/重试模式可复制** |
| rca_compaction_attempt（V102） | RESERVED→IN_FLIGHT→终态台账+唯一键单胜者 | 否（压缩域专用），但**幂等单胜者模式可复制** |
| skill_candidate（V97） | DRAFT→复核→ACTIVE 生命周期+S02 未复核隔离 | 是——curation 产物天然落此处，无需新候选面 |

**结论**：无现成表可直接承载（域绑定+形状不符），但新表 `skill_curation_job` 只需照抄两个在库先例的模式（lease 认领 × 幂等单胜者台账），零新机制；候选隔离复用 V97 S02 现成面。

**分期任务卡**（逐卡可独立验收）：

1. **SC-1 人工发起 LLM 提炼**：`skill_curation_job` 表（幂等键 (source_digest, policy) 单胜者、lease 认领、attempt 上限、状态封闭集）+ 异步执行器挂 worker 拍独立容错 + POST /api/skill-curations LLM_CURATE 分支改 202 受理→job 驱动 + 独立 TOKEN 预算键（非 run 维，需 BudgetKind 面扩展裁定）+ 取消（终态 CAS）+ 候选落 V97 隔离面。隔离验收零真模型（脚本桩）可先行。
2. **SC-2 自动生成候选**：触发判据（封存资格 run 数阈值）+ 去重（源 digest 命中既有候选/job 即跳过）+ 队列上限 + 审核积压背压（未复核存量>阈值暂停派发）。前置：SC-1。
3. **SC-3 发布与消费**：独立评测批（提炼质量对照）+ 审核流 + 版本绑定（S11 钉版链）+ 回滚（DEPRECATED 语义已备）。前置：SC-2+真模型窗。

## 验证与部署

- 本轮（①修复+②测试）全量回归随部署窗执行；195 部署随本窗合流。
- ②的 MC34 复测、③的 SC-1~SC-3 归后续窗；SC-1 可先行零模型隔离开发。
