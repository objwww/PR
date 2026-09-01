# OSS 证据清单 v1 —— 承重设计的开源先例核查

> **用途**：为《架构冻结文档 v2.2 修订》提供证据基线。方法论：冻结文档是"假设集合"，每条承重设计必须在经过大规模生产验证的高星开源项目中找到同构实现，或明确标注"无先例、属原创增强"。不抄代码，只验证决策结构。
> **标注规则**：【明示】= 官方文档/源码/issue 直接写明；【推断】= 基于明示材料的同构性判断。
> **核查日期**：2026-08-30。调研线程：A=Temporal，B=OpenHands/E2B/SWE-agent，C=Kafka/K8s/etcd，D=Atlantis/TFC/BinAuthz/GitHub App/Airflow/Argo。

---

## A. Temporal（temporalio/temporal）——账本、回放、Attempt、幂等、栅栏

### A-1 追加账本与三种回放语义【同构成立】

- Event History 是 append-only log，"durably persisted … enabling seamless recovery … also serves as an audit log"。
  来源：https://docs.temporal.io/workflow-execution/event 【明示】
- 状态重建靠重放代码 + 事件引导，Activity 结果"reused, not recomputed"——对应我们的 Projection 重建。
  来源：https://docs.temporal.io/workflows 【明示】
- 只读离线回放是独立工具 `WorkflowReplayer`（可从 JSON 历史文件回放、支持部分回放）；回放路径可用 `IsReplaying()` 自检且不得产生新 command——对应我们的"录制回放不调模型不执行工具"。
  来源：https://pkg.go.dev/go.temporal.io/sdk/worker 【明示】
- **重执行必须建新 Run 保 lineage = Temporal Reset**："terminates a Workflow Execution and creates a new Workflow Execution … Event History is copied up to and including the reset point"；lineage 用 `first_execution_run_id` + `original_execution_run_id` 双字段。
  来源：https://docs.temporal.io/workflow-execution/event、https://docs.temporal.io/workflow-execution/workflowid-runid 【明示】
- Reset 的合法切点受限（只有 WorkflowTask 边界事件）——重执行锚点必须落在决策边界。
  来源：同上 【明示】

### A-2 外部副作用幂等【同构成立，官方措辞与 effectively-once 逐字相同】

- "Temporal guarantees that the Activity will be **observed as completed exactly once**. However, the Activity **may be executed multiple times** and may even partially complete more than once."
  来源：https://docs.temporal.io/activity-definition 【明示】
- 官方幂等建议：idempotency key，"enforced by the service you are calling, not by the Activity itself"；推荐 key 构造 = `workflowRunId + '-' + activityId`；官方博客给出 SQL 模板（唯一约束 + ON CONFLICT + 业务更新同事务）并警告 check-then-act 竞态。
  来源：https://temporal.io/blog/idempotency-and-durable-execution 【明示】
- `maximumAttempts=1` 时是 at-most-once，"zero times is also possible"——恰好一次不存在，只有二选一。
  来源：同上 【明示】

### A-3 Attempt 建模与账本噪声【同构成立 + 一条修正】

- "An Activity Execution can be composed of multiple Activity Task Executions, with each Task representing a single attempt"——逻辑步骤 vs 物理尝试分离是官方术语。
  来源：https://docs.temporal.io/encyclopedia/detecting-activity-failures 【明示】
- **`ActivityTaskStarted` 在 activity 终态前刻意不入历史**，attempt 计数存 mutable state——避免账本被重试噪声淹没（→ v2.2 E10）。
  来源：https://docs.temporal.io/encyclopedia/retry-policies 【明示】
- 超时四分类：Schedule-To-Start（排队上限，设计上不可重试）/ Start-To-Close（单次尝试）/ Schedule-To-Close（含全部重试）/ Heartbeat。我们的 lease_until 只覆盖执行时长，**排队超时未建模**（待办，不阻塞 M0）。
  来源：https://temporal.io/blog/activity-timeouts 【明示】
- Heartbeat 除存活检测外承载 checkpoint（details payload 存服务端，下一 attempt 读回）——断点续跑的 checkpoint 应放服务端/账本而非 worker 本地。
  来源：https://docs.temporal.io/activities 【明示】

### A-4 僵尸 worker 栅栏【同构，且有一个诚实性要求】

- Task Token 是 per-attempt 唯一标识；旧尝试超时/重试后 token 作废，晚到完成上报被服务端拒绝（NOT_FOUND）。
  来源：https://docs.temporal.io/activity-execution、https://docs.temporal.io/troubleshooting/request-failures 【明示】
- **栅栏只在超时/epoch 推进后生效**；超时窗口内旧 worker 的上报是合法的。设计文档不得暗示 lease_epoch 能实时拦截——副作用去重最终靠幂等键。
  来源：https://docs.temporal.io/encyclopedia/detecting-activity-failures 【明示 + 推断】

### A-5 代码版本兼容【我们原设计完全缺失，必须补】

- Temporal 社区最大的坑：改代码后旧历史回放不出确定性结果。因此有 `GetVersion` patch API、离线 Replayer 回归测试、坏二进制禁轮询 fencing（`SetBinaryChecksum`）、Worker Versioning（Build ID 路由）。
  来源：https://pkg.go.dev/go.temporal.io/sdk/worker、https://docs.temporal.io/worker-versioning 【明示】
- 映射：我们的 prompt/模型/工具 schema 变更 ≡ Temporal workflow 代码变更 → v2.2 E9（回放兼容性测试 + 版本标记）。

### A-6 Workflow-per-entity 单写者保序【同构成立】

- 官方 design pattern："one Workflow per entity, using the entity ID as the Workflow ID"；同 Workflow Id 任意时刻最多一个 Open execution。
  来源：https://docs.temporal.io/design-patterns/entity-workflow、https://docs.temporal.io/workflow-execution/workflowid-runid 【明示】
- 代价：Event History 上限（>51,200 events 强制 terminate，10,240 告警），长寿命实体须 Continue-As-New 滚动——我们的 PR 聚合是短寿命，暂不受此约束，记录在案。
  来源：https://docs.temporal.io/workflow-execution/event 【明示】

---

## B. OpenHands / E2B / SWE-agent —— 沙箱边界、凭证、网络、传输

### B-1 Agent loop 与沙箱的边界【同构成立】

- OpenHands 标准架构：backend controller（跑 agent loop + LLM 调用）↔ runtime（独立 Docker 容器，内跑 action execution server），经 RESTful API 发 action 收 observation。Agent 不直接执行任何东西。
  来源：https://docs.openhands.dev/openhands/usage/architecture/runtime 【明示】
- SWE-agent 同构：agent loop 在宿主机，环境经 SWE-ReX 起容器跑 `swerex-remote` 服务器。
  来源：https://swe-agent.com/latest/usage/hello_world/ 【明示】

### B-2 凭证隔离【我们比锚点现状更严，且锚点正朝我们的方向演进】

