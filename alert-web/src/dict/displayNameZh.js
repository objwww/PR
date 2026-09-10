// M7-09 版本化中文词典（线框图 v1.6 annot「中文口径」契约：
// 机器码英文为稳定契约不变，UI 中文名统一来自本模块，对应后端词典 display_name_zh 字段。
// 后端词典 API 落码后改为远程拉取 + 本地缓存，键名与版本机制保持不变。
// 修订纪律：任何词条变更必须升 DICT_VERSION，禁止原地静默改义。
export const DICT_VERSION = 'zh-display-v2'

// 事件类型（rca_event.event_type → 中文显示名）
export const EVENT_TYPE_ZH = {
  RUN_STARTED: '调查启动',
  TASK_LEASED: '任务领取',
  TASK_DONE: '任务完成',
  TASK_RETRY_SCHEDULED: '重试调度',
  TOOL_CALL_STARTED: '工具调用开始',
  TOOL_CALL_FINISHED: '工具调用完成',
  TOOL_CALL_FAILED: '工具调用失败',
  CLAIM_CREATED: '新结论产生',
  CLAIM_UPDATED: '结论更新',
  BUDGET_COMMIT: '预算扣减',
  EVIDENCE_ATTACHED: '证据挂载',
}

// 通知类型（notification.type → 中文显示名）
export const NOTIFY_TYPE_ZH = {
  REPORT_READY: '报告就绪',
  CASE_ASSIGNED: '处置分配',
  SYSTEM_ERROR: '系统异常',
}

// 证据校验状态（evidence.verify_status → 中文显示名）
export const VERIFY_STATUS_ZH = {
  FRESH_VERIFIED: '新鲜/校验通过',
  VERIFIED: '校验通过',
  PENDING_REVIEW: '待复核',
}

// 未收录机器码一律回退原文展示（绝不猜测翻译），并留诊断线索
export function zh(table, code) {
  return table[code] ?? code
}
