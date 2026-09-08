// P2 告警中心 mock：分类分诊列表（/alerts）、Incident 详情（/alerts/:incidentId）、历史档案（/history）
// 契约口径见线框图 v1.6 section#p2 annot：
// - 机器码英文（severity/status/category/fault_type 等枚举），UI 中文名由视图层映射
// - 列表投影 = incident 二态/generation/三计数 + 当前代 labels 聚合 + 分类/Run/Case 摘要
// - 严重度取当前代活动实例最高级别，无 incident.severity 权威列
// - 脱敏红线：不含模型 Thought / 原始 prompt / secret / 完整工具参数
// - 归档：CAS 不可用明确返回 archive_status=ARCHIVE_UNAVAILABLE，不伪装“无历史”

const delay = (data, ms = 120) => new Promise(res => setTimeout(() => res(structuredClone(data)), ms))

// ---------- P2-A 分类分诊 /alerts ----------

const alertsSummary = {
  env: '生产环境',
  updated_at: '10:43:08',
  operator: 'operator',
  // 顶部计数卡（点击即过滤，Alerta ASI 先例，E-19 §2.2.6）
  cards: [
    { key: 'P0', label: 'P0 严重', value: 2, sub: '未确认 1', filter: { severity: 'P0' } },
    { key: 'P1', label: 'P1 高', value: 5, sub: '新增 2', filter: { severity: 'P1' } },
    { key: 'OPEN', label: '待处理 Incident', value: 7, sub: 'Case 12', filter: null },
    { key: 'STORM', label: '重复/风暴', value: 48, sub: '次接收 / 6 事件', filter: null },
    { key: 'SOURCE', label: '来源异常', value: 1, sub: 'NoData/Error', filter: { status: 'NODATA' } },
    { key: 'MTTR', label: '平均恢复', value: '26m', sub: '近 24h', filter: null },
  ],
  tabs: [
    { key: 'CURRENT', label: '当前告警', count: 9 },
    { key: 'HISTORY', label: '历史告警', count: null, route: '/history' },
    { key: 'MINE', label: '我负责的', count: 3 },
    { key: 'UNCLASSIFIED', label: '未分类', count: 1, filter: { category: 'UNKNOWN' } },
    { key: 'VIEWS', label: '保存的视图 ▾', count: null },
  ],
}

// 左 facet 栏：分类（taxonomy v1）+ 严重度/状态/来源/服务/其他
const alertsFacets = [
  {
    group: '分类（taxonomy v1）', key: 'category',
    items: [
      { value: 'BUSINESS', label: '业务 BUSINESS', count: 3 },
      { value: 'APPLICATION', label: '应用 APPLICATION', count: 2 },
      { value: 'DEPENDENCY', label: '依赖 DEPENDENCY', count: 1 },
      { value: 'INFRASTRUCTURE', label: '基础设施 INFRA', count: 2 },
      { value: 'NETWORK', label: '网络 NETWORK', count: 0 },
      { value: 'DATA', label: '数据 DATA', count: 0 },
      { value: 'SECURITY', label: '安全 SECURITY', count: 0 },
      { value: 'UNKNOWN', label: '平台/未分类', count: 1 },
    ],
  },
  {
    group: '严重度', key: 'severity',
    items: [
      { value: 'P0', label: 'P0', count: 2 },
      { value: 'P1', label: 'P1', count: 5 },
      { value: 'RANGE', label: 'P0–P2 范围', count: '▾' },
    ],
  },
  {
    group: '状态', key: 'status',
    items: [
      { value: 'FIRING', label: 'FIRING', count: 7 },
      { value: 'NODATA', label: 'NoData', count: 1 },
      { value: 'RESOLVED', label: '已解决', count: '—' },
    ],
  },
  {
    group: '来源', key: 'source',
    items: [
      { value: 'prometheus', label: 'Prometheus', count: 3 },
      { value: 'ALL', label: '全部来源', count: '▾' },
    ],
  },
  {
    group: '服务 / 团队', key: 'service',
    items: [
      { value: 'payment', label: 'service=payment', count: 2 },
      { value: 'checkout', label: 'service=checkout', count: 1 },
      { value: 'team-pay', label: 'team=支付', count: 2 },
      { value: 'team-order', label: 'team=订单', count: 1 },
    ],
  },
  {
    group: '其他 facets', key: 'misc',
    items: [
      { value: 'cn-east', label: 'region=cn-east', count: 2 },
      { value: 'cn-north', label: 'region=cn-north', count: 1 },
      { value: 'resource_type', label: '资源类型', count: '全部 ▾' },
      { value: 'env', label: '环境', count: 'prod ▾' },
      { value: 'time', label: '时间', count: '近 24h ▾' },
    ],
  },
]

