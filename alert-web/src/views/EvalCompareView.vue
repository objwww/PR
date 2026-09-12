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

    <!-- 已选齐：对比投影（GET /api/eval/compare；接口未就绪保持诚实空态） -->
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

        <div class="ops-row">
          <el-button type="primary" :loading="saving" @click="saveComparison">保存本次对比</el-button>
          <span v-if="savedRecordId" class="ops-note">已落档：<span class="mono">{{ savedRecordId }}</span></span>
          <span v-else-if="cmp?.gateRecord" class="ops-note">
            最新落档：<span class="mono">{{ shortId(cmp.gateRecord.recordId) }}</span>
            （{{ gateText(cmp.gateRecord.outcome) }} · {{ fmtTime(cmp.gateRecord.createdAt) }}）
          </span>
          <span v-if="cmp?.asOf" class="ops-note">数据截至 {{ fmtClock(cmp.asOf) }}</span>
        </div>

        <div v-if="compareState === 'loading'" v-loading="true" class="loading-box" />
        <div v-else-if="compareState === 'undeployed'" class="cmp-note">
          对比投影接口不可用（403/404，可能为后端版本滞后或权限不足）：前端不做推断，保持诚实空态。
        </div>
        <EmptyState
          v-else-if="compareState === 'error'"
          kind="error"
          :description="compareError || '对比投影加载失败，请重试'"
          @retry="loadCompare()"
        />
        <template v-else-if="compareState === 'ok' && cmp">
          <!-- 可比性检查带：comparable=false 只展示差异清单，不出配对结论 -->
          <div v-if="cmp.comparability?.comparable" class="cmp-ok">
            可比性检查通过：数据集 / 输入快照 / 规则版本 / 词典 / 场景驱动 / 评分策略版本严格一致。
          </div>
          <div v-else class="cmp-mismatch">
            <div class="cmp-mismatch-title">可比性检查未通过：以下严格维度不一致，不出配对结论。</div>
            <div v-for="d in mismatchDims" :key="d.name" class="mm-row">
              <span class="mm-name">{{ dimZh(d.name) }}</span>
              <span class="mm-val">基线：{{ dimVal(d.baseline) }}</span>
              <span class="mm-val">候选：{{ dimVal(d.candidate) }}</span>
            </div>
          </div>

          <!-- 对比质量门：缺席如实“未统计” -->
          <div class="gate-row">
            <span class="gate-label">对比质量门</span>
            <template v-if="cmp.gate">
              <el-tag :type="gateConf(cmp.gate.outcome).type" disable-transitions>{{ gateConf(cmp.gate.outcome).text }}</el-tag>
              <span v-if="cmp.gate.reasons?.length" class="gate-reasons">
                {{ cmp.gate.reasons.map(reasonZh).join('；') }}
              </span>
              <span class="gate-meta">规则 {{ cmp.gate.ruleVersion }}</span>
            </template>
            <span v-else class="gate-meta">未统计</span>
          </div>
          <div v-if="cmp.scanTruncated" class="cmp-note">
            单侧案例扫描超上限，读面已截断：门结论如实转为 INCONCLUSIVE，部分数据不出资格结论。
          </div>
        </template>
      </div>

      <div class="card panel">
        <div class="group-tabs">
          <button
            v-for="g in groups" :key="g.key"
            class="gt" :class="{ cur: group === g.key }"
            @click="group = g.key"
          >{{ g.label }} <span class="gt-count">{{ tabCount(g.key) }}</span></button>
          <span v-if="compareState === 'undeployed'" class="gt-note">分组计数来源未就绪：对比接口不可用（403/404），显示“—”而不显示 0。</span>
          <span v-else-if="cmp?.summary" class="gt-note">
            配对 {{ cmp.summary.pairedCount }} 例 · 未配对 {{ cmp.summary.unpairedCount }} 例
          </span>
          <span v-else-if="group === 'REGRESSED' && compareState === 'ok'" class="gt-note">
            退化组即评审工作清单：逐例可发起人工标注（复用 EV-08 评审链，人工结论独立留档，不覆盖机器评分）。
          </span>
        </div>
        <el-table :data="caseRows" class="cmp-table" v-loading="compareState === 'loading'">
          <el-table-column label="场景" min-width="180">
            <template #default="{ row }">
              <span class="mono">{{ row.scenarioId }}</span>
              <span class="round">第 {{ row.roundNo }} 轮</span>
            </template>
          </el-table-column>
          <el-table-column label="基线判定" width="150">
            <template #default="{ row }"><VerdictCell :side="row.baseline" /></template>
          </el-table-column>
          <el-table-column label="候选判定" width="150">
            <template #default="{ row }"><VerdictCell :side="row.candidate" /></template>
          </el-table-column>
          <el-table-column label="差值" width="220">
            <template #default="{ row }"><DeltaCell :delta="row.delta" /></template>
          </el-table-column>
          <el-table-column label="差异说明" min-width="220">
            <template #default="{ row }">{{ row.differenceNote ?? '—' }}</template>
          </el-table-column>
          <el-table-column label="标注" width="96">
            <template #default="{ row }">
              <el-button
                v-if="row.candidate?.caseExecutionId"
                text type="primary" size="small"
                :loading="annotating === String(row.candidate.caseExecutionId)"
                @click="annotate(row)"
              >发起评审</el-button>
              <span v-else>—</span>
            </template>
          </el-table-column>
          <template #empty>
            <EmptyState
              v-if="compareState === 'undeployed'"
              kind="empty"
              :image-size="120"
              description="逐例对比投影接口不可用（403/404，可能为后端版本滞后或权限不足）；此处不展示任何推测数据。"
            />
            <EmptyState
              v-else-if="cmp && !cmp.comparability?.comparable"
              kind="empty"
              :image-size="120"
              description="可比性检查未通过，按契约不出配对结论与逐例对比。"
            />
            <EmptyState
              v-else
              kind="empty"
              :image-size="120"
              description="当前分组无配对案例。"
            />
          </template>
        </el-table>
        <div v-if="nextCursor" class="more-row">
          <el-button text type="primary" :loading="appending" @click="loadMore">加载更多</el-button>
        </div>

        <!-- 未配对案例：计数与原因如实展示（单列折叠区） -->
        <el-collapse v-if="cmp?.summary" class="unpaired">
          <el-collapse-item name="unpaired">
            <template #title>
              未配对案例（{{ cmp.summary.unpairedCount }}）
              <span v-if="cmp.unpairedTruncated" class="up-note">仅展示前 500 条，计数为全集</span>
            </template>
            <el-table :data="cmp.unpaired" size="small">
              <el-table-column label="场景" min-width="160">
                <template #default="{ row }">
                  <span class="mono">{{ row.scenarioId }}</span>
                  <span class="round">第 {{ row.roundNo }} 轮</span>
                </template>
              </el-table-column>
              <el-table-column label="侧" width="110">
                <template #default="{ row }">{{ sideZh(row.side) }}</template>
              </el-table-column>
              <el-table-column label="原因" min-width="160">
                <template #default="{ row }">{{ unpairedReasonZh(row.reason) }}</template>
              </el-table-column>
              <template #empty><span class="up-none">无未配对案例</span></template>
            </el-table>
          </el-collapse-item>
        </el-collapse>
      </div>
    </template>
  </div>
