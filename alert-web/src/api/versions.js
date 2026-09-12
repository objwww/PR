import { api, http } from './client'

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

// ===== 发布/回滚命令（EV-10；ConfigBundleController 契约，ROLE_RELEASE 服务端裁定）=====
// 前端不决定资格：403/404/409/422 一律如实上抛给视图层分文案。
// expectedActiveRevision 是服务端 EN-02 CAS 锚（0=从未激活），也是实际幂等锚
// （重放返回 replayed=true 零改写）；Idempotency-Key 头当前仅 publish 端点消费，
// activate/rollback 只随请求留痕不存储——此处仍按稳定散列发送，与发布面口径一致。
export async function activateBundle(digest, expectedActiveRevision, idempotencyKey) {
  const { data } = await http.post(
    `/config-bundles/${encodeURIComponent(digest)}/activate`,
    { expectedActiveRevision },
    { headers: { 'Idempotency-Key': idempotencyKey } },
  )
  return data
}

export async function rollbackBundle(toDigest, expectedActiveRevision, idempotencyKey) {
  const { data } = await http.post(
    '/config-bundles/rollback',
    { toDigest, expectedActiveRevision },
    { headers: { 'Idempotency-Key': idempotencyKey } },
  )
  return data
}
