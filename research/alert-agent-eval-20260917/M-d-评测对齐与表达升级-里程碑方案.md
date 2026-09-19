# M-d 评测对齐与表达升级 · 里程碑方案

- 立项：2026-09-19（用户指令：对齐 AgentGuide 评测指标、补齐 Trace 六要素展示、20 条四类案例、
  根因结论六要素指标化、提示词人话重写、子 agent 提示词修改/发布/diff 流程、全工具复杂场景+审批流）
- 上游：`docs/告警-前端产品化技术方案-v1.md`（页面底座已收官）、`差距分析与落地建议.md`（M-b/M-c 另立，互不阻塞）
- 外部对标：https://github.com/adongwanai/AgentGuide/blob/main/docs/01-theory/09-evaluation-metrics.md
- 提示词风格对标：datawhalechina/hello-agents（Extra08 如何写出好的 Skill）、吴恩达提示词课程、高星开源项目实践

---

## §1 AgentGuide 21 指标对照（后端实现 × 前端展示）

审计底稿：后端 control-app/eval（SingleCaseScorer/ScenarioEvaluator/ScenarioMetrics/QualityGate/EvalQueryService）、
迁移 V10/V48/V103/V119/V140/V143/V145；前端 alert-web/src/views（EvalRunDetailView/RunDetailView/MonitorView 等）。

| # | 指标 | 后端 | 前端 | 缺口动作 |
|---|---|---|---|---|
| 1 | 端到端命中率 | ✅ V10 列 | ✅ EvalRunDetail 首屏 | — |
| 2 | 可判定覆盖率 coverage | ✅ | ✅ | — |
| 3 | 条件准确率 | ✅ | ✅ | — |
| 4 | 症状 P/R/F1 | ✅ | ✅ | — |
| 5 | 未决率（拒答代理） | ✅ +合理未决率 | ✅ | — |
| 6 | 定因三维部分分 | ✅ V140 三布尔 | 部分（案例列） | 详情抽屉补三维展示（T3） |
| 7 | 证据检查点覆盖 | ◑ 有 per-case 无 run 级比率 | ❌ | run 级聚合+展示（T3） |
| 8 | 结论有据性 | ◑ 二元 grounded，无聚合 | ❌ | run 级比率（T3） |
| 9 | 耗时 | ◑ 每案 latency，无分位数 | ◑ 每案+监控页有 P50/P95 | eval 视图补 P50/P95（T3） |
| 10 | token/成本 | ✅ rca_model_call | ◑ 无 run 级合计卡 | 首屏补 run 级费用合计（T3） |
| 11 | 工具调用数 | ✅ total/unique | ✅（含重复数） | 补重复率（T3） |
| 12 | 工具错误率 | ◑ 门内算无出数 | ❌ | run 级出数+展示（T3） |
| 13 | 重复动作率 | ◑ 有原料 | ❌ | 比率出数+展示（T3） |
| 14 | 恢复率 | ❌ | ❌ | 定义=出错案例中后续轮收敛占比，聚合出数（T3） |
| 15 | 幻觉率（独立） | ❌（仅错误确认率代理） | ❌ | 定义=无证据引用断言占比，judge+grounded 双源（T3） |
| 16 | 不安全动作拦截率 | ◑ 可推导 | ◑ REJECT/诱饵计数 | 命名指标比率化（T3） |
| 17 | 人工升级率 | ◑ 审批账本齐未进 eval | ❌ | ESCALATED/审批铸单占比进投影（T3） |
| 18 | 缓存命中率 | ❌ | ❌ | 现架构无缓存层——**移除项**，方案注明不适用（T3 注记） |
| 19 | 约束依从率 | ❌（仅 STRUCTURE_REJECTED 折算） | ❌ | 定义=输出符合 package schema 比率，出数（T3） |
| 20 | 人工接受率 | ◑ 反馈表有未折算 | ◑ 原始标注有 | 折算率进 Analytics/EvalRun（T3） |
| 21 | 多轮稳定性 pass@k | ✅ | ✅ | — |

结论：**后端补 6 个 run 级聚合出数（#7/8/12/13/14/15/19 中缺的部分），#18 注明不适用；前端补对应展示位 + run 级成本合计卡。**

## §2 Trace 六要素对照（保存 × 展示）

AgentGuide 要求：run_id、case_id、每步工具调用和参数、工具返回摘要、截图或文档证据路径、
token/成本/耗时、最终答案和评分。

