# CL/OP/OR 完成台账 v1（总方案 §18）

2026-09-13 初始化（B0 事实冻结批）。记录格式（总方案 §18）：
`owner | current_impl | target_behavior | commit/patch/image | migration | config/release | tests | scope | evidence | rollback | status | blocker | next_trigger`

状态枚举：UNVERIFIED、NEEDS_CHANGE、IMPLEMENTED_NOT_VERIFIED、VERIFIED_LOCAL、VERIFIED_TARGET、BLOCKED、DEFERRED。

**适用构建**：195 运行面＝2026-09-13 快照构建（jar md5 8bb553e9f31d…，无 git 身份——见 §6-G）；CL/OP 批代码在本地工作树**未提交**（commit 列标 WORKTREE），其 195 IT/Smoke 证据对应该快照。后续版本改变相关契约自动失效重验。

---

## 一、CL 卡（正确性）

| 卡 | owner | current_impl→target | commit | migration | tests | evidence | status | blocker / next_trigger |
|---|---|---|---|---|---|---|---|---|
| CL-01/02 提交围栏+恢复接线 | CL批执行者 | 围栏已落：PrimaryCheckpointCommitService 四身份校验（owner/leaseEpoch/configEpoch/revision）+REPLAYED 收敛，全部检查点写点收口单入口（BLRR/Supervisor/NIE 八处）→目标：迟到写拒绝、失租旧 writer 无效 | WORKTREE 未提交 | V99 已应用（RR01 flyway MATCH） | 本地 1809 全绿；195 真 PG PrimaryCheckpointCommitFenceIT 7/7 | OP批 PROGRESS 05:35；A13-03 复核（本批）| **VERIFIED_TARGET** | —；A13 复核结论：实现覆盖在位 |
| CL-03 有效证据投影 | CL批执行者 | ContextAssembler 类型前缀分派+logs 签名聚合/observations 有界投影+首末时间序解耦→目标：嵌套日志/指标原文关键内容入模 | WORKTREE 未提交 | —（无新表） | ContextAssemblerTest 19 本地绿 | A13-01 复核（本批 grep 定位）；本地测试记录 | **VERIFIED_LOCAL** | A13-01 验收残留"真实模型定位对照"→ 可复用路径一 run26 真模型链（next：MC01 重跑真件面） |
| CL-04 原文回读+输入身份 | CL批执行者 | 并入 CL-03 实施面（受控回读/单次成员快照/digest 分离）→目标：超长有用部分可回读、循环检测 digest 与实际 prompt digest 分离 | WORKTREE 未提交 | — | 随 ContextAssemblerTest | — | **IMPLEMENTED_NOT_VERIFIED** | B1 复核独立验收用例（MC 矩阵对位） |
| CL-05 Skill 持久绑定 | CL批执行者 | V100 rca_run_skill_binding PK(run,role,epoch) insert-if-absent 单写者+冻结允许集+SELECTED/NONE 显式+热切同事务预生成→目标：重启不漂移/发布不逃逸 | WORKTREE 未提交 | V100 已应用+运行表 9 行（RR01） | PostgresRunSkillBindingIT 3/3（195 真 PG） | A13-02 复核：SELECTED/NONE/insert-if-absent 实现在位 | **VERIFIED_TARGET** | — |
| CL-06 累计记忆 | CL批执行者 | V101 schema_version/parent_memory_id/ofV2 真 revision+父链+跨轮反证并集保留→目标：反证跨轮可达、控制拒绝不混业务 ruled_out | WORKTREE 未提交 | V101 已应用 | ContextAssemblerTest/WorkingMemory 单测 | A13-04 复核：WorkingMemory/Port 在位 | **VERIFIED_LOCAL** | 真实跨轮 Run 验证留 B2 |
| CL-07 摘要控制（台账） | CL批执行者 | V102 compaction_attempt 台账（唯一键一胜一拒/状态封闭集/Mode 三态 OFF/SHADOW_GENERATE/CONSUME_VALIDATED）→目标：压缩尝试可审计、消费围栏化 | WORKTREE 未提交 | V102 已应用 | PostgresCompactionAttemptIT 4/4（195；BA-131/132 修复链） | op-it2.log | **VERIFIED_TARGET** | — |
| CL-08 最小消费面 | CL批执行者 | ContextSummaryPort+信封 validated_summary 受控槽；**消费默认关**→目标：摘要消费经证明后启用 | WORKTREE 未提交 | —（复用 V102） | 单测绿 | CL 批 PROGRESS「未完成清单②」 | **IMPLEMENTED_NOT_VERIFIED** | 原 blocker=模型工具调用可靠性（MC34）；**路径一 run26 已破工具面死门**→MC34 三臂对照可开窗 |
| CL-09 人工 curation 入口 | CL批执行者 | POST /api/skill-curations 模板提炼+§7.4 资格 digest 钉闸+LLM_CURATE 501 如实→目标：认证入口+幂等+候选不自动发布 | WORKTREE 未提交 | — | SkillCurationControllerTest 4；195 smoke 4/4 | A13-06 复核：控制器在位 | **VERIFIED_TARGET** | LLM 归纳=条件项（DEFERRED） |
| CL-10 用户可查页面 | — | RunDetailView 报告评价卡（OP-05 交付一部分）→目标：版本/摘要消费/状态卡点全可见 | 部分随 OP-05 | — | npm build 绿 | FO31/32 截图未采 | **NEEDS_CHANGE** | B4：页面按 CL-10 清单走查补全 |

