# 告警 Agent 后续优化技术方案 v1

统一排期与收口入口：[生产级收口与后续优化总方案](告警-Agent生产级收口与后续优化总方案-v1.md)。本文件继续负责OP卡的实现细节；新增部署运维OR卡和RR测试由总方案管理，不重复计数。

2026-09-13。状态：待实施技术规格。配套[60条测试用例](告警-Agent后续优化测试用例-v1.md)。本方案承接[闭环修复与增强实施方案](告警-Agent闭环修复与增强-完整实施技术方案-v1.md)，不把前置修复默认视为已经完成，也不重复重建其围栏、记忆、Skill绑定和摘要生命周期。

## 一、目标与进入条件

第一阶段交付四项：固定回归集、调查动作价值分析、人工反馈转回归案例、面向值班任务的前端体验。结果质量评估贯穿这四项。第二阶段按数据触发：调查深度/模型路由、受控并行、RAG改进、Skill退化管理和留存优化。

采集与离线分析可以先做；运行策略改变必须等闭环修复的有效证据投影、提交围栏、持久版本绑定通过对应验收。本文不要求处理百万告警，不增加RabbitMQ/Redis/向量数据库作为默认前置。

## 二、本次源码核对与复用边界

Java路径根：`control-app/src/main/java/com/objwww/pr/control/`。

| 现有落点 | 已看到的能力 | 本次增量 |
|---|---|---|
| eval/domain/model/CaseVersion、DatasetVersion | 不可变案例/数据集版本，来源引用、scenarioFamilyId | 反馈来源关联、案例准入与按故障家族拆分 |
| eval/application/EvalReviewService | 评审领取CAS、独立结论、rubric版本、盲评投影 | 复用审核机制，补生产报告反馈进入审核的适配 |
| eval/application/EvalCompareService | 配对对比、缺成本语义、逐例差值 | 补质量维度、策略分层、行动价值的描述性比较 |
| ops/application/AgentOpsSummaryService | Agent运行汇总服务 | 增加质量/行动价值聚合，避免另建监控平台 |
| alert/application/CommandService | 运行中命令；非活跃Run一般拒绝 | 新增终态报告评价入口，不能直接放宽所有命令 |
| RunDetailView.vue | 已有摘要/页签、结论、待补证、进展；反馈按钮在runActive条件内 | 保留已有结构，优化优先级、终态反馈、证据跳转和窄屏 |

上述仅表示本次读到的实现，不等于线上部署或页面效果验收。已有反馈命令仍可保留；报告评价属于不同对象和生命周期。

## 三、P1-01 固定回归集与质量口径

### 3.1 案例如何进入系统

来源是已审查的真实调查、隔离故障演练和已修复缺陷。通过现有CaseVersion/DatasetVersion建立不可变版本，不另存可随意编辑的“标准答案列表”。

拟增`RegressionCaseAdmissionService`（优先复用已有候选准入逻辑），输入sourceRunId、reportId、evidenceSnapshotDigest、reviewVerdictId与caseKey。服务端验证来源完整、复核可用、权限和脱敏，再生成候选案例；正式入集由独立审核动作完成。来源缺失不能自动造补充证据。

案例内容分为调查可见输入、评分侧答案/判定依据、环境清理契约。保留症状、查询范围、工具/语料版本、可接受根因或合理未决条件。固定UUID不能直接当症状提示给模型；禁止把故障注入名称中明示根因的部分泄漏到调查输入。

同故障episode的重复通知、同一故障的改写、时间相邻的高度相似样本按scenarioFamilyId/来源关系分组，再拆开发集与holdout。不能仅对case行随机拆分。纠正答案生成新CaseVersion，不覆盖已发布版本。

首批建议收集30～50个经审核案例以便人工逐例检查：日志直接定位、指标异常、变更关联、跨服务、证据矛盾、来源不可用/合理未决。数量是首期工作量建议，不是统计充分性保证；真实案例不足如实显示覆盖空缺。

### 3.2 质量指标的分母必须明确

| 指标 | 计算与边界 |
|---|---|
| 根因正确率 | 有可靠GT且可判定案例中，结论正确的比例；同时报告样本总数、排除数及原因 |
| 错误确诊率 | 在可判定的确诊输出中错误的比例，同时列错误确诊绝对数；不能通过大量未决隐藏错误 |
| 合理未决率 | 预先标为证据不足的案例中正确保持未决的比例；与应能定位却未决分开 |
| 引用准确性 | 被抽样/完整审核的结论引用中，来源可达、作用域正确及语义支持分别计分 |
| 耗时 | 入口到报告、排队、模型、工具分别计；未完成作为删失/失败样本单列，不按0算 |
| 费用 | 包含主子Agent、重试、压缩和生成用途；未知定价单列，跨币种不直接相加 |

