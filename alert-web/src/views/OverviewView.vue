<template>
  <div class="ov">
    <!-- 面包屑 + 环境/更新时间（线框 P1 crumb） -->
    <div class="crumb-bar card">
      <span><b>首页</b><span class="sep">/</span>总览</span>
      <span class="env">{{ data.env }} ｜ {{ data.timezone }} ｜ 数据更新至 {{ data.updatedAt }}</span>
    </div>

    <!-- 统计卡行：全部可点击，带筛选参数深跳对应工作队列（E-19 §3-P1） -->
    <div class="stat-row">
      <div class="stat-card card" @click="jump(s.overdueCases.jump)">
        <div class="lbl">⏰ 逾期待办</div>
        <div class="num">{{ s.overdueCases.count }} <span v-if="s.overdueCases.overSla" class="tag t-red">超 SLA</span></div>
        <div class="jump">最老已逾期 {{ s.overdueCases.oldestOverdueMin }}m ｜ 点击 → 带筛选参数跳 P4 处置</div>
      </div>
      <div class="stat-card card" @click="jump(s.stuckFailedRuns.jump)">
        <div class="lbl">🧱 卡住/失败 Run</div>
        <div class="num">{{ s.stuckFailedRuns.total }}</div>
        <div class="jump">卡住 {{ s.stuckFailedRuns.stuck }} ｜ 失败 {{ s.stuckFailedRuns.failed }} ｜ 点击 → 带筛选参数跳 P3 调查队列</div>
      </div>
      <div class="stat-card card" @click="jump(s.oldestReady.jump)">
        <div class="lbl">⏳ 最老 READY</div>
        <div class="num">{{ s.oldestReady.ageMin }}m</div>
        <div class="jump">{{ s.oldestReady.runId }} ｜ {{ s.oldestReady.assignee || '未分配' }} ｜ 点击 → 跳 P3 队列（按等待排序）</div>
      </div>
      <div class="stat-card card" @click="jump(s.evalRegression.jump)">
        <div class="lbl">📉 评测回归</div>
        <div class="num small-num">{{ s.evalRegression.regressedCases }} case ｜ <span v-if="s.evalRegression.blockingCandidate" class="tag t-red">阻断候选</span></div>
        <div class="jump">{{ s.evalRegression.candidateEvalId }} vs {{ s.evalRegression.baselineEvalId }} ｜ 点击 → 跳 P5 实验对比</div>
      </div>
      <div class="stat-card card" @click="jump(s.publishFailures.jump)">
        <div class="lbl">📮 发布/投递失败</div>
        <div class="num">{{ s.publishFailures.count }}</div>
        <div class="jump">outbox 最老 {{ s.publishFailures.outboxOldestMin }}m ｜ 点击 → 跳 P6 监控</div>
      </div>
    </div>

    <div class="note">
      <b>统计卡可点击（E-19 §3-P1：腾讯云概览→列表三级下钻、Alerta ASI 计数卡点击过滤）</b>：
      hover 高亮边框，点击带筛选参数深跳对应工作队列——各卡下方文字即深跳目标。
    </div>

    <div class="row">
      <!-- 交接视图：自上次值班以来的变化 -->
      <div class="card panel" style="flex:2">
        <div class="lbl">🔁 交接视图：自上次值班以来的变化</div>
        <div v-for="h in handover" :key="h.id" class="list-item">
          <span class="time">{{ h.time }}</span> {{ h.text }}
          <button
            v-if="h.claimable && !h.claimed"
            class="btn"
            :disabled="claiming === h.id"
            @click="onClaim(h)"
          >{{ claiming === h.id ? '认领中…' : '认领' }}</button>
          <span v-else-if="h.claimable && h.claimed" class="tag t-green">已认领</span>
        </div>
      </div>
      <!-- 系统健康 -->
      <div class="card panel" style="flex:1">
        <div class="lbl">💚 系统健康</div>
        <div class="box small">
          control-app <span class="tag" :class="healthTag('control-app')">{{ healthText('control-app') }}</span>
        </div>
        <div class="box small">
          notify-app <span class="tag" :class="healthTag('notify-app')">{{ healthText('notify-app') }}</span>
        </div>
        <div class="box small">
          litellm <span class="tag" :class="healthTag('litellm')">{{ healthText('litellm') }}</span>
          holmes <span class="tag t-gray">对照</span>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup>
