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

### TB-08 M2 首跑 St26 IT 装备错：CGLIB 无法代理 final 类（测试代码缺陷）

- **状态**：已修复待回归 ｜ **发现时间**：2026-08-31 12:19 UTC（M2 阶段 A 首跑）｜ **触发用例**：ST-26（St26CrashBeforeCheckpointTxIT）
  ｜ **主会话裁定（08-31）**：猜想证实，测试装备缺陷——`StCheckpointCrashCheckpointWriter` 声明了 `final`，
  而线束 `transactionalProxy` 走 CGLIB 类代理（与生产 docker profile 语义一致），CGLIB 无法子类化 final 类。
  本机无 docker 从未真跑，属交接文档已预告的"首跑盲区"典型样本，非产品缺陷。
  已修：去掉 `final`，类 javadoc 留痕。归入 BUGLOG `INC-40`。**待执行方全量回归后关闭。**
- **影响面**：阶段 A BUILD FAILURE 的两因之一；连锁 publisher IT 无法在同 reactor 执行（见 TB-09 备注）。
- ① **现象**：IT 初始化即报 `AopConfigException: Could not generate CGLIB subclass of class
  com.objwww.pr.control.it.StCheckpointCrashCheckpointWriter ... Cannot subclass final class`。
- ② **复现步骤**：195 上 `mvn -s maven-settings-aliyun.xml -pl control-app,publisher-app -am verify`。
- ③ **退出码与输出**（failsafe-reports 全文关键段）：
  ```text
  Tests run: 1, Failures: 0, Errors: 1, Skipped: 0, Time elapsed: 0.040 s
  org.springframework.aop.framework.AopConfigException: Could not generate CGLIB subclass of class
  com.objwww.pr.control.it.StCheckpointCrashCheckpointWriter: Common causes ... final class or a non-visible class
    at ...CglibAopProxy.buildProxy(CglibAopProxy.java:238)
    at com.objwww.pr.control.it.StCheckpointHarness.transactionalProxy(StCheckpointHarness.java:168)
  Caused by: java.lang.IllegalArgumentException: Cannot subclass final class
  com.objwww.pr.control.it.StCheckpointCrashCheckpointWriter
  ```
- ④ **环境快照**：maven:3.9-eclipse-temurin-21 容器、JDK 21、Spring Boot 3.4.5 AOP；
  StCheckpointHarness:168 用 ProxyFactory 给 final 类造事务代理。
- ⑤ **时间线**：首跑（12:16~12:19Z）control surefire 301 全绿 → failsafe 52 条中本条 0.04s 即错。
- ⑥ **初步猜想**（标注：猜想）：测试装备缺陷（类被声明 final，CGLIB 无法子类化）——首跑警告
  预告的"从未真跑过的盲区"典型样本；修法方向：去掉 final / 改接口代理 / 换非代理注入方式。

### TB-09 M2 首跑 St30 IT：续跑路径与冷路径 Step 产出 digest 不一致

- **状态**：已修复待回归 ｜ **发现时间**：2026-08-31 12:19 UTC（M2 阶段 A 首跑）｜ **触发用例**：ST-30（St30CheckpointPathEquivalenceIT）
  ｜ **主会话裁定（08-31）**：判归**测试设计缺陷**，非产品缺陷——`finding_fingerprint = SHA256(head_sha|…)`
  是 FindingMapper 明示契约，而测试让 Run X（head-st30-x）与 Run Y（head-st30-y）头不同，
  digest 不等是设计内必然，原断言不可满足；测试自身在 outbox payload 对比处已把 head_sha
  列为易变键剔除，唯独漏了 Step 产出与 review_finding 两处含 fingerprint 的对比面
  （findingsOf 还含 fingerprint 列，不修则下一断言接着炸）。已修：Step 产出改 CAS 回读 +
  剔除 findings[].fingerprint 后 JSON 树逐字段比较；findingsOf 剔除 fingerprint 列。
  等价性强度不稀释（其余字段仍逐字段全等）。归入 BUGLOG `INC-41`。
  ｜ **第二轮复验（08-31）**：FAIL——修复不完整，`normalizedPayloads` 漏剔嵌套的
  findings[].fingerprint（唯一差异 `PUBLISH_REVIEW.findings[0].fingerprint`）。
  执行方诊断精确，照单修复：新增递归 `stripFingerprints`，payload 与 Step 产出两处
  对比面共用；BUGLOG INC-41 已续记。**仍待第三轮全量回归关闭。**

> **执行方第二轮复验（2026-08-31 14:1x UTC）：FAIL——修复不完整，还有第三处漏剔**。
> 修复后 `normalizedStepOutput`/`findingsOf` 两处断言已通过，但断在**第三处**
> `normalizedPayloads`（St30CheckpointPathEquivalenceIT.java:110，as 文案"outbox payload 剔除易变键后逐字段一致"）：
> expected/actual 的唯一差异是 `PUBLISH_REVIEW.findings[0].fingerprint`
> （`920cfae4…70212` vs `5948c630…d2de6`）——两 Run 头不同（head-st30-x/y），fingerprint 含
> head_sha（FindingMapper.java:29 契约），必然不等。**裁定文自身已点明"payload 对比处已把
> head_sha 列为易变键剔除"，但 payload 内嵌的 findings[].fingerprint 是 head_sha 的衍生值，
> 修复只处理了顶层易变键**。修法方向：`normalizedPayloads` 对 findings[] 同样剔除 fingerprint
> （与 normalizedStepOutput 同一原则）。其余 121 条 IT 全绿（TB-08/TB-10 复验 PASS）。
- **影响面**：阶段 A BUILD FAILURE 两因之一；若为产品缺陷则波及 M2-P1（checkpoint 复用等价性）主张。
- ① **现象**：断言"两路径 Step 产出 digest 相同"失败：expected `e2239a18…e857`，
  actual `3e1379b6…d78`。
- ② **复现步骤**：同 TB-08 命令；报告文件 `control-app/target/failsafe-reports/com.objwww.pr.control.it.St30CheckpointPathEquivalenceIT.txt`。
- ③ **退出码与输出**（全文关键段）：
  ```text
  Tests run: 1, Failures: 1, Errors: 0, Time elapsed: 0.082 s
  org.opentest4j.AssertionFailedError: [两路径 Step 产出 digest 相同]
  expected: "e2239a18a47a06843843ac65d90e106740db84b617da5835aa44086162ece857"
   but was: "3e1379b689049ca19ad26d1c8343e43c8f0b74da2f86a26e6e46eddc813e1d78"
    at St30CheckpointPathEquivalenceIT.resumedPathIsEquivalentToColdPath(St30CheckpointPathEquivalenceIT.java:95)
  ```
- ④ **环境快照**：同 TB-08；两 digest 值均为确定性 64 位十六进制（形似 sha256）。
- ⑤ **时间线**：0.08s 内完成冷/续跑两路径并断言 digest——失败在等价性比对，非崩溃。
- ⑥ **初步猜想**（标注：猜想）：两可能——(a) 夹具非确定性（输出含时间戳/随机数导致两路径天然不等）；
  (b) 产品缺陷（续跑重建产物与冷路径产出确有差异）。执行方无法从输出区分，需主会话判归。
- **备注（连带发现）**：publisher-app 的 IT 测试作用域依赖 `com.objwww.pr:control-app` jar
  （reactor 中 control 失败即 publisher SKIPPED）——本轮阶段 A 因此无法取得 publisher IT 基线；
  执行方已尝试 install-skipTests + `-f publisher-app/pom.xml verify` 补测（结果见测试记录）。

### TB-10 M2 首跑 Ex24 IT：换届后第二轮 RepairPlanner 处理 0 行（期望 1）

- **状态**：已修复待回归 ｜ **发现时间**：2026-08-31 12:24 UTC（M2 阶段 A 补测）｜ **触发用例**：EX-24（Ex24RepairSupersedeRaceIT）
  ｜ **主会话裁定（08-31）**：判归**产品缺陷**——`findActiveByPrSubjectId`（M1 所写换届/幂等守卫/
  账本挂载共用查询）对 run_mode 无甄别，把 V4 新增的 REPAIR Run 也当"在途评审 Run"：
  换届扫描把它扫成 SUPERSEDED 后，repair 收口查询 `findTerminalRunOutcomes`
  （要求 `r.state='CREATED'`）永远匹配不上，方案 I27 设计的"EXPIRED→FAILED 收口"成为
  不可达死路径。修复 = `findActiveByPrSubjectId` 收敛为"活跃评审 Run（run_mode='NORMAL'）"
  语义（PG SQL + InMemoryStores fake 同步，接口 javadoc 明示）；REPAIR Run 终态由 repair
  收口链独占。顺带消掉两个潜伏次生害：close/draft 重投幂等守卫与 ReconcilerDegraded
  挂载点不再被滞留在途 REPAIR Run 干扰。回归测试 = EX-24 本身。归入 BUGLOG `INC-39`。
  **待执行方全量回归后关闭。**
- **影响面**：阶段 A BUILD FAILURE 第三因（publisher 侧唯一败例）；涉及 repair 换届收口链后半段。
- ① **现象**：断言 `newRepairPlanner().runOnce()` 期望 1 实际 0——fence 链前段全部通过
  （命令 `SUPERSEDED:STALE_EPOCH`、Projector 收敛 `EXPIRED:COMMAND_SUPERSEDED`、
  `REPAIR_EXPIRED` 事件恰 1），control 侧第二轮 Planner 未处理任何行（期望将 repair Run 收口 FAILED）。
- ② **复现步骤**：195 上先 `mvn -s maven-settings-aliyun.xml install -DskipTests`（绕开 control 失败的
  reactor 快速失败），再 `mvn -s maven-settings-aliyun.xml -f publisher-app/pom.xml verify`。
