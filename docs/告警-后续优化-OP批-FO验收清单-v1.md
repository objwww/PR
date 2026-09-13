# 告警-Agent 后续优化 OP 批（第一阶段 P1）FO 验收清单 v1

> 范围：后续优化技术方案 v1 九卡（OP-01~09）中的第一阶段 P1 五卡（OP-01 案例准入 / OP-02 质量口径 / OP-03 动作分析 / OP-04 报告反馈 / OP-05 页面体验），对应测试用例 v1 的 FO01~40 中本批实现面；OP-06~08（模型分级/受控并行/RAG+Skill 运营）按用户裁定与方案触发条件 **DEFERRED**，OP-09（发布闭环）随批走查已覆盖其可执行子集。
>
> 列格式：`FO-ID | 场景摘要 | 实现状态 | 测试类/方法 | 构建与配置 | 模式/环境 | 结果 | 证据 | 清理 | 缺陷ID`
> 结果取值：PASS / FAIL / BLOCKED / NOT_RUN / DEFERRED。**PASS 的门槛 = 所列证据覆盖场景核心断言**；只实现未留证的场景记 NOT_RUN 并注明。
>
> 真机 = 195（146.56.195.225，/opt/build/pr，docker compose 栈）；本地 = Windows 工作树（无 Docker，IT 自动跳过）。

## 一、卡级总览

| 卡 | 状态 | 交付面 | 单测 | 真 PG IT | 195 真机 |
|---|---|---|---|---|---|
| OP-01 案例准入 | 完成 | V104/V106/V107 + RegressionCaseAdmissionService/RegressionCaseController + DatasetVersion 首接生产写路径 | 14+3 绿 | 6/6 绿（PostgresOpBatchIT） | smoke 全链 PASS |
| OP-02 质量口径 | 完成 | QualitySummaryService + GET /runs/{id}/quality | 6 绿 | （读面经 195 API 实证） | 404/200 双面 PASS |
| OP-03 动作分析 | 完成 | V105 + ActionAssessmentService + GET /agent-ops/action-assessment | 10+2 绿 | （表屏障入 PostgresOpBatchIT V105 面） | 端点 200 PASS |
| OP-04 报告反馈 | 完成 | V103 + ReportFeedbackService/Controller | 12 绿 | （V103 面 IT 3 例） | 201/200/409/列表 PASS |
| OP-05 页面体验 | 完成（构建面） | RunDetailView 报告评价卡（FO31-33 门控） | —（前端无 UT 框架） | — | npm build 绿；浏览器截图未采 |
| OP-06~08 | DEFERRED | 触发条件未满足（用户裁定 2026-09-12：第一批只做四件事） | — | — | — |
| OP-09 发布闭环 | 部分随批 | 回归集落库即"固定回归集"底座；评测→授权发布→新Run消费→回滚链 = FO60 BLOCKED | — | — | — |

## 二、逐 FO 验收