// P1 总览（/overview）：行动首页——逾期/卡住/最老 READY/评测回归/发布失败优先区 + 可点击统计卡深跳
// 数据契约：annot「新增行动摘要只读 API」；轮询 30s，不接 SSE；仅允许低风险「认领」
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { api } from '../api/client'
import { fetchActionSummary, claimCase } from '../mocks/overview'

const router = useRouter()
const data = ref({ env: '生产环境', timezone: 'Asia/Shanghai', updatedAt: '--:--:--', stats: null, handover: [], health: [] })
const claiming = ref('')
let timer = null

const s = computed(() => data.value.stats || {
  overdueCases: { count: '—', oldestOverdueMin: '—' },
  stuckFailedRuns: { total: '—', stuck: '—', failed: '—' },
  oldestReady: { ageMin: '—', runId: '—' },
  evalRegression: { regressedCases: '—' },
  publishFailures: { count: '—', outboxOldestMin: '—' },
})
const handover = computed(() => data.value.handover)

async function refresh() {
  data.value = await api('/v1/overview/action-summary', { mock: fetchActionSummary })
}

function jump(target) {
  if (target) router.push(target)
}

async function onClaim(h) {
  claiming.value = h.id
  try {
    await api(`/v1/operator-cases/${h.caseId}/claim`, { mock: claimCase })
    h.claimed = true
  } finally {
    claiming.value = ''
  }
}

function health(name) {
  return data.value.health.find(x => x.name === name)
}
function healthTag(name) {
  const st = health(name)?.status
  return st === 'UP' ? 't-green' : st === 'REFERENCE' ? 't-gray' : 't-red'
}
function healthText(name) {
  return health(name)?.status || 'UNKNOWN'
}

onMounted(() => {
  refresh()
  timer = setInterval(refresh, 30000) // annot：轮询 30s 即可，不接 SSE
})
onBeforeUnmount(() => clearInterval(timer))
</script>

<style scoped>
.ov { display: flex; flex-direction: column; gap: 0; }

.crumb-bar {
  display: flex; justify-content: space-between; align-items: center; flex-wrap: wrap;
  gap: 4px 12px; padding: 7px 14px; font-size: 12px; color: var(--ink-2);
}
.crumb-bar b { color: var(--head); font-weight: 600; }
.crumb-bar .sep { color: #9aa5b1; margin: 0 4px; }
.crumb-bar .env { font-size: 11.5px; }

.stat-row { display: flex; gap: 12px; padding: 12px 0; }
.stat-card { flex: 1; padding: 12px 14px; cursor: pointer; transition: border-color .15s, box-shadow .15s; }
.stat-card:hover { border-color: var(--brand); box-shadow: 0 0 0 2px var(--brand-soft), var(--shadow); }
.stat-card .lbl { font-size: 12px; color: var(--ink-2); font-weight: 600; }
.stat-card .num { font-size: 22px; font-weight: 700; color: var(--head); margin-top: 2px; }
.stat-card .num.small-num { font-size: 16px; }
.stat-card .jump { font-size: 11px; color: var(--brand); margin-top: 5px; }

.note {
  background: var(--warn-bg); border: 1px solid #ecd9a0; border-left: 4px solid #e6b93f;
  border-radius: 8px; padding: 10px 14px; font-size: 12.5px; line-height: 1.7; margin-bottom: 12px;
}

.row { display: flex; gap: 12px; }
.panel { padding: 12px 14px; }
.panel .lbl { font-size: 12px; color: var(--brand); font-weight: 700; margin-bottom: 6px; letter-spacing: .3px; }
.list-item { border-bottom: 1px solid #edf0f4; padding: 7px 4px; font-size: 12px; }
.list-item:last-child { border-bottom: none; }
.list-item .time { color: var(--ink-2); font-weight: 600; margin-right: 4px; }
.list-item .btn { margin-left: 6px; }

.box {
  border: 1px solid var(--line); border-radius: 8px; background: #f7f9fc;
  padding: 8px 10px; margin-bottom: 8px; font-size: 12px; line-height: 1.55;
}
.box.small { padding: 4px 8px; font-size: 11.5px; }
.box .tag { margin-left: 4px; }

@media (max-width: 1100px) {
  .stat-row { flex-wrap: wrap; }
  .stat-card { flex: 1 1 30%; }
  .row { flex-direction: column; }
}
</style>
