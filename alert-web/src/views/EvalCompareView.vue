<template>
  <div class="compare-page">
    <div class="crumb">
      <router-link to="/eval/runs">实验</router-link>
      <span class="sep">/</span>
      <b>对比工作台</b>
    </div>

    <!-- 基线 / 候选选择：数据来自真实 GET /eval/runs；支持固定基线后只换候选 -->
    <div class="card panel">
      <template v-if="runsState === 'ok'">
        <div class="pair">
          <div class="pair-item">
            <div class="pi-label">
              基线实验
              <el-checkbox
                v-model="baselinePinned"
                class="pin"
                :disabled="!baselineId"
              >固定基线</el-checkbox>
            </div>
            <el-select
              :model-value="baselineId"
              class="w-run"
              placeholder="选择基线实验"
              clearable
              :disabled="baselinePinned && !!baselineId"
              @change="v => onPick('baseline', v)"
            >
              <el-option
                v-for="r in baselineOptions" :key="r.runId"
                :value="r.runId" :label="runLabel(r)"
              >
                <div class="opt">
                  <span class="opt-main" :class="{ mono: !r.displayName }">{{ r.displayName ?? shortId(r.runId) }}</span>
                  <span class="opt-sub">{{ fmtState(r.state) }} · {{ fmtTime(r.startedAt) }}</span>
                </div>
              </el-option>
            </el-select>
            <div v-if="baselinePinned && baselineId" class="pi-note">已固定基线：更换候选时基线保持不变；取消勾选后可改选或交换。</div>
          </div>
          <div class="pair-item">
            <div class="pi-label">候选实验</div>
            <el-select
              :model-value="candidateId"
              class="w-run"
              placeholder="选择候选实验"
              clearable
              @change="v => onPick('candidate', v)"
            >
              <el-option
                v-for="r in candidateOptions" :key="r.runId"
                :value="r.runId" :label="runLabel(r)"
              >
                <div class="opt">
                  <span class="opt-main" :class="{ mono: !r.displayName }">{{ r.displayName ?? shortId(r.runId) }}</span>
                  <span class="opt-sub">{{ fmtState(r.state) }} · {{ fmtTime(r.startedAt) }}</span>
                </div>
              </el-option>
            </el-select>
          </div>
          <div class="pair-actions">
            <el-button :disabled="!canSwap" @click="swap">交换基线/候选</el-button>
            <el-button :disabled="!baselineId" @click="openRun(baselineId)">基线详情</el-button>
            <el-button :disabled="!candidateId" @click="openRun(candidateId)">候选详情</el-button>
            <el-button text @click="router.push('/eval/runs')">去实验列表选择</el-button>
          </div>
        </div>
        <div v-if="invalidQuery.length" class="query-note">
          链接中的 {{ invalidQuery.join('、') }} 未在已加载的实验列表中找到（可能已删除或不在本页加载范围内），请重新选择。
        </div>
      </template>
      <EmptyState v-else-if="runsState === 'forbidden'" kind="forbidden" />
      <EmptyState v-else-if="runsState === 'error'" kind="error" @retry="loadRuns" />
      <div v-else v-loading="true" class="loading-box" />
    </div>

    <!-- 未选齐：引导空态，不报错 -->
    <div v-if="runsState === 'ok' && !pairReady" class="card panel">
      <EmptyState
        kind="empty"
        :image-size="160"
        :description="guideText"
      >
        <el-button type="primary" @click="router.push('/eval/runs')">去实验列表选择两条实验</el-button>
      </EmptyState>
    </div>

    <!-- 已选齐：逐例对比骨架（对比投影依赖 EV-07 后端，全部诚实空态） -->
    <template v-else-if="runsState === 'ok' && pairReady">
      <div class="card panel">
        <div class="sum-grid">
          <div class="sum-item">
            <div class="sum-tag">基线</div>
            <div class="sum-name" :class="{ mono: !baselineRun.displayName }">{{ baselineRun.displayName ?? shortId(baselineRun.runId) }}</div>
            <div class="sum-sub">状态：{{ fmtState(baselineRun.state) }} · 数据集：{{ baselineRun.datasetVersion ?? '未统计' }}</div>
            <div class="sum-sub">模型：{{ baselineRun.model ?? '未统计' }} · Prompt：{{ baselineRun.promptVersion ?? '未统计' }}</div>
            <router-link class="sum-link" :to="`/eval/runs/${encodeURIComponent(baselineRun.runId)}`">查看基线详情</router-link>
          </div>
          <div class="sum-item">
            <div class="sum-tag">候选</div>
            <div class="sum-name" :class="{ mono: !candidateRun.displayName }">{{ candidateRun.displayName ?? shortId(candidateRun.runId) }}</div>
            <div class="sum-sub">状态：{{ fmtState(candidateRun.state) }} · 数据集：{{ candidateRun.datasetVersion ?? '未统计' }}</div>
            <div class="sum-sub">模型：{{ candidateRun.model ?? '未统计' }} · Prompt：{{ candidateRun.promptVersion ?? '未统计' }}</div>
            <router-link class="sum-link" :to="`/eval/runs/${encodeURIComponent(candidateRun.runId)}`">查看候选详情</router-link>
          </div>
        </div>
        <div class="cmp-note">可比性检查（数据集/输入快照/评分器与规则版本是否严格配对）依赖 EV-07 后端对比投影，本批未交付，前端不做推断。</div>
      </div>

      <div class="card panel">
        <div class="group-tabs">
          <button
            v-for="g in groups" :key="g.key"
            class="gt" :class="{ cur: group === g.key }"
            @click="group = g.key"
          >{{ g.label }} <span class="gt-count">—</span></button>
          <span class="gt-note">分组计数来源未就绪（依赖 EV-07 后端对比投影），显示“—”而不显示 0。</span>
        </div>
        <el-table :data="[]" class="cmp-table">
          <el-table-column label="场景" min-width="180" />
          <el-table-column label="基线判定" width="140" />
          <el-table-column label="候选判定" width="140" />
          <el-table-column label="差异说明" min-width="220" />
          <template #empty>
            <EmptyState
              kind="empty"
              :image-size="120"
              description="逐例对比投影依赖 EV-07 后端（按 case 身份配对、判定差异与差异说明），本批未交付；此处不展示任何推测数据。"
            />
          </template>
        </el-table>
      </div>
    </template>
  </div>
