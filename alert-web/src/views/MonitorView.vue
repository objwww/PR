<template>
  <div class="monitor-page">
    <!-- 面包屑 + 环境摘要条（线框 #p6 crumb） -->
    <div class="crumb card">
      <span class="crumb-path"><b>首页</b><span class="sep">/</span>Agent 监控</span>
      <span class="crumb-env">
        {{ data.env }} ｜ 近 {{ data.window }} ▾ ｜ 数据延迟 {{ data.dataDelaySeconds }}s ｜
        搜索 Run / worker / operation <input class="crumb-search" type="text" disabled> ｜ {{ data.operator }} ▾
      </span>
    </div>

    <!-- 队列/执行/投递/调和健康指标卡（可点击深链，annot：指标卡深链到带筛选条件的视图） -->
    <div class="metric-grid">
      <div class="card metric" :class="{ danger: m.oldestReady.breached }" @click="go('/runs', { status: 'READY' })">
        <b>最老 READY</b>
        <div class="metric-val">
          <span class="num">{{ fmtDur(m.oldestReady.ageSeconds) }}</span>
          <span v-if="m.oldestReady.breached" class="tag t-red">SLO {{ fmtDur(m.oldestReady.sloSeconds) }}</span>
        </div>
        <small>{{ m.oldestReady.runRef }} → {{ m.oldestReady.target }}</small>
      </div>
      <div class="card metric">
        <b>Worker 槽位</b>
        <div class="metric-val">
          <span class="num">{{ m.workerSlots.used }}/{{ m.workerSlots.total }}</span>
          <span class="pct">{{ fmtPct(m.workerSlots.utilization) }}</span>
        </div>
        <small>{{ m.workerSlots.hotspot.worker }} backlog {{ m.workerSlots.hotspot.backlog }}</small>
      </div>
      <div class="card metric" :class="{ danger: m.outboxLag.status === 'DEGRADED' }">
        <b>Outbox 延迟</b>
        <div class="metric-val">
          <span class="num">{{ fmtDur(m.outboxLag.lagSeconds) }}</span>
          <span v-if="m.outboxLag.status === 'DEGRADED'" class="tag t-red">DEGRADED</span>
        </div>
        <small>失败投递 {{ m.outboxLag.failedDeliveries }} → {{ m.outboxLag.target }}</small>
      </div>
      <div class="card metric">
        <b>调和积压</b>
        <div class="metric-val"><span class="num">{{ m.reconciliationBacklog.count }}</span></div>
        <small>最老 {{ fmtDur(m.reconciliationBacklog.oldestSeconds) }}</small>
      </div>
      <div class="card metric" @click="go('/cases', { overdue: 'true' })">
        <b>开放 Case</b>
        <div class="metric-val"><span class="num">{{ m.openCases.count }}</span></div>
        <small>逾期 {{ m.openCases.overdue }} → {{ m.openCases.target }}</small>
      </div>
      <div class="card metric" @click="go('/eval')">
        <b>评测队列</b>
        <div class="metric-val"><span class="num">{{ m.evalQueue.total }}</span></div>
        <small>运行 {{ m.evalQueue.running }} / 排队 {{ m.evalQueue.queued }}</small>
      </div>
    </div>

    <div class="cols">
      <!-- 左列：活跃 Run 预算风险 + doom-loop 熔断记录 -->
      <div class="col-main">
        <div class="card panel">
          <div class="lbl">活跃 Run 与预算风险（点击下钻）</div>
          <div
            v-for="r in data.activeRuns"
            :key="r.runId"
            class="run-item"
            @click="go('/runs/' + r.runId.replace('run#', ''))"
          >
            <span class="run-id">{{ r.runId }}</span>
            <span class="tag t-blue">{{ r.status }}</span>
            <span class="budget">
              step
              <span class="bar"><i :style="{ width: r.stepUsedPct + '%' }"></i></span>
              token
              <span class="bar"><i :class="{ hot: r.tokenRisk }" :style="{ width: r.tokenUsedPct + '%' }"></i></span>
            </span>
            <span v-if="r.tokenRisk" class="tag t-red">{{ r.tokenUsedPct }}%</span>
          </div>
        </div>
        <div class="card panel">
          <div class="lbl">doom-loop 熔断记录</div>
          <div v-for="b in data.doomLoopBreaks" :key="b.runId" class="box-line">
            {{ b.runId }} ｜ {{ b.tool }} 同签名连续 {{ b.signatureStallCount }} 次无进展 → {{ b.action }} ｜ {{ b.at }}
          </div>
        </div>
      </div>

      <!-- 右列：工具调用 / 模型成本 / 控制面健康 -->
      <div class="col-side">
        <div class="card panel">
          <div class="lbl">工具调用（近 24h）</div>
          <div class="box-line">
            <template v-for="(t, i) in data.toolCalls24h.tools" :key="t.name">
              <template v-if="i > 0"> ｜ </template>{{ t.name }} {{ t.count }} 次 ✓{{ fmtPct(t.successRate) }}
            </template>
          </div>
          <div class="box-line">
            失败原因码分布：<template v-for="(f, i) in data.toolCalls24h.failuresByReason" :key="f.reasonCode">
              <template v-if="i > 0"> ｜ </template>{{ f.reasonCode }} {{ f.count }}
            </template>
          </div>
        </div>
        <div class="card panel">
          <div class="lbl">模型调用成本</div>
          <div v-for="c in data.modelCosts" :key="c.model" class="box-line">
            {{ c.model }}：{{ c.invocations }} 次 ｜ {{ fmtTokens(c.tokens) }} token ｜ ¥{{ c.costCny.toFixed(2) }}（{{ c.source }}）
          </div>
        </div>
        <div class="card panel">
          <div class="lbl">控制面健康与数据新鲜度</div>
          <div class="box-line">
            <template v-for="(c, i) in data.controlHealth.components" :key="c.name">
              <template v-if="i > 0"> ｜ </template>
              {{ c.name }}
              <span v-if="c.status === 'OK'" class="ok">✓</span>
              <span v-else class="tag t-red">{{ c.status }}</span>
            </template>
            <br>
            metrics last_seen={{ data.controlHealth.freshness.metricsLastSeenSeconds }}s ｜
            outbox projector lag={{ fmtDur(data.controlHealth.freshness.outboxProjectorLagSeconds) }}
          </div>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup>
