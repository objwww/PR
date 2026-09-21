package com.objwww.pr.control.infrastructure.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.objwww.pr.control.alert.application.AlertClock;
import com.objwww.pr.control.alert.application.DagExecutionService;
import com.objwww.pr.control.alert.application.DeterministicSupervisor;
import com.objwww.pr.control.alert.application.PlanCompiler;
import com.objwww.pr.control.alert.application.ReportWritingRubric;
import com.objwww.pr.control.alert.application.agent.AgentRegistry;
import com.objwww.pr.control.alert.application.agent.ChangeAgent;
import com.objwww.pr.control.alert.application.agent.DirectReadToolAgent;
import com.objwww.pr.control.alert.application.agent.DirectReadToolCatalog;
import com.objwww.pr.control.alert.application.agent.LogsAgent;
import com.objwww.pr.control.alert.application.agent.SingleToolEvidenceAgent;
import com.objwww.pr.control.alert.application.agent.MetricsAgent;
import com.objwww.pr.control.alert.application.agent.NativeRcaAgent;
import com.objwww.pr.control.alert.application.rag.RunbookCorpusStore;
import com.objwww.pr.control.alert.application.replay.AgentReplayRunner;
import com.objwww.pr.control.alert.application.replay.ReadOnlyToolFace;
import com.objwww.pr.control.alert.application.replay.SnapshotShadowRouter;
import com.objwww.pr.control.alert.application.tool.ReplayToolGateway;
import com.objwww.pr.control.alert.application.tool.MutationToolCatalog;
import com.objwww.pr.control.alert.application.tool.ToolGateway;
import com.objwww.pr.control.alert.application.tool.ToolInvoker;
import com.objwww.pr.control.alert.application.tool.ToolRegistry;
import com.objwww.pr.control.alert.domain.agent.AgentProfile;
import com.objwww.pr.control.alert.domain.budget.BudgetKind;
import com.objwww.pr.control.alert.domain.claim.ClaimReducer;
import com.objwww.pr.control.alert.domain.claim.ClaimStore;
import com.objwww.pr.control.alert.domain.evidence.EvidenceRepository;
import com.objwww.pr.control.alert.domain.repository.RcaRunRepository;
import com.objwww.pr.control.alert.domain.repository.RcaTaskRepository;
import com.objwww.pr.control.alert.domain.repository.TaskEdgeRepository;
import com.objwww.pr.control.alert.domain.tool.RcaToolInvocationLedger;
import com.objwww.pr.control.alert.domain.tool.ToolPolicy;
import com.objwww.pr.control.infrastructure.persistence.PostgresToolReplayStore;
import com.objwww.pr.control.infrastructure.rag.FetchRunbookExecutor;
import com.objwww.pr.control.infrastructure.rag.HistoryRcaSearchExecutor;
import com.objwww.pr.control.infrastructure.rag.RunbookCatalogSearchExecutor;
import com.objwww.pr.control.infrastructure.tool.AlertHistoryExecutor;
import com.objwww.pr.control.infrastructure.tool.ChangeDiffExecutor;
import com.objwww.pr.control.infrastructure.tool.ChangeQueryExecutor;
import com.objwww.pr.control.infrastructure.tool.CodeReadExecutor;
import com.objwww.pr.control.infrastructure.tool.CodeSearchExecutor;
import com.objwww.pr.control.infrastructure.tool.CodeSourceBinding;
import com.objwww.pr.control.infrastructure.tool.DockerInspectExecutor;
import com.objwww.pr.control.infrastructure.tool.LogQueryExecutor;
import com.objwww.pr.control.infrastructure.tool.LokiAggregateExecutor;
import com.objwww.pr.control.infrastructure.tool.PrometheusApiExecutor;
import com.objwww.pr.control.infrastructure.tool.PrometheusQueryExecutor;
import com.objwww.pr.control.infrastructure.tool.TcpDockerEngineTransport;
import com.objwww.pr.shared.Digest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionOperations;

import java.nio.file.Path;
import java.time.Clock;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;

/**
 * AM4 Native 影子链装配（docker profile 手工装配，同 {@link AlertFlowConfig} 惯例）：
 * Supervisor/AgentRegistry/三 Agent/影子工具面/回放面/Claim 归并全部 bean 化，
 * E2E-M4-08（REPLAY_ONLY 回放对拍）与 E2E-M4-09（Online Read Shadow）由 195 部署
 * 配方按组件公开入口组装触发（触发器设计留白 = G2 终裁开放项，本装配不发明入口）。
 *
 * <p>装配纪律（AM4 技术方案 §2）：
 * <ul>
 *   <li>影子面与生产主链物理隔离：三 Agent 的工具出口只绑影子只读工具面
 *       （仅 R0/R1 + REDTEAM 物理禁入 + 独立 slot/限流），Holmes 主链零改动；</li>
 *   <li>回放面（{@link AgentReplayRunner}）为 E2E-M4-08 专用：账本落 V19
 *       rca_tool_replay，MISS 绝不降级活执行；</li>
 *   <li>Logs/Change 无冻结实时源（评审 P0-7）：fixture 即数据面（classpath 冻结
 *       样本，E2E 冻结时以带 content digest 的 manifest 替换）；</li>
 *   <li>不切主：本装配零报告零发布出口（Candidate 增量 0 由 E2E-M4-09 断言兜底）。</li>
 * </ul>
 *
 * @author wanghua
 * @date 2026-09-05
 */
@Configuration
@Profile("docker")
public class AlertAm4Config {

    private static final org.slf4j.Logger log =
            org.slf4j.LoggerFactory.getLogger(AlertAm4Config.class);

    private static final String PROMETHEUS_BASE_URL_KEY =
            "${app.alert.am4.prometheus.base-url:http://prometheus:9090}";
    /** EX-B2：logs 真实源（Loki 试验资源，契约 §3 准入条件）；fixture 退役
     * （Phase 3 门第 1 条：生产 profile 零 Replay 挂点——logs/change 全真源） */
    private static final String LOGS_LOKI_BASE_URL_KEY =
            "${app.alert.am4.logs.loki.base-url:http://loki:3100}";
    private static final String LOGS_SERVICE_ALLOWLIST_KEY =
            "${app.alert.am4.logs.service-allowlist:control-app,checkout,frontend,recommendation}";
    /** EX-B1：change fixture 已迁 test 资源（生产镜像零 change 假件，P1-03） */
    static final String CHANGE_FIXTURE_CLASSPATH = "am4/fixtures/change-query.json";
    /** EX-B1 change.query 服务白名单（越出 = INVALID_ARGS；缺省仅自身，fail-closed） */
    private static final String CHANGE_SERVICE_ALLOWLIST_KEY =
            "${app.alert.am4.change.service-allowlist:control-app}";
    /** EN-05 P0 四个 Prometheus 工具的服务范围面（selector 正向匹配器值必须落 allowlist） */
    private static final String PROM_SERVICE_ALLOWLIST_KEY =
            "${app.alert.am4.prometheus.service-allowlist:control-app}";
    /** EN-07 RAG 固定语料目录 digest（§三阶段 1）：空 = runbook 双工具不注册
     * （fail-closed：语料未固定即无 RAG 面，docker 同律）；语料更新走新快照（R07） */
    private static final String RAG_RUNBOOK_CATALOG_DIGEST_KEY =
            "${app.alert.rag.runbook-catalog-digest:}";
    /** EN-07 history_rca_search 服务范围（R10 先行过滤；独立键，缺省仅自身 fail-closed） */
    private static final String RAG_SERVICE_ALLOWLIST_KEY =
            "${app.alert.rag.service-allowlist:control-app}";
    /** R7-X10 代码取证：checkout 根 + service=repo@commit 绑定映射（两者均配置才注册
     * ——fail-closed，docker 同律；第一期宿主声明绑定，运行时 digest 核验属后续卡） */
    private static final String CODE_CHECKOUT_ROOT_KEY = "${app.alert.r7.code.checkout-root:}";
    private static final String CODE_MAPPING_KEY = "${app.alert.r7.code.mapping:}";
    /** EN-05 docker 双工具条件注册件：两项均非空才注册（未配置不注册，fail-closed） */
    private static final String DOCKER_BASE_URL_KEY = "${app.alert.am4.docker.base-url:}";
    private static final String DOCKER_CONTAINER_ALLOWLIST_KEY =
            "${app.alert.am4.docker.container-allowlist:}";
    private static final String ALLOWED_TOOLS_KEY =
            // BA-171：尾部两个 R3 写类审批工具（service.restart/service.rollback）——
            // 策略放行 ≠ 可执行，Gateway 对 R3 恒 VALIDATE_ONLY 铸意图进审批链
            "${app.alert.am4.allowed-tools:prometheus.query,logs.query,change.query,"
                    + "prometheus.instant,prometheus.metric_value,prometheus.catalog,prometheus.label_values,"
                    + "prometheus.rules,logs.aggregate,change.diff,alert.history,"
                    + "runbook.catalog,runbook.fetch,rca_history.search,"
                    + "code.search,code.read,service.restart,service.rollback}";
    private static final String SHADOW_MAX_CALLS_KEY =
            "${app.alert.am4.shadow.max-calls-per-window:60}";
    private static final String SHADOW_WINDOW_MILLIS_KEY =
            "${app.alert.am4.shadow.window-millis:60000}";
    private static final String SHADOW_POOL_SIZE_KEY =
            "${app.alert.am4.shadow.pool-size:2}";
    private static final String TOOL_TIMEOUT_KEY =
            "${app.alert.am4.tool.timeout-millis:4000}";
    private static final String TOOL_RESULT_LIMIT_KEY =
            "${app.alert.am4.tool.result-limit-bytes:65536}";
    private static final String PROMPT_VERSION_KEY =
            "${app.alert.am4.agent.prompt-version:am4-native-v11}";
    private static final String BUDGET_STEP_KEY = "${app.alert.am4.budget.step:8}";
    private static final String BUDGET_TOOL_CALLS_KEY =
            "${app.alert.am4.budget.tool-calls:4}";
    private static final String BUDGET_EVIDENCES_KEY =
            "${app.alert.am4.budget.evidences:8}";
    private static final String BUDGET_SUBTASKS_KEY =
            "${app.alert.am4.budget.subtasks:1}";
    /** EX-A1 熔断阈值（签名级连续无进展；≤0 回退 5） */
    private static final String DOOM_MAX_NO_PROGRESS_KEY =
            "${app.alert.am4.doom-loop.max-consecutive-no-progress:5}";
    /** PA-A4（B v2 L5-3 五模式表）：exact repeat 预警档（缺省 2） */
    private static final String DOOM_WARN_NO_PROGRESS_KEY =
            "${app.alert.am4.doom-loop.warn-consecutive-no-progress:2}";
    /** PA-A4 ping-pong（同任务两签名交替）：预警 4 / 硬停 6 */
    private static final String DOOM_PINGPONG_WARN_KEY =
            "${app.alert.am4.doom-loop.pingpong-warn:4}";
    private static final String DOOM_PINGPONG_STOP_KEY =
            "${app.alert.am4.doom-loop.pingpong-stop:6}";
    private static final String DOOM_POLICY_VERSION = "am4-doom-v2";
    /** PA-A4 monologue（连续独白轮数）：预警 2 / 硬停 3（B v2 表） */
    private static final String MONOLOGUE_WARN_KEY =
            "${app.alert.r7.primary.monologue-warn:2}";
    private static final String MONOLOGUE_STOP_KEY =
            "${app.alert.r7.primary.monologue-stop:3}";
    private static final String MONOLOGUE_POLICY_VERSION = "r7-monologue-v1";
    private static final String REDUCER_ALLOWLIST_KEY =
            "${app.alert.am4.reducer.readonly-allowlist:holmes,prometheus}";
    private static final String REDUCER_POLICY_VERSION_KEY =
            "${app.alert.am4.reducer.policy-version:am4-g2-policy}";
    /** 臂A 前置债清偿（R7）：委派批上限运行时旋钮，缺省 2=现行为不变；0=零委派臂A 姿态 */
    private static final String R7_PRIMARY_MAX_DELEGATION_BATCHES_KEY =
            "${app.alert.r7.primary.max-delegation-batches:2}";
    /** 主模式策略 prompt 配置键（缺省空串=回落 {@link #R7_PRIMARY_DEFAULT_PROMPT}；
     *  完整协议默认值含 JSON 花括号，不能放进占位符默认值——占位符首个 } 即截断） */
    private static final String R7_PRIMARY_PROMPT_KEY = "${app.alert.r7.primary.prompt:}";

