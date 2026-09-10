<template>
  <div class="monitor-page">
    <PageHeader title="监控" subtitle="先看系统可用性与最需要处理的异常，每 30 秒自动刷新">
      <template #actions>
        <span class="updated-at">数据更新于 {{ fmtTime(summary?.generatedAt) }}</span>
        <el-tag v-if="dataStale" type="warning" size="small">数据陈旧</el-tag>
        <el-button :loading="refreshing" @click="loadAll">刷新</el-button>
      </template>
    </PageHeader>

    <!-- 第一行：stat 卡（全部真字段，null → '—'；可点击深链对应工作页） -->
    <template v-if="summaryState === 'ok'">
      <div class="stat-row">
        <div class="stat card clickable" @click="go('/runs')">
          <span class="stat-label">活跃调查</span>
          <span class="stat-num">{{ num(summary?.activeRuns) }}</span>
          <span class="stat-sub">进行中 Run → 调查队列</span>
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
          <span class="stat-sub">未闭环 Case → 处置中心</span>
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
        </span>
      </div>
      <template v-if="trendState === 'ok'">
        <VChart v-if="trend.length" :option="trendOption" autoresize class="trend-chart" />
        <EmptyState v-else kind="empty" description="近 24 小时无告警数据" />
      </template>
      <EmptyState v-else-if="trendState === 'forbidden'" kind="forbidden" />
      <EmptyState v-else-if="trendState === 'error'" kind="error" description="告警趋势加载失败，请重试" @retry="loadTrend" />
      <div v-else v-loading="true" class="loading-box" />
    </div>

    <!-- 第三行双列：模型调用 + 工具调用 TopN（同属 agent-ops/summary，错误态共用上方空态） -->
    <div v-if="summaryState === 'ok'" class="cols">
      <div class="card panel">
        <div class="panel-head">
          <span class="panel-title">模型调用（近 24h）</span>
          <span class="panel-meta">单位：次 / token</span>
        </div>
        <div class="llm-nums">
          <div class="llm-item">
            <span class="llm-num">{{ num(summary?.llmCalls24h) }}</span>
            <span class="llm-label">调用次数</span>
          </div>
          <div class="llm-item">
            <span class="llm-num">{{ fmtTokens(summary?.tokens24h) }}</span>
            <span class="llm-label">Token 消耗</span>
          </div>
        </div>
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

    <!-- 未接入维度：主机/探针等指标未采集，显式说明，不造假面板 -->
    <el-alert type="info" :closable="false" show-icon
      title="未接入维度：主机、外部探针、来源异常等指标尚未接入采集，本页不展示对应面板；未采集显示未知，不以模拟数据填充。"
    />
  </div>
</template>

<script setup>
// UI-5 监控大盘（/monitor）：全部真端点
// 数据：/agent-ops/summary（stat 卡 + 模型调用 + 工具 TopN）、/v1/overview/summary 的 alertTrend24h（趋势图）
// 轮询 30s，页面隐藏（visibilitychange）时停止；generatedAt 超过 90s 未更新标记「数据陈旧」
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { api } from '../api/client'
import PageHeader from '../components/common/PageHeader.vue'
import EmptyState from '../components/common/EmptyState.vue'
import { fmtTime } from '../utils/format'

const router = useRouter()

const summary = ref(null)
const summaryState = ref('loading') // loading | ok | error | forbidden
const trend = ref([])
const trendState = ref('loading')
const refreshing = ref(false)
const now = ref(Date.now())
let timer = null

const topTools = computed(() => summary.value?.topTools24h ?? [])
const lastBucketStart = computed(() => trend.value.length ? trend.value[trend.value.length - 1].bucketStart : null)

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
  } catch (e) {
    // 已有旧数据时保留展示（配合「数据陈旧」标记），仅首屏失败进错误态
    if (!summary.value) {
      summaryState.value = e?.response?.status === 403 ? 'forbidden' : 'error'
    }
  }
}

async function loadTrend() {
  try {
    const d = await api('/v1/overview/summary')
    trend.value = d.alertTrend24h ?? []
    trendState.value = 'ok'
  } catch (e) {
    if (!trend.value.length) {
      trendState.value = e?.response?.status === 403 ? 'forbidden' : 'error'
    }
  }
}

async function loadAll() {
  refreshing.value = true
  try { await Promise.all([loadSummary(), loadTrend()]) } finally {
    refreshing.value = false
    now.value = Date.now()
  }
}

function go(path) { router.push({ path }) }

function num(v) { return v == null ? '—' : v }

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
</style>