</template>

<script setup>
// 对比工作台（/eval/compare?baseline=…&candidate=…）：基线固定/选择 + 逐例对比骨架。
// 实验选项来自真实 GET /eval/runs；query 参数非法/缺失走引导空态不报错；
// 分组计数与逐例对比投影依赖 EV-07 后端，未就绪一律“—”/诚实空态，不伪造 0、不展示推测数据。
import { computed, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { api } from '../api/client'
import EmptyState from '../components/common/EmptyState.vue'
import { fmtTime } from '../utils/format'

const route = useRoute()
const router = useRouter()

const str = v => (typeof v === 'string' ? v : '')
const shortId = id => (id && id.length > 16 ? `${id.slice(0, 8)}…${id.slice(-4)}` : id ?? '—')
const fmtState = s => ({ RUNNING: '执行中', SUCCEEDED: '已结束', FAILED: '执行失败' }[s] ?? s ?? '未统计')
const runLabel = r => `${r.displayName ?? shortId(r.runId)}（${fmtState(r.state)}）`

const groups = [
  { key: 'improved', label: '改善' },
  { key: 'regressed', label: '退化' },
  { key: 'flat', label: '持平' },
]
const group = ref('improved')

const runs = ref([])
const runsState = ref('loading') // loading | ok | error | forbidden
const baselineId = ref(str(route.query.baseline))
const candidateId = ref(str(route.query.candidate))
// 从实验列表带入（query 含 baseline）时默认固定基线，便于连续换候选对比
const baselinePinned = ref(!!str(route.query.baseline))
const invalidQuery = ref([]) // query 中带来但列表里找不到的身份标签

let reqSeq = 0

async function loadRuns() {
  const seq = ++reqSeq
  runsState.value = 'loading'
  try {
    const d = await api('/eval/runs', { params: { limit: 50 } })
    if (seq !== reqSeq) return
    runs.value = d.items ?? []
    runsState.value = 'ok'
    validateQuery()
  } catch (e) {
    if (seq !== reqSeq) return
    runsState.value = e?.response?.status === 403 ? 'forbidden' : 'error'
  }
}

// query 合法性：非空但不在已加载列表 → 记入引导提示（不报错、不静默吞掉）
function validateQuery() {
  const ids = new Set(runs.value.map(r => r.runId))
  const bad = []
  if (baselineId.value && !ids.has(baselineId.value)) bad.push(`基线 ${shortId(baselineId.value)}`)
  if (candidateId.value && !ids.has(candidateId.value)) bad.push(`候选 ${shortId(candidateId.value)}`)
  invalidQuery.value = bad
  if (baselineId.value && !ids.has(baselineId.value)) baselineId.value = ''
  if (candidateId.value && !ids.has(candidateId.value)) candidateId.value = ''
}

const baselineRun = computed(() => runs.value.find(r => r.runId === baselineId.value) ?? null)
const candidateRun = computed(() => runs.value.find(r => r.runId === candidateId.value) ?? null)

// 同一实验不能同时作基线与候选：下拉互斥
const baselineOptions = computed(() => runs.value.filter(r => r.runId !== candidateId.value))
const candidateOptions = computed(() => runs.value.filter(r => r.runId !== baselineId.value))

const pairReady = computed(() => !!(baselineRun.value && candidateRun.value))
const canSwap = computed(() => pairReady.value && !baselinePinned.value)

const guideText = computed(() => {
  if (invalidQuery.value.length) return '链接中的对比参数不完整或已失效，请重新选择基线与候选实验。'
  if (baselineId.value || candidateId.value) return '还差一侧未选择：选齐基线与候选后进入对比。'
  return '先选择基线与候选实验：可从上方下拉选择，或从实验列表勾选两条后点“比较”带入。'
})

function syncQuery() {
  const query = {}
  if (baselineId.value) query.baseline = baselineId.value
  if (candidateId.value) query.candidate = candidateId.value
  router.replace({ query })
}

function onPick(which, v) {
  const val = v ?? ''
  if (which === 'baseline') baselineId.value = val
  else candidateId.value = val
  invalidQuery.value = []
  syncQuery()
}

function swap() {
  if (!canSwap.value) return
  const b = baselineId.value
  baselineId.value = candidateId.value
  candidateId.value = b
  syncQuery()
}

function openRun(id) {
  if (id) router.push(`/eval/runs/${encodeURIComponent(id)}`)
}

// 浏览器前进/后退：query 回灌选择（EU05）
watch(() => route.query, q => {
  const b = str(q.baseline)
  const c = str(q.candidate)
  if (b === baselineId.value && c === candidateId.value) return
  baselineId.value = b
  candidateId.value = c
  if (runsState.value === 'ok') validateQuery()
})

onMounted(loadRuns)
</script>

<style scoped>
.compare-page { display: flex; flex-direction: column; gap: var(--section-gap); }

.crumb { font-size: 13px; color: var(--ink-2); }
.crumb a { color: var(--brand); }
.crumb .sep { margin: 0 6px; color: var(--line-strong); }

.panel { padding: var(--card-pad); }
.loading-box { height: 200px; }

.pair { display: flex; gap: 24px; flex-wrap: wrap; align-items: flex-end; }
.pair-item { min-width: 300px; }
.pi-label { font-size: var(--fs-aux); color: var(--ink-2); margin-bottom: 6px; display: flex; align-items: center; gap: 12px; }
.pin { height: auto; }
.w-run { width: 320px; }
.pi-note { font-size: var(--fs-aux); color: var(--ink-2); margin-top: 6px; max-width: 320px; }
.pair-actions { display: flex; gap: 8px; align-items: center; flex-wrap: wrap; }
.query-note { font-size: var(--fs-aux); color: var(--warn, #b26a00); margin-top: 12px; }

.opt { display: flex; justify-content: space-between; gap: 16px; }
.opt-main { overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.opt-sub { font-size: var(--fs-aux); color: var(--ink-2); }
.mono { font-family: var(--mono, monospace); }

.sum-grid { display: grid; grid-template-columns: repeat(auto-fit, minmax(300px, 1fr)); gap: 16px; }
.sum-item { border: 1px solid var(--line); border-radius: var(--radius); padding: 12px 16px; }
.sum-tag { font-size: var(--fs-aux); color: var(--ink-2); margin-bottom: 4px; }
.sum-name { font-size: 15px; font-weight: 600; color: var(--head); word-break: break-all; }
.sum-sub { font-size: var(--fs-aux); color: var(--ink-2); margin-top: 4px; }
.sum-link { display: inline-block; font-size: var(--fs-aux); color: var(--brand); margin-top: 8px; }
.cmp-note { font-size: var(--fs-aux); color: var(--ink-2); margin-top: 12px; }

.group-tabs { display: flex; align-items: center; gap: 8px; flex-wrap: wrap; margin-bottom: 12px; }
.gt {
  border: 1px solid var(--line); background: var(--card); color: var(--ink-2);
  border-radius: 999px; padding: 0 14px; height: 30px; font-size: 13px; font-family: inherit;
  cursor: pointer; transition: border-color .15s, color .15s;
}
.gt:hover { border-color: var(--brand); color: var(--brand); }
.gt.cur { border-color: var(--brand); color: var(--brand); background: var(--brand-soft); font-weight: 600; }
.gt-count { font-family: var(--mono, monospace); margin-left: 2px; }
.gt-note { font-size: var(--fs-aux); color: var(--ink-2); }
.cmp-table { width: 100%; }
</style>