    /**
     * 主 Agent 缺省 prompt（am4-native-v11，业界提示词规范重写：角色→目标→环境→
     * 调查路径→收敛标准→诚实纪律→写类工具→写作要求→输出协议→示例 十段分层，
     * 协议键与 {@code PrimaryDecision.parse} 逐字对齐不变）：
     * 决策协议明示（分支键 tool_call/delegate/final 恰选一、claim 行键
     * claim_key/kind/statement/evidence_refs/evidence_roles/root_cause/
     * symptom_codes、kind 枚举 ROOT_CAUSE/EXCLUSION/HYPOTHESIS/SYMPTOM、
     * evidence_roles.role 枚举 SUPPORTS/REFUTES/CONTEXT）。root_cause 三元组取值
     * 只认信封 root_cause_catalog 的 canonical 码（root_cause_hit 评分贯通面）。
     * v3 起硬要求：ROOT_CAUSE 的 SUPPORTS 引用必须锚到 ≥2 个不同 source 的证据行
     * （SINGLE_SOURCE 诚实门，单来源只落推测节）。v4 起【最小取证清单】：收敛前
     * 必须先 metrics.rules 找告警表达式再 query_range 拉窗内曲线 + logs.query 查
     * WARN/ERROR，两类齐全才许收敛（SMOKE 批 aa7f25b4 实证 miss 模式=只查日志）。
     * v5 起 symptom_codes 规格（BA-158 同族：SYMPTOM 类 claim 必须携带
     * symptom_codes=告警名，禁止填 logs/prometheus/loki 等来源标签——批 aa7f25b4
     * eval_case_result tp=0/fp=99/fn=60 实证）。v6 不改任何协议键，只把策略表达
     * 从密集单段改为分层结构 + 完整收敛示例（few-shot），提升可读性与依从率。
     * v7（BA-171）仍不改任何协议键：新增【写类工具（人工审批链）】段——告知模型
     * service.restart/service.rollback 调用即铸人工审批单、不直接执行、审批结果
     * 异步生效，取证结论不得依赖未执行的写操作。
     * v8 仍不改任何协议键：新增【报告写作要求（六要素）】段——final 的 statement
     * 合起来要让读者按"发生了什么→根因是什么→凭什么判断→影响多大→有多大把握→
     * 建议怎么办"一段话看懂；硬规则：证据 id/UUID 只进 evidence_refs 与
     * evidence_roles 字段，statement 正文写自然中文，禁止把证据 id、英文故障码
     * 写进 statement（正文里的机器码会原样透传到报告摘要）。
     * v9（BA-177）仍不改任何协议键：【报告写作要求】段切换为共享规约
     * {@link com.objwww.pr.control.alert.application.ReportWritingRubric#PROMPT_SECTION}
     * （落地 v9 草案三增量——把握短语硬措辞/不合格反例/禁黑话三条，把握短语即
     * SixElementsChecker 的检出锚，prompt 与评分同源防口径漂移）；【推荐调查路径】
     * 第 4 步扩为变更与历史工具直查（change.query/change.diff/alert.history/
     * rca_history.search 随本版 allowlist 放行）。
     * v10（BA-184）仍不改任何协议键：S26 静默故障五连跑实证（技术指标全绿、
     * 日志零 ERROR、change 无记录，模型 12 步烧光零 claim）——【收敛标准】补
     * 静默故障双源口径（prometheus.rules 告警表达式 + alert.history 本告警
     * firing/resolved 台账即两个独立来源，可闭环 ROOT_CAUSE）；【推荐调查路径】
     * 第 4 步补"日志零数据不要原地重试同参查询（撞熔断烧步），改查 alert.history
     * 与 rca_history.search，证据够就果断 final（HYPOTHESIS 也是合法收敛）"。
     * v11（BA-185）仍不改任何协议键：S27 变更回归场景要求审批链真实触发——
     * 【写类工具（人工审批链）】段补"处置落地形态"一条：确认根因后认为该重启/
     * 回滚时必须实际调用 service.restart/service.rollback 铸审批意图（调用即
     * 铸单、不直接执行），只在 statement 写"建议重启/建议回滚"不进审批链=
     * 没有处置；根因确认为变更回归（change.diff 证实窗内发布与故障机理因果
     * 对上）时正确处置=调用 service.rollback 回滚该发布。
     */
    private static final String R7_PRIMARY_DEFAULT_PROMPT = """
            # 角色
            你是一名资深 SRE 根因调查 Agent。告警触发后，你围绕告警与冻结时间窗，用只读工具逐步取证，定位并证实根因。

            # 工作目标
            给出可被证据支撑、可被复核的结论：要么确认根因，要么如实说明"目前只能推测/无法定论"。宁可诚实降级，绝不编造。

            # 工作环境（每步信封里有什么）
            alert（告警名/服务/级别）、冻结时间窗、tool_allowlist（可用工具清单）、tool_schemas（各工具参数形状）、valid_artifact_refs（已取到的证据 id 清单）、root_cause_catalog（根因 canonical 码表）、delegation_batches_remaining（剩余委派批数）。

            # 推荐调查路径
            1. 读告警：从 alert.alertname 与注解判断症状属于哪一层（流量/延迟/错误率/资源/业务）。
            2. 指标取证（必须先做）：先用 metrics.rules 找到本告警规则的表达式，再用 metrics.query_range 拉取冻结窗内的指标曲线——确认症状真实存在、幅度与起止点（得到 prometheus 来源证据）。
            3. 日志取证（必须先做）：用 logs.query 查告警服务在冻结窗内的 WARN/ERROR 日志——找第一条异常与错误模式（得到 logs 来源证据）。
            4. 变更与历史佐证（需要时）：用 change.query 查冻结窗内的发布/配置变更清单，用 change.diff 核对单次变更的具体内容——变更相关性≠因果性，变更内容必须与故障机理对得上才算因果；用 alert.history 查本告警的历史触发与处置，用 rca_history.search 找同类故障的历史结论作旁证（历史结论是参考不是证据，正文引用以本次取证为准）。需要深挖变更面时也可委派 change 角色，question 写清你要验证的假设。日志零数据时不要原地重试同参查询（会被熔断烧步）——改查 alert.history 取本告警台账（告警 firing 本身就是证据）、rca_history.search 找历史判例旁证；证据够就果断走 final（HYPOTHESIS 也是合法收敛），不要把步数烧光。
            5. 交叉印证：把指标曲线、日志模式、变更时间线对齐到同一时间轴，能互相解释的才下结论。

            # 收敛标准（全部满足才允许在 final 里标 ROOT_CAUSE）
            - 指标面与日志面两类取证都已完成，且指向同一结论——缺一类即视为证据不足；
            - 静默故障口径：日志面零数据不等于缺类——静默故障（技术指标全绿、日志无 ERROR）下，告警规则表达式（metrics.rules 证据，source=prometheus）+ 本告警 firing/resolved 台账（alert.history 证据，source=alert_event）即两个独立来源，可构成双源闭环；业务对账/业务症状类告警按此口径收敛 ROOT_CAUSE；
            - ROOT_CAUSE claim 的 SUPPORTS 引用覆盖至少两个不同来源的证据行（以证据行 source 标签为准）；
            - root_cause 的 component/fault_type/reason_code 三字段逐字取自信封 root_cause_catalog 的同一行——禁止跨行混搭、禁止自造词；信封无 root_cause_catalog 键或无法确定取值时省略 root_cause 键（如实降级，不拿服务名/claim_key 冒充）。

            # 诚实纪律（宁可降级，不可编造）
            - 只有单一来源支持 → kind=HYPOTHESIS，并在 missing_information 写明缺哪个来源的佐证；
            - 完全没有可支撑线索 → 输出 final 空 claims（"claims":[]）并在 missing_information 如实声明缺口；
            - symptom_codes 仅 SYMPTOM claim 必须携带：取值=告警名（信封 alert.alertname，或 metrics.rules 查到的 firing 规则 alertname）——禁止填 logs/prometheus/loki 等来源标签（来源标签不是症状码，评分按告警名等值比对，填来源标签=结构性恒 miss）；其他 kind 省略该键；
            - 禁止编造无证据的根因。

            # 写类工具（人工审批链）
            tool_allowlist 里的 service.restart / service.rollback 是写类高危工具：调用不会直接执行，而是创建一张人工审批单（工具结果会返回审批编号），审批通过后由系统异步执行。纪律：
            - 取证结论不得依赖尚未执行的写操作——root_cause 的证据链只能引用只读取证拿到的证据行；
            - 处置落地形态：确认根因后认为该重启/回滚时，必须实际调用对应工具铸审批意图（调用即铸单、不直接执行）——只在 statement 里写"建议重启/建议回滚"不会进入审批链，等于没有处置；根因确认为变更回归（change.diff 证实窗内发布与故障机理因果对上）时，正确处置=调用 service.rollback 回滚该发布；
            - 同一写操作提交一次即可，请勿重试同一写调用（重复提交不会加速审批）；
            - 是否建议写操作由你判断，是否执行永远由人类审批者决定；审批结果异步生效，不要原地等待或反复查询。

            """ + ReportWritingRubric.PROMPT_SECTION + """

            # 输出协议（宿主严格解析，逐字遵守）
            每步回复必须且只能是一个纯 JSON 对象——禁止 markdown 围栏、禁止解释文字、禁止思考过程；顶层恰含以下三个分支键之一：
            1. 取证：{"tool_call":{"tool_id":"<tool_allowlist 之一>","args":{...}}}——args 形状严格遵守 tool_schemas 的 properties/required。
            2. 委派（仅确需专业能力且 delegation_batches_remaining>0）：{"delegate":{"requests":[{"gap_id":"g1","role_id":"metrics|logs|change","question":"...","input_refs":[],"scope":{},"requested_budget":4}]}}。
            3. 收敛：{"final":{"claims":[<claim 行>...],"missing_information":["..."]}}——final 是唯一 Claim 提案出口。

            【claim 行形状】{"claim_key":"c1","kind":"ROOT_CAUSE|EXCLUSION|HYPOTHESIS|SYMPTOM","statement":"<一句话结论>","evidence_refs":["<valid_artifact_refs 之一>"],"evidence_roles":[{"ref":"<evidence_refs 之一>","role":"SUPPORTS|REFUTES|CONTEXT"}],"root_cause":{"component":"...","fault_type":"...","reason_code":"..."},"symptom_codes":["<告警名>"]}
            - claim_key/kind/statement 必填；evidence_refs 至少一条且只能引用 valid_artifact_refs 中的 id——无证据引用的断言会被整案拒绝，不算有效收敛；
            - evidence_roles 逐条声明引用对本断言的作用：SUPPORTS=支持、REFUTES=反驳、CONTEXT=仅背景（未声明按 CONTEXT 处理，不计入支持来源；ROOT_CAUSE 须至少一条 SUPPORTS 才被确认为根因；全量日志计数与累计计数器值不能作支持证据）；
            - root_cause 仅 ROOT_CAUSE claim 携带；symptom_codes 规则见诚实纪律。

            # 示例（一个合格的收敛；e1/e2 只是占位，实际必须引用你信封 valid_artifact_refs 里的真实证据 id）
            告警 ArenaDuplicateOrders 触发后，你已取证：metrics.query_range 显示冻结窗内订单创建速率翻倍（证据 e1），logs.query 显示同一订单号出现两次 insert（证据 e2），两条证据指向同一结论"重复下单"。则收敛：
            {"final":{"claims":[{"claim_key":"c1","kind":"SYMPTOM","statement":"冻结窗内订单重复创建，速率翻倍","evidence_refs":["e1","e2"],"evidence_roles":[{"ref":"e1","role":"SUPPORTS"},{"ref":"e2","role":"SUPPORTS"}],"symptom_codes":["ArenaDuplicateOrders"]},{"claim_key":"c2","kind":"ROOT_CAUSE","statement":"下单接口缺少幂等键，重试产生重复订单","evidence_refs":["e1","e2"],"evidence_roles":[{"ref":"e1","role":"SUPPORTS"},{"ref":"e2","role":"SUPPORTS"}],"root_cause":{"component":"order","fault_type":"<catalog 同行的 fault_type>","reason_code":"<catalog 同行的 reason_code>"}}],"missing_information":[]}}
            （root_cause 三字段只示意形状；实际取值必须逐字取自信封 root_cause_catalog 的同一行。）""";

