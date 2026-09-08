<template>
  <div class="cases-page">
    <!-- 面包屑条（全局壳已含顶栏；此处只留页内 crumb + 数据新鲜度） -->
    <div class="crumb card">
      <span><b>首页</b><span class="sep">/</span>处置中心</span>
      <span class="env">生产环境 ｜ 数据更新至 {{ summary?.updatedAt || '--:--:--' }}</span>
    </div>

    <div class="card frame">
      <!-- 页签：待办视图 + 通知（计数来自 summary 投影） -->
      <div class="tabs">
        <span
          v-for="t in tabs"
          :key="t.key"
          :class="{ cur: tab === t.key }"
          @click="switchTab(t.key)"
        >{{ t.label }} {{ t.count }}</span>
      </div>

      <template v-if="tab !== 'notify'">
        <!-- 筛选工具条：状态/优先级/原因 + 批量认领 -->
        <div class="toolbar">
          状态
          <select v-model="fStatus" class="field">
            <option value="">OPEN/ACKED</option>
            <option value="OPEN">OPEN</option>
            <option value="ACKED">ACKED</option>
            <option value="RESOLVED">RESOLVED</option>
          </select>
          优先级
          <select v-model="fPriority" class="field">
            <option value="">P0–P2</option>
            <option>P0</option><option>P1</option><option>P2</option>
          </select>
          原因
          <select v-model="fReason" class="field">
            <option value="">全部</option>
            <option v-for="r in reasonOptions" :key="r" :value="r">{{ r }}</option>
          </select>
          <button class="btn" @click="batchClaim">批量认领</button>
          <span v-if="conflictMsg" class="conflict">{{ conflictMsg }}</span>
        </div>

        <div class="row">
          <!-- 左：OperatorCase 队列（按 SLA 风险排序；行首 4px severity 色条 + 状态 badge 分离） -->
          <div class="col queue">
            <div class="lbl"> OperatorCase 队列（按 SLA 风险排序）</div>
            <div v-if="loading" class="empty">加载中…</div>
            <div v-else-if="!filteredCases.length" class="empty">
              筛选无结果 <button class="btn" @click="clearFilters">清除筛选</button>
            </div>
            <div
              v-for="c in filteredCases"
              :key="c.id"
              class="list-item case-item"
              :class="[sevClass(c.priority), { cur: selectedId === c.id }]"
              @click="selectCase(c.id)"
            >
              <span class="tag" :class="slaTag(c)">{{ c.priority }} {{ c.slaLabel }}</span>
              <span class="tag" :class="statusTag(c.status)">{{ c.status }}</span>
              <b>case#{{ c.id }} {{ c.subject }}</b><br>
              <small>
                <router-link :to="`/runs/${c.runId}`" @click.stop>run#{{ c.runId }}</router-link><template v-if="c.incidentType"> / {{ c.incidentType }}</template>
                ｜ {{ c.owner ? `owner=${c.owner}` : '未分配' }}
                <template v-if="c.ackOverdue"> ｜ {{ c.ackOverdue }}</template>
                <template v-else-if="c.status !== 'OPEN'"> ｜ resolve_due {{ c.resolveDue }}</template>
                <template v-if="c.id === 'c62'"> ｜ evidence {{ c.evidenceCount }} 条</template>
              </small>
              <span class="item-ops" @click.stop>
                <button v-if="!c.owner" class="btn" @click="claimCase(c)">认领</button>
                <router-link class="btn" :to="`/runs/${c.runId}`">查看 Run →</router-link>
                <button v-if="c.owner" class="btn" @click="selectCase(c.id)">打开 →</button>
              </span>
            </div>
          </div>

          <!-- 右：Case 详情 -->
          <div class="col detail">
            <div class="lbl"> Case 详情：case#{{ selectedId || '—' }}</div>
            <CaseDetailPanel v-if="detail" :detail="detail" @command="onCommand" />
            <div v-else class="empty">请选择左侧 Case</div>
          </div>
        </div>
      </template>

      <!-- 通知标签：投递与已读状态；卡片带对象深链 -->
      <template v-else>
        <div class="notify-list">
          <div v-if="!notifications.length" class="empty">暂无通知</div>
          <div v-for="n in notifications" :key="n.id" class="card notify-card" :class="{ unread: !n.read }">
            <div class="n-head">
              <i v-if="!n.read" class="unread-dot"></i>
              <span class="tag" :class="notifyTag(n.type)">{{ notifyLabel(n.type) }}</span>
              <b>{{ n.title }}</b>
              <span class="n-time">{{ n.time }}</span>
            </div>
            <div class="n-body">{{ n.body }}</div>
            <div class="n-ops">
              <button v-if="!n.read" class="btn" @click="readNotify(n.id)">标已读</button>
              <router-link v-if="n.runId" class="btn" :to="`/runs/${n.runId}`">查看 Run →</router-link>
              <button v-if="n.caseId" class="btn" @click="openCase(n.caseId)">查看 Case →</button>
            </div>
          </div>
        </div>
      </template>

      <div class="banner">
        “通知”标签示意：报告就绪 / Case 分配 / 系统异常；通知可标已读并跳转对象，但不会因此关闭 OperatorCase。
      </div>
    </div>
  </div>
