# OSS 调研 M4-G1：Sandbox Broker 评审断言的 Docker 一手资料核实

- 调研日期：2026-09-02
- 核实范围：评审方对 Docker / docker-java 的 10 条断言
- 一手来源：docs.docker.com（及其 GitHub 源码仓库 docker/docs、docker/cli）、moby/moby `api/swagger.yaml`（v20.10.24）、docker-java/docker-java 源码
- 环境背景：宿主 CentOS 7 / 内核 3.10.0-1160 / Docker 20.10+；Broker 容器挂载 `/var/run/docker.sock` 通过 docker-java 调用宿主 dockerd

---

## 断言 1：bind mount 路径由 daemon 所在宿主解释，Broker 容器内 tmpfs 不能直接作 bind source；named volume 是可行替代

**判定：成立**

**一手来源 URL**

- https://docs.docker.com/engine/storage/bind-mounts/
- https://docs.docker.com/engine/storage/volumes/

**关键原文**

> "Bind mounts are created to the Docker daemon host, not the client. If you're using a remote Docker daemon, you can't create a bind mount to access files on the client machine in a container."
> —— bind-mounts 文档
>
> 中文释义：bind mount 是在 Docker daemon 所在主机上创建的，而不是在客户端侧。即使面对"远程 daemon"，也无法用客户端机器上的路径做 bind mount 的 source。

> "`source`, `src` — The location of the file or directory on the host."
> —— `--mount type=bind` 选项表（同上页）
>
> 中文释义：`--mount` 的 source 明确指"宿主机上的文件/目录位置"。

> "Volumes can be more safely shared among multiple containers." / "A given volume can be mounted into multiple containers simultaneously." / "Multiple containers can mount the same volume. You can simultaneously mount a single volume as `read-write` for some containers and as `read-only` for others."
> —— volumes 文档
>
> 中文释义：named volume 可被多个容器同时挂载，且支持对一部分容器只读、另一部分读写。

**对本项目设计的含义**

Broker 与 Job 是兄弟容器，二者路径命名空间互相独立；Broker 向 dockerd 传的 bind source 必须是宿主路径（如 `/var/lib/xxx/staging`），Broker 容器自身需先把该宿主目录挂载进来（`docker run -v /var/lib/xxx/staging:/sandbox-staging`），否则容器内的临时目录/tmpfs 在宿主上无对应路径，Job 容器 create 会失败或挂到错误位置。named volume 作为替代方案官方明确支持（Broker rw、Job ro 挂载同一 volume），可行；但 volume 数据落在 `/var/lib/docker/volumes/`，若需宿主直接访问文件则 bind mount 更直接。

---

## 断言 2：GET /containers/{id}/archive 以 tar 流返回，对已停止（未删除）容器可用；客户端需自行安全解包

**判定：部分成立**（API 事实成立；"官方文档有安全解包警告"未核实到一手来源）

**一手来源 URL**

- https://docs.docker.com/reference/api/engine/version/v1.41/#tag/Container/operation/ContainerArchive （即 moby `api/swagger.yaml`）
- https://github.com/moby/moby/blob/v20.10.24/api/swagger.yaml
- https://github.com/docker/cli/blob/v20.10.24/docs/reference/commandline/cp.md （对应 https://docs.docker.com/reference/cli/docker/container/cp/）
- 补充（moby 侧 tar/symlink 真实风险）：https://github.com/moby/moby/pull/39252 （CVE-2018-15664 docker cp symlink race）

**关键原文**

> `GET /containers/{id}/archive` — "Get an archive of a filesystem resource in a container. Get a tar archive of a resource in the filesystem of container id." `produces: ["application/x-tar"]`
> —— swagger.yaml（v20.10.24）
>
> 中文释义：该端点以 tar 归档流形式返回容器文件系统资源。

> "The `CONTAINER` can be a running or stopped container."
> —— docker cp 文档（v20.10.x）
>
> 中文释义：`docker cp`（底层即 archive 端点）对已停止的容器同样有效——前提是容器未被删除。

**关于安全解包**：在 Engine API 文档与 `docker cp` 文档中**未找到**针对客户端解包 tar 流的安全警告（path traversal / symlink / device / FIFO / 稀疏文件均无官方提示）。但 moby 自身在 `docker cp` 的 daemon/CLI 侧就出过 symlink 相关漏洞（CVE-2018-15664，moby/moby PR #39252），说明该类风险真实存在。评审"客户端必须自行做安全解包"的工程建议正确，但不应归因为"官方文档有警告"。

