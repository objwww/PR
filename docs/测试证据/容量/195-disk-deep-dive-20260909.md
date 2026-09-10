# 195 磁盘深查与卷裁定建议（2026-09-09，只读勘察）

> **本任务未删除任何内容。** 全程只读（docker 只读命令 + 宿主 `_data` 直读，`ionice -c 3 nice -n 19` 低速分批）；所有"裁定建议"仅是建议，执行前需用户确认。
> 与备料文档的偏离：**未用 alpine 挂卷方案勘察**（备料文档建议 `docker run --rm -v <vol>:/data alpine du`），因当日硬约束"不许新起容器"（防撞主会话 195→127 rsync 与其他并行任务），改直读宿主 `/var/lib/docker/volumes/<卷>/_data`——效果等价且零容器动作。

## 1. 磁盘总数对账

| 面 | 数值 | 依据 |
|---|---|---|
| 根分区 | 59G 总 / 42G 已用 / 16G 可用（74%） | `df -h /` |
| /var/lib/docker | 23.6G（overlay2 19G + volumes 4.5G + containers 99M + image 28M + buildkit 31M） | M8 du |
| /opt | ≈13.4G，其中 **/opt/backups 6.4G（禁区未碰）**、/opt/build 5.8G | M7 du 一层 |
| /root | ≈0.14G（干净，见 §3） | M5/M6 du |
| Docker 镜像 | 34 个 9.32GB，RECLAIMABLE 1.898GB（20%）——`local/holmesgpt:am1-http` 与 `:am0` 两镜像 830M+831M **0 容器引用** | `docker system df -v` |
| Docker 卷 | 44 个 4.802GB，现役 6，**dangling 38 个，RECLAIMABLE 3.42GB（71%）** | 同上 |
| dangling 卷 du 合计 | ≈3.26GB（与 docker 口径 3.42GB 吻合，差异=统计口径 apparent/block） | 逐卷 du 汇总 |

overlay2 19G 为最大头，其中可回收面=两个无引用 holmesgpt 镜像 ≈1.9GB（属镜像清理，不在本次卷裁定范围，仅记录）。

## 2. 逐卷裁定建议表

### 2.1 现役 6 卷（全部需留，勿动）

| 卷名 | 大小 | 内容 | 挂载容器 |
|---|---|---|---|
| deploy_pg-data | 987.8MB | 生产 PG 数据 | deploy-postgres-1 |
| 11851d57…baa104（匿名） | 307MB | Prometheus TSDB（现役） | prometheus-am0 |
| deploy_cas-data | 20.98MB | control-app CAS | deploy-control-app-1 |
| alert_litellm-logs | ~0 | litellm 日志 | litellm-am3 |
| 09ffdba5…8bf0d（匿名） | ~0 | alertmanager 数据 | alertmanager-am0 |
| 9e8630d0…13e34d（匿名） | 65.91MB | astronomy-db PG | astronomy-db |

### 2.2 dangling 38 卷裁定

**A. 可删——纯空卷 17 个（合计 ≈0，零风险）**

