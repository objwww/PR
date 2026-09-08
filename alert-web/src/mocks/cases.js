// P4 处置中心 mock（线框图 v1.6 #p4）：OperatorCase 队列/详情 + 站内通知
// 后端 OperatorCase 列表/详情 API、Case 命令 API、通知查询/已读 API 落码前使用。
// 字段对齐 annot 契约：subject/priority/reason_code/status/owner/first_seen/ack/resolve SLA、
// evidence_refs、revision；机器码（status/reason_code/evidence type/source/通知 type）英文，
// 中文显示名由 view 层词典映射。命令一律 expected_revision + idempotency_key。

const UPDATED_AT = '10:43:08'
const ME = 'operator'

// ---- OperatorCase 队列（按 SLA 风险排序的代表性子集；tab 计数由 fetchCaseSummary 给出）----
const cases = [
  {
    id: 'c91', subject: 'Claim 冲突', priority: 'P0', status: 'OPEN', owner: null,
    reasonCode: 'CLAIM_CONFLICT', incidentType: 'payment-failure',
    runId: '8c1d', taskId: 'root-cause',
    firstSeen: '09:55', ackDue: '10:05', resolveDue: '10:35',
    slaLabel: '已逾期', ackOverdue: 'ACK 超时 6m', overdue: true,
    evidenceCount: 6, revision: 4,
  },
  {
    id: 'c77', subject: '预算即将耗尽', priority: 'P1', status: 'ACKED', owner: ME,
    reasonCode: 'BUDGET_NEAR_LIMIT', incidentType: null,
    runId: '7e00', taskId: 'budget-guard',
    firstSeen: '10:28', ackDue: '10:38', resolveDue: '10:55',
    slaLabel: '12m', ackOverdue: null, overdue: false,
    evidenceCount: 2, revision: 2,
  },
  {
    id: 'c62', subject: '报告需要复核', priority: 'P2', status: 'ACKED', owner: ME,
    reasonCode: 'REPORT_REVIEW', incidentType: null,
    runId: '62aa', taskId: 'report',
    firstSeen: '09:40', ackDue: '09:50', resolveDue: '11:40',
    slaLabel: '1h', ackOverdue: null, overdue: false,
    evidenceCount: 3, revision: 1,
  },
  {
    id: 'c84', subject: '自动修复执行失败', priority: 'P1', status: 'OPEN', owner: null,
    reasonCode: 'ACTION_FAILED', incidentType: 'inventory-timeout',
    runId: '7f42', taskId: 'remediation',
    firstSeen: '09:58', ackDue: '10:08', resolveDue: '10:38',
    slaLabel: '已逾期', ackOverdue: 'ACK 超时 35m', overdue: true,
    evidenceCount: 2, revision: 1,
  },
  {
    id: 'c55', subject: '通知投递失败排查', priority: 'P2', status: 'OPEN', owner: ME,
    reasonCode: 'NOTIFY_RETRY_EXHAUSTED', incidentType: null,
    runId: '5c09', taskId: 'outbox',
    firstSeen: '09:12', ackDue: '09:22', resolveDue: '12:12',
    slaLabel: '2h', ackOverdue: null, overdue: false,
    evidenceCount: 1, revision: 3,
  },
]

