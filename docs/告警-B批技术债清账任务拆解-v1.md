# 告警-B批技术债清账任务拆解 v1（2026-09-12）

来源：方案实现核对报告（2026-09-12 傍晚五路审计）→ 主会话核实清单 → 用户批准 B 批先行。B 批 = 技术债清账四卡（B1~B4），全部锚点经四路独立勘察亲读验证（2026-09-12，工作区 main=8c99ab5），行号如再漂移以符号名为准。

**北极星对账（质量红线必答）**：本批不直接推进评测命中率，属"LLM 可信上场"的承载面可靠性清账——B1 证据链分层纯度（证据校验器是 MC22 准入面的组成部分）、B2 预算闸假件与生产语义同形（预算闸是 LLM 远程调用唯一过账咽喉，假件失真=测试面说谎）、B3 连接舱壁（告警洪峰下 LLM 调查链路的 DB 供给保障）、B4 晋升窗门禁启用（这是 LLM 上场晋升机制本身：连续 K 窗 LIVE_CANARY PASS 才可晋升，直接服务北极星）。四卡均可答出关联，无零关联卡。

**明确不进本批**（各有裁定归属，不沉默）：
- BA-54 内存闸（待 G1 裁定方案①/②）、MC23/BA-116/BA-120/BA-125（待用户裁定）、AM2 冗余清理（报告全部建议待裁决）——决策项，非漏做。
- EN-05 P1/P2 工具面（log_context/metric_baseline_diff/tcp/http/pg 检查）——新建工具面规模超"清账批"定位，且按工作流需独立调研五步法，另立批。
- UX-01/UX-02、R12 下钻回放读面、历史 override 审计读面、EV-05/EV-07 零散面——前端/读面类，另立 C 批候选。
- FUT-30 Holmes 适配（Holmes 已退场，建议关单不实现）、FUT-24 WAL/PITR（2C4G 评测机 pg_dump 已够用，建议裁定降级接受现状）——建议关单项，待用户确认。
- 各卡真 PG IT 复核——本机无 Docker，一律 NOT_RUN 留 195 窗。

---

## 一、批次总览

| 卡 | 内容 | 风险 | 新迁移 | 文件面（独占） |
|---|---|---|---|---|
| B1 | BA-22：EvidencePackageValidator/EvidencePackageV2 去 Jackson（R3 铁律清账） | 中 | 零 | `alert/domain/service/EvidencePackageValidator.java`、`alert/domain/model/EvidencePackageV2.java`、新增 `alert/application/EvidencePackageJsonCodec.java`、`ControlArchitectureTest.java` + 各自测试 |
| B2 | BA-108：InMemoryRunBudgetLedger 向 PG fail-closed 语义对齐 | 中低 | 零 | `alert/infrastructure/InMemoryRunBudgetLedger.java`、`SingleToolEvidenceAgent.java`（6 参旧构造面）+ 预算相关测试 |
| B3 | FUT-26：会话级超时先行 + 双 Hikari 池舱壁（入口 4 / Worker 8） | 中高 | 零 | 新增 `infrastructure/config/DataSourceBulkheadConfig.java`、`application-docker.yml`、`PersistenceConfig.java`（装配面，见全局纪律 2） |
| B4 | 晋升窗门禁启用：canary_evidence_sample 采集写入 + CanaryWindowEvaluator 周期评窗接线 | 中 | 零（V30 表已在） | `release/` 域新增采集适配器/窗口任务 + 装配 + 测试 |

**V28 剩余两表（rca_evidence/alert_inbox）分区不进本批编码**：勘察证实其为高风险设计裁定项——alert_event.inbox_id、rca_snapshot_member.evidence_id、V38 result_ref 三处单列 FK 引用须先裁定"扩复合键（动 AM1/AM4 热表写路径）vs 引用侧不建 FK"，属 PROGRESS C-23② 挂的独立评审窗开放项。本批只产出《两表分区评审卡》输入材料（见 B4 卡末节），不动码。

### 全局纪律（违者返工）