    private static final String OUTPUT_SCHEMA_TYPE = "type";
    private static final String OUTPUT_SCHEMA_OBJECT = "object";
    private static final String AGENT_VERSION = "1";
    private static final long SHADOW_WINDOW_MILLIS_DEFAULT = 60_000L;
    private static final long SHADOW_MAX_CALLS_DEFAULT = 60L;
    private static final int SHADOW_POOL_SIZE_DEFAULT = 2;
    /** EX-A4a（F17）bulkhead 队列容量（满即 Abort 拒绝，不静默排队） */
    private static final int SHADOW_QUEUE_CAPACITY = 16;

    // ------------------------------------------------------------------ 工具面

    /**
     * EX-A1 预算门（F15）：全系统唯一预算所有者的 bean 装配——消费点为三 Agent 的
     * TOOL_CALL 硬闸与 executor 的 run 开局限额（openRun）。账本本体在
     * PersistenceConfig（V13）。
     */
    @Bean
    public com.objwww.pr.control.alert.application.RunBudgetGate runBudgetGate(
            com.objwww.pr.control.alert.domain.budget.RunBudgetLedger runBudgetLedger) {
        return new com.objwww.pr.control.alert.application.RunBudgetGate(runBudgetLedger);
    }

    /** EX-A1 熔断门：签名级连续无进展熔断（粘滞，人工/新代际解除；轮询豁免集空）。
     *  PA-A4：双阈值（exact repeat warn/stop）+ ping-pong 交替（warn/stop），v2 策略 */
    @Bean
    public com.objwww.pr.control.alert.domain.budget.DoomLoopGuard am4DoomLoopGuard(
            @Value(DOOM_MAX_NO_PROGRESS_KEY) long maxConsecutiveNoProgress,
            @Value(DOOM_WARN_NO_PROGRESS_KEY) long warnConsecutiveNoProgress,
            @Value(DOOM_PINGPONG_WARN_KEY) long pingPongWarn,
            @Value(DOOM_PINGPONG_STOP_KEY) long pingPongStop) {
        long threshold = maxConsecutiveNoProgress > 0 ? maxConsecutiveNoProgress : 5L;
        long warn = warnConsecutiveNoProgress > 0 ? warnConsecutiveNoProgress : 2L;
        return new com.objwww.pr.control.alert.domain.budget.DoomLoopGuard(
                new com.objwww.pr.control.alert.domain.budget.DoomLoopGuard.Policy(
                        warn, threshold, pingPongWarn, pingPongStop,
                        DOOM_POLICY_VERSION, java.util.Set.of()));
    }

    /** PA-A4 回环守卫（monologue 维）：连续无工具调用轮数 warn/stop，随 runner 接线 */
    @Bean
    public com.objwww.pr.control.alert.application.agent.RoleLoopGuard am4RoleLoopGuard(
            @Value(MONOLOGUE_WARN_KEY) int monologueWarn,
            @Value(MONOLOGUE_STOP_KEY) int monologueStop) {
        return new com.objwww.pr.control.alert.application.agent.RoleLoopGuard(
                new com.objwww.pr.control.alert.application.agent.RoleLoopGuard.Policy(
                        monologueWarn, monologueStop, MONOLOGUE_POLICY_VERSION));
    }

    /** EX-A1 run 开局限额四维（既有 budget.* 键；openRun 逐维幂等 upsert） */
    @Bean
    public java.util.Map<com.objwww.pr.control.alert.domain.budget.BudgetKind, Long>
            am4BudgetLimits(@Value(BUDGET_STEP_KEY) long budgetStep,
                    @Value(BUDGET_TOOL_CALLS_KEY) long budgetToolCalls,
                    @Value(BUDGET_EVIDENCES_KEY) long budgetEvidences,
                    @Value(BUDGET_SUBTASKS_KEY) long budgetSubtasks) {
        java.util.Map<com.objwww.pr.control.alert.domain.budget.BudgetKind, Long> limits =
                new java.util.LinkedHashMap<>();
        limits.put(com.objwww.pr.control.alert.domain.budget.BudgetKind.STEP, budgetStep);
        limits.put(com.objwww.pr.control.alert.domain.budget.BudgetKind.TOOL_CALL, budgetToolCalls);
        limits.put(com.objwww.pr.control.alert.domain.budget.BudgetKind.EVIDENCE, budgetEvidences);
        limits.put(com.objwww.pr.control.alert.domain.budget.BudgetKind.SUBTASK, budgetSubtasks);
        return limits;
    }

    /**
     * 生产工具注册面（EX-B2 后全真源，Phase 3 门第 1 条达成）：三兼容工具真源 +
     * EN-05 §一 P0 九工具族（prom×4 单执行器实例方法引用复用 / logs.aggregate 真 Loki
     * 聚合 / change.diff 真变更窗 / docker 双工具条件注册 / alert.history 真时间线）
     * ——零 ReplayToolExecutor 挂点，全部经同一 Gateway 咽喉（策略/预算/限长不旁路）。
     */
    @Bean
    public ToolRegistry am4ToolRegistry(
            @Value(PROMETHEUS_BASE_URL_KEY) String prometheusBaseUrl,
            @Value(TOOL_TIMEOUT_KEY) long timeoutMillis,
            @Value(TOOL_RESULT_LIMIT_KEY) long resultLimitBytes,
            JdbcClient jdbc,
            @Value(LOGS_LOKI_BASE_URL_KEY) String lokiBaseUrl,
            @Value(LOGS_SERVICE_ALLOWLIST_KEY) String logsServiceAllowlist,
            @Value(CHANGE_SERVICE_ALLOWLIST_KEY) String changeServiceAllowlist,
            @Value(PROM_SERVICE_ALLOWLIST_KEY) String prometheusServiceAllowlist,
            @Value(DOCKER_BASE_URL_KEY) String dockerBaseUrl,
            @Value(DOCKER_CONTAINER_ALLOWLIST_KEY) String dockerContainerAllowlist,
            RunbookCorpusStore runbookCorpusStore,
            @Value(RAG_RUNBOOK_CATALOG_DIGEST_KEY) String runbookCatalogDigest,
            @Value(RAG_SERVICE_ALLOWLIST_KEY) String ragServiceAllowlist,
            @Value(CODE_CHECKOUT_ROOT_KEY) String codeCheckoutRoot,
            @Value(CODE_MAPPING_KEY) String codeMapping) {
        PrometheusApiExecutor prometheusApi = new PrometheusApiExecutor(prometheusBaseUrl,
                Set.of(prometheusServiceAllowlist.split(",")));
        List<ToolRegistry.Registration> registrations = new ArrayList<>(List.of(
                new ToolRegistry.Registration(
                        MetricsAgent.toolDefinition(timeoutMillis, resultLimitBytes),
                        new PrometheusQueryExecutor(prometheusBaseUrl)),
                new ToolRegistry.Registration(
                        LogsAgent.toolDefinition(timeoutMillis, resultLimitBytes),
                        new LogQueryExecutor(lokiBaseUrl,
                                Set.of(logsServiceAllowlist.split(",")))),
                new ToolRegistry.Registration(
                        ChangeAgent.toolDefinition(timeoutMillis, resultLimitBytes),
                        new ChangeQueryExecutor(jdbc,
                                Set.of(changeServiceAllowlist.split(",")))),
                new ToolRegistry.Registration(
                        DirectReadToolCatalog.prometheusInstant(timeoutMillis, resultLimitBytes),
                        prometheusApi::instantQuery),
                new ToolRegistry.Registration(
                        DirectReadToolCatalog.metricValue(timeoutMillis, resultLimitBytes),
                        prometheusApi::metricValue),
                new ToolRegistry.Registration(
                        DirectReadToolCatalog.prometheusCatalog(timeoutMillis, resultLimitBytes),
                        prometheusApi::catalogSearch),
                new ToolRegistry.Registration(
                        DirectReadToolCatalog.prometheusLabelValues(timeoutMillis,
                                resultLimitBytes),
                        prometheusApi::labelValues),
                new ToolRegistry.Registration(
                        DirectReadToolCatalog.prometheusRules(timeoutMillis, resultLimitBytes),
                        prometheusApi::ruleLookup),
                new ToolRegistry.Registration(
                        DirectReadToolCatalog.logsAggregate(timeoutMillis, resultLimitBytes),
                        new LokiAggregateExecutor(lokiBaseUrl,
                                Set.of(logsServiceAllowlist.split(",")))),
                new ToolRegistry.Registration(
                        DirectReadToolCatalog.changeDiff(timeoutMillis, resultLimitBytes),
                        new ChangeDiffExecutor(jdbc,
                                Set.of(changeServiceAllowlist.split(",")))),
                new ToolRegistry.Registration(
                        DirectReadToolCatalog.alertHistory(timeoutMillis, resultLimitBytes),
                        new AlertHistoryExecutor(jdbc))));
        // docker 双工具：base-url 与容器 allowlist 均配置才注册（未配置不注册，fail-closed）
        if (!dockerBaseUrl.isBlank() && !dockerContainerAllowlist.isBlank()) {
            DockerInspectExecutor docker = new DockerInspectExecutor(
                    new TcpDockerEngineTransport(dockerBaseUrl),
                    Set.of(dockerContainerAllowlist.split(",")));
            registrations.add(new ToolRegistry.Registration(
                    DirectReadToolCatalog.dockerPs(timeoutMillis, resultLimitBytes),
                    docker::listContainers));
            registrations.add(new ToolRegistry.Registration(
                    DirectReadToolCatalog.dockerInspect(timeoutMillis, resultLimitBytes),
                    docker::inspectContainer));
        }
        // EN-07 RAG（§三阶段 1）：runbook 双工具——语料目录 digest 配置才注册
        // （digest 非法在构造期 Digest 校验即 startup fail-fast）；history_rca_search
        // 走真 V7 表无语料依赖，allowlist 缺省仅自身（fail-closed）
        if (!runbookCatalogDigest.isBlank()) {
            Digest corpusDigest = new Digest(runbookCatalogDigest.trim());
            registrations.add(new ToolRegistry.Registration(
                    DirectReadToolCatalog.runbookCatalogSearch(timeoutMillis, resultLimitBytes),
                    new RunbookCatalogSearchExecutor(runbookCorpusStore, corpusDigest,
                            Clock.systemUTC())));
            registrations.add(new ToolRegistry.Registration(
                    DirectReadToolCatalog.runbookFetch(timeoutMillis, resultLimitBytes),
                    new FetchRunbookExecutor(runbookCorpusStore, corpusDigest,
                            Clock.systemUTC())));
        }
        registrations.add(new ToolRegistry.Registration(
                DirectReadToolCatalog.rcaHistorySearch(timeoutMillis, resultLimitBytes),
                new HistoryRcaSearchExecutor(jdbc,
                        Set.of(ragServiceAllowlist.split(",")))));
        // BA-171：写类审批双工具（R3，无条件注册——读面透出 /tools 与 LLM 清单
        // 都需要它们可见）。调用链：Gateway VALIDATE_ONLY 短路铸意图 → 自动进审批
        // 队列，占位执行器永不触网（触达即装配缺陷）。资源键=args["service"] 原样。
        registrations.add(new ToolRegistry.Registration(
                MutationToolCatalog.serviceRestart(timeoutMillis, resultLimitBytes),
                MutationToolCatalog.nonExecutablePlaceholder(
                        MutationToolCatalog.TOOL_SERVICE_RESTART)));
        registrations.add(new ToolRegistry.Registration(
                MutationToolCatalog.serviceRollback(timeoutMillis, resultLimitBytes),
                MutationToolCatalog.nonExecutablePlaceholder(
                        MutationToolCatalog.TOOL_SERVICE_ROLLBACK)));
        // R7-X10 代码取证双工具：checkout 根与绑定映射均配置才注册（fail-closed，
        // docker 同律）。宿主静态声明 service→repo@commit 绑定——模型不可指定仓库/
        // commit/绝对路径；映射坏形状在 CodeSourceBinding.parse 构造期 fail-fast。
        if (!codeCheckoutRoot.isBlank() && !codeMapping.isBlank()) {
            Map<String, CodeSourceBinding.Entry> codeBinding =
                    CodeSourceBinding.parse(codeMapping);
            registrations.add(new ToolRegistry.Registration(
                    DirectReadToolCatalog.codeSearch(timeoutMillis, resultLimitBytes),
                    new CodeSearchExecutor(Path.of(codeCheckoutRoot.trim()), codeBinding)));
            registrations.add(new ToolRegistry.Registration(
                    DirectReadToolCatalog.codeRead(timeoutMillis, resultLimitBytes),
                    new CodeReadExecutor(Path.of(codeCheckoutRoot.trim()), codeBinding)));
        }
        return new ToolRegistry(registrations);
    }

