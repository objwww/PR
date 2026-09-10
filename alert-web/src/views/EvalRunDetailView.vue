<template>
  <div class="run-detail">
    <div class="crumb">
      <router-link to="/eval/runs">实验</router-link>
      <span class="sep">/</span>
      <b class="mono">{{ runId }}</b>
    </div>

    <template v-if="pageState === 'ok'">
      <!-- 页签进 URL query：刷新/分享不丢上下文 -->
      <div class="card tabs">
        <button
          v-for="t in tabs"
          :key="t.key"
          class="tab"
          :class="{ cur: tab === t.key }"
          @click="switchTab(t.key)"
        >{{ t.label }}</button>
      </div>

      <!-- 概览：全字段 descriptions，digest 可复制 -->
      <div v-if="tab === 'overview'" class="card panel">
        <el-descriptions :column="2" border>
          <el-descriptions-item label="实验 runId">
            <span class="mono">{{ run.runId }}</span>
            <el-button size="small" text @click="copyText(run.runId, 'runId 已复制')">复制</el-button>
          </el-descriptions-item>
          <el-descriptions-item label="状态">
            <StatusBadge :status="badgeState(run.state)" />
          </el-descriptions-item>
          <el-descriptions-item label="数据集版本">{{ run.datasetVersion ?? '—' }}</el-descriptions-item>
          <el-descriptions-item label="模型">{{ run.model ?? '—' }}</el-descriptions-item>
          <el-descriptions-item label="Prompt 版本">{{ run.promptVersion ?? '—' }}</el-descriptions-item>
          <el-descriptions-item label="案例数">{{ run.caseCount ?? '—' }}</el-descriptions-item>
          <el-descriptions-item label="registry digest">
            <span class="mono break">{{ run.registryDigest ?? '—' }}</span>
            <el-button v-if="run.registryDigest" size="small" text @click="copyText(run.registryDigest, 'registry digest 已复制')">复制</el-button>
          </el-descriptions-item>
          <el-descriptions-item label="config digest">
            <span class="mono break">{{ run.configDigest ?? '—' }}</span>
            <el-button v-if="run.configDigest" size="small" text @click="copyText(run.configDigest, 'config digest 已复制')">复制</el-button>
          </el-descriptions-item>
          <el-descriptions-item label="开始时间">{{ fmtTime(run.startedAt) }}</el-descriptions-item>
          <el-descriptions-item label="结束时间">{{ fmtTime(run.finishedAt) }}</el-descriptions-item>
          <el-descriptions-item label="耗时">{{ fmtDuration(run.startedAt, run.finishedAt) }}</el-descriptions-item>
          <el-descriptions-item label="TP / FP / FN">{{ run.tp ?? 0 }} / {{ run.fp ?? 0 }} / {{ run.fn ?? 0 }}</el-descriptions-item>
          <el-descriptions-item label="覆盖率">{{ fmtPct(run.coverage) }}</el-descriptions-item>
          <el-descriptions-item label="条件准确率">{{ fmtPct(run.conditionalAccuracy) }}</el-descriptions-item>
          <el-descriptions-item label="端到端命中率">{{ fmtPct(run.endToEndHitRate) }}</el-descriptions-item>
          <el-descriptions-item label="未决率">{{ fmtPct(run.unresolvedRate) }}</el-descriptions-item>
        </el-descriptions>
      </div>

      <!-- 案例：verdict 筛选 + 游标分页 + 失败样本展开 -->
      <div v-else-if="tab === 'cases'" class="card panel">
        <div class="case-filter">
          <el-input
            v-model="verdict" class="w-verdict" placeholder="按 verdict 原值筛选" clearable
            @keyup.enter="applyVerdict" @clear="applyVerdict"
          />
          <el-button :loading="casesLoading" @click="applyVerdict">查询</el-button>
        </div>
        <template v-if="casesState === 'ok'">
          <el-table :data="cases" v-loading="casesLoading" row-key="scenarioId">
            <el-table-column type="expand">
              <template #default="{ row }">
                <div v-if="row.failureSample" class="fail-sample">
                  <div class="fs-label">失败样本</div>
                  <pre>{{ row.failureSample }}</pre>
                </div>
                <div v-else class="fs-none">无失败样本</div>
              </template>
            </el-table-column>
            <el-table-column prop="scenarioId" label="场景 scenarioId" min-width="160" show-overflow-tooltip />
            <el-table-column prop="roundNo" label="轮次" width="70" align="right" />
            <el-table-column label="verdict" width="130">
              <template #default="{ row }">
                <StatusBadge :status="row.verdict" />
              </template>
            </el-table-column>
            <el-table-column label="根因命中" width="90">
              <template #default="{ row }">
                <el-tag v-if="row.rootCauseHit === true" type="success" disable-transitions>命中</el-tag>
                <el-tag v-else-if="row.rootCauseHit === false" type="danger" disable-transitions>未命中</el-tag>
                <span v-else>—</span>
              </template>
            </el-table-column>
            <el-table-column label="期望根因" min-width="140" show-overflow-tooltip>
              <template #default="{ row }">{{ row.expectedRootCause ?? '—' }}</template>
            </el-table-column>
            <el-table-column label="实际根因" min-width="140" show-overflow-tooltip>
              <template #default="{ row }">{{ row.actualRootCause ?? '—' }}</template>
            </el-table-column>
            <el-table-column label="耗时" width="100" align="right">
              <template #default="{ row }">{{ row.latencyMs == null ? '—' : `${row.latencyMs} ms` }}</template>
            </el-table-column>
            <template #empty>
              <EmptyState kind="empty" :description="verdict ? '当前 verdict 筛选无案例' : '暂无案例'" />
            </template>
          </el-table>
          <div class="pager">
            <span class="muted">共 {{ cases.length }} 条</span>
            <el-button v-if="casesCursor" :loading="casesLoadingMore" @click="loadMoreCases">加载更多</el-button>
          </div>
        </template>
        <EmptyState v-else-if="casesState === 'forbidden'" kind="forbidden" />
        <EmptyState v-else-if="casesState === 'error'" kind="error" @retry="loadCases" />
        <div v-else v-loading="true" class="loading-box" />
      </div>

      <!-- 对比 / 成本：无后端支撑，明示建设中 -->
      <div v-else-if="tab === 'compare'" class="card panel">
        <EmptyState kind="empty" description="实验对比建设中" />
      </div>
      <div v-else class="card panel">
        <EmptyState kind="empty" description="成本与费用对账建设中" />
      </div>
    </template>

    <EmptyState v-else-if="pageState === 'forbidden'" kind="forbidden" />
    <EmptyState v-else-if="pageState === 'error'" kind="error" @retry="loadRun" />
    <div v-else v-loading="true" class="loading-box card" />
  </div>