1. **共享工作区**：只 `git add` 自己名下文件；提交前 `git status --porcelain` 自查；禁 `git add -A`。发现他人未提交改动一律不碰。
2. **PersistenceConfig 协调**：B3 的装配改动若与他人（release/eval 线在飞）冲突，服务层/配置类先行，装配改动留收口清单由主会话统一执行（沿 A 批 §六 先例）。
3. **诚实语义**：无源字段显 null 或不给键，禁编造；真 PG IT 一律不写（本机无 docker），NOT_RUN 项留 195 窗并逐条登记。
4. **零新迁移**：Flyway 号段 V98 已占，B 批不需要也不许加 V99+（B4 用 V30 既有表）。
5. **测试风格**：仿 `MetricsQueryControllerTest` 惯例，内存假件/new 构造，不依赖 Spring 装配（除 B3 装配测试）。
6. **每卡毕即落账**：PROGRESS 追加时间线行；发现 Bug 当场入 BUGLOG（BA-NN 只增不删）。

---

## 二、B1：BA-22 去 Jackson（R3 铁律清账）

**缺口**：`EvidencePackageValidator.java`（Jackson 4 行 import，:3-6；JsonNode 渗透公共签名 :107-293 全链路）与 `EvidencePackageV2.java`（:3 import JsonNode；`fromJson(JsonNode)` :56 是公共 API 入参形态）在 `alert.domain` 内用 Jackson，违反 R3（`docs/告警AM4-技术方案.md:50`："R3 domain 零框架——无 Spring/JSON 库/HTTP 客户端/JDBC"）。BUGLOG `docs/告警-BUGLOG.md:34` 开放条目（注意 BA-22 编号撞车：:29 是已关闭的 AM2 指纹条目，关闭登记务必锚 :34）。

**守卫现状（切 2 的依据）**：`ControlArchitectureTest.java:95` `am4AlertDomainZeroFrameworkDependency()` 覆盖面 :96-100 仅七子包（dag/tool/budget/claim/evidence/event/agent），禁项 :101-104 含 `com.fasterxml..`；守卫注释 :91-92 明示"EvidencePackageValidator 是既有例外，待迁移后可扩面至全 alert.domain"。**冲突面**：同文件 :64-71 AFT-A01 旧规放行 jackson——扩面时必须同步收窄/删除该放行，否则两规则语义打架。

**实施（两刀，不许合一刀）**：

切 1（核心迁移）：
1. 新增 `alert/application/EvidencePackageJsonCodec`（与 NativeReportAdapter 同层同惯例，Jackson 在 application 层合规——先例 `NativeReportAdapter.java:42`）：`readTree` 后递归转中立树 `Map<String,Object>/List/String/Number/Boolean/null`；写出经 `InternalCanonicalJsonV1.canonicalize`（`alert/domain/tool/InternalCanonicalJsonV1.java:32`，domain 合规件，只吃已解析结构——先例 `ActionDigest.java:42`、`EvidenceSnapshotBuilder.java:59`）。
2. `EvidencePackageValidator.validate(String)` **签名不变**（唯一运行期消费点 `NativeInvestigationExecutor.java:295` 零改动；AlertFlowConfig :158-166/:243/:280 三处装配零改动；7 处测试直构零改动——最小性的关键）：内部先 codec 转中立树，后续 `pkg.has→containsKey`、`asText→(String)`、ArrayNode→List 全改 Map 操作。
3. `EvidencePackageV2.fromJson(JsonNode)` → `fromMap(Map<String,Object>)`，:101-141 私有辅助同步改。
4. 规范化 packageJson 输出（:160/:219 `mapper.createObjectNode()`）改中立树 + canonicalize。**前置检查（必做）**：grep packageJson 全部下游消费方（其随 AttemptArtifact 落库 `NativeInvestigationExecutor.java:315`）——ObjectNode.toString() 保输入键序、canonicalize 变字典序，若有下游对 packageJson 做 digest 对账会断链，须先核实再定输出形（redactedRawText digest :317-318 是对原文，不受影响）。
5. redact() 纯正则、extractJsonCandidate 围栏提取（:284-312）是文本级操作，留在 Validator 内不违 R3，不动。

