<template>
  <div class="create-page">
    <PageHeader
      title="新建演练"
      subtitle="三步发起一次完整演练作业；场景目录、服务端预检与启动命令走 DR-02 真实接口，接口未部署时如实降级。"
    >
      <template #actions>
        <el-button @click="router.push('/drills')">返回列表</el-button>
      </template>
    </PageHeader>

    <div class="card steps-card">
      <el-steps :active="step" align-center finish-status="success">
        <el-step title="选择场景" description="已发布场景模板" />
        <el-step title="配置受限参数" description="时长 / 流量 / 靶场" />
        <el-step title="检查并发起" description="服务端预检后提交" />
      </el-steps>
    </div>

    <!-- 第一步：选择场景。目录来自 GET /api/drills/templates；接口未就绪（403/404）回退静态说明空态 -->
    <div v-show="step === 0" class="card step-body">
      <div class="step-title">选择场景</div>
      <template v-if="templatesState === 'not-ready'">
        <el-alert type="info" :closable="false" show-icon class="step-alert"
          title="场景目录接口（GET /api/drills/templates）依赖 DR-02，后端未部署（实测 403/404）"
        >
          以下为 deploy/alert/eval/eval-scenarios.yml（registry v2）的静态说明文案，仅供了解场景内容，
          <b>当前不可选择</b>。接口部署后本步将展示真实目录：ready=false 的场景注明原因且不可选。
        </el-alert>
        <div class="scenario-grid">
          <div v-for="s in staticScenarios" :key="s.id" class="scenario-card disabled" :title="'场景目录接口未就绪（依赖 DR-02），暂不可选择'">
            <div class="sc-head">
              <span class="sc-id mono">{{ s.id }}</span>
              <span class="sc-name">{{ s.name }}</span>
            </div>
            <div class="sc-row"><span class="sc-k">类型</span>{{ s.type }}</div>
            <div class="sc-row"><span class="sc-k">故障源</span>{{ s.source }}</div>
            <div class="sc-row"><span class="sc-k">症状</span>{{ s.symptom }}</div>
            <div class="sc-state">不可用：场景目录接口依赖 DR-02</div>
          </div>
        </div>
      </template>
      <EmptyState v-else-if="templatesState === 'error'" kind="error" description="场景目录加载失败" @retry="loadTemplates" />
      <div v-else-if="templatesState === 'loading'" v-loading="true" class="loading-box" />
      <template v-else>
        <div class="catalog-note">
          目录来自 GET /api/drills/templates（registry v{{ catalogMeta?.registryVersion ?? '—' }}）。
          execution.ready=false 的场景本期不开放启动，卡片注明原因且不可选；启动前还须通过第三步服务端预检。
        </div>
        <div class="scenario-grid">
          <div
            v-for="t in templates" :key="t.scenarioId"
            class="scenario-card"
            :class="{ disabled: !t.execution?.ready, selected: t.scenarioId === selectedId }"
            :title="t.execution?.ready ? '点击选择该场景' : `本期不可启动：${t.execution?.reason ?? '执行面未就绪'}`"
            @click="selectScenario(t)"
          >
            <div class="sc-head">
              <span class="sc-id mono">{{ t.scenarioId }}</span>
              <span class="sc-name">{{ t.name }}</span>
            </div>
            <div class="sc-row"><span class="sc-k">类型</span>{{ t.scenarioType ?? '—' }}</div>
            <div class="sc-row"><span class="sc-k">故障源</span>{{ t.faultSource ?? '—' }}</div>
            <div class="sc-row"><span class="sc-k">症状</span>{{ t.symptomDisplay ?? '—' }}</div>
            <div class="sc-row"><span class="sc-k">影响</span>{{ t.impact ?? '—' }}</div>
            <div v-if="!t.execution?.ready" class="sc-state">不可启动：{{ t.execution?.reason ?? '执行面未就绪' }}</div>
            <div v-else class="sc-ready">{{ t.scenarioId === selectedId ? '已选择' : '可启动，点击选择' }}</div>
          </div>
        </div>
      </template>
    </div>

    <!-- 第二步：配置受限参数。取值受场景 params 白名单约束，服务端强制校验（DU04） -->
    <div v-show="step === 1" class="card step-body">
      <div class="step-title">配置受限参数</div>
      <el-alert type="info" :closable="false" show-icon class="step-alert"
        :title="selected ? '参数受场景白名单约束，服务端强制校验（DU04 篡改即 400）' : '先在第一步选择场景后开放参数配置'"
      >
        实际 TTL、预热与恢复窗口由后端按白名单计算（第三步预检返回 computed），前端不各自推算。
        目标资源从后端白名单选择，不输入服务器 IP、任意 SQL 或 shell 命令。
      </el-alert>
      <el-form label-width="140px" class="param-form">
        <el-form-item label="持续时间">
          <el-input-number
            v-model="durationSeconds" :disabled="!selected" :step="60"
            :min="selected?.params?.durationMinSeconds ?? 0" :max="selected?.params?.durationMaxSeconds ?? 0"
          />
          <span class="param-note">白名单区间 {{ selected?.params?.durationMinSeconds ?? '—' }} ~ {{ selected?.params?.durationMaxSeconds ?? '—' }} 秒</span>
        </el-form-item>
        <el-form-item label="测试流量规模">
          <el-select v-model="trafficScale" :disabled="!selected" placeholder="场景白名单档位" class="param-ctl">
            <el-option v-for="s in selected?.params?.trafficScales ?? []" :key="s" :label="s" :value="s" />
          </el-select>
        </el-form-item>
        <el-form-item label="靶场">
          <el-input v-model="targetEnv" :disabled="!selected" placeholder="部署靶场白名单由服务端强制" class="param-ctl" />
          <span class="param-note">服务端白名单强制（DU04）；预检 TARGET_ENV_WHITELIST 真值核验</span>
        </el-form-item>
        <el-form-item label="关联评测版本">
          <el-input
            v-model="linkedEvalVersion" class="param-ctl"
            :disabled="!selected || selected?.params?.linkedEvalVersionAllowed === false"
            placeholder="可选；关联一次评测实验，非必填"
          />
        </el-form-item>
      </el-form>
    </div>

    <!-- 第三步：检查并发起。预检必须服务端执行（POST /api/drills/preview），启动时服务端还会重执行 -->
    <div v-show="step === 2" class="card step-body">
      <div class="step-title">检查并发起</div>
      <el-alert type="warning" :closable="false" show-icon class="step-alert"
        title="预检由服务端执行（POST /api/drills/preview）：真实可查信号出 OK/FAIL，查不了的项如实 UNKNOWN"
      >
        靶场健康、资源水位、旧故障残留、管理面与恢复能力在 control 读面无授权通路，返回 UNKNOWN「无法核验」不造假；
        旧预览不能保证当前仍可启动，启动时服务端预检重执行（FAIL 即 409 拒绝）。
      </el-alert>

      <div class="preview-actions">
        <el-button
          type="primary" plain :loading="previewState === 'loading'"
          :disabled="!!previewDisabledReason || previewState === 'loading'"
          :title="previewDisabledReason || 'POST /api/drills/preview 服务端预检'"
          @click="runPreview"
        >执行预检</el-button>
        <span v-if="previewDisabledReason" class="submit-note">{{ previewDisabledReason }}</span>
      </div>

      <template v-if="previewState === 'ok' && preview">
        <div class="checks-list">
          <div v-for="c in preview.checks ?? []" :key="c.name" class="ck-row">
            <span class="ck-badge" :class="checkCls(c.status)">{{ checkText(c.status) }}</span>
            <span class="ck-name mono">{{ c.name }}</span>
            <span class="ck-detail">{{ c.detail ?? '' }}</span>
          </div>
        </div>
        <dl v-if="preview.computed" class="preview-list">
          <div class="pv-row"><dt>预计持续时间</dt><dd>{{ fmtSeconds(preview.computed.durationSeconds) }}</dd></div>
          <div class="pv-row"><dt>TTL（后端计算）</dt><dd>{{ fmtSeconds(preview.computed.ttlSeconds) }}</dd></div>
          <div class="pv-row">
            <dt>预计总时长</dt>
            <dd>{{ fmtSeconds(preview.computed.totalEstimateSeconds) }}（含预热 {{ fmtSeconds(preview.computed.preheatSeconds) }}、恢复窗口 {{ fmtSeconds(preview.computed.recoveryWindowSeconds) }}）</dd>
          </div>
          <div class="pv-row"><dt>预检时间</dt><dd>{{ fmtTime(preview.checkedAt) }}</dd></div>
          <div class="pv-row"><dt>停止条件</dt><dd>触及停止阈值时停止测试流量并发起恢复；同一靶场同一时间仅允许一个活动演练（数据库原子占位）</dd></div>
          <div class="pv-row"><dt>恢复方式</dt><dd>一旦注入可能发生，停止或失败都必须先进入恢复路径，核验完成才 CLOSED（§7.4）</dd></div>
        </dl>
      </template>
      <el-alert v-else-if="previewState === 'error'" type="error" :closable="false" show-icon class="step-alert" :title="launchError || '预检请求失败，请重试'" />

      <div class="submit-row">
        <el-button
          type="primary" :loading="launching"
          :disabled="!canLaunchNow" :title="launchDisabledReason || 'POST /api/drills（幂等键一次生成，重试同键）'"
          @click="launch"
        >开始演练</el-button>
        <span class="submit-note">{{ launchDisabledReason || '一次提交完整作业并返回 202 受理；受理≠完成，以详情页真实状态为准。' }}</span>
      </div>
      <el-alert v-if="launchError && previewState !== 'error'" type="error" :closable="false" show-icon class="launch-error" :title="launchError" />
    </div>

    <div class="step-nav">
      <el-button :disabled="step === 0" @click="step -= 1">上一步</el-button>
      <el-button v-if="step < 2" type="primary" plain @click="step += 1">下一步</el-button>
      <span class="step-nav-note">各步可回看；启动须先通过服务端预检（ready=false 场景本期不可选，原因见第一步卡片）。</span>
    </div>
  </div>
