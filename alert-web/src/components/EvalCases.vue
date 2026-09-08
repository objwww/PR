<template>
  <div class="eval-cases">
    <div class="crumb">
      <b>首页</b><span class="sep">/</span>评测<span class="sep">/</span>案例诊断<span class="sep">/</span>
      <template v-if="detail">{{ detail.context }} / <b>{{ detail.resultId }}</b></template>
      <span v-if="detail" class="crumb-right">
        <span class="tag" :class="verdictClass(detail.verdict)">{{ detail.verdict }}</span>
        root_hit={{ detail.rootHit }} ｜ latency={{ detail.latency }} ｜ cost={{ detail.cost }}
      </span>
    </div>

    <div class="cols">
      <div class="col" style="flex:2">
        <div class="lbl">案例列表</div>
        <div class="filter-chips">
          <button
            v-for="f in filters" :key="f.key"
            class="chip" :class="{ cur: filter === f.key }"
            @click="filter = f.key"
          >{{ f.labelZh }} <span class="chip-n">{{ countOf(f.key) }}</span></button>
        </div>
        <div
          v-for="c in visibleCases" :key="c.resultId"
          class="card case-item" :class="{ cur: selected === c.resultId }"
          @click="select(c.resultId)"
        >
          <span class="tag" :class="verdictClass(c.verdict)">{{ c.verdict }}</span>
          {{ c.family }} round{{ c.round }}
          <template v-if="c.expected"> ｜ expected {{ c.expected }}</template>
          <template v-if="c.actual && c.verdict !== 'PASS'"> ｜ actual {{ c.actual }}</template>
          <template v-if="c.latency && c.verdict === 'PASS'"> ｜ {{ c.latency }}</template>
          <div v-if="c.note" class="case-note">{{ c.note }}</div>
        </div>
        <div v-if="!visibleCases.length" class="card empty">筛选无结果——<a @click="filter = 'ALL'">清除筛选</a></div>
      </div>

      <div class="col" style="flex:3" v-if="detail">
        <div class="sub-tabs card">
          <button v-for="t in subTabs" :key="t" class="sub-tab" :class="{ cur: subTab === t }" @click="subTab = t">{{ t }}</button>
        </div>

        <div v-if="subTab === '输入/真值' || subTab === '评分明细'" class="card box">
          <b>期望</b> component={{ detail.expected.component }} ｜ fault_type={{ detail.expected.faultType }} ｜ reason_code={{ detail.expected.reasonCode }}<br>
          <b>实际</b> component={{ detail.actual.component }} {{ detail.actual.componentHit ? '✓' : '✕' }} ｜ fault_type={{ detail.actual.faultType }} ✕ ｜ reason_code={{ detail.actual.reasonCode }} ✕
        </div>

        <div v-if="subTab === '评分明细'" class="card box">
          <b>确定性判分说明</b>：{{ detail.scoring.rule }}。TP={{ detail.scoring.tp }} / FP={{ detail.scoring.fp }} / FN={{ detail.scoring.fn }}；verdict={{ detail.scoring.verdict }}。
          <button class="btn">查看 scorer 输入</button>
        </div>

        <div v-if="subTab === 'Trace/步骤' || subTab === '证据' || subTab === '评分明细'" class="card box">
          <b>证据与运行轨迹</b>：{{ detail.trace.rcaRunId }} ｜ Tasks {{ detail.trace.tasks }} ｜ ToolCalls {{ detail.trace.toolCalls }} ｜ Evidence {{ detail.trace.evidence }} ｜ Claims {{ detail.trace.claims }}<br>
          关键缺口：{{ detail.trace.gaps }}。
          <button class="btn">打开完整步骤</button>
        </div>

        <div v-if="subTab === '输出'" class="card box">输出视图：结构化结论与 summary/impact/remediation（中文契约）；thought / 原始 prompt 永不进前端。</div>
        <div v-if="subTab === '元数据'" class="card box">复现 digest 见「分析」页运行健康与可复现区；更细 Trace 由 rca_run_id 联查 P3 视图。</div>

        <div v-if="subTab === '复核历史' || subTab === '评分明细'" class="card box">
          <b>人工复核</b>　队列原因：{{ detail.review.queueReasons.join(' / ') }}<br>
          <span class="field">确认机器判分 ▾</span>
          <input class="review-input" type="text" placeholder="说明">
          <button class="btn primary" @click="submitted = true">提交复核事件</button>
          <span v-if="submitted" class="tag t-green">已追加 review_event（mock）</span>
          <small class="review-note">{{ detail.review.note }}。</small>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup>
