<template>
  <div class="alerts-page">
    <PageHeader title="告警中心" subtitle="筛选并找到应调查或处理的事故" />

    <!-- 统计条：firing/P0/P1/P2 可点击=应用对应过滤；未分派/24h/MTTR 契约无对应过滤参数，仅展示 -->
    <div class="stat-row">
      <div class="stat card clickable" :class="{ cur: filters.status === 'FIRING' }" @click="toggleFilter('status', 'FIRING')">
        <span class="stat-label">告警中</span>
        <span class="stat-num" style="color: var(--sev-p0)">{{ summary?.firingTotal ?? '—' }}</span>
      </div>
      <div
        v-for="c in sevCards" :key="c.raw"
        class="stat card clickable" :class="{ cur: filters.severity === c.raw }"
        @click="toggleFilter('severity', c.raw)"
      >
        <span class="stat-label">{{ c.label }}</span>
        <span class="stat-num" :style="{ color: c.color }">{{ c.count }}</span>
      </div>
      <div class="stat card">
        <span class="stat-label">未分派</span>
        <span class="stat-num">{{ summary?.unassigned ?? '—' }}</span>
      </div>
      <div class="stat card">
        <span class="stat-label">24h 接收</span>
        <span class="stat-num">{{ summary?.stormReceived24h ?? '—' }}</span>
      </div>
      <div class="stat card">
        <span class="stat-label">平均恢复（24h）</span>
        <span class="stat-num">{{ fmtMttr(summary?.mttrMinutes24h) }}</span>
      </div>
    </div>

    <!-- 工具栏：关键词搜索 + 状态/服务常用筛选，其余收进“更多筛选” -->
    <div class="toolbar card">
      <el-input
        v-model="kw" class="kw" placeholder="搜索告警名 / 服务 / 关键词" clearable
        :prefix-icon="Search" @keyup.enter="applyFilters" @clear="applyFilters"
      />
      <el-select v-model="filters.status" class="w-status" @change="applyFilters">
        <el-option value="" label="全部状态" />
        <el-option value="FIRING" label="告警中" />
        <el-option value="RESOLVED" label="已解决" />
      </el-select>
      <el-select
        v-model="filters.service" class="w-service" placeholder="全部服务" clearable filterable
        @change="applyFilters"
      >
        <el-option v-for="s in serviceOptions" :key="s" :value="s" :label="s" />
      </el-select>
      <!-- UX-01 分类过滤：词表静态；后端 UX-01 未部署（facets 无 category 维）时禁用并注明，
           启用后选中带 category 参数请求，失败显式提示不静默 -->
      <el-tooltip :disabled="categorySupported" content="分类过滤依赖后端 UX-01，当前未部署" placement="top">
        <span>
          <el-select
            v-model="filters.category" class="w-category" placeholder="全部分类" clearable
            :disabled="!categorySupported" @change="applyFilters"
          >
            <el-option v-for="c in categoryOptions" :key="c.value" :value="c.value" :label="c.label" />
          </el-select>
        </span>
      </el-tooltip>
      <span v-if="!categorySupported" class="cat-note">分类过滤依赖后端 UX-01（未部署）</span>
      <el-popover placement="bottom-start" trigger="click" width="260">
        <template #reference>
          <el-button>
            更多筛选<el-icon class="el-icon--right"><ArrowDown /></el-icon>
          </el-button>
        </template>
        <div class="more-filter">
          <div class="mf-label">严重度</div>
          <el-select v-model="filters.severity" placeholder="全部严重度" clearable style="width: 100%" @change="applyFilters">
            <el-option v-for="o in severityOptions" :key="o.value" :value="o.value" :label="o.label" />
          </el-select>
        </div>
      </el-popover>
      <el-button type="primary" :loading="loading" @click="applyFilters">查询</el-button>
      <el-button @click="resetFilters">重置</el-button>
      <span class="flex-spacer" />
      <el-button text @click="facetOpen = !facetOpen">{{ facetOpen ? '收起筛选栏' : '展开筛选栏' }}</el-button>
    </div>

    <div class="main">
      <!-- 左 facet 栏：计数来自 /v1/incidents/facets（带当前过滤参数），默认展开可收起 -->
      <aside v-show="facetOpen" class="facet-rail card">
        <template v-for="g in facetGroups" :key="g.key">
          <h4>{{ g.title }}</h4>
          <template v-if="g.items.length">
            <div
              v-for="it in g.items" :key="it.value"
              class="f-it" :class="{ cur: filters[g.key] === it.value }"
              @click="toggleFilter(g.key, it.value)"
            >
              <span class="f-label" :title="it.label">{{ it.label }}</span>
              <span class="f-count">{{ it.count }}</span>
            </div>
          </template>
          <div v-else class="f-empty">暂无</div>
        </template>
      </aside>

      <!-- 主区表格 + 游标分页 -->
      <div class="table-zone card">
        <template v-if="listState === 'ok'">
          <IncidentTable :rows="items" :loading="loading" @row-click="openDetail">
            <template #actions="{ row }">
              <el-button size="small" @click.stop="openDetail(row)">打开</el-button>
            </template>
            <template #empty>
              <EmptyState kind="empty" :description="hasFilter ? '当前筛选无结果，可调整或重置筛选' : '暂无告警'" />
            </template>
          </IncidentTable>
          <div class="pager">
            <span class="muted">共 {{ total }} 条</span>
            <el-button v-if="nextCursor" :loading="loadingMore" @click="loadMore">加载更多</el-button>
          </div>
        </template>
        <EmptyState v-else-if="listState === 'forbidden'" kind="forbidden" />
        <EmptyState v-else-if="listState === 'error'" kind="error" @retry="loadAll" />
        <div v-else v-loading="true" class="loading-box" />
      </div>
    </div>
  </div>
