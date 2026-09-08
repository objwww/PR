# AM5 执行交接文档（评测发布门与长期运维）

- @date 2026-09-07
- @author 主会话（ wanghua ）
- 受众：用户指定的 AM5 执行者（工序 3 编码）
- 状态：**AM5 G1 已由用户签署（2026-09-07），正式开工**

---

## 一、门禁与授权状态（先读这个）

| 项 | 状态 |
|---|---|
| AM5 技术方案 | **v1.2，G1 已签署**（`docs/告警AM5-技术方案.md`）；v1.0→v1.1→v1.2 修订全部落稿 |
| 动工硬前提 | 原裁定"AM4 G2"已被用户**当场豁免为与 M4 E2E 并行** |
| AM4 状态 | 编码/IT 层全收口（634 UT + 94 IT + E2E-06 IT 真 PG 全绿）；**E2E 层（195 栈 runall 十二场景）由并行会话独占执行中**，AM4 G2 未签 |
| 195 共享栈 | **归并行会话独占。M5 执行者不得触碰 195**（不部署、不升级、不跑栈）；M5 一切编码与测试在本地完成，部署验证排在 M4 E2E 收口之后 |
| AM7 前端 | 编码已完成（alert-web/，M7-01~09），与 AM5 并行；其所需后端配套 API 与 M5-12/13/14 有交集，见 §五 |

## 二、权威文档（执行依据，按优先级）

1. `docs/告警AM5-技术方案.md` **v1.2**——方案权威（含 v1.1 数据集三层修订、v1.2 端到端/真实场景测试要求）
1a. `docs/告警AM5-落码技术方案.md` **v1.0**（2026-09-07 出具）——**执行施工图**：M5-01~22 逐任务落点清单/迁移 DDL 要点（V20~V29 分配表）/API 字段级契约/验收命令/并行批次；落码与方案冲突处以方案为准并登记差异
2. `docs/告警Agent-增量实现任务拆解-v1.md` §8——M5-01~22 任务编号/依赖/验收的唯一权威
3. `docs/架构设计-告警Agent-v1.2.md`——FUT 冻结项（尤其 FUT-42 数据集四分区纪律）
4. `docs/告警-调研-M5发布门与运维-v1.md`（E-17 v1.1）+ `docs/告警-OSS-证据清单.md`——设计依据与开源先例
5. `docs/告警-PROGRESS.md` / `docs/告警-BUGLOG.md`——账本，规则见 §六

## 三、仓库当前状态（2026-09-07 晚实况）

- **HEAD 在快速移动**：本文档落笔时 HEAD=`4e4941b`，并行会话正在持续提交 AM4 E2E 修复（fixture 契约、e2e 脚本 v2、quiesce 资产等）。**每个任务开工前先 `git pull --ff-only` 或核对 HEAD**，在最新代码上工作。
- **迁移编号边界已移动**：AM4 已占 **V12~V19**（V19=am4_tool_replay，并行会话后加的）。AM5 方案原文"自 V19 起"已过时——**AM5 新迁移从 V20 起**，开工时用 `find */src/main/resources/db/migration -name 'V*.sql'` 实测最大号再定。
- **未提交面（46 个文件）**：并行会话的 AM4 v1.4 附录/AM5 v1.1~v1.2 文档修订、主会话的 BUGLOG BA-27~34 补登记与 PROGRESS 时间线等。**不要提交别人的改动**；你自己任务的提交只 stage 自己动过的文件。
- **未 push**：本地有多笔提交未推送（AM4 收口×2、fixtures 修复×2、模型身份 `507c3f6`（撤回中间态 `2fca37d`）等）。push 需用户明确授权。
- **alert-web/ 未跟踪**：AM7 前端代码，勿动勿删。

## 四、模型身份最终裁定（影响 M5 评测/预算面）

- **全链模型最终身份 = glm-5**（用户 2026-09-08 裁定：以 195 服务器 .env 与 AM4 v6 封存证据的 `model_id=glm-5` 为准）。中间态 `qwen3.7-plus`（commit `2fca37d`）已被 **commit `507c3f6` 撤回**，repo 配置面五处 + `M3ModelGatewayConfig` 兜底默认值统一回写 glm-5；SamplingFingerprint 钉值 sha256 随 canonical 样例值重钉（`3bf888…`）；定向测试 8/8 绿。
- 195 探针实证：HTTP 200；`response_format=json_object` 下纯 JSON 合规（BA-14 之坑不复发）；**thinking 型模型**——`reasoning_content` 独立字段、reasoning_tokens 计入 completion_tokens（预算账本口径不变），**max_tokens 过小会被思考耗尽导致 content 为空**，调用侧必须留足额度。
- **费率字段是 glm-5 刊例价占位**（litellm config.yaml 标 TODO）：成本记账与 per-EvalRun max_budget 硬拦依赖它，M5-04/05 涉及对账时注意此口径，刊例价校准归 AM5 运维项。
- **eval 基线不可比**：AM3 的 47 案例命中率 0 是 deepseek-v3 产出；M5 的统计门禁基线须以 **glm-5** 重测重建。

