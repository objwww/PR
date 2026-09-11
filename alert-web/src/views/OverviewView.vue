<template>
  <div class="overview-page">
    <PageHeader title="工作总览" subtitle="值班人员十秒内知道现在要处理什么">
      <template #actions>
        <span class="duty-chip">当前值班：{{ dutyText }}</span>
        <span class="updated-at">数据更新至 {{ updatedAtText }}</span>
      </template>
    </PageHeader>

    <!-- KPI 卡行：整卡为 router-link（键盘可达、链接语义），数字下最多一行解释 -->
    <div class="kpi-row">
      <router-link
        v-for="k in kpis" :key="k.label"
        :to="k.to" class="kpi card"
      >
        <span class="kpi-label">{{ k.label }}</span>
        <span class="kpi-num" :style="{ color: k.color }">{{ k.value }}</span>
        <span class="kpi-desc">{{ k.desc }}</span>
      </router-link>
    </div>

    <!-- 通栏：近 24 小时告警趋势（received/resolved 双系列面积图） -->
    <div class="card panel">
      <div class="panel-title">近 24 小时告警趋势</div>
      <template v-if="trendState === 'ok'">
        <VChart v-if="hasTrend" :option="trendOption" autoresize class="trend-chart" />
        <EmptyState v-else description="近 24 小时暂无告警趋势数据" />
      </template>
      <EmptyState v-else-if="trendState === 'forbidden'" kind="forbidden" />
      <EmptyState v-else-if="trendState === 'error'" kind="error" @retry="loadSummary" />
      <div v-else v-loading="true" class="loading-box" />
    </div>

    <!-- 双列：左 2/3 按风险待办（§三.1：severity 风险序 Top5，消费 overview/summary.topRisk），右 1/3 交接摘要 + 系统健康 -->
    <div class="grid-2col">
      <div class="card panel">
        <div class="panel-head">
          <span class="panel-title">按风险待办（Top 5）</span>
          <router-link class="more-link" to="/alerts?status=FIRING">查看全部</router-link>
        </div>
        <template v-if="todoState === 'ok'">
          <IncidentTable :rows="topRisk" :loading="summaryState === 'loading'" @row-click="openIncident">
            <template #actions="{ row }">
              <el-button size="small" @click.stop="openIncident(row)">打开</el-button>
            </template>
            <template #empty>
              <EmptyState description="当前没有待处理的告警" />
            </template>
          </IncidentTable>
        </template>
        <EmptyState v-else-if="todoState === 'forbidden'" kind="forbidden" />
        <EmptyState v-else-if="todoState === 'error'" kind="error" @retry="loadSummary" />
        <div v-else v-loading="true" class="loading-box" />
      </div>

      <div class="side-col">
        <!-- 交接摘要：无真数据源，如实空态 -->
        <div class="card panel">
          <div class="panel-title">交接摘要</div>
          <EmptyState description="暂无交接记录" :image-size="80" />
        </div>
        <!-- 系统健康：无真数据源，如实空态 -->
        <div class="card panel">
          <div class="panel-title">系统健康</div>
          <EmptyState description="健康数据未接入" :image-size="80" />
        </div>
      </div>
    </div>
  </div>
</template>

<script setup>
// UI-2 总览（/overview）：KPI 卡（可点击深跳）+ 24h 趋势面积图 + 按风险待办 Top5 + 交接/健康空态
// §三.1：首行三行动指标 = 待处理 / 调查异常 / 通知失败 24h；主区待办消费 topRisk（severity 风险序）
// 数据全真：GET /v1/overview/summary（KPI/趋势/值班/待办 单端点聚合）
// 字段可 null → 显「—」；交接与健康无真数据源，EmptyState 如实说明
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { api } from '../api/client'
import PageHeader from '../components/common/PageHeader.vue'
import EmptyState from '../components/common/EmptyState.vue'
import IncidentTable from '../components/IncidentTable.vue'
import { fmtTime } from '../utils/format'

const router = useRouter()

const summary = ref(null)
const summaryState = ref('loading') // loading | ok | error | forbidden
const updatedAt = ref(null)

let timer = null

const num = v => (typeof v === 'number' && Number.isFinite(v) ? v : '—')

const dutyText = computed(() => summary.value?.duty?.oncall || '—')
const updatedAtText = computed(() => fmtTime(updatedAt.value))

