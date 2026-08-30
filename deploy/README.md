# pr_agent M0 部署（T18）

目标主机：195 服务器（CentOS 7，内核 3.10，docker 26 + compose v2.27.1）。
构建现场 = compose 项目目录 `/opt/build/pr`（git 工作副本，deploy/ 直挂
../control-app 的迁移 SQL，单一事实源）；持久状态（.env / App 私钥）在
`/opt/projects/pr_agent`，经符号链接接入 deploy/，同步重建构建现场不影响。

栈组成（`docker-compose.yml` 头注有完整 hardening/迁移选型说明）：

| 服务 | 镜像 | 端口 | 说明 |
|---|---|---|---|
| postgres | postgres:16-alpine | 不发布 | `seccomp=unconfined`（INC-09，见下） |
| migrate | flyway/flyway:10-alpine | — | one-shot：owner 身份执行 V1/V2，完成即退出 |
| control-app | 本地构建 | 127.0.0.1:8080 | webhook 冒烟口，M0 不对公网开放 |
| publisher-app | 本地构建 | 不发布 | 只读 token 窄接口仅内网可达 |
| github-stub | wiremock/wiremock:3.13.1 | 127.0.0.1:19090 | M0 默认模式：GitHub API + 模型端点替身 |

## 部署步骤

```bash
# 0) 代码同步（本机执行）
tar czf - --exclude=target --exclude=.git --exclude=backups --exclude=.kimi-code --exclude=var . \
  | ssh -i ~/.ssh/id_ed25519 root@146.56.195.225 \
    'rm -rf /opt/build/pr && mkdir -p /opt/build/pr && tar xzf - -C /opt/build/pr'

# 1) 195 上准备持久目录（只放 .env 与私钥；构建现场每次同步会整体重建，不能放持久状态）
ssh -i ~/.ssh/id_ed25519 root@146.56.195.225
mkdir -p /opt/projects/pr_agent/keys

# 迁移 SQL 单一事实源 = control-app 资源目录，compose migrate 服务直挂
# ../control-app/src/main/resources/db/migration（T18 裁决：不复制双份，杜绝漂移）。
# 因此 compose 项目目录 = 构建现场 /opt/build/pr/deploy（../control-app 才有效）；
# 持久状态经符号链接接入：

# stub 模式的 GitHub App 私钥 = 一次性 RSA 测试 key（stub 不验签；真实部署换成真 key）
cd /opt/projects/pr_agent
[ -f keys/github-app-key.pem ] || \
  openssl genrsa 2048 2>/dev/null | openssl pkcs8 -topk8 -nocrypt > keys/github-app-key.pem
chmod 444 keys/github-app-key.pem   # 启动自检断言私钥无任何写位

# .env：全 stub 冒烟的最小集（模型/GitHub 均不打真实端点，不需要任何真实凭证）
cat > .env <<EOF
POSTGRES_PASSWORD=$(openssl rand -hex 24)
CONTROL_DB_PASSWORD=$(openssl rand -hex 24)
PUBLISHER_DB_PASSWORD=$(openssl rand -hex 24)
INTERNAL_TOKEN_SECRET=$(openssl rand -hex 32)
GITHUB_WEBHOOK_SECRET=$(openssl rand -hex 32)
GITHUB_APP_ID=880001
GITHUB_INSTALLATION_ID=555000
GITHUB_API_BASE=http://github-stub:8080
OPENAI_COMPAT_BASE_URL=http://github-stub:8080
AGENT_MODEL_API_KEY=stub-not-a-real-key
AGENT_MODEL=qwen-plus
EOF
chmod 600 .env

# 符号链接接入 compose 项目目录（deploy/.gitignore 已排 .env 与 *.pem，链接不落库）
ln -sfn /opt/projects/pr_agent/.env /opt/build/pr/deploy/.env
ln -sfn /opt/projects/pr_agent/keys /opt/build/pr/deploy/keys

# 2) 构建 jar（宿主机 maven 容器，挂 m2repo 缓存卷）
docker run --rm -v /opt/build/pr:/build -w /build \
  -v /opt/build/pr/maven-settings-aliyun.xml:/root/.m2/settings.xml \
  -v m2repo:/root/.m2/repository \
  maven:3.9-eclipse-temurin-21 mvn -B -DskipTests package

# 3) 构建镜像（运行镜像只封装 jar，见两个 Dockerfile）
docker build -t pr-agent/control-app:0.0.1-SNAPSHOT /opt/build/pr/control-app
docker build -t pr-agent/publisher-app:0.0.1-SNAPSHOT /opt/build/pr/publisher-app

# 4) 起栈 + 部署验证（DP-01~05，证据落 deploy/smoke-evidence/）
cd /opt/build/pr/deploy && bash smoke-test.sh
```

> 注意（实测踩坑）：步骤 0 的同步是 `rm -rf /opt/build/pr` 后整体重建——bind 挂载
> 钉的是旧 inode，**重新同步后必须 `docker compose up -d --force-recreate`（或 down 再 up），
> 否则 github-stub 等容器看到的还是已删除的旧目录**（首轮冒烟 tarball 500 即此因）。

## 配置项清单（.env）

