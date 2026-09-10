<template>
  <div class="runs-page">
    <PageHeader title="调查队列" subtitle="找到失败、等待、需要干预的调查">
      <template #actions>
        <span class="updated">数据更新至 {{ fmtTime(summary?.updatedAt) }}</span>
        <el-button :loading="loading" @click="load">刷新</el-button>
      </template>
    </PageHeader>

    <!-- 状态分组：计数来自投影汇总 buckets（GET /api/rca-runs 真端点） -->
    <div class="tabs card">
      <button
        v-for="t in tabs"
        :key="t.key"
        class="tab"
        :class="{ cur: tab === t.key }"
        @click="setTab(t.key)"
      >{{ t.label }}<span v-if="t.count != null" class="t-count">{{ t.count }}</span></button>
    </div>

    <div class="toolbar card">
      <el-input
        v-model="kw" class="kw" placeholder="搜索 Run ID / Incident ID" clearable
        @keyup.enter="applyFilters" @clear="applyFilters"
      />
      <el-select v-model="fStage" class="w-stage" placeholder="全部阶段" clearable @change="applyFilters">
        <el-option v-for="s in stageOptions" :key="s.value" :value="s.value" :label="s.label" />
      </el-select>
      <el-button type="primary" :loading="loading" @click="applyFilters">查询</el-button>
      <el-button @click="resetFilters">重置</el-button>
    </div>

    <!-- SLA 摘要：仅在确有超时调查时出现，文案一行 -->
    <el-alert
      v-if="summary && summary.sla.overSla > 0"
      type="warning" :closable="false" class="sla-banner"
      :title="slaText"
    />

    <div class="table-zone card">
      <template v-if="listState === 'ok'">
        <el-table
          :data="filteredRows" v-loading="loading"
          :row-class-name="rowClass"
          @row-click="openRun"
        >
          <el-table-column type="expand">
            <template #default="{ row }">
              <div class="expand-box">
                <div><span class="e-label">Run ID</span><code>{{ row.id }}</code></div>
                <div><span class="e-label">Incident ID</span><code>{{ row.incident }}</code></div>
                <div><span class="e-label">分组 / 阶段码</span>{{ row.bucket }} / {{ row.stage }}</div>
                <div><span class="e-label">卡点 / 错误</span>{{ row.blocker ?? '—' }}</div>
                <div><span class="e-label">负责人</span>{{ row.owner ?? '未分配（认领面未落码）' }}</div>
              </div>
            </template>
          </el-table-column>
          <el-table-column label="所属告警" min-width="200">
            <template #default="{ row }">
              <router-link
                v-if="row.incident" class="inc-link" :to="`/alerts/${row.incident}`"
                :title="row.incident" @click.stop
              >告警 {{ shortId(row.incident) }}</router-link>
              <span v-else class="muted">—</span>
              <div class="cell-sub">Run {{ shortId(row.id) }}</div>
            </template>
          </el-table-column>
          <el-table-column label="当前阶段" min-width="150">
            <template #default="{ row }">
              <span>{{ row.stageZh }}</span>
              <div v-if="row.blocker" class="cell-sub" :title="row.blocker">卡点：{{ row.blocker }}</div>
            </template>
          </el-table-column>
          <el-table-column label="状态" width="110">
            <template #default="{ row }">
              <StatusBadge :status="row.stage" />
            </template>
          </el-table-column>
          <el-table-column label="耗时" width="130">
            <template #default="{ row }">{{ row.duration }}</template>
          </el-table-column>
          <el-table-column label="预算风险" width="110">
            <template #default="{ row }">
              <el-tag v-if="budgetRisk(row)" type="warning" disable-transitions>预算扣减异常</el-tag>
              <span v-else class="muted">—</span>
            </template>
          </el-table-column>
          <el-table-column label="操作" width="90" fixed="right">
            <template #default="{ row }">
              <el-button size="small" @click.stop="openRun(row)">查看</el-button>
            </template>
          </el-table-column>
          <template #empty>
            <EmptyState kind="empty" :description="hasFilter ? '当前筛选无匹配调查，可调整或重置筛选' : '当前分组暂无调查'" />
          </template>
        </el-table>
      </template>
      <EmptyState v-else-if="listState === 'forbidden'" kind="forbidden" />
      <EmptyState v-else-if="listState === 'error'" kind="error" @retry="load" />
      <div v-else v-loading="true" class="loading-box" />
    </div>
  </div>
</template>

