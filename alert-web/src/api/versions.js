import { api } from './client'

// 版本中心 API（EN-10 /versions）：release_asset / config_bundle 只读面 + run 配置切换
// 状态（epoch 历史）。全部为后端已交付查询端点（docker profile）；本地 dev profile 无
// 这些路由 → 404，或权限矩阵拒绝 → 403，页面必须就地解释而非渲染成空数据。
export class ApiNotReadyError extends Error {
  constructor(path, status) {
    super(status === 403 ? `无权访问：${path}` : `接口未就绪：${path}`)
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

export async function listAssets(kind, limit = 50) {
  const path = '/api/release-assets'
  try {
    return await api(path, { params: { ...(kind ? { kind } : {}), limit } })
  } catch (e) {
    throw classify(path, e)
  }
}

export async function listBundles() {
  const path = '/api/release-assets/bundles'
  try {
    return await api(path)
  } catch (e) {
    throw classify(path, e)
  }
}

export async function getAssetDetail(kind, digest) {
  const path = `/api/release-assets/${encodeURIComponent(kind)}/${encodeURIComponent(digest)}`
  try {
    return await api(path)
  } catch (e) {
    throw classify(path, e)
  }
}

export async function getRunConfigEpochs(runId) {
  const path = `/api/rca-runs/${encodeURIComponent(runId)}/config-epochs`
  try {
    return await api(path)
  } catch (e) {
    throw classify(path, e)
  }
}