- OpenHands 现状：LLM key 只在控制面；但 **GitHub token 被刻意送进沙箱**（authenticated clone URL 在 runtime 内执行，token 写入 `.git/config`）。
  来源：software-agent-sdk issue #4288（凭证设计文档）、`openhands/runtime/base.py:325/430-468` 【明示】
- 泄露史：#4271（`git remote -v` 回显 token 给模型，仍 open）、#9168（"agent can echo GITHUB_TOKEN"）等 7+ 个泄露点。
  来源：https://github.com/OpenHands/software-agent-sdk/issues/4288 【明示】
- 其目标架构 `brokered`（控制面/egress 代理注入凭证，沙箱拿不到真值，参考 iron-proxy）= 我们当前设计。**我们直接站在他们的演进终点上。**
  来源：同上 【明示】

### B-3 沙箱网络策略【三家默认全通；我们必须显式补齐 → v2.2 E7】

- OpenHands：`network_mode` 仅 host/bridge，无 egress 控制（0.50.0 源码核实）【明示】
- E2B：默认 `allowInternetAccess: true`；支持 allowOut/denyOut；**allow 永远优先于 deny**；域名过滤只覆盖 80（Host 头）/443（SNI），不覆盖 QUIC；**被拦 TCP 连接从沙箱内看可能"假成功"**（防火墙先 accept 再判定）。
  来源：https://e2b.dev/docs/network/internet-access 【明示】
- SWE-agent：docker run 无 `--network` 参数，默认 bridge 全通。
  来源：hello world 日志 【明示】

### B-4 文件/artifact 传输【HTTP+zip 为主流；内容寻址 digest 无先例】

- OpenHands：REST `/upload_file`（zip multipart）/ `/download_files`；本地模式默认 bind mount，支持 `:ro,overlay` copy-on-write——与我们"只读 lower + 可写 overlay"同构，可作先例引用。
  来源：https://docs.openhands.dev/openhands/usage/architecture/runtime 【明示】
- E2B：HTTPS + pre-signed URL，无共享挂载；底座 Firecracker microVM。
  来源：https://e2b.dev/docs/sandbox/secured-access、https://github.com/e2b-dev/infra 【明示】
- **跨机内容寻址 artifact + digest 校验：三家均无先例（缺席，非冲突）**。最近先例是 OCI 镜像 digest 寻址。属我们的原创增强，评审时须自行论证威胁模型（跨机传输篡改）。【推断】

### B-5 "快照只经 git archive、绝不 checkout 不可信 ref"【严于所有锚点，保留】

- 反例：OpenHands 官方 PR Review action 直接 checkout 不可信 PR head，但配 `persist-credentials: false`（token 不进 `.git/config`）、`pull_request` 触发器、`contents: read` 权限、maintainer 人工门槛、禁用跨 workflow 共享缓存（防缓存投毒）。
  来源：https://github.com/OpenHands/extensions/blob/main/plugins/pr-review/action.yml 【明示】
- SWE-bench 只 checkout 数据集固定 base commit（pin 到可信常量）。
  来源：SWE-agent 文档 【明示】
- **注意**：OpenHands 官方 PR Review action 是"反面锚点"——agent loop + LLM key + GITHUB_TOKEN + 不可信代码同进程，靠人工门槛兜底。评审时若被引用，须指出其服务端主线架构与我们一致，action 是轻量路径且有明文风险告知。
  来源：https://github.com/OpenHands/software-agent-sdk/blob/main/examples/03_github_workflows/02_pr_review/workflow.yml 【明示】

---

## C. Kafka / Kubernetes / etcd / Kleppmann —— epoch fence、幂等、对账

### C-1 epoch fencing【同构成立 = KIP-98 producer epoch；+ 一条分支补充】

- Kafka 有两套 epoch：(a) leader epoch（KIP-101/320）fence 的是读/复制路径，**Produce 请求不携带 leader epoch**（KIP-320 明示 producer 侧保护是 future work）；(b) **事务生产者 epoch（KIP-98）才是与我们同构的机制**——新实例用同一 transactional.id 调 InitProducerId 时 coordinator 原子 bump epoch，旧实例（zombie）携带旧 epoch 的请求被拒（`ProducerFencedException`）。
  来源：https://cwiki.apache.org/confluence/display/KAFKA/KIP-98+-+Exactly+Once+Delivery+and+Transactional+Messaging 【明示】
- **双错误码设计**：旧 epoch = fence（拒绝）；请求 epoch 比当前**还新** = `UNKNOWN_LEADER_EPOCH`（元数据滞后，可重试）——映射为我们 v2.2 §3 第 6 条"epoch > 当前值按可重试处理"。
  来源：https://cwiki.apache.org/confluence/display/KAFKA/KIP-320%3A+Allow+fetchers+to+detect+and+handle+log+truncation 【明示】
- **我们拒绝的建议**：研究者建议"epoch 仅在写者切换时递增，revision 另做乐观并发号"。不采纳——Kafka fence 的是僵尸写者，我们 fence 的是结论时效；revision 变化废掉在途旧结论写命令是 F9 的设计目标。语义不同，不对齐。（v2.2 §3 已记录）

### C-2 幂等 sequence【同构成立，且 Kafka 语义比唯一约束强 → v2.2 E2】

- KIP-98：每 (PID, partition) sequence 单调递增，broker 拒绝任何 ≠ `last+1` 的写——sequence 更低 = 重复，ack 但不落盘；**sequence 跳号 = `OutOfOrderSequenceException`，致命错误**，不静默跳过。
  来源：KIP-98 同上 【明示】
- 映射：DB 唯一约束只覆盖"去重"半边；跳号检测须同事务校验 `seq == last_applied + 1`，跳号触发对账。`last_applied_sequence` 为持久字段。

### C-3 Reconcile 架构与漂移修复惯例【同构成立】

- K8s controller：事件驱动为主 + informer 周期 resync 兜底（kube-controller-manager 默认 `--min-resync-period=12h`）——"webhook 低延迟 + 周期对账保最终正确"与冻结文档 §19 阶段一致。
  来源：https://book.kubebuilder.io/reference/watching-resources.html、https://github.com/kubernetes/kubernetes/issues/108231 【明示】
- 漂移修复惯例：自有资源默认**自动收敛**（ReplicaSet 重建被删 Pod；Flux 对手工改动 "will be promptly reverted"）；不可逆/危险操作降级为**告警/挂起**（Flux `suspend`；finalizer 两阶段删除）。→ 我们"评论漂移默认告警不自动补发"符合惯例光谱。
  来源：https://fluxcd.io/flux/concepts/、https://kubernetes.io/docs/concepts/overview/working-with-objects/finalizers/ 【明示 + 推断】
- 多 Reconciler 拆分合理："It's useful to have simple controllers rather than one, monolithic set of control loops"；Flux 按"权威源对账/发布收敛/事件通知"分组件，与我们三 Reconciler 切分轴一致。
  来源：https://kubernetes.io/docs/concepts/architecture/controller/ 【明示】

