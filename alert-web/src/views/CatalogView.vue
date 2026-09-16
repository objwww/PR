<template>
  <div class="catalog-page">
    <PageHeader title="服务目录" subtitle="以服务为单元的告警健康聚合（Backstage Catalog 同律）；负责人为人工登记的配置真数据，点击行内「负责人」登记或解绑" />
    <div class="card zone">
      <template v-if="state === 'ok'">
        <el-table :data="items" size="small" border>
          <el-table-column prop="service" label="服务" min-width="140" />
          <el-table-column label="在警" width="80">
            <template #default="{ row }">
              <span :style="{ color: row.firing > 0 ? 'var(--sev-p0)' : 'var(--head)', fontWeight: 600 }">{{ row.firing }}</span>
            </template>
          </el-table-column>
          <el-table-column prop="resolved" label="已解决" width="90" />
          <el-table-column prop="total" label="事故总数" width="100" />
          <el-table-column prop="receivedTotal" label="累计接收" width="100" />
          <el-table-column label="告警名" min-width="280">
            <template #default="{ row }">{{ row.alerts ?? '—' }}</template>
          </el-table-column>
          <el-table-column label="最近活动" width="180">
            <template #default="{ row }">{{ row.lastEventAt ? fmtTime(row.lastEventAt) : '—' }}</template>
          </el-table-column>
          <el-table-column label="近 30 天事件" width="120" align="right">
            <template #default="{ row }">{{ row.events30d ?? '—' }}</template>
          </el-table-column>
          <el-table-column label="MTTR（30 天）" width="130">
            <template #default="{ row }">{{ mttrZh(row.mttr30dSec) }}</template>
          </el-table-column>
          <el-table-column label="负责人" width="120">
            <template #default="{ row }">
              <span v-if="row.owner">{{ row.owner }}</span>
              <span v-else class="dim">未登记</span>
            </template>
          </el-table-column>
          <el-table-column label="操作" width="90" align="right">
            <template #default="{ row }">
              <el-button size="small" text type="primary" @click="openOwner(row)">负责人</el-button>
            </template>
          </el-table-column>
          <template #empty><EmptyState kind="empty" description="尚无服务数据" /></template>
        </el-table>
      </template>
      <EmptyState v-else-if="state === 'error'" kind="error" @retry="load" />
      <div v-else v-loading="true" class="loading-box" />
    </div>

    <!-- 负责人登记弹窗（service_owner 人工配置真数据） -->
    <el-dialog v-model="ownerVisible" title="登记服务负责人" width="480px">
      <div class="owner-form">
        <div class="of-row"><span class="of-k">服务</span><code>{{ ownerTarget?.service }}</code></div>
        <div class="of-row">
          <span class="of-k">负责人</span>
          <el-input v-model="ownerInput" size="small" placeholder="姓名或值班角色（如：支付组-oncall）" maxlength="60" />
        </div>
        <div class="of-row">
          <span class="of-k">备注</span>
          <el-input v-model="ownerNote" size="small" placeholder="可选（如：升级路径、联系渠道）" maxlength="200" />
        </div>
        <div class="dim">登记后随目录展示；清空负责人并保存即解除绑定。</div>
      </div>
      <template #footer>
        <el-button size="small" @click="ownerVisible = false">取消</el-button>
        <el-button size="small" type="primary" :loading="ownerSaving" @click="saveOwner">保存</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
// 服务目录（业界路线v2第4项 + 3.13 补强）：incident 按服务聚合真数据 +
// 近 30 天事件/MTTR（incident 时长聚合）+ 负责人（service_owner 人工登记，V135）
import { onMounted, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { api } from '../api/client'
import PageHeader from '../components/common/PageHeader.vue'
import EmptyState from '../components/common/EmptyState.vue'
import { fmtTime } from '../utils/format'

const items = ref([])
const state = ref('loading')
const ownerVisible = ref(false)
const ownerTarget = ref(null)
const ownerInput = ref('')
const ownerNote = ref('')
const ownerSaving = ref(false)

function openOwner(row) {
  ownerTarget.value = row
  ownerInput.value = row.owner ?? ''
  ownerNote.value = ''
  ownerVisible.value = true
}
async function saveOwner() {
  ownerSaving.value = true
  try {
    const res = await api('/v1/catalog/owner', {
      method: 'POST',
      body: { service: ownerTarget.value?.service, owner: ownerInput.value?.trim() ?? '', note: ownerNote.value?.trim() ?? '' },
    })
    if (res?.status === 'OK') {
      ownerTarget.value.owner = res.owner
      ownerVisible.value = false
      ElMessage.success(res.owner ? '负责人已登记' : '负责人已解除绑定')
    } else {
      ElMessage.warning(`被拒绝：${res?.reason ?? '未知原因'}`)
    }
  } catch { ElMessage.error('保存失败，请重试') } finally { ownerSaving.value = false }
}
function mttrZh(sec) {
  if (sec == null) return '—（30 天内无已解决）'
  const h = sec / 3600
  if (h < 1) return `${Math.round(sec / 60)} 分钟`
  if (h < 48) return `${h.toFixed(1)} 小时`
  return `${(h / 24).toFixed(1)} 天`
}
async function load() {
  state.value = 'loading'
  try {
    items.value = (await api('/v1/catalog'))?.items ?? []
    state.value = 'ok'
  } catch { state.value = 'error' }
}
onMounted(load)
</script>

<style scoped>
.catalog-page { display: flex; flex-direction: column; gap: var(--section-gap); }
.zone { padding: 16px var(--card-pad); }
.loading-box { height: 240px; }
</style>
`n.dim { color: var(--ink-2); font-size: var(--fs-aux); }`n.owner-form { display: flex; flex-direction: column; gap: 10px; }`n.of-row { display: flex; align-items: center; gap: 10px; }`n.of-row .of-k { flex: 0 0 48px; color: var(--ink-2); font-size: var(--fs-aux); }
