# P0 批 + EN-08 195 真值补档（2026-09-12）

> 背景与更正：P0 批（c1128a1）与 EN-08（a76d70f/9ec227c）的 195 执行在实施会话中真实发生
> （ssh 远端 mvn，输出在会话流水），但**未按证据纪律落档**——提交消息中"195 全量 1636 绿+
> 部署验证闭环"当时在仓库内无原始日志可复核，2026-09-12 只读审计判定该声明"无证据"成立。
> 本包为**同代码、同环境**的补跑落档（195 /opt/build/pr 树 = 分支 feat/a-batch-readfaces
> @ 9ec227c 代码态；06390f4 仅改文档），自此声明有原始工件可查。

## 执行记录（195 真机，146.56.195.225，2026-09-12 18:35~18:45）

| 文件 | 内容 | 结果 |
|---|---|---|
| p0-en08-full-ut-20260912.log | control-app 全量 UT（mvn test，317KB 原始输出） | **1664/1664，0 失败 0 错误 1 跳过**（=CodeSearchExecutorTest.realTreeProbe 属性门控），BUILD SUCCESS |
| p0-en08-realpg-it-20260912.log | P0/EN-08 三个真 PG IT 定向批（Testcontainers 真 Docker PG） | **11/11 全绿**：PostgresDelegationReceiptIT **5/5**、PostgresOperatorMaterialIT **4/4**、PostgresSkillCandidateIT **2/2**，BUILD SUCCESS |
| p0-deploy-verify-20260912.log | 部署面六段核验（mc-p0-verify.sh）+ 资产内容级核验 + flyway 96/97 + 三表存在性 + V97 五 CHECK + 应用 ERROR 计数 | flyway **96\|true / 97\|true**；rca_delegation_receipt / incident_operator_material / rca_skill_candidate 三表在库；release_asset PROMPT 行 ff846b82…（variables_schema 四变量齐）；ck_mc21/mc31/mc32 与 ck_rca_skill_candidate_*（activation/asset/retire/status/verification）逐条在列；端点 GET 401/POST 403；授权面 control_app INSERT/SELECT、publisher 0；**应用 ERROR=0、登记 WARN=0** |

## 数字对账（审计问"1636 出处"）

1636 = P0 提交时（c1128a1，代码面=MC-P0 批）的 195 全量 UT 数；其后 X10 批 +11（1647）、
EN-08 一期 +13（1660）、EN-08 二期 +4（**1664=当前 HEAD 9ec227c 代码态**，本包日志即证）。
历次增量全部可由对应提交的测试文件数复算，非虚报；缺的只是本包这样的落档工件——已补。

## 范围声明（诚实边界）

- 本包覆盖：P0 批（MC21~23 回执/MC31~32 人工材料/MC24 复用/MC22 呈堂）与 EN-08 两期的
  **E/L 脸 + 真 PG IT + 部署面**。
- 本包不覆盖：R2/R10/R11/R6 等 IT 的真窗原始日志在审计已引用的 it-21batch-final-green.log
  与 full-verify-round3（2044 例六模块）；L/B/浏览器/完整 E2E 按用例文档 §七 三批次计划
  NOT_RUN，不在本包补跑范围。

## 追加（2026-09-12 晚，EN-08 装配缝 + V98）

| 文件 | 内容 | 结果 |
|---|---|---|
| skill-wiring-v98-full-ut-20260912.log | Skill→Run 装配接线（SkillSelectionService run 钉版+ContextAssembler skill 段+权限交集）+V98 全量 UT | **1669/1669**（0 失败 0 错误 1 门控跳过），BUILD SUCCESS |
| v98-deploy-verify-20260912.log | V98 迁移部署+CONTEXT_POLICY 资产核验 | flyway **98\|true**；release_asset CONTEXT_POLICY 行 **71054842c1d49f33**；启动 log 双 digest 锚（contextPolicyDigest=71054842…+compactionSchemaVersion=v1，compactionPromptDigest=ff846b82…同前）；**ERROR=0** |

EN-01/03"登记并固定 contextPolicyDigest、compactionPromptDigest"自此完整：确定性裁剪策略
独立资产（V98 CONTEXT_POLICY kind）+ 压缩指令资产（P0 批 PROMPT kind）构成重放解释锚对，
双 digest 启动 log 固定、版本中心可反查。