// ---- Case 详情（证据工作区 / Claim ↔ Evidence 引用矩阵 / 活动 / 审计）----
const details = {
  c91: {
    headline: 'Claim 冲突，需要人工裁决',
    summary: 'Reducer 对 payment-failure 的根因裁决出现两个互不支配的候选 Claim，且二者均有可核验证据支撑，自动裁决置信度不足，转人工。Case 固定展示创建时快照，迟到证据进入新快照，不改写旧裁决上下文。',
    sourceRefs: { count: 7, text: 'Evidence×6 + rca_event#442' },
    snapshot: '71ac…', generation: 13,
    evidence: [
      { id: 'e#81', type: 'METRIC', source: 'prometheus', window: '10:31–10:38', generation: 13, verify: 'FRESH_VERIFIED', digest: '1a2b…', summary: 'CPU throttle 92%', taskId: 'metrics', expandable: true },
      { id: 'e#83', type: 'LOG', source: 'loki', window: '10:33–10:37', generation: 13, verify: 'VERIFIED', digest: '3c4d…', summary: 'cgroup throttled 1842ms', taskId: 'logs', expandable: false },
      { id: 'e#84', type: 'TRACE', source: 'tempo', window: '10:36', generation: 13, verify: 'PENDING_REVIEW', digest: '5e6f…', summary: 'downstream inventory timeout', taskId: null, expandable: false },
      { id: 'e#82', type: 'METRIC', source: 'prometheus', window: '10:31–10:38', generation: 13, verify: 'VERIFIED', digest: '7a8b…', summary: '容器 CPU 限额 500m 持续打满', taskId: 'metrics', expandable: false },
      { id: 'e#86', type: 'LOG', source: 'loki', window: '10:35–10:37', generation: 13, verify: 'VERIFIED', digest: '9c0d…', summary: 'inventory 调用 2s 超时重试 3 次', taskId: 'logs', expandable: false },
      { id: 'e#87', type: 'TRACE', source: 'tempo', window: '10:36', generation: 13, verify: 'VERIFIED', digest: '1e2f…', summary: 'checkout span 重试风暴，扇出 4×', taskId: null, expandable: false },
    ],
    claims: [
      { id: 'claim#21', text: 'CPU 限流', verdict: 'TRUE', evidenceRefs: ['e#81', 'e#83', 'e#82'] },
      { id: 'claim#24', text: '下游超时', verdict: 'TRUE', evidenceRefs: ['e#84', 'e#86', 'e#87'] },
    ],
    conflictNote: '两个候选均有证据，Reducer 无法唯一裁决；不是“无证据”。',
    activities: [
      '10:31 system ｜ Case 创建，来源 run#8c1d/task#root-cause，generation=13',
      '10:32 system ｜ 绑定 EvidenceRef×6（e#81…e#87），snapshot=71ac…',
      '10:35 system ｜ resolve_due 到达，升级为已逾期',
      '10:43 operator ｜ 打开 Case 详情',
    ],
    audits: [
      { time: '10:31', actor: 'system', action: 'CASE_CREATED', revision: 1, key: 'idem-8c1d-case' },
      { time: '10:32', actor: 'system', action: 'EVIDENCE_BOUND', revision: 2, key: 'idem-8c1d-ev6' },
      { time: '10:35', actor: 'system', action: 'SLA_ESCALATED', revision: 3, key: 'idem-c91-esc' },
      { time: '10:37', actor: 'system', action: 'REVISION_BUMP', revision: 4, key: 'idem-c91-r4' },
    ],
  },
}

// 非 c91 Case 的详情：以 Case 自身字段合成，保证每个 Case ≥1 条可核验来源引用（annot：N≥1）
function synthDetail(c) {
  const ev = Array.from({ length: c.evidenceCount }, (_, i) => ({
    id: `e#${40 + i}`, type: ['METRIC', 'LOG', 'TRACE'][i % 3],
    source: ['prometheus', 'loki', 'tempo'][i % 3],
    window: '10:0' + i + '–10:1' + i, generation: 13,
    verify: i === 0 ? 'VERIFIED' : 'PENDING_REVIEW',
    digest: `${(i + 2).toString(16)}f${i}…`,
    summary: `${c.subject} 相关证据摘要 ${i + 1}`, taskId: c.taskId, expandable: false,
  }))
  return {
    headline: c.subject,
    summary: `Case ${c.id}（reason_code=${c.reasonCode}），来源 run#${c.runId}/task#${c.taskId}。处置动作均要求 expected_revision + idempotency_key。`,
    sourceRefs: { count: c.evidenceCount, text: `Evidence×${c.evidenceCount}` },
    snapshot: 'a3f9…', generation: 13,
    evidence: ev,
    claims: [],
    conflictNote: null,
    activities: [`${c.firstSeen} system ｜ Case 创建，来源 run#${c.runId}`],
    audits: [{ time: c.firstSeen, actor: 'system', action: 'CASE_CREATED', revision: 1, key: `idem-${c.id}-case` }],
  }
}

