<template>
  <div>
    <PageHeader
      title="AI 诊断"
      subtitle="问 AI：快捷问由真源 SQL 实时组装（零模型零幻觉），自由追问接真模型并留账本锚；问答落库可回放"
    />

    <!-- 统计行（/api/v1/diag/stats 真账本聚合） -->
    <div class="stat-row">
      <div class="stat-card"><span class="stat-num">{{ fmtNum(stats?.total) }}</span><span class="stat-label">累计问答</span></div>
      <div class="stat-card"><span class="stat-num">{{ fmtNum(stats?.incidents) }}</span><span class="stat-label">涉及事件</span></div>
      <div class="stat-card"><span class="stat-num">{{ fmtNum(stats?.today) }}</span><span class="stat-label">今日新增</span></div>
      <div class="stat-card"><span class="stat-num">{{ freeShare }}</span><span class="stat-label">自由问占比</span></div>
      <div class="stat-card">
        <span class="stat-num">{{ praiseRate }}</span><span class="stat-label">回答好评率</span>
        <span class="stat-sub-inline">有用 {{ fmtNum(stats?.upCount) }} / 无用 {{ fmtNum(stats?.downCount) }}</span>
      </div>
      <div class="stat-updated">数据更新至 {{ updatedAt }}</div>
    </div>

    <div class="diag-grid">
      <!-- 左栏：事件上下文 -->
      <div class="card panel ctx-panel">
        <h2 class="zone-title">选择告警事件</h2>
        <el-select
          v-model="selectedId" filterable placeholder="选择要诊断的告警事件"
          :loading="incidentsLoading" style="width: 100%" @change="loadHistory"
        >
          <el-option
            v-for="it in incidents" :key="it.id"
            :label="`${it.alertname ?? it.incidentKey ?? it.incidentId}（${it.service ?? '—'}）`"
            :value="incidentKeyOf(it)"
          />
        </el-select>

        <template v-if="current">
          <div class="ctx-card">
            <div class="ctx-line">
              <StatusBadge v-if="sevKey" :severity="sevKey" />
              <StatusBadge :status="current.status" />
            </div>
            <div class="ctx-name">{{ current.alertname ?? current.incidentKey }}</div>
            <div class="ctx-sub">服务：{{ current.service ?? '—' }}</div>
            <div class="ctx-sub">最近事件：{{ fmtTime(current.lastEventAt ?? current.episodeStartedAt) }}</div>
            <div class="ctx-sub">接收 {{ current.receivedCount ?? 0 }} 次 / 去重事件 {{ current.distinctEventCount ?? 0 }}</div>
            <div class="ctx-ops">
              <router-link :to="`/alerts/${selectedId}`"><el-button size="small" plain>打开告警详情</el-button></router-link>
              <router-link v-if="current.currentRcaRunId" :to="`/runs/${current.currentRcaRunId}`">
                <el-button size="small" plain>查看调查</el-button>
              </router-link>
            </div>
          </div>
        </template>
        <EmptyState
          v-else-if="!incidentsLoading"
          kind="empty"
          description="暂无可选告警事件——数据来自 /api/v1/incidents 实时列表，事件产生后即可诊断。"
        />
      </div>

      <!-- 右栏：聊天流 -->
      <div class="card panel chat-panel">
        <div class="chat-toolbar">
          <span class="chat-title">诊断会话{{ current ? ` · ${current.alertname ?? current.incidentKey}` : '' }}</span>
          <el-tag v-if="historyLoading" size="small" type="info" disable-transitions>加载中……</el-tag>
          <span class="flex-spacer" />
          <el-button size="small" :disabled="!selectedId" @click="loadHistory">刷新</el-button>
        </div>

        <div ref="chatListRef" class="chat-list">
          <template v-if="history.length">
            <div v-for="(d, i) in history" :key="i" class="qa-pair">
              <div class="bubble q">
                <span class="q-text">{{ d.question }}</span>
                <el-tag size="small" :type="d.question_key === 'FREE' ? 'warning' : 'info'" disable-transitions>
                  {{ d.question_key === 'FREE' ? '自由问' : '快捷问' }}
                </el-tag>
              </div>
              <div class="bubble a">
                <span class="a-text">{{ d.answer }}</span>
                <div class="a-meta">
                  {{ fmtTime(d.created_at) }} · {{ String(d.created_by ?? '').replace(/^human:/, '') }}
                  <template v-if="refInfo(d)"> · 模型 {{ refInfo(d).model ?? '—' }} · Token {{ fmtNum(refInfo(d).totalTokens) }}</template>
                </div>
                <!-- 3.4 补强：回答评价（Bits AI thumbs 同律）——一答一评可改评，踩可选原因 -->
                <div class="fb-line">
                  <el-button size="small" :type="d.rating === 'UP' ? 'success' : ''" plain
                    :loading="fbLoadingId === d.id" @click="submitFeedback(d, 'UP')">有用</el-button>
                  <el-button size="small" :type="d.rating === 'DOWN' ? 'danger' : ''" plain
                    :loading="fbLoadingId === d.id" @click="toggleDown(d)">无用</el-button>
                  <el-tag v-if="d.rating === 'UP'" size="small" type="success" disable-transitions>已评：有用</el-tag>
                  <el-tag v-else-if="d.rating === 'DOWN'" size="small" type="danger" disable-transitions>
                    已评：无用{{ d.feedback_reason ? ' · ' + d.feedback_reason : '' }}
                  </el-tag>
                </div>
                <div v-if="downOpenId === d.id" class="fb-down">
                  <el-select v-model="downReason" size="small" style="width: 180px"
                    placeholder="原因（可选）">
                    <el-option label="答非所问" value="OFF_TARGET" />
                    <el-option label="事实不准确" value="INACCURATE" />
                    <el-option label="信息不完整" value="INCOMPLETE" />
                    <el-option label="其他" value="OTHER" />
                  </el-select>
                  <el-button size="small" type="danger" :loading="fbLoadingId === d.id"
                    @click="submitFeedback(d, 'DOWN')">提交无用评价</el-button>
                </div>
              </div>
            </div>
          </template>
          <EmptyState
            v-else-if="!historyLoading && selectedId"
            kind="empty"
            description="该事件暂无诊断问答记录——点击下方快捷问开始，或输入问题自由追问；问答落库后可回放。"
          />
          <div v-else-if="!selectedId" class="chat-tip">先在左侧选择要诊断的告警事件。</div>
        </div>

        <div class="chat-quick">
          <el-button
            v-for="q in questions" :key="q.key" size="small" plain
            :disabled="!selectedId || askLoading" :loading="loadingKey === q.key"
            @click="askQuick(q)"
          >{{ q.text }}</el-button>
        </div>
        <div class="chat-input">
          <el-input
            v-model="freeText" size="small" maxlength="500" show-word-limit
            :disabled="!selectedId" placeholder="自由追问（接真模型，仅基于该事件已核实事实作答）"
            @keyup.enter="askFree"
          />
          <el-button
            size="small" type="primary" :disabled="!selectedId || !freeText?.trim()"
            :loading="freeLoading" @click="askFree"
          >提问</el-button>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup>
