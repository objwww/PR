# 告警 Agent 线 —— 进度总账（断点续做入口）

> 本文件是**告警 Agent 项目**的唯一进度入口。2026-09-04 起文档体系彻底分家：旧 PR-Agent 线全部文档压缩归档至 `docs/archive/pr-agent-line-20260904.tar.gz`（不再使用；git 历史 + m4-wip 分支另存）。
> **文档体系（2026-09-04 晚更新）**：架构基线双文档 = `docs/架构设计-告警Agent-v1.md`（AA-1~26 既有基线，内容版本 v2.2）+ `docs/架构设计-告警Agent-v1.2.md`（FUT-01~55 目标增量，**设计 PASS 待落码**，含 AA↔FUT 追溯矩阵，两文档互补不互替）；执行计划 = `docs/告警Agent-增量实现任务拆解-v1.md`（96 任务，**当前只释放 G0**）；评审裁定 = `docs/架构评审-*.md` 三份；方案系列 = `docs/告警AMx-技术方案.md`；本线账本 = 本文件 + `docs/告警-OSS-证据清单.md` + `docs/告警-BUGLOG.md`。
> 规则：每完成一个任务/工序立即更新；时间线只增不删。

## 项目定位

**交易域告警 RCA Agent**：开源拼装告警平台（Alertmanager + Keep）+ RCA 执行引擎（HolmesGPT 先行，后续 Java 逐步替换）+ 现有仓库 control-app 改造为告警控制面。五层（接口/调度/执行/数据/安全观测）每层抄开源已验证设计，证据入 `docs/告警-OSS-证据清单.md`（待建）。

**已定选型**（2026-09-03 用户确认）：
- 接口层：Alertmanager + Keep 都部署
- 执行层：HolmesGPT 先部署，逐步替换为 Java 实现
- 告警源第一期：OTel Demo（Astronomy Shop）靶子，内置故障开关 + Pumba 注入
- 总原则：能抄开源就不自研；LLM 只提建议，决策权在确定性组件

**里程碑序列**：AM0 平台拼装 → AM1 接入控制面（control-app 改造）→ AM2 交易域化（订单靶场 + 业务故障注入）→ AM3 评测 + 出口 → AM4+ 压力点驱动。

## 当前状态（2026-09-04 晚）

**架构状态**：**已定格**（用户确认）。架构基线双文档停止演化（AA 系列封版于 v2.3；FUT 系列以 v1.2 为准）；自此刻起 **G0 收口前不再新增设计文档**——工作重心全面转向代码与证据。

**当前阶段**：**AM1 G0 收口执行完毕（2026-09-04，单 commit 落账不 push，待用户 G2 终审后进 AM2）**。执行依据 = `docs/告警G0-收口技术方案.md`（v1.0，G0-01~10、G0-12 已全部单项验收；G0-11 复核材料即本 commit）；任务树总表 = `docs/告警Agent-增量实现任务拆解-v1.md`；AM1/AM2/AM3 技术方案为设计依据文档。

**执行清单（G0-01~10、G0-12 全部完成 ✅）**：
- G0-01~09（基线 manifest → 配置键统一 → Holmes key 桥接 → rca_report 自检修正 → 状态机三线接线+episode 乱序修复 → PG IT → AM0 配置回收+Holmes 运行时三修复）：全绿，BA-09/10/11/12 随之关闭
- G0-10 单链路 E2E：195 真栈全链打通（flagd 注入→Sloth firing→AM 分组→webhook 202→inbox→incident→run/task→Holmes 真 LLM→rca_report STRUCTURE_VALIDATED→resolved 归并 RESOLVED）+ SIGKILL 崩溃演练 + 全流程 5 截图与 DB 终态快照；**BA-14/BA-15 双修复即出自本轮实证**
- G0-12 BA-13 四条优化：全部落地带单测，已部署 195 重启验证
- BA 系列收口状态见 `docs/告警-BUGLOG.md`：BA-09~19 已关闭（BA-17/18 为记录/纪律类），BA-20/21 为开放观察项移交 AM2/AM3
- AM1 方案 v2.2 增量（rca_task_edge + task 预留列）：**经主会话自查纠偏，移交 AM4（M4-01~03），G0 不新增迁移**——V8 编号归还 AM3

**下一步动作**：用户按 G0-11 复核本 commit（构建绿 + 审查逐条对照 + IT 计数非零 + E2E 证据四件套已齐）→ 裁定 AM1 G2 通过 → 进 AM2（靶场，`docs/告警AM2-落码技术方案.md` v1.0 已备）。

**后台进行中**：harness 设计调研（Claude Code/Codex/OpenHands/DeepSeek 等，产出 `docs/告警-调研-Harness设计-v1.md`）——服务于 AM4 Native 内核设计，不阻塞 G0。