</template>

<script setup>
// 对比工作台（/eval/compare?baseline=…&candidate=…）：基线固定/选择 + EV-07 对比投影接线。
// 实验选项来自真实 GET /eval/runs；query 参数非法/缺失走引导空态不报错。
// GET /api/eval/compare：403/404 = 接口不可用（后端版本滞后/权限不足）→ 保持诚实空态（“—”/空表），不伪造对比数据；
// comparable=false 只展示差异清单不出配对结论；计数三件套 UNKNOWN→未统计 / NOT_APPLICABLE→不适用；
// R12 逐例差值：delta.score/cost/latency 三行，各带 改善/退化/持平/未知 徽章与方向 tooltip；
// 成本任一侧未定价/用量未知/跨币种 → delta=null 如实展示 costNote 机器码，不猜 0（R4 同律）；
// 标注：对候选侧案例发起 EV-08 评审任务（ensure 幂等，人工结论独立留档，不覆盖机器评分），跳评审页领取提交。
// 判定/维度/原因等机器码词表：未收录枚举原样透出（不虚构词表）。
import { computed, h, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, ElTag } from 'element-plus'
import { api } from '../api/client'
import EmptyState from '../components/common/EmptyState.vue'
import { fmtClock, fmtRatioStat, fmtTime } from '../utils/format'

