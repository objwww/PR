<template>
  <div class="incident">
    <!-- 头部摘要条（线框 P2-B .crumb）：severity/状态 badge + 首次/最近/owner -->
    <div class="crumb card">
      <span>
        <b>首页</b><span class="sep">/</span>告警<span class="sep">/</span>{{ d?.category_label ?? '…' }}<span class="sep">/</span><b>{{ d?.alertname ?? incidentId }}</b><span class="sep">/</span>generation {{ d?.generation ?? '—' }}
      </span>
      <span class="env" v-if="d">
        <span class="tag t-red">{{ d.severity }} {{ statusLabel(d.status) }}</span>
        首次 {{ d.first_at }} ｜ 最近 {{ d.last_at }} ｜ owner={{ d.owner ?? '未分配' }}
      </span>
    </div>

    <!-- 八标签（annot「详情分层」）：概览/实例与阈值/调查过程/证据与 RCA/报告/状态历史/通知/源数据 -->
    <div class="tabs card" v-if="d">
      <span
        v-for="t in d.tabs" :key="t"
        :class="{ cur: curTab === t }"
        @click="curTab = t"
      >{{ t }}</span>
    </div>

    <template v-if="d && curTab === '调查过程'">
      <!-- 轨迹工具条：按 generation/run/task 切换；SSE 增量遇 seq 缺口即全量重建 -->
      <div class="toolbar card">
        轨迹范围 <span class="field">{{ d.trace.scope }} ▾</span>
        Run <span class="field">run#{{ d.trace.run_id }} ▾</span>
        Task <span class="field">{{ d.trace.task }} ▾</span>
        <span class="bar-sep">｜</span>
        <button class="btn" :class="{ primary: onlyAbnormal }" @click="onlyAbnormal = !onlyAbnormal">仅看异常步骤</button>
        <button class="btn" :class="{ primary: expandIo }" @click="expandIo = !expandIo">展开耗时与输入输出引用</button>
        <span class="bar-sep">｜</span>
        <span class="live">实时 <i class="dot"></i> seq={{ d.trace.live_seq }}</span>
      </div>

      <!-- 全步骤轨迹：接收→归并→准入→Run→DAG→ToolCall/Attempt→Evidence→Claim/Report→发布/Case -->
      <div class="step-lane card">
        <div
          v-for="s in visibleSteps" :key="s.n"
          class="step" :class="[stepClass(s.state), { cur: curStep === s.n }]"
          @click="curStep = s.n"
        >
          <b>{{ s.n }} {{ s.name }}</b>
          <div v-for="(ln, i) in s.lines" :key="i">{{ ln }}</div>
        </div>
      </div>

      <div class="row">
        <!-- 选中步骤下钻（Task / Attempt 粒度） -->
        <div class="col" style="flex:3">
          <div class="lbl">选中步骤 {{ curStep }}：{{ curStepName }}（可继续下钻 Task / Attempt）</div>
          <template v-if="drill.length">
            <div v-for="it in drill" :key="it.id" class="list-item">
              <b>{{ it.id }} {{ it.tool }}</b> ｜ task#{{ it.task_id }} attempt#{{ it.attempt }} ｜ {{ it.window }} ｜
              <span :class="it.result === 'SUCCESS' ? 'ok-text' : 'bad-text'">{{ it.result }}</span>
              <template v-if="it.reason_code"> ｜ reason=<code>{{ it.reason_code }}</code></template>
              <template v-if="it.output"> ｜ output {{ it.output }}</template>
              <button class="btn" @click="drillAction(it)">{{ it.action }}</button>
              <div v-if="expandIo" class="io-line">input_ref={{ it.input_ref }} ｜ duration={{ it.duration_ms }}ms ｜ component={{ it.component }}</div>
            </div>
          </template>
          <div v-else class="list-item muted">该步骤暂无 Task/Attempt 级明细（{{ curStepStateText }}）；明细由 task/attempt、tool ledger 投影提供。</div>
          <div class="note-box">每一步显示：状态、开始/结束/耗时、触发事件、责任组件、输入引用、输出对象、错误码、重试/回退、关联 Task/Attempt/Evidence/Claim；点击才展开安全参数摘要。</div>
        </div>

        <!-- 结构化 InvestigationSummary：禁止展示模型 Thought / 原始 prompt / secret / 完整工具参数 -->
        <div class="col" style="flex:2">
          <div class="lbl">调查摘要（结构化过程，不展示模型 Thought）</div>
          <div class="note-box"><b>问题</b>：{{ d.summary.question }}</div>
          <div class="note-box"><b>已验证</b>：{{ d.summary.verified.map((v, i) => `${'①②③④⑤'[i]} ${v}`).join('；') }}。</div>
          <div class="note-box">
            <b>排除</b>：{{ d.summary.excluded.join('；') }}。<br>
            <b>当前假设</b>：{{ d.summary.hypothesis }}
          </div>
          <div class="note-box">
            <b>关联</b>：Evidence {{ d.summary.links.evidence }} ｜ Claims {{ d.summary.links.claims }} ｜ Report {{ d.summary.links.report }} ｜ Case {{ d.summary.links.case_id }}<br>
            <!-- 联动：打开 DAG 任务图 = /runs/{run_id} -->
            <router-link class="btn" :to="`/runs/${d.summary.links.run_id}`">打开 DAG 任务图</router-link>
            <button class="btn" @click="evidenceMsg = true">打开证据矩阵</button>
            <div v-if="evidenceMsg" class="muted mini">证据矩阵属 P3 审查台范围，联调后跳转对应视图。</div>
          </div>
        </div>
      </div>
    </template>

    <!-- 其余七个标签：投影未落码前的占位说明（不给空白页） -->
    <div v-else-if="d" class="card tab-placeholder">
      <b>{{ curTab }}</b>：由对应投影提供（annot「详情分层」——AlertEvent 是上游事实，Evidence 是观察，Claim 是有证据引用的判断）；后端接口落码前以「调查过程」为默认实现标签。
    </div>

    <!-- Run 未创建 / Incident 不存在：显示准入/排队说明，不给空白页（annot「联动」） -->
    <div v-else-if="loaded" class="card tab-placeholder">
      Incident <code>{{ incidentId }}</code> 未找到或尚未建 Run——处于准入/排队阶段，原因由准入策略投影提供；本页不留空白。
    </div>
  </div>
