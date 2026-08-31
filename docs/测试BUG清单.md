# 测试 BUG 清单（测试执行期专用账本）

> ⚠️ 本文件**不是** `BUGLOG.md`（那是主会话的开发缺陷账本，测试员无写权）。
> 测试执行期间发现的一切 bug **只追加到本文件**，编号 `TB-NN` 递增，条目只增不删不改。
> 每条必须含故障卡六要素；证据贴原文，禁止胡编乱造。
> 修复与归并回 `BUGLOG.md` 由主会话决定，测试员不做。

## 状态说明

- `待主会话确认`：测试员登记，尚未被主会话核对
- `已确认`：主会话已核对并接受
- `已修复待回归`：主会话修复后，等待全量回归
- `已关闭`：回归 PASS

## 环境类偏差（登机即发现，非用例触发，预登记）

### TB-01 webhook 公网入口不通，与背景文档矛盾

- **状态**：已关闭 ｜ **发现时间**：2026-08-31 ｜ **触发用例**：无（登机核对）
  ｜ **主会话裁定（08-31）**：背景文档 v1.1 已写明 stub 模式 loopback 为既定设计、
  真实模式公网入口由 `CONTROL_BIND=0.0.0.0` 控制——文档缺陷已修复，本条关闭。
- **现象**：文档称公网 `http://146.56.195.225:8080/webhooks/github` 已连通；实测 8080 仅绑
  `127.0.0.1`，公网访问 connection refused（curl 退出码 7），服务器无 80/443 监听。
- **复现步骤**：
  `ssh -i C:\Users\wangp\.ssh\id_ed25519 root@146.56.195.225 "ss -tlnp | grep -E ':8080|:80 |:443 '"`
  ；在任意外网机器 `curl -v http://146.56.195.225:8080/webhooks/github`
- **输出**：`LISTEN 0 511 127.0.0.1:8080 *:* users:(("docker-proxy",...))`；public:000 / 退出码 7
- **影响面**：所有依赖 GitHub 真实 webhook 回调的 E2E 用例将 BLOCKED。
- **初步猜想**（仅为猜想）：compose 端口映射被改为 loopback，或公网入口另由未配置的反代承担。

> **补充核对（2026-08-31，会话 2）**：交接文档 v1.0 §1 明示"webhook 注入入口（stub 模式只绑
> loopback）：`http://127.0.0.1:8080/webhooks/github`"——stub 模式下 loopback 绑定是主会话
> 的既定设计，本条降级为"背景文档 v1.0 未写明 loopback"的文档缺陷；对 M1 测试矩阵无阻塞
> （阶段 A/B/C 均在 195 本机注入）。真实模式下公网入口如何恢复属主会话决策，待其确认。

### TB-02 M1 迁移未部署：pr_agent 库无 webhook_inbox 表

- **状态**：已关闭 ｜ **发现时间**：2026-08-31 ｜ **触发用例**：无（登机核对）
  ｜ **主会话裁定（08-31）**：登记时点早于 T09a 部署完成；V3 已应用、表已存在，本条关闭。
- **现象**：195 上 `pr_agent` 库查询 `webhook_inbox` 报 `relation does not exist`；
  本地仓库存在未提交的 `V3__m1_inbox_reconcile.sql`。
- **复现步骤**：
  `ssh ... root@146.56.195.225 "docker exec deploy-postgres-1 psql -U postgres -d pr_agent -tc \"SELECT count(*) FROM webhook_inbox\""`
- **输出**：`ERROR: relation "webhook_inbox" does not exist`
- **影响面**：M1 全部功能用例（收件箱/重试/租约/三 Reconciler/Draft 预检）在当前栈上 BLOCKED，
  需主会话先完成 M1 部署再出具交接文档。
- **初步猜想**（仅为猜想）：M1 代码尚未构建部署到 195，当前跑的是 M0 骨架。