const route = useRoute()
const router = useRouter()

const str = v => (typeof v === 'string' ? v : '')
const shortId = id => (id && id.length > 16 ? `${id.slice(0, 8)}…${id.slice(-4)}` : id ?? '—')
const fmtState = s => ({ RUNNING: '执行中', SUCCEEDED: '已结束', FAILED: '执行失败' }[s] ?? s ?? '未统计')
const runLabel = r => `${r.displayName ?? shortId(r.runId)}（${fmtState(r.state)}）`

// 判定词表（ScenarioMetrics.ScoringVerdict）：未收录枚举原样透出
const VERDICT_ZH = {
  DECIDABLE: '可判定',
  UNRESOLVED: '未决',
  STRUCTURE_REJECTED: '结构失败',
  TIMEOUT_OR_ABSENT: '超时/缺席',
}
const verdictZh = v => (v == null ? '未统计' : VERDICT_ZH[v] ?? v)

// 逐例单侧判定单元：判定徽章 + 根因命中面（rootCauseHit 仅 DECIDABLE 有意义，余不展示命中字样）
const VerdictCell = props => {
  const side = props.side
  if (!side) return '—'
  const tag = h(ElTag, {
    size: 'small',
    type: side.verdict === 'DECIDABLE' ? (side.rootCauseHit ? 'success' : 'danger') : 'info',
    disableTransitions: true,
  }, () => verdictZh(side.verdict))
  if (side.verdict !== 'DECIDABLE') return tag
  return h('span', [tag, h('span', { class: 'hit-sub' }, side.rootCauseHit ? '命中' : '未命中')])
}
VerdictCell.props = { side: { type: Object, default: null } }

// 差值分组徽章（服务端已按 direction 判好组；UNKNOWN = 该侧缺值如实未知，未收录原样透出）
const GROUP_CONF = {
  IMPROVED: { text: '改善', type: 'success' },
  REGRESSED: { text: '退化', type: 'danger' },
  FLAT: { text: '持平', type: 'info' },
  UNKNOWN: { text: '未知', type: 'warning' },
}
const groupConf = g => GROUP_CONF[g] ?? { text: g ?? '未统计', type: 'info' }
// costNote 机器码 → 中文（R4 同律：未定价/用量缺失不折算不为 0；未收录原样透出）
const COST_NOTE_ZH = {
  BOTH_COST_UNKNOWN: '双侧费用未知',
  BASELINE_COST_UNKNOWN: '基线侧费用未知',
  CANDIDATE_COST_UNKNOWN: '候选侧费用未知',
}
const signed = v => (v > 0 ? `+${v}` : String(v))
// 差值单元（R12）：分数=命中 0/1 差（恒可算）；成本/时延任一侧缺 → UNKNOWN + costNote/未统计
const DeltaCell = props => {
  const d = props.delta
  if (!d) return '—'
  const line = (label, md, unit, note) => {
    const conf = groupConf(md?.group)
    const num = md && md.delta != null
      ? h('span', {
          class: 'dv-num mono',
          title: md.direction === 'HIGHER_IS_BETTER' ? '该指标分数越高越好' : '该指标数值越低越好',
        }, `${signed(md.delta)}${unit}`)
      : h('span', { class: 'dv-unknown', title: note ?? '' }, (note && (COST_NOTE_ZH[note] ?? note)) ?? '未统计')
    return h('div', { class: 'dv-line' }, [
      h('span', { class: 'dv-label' }, label),
      num,
      h(ElTag, { size: 'small', type: conf.type, disableTransitions: true }, () => conf.text),
    ])
  }
  return h('div', { class: 'dv' }, [
    line('分数', d.score, ''),
    line('成本', d.cost, 'μ', d.costNote),
    line('时延', d.latency, 'ms'),
  ])
}
DeltaCell.props = { delta: { type: Object, default: null } }

