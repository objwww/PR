# B 批技术债清账 195 真值（2026-09-12）

> 四卡（B1 去 Jackson / B2 BA-108 账本对齐 / B3 FUT-26 舱壁 / B4 晋升窗门禁），
> 分支 feat/a-batch-readfaces @ f7c5e54 代码态，195 /opt/build/pr 整树同步。

| 文件 | 内容 | 结果 |
|---|---|---|
| b-batch-full-ut-20260912.log | control-app 全量 UT（350KB 原始输出） | **1787/1787，0 失败 0 错误 1 门控跳过**，BUILD SUCCESS |
| b-batch-deploy-verify-20260912.log | B 批部署生效验证 | **Hikari 双池启动面**：alert-worker Start completed + alert-ingress Start completed（§13 ingress 4/worker 8 舱壁生效）；应用 Started；**health 200**；**ERROR=0** |

## 覆盖与边界

- 本包覆盖：B 批四卡的 E/L 脸 + 部署生效面（双池/会话级超时/Tomcat 16 配置进容器）。
- NOT_RUN 登记（195 窗后续）：B3 运行期耗尽验收（worker 占满时入口仍可用，需真 PG 连接
  饱和面=压测窗）；B3 余下入口链（inbox/intake+各 QueryService）跨面事务前置核实后逐链
  迁移；B4 scored_json 回填（需 verdict 表 update 授权，M6-03/V99 候选）；新 IT
  （PostgresCanaryEvidenceSample 授权面/幂等）随下窗 IT 批。
- BUGLOG：BA-22/BA-108 已关单（f7c5e54）。
