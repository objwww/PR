<template>
  <div class="eval-page">
    <div class="page-head">
      <h2 class="page-title">评测中心</h2>
      <span class="page-sub">从“跑一次看三个数”扩为数据治理、实验运行、六维诊断、人工校准、版本比较和发布审计</span>
    </div>

    <div class="card head-strip">
      <span class="hs-item">{{ summary.env }}</span>
      <span class="hs-sep">｜</span>
      <span class="hs-item">数据更新至 {{ summary.dataUpdatedAt }}</span>
      <span class="hs-sep">｜</span>
      <span class="hs-item hs-search">搜索数据集 / 实验 / 配置 digest
        <input type="text" disabled placeholder="开发中">
      </span>
      <span class="hs-right">{{ summary.evaluator }} ▾</span>
    </div>

    <div class="tabs card">
      <button
        v-for="t in tabs"
        :key="t.key"
        class="tab"
        :class="{ cur: activeTab === t.key, locked: t.locked }"
        @click="activeTab = t.key"
      >
        {{ t.label }}
        <span v-if="t.count != null" class="tab-count">{{ t.count }}</span>
        <span v-if="t.locked" class="tab-lock">🔒</span>
      </button>
    </div>

    <div class="tab-body">
      <EvalDatasets v-if="activeTab === 'datasets'" @launch="activeTab = 'launch'" />
      <EvalLaunch v-else-if="activeTab === 'launch'" />
      <EvalBatches v-else-if="activeTab === 'batches'" @open-analysis="openAnalysis" />
      <EvalAnalysis v-else-if="activeTab === 'analysis'" :run-id="analysisRunId" />
      <EvalCases v-else-if="activeTab === 'cases'" />
      <EvalScorer v-else-if="activeTab === 'scorer'" />
      <EvalReview v-else-if="activeTab === 'review'" />
      <EvalCompare v-else-if="activeTab === 'compare'" />
      <EvalGate v-else-if="activeTab === 'gate'" />
    </div>
  </div>
</template>

<script setup>
// P5 评测中心（/eval）：线框图 v1.6 #p5。子视图 tab 化：
// 数据集治理 / 发起评测(运行配置) / 批次 / 六维分析 / 逐案例诊断 / 评分器校准 / 人工复核 / 实验对比 / 发布门
import { onMounted, reactive, ref } from 'vue'
import { api } from '../api/client.js'
import { fetchEvalSummary } from '../mocks/eval.js'
import EvalDatasets from '../components/EvalDatasets.vue'
import EvalLaunch from '../components/EvalLaunch.vue'
import EvalBatches from '../components/EvalBatches.vue'
import EvalAnalysis from '../components/EvalAnalysis.vue'
import EvalCases from '../components/EvalCases.vue'
import EvalScorer from '../components/EvalScorer.vue'
import EvalReview from '../components/EvalReview.vue'
import EvalCompare from '../components/EvalCompare.vue'
import EvalGate from '../components/EvalGate.vue'

const summary = reactive({ env: '评测环境', dataUpdatedAt: '—', evaluator: 'evaluator', counts: {} })
const activeTab = ref('datasets')
const analysisRunId = ref('eval#0907')

const tabs = reactive([
  { key: 'datasets', label: '数据集' },
  { key: 'launch', label: '发起评测' },
  { key: 'batches', label: '批次', count: null },
  { key: 'analysis', label: '分析' },
  { key: 'cases', label: '案例诊断' },
  { key: 'scorer', label: '评分器校准' },
  { key: 'review', label: '人工复核', count: null },
  { key: 'compare', label: '实验对比' },
  { key: 'gate', label: '发布门', locked: true },
])

function openAnalysis(runId) {
  analysisRunId.value = runId
  activeTab.value = 'analysis'
}

onMounted(async () => {
  const s = await api('/eval/summary', { mock: fetchEvalSummary })
  Object.assign(summary, s)
  tabs.find(t => t.key === 'batches').count = s.counts.batches
  tabs.find(t => t.key === 'review').count = s.counts.pendingReview
})
</script>

<style scoped>
.page-head { display: flex; align-items: baseline; gap: 12px; margin-bottom: 10px; flex-wrap: wrap; }
.page-title { font-size: 18px; color: var(--head); }
.page-sub { font-size: 12px; color: var(--ink-2); }

.head-strip {
  display: flex; align-items: center; flex-wrap: wrap; gap: 2px 6px;
  padding: 8px 12px; font-size: 12px; color: var(--ink-2); margin-bottom: 10px;
}
.hs-sep { color: var(--line-strong); }
.hs-search input {
  margin-left: 6px; width: 160px; padding: 1px 8px; font-size: 12px;
  border: 1px solid var(--line); border-radius: 6px; background: var(--bg); color: var(--ink-2);
}
.hs-right { margin-left: auto; color: var(--ink); font-weight: 600; }

.tabs {
  display: flex; gap: 2px; padding: 4px 6px; margin-bottom: 12px;
  overflow-x: auto;
}
.tab {
  border: none; background: none; cursor: pointer; white-space: nowrap;
  padding: 7px 14px; font-size: 13px; color: var(--ink-2); border-radius: 8px;
  border-bottom: 2px solid transparent; font-family: inherit;
}
.tab:hover { background: var(--bg); }
.tab.cur { color: var(--brand); font-weight: 700; border-bottom-color: var(--brand); border-radius: 8px 8px 0 0; }
.tab-count {
  display: inline-block; min-width: 17px; text-align: center; margin-left: 4px;
  background: var(--brand-soft); color: var(--brand); border-radius: 999px;
  font-size: 11px; line-height: 17px; padding: 0 5px; font-weight: 700;
}
.tab.cur .tab-count { background: var(--brand); color: #fff; }
.tab-lock { font-size: 11px; }
.tab-body { min-width: 0; }
</style>