// 可比性维度名 → 中文（维度表开放，未收录原样透出）
const DIM_ZH = {
  datasetVersion: '数据集版本',
  inputSnapshotDigest: '输入快照摘要',
  rulesVersionDigest: '规则版本摘要',
  lexiconVersion: '词典版本',
  scenarioDriverVersion: '场景驱动版本',
  selectionPolicyVersions: '评分策略版本集',
  graderVersion: '评分器版本',
  model: '模型',
  promptVersion: 'Prompt 版本',
  configDigest: '配置摘要',
}
const dimZh = n => DIM_ZH[n] ?? n
const dimVal = v => (v == null || v === '' ? '未统计' : (v.length > 28 ? `${v.slice(0, 14)}…${v.slice(-8)}` : v))

// 质量门结论徽章（PASS 绿 / FAIL 红；未收录原样透出）
const GATE_CONF = {
  PASS: { text: 'PASS', type: 'success' },
  FAIL: { text: 'FAIL', type: 'danger' },
  INCONCLUSIVE: { text: 'INCONCLUSIVE', type: 'warning' },
  NOT_EVALUABLE: { text: 'NOT_EVALUABLE', type: 'info' },
}
const gateConf = o => GATE_CONF[o] ?? { text: o ?? '未统计', type: 'info' }
const gateText = o => gateConf(o).text

// 门原因 / 未配对侧与原因机器码 → 中文（未收录原样透出）
const REASON_ZH = {
  COMPARABILITY_CHECK_FAILED: '可比性检查未通过',
  DATA_TRUNCATED: '读面数据截断',
  NO_PAIRED_CASES: '无配对案例',
  INSUFFICIENT_CLUSTERS: '独立簇不足',
  REGRESSION_RATE_EXCEEDED: '退化率超限',
  CI_LOWER_BELOW_MARGIN: '置信区间下界低于余量',
}
const reasonZh = r => REASON_ZH[r] ?? r
const SIDE_ZH = { BASELINE_ONLY: '仅基线有', CANDIDATE_ONLY: '仅候选有' }
const sideZh = s => SIDE_ZH[s] ?? s ?? '—'
const UNPAIRED_REASON_ZH = {
  MISSING_IN_BASELINE: '基线缺失',
  MISSING_IN_CANDIDATE: '候选缺失',
  INPUT_DIGEST_MISMATCH: '输入摘要不一致',
}
const unpairedReasonZh = r => UNPAIRED_REASON_ZH[r] ?? r

const groups = [
  { key: 'IMPROVED', label: '改善', stat: 'improved' },
  { key: 'REGRESSED', label: '退化', stat: 'regressed' },
  { key: 'FLAT', label: '持平', stat: 'flat' },
]
const group = ref('IMPROVED')

const runs = ref([])
const runsState = ref('loading') // loading | ok | error | forbidden
const baselineId = ref(str(route.query.baseline))
const candidateId = ref(str(route.query.candidate))
// 从实验列表带入（query 含 baseline）时默认固定基线，便于连续换候选对比
const baselinePinned = ref(!!str(route.query.baseline))
const invalidQuery = ref([]) // query 中带来但列表里找不到的身份标签