- ③ **退出码与输出**（failsafe-reports 全文关键段）：
  ```text
  Tests run: 1, Failures: 1, Errors: 0, Time elapsed: 0.179 s
  org.opentest4j.AssertionFailedError: expected: 1 but was: 0
    at Ex24RepairSupersedeRaceIT.repairCommandMintedThenRevisionSupersededIsFenced(Ex24RepairSupersedeRaceIT.java:89)
  ```
  （:89 逐行核对 = `assertThat(harness.newRepairPlanner().runOnce()).isEqualTo(1);`）
- ④ **环境快照**：同轮 publisher IT 69 条中其余 68 条全过（含 St31~38 repair 族与 CT21~29）——
  仅本条失败；报告文件 `publisher-app/target/failsafe-reports/com.objwww.pr.publisher.it.Ex24RepairSupersedeRaceIT.txt`。
- ⑤ **时间线**：0.18s 内完成竞态构造→fence 断言（过）→Projector 断言（过）→Planner 断言（败）。
- ⑥ **初步猜想**（标注：猜想）：第二轮 Planner 的待处理扫描条件与首轮 EXPIRED 后的
  repair_request/Run 状态不匹配（或测试编排未把该行置于 Planner 扫描面）——产品/测试两可，需判归。

### TB-11 DP-15/DP-19 授权断言用错函数：has_table_privilege 断列级授权恒 f；另 DP-15 一条 psql 输出逐字比对缺陷（4 条 FAIL 均脚本缺陷，授权实况正确）

- **状态**：**已修复待回归**（主会话裁定 2026-09-01：执行方定性成立——脚本缺陷两类，非产品缺陷。
  修复：表级断言改断"f"（列级授权不提升表级 ACL 即设计意图）+ psql 逐字比对改 `grep -qx` +
  DP-19 同型修正，详见 INC-43；第四轮全量回归复验）
  ｜ **发现时间**：2026-08-31 14:24 UTC（M2 第三轮阶段 B，证据目录 `smoke-evidence/20260831-222406`）
  ｜ **触发用例**：DP-15（3 条）、DP-19（1 条）
  ｜ **影响面**：阶段 B 门禁 13 条 FAIL 中的 4 条；C/D 阶段 BLOCKED 连带。
- ① **现象**：(a) `has_table_privilege('publisher_app','repair_request','INSERT'/'UPDATE')` 实测 `f`、
  脚本期望 `t`——但 V4 对 publisher 授的是**列级**权限，PG 语义下列级授权不提升表级 ACL，
  `has_table_privilege` 恒返回 f；断言函数与 V4 授权形态不匹配（`smoke-test.sh:406/407`，同型
  复制于 DP-19 `:633`）。(b) DP-15 行为面第 2 例断言把 psql 输出与 `PENDING|<null>|<null>` 逐字
  全等比对，但 psql 未加 `-q` 时必然回显 `BEGIN/SET/INSERT 0 1/ROLLBACK` 命令标签——断言
  在任何环境都必败；而回显内容本身恰好证明功能正确（INSERT 成功、state=PENDING、审批列空、已回滚）。
- ② **复现步骤**：195 上全量跑 `deploy/smoke-test.sh`（阶段 B）→ DP-15/DP-19 段确定性失败。
  单独验证授权实况：`docker exec deploy-postgres-1 psql -U postgres -d pr_agent -c
  "select has_table_privilege('publisher_app','repair_request','INSERT'),
  has_column_privilege('publisher_app','repair_request','state','INSERT'),
  has_column_privilege('publisher_app','repair_request','state','UPDATE')"` → `f | t | t`。
- ③ **退出码与输出**（`smoke-evidence/20260831-222406/summary.txt` 原文，行号同文件）：
  ```text
  95:  [FAIL] publisher 对 repair_request INSERT（列级授权→表级 t）：实际=[f] 期望=[t]
  96:  [FAIL] publisher 对 repair_request UPDATE（列级授权→表级 t）：实际=[f] 期望=[t]
  117:  [FAIL] publisher 列级 INSERT 异常：BEGIN
       SET
       INSERT 0 1
       PENDING|<null>|<null>
       ROLLBACK
  167:  [FAIL] 升级后 publisher 对 repair_request INSERT 有（列级授权）：实际=[f] 期望=[t]
  ```
  （DP-15 同段其余 12 条断言——含 `has_column_privilege` 列级 10 条与行为面整行 INSERT 被拒
  ——全部 PASS，反证授权面本身正确；DP-15 断言代码 `smoke-test.sh:406-407/441-444/283(tp)`。）
- ④ **环境快照**：`V4__m2_checkpoint_repair.sql:136-150` 为**列级**授权语法
  （`grant insert (id, publication_resource_id, resource_type, policy_tier, state, attempt_count,
  max_attempts, next_attempt_at, last_error, created_at, updated_at) on repair_request to
  publisher_app`，UPDATE 同为列级清单）；`information_schema.role_table_grants` 中 publisher_app
  对 repair_request 表级仅 SELECT。表级 ACL 与列级授权并存且互不包含，与 PG 权限模型一致。
- ⑤ **时间线**：阶段 B 单次顺序执行；DP-15（:95/96/117）与 DP-19（:167）同型写法同因失败，
  确定性复现，无竞态/环境因素参与。
- ⑥ **初步猜想**（已定性部分不标；残余判断标注：猜想）：两类均为脚本缺陷无需猜想——(a) 断言
  应改用 `has_column_privilege` 组合（或按列逐项断言，脚本中已有 `cp_` 助手即此）；(b) 逐字比对
  应改 grep 关键子串或 psql 加 `-q`。**残余猜想（归主会话裁）**：若断言本意是要求表级 INSERT/UPDATE，
  则与 V4 列级授权设计冲突，属方案层矛盾而非脚本笔误——需主会话按 DP-15 的列级行为断言全 PASS
  事实裁定断言改法。执行方未动任何脚本。

### TB-12 DP-18 修复闭环九条连锁 FAIL：M1 遗留固定 remote_id 资源行占用 uq_pub_resource + stub 固定 id 7000001 → 新资源登记被 ON CONFLICT DO NOTHING 静默吸收

- **状态**：**已修复待回归**（主会话裁定 2026-09-01：执行方定性成立——stub 保真度 × 环境态，
  非产品缺陷，ON CONFLICT 幂等吸收为 I26 设计内行为。采纳修复方向 (a)+(b) 组合并升级为
  结构性修复：① 遗留两行已在 195 一次性删除（DELETE 2，零 FK 引用）；② stub 创建映射改
  response-template 随机唯一 id（号段纪律入 m2-lib.sh 文件头），基线登记从此不撞库；
  ③ 脚本去硬编码 7000001/8000001（DP-18 改基线捕获、E2E-29/30A/32B 探针预注册 id 同源唯一）。
  详见 INC-44；第四轮全量回归复验。方向 (c) 产品侧 upsert 明确驳回：与 I26 冲突）｜ **发现时间**：2026-08-31 14:24 UTC（M2 第三轮阶段 B，
  证据目录 `smoke-evidence/20260831-222406`）｜ **触发用例**：DP-18（9 条 FAIL）
  ｜ **影响面**：阶段 B 门禁；**修复闭环（E2E-28 门禁化目标）实际未被验证到**——主库
  repair_request 至今 0 行；C/D 阶段 BLOCKED 连带。
- ① **现象**：DP-18 基线闭环 CONFIRMED=2 通过后，`m2_resource_of_op` 按
  `created_by_operation_id` 查不到 check 资源行（`op=fc66afd3-9415-4d0a-ab20-1723083d4976`，
  命令本体 CONFIRMED）→ RID18 为空 → 后续 8 条断言按空 id 查询全部"实际=[]"连锁失败；
  stub check-runs POST 计数 1（修复重建从未发生）。
- ② **复现步骤**：全 stub 模式跑 `deploy/smoke-test.sh` DP-18 段；前置条件=库内存在
  `(CHECK_RUN,7000001)` 遗留行（当前主库即有，M1 轮次遗留 MISSING 行）。stub 对任意
  `POST /repos/*/check-runs` 恒回固定 `id=7000001`（`wiremock/mappings/stub.json:30-35`，
  urlPathPattern 不区分 PR）→ 与遗留行撞 `uq_pub_resource(resource_type,remote_id)` 唯一索引
  → publisher 登记走 `ON CONFLICT (resource_type,remote_id) DO NOTHING` 被静默吸收。
- ③ **退出码与输出**（`smoke-evidence/20260831-222406/summary.txt:143-152` 原文）：
  ```text
  143:  [FAIL] DP-18 找不到 check 资源行（op=fc66afd3-9415-4d0a-ab20-1723083d4976）
  144:  [FAIL] 修复单档级 AUTO（CHECK_RUN 状态型）：实际=[] 期望=[AUTO]
  145:  [FAIL] 旧行 REPAIRED：实际=[] 期望=[REPAIRED]
  147:  [FAIL] 旧行原 remote_id 保留不覆盖（I26）：实际=[] 期望=[7000001]
  148:  [FAIL] 缺新 PRESENT 行
  149:  [FAIL] 新行 remote_id=重建的新远端对象：实际=[] 期望=[7120776]
  150:  [FAIL] repair 命令 CONFIRMED：实际=[] 期望=[CONFIRMED]
  151:  [FAIL] REPAIR Run 独立铸造（I27，不挂终态评审 Run）：实际=[] 期望=[REPAIR]
  152:  [FAIL] stub check-runs POST 恰 2 次（原始创建+修复重建）：实际=[1] 期望=[2]
  ```
