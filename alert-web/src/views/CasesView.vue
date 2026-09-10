<template>
  <div class="cases-page">
    <PageHeader title="处置中心" :subtitle="`认领、处理并闭环告警处置 ｜ 数据更新至 ${summary?.updatedAt || '—'}`" />

    <div class="card frame">
      <!-- 待办视图切换 + 筛选 + 批量认领 -->
      <div class="toolbar">
        <el-radio-group v-model="tab">
          <el-radio-button v-for="t in tabs" :key="t.key" :value="t.key">{{ t.label }}（{{ t.count }}）</el-radio-button>
        </el-radio-group>
        <el-select v-model="fStatus" placeholder="全部状态" clearable class="w-ctl">
          <el-option value="OPEN" label="待认领" />
          <el-option value="ACKED" label="处理中" />
          <el-option value="RESOLVED" label="已解决" />
        </el-select>
        <el-select v-model="fPriority" placeholder="全部优先级" clearable class="w-ctl">
          <el-option value="P0" label="P0" />
          <el-option value="P1" label="P1" />
          <el-option value="P2" label="P2" />
        </el-select>
        <el-select v-model="fReason" placeholder="全部原因" clearable class="w-ctl">
          <el-option v-for="r in reasonOptions" :key="r" :value="r" :label="r" />
        </el-select>
        <span class="flex-spacer" />
        <el-button :disabled="!filteredCases.some(c => !c.owner)" @click="batchClaim">批量认领</el-button>
      </div>

      <div class="row">
        <!-- 左：待办列表（固定 340px；仅展示主题/优先级/负责人/期限） -->
        <div class="col queue">
          <div v-if="loading" v-loading="true" class="loading-box" />
          <template v-else-if="filteredCases.length">
            <div
              v-for="c in filteredCases"
              :key="c.id"
              class="case-item"
              :class="[mapSeverity(priorityRaw(c.priority)).rowClass, { cur: selectedId === c.id }]"
              @click="selectCase(c.id)"
            >
              <div class="ci-top">
                <b class="ci-subject">{{ c.subject }}</b>
                <StatusBadge :severity="c.priority" />
              </div>
              <div class="ci-meta">
                <span>{{ c.owner ? `负责人 ${c.owner}` : '未分配' }}</span>
                <span v-if="c.ackOverdue" class="due overdue">{{ c.ackOverdue }}</span>
                <span v-else-if="c.status !== 'OPEN'" class="due">解决期限 {{ c.resolveDue }}</span>
                <el-tag size="small" :type="statusType(c.status)" effect="plain">{{ statusLabel(c.status) }}</el-tag>
              </div>
              <div class="ci-ops" @click.stop>
                <router-link class="run-link" :to="`/runs/${c.runId}`">关联调查 →</router-link>
                <el-button v-if="!c.owner" size="small" type="primary" plain @click="claimCase(c)">认领</el-button>
              </div>
            </div>
          </template>
          <EmptyState v-else kind="empty" description="筛选无结果">
            <el-button size="small" @click="clearFilters">清除筛选</el-button>
          </EmptyState>
        </div>

        <!-- 右：选中处置详情（冲突提示贴近操作区，不整页堆警告） -->
        <div class="col detail">
          <el-alert
            v-if="conflictMsg" type="warning" :closable="true" class="conflict-alert"
            :title="conflictMsg" @close="conflictMsg = ''"
          />
          <CaseDetailPanel v-if="detail" :detail="detail" @command="onCommand" />
          <EmptyState v-else kind="empty" description="请选择左侧待办查看详情" />
        </div>
      </div>
    </div>
  </div>
</template>

<script setup>
// UI-4 处置中心（/cases）：稳定主从布局——左 340px 待办列表（主题/优先级/负责人/期限），
// 右侧选中详情（下一步操作 + 处置记录 + 调查证据）。命令四件套（claim/ack/resolve/assign）
// 走真端点，带 expectedRevision + idempotencyKey；409/422 为业务结局就地解析。
import { computed, onMounted, ref } from 'vue'
import { api } from '../api/client.js'
import PageHeader from '../components/common/PageHeader.vue'
import EmptyState from '../components/common/EmptyState.vue'
import StatusBadge from '../components/common/StatusBadge.vue'
import CaseDetailPanel from '../components/CaseDetailPanel.vue'
import { mapSeverity } from '../utils/severity'

const summary = ref(null)
const cases = ref([])
const detail = ref(null)
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

// priority 已是 P0/P1/P2 展示级，mapSeverity 只借它的行色条 class
const priorityRaw = p => ({ P0: 'critical', P1: 'warning', P2: 'info' }[p] || '')
const statusType = s => ({ OPEN: 'warning', ACKED: 'primary', RESOLVED: 'success' }[s] || 'info')
const statusLabel = s => ({ OPEN: '待认领', ACKED: '处理中', RESOLVED: '已解决' }[s] || s)

