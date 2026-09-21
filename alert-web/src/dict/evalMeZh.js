// M-e 专用字典（安全五态 + 三事实 tally / 逐案行为评测）中文文案。
// 独立模块缘由：zh.js 属其他 agent 在途改动面，本模块以新文件承载 M-e 新增文案，
// 全中文经字典渲染铁律不变（沿 mdZh.js / evalStabilityZh.js 先例）。
// 纪律：
// - 五态 NOT_ASSESSED = 观测证据不足未评，不等于无问题，绝不读作零违规；
// - 三事实（模型尝试 / 控制面拦截 / 实际违规副作用）分列，缺 tally 的旧批如实"旧口径"；
// - 行为六检查裸 reason code 不直接上人读面——就近标注中文成因，码值随行备查。

export const EVAL_ME_SAFETY_ZH = {
  verdictTitle: '五态裁决（通过/不通过/未评/不适用/异常）',
  verdicts: {
    PASS: '通过',
    REJECT: '不通过',
    NOT_ASSESSED: '未评',
    NOT_APPLICABLE: '不适用',
    ERROR: '异常',
  },
  notAssessedNote: '「未评」= 观测证据不足未评，不等于无问题，不得读作零违规。',
  notApplicableNote: '「不适用」= 该轮无注入/无观测义务（非违规判定）。',
  errorNote: '「异常」= 安全观测读取失败，裁决不可得。',
  tallyTitle: '三事实（模型尝试/控制面拦截/实际违规）',
  tally: {
    attempted: '模型尝试违规',
    blocked: '控制面成功拦截',
    executedViolations: '实际违规副作用',
    assessedFaces: '已评面数',
    notAssessedFaces: '未评面数',
    legacy: '旧口径：本批早于三事实计数落档（V158），无逐案计数，不填 0 冒充。',
    partial: '部分案例为旧行无三事实计数，合计只含有计数案例。',
  },
}

/** 安全五态 → 中文（未知值原样透传，不冒充已知枚举） */
export function safetyVerdictZh(verdict) {
  return EVAL_ME_SAFETY_ZH.verdicts[verdict] ?? verdict ?? '未统计'
}

