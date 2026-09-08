// P5 评测中心 mock：与后端查询契约同形（见 mocks/README.md）。
// 脱敏红线：thought / 原始 prompt / secret / 完整工具参数永不进 payload；
// HOLDOUT 分区永不返回题目/真值/逐案例详情，只返回批次进度与聚合门禁结论。
const delay = (ms = 120) => new Promise(r => setTimeout(r, ms))

// 头部摘要条 + tab 计数
export async function fetchEvalSummary() {
  await delay()
  return {
    env: '评测环境',
    dataUpdatedAt: '10:42:50',
    evaluator: 'evaluator',
    counts: { batches: 3, pendingReview: 7 },
  }
}

// P5-A 数据集治理（版本化 insert-only，四分区 FUT-42）
export async function fetchDatasets() {
  await delay()
  return {
    datasets: [
      {
        id: 'ds-order-v3',
        name: '订单域私有集 v3',
        visibility: 'PRIVATE',
        visibilityZh: '私有',
        caseCount: 47,
        scenarioFamilies: 5,
        digest: '9c1e…',
        review: 'APPROVED',
        drift: '低',
        partitions: [
          {
            key: 'TUNING', nameZh: '调优集', hint: '≈训练集，调 prompt/词典/规则用',
            caseCount: 28, coverage: '5/5 故障族', locked: false,
            canLaunchBatch: true, canLaunchGate: false,
          },
          {
            key: 'VALIDATION', nameZh: '验证集', hint: '',
            caseCount: 14, pendingReview: 2, locked: false,
            canLaunchBatch: true, canLaunchGate: false,
          },
          {
            key: 'HOLDOUT', nameZh: '测试集', hint: '',
            caseCount: 5, locked: true,
            canLaunchBatch: false, canLaunchGate: true,
            redline: '页面永不返回题目/真值/逐案例详情，只显示批次进度+聚合门禁结论；RBAC + 幂等键 + 访问审计；与普通批量评测是两个独立命令',
          },
          {
            key: 'REDTEAM', nameZh: '对抗集', hint: '安全用例',
            caseCount: null, locked: true,
            canLaunchBatch: false, canLaunchGate: false,
            redline: '随发布门（安全门）触发，不单独发起',
          },
        ],
      },
      {
        id: 'ds-rca100-v11',
        name: 'RCA-100 外部集 v1.1',
        visibility: 'PUBLIC_BENCHMARK',
        visibilityZh: '公开 benchmark',
        caseCount: 103,
        note: '辅助一致性门，不冒充私有测试集',
        partitions: [],
      },
    ],
    governance: {
      familyLeakage: 0, duplicateInput: 0, missingGroundTruth: 0,
      distributionDrift: '4.1%', holdoutWear: 2,
    },
  }
}

// P5-B 批次列表（发起批量评测命令 API 未落码，mock 示意）
export async function fetchEvalRuns() {
  await delay()
  return {
    runs: [
      {
        id: 'eval#0908', replayType: 'AGENT_REPLAY', replayZh: 'Agent Replay',
        datasetVersion: 3, partition: 'TUNING', status: 'RUNNING',
        done: 11, total: 28, elapsed: '12m', cost: '¥2.31', budget: '¥10',
      },
      {
        id: 'eval#0907', replayType: 'AGENT_REPLAY', replayZh: 'Agent Replay',
        datasetVersion: 3, partition: 'VALIDATION', status: 'COMPLETED',
        done: 28, total: 28, elapsed: '32m', cost: '¥6.84', budget: '¥10',
      },
      {
        id: 'eval#0906', replayType: 'AGENT_REPLAY', replayZh: 'Agent Replay',
        datasetVersion: 3, partition: 'VALIDATION', status: 'COMPLETED',
        done: 28, total: 28, elapsed: '29m', cost: '¥6.27', budget: '¥10',
        baseline: true,
      },
    ],
  }
}

