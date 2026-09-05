# 告警 AM3 调查落档、通知与评测基线 —— 落码技术方案（执行者用）v1.1

> 定位：AM3 编码的**执行施工图**。任务编号严格对齐 `docs/告警Agent-增量实现任务拆解-v1.md` M3-01~30；设计依据 = `docs/告警AM3-技术方案.md` v3.0（+本轮修订）+ 架构 v1.2（FUT 系列）。
> 动工硬前提：**AM2 过 G2**（M2-28）。
> v1.1：采纳第三轮评审（8 P0 + P1 + 新增测试，全部经主会话核实属实）。关键变化：迁移拆分 V8/V9、generation 栅栏列、attempt 全程落档（STARTED 先行）、统一 ScenarioDriver、S2 换 flagd、GT 延迟授权、评分对象选择规则、对账降级链修正、通知三档、Holmes 服务端日志脱敏、可复现元数据扩展、评测时间参数。
> 执行纪律：按编号顺序；只执行与取证；设计问题回报主会话。

---

## 阶段一：契约与持久化（M3-01~09）

| 任务 | 内容（文件级） | 验收 |
|---|---|---|
| M3-01 | **EvidencePackage v2 Java 契约**：`alert/domain/model/` 新增 v2 类型（类型化 root_cause{component,fault_type,reason_code} + claims[]）；v1 类原样保留 | JSON 正反样本、长度/枚举/schema 单测 |
| M3-02 | **v1/v2 路由 Validator**：按 schema_version 显式路由，未知版本拒绝，禁止猜版本 | v1 回归、v2 全绿、未知版本拒绝 |
| M3-03 | **V8 迁移** `V8__am3_eval_notify.sql`：四表 `rca_investigation_result` / `rca_tool_call` / `report_publication` / `notify_outbox` + **`notify_app` 角色创建与授权**（评审纠正：不在 AM2 提前建）。**generation 栅栏列（FUT-50 直挂，禁止多层 JOIN 推导）**：investigation_result 增 `observed_generation`/`payload_digest`；tool_call 增 `run_id`/`observed_generation`/`schema_version`/`payload_digest`/`created_at`。**状态列分离**：`execution_status`（STARTED/SUCCEEDED/FAILED/TIMEOUT/UNKNOWN/CANCELLED）与 `validation_status`（NOT_VALIDATED/STRUCTURE_VALIDATED/REJECTED_*）两列独立——执行失败和结构失败不混装 | 迁移契约 IT：约束/索引/FK/授权/栅栏列逐项 |
| M3-04 | **InvestigationResult 仓储 + 全程落档**：**创建 Attempt 同事事务落 InvestigationResult(STARTED)** → 调 Holmes → 终态 CAS（SUCCEEDED/REJECTED_*/FAILED/TIMEOUT/UNKNOWN）——进程在外调后落库前被杀也有悬挂 STARTED 可查（回收标 UNKNOWN） | 重复写幂等（attempt_id 冲突重读）；REJECTED/UNRESOLVED 落档；外调前/外调后/Result 提交后/ToolCall 提交中四崩溃点恢复测试 |
| M3-05 | **Holmes 外层响应 Parser**：解析 analysis/usage/tool_calls/truncation；**不保存 Thought**（FUT-09）；保留 BA-12② 限读 | 固定 fixture、未知字段兼容、超限拒绝 |
| M3-06 | **ToolCall Adapter**：Holmes tool_calls → 内部契约 | 状态枚举映射测试（SUCCESS/ERROR/NO_DATA/APPROVAL_REQUIRED） |
| M3-07 | **ToolCall 仓储**：携带 run_id/observed_generation/schema_version/digest 直挂栅栏 | FK/去重 IT；**旧 observed_generation 的 ToolCall/Result 写入被拒** IT |
| M3-08 | **Attempt 落档编排**：一次调用在同一收尾事务关联 Result + ToolCall +（验证通过时）RcaReport；**报告+report_publication(READY)+notify_outbox 同事务**（或补漏 reconciler，二选一冻结：本期选同事务） | 四崩溃点恢复；验证失败有完整落档；报告+publication+outbox 原子性 IT |
| M3-09 | **报告验证/发布状态分离**：rca_report 不可变；`report_publication` 状态机完整冻结（PENDING/READY/SENT/RETRY_WAIT/DEAD/SUPPRESSED + lease/epoch/attempt/available_at）；publication 与 outbox 一对多；4xx 终态/429 退避/5xx 退避/UNKNOWN 不自动重发 | UPDATE report 被拒 IT；publication 状态机穷举；终态语义测试 |

