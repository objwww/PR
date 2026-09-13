# 告警 Agent 闭环修复与增强：完整实施技术方案 v1

统一排期与收口入口：[生产级收口与后续优化总方案](告警-Agent生产级收口与后续优化总方案-v1.md)。本文件继续负责CL卡的实现细节；实施前按总方案B0重新核对当前代码与部署证据，不沿用历史缺陷状态。

日期：2026-09-13。性质：执行者技术规格，尚未实施。依据[代码审查](告警-Agent闭环与代码审查-20260913.md)及当前源码复核；本文件把A13-01～06合并成一条可交付路线。已存在的R7、EN、MC测试继续复用，不重开整个项目，不承诺文档即生产验收。

## 0. 交付目标与事实基线

目标是让一条告警能够持续地“读到有效观察→选择直接工具或委派→保存可靠记忆→在失租/重启后继续→形成有引用结论或诚实未决→发布并查询通知结果”。增强线另验“来源封存→Skill候选→评测→授权发布→新Run消费”和“摘要生成→校验→被下一次输入实际消费”。自动修改生产环境不属于本次范围。

当前已有ContextAssembler、PrimaryCheckpoint、WorkingMemoryPort、ContextSummaryPort、SkillCuratorService、SkillCandidateService、SkillSelectionService、RunConfigSwitchService、模型输入捕获和版本查询页。V91建记忆表，V92建摘要表，V97建Skill候选表，V98涉及上下文策略资产。新迁移只追加；**实施时重新确认最大迁移号，本文不预占V99，也不改已应用迁移**。

复核工作区基线见审查文档；BoundedLlmRoleRunner存在他人未提交修改，实施前记录相关文件指纹并合并现状。没有读用户禁止的三份文档。此前82例定向测试通过是基线证据，不代表本文新增契约通过。

### 0.1 已有部分与新增边界

| 已有实现 | 本次如何改 |
|---|---|
| 工具执行、证据存储、输入捕获 | 修正有效内容投影和冻结输入，复用原工具网关 |
| Run任务租约、预算、恢复 | 补检查点及收尾的原子提交围栏，不换调度框架 |
| 记忆快照与检查点memory_id | 扩版本与引用读取，增加累计更新语义 |
| 摘要档案及生成服务 | 补尝试记录、原子准入、消费指针与失败恢复 |
| release_manifest.skills/context_rules | 用已有字段钉版，不建第二个发布指针 |
| Skill候选状态与选择服务 | 选择落PG，增加来源封存、生成入口及资格关联 |
| 热更新命令、版本页 | 扩安全点检查与真实消费版本展示 |

## 1. 所有改动共用的约束

1. 模型和工具请求不能在持有数据库行锁的事务中执行。所有链路采用“短事务准入→外部执行→短事务提交”。
2. 统一锁序：需要多个锁时按 **Run→task→checkpoint→该Run派生记录**；涉及多个task按UUID排序。必须审查现有热更新、回收和取消写点，让相同记录遵守相同次序；不能只在新方法写注释。
3. leaseEpoch表示执行所有权，configEpoch表示运行配置，checkpointRevision表示提交修订，decisionSeq表示业务决策序号。四者不可互代。
4. 原始观察、模型提出的判断、人工意见、历史Skill建议分别存放；引用合法不等于结论正确。
5. Run终止后禁止获得新执行资格；已发送动作仍审计。UNKNOWN不能当失败退款后换新ID重做。
6. 内容哈希用现有canonical实现；数组排序是否规范化由业务语义决定，不能把有顺序的步骤排序。displayName不参与身份。
7. 旧Run不通过“读最新ACTIVE”隐式升级。新角色或不兼容schema需新Run或专门迁移，不能借热更新扩权。

## 2. 先补提交围栏：后续功能都建立在这层之上

### 2.1 数据调整

扩`rca_primary_checkpoint`：`revision bigint not null default 0`、`schema_version`、`current_context_digest`、`current_summary_id`（后两者可空）。现有memory_id/memory_digest复用。revision为真正的并发修订，不再用decisionSeq隐含代替。

检查点的task/run关联必须校验，必要时添加外键/检查约束；上线前扫描历史孤儿记录，有问题先报告，不强行加约束导致迁移失败。owner/leaseEpoch以task表为事实源，checkpoint可留审计副本但不能形成两套授权事实。

