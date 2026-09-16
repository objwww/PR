<template>
  <div class="catalog-page">
    <PageHeader title="服务目录" subtitle="以服务为单元的告警健康聚合（Backstage Catalog 同律）；负责人字段暂无配置源，如实留空" />
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
          <el-table-column label="负责人" width="100">
            <template #default>{{ '—' }}</template>
          </el-table-column>
          <template #empty><EmptyState kind="empty" description="尚无服务数据" /></template>
        </el-table>
      </template>
      <EmptyState v-else-if="state === 'error'" kind="error" @retry="load" />
      <div v-else v-loading="true" class="loading-box" />
    </div>
  </div>
</template>

<script setup>
// 服务目录（业界路线v2第4项）：incident 表按服务聚合真数据；owner 无配置源如实空
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
