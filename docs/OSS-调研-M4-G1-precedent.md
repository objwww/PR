# OSS 调研：M4 Sandbox 设计修订的业界先例证据

> 调研范围：OpenHands（All-Hands-AI/OpenHands，引用 0.50.0 tag 与 main）、SWE-agent / SWE-ReX（SWE-agent/SWE-rex main）、E2B（e2b-dev/E2B SDK 源码 + 官方文档）、GitHub Actions runner（actions/runner main）、Actions Runner Controller（actions/actions-runner-controller）、GitLab Runner（官方文档）。
> 原则：一手源码/官方文档为准；二手文章仅作线索并在文中标注。
> 版本说明：OpenHands main 分支已重构为 Agent Canvas 形态，runtime 实现细节以 0.50.0 tag 的稳定代码为准引用；docker_runtime.py 引用 main 分支（该文件在 main 仍存在且逻辑更新）。

---

## 1. 可写 workspace：源码只读 + 启动复制/挂载到可写区执行

### 业界做法

**OpenHands（DockerRuntime）**
- workspace 默认以 **rw bind mount** 挂进沙箱（`SANDBOX_VOLUMES=host:container[:mode]`，默认 mode=`rw`；legacy `workspace_mount_path` 同样 `rw`）。官方文档明说："Anything mounted read-write into `/workspace` can be modified by the agent."
- 同时内建了 **overlay 挂载模式**：`mode` 含 `overlay` 时，host 目录作为只读 `lowerdir`，为每个容器创建独立 `upper`/`work` 层做 copy-on-write —— 这正是"源码只读 + 可写 workspace"的 Docker 原生实现，写入不回流宿主。
- 关键代码：`_process_volumes()` 与 `_process_overlay_mounts()`（docker_runtime.py, main）。

**SWE-agent / SWE-ReX**
- SWE-bench 场景下，代码快照**烘焙进镜像**（仓库位于 `/testbed`），容器的可写镜像层就是 workspace，改完随容器销毁丢弃。另一种方式是通过容器内 swe-rex server 的 `/upload` HTTP 接口把 zip 传进容器解压。`DockerDeployment` 本身不做源码 bind mount（仅透传用户 `docker_args`）。

**GitHub Actions runner**
- job 容器把 `_work`（含 checkout 的代码）以 **rw** bind mount 挂入（`MountWellKnownDirectories`，默认 `ReadOnly=false`），而 runner 自带的 **Externals 目录以 read-only 挂载**（Linux 分支显式传 `true`）。即"执行产物区可写、宿主工具区只读"的混合挂载。

### 一手来源
- docker_runtime.py（main）`_process_volumes` / `_process_overlay_mounts`：https://raw.githubusercontent.com/All-Hands-AI/OpenHands/main/openhands/runtime/impl/docker/docker_runtime.py
- OpenHands Docker sandbox 文档（SANDBOX_VOLUMES、rw 警告）：https://docs.openhands.dev/openhands/usage/sandboxes/docker
- SWE-ReX DockerDeployment：https://raw.githubusercontent.com/SWE-agent/SWE-rex/main/src/swerex/deployment/docker.py
- GitHub runner ContainerOperationProvider.cs `MountWellKnownDirectories`：https://raw.githubusercontent.com/actions/runner/main/src/Runner.Worker/ContainerOperationProvider.cs

### 对本项目的适配裁定
**采纳。** 评审的"源码只读 + 可写 workspace"有 OpenHands overlay 模式（ro lowerdir + COW upper）和 GitHub runner"工具区 ro + 工作区 rw"的直接先例。本项目是"只执行、不原地修改"的形态，比 OpenHands 默认的 rw 原位挂载更严格，用 overlay 或启动时复制到容器内可写层/tmpfs 均可；不建议学 OpenHands 默认 rw bind（那是 agent 需要原地改代码的产品形态）。

---

## 2. 结果提取：取出容器内产物

### 业界做法