切 2（守卫收口）：
1. **前置全量确认**：grep 全 `alert.domain` 根包（service/model/statemachine 等，非仅七子包）无其他隐藏 Jackson，否则扩面即红。
2. `am4AlertDomainZeroFrameworkDependency` 覆盖面扩至 `..control.alert.domain..`；同步裁定 AFT-A01（:64-71）的 jackson 放行删除或收窄到白名单枚举。
3. BUGLOG BA-22（:34）关单：登记迁移 commit + 守卫扩面证据（红绿留证照 :81-92 惯例）。

**测试**：codec 单测（JSON 文本→中立树逐型断言、数字归一 `1.0→"1"` 钉、键序字典序钉）；EvidencePackageValidatorTest 既有用例全经 `validate(String)` 入口应零改绿（若红=切 1 有行为漂移，停下来查）；新增一个既有测试向量的 packageJson 输出比对案（锁输出形态漂移面）。

**禁区**：不改 `validate(String)` 签名；不动 NativeInvestigationExecutor/AlertFlowConfig/7 处测试构造；不把 codec 放进 domain；不动 AM1 旧放行规则以外的其他 ArchUnit 规则。

---

## 三、B2：BA-108 预算账本语义对齐（InMemory → fail-closed）

**缺口**：`InMemoryRunBudgetLedger.java:59-65` reserve 无限额行时跳过判定照常放行（`:142` probe remaining 给 `Long.MAX_VALUE`），PG 侧 `PostgresRunBudgetLedger.java:56-66` 单语句条件 UPDATE 无行=0 行更新=fail-closed 拒绝（:183-184 注释明载）。InMemory 类 javadoc :18-19 自认"刻意不同"。裁定方向已有：**InMemory 向 fail-closed 对齐**（`docs/告警-R7执行日志-20260911.md:568-570` 偏差登记②；BUGLOG :116 修复栏同述；接口 javadoc `RunBudgetLedger.java:21-22` 以 PG 语义为参照）。BA-108 条目 `docs/告警-BUGLOG.md:116`，状态"修复待真窗复核"。

**实施**：
1. `InMemoryRunBudgetLedger.reserve`：row==null 直接 `probe(runId, kind, false)` 拒绝且不落 entry；`probe` :139-145 row==null 分支 remaining 改 0；类 javadoc 删"刻意不同"段改记对齐后语义。预计 <30 行。
2. **爆炸半径扫尾（裁定明载的主要工作量）**：
   - `SingleToolEvidenceAgent.java:91-94` 6 参旧构造内建 `new RunBudgetGate(new InMemoryRunBudgetLedger())`（:98-99 javadoc 自称"生产装配禁止"但无强制机制）——fail-closed 化后凡走该构造的 agent 用例全拒。本卡一并处置：改注入或加显式守卫（二选一，倾向加守卫 fail-fast 报错文案指认"生产禁止"，改动最小）。
   - `R7PrimaryModeExecutorTest.java:621-625` generousLimits 仍带 TOKEN=1_000_000 五维（BA-108 点名的盲区夹具）清成生产形四维（先例=同文件 :208-234 回归案 openRunMergesPrimaryTokenBudgetFromProfile）。
   - grep 全部构造 InMemory 账本/gate 的测试（`MetricsAgentTest`/`R7ActionGuardAdmissionTest`/`NativeInvestigationExecutorTest.java:142` 等）逐一核对限额种子——缺种的补 openRun/ensureLimit。
3. 补 `InMemoryRunBudgetLedger` 专属 L0 测试类（当前缺位，Glob 穷尽无）：无限额行 reserve 拒绝 + 与 PG itB09（`AlertRunBudgetLedgerIT.java:240`）同断言文案的对照案，形成双实现同语义钉。

**登记不动项**（写进交付报告偏差清单，不改码）：findStaleReservations InMemory 空桩（:100-102，M4-37 Reconciler 输入面假件无对账能力——裁定口径若非"同语义假件"可豁免，javadoc 明载）；PG adjust（:172-181）与 InMemory adjust（:132-137）0 行双双静默（次要面）。

**禁区**：不改 PG 侧任何实现；不改 `RunBudgetGate` 编排序（`RunBudgetGateTest.java:169` 用自写 RecordingLedger，不经过 InMemory，零波及）；不动 IncidentBudgetLedger（`admit()` 生产无调用方，阶段 C 接线不归本卡）。

