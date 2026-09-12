# 路径一收官：A0 参数化直读工具面 15 跑实证（run12→run26）

> 用户四路径裁定（2026-09-12）路径一执行：A0 换 EN-05 参数化直读目录工具面再跑——
> "不是放水，评测『选对工具填对参』"。结论先行：**可行，已实证**。run26 phase5
> 历史死门首破（带引用 ACTIVE TRUE Claim），phase6 回执链 PASS；phase7 两处脚本雷
> 另案定责，系统链无缺陷。

## 一、里程碑：run26（2026-09-13 05:58 +08，fault=paymentFailure@100%）

- 机器：146.56.195.225（真机），模型 qwen3-max-preview，零委派（max-delegation-batches=0），
  prompt v13，7 工具直查面，tool-calls 预算 12 / 8 步主预算。
- phase1-6 全 PASS，phase5 断言原文：`phase5 PASS（直查全落 allowlist；证据 4 行；TRUE Claim 1 个引用全锚定）`
- 工具账本（rca_tool_invocation，4/4 SUCCESS 全带证据行）：

  | 序 | 工具 | 状态 | 说明 |
  |---|------|------|------|
  | 1 | prometheus.catalog | SUCCESS | service=checkout 指标清单 |
  | 2 | prometheus.metric_value | SUCCESS | rpc_client_call_duration_seconds_count（error_type 维） |
  | 3 | logs.aggregate | SUCCESS | count=10 |
  | 4 | logs.query | SUCCESS | PlaceOrder 原文行（流式截断修复后首战交付证据行） |

- Claim 实证（rca_claim 单行）：`kind=ROOT_CAUSE, status=TRUE, lifecycle=ACTIVE,
  evidence_basis=MULTI_SOURCE_CONSISTENT, refs=2`，
  reason="checkout 服务调用 PaymentService/Charge 时因下游服务不可用（error_type=UNAVAILABLE）
  导致失败，日志中存在相关活动佐证。"
- 故障注入（真实故障，非伪跑）：flagd 侧车（本项目 S1/S2 注入执行器）置
  paymentFailure=100% → payment 全量拒付 → checkout 指标域 rpc_client
  error_type=UNKNOWN 系列持续累积（OK 系列冻结）。模型经 v13 工具面提示选中该指标，
  双源（prometheus + loki）引用收敛 ROOT_CAUSE。跑后已回收（flag=off，payment warn 行清零）。
- phase7 定责（系统无缺陷）：
  1. 脚本探针旧列名 `package`（现行 schema 为 `rca_report.package_json`）——老雷家族第六处；
  2. `report_publication_loser`：同一 incident 的 generation 发布位已被更早报告占据，
     本 run 报告"落档不发布"是仲裁语义正确行为（rca_report 行在、STRUCTURE_VALIDATED、
     confirmed=1 speculative=0）；脚本假设"每 run 必有 publication 行"不成立于同场景连跑。

## 二、run12→run26 演化定责（18 份日志见 pr-logs/）

| run | 结果 | 死因/定责 |
|-----|------|-----------|
| 12 | phase4 FAIL | REMOTE 风暴（prometheus 300m 内存双峰时延）+ 缺省 2 批委派逃逸 |
| 13 | phase5 FAIL | phase1-4 首次全绿；零委派旋钮生效；差 TRUE |
| 14 | 零模型调用 | glm-5 上游配额尽（AUTH_DENIED）→ 切 qwen3-max-preview |
| 15 | phase5 FAIL | catalog 走 label-values 端点忽略 match → 全量 dump 截断误判 |
| 16/17 | DEAD | UNKNOWN_TOOL 终止族：(a) allowlist 收紧反噬；(b) directReadSpecs() 漏 metric_value 装配行 |
| 18 | phase1 FAIL | 新容器预热窗瞬态（重跑即绿） |
| 19 | phase5 FAIL | 反馈失真链定谳：NO_DATA→FAILED→REMOTE_UNAVAILABLE 三层折叠 + tool-calls=4 与 8 步错配 |
| 20/21 | phase5 FAIL | 配方 3/3 SUCCESS 双源双引用 MULTI_SOURCE；模型因"仅计数"保守收敛 HYPOTHESIS |
| 22 | phase5 FAIL | catalog 重复调用浪费 + 把"仅计数"误述为"调用失败" |
| 23 | phase5 FAIL | 五步全 SUCCESS；窗内健康 → 诚实 HYPOTHESIS（正确行为） |
| 24 | DEAD | logs.query RESULT_OVERSIZE：201 行×长行原文恒爆 64KB（readBounded 整包有界读） |
| 25 | phase5 FAIL | aggregate count=4（非零）但模型误读为"零"跳过 logs.query；健康窗诚实 HYPOTHESIS |
| 26 | **phase5 PASS** | 真故障+指标维度提示+流式读行三合流，ROOT_CAUSE/TRUE 双源收敛 |