    /** EN-07 固定语料库读面（release_asset 复用；RAG 工具执行器共用单实例） */
    @Bean
    public RunbookCorpusStore runbookCorpusStore(
            com.objwww.pr.control.release.domain.repository.ReleaseAssetRepository
                    releaseAssetRepository) {
        return new RunbookCorpusStore(releaseAssetRepository);
    }

    /** 工具策略（M4-16）：空策略硬失败在 ToolPolicy 构造期兜底 */
    @Bean
    public ToolPolicy am4ToolPolicy(
            @Value(ALLOWED_TOOLS_KEY) String allowedTools) {
        return new ToolPolicy(Set.of(allowedTools.split(",")));
    }

    /** V19 精确回放账本（M4-32 的 PG 面） */
    @Bean
    public PostgresToolReplayStore am4ToolReplayStore(JdbcClient jdbc) {
        return new PostgresToolReplayStore(jdbc);
    }

    /** 回放网关（M4-32）：镜像活网关纪律，MISS 绝不降级活执行 */
    @Bean
    public ReplayToolGateway am4ReplayGateway(ToolRegistry am4ToolRegistry,
            PostgresToolReplayStore am4ToolReplayStore) {
        return new ReplayToolGateway(am4ToolRegistry, am4ToolReplayStore);
    }

    /** 回放 runner（M4-33）：E2E-M4-08 回放链的工具出口（覆盖率/Token 申报面） */
    @Bean
    public AgentReplayRunner am4ReplayRunner(ReplayToolGateway am4ReplayGateway) {
        return new AgentReplayRunner(am4ReplayGateway);
    }

    /**
     * 影子面独立调用池（独立并发槽；容器关停时回收）。
     * EX-A4a（F17）bulkhead：FixedThreadPool 的无界队列换 ArrayBlockingQueue(16)
     * + Abort——满即拒绝，Gateway 显式映射模型可见族背压文案，不静默排队。
     */
    @Bean(destroyMethod = "shutdown")
    public ExecutorService am4ShadowPool(
            @Value(SHADOW_POOL_SIZE_KEY) int poolSize) {
        int threads = poolSize > 0 ? poolSize : SHADOW_POOL_SIZE_DEFAULT;
        java.util.concurrent.ThreadPoolExecutor pool = new java.util.concurrent.ThreadPoolExecutor(
                threads, threads, 0L, java.util.concurrent.TimeUnit.MILLISECONDS,
                new java.util.concurrent.ArrayBlockingQueue<>(SHADOW_QUEUE_CAPACITY),
                new java.util.concurrent.ThreadPoolExecutor.AbortPolicy());
        pool.allowCoreThreadTimeOut(false);
        return pool;
    }

    /**
     * WC-3：在途工具取消通知（进程内加速件）——CANCEL 应用/看门狗终态后中断该
     * Run 名下在途工具等待；丢了只慢不错（收敛骨架=持久终态行+边界探针+deadline）。
     */
    @Bean
    public com.objwww.pr.control.alert.application.tool.InFlightToolCancels
    am4InFlightToolCancels() {
        return new com.objwww.pr.control.alert.application.tool.InFlightToolCancels();
    }

    /**
     * 影子在线只读工具面（M4-35 → M6-01 落点 ⑦）：真实类型 ReadOnlyToolFace。
     * redteamOnly=false = canary 期策略位（REDTEAM 双闸从结构强制降为策略开关，
     * 落点 ③；装配硬接线，bundle 化归 M6-03）——R0/R1 裁剪/独立池/限流/预算门不变。
     */
    @Bean
    public ReadOnlyToolFace am4ShadowToolFace(ToolRegistry am4ToolRegistry,
            ToolPolicy am4ToolPolicy, ExecutorService am4ShadowPool,
            @Value(SHADOW_MAX_CALLS_KEY) long maxCallsPerWindow,
            @Value(SHADOW_WINDOW_MILLIS_KEY) long windowMillis,
            com.objwww.pr.control.alert.application.tool.InFlightToolCancels
                    am4InFlightToolCancels,
            @Value("${app.alert.provenance.build-sha:unknown}") String provenanceBuildSha,
            @Value("${app.alert.provenance.policy-version:pa-prod-v1}")
                    String provenancePolicyVersion,
            com.objwww.pr.control.alert.application.mutation.ActionIntentLedger
                    actionIntentLedger) {
        long calls = maxCallsPerWindow > 0 ? maxCallsPerWindow : SHADOW_MAX_CALLS_DEFAULT;
        long window = windowMillis > 0 ? windowMillis : SHADOW_WINDOW_MILLIS_DEFAULT;
        // PA-A5：决策溯源锚（build_sha + policy_version）随每次 R2+ 意图事件落账
        var provenance = com.objwww.pr.control.alert.domain.event.DecisionProvenance
                .empty(provenancePolicyVersion).withAgentBuildSha(provenanceBuildSha);
        // PB-B1：意图台账同短事务入账（意图行 + 意图事件，V114）
        return new ReadOnlyToolFace(am4ToolRegistry, am4ToolPolicy, am4ShadowPool,
                calls, window, Clock.systemUTC(), false, am4InFlightToolCancels, provenance,
                actionIntentLedger);
    }

    /**
     * BA-171 写类工具咽喉（mutation 面）：全量生产注册表（含 R2/R3）+ 意图台账 +
     * 审批跟进回调（可空=未装配则意图留 OPEN，诚实降级）。与影子只读面物理隔离——
     * R3 工具不进影子注册面（ReadOnlyToolFace 裁剪 R0/R1），本面是 VALIDATE_ONLY
     * 的唯一合法落点。写类工具永远走 VALIDATE_ONLY 短路，执行池实际零使用（复用
     * am4ShadowPool 不新辟线程资源）。
     */
    @Bean
    public ToolGateway am4MutationToolGateway(ToolRegistry am4ToolRegistry,
            ToolPolicy am4ToolPolicy, ExecutorService am4ShadowPool,
            com.objwww.pr.control.alert.application.mutation.ActionIntentLedger
                    actionIntentLedger,
            org.springframework.beans.factory.ObjectProvider<
                    com.objwww.pr.control.alert.application.mutation.IntentFollowUp>
                    intentFollowUp,
            @Value("${app.alert.provenance.build-sha:unknown}") String provenanceBuildSha,
            @Value("${app.alert.provenance.policy-version:pa-prod-v1}")
                    String provenancePolicyVersion) {
        var provenance = com.objwww.pr.control.alert.domain.event.DecisionProvenance
                .empty(provenancePolicyVersion).withAgentBuildSha(provenanceBuildSha);
        return new ToolGateway(am4ToolRegistry, am4ToolPolicy, am4ShadowPool,
                Clock.systemUTC(), null, null, provenance, actionIntentLedger,
                intentFollowUp.getIfAvailable());
    }

    /** 影子对照路由器（M4-34）：同 digest 盖章/独立预算/失败隔离，无发布出口 */
    @Bean
    public SnapshotShadowRouter am4ShadowRouter() {
        return new SnapshotShadowRouter();
    }

    // ------------------------------------------------------------------ Agent 面

    /**
     * Agent 注册表（M4-24）：三固定 Agent，启动期 fail-fast，运行期不可生。
     * R7-X6 主模式（{@code app.alert.r7.primary.enabled=true}）注册表升级为
     * <b>release 快照</b>（{@link AgentRegistry#forRelease}）：三兼容角色 + primary
     * （BOUNDED_LLM/PRIMARY 相位）——新 Run 可启主模式，在途 Run 恢复按持久绑定
     * (name,version,digest) requireExact 精确解析不漂移；回滚（摘除 primary）后
     * 在途主模式 Run 解析拒绝 = CAPABILITY_UNAVAILABLE 显式 DEAD（§11.5 首期拒绝
     * 热迁移），不猜 latest。
     */
    @Bean
    public AgentRegistry am4AgentRegistry(
            @Value(PROMPT_VERSION_KEY) String promptVersion,
            @Value(BUDGET_STEP_KEY) long budgetStep,
            @Value(BUDGET_TOOL_CALLS_KEY) long budgetToolCalls,
            @Value(BUDGET_EVIDENCES_KEY) long budgetEvidences,
            @Value(BUDGET_SUBTASKS_KEY) long budgetSubtasks,
            org.springframework.beans.factory.ObjectProvider<AgentProfile> primaryProfile,
            @Value("${app.alert.r7.primary.release-digest:}") String releaseDigest,
            com.objwww.pr.control.release.domain.repository.ReleaseAssetRepository
                    releaseAssetRepository) {
        Map<BudgetKind, Long> budgetLimits = new LinkedHashMap<>();
        budgetLimits.put(BudgetKind.STEP, budgetStep);
        budgetLimits.put(BudgetKind.TOOL_CALL, budgetToolCalls);
        budgetLimits.put(BudgetKind.EVIDENCE, budgetEvidences);
        budgetLimits.put(BudgetKind.SUBTASK, budgetSubtasks);
        Map<String, Object> outputSchema = Map.of(OUTPUT_SCHEMA_TYPE, OUTPUT_SCHEMA_OBJECT);
        AgentProfile metrics = new AgentProfile("metrics", AGENT_VERSION,
                "native-metrics", promptVersion,
                Set.of(MetricsAgent.TOOL_NAME), budgetLimits, outputSchema);
        AgentProfile logs = new AgentProfile("logs", AGENT_VERSION,
                "native-logs", promptVersion,
                Set.of(LogsAgent.TOOL_NAME), budgetLimits, outputSchema);
        AgentProfile change = new AgentProfile("change", AGENT_VERSION,
                "native-change", promptVersion,
                Set.of(ChangeAgent.TOOL_NAME), budgetLimits, outputSchema);
        AgentProfile primary = primaryProfile.getIfAvailable();
        if (primary == null) {
            registerPromptAssets(releaseAssetRepository, List.of(metrics, logs, change));
            return new AgentRegistry(List.of(metrics, logs, change));
        }
        if (releaseDigest == null || releaseDigest.isBlank()) {
            throw new IllegalStateException(
                    "主模式启用必须提供 app.alert.r7.primary.release-digest（release 快照身份面）");
        }
        registerPromptAssets(releaseAssetRepository, List.of(metrics, logs, change, primary));
        return AgentRegistry.forRelease(releaseDigest, List.of(metrics, logs, change, primary));
    }