## 阶段二：评分与 Dataset 最小闭环（M3-10~18）

| 任务 | 内容 | 验收 |
|---|---|---|
| M3-10 | **GoldenCase 适配器 + GT 延迟授权**（评审 P0-6）：eval_app **撤销 GT 基表直接 SELECT**；提供 security-barrier 视图/存储过程——**仅当绑定 Run 已终态、输出快照已冻结后才释放 GT**（冻结输出→获取 GT→评分 做成 CAS 状态迁移）；eval_app 不读 raw_artifact_ref/未脱敏工具结果，只读评分所需安全视图 | **输出冻结前读取 GT 必败 IT**；冻结后仅读绑定场景；权限正反 IT |
| M3-11 | coverage 纯函数 | 全矩阵穷举 |
| M3-12 | conditional_accuracy 纯函数（UNRESOLVED 不进分母单列） | 边界/零分母测试 |
| M3-13 | end_to_end_hit_rate 纯函数（独立计算） | 原始计数 + 公式快照 |
| M3-14 | **EvalRun 持久化（独立迁移 `V9__am3_eval_run.sql`）**：`eval_run` + `eval_case_result`（+可选 eval_run_event）——记录原始 TP/FP/FN 与三指标**分子分母**（不只存最终小数）；**可复现元数据全集**：dataset_version/config_digest/model/prompt_version+prompt_digest/tool_registry_digest/schema_version/temperature/top_p/max_tokens/requested_seed+effective_seed/provider_fingerprint/alert_rule_digest/scenario_driver_version | 同 EvalRun 不可覆盖 IT；同 case 两轮为两条独立记录；重跑 digest 一致 |
| M3-15 | eval-runner 独立身份（独立 profile/容器/DB role，非生产 Bean） | Spring context 隔离；DB 正反权限 |
| M3-16 | 单案例评分 CLI/API + **评分对象选择规则冻结**（评审 P0-7）：`selection_policy_version` + `scored_attempt_id` + `scored_report_id`——评分绑定该 Run 状态机**最终选定的唯一报告**，禁止 evaluator 从多 attempt 挑最优 | golden fixture 一致；选择规则测试（多 attempt 场景只取状态机选定者） |
| M3-17 | 5×2 串行批量评测 + **统一 ScenarioDriver**（评审 P0-4/5）：接口 `ScenarioDriver`（`FlagdScenarioDriver` / `ArenaChaosScenarioDriver` / `InfrastructureScenarioDriver`），统一返回 `ActivationReceipt/RecoveryReceipt{scenario_id, action_digest, generation, expected_alert_identity}`；**S2 由 `docker kill` 改为 flagd `paymentUnreachable`**（eval-runner 不持 docker socket；若未来必须真实 kill，走最小 allowlist Action Runner——AM5 域）；**评测时间参数显式化**：注入预热/for 持续/最大 firing 等待/最大 resolved 等待/场景专属恢复探针/超时清理/评测专用短窗口规则；S1/S2 恢复判定**不复用** AM2 的 `*_current==0`（各自定义恢复条件）。**E2E 按 E2E-M3-01~03 黄金链/批量链/结构失败链逐点断言（AM3 v3.0 §12 矩阵）** | 10 次记录齐全；失败不中断且落档；**上一轮 resolved 未归并禁止进入下一轮**；五 Driver 激活/恢复契约测试；任何失败都执行 finally 清理 |
| M3-18 | 基线报告生成器（原始 TP/FP/FN + 三指标 + UNRESOLVED 单列 + 失败样本原文 + 全配置版本） | 报告 schema + digest 测试 |

## 阶段三：通知、成本和最小可观测性（M3-19~30）