**对本项目设计的含义**

结果取回可走 `GET /containers/{id}/archive?path=/out`，容器停止后仍可读（删容器前先取）。解包侧必须使用带防穿越/拒绝 symlink-device-FIFO 的 tar 解包器（不可信作业可构造恶意 tar 内容），且建议限制单文件/总大小；这条要写成 Broker 的强制实现要求，而不是指望 docker-java 默认安全。

---

## 断言 3：只设 `--memory` 不限制 swap；禁止 swap 需 `--memory-swap` 等于 `--memory`

**判定：成立**

**一手来源 URL**

- https://docs.docker.com/engine/containers/resource_constraints/ （原 /config/containers/resource_constraints/）

**关键原文**

> "If `--memory-swap` is unset, and `--memory` is set, the container can use as much swap as the `--memory` setting, if the host has swap memory configured. For instance, if `--memory="300m"` and `--memory-swap` is not set, the container can use 600m in total of memory and swap."
>
> 中文释义：只设 `--memory` 而不设 `--memory-swap` 时，容器可用"内存 + 等量 swap"（宿主配置了 swap 的前提下），实际内存上限翻倍。

> "If `--memory-swap` is set to the same value as `--memory`, and `--memory` is set to a positive integer, the container doesn't have access to swap." / "If `--memory` and `--memory-swap` are set to the same value, this prevents containers from using any swap."
>
> 中文释义：`--memory-swap` 设为与 `--memory` 相同的正值，容器即完全无法使用 swap。

**对本项目设计的含义**

加固 Job 容器时 `HostConfig.Memory` 与 `MemorySwap` 必须成对设置相等值，否则不可信作业可借 swap 扩张实际内存占用，绕过表面上的内存限额。注意 cgroup v1（CentOS 7 / 3.10 内核）下需确认 swap accounting 已启用，否则 dockerd 会打印 "Your kernel does not support swap limit capabilities" 警告且限制不生效。

---

## 断言 4：`docker run image cmd` 的 cmd 只覆盖 CMD；ENTRYPOINT 存在时 cmd 成为其参数；须用 `--entrypoint` 覆盖

**判定：成立**（附一处措辞修正：API 中 Entrypoint 属于容器 Config，不在 HostConfig）

**一手来源 URL**

- https://docs.docker.com/reference/cli/docker/container/run/#default-entrypoint （源码：docker/docs `_vendor/github.com/docker/cli/docs/reference/run.md`）
- https://github.com/moby/moby/blob/v20.10.24/api/swagger.yaml （Config.Entrypoint 字段）
- https://github.com/docker-java/docker-java/blob/master/docker-java-api/src/main/java/com/github/dockerjava/api/command/CreateContainerCmd.java

**关键原文**

> "If the image also specifies an `ENTRYPOINT` then the `CMD` or `COMMAND` get appended as arguments to the `ENTRYPOINT`."
>
> 中文释义：镜像定义了 ENTRYPOINT 时，`docker run image cmd` 传入的 cmd 会被追加为 ENTRYPOINT 的参数，而非替代 ENTRYPOINT。

> "`--entrypoint=""`: Overwrite the default entrypoint set by the image … You can reset a containers entrypoint by passing an empty string, for example: `$ docker run -it --entrypoint="" mysql bash`"
>
> 中文释义：只有 `--entrypoint` 才能覆盖镜像的 ENTRYPOINT；传空串可将其重置为无。

> swagger.yaml Config：`Entrypoint: "The entry point for the container as a string or an array of strings. If the array consists of exactly one empty string ([""]) then the entry point is reset to system default"`
>
> 中文释义：Engine API 的 Entrypoint 字段在容器 Config（create body 顶层）中，传 `[""]` 可重置。

docker-java 源码确认存在：

```java
// CreateContainerCmd.java
@CheckForNull
String[] getEntrypoint();

CreateContainerCmd withEntrypoint(String... entrypoint);
CreateContainerCmd withEntrypoint(List<String> entrypoint);
```

**对本项目设计的含义**

Broker spawn Job 容器时不能依赖 `withCmd(...)` 来固定执行入口——若 Job 镜像带 ENTRYPOINT，传入命令只会成为其参数。应显式 `withEntrypoint(...)`（或传 `[""]` 清空后再用 cmd）。措辞修正：`Entrypoint`/`Cmd` 在 API 里是 Config 字段（docker-java 的 `CreateContainerCmd.withEntrypoint`），不是 HostConfig 字段；评审中"HostConfig 与 Commands"的说法把归属搞混了，但方法存在、结论正确。