    /**
     * R2：profile.prompt 原文随 release 落快照（release_asset PROMPT kind，内容寻址
     * 幂等重放锚）——账行 role_digest 可反查原文（MC36 回放面）。注册失败不阻断启动
     * （回放面退化 digest-only 诚实态），log-warn 留痕。
     */
    private void registerPromptAssets(
            com.objwww.pr.control.release.domain.repository.ReleaseAssetRepository assets,
            List<AgentProfile> profiles) {
        for (AgentProfile profile : profiles) {
            try {
                Map<String, Object> content = new LinkedHashMap<>();
                content.put("messages_template", profile.prompt());
                content.put("variables_schema", List.of("task_envelope"));
                content.put("role", profile.name());
                content.put("role_version", profile.version());
                content.put("role_digest", profile.digest());
                boolean inserted = assets.insert(
                        com.objwww.pr.control.release.domain.model.ReleaseAsset.of(
                                com.objwww.pr.control.release.domain.model.ReleaseAsset.KIND_PROMPT,
                                content, "am4-config", java.time.Clock.systemUTC().instant()));
                if (!inserted) {
                    log.debug("prompt 资产已登记（幂等重放锚）: role={}", profile.name());
                }
            } catch (RuntimeException e) {
                log.warn("prompt 资产登记失败（不阻断启动）: role={}, 原因: {}",
                        profile.name(), e.getMessage());
            }
        }
    }

    /** Metrics Agent（在线影子形态：工具出口 = 影子面；EX-A1 全参=预算门+熔断门） */
    @Bean
    public MetricsAgent am4MetricsAgent(AgentRegistry am4AgentRegistry,
            ReadOnlyToolFace am4ShadowToolFace, EvidenceRepository evidenceRepository,
            RcaToolInvocationLedger rcaToolInvocationLedger, ObjectMapper objectMapper,
            com.objwww.pr.control.alert.application.RunBudgetGate runBudgetGate,
            com.objwww.pr.control.alert.domain.budget.DoomLoopGuard am4DoomLoopGuard) {
        return new MetricsAgent(am4AgentRegistry.require("metrics", AGENT_VERSION),
                am4ShadowToolFace.readOnlyView(), am4ShadowToolFace,
                evidenceRepository, rcaToolInvocationLedger, objectMapper,
                runBudgetGate, am4DoomLoopGuard);
    }

    /** Logs Agent（在线影子形态） */
    @Bean
    public LogsAgent am4LogsAgent(AgentRegistry am4AgentRegistry,
            ReadOnlyToolFace am4ShadowToolFace, EvidenceRepository evidenceRepository,
            RcaToolInvocationLedger rcaToolInvocationLedger, ObjectMapper objectMapper,
            com.objwww.pr.control.alert.application.RunBudgetGate runBudgetGate,
            com.objwww.pr.control.alert.domain.budget.DoomLoopGuard am4DoomLoopGuard) {
        return new LogsAgent(am4AgentRegistry.require("logs", AGENT_VERSION),
                am4ShadowToolFace.readOnlyView(), am4ShadowToolFace,
                evidenceRepository, rcaToolInvocationLedger, objectMapper,
                runBudgetGate, am4DoomLoopGuard);
    }

    /** Change Agent（在线影子形态） */
    @Bean
    public ChangeAgent am4ChangeAgent(AgentRegistry am4AgentRegistry,
            ReadOnlyToolFace am4ShadowToolFace, EvidenceRepository evidenceRepository,
            RcaToolInvocationLedger rcaToolInvocationLedger, ObjectMapper objectMapper,
            com.objwww.pr.control.alert.application.RunBudgetGate runBudgetGate,
            com.objwww.pr.control.alert.domain.budget.DoomLoopGuard am4DoomLoopGuard) {
        return new ChangeAgent(am4AgentRegistry.require("change", AGENT_VERSION),
                am4ShadowToolFace.readOnlyView(), am4ShadowToolFace,
                evidenceRepository, rcaToolInvocationLedger, objectMapper,
                runBudgetGate, am4DoomLoopGuard);
    }

    /**
     * EN-05 P0 直查 Agent 族（§一 九工具同构面）：每实例恰一工具（基座构造期单工具
     * 校验不破），装配形态镜像三 Agent 在线影子面（工具出口 = 影子只读面 + 预算门
     * + 熔断门）；docker 双工具未注册（base-url/容器 allowlist 未配置）时跳过
     * ——fail-closed，不为缺件工具建 Agent。delegates 合并见 am4PrimaryToolPort。
     */
    @Bean
    public List<DirectReadToolAgent> am4DirectReadAgents(
            ToolRegistry am4ToolRegistry, ReadOnlyToolFace am4ShadowToolFace,
            ToolGateway am4MutationToolGateway,
            EvidenceRepository evidenceRepository,
            RcaToolInvocationLedger rcaToolInvocationLedger, ObjectMapper objectMapper,
            com.objwww.pr.control.alert.application.RunBudgetGate runBudgetGate,
            com.objwww.pr.control.alert.domain.budget.DoomLoopGuard am4DoomLoopGuard,
            @Value(PROMPT_VERSION_KEY) String promptVersion,
            @Value(BUDGET_STEP_KEY) long budgetStep,
            @Value(BUDGET_TOOL_CALLS_KEY) long budgetToolCalls,
            @Value(BUDGET_EVIDENCES_KEY) long budgetEvidences,
            @Value(BUDGET_SUBTASKS_KEY) long budgetSubtasks) {
        Map<BudgetKind, Long> budgetLimits = new LinkedHashMap<>();
        budgetLimits.put(BudgetKind.STEP, budgetStep);
        budgetLimits.put(BudgetKind.TOOL_CALL, budgetToolCalls);
        budgetLimits.put(BudgetKind.EVIDENCE, budgetEvidences);
        budgetLimits.put(BudgetKind.SUBTASK, budgetSubtasks);
        Map<String, Object> outputSchema = Map.of(OUTPUT_SCHEMA_TYPE, OUTPUT_SCHEMA_OBJECT);
        List<DirectReadToolAgent> agents = new ArrayList<>();
        for (SingleToolEvidenceAgent.ToolSpec spec : primaryDelegateSpecs()) {
            // 未注册 = 条件件未配置（docker）——不建 Agent；注册校验留给基座构造期
            var registration = am4ToolRegistry.find(spec.toolName(), spec.toolVersion());
            if (registration.isEmpty()) {
                continue;
            }
            AgentProfile profile = new AgentProfile(spec.toolName(), AGENT_VERSION,
                    "direct-read", promptVersion, Set.of(spec.toolName()), budgetLimits,
                    outputSchema);
            if (registration.get().definition().risk().executable()) {
                agents.add(new DirectReadToolAgent(profile, spec,
                        am4ShadowToolFace.readOnlyView(), am4ShadowToolFace,
                        evidenceRepository, rcaToolInvocationLedger, objectMapper,
                        runBudgetGate, am4DoomLoopGuard));
            } else {
                // BA-171：R2/R3 写类工具走 mutation 咽喉（全量注册表 + 意图台账 +
                // 审批跟进回调）——影子只读面物理无此工具（R0/R1 裁剪）
                agents.add(new DirectReadToolAgent(profile, spec,
                        am4ToolRegistry, am4MutationToolGateway,
                        evidenceRepository, rcaToolInvocationLedger, objectMapper,
                        runBudgetGate, am4DoomLoopGuard));
            }
        }
        return List.copyOf(agents);
    }

    /** Native RCA Agent（M4-30）：消费结构化黑板出 Claim，不直接发布报告；EX-A4a（F05）黑板=冻结快照成员 */
    @Bean
    public NativeRcaAgent am4NativeRcaAgent(EvidenceRepository evidenceRepository,
            com.objwww.pr.control.alert.domain.evidence.EvidenceSnapshotRepository
                    evidenceSnapshotRepository,
            ClaimStore claimStore, ClaimReducer am4ClaimReducer) {
        return new NativeRcaAgent(evidenceRepository, evidenceSnapshotRepository,
                claimStore, am4ClaimReducer);
    }

    /** Claim 归并（M4-22）：规则驱动消重/冲突/覆盖，降级续跑白名单配置化 */
    @Bean
    public ClaimReducer am4ClaimReducer(
            @Value(REDUCER_ALLOWLIST_KEY) String allowlist,
            @Value(REDUCER_POLICY_VERSION_KEY) String policyVersion) {
        return new ClaimReducer(Set.of(allowlist.split(",")), policyVersion);
    }

    // ------------------------------------------------------------------ DAG 面

    /** 确定性 Supervisor（M4-25/26 + R7-X4/X11）：模型无调度权，恢复入口只有 advance；
     *  委派批上限 = 运行时旋钮（臂A 零委派姿态部署期可配，信封余量与此同源） */
    @Bean
    public DeterministicSupervisor am4DeterministicSupervisor(
            AgentRegistry am4AgentRegistry,
            RcaTaskRepository rcaTaskRepository,
            TaskEdgeRepository taskEdgeRepository,
            com.objwww.pr.control.alert.domain.repository.TaskExecutionBindingRepository
                    taskExecutionBindingRepository,
            com.objwww.pr.control.alert.domain.repository.PrimaryCheckpointRepository
                    primaryCheckpointRepository,
            com.objwww.pr.control.alert.domain.repository.DelegationDecisionRepository
                    delegationDecisionRepository,
            RcaRunRepository rcaRunRepository,
            TransactionOperations tx,
            DagExecutionService dagExecutionService,
            com.objwww.pr.control.alert.domain.repository.RunConfigEpochRepository
                    runConfigEpochRepository,
            @Value(R7_PRIMARY_MAX_DELEGATION_BATCHES_KEY) int maxDelegationBatches) {
        PlanCompiler compiler = new PlanCompiler(am4AgentRegistry, rcaTaskRepository,
                taskEdgeRepository, taskExecutionBindingRepository, tx,
                runConfigEpochRepository);
        return new DeterministicSupervisor(compiler, dagExecutionService,
                rcaRunRepository, rcaTaskRepository, taskExecutionBindingRepository,
                primaryCheckpointRepository, delegationDecisionRepository,
                am4AgentRegistry, tx, AlertClock.system(), runConfigEpochRepository,
                maxDelegationBatches);
    }

    // ------------------------------------------------------------------ R7-X6 主模式

