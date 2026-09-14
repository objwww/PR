import { api } from './client'

// 故障演练 API（DR-01/DR-02）：调用方必须区分「权限/不存在/不可用」与「真实错误」，
// 不得把非 2xx 一律渲染成空数据。PAGE-02：错误分类保留原始 HTTP status，
// 403=无权限、404=资源不存在或接口未部署，由视图按 status 分文案。
export class ApiNotReadyError extends Error {
  constructor(path, status) {
    super(`接口不可用（${status}）：${path}`)
    this.name = 'ApiNotReadyError'
    this.path = path
    this.status = status
  }
}

function classify(path, err) {
  const s = err?.response?.status
  if (s === 404 || s === 403) return new ApiNotReadyError(path, s)
  return err
}

export async function listDrills({ cursor, limit } = {}) {
  // 键集游标分页（后端 limit 默认 50 上限 200，满页才给 nextCursor）
  const params = {}
  if (cursor) params.cursor = cursor
  if (limit != null) params.limit = limit
  try {
    return await api('/drills', { params })
  } catch (e) {
    throw classify('/api/drills', e)
  }
}

// PAGE-01：client.baseURL 已带 /api——传入路径不得再写 /api 前缀（曾产生 /api/api/drills/…）
export async function getDrill(drillId) {
  const path = `/drills/${encodeURIComponent(drillId)}`
  try {
    return await api(path)
  } catch (e) {
    throw classify(`/api${path}`, e)
  }
}

// DR-02 场景目录：{registryVersion, catalogDigest, templates:[TemplateCard]}；
// execution.ready=false 带 reason（本期五场景均不开放启动，卡片如实展示原因）
export async function listTemplates() {
  try {
    return await api('/drills/templates')
  } catch (e) {
    throw classify('/api/drills/templates', e)
  }
}

// DR-02 服务端预检：body {scenarioId, targetEnv, durationSeconds?, trafficScale?, linkedEvalVersion?}
// 返回 {template, computed, checks:[{name, status:OK|FAIL|UNKNOWN, detail}], canLaunch, checkedAt}
export async function previewDrill(plan) {
  try {
    return await api('/drills/preview', { method: 'POST', body: plan })
  } catch (e) {
    throw classify('/api/drills/preview', e)
  }
}

// DR-02 发起演练：202 {drillId, state:ACCEPTED} / 200 重放；409 不归类——
// PRECHECK_FAILED 带 checks、CONFLICT_ENV 带 occupantDrillId，由调用方如实渲染
export async function createDrill(plan) {
  try {
    return await api('/drills', { method: 'POST', body: plan })
  } catch (e) {
    throw classify('/api/drills', e)
  }
}

// DR-02 停止并恢复：202 受理（state=RECOVERING/CANCELLING——受理≠恢复完成，§7.4）；
// 409（终态/RECOVERY_FAILED 占位）不归类，由调用方展示服务端 error 文案
export async function stopDrill(drillId, idempotencyKey) {
  const path = `/drills/${encodeURIComponent(drillId)}/stop`
  try {
    return await api(path, { method: 'POST', body: { idempotencyKey } })
  } catch (e) {
    throw classify(`/api${path}`, e)
  }
}

// DR-02 事件流（§7.2 详情页原始账本视图 / DU10 游标增量轮询）：
// {items:[{eventId, seq, eventType, fromState, toState, actor, payload, createdAt}], nextCursor, asOf}；
// 游标 = seq（drill_event identity 单调序），afterSeq 严格大于续页，满页才给 nextCursor
export async function listDrillEvents(drillId, { afterSeq, limit } = {}) {
  const path = `/drills/${encodeURIComponent(drillId)}/events`
  const params = {}
  if (afterSeq != null) params.afterSeq = afterSeq
  if (limit != null) params.limit = limit
  try {
    return await api(path, { params })
  } catch (e) {
    throw classify(`/api${path}`, e)
  }
}

// 幂等键：优先 crypto.randomUUID（安全上下文）；非安全上下文回退 getRandomValues 拼 UUID 形
export function newIdempotencyKey() {
  if (globalThis.crypto?.randomUUID) return globalThis.crypto.randomUUID()
  const b = globalThis.crypto.getRandomValues(new Uint8Array(16))
  b[6] = (b[6] & 0x0f) | 0x40
  b[8] = (b[8] & 0x3f) | 0x80
  const h = [...b].map(x => x.toString(16).padStart(2, '0'))
  return `${h.slice(0, 4).join('')}-${h.slice(4, 6).join('')}-${h.slice(6, 8).join('')}-${h.slice(8, 10).join('')}-${h.slice(10).join('')}`
}
