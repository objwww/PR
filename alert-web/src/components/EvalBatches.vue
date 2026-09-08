<template>
  <div class="eval-batches">
    <div class="crumb"><b>首页</b><span class="sep">/</span>评测<span class="sep">/</span>批次运行</div>

    <div class="card toolbar">
      状态 <span class="field">全部 ▾</span>
      场景族 <span class="field">全部 ▾</span>
      verdict <span class="field">FAIL/ERROR ▾</span>
      根因类 <span class="field">全部 ▾</span>
      轮次 <span class="field">全部 ▾</span>
      <span class="tb-actions">
        <button class="btn">暂停</button>
        <button class="btn danger">取消剩余</button>
        <button class="btn">导出失败样本</button>
      </span>
    </div>

    <div
      v-for="r in runs"
      :key="r.id"
      class="card run-row"
      :class="{ clickable: true }"
      @click="$emit('open-analysis', r.id)"
    >
      <div class="run-head">
        <b>{{ r.id }}</b>
        <span class="run-sub">（{{ r.replayZh }} ｜ {{ r.partition }} v{{ r.datasetVersion }}）</span>
        <span v-if="r.baseline" class="tag t-blue">基准实验</span>
        <span class="run-right">
          <span class="tag" :class="statusClass(r.status)">{{ r.status }} {{ r.done }}/{{ r.total }}</span>
          elapsed {{ r.elapsed }} ｜ {{ r.cost }}/{{ r.budget }}
        </span>
      </div>
      <div class="bar"><i :style="{ width: pct(r) + '%' }" :class="{ done: r.status === 'COMPLETED' }"></i></div>
      <div class="run-foot">
        <small v-if="r.status === 'RUNNING'">RUNNING：进度轮询中；失败继续并落档，不中断批次</small>
        <small v-else>COMPLETED：点击行进入六维分析</small>
      </div>
    </div>
  </div>
</template>

<script setup>
import { onMounted, ref } from 'vue'
import { api } from '../api/client.js'
import { fetchEvalRuns } from '../mocks/eval.js'

defineEmits(['open-analysis'])

const runs = ref([])

const pct = r => Math.round((r.done / r.total) * 100)
const statusClass = s => (s === 'COMPLETED' ? 't-green' : s === 'RUNNING' ? 't-blue' : 't-red')

onMounted(async () => {
  runs.value = (await api('/eval/runs', { mock: fetchEvalRuns })).runs
})
</script>

<style scoped>
.crumb { font-size: 12px; color: var(--ink-2); margin-bottom: 10px; }
.crumb .sep { margin: 0 6px; color: var(--line-strong); }

.toolbar {
  display: flex; align-items: center; flex-wrap: wrap; gap: 4px 10px;
  padding: 8px 12px; font-size: 12px; color: var(--ink-2); margin-bottom: 10px;
}
.field {
  display: inline-block; border: 1px solid var(--line-strong); border-radius: 6px;
  padding: 1px 10px; background: #fff; color: var(--ink); cursor: pointer;
}
.tb-actions { margin-left: auto; display: flex; gap: 6px; }

.run-row { padding: 10px 14px; margin-bottom: 8px; }
.run-row.clickable { cursor: pointer; }
.run-row.clickable:hover { border-color: var(--brand); }
.run-head { display: flex; align-items: center; gap: 8px; flex-wrap: wrap; font-size: 13px; }
.run-sub { color: var(--ink-2); font-size: 12px; }
.run-right { margin-left: auto; display: flex; align-items: center; gap: 8px; font-size: 12px; color: var(--ink-2); }

.bar { height: 8px; background: var(--bg); border-radius: 999px; overflow: hidden; margin: 8px 0 6px; }
.bar i { display: block; height: 100%; background: var(--brand); border-radius: 999px; }
.bar i.done { background: var(--ok); }

.run-foot { color: var(--ink-2); }
</style>
