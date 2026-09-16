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