新增字段初始0只表示切换时的首个并发版本，不表示旧历史只有一次写入。新协议仅在兼容读代码部署、旧写者退出后启用。禁止新旧writer混跑，因为旧upsert不检查revision。

### 2.2 替换接口及返回值

修改`alert/domain/repository/PrimaryCheckpointRepository.java`，运行路径弃用无条件upsert。建议接口形状（拟新增，字段类型沿当前项目统一）：

```java
record CommitFence(UUID runId, UUID taskId, String owner,
                   long leaseEpoch, long configEpoch, long expectedRevision) {}
enum CommitStatus { APPLIED, REPLAYED, STALE_OWNER, STALE_REVISION,
                    CONFIG_CHANGED, RUN_TERMINAL }
record CheckpointCommitResult(CommitStatus status, PrimaryCheckpoint checkpoint) {}
```

由应用服务`PrimaryCheckpointCommitService`组织事务；repository只负责锁读和条件SQL。初始化用`insertIfAbsent`，读取用`findByTask`；运行提交统一`commit(fence, actionKey, mutation)`。不要让调用方自行传任意完整对象覆盖不相关字段，mutation限定STEP_COMPLETED、DELEGATION_COMMITTED、FINAL_PROPOSED、ERROR_RECORDED等合法操作。

`actionKey`为已经取得执行资格的逻辑动作身份。重复提交必须先检查该动作是否已落结果：相同动作、相同结果返回REPLAYED；相同动作不同结果记一致性错误。仅revision相同不是幂等证明。

### 2.3 原子提交实现

`PostgresPrimaryCheckpointRepository`的无条件ON CONFLICT不得出现在运行更新路径。事务流程：

1. 锁Run并验证仍活跃、generation及configEpoch符合。
2. 锁task，验证owner、leaseEpoch、租约未过期、任务尚可提交；使用数据库时间。显式处理无租约的初始化，不能拿deadline冒充租约。
3. 锁checkpoint，检查revision和动作重复状态。
4. 校验mutation对应合法状态；需要时插入不可变记忆/上下文/已验证摘要。
5. `UPDATE checkpoint ... revision=revision+1 WHERE task_id=? AND revision=?`，影响行数必须1。
6. 同事务记录本次动作结果/状态事件；FINAL收尾时需要原子推进的task状态一并处理。
7. 提交事务后返回新检查点。任何失败回滚，调用者拿到STALE结果立即退出该次驱动。

数据库已锁住任务归属时，不需要给所有表加巨大锁；但不得把锁读移到事务外。最终更新仍保留revision谓词，防止以后接口误用。

### 2.4 修改所有调用者

`BoundedLlmRoleRunner`中advanceStep、driveDelegate、driveFinal、deterministicFinal、modelFailureFinal、lastError写入全部改走新提交口。`DeterministicSupervisor`的父等待/唤醒、子任务创建与检查点更新使用同一事务约束；重复唤醒只允许一个相位迁移。

`NativeInvestigationExecutor.drivePrimary`不得在收到STALE后继续transitionState；返回一个明确LOST_OWNERSHIP结果供worker释放本地执行资源。最终DONE/DEAD条件更新也携带task owner/epoch，而非只看state=RUNNING。

`RcaWorker`的claim/reclaim/cancel与新锁序核对；租约续期失败必须让后续动作停止。网络调用期间不延长数据库事务。已返回的旧模型结果允许写调用审计，但不能写当前检查点、有效结论或启动下一工具。

### 2.5 必过测试

MC08扩为四组真实PG屏障：step/final/lastError/delegate。A过准入后停住→B接管并写新revision→A返回；A影响行数0，B状态不变。另测同一动作重复返回REPLAYED、取消与FINAL竞争、不同task锁序无死锁。对无法命中屏障的测试记INVALID_TEST，不以sleep推测时序。

## 3. 修真实证据入模与输入冻结

### 3.1 修改ContextAssembler

将`evidenceOf`、`validRefsOf`等多次独立读库改为一次获取本步有资格读取的证据集合，形成显式成员快照。该集合同时用于正文投影、validRefs、included/omitted及digest；后续新增证据进入下一步。

先依Run/generation/冻结时间窗和任务权限裁定证据范围，再按明确顺序选择。主Agent可按设计读取Run范围，受限子Agent按授权过滤；“同Run”不是自动跨角色授权。快照确定性排序使用稳定字段，不依数据库无ORDER BY结果。

