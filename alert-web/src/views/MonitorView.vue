<template>
  <div class="monitor-page">
    <PageHeader title="监控" subtitle="先看系统可用性与最需要处理的异常，每 30 秒自动刷新">
      <template #actions>
        <span class="updated-at">数据更新于 {{ fmtTime(summary?.generatedAt) }}</span>
        <el-tag v-if="dataStale" type="warning" size="small">数据陈旧</el-tag>
        <el-tag v-if="summaryError" type="danger" size="small" :title="summaryError">摘要刷新失败</el-tag>
        <el-button :loading="refreshing" @click="loadAll">刷新</el-button>
      </template>
    </PageHeader>

    <!-- 第一行：stat 卡（全部真字段，null → '—'；可点击深链对应工作页） -->
    <template v-if="summaryState === 'ok'">
      <div class="stat-row">
        <div class="stat card clickable" @click="go('/runs')">
          <span class="stat-label">活跃调查</span>
          <span class="stat-num">{{ num(summary?.activeRuns) }}</span>
          <span class="stat-sub">进行中的调查 → 调查队列</span>
        </div>
        <div class="stat card clickable" @click="go('/runs')">
          <span class="stat-label">待审查</span>
          <span class="stat-num">{{ num(summary?.awaitingReviewRuns) }}</span>
          <span class="stat-sub">等待人工审查 → 调查队列</span>
        </div>
        <div class="stat card clickable" @click="go('/runs')">
          <span class="stat-label">就绪任务积压</span>
          <span class="stat-num">{{ num(summary?.readyTasks) }}</span>
          <span class="stat-sub">最老等待 {{ fmtWait(summary?.oldestReadyWaitSeconds) }}</span>
        </div>
        <div class="stat card clickable" @click="go('/cases')">
          <span class="stat-label">开放处置</span>
          <span class="stat-num">{{ num(summary?.openCases) }}</span>
          <span class="stat-sub">未闭环处置单 → 处置中心</span>
        </div>
        <div class="stat card clickable" @click="go('/notifications')">
          <span class="stat-label">通知待投 / 24h 失败</span>
          <span class="stat-num">
            {{ num(summary?.notifyOutboxPending) }}
            <span class="slash">/</span>
            <span :style="summary?.notifyOutboxFailed24h > 0 ? 'color: var(--sev-p0)' : ''">{{ num(summary?.notifyOutboxFailed24h) }}</span>
          </span>
          <span class="stat-sub">待投 / 24h 失败 → 值班通知</span>
        </div>
      </div>
    </template>
    <EmptyState v-else-if="summaryState === 'forbidden'" kind="forbidden" />
    <EmptyState v-else-if="summaryState === 'error'" kind="error" description="监控摘要加载失败，请重试" @retry="loadSummary" />
    <div v-else v-loading="true" class="loading-box" />

    <!-- 第二行通栏：近 24h 告警接收/恢复趋势（复用 /v1/overview/summary 的 alertTrend24h） -->
    <div class="card panel">
      <div class="panel-head">
        <span class="panel-title">近 24h 告警接收 / 恢复趋势</span>
        <span class="panel-meta">
          窗口：近 24 小时 ｜ 单位：条/小时 ｜ 最新数据点 {{ fmtTime(lastBucketStart) }}
          <el-tag v-if="trendError" type="danger" size="small">刷新失败，当前为缓存数据</el-tag>
        </span>
      </div>
      <div v-if="trendError" class="stale-note">趋势刷新失败（{{ trendError }}）；下图保留 {{ fmtTime(trendLastSuccessAt) }} 最近成功数据，失败与陈旧独立于顶栏更新时间。</div>
      <template v-if="trendState === 'ok'">
        <VChart v-if="trend.length" :option="trendOption" autoresize class="trend-chart" />
        <EmptyState v-else kind="empty" description="近 24 小时无告警数据" />
      </template>
      <EmptyState v-else-if="trendState === 'forbidden'" kind="forbidden" />
      <EmptyState v-else-if="trendState === 'error'" kind="error" description="告警趋势加载失败，请重试" @retry="loadTrend" />
      <div v-else v-loading="true" class="loading-box" />
    </div>

    <!-- 「主机」区（方案 §三.12）：CPU/内存/磁盘三张小趋势图，消费 /api/metrics/query_range
         白名单代理（全量联通方案 §5.11/§6 B6）；图带时间窗/单位/最新数据点时间。
         分态：已查询但未采集 → 「未采集/未知」；503 → 「监控数据源未配置/不可达」；不造假面板 -->
    <div class="host-grid">
      <div v-for="c in hostCharts" :key="c.key" class="card panel">
        <div class="panel-head">
          <span class="panel-title">{{ c.title }}</span>
          <span class="panel-meta">
            窗口：近 1 小时 ｜ 单位：{{ c.unit }} ｜ 最新数据点 {{ fmtTime(lastPointAt(c)) }}
            <el-tag v-if="c.state === 'ok' && allStale(c)" type="warning" size="small">数据陈旧</el-tag>
            <el-tag v-else-if="c.state === 'ok' && staleSeriesNames(c).length" type="warning" size="small"
              :title="`以下实例最近数据点已超 3 个采集周期：${staleSeriesNames(c).join('、')}`"
            >部分实例陈旧：{{ staleSeriesNames(c).length }}</el-tag>
            <el-tag v-if="c.state === 'ok' && c.lastError" type="danger" size="small">刷新失败，当前为缓存数据</el-tag>
          </span>
        </div>
        <template v-if="c.state === 'ok'">
          <!-- 渲染条件 = 至少一个有效样本点；NaN/空序列与真实零值区分（PAGE-08） -->
          <VChart v-if="validSeries(c).length" :option="hostOption(c)" autoresize class="host-chart" />
          <EmptyState v-else-if="c.series.length" kind="empty"
            description="所选窗口无有效样本：数据源返回空序列或全部为无效数值（未知≠零，不画假曲线）。" />
          <EmptyState v-else kind="empty"
            description="未采集：当前 Prometheus 未接入该主机指标（无 node_exporter 抓取），显示未知而非估算" />
        </template>
        <EmptyState v-else-if="c.state === 'unavailable'" kind="error"
          description="监控数据源未配置/不可达" @retry="loadHost" />
        <EmptyState v-else-if="c.state === 'forbidden'" kind="forbidden" />
        <EmptyState v-else-if="c.state === 'error'" kind="error"
          description="主机指标加载失败，请重试" @retry="loadHost" />
        <div v-else v-loading="true" class="loading-box-sm" />
      </div>
    </div>

    <!-- 「执行器」区（方案 §三.12）：worker 活性表——按租约活动推导，非心跳注册表；
         asOf 必带；窗口内无租约活动 → 空态明示，不编造在线 worker -->
    <div class="card panel">
      <div class="panel-head">
        <span class="panel-title">执行器活性（按租约活动推导，非心跳注册表）</span>
        <span class="panel-meta">
          窗口：近 {{ workers?.windowMinutes ?? 60 }} 分钟 ｜ 数据更新于 {{ fmtTime(workers?.asOf) }}
          <el-tag v-if="workersError" type="danger" size="small">刷新失败，当前为缓存数据</el-tag>
        </span>
      </div>
      <div v-if="workersError" class="stale-note">执行器活性刷新失败（{{ workersError }}）；下表保留 {{ fmtTime(workersLastSuccessAt) }} 最近成功数据，与顶栏摘要更新时间无关。</div>
      <template v-if="workersState === 'ok'">
        <el-table v-if="workersList.length" :data="workersList" size="small">
          <el-table-column label="来源" width="90">
            <template #default="{ row }">
              <el-tag size="small" :type="sourceTagType(row.source)">{{ sourceLabel(row.source) }}</el-tag>
            </template>
          </el-table-column>
          <el-table-column prop="workerId" label="Worker" min-width="200" show-overflow-tooltip />
          <el-table-column label="最近活动" width="170">
            <template #default="{ row }">{{ fmtTime(row.lastActivityAt) }}</template>
          </el-table-column>
          <el-table-column prop="inFlightTasks" label="在飞任务" width="90" align="right" />
        </el-table>
        <EmptyState v-else kind="empty" description="近 60 分钟无 worker 领取/执行记录——当前无在跑调查或评测任务，系统空闲属正常；触发告警调查或发起评测批后，此处自动出现活动记录" />
      </template>
      <EmptyState v-else-if="workersState === 'forbidden'" kind="forbidden" />
      <EmptyState v-else-if="workersState === 'error'" kind="error"
        description="执行器活性加载失败，请重试" @retry="loadWorkers" />
      <div v-else v-loading="true" class="loading-box-sm" />
    </div>

    <!-- 第三行双列：性能概览环比 + 工具调用 TopN（§3.10：环比对比上一滚动 24h 窗口） -->
    <div v-if="summaryState === 'ok'" class="cols">
      <div class="card panel">
        <div class="panel-head">
          <span class="panel-title">模型调用 · 性能概览（近 24h）</span>
          <span class="panel-meta">
            环比上一滚动 24h 窗口（不重叠）
            <el-tag v-if="perf.error" type="danger" size="small">刷新失败，当前为缓存数据</el-tag>
          </span>
        </div>
        <template v-if="perf.state === 'ok'">
          <el-table :data="perfRows" size="small">
            <el-table-column prop="label" label="指标" min-width="110" />
            <el-table-column label="当前" width="120" align="right">
              <template #default="{ row }">
                <span :class="{ 'perf-bad': row.badNow }">{{ row.cur }}</span>
              </template>
            </el-table-column>
            <el-table-column label="上一窗口" width="120" align="right">
              <template #default="{ row }">{{ row.prev }}</template>
            </el-table-column>
            <el-table-column label="环比" width="90" align="right">
              <template #default="{ row }">
                <span :class="deltaClass(row)">{{ row.delta }}</span>
              </template>
            </el-table-column>
          </el-table>
        </template>
        <EmptyState v-else-if="perf.state === 'forbidden'" kind="forbidden" />
        <EmptyState v-else-if="perf.state === 'error'" kind="error" description="性能概览加载失败，请重试" @retry="loadPerf" />
        <div v-else v-loading="true" class="loading-box-sm" />
      </div>
      <div class="card panel">
        <div class="panel-head">
          <span class="panel-title">工具调用 TopN（近 24h）</span>
          <span class="panel-meta">单位：次</span>
        </div>
        <VChart v-if="topTools.length" :option="toolsOption" autoresize class="tools-chart" />
        <EmptyState v-else kind="empty" description="近 24 小时无工具调用记录" />
      </div>
    </div>

    <!-- 第四行双列：分层延迟 + 成本归因（§3.10 Wave4，Datadog LLMObs/Langfuse 同律） -->
    <div class="cols">
      <div class="card panel">
        <div class="panel-head">
          <span class="panel-title">分层延迟（近 24h）</span>
          <span class="panel-meta">
            单位：毫秒 ｜ P50/P95 = percentile_cont 直出 ｜ 任务层 = 终态就绪→落定
            <el-tag v-if="latency.error" type="danger" size="small">刷新失败，当前为缓存数据</el-tag>
          </span>
        </div>
        <template v-if="latency.state === 'ok'">
          <el-table :data="latencyRows" size="small">
            <el-table-column prop="layer" label="层" min-width="170" />
            <el-table-column prop="calls" label="完结数" width="90" align="right" />
            <el-table-column label="P50" width="110" align="right">
              <template #default="{ row }">{{ fmtMs(row.p50) }}</template>
            </el-table-column>
            <el-table-column label="P95" width="110" align="right">
              <template #default="{ row }">{{ fmtMs(row.p95) }}</template>
            </el-table-column>
          </el-table>
        </template>
        <EmptyState v-else-if="latency.state === 'forbidden'" kind="forbidden" />
        <EmptyState v-else-if="latency.state === 'error'" kind="error" description="分层延迟加载失败，请重试" @retry="loadLatency" />
        <div v-else v-loading="true" class="loading-box-sm" />
      </div>
      <div class="card panel">
        <div class="panel-head">
          <span class="panel-title">成本归因（近 24h）</span>
          <span class="panel-meta">
            定价回算真值（model_pricing 定价表）
            <el-tag v-if="costs.error" type="danger" size="small">刷新失败，当前为缓存数据</el-tag>
          </span>
        </div>
        <template v-if="costs.state === 'ok'">
          <div v-if="costModels.length" class="llm-nums">
            <div class="llm-item">
              <span class="llm-num">{{ fmtCost(costs.data?.totalCostMicros) }}</span>
              <span class="llm-label">模型总成本{{ costs.data?.currency ? `（${costs.data.currency}）` : '' }}</span>
            </div>
            <div v-if="costs.data?.unpricedCalls > 0" class="llm-item">
              <span class="llm-num">{{ costs.data.unpricedCalls }}</span>
              <span class="llm-label">无定价调用（不计入总额）</span>
            </div>
          </div>
          <VChart v-if="costModels.length" :option="costOption" autoresize class="tools-chart" />
          <EmptyState v-else kind="empty" description="近 24 小时无可计价模型调用——只统计成功且回报 token 用量的调用；欠费/失败的调用不产生费用" />
        </template>
        <EmptyState v-else-if="costs.state === 'forbidden'" kind="forbidden" />
        <EmptyState v-else-if="costs.state === 'error'" kind="error" description="成本归因加载失败，请重试" @retry="loadCosts" />
        <div v-else v-loading="true" class="loading-box-sm" />
      </div>
    </div>

    <!-- 第五行通栏：风险审计流（§3.10 Wave4，Datadog 护栏审计/Rootly 动作审计同律） -->
    <div class="card panel">
      <div class="panel-head">
        <span class="panel-title">风险审计（近 7 天）</span>
        <span class="panel-meta">
          Guardian 复核 / 审批拒绝 / 隔离与死信命中 ｜ 有 run 锚可跳调查轨迹
          <el-tag v-if="risk.error" type="danger" size="small">刷新失败，当前为缓存数据</el-tag>
        </span>
      </div>
      <template v-if="risk.state === 'ok'">
        <el-table v-if="riskList.length" :data="riskList" size="small">
          <el-table-column label="时间" width="170">
            <template #default="{ row }">{{ fmtTime(row.at) }}</template>
          </el-table-column>
          <el-table-column label="类型" width="130">
            <template #default="{ row }">
              <el-tag size="small" :type="riskTagType(row.kind)">{{ RISK_KIND_ZH[row.kind] ?? row.kind }}</el-tag>
            </template>
          </el-table-column>
          <el-table-column prop="title" label="事件" min-width="320" show-overflow-tooltip />
          <el-table-column label="轨迹" width="100">
            <template #default="{ row }">
              <el-link v-if="row.runId" type="primary" @click="go('/runs/' + row.runId)">查看 run</el-link>
              <span v-else class="risk-norun">—</span>
            </template>
          </el-table-column>
        </el-table>
        <EmptyState v-else kind="empty" description="近 7 天无风险审计事件（Guardian 复核/审批拒绝/隔离与死信均未命中）" />
      </template>
      <EmptyState v-else-if="risk.state === 'forbidden'" kind="forbidden" />
      <EmptyState v-else-if="risk.state === 'error'" kind="error" description="风险审计加载失败，请重试" @retry="loadRisk" />
      <div v-else v-loading="true" class="loading-box-sm" />
    </div>

    <!-- 未接入维度：外部探针/来源异常等指标未采集，显式说明，不造假面板；
         主机区已接入白名单代理——当前栈 Prometheus 未抓 node_exporter，按「未采集」显示 -->
    <el-alert type="info" :closable="false" show-icon
      title="未接入维度：外部探针、来源异常等指标尚未接入采集，本页不展示对应面板；未采集显示未知，不以模拟数据填充。"
    />
  </div>