<script setup>
// UI-3 调查队列（/runs）：el-table + 三分组 tab（进行中/待干预/已结束），计数来自真 buckets。
// 真端点契约（control-app RunQueryService.list）：
//   summary.buckets.{mine,running,stuck,failed,review}（done 桶无计数，由 rows 推导）；
//   summary.sla.{overSla:0|1, oldestReadyWait, projectionLag:null}；
//   rows[]: {id, incident, severity:null, bucket, stage, stageZh, stageTone,
//            progress:"done/total", blocker, owner:null, duration, action:"view"}
// 命令面：后端命令类型仅 CANCEL|HINT|FEEDBACK 且需 expectedRevision（详情页接线）；
// 队列投影无 revision、无认领命令类型 → 行尾只保留「查看」，不显示不可用按钮。
import { computed, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { api } from '../api/client'
import PageHeader from '../components/common/PageHeader.vue'
import StatusBadge from '../components/common/StatusBadge.vue'
import EmptyState from '../components/common/EmptyState.vue'
import { fmtTime } from '../utils/format'

const route = useRoute()
const router = useRouter()

const str = v => (typeof v === 'string' ? v : '')

const loading = ref(false)
const listState = ref('loading') // loading | ok | error | forbidden
const summary = ref(null)
const rows = ref([])

// 筛选状态经 URL query 持久化（tab/stage/q）
const tab = ref(str(route.query.tab) || 'running')
const fStage = ref(str(route.query.stage))
const kw = ref(str(route.query.q))
const q = ref(str(route.query.q))

const INTERVENE_BUCKETS = new Set(['mine', 'stuck', 'failed', 'review'])

const tabs = computed(() => {
  const b = summary.value?.buckets ?? {}
  return [
    { key: 'running', label: '进行中', count: b.running ?? null },
    { key: 'intervene', label: '待干预', count: (b.mine ?? 0) + (b.stuck ?? 0) + (b.failed ?? 0) + (b.review ?? 0) },
    { key: 'done', label: '已结束', count: summary.value ? rows.value.filter(r => r.bucket === 'done').length : null },
  ]
})

const slaText = computed(() => {
  const sla = summary.value?.sla
  if (!sla) return ''
  const wait = sla.oldestReadyWait ? `，最老就绪任务已等待 ${sla.oldestReadyWait}` : ''
  return `有调查任务超过 SLA${wait}，请优先处理「待干预」分组。`
})

const stageOptions = computed(() => {
  const seen = new Map()
  for (const r of rows.value) if (!seen.has(r.stage)) seen.set(r.stage, r.stageZh)
  return [...seen.entries()].map(([value, label]) => ({ value, label: `${label}（${value}）` }))
})

const hasFilter = computed(() => !!(fStage.value || q.value))

const filteredRows = computed(() => rows.value.filter(r => {
  const inTab = tab.value === 'intervene'
    ? INTERVENE_BUCKETS.has(r.bucket)
    : r.bucket === tab.value
  if (!inTab) return false
  if (fStage.value && r.stage !== fStage.value) return false
  const s = q.value.trim().toLowerCase()
  if (s && !r.id.toLowerCase().includes(s) && !(r.incident ?? '').toLowerCase().includes(s)) return false
  return true
}))

function rowClass({ row }) {
  // severity 投影恒 null（C-18①：无该列不冒充）；待审查行给最高视觉优先级
  return row.bucket === 'review' ? 'row-review' : ''
}

function shortId(id) { return id ? String(id).slice(0, 8) : '—' }

function budgetRisk(row) {
  // 队列投影无预算字段；仅卡点文本命中预算类事件时如实提示
  return !!row.blocker && /budget/i.test(row.blocker)
}

function syncQuery() {
  const query = {}
  if (tab.value !== 'running') query.tab = tab.value
  if (fStage.value) query.stage = fStage.value
  if (q.value) query.q = q.value
  router.replace({ query })
}

function setTab(key) { tab.value = key; syncQuery() }
function applyFilters() { q.value = kw.value.trim(); syncQuery() }
function resetFilters() { kw.value = ''; q.value = ''; fStage.value = ''; syncQuery() }

async function load() {
  loading.value = true
  try {
    const data = await api('/rca-runs')
    summary.value = data.summary
    rows.value = data.rows ?? []
    listState.value = 'ok'
  } catch (e) {
    listState.value = e?.response?.status === 403 ? 'forbidden' : 'error'
  } finally {
    loading.value = false
  }
}

function openRun(row) {
  router.push({ name: 'run', params: { runId: row.id } })
}

// 浏览器前进/后退：query 变化回灌筛选
watch(() => route.query, query => {
  const next = { tab: str(query.tab) || 'running', stage: str(query.stage), q: str(query.q) }
  if (next.tab === tab.value && next.stage === fStage.value && next.q === q.value) return
  tab.value = next.tab
  fStage.value = next.stage
  kw.value = next.q
  q.value = next.q
})

onMounted(load)
</script>

<style scoped>
.runs-page { display: flex; flex-direction: column; gap: var(--section-gap); }
.updated { font-size: var(--fs-aux); color: var(--ink-2); align-self: center; }

.tabs { display: flex; align-items: center; gap: 2px; padding: 4px 10px 0; }
.tab {
  border: none; background: none; cursor: pointer;
  padding: 8px 14px; font-size: var(--fs-body); color: var(--ink-2);
  border-bottom: 2px solid transparent; margin-bottom: -1px;
}
.tab:hover { color: var(--brand); }
.tab.cur { color: var(--brand); font-weight: 700; border-bottom-color: var(--brand); }
.t-count { margin-left: 6px; font-size: var(--fs-aux); color: var(--ink-2); }
.tab.cur .t-count { color: var(--brand); }

.toolbar { display: flex; align-items: center; gap: 8px; flex-wrap: wrap; padding: 12px var(--card-pad); }
.toolbar .kw { width: 260px; }
.toolbar .w-stage { width: 200px; }

.sla-banner { border-radius: var(--radius); }

.table-zone { padding: 8px var(--card-pad) 12px; }
.cell-sub { font-size: var(--fs-aux); color: var(--ink-2); overflow: hidden; text-overflow: ellipsis; white-space: nowrap; max-width: 320px; }
.inc-link { font-weight: 600; }
.muted { color: var(--ink-2); font-size: var(--fs-aux); }
.expand-box { padding: 8px 16px; font-size: var(--fs-body); line-height: 2; }
.e-label { display: inline-block; width: 110px; color: var(--ink-2); font-size: var(--fs-aux); }
.loading-box { height: 320px; }

/* 待审查行：琥珀底提示最高处理优先级（色条让位给徽章，不整行染色过重） */
.table-zone :deep(.row-review td:first-child) { box-shadow: inset 4px 0 0 var(--sev-p2); }
.table-zone :deep(.el-table__row) { cursor: pointer; }
</style>
