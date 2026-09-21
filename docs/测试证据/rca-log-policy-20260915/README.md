# 日志调查策略修复与单案回归（2026-09-15）

问题：run `5639b935` 的指标指向 payment，但日志调查停在 checkout 的级别聚合。
远端运行中提示词 SHA-256 为 `b260274817587949fe31a7088c8a7d3b1389376f4d1b563979e4634a3d1cab4a`。
其原文已要求查下游，但同时规定“都为 0 则跳过”日志正文、“第6步起必须收敛输出 FINAL，禁止继续取证”，并混用 epoch 秒与 ISO 时间指令。
因此本次替换冲突配方，而非继续追加指令。

## 变更

- 版本化完整提示词：[v1](../../../deploy/alert/prompts/primary-log-investigation-v1.txt)、[v2](../../../deploy/alert/prompts/primary-log-investigation-v2.txt)。指标指向下游时优先读取该服务正文；级别聚合零值不能跳过正文；统一使用冻结窗；移除第六步强制结束；预算不足明确报告调查缺口。v2 进一步区分证据支持的直接失败机制与未查明的深层起因，禁止把未经验证的深层起因写进确认结论。
- 未修改模型、工具权限、Claim 准入、双源确认标准或评分器。提示词内不提供本次故障日志答案或注入标签。
- 新增日志工具回归：INFO 级 payment 失败正文必须保留，发出的 Loki 查询不能附加级别过滤。
- 本地 `LogQueryExecutorTest`、`LokiAggregateExecutorTest`、`PrimaryClaimAdmissionTest` 共 **32 项通过，0 失败/错误/跳过**。这些测试证明工具与准入边界，不能替代模型行为验收。

## 部署

通过现有 `APP_ALERT_R7_PRIMARY_PROMPT` 配置替换，应用镜像不变。新提示词正文（去首尾空白）SHA-256：`01306516edf8a25d73d11654f4e4e150ab475a1c84384e9e66cc3c0ae4a31923`。
容器重建后健康检查通过，逐项验证其它运行环境变量与重建前一致。
旧配置备份保留在服务器 `/opt/build/pr/deploy/.env.before-log-policy-eb9ab5d1-8a83-4534-95b7-53365e9404e9`；含私密配置，不回传仓库。
预检曾将 Compose 的 `$$` 渲染转义误识为密码漂移，核验后修正比较；未更改登录配置。
v2 已部署且健康，正文 SHA-256：`7c278daa7ff13e1453e5f8f2147e2bb50114f7becb2a2e2d2a23537d62c3ac4f`；v1 备份路径为 `/opt/build/pr/deploy/.env.before-log-policy-e0d54c13-5b0f-4b8e-9162-08bc4022c71f`。

## 回归边界

复用 `CheckoutRpcClientErrorRateHigh|checkout`，只做本次策略单案验证，不计作完整 eval 批或北极星达标。
先确认无待执行/已领取 eval 命令、无活动 drill、无正在执行的 RCA，合成告警的前一 episode 已关闭。
`productCatalogFailure` 基线为 `on`，经用户明确同意在复测时暂关，结束恢复；`paymentFailure` 从 `off` 临时置 `100%`，结束恢复 `off`。
先验证实际 RPC 失败增量和 payment 正文，再投递不含根因答案的合成告警。
读取模型输入捕获、新日志证据和 Claim 的 `ROOT_CAUSE/TRUE/MULTI_SOURCE_CONSISTENT/ACTIVE`；执行终态 `SUCCEEDED` 本身不算通过。
无论结果如何均恢复本次旗标，并只 resolve 本次合成告警；不手工关闭 Sloth checkout episode。

执行脚本：[部署](../../review-tools/apply-rca-log-policy.py)、[单案回归](../../review-tools/verify-rca-log-policy.py)。
## 第一轮：日志调查已走通，确认未通过

远端 `/tmp/rca-log-policy-live-20260915-r1`，本地原始记录见 [r1/result.json](r1/result.json)。
run `559c1d59-260a-458d-bb4d-0d061ff47bb2`，qwen3-max 4 次调用全部 SUCCESS，共 10641 token。
预验 1 条非零 PaymentService 失败增量序列、4 条失败日志后才注入。
模型执行 catalog → metrics.instant → logs.query(payment) → FINAL，确实取得 INFO 级失败正文。
Claim 是 `HYPOTHESIS/UNKNOWN/MULTI_SOURCE_CONSISTENT`，两条证据均 SUPPORTS，`admission_note` 为空：不是准入门把 ROOT_CAUSE 降级，而是模型主动选择 HYPOTHESIS。
检查点的缺失信息是“未确认 checkout 与 payment 服务间调用链是否完整覆盖失败时段；未验证 token 无效是否由配置或凭据轮换引起”。

原始 `result.json` 中 `new_policy_visible=false` 是首版回归脚本把没有正文捕获误判为策略不可见。实际捕获级别为 `DIGEST_ONLY`，`policy-capture.json` 中对应值均为 null；无法据此声称新提示词未入模。保留原记录，不回写假绿。脚本已改为在无正文时输出 null，并记录 role_digest；容器环境验证与模型正文捕获是两种不同强度的证明。

[r1/cleanup.json](r1/cleanup.json) 无错误，paymentFailure=off、productCatalogFailure=on；合成告警 incident `5781021b-735a-44ca-8281-d2ccf3de6c0c` gen6 已 RESOLVED。

## 第二轮前置阻断

`r2` 在注入前退出，未改旗标。原因是 04:28 UTC 真实 Alertmanager 发来了同名 RPC 告警（标签含 `owner=am0`、`rpc_method=oteldemo.PaymentService/Charge`），形成 gen7；不是 r1 的合成告警未清理。
这说明短时故障关闭后，5 分钟指标窗口仍可触发后续告警。保留其自然恢复，不用手工 resolve 开门。
回归脚本补充有界自然恢复等待（300 秒，期间不注入，等待后重新核对旗标基线）；超过预算即退出。
第二轮实际重试目录为 `/tmp/rca-log-policy-live-20260915-r2b`，使用 v2 同款预验流程，结果待采集。