**验证终局（G1~G7）**：
- G1 镜像供应链 ✅（20/20，ghcr 直拉停滞 → 本机 crane 摆渡，记 INC-3）
- G2 中台 ⏭ **双出局降级**：HertzBeat RSS 1.126GiB 超 800M 线；Alerta 一次性 PG 起不来（细节待从证据目录复盘）；链路降级为 **Alertmanager 直打 echo-receiver**，汇聚层空缺记录在案
- G3 靶场 ✅（20 容器全 healthy，flagd 故障开关实测可用）
- G4 告警自动产生 ✅（paymentFailure 注入后 **3 分 17 秒** Sloth burn-rate 自动 firing，零手写 PromQL）
- G5 全链路 ✅（firing → AM 30s 聚合 → echo webhook 自动送达，含 resolved 回执实证）
- G6 RCA ✅（**qwen-plus 在专属 MaaS 实例不可用** → 按降级路径换 `openai/deepseek-v3` 成功；端点坑：MaaS 专属端点需补 `/v1` 后缀）
- G7 内存 ✅（全栈+存量同跑 available 5140M）

**下一步动作**：
1. 路线已定稿：Demo+flagd → collector → Prometheus(Sloth) → **Alertmanager 直打** →（AM1: control-app）→ HolmesGPT(deepseek-v3/百炼)
2. 把实测结论回写 `docs/告警AM0-技术方案.md`（v1.0 草稿 → v1.1 实测修订：中台双出局、AM 直连、模型降级、crane 摆渡）→ 送 G1
3. AM0 G2 确认后进 AM1 工序 1（control-app 告警入口+调度层改造方案）

## 六道工序状态（AM1）

| 工序 | 状态 | 产物 |
|---|---|---|
| 1 任务拆解+技术方案 | ✅ v2.1（用户批准执行，E2E 全流程截图要求并入） | `docs/告警AM1-技术方案.md` v2.1 |
| 2 G1 方案门 | ✅ 用户批准（2026-09-03 会话，含增补计划） | — |
| 3 编码 | ✅ 完成（T00~T10 全绿） | 见下方 AM1 编码明细 |
| 4 部署验证 | ✅ 2026-09-04 | T10 + G0-09 配置回收/G0-10 E2E 部署面 |
| 5 测试验证 | ✅ 2026-09-04 | G0-10 单链路 E2E + 截图证据；测试记录 `docs/告警-测试记录.md`；证据 `docs/测试证据/G0/` 与 `docs/测试证据/AM1/` |
| 6 G2 阶段门 | 🔶 复核材料齐备（G0-11），待用户终审 | 本 commit 即复核材料 |

### AM1 编码明细（工序 3）

