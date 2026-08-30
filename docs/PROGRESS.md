# PROGRESS —— 项目进度总账（断点续做入口）

> 规则：每完成一个任务/工序立即更新本文件（见 skill `milestone-workflow` 第五节）。
> 接手者读这一份就够：当前状态 → 下一步动作 → 环境锚点。

## 当前状态（2026-08-30 更新）

**项目**：AI Code Review Agent（GitHub App 形态的 AI 代码评审 Agent，Java 21 + Spring Boot 3.x + Spring AI + PostgreSQL，DDD 分层）

**当前阶段**：M0 最小可靠骨架 —— G1 已过门（2026-08-30 用户指示开工），工序 3 编码 **T01~T17 已完成**（329 单测 + 32 集成测试全绿），剩 T18 部署验证

**下一步动作**：T18（compose 双容器部署到 195，DP-01~05）→ 本地 commit 353b688 已落（用户指示暂不 push）→ G2 阶段门等待用户确认 M0 完成

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
