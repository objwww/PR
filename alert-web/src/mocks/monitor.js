// P6 Agent 监控（/monitor，线框标注 /agent-ops）mock 适配层
// 数据来源契约（线框 v1.6 #p6 annot）：
//   预算 run_budget_state / run_budget_entry（六维 kind）；工具 rca_tool_call（AM4 V15 工具账本落库后切新表）；
//   熔断 DoomLoopGuard 事件经 rca_event 透出；成本 external_invocation_ledger + LiteLLM SpendLogs 对账；
//   队列健康 rca_oldest_ready_age_seconds / worker/slot 饱和度 / rca_outbox_lag_seconds /
//   rca_reconciliation_backlog / rca_operator_case_open / eval job backlog
// 机器码（状态枚举、原因码）保持英文；原始数值（秒/比例）由 view 负责格式化展示

export function getMonitorSummary() {
  return Promise.resolve({
    env: '生产环境',
    window: '6h',
    dataDelaySeconds: 4,
    operator: 'operator',
    metrics: {
      oldestReady: { ageSeconds: 1080, sloSeconds: 300, breached: true, runRef: 'run#1fa0', target: '调查队列' },
      workerSlots: { used: 14, total: 16, utilization: 0.88, hotspot: { worker: 'worker-03', backlog: 7 } },
      outboxLag: { lagSeconds: 420, status: 'DEGRADED', failedDeliveries: 1, target: '对账' },
      reconciliationBacklog: { count: 23, oldestSeconds: 660 },
      openCases: { count: 12, overdue: 2, target: '处置中心' },
      evalQueue: { total: 3, running: 1, queued: 2 },
    },
    activeRuns: [
      { runId: 'run#3f2a', status: 'RUNNING', stepUsedPct: 30, tokenUsedPct: 41 },
      { runId: 'run#8c1d', status: 'RUNNING', stepUsedPct: 55, tokenUsedPct: 90, tokenRisk: true },
    ],
    doomLoopBreaks: [
      { runId: 'run#77aa', tool: 'metrics/query', signatureStallCount: 3, action: '熔断', at: '10:02' },
    ],
    toolCalls24h: {
      tools: [
        { name: 'metrics/query', count: 142, successRate: 0.97 },
        { name: 'logs/search', count: 88, successRate: 0.94 },
      ],
      failuresByReason: [
        { reasonCode: 'TIMEOUT', count: 6 },
        { reasonCode: 'RATE_LIMITED', count: 3 },
        { reasonCode: 'REMOTE_5XX', count: 1 },
      ],
    },
    modelCosts: [
      { model: 'deepseek-v3', invocations: 312, tokens: 412000, costCny: 3.86, source: 'external_invocation_ledger + LiteLLM 对账' },
    ],
    controlHealth: {
      components: [
        { name: 'control', status: 'OK' },
        { name: 'notify', status: 'DEGRADED' },
        { name: 'litellm', status: 'OK' },
        { name: 'PG', status: 'OK' },
      ],
      freshness: { metricsLastSeenSeconds: 4, outboxProjectorLagSeconds: 420 },
    },
  })
}