</template>

<script setup>
// P2-B Incident 详情与「每一步」可视化（/alerts/:incidentId）
// 线框图 v1.6 section#p2 frame B；步骤数据来自 IncidentTimelineProjection 契约
import { computed, onMounted, ref, watch } from 'vue'
import { useRoute } from 'vue-router'
import { api } from '../api/client'
import { fetchIncidentDetail } from '../mocks/alerts'

const route = useRoute()
const incidentId = computed(() => String(route.params.incidentId))

const d = ref(null)
const loaded = ref(false)
const curTab = ref('调查过程')
const curStep = ref(6)
const onlyAbnormal = ref(false)
const expandIo = ref(false)
const evidenceMsg = ref(false)

onMounted(load)
watch(incidentId, load)

async function load() {
  loaded.value = false
  d.value = await api(`/v1/incidents/${incidentId.value}`, { mock: () => fetchIncidentDetail(incidentId.value) })
  loaded.value = true
  curTab.value = '调查过程'
  curStep.value = 6 // 线框默认选中步骤 6：执行工具
}

// 仅看异常步骤：有 TIMEOUT/重试 Attempt 或未完成的步骤
const abnormalSteps = computed(() => {
  const set = new Set()
  Object.entries(d.value?.step_drill ?? {}).forEach(([n, items]) => {
    if (items.some(it => it.result !== 'SUCCESS' || it.attempt > 1)) set.add(Number(n))
  })
  d.value?.steps.forEach(s => { if (s.state !== 'DONE') set.add(s.n) })
  return set
})
const visibleSteps = computed(() =>
  onlyAbnormal.value ? (d.value?.steps ?? []).filter(s => abnormalSteps.value.has(s.n)) : (d.value?.steps ?? []))

const drill = computed(() => d.value?.step_drill?.[curStep.value] ?? [])
const curStepName = computed(() => d.value?.steps.find(s => s.n === curStep.value)?.name ?? '')
const curStepStateText = computed(() => {
  const st = d.value?.steps.find(s => s.n === curStep.value)?.state
  return { DONE: '已完成', RUNNING: '进行中', WAITING: '待执行', FAIL: '失败' }[st] ?? ''
})

