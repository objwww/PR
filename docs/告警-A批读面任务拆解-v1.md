# 告警-A批读面任务拆解 v1（2026-09-12）

来源：执行者《告警线方案文档实现点核对报告》（2026-09-12 傍晚）→ 主会话 A~E 处置分类 → 用户批准 A 批先行。A 批 = 读面/接线类小缺口四卡（A1~A4），全部**零新迁移**、不动 195 基线。

本拆解每条锚点均经主会话亲读验证（2026-09-12，main=bef28c4 工作区），行号如再漂移以符号名为准。

---

## 一、批次总览

| 卡 | 内容 | 文件面（独占） | 装配变更 | 测试基线 |
|---|---|---|---|---|
| A1 | Case 详情 evidence/claims 死占位接通 | `ops/application/OperatorQueryService.java` + 其测试 | 有（留收口，§六） | scoped 绿 |
| A2 | EN-04 expireOverdue 调度接线 | `alert/application/RcaWorker.java` + `infrastructure/config/AlertFlowConfig.java` + 测试 | 有（AlertFlowConfig，自做） | scoped 绿 |
| A3 | `/api/eval/runs/{id}/events` 游标事件端点 | eval 域四件（端口/实现/服务/控制器）+ 测试 | **零** | scoped 绿 |
| A4 | Run 详情字段补全（claims/name） | `alert/application/RunQueryService.java`、`domain/claim/ClaimStore.java`、`infrastructure/persistence/PostgresClaimStore.java`、4 处测试假件、`alert-web/src/views/RunDetailView.vue` + 测试 | 有（留收口，§六） | scoped 绿 + npm build |

分支：`feat/a-batch-readfaces`（已从 main=bef28c4 切出）。三 agent 并行：agent-α=A1、agent-β=A2、agent-γ=A3+A4（A3/A4 文件面不相交但与 A1 的 ClaimStore 投影语义需一致，γ 内串行）。

### 全局纪律（违者返工）

1. **共享工作区**：只 `git add` 自己名下文件；提交前 `git status --porcelain` 自查；**禁 `git add -A`**。工作区现存他人未提交改动（`PersistenceConfig.java` M + `SkillCuratorService.java`/`RcaRunSource.java`/`SkillCuratorServiceTest.java` 未跟踪，EN-08 二期另一执行者在途）——**一律不碰、不提交、不格式化该文件**。
2. **PersistenceConfig 协调**：A1/A4 的装配改动（构造加参）**不做，留 §六收口清单**由主会话统一执行。Agent 交付 = 服务层/端口/控制器/前端 + 测试绿（测试全部用内存假件/new 构造，不依赖 Spring 装配）。全量编译保持绿（构造加参只发生在收口时，同步改装配，不会断）。
3. **诚实语义**：无源字段显 null 或不给键，前端渲染「未统计/—」；禁编造 desc/summary/agree 等 mock 遗留字段；真 PG IT 一律不写（本地无 docker），NOT_RUN 项留 195 窗。
4. **白名单契约**：`OperatorApiController.java:30` 契约——投影只出白名单摘要/引用/digest，**禁回 canonicalPayload 正文**。
5. **零新迁移**：Flyway 号段 V96/V97 已占，A 批不需要也不许加 V98+。
6. 测试风格仿 `MetricsQueryControllerTest`/`ReportQueryControllerTest`：standalone MockMvc + 内存假件。

### 相关文档批注（执行者开工前必读对应行）

| 卡 | 必读文档 | 位置 |
|---|---|---|
| 全局 | `docs/告警-审查缺口修补方案-P0批任务拆解-v1.md`（格式与纪律范例）；`.kimi-code/skills/milestone-workflow/SKILL.md` | 全文 |
| A1 | `docs/告警-前端联调-端点缺口盘点-v1.md` P1 #5 条（本卡对应的缺口登记原文）；`docs/告警-前端重设计与全量联通方案-v1.md` §5 处置页 | 缺口盘点 P1 表 |
| A2 | `docs/告警-EN执行日志-20260911.md` EN-04 卡节「遗留」段（expireOverdue 未接线登记）；`docs/告警-增强线-Tool-MCP-RAG-Skill技术方案-v1.1.md` EN-04 epoch 章 | EN-04 节 |
| A3 | `docs/告警-EV03-评测投影契约-v1.md`（RV08 红线：禁读 model_call_ledger）；`docs/告警-评测中心与能力版本演进-审查及详细改造方案-v1.md` §5.3（events 端点契约出处） | 全文/§5.3 |
| A4 | `docs/告警-前端联调-端点缺口盘点-v1.md` P1 #3 条；`docs/告警-前端联调-runbook-v1.md` RunDetail 段 | 缺口盘点 P1 表 |

---

