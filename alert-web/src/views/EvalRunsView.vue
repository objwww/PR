<template>
  <div class="runs-page">
    <!-- 页头：标题 + 最近更新时间 + 新建实验 -->
    <PageHeader title="实验" :subtitle="`最近更新时间：${lastLoadedAt ? fmtTime(lastLoadedAt) : '—'}`">
      <template #actions>
        <el-button :loading="loading" @click="loadList">刷新</el-button>
        <el-button type="primary" @click="router.push('/eval/new')">新建实验</el-button>
      </template>
    </PageHeader>

    <!-- 三个可点过滤项：执行中 / 待处理 / 已结束（默认全部；待处理=执行失败，不并入质量/费用语义） -->
    <div class="quick-filters card">
      <button
        v-for="f in quickFilters" :key="f.key"
        class="qf" :class="{ cur: state === f.value }"
        @click="applyState(f.value)"
      >{{ f.label }}</button>
      <span class="qf-note">“待处理”当前仅含执行失败实验；质量未达标与费用待对账口径依赖后端 EV-03/EV-06，未并入。</span>
    </div>

    <!-- 筛选栏：名称/ID、数据集、被测资产类型；模式与时间进展开筛选。均依赖后端 EV-03 查询参数，本批禁用 -->
    <div class="filter-bar card">
      <el-input class="w-search" placeholder="按名称 / ID 搜索" disabled />
      <el-select class="w-filter" placeholder="数据集" disabled />
      <el-select class="w-filter" placeholder="被测资产类型" disabled />
      <el-button text @click="moreFiltersOpen = !moreFiltersOpen">
        {{ moreFiltersOpen ? '收起筛选' : '展开筛选' }}
      </el-button>
      <div v-if="moreFiltersOpen" class="more-filters">
        <el-select class="w-filter" placeholder="模式（E / B / L）" disabled />
        <el-date-picker class="w-date" type="daterange" start-placeholder="开始日期" end-placeholder="结束日期" disabled />
      </div>
      <div class="filter-note">搜索与筛选参数依赖后端 EV-03 查询扩展，暂未开放；当前仅支持上方执行状态过滤。</div>
    </div>

    <div class="table-zone card">
      <template v-if="listState === 'ok'">
        <el-table
          ref="tableRef"
          :data="items" v-loading="loading" row-key="runId"
          @row-click="openDetail" @selection-change="onSelectionChange"
          row-class-name="clickable-row"
        >
          <el-table-column type="selection" width="42" reserve-selection />
          <el-table-column label="实验名称与模式" min-width="180">
            <template #default="{ row }">
              <div class="cell-main">
                <span :class="{ mono: !row.displayName }" :title="row.runId">{{ row.displayName ?? shortId(row.runId) }}</span>
                <el-button size="small" text @click.stop="copyText(row.runId, '实验 ID 已复制')">复制</el-button>
              </div>
              <div class="cell-sub">模式：{{ row.mode ?? '未统计' }}</div>
            </template>
          </el-table-column>
          <el-table-column label="版本差异摘要" min-width="150">
            <template #default="{ row }">
              <div class="cell-main">模型：{{ row.model ?? '未统计' }}</div>
              <div class="cell-sub">Prompt：{{ row.promptVersion ?? '未统计' }}</div>
            </template>
          </el-table-column>
          <el-table-column label="数据集与样本数" min-width="130">
            <template #default="{ row }">
              <div class="cell-main">{{ row.datasetVersion ?? '未统计' }}</div>
              <div class="cell-sub">样本数：{{ fmtCount(row.totalScenarios) }}</div>
            </template>
          </el-table-column>
          <el-table-column label="执行阶段 / 已结清案例" width="170">
            <template #default="{ row }">
              <div class="cell-main"><StatusBadge :status="badgeState(row.state)" /></div>
              <div class="cell-sub">阶段：{{ fmtPhase(row.facets?.phase) }}</div>
              <div class="cell-sub">已结清案例：{{ fmtPair(row.caseCount, row.totalScenarios) }}</div>
            </template>
          </el-table-column>
          <el-table-column label="质量摘要" width="150">
            <template #default="{ row }">
              <div class="cell-main">端到端命中：{{ fmtRatioStatOr(row.quality?.endToEndHitRate, row.endToEndHitRate) }}</div>
              <div class="cell-sub">
                未决 {{ fmtRatioStatOr(row.quality?.unresolvedRate, row.unresolvedRate) }}
                · 错误确认 {{ fmtRatioStat(row.quality?.falseConfirmation) }}
              </div>
            </template>
          </el-table-column>
          <el-table-column label="费用状态与耗时" width="140">
            <template #default="{ row }">
              <div class="cell-main">费用：未统计（依赖 EV-06）</div>
              <div class="cell-sub">耗时：{{ fmtDuration(row.startedAt, row.finishedAt) }}</div>
            </template>
          </el-table-column>
          <el-table-column label="创建时间 / 操作" width="195">
            <template #default="{ row }">
              <div class="cell-main" :title="'后端仅上报开始时间，创建时间依赖 EV-03'">{{ fmtTime(row.startedAt) }}</div>
              <div class="cell-sub">
                <el-button size="small" text type="primary" @click.stop="openDetail(row)">详情</el-button>
              </div>
            </template>
          </el-table-column>
          <template #empty>
            <EmptyState kind="empty" :description="state ? '当前过滤项无实验' : '暂无评测实验'" />
          </template>
        </el-table>
        <div class="pager">
          <span class="muted">已加载 {{ items.length }} 条</span>
          <el-button v-if="nextCursor" :loading="loadingMore" @click="loadMore">加载更多</el-button>
        </div>
      </template>
      <EmptyState v-else-if="listState === 'forbidden'" kind="forbidden" />
      <EmptyState v-else-if="listState === 'error'" kind="error" @retry="loadList" />
      <div v-else v-loading="true" class="loading-box" />
    </div>

    <!-- 上下文操作条：选中恰好两条实验时出现，不常驻 -->
    <transition name="fade">
      <div v-if="comparePair" class="ctx-bar card">
        <span class="ctx-text">
          已选 2 条实验
          <template v-if="pairMarked">
            ：基线 <b class="mono">{{ shortId(comparePair.baseline) }}</b>
            · 候选 <b class="mono">{{ shortId(comparePair.candidate) }}</b>
          </template>
        </span>
        <el-button v-if="!pairMarked" size="small" @click="markBaseline">设为基线</el-button>
        <el-button size="small" type="primary" @click="goCompare">比较</el-button>
        <el-button size="small" text @click="clearSelection">取消选择</el-button>
      </div>
    </transition>
  </div>