</template>

<script setup>
// UI-1 告警中心（/alerts）：统计条 + 搜索工具栏 + 左 facet + el-table，全部真端点
// 端点：/v1/incidents（列表/游标分页）、/v1/incidents/facets（计数）、/v1/incidents/summary（统计条）
import { computed, onMounted, reactive, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ArrowDown, Search } from '@element-plus/icons-vue'
import { api } from '../api/client'
import PageHeader from '../components/common/PageHeader.vue'
import EmptyState from '../components/common/EmptyState.vue'
import IncidentTable from '../components/IncidentTable.vue'
import { mapSeverity } from '../utils/severity'
import { fmtMttr } from '../utils/format'
import { CATEGORY_OPTIONS, mapCategory } from '../utils/category'

const route = useRoute()
const router = useRouter()

const str = v => (typeof v === 'string' ? v : '')

// 过滤条件单一事实源=URL query（刷新/返回保持）
const kw = ref(str(route.query.q))
const filters = reactive({
  status: str(route.query.status),
  severity: str(route.query.severity),
  service: str(route.query.service),
  category: str(route.query.category),
  q: str(route.query.q),
})

const summary = ref(null)
const facets = ref(null)
const items = ref([])
const total = ref(0)
const nextCursor = ref(null)
const listState = ref('loading') // loading | ok | error | forbidden
const loading = ref(false)
const loadingMore = ref(false)
const facetOpen = ref(true)

const hasFilter = computed(() => !!(filters.status || filters.severity || filters.service || filters.category || filters.q))

// UX-01：facets 响应含 category 维分桶 = 后端 UX-01 已部署；缺席 = 旧契约，分类过滤禁用
const categorySupported = computed(() => facets.value?.category != null && typeof facets.value.category === 'object')
const categoryOptions = CATEGORY_OPTIONS