- ④ **环境快照**：主库 `publication_resource` 仅 2 行且均为 M1 遗留
  （`CHECK_RUN|7000001|MISSING`、`REVIEW|8000001|MISSING`，关联 TB-04）；`pg_indexes` 含
  `uq_pub_resource UNIQUE btree (resource_type, remote_id)`；登记代码
  `PostgresPublicationStore.java:211/:285` 两处均为
  `INSERT ... ON CONFLICT (resource_type,remote_id) DO NOTHING`（冲突静默吸收、命令仍
  CONFIRMED——与 op=CONFIRMED 实测互证）；`dp18-stub-checks.json` journal count=1；
  主库 `repair_request` count=0。脚本自身注释（`smoke-test.sh:565-567`）明示知晓该冲突语义
  （"重建若还回 7000001 会 ON CONFLICT DO NOTHING"），但未料到**初始创建**路径同样固定
  7000001、且库内有 M1 遗留行占位。
- ⑤ **时间线**：基线 PR 闭环（webhook→模型→check+review 双 CONFIRMED）正常完成 → CREATE_CHECK
  发布成功但资源登记冲突被吸收（无 `created_by_operation_id` 可查）→ 脚本取 RID18 落空 →
  注入 stub 删 check-run 后，脚本按 `RID18=''` 等待 MISSING/修复单/收敛，全部空转过超时窗口
  → 9 条 FAIL。
- ⑥ **初步猜想**（机制链已闭合互证，残余判断标注：猜想）：链路证据闭环，无需补充猜想。
  **修复方向三选（均超出执行方权限，未动任何代码/数据，归主会话裁）**：(a) 清理 M1 遗留
  publication_resource 行（业务数据处置权在主会话，执行方不自行清库）；(b) stub 保真度改造：
  check-run 响应 id 改动态唯一/每轮重置，避免任意两轮共享 remote_id；(c) 产品侧登记冲突改
  upsert——但与 I26"旧行保留原 remote_id 不覆盖"设计在先冲突，动产品需慎评回归面。

### TB-13 M2-C 首跑核心：stub 探针不可见引发 drift-repair 无限重建风暴（128 单/125 行 REPAIRED 仍在增长）×跨用例污染（E2E-32A×1 + E2E-33×6 = 7 条 FAIL）

- **状态**：**已修复待第五轮回归**（主会话裁定：执行方定性成立——stub 保真度结构性缺陷，非产品缺陷；
  修复=probe-sync 探针联动机制（m2-lib.sh 守护+状态文件+原子换装），195 已止血清库重建，
  90s 观察无复燃 → INC-45；"修复链无全局节流"记压力点留 G2/M4）
  ｜ **发现时间**：2026-09-01 04:36 UTC（M2 第四轮阶段 C，证据 `smoke-evidence/e2e-20260901-040845`）
  ｜ **触发用例**：E2E-32-A（1 FAIL）、E2E-33（6 FAIL）
  ｜ **影响面**：阶段 C 门禁；风暴持续期间所有后续用例的 stub 计数类断言均可被污染（阶段 D 同样暴露）。
- ① **现象**：(a) E2E-32-A"退避窗口内无重试风暴（POST 恰 1 次）"实际 2——第二条 POST 来自
  E2E-31 遗留资源的风暴重建（journal 不分 PR 计数）。(b) E2E-33 六条：R1 缺新 PRESENT 行/
  R1 remote_id=[]、R2 行2 REPAIRED=[]、R2 缺第三轮 PRESENT 行、R2 行3 remote_id=[]、POST 5 vs 3。
- ② **复现步骤**：混合模式跑 `bash e2e-m2.sh all`（26/27/30 拒绝）；观察
  `repair_request` 行数随时间增长；E2E-33 段注入 `m2_post_check_override_on 7816915/7904335` 后查
  PR 25181（E2E-31）链。
- ③ **退出码与输出**（关键原文）：
  ```text
  E2E-32-A: [FAIL] 退避窗口内无重试风暴（POST 恰 1 次）：实际=[2] 期望=[1]
  E2E-33:   [FAIL] [R1] 缺新 PRESENT 行
            [FAIL] [R1] 新行 remote_id=新远端对象：实际=[] 期望=[7816915]
            [FAIL] R2：行2 REPAIRED：实际=[] 期望=[REPAIRED]
            [FAIL] R2：缺第三轮 PRESENT 行
            [FAIL] R2：行3 remote_id：实际=[] 期望=[7904335]
            [FAIL] stub check-runs POST 恰 3 次（原始+两轮重建）：实际=[5] 期望=[3]
  DB: repair_request total=128, last_5min=5, latest=21:08:06 UTC（仍在增长）
      publication_resource: CHECK_RUN REPAIRED=125 / MISSING=2, REVIEW MISSING=1（循环中）
      PR 25181 链 117 单全 REPAIRED，其中两行 remote_id=7816915/7904335（E2E-33 的显式 id）
  ```
- ④ **环境快照**：机制链——stub 探针静态恒空（GET check-runs by sha → `{"check_runs":[]}`）+
  INC-44 修复后创建/重建响应为随机 id（脚本无法预注册探针可见映射）→ 每个新建/重建对象
  下一轮巡检必判 MISSING → AUTO 修复单 → 重建 POST → 仍不可见 → 无限循环（~15s 周期）。
  E2E-33 的显式 id override 映射按 URL 全局匹配（priority 1）→ 风暴重建同享该 id →
  `(CHECK_RUN,7816915)` 被风暴行先占 → E2E-33 自身 POST 撞 `uq_pub_resource` 被
  `ON CONFLICT DO NOTHING` 吸收（PostgresPublicationStore.java:211/285）→ 链断裂。
- ⑤ **时间线**：E2E-31 `m2_check_present_remove` 起风暴点火 → 32A/33 案例窗口全程有背景 POST →
  33 注入 override 后 id 被风暴抢占 → 33 链断 → 至今（21:08+）风暴未停。
- ⑥ **初步猜想**（标注：猜想）：修复方向归主会话——(a) stub 保真度：创建/重建响应 id 与探针
  LIST 联动（无状态替身做不到"重建即可见"，需 runtime 注册探针可见映射的 hook，或探针按
  external_id 匹配 journal 已 POST 记录）；(b) 脚本：重建后轮询 DB remote_id 再即时 register；
  (c) 产品侧全局节流（修复链无跨单频控/熔断，真实现网同样存在"探针持续不见"的病态场景，
  值得评估）。**未动任何代码/数据**；栈保持风暴运行态（清理/停栈由主会话决定）。

### TB-14 E2E-29：崩溃恢复认领路径无 PUBLICATION_OUTCOME_UNKNOWN 留痕（EX-03 语义与实现缺口，判归两可）

- **状态**：**已修复待第五轮回归**（主会话裁定：**产品小缺口**——javadoc 承诺的事件未在扫描器
  路径落地，崩溃恢复与响应丢失同属"结果未知"EX-03；修复=`toReconciling` 补事件参数同事务落账
  → INC-46）｜ **发现时间**：2026-09-01 04:09 UTC（第四轮阶段 C）｜ **触发用例**：E2E-29
  ｜ **影响面**：阶段 C 门禁 1 条；可观测性语义（EX-03）。
- ① **现象**：写已达远端、CONFIRM 前 SIGKILL publisher → 恢复扫描认领 → 命令 CONFIRMED
  （探针认领，POST 恰 1 ✓ 资源恰 1 行 ✓），但 `PUBLICATION_OUTCOME_UNKNOWN` 事件 0（期望 1）。
- ② **复现步骤**：`bash e2e-m2.sh E2E-29`；或查 PR 23321 事件链。
- ③ **退出码与输出**：`[FAIL] PUBLICATION_OUTCOME_UNKNOWN 事件留痕：实际=[0] 期望=[1]`；
  DB 事件链：PUBLICATION_REQUESTED×2 / PUBLICATION_CONFIRMED×2 / RUN_CREATED 等——无 UNKNOWN。
- ④ **环境快照**（源码级）：`OutboxRecoveryScanner.sweepExpiredInFlight`（过期 IN_FLIGHT→
  RECONCILING）调 `store.toReconciling(...)` **不带事件**；`driveReconciling`→FOUND→
  `PUBLICATION_CONFIRMED(via=reconcile)` 亦无 UNKNOWN。该事件仅在活写路径
  `FencedPublicationExecutor`（GitHubTransportException→markReconciling:166/220）发射。
  而 `PublicationStore.java:76` javadoc 明写"→RECONCILING（响应丢失，EX-03）+
  PUBLICATION_OUTCOME_UNKNOWN 事件"——崩溃恢复等价于"响应丢失"，javadoc 语义未在扫描器落地。
- ⑤ **时间线**：POST 达 stub（延迟 15s 放大窗口）→ SIGKILL → 恢复 → IN_FLIGHT 租约过期 →
  RECONCILING（无事件）→ 探针 FOUND → CONFIRMED。
- ⑥ **初步猜想**（标注：猜想）：判归两可——测试期望有 javadoc/EX-03 依据；实现可能在
  v1.2 裁定中豁免了崩溃路径留痕。修复方向：扫描器路径①补事件，或修订用例期望（主会话裁）。

### TB-15 E2E-32-B：恢复扫描退避的 Retry-After 被 120s 缺省底线压制（+118s vs 期望 ≤+70s；写路径 34s 正常）

- **状态**：**已修复待第五轮回归**（主会话裁定：**产品缺陷**——I23 口径为"显式头听头的、底线只兜
  无头"，写路径 34s PASS 互证；修复=`settleReconcile` UNKNOWN 分支 floor 条件化，单测两案钉死
  → INC-47）
  ｜ **发现时间**：2026-09-01 04:2x UTC（第四轮阶段 C）｜ **触发用例**：E2E-32-B ｜ **影响面**：1 条。