装配器逐步改成无写入计算：读取已提交记忆并计算候选输入，不在`assemble()`里抢先append新的工作记忆。当前commitMemory的副作用迁至上节提交服务，避免模型尚未执行就写入与检查点不匹配的记忆。

### 3.2 有效信息投影

先在现有ContextAssembler内按注册evidenceType组织私有投影方法；确实增长后再拆文件，不先建插件框架。返回统一结构：

```json
{
  "ref":"原始证据ID", "type":"日志证据类型", "source_digest":"...",
  "window":{"start":"...","end":"..."},
  "observations":[{"at":"...","service":"checkout","message":"具体错误"}],
  "total_count":31, "shown_count":3, "truncated":true,
  "omission_reason":"ITEM_LIMIT"
}
```

| 数据源 | 具体读取与保留规则 |
|---|---|
| logs.query | 从data.result读取ts/service/line；同错误签名聚合并保留频次、最早/最晚时间和代表行；不同严重错误不能因重复普通日志挤掉 |
| 指标查询 | 读取result中的metric标签和value/values；保留单位（源确有提供时）、窗口和实际数值；不得只展示status |
| change.query/diff | 提取变更时间、对象、动作及可用前后差异；窗前基线单列，不冒充窗内变更 |
| runbook.reference | 保留doc/corpus digest、正文有界段落、适用范围；标REFERENCE，不作为当前现场独立证据 |
| 未识别对象/数组/标量 | 有界JSON投影并明确未知形状和截断；禁止非对象时输出空字符串却无异常标记 |

保留原始工件，不覆盖或重新生成“原始证据”。字段脱敏沿现有安全过滤，截断前也要过滤秘密；错误消息中命令仍作为不可信数据。

### 3.3 受控回读

若现有工具不能按证据ref回读，拟新增`evidence.read`为注册只读工具，经ToolGateway预算、权限和审计。参数仅接受ref、有限的行/条目偏移与limit，不接受路径/任意URL。服务端查run/task授权及来源撤权，再返回有界窗口。范围失败明示，不让模型读库凭据或生成SQL。

结果包含实际offset/nextOffset与truncated，不能保证任意全文装入模型。无此工具的第一批也能修投影；页面与能力目录必须如实标回读尚未提供。

### 3.4 输入身份和预算

区分`semanticContextDigest`（循环检测，可排除瞬时错误提示）与`renderedPromptDigest`（实际完整请求，必须含lastError、Skill内容、schema等）。现有输入捕获保存实际发送内容及digest；不再把两个哈希都叫inputSnapshot而混用。日志只记引用和脱敏信息。

变量输入预算用`V=C-R-H-M`，其中H是固定指令/schema，变量材料装入V。若检查对象是完整prompt，则检查`tokens(fullPrompt)+R+M<=C`，不能重复扣H。模型能力取冻结配置；没有tokenizer时标估算并使用实测保守余量，不能把字符数/2称为严格上界。provider仍可能拒绝，拒绝走有界错误分支。

MC01以真实嵌套日志错误码穿透到下一请求为断言；MC03在组装中新增证据检验快照不漂移；MC09覆盖重复频次和重要异常；MC10/11覆盖schema超窗、中文和代码估算；完整请求在同一测试中通过实际网关捕获，不只测试一个投影函数。

## 4. 持久化Skill选择，统一版本生效规则

### 4.1 新增最小绑定表

现有`rca_skill_candidate`管候选生命周期，不能兼任每Run选择。新增`rca_run_skill_binding`：

| 字段 | 语义 |
|---|---|
| run_id、role_id、config_epoch | 联合唯一选择范围；首期每角色最多一个Skill，role_id来自冻结绑定 |
| selection_status | SELECTED或NONE，NONE必须持久化 |
| asset_digest | SELECTED必填、NONE必空；引用不可变资产 |
| release_digest、selector_version | 当次选择的组合与算法版本 |
| created_at、source_command_id | 准入/热切来源与审计 |

Run已固定service/alertname等选择材料，不使用“incident当前最新字段”重选。表添加唯一键与状态/空值CHECK；权限最小化，不授予模型连接身份。

### 4.2 修改SkillSelectionService

接口由`select(runId, alertname, service)`扩为从可信运行绑定接收role/configEpoch/release身份，材料由服务端加载。具体过程：