// P6 Agent 监控（/monitor，线框 #p6）：队列、执行、投递与调和链路只读运行健康大盘
// annot 契约：只读大盘、指标卡深链；手动熔断复位本期不做；阈值显示单位和窗口
import { reactive, computed, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { api } from '../api/client'
import { getMonitorSummary } from '../mocks/monitor'

const router = useRouter()

const data = reactive({
  env: '', window: '', dataDelaySeconds: 0, operator: '',
  metrics: null,
  activeRuns: [], doomLoopBreaks: [],
  toolCalls24h: { tools: [], failuresByReason: [] },
  modelCosts: [],
  controlHealth: { components: [], freshness: { metricsLastSeenSeconds: 0, outboxProjectorLagSeconds: 0 } },
})

const m = computed(() => data.metrics || {
  oldestReady: { ageSeconds: 0, sloSeconds: 0, breached: false, runRef: '', target: '' },
  workerSlots: { used: 0, total: 0, utilization: 0, hotspot: { worker: '', backlog: 0 } },
  outboxLag: { lagSeconds: 0, status: 'OK', failedDeliveries: 0, target: '' },
  reconciliationBacklog: { count: 0, oldestSeconds: 0 },
  openCases: { count: 0, overdue: 0, target: '' },
  evalQueue: { total: 0, running: 0, queued: 0 },
})

onMounted(async () => {
  const res = await api('/agent-ops/summary', { mock: getMonitorSummary })
  Object.assign(data, res)
})

function go(path, query) {
  router.push({ path, query })
}

function fmtDur(sec) {
  if (sec < 60) return sec + 's'
  if (sec < 3600) return Math.round(sec / 60) + 'm'
  return Math.round(sec / 3600) + 'h'
}

function fmtPct(ratio) {
  return Math.round(ratio * 100) + '%'
}

function fmtTokens(n) {
  return n >= 1000 ? Math.round(n / 1000) + 'k' : String(n)
}
</script>

<style scoped>
.monitor-page { display: flex; flex-direction: column; gap: 12px; }

.crumb {
  display: flex; justify-content: space-between; align-items: center;
  padding: 8px 14px; font-size: 13px; flex-wrap: wrap; gap: 4px 12px;
}
.crumb-path { color: var(--ink); }
.crumb-path .sep { color: var(--ink-2); margin: 0 6px; }
.crumb-env { color: var(--ink-2); font-size: 12.5px; }
.crumb-search {
  width: 150px; padding: 1px 8px; border: 1px solid var(--line-strong);
  border-radius: 6px; font-size: 12px; background: #f5f7fa;
}

.metric-grid {
  display: grid; grid-template-columns: repeat(auto-fit, minmax(180px, 1fr)); gap: 10px;
}
.metric { padding: 12px 14px; cursor: pointer; transition: box-shadow .15s, border-color .15s; }
.metric:hover { border-color: var(--brand); box-shadow: var(--shadow); }
.metric > b { font-size: 12.5px; color: var(--ink-2); }
.metric-val { display: flex; align-items: baseline; gap: 8px; margin: 4px 0 2px; }
.metric-val .num { font-size: 20px; font-weight: 700; color: var(--head); }
.metric-val .pct { font-size: 13px; color: var(--ink-2); }
.metric > small { color: var(--ink-2); font-size: 12px; }
.metric.danger { border-left: 3px solid var(--bad); }

.cols { display: flex; gap: 12px; align-items: flex-start; flex-wrap: wrap; }
.col-main { flex: 3; min-width: 420px; display: flex; flex-direction: column; gap: 12px; }
.col-side { flex: 2; min-width: 300px; display: flex; flex-direction: column; gap: 12px; }

.panel { padding: 12px 14px; }
.lbl {
  font-size: 12px; font-weight: 700; color: var(--ink-2);
  border-left: 3px solid var(--brand); padding-left: 8px; margin-bottom: 8px;
}

.run-item {
  display: flex; align-items: center; gap: 10px;
  padding: 7px 8px; border-radius: 6px; font-size: 13px; cursor: pointer;
}
.run-item:hover { background: var(--brand-soft); }
.run-id { font-weight: 600; color: var(--brand); }
.budget { display: flex; align-items: center; gap: 6px; flex: 1; color: var(--ink-2); font-size: 12.5px; }
.bar {
  display: inline-block; width: 90px; height: 8px; border-radius: 4px;
  background: #e3e8f0; overflow: hidden; vertical-align: middle;
}
.bar i { display: block; height: 100%; background: var(--brand); border-radius: 4px; }
.bar i.hot { background: var(--bad); }

.box-line {
  background: #f7f9fc; border: 1px solid var(--line); border-radius: 6px;
  padding: 7px 10px; font-size: 12.5px; color: var(--ink); margin-bottom: 6px;
}
.box-line:last-child { margin-bottom: 0; }
.ok { color: var(--ok); font-weight: 700; }
</style>