## 三、四项代码修复（全部 195 真机部署+本地测试绿）

1. **PrometheusApiExecutor**：catalog 改 `/api/v1/series?match[]=` 流式抽名
   （label-values 端点忽略 match 的替代）；metricValue 参数化直查；exchange() 错误两族统一。
2. **AlertAm4Config**：directReadSpecs() 补 metric_value 装配行（UNKNOWN_TOOL 真根因）。
3. **SingleToolEvidenceAgent**：NO_DATA 诚实透传（账本 SUCCESS + NO_DATA 结局，
   不再折 FAILED→REMOTE_UNAVAILABLE 失真）。
4. **LogQueryExecutor**：render 全流式重写——原始体与结果预算解耦（run24 死因），
   行级到限即止 + truncated=true 有界诚实截断；单行保真不裁字；
   CountingInputStream 16×limit 防御上限；半包不合约仍 SOURCE_UNAVAILABLE。

配套测试：PrometheusApiExecutorTest（series stub/oversize 300 名/allowlist 拒绝）、
LogQueryExecutorTest（突发窗截断两用例）、En05DirectToolChainTest（catalog service 参）。

## 四、prompt 演进（v8→v13，.env 留痕 a0-prompt-v*.sh）

v12 定型四步配方+证据语义（SUCCESS 即证据/count=0 非失败/两源 ROOT_CAUSE 纪律）；
v13 增补指标选择面（rpc_client_call_duration_seconds_count 的 error_type/rpc_method 维
区分度，goroutine 等健康指标无区分度勿选）+ ④任何非零计数必读原文（run25 教训）。

## 五、新雷落档（BUGLOG 另案）

- **BA-121**：run 级 allowlist 拒绝走 UNKNOWN_TOOL 终止族，绕过 runner 计步软反馈环
  （应为 TOOL_NOT_ALLOWED 软拒绝）——run16/17 实证。
- **BA-122 候选**：logs.aggregate 映射自 EN-05 `log_error_aggregate` 但 Loki 查询无
  error 过滤——数的是全量行（历 run 的"72/100 计数"实为流量波动非错误突发）；
  且空 vector 抛 NO_DATA 而非 count=0。
- **A0 phase7 脚本两雷**：`package`→`package_json` 列名（老雷家族第六处）+
  generation loser 假设（同场景连跑第二次恒无 publication 行）。
- **运维教训**：locust /swarm 拉 20 用户拧死 UI（browser-traffic 用户 spawn 悬挂，
  流量归零）——重启容器回 3 用户基线恢复；拉流前先核 user_class 构成。

## 六、姿态回收记录（195）

- deploy/.env：prompt=v13、AGENT_MODEL=qwen3-max-preview、MAX_DELEGATION_BATCHES=0、
  PROMETHEUS_SERVICE_ALLOWLIST=control-app,checkout,frontend,recommendation、
  BUDGET_TOOL_CALLS=12（.env.model-backup / .env.step-backup 留旧姿态）。
- flagd：paymentFailure=off（已验证 payment warn 行清零）。
- locust：3 用户基线（重启恢复）。
- prometheus：mem_limit 768m（compose 已入库）。

## 七、归档物清单

- `pr-logs/a0-path1-run{12..26}.log`（18 份 e2e 全程日志）
- `runs-r7batch3/a0-*`（各 run 的 phase 工件与断言明细）
- DB 实证摘录见本文第一节（q26*.sql 查询原件在仓库根 q25*.sql/q26*.sql 同族）