// 列表行：severity 取当前代活动实例最高级别；category 为 AlertTaxonomyV1 投影（含 rule/confidence）
const alerts = [
  {
    incident_id: '9ab2', generation: 13, severity: 'P0', status: 'FIRING',
    category: 'BUSINESS', category_label: '业务', extra_tags: [],
    alertname: 'payment-failure', service: 'order-arena',
    last_at: '10:42', counts: { received: 48, events: 6, notified: 3 },
    metric: 'error_rate=52%', owner: 'operator', run_id: '3f2a', case_id: null,
    classification: { rule_id: 'TX-BIZ-004', confidence: 0.98, taxonomy_version: 'v1', source: 'labels+alertname+service', mapped_at: '10:31:04' },
  },
  {
    incident_id: '77aa', generation: 2, severity: 'P1', status: 'FIRING',
    category: 'BUSINESS', category_label: '业务', extra_tags: ['依赖标签'],
    alertname: 'checkout-slo-burn', service: 'payment-api',
    last_at: '10:39', counts: { received: 12, events: 2, notified: 1 },
    metric: 'SLO 14.2×', owner: null, run_id: '77aa', case_id: null,
    classification: { rule_id: 'TX-BIZ-002', confidence: 0.91, taxonomy_version: 'v1', source: 'labels+slo', mapped_at: '10:33:20' },
  },
  {
    incident_id: 'b310', generation: 1, severity: 'P1', status: 'FIRING',
    category: 'BUSINESS', category_label: '业务', extra_tags: [],
    alertname: 'order-biz-divergence', service: 'checkout',
    last_at: '10:36', counts: { received: 5, events: 1, notified: 1 },
    metric: '对账差异 0.4%', owner: null, run_id: null, case_id: null,
    classification: { rule_id: 'TX-BIZ-007', confidence: 0.83, taxonomy_version: 'v1', source: 'labels+alertname', mapped_at: '10:30:11' },
  },
  {
    incident_id: 'a42f', generation: 3, severity: 'P1', status: 'FIRING',
    category: 'APPLICATION', category_label: '应用', extra_tags: [],
    alertname: 'order-latency-high', service: 'order-arena',
    last_at: '10:28', counts: { received: 9, events: 3, notified: 2 },
    metric: 'p99=2.4s', owner: 'operator', run_id: 'a42f', case_id: null,
    classification: { rule_id: 'TX-APP-001', confidence: 0.95, taxonomy_version: 'v1', source: 'labels+service', mapped_at: '10:19:47' },
  },
  {
    incident_id: 'c58d', generation: 5, severity: 'P1', status: 'FIRING',
    category: 'APPLICATION', category_label: '应用', extra_tags: [],
    alertname: 'app-restart-loop', service: 'notify-app',
    last_at: '09:47', counts: { received: 14, events: 5, notified: 2 },
    metric: 'restart=7/10m', owner: null, run_id: null, case_id: null,
    classification: { rule_id: 'TX-APP-004', confidence: 0.89, taxonomy_version: 'v1', source: 'labels+alertname', mapped_at: '09:41:02' },
  },
  {
    incident_id: 'd6e1', generation: 1, severity: 'P0', status: 'ACK',
    category: 'DEPENDENCY', category_label: '依赖', extra_tags: [],
    alertname: 'db-conn-pool-exhausted', service: 'order-db',
    last_at: '10:22', counts: { received: 6, events: 1, notified: 1 },
    metric: 'connections 98/100', owner: 'operator', run_id: 'd6e1', case_id: 'c90',
    classification: { rule_id: 'TX-DEP-002', confidence: 0.97, taxonomy_version: 'v1', source: 'labels+service', mapped_at: '10:14:55' },
  },
  {
    incident_id: 'e207', generation: 2, severity: 'P1', status: 'FIRING',
    category: 'INFRASTRUCTURE', category_label: '基础设施', extra_tags: [],
    alertname: 'node-cpu-throttle', service: 'k8s-node-07',
    last_at: '10:15', counts: { received: 3, events: 1, notified: 0 },
    metric: 'cpu_throttle=41%', owner: null, run_id: null, case_id: null,
    classification: { rule_id: 'TX-INF-003', confidence: 0.93, taxonomy_version: 'v1', source: 'labels+source', mapped_at: '10:02:36' },
  },
  {
    incident_id: 'f9b4', generation: 1, severity: 'P1', status: 'FIRING',
    category: 'INFRASTRUCTURE', category_label: '基础设施', extra_tags: [],
    alertname: 'disk-usage-high', service: 'kafka-broker-2',
    last_at: '09:58', counts: { received: 2, events: 1, notified: 0 },
    metric: 'disk=87%', owner: null, run_id: null, case_id: null,
    classification: { rule_id: 'TX-INF-006', confidence: 0.9, taxonomy_version: 'v1', source: 'labels+source', mapped_at: '09:55:18' },
  },
  {
    incident_id: '05cc', generation: 1, severity: 'P2', status: 'NODATA',
    category: 'UNKNOWN', category_label: '未分类', extra_tags: [],
    alertname: 'delivery-lag', service: null,
    last_at: '10:35', counts: { received: 1, events: 1, notified: 0 },
    metric: 'value=— ｜ 规则未命中，进入归类队列', owner: null, run_id: null, case_id: null,
    classification: null,
  },
]