export const EVAL_ME_BEHAVIOR_ZH = {
  title: '证据行为（M-e）',
  source: '来源 GET /eval/runs/{runId}/behavior（eval_case_behavior 落档；缺席=未评如实，不等于零问题）',
  assessed: '已评案例',
  graderVersion: '评分器版本',
  coverageTitle: '检查点覆盖（双轨）',
  coverageText: '文本覆盖（旧口径·报告子串）',
  coverageEvidence: '证据覆盖（新口径·实际语料）',
  coverageEvidenceNote: '证据覆盖分母只含有检查点且被评的案例，未评案例不进分母。',
  checksTitle: '六项检查',
  metricsTitle: '指标（分子/分母）',
  failureLabelsTitle: '失败标签分布',
  evidenceRefsTitle: '本案已归属证据引用',
  traceDigest: '轨迹摘要',
  noData: '未评',
  checkNames: {
    citation_attachment: '引用附带',
    citation_existence: '引用存在',
    citation_attribution: '引用归属',
    citation_time_window: '时窗有效',
    citation_support: '支持/反驳',
    evidence_checkpoint_coverage: '证据检查点覆盖',
  },
  // 六检查展示序（与 BehaviorEvaluator 评定序一致；聚合响应按名排序，展示按本序）
  checkOrder: [
    'citation_attachment',
    'citation_existence',
    'citation_attribution',
    'citation_time_window',
    'citation_support',
    'evidence_checkpoint_coverage',
  ],
  metricNames: {
    citation_attachment_rate: '引用附带率',
    citation_existence_rate: '引用存在率',
    citation_attribution_rate: '引用归属率',
    citation_time_window_rate: '时窗有效率',
    evidence_checkpoint_coverage: '证据检查点覆盖率',
  },
  // 裸 reason code → 人话成因（behavior-v1 词表；码值随行备查，未登录码原样透传）
  reasons: {
    NO_REPORT: '无评分报告，无可评对象',
    UNRESOLVED_SENTINEL: '根因未定（unresolved 哨兵），不作引用评判',
    NO_TRUE_ROOT_CAUSE_CLAIM: '无确认成立的根因结论',
    ATTACHMENT_FULL: '根因结论全部附带引用',
    CITATION_MISSING: '有根因结论缺少证据引用',
    NO_CITATIONS: '报告无任何引用可评',
    EVIDENCE_READ_UNAVAILABLE: '证据读面不可用，引用无法核验',
    CITATIONS_RESOLVED: '引用全部解析到真实证据',
    CITATION_DANGLING: '存在悬空引用（未解析到任何证据行）',
    NO_RESOLVED_CITATION: '无可解析引用',
    NO_RUN_CONTEXT: '无调查运行上下文，归属不可判',
    SAME_RUN: '引用均属本次调查产出',
    CITATION_CROSS_RUN: '存在跨调查引用（归属不符）',
    NO_ATTRIBUTED_CITATION: '无已归属引用',
    RUN_WINDOW_UNKNOWN: '调查时间窗未知，时窗不可判',
    IN_WINDOW: '证据时窗均落在调查窗口内',
    TIME_WINDOW_VIOLATION: '存在时窗外的证据引用',
    EVIDENCE_TIME_BOUNDS_MISSING: '证据时间界全部缺失，时窗不可评',
    NO_USABLE_EVIDENCE: '无可用证据（归属失败引用不进可用集）',
    CONTENT_UNAVAILABLE: '证据正文不可得（digest-only/截断），不猜支持性',
    SUPPORT_CONTRADICTED: '存在反驳性证据（v1 词法启发式判定）',
    SUPPORT_TOKEN_MATCH: '证据词法命中支持（v1 启发式，非语义判定）',
    SUPPORT_UNDETERMINED: '支持性未定（词法未命中，不猜）',
    NO_CHECKPOINTS: '本案无期望证据检查点',
    TRACE_MISSING: '调查轨迹缺失，覆盖不可评',
    EVIDENCE_CORPUS_UNAVAILABLE: '证据语料不可得，覆盖不可评',
    NO_RUN_EVIDENCE: '本次调查零证据产出（真实零取证）',
    EVIDENCE_COVERAGE_FULL: '检查点全部有证据覆盖',
    CORPUS_TRUNCATED: '语料截断，未命中检查点或在截断段（不猜）',
    CHECKPOINT_EVIDENCE_MISSING: '检查点证据缺失',
    TRACE_READ_ERROR: '观测读取失败',
  },
}

/** 检查/指标/原因名 → 中文（未登录原样透传，不冒充已知枚举） */
export function behaviorCheckZh(name) {
  return EVAL_ME_BEHAVIOR_ZH.checkNames[name] ?? name ?? '未统计'
}

export function behaviorMetricZh(name) {
  return EVAL_ME_BEHAVIOR_ZH.metricNames[name] ?? name ?? '未统计'
}

export function behaviorReasonZh(code) {
  return EVAL_ME_BEHAVIOR_ZH.reasons[code] ?? code ?? '未统计'
}

/** 行为检查五态 → 中文（与安全五态同词表纪律；FAIL=不通过） */
export function behaviorStatusZh(status) {
  return {
    PASS: '通过',
    FAIL: '不通过',
    NOT_ASSESSED: '未评',
    NOT_APPLICABLE: '不适用',
    ERROR: '异常',
  }[status] ?? status ?? '未统计'
}

/** 五态 → el-tag 类型（未评/不适用弱化不冒充；异常警示） */
export function behaviorStatusTagType(status) {
  return {
    PASS: 'success',
    FAIL: 'danger',
    NOT_ASSESSED: 'info',
    NOT_APPLICABLE: 'info',
    ERROR: 'warning',
  }[status] ?? 'info'
}

// ---------------------------------------------------------------- ME-T12a 死循环观测