**OpenHands**
- 不用 docker cp/archive API。容器内常驻 action-execution HTTP server，宿主端 `copy_from()` 调 `GET /download_files`（服务端打成 zip 流式回传，宿主写临时 zip 文件）；`copy_to()` 调 `POST /upload_file`（目录先本地打 zip 再传）。若 workspace 是 bind mount，产物直接落在宿主机，无需提取。

**SWE-ReX / SWE-agent**
- 上传：HTTP multipart 到容器内 server `/upload`，目录先本地 `shutil.make_archive` 打 zip，容器内 `zipfile.ZipFile.extractall(target_path)` 解压 —— **注意：无 zip-slip 路径校验**（target_path 也直接由客户端指定），属反面教材。
- 下载：server 只有 `read_file`（单文件内容回传），无目录打包下载端点；SWE-agent 评测靠 `read_file` 读容器内生成的 patch 文件拿回结果。

**E2B**
- 不走 docker cp。SDK `sandbox.files.read/write` 经沙箱内 envd 守护进程 HTTP 传输；结果也可由沙箱内命令自行上传。

**GitHub runner**
- job 容器产物直接写在 bind-mount 的 `_work` 目录，宿主 runner 直接读文件并作为 artifacts 上传服务端 —— 无"提取"环节。

### 一手来源
- ActionExecutionClient.copy_from/copy_to（0.50.0）：https://raw.githubusercontent.com/All-Hands-AI/OpenHands/0.50.0/openhands/runtime/impl/action_execution/action_execution_client.py
- SWE-ReX server.py `/upload`（extractall 无防护）：https://raw.githubusercontent.com/SWE-agent/SWE-rex/main/src/swerex/server.py
- SWE-ReX remote.py upload/read_file：https://raw.githubusercontent.com/SWE-agent/SWE-rex/main/src/swerex/runtime/remote.py
- E2B Python SDK（commands.run / files API）：https://raw.githubusercontent.com/e2b-dev/E2B/main/packages/python-sdk/README.md
- GitHub runner 挂载 `_work`：同上 ContainerOperationProvider.cs

### 对本项目的适配裁定
**部分采纳。** 评审建议的 docker archive API（`docker cp` 等价物）在这些系统里**恰恰不是主路径**——它们都有容器内常驻服务可走 HTTP 回传，或干脆 bind mount 免提取。本项目容器 network none、无容器内 agent，用 Docker archive API 取回 `/out` 是合理且必要的自洽选择（方向与评审一致，只是无直接先例）。"把输出 tar 当不可信输入做安全解包"在业界**无先例**（SWE-ReX 的裸 `extractall` 是反面证据），须自创：白名单路径、拒绝 `..`/绝对路径/symlink 逃逸、单文件与总量限额。

---

## 3. 日志与输出限额：流式限额 + truncated 标记

### 业界做法

**OpenHands（BashSession，0.50.0）**
- 命令在容器内 tmux 会话执行，`HISTORY_LIMIT = 10_000` 行作为有界滚动缓冲；每 0.5s `capture-pane` 轮询。超限时不崩、不无限占内存，而是在 observation 前缀显式标记：`[Previous command outputs are truncated. Showing the last N lines of the output below.]` —— **tail 保留 + truncated 标记**的直接先例。另有 no-change timeout（默认 30s）与 hard timeout 两级超时。

**GitHub runner**
- stdout/stderr 逐行事件回调（`OutputDataReceived`）直接转发写入日志管道，runner 侧不做大小截断（限额在 GitHub 服务端）；已知超长行会显著拖慢 runner（issue #1031，二手线索）。

**GitLab Runner / GitLab（对照先例）**
- 官方文档：job log 默认上限 **100 MB**，超限 job 被标记 failed 且日志被 runner 丢弃（drop），管理员可调。即"硬上限 + 超限显式标记为失败"的生产先例。

**E2B**
- `commands.run()` 默认把 stdout/stderr **完整缓冲进内存字符串**返回（CommandResult），无截断；流式回调（onStdout/onStderr）为可选。对本项目"禁止无界读入内存"而言是反面教材。