> **补充核对（2026-08-31，会话 2）**：PROGRESS 记载 T09a 已完成 V3 实库应用与四容器起标。
> 开工核对实测 `webhook_inbox` 表存在且 count=0（业务表已清零），本条**已消除**。
> 原登记保留作历史，不改不删。

### TB-03 DP-02 断言 FAIL：自检拒启后容器瞬态 running 被判"自检门失效"（疑似脚本竞态）

- **状态**：已关闭 ｜ **发现时间**：2026-08-31 10:24（阶段 B 轮次）｜ **触发用例**：DP-02（smoke-test.sh）
  ｜ **主会话裁定（08-31 二轮）**：执行方全量回归中独立复验 DP-02 四断言全 PASS
  （证据 `smoke-evidence/20260831-113355`），修复确认有效，本条关闭。
  ｜ **主会话裁定（08-31）**：猜想证实——**产品自检门行为正确**（注入写凭证 → 自检抛异常 →
  优雅关停 → exited/ExitCode=1，故障卡 ③ 的日志链完整）；FAIL 根因为脚本断言竞态：
  `docker compose run` 一次性容器**不继承** unless-stopped 重启策略，脚本"RestartCount>0 兜底"
  假设不成立，且"检出失败日志→立即 inspect"窗口内必采样到 running。已修复：
  DP-02 改轮询等终态（exited，30s 超时）+ 断言非零退出码；主会话复跑 DP 门禁
  **PASS=77 FAIL=0**（证据 `smoke-evidence/20260831-105403`）。归入 BUGLOG `INC-28`。
  **待执行方全量回归（A→B→C）后关闭，阶段 C 八条解封。**

> **执行方复验（2026-08-31 03:33 UTC）**：全量回归中独立复跑修复后脚本，DP-02 四断言全 PASS
> （`[PASS] 注入写凭证的 control 被拒启（State=exited ExitCode=1）`，证据 `smoke-evidence/20260831-113355`）。
> 修复确认有效。本轮复跑出现的另一处 FAIL 属新问题 → TB-06。
- **影响面**：阶段 B 计数 76/77；按交接文档 §2 门禁条款连锁 BLOCKED 阶段 C 全部 8 条用例（E2E-11/13/16/17/19/21/23/24）。
- ① **现象**：期望 DP-02 四断言全 PASS；实际第 4 条 FAIL——`dp02-control 稳定运行中（State=running RestartCount=0），自检门失效`。
- ② **复现步骤**：`cd /opt/build/pr/deploy && bash smoke-test.sh`；或手工复现（已执行）：
  `docker compose run -d -T --no-deps --name dp02-tb03 -e GITHUB_WRITE_TOKEN=tb03-fake control-app`，
  0.2s 轮询 `docker logs` 至出现"启动自检失败"后立即 `docker inspect`，再 `sleep 5` 后复查。
- ③ **退出码与输出**：
  ```text
  DETECTED-at-loop-iteration=43
  IMMEDIATE-Status=running-Restarts=0
  AFTER5s-Status=exited-Restarts=0-ExitCode=1
  AFTER15s-Status=exited-Restarts=0-ExitCode=1
  ```
  脚本轮次日志链：`IllegalStateException: [control] 启动自检失败（B25 运行时门）：检测到写凭证环境变量 GITHUB_WRITE_TOKEN` → `Application run failed` → Graceful/Hikari 关停完成（02:23:32.570→.709）。
- ④ **环境快照**：四容器 Up；一次性容器 dp02-control（脚本）/dp02-tb03（复现）均已删除核实；
  涉事表无行（DP-02 不触库）。证据文件：`smoke-evidence/20260831-102318/dp02-control.log`、`summary.txt`。
- ⑤ **时间线**：容器起（~8.6s JVM 启动）→ 自检抛异常 → 优雅关停约 300ms → 进程退出（ExitCode=1）；
  脚本在"检出失败日志→立即 inspect"窗口内采样到 running。
