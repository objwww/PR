<template>
  <div class="approval-ops">
    <header class="page-head">
      <div>
        <h1>审批处置</h1>
        <p class="sub">危险操作的人工闸门：待审批裁决 · 升级人工终裁 · 隔离告警放行。所有决策写入审计。</p>
      </div>
      <el-button @click="refreshAll" :loading="loading">刷新</el-button>
    </header>

    <el-tabs v-model="tab" class="ops-tabs">
      <!-- 待审批 -->
      <el-tab-pane :label="`待审批（${pending.length}）`" name="pending">
        <el-alert v-if="pending.length" type="warning" :closable="false" class="hint"
          title="待审批项有有效期倒计时，过期自动作废；批准前请核对参数与目标资源。" />
        <el-empty v-if="!pending.length && !loading" description="当前没有待审批的操作" />
        <el-table v-if="pending.length" :data="pending" stripe>
          <el-table-column label="操作" min-width="140">
            <template #default="{ row }">{{ row.action_id }}</template>
          </el-table-column>
          <el-table-column label="风险级" width="110">
            <template #default="{ row }">
              <el-tag :type="riskTag(row.risk)" effect="plain">{{ riskLabel(row.risk) }}</el-tag>
            </template>
          </el-table-column>
          <el-table-column label="所需票数" width="120">
            <template #default="{ row }">{{ row.approved_count }} / {{ row.required_approvers }}</template>
          </el-table-column>
          <el-table-column label="申请时间" width="170">
            <template #default="{ row }">{{ fmt(row.requested_at) }}</template>
          </el-table-column>
          <el-table-column label="有效期剩余" width="120">
            <template #default="{ row }">
              <span :class="{ danger: remainSeconds(row.expires_at) < 60 }">{{ remainText(row.expires_at) }}</span>
            </template>
          </el-table-column>
          <el-table-column label="操作" width="200" fixed="right">
            <template #default="{ row }">
              <el-button size="small" type="primary" :disabled="busy" @click="decide(row, true)">批准</el-button>
              <el-button size="small" type="danger" plain :disabled="busy" @click="decide(row, false)">拒绝</el-button>
            </template>
          </el-table-column>
        </el-table>
      </el-tab-pane>

      <!-- 升级人工 -->
      <el-tab-pane :label="`升级人工（${escalations.length}）`" name="escalations">
        <el-alert v-if="escalations.length" type="error" :closable="false" class="hint"
          title="以下操作执行结果未知，系统不猜测终态；请人工核实实际效果后裁决。锁在裁决前保持持有。" />
        <el-empty v-if="!escalations.length && !loading" description="当前没有等待人工裁决的操作" />
        <el-table v-if="escalations.length" :data="escalations" stripe>
          <el-table-column label="操作" min-width="140">
            <template #default="{ row }">{{ row.action_id }}</template>
          </el-table-column>
          <el-table-column label="目标资源" min-width="200">
            <template #default="{ row }">{{ row.resource_uid }}</template>
          </el-table-column>
          <el-table-column label="执行方式" width="100">
            <template #default="{ row }">{{ row.dry_run ? '演练' : '真实执行' }}</template>
          </el-table-column>
          <el-table-column label="锁状态" width="110">
            <template #default="{ row }">
              <el-tag v-if="row.lock_state" type="warning" effect="plain">{{ lockLabel(row.lock_state) }}</el-tag>
              <span v-else>—</span>
            </template>
          </el-table-column>
          <el-table-column label="准备时间" width="170">
            <template #default="{ row }">{{ fmt(row.prepared_at) }}</template>
          </el-table-column>
          <el-table-column label="裁决" width="220" fixed="right">
            <template #default="{ row }">
              <el-button size="small" type="primary" :disabled="busy" @click="rule(row, 'COMPLETED')">确认生效</el-button>
              <el-button size="small" type="danger" :disabled="busy" @click="rule(row, 'FAILED_CONFIRMED')">确认失败</el-button>
            </template>
          </el-table-column>
        </el-table>
      </el-tab-pane>

      <!-- 隔离区 -->
      <el-tab-pane :label="`隔离区（${quarantined.length}）`" name="quarantined">
        <el-alert v-if="quarantined.length" type="warning" :closable="false" class="hint"
          title="以下告警命中注入特征被隔离，未进入调查流程；放行前请人工确认内容安全。" />
        <el-empty v-if="!quarantined.length && !loading" description="隔离区为空——所有告警均通过安全扫描" />
        <el-table v-if="quarantined.length" :data="quarantined" stripe>
          <el-table-column label="告警名 / 服务" min-width="180">
            <template #default="{ row }">
              <div class="cell-name">{{ row.alertname }}</div>
              <div class="cell-sub">{{ row.service }}（{{ row.severity }}）</div>
            </template>
          </el-table-column>
          <el-table-column label="告警数" width="90">
            <template #default="{ row }">{{ row.alert_count }}</template>
          </el-table-column>
          <el-table-column label="命中特征" min-width="200">
            <template #default="{ row }">{{ hitPatterns(row.last_error) }}</template>
          </el-table-column>
          <el-table-column label="隔离时间" width="170">
            <template #default="{ row }">{{ fmt(row.received_at) }}</template>
          </el-table-column>
          <el-table-column label="操作" width="120" fixed="right">
            <template #default="{ row }">
              <el-tooltip :content="'分组键：' + (row.group_key ?? '—')" placement="top">
                <el-button size="small" :disabled="busy" @click="release(row)">放行</el-button>
              </el-tooltip>
            </template>
          </el-table-column>
        </el-table>
        <p v-if="quarantined.length" class="groupkey-note">
          注：「告警组」列的原始分组键（格式：告警名:服务:严重度::指纹）是告警平台聚合同类告警的内部审计标识，页面已解析为上方可读列；指纹为每批告警的唯一编号，悬停"放行"按钮可查看完整键。
        </p>
      </el-tab-pane>
    </el-tabs>
  </div>