---

## 断言 5：json-file 支持 max-size/max-file 轮转；local 驱动默认轮转；可按容器 log-opt 设置；无限制时刷 stdout 会耗尽宿主磁盘

**判定：成立**

**一手来源 URL**

- https://docs.docker.com/engine/containers/logging/json-file/ （原 /config/containers/logging/json-file/）
- https://docs.docker.com/engine/containers/logging/local/
- https://docs.docker.com/engine/install/linux-postinstall/ （"Configure default logging driver" 节）

**关键原文**

> json-file 选项表："`max-size` — The maximum size of the log before it is rolled. … Defaults to -1 (unlimited)." / "`max-file` — The maximum number of log files that can be present. … Only effective when `max-size` is also set."
>
> 中文释义：json-file 支持 max-size/max-file 轮转，但 max-size 默认 -1（不限制），max-file 仅在 max-size 已设时生效——默认配置下日志无上限。

> "You can set the logging driver for a specific container by using the `--log-driver` flag to `docker container create` or `docker run`: `docker run --log-driver json-file --log-opt max-size=10m alpine echo hello world`"
>
> 中文释义：日志驱动与 log-opt 可按容器粒度设置。

> local 驱动："By default, the `local` driver preserves 100MB of log messages per container … based on a 20M default size for each file and a default count of 5 … (to account for log rotation)."（max-size 默认 20m、max-file 默认 5）
>
> 中文释义：local 驱动默认即带轮转与上限（每容器约 100MB）。

> linux-postinstall："The default logging driver, `json-file`, writes log data to JSON-formatted files on the host filesystem. Over time, these log files expand in size, leading to potential exhaustion of disk resources."
>
> 中文释义：官方明确警告——json-file 日志写在宿主文件系统，长期增长可能耗尽宿主磁盘（日志文件位于 `/var/lib/docker/containers/<id>/` 下）。

**对本项目设计的含义**

恶意作业无限刷 stdout 确实能涨爆宿主 `/var/lib/docker` 所在分区，这是真实的拒绝服务面。Job 容器必须按容器设置 `LogConfig`（`Type=json-file`，`Config: {max-size, max-file}`），或在 daemon.json 全局兜底；两者可同时做（容器级覆盖 daemon 级）。

---

## 断言 6：create 时可设 labels，list 支持 label filter，可实现按 job-id/epoch 清扫孤儿容器的 reaper

**判定：成立**

**一手来源 URL**

- https://docs.docker.com/reference/api/engine/version/v1.41/#tag/Container/operation/ContainerCreate 与 /ContainerList（即 moby swagger.yaml）
- https://github.com/docker-java/docker-java CreateContainerCmd（`withLabels`）

**关键原文**

> swagger.yaml `POST /containers/create` 的 Config：`Labels: "User-defined key/value metadata."`（object, map[string]string）
>
> 中文释义：create 容器时可设置任意键值 labels。

> swagger.yaml `GET /containers/json` 的 `filters` 参数："Filters to process on the container list, encoded as JSON (a `map[string][]string`). … Available filters: … `label=key` or `label="key=value"` of a container label"
>
> 中文释义：容器列表 API 支持 label 过滤器（仅 key 或 key=value），JSON 编码的 `map[string][]string`，形如 `{"label": ["job-id=xxx"]}`。

**对本项目设计的含义**

Reaper 模式可行且是官方支持的用法：Job 容器 create 时打 `broker=sandbox`、`job-id=<uuid>`、`epoch=<n>` 等 label，Broker 重启后用 `listContainersCmd.withLabelFilter(...)`（底层即上述 filters）找出残留容器并清理。同时 volume 也支持 label + filter，孤儿 volume 可同样处理。

---

## 断言 7：Broker（客户端进程）死亡后，已创建容器由 dockerd 继续运行，不会自动停止

**判定：成立**（断言括号内举例措辞需修正，见下）

**一手来源 URL**

- https://docs.docker.com/reference/cli/docker/container/run/ （"Foreground and background" 节；源码 docker/docs `_vendor/.../run.md`）
- https://docs.docker.com/reference/cli/docker/container/attach/
- https://docs.docker.com/engine/containers/start-containers-automatically/

**关键原文**