- ① **现象**：探针吃 429+Retry-After=30 后，`reconcile_after - TOBS = 118s`；脚本判据
  `[TOBS-10, TOBS+70]`（30s 生效、缺省 120s 可区分）→ FAIL。同参数写路径（32-A）
  `next_attempt_at-T0=34s` PASS——仅恢复扫描路径异常。
- ② **复现步骤**：`bash e2e-m2.sh E2E-32`（B 段：m2_fault_on list-check 429 30 + 杀 publisher）。
- ③ **退出码与输出**：`[FAIL] 恢复扫描退避异常：reconcile_after=1788207855 TOBS=1788207737`（差 118s）。
- ④ **环境快照**（源码级）：`OutboxRecoveryScanner.settleReconcile` UNKNOWN 分支：
  exponential=backoff.nextAttemptAt(notFound+1, now, directive); configuredFloor=now.plus(unknownRetryDelay);
  reconcileUnknown(id, exponential.isAfter(configuredFloor) ? exponential : configuredFloor)——
  **无条件取 max**，`unknownRetryDelay`（缺省 120s）永远压制 Retry-After=30；`RetryBackoff` 本身
  能尊重 HonorRetryAfter（:31-32），被外层 max 抹平。
- ⑤ **时间线**：429 探针 → UNKNOWN verdict（携 directive）→ settleReconcile → max(≈30s, 120s)=120s。
- ⑥ **初步猜想**（标注：猜想）：若 I23 要求三调用点一致"Retry-After 精确生效"，此为产品缺陷
  （floor 应仅在无 Retry-After 时兜底）；若 120s 是安全下限设计，则用例判据过严。归主会话。

### TB-16 E2E-34：LIST 探针 404 被产品归为瞬时 UNKNOWN 退避——未走 sanity/权限告警路径，"自动复归"实为从未离开 PRESENT（2 条 FAIL 同根）

- **状态**：**已修复待第五轮回归**（主会话裁定：**用例注入形态错误**——LIST 探针端点级 404 归瞬时
  UNKNOWN 退避是 M1 既定裁决且方向安全，产品不动；E2E-34 改注"对象摘除+sanity 404"正确触发
  权限告警路径，恢复段补探针回填 → INC-48；"LIST 404 是否也接 sanity"记压力点留 G2）
  ｜ **发现时间**：2026-09-01 04:36 UTC（第四轮阶段 C）｜ **触发用例**：E2E-34 ｜ **影响面**：2 条。
- ① **现象**：注入 list-check 404 + repo 404 后：(a) `PUBLICATION_DRIFT_PERMISSION_ALERT`=0
  （期望 1）；(b) 观察窗内资源呈 PRESENT → 脚本判"UNKNOWN 自动复归（违反 EX-28 v1.2）"。
  另两项 PASS（零 repair 单=0；前置"资源 UNKNOWN"wait 带 ||true 未断言）。
- ② **复现步骤**：`bash e2e-m2.sh E2E-34`。
- ③ **退出码与输出**：`[FAIL] PUBLICATION_DRIFT_PERMISSION_ALERT 告警留痕：实际=[0] 期望=[1]`；
  `[FAIL] UNKNOWN 自动复归（违反 EX-28 v1.2 裁定的不回队语义）`。
- ④ **环境快照**（源码级）：CHECK_RUN 巡检探针=LIST（`GET /commits/{sha}/check-runs`）。
  `DriftReconciler` javadoc:148："404：单资源探针（GET_CHECK_RUN）直给；**列表探针窗口内
  穷尽（执行器裁决）**"——LIST 404 → verdict UNKNOWN（瞬时）→ `handleCheckError`（退避计数，
  无状态迁移、无 sanity、无告警）。sanity/`handlePermissionDenied`/`markUnknown+alert` 路径
  （:181-215）仅由**单资源 GET** 的确定性 NOT_FOUND/403 触发。资源全程留在 PRESENT（扫描集内），
  故 (b) 的 PRESENT 即本态，非"复归"；扫描集 `WHERE r.state IN ('PRESENT','MISSING')`
  （PostgresPublicationStore:586）确认 UNKNOWN 不回队语义已正确落地。
- ⑤ **时间线**：list 404 注入 → UNKNOWN verdict → handleCheckError（error_count+1 退避）→
  摘除故障 → 下一轮探针成功 → PRESENT（从未变过）。
- ⑥ **初步猜想**（标注：猜想）：产品对 LIST 404 取保守归类（sha 消失/瞬断/权限皆可能），
  与用例前提（F-3：404 须 sanity 消歧）冲突。判归：改用例注入（改 GET 单资源探针 404）或
  改产品（LIST 404 也走 sanity）——主会话裁。**"不回队"语义本身复验为正确**（无缺陷）。

### TB-17 E2E-31：repair 命令在 docker pause 生效前被认领并 POST（"零写"断言的注入竞态；fence 本身工作正常）

- **状态**：**已修复待第五轮回归**（主会话裁定：**用例注入竞态**，产品 fence 无缺陷证据；修复=
  0.5s 密轮询等铸单+命中即冻结+抢跑检测换 PR 重试（≤3 轮） → INC-48）
  ｜ **发现时间**：2026-09-01 04:2x UTC（第四轮阶段 C）｜ **触发用例**：E2E-31 ｜ **影响面**：1 条。
- ① **现象**：`stub 零写（repair 命令未触网）实际=[1] 期望=[0]`——ROP（99154837）在
  synchronize webhook（换届）之前已 POST；而 fence 断言（命令 SUPERSEDED）与 REPAIR_EXPIRED=1
  均通过——fence 对换届后命令工作正常。
- ② **复现步骤**：`bash e2e-m2.sh E2E-31`；journal 第 2 条 POST external_id=99154837、
  head_sha=deadbeef（换届前）；DB 链首 repair 单（b701af80）即 ROP，state=REPAIRED。
- ③ **退出码与输出**：`[FAIL] stub 零写（repair 命令未触网）：实际=[1] 期望=[0]`；
  journal：`{"external_id":"99154837-…","head_sha":"deadbeef…"}`（49 条 POST 之第 2 条）。
- ④ **环境快照**：脚本时序=等"repair 单已铸"（PENDING）→ `docker pause` → 等 DISPATCHED →
  sync webhook → 等 epoch 换届 → unpause。executor 轮询周期秒级，"单已铸"与 pause 冻结之间
  存在数秒窗口，ROP 在窗口内被 claim→POST→链首 REPAIRED（attempt_count=1）。
- ⑤ **时间线**：repair 单 PENDING（t0）→ executor claim（t0+Δ 秒）→ POST（deadbeef）→
  docker pause 冻结（晚于 POST）→ sync/换届 → 后续命令被 fence（SUPERSEDED ✓）。
- ⑥ **初步猜想**（标注：猜想）：修复方向=脚本先 pause 再触发铸单（时序对调）或 pause 前
  先确认命令仍 PENDING 未 IN_FLIGHT；产品侧无动作依据。归主会话。

### TB-18 BT-M2-03：内容漂移注入轮出现一次伪 NOT_FOUND 判定 → 游离 MANUAL 修复单（R2"只告警"语义本身演示正确）

- **状态**：**已修复待第五轮回归**（主会话裁定：**测试装备竞态**成立——换装 del/add 空档伪
  NOT_FOUND；修复=probe-sync 的 PUT 原地原子换装（INC-45③）根除空档 → INC-48；
  "episode 活跃期不重复判 MISSING"产品防御评估记压力点留 G2）｜ **发现时间**：2026-09-01 05:12 UTC（第四轮阶段 D，证据
  `smoke-evidence/bt-20260901-051223`）｜ **触发用例**：BT-M2-03 ｜ **影响面**：阶段 D 1 条。
- ① **现象**：`[FAIL] 零 repair 单（内容漂移只告警）：实际=[1] 期望=[0]`——REVIEW 资源名下
  出现 1 张 `PENDING|MANUAL` 修复单（id 8ffe4171…，21:12:46.783）；同轮同毫秒段同 PR 的
  CHECK 资源被风暴铸 AUTO 单（21:12:46.779，TB-13 污染，不在断言范围）。其余全部 PASS：
  CONTENT_DRIFTED 告警、资源仍 PRESENT、digest/时间戳记录、POST 恰 1、PATCH 0、复原后
  episode 关闭。
- ② **复现步骤**：`bash bt-m2.sh BT-M2-03`（风暴活跃背景下）；查 PR 31283 两资源名下修复单。
- ③ **退出码与输出**：`[FAIL] 零 repair 单（内容漂移只告警）：实际=[1] 期望=[0]`；DB：
  `CHECK_RUN|AUTO|REPAIRED|21:12:46.779`（风暴）+ `REVIEW|MANUAL|PENDING|21:12:46.783`（被计数）。
- ④ **环境快照**（源码级）：repair_request 全库唯一铸单点 = `markMissingWithRepair`
  （PostgresPublicationStore:701-710，missMissing 转换成功才插入）——即该轮 REVIEW 探针曾被判
  NOT_FOUND（sanity 通过 → MISSING + MANUAL 单，REVIEW 档位=MANUAL 属 RepairPolicy 设计）。
  下一轮 marker 命中 → `markContentDrift`（:618-634，**SET state='PRESENT'**）→ 资源回 PRESENT
  并开 episode——与"资源仍 PRESENT"断言通过互证。内容漂移主路径（handleFound→digest 不符→
  markContentDrift）**不铸单**（已核对实现）。