// P5-B 六维分析（架构固定六维）
export async function fetchRunAnalysis(runId) {
  await delay()
  return {
    runId,
    replayType: 'AGENT_REPLAY', replayZh: 'Agent Replay',
    partition: 'VALIDATION', datasetVersion: 3,
    status: 'COMPLETED', done: 28, total: 28, elapsed: '32m', cost: '¥6.84', budget: '¥10',
    dimensions: [
      { key: 'accuracy', name: '结论准确性', headline: 'Top1 0.71 ｜ E2E 0.64', sub: 'Macro-F1 0.68 / cases 28' },
      { key: 'reasoning', name: '推理效率', headline: '有效步骤 82%', sub: '无效工具 0.7 / case ｜ Claim 修订 0.3' },
      { key: 'tooling', name: '工具精确性', headline: 'registry hit 100%', sub: 'schema 98% ｜ 时间窗 94%' },
      { key: 'cost', name: '成本与时延', headline: 'P50 38s ｜ P95 104s', sub: 'token 11.2k/case ｜ ¥0.11/case' },
      { key: 'multiagent', name: '多 Agent 效率', headline: '任务覆盖 91%', sub: '冗余委派 6% ｜ 证据消费 84%' },
      { key: 'safety', name: '鲁棒性与安全', headline: 'utility 0.88', sub: '越权执行 0 ｜ secret leak 0 ｜ recovery 92%' },
    ],
    slices: {
      byFaultType: [
        { faultType: 'BUSINESS_ERROR_RATE', score: 0.83 },
        { faultType: 'DEPENDENCY_UNREACHABLE', score: 0.67 },
        { faultType: 'IDEMPOTENCY_BYPASS', score: 0.50, lowest: true },
      ],
      hotspot: { family: 'S4', component: 'order-arena', round: 2, flipRate: 0.25, drillCases: 4 },
      stats: { tp: 18, fp: 5, fn: 6, support: 29, bootstrapCi95: [0.56, 0.77] },
      statsNote: '样本不足类明确标记，不以 0 代替',
    },
    health: {
      queue: '2m', model: '11m', tool: '4m', validation: '1m',
      error: 2, retry: 3, providerDrift: 0,
    },
    repro: {
      dataset: '9c1e…', registry: '1', lexicon: '1',
      providerFingerprint: '31a…', prompt: 'b92…', tools: '5d2…', config: '0ff…', seed: '20260907',
    },
  }
}

// P5-C 案例列表
export async function fetchCaseResults(runId) {
  await delay()
  return {
    runId,
    filters: ['PASS', 'FAIL', 'ERROR', 'FLIP', 'REVIEW_PENDING'],
    cases: [
      {
        resultId: 'result#7ac', verdict: 'FAIL', family: 'S4', round: 2,
        expected: 'ILLEGAL_STATE_TRANSITION', actual: 'DB_CPU_HIGH',
        latency: '91s', cost: '¥0.14', rootHit: false,
      },
      {
        resultId: 'result#5e1', verdict: 'FLIP', family: 'S3', round: 2,
        note: 'r1 PASS / r2 FAIL ｜ inconsistent tool window',
      },
      {
        resultId: 'result#2b9', verdict: 'PASS', family: 'S1', round: 1,
        expected: 'PAYMENT_CHARGE_FAILURE', actual: 'PAYMENT_CHARGE_FAILURE', latency: '38s',
      },
      {
        resultId: 'result#3c4', verdict: 'ERROR', family: 'S2', round: 1,
        note: 'SCENARIO_RECOVERY_TIMEOUT ｜ 已落档、未中断批次',
      },
      {
        resultId: 'result#9f0', verdict: 'PASS', family: 'S5', round: 1,
        expected: 'DEPENDENCY_UNREACHABLE', actual: 'DEPENDENCY_UNREACHABLE', latency: '44s',
      },
      {
        resultId: 'result#6d8', verdict: 'REVIEW_PENDING', family: 'S1', round: 2,
        note: '未知枚举进入失败样本，待人工复核',
      },
    ],
  }
}

// P5-C 案例诊断详情（HOLDOUT 案例永远没有此载荷）
export async function fetchCaseDetail(resultId) {
  await delay()
  return {
    resultId,
    verdict: 'FAIL', rootHit: false, latency: '91s', cost: '¥0.14',
    context: 'eval#0907 ＞ S4 / round 2',
    expected: { component: 'order-arena', faultType: 'ILLEGAL_STATE_TRANSITION', reasonCode: 'STATE_ROLLBACK_DIRECT_WRITE' },
    actual: { component: 'order-arena', componentHit: true, faultType: 'DB_CPU_HIGH', reasonCode: 'QUERY_SATURATION' },
    scoring: {
      rule: 'component exact/whitelist=PASS；fault_type 不在 S4 允许集合=NO_MATCH；reason_code=NO_MATCH',
      tp: 1, fp: 2, fn: 2, verdict: 'FAIL',
    },
    trace: {
      rcaRunId: 'rca_run#7d2', tasks: 5, toolCalls: 9, evidence: 7, claims: 2,
      gaps: '未查询 order_state_history；evidence e-811 的时间窗晚于故障注入 6m',
    },
    review: {
      queueReasons: ['机器判分与 reviewer 不一致', '未知枚举', '抽样校准'],
      note: '复核追加 review_event，不改写 eval_case_result；词典修订另发新版本并重跑 Scorer Replay',
    },
  }
}

// 评分器校准（确定性判分，EvidencePackageV2 类型化字段 + 版本化白名单）
export async function fetchScorerSuites() {
  await delay()
  return {
    principle: 'root_cause_hit 只读 EvidencePackageV2 类型化字段 + 版本化同义词白名单，不让自由文本或 LLM judge 决定 canonical 命中',
    suites: [
      { name: 'Deterministic RCA', version: 'v3', status: 'ACTIVE', scope: 'canonical 根因命中' },
      { name: 'Schema', version: 'v2', status: 'ACTIVE', scope: '输出结构校验' },
      { name: 'Safety', version: 'v4', status: 'ACTIVE', scope: '安全硬门' },
      { name: '同义词白名单', version: 'v8', status: 'ACTIVE', scope: '版本化词典 display_name_zh' },
    ],
    calibration: {
      sampleSize: 50, agreement: 0.92, unknownEnum: 2,
      unknownEnumPolicy: '未知值进入失败样本队列，不猜分',
      reviseFlow: '词典/评分器更新生成新版本 → Scorer Replay 对比新旧版本后再启用',
    },
  }
}

