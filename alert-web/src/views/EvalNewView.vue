<template>
  <div class="new-page">
    <div class="crumb">
      <router-link to="/eval/runs">实验</router-link>
      <span class="sep">/</span>
      <b>新建实验</b>
    </div>

    <div class="card panel">
      <!-- 四步向导：模式 → 基线与候选 → 数据集/重复次数 → 预算/并发/截止时间；每步可点题头回看 -->
      <el-steps :active="step" align-center class="steps">
        <el-step
          v-for="(s, i) in stepTitles" :key="s" :title="s"
          class="step-clickable" @click="step = i"
        />
      </el-steps>

      <!-- 第一步：模式（能力读面禁用未实现模式）+ 实验名称（契约必填） -->
      <div v-if="step === 0" class="step-body">
        <el-alert v-if="capabilityState === 'error'" type="warning" :closable="false" show-icon class="cap-alert"
          title="能力面加载失败（GET /eval/launch-capability）：无法确认当前支持范围，暂不可提交。"
        >
          <el-button size="small" text type="primary" @click="loadCapability">重试</el-button>
        </el-alert>
        <el-form label-width="120px" class="form">
          <el-form-item label="实验名称" required>
            <el-input v-model="form.displayName" placeholder="例如：qwen3-max-preview 基线回归" maxlength="128" show-word-limit />
          </el-form-item>
        </el-form>
        <div
          v-for="m in modes" :key="m.key"
          class="mode-card" :class="{ cur: form.mode === m.key, disabled: !modeSupported(m.key) }"
          @click="pickMode(m.key)"
        >
          <div class="mode-head">
            <b>模式 {{ m.key }} · {{ m.name }}</b>
            <el-tag v-if="!modeSupported(m.key)" type="info" size="small" class="mode-tag" disable-transitions>当前环境未开放</el-tag>
          </div>
          <div class="mode-desc">{{ m.desc }}</div>
          <div class="mode-side">{{ m.side }}</div>
          <div v-if="!modeSupported(m.key)" class="mode-block">{{ capability?.modes?.length ? `当前环境仅支持：${capability.modes.join(' / ')}` : '支持范围以服务端为准' }}</div>
        </div>
      </div>

      <!-- 第二步：被测版本（能力面未开放覆盖时锁定，沿用 worker 部署版本） -->
      <div v-else-if="step === 1" class="step-body">
        <el-form label-width="120px" class="form">
          <el-form-item label="模型">
            <el-input v-model="form.model" :disabled="!capability?.modelOverride" placeholder="留空 = 沿用 worker 部署模型（digest 面落 configDigest）" />
            <div v-if="!capability?.modelOverride" class="field-note">当前环境不支持自定义模型（覆盖项不会切换实际执行内容，提交将被拒绝）；固定沿用部署模型。</div>
          </el-form-item>
          <el-form-item label="Prompt 版本">
            <el-input v-model="form.promptVersion" :disabled="!capability?.promptOverride" placeholder="留空 = 沿用 worker 部署版本" />
            <div v-if="!capability?.promptOverride" class="field-note">当前环境不支持自定义 Prompt 版本；固定沿用部署版本。</div>
          </el-form-item>
        </el-form>
        <div class="hint">
          后端契约（POST /api/eval/runs）只接受 model / promptVersion 两个版本面；
          基线对比不在此处指定——实验跑完后到对比工作台（/eval/compare）固定基线逐例比较。
          默认一次只变一个因素；多因素变更只能作组合验收，不能声称识别单因素收益。
        </div>
      </div>

      <!-- 第三步：数据集（仅部署版本可选）/ 重复次数 -->
      <div v-else-if="step === 2" class="step-body">
        <el-form label-width="120px" class="form">
          <el-form-item label="数据集版本">
            <el-select v-model="form.datasetVersion" placeholder="选择数据集版本" :loading="datasetsLoading" class="w-full">
              <el-option v-for="d in selectableDatasets" :key="d.version" :value="d.version" :label="`${d.version}（${d.caseCount} 案例）`" />
            </el-select>
            <div v-if="datasetsError" class="field-note">
              数据集列表加载失败（真实接口 GET /eval/datasets）。
              <el-button size="small" text type="primary" @click="loadDatasets">重试</el-button>
            </div>
            <div v-else-if="!datasetsLoading && !datasets.length" class="field-note">
              数据集接口（GET /eval/datasets）返回空列表，暂无可选版本；数据集版本为契约必填项，无数据集时无法提交，此处不提供静态兜底选项。
            </div>
            <div v-else-if="capability && !capability.datasetVersions.includes(form.datasetVersion) && form.datasetVersion" class="field-note">
              所选版本不是当前部署版本，提交将被拒绝；仅 {{ capability.datasetVersions.join('、') }} 为部署版本。
            </div>
          </el-form-item>
          <el-form-item label="重复次数">
            <el-input-number v-model="form.repeat" :min="1" :max="capability?.maxRoundsPerScenario ?? 10" />
          </el-form-item>
        </el-form>
        <div class="hint">
          案例切片、分层与固定运行顺序策略依赖后端 EV-04 实验计划，本批未开放；
          HOLDOUT 盲测集不向普通操作者页面开放浏览。
        </div>
      </div>

      <!-- 第四步：预算/并发/截止（能力面未接线的字段锁定——不接受"填了但不生效"的假约束） -->
      <div v-else class="step-body">
        <el-form label-width="120px" class="form">
          <el-form-item label="预算（tokens）">
            <el-input-number v-model="form.budgetMaxTokens" :min="1" :max="100000000"
              :disabled="!capability?.budgetMaxTokens" placeholder="本期不支持预算约束" class="w-num" />
            <div class="field-note">{{ capability?.budgetMaxTokens ? '预算将在执行面强制。' : '当前环境不支持执行面预算限制（填了也不会生效，提交将被拒绝），输入已锁定。' }}</div>
          </el-form-item>
          <el-form-item label="并发">
            <el-input-number v-model="form.maxConcurrency" :min="1" :max="capability?.maxConcurrency ?? 1"
              :disabled="(capability?.maxConcurrency ?? 1) <= 1" class="w-num" />
            <div class="field-note">当前环境固定并发 {{ capability?.maxConcurrency ?? 1 }}（多 worker 并发未开放）。</div>
          </el-form-item>
          <el-form-item label="截止时间">
            <el-date-picker v-model="form.deadline" type="datetime" :disabled="!capability?.deadlineSeconds" placeholder="本期不支持截止时间" />
            <div class="field-note">{{ capability?.deadlineSeconds ? '提交时换算为相对秒数（deadlineSeconds）。' : '当前环境不支持执行截止强制（填了也不会生效，提交将被拒绝），输入已锁定。' }}</div>
          </el-form-item>
        </el-form>
        <div class="hint">
          前置检查（依赖部署、schema 兼容、费用额度）归 worker 领取命令后的执行面；
          费用预估数据不足时显示未知，不承诺固定耗时。
        </div>
      </div>

      <div class="step-actions">
        <el-button :disabled="step === 0" @click="step--">上一步</el-button>
        <el-button v-if="step < 3" type="primary" @click="step++">下一步</el-button>
        <el-button
          v-else
          type="primary"
          :loading="submitting"
          :disabled="!canSubmit"
          @click="submit"
        >{{ submitting ? '提交中…' : (pendingIntent ? '重试提交（同键同参）' : '提交并启动') }}</el-button>
        <el-button v-if="pendingIntent && !submitting" text type="danger" @click="discardIntent">放弃上次提交意图</el-button>
      </div>
      <div v-if="step === 3 && !canSubmit" class="hint submit-hint">
        {{ submitBlockedReason }}
      </div>
      <el-alert v-if="pendingIntent" type="warning" :closable="false" show-icon class="cap-alert"
        :title="`上一次提交尚未确认受理（幂等键 ${pendingIntent.key.slice(0, 8)}…）：点「重试提交」原样重发；改表单后提交将确认新建实验。`"
      />
    </div>
  </div>