### 一手来源
- bash.py（0.50.0）`HISTORY_LIMIT`、truncated prefix： https://raw.githubusercontent.com/All-Hands-AI/OpenHands/0.50.0/openhands/runtime/utils/bash.py
- GitHub runner DockerCommandManager.cs 输出回调：https://raw.githubusercontent.com/actions/runner/main/src/Runner.Worker/Container/DockerCommandManager.cs
- GitLab 官方文档 job log 100MB 上限：https://docs.gitlab.com/administration/cicd/job_logs/
- E2B command_handle.py（CommandResult 全量缓冲）：https://raw.githubusercontent.com/e2b-dev/E2B/main/packages/python-sdk/e2b/sandbox/commands/command_handle.py

### 对本项目的适配裁定
**采纳。** OpenHands 的"有界缓冲 + 超限打 truncated 标记"与 GitLab 的"硬上限 + 超限显式处置"正好覆盖评审要求的两半：流式读取、到达上限即截断写盘并置 `truncated=true`，绝不无界读入内存。E2B 式全量缓冲应避免。

---

## 4. 孤儿容器清扫：标签 + 启动/退出 reaper

### 业界做法

**GitHub Actions runner（最强先例）**
- `ContainerOperationProvider.StartContainersAsync` 在每个 job 开始、领新容器**之前**先执行 "Clean up resources from previous jobs"：`docker ps --all --filter "label={DockerInstanceLabel}"` 找出残留容器逐个 `docker rm --force`，再 `docker network prune --filter label=...`。`DockerInstanceLabel` = runner 根目录 `.runner` 配置文件内容的 sha256 前 6 位（runner 实例级标签）。同时注册 post-job 步骤 `StopContainersAsync`（`always()`）做正常清理 —— 启动清扫兜底崩溃残留，与评审要求逐字对应。

**OpenHands**
- 进程退出路径注册 shutdown listener，调 `stop_all_containers(CONTAINER_NAME_PREFIX)` 按**名字前缀**（`openhands-runtime-` + session id）批量停容器；`close(rm_all_containers)` 配置项控制关全部还是只关本会话。用"统一前缀"充当名单，是优雅退出清扫（非崩溃恢复）。

**E2B**
- 平台侧 TTL reaper：沙箱默认 5 分钟无操作自动 kill（可 `set_timeout` 续期，Pro 上限连续 24h）；`kill()` 显式销毁。生命周期清扫完全由服务端按 timeout 驱动，客户端崩溃不影响回收。

### 一手来源
- ContainerOperationProvider.cs（启动清扫 + label filter + network prune）：https://raw.githubusercontent.com/actions/runner/main/src/Runner.Worker/ContainerOperationProvider.cs
- DockerCommandManager.cs（`DockerInstanceLabel` 生成）：https://raw.githubusercontent.com/actions/runner/main/src/Runner.Worker/Container/DockerCommandManager.cs
- containers.py `stop_all_containers`（0.50.0）：https://raw.githubusercontent.com/All-Hands-AI/OpenHands/0.50.0/openhands/runtime/impl/docker/containers.py
- docker_runtime.py shutdown listener 注册（main）：https://raw.githubusercontent.com/All-Hands-AI/OpenHands/main/openhands/runtime/impl/docker/docker_runtime.py
- E2B 沙箱生命周期文档：https://docs.e2b.dev/sandbox

### 对本项目的适配裁定
**采纳。** GitHub runner 的"job/实例标签 + 启动时 `ps --filter label=` 清扫 + post-job 正常清理"模式可直接照搬：容器打 `job=<id>, epoch=<n>` 标签，Broker 启动先按标签清扫再领作业。可再叠 E2B 式 TTL（容器级 `stop-timeout`/Broker 侧看门狗）做双保险。

---

## 5. 业务失败 vs 基础设施失败分类与重试纪律

### 业界做法