</template>

<script setup>
// UI-6 实验详情（/eval/runs/:runId）：概览（全字段）+ 案例 / 对比 / 成本 页签（tab 进 URL query）
import { computed, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { api } from '../api/client'
import EmptyState from '../components/common/EmptyState.vue'
import StatusBadge from '../components/common/StatusBadge.vue'
import { fmtDuration, fmtPct, fmtTime } from '../utils/format'

const route = useRoute()
const router = useRouter()

const runId = computed(() => String(route.params.runId ?? ''))
const badgeState = s => ({ RUNNING: 'RUNNING', SUCCEEDED: 'COMPLETED', FAILED: 'FAILED' }[s] ?? s)

const tabs = [
  { key: 'overview', label: '概览' },
  { key: 'cases', label: '案例' },
  { key: 'compare', label: '对比' },
  { key: 'cost', label: '成本' },
]
const TAB_KEYS = tabs.map(t => t.key)
const str = v => (typeof v === 'string' ? v : '')
const tab = ref(TAB_KEYS.includes(str(route.query.tab)) ? str(route.query.tab) : 'overview')

const run = ref(null)
const pageState = ref('loading') // loading | ok | error | forbidden

const verdict = ref(str(route.query.verdict))
const cases = ref([])
const casesCursor = ref(null)
const casesState = ref('loading')
const casesLoading = ref(false)
const casesLoadingMore = ref(false)
let casesLoaded = false

async function loadRun() {
  pageState.value = 'loading'
  try {
    run.value = await api(`/eval/runs/${encodeURIComponent(runId.value)}`)
    pageState.value = 'ok'
  } catch (e) {
    pageState.value = e?.response?.status === 403 ? 'forbidden' : 'error'
  }
}

function caseParams(cursor) {
  const p = { limit: 50 }
  if (verdict.value) p.verdict = verdict.value
  if (cursor) p.cursor = cursor
  return p
}

async function loadCases() {
  casesState.value = 'loading'
  casesLoading.value = true
  try {
    const d = await api(`/eval/runs/${encodeURIComponent(runId.value)}/cases`, { params: caseParams() })
    cases.value = d.items ?? []
    casesCursor.value = d.nextCursor ?? null
    casesState.value = 'ok'
    casesLoaded = true
  } catch (e) {
    casesState.value = e?.response?.status === 403 ? 'forbidden' : 'error'
  } finally {
    casesLoading.value = false
  }
}

async function loadMoreCases() {
  if (!casesCursor.value) return
  casesLoadingMore.value = true
  try {
    const d = await api(`/eval/runs/${encodeURIComponent(runId.value)}/cases`, { params: caseParams(casesCursor.value) })
    cases.value = cases.value.concat(d.items ?? [])
    casesCursor.value = d.nextCursor ?? null
  } catch {
    ElMessage.error('加载更多失败，请重试')
  } finally {
    casesLoadingMore.value = false
  }
}

function switchTab(key) {
  tab.value = key
  const query = { ...route.query }
  if (key === 'overview') delete query.tab
  else query.tab = key
  if (key !== 'cases') delete query.verdict
  router.replace({ query })
  if (key === 'cases' && !casesLoaded) loadCases()
}

function applyVerdict() {
  const query = { ...route.query, tab: 'cases' }
  if (verdict.value) query.verdict = verdict.value
  else delete query.verdict
  router.replace({ query })
  loadCases()
}

function copyText(text, tip) {
  navigator.clipboard?.writeText(text).then(
    () => ElMessage.success(tip),
    () => ElMessage.error('复制失败'),
  )
}

// 浏览器前进/后退：query 回灌
watch(() => route.query, q => {
  const nextTab = TAB_KEYS.includes(str(q.tab)) ? str(q.tab) : 'overview'
  if (nextTab !== tab.value) {
    tab.value = nextTab
    if (nextTab === 'cases' && !casesLoaded) loadCases()
  }
  const nextVerdict = str(q.verdict)
  if (nextVerdict !== verdict.value) {
    verdict.value = nextVerdict
    if (tab.value === 'cases') loadCases()
  }
})

onMounted(() => {
  loadRun()
  if (tab.value === 'cases') loadCases()
})
</script>

<style scoped>
.run-detail { display: flex; flex-direction: column; gap: var(--section-gap); }

.crumb { font-size: 13px; color: var(--ink-2); }
.crumb a { color: var(--brand); }
.crumb .sep { margin: 0 6px; color: var(--line-strong); }
.mono { font-family: var(--mono, monospace); }
.break { word-break: break-all; }

.tabs { display: flex; gap: 2px; padding: 4px 6px; overflow-x: auto; }
.tab {
  border: none; background: none; font-family: inherit; white-space: nowrap;
  padding: 7px 14px; font-size: 13px; color: var(--ink-2);
  border-radius: 8px 8px 0 0; border-bottom: 2px solid transparent; cursor: pointer;
}
.tab:hover { background: var(--bg); }
.tab.cur { color: var(--brand); font-weight: 700; border-bottom-color: var(--brand); }

.panel { padding: var(--card-pad); }
.case-filter { display: flex; gap: 8px; margin-bottom: 12px; }
.case-filter .w-verdict { width: 240px; }

.fail-sample { padding: 8px 16px; }
.fs-label { font-size: var(--fs-aux); color: var(--ink-2); margin-bottom: 4px; }
.fail-sample pre {
  margin: 0; padding: 8px 12px; background: var(--bg); border-radius: var(--radius-ctl);
  font-size: 12px; white-space: pre-wrap; word-break: break-all;
}
.fs-none { padding: 8px 16px; font-size: var(--fs-aux); color: var(--ink-2); }

.pager { display: flex; align-items: center; justify-content: center; gap: 16px; padding: 12px 0 4px; }
.muted { color: var(--ink-2); font-size: var(--fs-aux); }
.loading-box { height: 320px; }
</style>