### C-4 fencing token 红线【v2.2 E1 的理论源头】

- Kleppmann：fencing token 有效的前提是"**storage server 主动检查 token 并拒绝回退的写**"；仅在客户端写前自检不安全——GC 暂停/网络延迟可发生在检查后写之前（HBase 真实事故；GitHub 90 秒网络分区事故）。
  来源：https://martin.kleppmann.com/2016/02/08/how-to-do-distributed-locking.html 【明示】
- K8s resourceVersion：客户端携带读取时版本 PUT，API server 检测 lost update 返回 409——检查在存储端、与写原子绑定。
  来源：https://kubernetes.io/docs/reference/using-api/api-concepts/ 【明示】
- 我们的边界：外部 GitHub 写无法被本地 CAS 保护，顺序固定为"本地事务原子 claim → 外部写 → 结果回账本 → 不确定由 Reconciler 收敛"。【推断，已入 v2.2 E1】

---

## D. Atlantis / Terraform Cloud / Binary Authorization / GitHub App / Airflow / Argo —— 审批绑定、token、依赖语义

### D-1 审批绑定精确工件【方向正确；Atlantis 是反面标本】

- **反面**：Atlantis `apply_requirements: [approved]` 只查"PR 被批准"的 VCS 状态，审批对象是 PR 不是 plan 工件；#1508（批准后可对任意目录 replan+apply 无需再审，官方建议靠 GitHub "Dismiss stale approvals" 兜底——漏配即裸奔）。
  来源：https://www.runatlantis.io/docs/command-requirements、https://github.com/runatlantis/atlantis/issues/1508 【明示】
- **正面**：HCP Terraform 审批作用于不可变 run 对象，run 固定关联特定 commit；单 workspace 串行队列防"批准旧的执行新的"；"新 commit 自动作废旧 plan"是显式可选设置（auto-cancel plan-only runs）——说明这是公认的策略决策点。
  来源：https://developer.hashicorp.com/terraform/cloud-docs/workspaces/run/states、https://developer.hashicorp.com/terraform/enterprise/users-teams-organizations/organizations/settings 【明示】
- 推论（v2.2 E8）：审批失效判定必须自包含在账本会话内，不外包给 VCS 配置。

### D-2 Binary Authorization：attestation 绑定 digest + fail-open 教训

- "The attestation is a record that contains the registry path and **digest** of the container image"，部署时 enforcer 验证——门禁绑定精确 digest 是成熟行业惯例。Google 另维护 k8s-digester 把 `image:tag` 改写为 `image:tag@sha256:...`（官方承认 tag 可变、不能作审批对象）。
  来源：https://cloud.google.com/binary-authorization/docs/overview、https://github.com/google/k8s-digester 【明示】
- **反面细节**：enforcement 在 BinAuthz 服务不可达时 **fail-open**（pod 照常部署只留审计注解）——我们的安全门禁必须显式 fail-closed（v2.2 E5）。
  来源：同上 【明示】

### D-3 GitHub App token 实践【App 载体正确；"按操作现铸"须修正 → v2.2 E6】

- 官方最佳实践：最小权限注册；installation token **铸造时可进一步收窄 repositories 和 permissions**；1 小时过期；**"Cache tokens" 节明确建议缓存复用至过期**（现铸耗速率限额）。
  来源：https://docs.github.com/en/apps/creating-github-apps/about-creating-github-apps/best-practices-for-creating-a-github-app 【明示】
- Renovate 自托管：要求 installation token，按最小权限表配置。
  来源：https://docs.renovatebot.com/modules/platform/github/ 【明示】
- 反面事故：2022-04 Heroku/Travis CI OAuth token 盗窃（不过期 token 批量被盗）；2025-03 tj-actions CVE-2025-30066（tag 重指恶意 commit → pin to SHA 共识）；2026-05 Grafana（`pull_request_target` + checkout 不可信代码 → token 被盗拖库）——佐证"短命 + 窄 scope + 不可变引用 + 不信任 pull_request_target"。
  来源：https://blog.gitguardian.com/how-hackers-used-stolen-github-oauth-tokens/、https://github.com/tj-actions/changed-files/security/advisories/GHSA-mw4p-6x4p-x5m5 【明示】

### D-4 依赖语义：Airflow trigger_rule vs Argo depends【三模式够用，终态归类是缺口 → v2.2 E3】

- Airflow 13 种 trigger_rule（`all_success/all_done/none_failed/none_failed_min_one_success/one_failed/...`）；`removed` 态计入 done 不计入 success/failed。
  来源：https://airflow.apache.org/docs/apache-airflow/stable/core-concepts/dags.html 【明示】
- Argo depends 是逐前置布尔表达式：`.Succeeded/.Failed/.Errored/.Skipped/.Omitted/.Daemoned`，裸任务名 ≡ `(Succeeded || Skipped || Daemoned)`。
  来源：https://argo-workflows.readthedocs.io/en/latest/enhanced-depends-logic/ 【明示】
- 映射：REQUIRE_CONFIRMED ≈ all_success；REQUIRE_TERMINAL ≈ all_done；OPTIONAL ≈ Argo 裸操作数。**缺口**：SUPERSEDED 等终态如何计数必须显式定义——Airflow 在 skip 级联语义上踩了多年坑（`none_failed_min_one_success` 为此存在）。
- 我们**不引入**表达式引擎：线性发布流水线不需要 OR/混合语义，文档显式冻结"不支持 OR/混合"，避免未来隐式假设。
- Airflow TaskInstance + `try_number` 与我们 Step/Attempt 分离同构。
  来源：https://airflow.apache.org/docs/apache-airflow/2.3.1/_api/airflow/models/taskinstance/index.html 【明示】

### D-5 换包/空转事故【三 hash 同一性门的直接证据 → v2.2 E4】

- Atlantis #6529：`atlantis apply` 执行磁盘上的 `.tfplan`，**不校验 plan 是否属于当前 HEAD**；可 apply 到 stale/mixed-commit plan；场景 A 出现 "Ran Apply for 0 projects" 且 `atlantis/apply = success` 的空转成功。
  来源：https://github.com/runatlantis/atlantis/issues/6529 【明示】
- Codecov 2021：Bash Uploader 被篡改约两个月无人察觉，最终由**一个对比 SHA-256 的客户**发现——hash 校验是有效的换包检测手段。
  来源：https://incidents.cremit.io/incidents/codecov-bash-uploader-2021、https://cycode.com/blog/the-codecov-breach-development-infrastructure-is-the-weakest-link-its-now-increasingly-being-exploited/ 【明示】
- 推论：同一性门校验等集且非空（`verified == approved == published`），防空集合通过。

---

## E. 采纳 / 拒绝记录

### 采纳（已入 v2.2 第二部分 E1–E10）