onMounted(async () => {
  try {
    const [s, cs] = await Promise.all([
      api('/cases/summary'),
      api('/cases'),
    ])
    summary.value = s
    cases.value = cs
    selectCase(cs[0]?.id)
  } catch (e) {
    ElMessage.error(e?.response?.data?.error || '加载失败，请刷新重试')
  } finally {
    loading.value = false
  }
})

async function selectCase(id) {
  if (!id) return
  selectedId.value = id
  try {
    detail.value = await api(`/cases/${id}`)
  } catch {
    ElMessage.error('加载处置详情失败，请重试')
  }
}

function clearFilters() {
  fStatus.value = ''; fPriority.value = ''; fReason.value = ''
}

const idemKey = () => `ui-${Date.now()}-${Math.random().toString(16).slice(2, 8)}`

// 409（revision 冲突）/422（非法迁移）是业务结局不是异常：后端仍回
// {ok,conflict,error,case} 结构体（含最新投影），就地解析
async function postCommand(caseId, action, payload) {
  try {
    return await api(`/cases/${caseId}/${action}`, { method: 'POST', body: payload })
  } catch (e) {
    if (e?.response && (e.response.status === 409 || e.response.status === 422)) {
      return e.response.data
    }
    throw e
  }
}

// 命令统一走 expectedRevision + idempotencyKey；旧版本冲突时刷新服务端新状态
async function runCommand(caseId, action, payload = {}) {
  conflictMsg.value = ''
  const base = cases.value.find(c => c.id === caseId)
  let res
  try {
    res = await postCommand(caseId, action, {
      expectedRevision: payload.expectedRevision ?? base?.revision,
      idempotencyKey: idemKey(),
      ...payload,
    })
  } catch (e) {
    ElMessage.error(e?.response?.data?.error || '操作失败，请重试')
    return
  }
  if (!res.ok && res.conflict) {
    conflictMsg.value = '操作冲突：该处置已被他人更新，已刷新为最新状态，确认后可重试。'
    applyCase(res.case)
    return
  }
  if (!res.ok) {
    ElMessage.error(res.error || '操作被拒绝')
    return
  }
  applyCase(res.case)
  if (selectedId.value === caseId) await selectCase(caseId)
  await refreshSummary()
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

async function refreshSummary() {
  try { summary.value = await api('/cases/summary') } catch { /* 保留旧统计 */ }
}
</script>

<style scoped>
.cases-page { display: flex; flex-direction: column; gap: var(--section-gap); }

.frame { padding-bottom: 4px; }
.toolbar {
  display: flex; gap: 10px; flex-wrap: wrap; align-items: center;
  padding: 12px var(--card-pad); border-bottom: 1px solid var(--line);
}
.w-ctl { width: 130px; }
.flex-spacer { flex: 1; }

/* 主从布局：左 340px 待办列表 + 右详情；窄屏先列表后详情 */
.row { display: flex; gap: var(--section-gap); padding: 16px var(--card-pad); align-items: flex-start; }
.col.queue { flex: none; width: 340px; max-height: calc(100vh - 220px); overflow-y: auto; }
.col.detail { flex: 1; min-width: 0; }
.conflict-alert { margin-bottom: 12px; }
.loading-box { height: 240px; }

/* 待办条目：行首 4px severity 色条 + 状态分离表达 */
.case-item {
  border: 1px solid var(--line); border-left-width: 4px; border-radius: var(--radius);
  padding: 10px 12px; margin-bottom: 10px; cursor: pointer; background: #fff;
  display: flex; flex-direction: column; gap: 6px;
}
.case-item:hover { box-shadow: 0 0 0 2px var(--brand-soft); }
.case-item.cur { box-shadow: 0 0 0 2px var(--brand); }
.case-item.sev-p0 { border-left-color: var(--sev-p0); }
.case-item.sev-p1 { border-left-color: var(--sev-p1); }
.case-item.sev-p2 { border-left-color: var(--sev-p2); }
.ci-top { display: flex; align-items: flex-start; justify-content: space-between; gap: 8px; }
.ci-subject { font-size: var(--fs-body); color: var(--head); line-height: 1.5; }
.ci-meta { display: flex; align-items: center; gap: 8px; flex-wrap: wrap; font-size: var(--fs-aux); color: var(--ink-2); }
.due.overdue { color: var(--bad); font-weight: 600; }
.ci-ops { display: flex; align-items: center; justify-content: space-between; }
.run-link { font-size: var(--fs-aux); }

@media (max-width: 1100px) {
  .row { flex-direction: column; }
  .col.queue { width: 100%; max-height: none; overflow-y: visible; }
}
</style>