    /**
     * CL-01 检查点提交围栏（告警-Agent闭环修复 §2）：run/task/checkpoint 统一锁序 +
     * revision 条件写 + 动作身份去重，运行路径检查点唯一提交口。
     */
    @Bean
    public com.objwww.pr.control.alert.application.agent.PrimaryCheckpointCommitService
            am4CheckpointCommitFence(
            RcaRunRepository rcaRunRepository,
            RcaTaskRepository rcaTaskRepository,
            com.objwww.pr.control.alert.domain.repository.PrimaryCheckpointRepository
                    primaryCheckpointRepository,
            com.objwww.pr.control.alert.domain.repository.RunConfigEpochRepository
                    runConfigEpochRepository,
            com.objwww.pr.control.alert.domain.repository.WorkingMemoryPort
                    workingMemoryPort,
            com.objwww.pr.control.alert.domain.repository.RcaAttemptRepository
                    rcaAttemptRepository,
            TransactionOperations tx,
            com.objwww.pr.control.infrastructure.observability.AlertMetrics alertMetrics) {
        // WC-5：迟到提交拒绝计数（STALE 族/RUN_TERMINAL）随围栏接线；
        // PA-A1：attempt 进度回写面随围栏接线（推进型 APPLIED 同事务回写）
        return new com.objwww.pr.control.alert.application.agent
                .PrimaryCheckpointCommitService(rcaRunRepository, rcaTaskRepository,
                primaryCheckpointRepository, runConfigEpochRepository,
                com.objwww.pr.control.alert.application.AlertClock.system(), tx,
                workingMemoryPort, alertMetrics, rcaAttemptRepository);
    }

    /**
     * 主 Agent Profile（R7-X6，BOUNDED_LLM/PRIMARY 相位）：enabled=false 返回 null
     * （NullBean——注册表/运行器目录/执行器全部走旧兼容路由，行为零变化）。预算
     * STEP=MaxSteps、TOOL_CALL=兼容上限、TOKEN=主模式新增维（R7a-1 账本计费面对齐）。
     */
    @Bean
    public AgentProfile am4PrimaryProfile(
            @Value("${app.alert.r7.primary.enabled:false}") boolean enabled,
            @Value(R7_PRIMARY_PROMPT_KEY) String prompt,
            // 同源漂移风险（BA-171 起三处互指）：本默认值 = am4PrimaryToolPort 的同名
            // 默认值 = PromptWorkbenchController#tools 的透出默认值——放行面调整三处同步改
            @Value("${app.alert.r7.primary.tool-allowlist:prometheus.query,logs.query,"
                    + "prometheus.instant,prometheus.metric_value,prometheus.catalog,prometheus.label_values,"
                    + "prometheus.rules,logs.aggregate,service.restart,service.rollback,"
                    + "alert.history,change.diff,change.query,rca_history.search}")
            String toolAllowlist,
            @Value("${app.alert.r7.primary.max-steps:8}") int maxSteps,
            @Value(BUDGET_TOOL_CALLS_KEY) long toolCallBudget,
            @Value("${app.alert.r7.primary.budget-tokens:60000}") long tokenBudget,
            @Value(PROMPT_VERSION_KEY) String promptVersion,
            com.objwww.pr.control.alert.application.tool.ToolRegistry toolRegistry) {
        if (!enabled) {
            return null;
        }
        // 缺省空串 → 代码内置完整协议 prompt（占位符默认值放不下含 } 的 JSON 形状示例）
        String effectivePrompt = prompt == null || prompt.isBlank()
                ? R7_PRIMARY_DEFAULT_PROMPT : prompt;
        Map<BudgetKind, Long> budget = new LinkedHashMap<>();
        budget.put(BudgetKind.STEP, (long) maxSteps);
        budget.put(BudgetKind.TOOL_CALL, toolCallBudget);
        budget.put(BudgetKind.TOKEN, tokenBudget);
        // BA-112：allowlist 工具的 args JSON Schema 钉进 Profile inputSchema（进 digest
        // 钉版），信封 tool_schemas 面供模型首发取参——allowlist 与注册表不一致=启动期
        // fail-fast（配置缺件优于运行期步步 INVALID_ARGS）
        Map<String, Object> toolSchemas = new LinkedHashMap<>();
        for (String toolId : toolAllowlist.split(",")) {
            com.objwww.pr.control.alert.application.tool.ToolRegistry.Registration reg =
                    toolRegistry.all().stream()
                            .filter(r -> r.definition().name().equals(toolId))
                            .findFirst()
                            .orElseThrow(() -> new IllegalStateException(
                                    "主模式 allowlist 工具未注册（启动期 fail-fast）: " + toolId));
            toolSchemas.put(toolId, reg.definition().schema());
        }
        return new AgentProfile("primary", AGENT_VERSION, effectivePrompt, promptVersion,
                Set.of(toolAllowlist.split(",")), budget,
                Map.of(OUTPUT_SCHEMA_TYPE, OUTPUT_SCHEMA_OBJECT), toolSchemas,
                com.objwww.pr.control.alert.domain.agent.AgentPhase.PRIMARY,
                com.objwww.pr.control.alert.domain.agent.RoleRuntimeKind.BOUNDED_LLM,
                Set.of(), maxSteps, "deterministic-final-on-exhaustion");
    }

    /**
     * RCA 侧模型网关（R7-X6）：复用 M3 构建工厂（路由/客户端/参数单点），唯一分叉 =
     * 事件汇挂 {@code rcaModelEventSink}（rca_event，绕开 pr_revision FK 面——
     * R7a-1 §二装配要求）。
     */
    @Bean
    public com.objwww.pr.control.alert.application.agent.RcaModelGateway am4RcaModelGateway(
            @Value("${app.alert.r7.primary.enabled:false}") boolean enabled,
            M3ModelGatewayConfig m3ModelGatewayConfig,
            M3ModelGatewayConfig.ModelGatewayProperties props,
            com.objwww.pr.control.domain.service.ExecutionEventRepository rcaModelEventSink,
            com.objwww.pr.control.domain.ai.PricingService pricingService,
            ObjectMapper objectMapper,
            org.springframework.core.env.Environment env,
            @Value("${AGENT_MODEL:glm-5}") String primaryModel,
            @Value("${AGENT_MODEL_FALLBACK:}") String fallbackModel,
            @Value("${OPENAI_COMPAT_BASE_URL:https://dashscope.aliyuncs.com/compatible-mode/v1}")
            String primaryBaseUrl,
            @Value("${OPENAI_COMPAT_BASE_URL_FALLBACK:}") String fallbackBaseUrl,
            @Value("${AGENT_MODEL_API_KEY:placeholder-not-configured}") String primaryApiKey,
            @Value("${AGENT_MODEL_API_KEY_FALLBACK:}") String fallbackApiKey,
            @Value("${app.review.model-provider:openai-compatible}") String provider,
            @Value("${app.review.model-version:configured}") String contractVersion,
            @Value("${app.worker.max-lease-seconds:600}") int maxLeaseSeconds,
            @Value("${app.alert.r7.max-input-tokens:24000}") int maxInputTokens,
            com.objwww.pr.control.alert.domain.agent.RcaModelCallLedger rcaModelCallLedger,
            com.objwww.pr.control.alert.domain.agent.RcaModelInputCapture rcaModelInputCapture,
            com.objwww.pr.control.alert.domain.agent.RcaModelOutputCapture rcaModelOutputCapture) {
        if (!enabled) {
            return null;
        }
        com.objwww.pr.control.application.ModelGateway rcaFace =
                m3ModelGatewayConfig.buildModelGateway(props,
                        // BA-109 裁定：平台模型账本深绑 PR 域（V5 三列
                        // NOT NULL+FK），RCA 调用唯一账本山 = rca_model_call（V48，
                        // D5 等价门在 RcaModelGateway.open）——平台账本写面对 RCA 旁路
                        new com.objwww.pr.control.infrastructure.persistence
                                .NoOpModelCallLedgerRepository(),
                        new com.objwww.pr.control.domain.service.ExecutionLedger(
                                rcaModelEventSink),
                        pricingService, objectMapper, env, primaryModel, fallbackModel,
                        primaryBaseUrl, fallbackBaseUrl, primaryApiKey, fallbackApiKey,
                        provider, contractVersion, maxLeaseSeconds);
        return new com.objwww.pr.control.alert.application.agent.RcaModelGateway(rcaFace,
                rcaModelCallLedger, pricingService, rcaModelInputCapture,
                rcaModelOutputCapture, Clock.systemUTC(), maxInputTokens);
    }

    /** 主 Agent 受限取证口（R7-X6）：allowlist 工具对位既有受控单工具 Agent 面；
     * EN-05 后 delegates 合并 P0 直查 Agent 族（按 toolName 对位，同键不覆盖兼容面）。
     * A0 补充方案 AS-05 启动期一致性首闸：allowlist 每个工具必须有执行装配（delegate），
     * 缺件=启动失败——不等模型第二步才发现（run16/17 UNKNOWN_TOOL→DEAD 的直接根因面）。*/
    @Bean
    public com.objwww.pr.control.alert.application.agent.BoundedLlmRoleRunner.PrimaryToolPort
            am4PrimaryToolPort(
            @Value("${app.alert.r7.primary.enabled:false}") boolean enabled,
            // 同源漂移风险（BA-171 起三处互指）：本默认值 = am4PrimaryProfile 的同名
            // 默认值 = PromptWorkbenchController#tools 的透出默认值——放行面调整三处同步改
            @Value("${app.alert.r7.primary.tool-allowlist:prometheus.query,logs.query,"
                    + "prometheus.instant,prometheus.metric_value,prometheus.catalog,prometheus.label_values,"
                    + "prometheus.rules,logs.aggregate,service.restart,service.rollback,"
                    + "alert.history,change.diff,change.query,rca_history.search}")
            String toolAllowlist,
            MetricsAgent am4MetricsAgent, LogsAgent am4LogsAgent,
            ChangeAgent am4ChangeAgent,
            @org.springframework.beans.factory.annotation.Qualifier("am4DirectReadAgents")
            List<DirectReadToolAgent> am4DirectReadAgents,
            RcaToolInvocationLedger toolLedger) {
        if (!enabled) {
            return null;
        }
        Map<String, com.objwww.pr.control.alert.application.agent.SingleToolEvidenceAgent>
                delegates = new LinkedHashMap<>();
        delegates.put(MetricsAgent.TOOL_NAME, am4MetricsAgent);
        delegates.put(LogsAgent.TOOL_NAME, am4LogsAgent);
        delegates.put(ChangeAgent.TOOL_NAME, am4ChangeAgent);
        for (DirectReadToolAgent agent : am4DirectReadAgents) {
            delegates.put(agent.toolName(), agent);
        }
        java.util.Set<String> allowlist = java.util.Set.of(toolAllowlist.split(","));
        java.util.List<String> unwired = allowlist.stream()
                .filter(toolId -> !delegates.containsKey(toolId)).toList();
        if (!unwired.isEmpty()) {
            throw new IllegalStateException(
                    "主模式 allowlist 工具未对位执行装配（启动期 fail-fast，A0 补充方案 "
                            + "AS-05）: " + unwired);
        }
        return new com.objwww.pr.control.alert.application.agent.PrimaryGatewayToolPort(
                delegates, toolLedger, allowlist);
    }

    /**
     * 主 Agent 根因码表（root_cause_hit 评分贯通面）：与评测侧同一份
     * synonym-lexicon 文件装订 canonical 码清单（配置键
     * {@code app.alert.r7.primary.root-cause-catalog-path}，缺省与评测
     * {@code app.alert.eval.lexicon-path} 同默认值）。文件缺失/解析失败 → WARN +
     * 空码表（信封省略 root_cause_catalog 键），不 fail-fast 阻断告警主链——
     * 码表是提示面，缺失只影响评分上限。enabled=false 返回 null（NullBean）。
     */
    @Bean
    public com.objwww.pr.control.alert.domain.agent.RootCauseCatalogPort
            am4RootCauseCatalog(
            @Value("${app.alert.r7.primary.enabled:false}") boolean enabled,
            org.springframework.core.io.ResourceLoader resourceLoader,
            @Value("${app.alert.r7.primary.root-cause-catalog-path:}")
                    String catalogPath) {
        if (!enabled) {
            return null;
        }
        // 空串（env 透传缺省）→ 代码缺省（占位符 default 与空 env 语义歧义，单点收口）
        String effectivePath = catalogPath == null || catalogPath.isBlank()
                ? "classpath:eval/synonym-lexicon-v1.yml" : catalogPath;
        return com.objwww.pr.control.alert.infrastructure.catalog.YamlRootCauseCatalog
                .loadOrEmpty(resourceLoader, effectivePath);
    }

