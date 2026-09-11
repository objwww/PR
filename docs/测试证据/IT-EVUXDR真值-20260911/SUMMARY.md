# IT-EVUXDR 真值 — 2026-09-11

control-app EV/UX/DR 六卡 Postgres 集成测试在 195 真 PG（Testcontainers postgres:16-alpine）上的实跑结果。

## 环境

- 执行机：195（root@146.56.195.225），工作副本 `/opt/it-work`（rsync 自 `/opt/build/pr`，排除 target/.git），未动 `/opt/build/pr`
- JDK：`/opt/jdk-21.0.12.1+1`（OpenJDK 21.0.12.1）；Maven 3.9.9；Docker 可用
- PG 形态：**Testcontainers 自起 `postgres:16-alpine` 临时容器**（每次 mvn fork 一个，随机端口，ryuk 自动回收），Flyway 全量迁移 V1~V86，真实角色 control_app/publisher_app/notify_app/eval_app
- deploy 栈（deploy-postgres-1 / deploy-control-app-1 等）全程未触碰；跑完后 `docker ps` 无 testcontainers 残留容器

## 与任务书的两点实测偏差（如实登记）

1. **it_scratch 库未建未用**：`PostgresITBase`（control-app/src/test/java/com/objwww/pr/control/it/PostgresITBase.java:101-105）硬编码 Testcontainers `PG.getJdbcUrl()`，无 env/系统属性覆盖口。IT 天然用一次性容器隔离，与 deploy-postgres-1/pr_agent 零接触，隔离目标等价达成。故未创建 it_scratch，也无需 DROP。
2. **本地 skip 数对不上 21**：六卡候选池实为 **7 个 IT 类 42 例**（EV-03 对应 PostgresEvalQueryReaderIT 而非 Lifecycle；EV-04 才是 PostgresEvalLifecycleIT）。本批 42 例全部真实执行，Skipped=0。
3. IT 由 failsafe 在 verify 阶段执行（根 pom.xml:80-90），`mvn test -Dtest=` 不会触发；实际命令用 `mvn verify -Dit.test=`。

## 执行命令（每类一条，可复现）

```
cd /opt/it-work && export JAVA_HOME=/opt/jdk-21.0.12.1+1 PATH=$JAVA_HOME/bin:$PATH
mvn -pl control-app verify -Dit.test=<ClassName> -Dtest=__none__ -DfailIfNoTests=false -Dsurefire.failIfNoSpecifiedTests=false
```

（`-Dtest=__none__` 仅为跳过 surefire 单测阶段；IT 由 failsafe 跑。）

## 总账

42 例：**PASS 28 / FAILURE 12 / ERROR 2 / Skipped 0**。六卡全部 FAIL（各卡均有红例，非接线问题——Testcontainers 容器正常启动、迁移跑完、多数用例真实通过）。

## 逐卡结果

### EV-03 评测投影（V80）— PostgresEvalQueryReaderIT — FAIL（6 跑 / 5 PASS / 1 FAILURE，5.0s）
- PASS casesExposeStableExecutionIdentityAndLinks / phaseAndProgressAreDistinctRealTimestamps / terminalRunCountsAndCaseCountFromPg / runningRunWithoutEventsProjectsHonestNulls / displayNameAndModeWritableByEvalAppAndProjected
- FAILURE phaseEventGrantMatrix：期望权限拒绝信息，实际 `BadSqlGrammarException: bad SQL grammar [SELECT count(*) FROM eval_phase_event]`

### EV-04 生命周期（V81）— PostgresEvalLifecycleIT — FAIL（5 跑 / 3 PASS / 2 FAILURE，4.5s）
- PASS readSideEvolvesWithLifecycle / orphanSweepRequeuesOrFinalizes / idempotencyAnchorRejectsDuplicateKey
- FAILURE commandTableGrantMatrix（:82）：期望 PermissionDeniedDataAccessException，实际 BadSqlGrammarException `[UPDATE eval_run_command SET state = 'CLAIMED']`
- FAILURE workerColumnGrantsAndPhaseEventInsertOnly（:117）：同上，`[UPDATE eval_run_command SET payload = '{}'::jsonb]`

### EV-05 案例与证据读面 — PostgresEvalCaseEvidenceIT — FAIL（7 跑 / 6 PASS / 1 FAILURE，4.6s）
- PASS caseDetailProjectsChainAndHonorsDoubleKey / caseIdentityExactKeyRlsAndAmbiguity / caseDetailWithBrokenChainProjectsNulls / evidenceRefRowsResolveOnlyWithinCaseRun / evidenceMetaEnforcesRunScope / caseLogEvidenceProjectsFrozenPayloads
- FAILURE grantMatrixKeepsReadFaceMinimal：期望权限拒绝，实际 BadSqlGrammarException `[SELECT count(*) FROM rca_evidence]`