// 3.4 AI 对话/诊断页（前端产品化 Wave 4 残留补齐；后端诊断会话 v1/v2 已由路线 v2 落地）：
//   本页 = 事件上下文（左）+ 诊断聊天流（右）+ 统计行，全部真数据：
//   事件列表 /api/v1/incidents、问答五端点 /v1/incidents/{id}/diag*、统计 /api/v1/diag/stats。
//   快捷问=引用式真源 SQL 组装（零模型）；自由问=真模型+账本锚（NO_RUN_TO_ANCHOR 时如实拒绝）。
import { computed, nextTick, onMounted, ref } from 'vue'
import { useRoute } from 'vue-router'
import { ElMessage } from 'element-plus'
import { api } from '../api/client'
import { useSessionStore } from '../stores/session.js'
import { DIAG_REJECT } from '../dict/zh.js'
import { mapSeverity } from '../utils/severity'
import { fmtTime } from '../utils/format'
import PageHeader from '../components/common/PageHeader.vue'
import StatusBadge from '../components/common/StatusBadge.vue'
import EmptyState from '../components/common/EmptyState.vue'

const session = useSessionStore()
const route = useRoute()

const stats = ref(null)
const incidents = ref([])
const incidentsLoading = ref(false)
const selectedId = ref(null)
const questions = ref([])
const history = ref([])
const historyLoading = ref(false)
const freeText = ref('')
const loadingKey = ref('')
const freeLoading = ref(false)
const askLoading = ref(false)
const chatListRef = ref(null)
const updatedAt = ref('—')