| FO-ID | 场景摘要 | 实现状态 | 测试类/方法 | 构建与配置 | 模式/环境 | 结果 | 证据 | 清理 | 缺陷ID |
|---|---|---|---|---|---|---|---|---|---|
| FO01 | 终态报告→候选→审核入集→按新 DatasetVersion 读取 | OP-01 完成 | RegressionCaseAdmissionServiceTest.happyProposalAccepted / PostgresOpBatchIT.regressionCandidateIdempotentAnchor / datasetVersionWriteGrantForControlApp | V104+V106+V107；PersistenceConfig datasetVersionRepository 首次生产装配 | UT+IT+195 smoke | PASS | smoke propose 202→candidateId（/opt/build/runs-opbatch/cd1.out）；dataset/case 行对账 | 留存：dataset op-smoke-ds/v1 + case_version 键 op-smoke-*（可按名清理） | BA-129/130 |
| FO02 | 同源同 caseKey 并发/重放准入只一候选、同 ID、异载荷同键显式冲突 | OP-01 完成 | 同上 anchor 例 + materialize replay 例 | uq(source_digest,case_key) | UT+IT+195 | PASS | smoke propose 重放 200 replayed=true | 同上 | — |
| FO03 | 源证据缺项/未终态 run 拒入集，不编造来源 | OP-01 完成 | RegressionCaseAdmissionServiceTest.sourceContractRefusals（未终态/零证据/未验证报告三拒） | 来源契约门 | UT | PASS | 本地 1857 全绿含此三例 | — | — |
| FO04 | 已发布案例 v1 更正生成 v2，v1 不变、原实验仍解析 v1 | OP-01 完成 | RegressionCaseAdmissionServiceTest（materialize 不可变+重放收敛）；insertCaseVersion 撞 (dataset,case_key)=false 不覆盖 | V20 不可变版本表 | UT+195 | PASS | smoke materialize 201→重放 200 replayed=true；dataset_version/case_version insert-only | 同 FO01 | — |
| FO05 | 同族不跨分区泄漏；HOLDOUT 封存 | 部分：V107 保 HOLDOUT 不可写（应用侧） | PostgresOpBatchIT.datasetVersionWriteGrantForControlApp（TUNING 写）+ V107 with_check | V21 RLS 矩阵存量；assertFamilyPartition 存量 | IT（存量约束未逐族重测） | NOT_RUN（存量面） | family 跨分区断言与 HOLDOUT 导入路径未在本批重测——登记为 OP-09 前置补测项 | — | BA-130（关联面） |
| FO06 | 质量汇总分母/排除原因准确；SUCCEEDED≠根因正确 | OP-02 完成 | QualitySummaryServiceTest 6 例（DECIDABLE 分母/rate 空值诚实/runSucceeded 标记） | GET /api/eval/runs/{id}/quality | UT+195 API | PASS | 195 实数据：439f2cb2 run 10 例 DECIDABLE=10、wrongDiagnosis=10（率 1.0）、reasonableUnresolvedRate=null（分母 0 诚实空）、runSucceededIsNotRootCauseCorrect=true | — | — |
| FO07 | 错误确诊绝对数不被未决掩盖；合理/不合理未决分开 | OP-02 完成 | QualitySummaryServiceTest（wrongDiagnosis 绝对数+shouldHaveDecidedButUnresolved 单列） | 同上 | UT+195 API | PASS | 同 FO06 响应体字段面 | — | — |
| FO08 | 配对比较不错配、缺项 INCONCLUSIVE | 存量面（EvalCompare 线）未动 | — | — | — | NOT_RUN（存量未重测） | 本批未触碰配对面 | — | — |
| FO09 | rubric 冻结、人工/机器分歧不冒充一致 | 未实现（评审 rubric 面） | — | — | — | NOT_RUN | — | — | — |
| FO10 | RAG/历史/反馈路径禁止 GT 泄漏 | 未实现（RAG 属 OP-08） | — | — | — | DEFERRED | OP-08 触发条件未满足 | — | — |
| FO11 | 不同 UUID 同源同窗同内容 ≠ 新观察 | OP-03 完成 | ActionAssessmentServiceTest（(evidenceType,canonicalPayload) 去重） | V105 | UT+IT | PASS | IT actionAssessmentReentrantFaces；逻辑 UT 绿 | — | — |
| FO12 | 频次/时间窗变化保留为新观察 | OP-03 完成 | ActionAssessmentServiceTest（变更窗/频次→新观察） | 规范化 payload 含窗参数 | UT | PASS | 本地全绿 | — | — |
| FO13 | NO_DATA 不自动判无价值；关闭理由可追溯 | OP-03 完成 | ActionAssessmentServiceTest（NO_DATA 分类+gapResolutionRefs 记录） | gap_resolution_refs jsonb | UT | PASS（系统级真样本未积累——分析为按需触发，195 现值 logicalActions=0 属诚实空） | /api/agent-ops/action-assessment 200 真机 | — | — |
| FO14 | 一逻辑动作三物理尝试：逻辑 1/物理 3/费用保留 | OP-03 完成 | ActionAssessmentServiceTest（physicalAttempts 聚合） | V105 物理尝试计数列 | UT+IT | PASS | IT+UT | — | — |
| FO15 | 中断重跑：同版本同快照不重复、完整态可辨 | OP-03 完成 | ActionAssessmentServiceTest（快照含证据集+报告 digest，迟到→新快照重算）+ PostgresOpBatchIT.actionAssessmentReentrantFaces | uq(run,logical_key,version,snapshot) | UT+IT | PASS | IT 重入例 195 绿 | — | — |
| FO16 | 对账完成后重算可见新版本、旧审计不改、UNKNOWN≠0 | 重算面完成；费用对账面未接（存量 ledger 保证 UNKNOWN 不折 0） | ActionAssessmentServiceTest（evidenceSnapshotDigest 含报告面→新快照） | — | UT | PASS（重算面） | 费用对账→assessment 重算链未接（评估属 OP-06 触发后） | — | — |
| FO17 | 同源复述不双计；"被引用"≠因果贡献 | OP-03 完成 | ActionAssessmentServiceTest（去重）+ AgentOpsSummaryServiceTest（descriptiveNotCausal=true 面） | 汇总端点字段 | UT+195 | PASS | 195 端点响应含 descriptiveNotCausal:true | — | — |
| FO18 | SOURCE_FAILED/NO_DATA/正常无新增三因分开 | OP-03 完成 | ActionAssessmentServiceTest（分类优先级链：SOURCE_FAILED>UNDETERMINED>NO_DATA>…） | V105 分类封闭集 | UT+195 | PASS | 195 端点六分类计数字段在档 | — | — |
| FO19 | NOT_ASSESSED 与 0 区分；覆盖率分母正确 | 部分：assessedRuns 已出；覆盖率分母未实现 | AgentOpsSummaryServiceTest（assessedRuns/空分母 rate=0.0 面不含总 run 分母） | — | UT | NOT_RUN（分母面） | 覆盖率（总 run 数分母）登记 OP-03 后续小补 | — | — |
| FO20 | 因果收益声明仅来自开/关对照 | 未实现（评测策略面） | — | — | — | DEFERRED | 属 OP-06 触发后 | — | — |
| FO21 | 终态报告可评价、原 Run/报告不改 | OP-04 完成 | ReportFeedbackServiceTest（终态+STRUCTURE_VALIDATED 门、append-only） | POST /api/rca-runs/{run}/report/{rep}/feedback | UT+195 | PASS（浏览器手测未留证） | smoke 201（author=machine:operator-line）；DB 行 append-only | 留存：report_feedback 键 op-smoke-* | — |
| FO22 | 无报告/活跃 run 反馈明确拒绝；命令面不放开 | OP-04 完成 | ReportFeedbackServiceTest（无报告 404、活跃 run 拒） | 与 RunCommandController 面分离 | UT | PASS | UT 12 绿；真机 404 面由 quality 同构错误处理佐证 | — | — |
| FO23 | 同 (author,key) 同载荷 200/异载荷 409/无双转案例 | OP-04 完成 | ReportFeedbackServiceTest + PostgresOpBatchIT.reportFeedbackIdempotencyFace | uq(author,idempotency_key)+应用层异载冲突 | UT+IT+195 | PASS | smoke fresh 201→replay 200 replayed=true | 同 FO21 | BA-132（IT 夹具修正） |
| FO24 | 身份只认认证主体；越权零写入 | OP-04 完成 | ReportFeedbackServiceTest（author 不自报） | SecurityConfig /api/rca-runs/** OPERATOR | UT+195 | PASS | 195：未认证 GET 401/POST 403（CSRF 先拒）；bearer 线 author=machine:operator-line 落档 | — | — |
| FO25 | 并发更正同一前序一胜一拒；原意见不覆盖 | OP-04 完成 | ReportFeedbackServiceTest + PostgresOpBatchIT.reportFeedbackSupersedesSingleWinner | uq(supersedes_id) partial unique | UT+IT | PASS | IT 195 绿 | — | — |
| FO26 | 高置信反馈不越审核直接转正 | OP-01 完成 | RegressionCaseAdmissionServiceTest（反馈≠GT；仅 ACCEPTED 可 materialize，其余 409） | 候选状态机 | UT+195 | PASS | smoke review 202 ACCEPTED→materialize 201 | — | — |
| FO27 | 两审核者相异→DISPUTED，不最后写入胜出 | OP-01 完成 | RegressionCaseAdmissionServiceTest.majorityDoesNotAutoResolveDisputed（DISPUTED 吸收态）+ IT CAS 例 | V104 状态 CHECK | UT+IT | PASS | 195 IT 绿 | — | — |
| FO28 | feedback→review→case→dataset→eval 身份可追溯 | OP-01 完成 | 全链 UT + 真机 smoke 第 6/7/8 步 | sourceDigest 冻结+candidate 挂 feedbackId | UT+195 | PASS | smoke 全链 201/202 + DB 对账行（candidate.sourceFeedbackId→dataset/case） | 同 FO01 | — |
| FO29 | 断网/403/409 草稿保留、失败不显成功 | 后端面完成；FE 草稿保留已实现 | ReportFeedbackServiceTest（409 版本对账）+ RunDetailView（仅成功清表单） | npm build 绿 | UT+构建 | PASS（浏览器断网演练未做） | 195 digest-mismatch=404→409 响应体真机证；FE 行为代码面 | — | BA-128（401 伪装关联） |
| FO30 | 反馈注入/秘密片段不进 RAG/Skill 事实面 | reason 仅入 append-only 台账；RAG 面未实现 | — | 反馈不进任何调查上下文（无消费方） | 设计面 | DEFERRED（RAG 面） | OP-08 触发后补脱敏/审核留痕场景 | — | — |
| FO31 | 长告警名/未知费用在 1366/1920 可读 | UI 已实现（费用 UNKNOWN 不显 0 为存量行为） | — | npm build（2498 modules） | 构建 | NOT_RUN（视觉证据未采） | 浏览器截图留证待补（登记清账项） | — | — |
| FO32 | 390×844/200% 缩放单列与滚动 | UI 框架响应式存量 | — | 同上 | 构建 | NOT_RUN（同上） | 同上 | — | — |
| FO33 | SUCCEEDED+UNRESOLVED 无限"分析中"绝迹；DONE≠根因确认 | 投影面完成（报告评价卡门控 report.state==OK && !runActive；摘要面存量） | ReportQueryService 投影（存量 UT）+ RunDetailView 门控 | npm build | UT+构建 | PASS | FO06 真机响应 runSucceededIsNotRootCauseCorrect=true 佐证口径 | — | — |
| FO34 | 子任务展开/卡点跳证据 | A 批存量面 | — | — | — | NOT_RUN（存量未重测） | — | — | — |
| FO35 | SSE 游标去重/重连可见 | 存量（FUT-34 换票面） | — | — | — | NOT_RUN（存量未重测） | — | — | — |
| FO36 | 证据筛选/分页/分享 URL | 存量 | — | — | — | NOT_RUN | — | — | — |
| FO37 | 权限不足/能力未就绪显示 | 存量 401→登录重定向 | client.js 拦截器 | — | 存量 | NOT_RUN（本批未重测） | — | — | — |
| FO38 | 纯键盘可达 | 存量（El 组件默认焦点序） | — | — | — | NOT_RUN | — | — | — |
| FO39 | 评测总览→退化组→单例→来源一致 | 存量 eval 面+OP-02 新汇总 | — | — | — | NOT_RUN（浏览器面） | 质量汇总 API 已真机 200（FO06） | — | — |
| FO40 | 资产"可用/发布采用/本Run消费"分开 | 存量 EN-10 版本中心 | — | — | — | NOT_RUN | — | — | — |
| FO41 | 简单告警直接定位优先 | OP-06 DEFERRED | — | — | — | DEFERRED | 触发条件：OP-01/02/03 运行数据积累后启用 | — | — |
| FO42 | 升级/委派准入与剩余预算 | OP-06 DEFERRED（存量委派批上限旋钮已在 .env 透传） | — | — | — | DEFERRED | 同上 | — | — |
| FO43 | 备用模型协议不兼容拒绝/缺价保持未知 | 存量 fallback 三键（R4 批）已部署；FO43 场景未专项重测 | — | — | — | DEFERRED | OP-06 触发后按场景补测 | — | — |
| FO44 | shadow 停止建议/有价值反证不误停 | OP-06 DEFERRED（B4 canary 评窗为相近底座，已真机闭环） | — | — | — | DEFERRED | — | — | — |
| FO45 | 仅独立任务并发；预算不超额 | OP-07 DEFERRED | — | — | — | DEFERRED | 围栏/取消已验收（EX-A2/A3）为前置 | — | — |
| FO46 | 并发池满：控制通路有界、旧结果不覆盖 | OP-07 DEFERRED | — | — | — | DEFERRED | 同上 | — | — |
| FO47 | 文档过滤/固定版本可复现 | OP-08 DEFERRED | — | — | — | DEFERRED | — | — | — |
| FO48 | 召回/排序与定位分别报告 | OP-08 DEFERRED | — | — | — | DEFERRED | — | — | — |
| FO49 | Skill 退化建议复评→撤权 | OP-08 DEFERRED（canary 评窗底座已在 195 运行） | — | — | — | DEFERRED | — | — | — |
| FO50 | 留存策略/授权删除派生处理 | OP-08 DEFERRED | — | — | — | DEFERRED | — | — | — |
| FO51 | 固定集完整多 trial 不挑轮 | OP-09 前置=固定集底座已落（FO01-04）；评测执行面未实现 | — | — | — | NOT_RUN | — | — | — |
| FO52 | 冻结阈值不可原地改写 | 未实现 | — | — | — | NOT_RUN | — | — | — |
| FO53 | 缺价/缺 usage/异币种不冒充降本 | 未实现（成本面存量 usage 缺失诚实 UNKNOWN） | — | — | — | NOT_RUN | — | — | — |
| FO54 | 硬门违规不被均分稀释 | 未实现 | — | — | — | NOT_RUN | — | — | — |
| FO55 | 派生分析故障不阻断调查 | 设计面成立（assessment 按需触发、无同步依赖）；未演练 | — | — | — | NOT_RUN（故障注入未做） | — | — | — |
| FO56 | 回滚兼容：老 Run 身份不变、账本不删 | 存量 release/回滚面未按 FO56 场景重测 | — | — | — | NOT_RUN | — | — | — |
| FO57 | 候选转换中断不丢审核不重复候选 | 幂等收敛面已测（FO02/23/27）；kill 注入未做 | PostgresOpBatchIT 各幂等例 | — | IT | NOT_RUN（中断注入） | 幂等底座=IT 绿；kill 演练登记 OP-09 | — | — |
| FO58 | 重放缺件 REPLAY_INCOMPLETE 禁触网补真值 | 未实现 | — | — | — | NOT_RUN | — | — | — |
| FO59 | 隔离业务告警全链（firing→…→终态反馈） | 反馈段真机 PASS（本批 smoke）；前段 O01 闭环 PASS（r7batch4）；通知不外发真实值班=存量通道隔离 | op-smoke 全链（真实 NATIVE run 报告） | 195 | 真机分段 | PASS（分段拼合，整链单脚本未跑） | O01 闭环（r7batch4 行）+ 本清单 FO21-28 证据 | smoke 数据见清理列 | — |
| FO60 | 反馈→审核→案例→策略评测→授权发布→新Run消费→回滚 完整验收链 | 前半链（反馈→审核→案例落库）PASS；策略评测→授权发布→新Run消费→回滚 = OP-06~09 能力未实现 | — | — | — | BLOCKED | 按 FO60 自身规则：前置未实现即 BLOCKED，不以截图冒充 | — | — |

## 三、清账/遗留清单

1. **浏览器视觉证据（FO31/32）**：RunDetailView 报告评价卡与响应式走查未截图留证——建议随下一批前端卡补 1366×768 / 1920×1080 / 390×844 三档截图。
2. **FO05 family 分区/HOLDOUT 导入路径重测**：OP-09 启动前补 assertFamilyPartition + HOLDOUT owner 导入用例。
3. **FO19 覆盖率分母**：actionAssessmentStats 增总 run 数（窗口内）分母小补。
4. **FO57 kill 注入演练**：候选转换中断恢复（幂等底座已绿）。
5. **/error→denyAll 伪装面**（BA-125/128 两案同族）：建议单独立卡——API 401/403 可信度问题，error 分派放行待裁定。
6. **195 smoke 数据留存**：report_feedback（键 op-smoke-*）、rca_regression_candidate/review（caseKey op-smoke-*）、dataset op-smoke-ds/v1 + case_version——留作 FO28 追溯证据，需要清理时按前缀/名一键删。