// ---- 站内通知（notify-app IN_APP 渠道投影；已读不改变 Case 状态）----
const notifications = [
  { id: 'n51', type: 'REPORT_READY', title: '调查报告就绪：payment-failure', body: 'run#8c1d generation=13 报告已发布，可查看结论与证据 manifest。', runId: '8c1d', caseId: null, time: '10:38', read: false },
  { id: 'n47', type: 'CASE_ASSIGNED', title: 'Case 分配：case#c77 预算即将耗尽', body: '你已被指定为 case#c77 负责人，resolve_due 10:55。', runId: '7e00', caseId: 'c77', time: '10:12', read: false },
  { id: 'n44', type: 'SYSTEM_ERROR', title: 'notify-app outbox 投递重试', body: '钉钉渠道已停用保留；IN_APP 渠道正常，1 条历史 webhook 记录重试耗尽转 Case c55。', runId: null, caseId: 'c55', time: '09:58', read: false },
  { id: 'n40', type: 'REPORT_READY', title: '调查报告就绪：inventory-timeout', body: 'run#62aa 报告已发布，等待人工复核（case#c62）。', runId: '62aa', caseId: 'c62', time: '09:41', read: false },
  { id: 'n36', type: 'CASE_ASSIGNED', title: 'Case 分配：case#c62 报告需要复核', body: '你已被指定为 case#c62 负责人，evidence 3 条。', runId: '62aa', caseId: 'c62', time: '09:20', read: false },
  { id: 'n30', type: 'SYSTEM_ERROR', title: 'SSE 通道重连成功（已恢复）', body: '事件流出现 seq 缺口，已停止增量拼接并完成全量同步。', runId: null, caseId: null, time: '08:55', read: true },
]

// ---- 查询 API 同形函数 ----
export function fetchCaseSummary() {
  return Promise.resolve({
    updatedAt: UPDATED_AT,
    tabs: { mine: 4, all: 12, unassigned: 3, overdue: 2, notifyUnread: notifications.filter(n => !n.read).length },
  })
}

export function fetchCases() {
  return Promise.resolve(cases.map(c => ({ ...c })))
}

export function fetchCaseDetail(caseId) {
  const c = cases.find(x => x.id === caseId)
  if (!c) return Promise.resolve(null)
  const d = details[caseId] || synthDetail(c)
  return Promise.resolve({ ...c, ...d, evidence: d.evidence.map(e => ({ ...e })) })
}

export function fetchNotifications() {
  return Promise.resolve(notifications.map(n => ({ ...n })))
}

// ---- 命令 API 同形函数（expected_revision + idempotency_key，旧 revision 返回冲突）----
export function caseCommand(caseId, action, { expectedRevision, idempotencyKey, reason, remark, assignee } = {}) {
  const c = cases.find(x => x.id === caseId)
  if (!c) return Promise.resolve({ ok: false, conflict: false, error: 'NOT_FOUND' })
  if (expectedRevision != null && expectedRevision !== c.revision) {
    return Promise.resolve({ ok: false, conflict: true, case: { ...c }, error: 'REVISION_CONFLICT' })
  }
  if (action === 'claim') { c.owner = ME; c.status = 'ACKED' }
  if (action === 'ack') c.status = 'ACKED'
  if (action === 'resolve') { c.status = 'RESOLVED'; c.resolution = { reason, remark } }
  if (action === 'assign') { c.owner = assignee || c.owner }
  c.revision += 1
  const d = details[caseId]
  if (d) d.audits.push({ time: UPDATED_AT, actor: ME, action: action.toUpperCase(), revision: c.revision, key: idempotencyKey })
  return Promise.resolve({ ok: true, conflict: false, case: { ...c } })
}

// 已读标记：只写已读表，不回写 Case 状态（annot：通知侧）
export function markNotificationRead(id) {
  const n = notifications.find(x => x.id === id)
  if (n) n.read = true
  return Promise.resolve({ ok: true, unread: notifications.filter(x => !x.read).length })
}