const sevCards = computed(() => {
  const by = summary.value?.bySeverity ?? {}
  return [
    { label: 'P0', raw: 'critical', count: by.critical ?? 0, color: 'var(--sev-p0)' },
    { label: 'P1', raw: 'warning', count: by.warning ?? 0, color: 'var(--sev-p1)' },
    // info/notice 同映射 P2，点击按 info 过滤（契约 severity 传原始值）
    { label: 'P2', raw: 'info', count: (by.info ?? 0) + (by.notice ?? 0), color: 'var(--sev-p2)' },
  ]
})

const STATUS_LABEL = { FIRING: '告警中', RESOLVED: '已解决' }
const facetGroups = computed(() => [
  {
    key: 'status', title: '状态',
    items: Object.entries(facets.value?.status ?? {}).map(([v, c]) => ({ value: v, label: STATUS_LABEL[v] ?? v, count: c })),
  },
  {
    key: 'severity', title: '严重度',
    items: Object.entries(facets.value?.severity ?? {}).map(([v, c]) => ({ value: v, label: `${mapSeverity(v).label}（${v}）`, count: c })),
  },
  {
    key: 'service', title: '服务',
    items: Object.entries(facets.value?.service ?? {}).map(([v, c]) => ({ value: v, label: v, count: c })),
  },
  // UX-01 category 维分桶：缺席（旧契约后端）时整组不渲染
  ...(facets.value?.category ? [{
    key: 'category', title: '分类',
    items: Object.entries(facets.value.category).map(([v, c]) => ({ value: v, label: mapCategory(v)?.label ?? v, count: c })),
  }] : []),
])
const serviceOptions = computed(() => Object.keys(facets.value?.service ?? {}))
const severityOptions = computed(() => {
  const keys = Object.keys(facets.value?.severity ?? {})
  const fallback = ['critical', 'warning', 'info', 'notice']
  return (keys.length ? keys : fallback).map(v => ({ value: v, label: `${mapSeverity(v).label}（${v}）` }))
})

function listParams(cursor) {
  const p = { limit: 50 }
  if (filters.status) p.status = filters.status
  if (filters.severity) p.severity = filters.severity
  if (filters.service) p.service = filters.service
  if (filters.category) p.category = filters.category
  if (filters.q) p.q = filters.q
  if (cursor) p.cursor = cursor
  return p
}

async function loadList() {
  listState.value = 'loading'
  loading.value = true
  try {
    const d = await api('/v1/incidents', { params: listParams() })
    items.value = d.items ?? []
    total.value = d.total ?? items.value.length
    nextCursor.value = d.nextCursor ?? null
    listState.value = 'ok'
    // UX-01：URL 带 category 但后端未部署（旧契约会静默忽略未知参数）→ 显式提示条件未生效，不伪造过滤效果
    if (filters.category && !categorySupported.value) {
      ElMessage.warning('分类过滤依赖后端 UX-01，当前未部署，该条件未生效')
    }
  } catch (e) {
    // UX-01：分类过滤被后端 400 拒绝 → 显式提示，不归入通用错误态
    if (filters.category && e?.response?.status === 400) {
      ElMessage.error('分类过滤依赖后端 UX-01，当前未部署')
    }
    listState.value = e?.response?.status === 403 ? 'forbidden' : 'error'
  } finally {
    loading.value = false
  }
}

async function loadMore() {
  if (!nextCursor.value) return
  loadingMore.value = true
  try {
    const d = await api('/v1/incidents', { params: listParams(nextCursor.value) })
    items.value = items.value.concat(d.items ?? [])
    nextCursor.value = d.nextCursor ?? null
  } catch {
    ElMessage.error('加载更多失败，请重试')
  } finally {
    loadingMore.value = false
  }
}

async function loadSummary() {
  try { summary.value = await api('/v1/incidents/summary') } catch { summary.value = null }
}

async function loadFacets() {
  try {
    const p = {}
    if (filters.status) p.status = filters.status
    if (filters.q) p.q = filters.q
    if (filters.service) p.service = filters.service
    facets.value = await api('/v1/incidents/facets', { params: p })
  } catch { facets.value = null }
}

