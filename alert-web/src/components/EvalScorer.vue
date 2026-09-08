<template>
  <div class="eval-scorer" v-if="data.suites.length">
    <div class="crumb"><b>首页</b><span class="sep">/</span>评测<span class="sep">/</span>评分器校准</div>

    <div class="card principle">
      <b>判分原则</b>：{{ data.principle }}。
    </div>

    <div class="cols">
      <div class="col" style="flex:3">
        <div class="lbl">评分套件（版本化）</div>
        <table class="grid card">
          <thead>
            <tr><th>套件</th><th>版本</th><th>状态</th><th>范围</th></tr>
          </thead>
          <tbody>
            <tr v-for="s in data.suites" :key="s.name">
              <td><b>{{ s.name }}</b></td>
              <td><code>{{ s.version }}</code></td>
              <td><span class="tag t-green">{{ s.status }}</span></td>
              <td>{{ s.scope }}</td>
            </tr>
          </tbody>
        </table>

        <div class="lbl" style="margin-top:12px">修订流程</div>
        <div class="card box">
          {{ data.calibration.reviseFlow }}。
          <button class="btn primary" @click="replayed = true">发起 Scorer Replay 对比</button>
          <span v-if="replayed" class="tag t-blue">已入队（mock）：同数据集冻结观测，新旧版本各跑一次只重算评分</span>
        </div>
      </div>

      <div class="col" style="flex:2">
        <div class="lbl">抽样校准</div>
        <div class="card box">
          机器判分 vs 人工复核一致率 <b>{{ (data.calibration.agreement * 100).toFixed(0) }}%</b>（抽样 {{ data.calibration.sampleSize }} 例）<br>
          <small>分歧案例自动进入人工复核队列，复核写 append-only review_event，不覆盖原结果。</small>
        </div>
        <div class="card box">
          未知枚举 <b>{{ data.calibration.unknownEnum }}</b> 例<br>
          <small>{{ data.calibration.unknownEnumPolicy }}。</small>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup>
import { onMounted, reactive, ref } from 'vue'
import { api } from '../api/client.js'
import { fetchScorerSuites } from '../mocks/eval.js'

const data = reactive({ principle: '', suites: [], calibration: {} })
const replayed = ref(false)

onMounted(async () => {
  Object.assign(data, await api('/eval/scorer-suites', { mock: fetchScorerSuites }))
})
</script>

<style scoped>
.crumb { font-size: 12px; color: var(--ink-2); margin-bottom: 10px; }
.crumb .sep { margin: 0 6px; color: var(--line-strong); }

.principle { padding: 10px 14px; font-size: 12.5px; margin-bottom: 12px; }

.cols { display: flex; gap: 10px; align-items: flex-start; }
.col { min-width: 0; }
.lbl { font-size: 12px; font-weight: 700; color: var(--ink-2); margin-bottom: 6px; }

.grid { width: 100%; border-collapse: collapse; font-size: 12.5px; overflow: hidden; }
.grid th, .grid td { border-bottom: 1px solid var(--line); padding: 7px 12px; text-align: left; }
.grid th { background: #f6f8fb; color: var(--ink-2); font-size: 12px; }
.grid tr:last-child td { border-bottom: none; }

.box { padding: 10px 12px; margin-bottom: 8px; font-size: 12.5px; }
.box .btn { margin-left: 8px; }
.box small { color: var(--ink-2); }
</style>
