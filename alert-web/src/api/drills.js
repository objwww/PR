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
