# 告警 AM6 逐步替换 HolmesGPT —— 落码技术方案（执行者用）v1.1 G1 复审稿

> 定位：AM6 编码的**执行施工图**。只细化、不翻案——凡与上游冲突，以上游为准并回本方案登记差异（C 表）。
> 上游依据（按优先级）：
> 1. `docs/告警AM6-技术方案.md` **v1.1**（2026-09-08，**G1 审查退回修订、待复审——签署前本方案不生效、禁止编码/生产放量**）；
> 2. `docs/告警Agent-增量实现任务拆解-v1.md` **§9**——M6-01~07 任务编号/依赖/验收的唯一权威，本方案不新增编号；
> 3. `docs/架构设计-告警Agent-v1.2.md` FUT-05/06/12/16/39/42/49/51 + §10 五分支 + §16 升级条件；
> 4. 调研证据 E-20（`docs/告警-调研-M6渐进发布与引擎退场-v1.md`，9 对象源码级；E-19 已由前端 UI 调研占用）；
> 5. 格式模板：`docs/告警AM5-落码技术方案.md` v1.0。
> 环境锚点：本机无 Docker，`*IT` 自动跳过且**不计入完成证据**；真栈证据归 195（**动工硬前提=M5-22 G2 签署**；195 栈已升 main HEAD、V20~V29 已 apply，环境面已就绪）。每个任务开工前先核对 HEAD 与迁移目录实际最大号（2026-09-08 实测=V29）。
> 纪律顺延：TDD 红绿留证；PROGRESS 只增不删、每任务即时更新；BUGLOG 接 **BA-50** 之后只增不删；commit 前 `git diff --cached --name-status` 与落点清单**逐文件**对拍；新增仓储/装配交付必须含"接口名 grep main 树有生产者"自查。

---

## 迁移编号段分配表（V30~V33，开工重冻结）

AM4 占 V12~V19、AM5 占 V20~V29（2026-09-08 实测迁移目录最大值 = `V29__am5_retention_archive.sql`）。**AM6 自 V30 起，一迁移一任务；已发布迁移不得追加（INV-AM5-10 顺延适用）**。

| 迁移号 | 文件名【新增】 | 任务 | 内容 | 来源 |
|---|---|---|---|---|
| V30 | `V30__am6_canary_window_verdict.sql` | M6-01 | `canary_evidence_sample` + `canary_window_verdict`（生产 provenance、稳定候选身份、证据分级、分层原始计数）~~+ 重建活跃索引补回 `REPORTING`（BA-40）~~【2026-09-08 对账：BA-40 已由 AM5 部署段就地修复关闭（14edd86①，195 实证），V30 不再承担索引修复，号段完整留给 canary 证据表】 | 【v1.1 G1 修正；v1.1r1 对账】 |
| V31 | `V31__am6_engine_comparison.sql` | M6-02 | `engine_comparison`（引擎对照结论表，insert-only；M6-05 复用） | 【落码细化新增——方案 §3.3】 |
| V32 | `V32__am6_run_fallback.sql` | M6-04 | `run_fallback` 恰一次栅栏 + `report_generation_winner` 单一发布赢家 | 【v1.1 G1 修正】 |
| V33 | `V33__am6_engine_shadow_execution.sql` | M6-05 | 可租约恢复的 Holmes Shadow 工作表 | 【v1.1 G1 修正】 |
| —（不占号） | — | M6-06/07 | 退场决策记录入文档/证据包；engine check 不收紧，历史可读 | 方案 §4.4 |

### V30 DDL 要点（`canary_window_verdict`）