> "When you start a container, the container runs in the foreground by default. If you want to run the container in the background instead, you can use the `--detach` (or `-d`) flag. … While the container runs in the background, you can interact with the container using other CLI commands. For example, `docker logs` lets you view the logs for the container, and `docker attach` brings it to the foreground."
>
> 中文释义：容器的运行不依赖发起它的客户端会话；CLI（客户端）退出/断开后容器仍由 daemon 管理运行，之后可随时再 logs/attach。容器生命周期挂在 daemon（经 containerd/runc）与容器内 PID 1 上，而非 API 客户端进程上。

> restart policies 文档："`always` — Always restart the container if it stops." / "`--rm` — Automatically remove the container and its associated anonymous volumes when it exits"（docker run 选项表）
>
> 中文释义：容器只在自身退出、被显式 stop/kill 或 daemon 关停（无 live-restore 时）时停止；restart policy 决定退出后是否重启；`--rm` 决定退出后是否自动删除。

**措辞修正（评审括号内举例不准确）**

- `--rm`/autoRemove 的语义是"容器**退出后**自动删除"，它不会在 Broker 死亡时停止仍在运行的容器；若 Broker 死亡时 Job 还在跑，`--rm` 也帮不上忙。
- restart policy 的作用是"退出后重启"，同样不是"客户端死亡后停止"。
- 因此断言主干（客户端死了容器照跑）成立，但括号里"除非显式设置"的三个例子与"停止"的因果关系并不成立——真正的兜底只有容器 PID 1 自行退出，或外部的 reaper（断言 6）清理。

**对本项目设计的含义**

Broker 崩溃/重启后，在跑的 Job 容器不会被 dockerd 停掉，必须由基于 label 的 reaper 主动发现并 kill/rm；这正是断言 6 的模式存在的原因。

---

## 断言 8：dockerd 重启时运行中容器的默认行为与 restart policy 的作用

**判定：成立**

**一手来源 URL**

- https://docs.docker.com/engine/containers/live-restore/
- https://docs.docker.com/engine/containers/start-containers-automatically/

**关键原文**

> "By default, when the Docker daemon terminates, it shuts down running containers. You can configure the daemon so that containers remain running if the daemon becomes unavailable. This functionality is called live restore."
>
> 中文释义：默认（live-restore 关闭）时，dockerd 终止会同时关闭运行中的容器；开启 `"live-restore": true` 后 daemon 不可用时容器保持运行。

> "Restart policies are different from the `--live-restore` flag of the `dockerd` command. Using `--live-restore` lets you keep your containers running during a Docker upgrade, though networking and user input are interrupted."
>
> 中文释义：restart policy 与 live-restore 是两个独立机制。

> restart policy 表："`no` — Don't automatically restart the container. (Default)"；"`on-failure` … doesn't restart the container if the daemon restarts."；"`always` — If it's manually stopped, it's restarted only when Docker daemon restarts or the container itself is manually restarted."
>
> 中文释义：默认 `no`——daemon 重启后容器不会自动拉起；`on-failure` 明确不因 daemon 重启而重启容器；`always`/`unless-stopped` 才会在 daemon 重启后拉起容器。

**对本项目设计的含义**

宿主 dockerd 重启（升级/崩溃）后：默认配置下 Job 容器已被停止且不会回来，Broker 恢复后需要靠 reaper 发现这些"尸体"（status=exited/dead）并收集残留结果/清理。注意 live-restore 只支持 patch 级升级（`YY.MM.x`），且 daemon 配置（bridge IP、graph driver）变化时可能失效。

---

## 断言 9：Docker 官方把 docker socket 访问视为 root 等价/高危

**判定：成立**

**一手来源 URL**

- https://docs.docker.com/engine/install/linux-postinstall/ （"Manage Docker as a non-root user" 节）
- https://docs.docker.com/engine/security/ （"Docker daemon attack surface" 节）

**关键原文**

> linux-postinstall（WARNING 框）："The `docker` group grants root-level privileges to the user. For details on how this impacts security in your system, see Docker Daemon Attack Surface."
>
> 中文释义：官方原话——加入 docker 组（即获得 `/var/run/docker.sock` 访问权）等于授予用户 root 级权限。

> security 文档："First of all, only trusted users should be allowed to control your Docker daemon. … Docker allows you to share a directory between the Docker host and a guest container; and it allows you to do so without limiting the access rights of the container. This means that you can start a container where the `/host` directory is the `/` directory on your host; and the container can alter your host filesystem without any restriction."
>
> 中文释义：官方解释为何 socket 访问是 root 等价——拿到 daemon 控制权即可起容器把宿主 `/` 挂进去并无限制改写宿主文件系统。