1. 先查持久绑定，有则按digest加载或返回NONE。
2. 没有则读取该release_manifest.skills允许集，再过滤符合运行权限/选择条件的候选；不使用最新全局ACTIVE集合替代冻结允许集。
3. 按版本化选择器确定最多一个结果，冲突记录候选与选择理由。
4. 在短事务中验证Run/configEpoch仍匹配并insert-if-absent；并发冲突读取胜者，所有调用返回胜者。
5. 缓存仅以完整绑定键加速，不作为事实源。最简首期可直接去掉512项缓存，先测数据库开销再决定是否恢复。

DEPRECATED可禁止新选择但不自动破坏合法老绑定；RETIRED紧急撤销阻止后续消费。权限每次动作仍检查，缓存不能绕过撤权。绑定缺失资产时显式CAPABILITY_UNAVAILABLE，不静默换另一个Skill。

### 4.3 与热更新合并

`RunConfigSwitchService.applyAtSafePoint`应用新epoch时，验证新组合及角色兼容，并在同事务生成该epoch的选择记录或明确NONE。父等子、在飞模型、在飞压缩时不能切换。旧调用审计保留旧epoch，下一合法动作读取新epoch，不重置预算和deadline。

历史已运行Run若无持久Skill记录，不能拿今天ACTIVE集合回填成“当时选择”。优先从实际输入捕获的digest恢复并校验；无法证明的Run完成后退出旧模式，或人工关联新Run，标历史版本不完整。新协议启用前让旧写者排空。

验收覆盖重启、超过512个Run、双首次选择、NONE钉版、撤权、权限交集、热切与晚到回执。页面显示“实际消费digest”，而不只显示当前ACTIVE名称。

## 5. 工作记忆由覆盖重建改为累计更新

### 5.1 复用V91表，升级记忆结构

增加schema_version及parent_memory_id（或等效不可变父引用）。`checkpoint_revision`从新协议启用后严格对应真正revision；旧行保留旧schema，不能把历史decisionSeq冒充新revision。新旧唯一键冲突的迁移方案须显式区分schema/协议批次，或在新Run启用新协议；首期推荐新Run切换以减少历史重写。

新结构使用有身份的条目：hypothesis{id, statement, status, supportRefs, counterRefs, updatedByAction}；openGaps{id, question, status, resolutionRefs}；completedActions引用原账本；delegationRejections独立保存控制拒绝。没有新事实证据时不新增“事实”。

### 5.2 最小更新算法

从checkpoint.memory_id读取精确上一版，不以latestByTask替代指针。将本轮新观察、已接受子回执和经过校验的模型提案形成delta；按稳定ID合并去重。反证跨轮保留，缺口只有有依据的关闭操作才变RESOLVED。预算拒绝只影响delegationRejections。

模型当前决策协议不支持memory_delta时，第一批先只累计宿主可确定的反证、缺口和动作。需要模型维护假设时升级决策schema，并让Prompt/Profile/测试一起钉版；不能无声接受任意新增JSON字段。格式和refs校验只能证明结构，语义正确性仍需B模式评测。

候选记忆随成功动作在第2节短事务中append，并原子更新checkpoint.memory_id。WorkingMemoryPort增加findById及精确读取；append幂等只解决重复内容，不等于owner围栏。失败提交不得污染当前记忆。

超过容量时保留未决和关键反证，已结束细节可移至可回读历史引用；每个省略都有计数。测试MC05/07/16/22贯通两个以上round，不能只测试当前轮数组有值。

## 6. 完成上下文压缩的生成、提交、消费和恢复

### 6.1 模式与进入条件

固定三种模式：OFF、SHADOW_GENERATE、CONSUME_VALIDATED。现有enabled=true行为相当于SHADOW_GENERATE，迁移时保留含义，不能升级后自动变消费。模式进入context_rules并随release钉版。消费默认关闭，直到三组对照通过。

V92摘要档案继续append-only；不要把它的行数当调用次数。新增最小`rca_compaction_attempt`控制记录，记录attemptId、run/task、sourceContextDigest、policyDigest、owner/leaseEpoch/configEpoch/expectedRevision、state、logicalActionKey、createdAt、errorCode、summaryId。唯一键包括task/source/policy/configEpoch；同源有界重试使用原逻辑动作及独立物理序号，不通过成功行数取号。

