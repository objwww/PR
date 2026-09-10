<template>
  <div class="history-page">
    <PageHeader title="历史档案" subtitle="已解决告警的只读快照（复用 incidents 端点，不单独做归档投影）" />

    <!-- 工具栏：关键词 + 服务筛选，写 URL query -->
    <div class="toolbar card">
      <el-input
        v-model="kw" class="kw" placeholder="搜索告警名 / 服务 / 关键词" clearable
        :prefix-icon="Search" @keyup.enter="applyFilters" @clear="applyFilters"
      />
      <el-select
        v-model="filters.service" class="w-service" placeholder="全部服务" clearable filterable
        @change="applyFilters"
      >
        <el-option v-for="s in serviceOptions" :key="s" :value="s" :label="s" />
      </el-select>
      <el-button type="primary" :loading="loading" @click="applyFilters">查询</el-button>
      <el-button @click="resetFilters">重置</el-button>
    </div>

    <!-- 全宽结果列表（与告警中心同列口径），点击行打开右侧只读快照 -->
    <div class="table-zone card">
      <template v-if="listState === 'ok'">
        <IncidentTable :rows="items" :loading="loading" @row-click="openSnapshot">
          <template #actions="{ row }">
            <el-button size="small" @click.stop="openSnapshot(row)">查看快照</el-button>
          </template>
          <template #empty>
            <EmptyState kind="empty" :description="hasFilter ? '当前筛选无结果，可调整或重置筛选' : '暂无已解决告警'" />
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

    <!-- 只读快照抽屉 -->
    <DetailDrawer v-model="drawerOpen" :title="snap?.alertname ?? '历史快照'" :size="420">
      <div v-loading="snapLoading" class="snap-box">
        <template v-if="snap">
          <el-descriptions :column="1" border>
            <el-descriptions-item label="服务">{{ snap.service ?? '—' }}</el-descriptions-item>
            <el-descriptions-item label="严重度">
              <StatusBadge v-if="mapSeverity(snap.severity).key" :severity="mapSeverity(snap.severity).key" />
              <el-tag v-else type="info" disable-transitions>未分级</el-tag>
              <template v-if="snap.severity">（{{ snap.severity }}）</template>
            </el-descriptions-item>
            <el-descriptions-item label="首次发生">{{ fmtTime(snap.episodeStartedAt) }}</el-descriptions-item>
            <el-descriptions-item label="持续时长">{{ fmtDuration(snap.episodeStartedAt, snap.resolvedAt) }}</el-descriptions-item>
            <el-descriptions-item label="解决时间">{{ fmtTime(snap.resolvedAt) }}</el-descriptions-item>
            <el-descriptions-item label="接收 / 事件">{{ snap.receivedCount ?? 0 }} / {{ snap.distinctEventCount ?? 0 }}</el-descriptions-item>
            <el-descriptions-item label="时间线条数">{{ snap.timeline?.length ?? 0 }}</el-descriptions-item>
            <el-descriptions-item label="关联调查">
              <router-link v-if="snapRunId" :to="`/runs/${snapRunId}`"><code>{{ snapRunId }}</code></router-link>
              <span v-else>未发起</span>
            </el-descriptions-item>
          </el-descriptions>
          <el-button style="margin-top: 12px" @click="goDetail">打开完整详情</el-button>
        </template>
        <EmptyState v-else-if="snapError" kind="error" description="快照加载失败" @retry="retrySnapshot" />
      </div>
    </DetailDrawer>
  </div>
</template>

<script setup>
// UI-1 历史档案（/history）：已解决 incident 全宽列表 + 右侧 DetailDrawer 只读快照
// 数据复用 /v1/incidents?status=RESOLVED 与 /v1/incidents/{id}；删随机打开/假归档证明/假 verifyAll
import { computed, onMounted, reactive, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { Search } from '@element-plus/icons-vue'
import { api } from '../api/client'
import PageHeader from '../components/common/PageHeader.vue'
import EmptyState from '../components/common/EmptyState.vue'
import DetailDrawer from '../components/common/DetailDrawer.vue'
import StatusBadge from '../components/common/StatusBadge.vue'
import IncidentTable from '../components/IncidentTable.vue'
import { mapSeverity } from '../utils/severity'
import { fmtDuration, fmtTime } from '../utils/format'

const route = useRoute()
const router = useRouter()

const str = v => (typeof v === 'string' ? v : '')

const kw = ref(str(route.query.q))
const filters = reactive({ service: str(route.query.service), q: str(route.query.q) })

const items = ref([])
const total = ref(0)
const nextCursor = ref(null)
const listState = ref('loading') // loading | ok | error | forbidden
const loading = ref(false)
const loadingMore = ref(false)
const facets = ref(null)

const drawerOpen = ref(false)
const snap = ref(null)
const snapLoading = ref(false)
const snapError = ref(false)
const snapRunId = computed(() => snap.value?.run?.runId ?? snap.value?.currentRcaRunId ?? null)

const hasFilter = computed(() => !!(filters.service || filters.q))
const serviceOptions = computed(() => Object.keys(facets.value?.service ?? {}))

function listParams(cursor) {
  const p = { status: 'RESOLVED', limit: 50 }
  if (filters.service) p.service = filters.service
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
  } catch (e) {
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

async function loadFacets() {
  try {
    const p = { status: 'RESOLVED' }
    if (filters.q) p.q = filters.q
    facets.value = await api('/v1/incidents/facets', { params: p })
  } catch { facets.value = null }
}

function loadAll() { loadList(); loadFacets() }

function applyFilters() {
  filters.q = kw.value.trim()
  const query = {}
  if (filters.service) query.service = filters.service
  if (filters.q) query.q = filters.q
  router.replace({ query })
  loadAll()
}

function resetFilters() {
  kw.value = ''
  Object.assign(filters, { service: '', q: '' })
  applyFilters()
}

const curSnapId = ref(null)

async function openSnapshot(row) {
  curSnapId.value = row.incidentId
  drawerOpen.value = true
  snap.value = null
  snapError.value = false
  snapLoading.value = true
  try {
    snap.value = await api(`/v1/incidents/${row.incidentId}`)
  } catch {
    snapError.value = true
  } finally {
    snapLoading.value = false
  }
}

function retrySnapshot() { if (curSnapId.value) openSnapshot({ incidentId: curSnapId.value }) }

function goDetail() { if (snap.value) router.push(`/alerts/${snap.value.incidentId}`) }

watch(() => route.query, q => {
  const next = { service: str(q.service), q: str(q.q) }
  if (next.service === filters.service && next.q === filters.q) return
  Object.assign(filters, next)
  kw.value = next.q
  loadAll()
})

onMounted(loadAll)
</script>

<style scoped>
.history-page { display: flex; flex-direction: column; gap: var(--section-gap); }
.toolbar { display: flex; align-items: center; gap: 8px; flex-wrap: wrap; padding: 12px var(--card-pad); }
.toolbar .kw { width: 260px; }
.toolbar .w-service { width: 200px; }
.table-zone { padding: 8px var(--card-pad) 12px; }
.pager { display: flex; align-items: center; justify-content: center; gap: 16px; padding: 12px 0 4px; }
.muted { color: var(--ink-2); font-size: var(--fs-aux); }
.loading-box { height: 320px; }
.snap-box { min-height: 200px; }
</style>
