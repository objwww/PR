// 全站中文词典（前端产品化铁律 3：零裸枚举上屏）
// 后端枚举一律经此映射为中文后再渲染；未收录的枚举原样兜底并便于补录。

/** 调查（rca_run.state） */
export const RUN_STATE = {
  QUEUED: '排队中',
  RUNNING: '调查中',
  REPORTING: '报告组装中',
  SUCCEEDED: '已完成',
  PARTIAL: '部分成功',
  FAILED: '失败',
}

/** 事故等待原因（incident.waiting_reason） */
export const WAITING_REASON = {
  WAITING_CAPABILITY: '等待路由放量',
  DEFERRED: '背压暂扣',
}

/** 处置操作状态（rca_operation.status） */
export const OP_STATE = {
  PLANNED: '已计划',
  DISPATCHED: '已派发',
  ACKNOWLEDGED: '已确认',
  VERIFIED: '已验证',
  COMPLETED: '已完成',
  UNKNOWN: '结果未知',
  RECONCILING: '对账中',
  ESCALATED: '已升级人工',
  FAILED_CONFIRMED: '人工确认失败',
  CANCELLED_BEFORE_DISPATCH: '派发前取消',
}

/** 资源锁状态（resource_mutation_lock.state） */
export const LOCK_STATE = {
  HELD: '锁持有中',
  ORPHANED: '锁已孤儿化',
}

/** 操作风险级（action_intent.risk） */
export const RISK = {
  R1: '常规',
  R2: '低危写操作',
  R3: '高危双人',
}

/** 隔离原因（alert_inbox.last_error.reason） */
export const INBOX_STATE = {
  RECEIVED: '已接收',
  PROCESSING: '处理中',
  PROCESSED: '已处理',
  RETRY_WAIT: '等待重试',
  QUARANTINED: '已隔离',
  DEAD_LETTER: '投递终败',
  IGNORED: '已忽略',
}

/**
 * 告警行「AI 处理状态」推导（前端产品化波次1）：
 * runState / waitingReason / 兜底"未发起"三段判断——
 * 与路由纪律一致：未命中白名单的告警停留在"等待路由放量"。
 */
export function aiStatusOf(row) {
  if (row.runState) {
    if (row.runState === 'SUCCEEDED' || row.runState === 'PARTIAL') return '已调查'
    if (row.runState === 'FAILED') return '调查失败'
    if (RUN_STATE[row.runState]) return RUN_STATE[row.runState]
    return '调查中'
  }
  if (row.waitingReason) return WAITING_REASON[row.waitingReason] || row.waitingReason
  return '未发起'
}

/** 状态 → 徽章类型（element-plus tag type） */
export function aiStatusTagType(status) {
  if (status === '已调查') return 'success'
  if (status === '调查中' || status === '报告组装中' || status === '排队中') return 'primary'
  if (status === '等待路由放量' || status === '背压暂扣' || status === '未发起') return 'info'
  if (status === '调查失败') return 'danger'
  return 'warning'
}

/** 调用链 span 类型（前端产品化 3.17 Trace 瀑布页签） */
export const SPAN_KIND = {
  task: '任务尝试',
  model: '模型调用',
  tool: '工具调用',
}

/** 任务尝试状态（rca_attempt.status 六态） */
export const ATTEMPT_STATE = {
  STARTED: '进行中',
  SUCCEEDED: '成功',
  FAILED_RETRYABLE: '失败·可重试',
  FAILED_TERMINAL: '失败·终态',
  ABANDONED: '已放弃',
  STALE: '已失效',
}

/** 账本调用状态（rca_model_call.state / rca_tool_invocation.state 四态） */
export const LEDGER_STATE = {
  PENDING: '待回执',
  SUCCESS: '成功',
  FAILED: '失败',
  UNKNOWN: '结果未知',
}

/** span 状态 → 徽章类型（element-plus tag type） */
export function spanStateTagType(state) {
  if (state === 'SUCCEEDED' || state === 'SUCCESS') return 'success'
  if (state === 'FAILED_RETRYABLE' || state === 'FAILED_TERMINAL' || state === 'FAILED') return 'danger'
  if (state === 'UNKNOWN') return 'warning'
  return 'info'
}

/** 诊断问答类型（diag_session.question_key；'FREE'=自由问，其余=快捷问） */
export const DIAG_TYPE = {
  FREE: '自由问',
}

/** 诊断问答拒绝原因（/diag 端点 reason 字段；MODEL_CALL_FAILED:* 前端拼接错误码） */
export const DIAG_REJECT = {
  NO_RUN_TO_ANCHOR: '该事件尚无调查记录，自由问暂不可用——先完成一次调查以建立账本锚',
  QUESTION_REQUIRED: '问题不能为空',
  QUESTION_TOO_LONG: '问题超过 500 字上限',
  UNKNOWN_QUESTION_KEY: '未知的问题词',
  DB_FACE_NOT_ASSEMBLED: '服务端数据面未装配',
  GATEWAY_NOT_ASSEMBLED: '服务端模型网关未装配',
}

/** 告警分类（incident.category，V82 词表） */
export const CATEGORY_ZH = {
  BUSINESS: '业务',
  APPLICATION: '应用',
  DEPENDENCY: '依赖',
  INFRA: '基础设施',
  NETWORK: '网络',
  DATA: '数据',
  SECURITY: '安全',
  PLATFORM: '控制面',
  UNCLASSIFIED: '未分类',
}

/** 分类来源（incident.category_source 生成列） */
export const CATEGORY_SOURCE = {
  RULE: '规则判定',
  OVERRIDE: '人工修正',
}