```sql
create table canary_evidence_sample (
    id               bigserial primary key,
    run_id           uuid not null unique references rca_run(id),
    incident_id      uuid not null,
    stickiness_key   text not null,
    evidence_class   varchar(16) not null,
    provenance_json  jsonb not null,  -- inbox/tenant/test-marker/采集器版本；LIVE 必须可追到生产事实
    observed_json    jsonb not null,  -- 原始指标，不预聚合覆盖
    created_at       timestamptz not null,
    constraint ck_ces_class check (evidence_class in ('LIVE_CANARY','DRILL','REPLAY'))
);
create table canary_window_verdict (
    id              bigserial primary key,
    rollout_id      uuid        not null,
    candidate_digest char(64)   not null,            -- 行为候选；不含 rollout percent
    rollout_policy_digest char(64) not null,          -- 阈值/窗口/比例策略
    capability_digest char(64)  not null,             -- 实际装配能力指纹
    from_percent    int         not null,
    to_percent      int         not null,
    window_seq      int         not null,
    window_start    timestamptz not null,
    window_end      timestamptz,
    evidence_class  varchar(16) not null,             -- LIVE_CANARY / DRILL / REPLAY
    eligible_incidents int      not null default 0,   -- 按 incident/stickiness 聚类后的独立样本
    raw_counts      jsonb       not null,             -- 分子/分母/排除原因/缺数
    strata_json     jsonb       not null,             -- tenant/severity/scenario 覆盖
    control_json    jsonb       not null,             -- 同时段 Holmes cohort
    absolute_slo_json jsonb     not null,
    critical_pass   boolean,
    scored_json     jsonb,
    verdict         varchar(16) not null,             -- PASS / FAIL / INCONCLUSIVE
    evidence_refs   jsonb       not null default '[]',
    evaluated_at    timestamptz not null default now(),
    constraint ck_cwv_class check (evidence_class in ('LIVE_CANARY','DRILL','REPLAY')),
    constraint ck_cwv_verdict check (verdict in ('PASS','FAIL','INCONCLUSIVE')),
    constraint uq_cwv_window unique
      (rollout_id,candidate_digest,rollout_policy_digest,capability_digest,
       from_percent,to_percent,window_seq,evidence_class)
);
drop index uq_rca_run_active_incident;
create unique index uq_rca_run_active_incident
  on rca_run(incident_id,engine) where state in ('QUEUED','RUNNING','REPORTING');
-- 授权纪律同 V25：grant select, insert；revoke update, delete（insert-only 证据）
```

### V31 DDL 要点（`engine_comparison`）

```sql
create table engine_comparison (
    id               bigserial primary key,
    native_run_id    uuid        not null,
    comparison_key   char(64)    not null,            -- 两侧执行身份+snapshot+候选 digest 的 canonical hash
    shadow_exec_ref  text        not null,            -- M6-02 AM4 shadow 或 M6-05 V33 work 的审计引用
    snapshot_digest  char(64)    not null,            -- 同一冻结快照（FUT-06）
    holmes_outcome   jsonb,                           -- Holmes 侧结论摘要（脱敏）
    native_outcome   jsonb,
    disagree_flags   jsonb       not null default '[]', -- 六维差异标记；无 GT 只记 disagreement 不判对错（架构 :736）
    noise_baseline   jsonb,                           -- Holmes 自比对底噪引用（E-20）
    cost_compare     jsonb,                           -- token/时长双侧
    created_at       timestamptz not null default now(),
    constraint uq_ec_pair unique (native_run_id, comparison_key)
);
-- grant select, insert；revoke update, delete
```

### V32/V33 DDL 必要约束

```sql
create table run_fallback (
  id bigserial primary key,
  source_native_run_id uuid not null unique references rca_run(id),
  target_holmes_run_id uuid unique references rca_run(id),
  incident_id uuid not null,
  generation int not null,
  reason varchar(32) not null,
  depth int not null check (depth = 1),
  state varchar(16) not null check (state in ('RESERVED','CAST','SUCCEEDED','FAILED')),
  created_at timestamptz not null
);
create table report_generation_winner (
  incident_id uuid not null,
  generation int not null,
  winner_run_id uuid not null unique references rca_run(id),
  created_at timestamptz not null,
  primary key (incident_id,generation)
);
create table engine_shadow_execution (
  id bigserial primary key,
  shadow_key char(64) not null unique,
  native_run_id uuid not null references rca_run(id),
  snapshot_digest char(64) not null,
  holmes_candidate_digest char(64) not null,
  state varchar(24) not null,
  attempt int not null default 0,
  lease_owner text,
  lease_until timestamptz,
  budget_reservation_id text,
  created_at timestamptz not null,
  finished_at timestamptz
);
alter table engine_comparison add column shadow_execution_id bigint
  references engine_shadow_execution(id); -- M6-02 既有 AM4 shadow 记录允许 null；M6-05 新记录必须非 null（服务层+契约测试）
-- 三表按职责授予最小权限；状态迁移走 CAS，禁止业务角色裸 UPDATE/DELETE。
```