</template>

<script setup>
// UI-5 监控大盘（/monitor）：全部真端点
// 数据：/agent-ops/summary（stat 卡 + 模型调用 + 工具 TopN）、/v1/overview/summary 的
// alertTrend24h（趋势图）；方案 §三.12 两区——「主机」区走 /metrics/query_range
// 白名单代理（§5.11/§6 B6，键固定枚举，禁任意 PromQL），「执行器」区走
// /agent-ops/workers（按租约活动推导，非心跳注册表）
// 轮询 30s，页面隐藏（visibilitychange）时停止；generatedAt 超过 90s 未更新标记「数据陈旧」
import { computed, onBeforeUnmount, onMounted, reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { api } from '../api/client'
import PageHeader from '../components/common/PageHeader.vue'
import EmptyState from '../components/common/EmptyState.vue'
import { RISK_KIND_ZH } from '../dict/zh'
import { fmtTime } from '../utils/format'
// RV11：图表组件局部化（echarts 按需注册随页面懒加载，首包不再携带大依赖）
import { useChart } from '../composables/echarts'

const VChart = useChart()

const router = useRouter()

const summary = ref(null)
const summaryState = ref('loading') // loading | ok | error | forbidden
const summaryError = ref('') // 有数据但刷新失败：保留旧图 + 显式失败标记（PAGE-08）
const trend = ref([])
const trendState = ref('loading')
const trendError = ref('')
const trendLastSuccessAt = ref(null)
const workersError = ref('')
const workersLastSuccessAt = ref(null)
const refreshing = ref(false)
const now = ref(Date.now())
let timer = null

// 「主机」区（§三.12）：白名单键固定三张卡，窗口近 1 小时、step 60s
// 每卡 state: loading | ok | unavailable(503) | error | forbidden；
// lastError/lastSuccessAt 让"刷新失败"与"数据陈旧"两个维度独立可见（PAGE-08）
const HOST_WINDOW_SEC = 3600
const HOST_STEP_SEC = 60
const hostCharts = ref([
  { key: 'host_cpu_usage', title: '主机 CPU 使用率', unit: '%', state: 'loading', series: [], asOf: null, lastError: '', lastSuccessAt: null },
  { key: 'host_mem_usage', title: '主机内存使用率', unit: '%', state: 'loading', series: [], asOf: null, lastError: '', lastSuccessAt: null },
  { key: 'host_disk_usage', title: '主机磁盘使用率（按挂载点）', unit: '%', state: 'loading', series: [], asOf: null, lastError: '', lastSuccessAt: null },
])

// 「执行器」区（§三.12）：workers 应答 + asOf；state 同 summary 族
const workers = ref(null)
const workersState = ref('loading')
const workersList = computed(() => workers.value?.workers ?? [])

const topTools = computed(() => summary.value?.topTools24h ?? [])
const lastBucketStart = computed(() => trend.value.length ? trend.value[trend.value.length - 1].bucketStart : null)

// §3.10 Wave4：分层延迟四层行（层名全中文；null → '—' 由 fmtMs 兜底）
const latencyRows = computed(() => {
  const d = latency.data
  if (!d) return []
  return [
    { layer: '端到端（调查 run）', calls: d.runs, p50: d.runP50Ms, p95: d.runP95Ms },
    { layer: '任务（终态就绪→落定）', calls: d.taskCalls, p50: d.taskP50Ms, p95: d.taskP95Ms },
    { layer: '模型调用', calls: d.llmCalls, p50: d.llmP50Ms, p95: d.llmP95Ms },
    { layer: '工具调用', calls: d.toolCalls, p50: d.toolP50Ms, p95: d.toolP95Ms },
  ]
})
const costModels = computed(() => costs.data?.models ?? [])
const riskList = computed(() => risk.data ?? [])

// 成本占比横条：cost_micros → 主币种值（同 V129 回算口径 /1e6）
const costOption = computed(() => ({
  grid: { left: 8, right: 90, top: 8, bottom: 8, containLabel: true },
  tooltip: {
    trigger: 'axis', axisPointer: { type: 'shadow' },
    valueFormatter: v => `${v} ${costs.data?.currency ?? ''}`,
  },
  xAxis: { type: 'value' },
  yAxis: { type: 'category', data: costModels.value.map(m => m.model).reverse() },
  series: [{
    type: 'bar', barMaxWidth: 16,
    itemStyle: { color: '#409EFF', borderRadius: [0, 3, 3, 0] },
    label: {
      show: true, position: 'right', color: '#5b6572',
      formatter: p => `${p.value} ${costs.data?.currency ?? ''}`,
    },
    data: costModels.value.map(m => +(m.costMicros / 1e6).toFixed(4)).reverse(),
  }],
}))

// 毫秒人性化：null → '—'；≥1s 折秒
function fmtMs(v) {
  if (v == null || Number.isNaN(Number(v))) return '—'
  const n = Number(v)
  return n >= 1000 ? `${(n / 1000).toFixed(1)} 秒` : `${Math.round(n)} ms`
}

// 环比双窗行（§3.10）：调用/Token/成本/平均延迟/失败；成本与失败上升=坏（红）
const perfRows = computed(() => {
  const c = perf.data?.current
  const p = perf.data?.previous
  if (!c) return []
  return [
    { label: '调用次数', cur: num(c.calls), prev: num(p?.calls), delta: pct(c.calls, p?.calls) },
    { label: 'Token 消耗', cur: fmtTokens(c.tokens), prev: fmtTokens(p?.tokens), delta: pct(c.tokens, p?.tokens) },
    { label: '成本（元）', cur: fmtCost(c.costMicros), prev: fmtCost(p?.costMicros), delta: pct(c.costMicros, p?.costMicros), badWhenUp: true },
    { label: '平均延迟', cur: fmtMs(c.avgLatencyMs), prev: fmtMs(p?.avgLatencyMs), delta: pct(c.avgLatencyMs, p?.avgLatencyMs), badWhenUp: true },
    { label: '失败次数', cur: num(c.errors), prev: num(p?.errors), delta: pct(c.errors, p?.errors), badWhenUp: true, badNow: c.errors > 0 },
  ]
})
function pct(cur, prev) {
  if (cur == null || prev == null || Number(prev) === 0) return '—'
  const v = Math.round((Number(cur) - Number(prev)) * 100 / Number(prev))
  return v > 0 ? `+${v}%` : `${v}%`
}
function deltaClass(row) {
  if (!row.delta || row.delta === '—') return ''
  if (row.badWhenUp) return row.delta.startsWith('+') ? 'delta-bad' : 'delta-good'
  return row.delta.startsWith('+') ? 'delta-up' : 'delta-down'
}
// 微单位成本 → 主币种 4 位小数（V129 回算口径）；null → '—'
function fmtCost(micros) {
  if (micros == null || Number.isNaN(Number(micros))) return '—'
  return (Number(micros) / 1e6).toFixed(4)
}

const RISK_TAG_TYPES = { GUARDIAN: 'primary', APPROVAL_REJECTED: 'danger', QUARANTINE: 'warning', DEAD_LETTER: 'info' }
function riskTagType(k) { return RISK_TAG_TYPES[k] ?? 'info' }

// 数据新鲜度：generatedAt 距今超过 90s（≈3 个轮询周期）视为陈旧
const dataStale = computed(() => {
  const t = new Date(summary.value?.generatedAt ?? '').getTime()
  if (Number.isNaN(t)) return false
  return now.value - t > 90_000
})

async function loadSummary() {
  try {
    summary.value = await api('/agent-ops/summary')
    summaryState.value = 'ok'
    summaryError.value = ''
  } catch (e) {
    // 已有旧数据时保留展示并显式标失败（与顶栏 generatedAt 独立），仅首屏失败进错误态
    if (summary.value) {
      summaryError.value = e?.response?.data?.error || '请求失败'
    } else {
      summaryState.value = e?.response?.status === 403 ? 'forbidden' : 'error'
    }
  }
}

async function loadTrend() {
  try {
    const d = await api('/v1/overview/summary')
    trend.value = d.alertTrend24h ?? []
    trendState.value = 'ok'
    trendError.value = ''
    trendLastSuccessAt.value = new Date().toISOString()
  } catch (e) {
    if (trend.value.length) {
      trendError.value = e?.response?.data?.error || '请求失败'
    } else {
      trendState.value = e?.response?.status === 403 ? 'forbidden' : 'error'
    }
  }
}

// 主机三维：一次窗内三键并发；503 → unavailable（「监控数据源未配置/不可达」），
// 已有旧序列时保留展示并标"刷新失败，当前为缓存数据"（失败≠陈旧，两维独立）
async function loadHost() {
  const end = Math.floor(Date.now() / 1000)
  const start = end - HOST_WINDOW_SEC
  await Promise.all(hostCharts.value.map(async c => {
    try {
      const d = await api('/metrics/query_range', {
        params: { query: c.key, start, end, step: HOST_STEP_SEC },
      })
      c.series = d.series ?? []
      c.unit = d.unit ?? c.unit
      c.asOf = d.asOf ?? null
      c.state = 'ok'
      c.lastError = ''
      c.lastSuccessAt = new Date().toISOString()
    } catch (e) {
      if (c.series.length) {
        c.lastError = e?.response?.data?.error || '请求失败'
      } else {
        const st = e?.response?.status
        c.state = st === 403 ? 'forbidden' : st === 503 ? 'unavailable' : 'error'
      }
    }
  }))
}

async function loadWorkers() {
  try {
    workers.value = await api('/agent-ops/workers', { params: { windowMinutes: 60 } })
    workersState.value = 'ok'
    workersError.value = ''
    workersLastSuccessAt.value = new Date().toISOString()
  } catch (e) {
    if (workers.value) {
      workersError.value = e?.response?.data?.error || '请求失败'
    } else {
      workersState.value = e?.response?.status === 403 ? 'forbidden' : 'error'
    }
  }
}

// §3.10 Wave4 三区块（分层延迟/成本归因/风险审计）：分态同 PAGE-08 律——
// 首屏失败进错误态；有旧数据保留展示 + panel-meta 显式「刷新失败」标（失败≠陈旧）
function panel() {
  const p = reactive({ data: null, state: 'loading', error: '' })
  p.load = async loader => {
    try {
      p.data = await loader()
      p.state = 'ok'
      p.error = ''
    } catch (e) {
      if (p.data != null) {
        p.error = e?.response?.data?.error || '请求失败'
      } else {
        p.state = e?.response?.status === 403 ? 'forbidden' : 'error'
      }
    }
  }
  return p
}
const latency = panel()
const costs = panel()
const risk = panel()
const perf = panel()
const loadLatency = () => latency.load(() => api('/agent-ops/latency-layers'))
const loadCosts = () => costs.load(() => api('/agent-ops/costs'))
const loadRisk = () => risk.load(() => api('/agent-ops/risk-events'))
const loadPerf = () => perf.load(() => api('/agent-ops/perf-trend'))

async function loadAll() {
  refreshing.value = true
  try {
    await Promise.all([loadSummary(), loadTrend(), loadHost(), loadWorkers(),
      loadLatency(), loadCosts(), loadRisk(), loadPerf()])
  } finally {
    refreshing.value = false
    now.value = Date.now()
  }
}

function go(path) { router.push({ path }) }

function num(v) { return v == null ? '—' : v }

// 有效点：值存在且有限（NaN/Inf 点后端已剔除，前端二次防御——缺失≠零≠有效）
const hasValidPoints = s => (s.points ?? []).some(p => p.value != null && Number.isFinite(p.value))
const validSeries = c => c.series.filter(hasValidPoints)

// 序列最新有效点（epoch 秒 → ms，供 fmtTime）；无有效点 → null → '—'
function lastPointAt(c) {
  let latest = null
  for (const s of validSeries(c)) {
    for (const p of s.points ?? []) {
      if ((p.value == null || !Number.isFinite(p.value))) continue
      if (latest == null || p.epochSec > latest) latest = p.epochSec
    }
  }
  return latest == null ? null : latest * 1000
}

// 新鲜度按序列判断（PAGE-08）：单实例停更不再被另一新鲜实例掩盖
const STALE_CUTOFF_MS = HOST_STEP_SEC * 3 * 1000
function staleSeriesNames(c) {
  const cutoff = now.value - STALE_CUTOFF_MS
  return validSeries(c)
    .filter(s => Math.max(...(s.points ?? []).filter(p => p.value != null && Number.isFinite(p.value)).map(p => p.epochSec), 0) * 1000 < cutoff)
    .map(s => s.name)
}
const allStale = c => {
  const valid = validSeries(c)
  return valid.length > 0 && staleSeriesNames(c).length === valid.length
}

const SOURCE_LABELS = { rca: '调查', eval: '评测', drill: '演练' }
function sourceLabel(s) { return SOURCE_LABELS[s] ?? s }
function sourceTagType(s) { return { rca: 'primary', eval: 'success', drill: 'warning' }[s] ?? 'info' }

// 等待时长：null → '—'；不足 1 分钟按 1 分钟；超 1 小时显示小时
function fmtWait(sec) {
  if (sec == null || Number.isNaN(Number(sec))) return '—'
  const s = Number(sec)
  if (s < 60) return '不足 1 分钟'
  if (s < 3600) return `${Math.round(s / 60)} 分钟`
  return `${(s / 3600).toFixed(1)} 小时`
}

function fmtTokens(n) {
  if (n == null || Number.isNaN(Number(n))) return '—'
  const v = Number(n)
  return v >= 10000 ? `${(v / 10000).toFixed(1)} 万` : String(v)
}

function fmtBucket(iso) {
  const d = new Date(iso)
  if (Number.isNaN(d.getTime())) return ''
  return `${String(d.getHours()).padStart(2, '0')}:${String(d.getMinutes()).padStart(2, '0')}`
}

const trendOption = computed(() => ({
  grid: { left: 8, right: 16, top: 36, bottom: 8, containLabel: true },
  tooltip: { trigger: 'axis' },
  legend: { data: ['接收', '恢复'], top: 0, right: 0 },
  xAxis: { type: 'category', boundaryGap: false, data: trend.value.map(b => fmtBucket(b.bucketStart)) },
  yAxis: { type: 'value', minInterval: 1 },
  series: [
    { name: '接收', type: 'line', smooth: true, showSymbol: false, areaStyle: { opacity: 0.15 }, data: trend.value.map(b => b.received ?? 0) },
    { name: '恢复', type: 'line', smooth: true, showSymbol: false, areaStyle: { opacity: 0.15 }, data: trend.value.map(b => b.resolved ?? 0) },
  ],
}))

const toolsOption = computed(() => ({
  grid: { left: 8, right: 32, top: 8, bottom: 8, containLabel: true },
  tooltip: { trigger: 'axis', axisPointer: { type: 'shadow' } },
  xAxis: { type: 'value', minInterval: 1 },
  yAxis: { type: 'category', data: topTools.value.map(t => t.tool).reverse() },
  series: [{
    type: 'bar', barMaxWidth: 16,
    itemStyle: { color: '#409EFF', borderRadius: [0, 3, 3, 0] },
    label: { show: true, position: 'right', color: '#5b6572' },
    data: topTools.value.map(t => t.calls ?? 0).reverse(),
  }],
}))

// 主机小趋势图：x 轴时间（epoch 秒 → ms），一序列一条线（多实例并排）；
// 只画含有效点的序列；多序列时才出图例，单位打在 y 轴标签上（面板 meta 同时注明窗口/单位/最新点）
function hostOption(c) {
  const series = validSeries(c)
  return {
    grid: { left: 8, right: 16, top: series.length > 1 ? 30 : 12, bottom: 8, containLabel: true },
    tooltip: { trigger: 'axis' },
    legend: series.length > 1 ? { data: series.map(s => s.name), top: 0, right: 0 } : undefined,
    xAxis: { type: 'time' },
    yAxis: { type: 'value', axisLabel: { formatter: v => `${v}${c.unit}` } },
    series: series.map(s => ({
      name: s.name, type: 'line', smooth: true, showSymbol: false,
      areaStyle: { opacity: 0.12 },
      data: (s.points ?? [])
        .filter(p => p.value != null && Number.isFinite(p.value))
        .map(p => [p.epochSec * 1000, p.value]),
    })),
  }
}

function startTimer() {
  stopTimer()
  timer = setInterval(loadAll, 30000)
}
function stopTimer() {
  if (timer) { clearInterval(timer); timer = null }
}
// 页面隐藏时停止轮询，回到前台立即补一次刷新
function onVisibility() {
  if (document.hidden) stopTimer()
  else { loadAll(); startTimer() }
}

onMounted(() => {
  loadAll()
  startTimer()
  document.addEventListener('visibilitychange', onVisibility)
})
onBeforeUnmount(() => {
  stopTimer()
  document.removeEventListener('visibilitychange', onVisibility)
})
</script>

<style scoped>
.monitor-page { display: flex; flex-direction: column; gap: var(--section-gap); }

.updated-at { font-size: var(--fs-aux); color: var(--ink-2); align-self: center; }

.stale-note { font-size: var(--fs-aux); color: var(--warn, #b26a00); margin-bottom: 8px; }

/* stat 卡行 */
.stat-row { display: grid; grid-template-columns: repeat(auto-fit, minmax(160px, 1fr)); gap: 12px; }
.stat { padding: 12px 16px; display: flex; flex-direction: column; gap: 2px; }
.stat.clickable { cursor: pointer; transition: box-shadow .15s; }
.stat.clickable:hover { box-shadow: 0 0 0 2px var(--brand-soft); }
.stat-label { font-size: var(--fs-aux); color: var(--ink-2); }
.stat-num { font-size: 22px; font-weight: 700; color: var(--head); line-height: 1.3; }
.stat-num .slash { color: var(--ink-2); font-weight: 400; margin: 0 2px; }
.stat-sub { font-size: var(--fs-aux); color: var(--ink-2); }

/* 面板 */
.panel { padding: 12px var(--card-pad); }
.panel-head { display: flex; align-items: baseline; justify-content: space-between; gap: 12px; flex-wrap: wrap; margin-bottom: 8px; }
.panel-title { font-size: var(--fs-body); font-weight: 600; color: var(--head); }
.panel-meta { font-size: var(--fs-aux); color: var(--ink-2); }

.cols { display: grid; grid-template-columns: 1fr 1fr; gap: 12px; }
@media (max-width: 1100px) { .cols { grid-template-columns: 1fr; } }

.llm-nums { display: flex; gap: 48px; padding: 12px 0 4px; }
.llm-item { display: flex; flex-direction: column; gap: 2px; }
.llm-num { font-size: 28px; font-weight: 700; color: var(--head); line-height: 1.2; }
.llm-label { font-size: var(--fs-aux); color: var(--ink-2); }

.trend-chart { height: 280px; }
.tools-chart { height: 220px; }
.loading-box { height: 160px; }

/* 「主机」区三卡（§三.12） */
.host-grid { display: grid; grid-template-columns: repeat(3, 1fr); gap: 12px; }
@media (max-width: 1100px) { .host-grid { grid-template-columns: 1fr; } }
.host-chart { height: 180px; }
.loading-box-sm { height: 120px; }

/* 风险审计无 run 锚行的占位 */
.risk-norun { color: var(--ink-2); }

/* 环比语义色：成本/失败上升=坏（红），下降=好（绿）；调用/Token 中性 */
.delta-bad { color: var(--sev-p0); font-weight: 600; }
.delta-good { color: var(--ok); }
.delta-up, .delta-down { color: var(--ink-2); }
.perf-bad { color: var(--sev-p0); font-weight: 600; }
</style>