/** 评测实验治理标签（eval_run.governance_tag，3.11 废批治理） */
export const GOV_TAG_ZH = {
  VALID: '有效',
  SCRAP_TIMEOUT: '废批·超时',
  SCRAP_OTHER: '废批·其他',
  ARCHIVED: '归档',
}

/** 评测数据集分层（eval_dataset_tier.tier，对标分层评测纪律） */
export const TIER_ZH = {
  SMOKE: '冒烟',
  REGRESSION: '回归',
  EXPLORE: '探索',
  REDTEAM: '红队',
}

/** 风险审计事件类型（/agent-ops/risk-events，3.10 Wave4） */
export const RISK_KIND_ZH = {
  GUARDIAN: 'Guardian 复核',
  APPROVAL_REJECTED: '审批拒绝',
  QUARANTINE: '隔离命中',
  DEAD_LETTER: '死信',
}

/** 合并时间轴事件类型（/incidents/{id}/timeline-merged，3.3 补齐） */
export const TIMELINE_KIND_ZH = {
  ALERT_FIRING: '告警触发',
  ALERT_RESOLVED: '告警恢复',
  CHANGE: '变更',
  DRILL: '演练',
}

/** canary 路由决策类型（canary_route_decision.decision，调查路由可视化） */
export const ROUTE_DECISION_ZH = {
  BUCKETED_NATIVE: '桶内·原生执行',
  BUCKETED_HOLMES: '桶内·降级观测',
  WHITELISTED: '白名单直通',
  CANARY_DISABLED: '放量未开启',
}

/** 告警等待原因（incident.waiting_reason） */
export const WAITING_REASON_ZH = {
  WAITING_CAPABILITY: '等待路由放量',
  DEFERRED: '背压暂扣',
}

/**
 * 模型调用/调查失败原因码 → 中文说明 + 处置建议（RCA 故障分类 §4.2 全族 +
 * 确定性未决族）。value = [短名, 说明与建议]。页面只读本字典，不各自硬编码。
 */
export const ERROR_CODE_ZH = {
  REQUEST_INVALID: ['请求无效', '模型服务端拒绝了请求（参数或模型名不被接受）。请检查模型配置与价目/路由设置'],
  BILLING_OR_ACTIVATION: ['账户欠费或未开通', '模型服务商账户余额不足或能力未开通（HTTP 402/欠费码）。请前往服务商控制台充值或开通后重试，系统会自动恢复'],
  AUTH_DENIED: ['凭证被拒', 'API 密钥无效或已吊销（HTTP 401）。请更新密钥配置'],
  QUOTA_EXHAUSTED: ['配额耗尽', '账户配额或免费额度用尽（HTTP 429 配额族）。请升配或等待配额重置'],
  RATE_LIMIT: ['限流', '触发服务商限流（HTTP 429），系统已按退避策略自动重试'],
  TIMEOUT: ['调用超时', '模型响应超时。若为持续性超时请检查服务商状态'],
  SERVER_ERROR: ['服务商故障', '模型服务端 5xx 故障，系统已自动重试；持续出现请查看服务商状态页'],
  PROTOCOL_ERROR: ['协议错误', '响应协议异常（非预期报文），请保留现场联系开发'],
  OUTPUT_BUDGET_EXHAUSTED: ['输出预算耗尽', '输出 token 预算被推理段吃光导致正文为空。已修预算配置，新调用不再出现'],
  BUDGET_EXHAUSTED: ['调用预算耗尽', '本次调查的调用次数/token 预算用尽，调查按预算闸终止'],
  DEADLINE_EXCEEDED: ['调查超时', '调查总时限耗尽，按截止闸终止'],
  NO_CONFIRMED_ROOT_CAUSE: ['未确认根因', '调查未取到足够证据确认根因（双源佐证未满），系统如实给出未决结论而非猜测。可重新排查或人工介入'],
  MODEL_FAILURE_REQUEST_INVALID: ['模型调用失败', '主任务模型调用连续失败（详见调用链错误码），系统按零模型确定性收尾'],
}
export function errorCodeZh(code) {
  if (!code) return ''
  const hit = ERROR_CODE_ZH[code]
  if (hit) return `${hit[0]}：${hit[1]}`
  if (code.startsWith('UNKNOWN_ERROR')) return `未知错误：${code}`
  return code
}

/** 变更动作 action_id → 中文名 + 说明（审批处置页审批对象标题） */
export const ACTION_ID_ZH = {
  'chaos.resolve': ['故障恢复', '对故障演练注入的故障执行恢复动作，将目标对象恢复到正常状态'],
  'scale.database': ['数据库扩容', '对目标数据库实例执行扩容（连接池/规格），影响数据面可用性'],
  'scale.service': ['服务扩容', '对目标服务执行副本/规格扩容，影响业务面容量'],
  'service.restart': ['重启服务', 'AI 调查建议重启目标服务以恢复服务。R3 高危写操作：调用不直接执行，需两名审批人批准后由系统进入执行计划（无 unlock 白名单时为模拟执行）'],
  'service.rollback': ['回滚服务', 'AI 调查建议将目标服务回滚到上一版本。R3 高危写操作：调用不直接执行，需两名审批人批准后由系统进入执行计划（无 unlock 白名单时为模拟执行）'],
}
export function actionZh(actionId) {
  if (!actionId) return '—'
  const hit = ACTION_ID_ZH[actionId]
  return hit ? hit[0] : actionId
}
export function actionDesc(actionId) {
  const hit = ACTION_ID_ZH[actionId]
  return hit ? hit[1] : ''
}
