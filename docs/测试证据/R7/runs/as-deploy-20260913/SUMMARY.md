# AS 批 195 部署证据（A0 附件链修复批 + ①ruled_out 污染完整修复 + ②压缩消费面反证保留）

2026-09-13 15:0x~15:1x（195 现场时序 UTC+8）。部署脚本=`as-deploy.sh`（本目录语义：/tmp/as-deploy.sh），生效验证=`as-verify.sh`。

## 部署面七段（全绿）

1. **md5 对拍**：`5072acb290c2acdfc4af6e75fa03b813` 本地打包 ↔ 195 /tmp/as-batch.tar.gz，`md5sum -c` OK；打包 3800 文件，`.env` 红线扫描仅 `.env.example` 模板（零真实 env 进包）。
2. **deploy/.env 保险备份**：/tmp/env-backup-as-20260913T150907（内容零回显）；OVERLAY 解包后 `cmp` 逐字节一致、47 行——BA-137 纪律（tar 无 --delete、机器特定文件不触碰）执行无逾。
3. **mvn package**（195 宿主，JDK 21.0.12.1+1）：BUILD SUCCESS。
4. **compose build**：control-app + web 双镜像构建完成。
5. **migrate**：`Schema "public" is up to date. No migration necessary.`（本批零新迁移，符合预期）。
6. **up -d**：web + control-app Started；`Started ControlApplication in 13.417 seconds`。
7. **健康面**：health 首探 **200**、web 8090 **200**、`APPLICATION FAILED`=0、ERROR=0；flyway 顶端 `109|true`（不变）；容器态 control-app/web Up，postgres healthy。

## 启动校验在真姿态通过（AS-05 面实证）

195 姿态预检（as-precheck.sh）：`APP_ALERT_R7_PRIMARY_ENABLED=true`、**无 tool-allowlist 覆写**（.env/compose 均无）→ 缺省 8 工具全部有 delegate 对位。本轮新增的**启动期 delegates⊇allowlist 校验首闸**在 195 真姿态下启动通过（boot 成功即证），运行期兜底面（CONFIGURATION_ERROR/TOOL_NOT_ALLOWED）随端口生效。

## 部署生效验证（运行镜像类常量在位）

exec.jar 解包六新面 grep 全部命中：

| 类 | 印记 | 命中 |
|---|---|---|
| ContextAssembler | TRUSTED_EXCLUSION_MARK（①ruled_out v3 可信印记） | 1 |
| PrimaryGatewayToolPort | CONFIGURATION_ERROR（②错误分类/AS-05） | 2 |
| PrimaryClaimAdmission | ALL_COUNT_NOT_ERROR_EVIDENCE（AS-01 确定性检查） | 1 |
| LokiAggregateExecutor | detected_level（severity 口径面） | 2 |
| BoundedLlmRoleRunner | evidence_roles（协议 v2） | 3 |
| PrimaryFinalClaimProjector | r7-primary-v2（投影策略升版） | 1 |

## 伴随验证（部署前本地）

- 全量回归 **1934/1934（0 败 0 错 26 门控跳，BUILD SUCCESS）**——含 ①验收四测（ContextAssemblerTest 19/19）与 ②消费面反证保留新测（ContextCompactionServiceTest 21/21）。
- A0 批全量 1930/0/0/26 已于同窗先证。

## NOT_RUN 如实

- A0 v2 断言包真窗跑（需真模型窗+场景注入）；AS-09/10/12 留出集；MC34 三臂按当前版本复测——均归后续窗，见 docs/告警-工作记忆压缩Skill三项状态登记与拆解-v1.md。
- 本地工作树未提交（等用户裁定的合入窗；分支 feat/a-batch-readfaces）。
