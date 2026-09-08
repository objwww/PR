// src/mocks/runs.js — P3 调查队列 /runs + 调查详情 /runs/:runId 的页面级 mock
// 契约来源：docs/告警-前端页面设计-wireframes-v1.html v1.6 #p3 annot
//  - 队列依赖新增后端配套 GET /rca-runs 只读投影（状态/严重度/负责人/时间/SLA/预算筛选，游标分页+稳定排序），落码前走本 mock
//  - 脱敏红线（§17.9.3）：thought/原始 prompt/secret/完整工具参数永不进前端，payload 只含白名单摘要/引用/digest
//  - 机器码（status/event_type/reason_code/canonical 根因码）保持英文，中文名由前端词典渲染（M7-09 统一抽版本化词典）

const delay = (ms = 120) => new Promise(r => setTimeout(r, ms))

// ---------- P3-A 调查队列 ----------

// bucket：mine=待我处理 running=执行中 stuck=卡住 failed=失败 review=待审查 done=已完成
// action：claim-view=认领并查看 / view=查看 / claim=认领
const rows = [
  { id: 'run#8c1d', incident: 'payment-failure', severity: 'P0', bucket: 'stuck',
    stage: 'NEEDS_REVIEW', stageZh: '卡住', stageTone: 'red',
    progress: '—', blocker: 'Claim 冲突', owner: '未分配', duration: 'SLA 已超 6m', action: 'claim-view' },
  { id: 'run#b408', incident: 'inventory-timeout', severity: 'P0', bucket: 'stuck',
    stage: 'BLOCKED', stageZh: '卡住', stageTone: 'red',
    progress: '2/6', blocker: '依赖任务 DEAD', owner: '未分配', duration: 'SLA 已超 2m', action: 'claim-view' },
  { id: 'run#77aa', incident: 'inventory-timeout', severity: 'P1', bucket: 'failed',
    stage: 'DEAD', stageZh: '失败', stageTone: 'red',
    progress: '1/6 tasks', blocker: '—', owner: '王工', duration: '运行 24m', action: 'view' },
  { id: 'run#a17f', incident: 'payment-failure', severity: 'P1', bucket: 'failed',
    stage: 'FAILED_TERMINAL', stageZh: '失败', stageTone: 'red',
    progress: '3/5', blocker: 'SCHEMA_INVALID', owner: '王工', duration: '运行 9m', action: 'view' },
  { id: 'run#3f2a', incident: 'payment-failure', severity: 'P1', bucket: 'running',
    stage: 'RUNNING', stageZh: '执行中', stageTone: 'blue',
    progress: '3/4', blocker: '根因 Agent 2m31s', owner: 'operator', duration: '—', action: 'view' },
  { id: 'run#7e00', incident: 'inventory-timeout', severity: 'P1', bucket: 'running',
    stage: 'RUNNING', stageZh: '执行中', stageTone: 'blue',
    progress: '5/8', blocker: '预算即将耗尽', owner: 'operator', duration: '运行 41m', action: 'view' },
  { id: 'run#9c4f', incident: 'delivery-delay', severity: 'P2', bucket: 'running',
    stage: 'RETRY_WAIT', stageZh: '等待重试', stageTone: 'orange',
    progress: '2/5', blocker: '退避 90s', owner: '未分配', duration: '—', action: 'view' },
  { id: 'run#1fa0', incident: 'delivery-delay', severity: 'P2', bucket: 'mine',
    stage: 'READY', stageZh: '等待', stageTone: 'gray',
    progress: '—', blocker: 'oldest 18m', owner: '未分配', duration: '—', action: 'claim' },
  { id: 'run#c52e', incident: 'payment-failure', severity: 'P0', bucket: 'mine',
    stage: 'NEEDS_REVIEW', stageZh: '待我处理', stageTone: 'red',
    progress: '4/4', blocker: '报告待复核', owner: 'operator', duration: 'SLA 剩余 12m', action: 'view' },
  { id: 'run#5b9e', incident: 'payment-failure', severity: 'P2', bucket: 'review',
    stage: 'WAIT_REVIEW', stageZh: '待审查', stageTone: 'orange',
    progress: '4/4', blocker: '—', owner: 'operator', duration: '运行 18m', action: 'view' },
  { id: 'run#62aa', incident: 'delivery-delay', severity: 'P1', bucket: 'review',
    stage: 'WAIT_REVIEW', stageZh: '待审查', stageTone: 'orange',
    progress: '6/6', blocker: '—', owner: 'operator', duration: '运行 22m', action: 'view' },
]