function stepClass(state) {
  return { DONE: 'done', RUNNING: 'running', WAITING: 'wait', FAIL: 'fail' }[state] ?? ''
}
function statusLabel(s) {
  return { FIRING: 'FIRING', ACK: 'ACK', NODATA: 'NoData', RESOLVED: '已解决' }[s] ?? s
}
function drillAction() { /* 查看证据/错误/Attempt：P3 审查台视图联调后跳转 */ }
</script>

<style scoped>
.incident { display: flex; flex-direction: column; gap: 8px; }

.crumb { display: flex; justify-content: space-between; align-items: center; flex-wrap: wrap; gap: 4px 12px; padding: 7px 14px; font-size: 12px; color: var(--ink-2); }
.crumb b { color: var(--head); font-weight: 600; }
.crumb .sep { color: #9aa5b1; margin: 0 4px; }
.crumb .env { font-size: 11.5px; color: var(--ink-2); }
.crumb .env .tag { margin-right: 6px; }

.tabs { display: flex; flex-wrap: wrap; gap: 5px; padding: 9px 12px 0; background: #f6f8fb; }
.tabs span { border: 1px solid var(--line-strong); border-bottom: none; border-radius: 8px 8px 0 0; padding: 5px 12px; font-size: 11.5px; background: #fff; color: var(--ink-2); cursor: pointer; }
.tabs span.cur { background: var(--brand); color: #fff; border-color: var(--brand); font-weight: 600; }

.toolbar { display: flex; gap: 6px; flex-wrap: wrap; align-items: center; padding: 8px 12px; font-size: 11.5px; }
.field { display: inline-block; border: 1px solid var(--line-strong); border-radius: 6px; background: #fff; padding: 3px 8px; min-width: 88px; color: var(--ink-2); }
.bar-sep { color: var(--line-strong); }
.live { color: var(--ink-2); }
.live .dot { display: inline-block; width: 8px; height: 8px; border-radius: 50%; background: #7ee2a0; margin: 0 2px; vertical-align: middle; }

/* 步骤轨迹 lane */
.step-lane { display: flex; align-items: stretch; gap: 8px; padding: 10px; overflow-x: auto; }
.step { min-width: 104px; flex: 1; border: 1px solid var(--line); border-top: 4px solid var(--brand); border-radius: 8px; padding: 8px; background: #fff; font-size: 11px; position: relative; box-shadow: 0 1px 3px rgba(30,42,58,.06); cursor: pointer; }
.step.done { border-top-color: var(--ok); }
.step.wait { border-top-color: #9aa5b1; background: #f6f8fb; }
.step.running { border-top-color: var(--brand); background: var(--brand-soft); }
.step.fail { border-top-color: var(--bad); background: #fdf3f2; }
.step.cur { box-shadow: 0 0 0 2px var(--brand-soft), var(--shadow); }
.step b { display: block; margin-bottom: 3px; }
.step + .step::before { content: "→"; position: absolute; left: -9px; top: 26px; color: #8a94a1; background: #fff; border-radius: 50%; }

.row { display: flex; gap: 12px; }
.col { min-width: 0; }
.col .lbl { font-size: 11.5px; color: var(--brand); font-weight: 700; margin-bottom: 6px; letter-spacing: .3px; }
.list-item { border: 1px solid var(--line); border-radius: 8px; background: #fff; padding: 7px 10px; font-size: 12px; margin-bottom: 6px; }
.io-line { margin-top: 4px; font-size: 11px; color: var(--ink-2); }
.ok-text { color: var(--ok); font-weight: 600; }
.bad-text { color: var(--bad); font-weight: 600; }
.muted { color: var(--ink-2); }
.mini { font-size: 11px; margin-top: 4px; }
.note-box { border: 1px solid var(--line); border-radius: 8px; background: #f7f9fc; padding: 6px 10px; margin-bottom: 8px; font-size: 11.5px; line-height: 1.55; }
.note-box .btn { margin-top: 4px; }
.tab-placeholder { padding: 18px; font-size: 12.5px; color: var(--ink-2); }

@media (max-width: 1100px) {
  .row { flex-wrap: wrap; }
  .col { flex-basis: 100% !important; }
  .step { min-width: 130px; }
}
</style>