扩SingleCaseScorer及相关结果投影时采用新增版本化评分结果或可兼容字段；保留原机器评分，人工结论不覆盖它。rubric明确“相关不等于因果”“没有足够证据时不能确诊”。LLM评委只提供辅助结果，分歧/高风险结论人工复核，不拿模型自述置信度当校准概率。

### 3.3 跑批与发布门

复用EvalCompareService按冻结case内容配对。模型随机性用多个trial记录，每个trial保存配置；不能挑最好的一次。评测前登记主指标、非劣容忍范围、成本目标、样本排除规则与停止条件；看到结果后改变阈值需新实验。

权限、GT泄漏、预算越界、恢复一致性为硬门，发现违反即阻断。业务质量小样本报告逐例差异和不确定性，证据不足为INCONCLUSIVE，不输出虚假的稳定提升。holdout查询权限复用既有隔离机制，并实测工具/RAG/Skill路径无法旁路。

## 四、P1-02 动作价值分析：回答为什么查了这么多次

### 4.1 先观察，再影响调度

新增版本化派生分析，不改变原始调用账本。按逻辑action聚合物理attempt，关联输入快照、查询规范化键、返回证据、主子关系、缺口变化和最终报告引用。

建议新增`rca_action_assessment`：run_id、task_id、logical_action_key、assessor_version、evidence_snapshot_digest、new_observation_count、gap_resolution_refs、report_citation_count、classification、confidence_kind、computed_at；唯一键包含分析版本和源快照。分析错误可以生成新版本，不改调用是否成功。

分类先保持可解释：NEW_OBSERVATION、CONFIRMS_OR_REFUTES、NO_DATA、DUPLICATE_SAME_SNAPSHOT、SOURCE_FAILED、UNDETERMINED。NO_DATA可能排除方向，不自动判无价值；同样内容在新时间窗出现可能有新信息。report未引用也不等于动作无用。

动作“价值”是描述性归因，不是因果效益。若要声称某个子Agent提升根因质量，使用开/关该能力的配对实验，不把时间先后关系当收益证明。

### 4.2 处理流程

Run完成后从原账本读取动作/证据/最终记忆，执行可重入分析任务；复用现有持久作业机制，没有必要另建消息队列。迟到对账或报告变化触发新源快照版本重算。没有被分析的Run显示NOT_ASSESSED，不能当0价值。

将新观察定义绑定证据类型：同源同窗规范化内容去重，保留频次和时间变化；不能只按evidence UUID是否不同判断新信息。分析暂不采用LLM，先用确定规则并显示局限；若语义分类误差确实高再引入独立计费的审核模型。

汇总到AgentOpsSummaryService：按告警类别/策略版本显示重复查询率、各失败源占比、每新增有效观察费用、委派后仍未决比例。run/action ID放详情和trace，不放高基数Prometheus标签。

## 五、P1-03 报告反馈→审核→回归案例

### 5.1 不能直接复用活跃Run命令约束

运行中“补充线索”和终态后“评价报告”分开。新增或扩展报告维度的应用服务`ReportFeedbackService`，路由拟为`POST /api/reports/{reportId}/feedback`与对应查询；这些是待实现API。保留CommandService对Cancel/Hint等命令的活跃态约束，禁止为了反馈把所有终态命令放开。

反馈必含reportId、reportDigest、verdict（ACCEPTED/PARTIAL/INCORRECT/INSUFFICIENT）、reason、可选证据引用、idempotencyKey。身份来自认证主体。允许已终态Run的授权读者反馈，但不能修改原报告、触发生产处置或改变Run成功状态。

若现有反馈数据结构足以表达，扩展它；否则新增append-only `report_feedback`：id、report_id/digest、author_id、verdict、reason、evidence_refs、supersedes_id、created_at及请求幂等唯一键。更正追加新行，同一个前序版本的冲突更正用唯一约束或revision CAS控制；不同操作者意见可以并存。

### 5.2 审核不是自动采信

反馈创建→PENDING_REVIEW；复用EvalReviewService/assignment与verdict机制建立审核适配，不把业务反馈直接塞成holdout答案。审核结果为ACCEPTED_FOR_CANDIDATE、REJECTED或NEEDS_EVIDENCE；分歧需要裁决，来源不足保留待补证。