尝试配额在同Run短事务中原子预留，状态无论成功失败均可计attempt上限；明确配置为“逻辑压缩尝试上限”，物理重试另外受总调用策略限制。复用RunBudgetGate资金/token预留，不建立第二个费用账本。

这里的attempt配额与token预算是两种约束。attempt登记只占压缩次数资格；token预留由现有ActionGuard→RunBudgetGate按同一reservationKey执行一次，不在服务外层再包一次预算门。若需要把次数登记与预算预留合成原子事务，扩展现有门的准入事务接口；绝不能“先预扣一次，再进guard预扣一次”。

### 6.2 状态与提交语义

| 状态 | 进入与离开条件 |
|---|---|
| RESERVED | Run配额和逻辑身份已持久化，尚未发送 |
| IN_FLIGHT | 获得发送资格，远端可能已经执行 |
| COMMITTED | 候选验证通过，摘要档案和当前消费指针原子提交 |
| REJECTED | 格式、引用、无收益等拒绝；费用照实记录 |
| FAILED | 已知执行失败；重试仍按同一总策略有界 |
| UNKNOWN | 发送后结果未知；不释放成可再消费预算，恢复先对账 |
| SUPERSEDED | 原快照/权限/epoch/revision已失效，候选仅审计、不消费 |

IN_FLIGHT状态不精确证明网络已经发出，发送崩溃窗口按UNKNOWN处理，不承诺exactly-once远端调用。旧owner可以完成物理调用审计，但不能变更消费指针。

### 6.3 源快照与必需引用

sourceContextDigest必须对应实际本次assembly成员/记忆版本，不取可能代表上一步的checkpoint.inputSnapshotDigest。成员列表及顺序冻结保存；现有event_seq_from/to不得继续用decisionSeq伪装成证据事件序号。首期以明确成员列表为覆盖真相；若保留seq字段，定义为已持久输入事件序号并保证其生产者存在。

requiredRefs由宿主从反证、尚未解决的问题、正在验证的核心假设及必要动作结果产生；模型不可自行删空。冻结范围之外的新子回执留作增量。既保留上一摘要的来源链，也避免无限“摘要再摘要”漂移：能从原始成员重构的源优先重新生成。

### 6.4 实际调用过程

1. 组装有效上下文，计算软阈值和当前模式；未触发直接继续。
2. 短事务预留attempt次数资格，冻结来源/身份；提交后调用RcaActionGuard，由现有预算门取得唯一token预留和发送资格。预算拒绝时attempt记录拒绝原因，是否消耗逻辑尝试按冻结策略定义，不能静默删除记录。
3. 收到候选，检查schema、正文非空、requiredRefs完整、引用可读以及真实目标输入是否有节省。不得剥掉未知refs后假装候选原本合法；记录拒绝或显式修正政策。
4. 第2节围栏事务内复验，再写V92摘要和attempt终态；CONSUME_VALIDATED更新checkpoint.current_summary_id。SHADOW只留档，不换输入。
5. 下一次ContextAssembler读取current_summary_id，构造“固定Host规则＋经过验证的摘要＋未覆盖新证据＋必要原文＋协议”，不再发送全量旧正文。
6. 对最终完整prompt重新计预算、捕获实际输入。若仍超限，确定性选材或诚实未决，不无限再次压缩。

摘要不能移除Host权限、预算和停止条件的事实源；这些由当前冻结运行身份重建。候选超时/失租/无收益均保留旧指针。旧摘要引用来源被撤权时，按来源链阻止派生内容继续曝光，必要时废弃摘要并重建。

### 6.5 测试与放量

MC13～20覆盖缺反证、语义扭曲、空摘要、超时、并发、UNKNOWN、取消/热切竞争和清理。MC34固定模型/Prompt/工具/告警/预算，对照OFF、确定性、消费三组，计入所有失败和摘要调用成本。必须证明最终发送prompt变短，不以summary_text长度替代实际输入节省。安全断言零容忍；质量非劣和费用目标在跑批前登记，样本不足记INCONCLUSIVE，不自动开启。

## 7. 接通Skill经验沉淀并保留人工发布

### 7.1 先完善来源与生成入口

复用SkillCuratorService/SkillCandidateService和V97。当前确定性模板保留为TEMPLATE模式，新增LLM_CURATE模式才代表模型归纳；两者页面明确区分。默认由已认证操作者手动发起，后续调查完成事件只生成待审核建议，不自动发布。