</template>

<script setup>
// P4 处置中心（/cases，线框图 v1.6 #p4）：OperatorCase 管理（认领/SLA/闭环）+ 同页通知标签
// 数据经 api(path, { mock })：后端 OperatorCase/通知 API 落码后切换联调，组件代码不变
import { computed, onMounted, ref } from 'vue'
import { api } from '../api/client.js'
import { NOTIFY_TYPE_ZH, zh } from '../dict/displayNameZh.js'
import CaseDetailPanel from '../components/CaseDetailPanel.vue'
import {
  fetchCaseSummary, fetchCases, fetchCaseDetail, fetchNotifications,
  caseCommand, markNotificationRead,
} from '../mocks/cases.js'

const summary = ref(null)
const cases = ref([])
const detail = ref(null)
const notifications = ref([])
const loading = ref(true)
const tab = ref('mine')
const selectedId = ref(null)
const fStatus = ref('')
const fPriority = ref('')
const fReason = ref('')
const conflictMsg = ref('')

const ME = 'operator'

const tabs = computed(() => {
  const t = summary.value?.tabs || {}
  return [
    { key: 'mine', label: '我的待办', count: t.mine ?? 0 },
    { key: 'all', label: '全部待办', count: t.all ?? 0 },
    { key: 'unassigned', label: '未分配', count: t.unassigned ?? 0 },
    { key: 'overdue', label: '已逾期', count: t.overdue ?? 0 },
    { key: 'notify', label: '通知', count: t.notifyUnread ?? 0 },
  ]
})

const reasonOptions = computed(() => [...new Set(cases.value.map(c => c.reasonCode))])

const filteredCases = computed(() => {
  let list = cases.value
  if (tab.value === 'mine') list = list.filter(c => c.owner === ME && c.status !== 'RESOLVED')
  else if (tab.value === 'all') list = list.filter(c => c.status !== 'RESOLVED')
  else if (tab.value === 'unassigned') list = list.filter(c => !c.owner && c.status !== 'RESOLVED')
  else if (tab.value === 'overdue') list = list.filter(c => c.overdue && c.status !== 'RESOLVED')
  if (fStatus.value) list = list.filter(c => c.status === fStatus.value)
  if (fPriority.value) list = list.filter(c => c.priority === fPriority.value)
  if (fReason.value) list = list.filter(c => c.reasonCode === fReason.value)
  return list
})

const sevClass = p => ({ P0: 'sev-p0', P1: 'sev-p1', P2: 'sev-p2' }[p] || '')
const slaTag = c => (c.overdue || c.priority !== 'P2' ? 't-red' : 't-gray')
const statusTag = s => ({ OPEN: 't-orange', ACKED: 't-blue', RESOLVED: 't-green' }[s] || 't-gray')

// 中文名统一走 M7-09 版本化词典 src/dict/displayNameZh.js；tag 配色是 UI 本地映射，非词典内容
const NOTIFY_TAG = { REPORT_READY: 't-blue', CASE_ASSIGNED: 't-orange', SYSTEM_ERROR: 't-red' }
const notifyLabel = t => zh(NOTIFY_TYPE_ZH, t)
const notifyTag = t => NOTIFY_TAG[t] || 't-gray'

onMounted(async () => {
  const [s, cs, ns] = await Promise.all([
    api('/cases/summary', { mock: fetchCaseSummary }),
    api('/cases', { mock: fetchCases }),
    api('/notifications', { mock: fetchNotifications }),
  ])
  summary.value = s
  cases.value = cs
  notifications.value = ns
  loading.value = false
  selectCase(cs[0]?.id)
})

async function selectCase(id) {
  if (!id) return
  selectedId.value = id
  detail.value = await api(`/cases/${id}`, { mock: () => fetchCaseDetail(id) })
}

function switchTab(key) {
  tab.value = key
}

function clearFilters() {
  fStatus.value = ''; fPriority.value = ''; fReason.value = ''
}

const idemKey = () => `ui-${Date.now()}-${Math.random().toString(16).slice(2, 8)}`

// 命令统一走 expected_revision + idempotency_key；旧 revision 冲突时刷新服务端新状态
async function runCommand(caseId, action, payload = {}) {
  conflictMsg.value = ''
  const base = cases.value.find(c => c.id === caseId)
  const res = await caseCommand(caseId, action, {
    expectedRevision: payload.expectedRevision ?? base?.revision,
    idempotencyKey: idemKey(),
    ...payload,
  })
  if (!res.ok && res.conflict) {
    conflictMsg.value = '命令冲突：服务端 revision 已更新，已刷新最新状态后可重试。'
    applyCase(res.case)
    return
  }
  if (res.ok) {
    applyCase(res.case)
    if (selectedId.value === caseId) await selectCase(caseId)
    await refreshSummary()
  }
}

