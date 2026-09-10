<template>
  <!-- jsonb 键值表（labels/annotations 结构化展示；长值单行省略 + 悬浮全显） -->
  <el-table v-if="rows.length" :data="rows" size="small" class="kv-table">
    <el-table-column prop="k" label="键" width="220" show-overflow-tooltip />
    <el-table-column prop="v" label="值" show-overflow-tooltip />
  </el-table>
  <div v-else class="kv-empty">暂无数据</div>
</template>

<script setup>
import { computed } from 'vue'

const props = defineProps({
  data: { type: Object, default: null },
})

const rows = computed(() =>
  Object.entries(props.data ?? {}).map(([k, v]) => ({
    k,
    v: v == null ? '' : (typeof v === 'object' ? JSON.stringify(v) : String(v)),
  })))
</script>

<style scoped>
.kv-table { width: 100%; }
.kv-empty { padding: 12px 0; font-size: var(--fs-aux); color: var(--ink-2); }
</style>
