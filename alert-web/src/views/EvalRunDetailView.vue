<template>
  <div class="run-detail">
    <div class="crumb">
      <router-link to="/eval/runs">实验</router-link>
      <span class="sep">/</span>
      <b class="mono">{{ runId }}</b>
    </div>

    <template v-if="pageState === 'ok'">
      <!-- 第一屏固定：身份/版本 + 执行状态 + 当前阶段 + 最后有效进展时间；质量摘要 ≤4 项 -->
      <div class="card panel head-panel">
        <div class="head-grid">
          <div class="head-item">
            <div class="hi-label">实验 ID</div>
            <div class="hi-value">
              <span class="mono break">{{ run.runId }}</span>
              <el-button size="small" text @click="copyText(run.runId, '实验 ID 已复制')">复制</el-button>
            </div>
          </div>
          <div class="head-item">
            <div class="hi-label">执行状态</div>
            <div class="hi-value"><StatusBadge :status="badgeState(run.state)" /></div>
          </div>
          <div class="head-item">
            <div class="hi-label">当前阶段</div>
            <div class="hi-value muted">未统计（阶段投影依赖 EV-03）</div>
          </div>
          <div class="head-item">
            <div class="hi-label">最后有效进展时间</div>
            <div class="hi-value muted">未统计（依赖 EV-03）</div>
          </div>
        </div>
        <div class="head-versions">
          模型 <b>{{ run.model ?? '未统计' }}</b>
          <span class="sep">·</span> Prompt <b>{{ run.promptVersion ?? '未统计' }}</b>
          <span class="sep">·</span> 数据集 <b>{{ run.datasetVersion ?? '未统计' }}</b>
          <span class="sep">·</span> 案例数 <b>{{ fmtCount(run.caseCount) }}</b>
        </div>
        <div class="quality-strip">
          <div class="qs-item">
            <div class="qs-label">端到端命中率</div>
            <div class="qs-value">{{ fmtPctStat(run.endToEndHitRate) }}</div>
          </div>
          <div class="qs-item">
            <div class="qs-label">条件准确率</div>
            <div class="qs-value">{{ fmtPctStat(run.conditionalAccuracy) }}</div>
          </div>
          <div class="qs-item">
            <div class="qs-label">根因可判定覆盖率</div>
            <div class="qs-value">{{ fmtPctStat(run.coverage) }}</div>
          </div>
          <div class="qs-item">
            <div class="qs-label">未决率</div>
            <div class="qs-value">{{ fmtPctStat(run.unresolvedRate) }}</div>
          </div>
        </div>

        <!-- 配置详情默认折叠，完整 digest 不占首屏 -->
        <el-collapse class="cfg-collapse">
          <el-collapse-item title="配置详情（镜像 / 配置摘要、时间与症状计数）" name="cfg">
            <el-descriptions :column="2" border>
              <el-descriptions-item label="镜像摘要（registry digest）">
                <span class="mono break">{{ run.registryDigest ?? '未统计' }}</span>
                <el-button v-if="run.registryDigest" size="small" text @click="copyText(run.registryDigest, '镜像摘要已复制')">复制</el-button>
              </el-descriptions-item>
              <el-descriptions-item label="配置摘要（config digest）">
                <span class="mono break">{{ run.configDigest ?? '未统计' }}</span>
                <el-button v-if="run.configDigest" size="small" text @click="copyText(run.configDigest, '配置摘要已复制')">复制</el-button>
              </el-descriptions-item>
              <el-descriptions-item label="开始时间">{{ fmtTime(run.startedAt) }}</el-descriptions-item>
              <el-descriptions-item label="结束时间">{{ fmtTime(run.finishedAt) }}</el-descriptions-item>
              <el-descriptions-item label="耗时">{{ fmtDuration(run.startedAt, run.finishedAt) }}</el-descriptions-item>
              <el-descriptions-item label="症状 TP / FP / FN">{{ fmtTff(run) }}</el-descriptions-item>
            </el-descriptions>
          </el-collapse-item>
        </el-collapse>
      </div>

      <!-- 主区页签：案例结果 / 用量与对账（原“对比/成本”占位并入后者；对比走 /eval/compare） -->
      <div class="card tabs">
        <button
          v-for="t in tabs"
          :key="t.key"
          class="tab"
          :class="{ cur: tab === t.key }"
          @click="switchTab(t.key)"
        >{{ t.label }}</button>
      </div>

      <!-- 案例：verdict 筛选 + 游标分页 + 失败样本展开；复合 row-key 防同场景多轮串行 -->
      <div v-if="tab === 'cases'" class="card panel">
        <div class="case-filter">
          <el-select v-model="verdict" class="w-verdict" placeholder="全部判定" clearable @change="applyVerdict">
            <el-option value="DECIDABLE" label="可判定（DECIDABLE）" />
            <el-option value="UNRESOLVED" label="未决（UNRESOLVED）" />
            <el-option value="STRUCTURE_REJECTED" label="结构失败（STRUCTURE_REJECTED）" />
            <el-option value="TIMEOUT_OR_ABSENT" label="超时 / 缺席（TIMEOUT_OR_ABSENT）" />
          </el-select>
          <el-button :loading="casesLoading" @click="loadCases">刷新</el-button>
        </div>
        <template v-if="casesState === 'ok'">
          <el-table :data="cases" v-loading="casesLoading" :row-key="caseKey">
            <el-table-column type="expand">
              <template #default="{ row }">
                <div v-if="row.failureSample" class="fail-sample">
                  <div class="fs-label">失败样本</div>
                  <pre>{{ row.failureSample }}</pre>
                </div>
                <div v-else class="fs-none">无失败样本</div>
              </template>
            </el-table-column>
            <el-table-column prop="scenarioId" label="场景 ID（scenarioId）" min-width="160" show-overflow-tooltip />
            <el-table-column prop="roundNo" label="轮次" width="70" align="right" />
            <el-table-column label="判定" width="130">
              <template #default="{ row }">
                <StatusBadge :status="row.verdict" />
              </template>
            </el-table-column>
            <el-table-column label="根因命中" width="90">
              <template #default="{ row }">
                <el-tag v-if="row.rootCauseHit === false" type="danger" disable-transitions>未命中</el-tag>
                <span v-else-if="row.rootCauseHit === true">命中</span>
                <span v-else class="muted">未统计</span>
              </template>
            </el-table-column>
            <el-table-column label="期望根因" min-width="140" show-overflow-tooltip>
              <template #default="{ row }">{{ row.expectedRootCause ?? '未统计' }}</template>
            </el-table-column>
            <el-table-column label="实际根因" min-width="140" show-overflow-tooltip>
              <template #default="{ row }">{{ row.actualRootCause ?? '未统计' }}</template>
            </el-table-column>
            <el-table-column label="耗时" width="110" align="right">
              <template #default="{ row }">{{ row.latencyMs == null ? '未统计' : `${row.latencyMs} ms` }}</template>
            </el-table-column>
            <template #empty>
              <EmptyState kind="empty" :description="verdict ? '当前判定筛选无案例' : '暂无案例'" />
            </template>
          </el-table>
          <div class="pager">
            <span class="muted">已加载 {{ cases.length }} 条</span>
            <el-button v-if="casesCursor" :loading="casesLoadingMore" @click="loadMoreCases">加载更多</el-button>
          </div>
        </template>
        <EmptyState v-else-if="casesState === 'forbidden'" kind="forbidden" />
        <EmptyState v-else-if="casesState === 'error'" kind="error" @retry="loadCases" />
        <div v-else v-loading="true" class="loading-box" />
      </div>

      <!-- 用量与对账：占位说明（合并原“对比/成本”占位；均未交付能力集中说明） -->
      <div v-else class="card panel">
        <EmptyState kind="empty" description="用量与对账依赖后端 EV-06（调用账本接线与 usage 投影），本批未交付。实验对比请从实验列表选中两条后进入对比工作台（/eval/compare）。" :image-size="160" />
      </div>
    </template>

    <EmptyState v-else-if="pageState === 'forbidden'" kind="forbidden" />
    <EmptyState v-else-if="pageState === 'error'" kind="error" @retry="loadRun" />
    <div v-else v-loading="true" class="loading-box card" />
  </div>