**SWE-ReX**
- `RemoteRuntime._request(endpoint, payload, output_class, num_retries=0)`：默认**不重试**；重试只包传输层异常（连接失败等），指数退避有上限（0.1s×2 封顶 5s）。关键设计：每次请求带 `X-Request-ID` **幂等键**，服务端 `ResponseManager` 缓存已执行请求的响应，重试命中即返回旧结果而不重复执行 —— "重试必须幂等"的明确先例。
- 沙箱内命令非零退出：作为 `CommandResponse`/`Observation`（含 exit code、输出）正常返回，即"失败即数据"。

**OpenHands**
- `_send_action_server_request` 用 tenacity **只重试 `httpx.RemoteProtocolError` / `httpcore.RemoteProtocolError`**（协议级传输故障），最多 5 次指数退避（4–15s）；`httpx.TimeoutException` 直接转 `AgentRuntimeTimeoutError` 不重试；容器退出/丢失 → `AgentRuntimeDisconnectedError` / `AgentRuntimeNotFoundError`（运行时故障类，fail-fast）。
- 命令非零退出 = `CmdOutputObservation`（metadata 里带 exit_code），是喂给 agent 的数据，绝不重跑。

**GitHub runner**
- 只对**平台操作**重试：docker pull 与 registry login 各重试 3 次（1–10s 随机退避），3 次失败抛 `InvalidOperationException`（基础设施失败）。step 命令非零退出 → step failed → job failed，**不重跑**。

**E2B**
- 非零退出抛 `CommandExitException`，异常对象**携带 exit_code/stdout/stderr**——失败即数据（异常即数据载体），SDK 不做任何重试。

### 一手来源
- remote.py `_request`（num_retries=0、X-Request-ID 幂等键）：https://raw.githubusercontent.com/SWE-agent/SWE-rex/main/src/swerex/runtime/remote.py
- server.py `ResponseManager`（幂等响应缓存）：https://raw.githubusercontent.com/SWE-agent/SWE-rex/main/src/swerex/server.py
- action_execution_client.py `_is_retryable_error` / tenacity 配置（0.50.0）：https://raw.githubusercontent.com/All-Hands-AI/OpenHands/0.50.0/openhands/runtime/impl/action_execution/action_execution_client.py
- ContainerOperationProvider.cs pull/login 重试 3 次：https://raw.githubusercontent.com/actions/runner/main/src/Runner.Worker/ContainerOperationProvider.cs
- E2B command_handle.py `CommandExitException`：https://raw.githubusercontent.com/e2b-dev/E2B/main/packages/python-sdk/e2b/sandbox/commands/command_handle.py

### 对本项目的适配裁定
**采纳。** 四家一致：命令非零退出 = 业务数据，不重跑；重试仅限传输/平台故障、次数有限、指数退避；SWE-ReX 额外证明重试必须配幂等键防重复执行。评审的 failure_class 三分类（业务/安全/平台）比业界通行的两分类（业务数据 vs 可重试传输错误）更细——安全故障 fail-closed 一档是本项目增强，无直接先例但方向不冲突。

---

## 6. 镜像固定与入口控制：digest 钉死、ENTRYPOINT 覆盖、argv 命令

### 业界做法

**OpenHands**
- 启动沙箱时显式 **`entrypoint=[]` 置空**镜像自带 ENTRYPOINT（注释：镜像默认 entrypoint 是 bash，与二进制 command 冲突），`command` 以 **list[str]（argv）** 形式传给 docker SDK 启动 action server。拉取侧镜像 tag 自带构建 hash（`0.50-nikolaik` 等）。
- 注意：agent 的 bash 命令是在容器内 tmux 会话以 shell 字符串执行的（产品形态需要交互 shell），这与 broker 场景不同。

**SWE-ReX**
- `docker run` 命令构造为 **argv list + `subprocess.Popen(cmds)`（无 shell=True）**；镜像构建后**校验 image id 必须是 sha256**，否则拒绝。
- `DockerDeploymentConfig.pull` 三态：**`never` / `missing` / `always`** —— "部署期预取、运行期禁 pull"的直接先例（`missing`=本地有则不拉）。
- 容器内启动命令经 `exec_shell`（默认 `["sh", "-c"]`）包装后作为 argv 传入。