## 二、A1：Case 详情 evidence/claims 死占位接通

**缺口**：`OperatorQueryService.workspace()` 硬编码空列表（已验证 `control-app/src/main/java/com/objwww/pr/control/ops/application/OperatorQueryService.java:122-123`）：

```java
map.put("evidence", List.of());
map.put("claims", List.of());
```

端点：`OperatorApiController.java:70-76` GET `/api/cases/{id}`。前端 `CaseDetailPanel.vue` 两个 tab 消费（已验证 :63-93）。

**数据面**（已验证）：
- `OperatorCase.runId()` 可空（domain :29）——null 时 evidence/claims 给空列表。
- `EvidenceRepository.findByRunId(UUID)` → `List<EvidenceEnvelope>`（`alert/domain/evidence/EvidenceRepository.java:21`；读路径自带 digest 重算 verify，篡改行抛 `IllegalStateException`，本卡不动此契约）。Envelope 字段：evidenceId/runId/taskId/evidenceType/schemaVersion/observedGeneration/source/scope/timeStart/timeEnd/canonicalPayload/payloadDigest（**无 desc/summary**）。
- `ClaimStore.findByRunId(UUID)` → `List<ClaimRow>`（`alert/domain/claim/ClaimStore.java:28`）。ClaimRow 字段：id/runId/fingerprint/claimHash/claimKey/status(ClaimStatus: TRUE/FALSE/UNKNOWN)/evidenceBasis/lifecycle(ACTIVE/SUPERSEDED)/reason/scope/timeRange/observedGeneration/sources/evidenceRefs(List<String>)/policyVersion/snapshotDigest。

**字段映射（写死，禁自创）**——evidence 行（前端 `CaseDetailPanel.vue:64-70` 消费 ev.id/type/source/window/verify/summary/taskId）：

| 前端键 | 源 | 缺省 |
|---|---|---|
| id | `evidenceId.toString()` | — |
| type | `evidenceType` | — |
| source | `source` | — |
| window | `timeStart + " ~ " + timeEnd`（ISO-8601） | 任一端 null → null |
| verify | 常量 `"VERIFIED"`（读出口 digest 重算已通过即此义；词典 `displayNameZh.js:30-34` 已收录） | — |
| summary | **null**（无源，前端渲染空，不编造） | — |
| taskId | `taskId.toString()` | null → 不给键 |

claims 行（前端 `CaseDetailPanel.vue:83-88` 消费 cl.id/text/verdict/evidenceRefs + detail.conflictNote）：

| 前端键 | 源 |
|---|---|
| id | `id.toString()` |
| text | `reason`（判定理由，人读文本） |
| verdict | `status.name()` |
| evidenceRefs | `evidenceRefs`（原样，元素为引用字符串） |
| conflictNote（detail 级） | 存在 `lifecycle==ACTIVE && evidenceBasis==MULTI_SOURCE_CONFLICT` 的 claim → 其 claimKey 逗号串；否则 null |

排序：evidence 按仓储返回序（created_at,id 已保序）；claims 按 claimKey 字典序。

**实施**：OperatorQueryService 构造 2 参 → 4 参（加 `EvidenceRepository`、`ClaimStore`），workspace() 内 runId 非空时查两面投影。**装配不改**（§六收口）。同包新建/扩展测试：内存假件（EvidenceRepository/ClaimStore 桩）覆盖——runId null 空列表、正常投影字段逐键断言、conflictNote 三分支（无冲突/冲突 ACTIVE/冲突 SUPERSEDED 不算）、白名单（响应 JSON 无 canonicalPayload 键）。

**禁区**：不动 `OperatorApiController`、`OperatorCase` 域模型、前端任何文件；不按 evidenceRefs 反解 findById（侦察裁定：自由文本引用无强制关联，首版不反解）。

---

## 三、A2：EN-04 expireOverdue 调度接线

**缺口**：`RunConfigSwitchService.expireOverdue()`（`alert/application/RunConfigSwitchService.java:163-172`，零参，内部走 `OperatorCommandRepository.findWaitingOverdue`，Postgres 实现已存在）无调度调用方——过期的 WAITING 切换命令永不转 EXPIRED。

**接线点（已验证裁定）**：`RcaWorker.loop()`（`alert/application/RcaWorker.java:346-367`）`recoverExpired()`（:349）之后每拍调一次。理由：同一 `operator_command` 账本、同一 control_app 进程身份、同一 docker profile（Drill/Eval worker 在 eval-runner 进程不可挂）。

**实施**：
1. `RcaWorker` 构造加 `RunConfigSwitchService` 依赖（末位参数）。
2. loop() 内 `recoverExpired();` 后加独立容错调用：
   ```java
   try {
       runConfigSwitchService.expireOverdue();
   } catch (RuntimeException e) {
       log.error("expireOverdue 拍失败，下拍重试", e);
   }
   ```
   必须独立 try-catch——失败不能炸 recover 循环，不能阻断 runOneCycle。
