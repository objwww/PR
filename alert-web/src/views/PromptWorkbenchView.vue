<template>
  <div>
    <PageHeader
      title="Prompt 工作台"
      subtitle="提示词版本资产管理：投产状态来自模型调用账本（role_digest 真实匹配），生效能力版本来自当前激活配置包；版本不可变，发布走受控激活"
    />

    <!-- 统计行 -->
    <div class="stat-row">
      <div class="stat-card"><span class="stat-num">{{ fmtNum(summary.total) }}</span><span class="stat-label">PROMPT 版本总数</span></div>
      <div class="stat-card"><span class="stat-num">{{ fmtNum(summary.roles) }}</span><span class="stat-label">角色数</span></div>
      <div class="stat-card"><span class="stat-num">{{ fmtNum(summary.inProd) }}</span><span class="stat-label">有调用记录的版本</span></div>
      <div class="stat-card"><span class="stat-num">{{ activeLabel }}</span><span class="stat-label">当前激活配置包</span></div>
      <div class="stat-updated">数据更新至 {{ updatedAt }}</div>
    </div>

    <!-- 生效能力版本（当前激活包 plan 直出） -->
    <div class="card panel zone">
      <div class="zone-head">
        <h2 class="zone-title">生效能力版本（当前激活配置包）</h2>
        <el-tag v-if="activePlan?.revision != null" size="small" type="success" disable-transitions>
          配置包 #{{ activePlan.revision }} · 激活于 {{ fmtTime(activePlan.activatedAt) }}
        </el-tag>
        <span class="flex-spacer" />
        <router-link to="/versions"><el-button size="small" plain>前往版本中心执行受控发布</el-button></router-link>
        <router-link to="/eval/new"><el-button size="small" plain>前往评测中心发起对比实验</el-button></router-link>
      </div>
      <el-table v-if="activePlan?.tasks?.length" :data="activePlan.tasks" size="small">
        <el-table-column prop="taskKey" label="任务键" min-width="180" />
        <el-table-column prop="capability" label="能力" width="140" />
        <el-table-column prop="version" label="版本" width="100" />
        <el-table-column label="对位提示词版本" min-width="220">
          <template #default="{ row }">
            <template v-if="latestByRole(row.capability)">{{ latestByRole(row.capability).digest.slice(0, 8) }}（创建于 {{ fmtTime(latestByRole(row.capability).createdAt) }}）</template>
            <span v-else class="dim">该能力下暂无 PROMPT 资产——如实留空</span>
          </template>
        </el-table-column>
      </el-table>
      <EmptyState
        v-else
        kind="empty"
        description="当前激活配置包的编排计划（native.proposal.tasks）无任务条目或未装配——如实留空，不以模拟数据填充。"
      />
    </div>

    <!-- 工具注册面（真实注册表 /v1/prompt-workbench/tools 直出，不编造） -->
    <div class="card panel zone">
      <div class="zone-head">
        <h2 class="zone-title">工具注册面（真实注册表）</h2>
        <span class="dim">
          来源：后端 ToolRegistry 启动期装配清单；主 Agent 放行面
          {{ toolFace?.allowlist?.length ?? '—' }} 个 / 注册 {{ toolFace?.items?.length ?? '—' }} 个
        </span>
      </div>
      <el-table v-if="toolFace?.items?.length" :data="toolFace.items" size="small">
        <el-table-column prop="name" label="工具名" min-width="170" />
        <el-table-column label="用途" min-width="280">
          <template #default="{ row }"><span class="dim">{{ row.descriptionZh || row.name }}</span></template>
        </el-table-column>
        <el-table-column prop="version" label="版本" width="70" />
        <el-table-column label="风险等级" min-width="170">
          <template #default="{ row }">
            <el-tag size="small" :type="riskTone(row.risk)" disable-transitions>{{ row.risk }}</el-tag>
            <span class="dim"> {{ riskZh(row.risk) }}</span>
          </template>
        </el-table-column>
        <el-table-column label="真实执行" width="90" align="center">
          <template #default="{ row }">{{ row.executable ? '是' : '否' }}</template>
        </el-table-column>
        <el-table-column label="主 Agent 可用" width="110" align="center">
          <template #default="{ row }">{{ row.inPrimaryAllowlist ? '是' : '否' }}</template>
        </el-table-column>
        <el-table-column label="需审批" width="80" align="center">
          <template #default="{ row }">{{ row.approvalRequired ? '是' : '否' }}</template>
        </el-table-column>
      </el-table>
      <EmptyState
        v-else-if="toolFaceError"
        kind="error"
        description="工具注册面加载失败——如实留空，不以模拟清单填充。"
        @retry="loadTools"
      />
      <EmptyState
        v-else
        kind="empty"
        description="工具注册表未装配或暂无注册项——如实留空。"
      />
      <div v-if="toolFace?.items?.length" class="cmp-hint">
        R0/R1 只读工具可真实执行（「需审批」为否）；service.restart / service.rollback
        两个 R3 写类工具调用不直接执行——铸意图后自动进人工审批队列（需两名审批人），
        批准后进入执行计划（无 unlock 白名单行时为 dry_run 模拟执行，真执行是未开放的扩展点）。
        「主 Agent 可用=否」的工具已注册但未进 tool-allowlist，放行需经评测验证后修改配置生效。
      </div>
    </div>

    <!-- 版本指标对比（eval_run 终态行按 prompt_version 聚合，逐版本与上一版本对照） -->
    <div class="card panel zone">
      <div class="zone-head">
        <h2 class="zone-title">版本指标对比（每次改版本 vs 上一版本）</h2>
        <span class="dim">来源：评测实验账本终态行，按提示词版本聚合取最近一批；Δ = 与上一版本差值，绿色=改善，红色=退步</span>
      </div>
      <el-table v-if="versionMetrics.length" :data="versionMetrics" size="small">
        <el-table-column label="提示词版本" min-width="170">
          <template #default="{ row }">
            <div class="ver-name">{{ row.version }}</div>
            <div class="ver-sub">{{ row.runCount }} 批终态实验 · 最近 {{ fmtTime(row.latestAt) }}</div>
          </template>
        </el-table-column>
        <el-table-column label="模型 / 数据集" min-width="150">
          <template #default="{ row }">
            <div>{{ row.latest.model ?? '—' }}</div>
            <div class="ver-sub">{{ row.latest.datasetVersion ?? '—' }}</div>
            <div v-if="caliberNote(row)" class="ver-sub caliber-warn">⚠ {{ caliberNote(row) }}</div>
          </template>
        </el-table-column>
        <el-table-column v-for="m in METRICS" :key="m.key" :label="m.label" width="126" align="right">
          <template #default="{ row }">
            <div>{{ pct(row.latest[m.key]) }}</div>
            <div
              v-if="deltaOf(row, m.key) != null"
              class="ver-sub" :class="deltaCls(deltaOf(row, m.key), m.invert)"
            >
              {{ deltaOf(row, m.key) > 0 ? '▲' : '▼' }} {{ Math.abs(deltaOf(row, m.key) * 100).toFixed(1) }}pt
            </div>
          </template>
        </el-table-column>
        <el-table-column label="质量门 / 状态" width="130">
          <template #default="{ row }">
            <el-tag size="small" :type="row.latest.facets?.qualityVerdict === 'OK' ? 'success' : row.latest.facets?.qualityVerdict === 'VIOLATED' ? 'danger' : 'info'" disable-transitions>
              {{ qualityZh(row.latest.facets?.qualityVerdict) }}
            </el-tag>
            <div class="ver-sub">{{ row.latest.state === 'SUCCEEDED' ? '已完成' : '失败' }}</div>
          </template>
        </el-table-column>
        <el-table-column label="解读（人话）" min-width="300">
          <template #default="{ row }"><span class="dim">{{ versionInsight(row) }}</span></template>
        </el-table-column>
      </el-table>
      <EmptyState
        v-else
        kind="empty"
        description="暂无带提示词版本的终态实验——在评测中心发起实验后，此处自动出现相邻版本的指标对照。"
      />
    </div>

    <div class="wb-grid">
      <!-- 左：版本列表 -->
      <div class="card panel list-panel">
        <div class="zone-head">
          <h2 class="zone-title">版本列表</h2>
          <el-select v-model="roleFilter" size="small" clearable placeholder="全部角色" style="width: 140px" @change="loadAssets">
            <el-option v-for="r in roles" :key="r" :label="r" :value="r" />
          </el-select>
        </div>
        <el-table
          :data="assets" size="small" highlight-current-row
          @row-click="onRowClick"
        >
          <el-table-column label="版本" min-width="190">
            <template #default="{ row }">
              <div class="ver-name">
                {{ row.role }}@{{ row.roleVersion ?? '—' }}
                <el-tag v-if="isDeterministic(row)" size="small" type="info" effect="plain" disable-transitions>确定性执行器</el-tag>
              </div>
              <div class="ver-sub">{{ row.digest.slice(0, 8) }} · {{ fmtTime(row.createdAt) }}</div>
            </template>
          </el-table-column>
          <el-table-column label="投产状态" width="150">
            <template #default="{ row }">
              <el-tag size="small" :type="usageTone(row)" disable-transitions>{{ usageLabel(row) }}</el-tag>
              <div class="ver-sub">{{ row.calls > 0 ? `${fmtNum(row.calls)} 次调用` : '无调用记录' }}</div>
            </template>
          </el-table-column>
          <el-table-column label="对比" width="56" align="center">
            <template #default="{ row }">
              <input
                type="checkbox" class="cmp-box" :checked="diffBase?.digest === row.digest"
                @click.stop @change.stop="toggleBase(row)"
              />
            </template>
          </el-table-column>
        </el-table>
        <div class="cmp-hint">勾选一个版本作为对比基准（A），再在右侧查看它与当前选中版本（B）的逐行差异。</div>
        <div class="cmp-hint">
          metrics / logs / change 是<b>确定性单工具执行器</b>：按冻结窗固定查询、不调用模型，
          其"提示词"只是注册锚点，不存在可修改的提示词文本——因此修改/发布/diff 流程只对有
          模型提示词的主调查 Agent（primary）有意义。提示词修订随代码/配置发布：启动时按内容寻址
          登记为新版本，再经版本中心配置包受控激活后生效；本页提供版本列表、真实投产状态
          （模型调用账本 role_digest 匹配）、正文与逐行 diff。
        </div>
      </div>

      <!-- 右：正文查看 + 对比 -->
      <div class="card panel body-panel">
        <div class="zone-head">
          <h2 class="zone-title">{{ selected ? `正文 · ${selected.role}@${selected.roleVersion ?? '—'}` : '正文' }}</h2>
          <el-tag v-if="selected" size="small" type="info" disable-transitions>{{ selected.digest.slice(0, 8) }}</el-tag>
          <span class="flex-spacer" />
          <el-button v-if="selected" size="small" :loading="bodyLoading" @click="loadBody(selected)">加载全文</el-button>
          <el-button v-if="selected && diffBase" size="small" type="primary" @click="openDiff">
            与基准 {{ diffBase.digest.slice(0, 8) }} 对比
          </el-button>
        </div>
        <div v-if="variablesOf(selected).length" class="var-row">
          <el-tag v-for="v in variablesOf(selected)" :key="v" size="small" disable-transitions>{{ v }}</el-tag>
          <span class="dim">（variables_schema——模板可注入的变量）</span>
        </div>
        <pre v-if="selectedBody" class="body-pre">{{ selectedBody }}</pre>
        <div v-else-if="selected" class="dim" style="padding: 8px 0">
          摘要：{{ selected.templateHead }}……（正文 {{ fmtNum(selected.bodyLen) }} 字符，点「加载全文」查看）
        </div>
        <EmptyState
          v-else
          kind="empty"
          description="在左侧选择一个 PROMPT 版本查看正文；正文来自 release_asset 资产账本，只读不可改（版本不可变）。"
        />
      </div>
    </div>

    <!-- 试跑（Playground） -->
    <div class="card panel zone">
      <div class="zone-head">
        <h2 class="zone-title">试跑（Playground）</h2>
        <span class="dim">对指定告警事件发起一次真实调查——使用当前生效配置包运行；本页不提供“用历史版本试跑”（需要构造临时包，待受控发布链路评审后开放）</span>
      </div>
      <div class="run-row">
        <el-select
          v-model="runIncident" filterable clearable size="small" style="flex: 0 0 360px"
          placeholder="选择要试跑的告警事件" :loading="incidentsLoading"
        >
          <el-option
            v-for="it in incidents" :key="it.incidentId"
            :label="`${it.alertname ?? it.incidentKey}（${it.service ?? '—'}）`"
            :value="it.incidentId"
          />
        </el-select>
        <el-button size="small" type="primary" :disabled="!runIncident" :loading="runLoading" @click="runInvestigate">发起调查</el-button>
        <span v-if="runMessage" class="run-msg">{{ runMessage }}</span>
      </div>
    </div>

    <!-- 版本对比弹窗 -->
    <el-dialog v-model="showDiff" title="版本逐行对比（行级差异，绿色=基准无/当前有，红色=基准有/当前无）" width="960px">
      <template v-if="diffBase && selected && selectedBody">
        <div class="diff-head">
          <span>基准 A：{{ diffBase.role }}@{{ diffBase.roleVersion }}（{{ diffBase.digest.slice(0, 8) }}）</span>
          <span>当前 B：{{ selected.role }}@{{ selected.roleVersion }}（{{ selected.digest.slice(0, 8) }}）</span>
        </div>
        <pre class="diff-pre"><span v-for="(l, i) in diffLines" :key="i" :class="'d-' + l.t">{{ l.mark }} {{ l.text }}