const current = computed(() => incidents.value.find(it => incidentKeyOf(it) === selectedId.value) ?? null)
const sevKey = computed(() => mapSeverity(current.value?.severity).key)
const freeShare = computed(() => {
  const t = Number(stats.value?.total ?? 0)
  if (!t) return '—'
  return Math.round(Number(stats.value?.freeCount ?? 0) * 100 / t) + '%'
})
// 好评率：有用/(有用+无用)，未评不计入分母（ Bits AI 同口径）；无评价如实 —
const praiseRate = computed(() => {
  const up = Number(stats.value?.upCount ?? 0)
  const down = Number(stats.value?.downCount ?? 0)
  if (up + down === 0) return '—'
  return Math.round(up * 100 / (up + down)) + '%'
})

function refInfo(d) {
  if (d.question_key !== 'FREE' || !d.answer_refs) return null
  try {
    const r = JSON.parse(d.answer_refs)
    return r && r.model ? r : null
  } catch { return null }
}
function incidentKeyOf(it) { return it.incidentId ?? it.id }
function fmtNum(n) { return n == null ? '—' : Number(n).toLocaleString('zh-CN') }
function rejectZh(reason) {
  if (!reason) return '未知原因'
  if (String(reason).startsWith('MODEL_CALL_FAILED:')) {
    return `模型调用失败（${String(reason).split(':')[1] ?? '未知错误码'}）`
  }
  return DIAG_REJECT[reason] ?? reason
}
function scrollBottom() {
  nextTick(() => { if (chatListRef.value) chatListRef.value.scrollTop = chatListRef.value.scrollHeight })
}

async function loadStats() {
  try {
    const s = await api('/v1/diag/stats')
    if (s?.status === 'OK') {
      stats.value = s
      updatedAt.value = new Date().toLocaleString('zh-CN', { hour12: false })
    }
  } catch { /* 统计面缺席如实保留 — */ }
}
async function loadIncidents() {
  incidentsLoading.value = true
  try {
    const res = await api('/v1/incidents', { params: { limit: 50 } })
    incidents.value = res?.items ?? []
    const fromQuery = route.query.incidentId
    if (fromQuery && incidents.value.some(it => incidentKeyOf(it) === fromQuery)) selectedId.value = fromQuery
    else if (incidents.value.length) selectedId.value = incidentKeyOf(incidents.value[0])
    if (selectedId.value) loadHistory()
  } catch { /* 事件面缺席如实留空 */ } finally { incidentsLoading.value = false }
}
async function loadHistory() {
  if (!selectedId.value) return
  historyLoading.value = true
  try {
    const [qs, his] = await Promise.all([
      api(`/v1/incidents/${selectedId.value}/diag/questions`),
      api(`/v1/incidents/${selectedId.value}/diag`),
    ])
    questions.value = qs?.questions ?? []
    history.value = (his?.items ?? []).slice().reverse()
    scrollBottom()
  } catch { history.value = [] } finally { historyLoading.value = false }
}
function afterAsk(res) {
  if (res?.status === 'OK') { loadHistory(); loadStats(); return true }
  ElMessage.warning(`被拒绝：${rejectZh(res?.reason)}`)
  return false
}
async function askQuick(q) {
  loadingKey.value = q.key
  askLoading.value = true
  try {
    const res = await api(`/v1/incidents/${selectedId.value}/diag`, {
      method: 'POST', body: { key: q.key, createdBy: `human:${session.user || 'oncall'}` },
    })
    if (afterAsk(res)) scrollBottom()
  } catch { ElMessage.error('快捷问失败，请重试') } finally { loadingKey.value = ''; askLoading.value = false }
}
async function askFree() {
  if (!freeText.value?.trim()) return
  freeLoading.value = true
  try {
    const res = await api(`/v1/incidents/${selectedId.value}/diag/free`, {
      method: 'POST', body: { question: freeText.value.trim(), createdBy: `human:${session.user || 'oncall'}` },
    })
    if (afterAsk(res)) { freeText.value = ''; scrollBottom() }
  } catch { ElMessage.error('提问失败，请重试') } finally { freeLoading.value = false }
}