### EV-07 配对工作台（V85）— PostgresEvalCompareIT — FAIL（5 跑 / 4 PASS / 1 ERROR，4.6s）
- PASS compareMetaProjectsReproducibilityColumns / compareCasesProjectIdentityColumns 之外全部（grantMatrixKeepsComparisonFaceMinimal / comparisonInsertOnlyAndLatestByPair / runProjectionCarriesLatestComparisonGateOutcome）
- ERROR compareCasesProjectIdentityColumns：BadSqlGrammarException 直接抛出（未捕获）

### UX-01 告警分类（V82）— PostgresIncidentClassificationIT — FAIL（6 跑 / 4 PASS / 2 FAILURE，4.6s）
- PASS ruleClassificationAndGeneratedEffectiveColumns / readFaceCategoryProjection / idempotencyAnchorUnique / casRevisionGuard
- FAILURE auditTableGrantMatrix（:115）：期望 PermissionDenied，实际 BadSqlGrammarException `[update incident_category_override set reason = 'x' ...]`
- FAILURE checkConstraintsRejectOutOfVocabularyAndPartialWrites：`[update incident set category = 'DATA' where id = ?]` 同类异常形态

### UX-02 仿真机器人（V83）— PostgresDutyBotIT — FAIL（5 跑 / 2 PASS / 3 FAILURE，4.5s）
- PASS seqCursorPagination / replayPairingAndRefsRoundTrip
- FAILURE grantMatrix（:90）：期望 PermissionDenied，实际 BadSqlGrammarException `[update chat_session set title = 'x' where id = ?]`
- FAILURE clientMessageIdUnique（:73）：`expected: 4L but was: 3L`
- FAILURE checkConstraints（:43）：期望违反 `ck_chat_message_role`，实际先撞 `ck_chat_message_intent`

### DR-02 演练作业链（V86）— PostgresDrillIT — FAIL（8 跑 / 3 PASS / 4 FAILURE / 1 ERROR，5.0s）
- PASS idempotencyKeyUnique / activeEnvPlaceholderUnique / closedShapeCheck
- FAILURE controlAppGrantMatrix：`[UPDATE drill_job SET operator = 'hacked' WHERE id = ?]`
- FAILURE evalAppGrantMatrix：`[UPDATE drill_job SET state = 'PRECHECK' WHERE id = ?]`
- FAILURE publisherNotifyZeroGrants：`[SELECT count(*) FROM drill_job]`
- FAILURE eventsInsertOnly：`[UPDATE drill_event SET actor = 'hacked' WHERE drill_id = ?]`
- ERROR claimSkipLocked：BadSqlGrammarException 直接抛出

## 失败共性（猜想，非修复依据）

14 个红例中 12 个是同一形态：**期望「权限拒绝（42501→PermissionDeniedDataAccessException）」的负路径，实际抛 BadSqlGrammarException**——即 PG 报的不是鉴权错误而是 SQL 语法/标识符类错误（42xxx 非 42501，如列不存在 42703 或关系不可见 42P01）。涉 `title`/`reason`/`operator`/`payload`/`category` 等列名与 `SELECT count(*)` 裸表访问。UX-02 另有两例是约束/计数语义不符（ck_chat_message_intent 先于 role 触发；计数 3≠4）。猜想方向：测试 SQL 引用的列/授权面与 V80~V86 迁移实际落地不一致（测试先行或迁移缺列/缺 grant），需主会话对照迁移 SQL 定责。

## 证据清单

- `EV03-PostgresEvalQueryReaderIT.log` … `DR02-PostgresDrillIT.log`（7 个 mvn 全量输出，含完整 stacktrace）
- `failsafe-reports/`（7 个 .txt + 7 个 TEST-*.xml，逐方法三态与耗时）
- 本 SUMMARY.md
- 打包：`/opt/it-evidence.tar.gz`（195），已 scp 回主机同目录

## 清理登记

- Testcontainers 临时容器由 ryuk 自动回收，跑后 `docker ps` 实证无残留
- 未创建 it_scratch（见偏差 1），无需 DROP；pr_agent 库与 deploy 栈零接触
- 未改任何产品/测试/SQL 代码；/opt/it-work 仅为只读构建副本（target/ 产物留在 195，可随时整目录删除）

---

# 修复批（2026-09-11 深夜，执行者）——首轮 14 红定责与两轮复跑

## 定责（14 红 → 1 产品雷 + 13 测试形状）