| # | 修正 | 主要证据 |
|---|---|---|
| E1 | fence/lease 比对在存储端同事务原子完成 | C-4（Kleppmann/K8s 409） |
| E2 | sequence 跳号 = 致命，触发对账 | C-2（KIP-98） |
| E3 | 依赖终态归类表；级联尊重 dependency_mode | D-4（Airflow/Argo） |
| E4 | 三 hash 等集且非空，防空转成功 | D-5（Atlantis #6529/Codecov） |
| E5 | 安全门禁 fail-closed 显式冻结 | D-2（BinAuthz fail-open 反面） |
| E6 | token 铸造收窄 scope + TTL 内缓存 | D-3（GitHub 官方） |
| E7 | 沙箱 egress 默认 deny + 白名单 | B-3（E2B） |
| E8 | 审批失效自包含，不外包 VCS | D-1（Atlantis #1508） |
| E9 | 回放确定性 + 版本兼容 + 双字段 lineage + 锚点受限 | A-1/A-5（Temporal Reset/GetVersion） |
| E10 | attempt start 不进账本，控制账本噪声 | A-3（Temporal） |

### 拒绝（附理由）

| # | 建议 | 拒绝理由 |
|---|---|---|
| R1 | epoch 拆 fence_epoch + revision 两数（Kafka 调研建议） | 语义不同：Kafka fence 僵尸写者，我们 fence 结论时效；revision 变化废在途命令是 F9 设计目标（v2.2 §3 已记录） |
| R2 | 漂移默认自动重建（K8s/Flux 惯例） | 只采纳一半：自有资源自动收敛，不可逆外部副作用（删评论/关 PR）保持"仅告警 + 人工"档，对应 Flux suspend / finalizer 语义 |
| R3 | 依赖判定引入 Argo 式布尔表达式 | 线性发布流水线不需要 OR/混合；冻结"不支持 OR/混合"比引入表达式引擎更简单 |
| R4 | OpenHands 官方 PR Review action 的同进程模式 | 反面锚点：其官方 SDK 凭证设计文档（#4288）自身正在向 brokered 架构演进 |

### 无先例、属原创增强（评审时须自行论证）

- 跨机内容寻址 artifact 传输 + digest 校验（B-4）；最近先例 OCI 镜像 digest。
- "快照只经 git archive、绝不 checkout 不可信 ref"严于所有锚点（B-5）；OpenHands 的配套缓解（persist-credentials:false、人工门槛、禁用共享缓存）已记录备用。

## F. M1 入口可信化证据（webhook inbox / LWW / reconciler 分工）

### F-1 GitHub webhook 官方最佳实践【事实锚点，措辞逐句核对过】

- 来源：GitHub Docs《Best practices for using webhooks》（docs.github.com/en/webhooks/using-webhooks/best-practices-for-using-webhooks，2026-09 核对）。
- 官方明示的事实（G1 评审措辞修正后）：
  - "If your server goes down, you should redeliver missed webhooks"——**交付会丢失，且失败不保证自动重投**；官方提供的是手动 redelivery；
  - 重投时 "the `X-GitHub-Delivery` header will be the same as in the original delivery"——**重投同 ID，ID 去重是官方推荐的幂等机制**（原文用于防 replay attack）；
  - "Respond within 10 seconds" 否则 GitHub 判失败——**HTTP 线程零重活是官方硬约束**（M0 已守，M1 inbox 强化）。
  - 因此 webhook 交付的准确表述是"**可能重复、失败不保证自动重投**"，不笼统称自动 at-least-once。
- 官方**未承诺**交付顺序——本方案不声称"官方承认乱序"，而是按并发连接/重试的网络事实防御：
  快路径 LWW 启发式 + PrStateReconciler 周期兜底（分工证据见 C-3）。

### F-2 Idempotent Consumer 模式【同构成立】

- 来源：Chris Richardson microservices.io《Idempotent Consumer》模式；EIP（Hohpe/Woolf）同名模式；
  工程化实例：Debezium outbox/inbox 事件去重表、Kafka 消费者 dedup store。
- 核心两段式：① 消息唯一 ID 入库去重（同事务或先行）；② 业务处理自身幂等兜底。
  与 M1 设计逐条对应：webhook_inbox 主键去重（①）+ run_key 唯一约束/条件更新（②）。
- 推论采纳：去重记录必须落在**与业务同一个可恢复存储**（Postgres），不放 Redis——
  Redis 故障即去重记忆丢失，且引入第二种故障模式（与 M0 序号分配拒绝 Redis 锁同一判断）。

### F-3 GitHub 用 404 替代 403 隐藏私有资源【404 语义歧义，EX-17/E2E-18 的设计依据】

- 来源：GitHub Docs《Troubleshooting the REST API》（docs.github.com/en/rest/using-the-rest-api/troubleshooting-the-rest-api，2026-09 核对）：
  "GitHub uses a 404 Not Found response instead of a 403 Forbidden response to avoid confirming
  the existence of private repositories."
- 推论：对私有资源，**404 无法区分"对象不存在"与"无权限"**。因此任何"404 = 对象没了"的判定
  都必须先做一次 sanity 读（如 GET repo）：sanity 通过 → 真不存在；sanity 失败 → 权限/可用性
  问题，标 UNKNOWN + 权限告警，绝不标 MISSING。
- 本推论同时否定了"新增 ACCESS_UNKNOWN 状态"的必要性：UNKNOWN + 告警事件已完备表达，
  状态词集合保持 PRESENT/MISSING/UNKNOWN 三态不膨胀。

### F-4 合并队列工具的组合验证是"入队后、按序、CI 确定性"【Merge Preview 市场定位证据，ADR-021】

- GitHub Merge Queue（docs.github.com/en/repositories/.../managing-a-merge-queue，2026-08-31 核对）：
  "the changes in the pull request are grouped into a merge_group with the latest version of the
  base_branch as well as changes from pull requests ahead of it in the queue"；required checks 跑在
  组合提交上。注意：是对**累积组合**逐 merge_group 验证，不是任意 PR 两两组合
  （"Merge limits do not combine merge_group builds"）。
- Mergify（docs.mergify.com/merge-queue/batches、/speculative-checks，同日核对）：
  batch merging（batch_size 多 PR 合批过 CI）；speculative/parallel checks（临时 batch PR 并行验证
  累积组合）；失败拆批二分定位（"Mergify binary-searches for the culprit"，状态含 bisecting）。
- Trunk Merge Queue（docs.trunk.io/merge-queue/optimizations/parallel-queues，同日核对）：
  按 impacted targets 动态建并行队列（"dynamically creating merge queues for pull requests that
  affect different parts of your codebase"）；"every pull request is predictively tested against
  the pull requests ahead of it"。需 PR 上传 impacted targets（Bazel/Nx action）否则不处理。
- 结论：三家都做**组合验证**，但全部是入队后、按队列顺序、只跑确定性 CI——
  "入队前主动选择 + 语义评审分层 + 双 PR 证据 + 可回放"的空间仍在（见 ADR-021）。

