<template>
  <div class="users-page">
    <PageHeader title="账号管理" subtitle="平台账号与角色清单——真源 /auth/users（OPERATOR 权限面）；开通/停用走后端命令面" />
    <div class="card zone">
      <template v-if="state === 'ok'">
        <el-table :data="items" size="small" border>
          <el-table-column prop="username" label="账号" min-width="160" />
          <el-table-column label="角色" width="140">
            <template #default="{ row }">
              <el-tag size="small" effect="plain" :type="row.role === 'OPERATOR' ? 'warning' : 'info'">{{ row.role ?? row.role_name ?? '—' }}</el-tag>
            </template>
          </el-table-column>
          <el-table-column label="状态" width="120">
            <template #default="{ row }">
              <el-tag size="small" :type="(row.active ?? true) ? 'success' : 'danger'" effect="plain">{{ (row.active ?? true) ? '启用' : '停用' }}</el-tag>
            </template>
          </el-table-column>
          <el-table-column label="创建/更新时间" width="200">
            <template #default="{ row }">{{ row.created_at ? fmtTime(row.created_at) : '—' }}</template>
          </el-table-column>
          <template #empty><EmptyState kind="empty" description="暂无账号记录" /></template>
        </el-table>
      </template>
      <EmptyState v-else-if="state === 'error'" kind="error" @retry="load" />
      <div v-else v-loading="true" class="loading-box" />
    </div>
  </div>
</template>

<script setup>
// 账号管理（业界标准管理面）：真源 /auth/users 只读清单；写面（开通/停用）走后端命令接口
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
    const res = await api('/auth/users')
    items.value = res?.items ?? res?.users ?? (Array.isArray(res) ? res : [])
    state.value = 'ok'
  } catch { state.value = 'error' }
}
onMounted(load)
</script>

<style scoped>
.users-page { display: flex; flex-direction: column; gap: var(--section-gap); }
.zone { padding: 16px var(--card-pad); }
.loading-box { height: 240px; }
</style>
