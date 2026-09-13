# A0 全绿姿态窗总结（2026-09-13，195 真机，deepseek-v4-flash-0731）

## 窗口目标与裁定

目标：A0 v2 断言包（e2e-r7-a0-provider-receipt-chain-v2）phase1~7 全绿。
**裁定：未达全绿——phase5 场景门禁（FAULT_POSITIVE 需 ACTIVE TRUE 且 ROOT_CAUSE+SUPPORTS）
六跑未过，模型行为面 blocker 维持。** 按窗序纪律，prompt 策略调优两次（v4 预算纪律、
v5 ISO+修正重发）+ token 预算校准一次（2500→5000）后停止，不无限重试、不改断言口径。

## 六跑矩阵

| # | run_id（前缀） | 姿态 | 结果 | 失败/终止形态 |
|---|---|---|---|---|
| 1 | —（未起跑） | v3@2500 | 脚本 infra | e2e-r7-common.sh CRLF 残留（同步回滚所致），已修 |
| 2 | ec15f8d5 | v3@2500 | 脚本 env 误报 | **操作员 env 残留早前窗口 15 工具覆写 R7_PRIMARY_ALLOWLIST**，劫持脚本缺省断言面（缺省即 prometheus.query,logs.query，与直查姿态自洽）→ 误报"allowlist 外工具"；已注释作废并备份 /tmp/r7-operator-env.bak-20260913。注：该 run 本身亦为 OUTPUT_BUDGET_EXHAUSTED 同签名连败诚实兜底 |
| 3 | ec0d708f | v3@2500 | phase5 FAIL | 8 步全用于 prometheus 查询（7 SUCCESS+1 INVALID_INPUT，3 证据行），**从未 final**，STEPS_EXHAUSTED 诚实兜底，零 claim |
| 4 | 544c8824 | v4@2500 | phase5 FAIL | v4 预算纪律生效：2 步取证后即尝试 final——但 **final 组合（推理+协议JSON）单次输出超 2500** → OUTPUT_BUDGET_EXHAUSTED 同签名×2 → MODEL_FAILURE_UNRESOLVED 诚实兜底 |
| 5 | fa979102 | v4@5000 | phase5 FAIL（**最接近绿**） | 5000 预算下 final 成功落：HYPOTHESIS c1 带 evidence_refs+evidence_roles(CONTEXT)+3 条诚实缺口；5 工具调用（4×prometheus SUCCESS，**logs.query INVALID_INPUT：模型传 epoch 秒，工具要 ISO-8601 Instant**——反馈保真修复实证：具体原因原样回喂进 last_error）；模型因日志证据缺失**诚实地**不定 ROOT_CAUSE |
| 6 | 7ca8928e | v5@5000 | phase5 FAIL | v5 更长 prompt 推高推理量（首步 2785 ctok、第三步 3392 ctok），final 组合再撞 5000 → 同签名连败诚实兜底，3 步终止 |

## 定责

- **环境面**：已排除（metrics 管道 service 标签修复后稳定流动；工具 9 成 SUCCESS）。
- **平台面**：全程按设计工作——同签名连败确定性终止、零断言即零发布性根因、
  UNRESOLVED 诚实报告 STRUCTURE_VALIDATED、首次调查发布 SENT（5/5）。
- **blocker 族（模型行为面，维持）**：deepseek-v4-flash 的 reasoning_tokens 计入输出预算，
  其"长篇推理后一次性输出协议 JSON"的风格与逐步输出预算存在结构性张力
  （2500 不够→5000 下 prompt 变长又不够）；叠加工具参数可靠性（epoch vs ISO-8601、
  偶发畸形 PromQL）。run5 证明：当工具调用全部成功且预算够时，模型能产出
  结构合法、引用真实、缺口诚实的 HYPOTHESIS——距离 ROOT_CAUSE 门禁只差
  "logs.query 参数正确"这一步。

## 窗口产物（保留）

- `BoundedLlmRoleRunner` step-max-tokens 旋钮（cd674c0，缺省 1000 不变）——本窗直接救场两次。
- `deploy/a0-green-override.yml` 终态：零委派 + 5000 + prompt v5（预算纪律+ISO+修正重发）。
- 协议硬规则与 INVALID_ARGS 反馈保真（早前落码）在本窗真机实证生效。
- 操作员 env 排毒：R7_PRIMARY_ALLOWLIST 残留覆写作废（脚本缺省恢复）。
- e2e 脚本 CRLF 二次修复（195 侧 sed；仓库侧 .gitattributes 修复仍待裁定）。

## 窗后姿态

control-app 已重建回 .env 原姿态（**注意：.env 自带早前调优的配方 prompt 与
MAX_DELEGATION_BATCHES=0、AGENT_MODEL=deepseek-v4-flash-0731**，本窗 override 未残留）；
STEP_MAX_TOKENS 回缺省 1000。health 200、0 ERROR。全部 6 个演示告警已 resolved，
incident 面零 ACTIVE 残留。

## 证据

- 链证据 SQL 转储：`a0-green-window-chains.sql.log`（rca_run/model_call/tool_invocation/
  claim/checkpoint/report/publication 五 run 全链）。
- 195 侧逐跑现场：`/opt/build/runs-demo/a0v2-20260913T*`（phase 分解工件）。

## 后续建议（供裁定，非本窗结论）

1. step-max-tokens 升档空间仍在（平台天花板 16000）——但每升一档 prompt 变长会
   同步推高推理量，单纯堆预算收敛性存疑；建议先试「final 步单独放大预算」的
   结构性方案（final 调用与工具步分开配 token 上限），而非继续全局升档。
2. 工具 schema 的 ISO-8601 约束可在 tool_schemas 描述里直接写示例值，
   比靠 prompt 叮嘱更稳（模型读 schema 的忠实度高于读系统提示）。
3. 若换非推理型模型（qwen3-max-preview 额度恢复后），本姿态窗值得原样复测一遍。