- ⑤ **时间线**：注入篡改 body（映射换装）→ 某轮探针 NOT_FOUND（4ms 内 CHECK 风暴单同轮）→
  MISSING+MANUAL 单 → 下一轮 marker 命中 → PRESENT+episode 告警 → 断言窗内全程 PRESENT →
  MANUAL 单永久 PENDING 被计数。
- ⑥ **初步猜想**（标注：猜想）：NOT_FOUND 的成因两候选——(a) 映射换装竞态（探针恰在
  remove/add 之间）；(b) 篡改映射的响应形态破坏 marker 匹配一轮。均为测试装备面；产品侧若要
  防御可评估"episode 活跃期不重复判 MISSING"（现设计两判定独立）。归主会话裁。

### TB-19 第五轮阶段 A：195 源码同步缺口——publisher 测试引用 control 未同步类（ModelCallContext 等 5 类），testCompile 失败

- **状态**：**已关闭（第六轮阶段 A 复验通过，2026-09-01 12:01 UTC）**——主会话裁定：INC-49 次生面，
  非产品缺陷。处置=4 个涉事 IT 文件（ItModelClient/ItHarness/EX06/EX07）在临时副本中手工回退至
  M2-era API（ItModelClient 退回实现 `ModelClient`；ItHarness 的 ReviewAgentLoop 恢复
  4 参含 `ModelBudgetGuard`、ReviewStepExecutor 末参回 `String modelIdentity`；
  EX06 回 `ModelTimeoutException`；EX07 的 ReviewOutcome 去掉 M3 第 9 参），精确文件清单
  scp 到 195（本地 M3-era 工作区不动），195 上 `mvn test-compile -pl publisher-app -am`
  EXIT=0 编译通过（2026-09-01，记 INC-50）。
  **第六轮复验**：reactor `clean verify` BUILD SUCCESS 01:07min——shared 101/0、control 301
  UT+52 IT、publisher **141 UT/0 + 70 IT/0**（CT-05 预期 skip 1）全绿，IT 真跑非 skipped。
  **基线偏差留痕**：publisher UT 实测 **141**，交接文档 v1.5 与本卡原文写 139（+2，非失败，
  疑主会话本地统计口径差异，归主会话核对）。
  ｜ **发现时间**：2026-09-01 10:28 UTC（第五轮阶段 A）｜ **触发用例**：阶段 A reactor
  `clean verify` ｜ **影响面**：publisher 全部 139 单测+70 IT 无法编译执行（shared 101 与
  control 301+52 正常全绿）；C/D 栈级用例不受影响（测已部署镜像）。
- ① **现象**：reactor `clean verify` publisher FAILURE（3.5s）——testCompile
  `cannot find symbol: class ModelCallContext, location: package com.objwww.pr.control.domain.ai`。
- ② **复现步骤**：195 上 `docker run --rm -v /opt/build/pr:/workspace -v m2repo:/root/.m2/repository
  -v /var/run/docker.sock:/var/run/docker.sock -w /workspace maven:3.9-eclipse-temurin-21
  mvn -B -s maven-settings-aliyun.xml clean verify`。
- ③ **退出码与输出**：
  ```text
  [ERROR] ItModelClient.java:[3,39] cannot find symbol
    symbol:   class ModelCallContext   location: package com.objwww.pr.control.domain.ai
  publisher-app ...... FAILURE [3.518 s]（首跑 1.987s；重跑同因）
  ```
- ④ **环境快照**：195 `control-app/.../domain/ai/` 仅 7 类
  （ModelBudgetExceededException/ModelBudgetGuard/ModelClient/ModelRequest/ModelResult/
  ModelTimeoutException/TokenUsage）；`ItModelClient.java`（v1.4 同步的 publisher 测试）import
  `ModelCallContext/ModelGatewayPort/ModelRoute/ModelRouteIdentity/RoutedModelResult`——
  195 上均不存在。第四轮前该文件可编译（无 clean 增量掩盖或旧版文件不同），本轮 clean 后暴露。
- ⑤ **时间线**：v1.4 主会话同步（publisher 测试+主码、control 未动）→ 第五轮 clean verify →
  testCompile 失败。
- ⑥ **初步猜想**（标注：猜想）：主会话同步清单漏了 control 侧被引类（或 ItModelClient 应同步
  为不引用 M3 类的 M2 版本）。修复=补同步 control 五类或回退该测试文件（执行方按 INC-49
  禁令未动任何源码）。

### TB-20 第五轮 E2E-31 残余：epoch fence 比对"游标世代"而非 subject 现世代——按序处理下换届前铸造的 repair 命令合法完成（与 I22 用例期望冲突；TB-17 抢跑已修复）

- **状态**：**已关闭（第六轮阶段 C 复验通过，2026-09-01 12:06 UTC）**——主会话裁定**翻转执行方定性**：
  非语义判定冲突，**fence 无缺陷且从未被绕过，I22/E2E-31 期望不变，产品零改动**。主会话回码 + 195
  取证：① `PublicationGate:68-69` 传入的第三参是 `pr_subject` 行 `FOR UPDATE` 现值
  （`PostgresPublicationStore:120-127`），执行方"游标处世代=1"的机制诊断为**误读**；
  ② 真实时间线（195 DB 取证）：sync webhook `e31-sync` 10:34:48 收到，但处理连败 3 次
  （`last_error=dispatch_failed: 只读 token 窄接口调用失败`——**docker pause 把 publisher 的
  T14 token 口一起冻结，控制面处理换届必须先取 token**），10:38:49 才 PROCESSED →
  epoch bump（新命令 seq 4/5 铸于 10:38:51）比 repair CONFIRMED（10:37:49）**晚 62 秒**；
  fence 判定时 subject 现世代确为 1，ALLOW 完全合法；③ 脚本换届等待行 `|| true` 吞掉
  180s 超时 = 诚实性缺口（假绿通道）。修复（`deploy/e2e-m2.sh` E2E-31，记 INC-51）=
  **行锁代 pause**：pause 仅覆盖"铸单→行锁落地"窗口 → 后台 psql 持 ROP 行 `FOR UPDATE`
  （claim 走 SKIP LOCKED 必跳过、T3-A lockCommand 必阻塞、sweep `FOR UPDATE OF o` 必阻塞）
  → unpause 恢复 T14 token 口 → webhook 换届（等待改硬失败，超时=用例无效）→
  `pg_terminate_backend` 放锁 → fence/sweep 确定性 SUPERSEDED，全程无概率竞态。
  已同步 195 并 `bash -n` 通过。
  **第六轮复验（2026-09-01 12:06 UTC）**：E2E-31 增量 6 PASS/0 FAIL——行锁持有+token 口恢复后
  换届，SUPERSEDED/EXPIRED 两门通过、stub 零写（=0）、REPAIR_EXPIRED 事件（=1），复原完整。
  **TB-20 关闭。执行方第五轮④机制诊断被证伪一事留档为鉴**（journal+DB 侧写不足以判定
  fence 比对面的加载语义，应回码核对而非从行为反推）。
  ｜ **发现时间**：2026-09-01 10:37 UTC（第五轮阶段 C）｜ **触发用例**：E2E-31（本轮唯二 FAIL）
  ｜ **影响面**：阶段 C 2 条。
- ① **现象**：INC-48 冻结修复生效（无换届前抢跑：命令 10:34:47 铸、publisher 即冻、epoch
  1→2、10:37:47 unpause）——但 unpause 后该 repair 命令**未被 fence 拦截反而 CONFIRMED**
  （10:37:49）：`stub 零写 实际=[1]`+`REPAIR_EXPIRED 事件 实际=[0]`。
- ② **复现步骤**：`bash e2e-m2.sh E2E-31`（干净库，无风暴）。
- ③ **退出码与输出**：两条 FAIL 原文；DB：ROP 命令
  `CREATE_CHECK|CURRENT_EPOCH|publication_epoch=1|CONFIRMED`，subject `publication_epoch=2`；
  journal：ROP POST body `head_sha=deadbeef…（旧世代）`，全案例 check-runs POST 共 3 条（无风暴）。
- ④ **环境快照**（源码级）：`RevisionFence.check(fenceMode, commandEpoch, currentEpoch)` 规则
  本身会 REJECT（1<2）；但 `PublicationGate:67-69` 传入的"currentEpoch"是
  **`cursor.publicationEpoch()`（已解决游标处的世代）**，非 subject 现值——新世代命令排在
  aggregate_sequence 4+，修复命令（seq 3）按序先执行时游标尚在旧世代（seq 2 处）→
  1==1 → ALLOW。修复命令为 CREATE_CHECK/CURRENT_EPOCH 铸造（非 OWNED_GENERATION）。
- ⑤ **时间线**：10:34:47.489 铸单 → 冻结 → sync webhook → epoch 1→2（新命令入列 seq 4+）→
  10:37:47 unpause → 认领 seq 3（游标世代=1，fence ALLOW）→ probe NOT_FOUND →
  POST（旧 head_sha）→ 10:37:49 CONFIRMED → 修复单 REPAIRED（无 EXPIRED）。
- ⑥ **初步猜想**（标注：猜想）：两修法——(a) 用例期望改为"按序完成旧世代写"（该 POST 写的是
  旧 sha 对象，语义无害；I22 收窄为"新世代命令已先行出队后 fence 才拦"场景）；(b) 产品在
  repair 铸造/闸门处对 subject 现世代强校验（需评估与按序执行/OWNED_GENERATION 语义的相容性）。
  归主会话。**附带事实**：本轮全案例仅 3 POST 且 probe-sync 全程收敛——TB-13 修复在 C 阶段
  全程有效。