export const EVAL_ME_LOOP_ZH = {
  title: '死循环观测（M-e）',
  source: '来源 GET /eval/runs/{runId}/loop（eval_case_loop 落档；缺席=未评如实，不等于无循环）',
  assessed: '已评案例',
  graderVersion: '评分器版本',
  stopReasonsTitle: '终态分布',
  stopReasons: {
    LOOP_NO_PROGRESS: '循环检出停',
    BUDGET_EXHAUSTED: '预算兜底停（不冒充循环检出）',
    COMPLETED: '正常完成',
    NONE: '无终态（观测异常/无可评轨迹）',
  },
  checksTitle: '五项检查',
  metricsTitle: '指标（分子/分母）',
  failureLabelsTitle: '失败标签分布',
  notAssessedNote: '「未评」= 环境真值缺席，检出率/误报率不出数，不读作无循环。',
  checkNames: {
    loop_detection: '循环检出',
    loop_false_positive: '误报守卫',
    safe_stop: '安全停止',
    post_stop_new_actions: '停止后静默',
    normal_task_completion: '正常完成',
  },
  // 五检查展示序（与 LoopTraceEvaluator 评定序一致）
  checkOrder: [
    'loop_detection',
    'loop_false_positive',
    'safe_stop',
    'post_stop_new_actions',
    'normal_task_completion',
  ],
  metricNames: {
    loop_detection_rate: '检出率',
    loop_false_positive_rate: '误报率',
    wasted_extra_steps: '浪费步数',
    safe_stop_rate: '安全停止率',
    post_stop_new_actions: '停止后新动作',
    wasted_cost_physical_calls: '浪费物理调用',
    wasted_cost_tokens: '浪费 token',
    normal_task_success_rate: '正常完成率',
  },
  // 案例抽屉标量行（null 显「—」不猜）
  scalars: {
    stopReason: '停止原因',
    detectionEventIndex: '检出事件位序',
    firstNoProgressEventIndex: '首个无进展事件位序',
    postStopNewActions: '停止后新动作数',
    physicalCallsFromOnset: '起点后物理调用',
    tokensFromOnset: '起点后 token',
    secondsFromOnset: '起点后秒数',
  },
  reasons: {
    NO_EVENTS: '无可评轨迹事件',
    LOOP_CASE: '标注循环案例（有检出义务）',
    DETECTED_IN_WINDOW: '配置窗内检出',
    LOOP_NOT_DETECTED: '配置窗内未检出',
    NORMAL_CONTROL: '正常对照案例',
    FALSE_POSITIVE: '正常对照被误判为循环',
    NO_FALSE_POSITIVE: '无误报',
    SAFE_STOP_ON_DETECTION: '检出后安全停止',
    SAFE_STOP_ON_BUDGET: '预算兜底停（算安全停止，不算检出）',
    LOOP_UNSTOPPED: '硬预算内未停止',
    NO_STOP: '无停止事件',
    POST_STOP_QUIET: '停止后零新动作',
    POST_STOP_ACTION: '停止后仍有新动作',
    NORMAL_COMPLETED: '正常完成',
    NORMAL_DISRUPTED: '正常任务被中断',
    TRACE_MISSING: '调查轨迹缺失',
    TRACE_READ_ERROR: '观测读取失败',
  },
  failureLabelNames: {
    TRACE_NO_EVENTS: '无轨迹事件',
    LOOP_NOT_DETECTED: '循环未检出',
    LOOP_FALSE_POSITIVE: '误报',
    LOOP_UNSTOPPED: '硬预算内未停止',
    POST_STOP_ACTION: '停止后新动作',
    NORMAL_TASK_DISRUPTED: '正常任务中断',
    TRACE_READ_ERROR: '观测读取失败',
  },
}

export function loopCheckZh(name) {
  return EVAL_ME_LOOP_ZH.checkNames[name] ?? name ?? '未统计'
}

export function loopMetricZh(name) {
  return EVAL_ME_LOOP_ZH.metricNames[name] ?? name ?? '未统计'
}

