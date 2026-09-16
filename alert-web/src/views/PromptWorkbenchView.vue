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
              <div class="ver-name">{{ row.role }}@{{ row.roleVersion ?? '—' }}</div>
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

onMounted(() => { loadAssets(); loadActivePlan(); loadIncidents(); updatedAt.value = new Date().toLocaleString('zh-CN', { hour12: false }) })
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
</style>