拟新增入口（待实现，不是现有API）：`POST /api/skill-curations`，请求runId、sourceSnapshotDigest、expectedRevision、mode、idempotencyKey；operator从认证主体取得，不接收自报humanReviewed=true作为证明。返回202及jobId；GET对应job查进度。已有候选查询/发布服务优先复用，补Controller而非再建平行业务层。

外部LLM调用异步执行，不能占用HTTP线程长等待。优先复用已有持久命令/作业设施；若其状态语义不能承担生成任务，新增最小curation_job表，不创建内存线程即宣称持久任务。状态QUEUED→RUNNING→SUCCEEDED/FAILED/CANCELLED/UNKNOWN，租约/幂等复用现有模式；候选生命周期仍归V97，不混成一个巨型状态机。

### 7.2 封存不能只看SUCCEEDED

创建不可变来源清单：run/报告/实际动作轨迹、证据成员digest、模型与release版本、复核记录引用、成功与失败步骤。sourceDigest由这些内容计算。复核身份、结论和时间通过服务端持久记录引用；来源不完整明确拒绝或要求补齐，不在正文写“已封存”就当作完成。

来源只取调查时可见材料，排除评测GT、秘密和未授权跨服务数据；保留失败尝试和反例。调查结果UNRESOLVED不能生成“已验证根因修复Skill”，但可另行生成明确未验证的排查建议；现有V97 UNVERIFIED只能REJECTED的约束要尊重，首期不改变该规则，先要求合法复核再生成可评测候选。

### 7.3 生成与候选数据

LLM使用冻结curation Prompt和受控网关，单独purpose/费用归属；不得伪造一个调查role绕开RCA绑定检查。根据现有通用网关可支持的作业身份扩展最小用途，将curation job作为持久所有者，复用模型路由/审计/预算，不直接调用SDK。

输出schema包含selector、适用前提、步骤、参数约束、必要工具、成功验证条件、停止条件、已知失败场景、支持来源；每条经验能追溯。工具声明必须是允许集子集，Skill内容不能扩大权限。生成只到DRAFT；修改正文产生新assetDigest并使旧评测证明失效。

### 7.4 修评测资格关联与发布

本轮补读SkillCandidateService发现recordQualification目前主要消费passQualified，activate重算资产digest；实施时还必须将资格与**确切候选assetDigest、组合release、评测数据/评分器版本**持久关联。不能把任意PASS对象传进来就认可当前候选。

recordQualification读取真实评测记录ID并验证绑定，不接受客户端提交质量结论；候选状态更新加revision CAS，防评测/退休/激活并发覆盖。activate再次检查资格未撤销、依赖存在、权限合法、assetDigest一致，并记录认证操作者。

候选ACTIVE与线上release消费分两步：ACTIVE表示可被组合引用，实际生效通过ConfigBundleService发布/激活包含该digest的release；不能直接扫描ACTIVE偷偷影响新Run。页面分别展示“候选可用”与“已被当前发布组合采用”。

### 7.5 评测与反馈闭环

对冻结告警样本进行Skill关闭/候选开启对照，包含适用、近似但不适用、反例、工具变更、跨服务、来源污染。回放未封存查询标REPLAY_INCOMPLETE，不能返回合成成功；需真实模型验证的部分单独跑L模式。隐蔽答案仅评分侧可见。

人工发布后，新Run持久记录消费digest，按版本汇总正确性/未决率/工具量/成本/人工纠正；退化生成撤销建议，由授权者处理。只有这条链走通才能展示“受控经验演进”，不宣称无人监管自进化。

## 8. 页面、日志和指标跟随真实后端

| 已有文件 | 修改内容 |
|---|---|
| alert-web/src/views/RunDetailView.vue | 显示实际输入digest、记忆revision、来源引用、当前摘要是否已消费、每角色Skill绑定、失租/等待原因 |
| VersionsView.vue | 区分候选ACTIVE与release已采用；展示旧Run固定版本及切换命令真实状态 |
| EvalRunDetailView.vue、EvalCompareView.vue | 展示OFF/确定性/摘要消费对照，所有尝试费用、质量差异与触发次数 |
| EvalAssetsView.vue | Skill候选来源、精确资格、生成模式和依赖，未评测明确标记 |