// 3.4 补强·回答评价：一答一评 upsert 可改评；踩先展开原因（可选）再提交
const fbLoadingId = ref('')
const downOpenId = ref('')
const downReason = ref('')
function toggleDown(d) {
  if (downOpenId.value === d.id) { downOpenId.value = ''; return }
  downOpenId.value = d.id
  downReason.value = ''
}
async function submitFeedback(d, rating) {
  fbLoadingId.value = d.id
  try {
    const res = await api(`/v1/incidents/${selectedId.value}/diag/feedback`, {
      method: 'POST',
      body: {
        sessionId: d.id, rating,
        reason: rating === 'DOWN' ? downReason.value : null,
        createdBy: `human:${session.user || 'oncall'}`,
      },
    })
    if (res?.status === 'OK') {
      ElMessage.success(rating === 'UP' ? '已评：有用' : '已评：无用')
      downOpenId.value = ''
      downReason.value = ''
      await loadHistory()
      await loadStats()
    } else {
      ElMessage.warning(`被拒绝：${rejectZh(res?.reason)}`)
    }
  } catch { ElMessage.error('评价失败，请重试') } finally { fbLoadingId.value = '' }
}

onMounted(() => { loadStats(); loadIncidents() })
</script>

<style scoped>
.stat-row { display: flex; gap: 16px; align-items: center; flex-wrap: wrap; margin-bottom: var(--section-gap); }
.stat-card {
  flex: 0 0 150px; background: var(--card); border: 1px solid var(--line); border-radius: var(--radius);
  padding: 12px 16px; display: flex; flex-direction: column; gap: 2px; box-shadow: var(--shadow);
}
.stat-num { font-size: 22px; font-weight: 700; color: var(--ink); }
.stat-label { font-size: var(--fs-aux); color: var(--ink-2); }
.stat-updated { margin-left: auto; font-size: var(--fs-aux); color: var(--ink-2); }

.diag-grid { display: grid; grid-template-columns: 340px minmax(0, 1fr); gap: var(--section-gap); align-items: start; }
.ctx-panel { min-height: 320px; }
.zone-title { font-size: var(--fs-section); margin-bottom: 12px; }
.ctx-card { margin-top: 14px; padding: 12px; border: 1px dashed var(--line-strong); border-radius: var(--radius); }
.ctx-line { display: flex; gap: 6px; margin-bottom: 6px; }
.ctx-name { font-weight: 700; color: var(--ink); margin-bottom: 4px; word-break: break-all; }
.ctx-sub { font-size: var(--fs-aux); color: var(--ink-2); line-height: 1.8; }
.ctx-ops { margin-top: 10px; display: flex; gap: 8px; }

.chat-panel { display: flex; flex-direction: column; }
.chat-toolbar { display: flex; align-items: center; gap: 10px; margin-bottom: 10px; }
.chat-title { font-weight: 700; color: var(--ink); }
.flex-spacer { flex: 1 1 auto; }
.chat-list { height: calc(100vh - 420px); min-height: 300px; overflow: auto; padding-right: 4px; }
.chat-tip { color: var(--ink-2); text-align: center; padding: 40px 0; }
.qa-pair { margin-bottom: 14px; }
.bubble { border-radius: var(--radius); padding: 8px 12px; font-size: var(--fs-body); line-height: 1.6; }
.bubble.q {
  background: var(--brand-soft); border: 1px solid #b3d8ff; margin-left: auto; max-width: 70%;
  width: fit-content; display: flex; align-items: center; gap: 8px;
}
.q-text { color: var(--ink); font-weight: 600; }
.bubble.a { background: #fff; border: 1px solid var(--line); margin-top: 6px; max-width: 88%; }
.a-text { color: var(--ink); white-space: pre-wrap; word-break: break-word; }
.a-meta { margin-top: 4px; font-size: var(--fs-aux); color: var(--ink-2); }
.fb-line { margin-top: 6px; display: flex; align-items: center; gap: 6px; }
.fb-down { margin-top: 6px; display: flex; gap: 8px; align-items: center; }
.stat-sub-inline { font-size: var(--fs-aux); color: var(--ink-2); }
.chat-quick { display: flex; gap: 8px; flex-wrap: wrap; border-top: 1px dashed var(--line); padding-top: 10px; margin-top: 10px; }
.chat-input { display: flex; gap: 8px; margin-top: 10px; }
.chat-input .el-input { flex: 1 1 auto; }
</style>