### F-5 AI 评审工具的分析单位是单个 PR，无组合树验证【Merge Preview 市场定位证据，ADR-021】

- CodeRabbit（docs.coderabbit.ai，2026-08-31 核对）：living memory 学历史 PR/反馈属实；
  "多仓上下文"仅限 learnings/规则全局复用，无跨仓代码图；评审单位恒为单个 PR
  （Change Stack = "review scope"）；无公开证据表明构造多个在飞 PR 的组合合并树做验证。
- Greptile（greptile.com/docs，同日核对）：全仓代码图 + context.repos 跨仓上下文属实；
  T-REX 沙箱执行粒度是逐 PR（"every PR"各自独立）；同样无组合树验证的公开描述。
- 结论：评审类工具（语义层）不做组合；队列类工具（确定性层）不做语义。两层之间是空档。

### F-6 Langfuse v3 自托管资源底线远超本项目余量【ADR-020 不部署裁定的硬数字】

- 架构（langfuse.com/self-hosting，2026-08-31 核对）：Web + Worker + Postgres + ClickHouse +
  Redis/Valkey + S3/Blob 六组件全部必需（v3 起无精简部署）；可选第七件 LLM Gateway。
- 最低资源表（langfuse.com/self-hosting/configuration/scaling）：Web 2C/4Gi、Worker 2C/4Gi、
  PG 2C/4Gi、Redis 1C/1.5Gi、ClickHouse 2C/8Gi、MinIO 2C/4Gi——合计约 11C/25.5Gi；
  Compose 部署官方建议 4C/16Gi（t3.xlarge）。对照 195 余量约 2G：**超出约 13 倍**，
  "不部署"不是偏好是硬约束。

### F-7 OTel GenAI 约定全部 Development 且无独立发布【ADR-020 适配层+版本锁的强制依据】

- GenAI semconv 已迁独立仓库 open-telemetry/semantic-conventions-genai（主仓库 v1.42.0 起旧位置
  全部 deprecated）；新仓库 46 处 stability 声明**全部 development**（2026-08-31 main 快照统计），
  且尚无 release、schema_url 仍是 TODO。
- 主仓库当前 v1.44.0；version-selection 机制（声明式配置 + dual_emit + 降级到最近支持版本）
  文档存在但自身也是 Development 状态、未发布到官网。
- 结论：GenAI 字段**没有任何稳定锚点**——领域代码直接依赖必炸；适配层 + 版本锁不是
  可选加固，是使用的先决条件。同时 webhook 不携带 traceparent，根 Trace 必须在入口自建，
  业务关联仍用业务 ID（Trace ID 只做观测关联，不进账本身份）。

### F-8 GitHub 官方限流处置：Retry-After 头必须等够；无头二级限流至少等 60s【M2 精确退避的依据】

- 来源一：GitHub Docs《Best practices for using the REST API》与《Rate limits for the REST API》
  （docs.github.com/en/rest/using-the-rest-api/best-practices-for-using-the-rest-api，
  2026-08-31 核对）：
  "If the `retry-after` response header is present, you should not retry your request until
  after that many seconds has elapsed."——**带头时等够秒数是官方明示义务**。
- 来源二：GitHub Docs《Troubleshooting the REST API》（同域，同日核对）：二级限流无
  `retry-after` 头时 "wait for at least one minute before retrying"，持续失败则
  "wait for an exponentially increasing amount of time between retries"。
- 佐证（社区实录）：hub4j/github-api issue #1805——GitHub 二级限流**不总是**带
  `Retry-After` 头；粗放重试会放大处罚。故"无头时下限 60s + 指数"是必要兜底而非可选。
- 推论（M2 I23）：publisher 侧 `TypedResponse` 必须携带响应头解析结果，三处重试调度
  （outbox 写路径 / recovery 扫描 / drift 巡检）有头不早于 now+retryAfter，无头 429
  下限 60s。

### F-9 Resilience4j 熔断器语义实证【M3 手写熔断的设计依据与坑清单】

- HALF_OPEN permit 为**原子预扣**而非事后统计：`CircuitBreakerStateMachine.HalfOpenState.tryAcquirePermission()`
  用 AtomicInteger 预扣额度，领完即拒 CallNotPermittedException
  （github.com/resilience4j/resilience4j 源码 + resilience4j.readme.io/docs/circuitbreaker，
  2026-08-31 核对）→ 佐证 M3 `BreakerPermit` 设计。
- `maxWaitDurationInHalfOpenState` 默认 **0 = 无限等待**：探针挂死即 permit 泄漏、状态机永久卡
  HALF_OPEN（官方配置表）→ M3 必补探针独立超时 + 超时/取消强制归还 permit。
- `recordFailurePredicate` 默认一切异常算失败；`recordExceptions` 白名单 / `ignoreExceptions`
  不计数（官方文档 + 源码 handleThrowable）→ 佐证异常分类白名单；坑：ignore 类异常路径若不归还
  permit 会死锁；谓词/回调自身抛异常也泄漏 permit（源码专门打补丁）→ **permit 归还必须放 finally**。
- 官方默认装饰器顺序 Retry 在最外层（Retry(CircuitBreaker(...))），issue #2383 指出此默认导致
  每次重试都被熔断计为独立失败 → M3 明示条款：每次物理触网失败计入熔断（保守方向，重试次数
  已被预算封顶）。
- 状态纯进程内存态（Registry 基于 ConcurrentHashMap）；集群/持久化特性请求 issue #419 至今未实现
  → 佐证 R-M3 内存态裁定，多实例不一致是官方也未解决的已知局限。

### F-10 Spring AI 1.0.0 隐藏重试实证【I34 的事实基础；INC-42 来源】

- 官方 1.0 参考文档 Retry Properties（docs.spring.io/spring-ai/reference/1.0/api/chat/openai-chat.html，
  2026-08-31 核对）：`spring.ai.retry.max-attempts` 默认 **10**；backoff initial 2s、multiplier 5、
  max-interval 3min；`on-client-errors` 默认 false（4xx 不重试）。一次逻辑调用最坏 **10 次真实
  HTTP、拖 20+ 分钟**。
- 关闭方式：`spring.ai.retry.max-attempts=1`（无 enabled 键）；retry 为 ChatModel 级配置，
  不支持 per-request 覆盖（issue #3858）。
- 版本事实：1.0.x 底层为自家 RestClient 封装的 OpenAiApi；openai-java SDK 集成是 1.1 可选模块
  （OpenAiSdkChatModel），2.0.0-M5 起才全面改写（docs.spring.io/spring-ai/reference/upgrade-notes.html
  + Spring 官方博客 2026-04-27）。openai-java 默认 maxRetries=2（github.com/openai/openai-java README）
  → 升级检查单须固化 `maxRetries(0)`。
- Spring AI 1.0 无 per-request timeout/cancellation 官方 API；只能走底层 HTTP client 的
  connect/read 超时配置。
- **在役影响**：本项目 M0~M2 从未设置该项，Spring AI 默认 10 次隐藏重试一直在生效（INC-42）。