const kpis = computed(() => {
  const s = summary.value
  const stuck = s?.runs?.stuck
  const failed24h = s?.runs?.failed24h
  const abnormal = (typeof stuck === 'number' && typeof failed24h === 'number')
    ? stuck + failed24h
    : (typeof stuck === 'number' ? stuck : (typeof failed24h === 'number' ? failed24h : null))
  return [
    {
      label: '待处理告警',
      value: num(s?.firingIncidents),
      desc: '当前处于告警中的事故',
      to: '/alerts?status=FIRING',
      color: s?.firingIncidents > 0 ? 'var(--sev-p0)' : 'var(--head)',
    },
    {
      label: '调查异常',
      value: abnormal == null ? '—' : abnormal,
      desc: `卡住 ${num(stuck)} ｜ 24h 失败 ${num(failed24h)}`,
      to: '/runs?tab=intervene',
      color: abnormal > 0 ? 'var(--sev-p1)' : 'var(--head)',
    },
    {
      // §三.1 通知失败 24h：后端 notifications.failed24h（notify_outbox DEAD 口径）；
      // 旧契约缺席 → num() 显「—」，不显 0
      label: '通知失败 24h',
      value: num(s?.notifications?.failed24h),
      desc: '近 24 小时投递终败的通知',
      to: '/notifications',
      color: s?.notifications?.failed24h > 0 ? 'var(--sev-p1)' : 'var(--head)',
    },
  ]
})

// §三.1 按风险待办：IncidentRow 形状（severity/分类徽章由 IncidentTable 内 StatusBadge/CategoryBadge 渲染）
const topRisk = computed(() => summary.value?.topRisk ?? [])
const todoState = computed(() => summaryState.value)

const trend = computed(() => summary.value?.alertTrend24h ?? [])
const trendState = computed(() => summaryState.value)
const hasTrend = computed(() => trend.value.some(b => (b?.received ?? 0) > 0 || (b?.resolved ?? 0) > 0))

const trendOption = computed(() => ({
  grid: { left: 40, right: 16, top: 32, bottom: 28 },
  legend: { data: ['接收', '解决'], top: 0, right: 0 },
  tooltip: { trigger: 'axis' },
  xAxis: {
    type: 'category',
    boundaryGap: false,
    data: trend.value.map(b => fmtTime(b?.bucketStart).slice(5, 16)),
  },
  yAxis: { type: 'value', minInterval: 1 },
  series: [
    {
      name: '接收', type: 'line', smooth: true, showSymbol: false,
      data: trend.value.map(b => b?.received ?? 0),
      lineStyle: { color: '#409EFF' }, itemStyle: { color: '#409EFF' },
      areaStyle: { opacity: 0.15 },
    },
    {
      name: '解决', type: 'line', smooth: true, showSymbol: false,
      data: trend.value.map(b => b?.resolved ?? 0),
      lineStyle: { color: '#23C343' }, itemStyle: { color: '#23C343' },
      areaStyle: { opacity: 0.15 },
    },
  ],
}))

async function loadSummary() {
  summaryState.value = 'loading'
  try {
    summary.value = await api('/v1/overview/summary')
    updatedAt.value = new Date().toISOString()
    summaryState.value = 'ok'
  } catch (e) {
    summaryState.value = e?.response?.status === 403 ? 'forbidden' : 'error'
  }
}

function openIncident(row) { router.push(`/alerts/${row.incidentId}`) }

function refresh() { loadSummary() }

onMounted(() => {
  refresh()
  timer = setInterval(refresh, 30000) // 轮询 30s，不接 SSE
})
onBeforeUnmount(() => clearInterval(timer))
</script>

<style scoped>
.overview-page { display: flex; flex-direction: column; gap: var(--section-gap); }

.duty-chip {
  font-size: var(--fs-body); color: var(--head); font-weight: 600;
  background: var(--brand-soft); border-radius: 999px; padding: 4px 14px;
}
.updated-at { font-size: var(--fs-aux); color: var(--ink-2); align-self: center; }

/* KPI 卡：整卡为 <a>，hover/focus 一致高亮 */
.kpi-row { display: grid; grid-template-columns: repeat(auto-fit, minmax(180px, 1fr)); gap: 12px; }
.kpi {
  padding: 14px 16px; display: flex; flex-direction: column; gap: 2px;
  color: inherit; transition: box-shadow .15s;
}
.kpi:hover, .kpi:focus-visible { box-shadow: 0 0 0 2px var(--brand-soft); }
.kpi:focus-visible { outline: none; box-shadow: 0 0 0 2px var(--brand); }
.kpi-label { font-size: var(--fs-aux); color: var(--ink-2); }
.kpi-num { font-size: 26px; font-weight: 700; color: var(--head); line-height: 1.3; }
.kpi-desc { font-size: var(--fs-aux); color: var(--ink-2); }

/* 面板通用 */
.panel { padding: 12px var(--card-pad) 16px; }
.panel-title { font-size: var(--fs-section); font-weight: 600; color: var(--head); }
.panel-head { display: flex; justify-content: space-between; align-items: center; }
.more-link { font-size: var(--fs-aux); }
.trend-chart { height: 280px; margin-top: 8px; }
.loading-box { height: 200px; }

/* 双列 2/3 + 1/3 */
.grid-2col { display: flex; align-items: flex-start; gap: var(--section-gap); }
.grid-2col > .panel { flex: 2; min-width: 0; }
.side-col { flex: 1; min-width: 0; display: flex; flex-direction: column; gap: var(--section-gap); }

@media (max-width: 1100px) {
  .grid-2col { flex-direction: column; }
  .grid-2col > .panel, .side-col { width: 100%; flex: none; }
}
</style>