- ⑥ **初步猜想**（标注：猜想，不作为修复依据）：**产品自检门行为正确**（5s 后稳定 exited/ExitCode=1，
  日志链完整）；FAIL 根因为 smoke-test.sh DP-02 断言竞态 + 兜底条件失效
  （`docker compose run` 一次性容器不继承 unless-stopped，`RestartCount>0` 恒不触发；
  脚本第 115~121 行注释假设有误）。修法方向：改为轮询等待容器终态（exited 或超时 FAIL）。
  判归（产品 or 脚本）由主会话裁定。

### TB-04 publication_resource 现象：163 资源被 Drift 标 MISSING，其余 stub PR 全无资源行

- **状态**：已关闭 ｜ **发现时间**：2026-08-31 03:0x UTC（阶段 C 旁路观察）｜ **触发用例**：E2E-17 断言核查时发现
  ｜ **主会话裁定（08-31 二轮）**：猜想证实，两个机制均为 **stub 保真度伪影，非产品缺陷**——
  ① 163 两行被标 MISSING：`probe-list-checks-for-sha` 映射恒空（交接文档已知局限 §3-2），
  sanity 读通过 → 按设计标 MISSING，真实 GitHub 探测可得；
  ② 其余 PR 零资源行：stub 对所有 PR 返回**同一固定 remote_id**，`insertResource`
  的 `ON CONFLICT (resource_type, remote_id) DO NOTHING`（PostgresPublicationStore:604）
  把后续登记全部幂等吞掉；真实 GitHub 的 remote_id 全局唯一不会冲突，且 confirm 与
  reconcileConfirm 两条路径都登记资源（同文件 :186/:316），登记路径无差异。
  已补入交接文档 §3 已知局限第 5 条。本条关闭。
- **影响面**：无直接用例 FAIL（E2E-17 的"无 MISSING"以"917 无资源行"成立）；影响 stub 模式下 Drift 语义解读。
- ① **现象**：期望资源登记/观测一致；实际仅 PR#163（阶段 B DP-05 轮）有 2 行 publication_resource
  且 7 秒后被 DriftReconciler 标 `MISSING`；其余全部已 CONFIRMED 的 PR（231/720/828/901/911/913/916/917/919/924）
  **零资源行**。
- ② **复现步骤**：
  `docker exec -i deploy-postgres-1 psql -U postgres -d pr_agent -tAc 'SELECT resource_type,state,created_by_operation_id,drift_detected_at FROM publication_resource ORDER BY created_at'`
  及按 subject LEFT JOIN 计数。
- ③ **输出**：
  ```text
  CHECK_RUN|MISSING|2e585d09-…（DP-05 CREATE_CHECK op）|drift_detected_at=02:24:07.249
  REVIEW|MISSING|3c8724bf-…（DP-05 PUBLISH_REVIEW op）|drift_detected_at=02:24:07.264
  （created_at=02:24:00，drift 晚 7 秒；next_check_at=+8h）
  按 PR 计数：163=2，其余全 0
  ```
- ④ **环境快照**：stub 模式；probe-list-checks-for-sha 映射恒返回空（交接文档 §3.2 既有事实）。
- ⑤ **时间线**：publisher 发布成功（02:24:00 登记资源）→ Drift 首轮探测（02:24:07）probe 恒空 → MISSING。
- ⑥ **初步猜想**（标注：猜想）：163 的 MISSING 为 stub probe-list 恒空的**预期伪影**（真实 GitHub 探测可得）；
  但"仅 163 有行、同轮 828 及后续全部 PR 无行"的登记路径差异需主会话解释（可能与命令确认路径
  直发/崩溃重放的差异有关）。若为预期，建议补进交接文档已知局限。

### TB-05 E2E-19 注入配方不可执行：control 派发依赖 publisher token 端点（架构耦合）