### F-11 百炼限流与错误码官方文档实证【§4.2 二维分类的依据】

- 官方三篇核心文档（help.aliyun.com/zh/model-studio/error-code、/rate-limit、
  /rate-limiting-best-practices，2026-08-31 核对）**均未提及 Retry-After 响应头**；官方最佳实践
  示例为客户端自算指数退避+随机抖动（min 1s / max 60s，5~6 次），并述"限流通常 1 分钟内自动恢复"。
- 429 族：`Throttling.RateQuota`（RPM/RPS 请求频率）、`Throttling.AllocationQuota`（TPM/TPS token
  用量）、`Throttling.BurstRate`（流量增速）、通用 `Throttling`。
- **非 429 族**：`AllocationQuota.FreeTierOnly`（免费额度耗尽）是 **403**；`Model.AccessDenied` 是 403；
  `Arrearage`（欠费）是 **400**；"未开通服务"也是 400。
- 同码不同状态：`Throttling.AllocationQuota` 还存在 400 版本（音色/热词等资源配额）→ 分类器必须
  status × code 二维联合判定，不能只看 code。
- BurstRate 官方首选解法：请求头 `X-DashScope-Wait-Timeout: 3~120` 做服务端排队（仅对增速限流
  有效，对 RPM/TPM 无效）。
- 官方明示限流按**秒级** RPS/TPS 执行：分钟级总量未超限也会被拒。
- 错误 body 双结构：OpenAI 兼容模式 `{"error":{"code","message","param","type"}}`；DashScope 原生
  模式顶层 `{"code","message","request_id"}` → 分类器两种都要能解析。
- 官方建议 429 时才降级备选模型（仅限流错误才降级）→ 佐证 D7"ClientError 族不 fallback"。

### F-12 Stripe 幂等键与不确定态【两段记账的同构先例】

- Idempotent Requests（docs.stripe.com/api/idempotent_requests，2026-08-31 核对）：连接错误后用同
  key 安全重放，服务端保存首个请求的完整结果（含 500）并原样返回；key 24h 过期；参数校验失败/
  并发冲突不保存结果可直接重试（"前置拒绝"与"执行中失败"官方即作区分 → 佐证 M3 决策事件与
  物理调用分账 D14）。
- PaymentIntent 生命周期（docs.stripe.com/payments/paymentintents/lifecycle）：无 UNKNOWN 终态，
  但 `processing` 是诚实中间态（异步支付可达数天），终态靠 webhook 事后补推 → 不确定态不可省略，
  且必须有对账/reconciler 收口（M3 Recovery 同构）。
- 关键差异：模型 API（百炼/OpenAI 兼容）**无幂等键**——重试不是重放而是真实第二次计费调用，
  故每物理调用独立账本行（驳回"重试共享一行"建议）。

### F-13 Temporal 活动超时分类【总 deadline 分层的依据】

- 官方文档（docs.temporal.io/encyclopedia/detecting-activity-failures，2026-08-31 核对）：
  ScheduleToClose = 跨全部重试的总时长；StartToClose = 单次尝试上限；Heartbeat 检测 worker 存活。
- "Temporal Server 不检测 worker 失联，靠 StartToClose 超时强制重试"——**超时是唯一可信的崩溃
  检测器** → M3 Recovery 按时间扫描 STARTED 标 UNKNOWN 的依据。
- M3 映射：gateway-total-deadline ≈ StartToClose（单次编排）；attempt 预算+Defer ≈ ScheduleToClose
  （跨重试总量）。

### F-14 gRPC deadline 传播与重试节流【deadline 下传与 R-M7 的依据】

- 官方指南（grpc.io/docs/guides/deadlines/，2026-08-31 核对）：deadline 跨层自动传播，以**剩余
  timeout** 传递（免疫时钟偏移）→ M3 总 deadline 换算剩余毫秒逐层下传，各层不自设绝对值。
- 同文档：客户端 DEADLINE_EXCEEDED 后"server application is responsible for stopping any activity
  it has spawned"——服务端**可能继续执行** → R-M7（超时后供应商可能继续计费）的官方实证。
- A6 proposal（github.com/grpc/proposal/blob/master/A6-client-retries.md）：retryPolicy maxAttempts
  客户端硬上限 5——官方理由是防 DNS 下发恶意配置的安全缓解，**不是容量最优值**（引用勿误传）；
  retry throttling 令牌桶（token ≤ maxTokens/2 时全禁重试）；deadline 横跨全部 attempts。

### F-15 Envoy 重试预算与故障域处置【总预算 + 同域禁 fallback 的依据】

- retry_budget（circuit_breaker.proto，2026-08-31 核对）：活跃重试并发 ≤(active+pending)×budget_percent
  （默认 20%、min_retry_concurrency=3——**低流量时比例预算失真才需要这个兜底**）；设置后覆盖
  max_retries 熔断器。issue #30205：backoff 中的重试计入分子。
- retry_host_predicate `PreviousHostsPredicate` 拒绝已试主机；outlier detection 把连续 5xx 主机
  逐出 LB 集合（官方 intro 文档）→ **端点级故障的设计哲学是"换实例 + 逐出坏实例"，同端点原地
  重试被认为无效**——I36 同域禁 fallback 的强佐证。
- `rate_limited_retry_back_off`：收到 Retry-After/X-RateLimit-Reset 时改用服务端给定退避——
  域内精确退避先例。
- 官方重试语义明文：所有重试包含在整体请求超时内（deadline 横跨 attempts，同 F-14）。

### F-16 Google SRE 重试风暴定量【预算语义与故障冒泡的依据】

- 《Addressing Cascading Failures》（sre.google/sre-book，2026-08-31 核对）：100 QPS 失败重试
  →200→300 正反馈直至崩溃；多层各 4 attempts 则放大 4³=64 次。
- 《Handling Overload》（同书）：每请求预算 ≤3 attempts；**每客户端重试占比预算 10%**——无比例
  预算最坏流量 ~3x，有则 ~1.1x；"大面积过载时错误应直接冒泡不重试"；"只在紧邻失败层的上一层重试"。
- 对 M3：max-physical-calls-per-step=6 偏上限可接受（gRPC 硬上限 5、Envoy 默认并发 3、SRE 3）；
  比例预算在低流量失真（F-15 min_retry_concurrency 同族坑）→ 单实例低并发本版用总额度，
  比例语义记 M3-P11 观察项。

### F-17 Spring AI 1.0.0 的 429 误分类与 usage 元数据坑【ProviderErrorClassifier 输入约束的依据】

- 官方源码（raw.githubusercontent.com/spring-projects/spring-ai/v1.0.0/.../RetryUtils.java，
  2026-08-31 核对）：`DEFAULT_RESPONSE_ERROR_HANDLER` 对**所有 4xx 一律抛
  `NonTransientAiException`**——429 限流与"参数错误、该停"混为一谈；官方 issue #3857
  自承粒度不足。默认 RetryTemplate 只 retryOn TransientAiException。
