// P1 总览 mock：行动摘要只读聚合（incident / rca_run/task / operator_case / outbox / eval_run / run_budget_state）
// 契约：GET /api/v1/overview/action-summary；机器码（severity/status/枚举）保持英文，UI 展示层转中文名
export function fetchActionSummary() {
  return Promise.resolve({
    env: '生产环境',
    timezone: 'Asia/Shanghai',
    updatedAt: '10:43:08',
    stats: {
      overdueCases: {
        count: 2,
        overSla: true,
        oldestOverdueMin: 18,
        // 深跳：P4 处置中心，带筛选参数
        jump: { path: '/cases', query: { status: 'OPEN', overdue: '1' } },
      },
      stuckFailedRuns: {
        total: 5,
        stuck: 3,
        failed: 2,
        jump: { path: '/runs', query: { status: 'STUCK,DEAD' } },
      },
      oldestReady: {
        ageMin: 18,
        runId: 'run#1fa0',
        assignee: null, // 未分配
        jump: { path: '/runs', query: { status: 'READY', sort: 'WAITING_DESC' } },
      },
      evalRegression: {
        regressedCases: 1,
        blockingCandidate: true,
        candidateEvalId: 'eval#0907',
        baselineEvalId: 'eval#0906',
        jump: { path: '/eval', query: { candidate: '0907', baseline: '0906', regression: '1' } },
      },
      publishFailures: {
        count: 1,
        outboxOldestMin: 7,
        jump: { path: '/monitor', query: { tab: 'outbox', status: 'FAILED' } },
      },
    },
    handover: [
      {
        id: 'ho-1',
        time: '10:42',
        severity: 'P0',
        text: '新增 P0 Case：run#8c1d Claim 冲突，尚未分配',
        claimable: true,
        caseId: 'case#c91',
        claimed: false,
      },
      {
        id: 'ho-2',
        time: '10:31',
        severity: null,
        text: 'payment-failure 再次 FIRING，已创建 run#3f2a',
        claimable: false,
        incidentId: 'inc#9ab2',
      },
      {
        id: 'ho-3',
        time: '09:58',
        severity: null,
        text: '候选 eval#0907 出现 1 个退化案例，发布门未发起',
        claimable: false,
        evalId: 'eval#0907',
      },
    ],
    health: [
      { name: 'control-app', status: 'UP' },
      { name: 'notify-app', status: 'UP' },
      { name: 'litellm', status: 'UP' },
      { name: 'holmes', status: 'REFERENCE' }, // 对照
    ],
  })
}

// P1 唯一允许的低风险命令：认领（其余命令进入对象详情完成）
// 契约：POST /api/v1/operator-cases/{caseId}/claim（mock 仅模拟成功）
export function claimCase() {
  return Promise.resolve({ ok: true })
}