接受反馈仅生成回归候选，仍需案例准入；反馈不能自动发布Prompt/Skill，也不能被调查Agent直接检索为事实。机器分数、用户意见、审核结论分别保存。

拟新增`POST /api/report-feedback/{id}/regression-candidates`或等效审核命令，返回202/候选ID；服务端检查审核资格与幂等。转换依据sourceDigest固定，源报告后续修订不静默改候选。

### 5.3 前端操作

RunDetailView将“报告评价”绑定已发布report且权限可用，不再仅绑定runActive。操作前显示评价对象和版本；提交失败保留本地草稿但不得把草稿当服务器成功。刷新后读取真实反馈记录。事实纠正和评测审核入口使用链接跳转，不在详情默认展开整套审核表单。

## 六、P1-04 前端按任务组织，避免再次堆满页面

已有摘要页签保留，不推倒重写。第一屏依次展示业务影响/状态、当前结论及证据充分性、等待或下一步、关键证据入口；费用/版本/完整轨迹放次级信息。UNRESOLVED终态显示“调查已结束，证据不足”，不能继续显示无限旋转的“分析中”。

| 页面 | 增量布局和动作 |
|---|---|
| IncidentDetailView | 告警生命周期与认领处置在前，调查列表后置；多个Run可比较，默认当前有效报告 |
| RunDetailView | 摘要、证据、过程、版本/费用分区；反馈靠近报告；主子层级可展开，默认收起已完成细节 |
| EvalDatasetsView | 来源、覆盖类别、版本、隔离分区与未覆盖提示；不让普通调查角色看到GT |
| EvalRunDetailView/EvalCompareView | 总体结果→退化案例→单例证据，显式分母、UNKNOWN、未配对和trial；比较筛选同步URL |
| EvalReviewView | 症状/输出/证据与评审表分区；小屏上下排列，提交与草稿状态区分 |
| EvalAssetsView/VersionsView | 候选→资格→组合采用→实际消费分开显示；退化提示关联案例，不直接自动发布 |

建议设计约束：统一间距与字号token；宽屏主内容可读宽度受限，长表格在自身容器横向滚动；窄屏切单列而非压缩文字。1366×768、1920×1080、390×844与200%缩放验收。长UUID缩写但能复制完整值，关键告警名称允许换行；不能仅靠颜色表达严重程度或失败。

分页/筛选由后端或有界读面支持；大证据/trace按需加载，离开页面取消请求。SSE重连按事件游标去重与补拉，不能通过重复刷新制造重复时间线。最终以实际浏览器任务走查、截图和键盘操作验收，本文未制作或验收视觉稿。

## 七、P2能力：触发条件与具体改法

### 7.1 调查深度与模型分级

进入条件：质量基线、动作统计及前置正确性修复通过，能定位“简单告警过度调查”或“复杂告警预算不足”。

扩冻结策略Profile支持SIMPLE/STANDARD/DEEP的上限与可用角色，但不是按severity直接选模型；严重程度决定响应优先级，复杂度还要看证据冲突和缺口。主Agent提出升级理由，Host按剩余预算、工具/模型能力和策略准入。

模型升级复用现有路由，不启动新Run重置预算；每次实际调用记录模型、路由与升级理由。低成本模型只在相应类别非劣通过后使用。上下文窗口、输出协议、定价未知等兼容问题按现有网关契约处理。

停止条件基于可验证信息：证据充分、关键源不可用、重复动作无新信息、deadline/预算到达。所谓“连续无收益N次”先shadow观察，不直接上线，因为NO_DATA或反证有价值。停止输出已知事实/缺口/建议下一步，不能强制生成根因。

### 7.2 受控并行

只有延迟分解表明独立外部等待占主导才启用。先最大2个独立查询或子任务，数值是初始配置而非性能承诺。显式依赖存在时必须顺序执行；共享模型RPM/TPM、工具池、Run预算和取消传播由Host管理。

结果合入顺序按稳定身份，不能让先返回专家自动成为根因。一个子任务失败不丢另一个已提交结果；超时与UNKNOWN继续沿前置恢复契约。取消/回收保留独立执行通路，不被满池饿死。固定输入对照串行/并行质量、尾延迟、资源峰值，改善不明显就不启用。

### 7.3 RAG检索

先给catalog检索补服务/时间/版本过滤与离线相关文档标注；记录候选、实际fetch与引用。用miss案例证明问题是召回还是阅读/推理，不能一律上向量库。