export function loopReasonZh(code) {
  return EVAL_ME_LOOP_ZH.reasons[code] ?? code ?? '未统计'
}

export function loopStopReasonZh(reason) {
  return EVAL_ME_LOOP_ZH.stopReasons[reason] ?? reason ?? '未统计'
}

export function loopFailureLabelZh(label) {
  return EVAL_ME_LOOP_ZH.failureLabelNames[label] ?? label ?? '未统计'
}

// ---------------------------------------------------------------- ME-T12a 多 Agent 协作

export const EVAL_ME_COLLAB_ZH = {
  title: '多 Agent 协作（M-e）',
  source: '来源 GET /eval/runs/{runId}/collab（eval_case_collab 落档；缺席=未评如实，不等于零问题）',
  assessed: '已评案例',
  graderVersion: '评分器版本',
  scalars: {
    edgeCount: '交接边数',
    admittedCount: '准入回执边数',
    tokenCostTotal: '角色 token 成本合计',
  },
  checksTitle: '十三项检查',
  metricsTitle: '指标（分子/分母）',
  failureLabelsTitle: '失败标签分布',
  suspectedTitle: '疑似归因（无干预对照不定谳）',
  supportedTitle: '实证归因（重放改善因果）',
  zeroHandoffsNote: '「不适用」= 本 run 无子 Agent 交接边（非违规判定）。',
  notAssessedNote: '「未评」= 生产投影未观测该面，不等于无协作问题。',
  checkNames: {
    delegation_necessity_choice: '委派必要性',
    cross_modal_support: '跨模态支持',
    conflict_resolution: '冲突处置',
    handoff_fact_constraint_retention: '交接保留率',
    bounded_degradation: '有界降级',
    receipt_consumption_idempotency: '回执幂等',
    cancellation_fence: '取消围栏',
    duplicate_evidence_accounting: '重复取证',
    evidence_consumption: '证据消费',
    error_propagation_containment: '错误遏制',
    arm_comparability: '臂可比性',
    ablation_single_factor: '消融单因子',
    intervention_replay_attribution: '干预重放归因',
  },
  checkOrder: [
    'delegation_necessity_choice',
    'cross_modal_support',
    'conflict_resolution',
    'handoff_fact_constraint_retention',
    'bounded_degradation',
    'receipt_consumption_idempotency',
    'cancellation_fence',
    'duplicate_evidence_accounting',
    'evidence_consumption',
    'error_propagation_containment',
    'arm_comparability',
    'ablation_single_factor',
    'intervention_replay_attribution',
  ],
  metricNames: {
    delegation_choice_correct: '委派选择正确',
    handoff_fact_retention: '交接事实保留',
    evidence_consumption_rate: '证据消费率',
    conflict_resolution_correct: '冲突处置正确',
    error_propagation: '错误传播',
    duplicate_physical_fetches: '重复物理取证',
    role_token_cost: '角色 token 成本',
  },
  // MAST 五标签中文名（机制码随行原样备查）
  mastLabels: {
    MAST_INFORMATION_LOSS: '信息遗漏',
    MAST_IGNORED_PEER: '忽略同伴',
    MAST_REPETITION: '重复',
    MAST_VERIFICATION_INADEQUATE: '验证不足',
    MAST_TASK_DERAIL: '任务偏离',
  },
  reasons: {
    TRACE_MISSING: '调查轨迹缺失，整面未评',
    NEED_LABEL_MISSING: '无评分侧必要性真值标注，不猜',
    CHOICE_APPROPRIATE: '委派选择与必要性标注一致',
    COLLABORATION_OMITTED: '必要协作被省略',
    UNNECESSARY_DELEGATION: '不必要的委派',
    SINGLE_MODAL: '单模态场景（无跨模态面）',
    ROLE_UNATTRIBUTABLE: '支持证据无法归因到角色',
    CROSS_MODAL_SUPPORT: '跨模态证据相互支持',
    NO_CROSS_MODAL_SUPPORT: '跨模态支持缺失',
    NO_CONFLICT: '无角色间冲突',
    CONFLICT_GROUNDED: '冲突按证据处置',
    CONFLICT_BY_VOTE_OR_CONFIDENCE: '冲突按多数票/置信措辞处置（非证据）',
    CONFLICT_NO_BASIS: '冲突处置无依据',
    CONFLICT_WRONG_RESOLUTION: '冲突处置错误',
    NO_HANDOFF: '本 run 无交接边（非违规判定）',
    HANDOFF_CONTENT_UNOBSERVED: '交接内容生产未观测，不猜',
    HANDOFF_FACTS_PRESERVED: '交接事实/约束全部保留',
    HANDOFF_FACT_MISSING: '交接事实丢失',
    NO_DEGRADED_EDGE: '无降级边',
    FABRICATION_UNOBSERVED: '编造面未观测，不猜',
    BOUNDED_DEGRADATION: '降级有界（未编造子结论）',
    FABRICATED_CHILD_CONCLUSION: '编造子任务结论',
    NO_RECEIPT: '无回执面',
    RECEIPT_CONSUMPTION_UNOBSERVED: '回执消费面未观测，不猜',
    RECEIPT_CONSUMED_ONCE: '回执恰消费一次',
    RECEIPT_DOUBLE_CONSUMPTION: '回执重复消费',
    LATE_RECEIPT_CONSUMED: '晚到回执被消费',
    BUDGET_DOUBLE_SETTLED: '预算重复结算',
    NOT_CANCELLED: '主任务未取消',
    CANCEL_FENCED: '取消后无新派发/新归并',
    DISPATCH_AFTER_CANCEL: '取消后仍新派发',
    LATE_RESULT_REVIVED: '晚到结果被归并复活',
    IN_FLIGHT_COST_UNSETTLED: '在途成本未结清',
    NO_SHARED_EVIDENCE: '无共享证据面',
    INDEPENDENCE_CLAIM_UNOBSERVED: '独立性声明未观测，不猜',
    DUPLICATE_COUNTED_AS_REUSE: '重复取证按复用计（未二次验证冒充）',
    SHARED_EVIDENCE_DOUBLE_VERIFICATION: '共享证据被冒充独立验证',
    APPLICABILITY_UNOBSERVED: '适用性面未观测，不猜',
    NO_APPLICABLE_EVIDENCE: '无适用证据',
    APPLICABLE_EVIDENCE_CONSUMED: '适用证据被消费',
    APPLICABLE_EVIDENCE_IGNORED: '适用证据被忽略',
    NO_INJECTED_ERROR: '无注入错误面',
    ERROR_CONTAINED: '错误被遏制未扩散',
    ERROR_PROPAGATED: '子任务错误扩散入主结论',
    SINGLE_ARM: '单臂运行（无可比面）',
    ARMS_COMPARABLE: '臂间可比',
    ARM_INCOMPARABLE: '臂间不可比',
    NO_ABLATION: '无消融面',
    SINGLE_FACTOR_ABLATION: '单因子消融',
    MULTI_FACTOR_ABLATION: '多因子混淆消融',
    NO_INTERVENTION_CONTROL: '无干预对照',
    REPLAY_IMPROVED_AFTER_RECEIPT_FIX: '替换错误回执后重放改善（实证归因）',
    REPLAY_NOT_IMPROVED: '重放未改善',
    TRACE_READ_ERROR: '观测读取失败',
  },
}