### TB-21 TB-13 修复边界缺口：脚本防护窗外栈重启 → 全量历史资源 MISSING 一次性消化风暴
- 状态：**已关闭（第七轮复验关闭（C 46/0：E2E-28/33 复验面无风暴无循环；守护全程健康）；原修复记录：主会话 INC-52：方向裁定=(a) probe-sync 常驻化——probe-sync-daemon.sh nohup 常驻+心跳+stub 重启自动全量重发布+全链路超时；m2_cleanup 不再随脚本退出摘探针映射。方向(b) 单独不解决新建注册与案内冻结、方向(c) 产品感知 stub 违反架构纪律，均驳回）*）* ｜ 发现时间：2026-09-01 11:57 UTC（第六轮阶段 0 基线观测）｜ 触发：非用例注入，
  环境自然观测（主会话 11:0x UTC 重启容器后的窗口）｜ 影响面：阶段 0 基线污染（起栈即 651→754
  张遗留单）；**阶段 C 坐实 2 败**（E2E-28 铁证、E2E-33 高度一致，见⑥）；不阻塞阶段 A（构建面独立）
- ① 现象：期望第五轮终态 29 单收敛保持到第六轮起栈；实际起栈后 repair_request=651（EXPIRED 1/
  FAILED_TERMINAL 7/PENDING 17/REPAIRED 626，11:57 UTC 快照）且**仍在活跃铸单**，数分钟后 694
  （AUTO REPAIRED 670/RETRY_WAIT 3/FAILED_TERMINAL 7/EXPIRED 1 + MANUAL PENDING 13）；publication_resource
  分布 6 PRESENT/24 MISSING/661 REPAIRED——资源表同步膨胀（修复 POST 各生成新 REPAIRED 行）。
- ② 复现步骤：主会话源码同步后 11:0x UTC `docker compose up -d --force-recreate`（无 probe-sync
  守护）→ 无人干预观测 ~50 分钟 → 执行方阶段 0 规定动作再次 force-recreate（11:54 UTC）→
  风暴延续（11:56:19 仍铸新 MANUAL 单）。
- ③ 输出原文：`SELECT date_trunc('hour',created_at),count(*)` → `09-01 10h|17`、`09-01 11h|668`；
  `WHERE created_at > now()-interval '5 minutes'` → `95`；publisher 日志每 10 秒一条：
  `WARN ... DriftReconciler : 资源漂移确认 MISSING resource=<uuid> type=CHECK_RUN remoteId=7xxxxxx
  repo=stuborg/stubrepo`（resource/remoteId 各不相同）。
- ④ 环境快照：四容器 Up（stub healthy）；`docker inspect deploy-github-stub-1` 挂载仅
  `wiremock/mappings` 与 `wiremock/__files` 两目录卷——**admin API 运行时注册的探针映射为内存态，
  随容器重建丢失**；probe-sync 状态文件存在但守护仅由 m2 脚本启动（脚本外无进程）；
  单资源**行**铸单数 TOP=1（每行一单）。
- ⑤ 时间线：第五轮收官 11:0x UTC（29 单收敛、probe-sync 随脚本 trap 退出）→ 主会话重启容器
  （stub 内存映射清空）→ 11:08:57 首张单 → drift 扫描 10s/个逐个判 MISSING → **机制更正（原表述
  "逐资源一次性长尾"有误，见⑧）**：AUTO 修复=旧行转 REPAIRED 出扫描集 + **铸新行 PRESENT 入扫描集**，
  新行新 remote_id 仍无映射 → 下一轮再 MISSING → **每个受影响 subject 的 check-run 链无限循环**
  （每 subject 每 10s 一轮重建；多 subject 并行→聚合 ~19 单/min）→ 执行方 11:54 force-recreate
  不清库继续观测（铁律：业务表 TRUNCATE 归主会话）。
  **用户授权处置（2026-09-01 12:33 UTC）**：清库 15 张业务表 → C 复跑验证（见⑧）。
- ⑥ 初步猜想（标注：猜想）：INC-45 probe-sync 是**脚本生命周期守护**，栈独立运行期（尤其重启后
  窗口）无任何防线；修复重建对象自身不注册探针映射 → 修复"自证成功"但对象依旧不可见。处置方向
  归主会话：(a) probe-sync 常驻化（systemd/sidecar 守护）；(b) stub 映射持久化落盘；(c) 产品侧
  修复闭环自带注册（修复 POST 成功即回写探针可见映射）。**附带观察**：7 张 AUTO FAILED_TERMINAL
  的 last_error 全为 `PLANNER_TRANSIENT`（修复链 planner 瞬态达上限终态，量小，随卡附带记录）。
- **⑦ 第六轮 C 阶段污染坐实（2026-09-01 12:0x UTC）**：
  - **E2E-28 [FAIL] POST 恰 2 实际 7——铁证**：journal 两簇 3 连发（12:02:23.382/.394/.410 与
    12:02:33.475/.489/.501，簇内 12~26ms）与 DB 两批背景 AUTO 单铸造时刻（12:02:20.398/.406/.430
    与 12:02:30.450/.458/.476）一一对齐（铸单→派发→POST ≈3s 恒定滞后）；且所有 e2e 用例共用
    脚本合成 head_sha `deadbeefcccc`，背景历史资源同 sha——**head_sha 维度完全不可区分本案/背景**，
    全局 POST 计数断言在风暴期必污染。
  - **E2E-33 [FAIL] POST 恰 3 实际 4——高度一致（非铁证）**：第 4 条与本案 R2 重建仅隔 15ms（同 sha
    不同 external_id）；窗口（12:05:55~12:06:05）无新铸单，但同窗两张背景 RETRY_WAIT 卡 att=5 走
    FAILED_TERMINAL（12:05:42.791/12:06:02.805），其修复尝试 POST 亦留痕 journal；wiremock
    journal 无响应码字段，卡片级归因不可达，如实标注。
  - 处置前任何 stub 全局计数断言在非干净基线下持续暴露（同 TB-13 第四轮教训）；C 复跑前需
    主会话清库或风暴消化完毕（当前赛后仍 ~6 单/min 长尾）。
- **⑧ 清库 C 复跑验证（用户授权，2026-09-01 12:33~12:51 UTC）**：TRUNCATE 15 张业务表后干净基线
  （repair_request=0）复跑 `e2e-m2.sh all`——**E2E-28 "POST 恰 2" 转绿（8/0），⑥之污染定性获终锤**；
  60s 静默观察无复燃（空扫描集=无循环燃料，符合⑤修正后的机制）。**新发现**：干净基线下 E2E-33
  案内自燃同族无限循环（登记 TB-22）、E2E-32-A 出现 3s 早重派疑点（登记 TB-23）；且脚本结束后
  stub journal 12:51 分钟桶仍有 5 条循环 POST（TB-22 循环不随脚本 trap 终止——再次印证常驻防线
  缺失）。**常驻化修复（三方向选一）仍归主会话**。

### TB-22 干净基线 C 复跑 E2E-33：案内 check-run 无限重建循环自燃 + R1/R2 断言链崩溃（probe-sync 案内未拦截）
- 状态：**已关闭（第七轮复验关闭（C 46/0：E2E-33 全链通过，零自燃循环）；原修复记录：主会话 INC-52：根因=守护冻结——最后一轮扫描写文件止于 12:45:35 UTC（.scan-ck.json mtime 实证），恰在循环首 POST 前 9s；循环内 curl/psql 全无超时，负载尖峰单轮卡死即永久失能；⑥猜想(a) journal 截断被证伪（.scan-ck.json 完整、内容即 journal 当时实况）。修复=常驻化+全链路超时+心跳，见 INC-52）*）* ｜ 发现时间：2026-09-01 12:50 UTC（第六轮 C 复跑，用户授权清库后）｜ 触发用例：
  E2E-33（本轮 6 FAIL）｜ 影响面：E2E-33 六败；循环**不随脚本结束终止**（12:51 仍有 5 条 POST），
  污染后续一切 stub 计数
- ① 现象：期望（第五轮与第六轮首跑同用例 R1/R2 链全过）：删→MISSING→重建→新 PRESENT 行→
  再删→R2→三行链+POST 恰 3。实际：R1 轮询 240s 未见 MISSING（最后=[REPAIRED]）→"缺新 PRESENT
  行"→**空 uuid SQL 连锁 6 处**→R2 全链超时→POST 恰 3 实际 4。
- ② 复现步骤：TRUNCATE 15 张业务表（干净基线）→ `bash e2e-m2.sh all` → E2E-33。
- ③ 输出原文（/tmp/e2e-r6c2.log 78~118 行区间）：
  ```text
  [超时] [R1] 资源 MISSING（>240s，最后=[REPAIRED]）
  [FAIL] [R1] 缺新 PRESENT 行
  ERROR: invalid input syntax for type uuid: ""   ← 共 6 处（id 变量为空直拼 SQL）
  [超时] R2：新行转 MISSING（>240s）/ 第二张 repair 单（>120s）/ 修复单 REPAIRED（>300s）
  [FAIL] R2：行2 REPAIRED：实际=[] / 缺第三轮 PRESENT 行 / 行3 remote_id：实际=[]
  [FAIL] stub check-runs POST 恰 3 次：实际=[4]
  ```
- ④ 环境快照（DB/journal 三面取证）：
  - 本案 subject 资源分布 `CHECK_RUN|PRESENT|1 + CHECK_RUN|REPAIRED|30 + REVIEW|PRESENT|1`——
    **循环对象是 check-run（非 review）**；
  - 连环行 12:45:44→12:50:34 **每整 10s 一行**（30 行）+30 张 AUTO REPAIRED 单（每行恰一单，
    行级无重复、链级无限）；
  - stub journal 现存 check-runs POST 38 条分钟直方图：`12:43|1、12:45|2、12:46~12:50|6/min、
    12:51|5`——循环真实触网且延续到脚本结束之后；
  - probe-sync（INC-45）案内运行中（1s/轮；m2_ps_scan_once 覆盖 check-runs+reviews 两类 POST）
    **却未拦住循环**。