// 选中行的「分类说明与修正」面板数据（线框以 payment-failure 为例）
const classificationDetail = {
  '9ab2': {
    category_label: '业务异常 / BUSINESS', taxonomy_version: 'v1',
    rule_id: 'TX-BIZ-004', source: 'labels+alertname+service', confidence: 0.98, mapped_at: '10:31:04',
    basis: 'alertname=PaymentFailureRateHigh；labels.domain=checkout；service=payment；SLO burn-rate 14.2×',
    note: '告警主分类是入口分诊；RCA 最终 fault_type 可能是 BUSINESS_ERROR_RATE，也可能是 DEPENDENCY_UNREACHABLE，不提前偷填答案。',
    override_note: '人工修正写 override 审计和新投影版本；原始 labels/AlertEvent 永不改写。',
  },
}

// ---------- P2-B Incident 详情 /alerts/{incident_id} ----------

// 全步骤投影 IncidentTimelineProjection：接收→归并→准入→Run→DAG→ToolCall/Attempt→Evidence→Claim/Report→发布/Case
const incidentDetail = {
  '9ab2': {
    incident_id: '9ab2', generation: 13, alertname: 'payment-failure', category_label: '业务异常',
    severity: 'P0', status: 'FIRING', first_at: '10:31', last_at: '10:42', owner: 'operator',
    tabs: ['概览', '实例与阈值', '调查过程', '证据与 RCA', '报告', '状态历史', '通知', '源数据'],
    trace: { scope: '本代全景', run_id: '3f2a', task: '全部', live_seq: 184 },
    steps: [
      { n: 1, name: '接收', state: 'DONE', lines: ['AlertEvent×6', '48 次接收', '10:31:02 ✓'] },
      { n: 2, name: '归并', state: 'DONE', lines: ['Incident inc#9ab2', 'gen 13', '0.12s ✓'] },
      { n: 3, name: '准入', state: 'DONE', lines: ['ADMITTED', 'policy v4', '0.08s ✓'] },
      { n: 4, name: '建 Run', state: 'DONE', lines: ['run#3f2a', 'config 9c1e…', '0.21s ✓'] },
      { n: 5, name: '规划 DAG', state: 'DONE', lines: ['4 Tasks / 3 Edges', 'plan v2', '1.3s ✓'] },
      { n: 6, name: '执行工具', state: 'DONE', lines: ['8 calls / 1 retry', 'metrics+logs', '2m08s ✓'] },
      { n: 7, name: '汇聚证据', state: 'RUNNING', lines: ['Evidence 6', 'snapshot gen13', 'RUNNING'] },
      { n: 8, name: 'Claim/报告', state: 'WAITING', lines: ['Claims 2', '1 条冲突', 'WAITING'] },
      { n: 9, name: '发布/处置', state: 'WAITING', lines: ['Report —', 'Case c91 OPEN', 'WAITING'] },
    ],
    // 选中步骤下钻（Task / Attempt 粒度；状态、耗时、错误码、重试、输入输出引用）
    step_drill: {
      6: [
        { id: '6.1', tool: 'metrics/query', task_id: 't21', attempt: 1, window: '10:31:09–10:31:11', result: 'SUCCESS', output: 'Evidence e-41', action: '查看证据', input_ref: 'plan v2 / task#t21.params', duration_ms: 2100, component: 'tool-executor' },
        { id: '6.2', tool: 'logs/search', task_id: 't22', attempt: 1, window: '10s', result: 'TIMEOUT', reason_code: 'REMOTE_TIMEOUT', action: '查看错误', input_ref: 'plan v2 / task#t22.params', duration_ms: 10000, component: 'tool-executor' },
        { id: '6.3', tool: 'logs/search', task_id: 't22', attempt: 2, window: 'backoff 2s', result: 'SUCCESS', output: 'Evidence e-42/e-43', action: '查看 Attempt', input_ref: 'plan v2 / task#t22.params(retry)', duration_ms: 3400, component: 'tool-executor' },
      ],
    },
    // 结构化 InvestigationSummary（禁止模型 Thought / 原始 prompt / secret / 完整工具参数）
    summary: {
      question: '支付失败率为何在 10:31 后升至 52%？',
      verified: ['checkout 三实例同时升高', 'payment 网络可达', '扣款返回业务失败', '发布/配置窗口匹配'],
      excluded: ['网络中断（e-42）', 'DB 锁等待（e-44）'],
      hypothesis: 'paymentFailure 开关异常，置信度 0.78，仍待配置证据 e-46。',
      links: { evidence: 6, claims: 2, report: 'draft', case_id: 'c91', run_id: '3f2a' },
    },
  },
}