3. 装配：`AlertFlowConfig.java:436-466` 的 RcaWorker bean 加参注入（RunConfigSwitchService bean 已在 `PersistenceConfig.java:801-818`，直接引用，**不动 PersistenceConfig**）。本卡装配自做（AlertFlowConfig 是 A2 独占文件）。
4. 波及：`RcaWorker` 其他构造点（测试）同步补参——`grep -rn "new RcaWorker(" control-app/src` 全量补齐。

**测试**：新建/扩展 RcaWorker 测试——①每拍恰调一次 expireOverdue；②expireOverdue 抛异常时循环继续（下一拍仍执行 recoverExpired）；③stop() 后不再调用。内存假件 mock RunConfigSwitchService。

**禁区**：不改 `expireOverdue()` 本体、不改 `RunConfigSwitchService` 任何方法、不加调度框架（@Scheduled/Quartz 一律不许——沿用 worker 拍循环惯例）。

---

## 四、A3：/api/eval/runs/{id}/events 游标事件端点

**缺口**：评测中心方案 §5.3 拟定的 `GET /api/eval/runs/{id}/events` 未实现；`eval_phase_event` 表（V80 迁移 :31-46）只有写面（`EvalBatchRunner.java:121` 经 `EvalPhaseEventSink` 端口），无读面。

**表结构（已验证 V80）**：id / eval_run_id / phase（ck 七值 PREPARING/INJECTING/AWAITING_ALERT/AWAITING_RCA/SCORING/FINALIZING/RECOVERING）/ entered_at / worker_id / detail jsonb / created_at。**无 seq 列** → 用键集游标 `(entered_at, id)`，**不能照抄 rca_event 的 after_seq 数字游标**。键集先例：`EvalQueryReader.java:33` KeysetCursor。

**读面模板**（照抄惯例）：`EventQueryController.java:75-98` + `EventQueryService.java:42-60`（limit 钳 [1,200] 默认 50、游标解析失败 400）+ `RcaEventReader` 端口形状 `{rows, nextCursor}`。

**实施（四件，全部 eval 域内，零装配变更）**：
1. `EvalQueryReader` 端口（`eval/application/EvalQueryReader.java`）加法：`record EvalPhaseEventRow(String id, String phase, String enteredAt, String workerId, String detail)` + `List<EvalPhaseEventRow> findPhaseEvents(UUID runId, String cursor, int limit)`（或独立小端口 `EvalPhaseEventReader`，二选一，倾向并入现有 reader——`PostgresEvalQueryReader.java:86-88` 单 JdbcClient 构造，加方法是本仓惯例）。detail 为 jsonb 原文字符串透传（白名单：detail 是事件自有载荷，非证据正文）。
2. `PostgresEvalQueryReader` 实现：`WHERE eval_run_id=? AND (entered_at, id) > (?, ?) ORDER BY entered_at, id LIMIT ?`；游标编码 `enteredAtIso|id`（KeysetCursor 同形）。
3. `EvalQueryService` 包装：run 不存在 → 404 语义（照 `:67-77` 惯例，先查 run 存在性）；返回 `{rows, nextCursor}`。
4. `EvalQueryController`（`eval/interfaces/EvalQueryController.java:45-174`，单参构造）加 `GET /runs/{runId}/events`：limit 超界 400、游标畸形 400、未知 run 404。

**测试**：standalone MockMvc + 内存 EvalQueryReader 桩——正常页/末页 nextCursor null/游标续页/limit=0 与 limit=201 钳制或 400（按所照模板语义二选一并断言）/未知 run 404/畸形游标 400。端口级排序与键集边界用内存桩模拟（真 SQL 语义留 195 窗 NOT_RUN 标注）。

**禁区**：不读 model_call_ledger/rca_model_call（RV08 红线）；不改 V80 表；不加 seq 列。

---

## 五、A4：Run 详情字段补全（claims 投影 + task name + 前端分组微调）

**缺口**（已验证 `alert/application/RunQueryService.java:116-175` detail()）：
- out map 仅 4 键 run/tasks/edges/usage（:168-173）——**无 claims 键** → 前端 claims tab 恒空（`RunDetailView.vue:604` `detail.value?.claims ?? []`）。
- task 行无 `name` 键（:144-158）→ 前端 `:703` `t.name ?? t.id` 恒兜底。rca_task 表无 name 列（V7），**裁定：name = taskKey 显式给键消歧义，不加列**。

**前端消费字段（已亲读模板 :46-66、:248-263、:820-838）**：行级 `c.id / c.text / c.code / c.verdict / c.current / c.kind`；展开 `c.evidences[]`（e.id/e.desc/e.digest/e.window）；`:253` 的 `c.agree` 是 mock 遗留无源字段。