</template>

<script setup>
// 实验详情（/eval/runs/:runId）：固定首屏（身份/状态/阶段/进展时间）+ 折叠配置 + 案例 / 用量与对账页签。
// EV-01：复合 row-key（caseExecutionId，旧接口回退 runId+scenarioId+roundNo）；watch runId 取消旧请求、
// 清空案例缓存、请求序号防旧响应覆盖；null 显示“未统计”；“已加载 N 条”。
import { computed, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { api } from '../api/client'
import EmptyState from '../components/common/EmptyState.vue'
import StatusBadge from '../components/common/StatusBadge.vue'
import { fmtCount, fmtDuration, fmtPctStat, fmtTime } from '../utils/format'

const route = useRoute()
const router = useRouter()

const runId = computed(() => String(route.params.runId ?? ''))
const badgeState = s => ({ RUNNING: 'RUNNING', SUCCEEDED: 'COMPLETED', FAILED: 'FAILED' }[s] ?? s)

// RV02：优先 caseExecutionId；旧接口无该字段时回退 runId+scenarioId+roundNo，禁止数组下标
const caseKey = row =>
  row.caseExecutionId ?? `${runId.value}|${row.scenarioId}|${row.roundNo}`

const tabs = [
  { key: 'cases', label: '案例结果' },
  { key: 'usage', label: '用量与对账' },
]
const TAB_KEYS = tabs.map(t => t.key)
const str = v => (typeof v === 'string' ? v : '')
// 旧链接兼容：tab=overview/compare/cost 归并到现页签
function normalizeTab(v) {
  if (v === 'compare' || v === 'cost') return 'usage'
  if (v === 'overview') return 'cases'
  return TAB_KEYS.includes(v) ? v : 'cases'
}
const tab = ref(normalizeTab(str(route.query.tab)))

const run = ref(null)
const pageState = ref('loading') // loading | ok | error | forbidden

const verdict = ref(str(route.query.verdict))
const cases = ref([])
const casesCursor = ref(null)
const casesState = ref('loading')
const casesLoading = ref(false)
const casesLoadingMore = ref(false)
let casesLoaded = false

// RV04：每类请求一个 AbortController + 单调序号；runId 变化即取消/丢弃旧请求
let runSeq = 0
let casesSeq = 0
let runCtl = null
let casesCtl = null

async function loadRun() {
  const id = runId.value
  const seq = ++runSeq
  runCtl?.abort()
  const ctl = new AbortController()
  runCtl = ctl
  pageState.value = 'loading'
  try {
    const r = await api(`/eval/runs/${encodeURIComponent(id)}`, { signal: ctl.signal })
    if (seq !== runSeq || id !== runId.value) return // 旧响应不覆盖新实验
    run.value = r
    pageState.value = 'ok'
  } catch (e) {
    if (ctl.signal.aborted || seq !== runSeq) return
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
  const id = runId.value
  const seq = ++casesSeq
  casesCtl?.abort()
  const ctl = new AbortController()
  casesCtl = ctl
  casesState.value = 'loading'
  casesLoading.value = true
  try {
    const d = await api(`/eval/runs/${encodeURIComponent(id)}/cases`, { params: caseParams(), signal: ctl.signal })
    if (seq !== casesSeq || id !== runId.value) return
    cases.value = d.items ?? []
    casesCursor.value = d.nextCursor ?? null
    casesState.value = 'ok'
    casesLoaded = true
  } catch (e) {
    if (ctl.signal.aborted || seq !== casesSeq) return
    casesState.value = e?.response?.status === 403 ? 'forbidden' : 'error'
  } finally {
    if (seq === casesSeq) casesLoading.value = false
  }
}

async function loadMoreCases() {
  if (!casesCursor.value) return
  const id = runId.value
  const seq = casesSeq // 必须与最近一次 loadCases 同代
  const cursor = casesCursor.value
  casesLoadingMore.value = true
  try {
    const d = await api(`/eval/runs/${encodeURIComponent(id)}/cases`, { params: caseParams(cursor) })
    if (seq !== casesSeq || id !== runId.value) return // 旧页不拼接
    cases.value = cases.value.concat(d.items ?? [])
    casesCursor.value = d.nextCursor ?? null
  } catch {
    if (seq === casesSeq) ElMessage.error('加载更多失败，请重试')
  } finally {
    if (seq === casesSeq) casesLoadingMore.value = false
  }
}

function switchTab(key) {
  tab.value = key
  const query = { ...route.query }
  if (key === 'cases') delete query.tab
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

// TP/FP/FN 三态：全 null → 未统计；单项 null 单项显示“未统计”，真实 0 显示 0
function fmtTff(r) {
  if (r.tp == null && r.fp == null && r.fn == null) return '未统计'
  return `${fmtCount(r.tp)} / ${fmtCount(r.fp)} / ${fmtCount(r.fn)}`
}

function copyText(text, tip) {
  navigator.clipboard?.writeText(text).then(
    () => ElMessage.success(tip),
    () => ElMessage.error('复制失败'),
  )
}

// 浏览器前进/后退：query 回灌（EU05）
watch(() => route.query, q => {
  const nextTab = normalizeTab(str(q.tab))
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

// RV04：同组件切换到另一实验——取消在飞请求、清空案例缓存、按新身份重载
watch(runId, (id, old) => {
  if (!id || id === old) return
  runCtl?.abort()
  casesCtl?.abort()
  runSeq++
  casesSeq++
  casesLoaded = false
  cases.value = []
  casesCursor.value = null
  casesState.value = 'loading'
  run.value = null
  loadRun()
  if (tab.value === 'cases') loadCases()
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
.muted { color: var(--ink-2); }

.panel { padding: var(--card-pad); }

.head-panel { display: flex; flex-direction: column; gap: 16px; }
.head-grid { display: grid; grid-template-columns: repeat(auto-fit, minmax(220px, 1fr)); gap: 12px 24px; }
.hi-label { font-size: var(--fs-aux); color: var(--ink-2); margin-bottom: 2px; }
.hi-value { font-size: 15px; }
.head-versions { font-size: var(--fs-body); color: var(--ink-2); }
.head-versions b { color: var(--ink); font-weight: 600; }
.head-versions .sep { margin: 0 8px; color: var(--line-strong); }

.quality-strip { display: flex; gap: 24px; flex-wrap: wrap; border-top: 1px solid var(--line); padding-top: 12px; }
.qs-label { font-size: var(--fs-aux); color: var(--ink-2); }
.qs-value { font-size: 18px; font-weight: 600; color: var(--head); }

.cfg-collapse { border-top: 1px solid var(--line); }
.cfg-collapse :deep(.el-collapse-item__header) { font-size: var(--fs-body); }

.tabs { display: flex; gap: 2px; padding: 4px 6px; overflow-x: auto; }
.tab {
  border: none; background: none; font-family: inherit; white-space: nowrap;
  padding: 7px 14px; font-size: 13px; color: var(--ink-2);
  border-radius: 8px 8px 0 0; border-bottom: 2px solid transparent; cursor: pointer;
}
.tab:hover { background: var(--bg); }
.tab.cur { color: var(--brand); font-weight: 700; border-bottom-color: var(--brand); }

.case-filter { display: flex; gap: 8px; margin-bottom: 12px; }
.case-filter .w-verdict { width: 260px; }

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
