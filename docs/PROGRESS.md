# PROGRESS —— 项目进度总账（断点续做入口）

> 规则：每完成一个任务/工序立即更新本文件（见 skill `milestone-workflow` 第五节）。
> 接手者读这一份就够：当前状态 → 下一步动作 → 环境锚点。

## 当前状态（2026-08-31 更新）

**项目**：AI Code Review Agent（GitHub App 形态的 AI 代码评审 Agent，Java 21 + Spring Boot 3.x + Spring AI + PostgreSQL，DDD 分层）

**当前阶段**：M1 入口可信化 —— 工序 5（测试验证）进行中：首轮回流已处置（TB-03 已修复待回归，DP 复跑 77/0），**等执行方全量回归（A→B→C）**。M2 技术方案 v1.0 已按用户指示提前起草（文档先行，M1 G2 前不动一行实现代码）

**下一步动作**：① 执行方全量回归回流 → 核对 TB 全关闭 → 推送 GitHub（当场确认）→ M1 G2；② M2 方案 `docs/M2-技术方案.md` 待用户 G1 评审（M1 G2 之后送审）

## 六道工序状态（M0）

| 工序 | 状态 | 产物 |
|---|---|---|
| 1 任务拆解+技术方案 | ✅ done | `docs/M0-技术方案.md` |
| 2 G1 方案门（人工确认） | ✅ 2026-08-30 用户指示开工 | — |
| 3 最小模块编码 | 🔨 进行中 | 任务表见方案 §2（T01~T18）：**T01~T17 已完成**（329 单测 + 32 IT 全绿；T18 部署待做） |
| 4 部署验证 | pending | docker-compose 双容器 |
| 5 测试验证 | pending | 方案 §12 测试矩阵 L0–L5 |
| 6 G2 阶段门（人工确认） | pending | — |

## 已完成事项

