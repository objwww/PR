// 时间与时长格式化（UI-1）：全站中文口径，ISO 时间戳可为 null → '—'
const pad = n => String(n).padStart(2, '0')

// 'YYYY-MM-DD HH:mm:ss'（本地时区）；null/非法 → '—'
export function fmtTime(iso) {
  if (!iso) return '—'
  const d = new Date(iso)
  if (Number.isNaN(d.getTime())) return '—'
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())} ${pad(d.getHours())}:${pad(d.getMinutes())}:${pad(d.getSeconds())}`
}

// 距今相对时间：'刚刚' / 'x 分钟前' / 'x 小时前' / 'x 天前'；超过 30 天回退绝对时间
export function fmtAgo(iso, now = Date.now()) {
  if (!iso) return '—'
  const t = new Date(iso).getTime()
  if (Number.isNaN(t)) return '—'
  const m = Math.floor(Math.max(0, now - t) / 60000)
  if (m < 1) return '刚刚'
  if (m < 60) return `${m} 分钟前`
  const h = Math.floor(m / 60)
  if (h < 24) return `${h} 小时前`
  const d = Math.floor(h / 24)
  if (d < 30) return `${d} 天前`
  return fmtTime(iso)
}

// 起止时长：'x 分钟' / 'x 小时 x 分钟' / 'x 天 x 小时'；endIso 为空按当前时间
export function fmtDuration(startIso, endIso) {
  if (!startIso) return '—'
  const start = new Date(startIso).getTime()
  const end = endIso ? new Date(endIso).getTime() : Date.now()
  if (Number.isNaN(start) || Number.isNaN(end) || end < start) return '—'
  const m = Math.floor((end - start) / 60000)
  if (m < 1) return '不足 1 分钟'
  if (m < 60) return `${m} 分钟`
  const h = Math.floor(m / 60)
  if (h < 24) return m % 60 ? `${h} 小时 ${m % 60} 分钟` : `${h} 小时`
  return `${Math.floor(h / 24)} 天 ${h % 24} 小时`
}

// MTTR（分钟，可为 null）：'x 分钟' / 'x.x 小时'；null → '—'
export function fmtMttr(minutes) {
  if (minutes == null || Number.isNaN(Number(minutes))) return '—'
  const m = Number(minutes)
  if (m < 60) return `${Math.round(m)} 分钟`
  return `${(m / 60).toFixed(1)} 小时`
}

// 比率（0~1 小数或百分数均可）：'xx.x%'；null/非法 → '—'
export function fmtPct(v) {
  if (v == null || Number.isNaN(Number(v))) return '—'
  const n = Number(v)
  const pct = Math.abs(n) <= 1 ? n * 100 : n
  return `${(Math.round(pct * 10) / 10)}%`
}

// EV-01 计数三态：null/undefined → '未统计'；真实 0 → '0'
export function fmtCount(v) {
  return v == null ? '未统计' : String(v)
}

// EV-01 比率三态：null/非法 → '未统计'；真实 0 → '0%'（与 fmtPct 的 '—' 口径分离，评测页专用）
export function fmtPctStat(v) {
  if (v == null || Number.isNaN(Number(v))) return '未统计'
  return fmtPct(v)
}

// 'HH:mm:ss'（本地时区，asOf 数据截至用）；null/非法 → '—'
export function fmtClock(iso) {
  if (!iso) return '—'
  const d = new Date(iso)
  if (Number.isNaN(d.getTime())) return '—'
  return `${pad(d.getHours())}:${pad(d.getMinutes())}:${pad(d.getSeconds())}`
}

// DR-02 秒数时长（预检 computed：duration/ttl/total 等后端计算值）：'x 秒' / 'x 分 x 秒' / 'x 小时 x 分'；null/非法 → '—'
export function fmtSeconds(total) {
  if (total == null || Number.isNaN(Number(total)) || Number(total) < 0) return '—'
  const s = Math.floor(Number(total))
  if (s < 60) return `${s} 秒`
  const m = Math.floor(s / 60)
  if (m < 60) return s % 60 ? `${m} 分 ${s % 60} 秒` : `${m} 分钟`
  return `${Math.floor(m / 60)} 小时 ${m % 60} 分`
}

// EV-03 分子/分母计数对：'12/20'；两侧皆缺席 → '未统计'；单侧缺席该侧 '—'（不填 0 冒充）
export function fmtPair(numerator, denominator) {
  if (numerator == null && denominator == null) return '未统计'
  return `${numerator ?? '—'}/${denominator ?? '—'}`
}

// EV-03 比率三件套 RatioStat{numerator, denominator, status}：
// OK → 'xx.x%'（保留 1 位）；UNKNOWN/字段缺席 → '未统计'；NOT_APPLICABLE（分母 0）→ '不适用'
export function fmtRatioStat(stat) {
  if (!stat || stat.status == null) return '未统计'
  if (stat.status === 'NOT_APPLICABLE') return '不适用'
  if (stat.status !== 'OK') return '未统计'
  if (stat.numerator == null || stat.denominator == null) return '未统计'
  const n = Number(stat.numerator)
  const d = Number(stat.denominator)
  if (Number.isNaN(n) || Number.isNaN(d) || d === 0) return '未统计'
  return `${Math.round((n / d) * 1000) / 10}%`
}

// EV-03 过渡：优先 RatioStat 三件套；旧契约无 quality 字段时回退旧数值比率（真实数据，不冒充）
export function fmtRatioStatOr(stat, legacy) {
  return stat ? fmtRatioStat(stat) : fmtPctStat(legacy)
}

// EV-03 阶段词表 → 中文；null（阶段未采集）→ '未统计'；未收录枚举原样透出（不虚构词表）
const PHASE_ZH = {
  PREPARING: '准备中',
  INJECTING: '注入中',
  AWAITING_ALERT: '等待告警',
  AWAITING_RCA: '等待调查',
  SCORING: '评分中',
  FINALIZING: '收尾中',
  RECOVERING: '恢复中',
}
export function fmtPhase(phase) {
  if (!phase) return '未统计'
  return PHASE_ZH[phase] ?? phase
}

// EV-03 状态分面三态：null/UNKNOWN → '未统计'；NOT_APPLICABLE → '不适用'；
// freshness=LIVE → '实时'；未收录枚举原样透出（不虚构词表）
export function fmtFacet(v) {
  if (v == null || v === 'UNKNOWN') return '未统计'
  if (v === 'NOT_APPLICABLE') return '不适用'
  if (v === 'LIVE') return '实时'
  return v
}
