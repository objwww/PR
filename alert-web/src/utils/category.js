// UX-01 告警分类词表（与后端 IncidentCategory 枚举 / V82 CHECK 一一对应）：机器码 → 中文显示名。
// 分类只描述告警所属面；severity 不参与分类。词表静态，后端 UX-01 未部署时仅用于展示。
export const CATEGORY_ZH = {
  BUSINESS: '业务',
  APPLICATION: '应用',
  DEPENDENCY: '依赖',
  INFRA: '基础设施',
  NETWORK: '网络',
  DATA: '数据',
  SECURITY: '安全',
  PLATFORM: '平台',
  UNCLASSIFIED: '未分类',
}

// 列表过滤下拉选项（含 UNCLASSIFIED）
export const CATEGORY_OPTIONS = Object.entries(CATEGORY_ZH).map(([value, label]) => ({ value, label }))

// 人工修正目标分类选项：不含 UNCLASSIFIED（撤销修正即回到规则/未分类面，不设人工未分类）
export const CATEGORY_OVERRIDE_OPTIONS = CATEGORY_OPTIONS.filter(o => o.value !== 'UNCLASSIFIED')

// mapCategory(raw) → { key, label, cls }；raw 缺席（null/''）→ null——调用方降级显示 '—'，不渲染徽章。
// 未收录机器码原样透出（沿用 dict 惯例，绝不猜测翻译），色阶回退灰。
export function mapCategory(raw) {
  if (raw == null || raw === '') return null
  const key = String(raw).toUpperCase()
  const known = key in CATEGORY_ZH
  return {
    key,
    label: known ? CATEGORY_ZH[key] : String(raw),
    cls: 'cat-' + (known ? key.toLowerCase() : 'unclassified'),
  }
}