| 卷名 | 大小 | 内容族 | 关联面 | 裁定 |
|---|---|---|---|---|
| 0306f836…c31d | 4K | 空 PG 数据目录（uid 70） | 9-05 13:00 PG 容器残片 | 可删 |
| 347dfac0…2021 | 4K | 空 PG 数据目录 | 9-03 17:02 | 可删 |
| 41e51a6f…9319 | 4K | 空 PG 数据目录 | 9-05 13:02 | 可删 |
| 56dea56d…5747 | 4K | 空 PG 数据目录 | 9-05 13:01 | 可删 |
| 6698f8c0…61a5 | 4K | 空 PG 数据目录 | 9-05 13:01 | 可删 |
| a3b7aafa…eb0c | 4K | 空 PG 数据目录 | 9-05 13:01 | 可删 |
| b889f246…6368 | 4K | 空 PG 数据目录 | 9-05 02:00 | 可删 |
| c534159b…345b | 4K | 空 PG 数据目录 | 9-05 02:05 | 可删 |
| cd74811d…154dd | 4K | 空 PG 数据目录 | 9-05 01:57 | 可删 |
| f676ad4f…5247 | 4K | 空 PG 数据目录 | 9-05 12:56 | 可删 |
| 3194f5c6…50cf | 4K | 空 PG 数据目录 | 8-30 07:53 | 可删 |
| 9ef722ee…af09 | 4K | 空 PG 数据目录 | 9-03 16:57 | 可删 |
| 6e8dd9cf…60ea | 4K | 空目录（uid 70） | 9-05 22:07（litellm 栈拉起时刻产生） | 可删 |
| 998b0895…f08a | 4K | 空目录（uid 70） | 9-03 17:05 | 可删 |
| 23f3979d…9186 | 4K | 旧 alertmanager nflog/silences（全 0 字节） | 9-03 19:58 旧 alertmanager-am0 匿名卷（现役为 09ffdba5） | 可删 |
| adc5eb3b…54778 | 4K | 空目录（polkitd，8-13 时代最老残片） | hotel 时代 | 可删 |

**B. 可删（建议先抽查/打包备份后删）——旧栈数据残片 21 个（合计 ≈2.90GB）**

| 卷名 | 大小 | 内容族 | 关联面 | 裁定 |
|---|---|---|---|---|
| 0afcb36a…46d4 | 267M | MySQL 数据（`mall` 库，ibdata1 80M） | mall-app 栈（/opt/deploy/mall-app/docker-compose.yml），8-29 反复 up/down 留下 | 可删（mall-app 不再部署即删；删前可 tar 留档） |
| 1f017921…f927 | 260M | 同上 MySQL mall | 8-29 14:33 | 同上 |
| 5fce55f5…1937 | 267M | 同上 MySQL mall | 8-29 14:19 | 同上 |
| 8d68da94…358d | 260M | 同上 MySQL mall | 8-29 16:13 | 同上 |
| 251e0041…fc64 | 260M | 同上 MySQL mall | 8-29 16:14 | 同上 |
| 848f43e4…b371 | 237M | 同上 MySQL mall（数据最少） | 8-29 16:14 | 同上 |
| 2cdc4285…ba3c | 46M | PG 数据目录（uid 70） | 9-05 13:04 PG 容器残片（有数据：演练/IT 期产物） | 可删（先确认无回溯取证需求） |
| 4d54bb86…ea78 | 50M | PG 数据目录 | 9-05 13:08 | 同上 |
| 817e404d…6feaa | 50M | PG 数据目录 | 9-05 13:05 | 同上 |
| e92efa1c…1b9dc | 47M | PG 数据目录 | 8-30 16:33 | 同上 |
| 5d837ad7…535 | 39M | PG 数据目录 | 8-30 18:25 | 同上 |
| 5ff53569…d0c7 | 39M | PG 数据目录 | 8-30 18:10 | 同上 |
| 99dcd43b…ad2c | 39M | PG 数据目录 | 8-30 18:26 | 同上 |
| b5b53b12…370f | 40M | PG 数据目录 | 8-31 00:32 | 同上 |
| f118c8f5…91d8 | 39M | PG 数据目录 | 8-30 07:55 | 同上 |
| f6a93336…35253 | 124M | 旧 Prometheus TSDB（chunks_head/wal/queries.active，9-03~9-05） | 旧 prometheus-am0 匿名卷（现役为 11851d57） | 可删（历史指标不再回读即删） |
| hotel-platform_hotel-mysql | 474M | MySQL（hotel 库 + binlog 272M） | hotel-platform compose 项目（labels 实证），8-09~8-12 | 可删（hotel 栈弃用即删；删前可 tar） |
| hotel-platform_hotel-logs | 56M | hotel 应用日志+gz 归档（止于 8-12） | 同上 | 可删 |
| hotel-platform-obs_hotel-logs | 100M | hotel 应用日志（止于 8-13） | hotel-platform-obs compose 项目 | 可删 |
| hotel-platform-obs_hotel-otelcol-state | 212M | otelcol state（止于 8-13） | 同上 | 可删 |
| hotel-platform_hotel-media | 4K | 空媒体目录 | hotel-platform | 可删 |

