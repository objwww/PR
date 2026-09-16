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