export function collabCheckZh(name) {
  return EVAL_ME_COLLAB_ZH.checkNames[name] ?? name ?? '未统计'
}

export function collabMetricZh(name) {
  return EVAL_ME_COLLAB_ZH.metricNames[name] ?? name ?? '未统计'
}

export function collabReasonZh(code) {
  return EVAL_ME_COLLAB_ZH.reasons[code] ?? code ?? '未统计'
}

/** 失败标签 → 中文：MAST_* 五标签走专名，机制码原样透传备查 */
export function collabFailureLabelZh(label) {
  return EVAL_ME_COLLAB_ZH.mastLabels[label] ?? label ?? '未统计'
}

// ---------------------------------------------------------------- ME-T12a 上下文漂移观测

export const EVAL_ME_DRIFT_ZH = {
  title: '上下文漂移观测（M-e）',
  source: '来源 GET /eval/runs/{runId}/drift（eval_case_drift 落档；缺席=未评如实，不等于零漂移）',
  assessed: '已评案例',
  graderVersion: '评分器版本',
  withSummary: '有压缩摘要案例',
  checksTitle: '六项检查',
  metricsTitle: '指标（分子/分母）',
  failureLabelsTitle: '失败标签分布',
  deferredTitle: '如实挂起（涉真实模型指标，本版不出数）',
  consumptionTitle: '消费观测（四件）',
  consumption: {
    observed: '有消费观测案例',
    mode: '运行模式',
    summaryCommitted: '摘要已提交',
    consumerInvoked: '消费口已调用',
    consumed: '消费生效',
    policyDigest: '消费策略锚',
  },
  noSummaryNote: '「不适用」= 本案无压缩事件（当前默认 OFF），非零漂移结论。',
  notAssessedNote: '「未评」= 下一步行为面未观测，不等于无漂移。',
  summaryDigest: '摘要指纹',
  checkNames: {
    key_fact_retention: '关键事实保留',
    counter_evidence_retention: '反证保留',
    fact_distortion: '事实扭曲',
    constraint_compliance: '约束遵守',
    evidence_update_correctness: '证据更新',
    re_error: '重新犯错',
  },
  checkOrder: [
    'key_fact_retention',
    'counter_evidence_retention',
    'fact_distortion',
    'constraint_compliance',
    'evidence_update_correctness',
    're_error',
  ],
  metricNames: {
    key_fact_retention_rate: '关键事实保留率',
    counter_evidence_retention_rate: '反证保留率',
    fact_distortion_rate: '事实扭曲率',
    constraint_compliance_rate: '约束遵守率',
    evidence_update_correct_rate: '证据更新正确率',
    re_error_rate: '重新犯错率',
  },
  reasons: {
    NO_SUMMARY: '无压缩摘要（忠实性面无对象）',
    NO_REQUIRED_FACTS: '无必需保留事实（评分侧真值缺席）',
    SUMMARY_EMPTY: '摘要为空（语义不足必判不通过）',
    FACTS_RETAINED: '事实全部保留',
    FACT_MISSING: '事实缺失',
    FACT_WRONG: '事实错写',
    NO_COUNTER_EVIDENCE: '无关键反证',
    COUNTER_EVIDENCE_KEPT: '反证保留',
    COUNTER_EVIDENCE_LOST: '反证丢失',
    NO_DISTORTION_ASSERTED: '未检出扭曲断言',
    DISTORTION_ASSERTED: '检出扭曲断言（冒号后缀为扭曲分型）',
    BEHAVIOR_UNOBSERVED: '下一步行为未观测，不猜',
    NO_CONSTRAINTS: '无任务约束',
    CONSTRAINTS_HELD: '约束全部遵守',
    CONSTRAINT_VIOLATED: '约束违例（冒号后缀为违例码）',
    NO_SUPERSESSION: '无信念修订面',
    BELIEF_UPDATED: '证据更新正确',
    STALE_BELIEF_KEPT: '旧信念滞留',
    NO_EXCLUDED_FACTS: '无被排除事实',
    NO_RE_ERROR: '无重新犯错',
    REJECTED_FACT_RECONFIRMED: '已排除事实被重新确认',
    TRACE_READ_ERROR: '观测读取失败',
  },
}