**GitHub runner**
- job 容器**显式覆盖 entrypoint** 为 `tail -f /dev/null` 常驻，命令以 ARGs 形式追加；`docker create` 参数字符串拼接后走 `ProcessInvoker.ExecuteAsync(fileName=docker, arguments=...)`（不经 shell），env/路径用 `DockerUtil.CreateEscapedOption` 转义；step 脚本写成临时文件再 `docker exec` 执行。
- 镜像 digest 固定由用户在 workflow YAML 里写 `image@sha256:...`，runner 照单执行。

**GitLab Runner（对照）**
- `config.toml` 提供 `disable_entrypoint_overwrite`（默认 false = **默认覆盖**镜像 ENTRYPOINT）、`allowed_pull_policies`、`allowed_images` 白名单——把"入口控制"和"镜像收敛"做成管理员级配置的先例。

### 一手来源
- docker_runtime.py（main）`entrypoint=[]`、`command: list[str]`：https://raw.githubusercontent.com/All-Hands-AI/OpenHands/main/openhands/runtime/impl/docker/docker_runtime.py
- docker.py（SWE-ReX）argv list、pull 三态、sha256 image id 校验：https://raw.githubusercontent.com/SWE-agent/SWE-rex/main/src/swerex/deployment/docker.py
- DockerCommandManager.cs `--entrypoint` 覆盖、CreateEscapedOption：https://raw.githubusercontent.com/actions/runner/main/src/Runner.Worker/Container/DockerCommandManager.cs
- ContainerOperationProvider.cs `ContainerEntryPoint = "tail"` / `"-f" "/dev/null"`：https://raw.githubusercontent.com/actions/runner/main/src/Runner.Worker/ContainerOperationProvider.cs
- GitLab Docker executor 文档（disable_entrypoint_overwrite、allowed_pull_policies、allowed_images）：https://docs.gitlab.com/runner/executors/docker/

### 对本项目的适配裁定
**采纳。** "显式覆盖/置空 ENTRYPOINT"有 OpenHands（`entrypoint=[]`）、GitHub runner（`tail -f /dev/null`）、GitLab（默认覆盖）三方一致先例；"argv 不用 shell 拼接"有 SWE-ReX（argv list）与 GitHub runner（ProcessInvoker 不经 shell + 转义）先例；SWE-ReX 的 `pull=never/missing` 正是"部署期预取、运行期禁 pull"；digest 钉死业界由配置层承担，本项目落到 Broker 校验即可。

---

## 开放问题：同机形态下把 docker.sock 挂给 agent 容器的先例与立场

### 正面先例（控制面挂 sock）

**OpenHands**：官方 README（0.50.0）的启动命令明确 `-v /var/run/docker.sock:/var/run/docker.sock`——主容器拿 sock 的目的是作为 **broker** 启动平级（sibling）沙箱容器，沙箱容器内默认**不**再透传 sock。同时官方给出两条边界声明：公网部署须按 "Hardened Docker Installation" 加固（限制网络绑定等）；项目定位"单用户本地工作站，不适合多租户共享实例"。即：官方承认 sock 授权 = 宿主 root 等价权限，因此限定信任域（控制面）与部署形态，而非声称挂 sock 本身安全。

### 反面/劝退先例

- **GitLab 官方文档**（最明确的官方表态）：明确 "Do not mount the Podman/Docker socket into job containers… The socket controls the host service, so exposing it to a job returns the privilege that rootless exists to remove."——sock 只许 runner（控制面）持有，作业容器不得触碰；并把 privileged/DinD 收进 rootless + `allowed_privileged_services` 白名单。
- **GitHub self-hosted runner / ARC**：actions-runner-controller 早期支持 docker.sock 模式，后续版本默认转向 **dind sidecar（privileged）→ rootless dind / kubernetes containerMode**；安全社区分析（StepSecurity 博客、ruse.tech 的 ARC DinD 逃逸文章——二手线索，结论可回到 ARC 官方文档的 containerMode 演进）一致把"runner 能摸到 daemon socket"列为逃逸根源。
- ARC 官方 controller README 还提供了 pod 失败重试 5 次、24h 未接单则 Actions 服务端撤单等平台侧纪律（与主题 4/5 互证）。