export async function fetchRuns() {
  await delay()
  return {
    // 投影汇总计数取线框示例值（待我处理 4 / 执行中 9 / 卡住 3 / 失败 2 / 待审查 5）；
    // rows 为示意子集，正式投影由游标分页返回全量
    summary: {
      buckets: { mine: 4, running: 9, stuck: 3, failed: 2, review: 5 },
      sla: { overSla: 2, oldestReadyWait: '18m', projectionLag: '1.2s' },
      updatedAt: '10:43:08',
    },
    rows,
  }
}

// ---------- P3-B 调查详情（示例 run#3f2a） ----------

const detail = {
  run: {
    id: 'run#3f2a', incident: 'payment-failure',
    status: 'RUNNING', statusZh: '执行中', severity: 'P1',
    engine: 'native', config: '9c1e…',
    progress: { done: 2, running: 1, blocked: 1, total: 4 },
    budget: { pct: 30, step: { used: 6, total: 20 }, token: { used: '8.2k', total: '50k' }, tool: { used: 9, total: 30 } },
  },
  tasks: [
    {
      id: 'metrics', name: '指标 Agent', status: 'DONE', priority: 10,
      duration: '1m12s', deadline: '—', lease: { worker: 'worker-01', epoch: 3 },
      deps: [], downstream: [{ name: '根因 Agent', status: 'RUNNING' }],
      attempts: [{ n: 1, status: 'DONE', worker: 'worker-01', span: '10:37:02–10:38:14' }],
      safety: { inputDigest: '9f8e…', outputRefs: ['evidence#11'] },
    },
    {
      id: 'logs', name: '日志 Agent', status: 'DONE', priority: 10,
      duration: '1m04s', deadline: '—', lease: { worker: 'worker-02', epoch: 5 },
      deps: [], downstream: [{ name: '根因 Agent', status: 'RUNNING' }],
      attempts: [{ n: 1, status: 'DONE', worker: 'worker-02', span: '10:37:05–10:38:09' }],
      safety: { inputDigest: '7c6d…', outputRefs: ['evidence#14'] },
    },
    {
      id: 'root-cause', name: '根因 Agent', status: 'RUNNING', priority: 20,
      duration: '2m31s', deadline: '10:48', lease: { worker: 'worker-03', epoch: 7 },
      deps: [
        { name: '指标 Agent', status: 'DONE', req: 'REQUIRED' },
        { name: '日志 Agent', status: 'DONE', req: 'REQUIRED' },
      ],
      downstream: [{ name: '报告任务', status: 'BLOCKED' }],
      attempts: [
        { n: 2, status: 'RUNNING', worker: 'worker-03', span: '10:40:37 → 现在' },
        { n: 1, status: 'RETRYABLE_FAILED', worker: 'worker-02', span: '10:38:11–10:38:42', error: 'REMOTE_5XX / METRICS_QUERY_FAILED', backoff: '退避 90s' },
      ],
      safety: { inputDigest: '1a2b…', outputRefs: ['claim#17', 'evidence#11'] },
    },
    {
      id: 'report', name: '报告任务', status: 'BLOCKED', priority: 30,
      duration: '—', deadline: '—', lease: null,
      deps: [{ name: '根因 Agent', status: 'RUNNING', req: 'REQUIRED' }],
      downstream: [],
      attempts: [],
      safety: { inputDigest: '—', outputRefs: [] },
    },
  ],
  edges: [
    { source: 'metrics', target: 'root-cause' },
    { source: 'logs', target: 'root-cause' },
    { source: 'root-cause', target: 'report' },
  ],
  // 事件流静态快照（after_seq 初次读取的返回）；level=error 供「仅错误」筛选
  // payload 只含白名单摘要字段，关联对象一律以不可变引用（claim#/evidence#/task）表达
  events: [
    { seq: 31, type: 'RUN_STARTED', taskId: null, taskName: null, level: 'info',
      summary: 'incident=payment-failure ｜ engine native ｜ config 9c1e…' },
    { seq: 32, type: 'TASK_LEASED', taskId: 'metrics', taskName: '指标Agent', level: 'info',
      summary: 'worker-01 领取（epoch 3）' },
    { seq: 33, type: 'TASK_LEASED', taskId: 'logs', taskName: '日志Agent', level: 'info',
      summary: 'worker-02 领取（epoch 5）' },
    { seq: 34, type: 'TASK_DONE', taskId: 'metrics', taskName: '指标Agent', level: 'info',
      summary: '✓ 1m12s ｜ 输出 evidence#11' },
    { seq: 35, type: 'TASK_DONE', taskId: 'logs', taskName: '日志Agent', level: 'info',
      summary: '✓ 1m04s ｜ 输出 evidence#14' },
    { seq: 36, type: 'TASK_LEASED', taskId: 'root-cause', taskName: '根因Agent', level: 'info',
      summary: 'worker-02 领取（attempt #1）' },
    { seq: 37, type: 'TOOL_CALL_FAILED', taskId: 'root-cause', taskName: '根因Agent', level: 'error',
      summary: 'metrics/query ｜ REMOTE_5XX / METRICS_QUERY_FAILED' },
    { seq: 38, type: 'TASK_RETRY_SCHEDULED', taskId: 'root-cause', taskName: '根因Agent', level: 'warn',
      summary: 'attempt #1 可重试 ｜ 退避 90s' },
    { seq: 39, type: 'TASK_LEASED', taskId: 'root-cause', taskName: '根因Agent', level: 'info',
      summary: 'worker-03 领取（attempt #2 ｜ epoch 7）' },
    { seq: 40, type: 'TOOL_CALL_STARTED', taskId: 'root-cause', taskName: '根因Agent', level: 'info',
      summary: 'metrics/query' },
    { seq: 41, type: 'TOOL_CALL_FINISHED', taskId: 'root-cause', taskName: '根因Agent', level: 'info',
      summary: 'metrics/query ✓ 182ms' },
    { seq: 42, type: 'CLAIM_CREATED', taskId: 'root-cause', taskName: '根因Agent', level: 'info',
      summary: 'CPU 限流（cpu-throttle）｜ 多源一致' },
    { seq: 43, type: 'BUDGET_COMMIT', taskId: 'root-cause', taskName: '根因Agent', level: 'info',
      summary: 'token +1240' },
  ],
  // SSE 增量模拟池（前端定时器逐条追加，模拟实时事件；真实接入点见 RunDetailView 注释）
  liveEvents: [
    { seq: 44, type: 'TOOL_CALL_STARTED', taskId: 'root-cause', taskName: '根因Agent', level: 'info',
      summary: 'logs/search' },
    { seq: 45, type: 'TOOL_CALL_FINISHED', taskId: 'root-cause', taskName: '根因Agent', level: 'info',
      summary: 'logs/search ✓ 96ms' },
    { seq: 46, type: 'EVIDENCE_ATTACHED', taskId: 'root-cause', taskName: '根因Agent', level: 'info',
      summary: 'e#16 日志摘要挂载 ｜ payload_digest=3c4d…' },
    { seq: 47, type: 'BUDGET_COMMIT', taskId: 'root-cause', taskName: '根因Agent', level: 'info',
      summary: 'token +860' },
    { seq: 48, type: 'CLAIM_UPDATED', taskId: 'root-cause', taskName: '根因Agent', level: 'info',
      summary: 'claim#17 证据 +1（e#16）' },
  ],
  claims: [
    {
      id: 'claim#17', kind: '根因 Claim', text: 'CPU 限流', code: 'cpu-throttle',
      verdict: '成立', agree: '多源一致', current: true,
      evidences: [
        { id: 'e#11', desc: '指标曲线', digest: 'digest…', window: '10:31–10:38' },
        { id: 'e#14', desc: '日志摘要', digest: 'digest…', window: '10:33–10:37' },
      ],
    },
  ],
  reportState: {
    status: 'NOT_GENERATED',
    note: '报告任务阻塞中；完成后分别展示 VALIDATED / SEALED / PUBLISHED，旧版本显示「已被取代」。',
  },
}

export async function fetchRunDetail(runId) {
  await delay()
  // mock 只备 run#3f2a 一份示例数据；其他 runId 复用同构数据并替换头信息
  if (runId && runId !== detail.run.id) {
    return { ...detail, run: { ...detail.run, id: runId } }
  }
  return detail
}