| 任务 | 状态 | 验收证据 |
|---|---|---|
| T00 死代码清除 | ✅ 2026-09-03 | 全 reactor `mvn clean test` 绿（100 tests/0 fail/2 skip=需 docker）；残留扫描仅注释性提及；`docs/测试证据/AM1/T00-死代码清除/`（完整日志+BUILD SUCCESS 截图） |
| T01 V7 迁移 9 表 | ✅ 2026-09-03 | `M7MigrationContractTest` 7/7 绿（本地静态门）；CT-A01 真 PG 归 195；`docs/测试证据/AM1/T01-V7迁移/` |
| T02 告警域 domain | ✅ 2026-09-03 | 枚举 10 + 实体 9 + 状态机 4（TransitionTable 通用件）+ 纯函数 4（Identity/Sla/Deferred/EvidenceValidator）+ 端口 9 + HolmesErrorClassifier；UT-A01~A09 全绿（141 tests 总量，0 fail）；AFT-A01 零框架依赖规则入 ControlArchitectureTest；`docs/测试证据/AM1/T02-告警域domain/` |
| T03 Postgres 仓储×9 | ✅ 2026-09-03 | PostgresAlertInbox/Event/Incident/RcaRun/RcaTask/RcaAttempt/RcaReport/ExternalInvocation/SchedulerSlot 九仓储 + PersistenceConfig 装配（docker profile）+ AlertInMemoryStores 约束模拟（唯一约束 23505 模拟/epoch 栅栏/slot 原子/SLA 领取排序）；146 tests 绿；PostgresITBase 清场清单增 V7 九表；CT-A02~A08 归 195；`docs/测试证据/AM1/T03-Postgres仓储/` |
| T04 告警入口 | ✅ 2026-09-03 | `AlertWebhookController`（`@Profile("docker")` POST /webhooks/alertmanager）+ `AlertIntakeService`（gunzip 边解压边限流/Jackson 深度限制/空组落 IGNORED）+ `AlertIntakeLimits`/`IntakeRejectedException` + `AlertFlowConfig` 装配；`AlertWebhookControllerTest` 11/11 绿（202 语义/401 常量时间验签/400 五变体/413 gzip 炸弹 64KB/503 DB 故障/空组 IGNORED）；全量 157 tests 0 fail；EX-A01~A03/A09/A10 本地收口；`docs/测试证据/AM1/T04-告警入口/`（日志+2 截图） |
| T05 投影与消费 | ✅ 2026-09-03 | `AlertPayloadParser`（拆组/腐坏死信）+ `ParsedAlert` + `IncidentProjector`（§6.7 单事务：逐 alert 软背压重读 backlog → event 幂等 existsByDedup 预判+merge 后追加 → incident upsert（episode 水印乱序收敛/三计数/首见 INITIAL/再现 generation+1/调查中材料变化记 pending/完成后材料变化铸 RERUN）→ run+task 铸造 SLA 列）+ `AlertInboxProcessor`（六态消费循环/虚拟线程/DEFERRED→RETRY_WAIT 决策列/DEAD_LETTER/崩溃租约回收）+ `AlertClock` 统一时钟 + AlertFlowConfig 装配；端口增 scheduleRetry(decision) 与 existsByDedup；ST-A01~A05/A07 + 幂等/升级不裂单/新 episode/RERUN/死信 15 用例绿，全量 172 tests 0 fail；**BA-05/06/07 三条 bug 记录与修复**（Timestamp.from(Instant.MAX) 溢出环绕 → pgjdbc infinity 特判）；ST-A06/A08 与 CT 归 T06/195；`docs/测试证据/AM1/T05-投影与消费/`（日志+截图） |
| T06 Worker 与收尾 | ✅ 2026-09-04 | `RcaTaskExecutor` 执行抽象（ReportContent 内容子集，归属由收尾铸造）+ `RcaRunOrchestrator.finishTask`（§6.7 四分支固定算法：epoch 栅栏拒写/attempt 终态/task 终态+slot 归还/RESOLVED 短路/材料变化铸 RERUN/材料锚定；失败退避 RETRY_WAIT 耗尽 DEAD+run FAILED）+ `RcaWorker`（恢复扫描双回收+悬挂账本 STARTED→UNKNOWN/slot+task 同事务领取/心跳续租/虚拟线程循环）；**V7 增 rca_run.investigation_hash 材料快照列**（finishTask rerun 判定锚，未部署可改）+ RcaRun 实体/PG 映射/契约块级断言；SchedulerSlotRepository.tryAcquire 返回 AcquiredSlot(slotNo, epoch)+heartbeat 端口；ST-A06 四分支+ST-A08 旧 epoch 拒写+SLA 排序+槽饱和+崩溃恢复 9 用例绿，全量 181 tests 0 fail；CT-A02~A05/A07 真 PG 并发归 195；`docs/测试证据/AM1/T06-Worker与收尾/` |
| T07 Holmes 执行器 | ✅ 2026-09-04 | `HolmesClient`（HTTP 包装，未使用）+ `HolmesErrorClassifier`（LLM 错误五分类：TRANSIENT/RATE_LIMIT/AUTH/INVALID_REQUEST/UNKNOWN）+ `HolmesInvestigationExecutor`（CLI 执行模式：docker exec holmes investigate，stdout 捕获 + 结构化解析 fallback plain）+ `ExternalInvocation` 实体（AM1 不存储调用历史，仅领域模型占位）+ `ExternalInvocationRepository` 端口；`HolmesErrorClassifierTest` 28 tests 绿（五分类覆盖）+ `HolmesInvestigationExecutorWireMockTest` WireMock 模拟（未覆盖 CLI 路径，真实调用归 T10）；全量 209 tests 0 fail；`docs/测试证据/AM1/T07-Holmes执行器/` |
| T08 Holmes 常驻容器 | ✅ 2026-09-04 | 195 部署 `holmesgpt-am1` 容器（镜像 `local/holmesgpt:am0`，CLI 守护模式 `/bin/sh -c "while true; do sleep 3600; done"`）；网络 `alert-net`（与 Alertmanager/Prometheus 互通）；安全硬化：`security_opt: no-new-privileges`、`cap_drop: ALL`、tmpfs `/tmp` 512M、内存限制 1536m；环境变量 `OPENAI_API_BASE`/`OPENAI_API_KEY`/`HOME=/tmp`；**部署策略确认**：HolmesGPT 官方无 HTTP server 模式（仅 CLI），采用 **CLI 执行模式**（control-app 通过 `docker exec holmesgpt-am1 holmes investigate` 调用，stdout 捕获）；容器状态 Up 运行中，CLI 可用（`holmes --help` 验证通过）；195 `smoke-evidence/t08-holmes/` |
| T09 启动自检 | ✅ 2026-09-04 | `AlertSelfCheck`（告警域自检：webhook bearer token 存在性 + Holmes 凭证存在性（holmesEnabled 控制）+ V7 九表权限（alert_inbox/alert_event/incident/rca_run/rca_task/rca_attempt/rca_report/external_invocation_ledger/scheduler_slot，其中 alert_event/external_invocation_ledger 只需 INSERT+SELECT，其余需 CRUD））+ `AlertSelfCheckTest` 6 tests 绿（缺失 token/缺失 Holmes key/Holmes disabled/V7 权限缺失）；`ControlSelfCheck.violations()` 新增 `holmesEnabled` 参数集成告警域自检，向后兼容重载保留；全量 215 tests 0 fail；`docs/测试证据/AM1/T09-启动自检/` |
| T10 部署验证 | ✅ 2026-09-04 | 195 端到端验收：(1) 容器状态检查（holmesgpt-am1/control-app/postgres/alertmanager/prometheus 全 Up）；(2) HolmesGPT CLI 可用性（`holmes version` 通过）；(3) Postgres 连接性（`alert_inbox` 表可查）；(4) 故障注入（flagd `paymentServiceFailure=ON`）；(5) **告警触发验证**（Alertmanager 活跃告警：`checkout` SLO 慢烧损，severity=ticket，state=active，fingerprint=283f38d9cb61c6aa，startsAt=2026-09-03T12:06:02.984Z）；证据文件：`smoke-evidence/t10-e2e/e2e-result-*.log`（完整告警 JSON + flagd 配置）、`t10-summary.txt`（验收总结）；**待办**：control-app webhook 接收（需配置 Alertmanager webhook_configs）、Grafana 告警面板截图；`docs/测试证据/AM1/T10-部署验证/` |