### 一手来源
- OpenHands README 0.50.0（docker.sock 挂载 + Hardened 警告 + 单用户声明）：https://raw.githubusercontent.com/All-Hands-AI/OpenHands/0.50.0/README.md
- GitLab Docker executor 文档（禁止向 job 容器挂 socket、rootless dind 白名单）：https://docs.gitlab.com/runner/executors/docker/
- ARC controller README（containerMode 演进、重试纪律）：https://raw.githubusercontent.com/actions/actions-runner-controller/master/docs/gha-runner-scale-set-controller/README.md
- 二手线索：StepSecurity《How to Use Docker in ARC Runners Securely》https://www.stepsecurity.io/blog/how-to-use-docker-in-actions-runner-controller-runners-securelly ；ruse.tech《Escaping Kubernetes-based GitHub Action Runners》https://www.ruse.tech/blogs/github-arc-dind-node-escape

### 对本项目的适配裁定
**部分采纳。** 业界的共识结构是：broker/runner 持 sock 属控制面授权（OpenHands 同构先例成立），但 **(a) 绝不向不可信作业容器透传 sock（GitLab 明令）**；**(b) 部署文档必须声明该授权等价宿主 root 并限定信任域**（OpenHands 的单用户/多租户警告式声明）。真正的"同机加固"（rootless 模式 socket、authz 代理插件如 docker authz plugin）业界只有零散实践、无成熟公开先例，需自创并在设计文档中写明风险立场。

---

## 总结：评审六块要求的先例背书情况

| 评审要求 | 业界先例 | 裁定 |
|---|---|---|
| 1. 源码只读 + 可写 workspace | OpenHands overlay（ro lower + COW upper）、GitHub runner（工具区 ro + 工作区 rw） | **有强先例，采纳** |
| 2. remove 前取回 /out + 安全解包 | 提取主路径业界走容器内 HTTP 回传/bind mount，docker archive 无直接先例；安全解包无先例（SWE-ReX 裸 extractall 是反面） | **方向采纳，实现需自创** |
| 3. 日志流式限额 + truncated 标记 | OpenHands（有界 tmux 缓冲 + truncated 标记）、GitLab（100MB 硬上限 + 超限标记失败）；E2B 全量缓冲为反面 | **有强先例，采纳** |
| 4. 标签 + 启动清扫孤儿容器 | GitHub runner（label filter + 启动清扫 + post-job 清理）逐字对应；OpenHands 前缀名单；E2B TTL reaper | **有强先例，采纳** |
| 5. 失败分类、仅平台故障重试 | SWE-ReX（默认不重试 + 幂等键）、OpenHands（仅协议错误重试 5 次）、GitHub runner（仅 pull/login 重试 3 次）、E2B（失败即数据） | **有强先例，采纳；安全 fail-closed 一档为本项目增强** |
| 6. digest 钉死 + ENTRYPOINT 覆盖 + argv | SWE-ReX（pull 三态 + argv + sha256 校验）、OpenHands（entrypoint=[] + argv）、GitHub runner（tail -f /dev/null）、GitLab（默认覆盖 entrypoint + 镜像白名单） | **有强先例，采纳** |
| 开放问题 docker.sock | OpenHands 正面（控制面授权 + 信任域声明）；GitLab/ARC 反面（禁透传、转 dind/rootless） | **部分采纳，加固需自创** |

**本项目独有、需自创的部分**：(a) 不可信 tar 的安全解包器；(b) failure_class 中的"安全故障 fail-closed"独立档（业界只有业务/传输两类）；(c) 同机 sock 的具体加固机制（authz 代理 / rootless 化）；(d) docker archive API 提取 /out 这一具体路径（业界因架构不同均未采用，但不与任何先例冲突）。
