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

---

*证据清单完。任何 v2.2 条文被质疑时，回查本文对应索引；新引入设计决策时，先补本清单再入冻结文档。*