## G0 收口执行记录（2026-09-04，执行依据 `docs/告警G0-收口技术方案.md` v1.0）

| 任务 | 内容 | 结果 |
|---|---|---|
| G0-01 基线 manifest | commit 基线+测试计数+迁移清单固化 | ✅ `docs/测试证据/G0/baseline-manifest.md` |
| G0-02 webhook 配置键三方统一 | compose/yml/.env.example 三方对齐 `ALERTMANAGER_WEBHOOK_BEARER_TOKEN` | ✅ 关闭 BA-09① |
| G0-03 Holmes key 桥接 | yml 显式占位 `${HOLMES_API_KEY:}` | ✅ 关闭 BA-10① |
| G0-04 rca_report 自检权限修正 | alert_event/external_invocation_ledger/rca_report 三只增表改查 INSERT+SELECT | ✅ 关闭 BA-10② |
| G0-05~07 状态机三线接线+episode 乱序修复 | Inbox/Run/Task 三台 requireTransition 全接线；resolved 后迟到 firing（startsAt<resolvedAt）只计数不复活 | ✅ 关闭 BA-11①/BA-12① |
| G0-08 告警仓储 PG IT | AlertV7MigrationContractIT/PostgresRcaTaskRepositoryIT 195 真 PG 真跑；迁移增量裁定移交（rca_task_edge 等归 AM4，V8 编号归还 AM3） | ✅ 关闭 BA-09③ |
| G0-09 AM0 配置回收+Holmes 运行时修复 | AM webhook 配置入 git；响应限读/UNKNOWN 语义/Hikari 显式预算 | ✅ 关闭 BA-12②③④；`docs/测试证据/G0/g0-09-配置回收与Holmes三修复.md` |
| G0-10 单链路 E2E（195 真栈） | 全链打通+SIGKILL 崩溃演练+resolved 归并+全流程截图；BA-14/15 双修复出自本轮实证 | ✅ 见下"G0-10 E2E 实录" |
| G0-11 AM1 G2 复核 | 构建绿+审查逐条对照+IT 计数非零+E2E 证据四件套齐备 | 🔶 材料即本 commit，待用户终审 |
| G0-12 BA-13 四条优化 | startedAt 绑定参数/退避宽限配置化/组首 count 上界/首见并发重读合并 | ✅ 关闭 BA-13 |

### G0-10 E2E 实录（2026-09-04，195 真栈）

- **链路**：flagd `paymentFailure` 注入 → Prometheus Sloth 烧损率 firing（page/ticket 双档）→ Alertmanager 分组投递 → control-app webhook 202 → alert_inbox → alert_event → incident → rca_run/rca_task → HolmesGPT（DashScope deepseek-v3 真 LLM 调查）→ rca_report **STRUCTURE_VALIDATED**
- **过程修复**：BA-14（response_format 被兼容端点忽略→全 REJECTED_MALFORMED）与 BA-15（bash 工具诱导 kubectl+prometheus 工具集静默禁用）——双修复后报告引用 prometheus:// 真实证据、根因指向真实烧损
- **SIGKILL 崩溃演练（DP-B05）**：kill 时 task LEASED/账本 STARTED → 租约到期回收 RETRY_WAIT → 重领 attempt+1 → SUCCEEDED，报告唯一无重复；悬挂账本超宽限 → UNKNOWN（诚实对账）；195 旧 docker SIGKILL 后不自动拉起记 BA-17
- **resolved 归并**：停注入 → 1h 窗口自然稀释（程序化成功流量造不出记 BA-20）→ 烧损率回落 → Alertmanager 真实 resolved 通知 → incident **RESOLVED**
- **DB 终态**：alert_inbox 11 行、incident 2 单全 RESOLVED、rca_run 7（1 FAILED 修复前+6 SUCCEEDED）、rca_task 7（1 DEAD+5 DONE@1+1 DONE@2）、外部调用账本 10（9 SUCCEEDED+1 UNKNOWN）、报告 6 全 STRUCTURE_VALIDATED 无重复
- **证据**：`docs/测试证据/G0/e2e/`（5 张全流程截图+195 终态快照五件套+manifest.sha256）；测试记录 `docs/告警-测试记录.md`