- 架构冻结基线评审与三轮修订：`docs/架构冻结文档-v2.1-修订草案.md`（九态机→已被 v2.2 部分替代）、`docs/架构冻结文档-v2.2-修订.md`（五项裁定 + E1–E10，**建表以 v2.2 为准**）
- 开源先例核查：`docs/OSS-证据清单-v1.md`（Temporal / OpenHands / Kafka / K8s / Atlantis / BinAuthz / Airflow 等，含采纳与拒绝记录）
- 服务器清理（用户已确认执行并完成）：
  - 195（商城核心 146.56.195.225）：mall_R / mall_R_new / mall-admin-web / mall-app-web / mall-swarm / agent-harness 六目录 + 17 容器 + 项目镜像/卷/网络 + mall nginx 配置，全部清除；保留基础镜像、hotel 卷、既有备份
  - 117（mallAgent 117.72.208.68）：mall_agent_prod / mall_agent_boottest + 9 容器 + 项目镜像/卷，全部清除
  - 备份：服务器本地 `/opt/backups/20260830/`（两台均完好）+ 本机 `E:\KimiCode\backups\`（两台均已下载并 gzip 校验通过，195 为 11/11）
- 项目规范 skill：`.kimi-code/skills/milestone-workflow/SKILL.md`（`git init` 后生效）
- 资源配置：`.env`（真实 key 已由用户提供并写入，用户后续会自行更换）、`.env.example`（模板）、`.gitignore`（已排除 .env/backups/pem）

## 环境锚点

| 项 | 值 / 位置 |
|---|---|
| GitHub 项目仓库（本系统代码） | https://github.com/objwww/PR.git（测试通过后推送，见 skill 工序 5） |
| GitHub 评审目标仓库 | https://github.com/objwww/mall_R |
| 模型端点 | 见 `.env.example`（百炼兼容模式，qwen/deepseek） |
| 密钥注入 | 环境变量 `AGENT_MODEL_API_KEY`，本地 `.env`（gitignored） |
| 195 服务器 | `ssh -i ~/.ssh/id_ed25519 root@146.56.195.225`（部署目录 `/opt/projects/pr_agent`，INC-09 seccomp 限制见 BUGLOG） |
| 117 服务器 | `ssh -i ~/.ssh/hotel_deploy root@117.72.208.68` |
| 本机构建 | `export JAVA_HOME='E:\ProgramInstall\jdk21'` 后 `mvn -s maven-settings-aliyun.xml package`（mvn 默认 JDK 17，必须覆盖） |
| 本机无 docker | L2+ 集成测试在 195 上跑（maven 容器 + 挂 docker.sock） |
| 备份（本机） | `E:\KimiCode\backups\146.56.195.225\`（11/11 gzip 校验通过）、`E:\KimiCode\backups\117.72.208.68\`（已校验） |
| 备份（服务器） | 两台各 `/opt/backups/20260830/` |
| Bug 记录 | `docs/BUGLOG.md` |

## 时间线

- 2026-08-30：架构冻结文档评审 → v2.1/v2.2 修订冻结；四路开源证据核查完成；195/117 服务器备份+清理完成；M0 技术方案交付评审；milestone-workflow skill 建立；PROGRESS/BUGLOG 建账。
- 2026-08-30：M0 运行环境在 195（146.56.195.225）搭建完成并验证：Docker 26.1.4 + Compose v2.27.1 + Git 2.43 复用既有；新拉镜像 `eclipse-temurin:21-jre`（运行基座，冒烟 21.0.12 OK）、`maven:3.9-eclipse-temurin-21`（构建基座，冒烟 OK）；`postgres:16-alpine`（16.15）复用既有；部署目录 `/opt/projects/pr_agent` 已建。宿主机 JDK 17 不参与运行（应用全部容器化）。**待用户提供**：GitHub App 凭证（App ID/私钥/webhook secret）；webhook 公网入站端口 M0 不开（走 WireMock stub）。
- 2026-08-30：用户指示开工（G1 过门）。**T01 完成**：Maven 多模块骨架（shared-kernel/control-app/publisher-app，Boot 3.4.5 + Spring AI 1.0.0 + Java 21），双应用空跑启动验证通过，git init + origin=objwww/PR（未 commit/push）。**T02 完成**：V1 schema + V2 grants（本会话手写，冻结设计落点）在 195 的 postgres:16-alpine 上一次通过；12 条权限矩阵断言全 PASS（含 AFT-06 control 对 outbox 无 UPDATE、不可变 trigger 双层）。现场修 INC-08（roles 脚本列名 bug）；登记 INC-09（195 内核 3.10 seccomp 限制，T18 需处理）。`.kimi-code/` 不入库决策待定。
- 2026-08-30：**T03/T04 完成**（domain 实体/状态机/RevisionService/fence/依赖判定/SequenceAllocator/ExecutionLedger/Projector，116 测试）。**T06/T07 完成**（SafeTarExtractor 安全解包+CAS、ModelClient+Spring AI 适配器+预算闸，151 测试；INC-10 commons-compress isFile 语义坑已登记）。**T05/T08/T09 完成**（WebhookController 验签+IntakeService 异步派发+ReviewOrchestrator T1/T2 事务脚本+FindingMapper+ReviewAgentLoop+OutboxWriter+8 个 PG Repository，196 测试）。**T11/T12/T13 完成**（Publisher：OutboxClaimer/FencedPublicationExecutor/三 Handler/OutboxRecoveryScanner 三路扫描/GitHubWriteAdapter 唯一出口，fence 与依赖判定上移到 shared-kernel，264 测试）。**T10/T14 完成**（WorkItemWorker 领取→执行→心跳→T2 收尾→崩溃接管闭环、CredentialBroker 完整实现 JWT→收窄 scope token+TTL 缓存、只读 token 窄接口共享密钥守护，324 测试）。注：T10/T14 由中断遗留的 agent 实例与补验 agent 接力完成，代码经逐文件核对。
- 2026-08-30：**T15/T16 完成**：双应用启动自检（Control：写凭证 env 扫描 + `has_table_privilege` 断言 outbox 无 UPDATE 权；Publisher：非 root + 私钥只读 + 无模型 key + DB 权限矩阵；违规即拒绝启动）+ ArchUnit AFT-01/02s/03/04 全套（红绿验证留证）。**主会话复跑 `mvn clean test` 亲验：shared-kernel 98 + control 136 + publisher 95 = 329 全绿，BUILD SUCCESS**。注：T15/T16 期间检测到工作区有第二个写入方（docs 被并行更新，内容与主会话一致无冲突）——已留档，待用户确认是否有并行会话。
- 2026-08-30：**T17 完成**：§12 集成矩阵在 195 真跑（Testcontainers PG16 + WireMock + 双进程单 JVM harness）——L2 CT-01~07（CT-05 占位待 M5）、L3 ST-01~08、L4 EX-01~10 共 32 IT 全绿；本机 `mvn clean test` 329 单测全绿。集成暴露并修复 5 个生产缺陷（INC-11 V2 grants 列级授权偏差、INC-12 claim SQL 类型、INC-13 jsonb 绑定、INC-14 账本失败路径缺事件、INC-15 换届事件挂错流），全部有 IT 回归。工程修复：control 胖 jar 加 `classifier=exec`（T18 部署注意用 `*-exec.jar`）。已知 spec gap：T0 入口拒绝恶意 tar 时无 Run 可标失败（仅 webhook 登记），是否补 Run 级登记留 G2 评审。服务器构建现场 `/opt/build/pr`，m2 缓存在卷 `m2repo`。
- 2026-08-30：**T18 完成，M0 部署验证收官**：195 上 compose 五服务栈（postgres + migrate one-shot + control-app + publisher-app + github-stub）起栈，`deploy/smoke-test.sh` **DP-01~05 全 PASS（40/0）**，证据 `/opt/build/pr/deploy/smoke-evidence/20260830-195729/`。亮点：DP-05 走真实百炼 qwen-plus 完成评审闭环，stub journal 证实 check/review 各恰好 1 次（effectively-once 远端证据）。部署暴露并修复 INC-17（CAS 600 权限跨 uid 拒读，IT 盲区）、INC-18（compose secrets target 后缀）。本机最终回归 329 全绿。**栈保持运行**：control webhook `127.0.0.1:8080`、stub admin `127.0.0.1:19090`。剩余：推送 GitHub（当场确认）→ G2 阶段门。
- 2026-08-30：本地 git commit `353b688` 落盘（297 文件，+23630 行，含 docs/deploy/skill；密钥泄漏检查通过）。用户指示暂不 push。
- 2026-08-30：**真实凭证接入（M0 真实联调就绪）**：GitHub App `prreviewagent-my-app`（App ID 4770310）创建完成，安装于 objwww 全部仓库（installation_id=157714567）。私钥（GitHub 签发的 PKCS#1 已转 PKCS#8）0444 挂载 publisher（compose secrets 长语法）；webhook secret 已同步两侧 .env；control 8080 改公网发布（HMAC 守门实测：正确签名 202 / 错误 401 / 无签名 401），Webhook URL=`http://146.56.195.225:8080/webhooks/github`。凭证实测：JWT 认证 OK、installation token 铸造 OK、权限恰好 = checks:write/contents:read/metadata:read/pull_requests:write（F1-B 最小权限兑现）。栈已切真实模式（GITHUB_API_BASE=api.github.com），双应用自检通过。
- 2026-08-30：**真实凭证接入完成（真实联调就绪）**：GitHub App `4770310`（installation `157714567`，objwww 全仓库）私钥已部署到 195（compose secrets 只读挂载；GitHub 签发的 PKCS#1 已转 PKCS#8，原格式代码不接受）。webhook 端点公网开放 `http://146.56.195.225:8080/webhooks/github`（公网探测 401=验签守门正常；安全组无需额外操作）。双应用重启自检通过；**真实铸币链验证**：窄接口签发只读 token（ghs_…）→ 真实读取 objwww/mall_R（repo id 1318918285）成功。待用户在 mall_R 开真实 PR 跑真实闭环（App 的 Contents 是只读权限，无法代为创建分支/commit，PR 须用户手工开）。
- 2026-08-30：**真实 GitHub 联调完成 + INC-19 修复**：webhook secret 粘贴混入尾部空格导致 GitHub 投递全部 401（定界实验定位），已用 App JWT PATCH /app/hook/config 修正并重投 → 202 受理。首个真实闭环：mall_R PR#1 → T0 快照（7.3MB）→ 真实 qwen-plus → Check/Review 恰好一次落真实 GitHub。首轮暴露 INC-19（真实模型 6 条 finding 全被丢弃，dropped=6）：修复 = FindingMapper diff 前缀归一化 + 路径容忍 + 映射面扩为全量快照 + prompt 逐字引用契约 + MODEL_RESPONSE 落 CAS。真实回归两轮：各 6 findings、dropped=0、5 类埋点全抓到、行号被工程侧纠正。本机 339 单测全绿。安全备注：本地 stub 测试私钥已删除（防覆盖服务器真实 key）；App 私钥仅存于 195。观察项：finding.state 停在 PENDING（M0 只登记不逐条驱动，PUBLISHED 推进留 M1/G2 评审）；195→codeload tarball 约 3~6 分钟是每轮评审主要耗时。
- 2026-08-30：M0 推送 GitHub 完成（`objwww/PR` main 新建，commit `353b688`→`ae181a4` 共 4 笔，含 M0-源码导读.md）。用户确认进入 M1（G2 过门）。
- 2026-08-30：技能基建：安装 archify v2.16.0（用户级 `~/.kimi-code/skills/archify`，doctor 15 项全绿）+ karpathy-guidelines + mattpocock/skills 精选 8 个（code-review/diagnosing-bugs/domain-modeling/tdd/to-spec/implement/research/handoff）；agency-agents 与 checkstyle 用户决策不装。
- 2026-08-30：**M1 工序 1 完成（待 G1）**：`docs/M1-技术方案.md` v1.0 交付——范围 = P1 webhook_inbox 五态去重/半截重放 + P2 PrStateReconciler(control 只读对账) & DriftReconciler(publisher 只检测不修复) + P3 Draft 廉价预检（决策表 4.4）+ 收编 INC-16。任务拆解 M1-T01~T09；V3 迁移对冻结 §5.7 最小增补 3 列（updated_at/attempt_count/next_retry_at，理由入 §7）；新增不变量 I9~I13；测试新增 L0×3/L1×6/L2×4/L3×8/L4×5/DP×4。OSS 证据清单追加 F-1（GitHub 官方最佳实践，措辞已逐句核对：官方确认交付丢失+重投同 ID+10s 应答，未承诺顺序——乱序按网络事实防御）与 F-2（Idempotent Consumer 两段式）。**待用户 G1 确认后开工编码。**
- 2026-08-31：**M1 方案 v1.2（G1 二审处置）**：二审新增 BT-01~14 + E2E-09~24 共 30 条用例，逐条证据核查后处置——采纳 18、修正后采纳 4（E2E-09 policy 无 webhook 触发源落为 IT；E2E-10 暴露 v1.1 真缺口：reconciler 只比 head → 改 (head,base) 二元组；E2E-18 驳回 ACCESS_UNKNOWN 新状态名，按评审自己的三态裁决用 UNKNOWN+权限告警，依据新增证据 F-3：GitHub 官方用 404 替代 403 隐藏私有资源；E2E-20 机制校正：run_key 含 trigger_key 拦不住双源并发，真正防线是行锁 check-then-insert + 新增 review_run 部分唯一索引兜底）、现实化 2（E2E-14/15 降 IT、E2E-21 改 Clock 注入+DB now() 不变量 I17）。配套修正：payload_json 可空（E2E-22 畸形 JSON 落库审计）。BT 表作为 G2 演示验收别名不重复实现。**订立 `docs/测试交接规范.md`**（执行方不修复/不跳测/禁临时脚本；故障卡六要素回流；修复后全量回归）并回写 skill 工序5；M1 编码完成时将按此规范出具《M1 测试交接文档》。**待用户 G1 三审。**
- 2026-08-31：测试交接配套补齐：新增 `docs/测试交接-背景与服务器现状.md`（项目背景/M0-M1 进度/195 四容器栈+库名 pr_agent+密钥位置+内核 3.10 怪癖/117 与 mall_R 禁碰区/真实凭证成本警告/出问题自查顺序），`docs/测试交接规范.md` 第四节新增第 0 条——交接文档必须附此背景简报且出具前核对刷新。
- 2026-08-31：**M1 G1 过门（用户确认 v1.2）**，进入工序 3 编码。任务链 M1-T01→T09 按方案 §2 执行。
- 2026-08-31：**M1-T01 完成**：V3 迁移（webhook_inbox 六态+租约+raw/jsonb、pr_subject 三列、publication_resource 观测态迁移+巡检列、review_run 活跃世代部分唯一索引、授权矩阵）在 195 沙箱 postgres 容器（V1→V2→V3 顺序）一次通过；14 项权限/约束断言实测全对（publisher 对 inbox 零权限含 SELECT、control 无 resource UPDATE、publisher 仅观测列可 UPDATE 且仍无 outbox INSERT）。沙箱容器已销毁。注意：V3 不进运行中栈（观测态迁移会破坏 M0 代码的 ACTIVE 写入），随 M1 代码在 T09 统一部署。
- 2026-08-31：**M1-T02 完成**：inbox 领域件交付（InboxState 六态迁移表/WebhookInbox 模型/WebhookInboxRepository port/Postgres 实现 + PersistenceConfig 接线 + UT-11 + control 侧 IT 基座与 CT-12/13/15/17 用例）。主会话复跑亲验：`mvn -pl control-app -am test` 155 全绿（5 个 IT 因本机无 docker 正确跳过，留 195）。agent 两处合理偏差已记录：PROCESSING→IGNORED 迁移补全（ST-11/16 需要）、claim RETURNING 显式列清单（不拖回 payload_raw 大字段）。
- 2026-08-31：**M1-T03+T04 完成**：入口两段式（controller 瘦身=验签+落 inbox+202；重投六态应答含 DEAD_LETTER 不唤醒、异 digest 409；过滤解析后移）+ InboxProcessor 工作器（租约领取/六 action 路由/malformed 死信/指数退避/耗尽死信/三崩溃窗口防线注释）+ IntakeService 改纯执行段。主会话复跑亲验：170 全绿（5 IT 无 docker 跳过）。M0 行为变更四项已记录（畸形 JSON 400→202 留审计等，均有方案裁决背书）。**留档决策点**：EX-13 安全告警落账本被 V1 schema 挡（execution_event.review_run_id NOT NULL 而无 Run 可挂），本版落地为 409+结构化 WARN 日志，V4 是否加安全事件表待用户/冻结文档裁定（将记 §8 M1-P9）。
- 2026-08-31：**M1-T05+T06 完成**：StaleEventGuard 三值快筛 + GitHubPrMetadataPort/Adapter（可区分 404/403/429/5xx，尊重 Retry-After）+ PrEventAuthoritativeReader 七值决策树 + T-close/T-draft（同事务投影+epoch+1+Run SUPERSEDED）+ draft 廉价预检（零 T0/Run/Outbox）+ 水印 GREATEST 推进。主会话全 reactor 复跑亲验：98+226+95=419 全绿。核查结论：M0 epoch 语义本就 revision||policy 双条件递增，E2E-09 无偏差。偏差留档：404 补 sanity 读层（方案 EX-17 精神）；draft 预检账本事件因 V1 NOT NULL FK 落不了库，以 inbox 行充当审计（与 EX-13 同款缺口，V4 待裁定）。
- 2026-08-31：**M1-T07 完成**：PrStateReconciler（公平扫描+API 预算+指数退避+429 全局暂停游标+ReconcilerDegraded 阈值告警+404 sanity 两态）+ PRSubjectRepository 三方法 + RECONCILER_DEGRADED 事件类型。主会话复跑亲验：control 239 全绿。ReconcilerDegraded 落账本需 Run 挂载点（active Run→最近 Run→无 Run 退化日志，与 EX-13/draft 预检同族 V4 决策）。**T09 必办：ST-21 并发 IT 未写（T07 只覆盖了收敛点对账侧），关键五条之一，必须补。**
- 2026-08-31：**M1-T08 完成**：DriftReconciler（公平巡检 IN(PRESENT,MISSING)/复用 handler 探针零新增触网/sanity 读 GET_REPO 经唯一触网点/404 两态 UNKNOWN+权限告警/MISSING 单次事件行锁守卫/只检测不修复）+ PublicationResourceState 观测态迁移（引用面仅 2 处，control 零涟漪）+ AFT-13 静态断言。主会话全 reactor 复跑亲验：98+239+106=443 全绿。偏差留档：429 精确 retry-after 需扩 shared-kernel 响应契约，留 M2；CT-20 列级授权真跑留 195。**编码任务 T01~T08 全部完成。**
- 2026-08-31：**M1-T09a 进行中（部署阶段）**：ST-21 并发 IT（20 轮 CyclicBarrier 双源并发）已补；代码 tar 同步 195、maven 容器打包、V3 迁移实库应用成功、四容器起栈。部署暴露 INC-22（claim SQL 文本块拼接丢空白，本机无 docker 盲区）已修复并复验；INC-20/21（V3 实库踩坑，T01 期已修）补记 BUGLOG。发现 DB 残留 M0 stub+真实混合测试数据致 DriftReconciler 探测全 probe_unknown（stub 期 installation=555000 行对真实 API 铸币 404）——业务表已 TRUNCATE（共 13 表约 200 行测试数据，flyway_schema_history 保留），决策：DP 门禁先跑真实模式（DP-13 需要真实 draft 链路），完成后切 stub 模式供测试 agent 故障注入。control/publisher 自检通过、401 探针正常、INC-22 不再复现。下一步：扩展 smoke-test.sh DP-11~14 → DP 门禁 → 切 stub → T09b 交接文档。
- 2026-08-31：**M1-T09a DP 门禁迭代**：smoke-test.sh 扩展 DP-11（V3 权限矩阵 21 断言）/DP-12（三 worker 心跳+重启自愈）/DP-14（SIGKILL 半截重放恰好一次）+ stub 映射补 M1 两读端点（get-pr-metadata 固定 SHA 与 DP-05 负载一致、get-repo-sanity）。四轮迭代暴露并修复 4 个脚本/stub 缺陷（INC-23：WireMock3 journal 清零端点 404、重启等待被旧日志标记骗过、DP-05 固定 PR#7 撞同 revision 去重、DP-14 缺 outbox 收敛等待 + sequence 全局基线误用）；产品侧复核无 bug（去重/恢复账本行为均正确）。stub 探针恒空保真度局限留档。
- 2026-08-31：**DP 门禁 stub 段全绿**：第四轮 **PASS=77 FAIL=0**（DP-01~05 回归 + DP-11 V3 权限矩阵 + DP-12 三 worker 心跳/重启自愈 + DP-14 SIGKILL 半截重放恰好一次），证据 smoke-evidence/20260831-042903。
- 2026-08-31：**DP-13 真实模式进行中**：切真实模式后连续暴露两缺陷——INC-25（compose 公网绑定未入库被 tar 同步冲掉 → 投递 502；已 env 化 CONTROL_BIND 并用精确 delivery ID 重投成功）与 INC-24（M1 新读路径所需 pull_requests:read/checks:read 不在 READ scope 里 → 权威读 403；READ 改只读三元组后 inbox 第 5 次重试自愈 PROCESSED）。draft 零评审断言①②通过（opened+synchronize 均 PROCESSED、runs=0、artifacts=0、epoch=0）；GraphQL markReady 被 GitHub 拒（已知平台坑，hub4j#1578），改走用户 gh 凭证 `gh pr ready` 成功；ready 后完整评审闭环等待中（真实 tarball 3~6min）。
- 2026-08-31：**DP-13 真实 draft 闭环五断言全部通过，INC-26 修复并复验**：断言⑤暴露 INC-26（reopened 无特判 → 代码未变的 reopen 不递增 epoch，单测编码错误预期掩盖）；修复（PrRouteDecision 新增 Reopen 值 + reopenGeneration 幂等换届 + 三层用例）后真实 close→reopen 重验：epoch 2→3（close）→4（reopen）+ 新 Run REVIEW_COMPLETE + 2 outbox CONFIRMED（同 head Revision 复用属预期）。PR#2 已关闭。本机全 reactor 348 绿（control 242 + publisher 106；shared-kernel 98 另计）。进行中：195 挂 docker.sock 真跑 M1 全量 IT（mvn verify，ST-20 IT 即 INC-26 回归测试）→ 切回 stub 模式留栈 → T09b 交接文档。
- 2026-08-31：**195 真跑 M1 全量 IT（硬门禁落地）**：挂 docker.sock 的 maven 容器 `mvn verify` 首跑暴露 INC-27（两条从未真跑的 IT 断言缺陷：st09 在途重投应答应为 202 processing 而非 200 duplicate；st12 批量 claim 的 RETURNING 顺序不定触发 LWW 守卫——均为设计内行为，测试编排修正）+ ST05 IT 误查 review_run.head_sha（列在 pr_revision，改 join）。修复后重跑中。INC-26/27 已记 BUGLOG。
- 2026-08-31：**M1-T09a 收官**：195 全量 `mvn verify` 绿（446 单测 + control IT 22 + publisher IT 39，CT-05 预期跳过）；栈切回 stub 模式（.env.stub.bak/.env.realmode.bak 双备份互切机制建立）、双应用自检通过、未签名探针被拒；DP-13 证据归档 `smoke-evidence/dp13-final-20260831-051645`；业务表 TRUNCATE 清零（flyway 历史保留）供测试 agent 干净接手。INC-27 已记 BUGLOG。
- 2026-08-31：**M1-T09b 完成**：出具 `docs/M1-测试交接文档.md` v1.0（四阶段矩阵：A 自动化回归 446+61 / B DP 门禁 77 断言 / C 栈级 E2E-11~24 八条注入规程 / D 真实模式默认不执行；用例→测试类对照表；故障注入工具清单；清理清单；已知局限四条）+ 刷新 `docs/测试交接-背景与服务器现状.md` v1.1（容器名/进度/INC-20~27/模式互切/stub 局限全部核对）。方案 §8 补 M1-P9（无 Run 可挂的账本缺口，V4 待 G2 裁定）/M1-P10（Drift 429 精确退避留 M2）。**待用户转交执行 agent；T09c 等测试回流。**
- 2026-08-31：出具 `docs/交接-工作准则与质量基线.md` v1.1（态度层：不盲从证据先行/不信二手结论/三层替身盲区/bug 是资产/不炫技/里程碑硬门禁；基线层：完成的定义/可复现验证/断点续做/异常追到底；判断力场景四条），并在《M1 测试交接文档》§0 置顶引用——交接包 = 准则（态度）+ 规范（纪律）+ 背景（上下文）+ 交接文档（矩阵）四件套。
- 2026-08-31：准则文档 v1.2 补"里程碑工作法"一节（六道工序 + G1/G2 双人工门禁摘要，指向 milestone-workflow skill 强制版；明确测试执行 agent 只参与工序 5），交接文档 §0 引用同步更新。
- 2026-08-31：**T09c 首轮回流处置完成**：执行 agent（用户指定）首轮结果 9 PASS / 1 FAIL / 8 BLOCKED。TB-01（背景文档未写 loopback）/TB-02（登记早于 V3 部署）裁定关闭；TB-03 证实为 smoke 脚本竞态（compose run 不继承 restart 策略 + 瞬时采样），产品自检门行为正确——DP-02 改终态轮询+ExitCode 断言，195 复跑 **PASS=77 FAIL=0**，TB-03 置「已修复待回归」并记 INC-28。配套补齐：规范 §五 修复回路明确"执行 agent 由用户指定、经三文档+用户转达回流"；交接文档 §0/§6 同步；milestone-workflow skill 工序5 与 test-executor skill 现场事实刷新。待执行方全量回归解封阶段 C。
- 2026-08-31：准则文档 v1.3 补两节——§七 技术方案写作标准（可执行性/决策依据三选一：开源证据∥冻结条款∥INC 实录/压力点带触发条件/图必渲染且含崩溃路径/残余风险诚实清单/评审意见逐条处置留痕不盲从）与 §八 测试全面性定义（六层防线各防一类事故 + 用例四要素 + 送审前覆盖自查清单：重试耗尽/幂等/并发/崩溃窗口枚举/乱序迟到/安全面/成本面/替身盲区标注）；milestone-workflow skill 工序1 引用对齐。
- 2026-08-31：**外部评审"适配性裁定"处置**：四路官方文档核查（AgentSwarm）全部完成——GitHub Merge Queue/Mergify/Trunk 组合验证论断属实、CodeRabbit/Greptile 无组合树验证（公开证据）、Langfuse 六组件最低 11C/25.5Gi（超 195 余量 13 倍）、OTel GenAI 46 处 stability 全 Development 且无 release（比评审措辞更硬：适配层+版本锁是先决条件）。落盘三处：OSS 证据清单追加 F-4~F-7；新增 ADR-020（外部组件引入判据六元规则+六项目裁定，编号顺延避开已占用的 018/019）；新增 ADR-021（Merge Preview 收窄定位+ValidationTarget 身份模型+merge_order 入账+双边非原子+M5 后确定性先行排期）。两 ADR 均标"设计输入·未冻结"，零实现，符合里程碑纪律。
- 2026-08-31：**M2 技术方案 v1.0 起草完成（用户指示文档先行，编码硬等 M1 G2）**：`docs/M2-技术方案.md`——范围四块：P1 Step 内 checkpoint 断点续跑（step_checkpoint 表，模型产出 CAS 复用，prompt_version+model_route 双匹配，依据 A-3/E9）；P2 Drift 修复闭环（repair_request 一资源一活跃单 + RepairPlanner 世代 gate + probe-first 短路，CHECK_RUN 自动档/REVIEW 人工档，依据 C-3/R2）；P3 内容级 digest 巡检（只告警不自动改写）；P4 TypedResponse 契约扩展精确 Retry-After（新证据 F-8 已入 OSS 清单）。retention 只出 ADR 草案留 M6。任务 M2-T01~T09；不变量 I18~I24；测试续编 CT-21+/ST-22+/EX-19+/E2E-25+/DP-15+；三张 mermaid 经官方 parser 实渲染校验通过。M1-P9（V4 安全事件表）标为 M1 G2 裁定项。**待：M1 全量回归回流（G2）+ M2 方案 G1 评审。**
- 2026-08-31：**M1 工序5 二轮回流处置完成**：执行方全量回归结果——阶段 A' PASS、B' 76/77（DP-02 复验通过、新 FAIL=DP-01）、阶段 C 实质 7/8（E2E-19 BLOCKED）。主会话逐条裁定：TB-03 关闭（执行方复验通过）；TB-04 关闭（归因证实=stub 固定 remote_id 撞 (resource_type,remote_id) 唯一约束 + probe-list 恒空，双机制均为 stub 保真度伪影，非产品缺陷，已入交接文档已知局限 §3-5）；TB-05 已修复待回归（E2E-19 配方重设计=停机排队+恢复恰好一次排空，换届 fence 语义声明由 ST-02/CT-06/ST-19 IT 覆盖）；TB-06 已修复待回归（DP-01 改双态断言 + flyway 版本门禁，INC-29 入 BUGLOG，脚本已同步 195 并留 .bak）。**待执行方按更新后交接文档全量回归（含新配方 E2E-19 + DP-01/02 复验），TB 全关闭后推送 GitHub 进 G2。**
- 2026-08-31：**M2 技术方案 v1.1（G1 两轮评审处置）**：25 项意见全部处置留痕（采纳 14/修正后采纳 11/驳回 0）——编号冲突续编（UT-17/CT-21/ST-23/EX-19/E2E-26/DP-15 起）；MANUAL 改 APPROVED 审批模型（tier 永不改写+审计三列）；checkpoint 加 lease_epoch 迟到写栅栏 + contract digest 五分量；repair_request 七态生命周期 + RepairOutcomeProjector + REPAIR 型 Run + 新 PRESENT 行资源身份模型（replaces_resource_id）；ProbeResult/RetryDirective 封闭类型；限流 403 与普通 403 分流；retention 降级为调研记录不冻结实现；测试矩阵扩充至 L0×5/L1×10/L2×8/L3×16/L4×12/L6×10 + BT×3 + DP×5 全编号四/五要素；M0+M1 全量回归列入通过硬条款。三张 mermaid 经官方 parser 实渲染校验通过。**待用户 G1 二审。**
- 2026-08-31：**外部评审 27 条测试增补对账与补缺完成（T09c 第三轮）**：explore 代理逐条对账——14 已覆盖/11 部分覆盖/真缺口 2；驳回 1 条（"超大/空结果应失败"与本项目设计语义相反：超大=确定性截断计数、空=合法 0 findings 成功，已补显式断言钉死）。补缺 11 项（control 6 + publisher 5，含唯一授权产品改动：Outbox payload 铸 `installation_id` + FencedPublicationExecutor 触网前预检，错配 FAILED_TERMINAL(INSTALLATION_MISMATCH) 零触网）；#25 升产品修复记 INC-30（FindingMapper 歧义锚定多重命中改丢弃）。验证链全绿：本机 453 单测 → 195 挂 docker.sock `mvn verify` 全绿（453 单测 + control IT 22 + publisher IT 45，CT-05 预期跳过）→ 重建容器 DP 门禁 **PASS=78 FAIL=0**（+1=TB-06 flyway 版本门禁；installation 预检未拦 DP-05，stub installation 555000 与种子一致）。文档同步：BUGLOG INC-30；M1 方案 §8 新增 M1-P11（T2 重放异常类型缺口，G2 裁定项）；冻结文档 v2.3 增量 §6（payload installation_id+预检）/§7（锚定唯一性）；交接文档升 v1.2（计数 453/22/45、DP 78、新测试类对照）。代理偏差留档：DriftReconcilerTest 构造签名漏改（主会话修）；EX07 属 publisher 侧 IT；PostgresReviewFindingRepositoryTest 落 surefire 段。**待执行方按 v1.2 全量回归（A→B→C 九条），TB 全关闭后经用户确认 push GitHub + G2。**
- 2026-08-31：**M1 工序5 第三轮回流处置**：三轮结果 11 PASS / 1 FAIL（E2E-21 → TB-07）。TB-05/TB-06 经执行方复验 PASS 关闭（E2E-19 新配方停机排队+恢复恰好一次排空通过；DP 门禁 78/0）。**TB-07 裁定当期修复**（M0 遗留 I17 违例：work_item 租约比较走应用时钟；危害潜伏但 M7 必兑现）——work_item 家族六条 SQL 改 DB now()/make_interval、port 摘除 Instant 参数、fake 引入可设置时钟、新增 SqlGuardTest 文本守卫；PrStateReconciler 内存节奏门两处豁免留档（M7 再议）。本机全 reactor 亲验 98+249+107 全绿（含修复中暴露的 publisher ItHarness 签名涟漪一处）。INC-30 已入 BUGLOG。代码已同步 195 并重建 control 镜像重启：四容器 Up/healthy、401 探针守门、自检通过。**待执行方第四轮全量回归（重点 E2E-21 复验 + 阶段 A 195 真跑 IT），TB-07 关闭后即 TB 全关 → push GitHub（当场确认）→ G2。**
