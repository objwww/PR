<template>
  <div class="eval-compare" v-if="c.candidate">
    <div class="crumb">
      <b>首页</b><span class="sep">/</span>评测<span class="sep">/</span>实验比较<span class="sep">/</span>
      候选 <b>{{ c.candidate }}</b> vs 基线 <b>{{ c.baseline }}</b>
      <span class="crumb-right">同 dataset_version={{ c.constraint.datasetVersion }} ｜ partition={{ c.constraint.partition }} ｜ paired samples={{ c.constraint.pairedSamples }}</span>
    </div>

    <div class="lbl">配对比较（先看退化，再看平均数）</div>
    <div class="card box" v-for="p in c.paired" :key="p.metric">
      <b>{{ p.metric }}</b>
      <template v-if="p.from != null"> {{ p.from }} → {{ p.to }}</template>
      <template v-if="p.delta">，Δ {{ p.delta }}</template>
      <template v-if="p.ci95">，95% paired bootstrap CI [{{ p.ci95[0] > 0 ? '+' : '' }}{{ p.ci95[0] }},+{{ p.ci95[1] }}]</template>
      <span v-if="p.significant === false" class="tag t-gray">尚不显著</span>
      <span v-if="p.regression" class="tag t-red">{{ p.regression }}</span>
      <span v-if="p.diagnostic" class="diag-note">（诊断指标：{{ p.note }}）</span>
    </div>

    <div class="card box">
      <b>案例迁移</b>：改进 {{ c.migration.improved }} ｜ 退化 {{ c.migration.regressed }} ｜ flip {{ c.migration.flip }} ｜ 不变 {{ c.migration.unchanged }}<br>
      <span class="tag t-red">退化</span> {{ c.worstRegression.case }}：{{ c.worstRegression.from }} → {{ c.worstRegression.to }}
      <button class="btn">并排输出/Trace/证据</button>
    </div>

    <div class="card box">
      <b>唯一配置差异</b>：{{ c.configDiff.only }} ｜ {{ c.configDiff.same }}
      <button class="btn">查看完整 digest diff</button>
    </div>

    <div class="card matrix-toolbar">
      <label class="reg-toggle">
        <input type="checkbox" v-model="onlyRegressions"> 只看回归
      </label>
      ｜ 视图 <span class="field">Compact ▾</span>
      <span class="mt-actions">
        <button class="btn" @click="baselineSet = true">设为基准实验</button>
        <span v-if="baselineSet" class="tag t-blue">已设为基准（mock）</span>
        <button class="btn">导出矩阵</button>
      </span>
    </div>

    <div class="lbl">对比矩阵（实验为列、用例为行，单元格 ✓/✗，回归红 / 改进绿；点列头只显示回归/改进行）</div>
    <table class="grid card matrix">
      <thead>
        <tr>
          <th>用例</th>
          <th
            v-for="col in c.matrix.columns" :key="col.id"
            class="col-head" :class="{ filtered: colFilter === col.id }"
            @click="colFilter = colFilter === col.id ? null : col.id"
          >
            {{ col.id }} {{ col.role }}<br>
            <small>
              <template v-if="col.betterCount">更好 {{ col.betterCount }} ｜ </template>更差 {{ col.worseCount }}
            </small>
          </th>
          <th>判定 / 操作</th>
        </tr>
      </thead>
      <tbody>
        <tr
          v-for="row in visibleRows" :key="row.case"
          :class="{ 'sev-p0': row.sev === 'p0', cur: selectedRow === row.case }"
          @click="selectedRow = selectedRow === row.case ? null : row.case"
        >
          <td>{{ row.case }}</td>
          <td v-for="(cell, i) in row.cells" :key="i" :class="cellClass(row, i)">{{ cell }}</td>
          <td>
            <span v-if="row.change === 'UNCHANGED'" class="tag t-gray">不变</span>
            <span v-else-if="row.change === 'FLIP'" class="tag t-gray">FLIP</span>
            <span v-else-if="row.change === 'IMPROVED'" class="chg chg-imp">改进</span>
            <span v-else-if="row.change === 'REGRESSED'" class="chg chg-reg">退化</span>
            <span v-if="row.note" class="row-note">{{ row.note }}</span>
            <button v-if="row.change === 'REGRESSED'" class="btn" @click.stop>并排输出/Trace/证据</button>
          </td>
        </tr>
      </tbody>
    </table>
    <div v-if="!visibleRows.length" class="card empty">当前筛选无回归/改进行——<a @click="resetFilter">清除筛选</a></div>

    <div v-if="selectedRowData" class="card detail-panel">
      <b>{{ selectedRowData.case }}</b> 并排详情：{{ selectedRowData.cells[0] }}（{{ c.matrix.columns[0].id }}） vs
      <span :class="cellClass(selectedRowData, 1)">{{ selectedRowData.cells[1] }}</span>（{{ c.matrix.columns[1].id }}）
      ｜ 关键字段差异徽标：fault_type ✕ reason_code ✕
      <small class="dp-note">可再跳单条 Run 的 P3 视图；「只看回归」开关是发布门审查的主路径。</small>
    </div>
  </div>