---

## M6-01 1% Native Canary（执行面接线，本里程碑最大件）

### 前置子项（不占编号，M6-01 完成面的一部分）

1. ~~**BA-39 修复**……验收 = docker profile 全装配真启动冒烟~~【已关闭 9ebe952，195 真启动实证】；保留其**验收要求**作为 M6-01 自身装配面的冒烟门：docker profile 全装配真启动（不止编译绿）。
2. ~~**BA-40 修复**：V30 重建 V25 活跃索引……~~【已关闭 14edd86①：修复时 V25 未在任何已部署库执行，按 INV-AM5-10 就地修复，195 真 PG 实证；V30 不再承担索引修复】；保留其**契约钉**作为回归守卫：活跃索引谓词含 REPORTING 的真 PG 并发契约测试已在 AM5 侧落位，M6-01 开工时复跑一次确认不衰减。
3. **生产身份硬门**：ConfigBundle activate/rollback 的 operator 必须来自认证 principal，禁止信任可伪造请求头；未具备时只允许 `evidence_class=DRILL`，禁止 LIVE_CANARY。
4. **O-3 裁定落码（C-61）**：Shadow 与 Canary 互斥规则——已有 NATIVE 路由 run 的 incident 不再起 Native 影子；HOLMES 路由 run 的影子继续供对照数据。
5. **M5-22 G2 签署确认**：未签署不动工（拆解禁止并行条款）。

### 落点清单（提交前逐文件对拍）

| # | 文件 | 动作 | 要点 |
|---|---|---|---|
| 1 | `alert/domain/model/RcaTask.java` | 改 | 新增常量 `NATIVE_INVESTIGATE`（与 HOLMES_INVESTIGATE 并列；V7 "本期唯一值"注释同步修订） |
| 2 | `alert/application/IncidentProjector.java` | 改 | `castRunAndTask`（:285-294）按 `routing.engine()` 选 taskKey |
| 3 | `alert/application/RcaRunOrchestrator.java` | 改 | 同名铸造点（:280-294）同步分支 |
| 4 | `alert/application/RcaWorker.java` | 改 | claim 后读 run routing → `Map<RcaEngine, RcaTaskExecutor>` 分派；未知 engine fail-closed（任务 DEAD + 事件，不抛穿 worker 循环） |
| 5 | `infrastructure/native/NativeInvestigationExecutor.java` | 新 | 实现 `RcaTaskExecutor`：驱动 Supervisor（提案=active bundle `native.proposal` 段，缺失/非法 fail-closed）→ 三 Agent 执行（工具面=ReadOnlyToolFace）→ freezeSnapshot → advance → NativeRcaAgent → ReportAssembler → NativeReportAdapter → 复用 `RcaRunOrchestrator.finishTask` 收尾链落报告/发布 |
| 6 | `alert/application/NativeReportAdapter.java` | 新 | AssembledReport→RcaReport：payload 标 `engine=NATIVE`+claim 引用+三态映射 validation_status；复用 `ReportCompletedNotifier` 出口 |
| 7 | `application/replay/ShadowToolFace.java` → `ReadOnlyToolFace` | 改（行为保持重构） | REDTEAM 双闸从结构强制降为策略开关（bundle `native.toolFace.redteamOnly`，canary 期 false）；R0/R1 裁剪/独立池/限流/预算门不变；原 UT 不改一字全绿为验收（M4-28 惯例） |
| 8 | `release/application/CanaryWindowEvaluator.java` + `domain/repository/CanaryWindowVerdictRepository` + `infrastructure/persistence/PostgresCanaryWindowVerdictRepository.java` | 新 | 四 digest 建窗；LIVE/DRILL/REPLAY 强类型；incident 聚类、分层完整率、同时段 control + 绝对 SLO、连续 K 窗；K/最小样本/墙钟值经基线 spike 后版本化，禁止硬编码 50/72h |
| 9 | `release/interfaces/CanaryStatusController.java` | 新 | `GET /api/canary/status` 只读（C-64 登记：拆解无此编号，参照 O-2 先例作为 M6-01 验收观察面落码，G1 评审追认） |
| 10 | `infrastructure/config/AlertFlowConfig.java` + `NativeCapabilityProbe.java` | 改/新 | Executor 映射表显式构造；依据实际 bean/外部依赖生成 capability digest；percent>0 但 capability 不完整时 NATIVE_DEFERRED；**不新增 nativeReady 热开关** |
| 11 | `resources/db/migration/V30__am6_canary_window_verdict.sql` | 新 | 见上文 DDL 要点 |
| 12 | `PersistenceConfig.java` | 改 | BA-39 @Bean + 新仓储 @Bean（对照既有 45 个的写法惯例） |