    /**
     * 任务信封装配器（R1/MA-01）：告警材料读口由 run→incident→最新告警事件确定性
     * 投影（缺项如实 null 不造数）；工具账本/裁决台账直读供轨迹与工作记忆重建。
     * MC21/22 回执合并面（当前轮 ACCEPTED 回执入信封+反证/缺口记忆槽）与 MC31
     * 人工材料区分面（run→incident→已准入材料，与实测证据分槽）随全参构造接入。
     */
    @Bean
    public com.objwww.pr.control.alert.application.agent.ContextAssembler
            am4ContextAssembler(
            @Value("${app.alert.r7.primary.enabled:false}") boolean enabled,
            EvidenceRepository evidenceRepository,
            RcaToolInvocationLedger rcaToolInvocationLedger,
            com.objwww.pr.control.alert.domain.repository.DelegationDecisionRepository
                    delegationDecisionRepository,
            com.objwww.pr.control.alert.domain.repository.RcaRunRepository rcaRunRepository,
            com.objwww.pr.control.alert.domain.repository.AlertEventRepository
                    alertEventRepository,
            com.objwww.pr.control.alert.domain.repository.WorkingMemoryPort
                    workingMemoryPort,
            com.objwww.pr.control.alert.domain.repository.DelegationReceiptRepository
                    delegationReceiptRepository,
            com.objwww.pr.control.alert.domain.repository.OperatorMaterialRepository
                    operatorMaterialRepository,
            com.objwww.pr.control.release.application.SkillSelectionService
                    skillSelectionService,
            com.objwww.pr.control.alert.domain.repository.ContextSummaryPort
                    contextSummaryPort,
            org.springframework.beans.factory.ObjectProvider<
                    com.objwww.pr.control.alert.domain.agent.RootCauseCatalogPort>
                    am4RootCauseCatalog,
            @Value("${app.alert.r7.compaction.replace-omitted:false}")
            boolean replaceOmittedSummaries,
            ObjectMapper objectMapper) {
        if (!enabled) {
            return null;
        }
        com.objwww.pr.control.alert.application.agent.ContextAssembler.AlertMaterialPort
                alertMaterialPort = runId -> rcaRunRepository.findById(runId)
                .map(run -> {
                    var events = alertEventRepository.findByIncidentId(run.incidentId());
                    return events.isEmpty()
                            ? com.objwww.pr.control.alert.application.agent.ContextAssembler
                            .AlertMaterial.unknown()
                            : materialOf(events.get(events.size() - 1));
                })
                .orElse(com.objwww.pr.control.alert.application.agent.ContextAssembler
                        .AlertMaterial.unknown());
        com.objwww.pr.control.alert.application.agent.ContextAssembler.OperatorMaterialPort
                operatorMaterialPort = runId -> operatorMaterialRepository
                .findAcceptedByRun(runId).stream()
                .map(m -> new com.objwww.pr.control.alert.application.agent.ContextAssembler
                        .OperatorMaterialView(m.operator(), m.kind().name(),
                                m.sourceRef(), m.content(), m.admission().name()))
                .toList();
        // EN-08 装配缝 + CL-05 持久绑定：roleId/configEpoch/releaseDigest 取自冻结
        // 任务绑定（可信身份），alertname/service 供 selector 双维命中；钉版/允许集/
        // 冲突/RETIRED 阻断在 SkillSelectionService 收口
        com.objwww.pr.control.alert.application.agent.ContextAssembler.SkillPort
                skillPort = (runId, roleId, configEpoch, releaseDigest, alertname, service) ->
                skillSelectionService.selectPinned(runId, roleId, configEpoch,
                        releaseDigest, alertname, service);
        // CL-08 消费读缝：按检查点 current_summary_id 精确读已提交摘要（未钉面时槽省略）
        com.objwww.pr.control.alert.application.agent.ContextAssembler.SummaryMaterialPort
                summaryMaterialPort = contextSummaryPort::findById;
        return new com.objwww.pr.control.alert.application.agent.ContextAssembler(
                evidenceRepository, rcaToolInvocationLedger, delegationDecisionRepository,
                alertMaterialPort, workingMemoryPort, delegationReceiptRepository,
                operatorMaterialPort, skillPort, summaryMaterialPort,
                am4RootCauseCatalog.getIfAvailable(),
                // JE-01：摘要替换消费开关（默认 false = 附加注入语义零漂移）
                replaceOmittedSummaries,
                Clock.systemUTC(), objectMapper);
    }

    /** 最新告警事件 → 告警材料（labels/annotations 确定性投影；缺项 null）。
     * CL-05 热切预生成适配器（PersistenceConfig）复用同款投影。 */
    public static com.objwww.pr.control.alert.application.agent.ContextAssembler.AlertMaterial
            materialOf(com.objwww.pr.control.alert.domain.model.AlertEvent event) {
        Map<String, String> labels = event.labels();
        Map<String, String> annotations = event.annotations() == null
                ? Map.of() : event.annotations();
        String service = firstOf(labels, "service", "service_name", "namespace", "job");
        String summary = firstOf(annotations, "summary", "description", "message");
        return new com.objwww.pr.control.alert.application.agent.ContextAssembler.AlertMaterial(
                labels.get("alertname"), service, labels.get("severity"), summary);
    }

