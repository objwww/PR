<template>
  <div class="review-page">
    <PageHeader title="人工评审" subtitle="领取任务 → 盲评案例 → CAS 提交评分；提交后不可改，重评分 = 新任务">
      <template #actions>
        <el-button :loading="refreshing" @click="reloadAll">刷新</el-button>
      </template>
    </PageHeader>

    <!-- run 选择 + 任务生成 + 进度分桶 -->
    <div class="card run-bar">
      <el-select
        v-model="runId" class="w-run" filterable placeholder="选择实验（仅列最近 50 条）"
        :loading="runsLoading" @change="onRunChange"
      >
        <el-option
          v-for="r in runs" :key="r.runId" :value="r.runId"
          :label="`${r.displayName ?? r.runId}（${r.state ?? '未统计'}）`"
        />
      </el-select>
      <el-select v-model="perCase" class="w-per" :disabled="!runId">
        <el-option :value="1" label="每案例 1 份任务" />
        <el-option :value="2" label="每案例 2 份任务（双人评审）" />
      </el-select>
      <el-button
        type="primary" plain :disabled="!runId" :loading="generating"
        @click="generate"
      >生成 / 补足评审任务</el-button>
      <span class="muted gen-note">ensure 幂等：重复调用补足差额，不重复建任务</span>
    </div>

    <template v-if="runId">
      <div v-if="progress" class="card prog-strip">
        <div class="pg-item"><div class="pg-label">案例数</div><div class="pg-value">{{ progress.caseCount }}</div></div>
        <div class="pg-item"><div class="pg-label">任务总数</div><div class="pg-value">{{ progress.totalAssignments }}</div></div>
        <div class="pg-item"><div class="pg-label">待评</div><div class="pg-value">{{ progress.pending }}</div></div>
        <div class="pg-item"><div class="pg-label">进行中</div><div class="pg-value">{{ progress.inProgress }}</div></div>
        <div class="pg-item"><div class="pg-label">已提交</div><div class="pg-value">{{ progress.submitted }}</div></div>
        <div class="pg-item"><div class="pg-label">已评案例</div><div class="pg-value">{{ progress.reviewedCases }}</div></div>
        <div class="pg-item"><div class="pg-label">分歧案例</div><div class="pg-value">{{ progress.disagreementCases }}</div></div>
        <div class="asof muted">数据截至 {{ progress.asOf ? fmtTime(progress.asOf) : '未统计' }}</div>
      </div>

      <div class="card tabs">
        <button
          v-for="t in tabs" :key="t.key"
          class="tab" :class="{ cur: tab === t.key }" @click="tab = t.key"
        >{{ t.label }}</button>
      </div>

      <!-- 任务队列 -->
      <div v-if="tab === 'queue'" class="card panel">
        <div class="queue-bar">
          <el-radio-group v-model="scope" @change="loadAssignments">
            <el-radio-button value="mine">我的任务</el-radio-button>
            <el-radio-button value="all">全部任务</el-radio-button>
          </el-radio-group>
          <el-select v-model="statusFilter" class="w-status" clearable placeholder="全部状态" @change="loadAssignments">
            <el-option value="PENDING" label="待评" />
            <el-option value="IN_PROGRESS" label="进行中" />
            <el-option value="SUBMITTED" label="已提交" />
          </el-select>
        </div>
        <template v-if="queueState === 'ok'">
          <el-table :data="assignments" v-loading="queueLoading" row-key="assignmentId">
            <el-table-column label="场景 / 轮次" min-width="170">
              <template #default="{ row }">
                <span class="mono">{{ row.scenarioId ?? '未统计' }}</span>
                <span class="muted"> 第 {{ row.roundNo }} 轮</span>
              </template>
            </el-table-column>
            <el-table-column label="有效状态" width="110">
              <template #default="{ row }">
                <el-tag size="small" :type="statusTag(row.effectiveStatus)" disable-transitions>
                  {{ statusText(row.effectiveStatus) }}
                </el-tag>
                <el-tooltip
                  v-if="row.status !== row.effectiveStatus"
                  content="存储状态为进行中但租约已超时被惰性回收，如实按待评显示" placement="top">
                  <span class="facet-tip">?</span>
                </el-tooltip>
              </template>
            </el-table-column>
            <el-table-column prop="reviewer" label="评审人" width="120">
              <template #default="{ row }">{{ row.reviewer ?? '—' }}</template>
            </el-table-column>
            <el-table-column label="租约到期" width="160">
              <template #default="{ row }">{{ row.leaseExpiresAt ? fmtTime(row.leaseExpiresAt) : '—' }}</template>
            </el-table-column>
            <el-table-column label="提交时间" width="160">
              <template #default="{ row }">{{ row.submittedAt ? fmtTime(row.submittedAt) : '—' }}</template>
            </el-table-column>
            <el-table-column label="操作" width="150" fixed="right">
              <template #default="{ row }">
                <el-button
                  v-if="row.effectiveStatus === 'PENDING'"
                  size="small" type="primary" @click="claim(row)"
                >领取</el-button>
                <el-button
                  v-else size="small" text type="primary"
                  @click="openWorkspace(row.assignmentId)"
                >查看</el-button>
              </template>
            </el-table-column>
            <template #empty>
              <EmptyState kind="empty" description="当前筛选无评审任务；可点上方「生成 / 补足评审任务」" />
            </template>
          </el-table>
          <div class="pager">
            <span class="muted">已加载 {{ assignments.length }} 条</span>
            <el-button v-if="nextCursor" :loading="queueLoadingMore" @click="loadMore">加载更多</el-button>
          </div>
        </template>
        <EmptyState v-else-if="queueState === 'forbidden'" kind="forbidden" />
        <EmptyState v-else-if="queueState === 'notfound'" kind="empty"
          description="实验不存在或不可见（服务端 404）；请重新选择实验。" />
        <EmptyState v-else-if="queueState === 'error'" kind="error" @retry="loadAssignments" />
        <div v-else v-loading="true" class="loading-box" />
      </div>

      <!-- 分歧清单 -->
      <div v-else class="card panel">
        <template v-if="disState === 'ok'">
          <el-table :data="disagreements" v-loading="disLoading">
            <el-table-column label="场景 / 轮次" min-width="170">
              <template #default="{ row }">
                <span class="mono">{{ row.scenarioId ?? '未统计' }}</span>
                <span class="muted"> 第 {{ row.roundNo }} 轮</span>
              </template>
            </el-table-column>
            <el-table-column label="各评审最新结论" min-width="320">
              <template #default="{ row }">
                <div v-for="v in row.latestByReviewer ?? []" :key="v.verdictId" class="dg-row">
                  <b>{{ v.reviewer }}</b>：{{ verdictText(v.verdict) }}
                  <span v-if="v.score != null" class="muted">（{{ v.score }} 分）</span>
                  <span class="muted dg-reason" :title="v.reason">{{ v.reason }}</span>
                </div>
              </template>
            </el-table-column>
            <template #empty>
              <EmptyState kind="empty" description="暂无分歧（同一案例两名评审最新结论均一致）" />
            </template>
          </el-table>
        </template>
        <EmptyState v-else-if="disState === 'forbidden'" kind="forbidden" />
        <EmptyState v-else-if="disState === 'error'" kind="error" @retry="loadDisagreements" />
        <div v-else v-loading="true" class="loading-box" />
      </div>
    </template>

    <EmptyState v-else kind="empty" description="请先选择要评审的实验" :image-size="160" />

    <!-- 工作区抽屉：任务 + 盲评案例投影 + 结论历史 + 提交表单 -->
    <DetailDrawer v-model="wsOpen" title="评审工作区" :size="640">
      <div v-if="wsState === 'loading'" v-loading="true" class="ws-loading" />
      <EmptyState v-else-if="wsState === 'notfound'" kind="empty" description="评审任务不存在（404）" />
      <EmptyState v-else-if="wsState === 'error'" kind="error" @retry="reloadWorkspace" />
      <div v-else-if="ws" class="ws-body">
        <el-alert
          v-if="ws.reviewCase?.blind" type="info" :closable="false" show-icon class="ws-blind"
          title="盲评案例（HOLDOUT）：标准答案与期望症状不可见，请仅依据症状与系统输出评分。"
        />
        <div class="cd-section">
          <div class="cd-sec-title">案例</div>
          <el-descriptions :column="2" border size="small">
            <el-descriptions-item label="场景">{{ ws.reviewCase?.scenarioId ?? '未统计' }}</el-descriptions-item>
            <el-descriptions-item label="轮次">第 {{ ws.reviewCase?.roundNo }} 轮</el-descriptions-item>
            <el-descriptions-item label="机器判定">{{ ws.reviewCase?.machineVerdict ?? '未统计' }}</el-descriptions-item>
            <el-descriptions-item label="根因命中">
              <el-tag v-if="ws.reviewCase?.rootCauseHit === false" type="danger" disable-transitions>未命中</el-tag>
              <span v-else-if="ws.reviewCase?.rootCauseHit === true">命中</span>
              <span v-else class="muted">未统计</span>
            </el-descriptions-item>
            <el-descriptions-item label="实际根因" :span="2">{{ ws.reviewCase?.actualRootCause ?? '未统计' }}</el-descriptions-item>
            <el-descriptions-item v-if="!ws.reviewCase?.blind" label="期望根因" :span="2">
              {{ ws.reviewCase?.expectedRootCause ?? '未统计' }}
            </el-descriptions-item>
            <el-descriptions-item label="实际症状" :span="2">
              <template v-if="ws.reviewCase?.actualSymptomCodes?.length">
                <el-tag v-for="s in ws.reviewCase.actualSymptomCodes" :key="s" size="small" effect="plain" class="sym-chip" disable-transitions>{{ s }}</el-tag>
              </template>
              <span v-else class="muted">未统计</span>
            </el-descriptions-item>
            <el-descriptions-item v-if="!ws.reviewCase?.blind" label="期望症状" :span="2">
              <template v-if="ws.reviewCase?.expectedSymptomCodes?.length">
                <el-tag v-for="s in ws.reviewCase.expectedSymptomCodes" :key="s" size="small" effect="plain" class="sym-chip" disable-transitions>{{ s }}</el-tag>
              </template>
              <span v-else class="muted">未统计</span>
            </el-descriptions-item>
          </el-descriptions>
          <div v-if="ws.reviewCase?.failureSample" class="fail-sample">
            <div class="fs-label">失败样本</div>
            <pre>{{ ws.reviewCase.failureSample }}</pre>
          </div>
        </div>

        <div v-if="ws.reviewCase?.report" class="cd-section">
          <div class="cd-sec-title">系统输出（报告摘要）</div>
          <el-descriptions :column="1" border size="small">
            <el-descriptions-item label="摘要">{{ ws.reviewCase.report.summary ?? '未统计' }}</el-descriptions-item>
            <el-descriptions-item label="影响">{{ ws.reviewCase.report.impact ?? '未统计' }}</el-descriptions-item>
            <el-descriptions-item label="处置建议">{{ ws.reviewCase.report.remediation ?? '未统计' }}</el-descriptions-item>
          </el-descriptions>
        </div>

        <div class="cd-section">
          <div class="cd-sec-title">本案例结论历史（审计闭环，提交后不可改）</div>
          <template v-if="ws.verdictHistory?.length">
            <div v-for="v in ws.verdictHistory" :key="v.verdictId" class="vh-row">
              <b>{{ v.reviewer }}</b> · {{ verdictText(v.verdict) }}
              <span v-if="v.score != null" class="muted">（{{ v.score }} 分）</span>
              <span class="muted"> · rubric {{ v.rubricVersion }} · {{ fmtTime(v.createdAt) }}</span>
              <div class="muted vh-reason">{{ v.reason }}</div>
            </div>
          </template>
          <div v-else class="muted">暂无结论</div>
        </div>

        <!-- 提交表单：仅进行中任务可见；expectedRevision 取工作区最新 revision（CAS 锚） -->
        <div v-if="ws.assignment?.effectiveStatus === 'IN_PROGRESS'" class="cd-section">
          <div class="cd-sec-title">提交评分</div>
          <el-form label-width="110px">
            <el-form-item label="rubric 版本" required>
              <el-input v-model="form.rubricVersion" class="w-rubric" />
              <div class="field-note">默认取数据集读面透出的当前生效版本；服务端只接受注册表已知版本（未知 400）。</div>
            </el-form-item>
            <el-form-item label="结论" required>
              <el-select v-model="form.verdict" class="w-rubric">
                <el-option v-for="o in verdictOptions" :key="o.value" :value="o.value" :label="o.label" />
              </el-select>
            </el-form-item>
            <el-form-item label="分数（可选）">
              <el-input-number v-model="form.score" :min="0" :max="100" class="w-score" />
            </el-form-item>
            <el-form-item label="标签（可选）">
              <el-input v-model="form.labelsText" placeholder="逗号分隔，如：误报,根因偏移" />
            </el-form-item>
            <el-form-item label="理由" required>
              <el-input v-model="form.reason" type="textarea" :rows="3" placeholder="必填：评分依据与关键观察" />
            </el-form-item>
            <el-form-item label="证据引用">
              <el-input v-model="form.evidenceRefsText" placeholder="可选，逗号分隔的证据 ID" />
            </el-form-item>
            <el-form-item>
              <el-button type="primary" :loading="submitting" :disabled="!canSubmitReview" @click="submitReview">
                提交评分（提交后不可改）
              </el-button>
            </el-form-item>
          </el-form>
        </div>
        <el-alert
          v-else-if="ws.assignment?.effectiveStatus === 'SUBMITTED'" type="success"
          :closable="false" show-icon title="该任务已提交。更正与重评分 = 生成新任务 + 新行（insert-only）。"
        />
        <div class="asof muted">数据截至 {{ ws.asOf ? fmtTime(ws.asOf) : '未统计' }}</div>
      </div>
    </DetailDrawer>
  </div>