### 测试清单

- UT：分派/任务键/提案/报告；CapabilityProbe 缺件；Evaluator 的 evidence class、四 digest、聚类/分层/缺数、双门、连续 K 窗与作废矩阵；ReadOnlyToolFace 原回归全绿。
- 契约：V30 约束/幂等/insert-only；测试适配器不能构造 LIVE；活跃索引谓词含 REPORTING。
- IT（真 PG，195）：Native 全链；REPORTING 期间并发铸造被索引拒绝；DRILL 全链即使指标全优也无 promotion eligibility。
- E2E-AM6-00/01 脚本（195）：见技术方案 §11 L4。
- 静态：ArchUnit——`infrastructure/native` 不依赖 `infrastructure/holmes`；release 域零框架。

## M6-02 10% Native Canary（观察面成账）

| 文件 | 动作 | 要点 |
|---|---|---|
| `V31__am6_engine_comparison.sql` | 新 | 见上文 DDL 要点 |
| `release/application/EngineComparisonRecorder.java` + 仓储 | 新 | 双侧 outcome 归一化落表；disagree_flags 六维标记；无 GT 不判对错 |
| `infrastructure/observability/AlertMetrics.java` | 改 | engine 维度指标补齐；指标个位数；同窗同时保存 contemporaneous control 与 absolute SLO（E-20） |
| Am4ShadowTrigger 守卫 | 改 | C-61 互斥规则的 HOLMES 侧：影子结论接 EngineComparisonRecorder |

放量动作本身=发布 percent=10 的 bundle（零代码）；验收含预算/延迟/错误率/人工分歧四维台账 SQL 固化为 `deploy/policy/` 下只读查询脚本。

## M6-03 50% Native Canary（容量与演练，零新服务）

- 容量报告脚本：Native 实跑预算/P95/内存，与 Holmes 同时段同分布 control 对比并检查双方绝对 SLO（E-20，禁 before/after）。
- HOST1 三档内存闸演练脚本：按架构 :946-953 逐档触发并记录系统行为（<1.2GiB 停影子、<768MiB 停领取、<512MiB readiness fail）。
- 回退演练：激活 percent=0 bundle → 新 run 全 Holmes + 在途 NATIVE run digest 不变（断言 SQL 随件）。
- 无新迁移、无新类；产出=演练证据包。

## M6-04 Native Primary（fallback 链）