## 六道工序状态（AM0）

| 工序 | 状态 | 产物 |
|---|---|---|
| 1 任务拆解+技术方案 | ✅ 起草完成，待 G1 | `docs/告警AM0-技术方案.md` v1.0 + `docs/告警-OSS-证据清单.md` |
| 2 G1 方案门 | 🔨 等用户评审 | — |
| 3 环境拼装 | ✅ done（验证形式） | 195 实测全链路（执行者） |
| 4 部署验证 | ✅ done | G1~G7 判定表 `smoke-evidence/s9-verdict-G1-G7-20260903-203846.md`（195） |
| 5 测试验证 | ✅ done（演练形式） | 演练 1/2 取证 26 文件，echo 载荷解码留证 |
| 6 G2 阶段门 | pending | 待用户确认"AM0 完成" |

## 环境锚点

- **195 服务器**（146.56.195.225）：CentOS 7 内核 3.10.0、docker 26.1.4、**内存 7.5G（可用约 5.7G）、磁盘余 24G**、公网 IP（端口只绑内网/localhost，访问走 SSH 隧道）
- 现状容器：postgres（healthy，PG 16）、control-app、publisher-app、github-stub（**unhealthy**）；AM0 计划停 publisher-app + github-stub 腾约 1G 内存（可逆，`compose up` 即恢复）
- SSH：`ssh -i ~/.ssh/id_ed25519 root@146.56.195.225`；构建现场 `/opt/build/pr`，持久态 `/opt/projects/pr_agent`
- 本机 Windows **无 docker**——所有容器验证只能在 195 上跑
- 模型：阿里云百炼 OpenAI 兼容端点，key 经环境变量 `AGENT_MODEL_API_KEY` 注入（纪律：密钥永不写入文档/代码/日志）
- **旧方向封存**：旧线全部文档已归档 `docs/archive/pr-agent-line-20260904.tar.gz`；M4 未完结工作区在 **m4-wip 分支**（commit `ab32b79`）；main 停在 M4-A 锚 `5686d28`。旧线复启：解归档 + 读其中 PROGRESS.md。

## 关键风险（勘察与调研已识别）

0. **Coroot 路线已判死刑（2026-09-03 实测+官方文档）**：用户并行调研曾推 Coroot（自动 inspection/默认 SLO/免写告警规则 + MCP Server 可当 Agent 证据源），但 Coroot 硬依赖 eBPF，官方要求内核 ≥ 5.1；195 实测内核 3.10.0，不可行。备选路线（待 G1 裁定）：A=原计划 Alertmanager+Keep+HolmesGPT + 现成规则库导入；B=OpenObserve 单容器替换 Prom+AM；C=Coroot 迁往内核 ≥5.1 的机器（本机 Docker Desktop/WSL2 或待确认的 117）

1. **内存超卖风险**：OTel Demo（最小模式约 3G）+ Keep 全家（约 1.5G）+ HolmesGPT（约 1G）+ Prometheus/Alertmanager（约 0.6G）+ 存量（约 1.5G）≈ 7.6G > 7.5G 上限——AM0 按"风险优先"顺序验证，资源扛不住时告警源降级为模拟脚本直打 Alertmanager（链路其余不变）
2. **ghcr.io 国内可达性**：OTel Demo 镜像在 ghcr.io，195 是国内机器，拉取可行性是一票否决项，AM0 第一个验证
3. **HolmesGPT 接百炼**：OpenAI 兼容端点配置（OPENAI_API_BASE + openai/<model>）未经实测，第二个验证
4. **HolmesGPT 账本盲区**：其模型调用不经过我们 ModelGateway 账本，Token 消耗无记录（AM3 前经 LiteLLM proxy 收口）

## 时间线

- 2026-09-04：用户确认架构核心冻结后进入“逐渐模块堆积实现整个任务”阶段 → 新增 `docs/告警Agent-增量实现任务拆解-v1.md`。任务树按 G0→AM2→AM3→AM4→AM5→AM6 切为可独立提交和验证的小模块；每项区分 V0 静态、V1 单元、V2 真实组件、V3 真栈 E2E。现场复跑 `mvn -q test`：215 tests、0 failure、0 error、2 skipped；本机 Docker/Testcontainers 不可用，因此仅记普通测试基线，不把跳过的 PG IT 计为 G2 证据。首批只释放 G0-01~11，先关闭配置三名漂移、报告权限自检、三台状态机生产接线、告警 PG IT 和 AM1 E2E，再启动 AM2。