1. **产品雷 BA-123（1 红）**：`PostgresDrillJobRepository.CLAIM_SQL` 把 `RETURNING ` 的尾空格写在 text block 行尾，JLS 编译期剥离行尾空白 → 实际 SQL 拼成 `RETURNINGid`（42703）→ 领取 CAS 全瘫。claimSkipLocked 的 ERROR 即此。产品修复：空格移出 text block（`RETURNING""" + " " + COLS`，附注释）。195 生产容器日志零 drill 领取报错（DrillWorker 未进轮询=休眠面），修复随源走、jar 重部署随下一部署窗。
2. **整链文本断言改形（11 红）**：Spring 对 42501（permission denied）的翻译是 BadSqlGrammarException，其**顶层消息只带 SQL 文本**，PG 真因（permission denied for …）在 cause 链——`hasMessageContaining("permission denied")`/`isInstanceOf(PermissionDeniedDataAccessException)` 永远扑空。统一改 AssertJ `hasStackTraceContaining(...)`（整链含 cause）。涉 EV-03 phaseEventGrantMatrix、EV-04 两案、EV-05 grantMatrix、UX-01 auditTable+生成列两案、UX-02 grantMatrix、DR-02 四案。UX-01 生成列案的 PG 真文（can only be updated to DEFAULT）同样只在 cause 链。
3. **约束求值顺序（1 红，UX-02 checkConstraints）**：`(role='bot', intent='HELP')` 行 PG 先判 ck_chat_message_intent 再判 ck_chat_message_role——种子 role 改 'user' 后逐断言各撞目标约束；顺带 clientMessageIdUnique 计数 4→3（原断言多算一行）。
4. **RLS 写面种子（1 红，EV-07 compareCases）**：HOLDOUT 案行 eval_app INSERT 策略本就拒（partition_class <> 'HOLDOUT'），种子须走 admin 面（RLS 行级拒绝即策略生效实证）。

## 复跑轮 1（it-evuxdr-fix-20260911）——5/7 类绿

修复同步 195（本地↔195 md5 双侧对拍；PowerShell UTF8 需无 BOM 重写，BOM 曾致 javac 非法字符 \ufeff）后逐类 `mvn verify -Dit.test=`：EV-03(6)/EV-04(5)/EV-05(7)/UX-01(6)/UX-02(5) 全绿；剩 2 红：
- **PostgresDrillIT.claimSkipLocked**：BA-123 修复后 SQL 可跑，露出断言与真实契约错位——断言期望「第二 worker 领取空手」，但**领取契约=身份标记不翻相位**（State 枚举无 CLAIMED；CLAIM_SQL 只写 worker_id/claimed_at/revision，state 留 QUEUED；worker 首个相位推进就是 QUEUED→PRECHECK；findOrphanedClaims 按 worker_id+claimed_at 认领超龄，QUEUED 在扫描集内）。「恰一人执行」由相位 CAS 兜底（SKIP LOCKED 只保并发瞬间一人锁到，顺序双领=后者覆盖身份 revision+1）。
- **PostgresEvalCompareIT.compareCasesProjectIdentityColumns**：HOLDOUT 种子撞 V21 复合 FK `fk_case_version_dataset_partition (dataset_version_id, partition_class)`——HOLDOUT 案行必须挂 partition_class='HOLDOUT' 的 dataset_version 头，且 INV-AM5-1 触发器禁 PUBLIC_BENCHMARK×HOLDOUT（V20 词表两约束均容 PRIVATE×HOLDOUT）。

## 复跑轮 2（it-evuxdr-fix2-20260911）——7/7 类全绿收官

- **claimSkipLocked 重钉真契约**：领取后 state=QUEUED 钉死；顺序双领 worker-b 覆盖身份 revision=2；持旧 revision(1) 推进败、持新 revision(2) 推进胜（执行权恰一人）；无 QUEUED 行才真空手。
- **HOLDOUT 种子补版本头**：admin 面 insert dataset_version(source_class='PRIVATE', partition_class='HOLDOUT', name='rca100-holdout', version='rca100-v1.1')，再落 HOLDOUT 案行——reader join（dv.version=run.dataset_version AND case_key=scenario_id）对 RLS 不可见行零命中 → 身份列 null 语义不变。
- 终面：**PostgresDrillIT 8/8、PostgresEvalCompareIT 5/5，两轮合计 42/42 全绿，Skipped=0**；跑后 `docker ps -a` 无 testcontainers 残留（轮 1 快照瞬见 ryuk 系自清中态，终查无）。

## 证据清单（修复批增量）

- `runs/it-evuxdr-fix-20260911/`（轮 1：7 类 mvn 全量输出，5 绿 2 红原样留证）
- `runs/it-evuxdr-fix2-20260911/`（轮 2：两红类终绿输出）
- `it-evuxdr-fix-round1.tar.gz` / `it-evuxdr-fix2-final-green.tar.gz`（两轮 failsafe-reports 打包）
- 改动面：7 IT 类 + PostgresDrillJobRepository（BA-123）+ PostgresEvalCompareIT/PostgresDrillIT 终钉，195 /opt/build/pr 已同步（md5 对拍：Drill 6b48d19e…、EvalCompare 9c6da80d…）

