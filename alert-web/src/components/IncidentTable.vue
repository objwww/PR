<template>
  <!-- incident 列表通用表格（UI-1：告警中心 / 历史档案同列口径）：
       行首 4px severity 色条 + 严重度徽章、告警/服务、状态、持续时间、接收/事件、调查状态、操作槽 -->
  <el-table
    :data="rows"
    v-loading="loading"
    :row-class-name="({ row }) => mapSeverity(row.severity).rowClass"
    class="incident-table"
    @row-click="row => $emit('row-click', row)"
  >
    <el-table-column label="严重度" width="96">
      <template #default="{ row }">
        <StatusBadge v-if="mapSeverity(row.severity).key" :severity="mapSeverity(row.severity).key" />
        <el-tag v-else type="info" disable-transitions>未分级</el-tag>
      </template>
    </el-table-column>
    <el-table-column label="告警 / 服务" min-width="220">
      <template #default="{ row }">
        <div class="cell-name">{{ row.alertname ?? row.incidentKey ?? row.incidentId }}</div>
        <div class="cell-sub">{{ row.service ?? '—' }}</div>
      </template>
    </el-table-column>
    <el-table-column label="状态" width="96">
      <template #default="{ row }">
        <StatusBadge :status="row.status" />
      </template>
    </el-table-column>
    <el-table-column label="持续时间" width="120">
      <template #default="{ row }">
        <span :title="fmtTime(row.lastEventAt ?? row.episodeStartedAt)">
          {{ fmtAgo(row.lastEventAt ?? row.episodeStartedAt) }}
        </span>
      </template>
    </el-table-column>
    <el-table-column label="接收 / 事件" width="100" align="right">
      <template #default="{ row }">{{ row.receivedCount ?? 0 }} / {{ row.distinctEventCount ?? 0 }}</template>
    </el-table-column>
    <el-table-column label="调查状态" min-width="150">
      <template #default="{ row }">
        <template v-if="row.currentRcaRunId">
          <StatusBadge v-if="row.runState" :status="row.runState" />
          <el-tag v-else type="primary" disable-transitions>调查中</el-tag>
          <router-link class="run-link" :to="`/runs/${row.currentRcaRunId}`" @click.stop>查看调查</router-link>
        </template>
        <span v-else class="cell-sub">未发起</span>
      </template>
    </el-table-column>
    <el-table-column label="操作" width="90" fixed="right">
      <template #default="{ row }">
        <slot name="actions" :row="row" />
      </template>
    </el-table-column>
    <template #empty>
      <slot name="empty" />
    </template>
  </el-table>
</template>

<script setup>
import StatusBadge from './common/StatusBadge.vue'
import { mapSeverity } from '../utils/severity'
import { fmtAgo, fmtTime } from '../utils/format'

defineProps({
  rows: { type: Array, default: () => [] },
  loading: { type: Boolean, default: false },
})
defineEmits(['row-click'])
</script>

<style scoped>
.cell-name { font-weight: 600; color: var(--head); line-height: 1.4; }
.cell-sub { font-size: var(--fs-aux); color: var(--ink-2); }
.run-link { margin-left: 8px; font-size: var(--fs-aux); }

/* 行首 4px severity 色条（不整行着色） */
.incident-table :deep(.sev-p0 td:first-child) { box-shadow: inset 4px 0 0 var(--sev-p0); }
.incident-table :deep(.sev-p1 td:first-child) { box-shadow: inset 4px 0 0 var(--sev-p1); }
.incident-table :deep(.sev-p2 td:first-child) { box-shadow: inset 4px 0 0 var(--sev-p2); }
.incident-table :deep(.sev-p3 td:first-child) { box-shadow: inset 4px 0 0 var(--sev-p3); }
.incident-table :deep(.sev-none td:first-child) { box-shadow: inset 4px 0 0 var(--sev-info); }
.incident-table :deep(.el-table__row) { cursor: pointer; }
</style>
