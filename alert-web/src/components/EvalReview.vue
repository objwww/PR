<template>
  <div class="eval-review">
    <div class="crumb"><b>首页</b><span class="sep">/</span>评测<span class="sep">/</span>人工复核<span class="sep">/</span>队列 {{ items.length }}</div>

    <div class="card policy">
      复核追加 <code>review_event</code>（append-only），不改写 <code>eval_case_result</code>；词典修订另发新版本并重跑 Scorer Replay。
    </div>

    <table class="grid card">
      <thead>
        <tr><th>result</th><th>批次</th><th>场景族 / 轮次</th><th>机器 verdict</th><th>队列原因</th><th>入队时间</th><th>操作</th></tr>
      </thead>
      <tbody>
        <tr v-for="it in items" :key="it.resultId">
          <td><b>{{ it.resultId }}</b></td>
          <td>{{ it.runId }}</td>
          <td>{{ it.family }} / round {{ it.round }}</td>
          <td><span class="tag" :class="verdictClass(it.verdict)">{{ it.verdict }}</span></td>
          <td>{{ it.reason }}</td>
          <td>{{ it.queuedAt }}</td>
          <td><button class="btn" @click="opened = it.resultId">打开</button></td>
        </tr>
      </tbody>
    </table>
    <div v-if="opened" class="card opened-note">
      {{ opened }}：请切换到「案例诊断」tab 查看逐项评分明细并提交复核事件（跨 tab 联动待后端 result 查询 API）。
    </div>
  </div>
</template>

<script setup>
import { onMounted, ref } from 'vue'
import { api } from '../api/client.js'
import { fetchReviewQueue } from '../mocks/eval.js'

const items = ref([])
const opened = ref(null)

function verdictClass(v) {
  return { PASS: 't-green', FAIL: 't-red', ERROR: 't-red', FLIP: 't-gray', REVIEW_PENDING: 't-orange' }[v] || 't-gray'
}

onMounted(async () => {
  items.value = (await api('/eval/review-queue', { mock: fetchReviewQueue })).items
})
</script>

<style scoped>
.crumb { font-size: 12px; color: var(--ink-2); margin-bottom: 10px; }
.crumb .sep { margin: 0 6px; color: var(--line-strong); }

.policy { padding: 10px 14px; font-size: 12.5px; color: var(--ink-2); margin-bottom: 10px; }

.grid { width: 100%; border-collapse: collapse; font-size: 12.5px; overflow: hidden; }
.grid th, .grid td { border-bottom: 1px solid var(--line); padding: 7px 12px; text-align: left; }
.grid th { background: #f6f8fb; color: var(--ink-2); font-size: 12px; }
.grid tr:last-child td { border-bottom: none; }

.opened-note { margin-top: 8px; padding: 8px 12px; font-size: 12px; color: var(--ink-2); }
</style>