## 二、OP 卡（质量与产品）

| 卡 | owner | current_impl→target | commit | tests | evidence | status | blocker / next_trigger |
|---|---|---|---|---|---|---|---|
| OP-01 案例准入 | OP批执行者 | V104 三态候选/审核 append-only+物化入不可变 DatasetVersion/CaseVersion | WORKTREE 未提交 | PostgresOpBatchIT 6/6（195） | smoke 物化双 replay；BA-129/130 修复链 | **VERIFIED_TARGET** | — |
| OP-02 质量口径 | OP批执行者 | QualitySummaryService 分母 DECIDABLE/率 null 诚实 | WORKTREE 未提交 | IT+单测 | GET /runs/{id}/quality 200 实数据（439f2cb2 10 例） | **VERIFIED_TARGET** | — |
| OP-03 动作分析 | OP批执行者 | V105 版本化派生台账（逻辑动作去重/六分类/uq 重入） | WORKTREE 未提交 | IT | GET /agent-ops/action-assessment 200（logicalActions=0 诚实空） | **VERIFIED_TARGET** | 有真实 Run 后回访数据面 |
| OP-04 报告反馈 | OP批执行者 | V103 append-only 四值裁决/幂等同载 409/supersedes | WORKTREE 未提交 | IT | smoke 201→200→409→列表 200 | **VERIFIED_TARGET** | — |
| OP-05 前端 | OP批执行者 | RunDetailView 报告评价卡（report.state 门控） | WORKTREE 未提交 | npm build 绿 | FO31/32 浏览器截图未采 | **VERIFIED_LOCAL** | 浏览器走查窗（MC33 排队同窗） |
| OP-06 动态调查深度 | — | 未实施（条件项） | — | — | — | **DEFERRED** | 触发证据：简单案例过度取证/复杂案例预算不足（路径一 run26 已证 4-5 调收敛） |
| OP-07 受控并行 | — | 未实施（条件项） | — | — | — | **DEFERRED** | 触发证据：延迟主要来自互不依赖外部等待 |
| OP-08 RAG 检索升级 | — | 未实施（条件项） | — | — | — | **DEFERRED** | 触发证据：确有召回遗漏（非阅读/推理失败） |
| OP-09 发布评价 | — | 未实施 | — | — | — | **UNVERIFIED** | B4 批；与 OR-11 发布资格联动 |

## 三、OR 卡（运维）

| 卡 | owner | current_impl→target | tests/evidence | status | blocker / next_trigger |
|---|---|---|---|---|---|
| OR-01 部署事实对齐 | **B0执行者（本批）** | deploy/audit-runtime.sh（只读/脱敏白名单/三模式/UNKNOWN≠MATCH 退出码）→runtime-manifest 对拍 | RR01：195 overall=UNKNOWN 无 DRIFT（flyway 80 版本 MATCH/资源限制 MATCH/Skill 绑定 MATCH；构建身份与 BUDGET_STEP 两项诚实 UNKNOWN） | **VERIFIED_TARGET（RR01）** | RR02（DRIFT 注入）/RR03（权限缺失）/RR04（双版本消费）留 B3；镜像嵌 commit 身份归 OR-11 |
| OR-02 外部探针值班链 | B0执行者（事实核对） | **HOST2 现场已核**：gatus（digest 锁定 a8c53f9e…，09-10 起 Up）+duty-adapter（Up 2d）+node-exporter 在 117.72.208.68 运行；/srv/alert-eval 目录树在 | 本批现场探测（只读） | **CONFIGURED（深核待 B3）** | RR05~08；duty snapshot 502 瞬态（见 §6-H）；告警链实测留授权窗 |
| OR-03 备份恢复 | — | backup.sh/restore.sh 已存在（pg_dump+指纹+TOC）→扩行为验证 | 未实测 | **UNVERIFIED** | B3：RR09~12（恢复到隔离库/RPO-RTO 实测） |
| OR-04 身份凭据审计 | — | SecurityConfig 角色/CSRF 在 | 未实测 | **UNVERIFIED** | B1/B3：RR13~16 |
| OR-05 外部输入与边界 | — | — | 未实测 | **UNVERIFIED** | B1/B3：RR17~20（SSE/MCP 面复测） |
| OR-06 依赖故障有界 | — | — | 未实测 | **UNVERIFIED** | B2：RR21~24 |
| OR-07 入口积压通知 | — | BA-126/127 已立案（影子收口/Run 对账）归本卡+CL-02 | 未实测 | **UNVERIFIED** | B2：RR25~28；SR01~12 用例集 |
| OR-08 资源长稳 | — | 容器限制已核生效（RR01） | 长稳窗未跑 | **UNVERIFIED** | B3：RR29~32（24~72h 窗） |
| OR-09 可观测就绪 | — | StartupSelfCheck/ControlSelfCheck 在 | 未实测；**195 主机 timedatectl 不可用：时区/NTP=UNKNOWN**（登记） | **UNVERIFIED** | B3：RR33~36；时钟面用 chronyc/NTP 源直查补核 |
| OR-10 故障演练页 | — | Drill 页面/服务在（BA-114 等链） | 未实测 | **UNVERIFIED** | B3：RR37~40 |
| OR-11 构建供应链发布 | — | **两缺口已实证**：195 构建树无 git 身份（NO_GIT）+镜像无 revision 标签（RR01 UNKNOWN×1） | RR01 | **UNVERIFIED（缺口已立案）** | B3：RR41~44；构建来源绑定=镜像标签+manifest 对拍闭环 |
| OR-12 运行手册交接 | — | 待建 runbook | — | **UNVERIFIED** | B3：RR45~48 |

