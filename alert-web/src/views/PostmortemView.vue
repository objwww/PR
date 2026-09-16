<template>
  <div class="pm-page">
    <PageHeader title="复盘草稿" subtitle="已解决事故的复盘材料自动汇编——时间线/断言/调查/费用全真源，根因与改进项由人补写（Rootly Postmortem 同律）" />
    <div class="row">
      <div class="card zone list">
        <template v-if="state === 'ok'">
          <div v-for="r in items" :key="r.incidentId" class="pm-item" :class="{ cur: cur === r.incidentId }" @click="open(r)">
            <b>{{ r.alertname ?? '—' }}</b>
            <div class="cell-sub">{{ r.service || '—' }} ｜ 接收 {{ r.receivedCount }} 次 ｜ 解决于 {{ r.resolvedAt ? fmtTime(r.resolvedAt) : '—' }}</div>
          </div>
          <EmptyState v-if="!items.length" kind="empty" description="暂无已解决事故" />
        </template>
        <EmptyState v-else-if="state === 'error'" kind="error" @retry="load" />
        <div v-else v-loading="true" class="loading-box" />
      </div>
      <div class="card zone detail">
        <template v-if="d">
          <h3>影响</h3>
          <p class="cell-sub">{{ d.impact.alertname }} ｜ 服务 {{ d.impact.service || '—' }} ｜ 接收 {{ d.impact.receivedCount }} 次</p>
          <h3>时间线</h3>
          <div v-for="(t, i) in d.timeline" :key="i" class="cell-sub">
            {{ fmtTime(t.startsAt) }} · {{ t.status === 'firing' ? '触发' : '恢复' }}
          </div>
          <h3>调查断言</h3>
          <div v-for="(c, i) in d.claims" :key="i" class="cell-sub">[{{ c.status }}] {{ c.reason }}</div>
          <p v-if="!d.claims.length" class="cell-sub">无结构化断言</p>
          <h3>调查与费用</h3>
          <p class="cell-sub">{{ d.investigation.summary }} ｜ 模型费用 {{ d.investigation.cost || '—' }}</p>
          <h3>根因与改进项（人工补写）</h3>
          <p class="cell-sub">本区为人工区：复盘结论请记录在处置工单/文档中，系统不代拟根因。</p>
          <h3>ITSM 工单草稿</h3>
          <el-button size="small" type="primary" plain :loading="ticket.loading" @click="createTicket">生成工单草稿</el-button>
          <div v-if="ticket.items.length" style="margin-top: 8px">
            <div v-for="t in ticket.items" :key="t.id" class="cell-sub">
              [{{ t.priority }}] {{ t.title }} —— {{ t.state }}（{{ fmtTime(t.createdAt) }}）
            </div>
          </div>
        </template>
        <EmptyState v-else kind="empty" description="选择左侧事故查看复盘材料" />
      </div>
    </div>
  </div>
</template>

<script setup>
// 复盘草稿（业界路线v2第5项）：已解决事故材料自动汇编，根因人工补写，系统不代拟
import { onMounted, reactive, ref } from 'vue'
import { api } from '../api/client'
import PageHeader from '../components/common/PageHeader.vue'
import EmptyState from '../components/common/EmptyState.vue'
import { fmtTime } from '../utils/format'

const items = ref([])
const d = ref(null)
const cur = ref('')
const state = ref('loading')
async function load() {
  state.value = 'loading'
  try {
    items.value = (await api('/v1/postmortems'))?.items ?? []
    state.value = 'ok'
  } catch { state.value = 'error' }
}
async function open(r) {
  cur.value = r.incidentId
  try { d.value = await api(`/v1/postmortems/${r.incidentId}`) } catch { d.value = null }
}

// ITSM 工单草稿（业界路线v2第6项第一阶段）：真源汇编落库 DRAFT，导出粘贴进工单系统；真推送待外部配置
const ticket = reactive({ items: [], loading: false })
async function createTicket() {
  if (!cur.value) return
  ticket.loading = true
  try {
    const res = await api(`/v1/itsm/incidents/${cur.value}/draft`, { method: 'POST' })
    if (res?.status === 'OK') {
      ElMessage.success('工单草稿已生成（DRAFT，可导出）')
      await loadTickets()
    } else {
      ElMessage.warning(`被拒绝：${res?.reason ?? '未知原因'}`)
    }
  } catch { ElMessage.error('生成失败，请重试') } finally { ticket.loading = false }
}
async function loadTickets() {
  try { ticket.items = (await api('/v1/itsm/tickets'))?.items ?? [] } catch { ticket.items = [] }
}
onMounted(() => { load(); loadTickets() })
</script>

<style scoped>
.pm-page { display: flex; flex-direction: column; gap: var(--section-gap); }
.row { display: flex; gap: var(--section-gap); align-items: flex-start; }
.list { flex: none; width: 360px; max-height: calc(100vh - 200px); overflow-y: auto; }
.detail { flex: 1; min-width: 0; }
.zone { padding: 16px var(--card-pad); }
.pm-item { padding: 10px; border: 1px solid var(--line, #ebeef5); border-radius: 6px; margin-bottom: 8px; cursor: pointer; }
.pm-item.cur { box-shadow: 0 0 0 2px var(--brand, #409EFF); }
.loading-box { height: 240px; }
</style>