// EV-07 对比投影：idle | loading | ok | undeployed（403/404 = 接口不可用：版本滞后/权限不足）| error
const compareState = ref('idle')
const compareError = ref('')
const cmp = ref(null)          // 最近一次 GET /api/eval/compare 响应
const caseRows = ref([])       // 逐例行（group 过滤 + 游标累积）
const nextCursor = ref(null)
const appending = ref(false)
const saving = ref(false)
const savedRecordId = ref('')
const annotating = ref('') // 正在发起评审的 caseExecutionId（防连点）

let reqSeq = 0
let cmpSeq = 0

async function loadRuns() {
  const seq = ++reqSeq
  runsState.value = 'loading'
  try {
    const d = await api('/eval/runs', { params: { limit: 50 } })
    if (seq !== reqSeq) return
    runs.value = d.items ?? []
    runsState.value = 'ok'
    validateQuery()
    if (pairReady.value) loadCompare()
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

// comparable=false 的差异清单：mismatches 为维度名，值从 dimensions 取（含 null 如实“未统计”）
const mismatchDims = computed(() => {
  const c = cmp.value?.comparability
  if (!c) return []
  const byName = new Map((c.dimensions ?? []).map(d => [d.name, d]))
  return (c.mismatches ?? []).map(n => byName.get(n) ?? { name: n, baseline: null, candidate: null })
})

// 分组标签计数：三件套 OK → “分子（百分比）”；UNKNOWN/缺席 → 未统计；NOT_APPLICABLE → 不适用
function tabCount(key) {
  const stat = cmp.value?.summary?.[groups.find(g => g.key === key)?.stat]
  if (!stat || compareState.value !== 'ok') return '—'
  return stat.status === 'OK' && stat.numerator != null
    ? `${stat.numerator}（${fmtRatioStat(stat)}）`
    : fmtRatioStat(stat)
}

// EV-07 对比投影：两侧选齐后调用；append=true 走键集游标加载更多
async function loadCompare(append = false) {
  if (!pairReady.value) return
  const seq = ++cmpSeq
  if (!append) {
    compareState.value = 'loading'
    compareError.value = ''
    nextCursor.value = null
    savedRecordId.value = ''
  } else {
    appending.value = true
  }
  try {
    const params = {
      baseline: baselineId.value,
      candidate: candidateId.value,
      group: group.value,
      limit: 500,
    }
    if (append && nextCursor.value) params.cursor = nextCursor.value
    const d = await api('/eval/compare', { params })
    if (seq !== cmpSeq) return
    cmp.value = d
    caseRows.value = append ? caseRows.value.concat(d.cases ?? []) : (d.cases ?? [])
    nextCursor.value = d.nextCursor ?? null
    compareState.value = 'ok'
  } catch (e) {
    if (seq !== cmpSeq) return
    const st = e?.response?.status
    if (st === 403 || st === 404) {
      compareState.value = 'undeployed'
      cmp.value = null
      caseRows.value = []
    } else {
      compareState.value = 'error'
      compareError.value = e?.response?.data?.error ?? ''
    }
  } finally {
    if (seq === cmpSeq) appending.value = false
  }
}

function loadMore() {
  if (nextCursor.value && !appending.value) loadCompare(true)
}

// 落档：POST /api/eval/comparisons；未部署（403/404）显式提示，不假装成功
async function saveComparison() {
  if (!pairReady.value || saving.value) return
  saving.value = true
  try {
    const d = await api('/eval/comparisons', {
      method: 'POST',
      body: { baselineRunId: baselineId.value, candidateRunId: candidateId.value },
    })
    savedRecordId.value = d?.gateRecord?.recordId ?? ''
    if (cmp.value && d?.gateRecord) cmp.value = { ...cmp.value, gateRecord: d.gateRecord }
    ElMessage.success(savedRecordId.value ? `已落档：${savedRecordId.value}` : '已落档')
  } catch (e) {
    const st = e?.response?.status
    if (st === 403 || st === 404) {
      ElMessage.warning('落档接口不可用（403/404），请检查后端版本与权限')
    } else {
      ElMessage.error(e?.response?.data?.error || '落档失败，请重试')
    }
  } finally {
    saving.value = false
  }
}

// 标注（R12）：对候选侧案例生成 EV-08 评审任务（POST review-assignments，ensure 幂等：
// 重复生成 created=0），人工结论在评审页 claim/submit 独立留档，不覆盖机器评分
async function annotate(row) {
  const caseId = row.candidate?.caseExecutionId
  if (!caseId || annotating.value) return
  annotating.value = String(caseId)
  try {
    const d = await api(`/eval/runs/${encodeURIComponent(candidateId.value)}/review-assignments`, {
      method: 'POST',
      body: { caseExecutionIds: [caseId], assignmentsPerCase: 1 },
    })
    ElMessage.success(d?.created > 0
      ? `已生成评审任务 ${d.created} 份，请到评审页领取并提交结论`
      : '该案例已有评审任务，请到评审页领取')
    router.push({ path: '/eval/review', query: { runId: candidateId.value } })
  } catch (e) {
    const st = e?.response?.status
    if (st === 403 || st === 404) ElMessage.warning('评审任务接口不可用（403/404），请检查后端版本与权限')
    else ElMessage.error(e?.response?.data?.error || '评审任务生成失败，请重试')
  } finally {
    annotating.value = ''
  }
}

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

// 选择变化：重置旧投影并按新对重新加载（含交换基线/候选）
watch([baselineId, candidateId], () => {
  cmpSeq++
  compareState.value = 'idle'
  cmp.value = null
  caseRows.value = []
  nextCursor.value = null
  savedRecordId.value = ''
  if (pairReady.value) loadCompare()
})

// 分组切换：服务端 group 过滤重取逐例（计数仍来自 summary 全集）
watch(group, () => {
  if (pairReady.value && compareState.value === 'ok') loadCompare()
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

.ops-row { display: flex; align-items: center; gap: 12px; flex-wrap: wrap; margin-top: 16px; }
.ops-note { font-size: var(--fs-aux); color: var(--ink-2); }

.cmp-ok { margin-top: 12px; font-size: 13px; color: var(--ok, #2f9e44); }
.cmp-mismatch { margin-top: 12px; border: 1px solid var(--warn, #b26a00); border-radius: var(--radius); padding: 10px 14px; }
.cmp-mismatch-title { font-size: 13px; color: var(--warn, #b26a00); font-weight: 600; margin-bottom: 8px; }
.mm-row { display: flex; gap: 16px; flex-wrap: wrap; font-size: var(--fs-aux); padding: 4px 0; }
.mm-name { color: var(--head); font-weight: 600; min-width: 120px; }
.mm-val { color: var(--ink-2); word-break: break-all; }

.gate-row { display: flex; align-items: center; gap: 10px; flex-wrap: wrap; margin-top: 12px; }
.gate-label { font-size: var(--fs-aux); color: var(--ink-2); }
.gate-reasons { font-size: var(--fs-aux); color: var(--head); }
.gate-meta { font-size: var(--fs-aux); color: var(--ink-2); }

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
.round { font-size: var(--fs-aux); color: var(--ink-2); margin-left: 6px; }
:deep(.hit-sub) { font-size: var(--fs-aux); color: var(--ink-2); margin-left: 6px; }
:deep(.dv-line) { display: flex; align-items: center; gap: 8px; padding: 2px 0; font-size: var(--fs-aux); }
:deep(.dv-label) { color: var(--ink-2); flex: none; width: 26px; }
:deep(.dv-num) { color: var(--head); }
:deep(.dv-unknown) { color: var(--ink-2); }
.more-row { text-align: center; padding-top: 8px; }
.unpaired { margin-top: 16px; }
.up-note { font-size: var(--fs-aux); color: var(--warn, #b26a00); margin-left: 8px; }
.up-none { font-size: var(--fs-aux); color: var(--ink-2); }
</style>