</template>

<script setup>
import { onMounted, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { api, http } from '../api/client'
import { useSessionStore } from '../stores/session'

// 审批处置页（前端产品化波次1）：三张列表 + 人工决策动作。
// 全部数据来自真实账本（approval_request / rca_operation / alert_inbox），
// 动作复用既有审计端点（decide / operate / release）。

const session = useSessionStore()
const tab = ref('pending')
const loading = ref(false)
const busy = ref(false)
const pending = ref([])
const escalations = ref([])
const quarantined = ref([])

function actorId() {
  const u = session.user || ''
  return u ? `human:${u}` : 'human:oncall'
}

async function refreshAll() {
  loading.value = true
  try {
    const [p, e, q] = await Promise.all([
      api('/mutation/pending-approvals').catch(() => null),
      api('/mutation/escalations').catch(() => null),
      api('/inbox-admin/quarantined').catch(() => null),
    ])
    pending.value = p?.items ?? []
    escalations.value = e?.items ?? []
    quarantined.value = q?.items ?? []
  } finally {
    loading.value = false
  }
}

async function decide(row, approved) {
  busy.value = true
  try {
    const res = await http.post('/mutation/decide', {
      requestId: row.request_id,
      approverId: actorId(),
      approverRole: 'OPERATOR',
      approved,
    })
    if (res.data?.status === 'OK') {
      ElMessage.success(approved ? '已批准，授权链继续' : '已拒绝')
    } else {
      ElMessage.warning(`被拒绝：${res.data?.reason ?? '未知原因'}`)
    }
    await refreshAll()
  } catch (err) {
    ElMessage.error('决策失败：' + (err?.message ?? '网络错误'))
  } finally {
    busy.value = false
  }
}

async function rule(row, ruling) {
  busy.value = true
  try {
    const res = await http.post('/mutation/operate', {
      operationId: row.operation_id,
      ruling,
      operatorId: actorId(),
    })
    if (res.data?.status === 'OK') {
      ElMessage.success(ruling === 'COMPLETED' ? '已裁决：确认生效，锁已释放' : '已裁决：确认失败，锁已释放')
    } else {
      ElMessage.warning(`被拒绝：${res.data?.reason ?? '未知原因'}`)
    }
    await refreshAll()
  } catch (err) {
    ElMessage.error('裁决失败：' + (err?.message ?? '网络错误'))
  } finally {
    busy.value = false
  }
}

async function release(row) {
  busy.value = true
  try {
    const res = await http.post(`/inbox-admin/alerts/${row.id}/release`, {
      releasedBy: actorId(),
    })
    if (res.data?.released) {
      ElMessage.success('已放行，告警将重新进入处理流程')
    } else {
      ElMessage.warning('放行未生效（状态已变化）')
    }
    await refreshAll()
  } catch (err) {
    ElMessage.error('放行失败：' + (err?.message ?? '网络错误'))
  } finally {
    busy.value = false
  }
}

function riskLabel(risk) {
  return ({ R1: '常规', R2: '低危写操作', R3: '高危双人' })[risk] ?? (risk ?? '未知')
}
function riskTag(risk) {
  if (risk === 'R3') return 'danger'
  if (risk === 'R2') return 'warning'
  return 'info'
}
function lockLabel(state) {
  return ({ HELD: '锁持有中', ORPHANED: '锁已孤儿化' })[state] ?? state
}
function hitPatterns(lastError) {
  if (!lastError) return '—'
  try {
    const parsed = typeof lastError === 'string' ? JSON.parse(lastError) : lastError
    if (Array.isArray(parsed?.patterns)) return parsed.patterns.join('、')
    if (parsed?.reason) return parsed.reason
  } catch { /* 非审计 JSON，原样兜底 */ }
  return String(lastError).slice(0, 80)
}
function fmt(iso) {
  if (!iso) return '—'
  return new Date(iso).toLocaleString('zh-CN', { hour12: false })
}
function remainSeconds(iso) {
  return Math.max(0, Math.floor((new Date(iso).getTime() - Date.now()) / 1000))
}
function remainText(iso) {
  const s = remainSeconds(iso)
  if (s <= 0) return '已过期'
  if (s < 60) return `${s} 秒`
  return `${Math.floor(s / 60)} 分钟`
}

onMounted(refreshAll)
</script>

<style scoped>
.approval-ops { max-width: 1280px; }
.page-head { display: flex; justify-content: space-between; align-items: flex-start; margin-bottom: 16px; }
.page-head h1 { font-size: 24px; margin: 0 0 6px; }
.page-head .sub { color: #5f6b7a; font-size: 13px; margin: 0; }
.ops-tabs { background: #fff; border: 1px solid #d8dee4; border-radius: 8px; padding: 8px 16px 16px; }
.hint { margin: 8px 0 12px; }
.danger { color: #d93026; font-weight: 600; }
.cell-name { font-weight: 600; line-height: 1.4; }
.cell-sub { font-size: 12px; color: #5f6b7a; }
.groupkey-note { font-size: 12px; color: #5f6b7a; margin: 10px 2px 0; line-height: 1.7; }
</style>