**ClaimRow 扩 kind**：rca_claim 表已有 `kind` 列（V37:15-21，nullable，四值 SYMPTOM/HYPOTHESIS/ROOT_CAUSE/EXCLUSION），但 `ClaimStore.ClaimRow`（:49-65）未投影。**实施：ClaimRow record 末位加 `@Nullable ClaimKind kind` 组件**，波及 5 处构造点全部补参（已清点）：
- `PostgresClaimStore.java:210`（SQL SELECT 加 kind 列 + 映射，null 如实）
- `HolmesShadowWorkerTest.java:230`
- `ClaimProjectionTest.java:96`
- `NativeInvestigationExecutorTest.java:775`
- `EngineComparisonRecorderTest.java:246`

**claims 行映射（写死）**：

| 前端键 | 源 | 说明 |
|---|---|---|
| id | `id.toString()` | — |
| kind | `kind == null ? null : kind.name()` | 旧行 null 如实 |
| text | `reason` | 人读判定理由 |
| code | `claimKey` | 结构化键 |
| verdict | `status.name()` | TRUE/FALSE/UNKNOWN |
| current | `lifecycle == ACTIVE` | — |
| evidences | `evidenceRefs.stream().map(r -> Map.of("id", r))` | 首版只给 id（ref 原文）；desc/digest/window 无源不给键 |
| ~~agree~~ | **不给键** | mock 遗留，前端同步删渲染 |

排序：claimKey 字典序（与 A1 一致）。

**RunQueryService 实施**：构造加 `ClaimStore`（装配留 §六收口）；detail() out map 加 `claims` 键 + task 行加 `row.put("name", t.taskKey())`。

**前端微调（RunDetailView.vue，三处，写死 diff 点）**：
1. `:824-827` 分组逻辑：按四值精确分组——`SYMPTOM`→evidence 组、`HYPOTHESIS`→hypothesis 组、`ROOT_CAUSE`/`EXCLUSION`→conclusion 组、null/未知→conclusion 组（保旧兜底语义）。删除 `/evidence|证据/i` 正则（mock 时代中英混排匹配，真值四枚举精确匹配即可）。
2. `:836` currentConclusion：`kind === 'ROOT_CAUSE' && c.current`（EXCLUSION 不得成为当前结论）。
3. `:253` 删 `｜ {{ c.agree }}` 段（无源字段不渲染）。
其余（pendingHypotheses :838、展开 evidences 渲染 :257-262）语义已兼容，不动。

**测试**：RunQueryServiceTest（内存 ClaimStore 桩）——claims 键存在性、五字段映射逐键断言、kind null 如实、current 由 lifecycle 推导、evidences 只含 id、task 行 name=taskKey、无 claim run → 空数组非 null。ClaimRow 扩 kind 后 4 处假件补参即编译绿（无需新断言）。前端无测试设施，npm build 绿即过。

**禁区**：不动 rca_claim 表/V37 迁移；不动 ClaimStore 写路径（append/markUnresolved）；不加 rca_task.name 列。

---

## 六、装配收口步骤（主会话执行，agent 不做）

待 A1/A4 代码合入且 `PersistenceConfig.java` 上他人 EN-08 二期改动已提交（`git status` 干净）后：

1. `PersistenceConfig.java:502-507` OperatorQueryService bean：构造加 `evidenceRepository, claimStore` 两参（同文件 :361-365 EvidenceRepository bean、:380-385 ClaimStore bean 已有，直接引用）。
2. `PersistenceConfig.java:517-525` RunQueryService bean：构造加 `claimStore` 一参。
3. 若收口时文件仍被他人占用：先提交 A 批服务层（编译绿不破），装配改动等对方提交后由主会话补一个小提交。
4. 收口后跑全量 `mvn -q -pl control-app test`（基线 1743 例）+ `npm run build`，双绿后 `git push origin feat/a-batch-readfaces:main` + `git update-ref refs/heads/main feat/a-batch-readfaces`，PROGRESS 落账一行。

## 七、DoD（每张卡）

- [ ] scoped `mvn -q -pl control-app test -Dtest=<相关测试>` 绿（新测试必须写）
- [ ] 字段映射与本拆解 §二/§五 表格逐键一致（禁自创键名）
- [ ] 白名单自查：响应投影无 canonicalPayload/raw_text 键
- [ ] `git status --porcelain` 只含自己名下文件；PersistenceConfig.java 零触碰（A1/A4）
- [ ] 偏差与 NOT_RUN 项在交付报告逐条列明
- [ ] 收口后全量 1743+新增例 0 败 0 错 + npm build 绿（主会话验证）
