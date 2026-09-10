// severity 原始 label → 展示级映射（UI-1 契约）：
// critical→P0、warning→P1、info/notice→P2、其他非空→P3、null/空→未分级
const LEVEL_MAP = { critical: 'P0', warning: 'P1', info: 'P2', notice: 'P2' }

const BAR_COLOR = {
  P0: 'var(--sev-p0)',
  P1: 'var(--sev-p1)',
  P2: 'var(--sev-p2)',
  P3: 'var(--sev-p3)',
  NONE: 'var(--sev-info)',
}

// 返回 { key, label, barColor, rowClass }：
// key 可直接喂给 StatusBadge 的 severity prop（'P0'~'P3'），null 表示未分级（页面自渲染“未分级” tag）
export function mapSeverity(raw) {
  const key = raw == null || raw === '' ? null : (LEVEL_MAP[String(raw).toLowerCase()] ?? 'P3')
  return {
    key,
    label: key ?? '未分级',
    barColor: BAR_COLOR[key ?? 'NONE'],
    rowClass: 'sev-' + (key ?? 'none').toLowerCase(),
  }
}
