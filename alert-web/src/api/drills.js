import { api } from './client'

// 故障演练 API（DR-01 页面骨架）：后端 /api/drills* 整组接口依赖 DR-02 持久化作业，
// 本批未交付——调用方必须区分「接口未就绪（404）」与「真实错误」，不得把 404 渲染成空数据。
export class ApiNotReadyError extends Error {
  constructor(path) {
    super(`接口未就绪：${path}`)
    this.name = 'ApiNotReadyError'
    this.path = path
  }
}

function classify(path, err) {
  // 实测：未实现的 /api/drills* 在认证后由 Spring Security 默认拒绝返回 403，未登录为 401；
  // 两类「路由不存在」语义统一归为接口未就绪
  const s = err?.response?.status
  if (s === 404 || s === 403) return new ApiNotReadyError(path)
  return err
}

export async function listDrills() {
  try {
    return await api('/drills')
  } catch (e) {
    throw classify('/api/drills', e)
  }
}

export async function getDrill(drillId) {
  const path = `/api/drills/${encodeURIComponent(drillId)}`
  try {
    return await api(path)
  } catch (e) {
    throw classify(path, e)
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
  const path = `/api/drills/${encodeURIComponent(drillId)}/stop`
  try {
    return await api(path, { method: 'POST', body: { idempotencyKey } })
  } catch (e) {
    throw classify(path, e)
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
