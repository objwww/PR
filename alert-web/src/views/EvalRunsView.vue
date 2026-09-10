<template>
  <div class="runs-page">
    <!-- 工具栏：状态筛选（进 URL query） -->
    <div class="toolbar card">
      <el-select v-model="state" class="w-state" @change="applyState">
        <el-option value="" label="全部状态" />
        <el-option value="RUNNING" label="执行中" />
        <el-option value="SUCCEEDED" label="已完成" />
        <el-option value="FAILED" label="已失败" />
      </el-select>
      <el-button :loading="loading" @click="loadList">刷新</el-button>
    </div>

    <div class="table-zone card">
      <template v-if="listState === 'ok'">
        <el-table :data="items" v-loading="loading" @row-click="openDetail" row-class-name="clickable-row">
          <el-table-column label="实验 ID" min-width="180">
            <template #default="{ row }">
              <span class="mono" :title="row.runId">{{ shortId(row.runId) }}</span>
              <el-button size="small" text @click.stop="copyText(row.runId, '实验 ID 已复制')">复制</el-button>
            </template>
          </el-table-column>
          <el-table-column prop="datasetVersion" label="数据集版本" min-width="120" show-overflow-tooltip />
          <el-table-column prop="model" label="模型" min-width="140" show-overflow-tooltip />
          <el-table-column prop="promptVersion" label="提示词版本" min-width="110" show-overflow-tooltip />
          <el-table-column label="状态" width="100">
            <template #default="{ row }">
              <StatusBadge :status="badgeState(row.state)" />
            </template>
          </el-table-column>
          <el-table-column label="覆盖率" width="90" align="right">
            <template #default="{ row }">{{ fmtPct(row.coverage) }}</template>
          </el-table-column>
          <el-table-column label="条件准确率" width="100" align="right">
            <template #default="{ row }">{{ fmtPct(row.conditionalAccuracy) }}</template>
          </el-table-column>
          <el-table-column label="端到端命中" width="100" align="right">
            <template #default="{ row }">{{ fmtPct(row.endToEndHitRate) }}</template>
          </el-table-column>
          <el-table-column label="TP / FP / FN" width="110" align="right">
            <template #default="{ row }">{{ row.tp ?? 0 }} / {{ row.fp ?? 0 }} / {{ row.fn ?? 0 }}</template>
          </el-table-column>
          <el-table-column label="开始时间" width="160">
            <template #default="{ row }">{{ fmtTime(row.startedAt) }}</template>
          </el-table-column>
          <el-table-column label="耗时" width="120">
            <template #default="{ row }">{{ fmtDuration(row.startedAt, row.finishedAt) }}</template>
          </el-table-column>
          <template #empty>
            <EmptyState kind="empty" :description="state ? '当前状态筛选无实验' : '暂无评测实验'" />
          </template>
        </el-table>
        <div class="pager">
          <span class="muted">共 {{ items.length }} 条</span>
          <el-button v-if="nextCursor" :loading="loadingMore" @click="loadMore">加载更多</el-button>
        </div>
      </template>
      <EmptyState v-else-if="listState === 'forbidden'" kind="forbidden" />
      <EmptyState v-else-if="listState === 'error'" kind="error" @retry="loadList" />
      <div v-else v-loading="true" class="loading-box" />
    </div>
  </div>
</template>

<script setup>
// UI-6 实验页（/eval/runs）：GET /eval/runs 批次表 + 状态筛选（URL query）+ 游标分页
import { onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { api } from '../api/client'
import EmptyState from '../components/common/EmptyState.vue'
import StatusBadge from '../components/common/StatusBadge.vue'
import { fmtDuration, fmtPct, fmtTime } from '../utils/format'

const route = useRoute()
const router = useRouter()

const str = v => (typeof v === 'string' ? v : '')
const state = ref(str(route.query.state))

const items = ref([])
const nextCursor = ref(null)
const listState = ref('loading') // loading | ok | error | forbidden
const loading = ref(false)
const loadingMore = ref(false)

// eval 状态码 → 共享徽章词汇（SUCCEEDED 语义为成功，非 Run 的待审查）
const badgeState = s => ({ RUNNING: 'RUNNING', SUCCEEDED: 'COMPLETED', FAILED: 'FAILED' }[s] ?? s)
const shortId = id => (id && id.length > 16 ? `${id.slice(0, 8)}…${id.slice(-4)}` : id ?? '—')

function listParams(cursor) {
  const p = { limit: 50 }
  if (state.value) p.state = state.value
  if (cursor) p.cursor = cursor
  return p
}

async function loadList() {
  listState.value = 'loading'
  loading.value = true
  try {
    const d = await api('/eval/runs', { params: listParams() })
    items.value = d.items ?? []
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
    const d = await api('/eval/runs', { params: listParams(nextCursor.value) })
    items.value = items.value.concat(d.items ?? [])
    nextCursor.value = d.nextCursor ?? null
  } catch {
    ElMessage.error('加载更多失败，请重试')
  } finally {
    loadingMore.value = false
  }
}

function applyState() {
  router.replace({ query: state.value ? { state: state.value } : {} })
  loadList()
}

function openDetail(row) { router.push(`/eval/runs/${encodeURIComponent(row.runId)}`) }

function copyText(text, tip) {
  navigator.clipboard?.writeText(text).then(
    () => ElMessage.success(tip),
    () => ElMessage.error('复制失败'),
  )
}

// 浏览器前进/后退：query 变化回灌筛选并重载
watch(() => route.query.state, v => {
  const next = str(v)
  if (next === state.value) return
  state.value = next
  loadList()
})

onMounted(loadList)
</script>

<style scoped>
.runs-page { display: flex; flex-direction: column; gap: var(--section-gap); }
.toolbar { display: flex; align-items: center; gap: 8px; flex-wrap: wrap; padding: 12px var(--card-pad); }
.toolbar .w-state { width: 130px; }
.table-zone { padding: 8px var(--card-pad) 12px; }
.mono { font-family: var(--mono, monospace); margin-right: 4px; }
.pager { display: flex; align-items: center; justify-content: center; gap: 16px; padding: 12px 0 4px; }
.muted { color: var(--ink-2); font-size: var(--fs-aux); }
.loading-box { height: 320px; }
:deep(.clickable-row) { cursor: pointer; }
</style>