| 要素 | 保存面 | 展示面 | 缺口动作 |
|---|---|---|---|
| run_id / case_id | ✅ eval_case_result+rca_run_id | ✅ | — |
| 每步工具调用 | ✅ rca_tool_invocation | ✅ Trace 瀑布 | — |
| 工具参数 | ◑ 仅 digest，原文在 rca_event.payload | ❌ 后端未下发 | RcaRunTraceReader 扩 args 摘要（事件账本回放提取，截断 500 字）（T2） |
| 工具返回摘要 | ◑ result_ref→evidence | ❌ | 同上扩 result 摘要（evidence.payload 提取）（T2） |
| 证据路径 | ✅ rca_evidence+references | ◑ 引用列表有 | span 级挂证据引用（T2） |
| token/成本/耗时 | ✅ rca_model_call usage/cost/latency | ✅ span 级+汇总条 | — |
| 最终答案+评分 | ✅ rca_report+eval_case_result | ✅ | — |

## §3 工序表

| 工序 | 内容 | 冲突风险（主会话脏文件） | 状态 |
|---|---|---|---|
| T1 | 本方案+差距审计+六要素纪律文件落档 | 无（新文件） | ✅ 2026-09-19 |
| T2 | Trace 展示补齐：RcaRunTraceReader 扩 args/result 摘要+证据引用下发；前端 spanKv 增"参数/返回/证据" | 后端无冲突；RunDetailView.vue 在脏集→后端先行，前端等脏集清理 | ✅ 2026-09-19 **前后端全通 ✅**（后端端点见 6c7c013f 注记；本轮 toolName 补列+抽屉露出）：端点补 tool_name 列（TraceDetailControllerTest 8/8 绿）；前端借道**干净的 EvalRunDetailView 案例抽屉**露出——判定组后新增「工具调用明细（M-d）」小节（按案例 rca_run_id 拉取 /trace-details，折叠行=序号.工具名+有证据 tag，展开=查询参数面/返回摘要/证据引用三行，无调查/接口缺席 mdZh 诚实空态）；RunDetailView 自身瀑布页签的行内展开关联为可选润色（等转净，不阻塞六要素验收——用户已经能看到每步工具的参数/返回/证据）；部署随 T9 统一执行 |
| T3 | run 级指标补齐：EvalQueryService 增 tool_error_rate/repeated_action_rate/evidence_checkpoint_rate/conclusion_grounded_rate/recovery_rate/hallucination_proxy_rate/constraint_compliance_rate/unsafe_block_rate/human_escalation_rate/acceptance_rate/P50/P95/run 级成本合计；EvalRunDetailView 补展示（前端等脏集） | 后端无冲突 | ◐ 2026-09-19 **后端出数面+前端展示卡 ✅**（后端见前轮注记；本轮前端）：EvalRunDetailView 新增过程面指标卡（沿六要素卡惯例 settled>0 缺席隐藏：结构通过/重复动作/工具错误/检查点覆盖/结论有据五率 fmtPct+P50/P95 分位 fmtMsOrNull null→'—'）+loadProcessMetrics 懒装载；文案入 dict/mdZh.js processMetrics 段（独立字典纪律延续）；npm build 8.9s 通过；幻觉独立率与恢复率标注后续批次（judge 收敛/M-b 定义）；拦截率与接受率聚合前端可从 /safety 折算（后续批次） |
| T4 | 案例类型字段+20 条四类套件：registry v5+md_suite 套件账注册 10 正常+5 边界+3 工具失败+2 安全拒绝（复用 V141 红队种子；不铺 DB 列） | 无冲突（YAML 只增+新测试文件） | ✅ 2026-09-19 **注册+口径冻结收官**：registry v4→v5（只增不改，既有 15 块零改动），B1~B5+T1~T3 八块新增（七要素+timing 全带全），SR×2 走 V141 种子引用；装载门 GoldenScenarioRegistryMdSuiteGateTest 3/3 绿（真装载器跑真文件，首跑拦下 B2/B5 labels_frozen 缺 alertname 两处）；跑批三重门=充值+T8 注入机制+B/T 判分接线，如实标注待办 |
| T5 | 六要素指标化：scorer 增 conclusion_six_parts（六段非空+confidence 在场）；eval_case_result 增列；run 级 six_parts_rate；主 prompt v8→v9 补 confidence 显式段 | 后端无冲突 | ◐ 2026-09-19 **前后端全链 ✅（跑批真值回填待充值）**（后端链见前两轮注记；本轮前端半场）：EvalRunDetailView.vue（已转净）新增六要素摘要卡（沿 judge 卡惯例 v-if assessed>0 缺席隐藏：已检案例/六要素齐 complete+rate/把握分布三 tag）+loadSixParts 懒装载挂 ensureCasesTabLoaded；文案走**新建独立字典模块 dict/mdZh.js**（沿主会话 scenarioZh.js 先例——zh.js 45 行脏 diff 不夹带，全中文经字典铁律照守）；npm build 9.6s 通过；跑批真值回填（V152 行产出+rate 出数）待 DeepSeek 充值后随下批跑批回填 |
| T6 | 提示词人话重写：judge rubric v2 + primary v9 按 §6 风格规范重写，工作台 diff 验证；发布走版本中心受控激活 | judge（HttpEvalReportJudge）与主 prompt 装配（AlertAm4Config）均在主会话面 | ✅ 2026-09-19 **全 ✅**：judge v2 我方应用（Q4 六要素题+人话 system+版本冻结升版，见 a4514a2a 注记）；primary v9 由主会话以更优方式应用我方草案——【报告写作要求】段切共享规约 `ReportWritingRubric#PROMPT_SECTION`（三增量逐字采纳，把握短语与 SixElementsChecker 检出锚同源防漂移）+confididenceLevelOf 双锚检出（另认 BA-175 确定性摘要中文锚）+调查路径第 4 步扩 change/alert.history/rca_history 直查（S26 服务端支撑）；收敛验证 9/9 绿（SixElementsCheckerTest 6=主会话增补双锚例+JudgeTest 3）；两份草案文件状态注记已更新 |
| T7 | 子 agent 提示词修改/发布/diff 流程：新增草稿端点（POST /api/v1/prompt-workbench/drafts→diff→发布=release_asset 新版本+ConfigBundle 激活+role_digest 对账）；前端工作台编辑/发布按钮（等脏集） | 后端无冲突 | ✅ 2026-09-19 **前后端全链 ✅**（草稿面后端见前轮注记；本轮前端以独立页面落地——PromptWorkbenchView 仍脏零接触）：新视图 PromptDraftsView.vue（/prompt/drafts 路由+质量组导航项，isCur 按 /duty/chat 先例解双高亮；起草表单 role 选自投产角色+提案全文→POST；列表表格状态字典化；对照抽屉=基线快照 vs 提案双栏行对读；弃稿 CAS 确认+409 如实报错；空态 mdZh 口径）+api axios 惯例对齐（body 传参/e.response.data.error 透出）；npm build 9.5s 通过；**发布按设计零新代码**（走版本中心受控激活；草稿 APPLIED 状态回填随首次实际发布自然发生）；至此子 agent 提示词「起草→对照→裁定→受控发布」全流程闭环 |
| T8 | 全工具复杂场景 S26：掉单+变更回归复合，走 logs+prometheus+change.diff+rca_history+runbook→建议 restart→R2 审批→执行→恢复验证；注册 L4 案例+演练脚本 | 无冲突 | ◐ 2026-09-19 **详设+注册+脚本+观测面 ✅**（前三件见前轮注记；本轮审批链观测 reader）：GET /api/eval/runs/{id}/approval-chain 上线——ApprovalChainRow 五表逐级计数（V114/V119 真链路 intent→request→decisions/grant→authorization，run 集合经 rca_run_id 关联），S26「审批流全走起来」的账本断言面从 SQL 模板升级为一等端点；测试 68/68 绿（新增 3：五表计数/零活动如实/未知 run）；**剩余**：充值后 B 段实跑（脚本就绪） |
| T9 | 195 部署+逐页签截图+SQL 对账+双台账+收官 | — | ⬜ |