| 文件 | 动作 | 要点 |
|---|---|---|
| `V32__am6_run_fallback.sql` + 仓储/服务 | 新 | `source_native_run_id` 唯一占位后同事务铸 Holmes；depth=1；独立 fallback 预算；`report_generation_winner` CAS 决定唯一发布者；事件 `fallback_of` 只是审计副本 |
| `alert/application/RcaRunOrchestrator.java` | 改 | 仅安全/运行封闭类进入 FallbackService；取消/过期/人工终止/UNRESOLVED/低质量不触发；所有报告发布先争夺 generation winner |
| `release/application/CanaryRouter.java` | 不改 | percent=100 即默认 Native，已有通道 |
| 演练脚本 | 新 | 20 路并发+进程重启只铸一个 fallback；Native 已部分收尾与 Holmes 竞争仍只一份报告/每渠道一条通知；深度 2 被拒；一键切回计时 |

## M6-05 Holmes 只读对照期（反向影子）

| 文件 | 动作 | 要点 |
|---|---|---|
| `V33__am6_engine_shadow_execution.sql` + `HolmesShadowScheduler/Worker` | 新 | 抽样命中后以确定性 shadow_key 入库；SKIP LOCKED/租约/CAS/有界重试/预算预留；不铸生产 run、不调 finishTask；成功才恰一次落 V31 |
| 底噪校准程序 | 新（复用持久工作面） | 同 snapshot digest 下 Holmes control-vs-control，底噪结论落 V31；差异−底噪之外仍查绝对 SLO |
| 差异报告模板 | 新（`docs/` 模板） | 成本收益对照 + disagreement 分类台账；结论供 M6-06 |

ArchUnit 断言：Sampler 类无 reports/publication/outbox 引用（INV-AM6-5）。

## M6-06 Holmes 退场决策（证据包，无新服务）

- `scripts/m6-dependency-scan.sh` 六面扫描：①代码引用（`grep -ri holmes --include='*.java' main 树`）；②compose 服务/network/volume；③密钥与 env 键（`APP_ALERT_HOLMES_*`、holmesgpt 容器 env——只列键名不打印值）；④监控仪表盘/告警规则中的 holmes 维度；⑤灾备/恢复脚本的 holmes 依赖；⑥文档锚点。每项=依赖清单+处置裁定（删/留/豁免留痕）。
- drain barrier：Holmes 非终态 run/task、V33 shadow work、重试/恢复队列必须全为 0；不满足则 M6-07 fail-closed。
- 恢复演练：备份恢复模式在无 holmesgpt 容器下跑通；旧路径制品恢复另做实跑并记录 RTO/RPO、审批步骤和制品保留期。
- 回滚制品：git tag `pre-holmes-removal` + holmesgpt 镜像 digest 封存 + 旧 compose 文件归档 `backups/`。
- 产出 `docs/告警AM6-Holmes退场决策记录.md`（含 V30~V33 晋升/对照/fallback/shadow 全量证据引用）。
- 复核 V30~V33 retention/legal-hold 策略是否与 M6-01 冻结值一致，不得在此时首次决定。

## M6-07 Holmes 生产路径下线

- compose 移除 holmesgpt 服务/卷/network 段（litellm 保留，C-63）；`AlertFlowConfig` 摘除 `holmesClient`/`holmesInvestigationExecutor` bean 与铸造点 HOLMES 分支；`RcaEngine.HOLMES` 保留标注 deprecated（历史读面，C-62）。
- 密钥回收 + 全库密钥扫描零命中（键名列清单）。
- 历史报告回放套件 + 审计查询全绿；全量回归无跳过；制品恢复路径在目标环境按 RTO/RPO 实跑。此阶段不再宣称“一键回切”。
- AM7 前端 engine 展示锚点（wireframes :467）不受影响——native 标签照常。

---

## C 表（落码裁定登记）