</template>

<script setup>
import { computed, onMounted, reactive, ref } from 'vue'
import { api } from '../api/client.js'
import { fetchComparison } from '../mocks/eval.js'

const c = reactive({
  candidate: null, baseline: null, constraint: {}, paired: [],
  migration: {}, worstRegression: {}, configDiff: {}, matrix: { columns: [], rows: [] },
})
const onlyRegressions = ref(false)
const colFilter = ref(null)
const selectedRow = ref(null)
const baselineSet = ref(false)

const changedOnly = computed(() => !!colFilter.value)
const visibleRows = computed(() => {
  let rows = c.matrix.rows
  if (onlyRegressions.value) rows = rows.filter(r => r.change === 'REGRESSED')
  else if (changedOnly.value) rows = rows.filter(r => r.change === 'REGRESSED' || r.change === 'IMPROVED')
  return rows
})
const selectedRowData = computed(() => c.matrix.rows.find(r => r.case === selectedRow.value) || null)

function cellClass(row, i) {
  if (i === 1 && row.change === 'REGRESSED') return 'cell-reg'
  if (i === 1 && row.change === 'IMPROVED') return 'cell-imp'
  return null
}
function resetFilter() {
  onlyRegressions.value = false
  colFilter.value = null
}

onMounted(async () => {
  Object.assign(c, await api('/eval/compare', { mock: fetchComparison }))
})
</script>

<style scoped>
.crumb { font-size: 12px; color: var(--ink-2); margin-bottom: 10px; }
.crumb .sep { margin: 0 6px; color: var(--line-strong); }
.crumb-right { margin-left: 12px; }

.lbl { font-size: 12px; font-weight: 700; color: var(--ink-2); margin: 10px 0 6px; }
.box { padding: 8px 12px; margin-bottom: 8px; font-size: 12.5px; }
.box b { margin-right: 8px; }
.box .btn { margin-left: 8px; }
.diag-note { font-size: 11.5px; color: var(--warn); }

.matrix-toolbar {
  display: flex; align-items: center; gap: 8px; flex-wrap: wrap;
  padding: 7px 12px; font-size: 12.5px; color: var(--ink-2); margin: 8px 0;
}
.reg-toggle { display: inline-flex; align-items: center; gap: 4px; cursor: pointer; color: var(--ink); }
.field {
  display: inline-block; border: 1px solid var(--line-strong); border-radius: 6px;
  padding: 1px 10px; background: #fff; color: var(--ink); cursor: pointer;
}
.mt-actions { margin-left: auto; display: flex; align-items: center; gap: 6px; }

.grid { width: 100%; border-collapse: collapse; font-size: 12.5px; overflow: hidden; }
.grid th, .grid td { border-bottom: 1px solid var(--line); padding: 7px 12px; text-align: left; }
.grid th { background: #f6f8fb; color: var(--ink-2); font-size: 12px; }
.col-head { cursor: pointer; }
.col-head:hover { color: var(--brand); }
.col-head.filtered { color: var(--brand); }

.matrix tbody tr { cursor: pointer; }
.matrix tbody tr:hover { background: #f8fafd; }
.matrix tbody tr.cur { background: var(--brand-soft); }
tr.sev-p0 td:first-child { border-left: 3px solid var(--sev-p0); }

.cell-reg { background: var(--bad-bg); color: var(--bad); font-weight: 600; }
.cell-imp { background: var(--ok-bg); color: var(--ok); font-weight: 600; }

.chg { padding: 0 6px; border-radius: 4px; font-size: 12px; font-weight: 600; }
.chg-imp { background: var(--ok-bg); color: var(--ok); }
.chg-reg { background: var(--bad-bg); color: var(--bad); }
.row-note { margin-left: 6px; font-size: 12px; color: var(--ink-2); }
.chg + .btn, .row-note + .btn { margin-left: 8px; }

.empty { margin-top: 8px; padding: 14px; text-align: center; color: var(--ink-2); font-size: 12px; }
.empty a { cursor: pointer; }

.detail-panel { margin-top: 8px; padding: 10px 12px; font-size: 12.5px; }
.detail-panel .cell-reg, .detail-panel .cell-imp { padding: 0 6px; border-radius: 4px; }
.dp-note { display: block; color: var(--ink-2); margin-top: 6px; }
</style>