- 结论：M3 的 `ProviderErrorClassifier` **不得以 Spring AI 异常类型（Transient/NonTransient）
  为分类输入**，必须解析原始 HTTP status/headers/body——否则全部 429 会被误判为不可重试，
  §4.2 的限流细分整体失效。
- usage 元数据坑：Advisor 覆盖 usage（issue #1309）；流式 usage 恒 0（issue #4785，1.1 未修）。
  本项目同步单发不走 Advisor/流式，主要残余风险是 usage 为 null 被静默记 0（M0~M2 的
  `SpringAiModelClient.map()` 正是此行为）→ M3 `usage_missing` 显式标记条款（§4.8）的依据。

### F-18 JDK HttpClient 中断不释放在途请求【超时兜底条款的依据】

- JDK-8245462（bugs.openjdk.org，2026-08-31 核对）：线程中断时 `send()` 抛
  InterruptedException，但 **HTTP 请求在后台继续执行，同步调用方无任何句柄取消它**；
  JDK 21 上行为依旧。Apache HttpClient 亦有中断/超时泄连接的案（HTTPCLIENT-2416）。
- 结论：`SpringAiModelClient.callWithTimeout()` 的 `executor.shutdownNow()` 只是"放手"——
  模型端继续计费生成、socket 挂到服务端超时。唯一可靠兜底是 request factory 级
  connect/read timeout（§4.10）；R-M7 的"不可见费用"由此从"可能"升级为"确定存在窗口"。

### F-19 LangGraph checkpointer 实证【checkpoint 设计的对照组】

- Postgres checkpointer（docs.langchain.com/oss/python/langgraph/checkpointers，2026-08-31
  核对）：super-step 边界全量 StateSnapshot + 节点级 pending writes；thread_id/checkpoint_ns
  两级命名空间；durability 三档 sync/async/exit（exit 模式 interrupt 不可用）。
- 官方自承认的坑：checkpoint 无内建保留策略（需自写 cron）；序列化膨胀实证 85% 开销
  （issue #7714）；写放大生产事故（12 节点×500 线程=6000 次写）；schema 初始化与连接语义
  耦合脆弱（#7630/#5327）；跨版本破坏性变更史（#3557）。
- interrupt/resume：恢复时**整个节点从头重跑**，interrupt 前副作用必须幂等（官方警告
  while True+interrupt 指数级重放）——"恢复即重放"把幂等性留作隐式约定。
- 对照结论：本项目"digest 契约五分量显式拒绝 + CAS 大对象分离 + 租约栅栏"在幂等保证上
  强于 LangGraph 的隐式约定；durability 分档思想可作"按丢失后果选持久化时机"的论证框架；
  interrupt/全量快照/ns 命名空间对单 Step 架构为投机性泛化，拒绝引入（且无 Java 实现）。

### F-20 OpenHands / SWE-agent harness 设计实证【错误分级与预算分层的同构验证】

- OpenHands 事件系统（docs.openhands.dev/sdk/arch/events + 源码）：事件流 append-only 为
  唯一事实源；`Event.source` 与 LLM `role` 刻意分离；错误分两级——AgentErrorEvent（工具级，
  喂回模型自愈）vs ConversationErrorEvent（会话级，不喂模型直接终态）。429 重试参数：
  4 次、5~30s 指数退避、乘数 2。
- SWE-agent（swe-agent.com 文档 + 源码）：三级预算 per_instance/total/per_instance_call_limit
  + 1.1 倍超额保险丝；解析失败 requery 上限 3；fallbacks 走 litellm 列表；每步
  save_trajectory 但无运行时断点续跑（崩溃=attempt 报废）。
- 已知坑：OpenHands #12344 密钥序列化不对称致恢复失败——"存得下≠恢复得了"，恢复路径必须
  专项测；#6857 max_iterations 曾失效。
- 对照结论：错误两级分类与我们"调用级故障走 §4.2、输出级失败走 Step FAILED"同构互证；
  预算分层与 §4.4 四预算同构；requery/死循环检测/RetryAgent 为多轮循环特效药，拒绝
  （记 M3-P12 触发式评估 requery）。重试参数档位（4 次/5~30s）佐证我方默认值同量级合理。

### F-21 Agent 工程方法论三文献【架构定位的权威背书】

- Anthropic《Building effective agents》（anthropic.com/engineering/building-effective-agents，
  2026-08-31 核对）：workflow（预定义路径）vs agent（自主决策）划分；"对多数应用，优化单次
  LLM 调用已足够"；警惕框架抽象遮蔽原始 prompt/response；工具设计 poka-yoke。
- HumanLayer《12-Factor Agents》（github.com/humanlayer/12-factor-agents）：F8 own your
  control flow（自写控制流才能中断/限流/恢复）；F9 compact errors（错误入上下文自愈 +
  errorCounter 阈值接管——我们无"下一次调用"，转用为熔断/重试分类依据）；F12 stateless
  reducer；F5 状态统一作者自承非强制。
- OpenAI Agents SDK（openai.github.io/openai-agents-python）：guardrails tripwire 模式
  （input 拦在贵模型前 / output 校验产出）；sessions 多轮记忆九种后端。
- 适配结论：本项目是 workflow 不是 agent，获直接背书；确定性输出校验（FindingMapper
  契约）已实现等于 output guardrail 的确定性部分；LLM 型 guardrail/sessions/预分类路由
  与"单次调用"定位冲突，拒绝。raw 请求不入 CAS 的裁定：prompt 可由 input digest +
  contract 五分量**确定性重建**，无需存储（响应不可重建故必须存——INC-19）。

### F-22 Kleppmann fencing token 文献对照【lease_epoch 的边界确认】

- martin.kleppmann.com/2016/02/08/how-to-do-distributed-locking.html（2016，通用结论）：
  fencing token 必须由**资源持有方**主动检查拒绝回退写。
- 对照：本项目 (owner, epoch) 比较放进 UPDATE WHERE 由 Postgres 原子裁决——教科书式正确，
  强于 Redlock。
- **边界**：fencing 只覆盖存储写；对 GitHub API/模型调用这类不可 fencing 的外部副作用，
  epoch 挡不住重复执行——租约在模型在途时过期，reclaimer 会发起第二次调用，epoch 只挡
  落库不挡花钱。M3 必须保证 lease 时长 > gateway-total-deadline + 余量（启动校验，
  §4.9）+ heartbeat 续租（M0 既有）。

### F-23 GitHub Checks API 实证坑【漂移源清单输入】

- 官方文档（docs.github.com/en/rest/checks/runs，2026-08-31 核对）：传 conclusion 自动置
  status=completed（隐式跃迁）；`stale` 只能 GitHub 设；**rerequest 会清空 conclusion、
  重置 suite 为 queued，但不改 check run**——App 须收 `check_run.rerequested` webhook 自行
  决策；同名 check run 超 1000 自动删旧；无 ETag/条件更新（last-write-wins）。