## 四、RR 验收矩阵状态

RR01 **VERIFIED_TARGET**（本批）；RR02~48 PENDING（B1~B3 批按归属执行；与 MC/FO/EN 重叠处复用同一证据不加计）。

## 五、A13 复核结论（B0）

| A13 | 对位卡 | 实现覆盖（本批 grep/文档定位） | 状态 |
|---|---|---|---|
| A13-01 证据投影丢失 | CL-03 | ContextAssembler 类型前缀分派/有界投影在位 | 已实现（真实模型对照残留→MC01 真件重跑） |
| A13-02 Skill 绑定仅进程内 | CL-05 | V100 SELECTED/NONE/insert-if-absent 在位+FenceIT | 已实现且 195 真 PG 验证 |
| A13-03 检查点无围栏 | CL-01/02 | PrimaryCheckpointCommitService 八处接线+V99 应用 | 已实现且 195 真 PG 验证 |
| A13-04 工作记忆跨轮语义 | CL-06 | V101 父链/反证并集在位 | 已实现（本地验证；真实跨轮 Run 留 B2） |
| A13-05 LLM 压缩仅候选 | CL-07/08 | mode 默认 OFF+消费围栏化 | 按裁定保持关闭（DEFERRED 有理由） |
| A13-06 Skill 提炼未接线 | CL-09 | SkillCurationController+195 smoke | 已实现（LLM 归纳 DEFERRED） |

## 六、B0 不确定项与事实登记

- **G. 构建身份双缺口（→OR-11）**：195 构建树为 tar 快照（无 .git→expect 记 NO_GIT）；运行镜像未嵌 org.opencontainers.image.revision（对拍 UNKNOWN）。发布资格链须补：构建注入 commit 标签+manifest 对拍闭环。
- **H. duty-adapter 快照拉取瞬态 502（→OR-02）**：2026-09-12T20:13~21:19Z 三次 DUTY_SNAPSHOT_PULL_FAILED（cached_version=1 苟活）；21:20Z 后零失败=自愈；时段与 195 部署窗（control-app 滚动）吻合。B3 值班链实测时复验。
- **I. 195 主机时钟面 UNKNOWN（→OR-09）**：CentOS 7 无 timedatectl；RR01 记 UNKNOWN。补核：chronyc tracking / ntpstat 直查。
- **J. release_asset 表 0 行（→OR-01 基线）**：当前运行环境无 ACTIVE release 资产（A0 e2e 的 bundle 生命周期属跑内发布）；后续对拍以采集时刻状态为准。
- **K. BUDGET_STEP 部署层未钉**：走应用内默认 8（与 8 步主预算一致，无功能漂移）；如需钉定加 compose 透传行即可。
- **HOST2 现场事实（修正过时假设，→OR-02）**：gatus（twinproduction/gatus digest a8c53f9e…，2026-09-10T09:08Z 起）+duty-adapter+node-exporter 均运行中；/srv/alert-eval 九目录树在；WireGuard 隧道 10.250.250.1↔.2 活跃（README 记录）。**"探针未部署"结论作废**。

## 七、台账维护纪律

每次更新提供证据时间与适用构建；新增工作只从三类来源（验收失败/观测瓶颈/用户新需求）进入并先归现有卡；状态变更必须带 evidence 指针；DEFERRED 必须带条件与理由（§18/总收口规则）。