</span></pre>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
// 3.16 Prompt 工作台（前端产品化 Wave 5）：
//   版本列表/投产状态 = release_asset(PROMPT) × rca_model_call(role_digest 聚合)；
//   生效能力版本 = config_bundle_active × config_bundle(native.proposal.tasks)；
//   正文 = /api/release-assets/PROMPT/{digest}（既有明细端点）；
//   试跑 = 既有重查路径 POST /v1/incidents/{id}/reinvestigate（用当前生效配置）；
//   发布 = 跳转版本中心受控激活；评测联动 = 跳转评测中心。版本不可变，本页只读不编辑。
import { computed, onMounted, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { api } from '../api/client'
import { fmtTime } from '../utils/format'
import { ERROR_CODE_ZH } from '../dict/zh'
import PageHeader from '../components/common/PageHeader.vue'
import EmptyState from '../components/common/EmptyState.vue'

const assets = ref([])
const roles = ref([])
const roleFilter = ref(null)
const selected = ref(null)
const diffBase = ref(null)
const showDiff = ref(false)
const selectedBody = ref('')
const bodyLoading = ref(false)
const activePlan = ref(null)
const incidents = ref([])
const incidentsLoading = ref(false)
const runIncident = ref(null)
const runLoading = ref(false)
const runMessage = ref('')
const updatedAt = ref('—')
const evalRuns = ref([])
const toolFace = ref(null)
const toolFaceError = ref(false)

// 工具风险等级中文字典（ToolRisk 枚举口径）
const RISK_ZH = {
  R0: '只读·无副作用',
  R1: '敏感读·无副作用',
  R2: '写操作·仅记录不执行',
  R3: '危险·需人工审批',
}
function riskZh(risk) { return RISK_ZH[risk] ?? risk }
function riskTone(risk) {
  return ({ R0: 'success', R1: 'warning', R2: 'danger', R3: 'danger' })[risk] ?? 'info'
}
async function loadTools() {
  toolFaceError.value = false
  try {
    const res = await api('/v1/prompt-workbench/tools')
    if (res?.status === 'OK') {
      toolFace.value = res
    } else {
      toolFace.value = null
      toolFaceError.value = true
    }
  } catch {
    toolFace.value = null
    toolFaceError.value = true
  }
}

// 确定性单工具执行器（metrics/logs/change）：固定查询不调模型，登记的"提示词"
// 只是 native-* 注册锚点——据实打标，避免被当成可编辑的模型提示词
const isDeterministic = (row) =>
  ['metrics', 'logs', 'change'].includes(row?.role) && /^native-/.test(row?.templateHead ?? '')

// 版本指标对比：按 prompt_version 聚合终态实验，取每版本最近一批为代表行
const METRICS = [
  { key: 'f1', label: 'F1' },
  { key: 'precision', label: '精确率' },
  { key: 'recall', label: '召回率' },
  { key: 'endToEndHitRate', label: '端到端命中率' },
  { key: 'conditionalAccuracy', label: '条件准确率' },
  { key: 'unresolvedRate', label: '未决率', invert: true },
]
const versionMetrics = computed(() => {
  const byVer = new Map()
  for (const r of evalRuns.value) {
    if (!r.promptVersion) continue
    if (!byVer.has(r.promptVersion)) byVer.set(r.promptVersion, [])
    byVer.get(r.promptVersion).push(r)
  }
  const rows = []
  for (const [version, runs] of byVer) {
    const terminal = runs.filter(r => r.state === 'SUCCEEDED' || r.state === 'FAILED')
    if (!terminal.length) continue
    terminal.sort((a, b) => Date.parse(b.startedAt) - Date.parse(a.startedAt))
    rows.push({ version, runCount: terminal.length, latestAt: terminal[0].startedAt, latest: terminal[0] })
  }
  rows.sort((a, b) => Date.parse(b.latestAt) - Date.parse(a.latestAt))
  rows.forEach((row, i) => { row.prev = rows[i + 1]?.latest ?? null })
  return rows
})
function pct(v) { return v == null ? '—' : (v * 100).toFixed(1) + '%' }
function deltaOf(row, key) {
  const cur = row.latest?.[key]
  const prev = row.prev?.[key]
  if (cur == null || prev == null) return null
  return cur - prev
}
function deltaCls(d, invert) {
  if (d == null || Math.abs(d) < 1e-9) return 'delta-flat'
  const good = invert ? d < 0 : d > 0
  return good ? 'delta-up' : 'delta-down'
}
function qualityZh(v) {
  return ({ OK: '通过', VIOLATED: '未通过', UNKNOWN: '无裁决' })[v] ?? '无裁决'
}

// BA-176 版本解读：可比性提示 + 进步/退步原因人话文案。
// 数据全部来自 /eval/runs 列表项真实字段（model/datasetVersion/endToEndHitRate/
// tp/fp/fn/modelCallFailures/modelCallFailureCode），失败码中文名读 ERROR_CODE_ZH
// 共享字典——缺什么说什么，不估算不编造。
function failureShortZh(code) {
  const hit = ERROR_CODE_ZH[code]
  return hit ? hit[0] : (code || '未分类')
}

/** 可比性提示：相邻版本的模型或数据集换了 → Δ 非同口径，仅供参考 */
function caliberNote(row) {
  const p = row.prev
  if (!p) return ''
  const changed = []
  if ((row.latest.model ?? null) !== (p.model ?? null)) changed.push('模型')
  if ((row.latest.datasetVersion ?? null) !== (p.datasetVersion ?? null)) changed.push('数据集')
  return changed.length ? `${changed.join('与')}已更换，与上一版本非同口径，Δ 仅供参考` : ''
}

/** 版本行解读（人话）：欠费/限流窗的低分与真实判错必须区分开 */
function versionInsight(row) {
  const cur = row.latest
  if (cur.state === 'FAILED') {
    return '本批实验执行失败（未跑到终态评分），指标不反映提示词效果——请先在评测中心查看该批的失败原因'
  }
  const parts = []
  const failed = cur.modelCallFailures ?? 0
  const e2e = cur.endToEndHitRate
  const prevE2e = row.prev?.endToEndHitRate
  if (failed > 0) {
    parts.push(`本批有 ${failed} 次模型调用被拒（主因：${failureShortZh(cur.modelCallFailureCode)}）`
      + `——调查在模型不可用下按设计诚实降级为未决，命中率 ${pct(e2e)} 主要反映供应商可用性，不代表提示词退步`)
  }
  if (row.prev && e2e != null && prevE2e != null) {
    const d = (e2e - prevE2e) * 100
    if (Math.abs(d) < 0.05) {
      parts.push(`与上一版本基本持平（端到端命中率 ${pct(prevE2e)}→${pct(e2e)}）`)
    } else if (d > 0) {
      parts.push(`较上一版本进步（端到端命中率 ${pct(prevE2e)}→${pct(e2e)}）`)
    } else if (failed === 0) {
      parts.push(`较上一版本退步（端到端命中率 ${pct(prevE2e)}→${pct(e2e)}）：`
        + `本批症状判定漏报 ${cur.fn ?? '—'} 案、误报 ${cur.fp ?? '—'} 案，详见评测中心逐案账本`)
    }
  }
  if (!parts.length) {
    if (e2e == null) return '本批指标未回填（案例未全部结清），暂无终态数据可解读'
    parts.push(`本批端到端命中率 ${pct(e2e)}，症状判定 tp=${cur.tp ?? '—'}、fp=${cur.fp ?? '—'}、fn=${cur.fn ?? '—'}`)
  }
  return parts.join('；')
}
async function loadEvalRuns() {
  try {
    const res = await api('/eval/runs', { params: { limit: 200 } })
    evalRuns.value = res?.items ?? []
  } catch { /* 实验面缺席如实留空 */ }
}

const summary = computed(() => {
  const inProd = assets.value.filter(a => a.calls > 0).length
  return { total: assets.value.length, roles: roles.value.length, inProd }
})
const activeLabel = computed(() =>
  activePlan.value?.revision != null ? `#${activePlan.value.revision}` : '—')

function fmtNum(n) { return n == null ? '—' : Number(n).toLocaleString('zh-CN') }
function usageLabel(row) {
  if (!row.calls) return '未投产'
  const days = row.lastUsedAt ? (Date.now() - Date.parse(row.lastUsedAt)) / 86400000 : Infinity
  return days <= 7 ? '投产中（近 7 天有调用）' : '已投产（历史有调用）'
}
function usageTone(row) {
  if (!row.calls) return 'info'
  const days = row.lastUsedAt ? (Date.now() - Date.parse(row.lastUsedAt)) / 86400000 : Infinity
  return days <= 7 ? 'success' : 'warning'
}
function variablesOf(row) {
  return Array.isArray(row?.variablesSchema) ? row.variablesSchema : []
}
function onRowClick(row) { selected.value = row; selectedBody.value = '' }
function latestByRole(capability) {
  const list = assets.value.filter(a => a.role === capability)
  return list.length ? list[0] : null
}

async function loadAssets() {
  try {
    const res = await api('/v1/prompt-workbench/assets', {
      params: roleFilter.value ? { role: roleFilter.value } : {},
    })
    if (res?.status === 'OK') {
      assets.value = res.items ?? []
      roles.value = res.roles ?? []
      if (selected.value && !assets.value.some(a => a.digest === selected.value?.digest)) {
        selected.value = null; selectedBody.value = ''
      }
    }
  } catch { ElMessage.error('Prompt 版本列表加载失败') }
}
async function loadActivePlan() {
  try {
    const res = await api('/v1/prompt-workbench/active-plan')
    if (res?.status === 'OK') activePlan.value = res
  } catch { /* 生效面缺席如实留空 */ }
}
async function loadBody(row) {
  bodyLoading.value = true
  try {
    const res = await api(`/release-assets/PROMPT/${row.digest}`)
    selectedBody.value = typeof res?.content?.messages_template === 'string'
      ? res.content.messages_template : JSON.stringify(res?.content ?? {}, null, 2)
  } catch { ElMessage.error('正文加载失败') } finally { bodyLoading.value = false }
}
function toggleBase(row) {
  diffBase.value = diffBase.value?.digest === row.digest ? null : row
  if (!diffBase.value) showDiff.value = false
}
// 行级 LCS diff（两段模板全文，行粒度；版本不可变，纯前端只读对比；对比前先确保双方全文已加载）
const diffLines = computed(() => {
  if (!diffBase.value || !selected.value) return []
  const a = String(diffBase.value.__body ?? diffBase.value.templateHead).split('\n')
  const b = String(selected.value.__body ?? selected.value.templateHead).split('\n')
  const n = a.length, m = b.length
  const dp = Array.from({ length: n + 1 }, () => new Array(m + 1).fill(0))
  for (let i = n - 1; i >= 0; i--) {
    for (let j = m - 1; j >= 0; j--) {
      dp[i][j] = a[i] === b[j] ? dp[i + 1][j + 1] + 1 : Math.max(dp[i + 1][j], dp[i][j + 1])
    }
  }
  const out = []
  let i = 0, j = 0
  while (i < n && j < m) {
    if (a[i] === b[j]) { out.push({ t: 'ctx', mark: ' ', text: a[i] }); i++; j++ }
    else if (dp[i + 1][j] >= dp[i][j + 1]) { out.push({ t: 'del', mark: '-', text: a[i] }); i++ }
    else { out.push({ t: 'add', mark: '+', text: b[j] }); j++ }
  }
  while (i < n) { out.push({ t: 'del', mark: '-', text: a[i] }); i++ }
  while (j < m) { out.push({ t: 'add', mark: '+', text: b[j] }); j++ }
  return out
})
async function ensureBody(row) {
  if (row.__body) return
  try {
    const res = await api(`/release-assets/PROMPT/${row.digest}`)
    row.__body = typeof res?.content?.messages_template === 'string'
      ? res.content.messages_template : JSON.stringify(res?.content ?? {})
  } catch { row.__body = '' }
}
async function openDiff() {
  if (!diffBase.value || !selected.value) return
  await Promise.all([ensureBody(diffBase.value), ensureBody(selected.value)])
  showDiff.value = true
}

async function loadIncidents() {
  incidentsLoading.value = true
  try {
    const res = await api('/v1/incidents', { params: { limit: 50 } })
    incidents.value = res?.items ?? []
  } catch { /* 事件面缺席如实留空 */ } finally { incidentsLoading.value = false }
}
async function runInvestigate() {
  runLoading.value = true
  runMessage.value = ''
  try {
    const res = await api(`/v1/incidents/${runIncident.value}/reinvestigate`, { method: 'POST' })
    runMessage.value = res && !res.error
      ? '已受理——调查以当前生效配置包运行，可在「调查」页跟踪进度'
      : `暂不能试跑：${res?.error ?? '已有进行中的调查，或该告警未在路由放量名单'}`
  } catch (e) {
    runMessage.value = e?.response?.status === 409
      ? '暂不能试跑：已有进行中的调查，或该告警未在路由放量名单'
      : '试跑发起失败（网络或服务不可达）'
  } finally { runLoading.value = false }
}

onMounted(() => { loadAssets(); loadActivePlan(); loadTools(); loadIncidents(); loadEvalRuns(); updatedAt.value = new Date().toLocaleString('zh-CN', { hour12: false }) })
</script>

<script>
export default { name: 'PromptWorkbenchView' }
</script>

<style scoped>
.stat-row { display: flex; gap: 16px; align-items: center; flex-wrap: wrap; margin-bottom: var(--section-gap); }
.stat-card {
  flex: 0 0 170px; background: var(--card); border: 1px solid var(--line); border-radius: var(--radius);
  padding: 12px 16px; display: flex; flex-direction: column; gap: 2px; box-shadow: var(--shadow);
}
.stat-num { font-size: 22px; font-weight: 700; color: var(--ink); }
.stat-label { font-size: var(--fs-aux); color: var(--ink-2); }
.stat-updated { margin-left: auto; font-size: var(--fs-aux); color: var(--ink-2); }

.zone { margin-bottom: var(--section-gap); }
.zone-head { display: flex; align-items: center; gap: 10px; flex-wrap: wrap; margin-bottom: 10px; }
.zone-title { font-size: var(--fs-section); }
.flex-spacer { flex: 1 1 auto; }
.dim { color: var(--ink-2); font-size: var(--fs-aux); }

.wb-grid { display: grid; grid-template-columns: 460px minmax(0, 1fr); gap: var(--section-gap); align-items: start; margin-bottom: var(--section-gap); }
.ver-name { font-weight: 600; color: var(--ink); }
.ver-sub { font-size: var(--fs-aux); color: var(--ink-2); }
.caliber-warn { color: var(--el-color-warning); }
.cmp-box { cursor: pointer; }
.cmp-hint { margin-top: 8px; font-size: var(--fs-aux); color: var(--ink-2); }

.var-row { display: flex; gap: 6px; flex-wrap: wrap; margin-bottom: 8px; }
.body-pre {
  max-height: 420px; overflow: auto; background: #f8f9fb; border: 1px solid var(--line);
  border-radius: var(--radius); padding: 12px; font-size: var(--fs-aux); line-height: 1.7;
  white-space: pre-wrap; word-break: break-word; color: var(--ink);
}
.run-row { display: flex; gap: 10px; align-items: center; flex-wrap: wrap; }
.run-msg { font-size: var(--fs-aux); color: var(--ink-2); }

.diff-head { display: flex; gap: 24px; margin-bottom: 8px; font-size: var(--fs-aux); color: var(--ink-2); }
.diff-pre {
  max-height: 520px; overflow: auto; background: #f8f9fb; border: 1px solid var(--line);
  border-radius: var(--radius); padding: 12px; font-size: var(--fs-aux); line-height: 1.6;
  white-space: pre-wrap; word-break: break-word;
}
.d-add { background: var(--ok-bg); color: #1a7a34; display: block; }
.d-del { background: var(--bad-bg); color: #b3261e; display: block; text-decoration: line-through; }
.d-ctx { color: var(--ink); display: block; }
.delta-up { color: #1a7a34; font-weight: 600; }
.delta-down { color: #b3261e; font-weight: 600; }
.delta-flat { color: var(--ink-2); }
</style>