| 编号 | 裁定 | 理由 |
|---|---|---|
| C-61 | Shadow/Canary 互斥：incident 有 NATIVE 路由 run 即停 Native 影子；HOLMES run 影子继续供 V31 | O-3 落码裁定；同引擎双跑无对照价值且费预算 |
| C-62 | `rca_run.engine` check 不收紧、枚举不删 | 历史行恒 HOLMES；PG check 全表约束，收紧破 FUT-16 |
| C-63 | litellm 不属于 Holmes 退场范围 | Native 模型调用同样经 litellm；INV-AM6-8 |
| C-64 | `GET /api/canary/status` 归 M6-01 落码 | 拆解无编号；参照 O-2 先例登记待 G1 追认（M6-01 验收需要可查询证据面） |
| C-65 | Holmes Shadow 不铸生产 run，但必须走 V33 持久 work，不采用一次性 runner | 零发布与可恢复性必须同时满足；Am4ShadowTrigger 只适合 E2E 触发，不是生产调度器 |
| C-66 | DRILL/REPLAY/TEST/HOLDOUT/SHADOW/FALLBACK 永不进入 LIVE_CANARY 晋升窗 | Google SRE 真实流量准则 + Argo dry-run 隔离；防测试造量“刷绿” |
| C-67 | 不新增 `nativeReady` 热开关；部署 capability 指纹 + ConfigBundle percent 分责 | 消除双事实源与伪原子切换 |
| C-68 | M6-04 必须占 V32，fallback 事件不能承担幂等；增加 generation 发布赢家 | 并发/重启与 Native 部分收尾下防双铸双发 |
| C-69 | M6-07 后回退语义改为制品恢复 SLA | 物理删除后即时回切客观不存在；Strangler Fig 官方风险边界 |

## O 表（开放项，G1 评审裁定或后续阶段承接）

| 编号 | 开放项 | 建议归属 |
|---|---|---|
| O-61 | 完整 RBAC/用户体系（O-4 顺延） | 可归 AM7；但 M6 live 前“认证 principal 不可伪造 + activate 审计”是硬前提，不能顺延 |
| O-62 | PROVISIONAL 对账积压监控面板（AM4 遗留归 AM6 的 P-63） | M6-03 容量报告附带台账，面板归 AM7 |
| O-63 | K/最小独立 incident/墙钟/分层阈值 | 用历史到达率与 cluster bootstrap 功效 spike 后在 M6-01 live 前冻结，不预设 50/72h 即正确 |
| O-64 | V30~V33 retention/legal-hold | 建表前随 M6-01 冻结，M6-06 只复核 |
| O-65 | R2/R3 与 OPA/审批态 | 继续延期；Native Primary 稳定后单独里程碑评审 |
| O-66 | Gatus 栈部署：`GATUS_ONCALL_WEBHOOK_URL` 值班通道收口地址 + 网络面裁定（Gatus 容器对 127.0.0.1 绑定的 control 不可达，放宽发布面或 host 网络） | 【AM5 G2 附条件转入，2026-09-08 用户裁定】M6 期内随运维面处置；阻塞 E2E-AM5 场景补跑 |
| O-67 | `AM5_LLM_BUDGET_CAP` 设值（真栈 LLM 花费护栏，未设时 preflight 拦 runall 属正确契约行为） | 【AM5 G2 附条件转入】用户裁定面；M6-01 live 前必须落值（真流量=真花费） |
| O-68 | RCA-100 授权核查 + E2E-AM5 十一场景脚本骨架填实（依赖面已齐） | 【AM5 G2 附条件转入】授权归用户/法务面；场景填实归 M6 期测试工序排期 |

## 附：执行纪律（顺延 AM5 落码方案附录）

1. E2E 批次产证七件套（suite-manifest/assertions.tsv+json/commands.log/sql/raw/sha256sums）顺延；批次统一 UTC 批次号入 `docs/测试证据/AM6/<task-id>/`。
2. 195 使用纪律：先备份后变更；传输回读验证（BA-34⑧）；外部依赖寿命窗口≥批次时长对账（BA-38）。
3. 换模型/改默认值 → 同 commit 更新钉值测试（BA-35）；新增仓储 → grep 接口名有生产者（BA-39）；commit 前逐文件对拍（BA-36）。
4. 功能 E2E 全部标 DRILL，只证机制；真实晋升证据由长窗采集器生成，必须可追溯到生产 incident，禁止脚本补数。
5. 本方案 v1.1 为 G1 复审稿；C-66~69 未闭环前禁止编码/生产放量。【2026-09-08 对账：BA-40 已闭环（AM5 部署段 14edd86① 就地修复，195 实证）】