- 对本项目：check run 终态可被 GitHub 单方面清空 = 新漂移源；好在 DriftReconciler 以远端
  为准已覆盖检测面；rerequested 策略（重新评审 or 重置 Run）涉及 revision/epoch 语义，
  留 M4/M5（M3-P13）。

### F-24 WireMock 故障注入保真度边界【E2E 诚实清单依据】

- 官方文档（wiremock.org/docs/simulating-faults/，3.x）：支持固定/对数正态延迟、chunked
  dribble、四种 Fault；**`CONNECTION_RESET_BY_PEER` 在 Windows 上表现为挂起而非 reset**——
  本机（Windows）跑 reset 类用例会误报，必须在 195 Linux 跑。
- 模拟不了：TLS/DNS 层失败、SSE 流中途 RST、限流配额窗口滑动、GitHub 写后读旧值最终一致性、
  rerequest 清 conclusion 这类**有状态服务端行为**（stub 无服务端状态，scenario 只能脚本化
  不能对弈）。
- 结论：M3 E2E 的重试/熔断/超时/截断在能力内；状态机对弈类标注"近似注入"，上线前需
  真实环境回归一次（既有 e2e 真实通道）。

### F-25 Outbox / SKIP LOCKED 参考实现对照【自研等价性确认】

- microservices.io transactional-outbox（2026-08-31 核对）：两个固有坑——relay 可能重复
  发布（消费者必须幂等）、开发者可能漏写；Debezium CDC 免轮询但引入 Kafka Connect 全家桶，
  同样 at-least-once。
- 对照：本项目 INSERT-only（DB 角色无 UPDATE 权）比参考实现更严；单 claimer 串行天然保序；
  RECONCILING 探测先于重发正是重复发布坑的正解。**真实缺口**：relay 崩溃=静默停摆，
  参考实现均未覆盖 relay 存活监控 → M3-P14（outbox 最老 PENDING 年龄自检）。
- SKIP LOCKED：crunchydata 博客两坑（高周转表膨胀、处理期持事务阻塞 vacuum）；pgmq 用
  visibility timeout 替代长事务锁。本项目 claim 已是"SKIP LOCKED + 短事务租约立即提交"
  等价模型，两坑已避；pgmq 扩展=新外部组件，ADR-020 不过，拒绝。

### F-26 Uber 软件工厂成本治理实证【M3 成本账本口径的参照系】

- 《Running a Software Factory Efficiently at Uber Scale》（uber.com/us/en/blog/efficient-software-factory/，
  2026-08-27，2026-08-31 核对；正文抓取有截断，缺口经 cellcog/CSDN 转述交叉核对）：
  >70% PR 有 agent 参与（人工 review 仍在环）；3,600+ 员工自建 skills 日均 30K+ 次执行；
  2 月至 8 月周活 7x、请求 9.4x 而总花费自 4 月持平；固定模型口径每千请求成本降 34%。
- 核心方法：agentic 成本拆六因子方程（用户数×会话×轮次×请求×token×单价）逐项独立度量；
  **按产出计价**（cost per merged PR / per review），不按 token 计价；benchmark 驱动选型
  （统一 harness 接任意模型选 Pareto 最优，模型几周一换）；subagent 默认降级便宜模型；
  prompt cache TTL 按真实空闲间隔调；MCP 工具 schema 不进上下文（省 50~70K token/会话）。
- 对本项目：成本账本聚合口径补"每 PR/每 finding"产出计价（§4.8 runbook 已含 Run 级聚合，
  产出计价列记 runbook 增量）；subagent 降级/cache TTL/压缩对当前单 Step 形态无对象，不引入。

### F-27 Uber agent 身份危机与参与者链【多 agent 演进的安全输入；当前不采用】

- 《Solving the Identity Crisis for AI Agents》（uber.com/us/en/blog/solving-the-agent-identity-crisis/，
  2026-05-21，2026-08-31 核对）：agent 不是人也不是传统服务；多跳链路 provenance 丢失、
  审计断链。方案 = Agent Registry + SPIRE 工作负载身份 + STS 每跳短 TTL JWT（P99<40ms）+
  MCP Gateway 策略执行点 + AI Gateway 出站防护（AI Guard）；核心概念 Participant Chain
  （令牌携带发起用户→各 agent→当前调用者全链）。
- 关键教训：外挂代理解决不了 provenance，**必须集成进 agent SDK 应用层端到端传播**——
  与本项目"能在应用层解决就不引中间件"的冻结纪律同构。
- 适配裁定：SPIRE/STS/Mesh 全套是平台团队级基础设施，单人维护不可行、ADR-020 过不了，
  **不采用**；Participant Chain 思想可简化为事件表加 provenance 字段（M4 多 Step 编排时再评）。

### F-28 uReview 同领域实证【Uber 的 GenAI 代码评审系统；M5 评测域的最重要参照】

- 《uReview: Scalable, Trustworthy GenAI for Code Review at Uber》（uber.com/us/en/blog/ureview/，
  2025-08-12，2026-08-31 核对）：prompt-chaining 四段流水线——生成（可插拔 assistant）
  → 二次 prompt 打分+置信度过滤（阈值细到 assistant×语言×类别）→ 语义相似去重合并 →
  类别分类器整体抑制低价值类别（readability:naming 砍掉，correctness:null-check 保留）。
- **幻觉防护核心结论：单发 prompt 必然高误报；"Guardrails 和 prompt 同等重要"，pipeline/
  后处理比 prompt 设计更关键**——与本项目 harness 哲学（把模型装进工程流水线消除
  不确定性）完全同构。
- 评估闭环：逐条 Useful/Not Useful 反馈；自动 addressed 判定 = **对最终 commit 重跑 5 次**
  消除随机漏检；golden comments 人工标注 benchmark 算 P/R/F1；最优组合 Claude-4-Sonnet 生成
  + o4-mini-high 打分（生成模型≠评分模型）。
- 数据：周覆盖 65,000 diffs 的 90%+；usefulness ≥75%；65% 评论被 address（人类 51%）；
  CI 中位 4 分钟；成本比第三方低一个数量级。
- 教训：精确度>数量；开发者讨厌 readability/style 类评论；**linter 能查的别用 LLM**；
  缺 PR 历史/schema/文档上下文所以评不了系统设计；灰度逐 team/assistant 放量 + A/B +
  go/hold 仪表板。
- 适配裁定：后处理链（打分/过滤/去重/类别抑制）与反馈闭环全部可用纯 Java+Postgres 实现，
  零新组件——但属**质量评测域（M5）**，不进 M3 范围（M3 只管调用可靠性与成本，不管
  输出质量分级）；finding 类别标签/置信度字段是否前置进 prompt 契约，记 M3-P17 由用户裁定。

---

*证据清单完。任何 v2.2 条文被质疑时，回查本文对应索引；新引入设计决策时，先补本清单再入冻结文档。*