- 2026-09-03：方向切换（用户裁定 PR 线暂停，转告警 Agent）；三项选型确认；M4 打锚 m4-wip；195/本机资源勘察完成；旧 PROGRESS.md 保持不动，本文件开账；后台调研启动（快速部署最短路径）。
- 2026-09-03：195 服务器备份数据清除（用户裁定"清除不保留"，仅限服务器侧）：/opt/backups、/opt/backup、/opt/projects/mall_R_new*、/opt/build 与 /root 下各备份项全部删除，磁盘余量 24G→**29G**；保留 maven 安装包、hotel 源码包、备份脚本（非备份数据）。117 服务器 SSH 公钥认证失败，其备份未清（待用户提供访问方式）。本地 `backups/`（4.7G）保留未动。
- 2026-09-03：部署调研因会话重启断联，已重启并追加调研题——**一体化告警平台核查**（Keep 一体化程度/Robusta+HolmesGPT 捆绑/夜莺/SigNoz/Grafana 等 vs 拼装方案的妥协对比）。
- 2026-09-03：调研 v1 产出（`docs/告警-调研-快速部署-v1.md`）；主会话 195 注册表实测：ghcr/quay/docker.io 通、**GAR 不通**（Keep 出局、HolmesGPT 转自建镜像）；Coroot 内核门槛判死（官方要求 ≥5.1，195=3.10）；用户两轮外部调研收敛 Sloth 路线 + HertzBeat 主候选；**用户 G1 前拍板路线 D（HertzBeat，A/B 先行）**；AM0 技术方案 v1.0 + 告警线 OSS 证据清单完成起草，待 G1。
- 2026-09-03：用户指示"先别写技术方案，先出部署验证设计给执行者验证路线"→ `docs/告警AM0-部署验证设计.md` v1.0 产出；应用户要求补背景简报/范围边界（含禁区）/验证证据规范，升 v1.1。交叉调研 `告警-调研-Keep替代-v1.md` 回收：推荐 Alerta，推翻"夜莺可收 AM 告警"——主会话复核属实，A/B 更正为 **Alerta vs HertzBeat**，证据清单留处置痕迹。当前等用户转交执行者。
- 2026-09-03 晚：**执行者完成 AM0 部署验证，路线 Go**（G1~G7 判定表在 195 `smoke-evidence/s9-verdict-G1-G7-20260903-203846.md`）。关键事实：中台双出局（HertzBeat RSS 1.126GiB 超标 / Alerta 一次性 PG 未起来）→ AM 直打 echo 降级链路验证通过；Sloth 零手写规则 3 分 17 秒自动 firing；HolmesGPT 经百炼出 RCA（qwen-plus 专属实例不可用 → deepseek-v3 降级成功；MaaS 端点需补 `/v1`）；INC-3 ghcr 直拉停滞 → crane 摆渡；全栈+存量同跑内存 available 5140M。主会话收尾：`github-app-key.pem` 从 `/opt/projects/pr_agent/keys/` 恢复，publisher-app 与 github-stub 还原运行。
- 2026-09-04：AM1 方案 v1.0/v1.1 → 用户 G1 评审有条件退回（8 条必须修正 + 账本/结构验证/安全三项提前）→ 主会话对评审承重声明做一手核查（AM webhook.go + Holmes http-api.md，全部属实，无驳回）→ **v2.0 修订完成重新送审**；核查证据入 E-12。
- 2026-09-04：用户指示"无用的类删除不留技术债"→ AM1 方案升 v2.1（新增 T00 死代码清除，依赖链 T00 起手）；用户指示"旧架构文档压缩归档、新线另起一套"→ **旧线文档整体归档** `docs/archive/pr-agent-line-20260904.tar.gz`（15M，5036 项，含 M0~M5 方案/PROGRESS/BUGLOG/冻结文档/ADR/测试文档/agent-learning），docs/ 只剩告警线；新建 `docs/架构设计-告警Agent-v1.md`（AA-1~13 冻结决策 + 组件锚点 + 演进阶梯 + 工作流规范）、`docs/告警-BUGLOG.md`（BA-01~03 补录 AM0 期三个缺陷）。
- 2026-09-04：AM1 编码方自称"编码测试完成"→ 主会话双轴审查（Standards+Spec 子代理）+ 实测 `mvn clean verify`：**BUILD FAILURE**（ControlSelfCheckTest 4 错误）+ Standards 5 条硬违规（配置三名漂移/自检与 V7 授权互斥/slot scope 错位/状态机零调用/双时钟混用）+ Spec 3 条运行时缺陷（gzip 载荷必死信/重试 deadline 不重算/T00 残留 ExecutionLedger 等）——**回流修复，AM1 未过 G2，不进 AM2**。
- 2026-09-04：用户指示提前起草 AM2/AM3 方案并要求详细版（每设计点带参照/思路/弊端/注意）→ `docs/告警AM2-技术方案.md` v2.0（订单靶场：两步创单/三单/废单/三类故障注入/ground truth）与 `docs/告警AM3-技术方案.md` v2.0（确定性评测 + notify-app 出口 + LiteLLM proxy 收口）完成，均标注"待前序 G2 后正式送 G1"。
- 2026-09-04：用户转来两批外部架构调研，指示先调研找差距再采纳 → 主会话一手核查发现三个差距（①Spring AI ToolCallingAdvisor 为 2.0 特性绑 Boot 4，1.1 才兼容本项目 Boot 3.4；②kagent/K8sGPT/Kyverno/Helm 系 K8s 环境假设，docker 靶场不适用，降为迁移储备；③agentgateway CEL 授权空规则全放行）→ 其余声明属实采信。**架构基线升 v2**（AA-14~26 + §6~§12 新增章节）；**AM1 方案升 v2.2**（V7 增 rca_task_edge 变 10 表 + task 预留列 + 四契约版本化 + 报告状态链预留 + §15 线程池预算 + DP-B 接 E2E 证据契约）；证据入 E-13。
- 2026-09-04：用户问"无 K8s 替代方案"→ 专项调研 `docs/告警-调研-无K8s替代-v1.md` 产出并固化：**架构基线升 v2.1**（§8 安全增补 conftest 静态门 + docker-bench-security 运行时门 = "穷人版 Kyverno"；gVisor 内核 3.10 确认不可用；§12 工具层基线修正为 Holmes 自带只读 toolset + 可选 prometheus-mcp 官方组织版/DomainProbe 只读 API；预诊断走自研 DomainProbe + docker inspect/events）。证据入 E-14。新增常驻内存 <150MB。
- 2026-09-04：编码方报"AM1 都修复了"→ 主会话复核：构建转绿 + 6 条修复属实（gzip/deadline 重算/自检授权/slot scope/状态机接线/构建），**3 条未收口**（compose 环境变量名仍与 yml 漂移、publisher/sandbox 目录与 ExecutionLedger 残留、IT 套件为空 "No tests to run"）→ 记 BA-09 开放，AM1 仍未过 G2。同日：用户指出架构设计缺多 Agent 全案与 HolmesGPT 借鉴地图 → **架构基线升 v2.2**（新增 §14：分层定案对照表/Supervisor=状态机/HolmesGPT 直接用-抄机制-逐块替换三分法）。
- 2026-09-04：用户转来对照真实代码基线的 AM2/AM3 评审（7 阻断项 + 代码前置问题 + M2/M3 补充）→ 主会话抽查关键声明（EvidencePackageValidator 自由文本/RcaReport 非空约束/HolmesClient 只取 body/compose 服务清单）全部属实 → **AM2 方案升 v2.1**（§15：chaos 开关 DB 化 oa_chaos_session、eval-mgmt 私有管理网、Gauge/Counter 双族 + 探测去重、补偿改 PG outbox、幂等记录完整字段、scenario→report 映射表、GT 权限隔离、F3 持久化状态机、线程预算）；**AM3 方案升 v2.1**（§15：EvidencePackage v2 结构化契约、失败调查落档、通知降级 at-least-once、tool_calls 提取 + LiteLLM 一对多对账、报告不可变 + report_publication、通知白名单渲染、eval-runner 独立身份、5 场景枚举、评分公式写死）；新冲突记 BA-10（Holmes key 桥接缺失 + rca_report 自检/授权矛盾）。AM2/AM3 动工仍待 AM1 G2。
- 2026-09-04：用户批评"先调研核对现有代码再修正"→ 主会话派 agent-64 全量盘点当前工作区，**纠正两处前判**：① 主会话曾误判"状态机已接线"（实际仅 1 处生产调用，三台状态机零调用，记 BA-11① 认账）；② 评审"EvidencePackageValidator 全自由文本"已过时（实际为六段式结构 + schema_version 路由，缺的是类型化 root_cause/claims）→ AM3 §15.1 措辞校正。另确认：publisher/sandbox 仅剩 target 残留；ExecutionLedger 链被 ModelGateway 依赖不宜蛮删（T00 保留清单修订，AM1 方案 v2.3）；V7 落码为 9 表，v2.2 的 rca_task_edge+预留列属未实施增量；AM1 告警域 IT 为空（BA-09③）。
- 2026-09-04：评审第二轮（§15 补丁与正文双轨不可接受 + 5 阻断项）→ **AM2/AM3 规范归一重写为 v3.0**：补丁全部合入正文（任务表/类设计/时序图/不变量/测试/DoD 单轨语义）；五项阻断落地——① 正文归一 ② 部署形态冻结（新建 deploy/alert/docker-compose.yml 为告警栈唯一事实源 + AM0 手工配置回收进 git + deploy/docker-compose.yml 分工） ③ DB 角色与迁移所有权矩阵（arena_app/eval_app/notify_app + 01-roles.sh 扩展 + GT 对 control/Holmes 禁读） ④ InvestigationResult/rca_tool_call 冻结 DDL（V8，每次 attempt 必落档） ⑤ 三指标评分公式（coverage/conditional_accuracy/end_to_end_hit_rate + UNRESOLVED 单列）；另冻结 chaos 激活事务四 DB 约束、通知触发点（结构验证+候选标记）、AM1 上游能力以代码盘点为准（M2/M3 不假设 v2.2 增量已落码）。
- 2026-09-04 晚：磁盘新增五份文档（另一会话产出）——`架构设计-告警Agent-v1.2.md`（FUT-01~55 目标架构，自述"不覆盖 v1、设计 PASS 待落码"，含双机 195+2C4G 部署、六维评测、Quality Gate、AM2~AM6 路线）、三份 `架构评审-*.md` 裁定记录（采纳项已写入 v1.2，声明不改 AM2/AM3 冻结语义）、`告警Agent-增量实现任务拆解-v1.md`（96 任务执行计划，当前只释放 G0）。主会话全部消化核对：与现有文档无致命冲突（报告两轴状态/AM3 只产 Top-1 等与 AM3 v3.0 一致）；**G0 任务清单与 BA-09/BA-10/BA-11 完全同构**——缺口清单两侧独立互证。
- 2026-09-04 晚：主会话代码审计（agent-67）：P0×3（BA-09/10 未修确认）+ P1×4 新发现（episode 乱序漏洞/Holmes 响应限读/UNKNOWN 语义无触发点/Hikari 无显式配置，记 BA-12）+ P2×4 优化（记 BA-13）；核实无缺口六项（限长拒绝不截断/202=已持久化/429 不落队列/外部调用零事务/悬挂回收路径/claim 索引齐全）。用户问"方案有没有修正或不足"→ 主会话自纠六条（文档跑在代码前/任务编号双轨/Holmes 未验证假设/内存未实测/决策治理成本/评测统计意义），用户裁定两条立即固化。
- 2026-09-04 晚：**用户确认"架构基本定了"**——架构定格（v2.3）：① G0 收口前不再新增设计文档；② `告警Agent-增量实现任务拆解-v1.md` 为唯一执行任务表，AM1/2/3 方案降为设计依据文档；③ AA 系列封版，演进走 v1.2 FUT 系列。后台 harness 设计调研启动（服务 AM4 Native 内核）。
- 2026-09-04 晚：用户指示"给出下一步技术方案让执行者开始编码"→ 主会话出具 `docs/告警G0-收口技术方案.md` v1.0（G0-01~12：基线 manifest/配置键统一/Holmes key 桥接/rca_report 自检/状态机三线接线 + episode 乱序修复/V8 迁移增量 + PG IT/AM0 配置回收 + Holmes 运行时三修复/195 单链路 E2E + 崩溃演练/G2 复核/BA-13 顺带优化），可直接转执行者。**注意迁移编号裁定**：G0 增量用 V8，AM3 eval/notify 顺延 V9（AM3 v3.0 §6.1 引用待编码时同步）。
- 2026-09-04 晚：harness 设计调研回收 `docs/告警-调研-Harness设计-v1.md`（35KB）——最值得抄 5 条（Claude Code 权限 harness 强制执行/Codex safety.rs 三态判定/MCP 治理三件套/Holmes spill-to-disk 上下文预算/工具宁少勿多按工作流聚合）+ 拒绝 3 条（Pi YOLO 无权限/auto 模式 LLM 代审/OpenHands 平台化架构）；MCP 接入施工图齐备（命名空间/白黑名单/延迟加载）。证据入 E-15，全部作 AM4 设计素材，不动已定格基线。
- 2026-09-04 晚：用户指示开始 G1 技术方案 → 主会话出具 `docs/告警AM2-落码技术方案.md` v1.0（AM2 订单靶场施工图，对齐任务树 M2-01~28 四阶段：工程底座/正常订单切片/故障叠加/三场景 E2E+G2，每任务文件级改动点 + 验收命令；设计依据 AM2 v3.0；动工硬前提 = AM1 G0 过 G2），可转执行者备料。
- 2026-09-04 深夜：**G0 收口执行完毕**（执行依据 `告警G0-收口技术方案.md` v1.0）：G0-01~10、G0-12 全部单项验收通过；G0-10 单链路 E2E 195 真栈全绿（flagd 注入→Sloth firing→AM 投递→webhook→投影→Holmes 真 LLM→结构验证报告→SIGKILL 崩溃演练→真实 resolved 归并，全流程 5 截图+DB 终态快照）；**BA-14（response_format 被忽略→散文拒收）/BA-15（bash 工具诱导 kubectl+prometheus 工具集静默禁用）双修复出自 E2E 实证**；G0-12 四条优化带单测并部署 195。缺陷账：BA-09~17 关闭、BA-18 纪律固化、BA-20/21 开放观察项移交 AM2/AM3。本地全量 215 tests 0 fail（2 skip=需 docker 的 PG IT），195 真 PG IT 真跑。全部成果**单个 commit 落账（不 push）**；G0-11 G2 复核材料即该 commit，待用户终审后进 AM2。