> 同页："even if you have a firewall to limit accesses to the REST API endpoint from other hosts in the network, the endpoint can be still accessible from containers, and it can easily result in the privilege escalation."
>
> 中文释义：从容器内访问 daemon API 是典型的提权路径——这恰好就是 Broker 容器的部署形态。

**对本项目设计的含义**

Broker 容器挂 `/var/run/docker.sock` 等于持有宿主 root 等价能力，是整个架构里最大的单点：Broker 进程一旦被不可信输入攻破（或 Broker 自身漏洞），攻击面直接覆盖宿主。设计评审应要求：Broker 不直接处理不可信内容、严格校验所有传入 dockerd 的参数（官方原文也专门警告了"if you instrument Docker from a web server … be even more careful … with parameter checking"）。

---

## 断言 10：docker-java 的 `awaitCompletion(timeout)` 超时返回 false，`close()` 中止在途 HTTP

**判定：成立（源码级一手来源）**，且实际情况比评审说的更强

**一手来源 URL**

- https://github.com/docker-java/docker-java/blob/master/docker-java-api/src/main/java/com/github/dockerjava/api/async/ResultCallbackTemplate.java
- （旧路径 `docker-java-core/.../async/ResultCallbackTemplate.java` 现仅保留 deprecated 转发类）

**关键原文（源码）**

```java
/**
 * Blocks until {@link ResultCallback#onComplete()} was called or the given timeout occurs
 * @return {@code true} if completed and {@code false} if the waiting time elapsed
 *         before {@link ResultCallback#onComplete()} was called.
 */
public boolean awaitCompletion(long timeout, TimeUnit timeUnit) throws InterruptedException {
    try {
        boolean result = completed.await(timeout, timeUnit);
        throwFirstError();
        return result;
    } finally {
        try {
            close();          // ← 超时返回 false 时也会执行
        } catch (IOException e) { ... }
    }
}

@Override
public void close() throws IOException {
    if (!closed) {
       closed = true;
       try {
           if (stream != null) {
               stream.close();   // stream 为 onStart(Closeable) 传入的在途 HTTP 响应流
           }
       } finally {
           completed.countDown();
       }
    }
}
```

中文释义：超时等待返回 `false`（javadoc 与实现一致）；`close()` 关闭 `onStart` 时拿到的在途流（底层即 HTTP 响应流）。注意实现细节：`awaitCompletion` 的 `finally` 块**无条件调用 `close()`**——即超时不只是"返回 false"，还会顺带中止该回调对应的在途 HTTP 请求。

**对本项目设计的含义**

Broker 对 `waitContainer` / `logContainer` / `copyArchiveFromContainer` 等异步回调使用带超时的 `awaitCompletion` 是安全的：超时即返回 false 且连接被中止，不会悬挂线程；但要注意超时后该回调流已关闭，不能再复用/重读，需要重新发起请求。

---

## 总结

**完全成立、可直接采纳（措辞无需改动）**：断言 1、3、5、6、8、9。其中：

- 断言 1（bind mount 宿主侧解释 + named volume 替代）有官方文档逐字背书；
- 断言 3（memory-swap 相等才禁 swap）有官方逐字背书，且对 CentOS 7/cgroup v1 需额外验证 swap accounting 已启用；
- 断言 5 的"刷爆磁盘"风险官方在 postinstall 文档里自己警告过；
- 断言 9 的"socket 访问 = root 级权限"是官方 WARNING 框原话。

**成立但需修正措辞**：

- 断言 2：API 事实（tar 流、停止容器可用）成立；但"官方文档有解包安全警告"查无出处，应改为"官方无警告，客户端必须自行安全解包（moby 自身有 CVE-2018-15664 前科）"。
- 断言 4：结论成立、docker-java `withEntrypoint` 存在；但 Entrypoint 在 API 中属于容器 **Config**（CreateContainerCmd 顶层），不在 **HostConfig**，评审把字段归属说错了。
- 断言 7：主干成立（客户端死亡容器照跑）；但括号内把 `--rm`、restart policy 当作"客户端死亡后停止容器"的机制是错的——`--rm` 只在容器退出后删除，restart policy 只在容器退出后重启，二者与"Broker 死亡"无因果关系。
- 断言 10：成立且可加强——`awaitCompletion(timeout)` 超时不仅返回 false，其 `finally` 还会 `close()` 掉在途 HTTP 流，即超时即中止。

**未找到一手来源的点**：仅断言 2 中"官方文档对 archive tar 流的安全解包警告"一项；其余 10 条全部核实到一手来源（docs.docker.com / docker-cli 文档 / moby swagger.yaml / docker-java 源码）。