- **状态**：已关闭 ｜ **发现时间**：2026-08-31 03:12 UTC（E2E-19 执行中）｜ **触发用例**：E2E-19（BLOCKED）
  ｜ **主会话裁定（08-31 三轮）**：执行方按新配方复验 **PASS**（停机 90s 排队不死信 → 恢复后
  attempt3 恰好一次排空 → REVIEW_COMPLETE → 双 CONFIRMED → journal 1/1），本条关闭。
  ｜ **主会话裁定（08-31 二轮）**：猜想证实，非产品缺陷——control 一切 GitHub 读借道
  publisher token 窄接口是 I2 拓扑的既定设计，原配方在本架构上不可执行。处置 = 配方重设计
  （交接文档 E2E-19 行已重写）：栈级场景改为"停机期间 inbox 排队不死信不丢失 → 恢复后
  恰好一次排空 CONFIRMED"（该行为已在 TB-05 ③/⑤ 取证中实际观察到）；换届 fence/级联
  语义声明由 publisher 侧 IT 覆盖（ST-02 push 换届 / CT-06 级联 / ST-19 T-close·T-draft
  扫尾），栈级不重复。**待执行方按新配方回归后关闭。**
- **影响面**：E2E-19 一条 BLOCKED；交接文档 §2 该行配方需重设计。
- ① **现象**：期望"停 publisher 期间注入 opened+synchronize 形成两世代"；实际 publisher 停机后 control 无法派发任何事件
  （也无法对账换届），事件全部 RETRY_WAIT。
- ② **复现步骤**：`docker stop deploy-publisher-app-1` → 注入 PR#919（签名 webhook）→ 查 inbox 与 control 日志。
- ③ **退出码与输出**：
  ```text
  RETRY_WAIT|2|next=+60s|{"kind": "dispatch_failed", "message": "UncheckedIOException: 只读 token 窄接口调用失败"}
  03:12:47 WARN inbox 派发失败转 RETRY_WAIT delivery=e2e19-d1 attempt=1/5 backoff=30s
  03:13:17 WARN ... attempt=2/5 backoff=60s
  ```
- ④ **环境快照**：publisher Exited（用例注入）；control Up；`application-docker.yml`：
  `token-endpoint: ${PUBLISHER_TOKEN_ENDPOINT:http://publisher:8081}`（T14 只读 token 窄接口）。
- ⑤ **时间线**：stop publisher → 注入 202 受理（落库不依赖 token）→ claim → dispatch 需权威读 → 取 token 失败 →
  RETRY_WAIT；恢复 publisher（03:15）后 attempt3 成功排空：PROCESSED、REVIEW_COMPLETE、两命令 03:16:22 CONFIRMED。
- ⑥ **初步猜想**（标注：猜想）：非产品缺陷，是**交接文档配方与架构事实不符**——GitHub App 凭证只在 publisher（I2），
  control 一切 GitHub 读借道 token 端点。改法建议：(a) 预置两世代 outbox 状态后停机，断言 fence/级联；
  (b) 承认 PENDING 窗口 <1s 不可捕；(c) 降级注明该面已由 publisher 侧 IT（ST02/ST19/E2E-19 同构）覆盖。

### TB-06 DP-01 迁移日志断言与 E2E-24 流程互斥（compose down 后永久失效）

- **状态**：已关闭 ｜ **发现时间**：2026-08-31 03:35 UTC（TB-03 修复后全量回归复跑）｜ **触发用例**：DP-01 第 2 断言
  ｜ **主会话裁定（08-31 三轮）**：执行方全量回归 DP 门禁 **PASS=78 FAIL=0**（证据
  `smoke-evidence/20260831-123444`），双态断言与 flyway 版本门禁（=3）均生效，本条关闭。
  ｜ **主会话裁定（08-31 二轮）**：猜想证实，测试设计缺陷（与 TB-03 同类：断言耦合环境态）。
  已修 smoke-test.sh DP-01：日志断言改双态（首启 `Migrating schema` / 稳态 `up to date` 均 PASS），
  并新增更硬的门禁断言——`flyway_schema_history` 已应用最大版本 == `control-app/.../migration/V*.sql`
  文件最大版本（版本号不再依赖日志存续，V4 落地后也不需要改脚本）。归入 BUGLOG `INC-29`。
  **待执行方回归复跑后关闭。**