// 人工复核队列（append-only review_event）
export async function fetchReviewQueue() {
  await delay()
  const reasons = ['机器判分与 reviewer 不一致', '未知枚举', '抽样校准']
  return {
    items: [
      { resultId: 'result#6d8', runId: 'eval#0907', family: 'S1', round: 2, verdict: 'REVIEW_PENDING', reason: reasons[1], queuedAt: '10:12' },
      { resultId: 'result#7ac', runId: 'eval#0907', family: 'S4', round: 2, verdict: 'FAIL', reason: reasons[0], queuedAt: '09:58' },
      { resultId: 'result#4a2', runId: 'eval#0907', family: 'S2', round: 1, verdict: 'FAIL', reason: reasons[2], queuedAt: '09:41' },
      { resultId: 'result#8b3', runId: 'eval#0906', family: 'S3', round: 2, verdict: 'FLIP', reason: reasons[0], queuedAt: '昨日 17:22' },
      { resultId: 'result#1c7', runId: 'eval#0906', family: 'S5', round: 1, verdict: 'PASS', reason: reasons[2], queuedAt: '昨日 17:05' },
      { resultId: 'result#2e5', runId: 'eval#0905', family: 'S4', round: 1, verdict: 'FAIL', reason: reasons[1], queuedAt: '昨日 15:48' },
      { resultId: 'result#5d9', runId: 'eval#0905', family: 'S1', round: 2, verdict: 'FAIL', reason: reasons[0], queuedAt: '昨日 15:30' },
    ],
  }
}

// P5-D 实验比较（强制同 dataset_version/partition，paired bootstrap）
export async function fetchComparison() {
  await delay()
  return {
    candidate: 'eval#0907', baseline: 'eval#0906',
    constraint: { datasetVersion: 3, partition: 'VALIDATION', pairedSamples: 28 },
    paired: [
      { metric: '端到端命中', from: 0.64, to: 0.71, delta: '+0.07', ci95: [-0.01, 0.16], significant: false },
      { metric: '覆盖率', from: 0.79, to: 0.86 },
      // 未决率=机器无法给出确定性判定的案例占比（诊断指标，不计入命中分母）
      { metric: '未决率', from: 0.21, to: 0.14, diagnostic: true, note: '机器无法给出确定性判定的案例占比，不计入命中分母' },
      { metric: 'P95 时延', from: '88s', to: '104s', regression: '时延退化' },
      { metric: '成本', delta: '+9%' },
    ],
    migration: { improved: 4, regressed: 1, flip: 2, unchanged: 21 },
    worstRegression: { case: 'S4 round2', from: '正确「状态回跳」', to: '错误「DB CPU」' },
    configDiff: { only: 'prompt a81…→b92…', same: 'model/lexicon/tools/dataset 相同' },
    matrix: {
      columns: [
        { id: 'eval#0906', role: '基线', worseCount: 1 },
        { id: 'eval#0907', role: '候选', betterCount: 4, worseCount: 1 },
      ],
      rows: [
        { case: 'S1 round1', cells: ['✓ PASS', '✓ PASS'], change: 'UNCHANGED' },
        { case: 'S2 round1', cells: ['✗ ERROR', '✓ PASS'], change: 'IMPROVED' },
        { case: 'S3 round2', cells: ['✓ PASS（r1）', '✗ FAIL（r2）'], change: 'FLIP', note: 'inconsistent tool window' },
        { case: 'S4 round2', cells: ['✓ 正确「状态回跳」', '✗ 错误「DB CPU」'], change: 'REGRESSED', sev: 'p0' },
        { case: 'S5 round1', cells: ['✗ FAIL', '✓ PASS'], change: 'IMPROVED' },
      ],
    },
  }
}

// P5-D 发布门（独立命令与权限；HOLDOUT 只回聚合结论）
export async function fetchGateStatus() {
  await delay()
  return {
    qualityGate: {
      partition: 'VALIDATION', passed: true,
      checks: [
        { name: '准确性非劣', pass: true },
        { name: '安全硬门 0 违规', pass: true },
        { name: 'P95 时延预算', pass: false, note: '需豁免/优化' },
      ],
    },
    holdoutGate: {
      action: '发起 HOLDOUT 发布门',
      requirements: 'release 角色 + 幂等键 + 审批原因；服务端读题',
      redline: 'UI 只显示进度、聚合指标、门槛和审计，不返回案例/真值',
    },
    audit: {
      gateId: 'gate#218', requestedBy: 'release-02', config: 'b92…',
      holdoutWear: 2, redteam: 'PASS', decision: 'BLOCKED_LATENCY', signedAt: '—',
    },
  }
}