沿现有API新增只读投影，不让浏览器读数据库或供应商密钥。输入/证据查看按权限脱敏；不展示隐藏思维链。API若未部署显示能力未就绪，不用静态样例伪装成功。

建议事件包括CHECKPOINT_COMMIT_REJECTED、SKILL_BINDING_CREATED、COMPACTION_REJECTED/CONSUMED、CURATION_FINISHED；名称实施时对齐现有事件注册。记录run/task/action/epoch/revision引用，不写全量秘密。指标以有限reason/purpose分类统计，runId放日志/trace而非Prometheus标签。

## 9. 文件级执行卡与依赖

Java路径根为`control-app/src/main/java/com/objwww/pr/control/`，迁移在`control-app/src/main/resources/db/migration/`；拟新增类在卡中注明。

| 卡 | 具体改动 | 前置 | 完成证据 |
|---|---|---|---|
| CL-01 提交围栏 | domain/agent/PrimaryCheckpoint、repository接口、PostgresPrimaryCheckpointRepository；新增PrimaryCheckpointCommitService及revision迁移 | 无 | MC08真实PG四写点、取消竞争 |
| CL-02 接线恢复 | BoundedLlmRoleRunner、DeterministicSupervisor、NativeInvestigationExecutor、RcaWorker替换所有提交/收尾写点 | CL-01 | RG03重启、旧owner退出、原恢复回归 |
| CL-03 有效投影 | ContextAssembler提取日志/指标/变更/RAG，单次证据快照；RcaModelGateway输入身份校对 | 可先独立做投影；副作用迁移依CL-01 | MC01/03/09，实际输入捕获 |
| CL-04 回读与输入预算 | 注册evidence.read（如无等效实现）、原ToolGateway装配、模型能力估算 | CL-03 | MC02/10/11，越权零读取 |
| CL-05 Skill持久绑定 | SkillSelectionService、绑定repository/PG实现（新增）、AlertAm4Config及RunConfigSwitchService | CL-01/02 | 重启/淘汰/NONE/并发/热切 |
| CL-06 累计记忆 | WorkingMemory、WorkingMemoryPort及PG实现、ContextAssembler、提交服务；schema升级 | CL-01/02/03 | MC05/06/07/22跨轮证据 |
| CL-07 摘要控制 | ContextCompactionService、ContextSummaryPort/PG；新增attempt记录、purpose与预算接线 | CL-01/02/06 | MC13～20机制验证 |
| CL-08 摘要消费 | ContextAssembler读取当前summary、Runner三模式、context_rules版本 | CL-07 | MC33/34/36实际输入与三组评测 |
| CL-09 Skill生成与资格 | SkillCurator/CandidateService、候选repository CAS、来源清单、生成命令/Controller（新增） | CL-05/06 | 第7节完整候选链，GT隔离 |
| CL-10 页面与发布验收 | RunQuery等读面、上述Vue页、评测编排、迁移回滚手册 | 按后端卡分批跟进 | 浏览器证据、隔离环境全链 |

合并顺序优先CL-01/02/03/05，先消除正确性风险；CL-04/06随后。CL-07/08默认关闭可以后置，但卡必须把未完成消费标清楚。CL-09模板模式可先人工入口，LLM模式经过独立预算及评测再启用。

不机械给每卡相加工期：同一提交服务和事务测试被多卡复用。每卡开始确认当前代码变更，完成后登记实际耗时及阻塞；真实PG/模型窗口单独排，不把等待当开发完成。

## 10. 测试分层与最终通过标准

复用[存量回归及增量验收用例](告警-R7与增强线-存量回归及增量验收用例.md)，不再增加一套重复编号。每例记录实现状态、测试文件/方法、构建指纹、scope、结果、证据和清理；旧PASS保留原版本。

| 层 | 必做内容 | 不能替代的内容 |
|---|---|---|
| 单元/组件 | 真实JSON形状投影、schema、记忆合并、选择器、状态转换 | 不能代替PG并发和真实模型 |
| PG集成 | 租约/revision/取消竞争、Skill唯一绑定、attempt配额、资格CAS、迁移兼容 | BUILD SUCCESS但全部skip不算通过 |
| 系统E2E | 告警入口→主子任务→工具→报告→测试通知→resolved，worker硬杀恢复 | 不允许直接种最终报告冒充推理 |
| L/B | 真实模型选择、反证保留、摘要/Skill对照和污染攻击 | 不用字符串包含refs证明语义正确 |
| 浏览器 | 查询实际消费版本、提交与生效差异、失败与回滚可见 | npm build不代表页面验收 |