function loadAll() { loadList(); loadSummary(); loadFacets() }

function applyFilters() {
  filters.q = kw.value.trim()
  const query = {}
  for (const k of ['status', 'severity', 'service', 'category', 'q']) if (filters[k]) query[k] = filters[k]
  router.replace({ query })
  loadAll()
}

function toggleFilter(key, value) {
  filters[key] = filters[key] === value ? '' : value
  applyFilters()
}

function resetFilters() {
  kw.value = ''
  Object.assign(filters, { status: '', severity: '', service: '', category: '', q: '' })
  applyFilters()
}

function openDetail(row) { router.push(`/alerts/${row.incidentId}`) }

// 浏览器前进/后退：query 变化回灌过滤条件并重载
watch(() => route.query, q => {
  const next = { status: str(q.status), severity: str(q.severity), service: str(q.service), category: str(q.category), q: str(q.q) }
  if (['status', 'severity', 'service', 'category', 'q'].every(k => next[k] === filters[k])) return
  Object.assign(filters, next)
  kw.value = next.q
  loadAll()
})

onMounted(loadAll)
</script>

<style scoped>
.alerts-page { display: flex; flex-direction: column; gap: var(--section-gap); }

/* 统计条 */
.stat-row { display: grid; grid-template-columns: repeat(auto-fit, minmax(140px, 1fr)); gap: 12px; }
.stat { padding: 12px 16px; display: flex; flex-direction: column; gap: 2px; }
.stat.clickable { cursor: pointer; transition: box-shadow .15s; }
.stat.clickable:hover { box-shadow: 0 0 0 2px var(--brand-soft); }
.stat.cur { box-shadow: 0 0 0 2px var(--brand); }
.stat-label { font-size: var(--fs-aux); color: var(--ink-2); }
.stat-num { font-size: 22px; font-weight: 700; color: var(--head); line-height: 1.3; }

/* 工具栏 */
.toolbar { display: flex; align-items: center; gap: 8px; flex-wrap: wrap; padding: 12px var(--card-pad); }
.toolbar .kw { width: 260px; }
.toolbar .w-status { width: 120px; }
.toolbar .w-service { width: 200px; }
.toolbar .w-category { width: 150px; }
.cat-note { font-size: var(--fs-aux); color: var(--ink-2); }
.flex-spacer { flex: 1; }
.more-filter .mf-label { font-size: var(--fs-aux); color: var(--ink-2); margin-bottom: 6px; }

/* 主区：左 facet（200px 默认展开）+ 右表格 */
.main { display: flex; align-items: flex-start; gap: var(--section-gap); }
.facet-rail { width: 200px; flex: none; padding: 12px 14px; font-size: var(--fs-body); }
.facet-rail h4 { font-size: var(--fs-aux); color: var(--ink-2); margin: 12px 0 4px; font-weight: 600; }
.facet-rail h4:first-child { margin-top: 0; }
.f-it { display: flex; justify-content: space-between; gap: 6px; padding: 4px 8px; border-radius: var(--radius-ctl); cursor: pointer; }
.f-it:hover { background: var(--bg); }
.f-it.cur { background: var(--brand-soft); color: var(--brand); font-weight: 600; }
.f-label { overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.f-count { color: var(--ink-2); flex: none; }
.f-it.cur .f-count { color: var(--brand); }
.f-empty { padding: 2px 8px; font-size: var(--fs-aux); color: var(--ink-2); }

.table-zone { flex: 1; min-width: 0; padding: 8px var(--card-pad) 12px; }
.pager { display: flex; align-items: center; justify-content: center; gap: 16px; padding: 12px 0 4px; }
.muted { color: var(--ink-2); font-size: var(--fs-aux); }
.loading-box { height: 320px; }

@media (max-width: 1100px) {
  .main { flex-direction: column; }
  .facet-rail { width: 100%; }
}
</style>