</template>

<script setup>
// 新建演练（/drills/new，DR-02 接线）：
// ① 选择场景：GET /api/drills/templates 真实目录——ready=false 卡片注明原因且不可选，
//    接口未就绪（403/404）回退「依赖 DR-02」静态说明空态；
// ② 配置受限参数：取值受场景 params 白名单约束（服务端 DU04 强制）；
// ③ 检查并发起：POST /api/drills/preview 逐项渲染 checks（OK 绿 / FAIL 红 / UNKNOWN 灰「无法核验」），
//    canLaunch 才允许 POST /api/drills（幂等键 crypto.randomUUID，202 跳详情，409 展示服务端 checks）。
import { computed, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import EmptyState from '../components/common/EmptyState.vue'
import PageHeader from '../components/common/PageHeader.vue'
import {
  ApiNotReadyError, createDrill, listTemplates, newIdempotencyKey, previewDrill,
} from '../api/drills'
import { fmtSeconds, fmtTime } from '../utils/format'

const router = useRouter()
const step = ref(0)

// eval-scenarios.yml（registry_version: 2）S1~S5 的静态说明——仅目录接口未就绪时展示，不构成可选目录
const staticScenarios = [
  { id: 'S1', name: 'paymentFailure=50%', type: '业务链路错误率', source: 'AM0 flagd', symptom: 'checkout 烧损率告警（page/ticket）' },
  { id: 'S2', name: 'flagd paymentUnreachable', type: '依赖不可达', source: 'AM0 flagd', symptom: 'checkout 烧损率告警（page/ticket）' },
  { id: 'S3', name: 'F1 幂等失效', type: '业务完整性', source: 'AM2 靶场（Arena）', symptom: 'ArenaDuplicateOrders（page）' },
  { id: 'S4', name: 'F2 状态回跳', type: '状态机非法迁移', source: 'AM2 靶场（Arena）', symptom: 'ArenaIllegalTransitions（page）' },
  { id: 'S5', name: 'F3 超时结果未知', type: '中间态悬挂', source: 'AM2 靶场（Arena）', symptom: 'ArenaOrderStuck（page）' },
]

// ---------------------------------------------------------------- 第一步：场景目录

const templatesState = ref('loading') // loading | ok | not-ready | error
const templates = ref([])
const catalogMeta = ref(null)
const selectedId = ref(null)

const selected = computed(() => templates.value.find(t => t.scenarioId === selectedId.value) ?? null)

async function loadTemplates() {
  templatesState.value = 'loading'
  try {
    const d = await listTemplates()
    templates.value = d.templates ?? []
    catalogMeta.value = { registryVersion: d.registryVersion, catalogDigest: d.catalogDigest }
    templatesState.value = 'ok'
  } catch (e) {
    templatesState.value = e instanceof ApiNotReadyError ? 'not-ready' : 'error'
  }
}

function selectScenario(t) {
  if (!t?.execution?.ready) return // ready=false 不可选：卡片已注明原因（本期五场景均不开放启动）
  selectedId.value = t.scenarioId
  durationSeconds.value = t.params?.durationDefaultSeconds ?? null
  trafficScale.value = t.params?.trafficScales?.[0] ?? null
  resetPreview() // 换场景后旧预检作废：旧预览不保证现在仍可启动
}

// ---------------------------------------------------------------- 第二步：受限参数

const durationSeconds = ref(null)
const trafficScale = ref(null)
const targetEnv = ref('arena-195') // 默认唯一部署靶场；白名单由服务端强制，预检 TARGET_ENV_WHITELIST 真值核验
const linkedEvalVersion = ref('')

function buildPlan() {
  const plan = { scenarioId: selected.value.scenarioId, targetEnv: targetEnv.value?.trim() }
  if (durationSeconds.value != null) plan.durationSeconds = durationSeconds.value
  if (trafficScale.value) plan.trafficScale = trafficScale.value
  const lev = linkedEvalVersion.value?.trim()
  if (lev) plan.linkedEvalVersion = lev
  return plan
}

// ---------------------------------------------------------------- 第三步：预检与发起

const previewState = ref('idle') // idle | loading | ok | not-ready | error
const preview = ref(null)
const launchKey = ref(null) // 预检通过时生成一次，重试同键（DU02 幂等）；换场景/新预检重新生成
const launching = ref(false)
const launchError = ref('')

// 预检按钮禁用原因（显式降级注明，不伪造可点）
const previewDisabledReason = computed(() => {
  if (templatesState.value === 'not-ready') {
    return '预检接口依赖 DR-02：目录接口实测 403/404（后端未部署），预检同属本批接口面，暂不开放'
  }
  if (templatesState.value !== 'ok') return '场景目录加载完成后可执行预检'
  if (previewState.value === 'not-ready') return '预检接口（POST /api/drills/preview）未部署（403/404），待 DR-02 后端部署后开放'
  if (!selected.value) return '请先在第一步选择场景（本期五场景 ready=false 均未开放启动，原因见卡片）'
  return ''
})

const canLaunchNow = computed(() =>
  previewState.value === 'ok' && preview.value?.canLaunch === true && !!launchKey.value && !launching.value)

const launchDisabledReason = computed(() => {
  if (canLaunchNow.value || launching.value) return ''
  if (previewState.value === 'ok' && preview.value && !preview.value.canLaunch) {
    return '预检存在未通过项（FAIL），不开放启动；UNKNOWN「无法核验」不阻塞但逐条可见'
  }
  return '预检通过（canLaunch=true）后才允许开始演练'
})

function resetPreview() {
  if (previewState.value !== 'not-ready') previewState.value = 'idle'
  preview.value = null
  launchKey.value = null
  launchError.value = ''
}

const CHECK_VIEW = {
  OK: ['通过', 'ck-ok'],
  FAIL: ['未通过', 'ck-fail'],
  UNKNOWN: ['无法核验', 'ck-unknown'],
}
const checkText = s => CHECK_VIEW[s]?.[0] ?? s ?? '—'
const checkCls = s => CHECK_VIEW[s]?.[1] ?? 'ck-unknown'

async function runPreview() {
  if (!selected.value || previewDisabledReason.value) return
  previewState.value = 'loading'
  launchError.value = ''
  try {
    preview.value = await previewDrill(buildPlan())
    previewState.value = 'ok'
    launchKey.value = preview.value?.canLaunch ? newIdempotencyKey() : null
  } catch (e) {
    if (e instanceof ApiNotReadyError) {
      previewState.value = 'not-ready'
      preview.value = null
    } else {
      previewState.value = 'error'
      launchError.value = e?.response?.data?.error || '预检请求失败，请重试'
    }
  }
}

async function launch() {
  if (!canLaunchNow.value) return
  launching.value = true
  launchError.value = ''
  try {
    const res = await createDrill({ idempotencyKey: launchKey.value, ...buildPlan() })
    ElMessage.success(`演练作业已受理（${res.replayed ? '200 幂等重放' : '202'}）：受理≠完成，以详情页真实状态为准`)
    router.push(`/drills/${res.drillId}`)
  } catch (e) {
    if (e instanceof ApiNotReadyError) {
      launchError.value = '启动接口（POST /api/drills）未部署（403/404），待 DR-02 后端部署后开放'
    } else if (e?.response?.status === 409) {
      const d = e.response.data ?? {}
      launchError.value = d.error || '启动冲突（409）'
      // 服务端预检重执行 FAIL：以 409 返回的 checks 覆盖旧预览（旧预览不保证现在仍可启动）
      if (Array.isArray(d.checks) && d.checks.length) {
        preview.value = { ...(preview.value ?? {}), checks: d.checks, canLaunch: false }
        previewState.value = 'ok'
        launchKey.value = null
      }
    } else {
      launchError.value = e?.response?.data?.error || '启动请求失败，请重试'
    }
  } finally {
    launching.value = false
  }
}

onMounted(loadTemplates)
</script>

<style scoped>
.create-page { display: flex; flex-direction: column; gap: var(--section-gap); }

.steps-card { padding: var(--card-pad); }
.step-body { padding: var(--card-pad); }
.step-title { font-size: var(--fs-section); font-weight: 600; color: var(--head); margin-bottom: 12px; }
.step-alert { margin-bottom: 16px; }
.loading-box { height: 240px; }
.catalog-note { font-size: var(--fs-aux); color: var(--ink-2); margin-bottom: 16px; line-height: 1.7; }

.scenario-grid { display: grid; grid-template-columns: repeat(auto-fill, minmax(260px, 1fr)); gap: 12px; }
.scenario-card {
  border: 1px solid var(--line); border-radius: var(--radius); padding: 14px 16px;
  background: var(--card); cursor: pointer;
}
.scenario-card.selected { border-color: var(--brand); box-shadow: 0 0 0 1px var(--brand); }
.scenario-card.disabled { opacity: .72; cursor: not-allowed; }
.sc-head { display: flex; align-items: baseline; gap: 8px; margin-bottom: 8px; }
.sc-id {
  font-size: var(--fs-aux); font-weight: 700; color: var(--brand);
  background: var(--brand-soft); border-radius: var(--radius-ctl); padding: 1px 6px;
}
.sc-name { font-size: var(--fs-body); font-weight: 600; color: var(--head); }
.sc-row { font-size: var(--fs-aux); color: var(--ink-2); line-height: 1.7; }
.sc-k { display: inline-block; width: 44px; color: var(--ink); font-weight: 600; }
.sc-state {
  margin-top: 8px; font-size: var(--fs-aux); color: var(--warn);
  background: var(--warn-bg); border-radius: var(--radius-ctl); padding: 3px 8px; line-height: 1.6;
}
.sc-ready {
  margin-top: 8px; font-size: var(--fs-aux); color: var(--ok);
  background: var(--ok-bg); border-radius: var(--radius-ctl); padding: 3px 8px;
}
.mono { font-family: var(--mono, monospace); }

.param-form { max-width: 640px; }
.param-ctl { width: 240px; }
.param-note { margin-left: 12px; font-size: var(--fs-aux); color: var(--ink-2); }

.preview-actions { display: flex; align-items: center; gap: 12px; margin-bottom: 16px; }

.checks-list { border: 1px solid var(--line); border-radius: var(--radius); overflow: hidden; margin-bottom: 16px; }
.ck-row { display: flex; align-items: baseline; gap: 12px; padding: 10px 16px; border-bottom: 1px solid var(--line); }
.ck-row:last-child { border-bottom: none; }
.ck-badge {
  flex: none; width: 64px; text-align: center; font-size: var(--fs-aux); font-weight: 600;
  border-radius: var(--radius-ctl); padding: 1px 0;
}
.ck-ok { background: var(--ok-bg); color: var(--ok); }
.ck-fail { background: var(--bad-bg); color: var(--bad); }
.ck-unknown { background: #f2f4f8; color: var(--ink-2); }
.ck-name { flex: none; font-size: var(--fs-aux); font-weight: 600; color: var(--ink); }
.ck-detail { font-size: var(--fs-aux); color: var(--ink-2); line-height: 1.6; }

.preview-list { border: 1px solid var(--line); border-radius: var(--radius); overflow: hidden; }
.pv-row { display: flex; gap: 16px; padding: 12px 16px; border-bottom: 1px solid var(--line); }
.pv-row:last-child { border-bottom: none; }
.pv-row dt { flex: none; width: 120px; font-size: var(--fs-body); font-weight: 600; color: var(--ink); }
.pv-row dd { font-size: var(--fs-body); color: var(--ink-2); }

.submit-row { display: flex; align-items: center; gap: 12px; margin-top: 16px; }
.submit-note { font-size: var(--fs-aux); color: var(--ink-2); }
.launch-error { margin-top: 12px; }

.step-nav { display: flex; align-items: center; gap: 12px; }
.step-nav-note { font-size: var(--fs-aux); color: var(--ink-2); }
</style>