---

## 四、B3：FUT-26 连接舱壁（会话级超时 + 双 Hikari 池）

**缺口**：架构 v1.2 FUT-26 裁定（`docs/架构设计-告警Agent-v1.2.md:71`："入口与 Worker 使用隔离连接池……池耗尽时入口快速返回 503"）+ §13 预算表（:969 ingress 4 / :970 worker 8 / :971 合计固定 12）未落。现状：`application-docker.yml:11-15` 单池 12 + connection-timeout 5000（BA-12④）；**无自定义 DataSource @Bean**（全仓 grep 零命中，PersistenceConfig.java:26 注释自认"DataSource 由 Boot 自动配置提供"）；会话级超时（statement/lock/idle-in-transaction）全仓零设置（仅 `AlertHistoryExecutor.java:30` 一处注释提及）。附带缺口：Tomcat threads 未配（Boot 默认 200 vs §13 :968 的 16）；yml :11 注释"§15"实为 §13（文档章节漂移）。

**关键事实（决定切法）**：纯 yml 无法分双池——`spring.datasource.hikari.*` 只产一个 DataSource bean；双池需两个 `HikariDataSource` + 两套 JdbcClient/事务管理器 + 入口/Worker 仓储分别注入。

**实施（三步，每步独立可交付、独立提交）**：

步 1（会话级超时，零 Java 改动）：单 DataSource 经 Hikari `connection-init-sql` 落 `SET lock_timeout='2s'; SET idle_in_transaction_session_timeout='30s'`（纯 yml）。statement_timeout 暂不落——Worker 长投影事务需留量，先仅 lock/idle 两项，statement 档值待 195 压测窗定（登记偏差）。顺手修正 yml :11 注释 §15→§13。

步 2（双池配置类）：新建 `infrastructure/config/DataSourceBulkheadConfig.java`（`@Profile("docker")`）：`@ConfigurationProperties("app.datasource.ingress"/"app.datasource.worker")` 各铸 HikariDataSource（4 + 8，yml 暴露旋钮，compose 零新增 env）；worker 池 `@Primary` 兜底未迁移 bean（Flyway/IT 基建只认单 DataSource 不炸）。PersistenceConfig 改动面：入口面仓储 bean（alert inbox/intake + 各 QueryService 读面链）改注 ingressJdbcClient；Worker 面（RcaWorker 依赖链/AlertInboxProcessor/slot/heartbeat）显式 workerJdbcClient。
   - **前置核实（必做）**：跨池事务边界——切分点必须选在无跨面事务处（inbox 写入与 claim/投影天然分阶段，但 `AlertIntakeService` 与投影是否同事务需逐链路核实，发现跨面事务则该链路暂缓迁移并登记）。
   - 三个直接 new DataSourceTransactionManager 的仓储（`PostgresConfigBundleRepository.java:108-110`、`PostgresGoldenCandidateRepository.java:85-88` 等）显式改注入，漏改=静默用默认池。

步 3（入口 503 语义收口 + 验收测试）：入口面把连接获取超时（CannotGetJdbcConnectionException）显式映射 503（现状 `AlertWebhookController.java:96-98` DataAccessException 兜底可能已够，需测试证明）；**核心验收测试**：Worker 占满 worker 池时入口 4 连接仍可用（内存双池 + 计数假件模拟占满，断言入口请求仍 200/快 503）。

**附带项（进不进本卡由用户裁定）**：Tomcat threads 200→16（一行 yml，§13 :968）；PG max_connections 收紧（PG 侧现用默认 100，doc :971 的"12"只是 control-app 应用侧预算；arena/eval/notify 共享同实例，收紧需全局算账——**建议不做**，登记）。

**禁区**：不动 eval profile（`application-eval.yml` 不装配 PersistenceConfig，天然免疫）；不加 compose env（旋钮走 yml）；不动 PG 实例参数。

---

## 五、B4：晋升窗门禁启用（采集写入 + 周期评窗接线）

