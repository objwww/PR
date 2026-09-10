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