// ---------- P2-C 历史档案 /history ----------

const historyFilters = [
  { key: 'category', label: '分类', value: '全部' },
  { key: 'fault_type', label: '根因', value: '全部' },
  { key: 'service', label: '服务', value: '全部' },
  { key: 'conclusion', label: '结论', value: '已发布' },
  { key: 'storage', label: '存储层', value: '热/冷' },
  { key: 'time', label: '时间', value: '近 180 天' },
]

// 历史列表：先查热表再查 archive_catalog；archive_status 显式表达可用性
const historyIncidents = [
  {
    incident_id: '81a', generation: 7, alertname: 'payment-failure',
    category_label: '业务', storage: 'HOT', archive_status: 'AVAILABLE',
    date: '2026-08-31', fault_type: 'PAYMENT_CHARGE_FAILURE', duration: '18m',
    evidence_count: 7, report: 'published',
  },
  {
    incident_id: '6bd', generation: 2, alertname: 'pod-restart-loop',
    category_label: '基础设施', storage: 'ARCHIVED', archive_status: 'AVAILABLE',
    date: '2026-05-14', fault_type: 'OOM_KILLED', duration: '42m',
    evidence_count: 11, manifest_id: 'm-62d…',
  },
  {
    incident_id: '4ce', generation: 5, alertname: 'mq-timeout',
    category_label: '依赖', storage: 'ARCHIVED', archive_status: 'ARCHIVE_UNAVAILABLE',
    date: '2026-02-03', note: 'catalog 命中，CAS 暂不可读',
  },
]