**缺口**（两项指控均勘察证实）：①`canary_evidence_sample` 表（V30:18-28，授权 select+insert only V30:95-103）src/main 零引用——无 Repository、无写入路径（e2e-am6-01 脚本 :23-25 自认"M6-01 无采集适配器=装配事实"）；②`CanaryWindowEvaluator.java:26`（186 行纯域类，evaluate()+hasConsecutivePasses()）生产零调用点，verdict 表写面 append() 同零生产写入（周边已建成：端口+PG 实现 `PersistenceConfig.java:497-499` 已装配、只读消费 `CanaryStatusController.java:101` 在位）。设计挂钩点：样本写入者=生产采集适配器（`docs/告警AM6-落码技术方案.md:259`"真实晋升证据由长窗采集器生成，必须可追溯到生产 incident，禁止脚本补数"）；Evaluator 调用者=周期窗口任务（V30:88-89 注释"scored_json M6-03 窗口任务回填"）。

**实施**：
1. 新增 `CanaryEvidenceSampleRepository` 端口 + PG 实现 + @Bean（对照 `PostgresCanaryWindowVerdictRepository` 既有写法，授权面 V30 已备）。
2. 采集适配器挂 NATIVE run 收尾链（复用 NativeInvestigationExecutor→finishTask 面，落码方案 :155 落点）；按 INV-AM6-9 只许生产路径构造 LIVE_CANARY（DRILL/REPLAY 类由各自既有路径，样本 evidence_class 值域 CHECK V30 已钉）。
3. 周期窗口任务：沿用 worker 拍循环惯例（A2 裁定同律，**禁 @Scheduled/Quartz**——RcaWorker 或 release 侧既有 worker 拍内独立容错调用）：读样本→调 Evaluator→append verdict→scored_json 回填（对齐 V30:88-89）。
4. K/minSamples/墙钟值走 bundle 策略段（`CanaryWindowPolicy.fromBundle` 已就绪）——文档明令"基线 spike 后版本化、禁硬编码 50/72h"（落码方案 :158）。
5. ArchUnit 补钉："LIVE_CANARY 只能由生产采集适配器构造"（技术方案 :248 有规无则）。

**明确不做（另切小卡）**：critical FAIL→STOP 新 Native admission 自动动作（技术方案 :133）——涉及 ConfigBundle/路由写面，独立评审。

**层位偏差先裁定**：技术方案 :77 把 Evaluator 归 `release/application/`，实际落在 `release/domain/service/`——本卡不动其位置（纯域类归 domain 反而合规），偏差写进交付报告。

**测试**：采集适配器单测（生产路径构造 LIVE_CANARY 落行/非生产路径拒造）；窗口任务拍循环测试（独立容错、样本不足 minSamples 不评、评窗后 append+回填各一次）；ArchUnit 新规则红绿留证。真 PG IT（样本表授权面/唯一约束 run_id 幂等）NOT_RUN 留 195 窗。

**附带交付**：《rca_evidence/alert_inbox 分区评审卡》输入材料——三处单列 FK 依赖清单（alert_event.inbox_id / rca_snapshot_member.evidence_id / V38 result_ref）、扩键 vs 免 FK 两方案代价对账、alert_inbox 热轮询部分索引分区重建风险点——纯文档，供 C-23② 评审窗使用。

**禁区**：不改 V30 表；不动 CanaryWindowEvaluator 判定逻辑本体；不做 STOP admission 自动动作；样本采集禁脚本补数（INV-AM6-9）。

---

## 六、执行编排建议

- B2（最小、语义裁定已齐）先行；B1 切 1 与之文件面不相交可并行；B3 步 1 独立小提交随时可插；B3 步 2/3 与 B4 体量最大排后。
- 并行 agent 分工注意：B1 与 B2 都碰测试面但文件不相交；B3 碰 PersistenceConfig 遵循全局纪律 2。
- 每卡 DoD：scoped 测试绿 + 字段/语义与本拆解逐条一致 + 禁区自查 + PROGRESS 落账 + 偏差/NOT_RUN 逐条列明。
- 全批毕：全量 `mvn -q -pl control-app test` + `npm run build` 双绿后合并 main（合并/push 时机与授权归用户）。