- ⑤ 时间线：12:43:51 案例两对象创建 → 案注入删 check-run → 12:45:44 起每 10s 一轮
  "新行 PRESENT→探针 404→MISSING→AUTO 修复→旧行 REPAIRED+铸新行"→R1/R2 断言取数落空 →
  脚本 ~12:50 trap 收 probe-sync →循环仍续（12:51 桶 5 条）。
- ⑥ 初步猜想（标注：猜想）：同 TB-21 根族（探针可见性依赖注册）+三个新面：(a) **probe-sync
  案内失效条件**——本轮 stub 容器自 11:54 起未重启，journal 已累积 50 分钟风暴（~600 POST+海量
  GET）+C 首跑全量请求，`__admin/requests/find` 全量查询可能截断/滞后致新 POST 漏登记（第五轮
  与第六轮首跑均 stub 新起/journal 近空 → probe-sync 有效，本轮失效——**journal 体量差异是两轮
  结果分岔的最显差异**）；(b) R1 轮询错过 MISSING 态（MISSING→REPAIRED 间隔与轮询节奏）；
  (c) 脚本 id 变量为空直拼 SQL 无防护（装备健壮性）。判归与修复归主会话。

### TB-23 E2E-32-A 退避窗内 3s 第二次 POST（疑似早重派竞态，低频 1/3）
- 状态：**已关闭（第七轮复验关闭（E2E-32-A 过滤版断言首跑通过，INC-53 生效）；原修复记录：主会话裁定翻转=测试断言缺陷，产品零缺陷——铁证：stub-checks.json 两条 POST external_id 不同源、head_sha 不同（eeee…/deadbeef…），seen.txt 中成对出现两次间隔恰 30s；第一条=邻案 E2E-31 收尾 synchronize 换届评审管线的迟到写落入本案窗口，第二对=两命令各自 429+RA:30 合法重试。修复=INC-53：窗口断言改按本案 operation_id 过滤。附带发现 CLAIM_SQL 租约洞=INC-54 已修）*）* ｜ 发现时间：2026-09-01 12:3x UTC（第六轮 C 复跑）｜ 触发用例：
  E2E-32-A（1 FAIL）｜ 影响面：32-A 一条断言
- ① 现象：期望 429+Retry-After:30 后窗口内 check-runs POST 恰 1（退避生效）；实际 2 条
  （**t+0 与 t+3013ms**）——且同窗"next_attempt_at-T0=34s"断言 PASS：**调度面声明 +34s，
  网面 3s 已重发**。
- ② 复现步骤：TRUNCATE → `bash e2e-m2.sh all` → E2E-32-A（历史 3 跑 2 过 1 败，低频竞态形态）。
- ③ 输出原文：`[FAIL] 退避窗口内无重试风暴（POST 恰 1 次）：实际=[2] 期望=[1]`；
  stub-checks.json：t+0/t+3013ms 两条同 URL（/repos/stuborg/stubrepo/check-runs）POST。
- ④ 环境快照：32-A 时段（~12:37）无背景循环（journal 分钟直方图该时段无外案条目；同跑
  E2E-28/32-C 全绿）→ **第二条为本案自身**；CREATE_REVIEW 走 /reviews 不进 check-runs 计数，
  排除第二命令混入。第六轮首跑 E2E-33 曾见 +15ms 双发（当时归 TB-21 污染，现存疑同族）。
- ⑤ 时间线：webhook → CREATE_CHECK POST t+0（429+RA:30）→ RETRY_WAIT，next_attempt=+34s
  （断言过）→ t+3013ms 第二条 POST（发起路径不明）→ 窗口计数断言败。
- ⑥ 初步猜想（标注：猜想）：候选路径——恢复扫描器/sweep 未按 next_attempt_at 的早重投、
  租约双认领、或 claim 竞态双派发。产品面疑点、频次低（1/3），判归主会话；建议取证方向：
  journal 响应码（第二条是否也 429）+ 该命令 attempt_count/lease_epoch 时间线后复跑观察。

### TB-24 全 stub 模型模式不可用：stub 模型端点对 M2 Spring AI 客户端 HTTP/2 升级回 RST_STREAM（五条悬置用例首开全灭的根因）
- 状态：**已修复待回归（主会话 09-02 INC-56。诊断为双层缺陷：① h2c 传输竞态——JDK HttpClient 默认 HTTP/2 优先 + Spring 流式 chunked body 触发 Jetty h2c 升级 bug（jetty.project#11588 上游机理；带 Content-Length 的 GitHub stub 调用六轮一直绿正因优雅回落 1.1，只有 chunked 模型调用踩进竞态），修复=compose stub 启动加 `--disable-http2-plain`（基建侧零产品码）；② 映射缺 Content-Type——WireMock jsonBody 不自动补 CT，模型映射从未声明，传输修通后 Spring AI 报 no suitable HttpMessageConverter（原卡未记录的第二层，stub.json + m2-lib.sh 的 m2_model_delay_on jq 负载两处补齐）。195 实证：修前 chunked 探针 3 次中 2 次 EOF 复现竞态，修后 5/5 HTTP_1_1 200；BT-M2-01 全 stub 模式 5/0 绿。195 当前停在全 stub 模式备剩余四条续跑；回混合模式=恢复 deploy/.env.mixed.bak-tb24 + force-recreate control-app）** ｜ 发现时间：2026-09-01 13:0x UTC（全 stub 窗口首开，
  用户授权）｜ 触发用例：DP-17 / E2E-26 / E2E-27 / E2E-30 / BT-M2-01（悬置五条全部）｜ 影响面：
  五条用例在窗口内**全部 FAIL**，同根级联
- ① 现象：期望全 stub 模式下五条用例可执行；实际 control 的模型调用
  `POST http://github-stub:8080/v1/chat/completions` 全部以 `IOException: Received RST_STREAM:
  Stream cancelled` 失败（Java HttpClient 发 h2c 升级请求 "Connection: Upgrade, HTTP2-Settings"，
  wiremock 取消升级流）→ SpringAiRetry 重试耗尽 → review_run 全军 FAILED（3/3）→
  checkpoint 永不落库 → 一切依赖评审推进的断言级联烧毁。
- ② 复现步骤：由混合 .env 派生全 stub（仅改 `OPENAI_COMPAT_BASE_URL=http://github-stub:8080`
  + 占位 key，README:51-53 权威值）→ `up -d --force-recreate control-app publisher-app` →
  `bash smoke-test.sh` / `bash e2e-m2.sh E2E-26 all|E2E-27|E2E-30` / `bash bt-m2.sh BT-M2-01`。
- ③ 输出原文（control 日志，13:07 起反复）：
  ```text
  WARN ... SpringAiRetryAutoConfiguration : Retry error. Retry count: 1,
  Exception: I/O error on POST request for "http://github-stub:8080/v1/chat/completions":
  Received RST_STREAM: Stream cancelled
  ```
  窗口五段结果：smoke **118/22**（DP-17：模型计数 3≠1、checkpoint 0≠1）；E2E-26 **6/9**
  （W1/W4/W5 各 2/3）；E2E-27 **1/4**（work_item LEASED 超时、租约接管 900s 超时）；
  E2E-30 **2/9**（outbox 铸出 900s 超时）；**BT-M2-01 2/3**（评审/自愈段同根超时；证据
  bt-20260902-014049）。
- ④ 环境快照：stub mappings 12 条含 `.*/chat/completions`（模型映射**存在**，非缺映射——
  journal 见请求抵达）→ 断点在传输层；`.env.stub.bak` 系 M1 时代文件，其
  OPENAI_COMPAT_BASE_URL 实为 aliyuncs 真端点（"stub bak"当年只指 GitHub=stub）——
  **模型走 github-stub 的配置从未被真实执行过**，此模式六轮以来仅存在于 README 文档意图。
- ⑤ 时间线：M2 首轮用户裁定保持混合 → 五条用例六轮行政性 SKIP（无可用性探针——**执行方
  失误，教训已留档，用户颁布硬约束 HX-01**）→ 2026-09-01 用户授权窗口 → 首试 .env.stub.bak
  失败（M1 键缺失+模型端点为真端点）→ 二试派生 .env 自检过 → 模型链路 RST_STREAM 全灭。
- ⑥ 初步猜想（标注：猜想）：修法候选——(a) wiremock 侧禁用/正确应答 h2c 升级（版本或
  HTTP 方言配置）；(b) SpringAiModelClient 的 HttpClient 强制 HTTP/1.1（产品侧小改，如
  `.version(HTTP_1_1)`，需主会话裁定）；(c) 模型 stub 换独立 HTTP/1.1 监听。修复合规后
  五条用例须整体重跑（HX-01：重跑前先做最小探针）。

### TB-25 BT-M2-03 断言"零 repair 单"失败：新发布资源首个巡检 tick 与 probe-sync 守护登记延迟的竞态（误 MISSING 铸单，probe-first 零写自愈）
- 状态：**已修复待回归（主会话 09-02 裁定：测试基建竞态残余定性成立、非产品缺陷；但 (a) 脚本确认/(b) 守护提速均无法确定性压窗——自然 tick 由产品调度，脚本/守护抢不过；取 (c) 产品侧首查宽限，INC-55：`next_check_at` 初值 = 创建时刻 + `publisher.drift.first-check-grace-seconds`（默认 10s，代码侧显式赋值，不动 V1~V4 迁移）；IT 线束刻意零宽限保持既有语义；新增 FirstCheckGraceIT（195 真跑 2/2 绿）；4 张 PENDING MANUAL 残余单已按 id 定点 DELETE 清理）**
  ｜ 发现时间：2026-09-02 02:01 UTC（第七轮阶段 D）｜ 触发用例：BT-M2-03（12/1 唯一败点；
  BT-M2-02 同窗口同签名中招但断言面未覆盖）｜ 影响面：第七轮阶段 D FAIL=1；A/B/C 不受影响