export function driftCheckZh(name) {
  return EVAL_ME_DRIFT_ZH.checkNames[name] ?? name ?? '未统计'
}

export function driftMetricZh(name) {
  return EVAL_ME_DRIFT_ZH.metricNames[name] ?? name ?? '未统计'
}

/** 裸 reason code → 人话：带冒号后缀的复合码（如 CONSTRAINT_VIOLATED:X,Y）按主码翻译、后缀随行备查 */
export function driftReasonZh(code) {
  if (code == null) return '未统计'
  const head = String(code).split(':')[0]
  return EVAL_ME_DRIFT_ZH.reasons[code] ?? EVAL_ME_DRIFT_ZH.reasons[head] ?? code
}

// ---------------------------------------------------------------- ME-T12b 发布验收四查

export const EVAL_ME_ACCEPTANCE_ZH = {
  title: '发布验收（四查）',
  source: '来源 GET /api/eval/compare 响应 releaseAcceptance（ReleaseAcceptanceEvaluator 四面合成；流程状态与质量结论始终分展）',
  qualificationTitle: '发布资格',
  qualifications: {
    QUALIFIED: '通过（可发布）',
    NOT_QUALIFIED: '不通过',
    INCONCLUSIVE: '证据不足，不下结论',
  },
  pipelineState: '流程状态',
  pipelineStates: {
    SUCCEEDED: '跑完',
    FAILED: '未跑完/失败',
  },
  pipelineNote: '流程跑完 ≠ 质量通过，两面始终分展。',
  facesTitle: '四面结论',
  faces: {
    quality: '质量',
    safety: '安全',
    behavior: '行为',
    evidence: '证据完整性',
  },
  verdicts: {
    PASS: '通过',
    FAIL: '不通过',
    INCONCLUSIVE: '证据不足，不下结论',
  },
  reasonsTitle: '判定原因（机器码随行备查）',
  reasons: {
    QUALITY_GATE_FAILED: '对比质量门未通过',
    KEY_STRATUM_REGRESSED: '关键分层退化（不被总体均分掩盖）',
    KEY_STRATUM_UNCOVERED: '关键分层未覆盖',
    SMALL_SAMPLE: '独立簇数低于预登记下限（小样本）',
    SAFETY_REJECTED: '存在确证安全违规',
    SAFETY_NOT_ASSESSED: '存在安全未评案例（缺证据≠零违规）',
    BEHAVIOR_SUITE_FAILED: '行为套件检查不通过',
    BEHAVIOR_COVERAGE_INCOMPLETE: '行为案例覆盖不完整',
    PLAN_COVERAGE_INCOMPLETE: '计划案例覆盖不完整（未执行/环境失败在计划内）',
    PIPELINE_NOT_SUCCEEDED: '流程未跑完（SUCCEEDED 与质量通过分展）',
    PREREGISTRATION_MISSING: '预登记缺席，簇数锚不可得，不猜阈值',
    GATE_INCONCLUSIVE: '对比门未出通过/不通过确证结论，质量面不冒充失败也不冒充通过',
  },
  preregTitle: '预登记锚（V165，跑批前冻结）',
  minClusters: '独立簇数下限',
  preregDigest: '登记摘要',
  preregMissingWarn: '本批无预登记落档（V165 eval_preregistration 无行）：簇数判定锚不可得，质量面如实「证据不足」，不猜阈值。',
}

/** 发布资格/面结论/流程状态 → 中文（未登录原样透传，不冒充已知枚举） */
export function acceptanceQualificationZh(q) {
  return EVAL_ME_ACCEPTANCE_ZH.qualifications[q] ?? q ?? '未统计'
}

export function acceptanceVerdictZh(v) {
  return EVAL_ME_ACCEPTANCE_ZH.verdicts[v] ?? v ?? '未统计'
}

export function acceptancePipelineStateZh(s) {
  return EVAL_ME_ACCEPTANCE_ZH.pipelineStates[s] ?? s ?? '未统计'
}

export function acceptanceReasonZh(code) {
  return EVAL_ME_ACCEPTANCE_ZH.reasons[code] ?? code ?? '未统计'
}

/** 面结论/资格 → el-tag 类型（INCONCLUSIVE 弱化不冒充通过） */
export function acceptanceVerdictTagType(v) {
  return {
    PASS: 'success',
    QUALIFIED: 'success',
    FAIL: 'danger',
    NOT_QUALIFIED: 'danger',
    INCONCLUSIVE: 'info',
  }[v] ?? 'info'
}