</template>

<script setup>
// 实验列表（/eval/runs）：页头 + 三过滤项 + 筛选栏（EV-03 查询参数待开放）+ 七列表格 + 上下文比较条。
// EV-01：固定 queryKey + 请求序号防竞态；null 显示“未统计”；“已加载 N 条”。
// EV-03 接线：displayName/mode/totalScenarios/quality（比率三件套）/facets.phase；
// 字段缺席（旧契约）一律“未统计”，不填 0 冒充。
import { computed, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { api } from '../api/client'
import EmptyState from '../components/common/EmptyState.vue'
import PageHeader from '../components/common/PageHeader.vue'
import StatusBadge from '../components/common/StatusBadge.vue'
import { fmtCount, fmtDuration, fmtPair, fmtPhase, fmtRatioStat, fmtRatioStatOr, fmtTime } from '../utils/format'

const route = useRoute()
const router = useRouter()

const str = v => (typeof v === 'string' ? v : '')
const state = ref(str(route.query.state))

const quickFilters = [
  { key: 'all', label: '全部', value: '' },
  { key: 'running', label: '执行中', value: 'RUNNING' },
  { key: 'pending', label: '待处理', value: 'FAILED' },
  { key: 'done', label: '已结束', value: 'SUCCEEDED' },
]
const moreFiltersOpen = ref(false)

const items = ref([])
const nextCursor = ref(null)
const listState = ref('loading') // loading | ok | error | forbidden
const loading = ref(false)
const loadingMore = ref(false)
const lastLoadedAt = ref(null)

// 上下文比较条
const tableRef = ref()
const selectedRows = ref([])
const pairMarked = ref(false)

// eval 状态码 → 共享徽章词汇（SUCCEEDED 语义为成功，非 Run 的待审查）
const badgeState = s => ({ RUNNING: 'RUNNING', SUCCEEDED: 'COMPLETED', FAILED: 'FAILED' }[s] ?? s)
const shortId = id => (id && id.length > 16 ? `${id.slice(0, 8)}…${id.slice(-4)}` : id ?? '—')

// RV05：固定 queryKey + 单调序号；失效请求（筛选已变/已有更新请求）一律不准入
const queryKey = computed(() => JSON.stringify({ state: state.value }))
let reqSeq = 0
let inflight = null // AbortController，切换筛选时取消旧请求

function listParams(cursor) {
  const p = { limit: 50 }
  if (state.value) p.state = state.value
  if (cursor) p.cursor = cursor
  return p
}

async function loadList() {
  const key = queryKey.value
  const seq = ++reqSeq
  inflight?.abort()
  const ctl = new AbortController()
  inflight = ctl
  loading.value = true
  if (listState.value !== 'ok') listState.value = 'loading'
  try {
    const d = await api('/eval/runs', { params: listParams(), signal: ctl.signal })
    if (seq !== reqSeq || key !== queryKey.value) return // 失效响应丢弃
    items.value = d.items ?? []
    nextCursor.value = d.nextCursor ?? null
    listState.value = 'ok'
    lastLoadedAt.value = new Date().toISOString()
    pairMarked.value = false
  } catch (e) {
    if (ctl.signal.aborted || seq !== reqSeq) return
    listState.value = e?.response?.status === 403 ? 'forbidden' : 'error'
  } finally {
    if (seq === reqSeq) loading.value = false
  }
}

async function loadMore() {
  if (!nextCursor.value) return
  const key = queryKey.value
  const seq = reqSeq // 必须与最近一次 loadList 同代，否则是旧筛选的游标
  const cursor = nextCursor.value
  loadingMore.value = true
  try {
    const d = await api('/eval/runs', { params: listParams(cursor) })
    if (seq !== reqSeq || key !== queryKey.value) return // 旧页不拼接到新筛选
    items.value = items.value.concat(d.items ?? [])
    nextCursor.value = d.nextCursor ?? null
    lastLoadedAt.value = new Date().toISOString()
  } catch (e) {
    if (seq === reqSeq) ElMessage.error('加载更多失败，请重试')
  } finally {
    if (seq === reqSeq) loadingMore.value = false
  }
}

function applyState(v) {
  state.value = v
  router.replace({ query: v ? { state: v } : {} })
  pairMarked.value = false
  loadList()
}

function openDetail(row) { router.push(`/eval/runs/${encodeURIComponent(row.runId)}`) }

function onSelectionChange(rows) {
  selectedRows.value = rows
  if (rows.length !== 2) pairMarked.value = false
}

// 恰好两条选中时构成比较对；基线默认取开始时间较早者（确定性规则，界面上明示）
const comparePair = computed(() => {
  if (selectedRows.value.length !== 2) return null
  const [a, b] = [...selectedRows.value].sort(
    (x, y) => new Date(x.startedAt ?? 0) - new Date(y.startedAt ?? 0),
  )
  return { baseline: a.runId, candidate: b.runId }
})

function markBaseline() { pairMarked.value = true }

function goCompare() {
  if (!comparePair.value) return
  router.push({
    path: '/eval/compare',
    query: { baseline: comparePair.value.baseline, candidate: comparePair.value.candidate },
  })
}

function clearSelection() {
  tableRef.value?.clearSelection()
  pairMarked.value = false
}

function copyText(text, tip) {
  navigator.clipboard?.writeText(text).then(
    () => ElMessage.success(tip),
    () => ElMessage.error('复制失败'),
  )
}

// 浏览器前进/后退：query 变化回灌过滤项并重载（EU05）
watch(() => route.query.state, v => {
  const next = str(v)
  if (next === state.value) return
  state.value = next
  pairMarked.value = false
  loadList()
})

onMounted(loadList)
</script>

<style scoped>
.runs-page { display: flex; flex-direction: column; gap: var(--section-gap); }

.quick-filters { display: flex; align-items: center; gap: 8px; flex-wrap: wrap; padding: 10px var(--card-pad); }
.qf {
  border: 1px solid var(--line); background: var(--card); color: var(--ink-2);
  border-radius: 999px; padding: 0 14px; height: 30px; font-size: 13px; font-family: inherit;
  cursor: pointer; transition: border-color .15s, color .15s;
}
.qf:hover { border-color: var(--brand); color: var(--brand); }
.qf.cur { border-color: var(--brand); color: var(--brand); background: var(--brand-soft); font-weight: 600; }
.qf-note { font-size: var(--fs-aux); color: var(--ink-2); margin-left: 4px; }

.filter-bar { display: flex; align-items: center; gap: 8px; flex-wrap: wrap; padding: 12px var(--card-pad); }
.filter-bar .w-search { width: 220px; }
.filter-bar .w-filter { width: 160px; }
.more-filters { display: flex; align-items: center; gap: 8px; flex-wrap: wrap; flex-basis: 100%; }
.more-filters .w-filter { width: 160px; }
.more-filters .w-date { width: 280px; }
.filter-note { flex-basis: 100%; font-size: var(--fs-aux); color: var(--ink-2); }

.table-zone { padding: 8px var(--card-pad) 12px; }
.cell-main { font-size: var(--fs-body); line-height: 1.4; }
.cell-sub { font-size: var(--fs-aux); color: var(--ink-2); line-height: 1.4; }
.mono { font-family: var(--mono, monospace); }
.pager { display: flex; align-items: center; justify-content: center; gap: 16px; padding: 12px 0 4px; }
.muted { color: var(--ink-2); font-size: var(--fs-aux); }
.loading-box { height: 320px; }
:deep(.clickable-row) { cursor: pointer; }

.ctx-bar {
  position: fixed; left: 50%; bottom: 24px; transform: translateX(-50%); z-index: 20;
  display: flex; align-items: center; gap: 12px; padding: 10px 16px;
  box-shadow: 0 4px 16px rgba(0, 0, 0, .12);
}
.ctx-text { font-size: var(--fs-body); }
.fade-enter-active, .fade-leave-active { transition: opacity .15s; }
.fade-enter-from, .fade-leave-to { opacity: 0; }
</style>