**C. 待问 1 个（≈0.36GB）**

| 卷名 | 大小 | 内容族 | 关联面 | 裁定 |
|---|---|---|---|---|
| m2repo | 358M | Maven 本地仓库（最后写入 2026-09-05 20:29，较新） | 具名卷、labels=null；/opt maxdepth5 compose 无引用（应为 `docker run -v m2repo:…` 构建用法；宿主另有 /opt/m2repo 250M 独立副本） | **待问**：若容器内 maven 构建仍是常用工作流则留（删了会全量重拉依赖）；否则可删 |

### 2.3 汇总

| 分级 | 卷数 | 合计 |
|---|---|---|
| 可删（纯空） | 16 | ≈0 |
| 可删（数据残片，建议删前抽查/留档） | 21 | ≈2.90GB |
| 待问 | 1（m2repo） | 0.36GB |
| 需留（现役） | 6 | — |
| **dangling 合计** | **38** | **≈3.26GB du / 3.42GB docker 口径** |

> 全部 32 个容器均 Up（无 stopped/exited 残留容器面），dangling 卷全部来自**历史 compose 重建的匿名卷**（labels=`com.docker.volume.anonymous`）与**弃用项目**（hotel-platform/hotel-platform-obs、mall-app）。建议的执行面（用户确认后另行任务）：`docker volume rm` 逐个点名（不用 `prune`，避免误伤），MySQL/PG 族删前可 `tar` 留档到 /opt/backups 同级（禁区只指删除，写入需另行确认）。

## 3. /root 与 /opt 明细

### /root（干净）
- 目录合计 ≈0.14G：最大 hotel-src 47M、hotel 32M（hotel 栈源码残留，弃用可归档）、go 30M，其余均 <1.3M。
- **maxdepth 1 无任何 >20M 文件**（M6 find 空输出）。
- 无清理刚需。

### /opt 一层（仅一层，未做深层递归）
| 路径 | 大小 | 备注 |
|---|---|---|
| /opt/backups | 6.4G | **禁区，本任务未进入/未列出内部/未做任何变更** |
| /opt/build | 5.8G | 现役工作树（含 pr、pr.bak-20260907、pr-sync-stage、am5-stage、opentelemetry-demo） |
| /opt/jdk-21.0.12.1+1 | 346M | JDK |
| /opt/node16 / node22 / node18 | 399M / 192M / 161M | 三代 Node 并存 ≈752M，node16 疑似可退役（待问，不在本任务范围） |
| /opt/jdk21.tar.gz | 198M | JDK 解压后安装包（可删候选，待问） |
| /opt/m2repo + /opt/m2 | 250M + 134M | 宿主 maven 仓库两份 |
| /opt/apache-maven-3.9.9-bin.tar.gz | 8.7M | 安装包 |
| 其余（deploy/src/observability/projects/maven/jdk21 等） | <200M 合计 | — |

### 镜像面（顺带记录，不做动作）
- `local/holmesgpt:am1-http`（830M）与 `local/holmesgpt:am0`（831M）均 **0 容器引用**，RECLAIMABLE ≈1.9GB 的主体；清理属镜像管理任务，需用户单独裁定。

## 4. 声明
- 本任务**未删除任何文件/卷/镜像/容器**，未 restart/新建容器，未占用新端口；仅只读命令 + 两个新证据文件落本地仓库（未 git add）。
- 原始数据（未加工）：`195-disk-deep-dive-20260909-raw-df-v.txt`（docker system df -v 全量）、`-raw-volume-du.txt`（38 卷逐卷 du+ls）、`-raw-meta-crossref.txt`（卷 CreatedAt/labels、compose 发现、/root 与 /opt du）、`-raw-dangling-list.txt`（dangling 卷名清单）。