## 五、任务清单与依赖（拆解 §8 唯一权威，此处为导读）

执行顺序按依赖拓扑，前五个是地基：

| 任务 | 内容 | 依赖 |
|---|---|---|
| M5-01 | DatasetVersion/CaseVersion 迁移（**v1.1 修订：统一 DatasetAdapter——OrderArena/Rca100/RcaEval 三适配器、EvalCaseV1、版本九字段、source_class=PUBLIC_BENCHMARK、scenario_family_id 整组分区**） | M4-38 |
| M5-02 | 数据集四分区权限（schema/RLS/角色物理隔离；Agent/RAG 对 HOLDOUT/GT 查询为 0） | M5-01 |
| M5-03 | Golden Candidate 工作流（双人复核、同人不能双签） | M5-01 |
| M5-04 | 模型采样指纹（temperature/top_p/seed/provider fingerprint；缺字段不可进正式门禁） | M4-33 |
| M5-05 | 配对重复试验与方差（cluster bootstrap 整组重采样、stats_seed、CI 方法版本化） | M5-04 |

其后：M5-06 六维 Evaluator → M5-07 硬安全门 → M5-08 质量门与运行门（**v1.1 冻结的五分支逻辑：安全违规>0→REJECT / cluster 不足→INCONCLUSIVE / CI 下界<-margin→REJECT / 运行门超→REJECT / 全过→ELIGIBLE_FOR_CANARY**）→ M5-09 ConfigBundle（**OPA/WAITING_APPROVAL 已延期，只留策略版本字段**）→ M5-10 Canary（无 GT，只判安全/运行/成本/disagreement）→ M5-11/12 OperatorCase+Operator API → M5-13 SSE（**注意：AM7 前端的事件流/队列/命令按钮都依赖 M5-12/13/14 的 API，契约形状见线框图 annot**）→ M5-14 命令 → M5-15~21 运维面 → M5-22 G2。

关键裁定（v1.1/v1.2 评审结论，必须遵守）：
- RCA-100=阿里云 STAROps RCA-Bench 真实存在（103 例，v1.1，answer key 受控须授权核查），与 RCAEval、OpenRCA 是三个独立项目——别再混；
- 公共 Benchmark 不得冒充私有 HOLDOUT；订单域私有集是唯一决定上线的主质量门；
- AC@1 限定（无 candidate_root_causes[] 契约时禁止 AC@3/5）；chance/lift 只校正随机基线；
- 质量门阈值版本化；小样本不自动放行。

## 六、执行纪律（里程碑工作流强制）

1. **工序 3 补充纪律**：每完成一个 M5-xx 任务——① `cat >>` 追加 `docs/告警-PROGRESS.md`（只增不删，含任务号+证据路径）；② 有 Bug 当场追加 `docs/告警-BUGLOG.md`（编号接 BA-34 之后，模板见文件头，根因挖到底，修复补回归测试并登记编号）；③ 再开始下一个任务。
2. 只实现当前任务范围；发现后续依赖记录在方案"问题与压力点"，不提前实现。
3. DDD 四层（interfaces/application/domain/infrastructure）；domain 零框架（ArchUnit 门，BA-22 仍在开放治理）；不引入方案外新依赖。
4. **本机无 Docker**：`*IT`（Testcontainers 真 PG）本机自动跳过——跳过的 IT **不计入任何完成证据**；本地只报 `mvn clean test` 的 UT 基线（当前 634 绿）。真 PG 证据待 195 归属释放后统一补（M5 测试验证阶段）。
5. git：commit 只 stage 自己任务的文件；push 需用户授权。
6. 密钥永不入文档/代码/日志；读 195 的 .env 类文件不属于本任务（195 不归你）。

## 七、环境锚点

- 本地构建/测试：`mvn -B -ntp clean test`（全 reactor，当前 634 UT 绿，2 skipped=无 Docker 的 testcontainers UT）；前端 `cd alert-web && npm run build`（与 M5 无关，勿动）。
- 模型端点：百炼 OpenAI 兼容端点（baseURL 见 `.env.example`；Spring AI 自动拼 /v1，配置不带 /v1 尾段）；密钥走 `AGENT_MODEL_API_KEY` 环境变量。
- 195 = `ssh -i ~/.ssh/id_ed25519 root@146.56.195.225`——**本会话周期内与你无关**（并行会话独占）。
