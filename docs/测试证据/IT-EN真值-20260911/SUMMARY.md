# IT-EN 真值 — 2026-09-11

control-app EN 线五卡 Postgres 集成测试（§六②）在 195 真 PG（Testcontainers
postgres:16-alpine）上的实跑结果。**终态全绿：15/15，0 失败 0 错误 0 跳过。**

## 环境

- 执行机：195（root@146.56.195.225），工作副本 `/opt/it-work`（rsync 自
  `/opt/build/pr`，排除 target/.git），未动 deploy 栈容器
- JDK：`/opt/jdk-21.0.12.1+1`（OpenJDK 21.0.12.1）；Maven（/opt/maven）；Docker 可用
- PG 形态：Testcontainers 自起 `postgres:16-alpine` 临时容器（每次 mvn fork 一个），
  Flyway 全量迁移 V1~V89（含本轮新铸 V89），跑后 `docker ps -a` 实证无残留
- 跑法照 IT-EVUXDR真值-20260911 先例：

```
cd /opt/it-work && export JAVA_HOME=/opt/jdk-21.0.12.1+1 PATH=$JAVA_HOME/bin:$PATH
mvn -pl control-app verify -Dit.test=<ClassName> -Dtest=__none__ -DfailIfNoTests=false -Dsurefire.failIfNoSpecifiedTests=false
```

## 终态总账（第九轮，2026-09-11 16:17~16:18Z）

| 类 | 跑 | 失败 | 错误 | 跳过 | 结果 |
|---|---|---|---|---|---|
| En04ConfigEpochIT（V63+V48 栅栏） | 4 | 0 | 0 | 0 | PASS（27s） |
| En05ToolSourcesIT（工具事实源） | 4 | 0 | 0 | 0 | PASS（12s） |
| En06McpRegistryIT（MCP 注册表 V64） | 3 | 0 | 0 | 0 | PASS（11s） |
| En07RagSourcesIT（RAG 固定语料） | 2 | 0 | 0 | 0 | PASS（12s） |
| En10VersionCenterIT（版本中心） | 2 | 0 | 0 | 0 | PASS（11s） |

## 九轮到达史（红→绿全程如实）

1. **轮 1-4（编译期早退 exit 1）**：合并版 control-app/pom.xml 新增 MCP SDK 依赖，195 无此件。
   双因：①195 maven 仓缺 io.modelcontextprotocol.sdk 三件+tools.jackson 3.x+reactor-core
   3.7.5+jackson-annotations 2.20——从执行机 .m2 同株回填（含 pom/sha1 全链，双侧对拍）；
   ②**r7fix8.tar.gz 不含 control-app/pom.xml**（包内仅 control-app/src+target 共 1082 文件，
   零 pom 零 shared-kernel）——195 部署树 pom 停在 r7fix7 旧版（无 mcp 依赖声明，classpath
   148 条目零 mcp）。部署本体无恙（exec.jar 由主会话本地预打包随包携带，运行时依赖全在胖 jar），
   但 IT 构建面撞旧 pom——同步合并版 pom（md5 3b4b278c 双侧一致）后编译通过。
2. **轮 5-8（真跑红例 10→1→0）**：首度真跑暴露 10 红例，逐例对迁移源定责：
   - **BA-122（真迁移缺口，1 根因 3 红例）**：EN-07 产品域 ReleaseAsset 五 kind 词表
     （RUNBOOK_DOC/RUNBOOK_CATALOG）无 DB 侧对应——V60 check 只认 PROMPT/SKILL/TOOL_SCHEMA，
     En07 语料发布/En10 资产列表真 PG 即撞 release_asset_asset_kind_check（生产
     RunbookCorpusPublisher 同路径必然复发）。修复=**新迁移 V89 原位扩集**（V12 同律），
     195 生产库已应用（success=t，flyway 失败计数 0）。
   - 测试夹具/种子形态缺陷（7 红例，5 处修复）：En04 call() 夹具 physical_seq=0 违反
     V48 ck_rca_model_call_seq（≥1，产线真路径恒合规）；En04 伪造 task/attempt UUID 撞
     rca_model_call 双 FK（BA-53/60 幽灵引用同族的夹具版）→ 真种 rca_task+rca_attempt；
     En04 findWaitingOverdue 断言前未把 overdue 行推进到 WAITING_SAFE_POINT（巡回谓词只扫
     该态）；En04 containsExactly 过度指定插入序（台账序按随机 task_id 排）→
     containsExactlyInAnyOrder；En05 种子 incident status='OPEN' 不在 V7 词表（FIRING/RESOLVED）
     → 'FIRING'；En07 种子 rca_run state='SUCCEEDED' 无 finished_at 违反 ck_rca_run_finish
     → 补 finished_at；En10 activate() 助手 UPDATE config_bundle_active 写不存在的 revision 列
     （V24 表无此列）→ 摘除。
   - 契约钉：EnMigrationContractTest 增 v89ReleaseAssetKindCheckCoversRunbookCorpusKinds
     （V89 原位扩集+两新 kind 在集）。
3. **轮 9（终态）**：五类全绿（上表）。本地 mvn test-compile + EnMigrationContractTest 绿。

## 与任务书的偏差（如实）

1. EN-08 不在五卡清单（§六②同名五类；EN-08 维持阻塞=封存轨迹源头面未建，承前裁定）。
2. 修复批（V89+5 IT 文件+契约测试）由执行者在本窗完成——属 §六⑤「IT 断言形态修复批」
   同族捎带 + 真缺陷立案即修（窗规：新雷→立案→修→重跑，禁改口径放行）。

## 证据清单

- `En04ConfigEpochIT.log` … `En10VersionCenterIT.log`：第九轮（终绿）五条 mvn 全量输出
- `prev/`：轮 5-8 红例日志（red 证据）
- `failsafe-reports-final-green/`：终绿逐方法三态（.txt + TEST-*.xml）
- `it-en-evidence.tar.gz` / `it-en-logs.tar.gz`：195 侧原始打包（同上内容）
- 195：/opt/it-en-evidence.tar.gz、/opt/it-en-logs.tar.gz、/opt/r7-e2e/runs/it-en-20260911/

## 清理登记

- Testcontainers 容器 ryuk 自动回收，跑后 `docker ps -a` 零残留
- pr_agent 库仅 +V89 迁移（DDL 扩 check，零数据变更）；deploy 栈容器零触碰
- /opt/it-work 为构建副本（target 留 195 可整目录删除）；195 maven 仓新增件为本仓正常依赖