| 变量 | 消费方 | 说明 |
|---|---|---|
| POSTGRES_PASSWORD | postgres / migrate | 超级用户（owner）口令；只进这两个容器 |
| CONTROL_DB_PASSWORD / PUBLISHER_DB_PASSWORD | postgres(initdb) / 对应应用 | 应用角色口令，initdb 时由 `db/01-roles.sh` 建角色 |
| GITHUB_WEBHOOK_SECRET | control | webhook HMAC 验签；缺失即启动失败（fail-closed） |
| INTERNAL_TOKEN_SECRET | control + publisher | 只读 token 窄接口共享密钥（X-Internal-Token 头，两边一致才放行） |
| GITHUB_APP_ID / GITHUB_INSTALLATION_ID | publisher | GitHub App 身份（stub 模式为占位值） |
| GITHUB_MINT_REPOSITORIES | publisher | 铸 token 收窄的仓库名（空 = installation 全域） |
| GITHUB_API_BASE | control + publisher | stub 模式 `http://github-stub:8080`；真实 = `https://api.github.com` |
| OPENAI_COMPAT_BASE_URL / AGENT_MODEL_API_KEY / AGENT_MODEL | control | 模型端点；stub 模式指 github-stub、key 为占位串 |

## Secret 管理（B-5 诚实边界）

- 所有密码/密钥经 `.env`（chmod 600）注入环境变量，或经 compose secrets 文件挂载
  （私钥 `keys/github-app-key.pem` → `/run/secrets/github-app-key.pem`，mode 0444 只读）。
- **Docker Compose secrets 是受控文件挂载，不是 KMS**：值在宿主机文件系统明文存在，
  保护依赖宿主机文件权限与 SSH 边界。M0 接受该边界；M4+ 再评估 KMS/凭据管家。
- `.env` 与 `*.pem` 已在 `.gitignore`，永不入库；冒烟用的 App 私钥是一次性测试 key，
  stub 不验签，与任何真实 GitHub App 无关。
- 真实模型 key（若切真实端点）只允许写入 195 上 `.env` 或经 ssh 环境注入，
  不得落盘到仓库任何文件/日志。

## 冒烟（DP-01~05）

`bash smoke-test.sh` 顺序执行并留证据（`smoke-evidence/<ts>/summary.txt` 为一页结论）：

- DP-01：`docker compose up -d` 一键起栈；migrate 退出码 0；两应用日志含『启动自检通过』；
  control 未签名 POST → 401（fail-closed 兼作存活探针）；四容器存活。
- DP-02：一次性容器注入 `GITHUB_WRITE_TOKEN` → control 拒绝启动（日志含自检失败、点名变量名、
  不含值）→ 删除注入容器恢复。
- DP-03：`grant update on outbox_command to control_app` → control 重启后拒绝启动
  （has_table_privilege 自检）→ revoke + 重启恢复。
- DP-04：`docker inspect` 逐项断言 non-root / ReadonlyRootfs / CapDrop ALL / no-new-privileges /
  私钥 RO 挂载 / 无 docker.sock / restart policy / 端口暴露面，输出检查表。
- DP-05：HMAC 签名伪造 `pull_request.opened` → 202 → 轮询 DB 至本批次 outbox 全 CONFIRMED →
  断言 stub 恰好收到 1 个 check（external_id=operation_id）+ 1 个 review（含幂等 marker）。

## INC-09：seccomp 说明（195 内核 3.10）

- postgres:16-alpine 在默认 seccomp profile 下 initdb 报 `pg_wal` EPERM，
  **必须** `security_opt: seccomp=unconfined`（compose 中已声明，仅此一个例外）。
- JVM 容器（eclipse-temurin:21-jre）实测默认 seccomp 可正常启动
  （`java -version` 与 Spring Boot 全量启动均验证），不加例外；flyway:10-alpine 同。
- 根因是宿主机内核过旧（CentOS 7 已 EOL）；长期解法是换 117 或升级内核，M0 记录即可。

## 切真实 GitHub / 真实模型

**195 当前形态（T18 验收态）= 混合模式：GitHub API 走 github-stub（无真实 App 凭证），
模型链路走真实百炼**（195→百炼已实测连通：POST /compatible-mode/v1/chat/completions 回 401
= 端点活、需鉴权；DP-05 的 review step 经真实 qwen-plus 完成，stub journal 无模型请求）。
注意 Spring AI 1.0 会把 base-url 拼上 `/v1/chat/completions`，所以
`OPENAI_COMPAT_BASE_URL` **不要带 /v1 尾段**（写成 `.../compatible-mode`），否则 404。

1. `.env` 改 `GITHUB_API_BASE=https://api.github.com`、`OPENAI_COMPAT_BASE_URL=<百炼端点，不含 /v1>`，
   填入真实 `AGENT_MODEL_API_KEY` / `GITHUB_APP_ID` / `GITHUB_INSTALLATION_ID`；
2. `keys/github-app-key.pem` 换成真实 App 私钥（PKCS#8 PEM，chmod 444）；
3. 移除 `github-stub` 服务（并去掉 control/publisher 对它的 depends_on）；
4. webhook 入口暴露由网关/反代决定——M0 默认只绑 127.0.0.1，不对公网开放。