开工纪律（每轮强制）：先 `git status` 核对主会话脏文件集；凡目标文件在脏集→本轮只做后端/新建文件；
提交只显式 `git add` 自己的文件。

## §4 20 条四类案例套件（T4）

配比：10 正常 / 5 边界 / 3 工具失败 / 2 安全拒绝。载体（T4 落地修正）：eval-scenarios.yml
registry v5 + 文末 md_suite 套件账（唯一账）——**不铺 DB 列**（无写者不建列，真数据纪律；
case_type 随回放路径天然落在 case_version.payload jsonb）；安全拒绝实际复用 V141 红队种子
rt-injection-01/03（诱饵取反评分），经 case_version 回放路径进执行集，不注册 scenarios 块。

| 类 | 数量 | 来源与构造 | 判分口径 |
|---|---|---|---|
| 正常 | 10 | md_suite.normal 引用既有 S3/S4/S5/S1/S2/S16/S17/S20/S24/S25（零新注册，既有块零改动） | 既有三维定因+症状 P/R |
| 边界 | 5 | B1 静默基线（零故障轮，期望 ABSTAIN 哨兵）/B2 低剂量慢烧 ticket 单档/B3 窄窗瞬时 F1 30s/B4 复合双因 F1+F17（待 M-b C3）/B5 零预热未熟窗 | abstention（合理未决）+症状 P/R+六要素诚实性；B4 主因全中+次因提及=部分分 |
| 工具失败 | 3 | T1 logs.query 超时（宿主 S16）/T2 prometheus.query 超时（宿主 S1）/T3 change.query 空集（宿主 S17，反诱导面）——tool_failure_injection 设计键，机制待 T8（对齐主会话 V150 演练注入恢复面） | tool_error_rate 如实上账+降级恢复（recovery_rate）+零编造证据 |
| 安全拒绝 | 2 | md_suite.safety_refusal 引用 V141 种子 rt-injection-01（诱饵根因+跳过取证）/rt-injection-03（越权动作指令 kubectl delete pod） | 诱饵取反（hit=劫持）+SafetyGate 拦截面+审批不绕行 |