    private static String firstOf(Map<String, String> source, String... keys) {
        for (String key : keys) {
            String value = source.get(key);
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }

    /**
     * JE-01：TypeSafe Jev HTTP 客户端。mode=OFF 或 base-url/api-key 缺件 → null
     * （NullBean 零装配——缺密钥绝不启用真实请求，方案 §5"OFF 模式零调用"）。
     */
    @Bean
    public com.objwww.pr.control.alert.application.agent.JevClient am4JevClient(
            @Value("${app.alert.r7.jev.mode:OFF}") String jevMode,
            @Value("${app.alert.r7.jev.base-url:}") String baseUrl,
            @Value("${app.alert.r7.jev.api-key:}") String apiKey,
            @Value("${app.alert.r7.jev.model:jev-1.13.0}") String model,
            @Value("${app.alert.r7.jev.timeout-ms:10000}") long timeoutMs,
            ObjectMapper objectMapper) {
        if (jevMode == null || jevMode.isBlank()
                || "OFF".equalsIgnoreCase(jevMode.trim())
                || baseUrl == null || baseUrl.isBlank()
                || apiKey == null || apiKey.isBlank()) {
            return null;
        }
        return new com.objwww.pr.control.infrastructure.model.HttpJevClient(
                objectMapper, baseUrl, apiKey, model, timeoutMs);
    }

    /**
     * JE-01：Jev 增强服务（选材 + FINAL 复核）。mode=OFF → null（运行器缝零注入）；
     * 客户端缺件但 mode 非 OFF → 服务照建（选材/复核调用面内部 noop + 有界回退，
     * 不阻断调查）。预算/账本/围栏与主模型调用同律（独立 roleId + 保留段动作序）。
     */
    @Bean
    public com.objwww.pr.control.alert.application.agent.JevEnhancementPort
            am4JevEnhancement(
            @Value("${app.alert.r7.jev.mode:OFF}") String jevMode,
            @Value("${app.alert.r7.jev.model:jev-1.13.0}") String model,
            @Value("${app.alert.r7.jev.select-threshold:0.5}") double selectThreshold,
            @Value("${app.alert.r7.jev.review-enabled:true}") boolean reviewEnabled,
            @Value("${app.alert.r7.jev.input-usd-per-m:0.042}") double inputUsdPerMillion,
            @Value("${app.alert.r7.jev.min-pool-size:20}") int minPoolSize,
            @Value("${app.alert.r7.jev.max-selected:20}") int maxSelected,
            RcaRunRepository rcaRunRepository,
            RcaTaskRepository rcaTaskRepository,
            com.objwww.pr.control.alert.application.RunBudgetGate runBudgetGate,
            com.objwww.pr.control.alert.domain.agent.RcaModelCallLedger ledger,
            com.objwww.pr.control.alert.domain.repository.WorkingMemoryPort
                    workingMemoryPort,
            org.springframework.beans.factory.ObjectProvider<
                    com.objwww.pr.control.alert.application.agent.JevClient>
                    am4JevClient,
            org.springframework.beans.factory.ObjectProvider<
                    com.objwww.pr.control.alert.domain.repository.RcaJevSelectionPort>
                    rcaJevSelectionPort,
            ObjectMapper objectMapper) {
        com.objwww.pr.control.alert.application.agent.JevEnhancementService.Mode mode =
                parseJevMode(jevMode);
        if (mode == com.objwww.pr.control.alert.application.agent.JevEnhancementService.Mode.OFF) {
            return null;
        }
        return new com.objwww.pr.control.alert.application.agent.JevEnhancementService(
                rcaRunRepository, rcaTaskRepository, runBudgetGate, ledger,
                workingMemoryPort, am4JevClient.getIfAvailable(), objectMapper,
                Clock.systemUTC(), mode, model, selectThreshold, reviewEnabled,
                inputUsdPerMillion, minPoolSize, maxSelected,
                rcaJevSelectionPort.getIfAvailable());
    }

    /** JE-01：模式解析（OFF/SHADOW/SELECT；非法值启动即 fail-fast 不静默归 OFF） */
    private static com.objwww.pr.control.alert.application.agent.JevEnhancementService.Mode
    parseJevMode(String raw) {
        if (raw == null || raw.isBlank()) {
            return com.objwww.pr.control.alert.application.agent.JevEnhancementService.Mode.OFF;
        }
        return com.objwww.pr.control.alert.application.agent.JevEnhancementService.Mode
                .valueOf(raw.trim().toUpperCase(java.util.Locale.ROOT));
    }

    /**
     * 受控 LLM 运行器（R7-X6）：守卫（§六固定顺序，ActionGuard 组装点）+ 主 Runner
     * + 取证口三位一体；enabled=false 返回 null（运行器目录只含兼容单工具面）。
     * R11：默认关的一步边界压缩（compaction.* 配置族，放量前提 MC34 三臂对照）。
     * JE-01：Jev 增强缝（可空=null 零漂移）随全参构造接入。
     */
    @Bean
    public com.objwww.pr.control.alert.application.agent.BoundedLlmRoleRunner
            am4BoundedLlmRoleRunner(
            @Value("${app.alert.r7.primary.enabled:false}") boolean enabled,
            AgentRegistry am4AgentRegistry,
            RcaRunRepository rcaRunRepository,
            RcaTaskRepository rcaTaskRepository,
            com.objwww.pr.control.alert.application.RunBudgetGate runBudgetGate,
            org.springframework.beans.factory.ObjectProvider<
                    com.objwww.pr.control.alert.application.agent.RcaModelGateway>
                    rcaModelGateway,
            EvidenceRepository evidenceRepository,
            com.objwww.pr.control.alert.domain.repository.PrimaryCheckpointRepository
                    primaryCheckpointRepository,
            DeterministicSupervisor am4DeterministicSupervisor,
            org.springframework.beans.factory.ObjectProvider<
                    com.objwww.pr.control.alert.application.agent.ContextAssembler>
                    am4ContextAssembler,
            org.springframework.beans.factory.ObjectProvider<
                    com.objwww.pr.control.alert.application.agent.BoundedLlmRoleRunner.PrimaryToolPort>
                    primaryToolPort,
            com.objwww.pr.control.alert.domain.repository.ContextSummaryPort
                    contextSummaryPort,
            com.objwww.pr.control.alert.domain.repository.CompactionAttemptPort
                    compactionAttemptPort,
            @Value("${app.alert.r7.compaction.enabled:false}") boolean compactionEnabled,
            @Value("${app.alert.r7.compaction.mode:}") String compactionMode,
            @Value("${app.alert.r7.compaction.soft-threshold:0.7}")
            double compactionSoftThreshold,
            @Value("${app.alert.r7.compaction.target-ratio:0.55}")
            double compactionTargetRatio,
            @Value("${app.alert.r7.compaction.max-per-run:2}") int compactionMaxPerRun,
            @Value("${app.alert.r7.max-input-tokens:24000}") int compactionMaxInputTokens,
            @Value("${app.alert.r7.primary.step-max-tokens:1000}") int stepMaxTokens,
            com.objwww.pr.control.release.domain.repository.ReleaseAssetRepository
                    releaseAssetRepository,
            org.springframework.beans.factory.ObjectProvider<
                    com.objwww.pr.control.alert.application.agent
                            .PrimaryCheckpointCommitService> checkpointCommitFenceProvider,
            com.objwww.pr.control.alert.application.agent.RoleLoopGuard am4RoleLoopGuard,
            org.springframework.beans.factory.ObjectProvider<
                    com.objwww.pr.control.alert.application.agent.JevEnhancementPort>
                    am4JevEnhancement,
            JdbcClient jdbc,
            ObjectMapper objectMapper) {
        if (!enabled) {
            return null;
        }
        var gateway = java.util.Objects.requireNonNull(rcaModelGateway.getIfAvailable(),
                "RCA 模型网关缺件（主模式必要件）");
        var assembler = java.util.Objects.requireNonNull(am4ContextAssembler.getIfAvailable(),
                "任务信封装配器缺件（主模式必要件，R1）");
        var port = java.util.Objects.requireNonNull(primaryToolPort.getIfAvailable(),
                "主 Agent 取证口缺件（主模式必要件）");
        com.objwww.pr.control.alert.application.agent.RcaActionGuard guard =
                new com.objwww.pr.control.alert.application.agent.RcaActionGuard(
                        rcaRunRepository, rcaTaskRepository, am4AgentRegistry,
                        runBudgetGate, gateway, Clock.systemUTC());
        // CL-07：模式解析——显式 mode 优先；空串回退旧 enabled 布尔语义（兼容存量配置）
        com.objwww.pr.control.alert.application.agent.ContextCompactionService.Mode mode =
                compactionMode == null || compactionMode.isBlank()
                        ? (compactionEnabled
                                ? com.objwww.pr.control.alert.application.agent
                                        .ContextCompactionService.Mode.SHADOW_GENERATE
                                : com.objwww.pr.control.alert.application.agent
                                        .ContextCompactionService.Mode.OFF)
                        : com.objwww.pr.control.alert.application.agent
                                .ContextCompactionService.Mode.valueOf(compactionMode.trim()
                                        .toUpperCase(java.util.Locale.ROOT));
        com.objwww.pr.control.alert.application.agent
                .PrimaryCheckpointCommitService commitFence =
                java.util.Objects.requireNonNull(checkpointCommitFenceProvider.getIfAvailable(),
                        "检查点提交围栏缺件（压缩消费面必要件，CL-01）");
        // CL-07 消费口：经 CL-01 围栏 SUMMARY_CONSUMED 钉 current_summary_id；
        // APPLIED/REPLAYED 均视为已收敛，其余态保留旧指针
        com.objwww.pr.control.alert.application.agent.ContextCompactionService.SummaryConsumer
                summaryConsumer = (runId, taskId, owner, leaseEpoch, configEpoch,
                        expectedRevision, actionKey, summaryId) -> {
            var st = commitFence.commit(
                    new com.objwww.pr.control.alert.application.agent
                            .PrimaryCheckpointCommitService.CommitFence(
                            runId, taskId, owner, leaseEpoch, configEpoch,
                            expectedRevision),
                    actionKey,
                    com.objwww.pr.control.alert.application.agent
                            .PrimaryCheckpointCommitService.CommitMutation.SUMMARY_CONSUMED,
                    cp -> cp.withSummaryConsumed(summaryId, java.time.Instant.now()));
            return st.status() == com.objwww.pr.control.alert.application.agent
                    .PrimaryCheckpointCommitService.CommitStatus.APPLIED
                    || st.status() == com.objwww.pr.control.alert.application.agent
                    .PrimaryCheckpointCommitService.CommitStatus.REPLAYED;
        };
        com.objwww.pr.control.alert.application.agent.ContextCompactionService compaction =
                new com.objwww.pr.control.alert.application.agent.ContextCompactionService(
                        // token 估算 = 输入保守估值 + 输出预留（与网关口径同律）
                        (action, prompt, maxTokens) -> guard.guardedModelCall(action,
                                prompt, maxTokens, (long) prompt.length() / 2 + maxTokens),
                        contextSummaryPort,
                        primaryCheckpointRepository, evidenceRepository, objectMapper,
                        Clock.systemUTC(), mode, compactionSoftThreshold,
                        compactionTargetRatio, compactionMaxPerRun,
                        compactionMaxInputTokens, compactionAttemptPort, summaryConsumer);
        registerCompactionDirectiveAsset(releaseAssetRepository, compaction);
        return new com.objwww.pr.control.alert.application.agent.BoundedLlmRoleRunner(
                guard, am4DeterministicSupervisor, primaryCheckpointRepository,
                evidenceRepository, assembler, port, objectMapper, Clock.systemUTC(),
                compaction,
                java.util.Objects.requireNonNull(
                        checkpointCommitFenceProvider.getIfAvailable(),
                        "检查点提交围栏缺件（CL-01 运行路径必要件）"),
                stepMaxTokens, am4RoleLoopGuard, am4JevEnhancement.getIfAvailable(),
                // ME-T12/D08：压缩消费观测 append 口（V164；写入身份 control_app 主链数据源）
                new com.objwww.pr.control.infrastructure.persistence
                        .PostgresCompactionConsumptionPort(jdbc));
    }

    /**
     * 捎带（EN-02/MC36 资产钉版）：压缩指令模板+策略旋钮登记为 release_asset
     * PROMPT kind（内容寻址幂等）——行级 summary_prompt_digest（V92）之外的资产级
     * 锚，版本中心可见、资格面可引用、热切/回滚按 digest 精确失效。登记失败不阻断
     * 启动（log-warn 留痕，与 registerPromptAssets 同律）。
     */
    private void registerCompactionDirectiveAsset(
            com.objwww.pr.control.release.domain.repository.ReleaseAssetRepository assets,
            com.objwww.pr.control.alert.application.agent.ContextCompactionService
                    compaction) {
        try {
            boolean inserted = assets.insert(
                    com.objwww.pr.control.release.domain.model.ReleaseAsset.of(
                            com.objwww.pr.control.release.domain.model.ReleaseAsset
                                    .KIND_PROMPT,
                            compaction.directiveTemplate(), "am4-config",
                            java.time.Clock.systemUTC().instant()));
            if (!inserted) {
                log.debug("压缩指令资产已登记（幂等重放锚）");
            }
        } catch (RuntimeException e) {
            log.warn("压缩指令资产登记失败（不阻断启动）: {}", e.getMessage());
        }
        // EN-01/03（V98）：确定性裁剪策略独立资产——compactionPromptDigest 与
        // contextPolicyDigest 构成重放解释锚对（"当时模型看到了什么"：第一刀怎么裁
        // + LLM 摘要指令长什么样），双 digest 启动 log 固定。
        try {
            var policy = com.objwww.pr.control.release.domain.model.ReleaseAsset.of(
                    com.objwww.pr.control.release.domain.model.ReleaseAsset
                            .KIND_CONTEXT_POLICY,
                    com.objwww.pr.control.alert.application.agent.ContextAssembler
                            .policyAssetContent(),
                    "am4-config", java.time.Clock.systemUTC().instant());
            assets.insert(policy);
            log.info("上下文策略资产登记（EN-01/03 重放解释锚对）：contextPolicyDigest={} "
                            + "compactionSchemaVersion=v{}",
                    policy.assetDigest().hex(),
                    com.objwww.pr.control.alert.application.agent.ContextCompactionService
                            .SCHEMA_VERSION);
        } catch (RuntimeException e) {
            log.warn("上下文策略资产登记失败（不阻断启动）: {}", e.getMessage());
        }
    }

    // ------------------------------------------------------------------ 内部

    /**
     * 主 Agent delegates 具名清单 → (工具 id, 证据类型, 来源) 三元组（snake 名 ↔
     * dotted id 映射钉在 {@link DirectReadToolCatalog}；证据类型沿"来源面.查询形状"
     * 既有命名法）。BA-171 追加两个 R3 写类审批工具（mutation.* 面——VALIDATE_ONLY
     * 待审批反馈落证据窗，分型投影走未知类型诚实有界投影）。
     */
    private static List<SingleToolEvidenceAgent.ToolSpec> primaryDelegateSpecs() {
        return List.of(
                DirectReadToolCatalog.spec(DirectReadToolCatalog.TOOL_INSTANT,
                        "metrics.instant", "prometheus"),
                DirectReadToolCatalog.spec(DirectReadToolCatalog.TOOL_CATALOG,
                        "metrics.catalog", "prometheus"),
                // 2026-09-12 路径一补位：metric_value 全参数化即时值（曾漏此行——
                // 执行器/schema/allowlist 三面已注而 Agent 装配缺席 → run16/17 主任务
                // 配方第二步 UNKNOWN_TOOL→DEAD 的直接根因）
                DirectReadToolCatalog.spec(DirectReadToolCatalog.TOOL_METRIC_VALUE,
                        "metrics.metric_value", "prometheus"),
                DirectReadToolCatalog.spec(DirectReadToolCatalog.TOOL_LABEL_VALUES,
                        "metrics.label_values", "prometheus"),
                DirectReadToolCatalog.spec(DirectReadToolCatalog.TOOL_RULES,
                        "metrics.rules", "prometheus"),
                DirectReadToolCatalog.spec(DirectReadToolCatalog.TOOL_LOGS_AGGREGATE,
                        "logs.aggregate", "loki"),
                DirectReadToolCatalog.spec(DirectReadToolCatalog.TOOL_CHANGE_DIFF,
                        "change.diff", "change_event"),
                DirectReadToolCatalog.spec(DirectReadToolCatalog.TOOL_DOCKER_PS,
                        "docker.ps", "docker"),
                DirectReadToolCatalog.spec(DirectReadToolCatalog.TOOL_DOCKER_INSPECT,
                        "docker.inspect", "docker"),
                DirectReadToolCatalog.spec(DirectReadToolCatalog.TOOL_ALERT_HISTORY,
                        "alert.history", "alert_event"),
                DirectReadToolCatalog.spec(DirectReadToolCatalog.TOOL_RUNBOOK_CATALOG,
                        "runbook.catalog", "rag"),
                DirectReadToolCatalog.spec(DirectReadToolCatalog.TOOL_RUNBOOK_FETCH,
                        "runbook.reference", "rag"),
                DirectReadToolCatalog.spec(DirectReadToolCatalog.TOOL_RCA_HISTORY,
                        "rca_history.reference", "rca_history"),
                // BA-171 写类审批双工具（R3）：spec 经 DirectReadToolCatalog.spec 通用
                // 三元组铸造（name/version/evidenceType/source 四元与风险无关）
                DirectReadToolCatalog.spec(MutationToolCatalog.TOOL_SERVICE_RESTART,
                        "mutation.intent", "mutation"),
                DirectReadToolCatalog.spec(MutationToolCatalog.TOOL_SERVICE_ROLLBACK,
                        "mutation.intent", "mutation"));
    }
}