发布门：提交围栏、版本固定、权限和GT隔离零违反；功能质量和成本分别报告，未决不当作伪成功。长轨迹摘要评测须含实际触发样本；Skill评测须包含不适用反例。高风险竞争失败直接阻断，不能用总通过率稀释。

本方案新增测试尚未运行。现有82例仅作为实施前基线；执行者先重跑受影响类，再按卡运行相关PG IT，不为每次文档改动跑全仓。

## 11. 迁移、灰度、回滚的实际步骤

### 11.1 Expand阶段

核对最新迁移号、备份及恢复可用性；追加兼容字段/表，发布能读新旧结构但暂不启用新writer的代码。迁移在真实PG演练，检查权限、索引和历史数据。为新增FK先检历史引用，不静默删除脏数据。

### 11.2 切换writer

停止旧版本领取新任务，等待其在飞动作结算；无法正常排空的动作按UNKNOWN和既有恢复规程处理。确认旧进程无权继续写，再启用带revision的writer和新Run协议。不能让旧upsert writer继续服务新协议任务。

采用新Run启用新记忆/Skill协议，不把历史不可证明的选择重新编造。已有Run若不能兼容恢复，显式暂停并关联新Run，保留原运行结论和审计；不要用默认0或最新ACTIVE假装完成迁移。

### 11.3 功能灰度

先开正确性修复和持久Skill绑定；验证真实日志入模和恢复。摘要先SHADOW收集对照，确认收益后小范围CONSUME_VALIDATED。Skill先TEMPLATE/人工入口，再LLM_CURATE；每次发布冻结一个组合与评测证明，避免同时换模型、Prompt和策略无法归因。

### 11.4 回滚

应用回滚必须选择能理解新revision/schema的兼容构建，不能退到无围栏旧writer。关摘要消费使后续合法安全点回到确定性选材，保留摘要/attempt账本；撤销Skill通过release回滚与紧急撤权处理，不改老绑定历史。数据库默认只向前修复，不执行DROP记忆/摘要表作为常规回滚。

切换配置失败仍展示WAITING/REJECTED等真实状态；旧请求费用与身份保留。备份恢复演练对拍canonical digest、依赖闭包、绑定和检查点，并实测RPO/RTO。

## 12. 每卡交接与最终演示

每卡提交：问题对应A13编号、变更文件/迁移、接口与状态契约、测试结果（含skip）、实际证据目录、残余限制、回滚办法。未实现、已实现未验收、已本地验证、已目标环境验证分别记录。

最终演示分三条，不能合并成一个SUCCEEDED截图：

1. 调查：真实告警→模型读到实际错误→直接工具或必要委派→有引用报告/诚实未决→测试通知回执→恢复事件。
2. 恢复：模型/工具在飞时失租→新worker接管→旧worker迟到被拒→有效结果唯一、预算不重置。
3. 经验演进：封存来源→候选→隔离评测→人工发布组合→新Run固定使用→撤销/回滚；若摘要启用，展示下一请求确实消费及总成本对照。

三条证据齐全后才更新对应能力“已闭环”。没有新增更多角色也可以完成这次生产工程补强；自动生产修复、多层递归Agent和额外消息中间件不在本次实现范围。

## 13. 依据与关联文档

- [2026-09-13代码审查与定向测试结论](告警-Agent闭环与代码审查-20260913.md)
- [R7现有总体方案](告警R7-真LLM多Agent技术方案.md)、[增强线现有方案](告警-增强线-Tool-MCP-RAG-Skill技术方案-v1.1.md)
- [Anthropic上下文工程](https://www.anthropic.com/engineering/effective-context-engineering-for-ai-agents)：参考上下文选材与长任务记忆；本方案具体schema和锁设计是本项目建议。
- [LangGraph持久化](https://docs.langchain.com/oss/python/langgraph/persistence)：参考检查点和已完成工作恢复语义，继续使用项目现有PG实现。
- [Anthropic工具设计](https://www.anthropic.com/engineering/writing-tools-for-agents)：参考有效工具输出与评测方式，不以新增工具数量作为完成指标。