import { computed, onMounted, ref } from 'vue'
import { api } from '../api/client.js'
import { fetchCaseResults, fetchCaseDetail } from '../mocks/eval.js'

const cases = ref([])
const filter = ref('ALL')
const selected = ref(null)
const detail = ref(null)
const subTab = ref('评分明细')
const submitted = ref(false)

const filters = [
  { key: 'ALL', labelZh: '全部' },
  { key: 'PASS', labelZh: 'Pass' },
  { key: 'FAIL', labelZh: 'Fail' },
  { key: 'ERROR', labelZh: 'Error' },
  { key: 'FLIP', labelZh: 'Flip' },
  { key: 'REVIEW_PENDING', labelZh: '待复核' },
]
const subTabs = ['输入/真值', '输出', '评分明细', 'Trace/步骤', '证据', '元数据', '复核历史']

const countOf = k => (k === 'ALL' ? cases.value.length : cases.value.filter(c => c.verdict === k).length)
const visibleCases = computed(() => (filter.value === 'ALL' ? cases.value : cases.value.filter(c => c.verdict === filter.value)))

function verdictClass(v) {
  return { PASS: 't-green', FAIL: 't-red', ERROR: 't-red', FLIP: 't-gray', REVIEW_PENDING: 't-orange' }[v] || 't-gray'
}

async function select(resultId) {
  selected.value = resultId
  submitted.value = false
  detail.value = await api(`/eval/cases/${resultId}`, { mock: () => fetchCaseDetail(resultId) })
}

onMounted(async () => {
  cases.value = (await api('/eval/runs/eval%230907/cases', { mock: () => fetchCaseResults('eval#0907') })).cases
  if (cases.value.length) select(cases.value[0].resultId)
})
</script>

<style scoped>
.crumb { font-size: 12px; color: var(--ink-2); margin-bottom: 10px; }
.crumb .sep { margin: 0 6px; color: var(--line-strong); }
.crumb-right { margin-left: 12px; display: inline-flex; align-items: center; gap: 8px; }

.cols { display: flex; gap: 10px; align-items: flex-start; }
.col { min-width: 0; }
.lbl { font-size: 12px; font-weight: 700; color: var(--ink-2); margin-bottom: 6px; }

.filter-chips { display: flex; flex-wrap: wrap; gap: 6px; margin-bottom: 8px; }
.chip {
  border: 1px solid var(--line-strong); background: #fff; border-radius: 999px;
  padding: 2px 10px; font-size: 12px; cursor: pointer; color: var(--ink-2); font-family: inherit;
}
.chip.cur { border-color: var(--brand); color: var(--brand); font-weight: 700; background: var(--brand-soft); }
.chip-n { font-size: 11px; }

.case-item { padding: 8px 12px; margin-bottom: 6px; font-size: 12.5px; cursor: pointer; }
.case-item:hover { border-color: var(--brand); }
.case-item.cur { border-color: var(--brand); box-shadow: 0 0 0 1px var(--brand); }
.case-note { color: var(--ink-2); font-size: 12px; margin-top: 2px; }
.empty { padding: 16px; text-align: center; color: var(--ink-2); font-size: 12px; }
.empty a { cursor: pointer; }

.sub-tabs { display: flex; gap: 2px; padding: 4px 6px; margin-bottom: 8px; overflow-x: auto; }
.sub-tab {
  border: none; background: none; cursor: pointer; white-space: nowrap; font-family: inherit;
  padding: 6px 12px; font-size: 12.5px; color: var(--ink-2); border-bottom: 2px solid transparent;
}
.sub-tab.cur { color: var(--brand); font-weight: 700; border-bottom-color: var(--brand); }

.box { padding: 10px 12px; margin-bottom: 8px; font-size: 12.5px; }
.box .btn { margin-left: 8px; }
.field {
  display: inline-block; border: 1px solid var(--line-strong); border-radius: 6px;
  padding: 1px 10px; background: #fff; font-size: 12px; margin: 6px 4px 0 0;
}
.review-input {
  border: 1px solid var(--line-strong); border-radius: 6px; padding: 2px 10px;
  font-size: 12px; width: 180px; margin-right: 6px; font-family: inherit;
}
.review-note { display: block; color: var(--ink-2); margin-top: 6px; }
</style>