function applyCase(updated) {
  if (!updated) return
  const i = cases.value.findIndex(c => c.id === updated.id)
  if (i >= 0) cases.value[i] = { ...cases.value[i], ...updated }
}

function onCommand(action, payload = {}) {
  if (!selectedId.value) return
  runCommand(selectedId.value, action, { ...payload, expectedRevision: detail.value?.revision })
}

function claimCase(c) {
  runCommand(c.id, 'claim')
}

async function batchClaim() {
  for (const c of filteredCases.value.filter(x => !x.owner)) {
    await runCommand(c.id, 'claim')
  }
}

async function readNotify(id) {
  const res = await markNotificationRead(id)
  if (res.ok) {
    const n = notifications.value.find(x => x.id === id)
    if (n) n.read = true
    await refreshSummary()
  }
}

// 通知卡片 → Case 深链：切到全部待办并选中该 Case（已读不改变 Case 状态）
function openCase(caseId) {
  tab.value = 'all'
  selectCase(caseId)
}

async function refreshSummary() {
  summary.value = await api('/cases/summary', { mock: fetchCaseSummary })
}
</script>

<style scoped>
.crumb {
  display: flex; justify-content: space-between; align-items: center; flex-wrap: wrap;
  gap: 4px 12px; padding: 7px 14px; font-size: 12px; color: var(--ink-2); margin-bottom: 10px;
}
.crumb b { color: var(--head); font-weight: 600; }
.crumb .sep { color: #9aa5b1; margin: 0 4px; }
.crumb .env { font-size: 11.5px; }

.frame { padding-bottom: 10px; }

.tabs { display: flex; flex-wrap: wrap; gap: 5px; padding: 9px 12px 0; border-bottom: 1px solid var(--line); background: #f6f8fb; border-radius: var(--radius) var(--radius) 0 0; }
.tabs span {
  border: 1px solid var(--line-strong); border-bottom: none; border-radius: 8px 8px 0 0;
  padding: 5px 12px; font-size: 11.5px; background: #fff; color: var(--ink-2); cursor: pointer;
}
.tabs span.cur { background: var(--brand); color: #fff; border-color: var(--brand); font-weight: 600; }

.toolbar {
  display: flex; gap: 6px; flex-wrap: wrap; align-items: center;
  padding: 8px 12px; border-bottom: 1px solid var(--line); background: #fafbfd; font-size: 11.5px;
}
.field {
  display: inline-block; border: 1px solid var(--line-strong); border-radius: 6px;
  background: #fff; padding: 3px 8px; min-width: 88px; color: var(--ink-2); font-size: 11.5px;
}
.conflict { color: var(--bad); font-size: 11.5px; }

.row { display: flex; gap: 14px; padding: 10px 12px; align-items: flex-start; }
.col.queue { flex: 3; min-width: 0; }
.col.detail { flex: 2; min-width: 320px; }
.lbl { font-size: 12px; font-weight: 700; color: var(--head); margin-bottom: 4px; }

.list-item { border-bottom: 1px solid #edf0f4; padding: 7px 4px; font-size: 12px; }
.list-item:last-child { border-bottom: none; }

/* 行首 4px severity 色条（色条=priority，badge=状态，分离表达，E-19 §4） */
.case-item { border-left: 4px solid transparent; padding-left: 8px; cursor: pointer; border-radius: 4px; }
.case-item.sev-p0 { border-left-color: var(--sev-p0); }
.case-item.sev-p1 { border-left-color: var(--sev-p1); }
.case-item.sev-p2 { border-left-color: var(--sev-p2); }
.case-item:hover { background: #f6f9fe; }
.case-item.cur { background: var(--brand-soft); }
.case-item .tag { margin-right: 4px; }
.item-ops { margin-left: 8px; display: inline-flex; gap: 4px; }
.item-ops .btn { padding: 1px 8px; }

.empty { padding: 24px; text-align: center; color: var(--ink-2); font-size: 12px; }

.notify-list { padding: 10px 12px; display: flex; flex-direction: column; gap: 8px; }
.notify-card { padding: 10px 12px; border-left: 4px solid var(--line-strong); }
.notify-card.unread { border-left-color: var(--brand); }
.n-head { display: flex; align-items: center; gap: 8px; font-size: 12.5px; flex-wrap: wrap; }
.unread-dot { width: 8px; height: 8px; border-radius: 50%; background: var(--brand); flex: none; }
.n-time { margin-left: auto; color: var(--ink-2); font-size: 11px; }
.n-body { font-size: 12px; color: var(--ink-2); margin: 6px 0; }
.n-ops { display: flex; gap: 6px; }

.banner {
  margin: 10px 12px 0; padding: 7px 10px; border: 1px solid #ecd9a0;
  border-left: 4px solid #e6b93f; border-radius: 8px; background: var(--warn-bg); font-size: 11.5px;
}

@media (max-width: 1100px) {
  .row { flex-wrap: wrap; }
  .col.detail { min-width: 0; flex-basis: 100%; }
}
</style>