</template>

<script setup>
// R9 人工评审页（/eval/review）：接 EV-08 后端七端点（生成/领取/提交/列表/工作区/进度/分歧）。
// 诚实面：HOLDOUT 盲评 GT 恒 null（服务端 RLS）；提交后不可改；租约超时惰性回收如实显示。
import { computed, onMounted, reactive, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { api } from '../api/client'
import DetailDrawer from '../components/common/DetailDrawer.vue'
import EmptyState from '../components/common/EmptyState.vue'
import PageHeader from '../components/common/PageHeader.vue'
import { fmtTime } from '../utils/format'

const route = useRoute()
const router = useRouter()

const str = v => (typeof v === 'string' ? v : '')
const runId = ref(str(route.query.runId))

const runs = ref([])
const runsLoading = ref(false)
const perCase = ref(1)
const generating = ref(false)
const refreshing = ref(false)

const progress = ref(null)
const tabs = [
  { key: 'queue', label: '任务队列' },
  { key: 'disagreements', label: '分歧清单' },
]
const tab = ref('queue')

const scope = ref('mine')
const statusFilter = ref('')
const assignments = ref([])
const nextCursor = ref(null)
const queueState = ref('loading') // loading | ok | error | forbidden | notfound
const queueLoading = ref(false)
const queueLoadingMore = ref(false)

const disagreements = ref([])
const disState = ref('loading')
const disLoading = ref(false)

const wsOpen = ref(false)
const ws = ref(null)
const wsState = ref('loading') // loading | ok | error | notfound
let wsAssignmentId = null

const defaultRubricVersion = ref('')

const form = reactive({
  rubricVersion: '', verdict: 'CORRECT', score: null,
  labelsText: '', reason: '', evidenceRefsText: '',
})
const submitting = ref(false)

const verdictOptions = [
  { value: 'CORRECT', label: '正确（CORRECT）' },
  { value: 'PARTIAL', label: '部分正确（PARTIAL）' },
  { value: 'INCORRECT', label: '不正确（INCORRECT）' },
  { value: 'UNDECIDABLE', label: '无法判定（UNDECIDABLE）' },
  { value: 'INSUFFICIENT_SOURCE', label: '证据不足（INSUFFICIENT_SOURCE）' },
]
const verdictText = v => verdictOptions.find(o => o.value === v)?.label ?? (v ?? '未统计')
const statusText = s => ({ PENDING: '待评', IN_PROGRESS: '进行中', SUBMITTED: '已提交' }[s] ?? (s ?? '未统计'))
const statusTag = s => ({ PENDING: 'info', IN_PROGRESS: 'warning', SUBMITTED: 'success' }[s] ?? 'info')

const canSubmitReview = computed(() =>
  form.rubricVersion.trim().length > 0 && form.reason.trim().length > 0)

async function loadRuns() {
  runsLoading.value = true
  try {
    const d = await api('/eval/runs', { params: { limit: 50 } })
    runs.value = d.items ?? []
  } catch {
    ElMessage.error('实验列表加载失败')
  } finally {
    runsLoading.value = false
  }
}

// rubric 默认版本：数据集读面透出当前生效版本（EV-08 契约），取首行即注册表 current
async function loadDefaultRubric() {
  try {
    const d = await api('/eval/datasets')
    defaultRubricVersion.value = d.items?.find(i => i.rubricVersion)?.rubricVersion ?? ''
  } catch { /* 默认值缺失不阻塞；提交时服务端校验 */ }
}

async function loadProgress() {
  if (!runId.value) return
  try {
    progress.value = await api(`/eval/runs/${encodeURIComponent(runId.value)}/review-progress`)
  } catch {
    progress.value = null // 进度失败不阻塞队列
  }
}

function assignmentParams(cursor) {
  const p = { runId: runId.value, scope: scope.value, limit: 50 }
  if (statusFilter.value) p.status = statusFilter.value
  if (cursor) p.cursor = cursor
  return p
}

async function loadAssignments() {
  if (!runId.value) return
  queueState.value = 'loading'
  queueLoading.value = true
  try {
    const d = await api('/eval/reviews/assignments', { params: assignmentParams() })
    assignments.value = d.items ?? []
    nextCursor.value = d.nextCursor ?? null
    queueState.value = 'ok'
  } catch (e) {
    const st = e?.response?.status
    queueState.value = st === 403 ? 'forbidden' : st === 404 ? 'notfound' : 'error'
  } finally {
    queueLoading.value = false
  }
}

async function loadMore() {
  if (!nextCursor.value) return
  queueLoadingMore.value = true
  try {
    const d = await api('/eval/reviews/assignments', { params: assignmentParams(nextCursor.value) })
    assignments.value = assignments.value.concat(d.items ?? [])
    nextCursor.value = d.nextCursor ?? null
  } catch {
    ElMessage.error('加载更多失败，请重试')
  } finally {
    queueLoadingMore.value = false
  }
}

async function loadDisagreements() {
  if (!runId.value) return
  disState.value = 'loading'
  disLoading.value = true
  try {
    const d = await api('/eval/reviews/disagreements', { params: { runId: runId.value } })
    disagreements.value = d.items ?? []
    disState.value = 'ok'
  } catch (e) {
    disState.value = e?.response?.status === 403 ? 'forbidden' : 'error'
  } finally {
    disLoading.value = false
  }
}

async function generate() {
  generating.value = true
  try {
    const d = await api(`/eval/runs/${encodeURIComponent(runId.value)}/review-assignments`, {
      method: 'POST', body: { assignmentsPerCase: perCase.value },
    })
    ElMessage.success(`任务生成完成：新建 ${d.created} 份，当前开放共 ${d.openTotal} 份`)
    loadProgress()
    loadAssignments()
  } catch (e) {
    const st = e?.response?.status
    ElMessage.error(st === 404 ? '实验不存在（404）' : (e?.response?.data?.error || '任务生成失败'))
  } finally {
    generating.value = false
  }
}

async function claim(row) {
  try {
    const d = await api(`/eval/reviews/assignments/${encodeURIComponent(row.assignmentId)}/claim`,
      { method: 'POST' })
    ElMessage.success(d.replayed ? '你已持有该任务（幂等重放）' : '领取成功，租约 30 分钟')
    loadAssignments()
    openWorkspace(row.assignmentId)
  } catch (e) {
    const st = e?.response?.status
    if (st === 409) {
      ElMessage.warning(e?.response?.data?.error || '领取冲突')
      loadAssignments() // 冲突即刷新队列（他人持有/已提交如实呈现）
    } else if (st === 404) {
      ElMessage.error('评审任务不存在')
      loadAssignments()
    } else {
      ElMessage.error('领取失败，请重试')
    }
  }
}

function openWorkspace(assignmentId) {
  wsAssignmentId = assignmentId
  wsOpen.value = true
  reloadWorkspace()
}

async function reloadWorkspace() {
  if (!wsAssignmentId) return
  wsState.value = 'loading'
  try {
    const d = await api(`/eval/reviews/assignments/${encodeURIComponent(wsAssignmentId)}`)
    ws.value = d
    wsState.value = 'ok'
    form.rubricVersion = form.rubricVersion || defaultRubricVersion.value
  } catch (e) {
    wsState.value = e?.response?.status === 404 ? 'notfound' : 'error'
  }
}

function csvList(text) {
  return text.split(',').map(s => s.trim()).filter(Boolean)
}

async function submitReview() {
  if (!canSubmitReview.value || submitting.value) return
  submitting.value = true
  try {
    await api(`/eval/reviews/assignments/${encodeURIComponent(wsAssignmentId)}/submit`, {
      method: 'POST',
      body: {
        rubricVersion: form.rubricVersion.trim(),
        verdict: form.verdict,
        score: form.score ?? null,
        labels: csvList(form.labelsText),
        reason: form.reason.trim(),
        evidenceRefs: csvList(form.evidenceRefsText),
        expectedRevision: ws.value?.assignment?.revision,
      },
    })
    ElMessage.success('评分已提交（insert-only，不可改；更正请生成新任务）')
    form.reason = ''
    form.evidenceRefsText = ''
    reloadWorkspace()
    loadProgress()
    loadAssignments()
  } catch (e) {
    const st = e?.response?.status
    const msg = e?.response?.data?.error
    if (st === 409) {
      ElMessage.warning(msg || '提交冲突')
      reloadWorkspace() // revision 漂移/状态变化 → 重取工作区
      loadAssignments()
    } else {
      ElMessage.error(msg || '提交失败，请检查后重试')
    }
  } finally {
    submitting.value = false
  }
}

function onRunChange() {
  router.replace({ query: runId.value ? { runId: runId.value } : {} })
  progress.value = null
  assignments.value = []
  nextCursor.value = null
  loadProgress()
  loadAssignments()
  loadDisagreements()
}

async function reloadAll() {
  refreshing.value = true
  try {
    await Promise.all([loadProgress(), loadAssignments(), loadDisagreements()])
  } finally {
    refreshing.value = false
  }
}

// 浏览器前进/后退：?runId= 回灌（EU05）
watch(() => route.query.runId, v => {
  const next = str(v)
  if (next && next !== runId.value) {
    runId.value = next
    onRunChange()
  }
})

onMounted(() => {
  loadRuns()
  loadDefaultRubric()
  if (runId.value) {
    loadProgress()
    loadAssignments()
    loadDisagreements()
  }
})
</script>

<style scoped>
.review-page { display: flex; flex-direction: column; gap: var(--section-gap); }

.run-bar { display: flex; align-items: center; gap: 8px; flex-wrap: wrap; padding: 12px var(--card-pad); }
.w-run { width: 380px; }
.w-per { width: 220px; }
.gen-note { font-size: var(--fs-aux); }

.prog-strip { display: flex; align-items: center; gap: 24px; flex-wrap: wrap; padding: 12px var(--card-pad); }
.pg-item { text-align: center; }
.pg-label { font-size: var(--fs-aux); color: var(--ink-2); }
.pg-value { font-size: 18px; font-weight: 600; }
.asof { margin-left: auto; font-size: var(--fs-aux); }

.tabs { display: flex; gap: 2px; padding: 4px 6px; }
.tab {
  white-space: nowrap; padding: 7px 14px; font-size: 13px; color: var(--ink-2);
  border-radius: 8px 8px 0 0; border-bottom: 2px solid transparent; cursor: pointer;
  background: none; border-top: none; border-left: none; border-right: none; font-family: inherit;
}
.tab:hover { background: var(--bg); }
.tab.cur { color: var(--brand); font-weight: 700; border-bottom-color: var(--brand); }

.panel { padding: 8px var(--card-pad) 12px; }
.queue-bar { display: flex; gap: 8px; margin: 8px 0 12px; }
.w-status { width: 140px; }
.pager { display: flex; align-items: center; justify-content: center; gap: 16px; padding: 12px 0 4px; }
.muted { color: var(--ink-2); font-size: var(--fs-aux); }
.loading-box { height: 240px; }
.facet-tip { color: var(--ink-2); cursor: help; margin-left: 2px; }

.dg-row { line-height: 1.8; }
.dg-reason { display: inline-block; max-width: 260px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; vertical-align: bottom; }

.ws-loading { height: 320px; }
.ws-body { display: flex; flex-direction: column; gap: 16px; }
.ws-blind { margin-bottom: 4px; }
.cd-section { display: flex; flex-direction: column; gap: 8px; }
.cd-sec-title { font-weight: 600; font-size: var(--fs-body); }
.sym-chip { margin: 2px 4px 2px 0; }
.fail-sample .fs-label { font-size: var(--fs-aux); color: var(--ink-2); margin-bottom: 4px; }
.fail-sample pre { background: var(--bg); padding: 10px; border-radius: 6px; overflow: auto; max-height: 200px; }
.vh-row { line-height: 1.8; }
.vh-reason { padding-left: 12px; }
.w-rubric { width: 260px; }
.w-score { width: 160px; }
.field-note { font-size: var(--fs-aux); color: var(--ink-2); line-height: 1.6; }
</style>
