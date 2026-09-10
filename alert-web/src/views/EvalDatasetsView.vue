<template>
  <div class="datasets-page">
    <div class="table-zone card">
      <template v-if="listState === 'ok'">
        <el-table :data="items" v-loading="loading">
          <el-table-column prop="version" label="版本" min-width="150" show-overflow-tooltip />
          <el-table-column prop="source" label="来源" min-width="140" show-overflow-tooltip>
            <template #default="{ row }">{{ row.source ?? '—' }}</template>
          </el-table-column>
          <el-table-column label="案例数" width="90" align="right">
            <template #default="{ row }">{{ row.caseCount ?? 0 }}</template>
          </el-table-column>
          <el-table-column label="故障族" min-width="220">
            <template #default="{ row }">
              <template v-if="row.families?.length">
                <el-tag
                  v-for="f in row.families" :key="f"
                  size="small" effect="plain" class="fam-chip" disable-transitions
                >{{ f }}</el-tag>
              </template>
              <span v-else>—</span>
            </template>
          </el-table-column>
          <el-table-column label="创建时间" width="160">
            <template #default="{ row }">{{ fmtTime(row.createdAt) }}</template>
          </el-table-column>
          <template #empty>
            <EmptyState kind="empty" description="暂无评测数据集" />
          </template>
        </el-table>
      </template>
      <EmptyState v-else-if="listState === 'forbidden'" kind="forbidden" />
      <EmptyState v-else-if="listState === 'error'" kind="error" @retry="loadList" />
      <div v-else v-loading="true" class="loading-box" />
    </div>
  </div>
</template>

<script setup>
// UI-6 数据集页（/eval/datasets）：GET /eval/datasets 版本表
import { onMounted, ref } from 'vue'
import { api } from '../api/client'
import EmptyState from '../components/common/EmptyState.vue'
import { fmtTime } from '../utils/format'

const items = ref([])
const listState = ref('loading') // loading | ok | error | forbidden
const loading = ref(false)

async function loadList() {
  listState.value = 'loading'
  loading.value = true
  try {
    const d = await api('/eval/datasets')
    items.value = d.items ?? []
    listState.value = 'ok'
  } catch (e) {
    listState.value = e?.response?.status === 403 ? 'forbidden' : 'error'
  } finally {
    loading.value = false
  }
}

onMounted(loadList)
</script>

<style scoped>
.datasets-page { display: flex; flex-direction: column; gap: var(--section-gap); }
.table-zone { padding: 8px var(--card-pad) 12px; }
.fam-chip { margin: 2px 4px 2px 0; }
.loading-box { height: 320px; }
</style>