// 只读历史快照（含 ArchiveManifest 与归档证明）
const historySnapshots = {
  '6bd': {
    incident_id: '6bd', generation: 2, readonly: true,
    conclusion_summary: '订单服务 3 个 Pod 在扩容后持续重启。内存工作集越过 limit，kubelet 记录 OOMKilled；排除节点压力与镜像拉取。最终调整 limit 并回滚批量缓存后恢复。',
    path: '告警实例 → Pod 状态 → 容器退出原因 → 内存曲线 → 发布变更 → Claim 交叉验证 → 修复后观察 15m',
    cost_split: '准入 8s ｜ 调查 31m ｜ 人工确认 7m ｜ 恢复验证 4m',
    conclusion: { fault_type: 'OOM_KILLED', reason_code: 'CONTAINER_MEMORY_LIMIT', reviewer: 'operator-17', report_digest: '7e3a…' },
    manifest: {
      manifest_id: 'm-62d…', evidence_total: 11,
      items: [
        { evidence_id: 'e-301', type: 'metrics_range', window: '09:10–09:45', sha256: 'a81…', cas: true },
        { evidence_id: 'e-302', type: 'kube_event', window: 'gen2', sha256: 'b92…', cas: true },
        { evidence_id: 'e-303', type: 'deploy_change', window: 'release#214', sha256: 'c03…', cas: true },
      ],
    },
    archive_proof: {
      policy: 'RetentionPolicyV1', archived_at: '2026-06-14', object_count: 48,
      schema: 'v4', reread_validation: 'PASS',
    },
    reuse_note: '历史相似案例只可提出假设；若用于当前 Incident，必须在当前时间窗重新取证，不能把旧 Evidence 直接当新证据。',
  },
  '81a': {
    incident_id: '81a', generation: 7, readonly: true,
    conclusion_summary: '支付渠道在发版后扣款失败率升高。定位为渠道侧证书轮换与客户端信任库不一致；回滚客户端配置并补发对账任务后恢复。',
    path: '告警实例 → 失败率分解 → 渠道返回码分布 → 发布变更比对 → 证书链核验 → Claim 交叉验证 → 修复后观察 30m',
    cost_split: '准入 5s ｜ 调查 12m ｜ 人工确认 4m ｜ 恢复验证 2m',
    conclusion: { fault_type: 'PAYMENT_CHARGE_FAILURE', reason_code: 'CHANNEL_CERT_ROTATION', reviewer: 'operator-09', report_digest: '5f1c…' },
    manifest: null,
    archive_proof: null,
    reuse_note: '热数据可直接打开完整快照；归档后按 RetentionPolicyV1 生成 ArchiveManifest 并异地复读校验。',
  },
  '4ce': {
    incident_id: '4ce', generation: 5, readonly: true,
    archive_status: 'ARCHIVE_UNAVAILABLE',
    catalog_meta: {
      manifest_id: 'm-1f8…', date: '2026-02-03', fault_type: 'DEPENDENCY_TIMEOUT',
      object_count: 36, schema: 'v4', cas_endpoint: 'archive-dr/cas',
    },
    note: 'catalog 命中，CAS 暂不可读。归档不可用明确返回 ARCHIVE_UNAVAILABLE，不伪装“无历史”；可查看目录元数据，待 CAS 恢复后再打开快照。',
  },
}

// ---------- 导出（与 API 契约同形，返回 Promise） ----------

export function fetchAlertsSummary() { return delay(alertsSummary) }
export function fetchAlertsFacets() { return delay(alertsFacets) }
export function fetchAlerts() { return delay(alerts) }
export function fetchClassificationDetail(incidentId) { return delay(classificationDetail[incidentId] ?? null) }
export function fetchIncidentDetail(incidentId) { return delay(incidentDetail[incidentId] ?? null) }
export function fetchHistoryFilters() { return delay(historyFilters) }
export function fetchHistoryIncidents() { return delay(historyIncidents) }
export function fetchHistorySnapshot(incidentId) { return delay(historySnapshots[incidentId] ?? null) }