- ① 现象：期望 BT-M2-03 内容漂移只告警（REVIEW 资源零 repair 单）；实际本案 REVIEW 资源挂
  1 张 MANUAL PENDING 单（另 CHECK_RUN 资源 1 张 AUTO 单已 REPAIRED——probe-first 零写自愈）。
- ② 复现步骤：`cd /opt/build/pr/deploy && bash bt-m2.sh BT-M2-03`（概率性：竞态窗 =
  资源 CONFIRM 后 ~1s 内首个 drift tick；本日实测 4 次中招——阶段 0 窗口 PR#22206、
  C 阶段 PR#23139（静默）、BT02 PR#31119、BT03 PR#31271）。
- ③ 输出原文（`smoke-evidence/bt-20260902-095645/BT-M2-03/summary.txt` 尾部）：
  ```text
  [PASS] review 零自动重发（POST 仍恰 1 次）（=1）
  [PASS] review 零自动改写（PATCH 0 次）（=0）
  [FAIL] 零 repair 单（内容漂移只告警）：实际=[1] 期望=[0]
  [PASS] 恢复原文后 episode 关闭
  -- BT-M2-03 累计：PASS=12 FAIL=1
  ```
  断言源码 `bt-m2.sh:167-168`：`select count(*) from repair_request where
  publication_resource_id='$RVID'`（按本案 REVIEW 资源行计数，非全局）。
- ④ 环境快照（2026-09-02 02:0x UTC，混合模式四容器 Up）：
  ```text
  repair_request（窗口内 4 行，前 2 行=BT-M2-02 基线同签名）：
  01:56:49  CHECK_RUN AUTO    REPAIRED  remote 7354981（PR#31119）
  01:56:49  REVIEW     MANUAL PENDING   remote 8187457（PR#31119）
  02:00:59  CHECK_RUN AUTO    REPAIRED  remote 7440685（PR#31271）
  02:00:59  REVIEW     MANUAL PENDING   remote 8869377（PR#31271）

  execution_event 关键序（PR#31271）：
  02:00:59.321  PUBLICATION_CONFIRMED ×2（check+review 双资源落库）
  02:00:59.697  PUBLICATION_DRIFT_DETECTED + REPAIR_REQUESTED（CHECK_RUN）← CONFIRM 后 0.38s
  02:00:59.707  PUBLICATION_DRIFT_DETECTED + REPAIR_REQUESTED（REVIEW/MANUAL）
  02:01:02.990  REPAIR_DISPATCHED
  02:01:03.333  REPAIR_REPAIRED（probe-first 零写：无新 resource 行、remote_id 不变、
                journal check/review POST 各恰 1）
  02:01:09.714  PUBLICATION_CONTENT_DRIFTED（真正的被测事件——语义全对：资源仍 PRESENT/
                digest=篡改后 sha256/零重发/零改写/复原后 episode 关闭，全 PASS）
  ```
  `probe-sync-daemon.sh:87 sleep 1`（守护 1s 轮询登记延迟）；`m2-lib.sh:569
  m2_force_drift_due` 仅把 next_check_at 拉到 now()——本案 02:00:59.697 tick **早于**
  脚本注入（编辑+force 在其后），属自然 tick：新 CONFIRMED 资源 next_check_at≈now()
  立即可扫。stub journal 证据：bt-20260902-095645/BT-M2-03/{reviews-after,reviews-patch}.json。
- ⑤ 时间线：见 ④ 事件序；BT-M2-02 基线（PR#31119，01:56:49，CONFIRM 后 ~1s 内）同签名
  中招，其断言面不查活跃单故 5/0 PASS；C 阶段 PR#23139（01:51:02）静默中招未触发任何断言。
- ⑥ 初步猜想（标注：猜想）：**非产品缺陷**——产品对"探针空"输入的全链反应正确
  （MISSING→AUTO/MANUAL 分档铸单、probe-first 防重复写、effectively-once 未破坏）。
  根因 = INC-52 守护 1s 轮询登记延迟 × 新资源立即可扫的亚秒窗口残余（TB-13/21/22 同族
  第五例；INC-52 已把窗从分钟级缩到亚秒级，未归零）。修法候选（主会话裁定）：
  (a) 脚本 `m2_run_pr_e2e` 基线收敛后先确认探针映射已登记（查 stub mappings）再继续；
  (b) daemon 订阅/扫描 PUBLICATION_CONFIRMED 事件即时登记，压窗到毫秒级；
  (c) 产品侧新资源首个巡检周期宽限（next_check_at 初值 +宽限）——动产品语义需谨慎。
  附注：PENDING MANUAL 累计 4 张（上列 4 个 PR 各一），REVIEW 档设计内挂起（等人工批准，
  测试资源无人批），是否清理留主会话裁定。

### TB-26 E2E-30-C 断言"repair 远端写恰 1 次"失败=0：断言在自己 down/up 之后查询内存态 journal，崩溃前 POST 记录已被栈重启清空（断言缺陷，产品恢复语义全对）
- 状态：**已关闭（主会话 09-02 裁定：执行方定性成立=断言时序缺陷，产品零缺陷——DB 侧恰好一次证据链完整（CONFIRMED/attempt=1/新 PRESENT 行链回/probe-first 零重复写）。INC-57：E2E-30-C 改"先取证后拆栈"——down 前快照 journal（pre 窗）断言崩溃前写恰 1 次，恢复后（post 窗）断言零重复写，两段闭合证明恰好一次，不再依赖内存态 journal 跨重启存活。第九轮执行方复验：E2E-30 全量 12/0 全绿，C 段两断言 =1/=0 双过）** ｜ 发现时间：2026-09-02
  04:1x UTC（第八轮全 stub 窗口）｜ 触发用例：E2E-30-C（compose down/up：repair 命令
  IN_FLIGHT）｜ 影响面：第八轮全 stub E2E-30 唯一败点（10/1；A 3/0、B 7/0 不受影响）
- ① 现象：期望 journal 中含 repair 命令 operation_id 的 POST 恰 1；实际 0。
- ② 复现步骤：全 stub 模式 `cd /opt/build/pr/deploy && bash e2e-m2.sh E2E-30`。
- ③ 输出原文（`smoke-evidence/e2e-20260902-121008/E2E-30-C/summary.txt`）：
  ```text
  == E2E-30-C compose down/up：repair 命令 IN_FLIGHT ==
  [PASS] C 基线闭环
  [FAIL] C：repair 远端写恰 1 次（恢复不重复写）：实际=[0] 期望=[1]
  [PASS] C：新 PRESENT 行链回
  [PASS] C：新行 remote_id（=7732238）
  ```
  日志关键前序行：`[就绪] C：repair 写已达 stub（IN_FLIGHT 窗口）（=1）`——**崩溃前那笔
  POST 确实抵达过 journal**（m2_wait_journal_body 等到恰 1）。
- ④ 环境快照（全 stub 模式）：
  ```text
  stub-checks.json（断言取据）requests 数=0（journal 已被清空）
  DB PR#24281 资源链：旧行 7444392 REPAIRED ← 新行 7732238 PRESENT
    （created_by=f9d67942…=repair 命令 ROPC，CONFIRMED）
  repair_request（ROPC）：REPAIRED，attempt_count=1（零重复执行）
  ```
  断言源码 `e2e-m2.sh:387-390`：`m2_journal_find POST …/check-runs` 后按 ROPC 过滤
  计数——执行点在 `docker compose down/up` **之后**；WireMock journal 为容器内存态，
  down/up 即清空。up 后编排仅 `m2_probe_sync_republish_all` 重发布探针映射 +
  `m2_check_present_add "$ROPC" "$NEWIDC"`（行 382 注释明示"恢复探针认领"）；delay/override
  POST 映射（m2_post_check_delay_on）未重挂。
- ⑤ 时间线：journal_reset → 基线闭环 → repair 命令 DISPATCHED（20s 写延迟放大窗口）→
  POST 抵达 journal（wait=1 恰好）→ **compose down/up（journal 清空、mappings 清空）**
  → 探针映射重发布+认领注册 → 产品恢复扫描 probe-first 命中（零写认领）→ 命令
  CONFIRMED、修复单 REPAIRED（attempt=1）、新行 remote=NEWIDC=7732238 → 断言查 journal
  =0 → FAIL。
- ⑥ 初步猜想（标注：猜想）：**断言与编排自相矛盾，非产品缺陷**——产品恢复行为全部正确
  （零重复写=比"恰 1"更强的保障、probe-first 认领与 E2E-29/ST-33 同语义、状态链完整）。
  "恰 1"本意是数崩溃前那笔 POST（wait_journal_body 已实证=1），但断言执行点在 down/up
  之后、journal 已被自身编排清空，**该断言在本用例自动化形态下必然 0≠1**（全 stub 首次
  真实执行：补测二 E2E-30 被 TB-24 烧毁未及此断言；v1.7 主会话预检在混合模式未跑 30）。
  修法建议（主会话裁定）：(a) "恰 1"证据前移到 down 前快照（wait_journal_body 步已具备），
  up 后断言改为"含 ROPC 的新增 POST=0"（零重复写）；或 (b) 断言改为"新行 remote_id=
  NEWIDC 且修复单 attempt_count=1"（两项已各自 PASS）。


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