关键词检索不足再评估PG全文或混合检索；只有语义漏召回有明确收益才加embedding。语料/分词/embedding/排序器版本一起固定，候选评分不代表根因置信度。召回过滤发生在内容返回前；跨权限文档不能先返回再靠Prompt忽略。

### 7.4 Skill退化与留存

按持久绑定digest统计适用样本、人工修正、错误确诊与费用；考虑使用选择偏差，不能仅按线上平均值声称因果提升。依赖schema改变触发重新资格检查；退化只生成审核建议，紧急安全撤权仍可即时生效。

证据按原文/派生摘要/必要审计分级留存；评测案例与在用Skill来源引用设保留依赖。可删内容删除后写tombstone并使重放标不完整；删除权限撤销来源时同步检查派生摘要和Skill是否含原内容。归档不承诺无限可重放；先实测存储增长再调整保留周期。

## 八、数据迁移、接口与执行卡

新表前先确认现有结构能否复用；新增迁移号在实施时分配，不修改已应用迁移。派生指标允许从原账本重算，业务反馈不可静默重写。新增API统一认证、作用域、幂等与错误码，页面不直接调用数据库或模型供应商。

| 卡 | 文件/能力落点 | 依赖 | 验收 |
|---|---|---|---|
| OP-01 案例准入 | CaseVersion/DatasetVersion、来源审核适配、数据集页 | 来源可封存 | FO01～10 |
| OP-02 质量比较 | SingleCaseScorer、EvalCompareService、评审rubric | OP-01 | FO06～10、FO51～54 |
| OP-03 动作分析 | 派生assessment服务/表、AgentOpsSummaryService | 前置有效证据与身份 | FO11～20 |
| OP-04 报告反馈 | ReportFeedbackService/接口、审核适配、RunDetail反馈 | 身份与报告读面 | FO21～30 |
| OP-05 页面体验 | 本文第六节已有Vue页和读API | 按卡同步 | FO31～40 |
| OP-06 调查/模型策略 | Runner/Host准入、Profile、ModelRouter | OP-01/02/03 | FO41～44 |
| OP-07 受控并行 | Supervisor/Worker/共享配额 | 围栏/取消已验收 | FO45～46 |
| OP-08 知识与Skill运营 | RAG过滤、版本评测、Skill汇总、留存服务 | OP-01/04 | FO47～50、FO58 |
| OP-09 发布闭环 | 组合资格、回滚、评测证据和运维任务 | 对应能力完成 | FO55～60 |

顺序：OP-01/02→03/04→05完整走查；OP-06～08各自满足触发条件后才进入。OP-09随每批执行，不留到最后。业务质量门必须先定义再运行，不能为通过测试修改答案或降低阈值。

## 九、验证与发布

新测试目录与既有Test/IT命名规则保持一致，复用EvalReviewServiceTest、EvalCompareServiceTest、AgentOpsSummaryServiceTest、CommandServiceTest及PostgresEvalReviewIT/PostgresEvalCompareIT。新增报告反馈服务测试与PG IT仅覆盖新增语义。浏览器复用已有项目设施；若没有则引入最小浏览器E2E设施，先确认项目依赖，不声称已经安装。

新增分析先只读shadow，反馈与审核先受限角色可用，策略改变按独立release灰度。发生问题：关闭派生面不影响调查；撤回策略用兼容release并保留旧Run身份；关闭反馈创建不删除历史。禁止回滚数据库时删除反馈/评审或旧测试证据。

报告形式统一：caseId、模式、构建/工作区指纹、release、数据集/rubric/策略版本、执行环境、结果、断言证据、清理结果。NOT_RUN/BLOCKED/FAIL/PASS分别列出，P2未启用列DEFERRED不计通过。

## 十、调研依据

[Anthropic Agent评测实践](https://www.anthropic.com/engineering/demystifying-evals-for-ai-agents)区分能力评测与回归，并强调检查执行轨迹和结果；本项目据此把已修复故障加入固定集，并分离“流程成功”与“质量正确”。

[Langfuse评测概览](https://langfuse.com/docs/evaluation/overview)将运行反馈、数据集与实验比较串联；[评分概念](https://langfuse.com/docs/evaluation/core-concepts)区分人工、程序和模型评价。本方案复用本地PG/评审服务实现相同闭环，不要求部署Langfuse，也不把其产品能力当成本项目已具备。

上述为参考原则。具体字段、API、门槛与实施顺序是项目建议；本次仅更新技术文档，没有修改业务代码、跑新增测试或访问195。
