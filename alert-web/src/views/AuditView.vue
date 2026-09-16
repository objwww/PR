<template>
  <div class="audit-page">
    <PageHeader title="审计与变更历史" subtitle="谁 / 何时 / 做了什么——认证与配置变更统一流水（PagerDuty Audit Logs 同律）">
      <template #actions>
        <el-button :loading="loading" @click="load">刷新</el-button>
      </template>
    </PageHeader>
    <div class="card zone">
      <template v-if="state === 'ok'">
        <el-table :data="items" size="small" border row-key="rowKey">
          <el-table-column label="时间" width="180">
            <template #default="{ row }">{{ fmtTime(row.occurredAt) }}</template>
          </el-table-column>
          <el-table-column label="来源" width="110">
            <template #default="{ row }">
              <el-tag size="small" :type="row.source === '认证' ? 'info' : 'warning'" effect="plain">{{ row.source }}</el-tag>
            </template>
          </el-table-column>
          <el-table-column prop="actor" label="操作者" width="200" />
          <el-table-column prop="action" label="动作" width="200" />
          <el-table-column prop="detail" label="详情" min-width="320" />
          <el-table-column prop="extra" label="附加" width="140" />
          <template #empty><EmptyState kind="empty" description="暂无审计记录" /></template>
        </el-table>
      </template>
      <EmptyState v-else-if="state === 'error'" kind="error" @retry="load" />
      <div v-else v-loading="true" class="loading-box" />
    </div>
  </div>
</template>

<script setup>
// 审计与变更历史（业界路线 v2 第1项）：auth_event + change_event 统一只读流，无模拟数据
import { onMounted, ref } from 'vue'
import { api } from '../api/client'
import PageHeader from '../components/common/PageHeader.vue'
import EmptyState from '../components/common/EmptyState.vue'
import { fmtTime } from '../utils/format'

const items = ref([])
const state = ref('loading')
async function load() {
  state.value = 'loading'
  try {
    items.value = (await api('/v1/audit'))?.items ?? []
    state.value = 'ok'
  } catch { state.value = 'error' }
}
onMounted(load)
</script>

<style scoped>
.audit-page { display: flex; flex-direction: column; gap: var(--section-gap); }
.zone { padding: 16px var(--card-pad); }
.loading-box { height: 240px; }
</style>