- **影响面**：复跑计数 76/77；主会话 10:54 轮（ pristine 栈）为 77/0——差异全部由此条解释。
- ① **现象**：期望 `dp01-migrate.log` 含 `[Migrating schema]`；实际为
  `Schema "public" is up to date. No migration necessary.`（退出码断言 PASS）。
- ② **复现步骤**：执行交接文档 E2E-24（`docker compose down && docker compose up -d`）后再
  `cd /opt/build/pr/deploy && bash smoke-test.sh`。
- ③ **退出码与输出**：
  ```text
  [PASS] migrate one-shot 退出码（=0）
  [FAIL] migrate 执行了 V1/V2 迁移：.../dp01-migrate.log 不含 [Migrating schema]
  docker logs deploy-migrate-1 → Successfully validated 3 migrations / Current version ... 3 / up to date
  docker ps -a → deploy-migrate-1|Exited (0)|CreatedAt=2026-08-31 11:17:00 +0800（=E2E-24 重启时刻）
  ```
- ④ **环境快照**：flyway_schema_history 至 V3 未动；原首次启动 migrate 容器已随 compose down 销毁。
- ⑤ **时间线**：09:4x 首次起栈（含迁移日志）→ 10:23 / 10:54 两轮 smoke PASS → 11:17 E2E-24 down/up
  （交接文档明示动作）→ 11:33 复跑 FAIL。
- ⑥ **初步猜想**（标注：猜想）：断言依赖首启容器日志存续，属测试设计缺陷（与 TB-03 同类：环境态耦合）。
  修法方向：接受"首启 Migrating schema / 稳态 up to date"双态，或直查 flyway_schema_history 版本。

### TB-07 E2E-21：work_items 租约过期比较走应用时钟（M0 遗留，违反 I17 字面）

- **状态**：已关闭 ｜ **发现时间**：2026-08-31 05:0x UTC（第三轮 v1.2 E2E-21）｜ **触发用例**：E2E-21
  ｜ **第四轮复验（08-31）**：**PASS**——work_items 六条租约 SQL 全走 DB now()/make_interval；
  port 六方法签名已摘除 Instant 参数；PostgresWorkItemRepositorySqlGuardTest 在列且通过；
  反向核查仅余 PrStateReconciler:141/292 两处豁免内存节奏门；UPSERT 对象级保存用应用时钟属设计内
  （非租约比较）。**TB-07 关闭，TB 全关。**
  ｜ **主会话裁定（08-31 三轮）**：确认为真实违例（M0 遗留，I17 字面不符），**当期修复不挂账**——
  I17 是 M1 新增不变量，工序 5 纪律不允许带病验收；行为级补充（偏差≈0 + epoch 栅栏有效）
  证实危害当前潜伏，但风险条件（应用与 DB 时钟域分离）在 M7 多实例时必然兑现。
  修法：work_item 家族六条 SQL 的租约/过期比较与 `updated_at`/`lease_until` 写入全部改 DB
  `now()`/`make_interval`，port 签名摘除 Instant 参数。`PrStateReconciler.java:141/292` 两处
  为进程内内存节奏门（不参与共享状态比较），裁定**豁免留档**——多实例化（M7）时需改 DB 后盾，
  记入 M2 方案 §8 观察项。回归测试 = E2E-21（栈级 grep）+ 新增 SQL 文本守卫单测。