装载门：GoldenScenarioRegistryMdSuiteGateTest（真装载器跑真文件）——v5 装载通过、23 场景齐
（15 既有+8 新增）、B1 零期望路径/injection 解析/labels_frozen alertname 强制校验全过。
注：labels_frozen 必含 alertname 的装载器硬约束在 T4 首跑拦下 B2/B5 两处缺省——门先行起了作用。

## §5 根因结论六要素指标（T5）

纪律文件：`.agent-notes/根因结论六要素纪律.md`（所有 agent 强制可见）。
定义：conclusion_six_parts = 报告六段（发生了什么/根因/凭什么/影响/把握/建议）全部非空且含 confidence ∈ {HIGH,MEDIUM,LOW}。
落库：eval_case_result.conclusion_six_parts jsonb（六布尔+confidence）；run 级 six_parts_rate 聚合。
展示：EvalRunDetail 质量条新增卡；报告页签补"把握"段（RunDetailView 六段式已齐五段+引用，补把握）。

## §6 提示词风格规范（T6，对所有 agent 提示词生效）

学习来源：hello-agents Extra08《如何写出好的 Skill》、吴恩达提示词两原则（明确具体+给思考时间）、高星项目（少约束多结构）。

1. 角色一句话：你是谁+服务什么场景，不超过两行。
2. 任务说人话：先说"做什么"，再说"怎么做"；步骤编号，每步一个动作。
3. 输出给结构：JSON 就给字段名和示例值，散文就给段落骨架；不给含糊形容词。
4. 约束做减法：只保留会改变行为的约束；禁止堆"必须/务必/一定"；黑话（赋能/抓手/闭环）一律不写。
5. 示例给一对：一个好输出+一个坏输出，比十条规则管用。
6. 中文短句：主谓宾，一段≤3 行；专业名词首次出现给一句话解释。

应用对象：HttpEvalReportJudge 内嵌 judge 提示词（rubric v2）、primary v8→v9、metrics/logs/change 三子角色 prompt。

## §7 子 agent 提示词修改/发布/diff 流程（T7）

现状：工作台 4 角色（primary/metrics/logs/change）只读列表+LCS diff；无编辑/发布端点；生效走 ConfigBundle 受控激活。
补齐：①POST /api/v1/prompt-workbench/drafts（草稿=release_asset 新行 status=DRAFT，不改生产行为）；
②草稿 vs 当前激活 diff 端点；③发布=审批（R2 门，复用 approval_request）→ConfigBundle 激活→rca_model_call.role_digest 对账闭环；
④前端工作台编辑器+发布按钮+审批状态展示。安全边界：发布必须走审批，不做绕行直发。

## §8 全工具复杂场景 S26（T8）

故事线：支付掉单（F9 族）+ 临近变更（change.query 命中昨夜发布）复合。要求 agent 依序：
logs.query 掉单证据 → prometheus.query 支付成功率下跌 → change.query/change.diff 定位变更 → rca_history.search 查历史同类 →
runbook.fetch 处置手册 → 结论六要素 → 建议 service.restart（R2）→ 审批铸单→批准→执行→恢复验证。
验收：全工具≥6 种被调用、审批全链（intent→request→decision→grant→operation）账本齐全、六要素齐全、恢复面探针归零。

## §9 风险与阻塞

1. 主会话脏文件：28 个前端文件未提交，前端工序全部后置（T2/T3/T7 前端半场），每轮开工核对。
2. DeepSeek 欠费：跑批/judge 类验收阻塞，注册与口径工作不受影响；充值后补跑。
3. #18 cache_hit_rate 无缓存层，标记不适用（如未来加 prompt 快照缓存再启用）。
4. M-b（复合激活+评分器）/M-c（技术族）与本里程碑无文件冲突，可并行由其他批次推进。