</template>

<script setup>
// 新建实验（/eval/new）：四步向导，配置每步可回看；提交接真实端点 POST /api/eval/runs（EV-04 已交付）。
// PAGE-03：模式/覆盖项/限额从能力读面（GET /eval/launch-capability）取支持范围——
// 未开放项禁用并注明原因；能力面失败 = 无法确认支持范围，fail-closed 禁提交（服务端仍会拒绝）。
// PAGE-04：提交意图冻结——首次提交生成随机幂等键并冻结完整 body（sessionStorage 持久），
// 超时/失败重试同键同参原样重发；修改表单后再提交须确认"新建实验"，明确新建才生成新意图。
import { computed, onMounted, reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { api } from '../api/client'

const router = useRouter()

const stepTitles = ['选择模式', '被测版本', '数据集与重复次数', '预算与限额']
const step = ref(0)

const modes = [
  {
    key: 'E', name: '确定性全链测试',
    desc: '以确定性执行器重放冻结调用链，校验编排、工具调用与输出结构。',
    side: '不触发真实模型，无现场副作用；Replay 不属于真实端到端。',
  },
  {
    key: 'B', name: '冻结输入的质量对照',
    desc: '对冻结数据集输入调用真实模型并评分，与基线做同输入对照。',
    side: '触发真实模型调用并产生费用；不触碰靶场与生产现场。',
  },
  {
    key: 'L', name: '真实模型与靶场',
    desc: '真实模型 + 故障靶场注入的端到端实验，复用 drill 编排与环境互斥。',
    side: '有现场副作用，需在另行授权窗口执行；取消时转入恢复流程。',
  },
]

const form = reactive({
  displayName: '',
  mode: 'L',
  model: '',
  promptVersion: '',
  datasetVersion: '',
  repeat: 1,
  budgetMaxTokens: null,
  maxConcurrency: 1,
  deadline: null,
})

// ---------------------------------------------------------------- 能力读面（PAGE-03）

const capability = ref(null)
const capabilityState = ref('loading') // loading | ok | error

async function loadCapability() {
  capabilityState.value = 'loading'
  try {
    capability.value = await api('/eval/launch-capability')
    capabilityState.value = 'ok'
    if (!modeSupported(form.mode) && capability.value?.modes?.length) {
      form.mode = capability.value.modes[0]
    }
  } catch {
    capability.value = null
    capabilityState.value = 'error'
  }
}

const modeSupported = key => !!capability.value?.modes?.includes(key)

function pickMode(key) {
  if (!modeSupported(key)) return
  form.mode = key
}

// 数据集下拉仅列部署版本（动态版本解析未开放；其余版本提交会被服务端拒绝）
const selectableDatasets = computed(() => {
  const allowed = capability.value?.datasetVersions
  if (!allowed?.length) return datasets.value
  return datasets.value.filter(d => allowed.includes(d.version))
})

// ---------------------------------------------------------------- 提交意图冻结（PAGE-04）

const INTENT_STORAGE_KEY = 'eval.launch.intent'
const submitting = ref(false)
let pendingIntent = null // {key, body}：一次提交意图 = 随机键 + 冻结 body

function loadStoredIntent() {
  try {
    pendingIntent = JSON.parse(sessionStorage.getItem(INTENT_STORAGE_KEY)) || null
  } catch {
    pendingIntent = null
  }
  if (pendingIntent?.body) {
    // 恢复未确认意图：表单回灌冻结 body（重试原样重发的基准）
    form.displayName = pendingIntent.body.displayName ?? ''
    form.mode = pendingIntent.body.mode ?? form.mode
    form.datasetVersion = pendingIntent.body.datasetVersion ?? ''
    form.repeat = pendingIntent.body.roundsPerScenario ?? 1
  }
}

function storeIntent() {
  try {
    sessionStorage.setItem(INTENT_STORAGE_KEY, JSON.stringify(pendingIntent))
  } catch { /* 隐私模式等存储不可用：仅进程内重试可用，不阻塞提交 */ }
}

function clearIntent() {
  pendingIntent = null
  try {
    sessionStorage.removeItem(INTENT_STORAGE_KEY)
  } catch { /* 同上 */ }
}

function discardIntent() {
  clearIntent()
  ElMessage.info('已放弃上次提交意图；重新提交将新建实验')
}

function newIdempotencyKey() {
  if (globalThis.crypto?.randomUUID) return globalThis.crypto.randomUUID()
  const b = globalThis.crypto.getRandomValues(new Uint8Array(16))
  return Array.from(b, x => x.toString(16).padStart(2, '0')).join('')
}

// datetime → deadlineSeconds（契约是相对秒数；冻结意图后重试不再随时间漂移）
function deadlineSeconds() {
  if (!form.deadline) return null
  return Math.max(1, Math.round((new Date(form.deadline).getTime() - Date.now()) / 1000))
}

function buildBody() {
  return {
    displayName: form.displayName.trim(),
    mode: form.mode,
    datasetVersion: form.datasetVersion,
    model: form.model || null,
    promptVersion: form.promptVersion || null,
    budgetMaxTokens: form.budgetMaxTokens ?? null,
    maxConcurrency: form.maxConcurrency,
    deadlineSeconds: deadlineSeconds(),
    roundsPerScenario: form.repeat,
  }
}

// 意图与当前表单是否同一次提交：deadlineSeconds 是相对秒数会自然漂移，不参与比较
function sameIntent(body) {
  if (!pendingIntent?.body) return false
  const p = pendingIntent.body
  return p.displayName === body.displayName && p.mode === body.mode
    && p.datasetVersion === body.datasetVersion && (p.model ?? null) === (body.model ?? null)
    && (p.promptVersion ?? null) === (body.promptVersion ?? null)
    && (p.budgetMaxTokens ?? null) === (body.budgetMaxTokens ?? null)
    && (p.maxConcurrency ?? null) === (body.maxConcurrency ?? null)
    && (p.roundsPerScenario ?? null) === (body.roundsPerScenario ?? null)
}

const canSubmit = computed(() =>
  capabilityState.value === 'ok'
  && modeSupported(form.mode)
  && form.displayName.trim().length > 0
  && form.datasetVersion.length > 0
  && selectableDatasets.value.some(d => d.version === form.datasetVersion))

const submitBlockedReason = computed(() => {
  if (capabilityState.value !== 'ok') return '能力面未就绪：无法确认当前支持范围，不可提交（服务端亦会拒绝）。'
  if (!form.displayName.trim().length || !form.datasetVersion.length) {
    return '实验名称与数据集版本为契约必填项，补齐后可提交。'
  }
  if (!modeSupported(form.mode)) return '所选模式当前环境未开放，请回到第一步选择受支持的模式。'
  if (!selectableDatasets.value.some(d => d.version === form.datasetVersion)) {
    return '数据集版本不是当前部署版本，请重新选择。'
  }
  return ''
})

// 数据集列表是真实端点（GET /eval/datasets）；失败/空列表诚实提示，不做静态兜底
const datasets = ref([])
const datasetsLoading = ref(false)
const datasetsError = ref(false)

async function loadDatasets() {
  datasetsLoading.value = true
  datasetsError.value = false
  try {
    const d = await api('/eval/datasets')
    datasets.value = d.items ?? []
  } catch {
    datasetsError.value = true
  } finally {
    datasetsLoading.value = false
  }
}

async function submit() {
  if (!canSubmit.value || submitting.value) return
  let body = buildBody()
  if (pendingIntent && !sameIntent(body)) {
    // 旧意图结果未知：不允许把新内容塞进旧键，也不悄悄另起新实验
    try {
      await ElMessageBox.confirm(
        '上一次提交的结果未知（可能已受理执行）。继续提交将按当前表单新建实验，原实验不受影响。确认新建？',
        '新建实验确认',
        { confirmButtonText: '新建实验', cancelButtonText: '先去重试原提交', type: 'warning' },
      )
    } catch {
      return
    }
    clearIntent()
  }
  if (!pendingIntent) {
    pendingIntent = { key: newIdempotencyKey(), body }
    storeIntent()
  }
  submitting.value = true
  try {
    // 原样重发冻结意图（重试 = 同键同 body 字节不变，服务端幂等重放/受理）
    const d = await api('/eval/runs', {
      method: 'POST',
      body: { idempotencyKey: pendingIntent.key, ...pendingIntent.body },
    })
    clearIntent()
    ElMessage.success(d.replayed
      ? '该意图此前已提交成功（幂等重放，返回原实验）'
      : '实验已受理（ACCEPTED）；详情页将显示等待执行状态，worker 领取命令后自动开始')
    router.push(`/eval/runs/${encodeURIComponent(d.runId)}`)
  } catch (e) {
    const st = e?.response?.status
    const data = e?.response?.data ?? {}
    if (st === 409 && data.existingRunId) {
      clearIntent()
      ElMessage.warning('相同幂等键已被不同计划占用——已为你定位到原实验')
      router.push(`/eval/runs/${encodeURIComponent(data.existingRunId)}`)
    } else if (st === 403) {
      clearIntent()
      ElMessage.error('无评测发起权限（需 OPERATOR 角色），未产生任何执行')
    } else if (st === 400 && data.code) {
      // 能力拒绝（模式/覆盖项/限额）：意图可废弃——服务端明确拒绝且零落库
      clearIntent()
      ElMessage.error(data.error || '提交被服务端拒绝：包含当前环境不支持的能力项')
    } else {
      // 超时/网络/5xx：结果未知——保留意图，重试原样重发
      ElMessage.error(data.error || '提交失败（结果未知）；可点「重试提交」原样重发，或放弃后重新填写')
    }
  } finally {
    submitting.value = false
  }
}

onMounted(() => {
  loadCapability()
  loadDatasets()
  loadStoredIntent()
})
</script>

<style scoped>
.new-page { display: flex; flex-direction: column; gap: var(--section-gap); }

.crumb { font-size: 13px; color: var(--ink-2); }
.crumb a { color: var(--brand); }
.crumb .sep { margin: 0 6px; color: var(--line-strong); }

.panel { padding: var(--card-pad); }
.steps { margin-bottom: 24px; }
.step-clickable { cursor: pointer; }
.step-clickable :deep(.el-step__title) { cursor: pointer; }

.step-body { min-height: 260px; }
.form { max-width: 560px; }
.w-full { width: 100%; }
.w-num { width: 240px; }
.hint { font-size: var(--fs-aux); color: var(--ink-2); margin-top: 12px; max-width: 720px; line-height: 1.7; }
.field-note { font-size: var(--fs-aux); color: var(--ink-2); line-height: 1.6; }

.mode-card {
  border: 1px solid var(--line); border-radius: var(--radius); padding: 14px 16px;
  margin-bottom: 12px; cursor: pointer; transition: border-color .15s;
}
.mode-card:hover { border-color: var(--brand); }
.mode-card.cur { border-color: var(--brand); background: var(--brand-soft); }
.mode-card.disabled { opacity: .62; cursor: not-allowed; }
.mode-card.disabled:hover { border-color: var(--line); }
.mode-head { font-size: 15px; margin-bottom: 4px; display: flex; align-items: center; gap: 8px; }
.mode-desc { font-size: var(--fs-body); color: var(--ink); }
.mode-side { font-size: var(--fs-aux); color: var(--ink-2); margin-top: 4px; }
.mode-block {
  margin-top: 6px; font-size: var(--fs-aux); color: var(--ink-2);
  background: var(--bg); border-radius: var(--radius-ctl); padding: 3px 8px;
}
.cap-alert { margin-bottom: 12px; }

.step-actions { display: flex; gap: 8px; margin-top: 24px; border-top: 1px solid var(--line); padding-top: 16px; }
.submit-hint { margin-top: 8px; }
</style>
