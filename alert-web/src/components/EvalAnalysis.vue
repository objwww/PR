<template>
  <div class="eval-analysis" v-if="a.runId">
    <div class="crumb">
      <b>首页</b><span class="sep">/</span>评测<span class="sep">/</span>批次运行<span class="sep">/</span>
      <b>{{ a.runId }}</b>（{{ a.replayZh }} ｜ {{ a.partition }} v{{ a.datasetVersion }}）
      <span class="crumb-right">
        <span class="tag" :class="a.status === 'COMPLETED' ? 't-green' : 't-blue'">{{ a.status }} {{ a.done }}/{{ a.total }}</span>
        elapsed {{ a.elapsed }} ｜ {{ a.cost }}/{{ a.budget }}
      </span>
    </div>

    <div class="bar"><i :style="{ width: pct + '%' }" :class="{ done: a.status === 'COMPLETED' }"></i></div>

    <div class="dim-grid">
      <div v-for="d in a.dimensions" :key="d.key" class="card metric">
        <b>{{ d.name }}</b>
        <div class="m-head">{{ d.headline }}</div>
        <small>{{ d.sub }}</small>
      </div>
    </div>

    <div class="cols">
      <div class="col" style="flex:3">
        <div class="lbl">指标切片（总分之外必须定位“哪类问题”）</div>
        <div class="card box">
          <b>按 fault_type</b>
          <span v-for="s in a.slices.byFaultType" :key="s.faultType" class="slice-item">
            {{ s.faultType }} {{ s.score.toFixed(2) }}
            <span v-if="s.lowest" class="tag t-red">最低</span>
          </span>
        </div>
        <div class="card box">
          <b>按 scenario_family / component / severity / replay / round</b>
          {{ a.slices.hotspot.family }} × {{ a.slices.hotspot.component }} × round{{ a.slices.hotspot.round }}
          flip rate={{ a.slices.hotspot.flipRate }}
          <button class="btn">钻取 {{ a.slices.hotspot.drillCases }} 例</button>
        </div>
        <div class="card box">
          <b>统计口径</b>
          TP={{ a.slices.stats.tp }} / FP={{ a.slices.stats.fp }} / FN={{ a.slices.stats.fn }} / support={{ a.slices.stats.support }}
          ｜ 95% bootstrap CI [{{ a.slices.stats.bootstrapCi95.join(',') }}]
          ｜ {{ a.slices.statsNote }}。
        </div>
      </div>
      <div class="col" style="flex:2">
        <div class="lbl">运行健康与可复现</div>
        <div class="card box">
          queue {{ a.health.queue }} ｜ model {{ a.health.model }} ｜ tool {{ a.health.tool }} ｜ validation {{ a.health.validation }}
          ｜ error {{ a.health.error }} ｜ retry {{ a.health.retry }} ｜ provider drift {{ a.health.providerDrift }}
        </div>
        <div class="card box">
          dataset={{ a.repro.dataset }} ｜ registry={{ a.repro.registry }} ｜ lexicon={{ a.repro.lexicon }}
          ｜ model/provider fingerprint={{ a.repro.providerFingerprint }} ｜ prompt={{ a.repro.prompt }}
          ｜ tools={{ a.repro.tools }} ｜ config={{ a.repro.config }} ｜ seed={{ a.repro.seed }}
          <button class="btn" @click="copied = true">复制复现清单</button>
          <span v-if="copied" class="tag t-green">已复制（mock）</span>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup>
import { computed, onMounted, reactive, ref, watch } from 'vue'
import { api } from '../api/client.js'
import { fetchRunAnalysis } from '../mocks/eval.js'

const props = defineProps({ runId: { type: String, default: 'eval#0907' } })

const a = reactive({ slices: { byFaultType: [], hotspot: {}, stats: { bootstrapCi95: [] } }, health: {}, repro: {}, dimensions: [] })
const copied = ref(false)

const pct = computed(() => (a.total ? Math.round((a.done / a.total) * 100) : 0))

async function load(runId) {
  Object.assign(a, await api(`/eval/runs/${runId}/analysis`, { mock: () => fetchRunAnalysis(runId) }))
}

onMounted(() => load(props.runId))
watch(() => props.runId, load)
</script>

<style scoped>
.crumb { font-size: 12px; color: var(--ink-2); margin-bottom: 8px; }
.crumb .sep { margin: 0 6px; color: var(--line-strong); }
.crumb-right { margin-left: 12px; display: inline-flex; align-items: center; gap: 8px; }

.bar { height: 8px; background: var(--card); border: 1px solid var(--line); border-radius: 999px; overflow: hidden; margin-bottom: 10px; }
.bar i { display: block; height: 100%; background: var(--brand); }
.bar i.done { background: var(--ok); }

.dim-grid { display: grid; grid-template-columns: repeat(6, 1fr); gap: 8px; margin-bottom: 12px; }
@media (max-width: 1500px) { .dim-grid { grid-template-columns: repeat(3, 1fr); } }
.metric { padding: 10px 12px; font-size: 12px; }
.metric b { font-size: 12.5px; }
.m-head { font-size: 15px; font-weight: 700; color: var(--head); margin: 4px 0 2px; }
.metric small { color: var(--ink-2); }

.cols { display: flex; gap: 10px; align-items: flex-start; }
.col { min-width: 0; }
.lbl { font-size: 12px; font-weight: 700; color: var(--ink-2); margin-bottom: 6px; }
.box { padding: 8px 12px; margin-bottom: 8px; font-size: 12.5px; }
.box b { margin-right: 8px; }
.slice-item { margin-right: 12px; }
.box .btn { margin-left: 8px; }
</style>