> **修复完成（2026-08-31，主会话）**：INC-30 已入 BUGLOG。改动 8 文件（port/PG 实现/
> WorkItemWorker/ReviewOrchestrator/InMemoryStores fake/WorkItemWorkerTest/publisher
> ItHarness + 新增 SqlGuardTest）。本机全 reactor 亲验 98+249+107 全绿（IT 无 docker 跳过，
> 留 195）。代码已 tar 同步 195 → maven 容器 package → 重建 control 镜像 → compose 重启
> control-app；栈四容器 Up/healthy、未签名探针 401、启动自检通过。**待执行方第四轮
> 全量回归（A→B→C，重点 E2E-21 复验 + 阶段 A 195 真跑 IT）后关闭。**
- **影响面**：E2E-21 一条 FAIL（M1 新增路径全部合规，违例集中在 M0 的 work_items 租约路径）。
- ① **现象**：断言"一切过期/退避比较走 DB now()（I17）"；实际 `claimNext`/`findExpiredLeases`
  把应用侧 `Instant.now()` 作为 SQL `:now` 参数参与 work_items 租约比较。
- ② **复现步骤**：
  `grep -rn -iE 'Instant\.now' control-app/src/main/java --include=*.java | grep -iE 'lease|retry|expire|reconcile|next_'`
  → 4 行；再读 `PostgresWorkItemRepository.java:190-220`（`.param("now", Timestamp.from(now))`）。
- ③ **退出码与输出**：
  ```text
  WorkItemWorker.java:91  claimNext(workerId, Instant.now(), maxLeaseSeconds)
  WorkItemWorker.java:99  findExpiredLeases(Instant.now(), recoveryScanLimit)
  PrStateReconciler.java:141/292  Instant.now()（429 暂停游标，内存节奏门，判设计允许）
  ```
- ④ **环境快照**：M1 路径（webhook_inbox 全家族 + pr_subject 对账家族）SQL 原文全部
  `now()/make_interval` 合规；work_items 为 M0 遗留。`git show HEAD:...WorkItemWorker.java`
  第 91/99 行即如此——**非本轮引入**。
- ⑤ **时间线**：第二轮 E2E-21 曾报"应用时钟零命中"——本轮用正确正则复核发现该结论是
  执行方 grep 过度转义（`Instant\\.now` 匹配字面反斜杠）造成的假阴性，特此更正。
- ⑥ **初步猜想**（标注：猜想）：危害有界（lease_epoch 栅栏拦早接管的旧写），但 I17 字面不符。
  处置二选一：改 SQL 用 DB now()（三处），或挂账"M0 残余 + 栅栏缓解"并写入冻结文档残余风险。
  另请裁定 PrStateReconciler 暂停游标两处是否与"应用时钟只驱动节奏"注释同权豁免。

> **执行方行为级补充（2026-08-31 05:06~05:31 UTC，应用户"M0 旧账本次也要测"指示）**：
> ① 时钟偏差量化（PR#946 探针）：`work_item.lease_until − 600s` 与领取时刻自洽到毫秒，
> inbox（DB 时钟）与 outbox（应用时钟）流水线时间戳全部自洽；应用与 DB 同宿主机内核同源，
> **实测偏差 ≈ 0，违例当前潜伏无行为影响**（风险条件=应用与 DB 时钟域分离）。
> ② 接管行为实证（PR#948 插桩）：租约有效期内（05:22~05:30:17 十轮采样）**零提前接管**；
> 过期后 ~37s 完成 epoch 1→3 栅栏推进重认领并 REVIEW_COMPLETE——lease_epoch 栅栏对
> 早接管危害的缓解**实测有效**。证据 `测试记录.md` §TB-07 行为级补充验证。

## 用例触发 BUG（格式模板，按 TB-NN 递增追加）

```text
### TB-NN <一句话标题>
- 状态 / 发现时间 / 触发用例ID / 影响面（阻塞了哪些用例）
- ① 现象（期望 vs 实际，一句话）
- ② 复现步骤（完整命令序列）
- ③ 退出码与输出（尾部 ≤50 行原文）
- ④ 环境快照（docker ps / docker logs --tail / 涉事表 SELECT 原文）
- ⑤ 时间线（注入点 → 各事件先后）
- ⑥ 初步猜想（标注"猜想"）
```