| 任务 | 内容 | 验收 |
|---|---|---|
| M3-19 | NotifyOutbox 生产者（同事务，见 M3-08） | 原子性 IT |
| M3-20 | Outbox Claimer（SKIP LOCKED + 租约 + 退避 + 有限批量，单 worker 串行） | 双 worker 互斥、崩溃回收、索引计划 IT |
| M3-21 | 白名单通知渲染器 | 注入/凭据/超长字段测试 |
| M3-22 | **通知渠道三档**（评审纠正"dry-run 真实到达"自相矛盾）：`VALIDATE_ONLY`（零触网）/ `TEST_CHANNEL`（真实发送测试机器人）/ `LIVE`（正式渠道）；首个渠道 = TEST_CHANNEL 测试接收器；文案带 operation_id；**DP-D01 改名 TEST_CHANNEL** | VALIDATE_ONLY 零网络调用断言；TEST_CHANNEL 真实发送；at-least-once 重复可检测 |
| M3-23 | 通知失败与 DEAD（429 持久化退避不占槽/5xx 退避/4xx 终态/UNKNOWN 不自动重发） | WireMock 时间控制测试 |
| M3-24 | LiteLLM 部署收口（crane 摆渡预案 BA-01；master key env；日志卷 600） | health 检查；直连百炼被网络策略阻止断言 |
| M3-25 | LiteLLM usage 适配器 + **对账降级链修正**（评审 P0-8）：① 优先 run/attempt metadata 关联；② 次选每 EvalRun 独立 virtual key；③ 都没有记 UNMATCHED/BEST_EFFORT；**禁止时间窗强行归因**（评测期正常 RCA 共用 proxy 会误归） | 1:1、1:N、缺 usage 三态测试；**缺 metadata 时不得用时间窗伪造 MATCHED** 测试 |
| M3-26 | Holmes 预算 feasibility spike（含 metadata 透传验证） | 可重复实验记录；不能硬拦标 BEST_EFFORT |
| M3-27 | **结构化 Logging 最小集（含 Holmes 服务端）**：control-app 侧 run/task/decision/latency 进 JSON 字段；**Holmes 服务侧（deploy/alert/holmesgpt/server.py）禁记完整 ask**——只记 request digest/大小/run·attempt 关联；tool args/result 脱敏后才落 CAS | 双侧日志 capture 测试；**断言日志中无 ask 正文/secret/原始工具参数** |
| M3-28 | Micrometer 最小指标（禁高基数 ID label） | actuator + label allowlist 测试 |
| M3-29 | OTel Context 贯穿最小链路 | 异步边界 span 关联测试 |
| M3-30 | AM3 G2（**最终部署门 = E2E-M3-01~08 全链覆盖**，AM3 v3.0 §12 矩阵：黄金链/批量链/结构失败链/崩溃恢复链/通知至少一次链/LiteLLM 故障链/权限泄漏链/正常业务共存链） | 5×2 全绿；失败落档齐；通知重发可审计；账本对账三态出账；**HOST1 全栈 RSS 合账**（不只测 LiteLLM）；E2E 八链逐点断言全过 |

---

## DoD（验收命令）

1. M3-01~30 单项验收全过，每任务验收项有测试映射
2. 快速单测：`mvn -q -pl control-app,notify-app -am test` 绿；完整门：`mvn -q clean verify` 绿且 Failsafe 实际执行数非零
3. 部署静态门：双 compose `config --quiet` + conftest 策略门（若 P3 备料已回流）
4. 真栈门：195 上 M3-17 + M3-30 全绿；证据包归档 `docs/测试证据/AM3/`（AA-26 契约）
5. INV-AM3-1~8 实证 + 本轮新增：GT 延迟授权（冻结前必败）/评分对象唯一/时间窗归因禁令
6. **准确率基线报告归档**（三指标 + 全配置版本 + 失败样本原文）；文档三件套同步；无新增设计文档

## 修订记录

| 版本 | 变更 |
|---|---|
| v1.0 | 初版（读任务拆分原文出具） |
| v1.1 | 第三轮评审 8 P0 + P1 全部采纳（主会话已核实关键引用属实）：迁移拆 V8/V9 + V9 增 eval_run/eval_case_result；generation 栅栏列直挂（FUT-50）；attempt 全程落档（STARTED 先行 + 执行/验证状态两列分离）；统一 ScenarioDriver 三实现 + S2 换 flagd paymentUnreachable（eval-runner 不持 docker socket）；GT 延迟授权（安全视图 + 冻结后释放 + CAS）；评分对象选择规则（selection_policy_version，禁挑最优）；对账降级链（metadata → virtual key → BEST_EFFORT，禁时间窗归因）；通知三档（VALIDATE_ONLY/TEST_CHANNEL/LIVE）；Holmes 服务端日志脱敏；可复现元数据扩展（prompt/tool_registry/seed/规则 digest 等十项）；评测时间参数显式化；评审新增测试全部并入对应任务验收 |
| v1.2 | E2E 矩阵增补（评审第四轮）：AM3 v3.0 §12 新增 E2E-M3-01~08 八条正式端到端链（黄金链/批量链/结构失败链/崩溃恢复四杀点/通知至少一次/LiteLLM 故障/权限泄漏/正常业务共存），逐点主键+digest 断言取代"时间接近"；M3-17 标注为批量功能 E2E、M3-30 升为覆盖八链的最终部署门；D01 改名 TEST_CHANNEL |
