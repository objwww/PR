<template>
  <div class="incident-page">
    <template v-if="state === 'ok' && d">
      <PageHeader :title="d.alertname ?? d.incidentKey ?? d.incidentId" :subtitle="`服务：${d.service ?? '—'}`">
        <template #actions>
          <StatusBadge v-if="sev.key" :severity="sev.key" />
          <el-tag v-else type="info" disable-transitions>未分级</el-tag>
          <StatusBadge :status="d.status" />
          <!-- 后端无“从 incident 发起调查”命令端点（RunCommandController 仅对已存在 run 发命令），
               主操作只做“查看调查”跳转，不造假按钮 -->
          <el-button v-if="runId" type="primary" @click="goRun">查看调查</el-button>
        </template>
      </PageHeader>

      <el-tabs v-model="tab" class="detail-tabs">
        <!-- 概览：影响与当前结论 + 属性面板 + run 状态卡 -->
        <el-tab-pane label="概览" name="overview">
          <div class="card block">
            <h3>影响与当前结论</h3>
            <p class="impact">
              服务 <b>{{ d.service ?? '—' }}</b>
              ｜ 首次发生 {{ fmtTime(d.episodeStartedAt) }}
              ｜ 持续 {{ fmtDuration(d.episodeStartedAt, d.resolvedAt) }}
              ｜ 接收 {{ d.receivedCount ?? 0 }} 次
            </p>
            <el-alert
              v-if="runState === 'COMPLETED'" type="success" :closable="false"
              title="调查已完成，结论见调查报告"
            >
              <el-button size="small" style="margin-top: 6px" @click="goRun">查看调查</el-button>
            </el-alert>
            <el-alert
              v-else type="warning" :closable="false"
              title="原因待确认"
              description="尚未形成已确认的根因结论；调查进展见「调查」页签。"
            />
            <!-- 前端产品化波次2/3：AI 结论人工复核（标注闭环 + 复核待办 + 结构化驳回 + 重查入口） -->
            <div class="fb-block">
              <div class="fb-actions">
                <span class="cat-label">人工复核：</span>
                <el-button size="small" type="primary" :disabled="fb.submitting"
                  @click="submitFeedback('CONFIRMED')">确认结论</el-button>
                <el-button size="small" type="danger" plain :disabled="fb.submitting"
                  @click="fb.rejectOpen = true">驳回结论</el-button>
                <el-button v-if="latestRejected" size="small" type="warning" plain
                  :loading="fb.riSubmitting" @click="reinvestigate">重新排查</el-button>
                <span v-if="runState === 'COMPLETED'" class="cat-label">（对当前调查结论的裁决将计入采纳率）</span>
              </div>
              <div v-if="reviewPending" class="fb-pending" :class="{ overdue: reviewOverdue }">
                复核待办（归属值班：{{ duty.onCall ?? '—' }}）：已等待 {{ reviewWait }}（时限 24 小时）<template v-if="reviewOverdue">——已超时，请尽快裁决</template>
              </div>
              <div v-if="fb.items.length" class="fb-list">
                <div v-for="(f, i) in fb.items" :key="i" class="fb-row">
                  <el-tag :type="f.verdict === 'CONFIRMED' ? 'success' : 'danger'" size="small" disable-transitions>
                    {{ f.verdict === 'CONFIRMED' ? '已确认' : '已驳回' }}
                  </el-tag>
                  <span class="fb-actor">{{ f.actor }}</span>
                  <span class="cell-sub">{{ fmtTime(f.created_at) }}</span>
                  <span v-if="f.category" class="cell-sub">分类：{{ rejectCategoryZh(f.category) }}</span>
                  <span v-if="f.reason && f.reason !== '—'" class="cell-sub">理由：{{ f.reason }}</span>
                  <span v-if="f.actual_cause" class="cell-sub">真实根因：{{ f.actual_cause }}</span>
                </div>
              </div>
            </div>
            <el-dialog v-model="fb.rejectOpen" title="驳回 AI 结论" width="500px">
              <el-form label-width="90px">
                <el-form-item label="驳回分类" required>
                  <el-select v-model="fb.category" style="width: 100%" placeholder="选择驳回分类">
                    <el-option label="证据不足" value="INSUFFICIENT_EVIDENCE" />
                    <el-option label="误报" value="FALSE_POSITIVE" />
                    <el-option label="根因方向错误" value="WRONG_DIRECTION" />
                    <el-option label="其他" value="OTHER" />
                  </el-select>
                </el-form-item>
                <el-form-item label="真实根因">
                  <el-input v-model="fb.actualCause" type="textarea" :rows="2" maxlength="300"
                    placeholder="选填：你认为的真实根因（进入反馈环）" />
                </el-form-item>
                <el-form-item label="理由" required>
                  <el-input v-model="fb.reason" type="textarea" :rows="3" maxlength="500"
                    placeholder="必填：驳回理由（写入标注审计）" />
                </el-form-item>
              </el-form>
              <template #footer>
                <el-button @click="fb.rejectOpen = false">取消</el-button>
                <el-button type="danger" :disabled="!fb.reason?.trim() || !fb.category || fb.submitting"
                  @click="submitFeedback('REJECTED')">提交驳回</el-button>
              </template>
            </el-dialog>
          </div>

          <!-- UX-01 分类区块：生效分类徽章 + 命中依据 + override 快照（缺席不显示）+ 人工修正入口。
               旧契约后端（categoryDetail 缺席）：徽章区 '—'、命中依据'未统计'，区块照常渲染不报错 -->
          <div class="card block">
            <div class="cat-title-row">
              <h3>分类</h3>
              <span class="cat-actions">
                <el-button size="small" @click="openOverride('set')">人工修正</el-button>
                <el-button v-if="d.categorySource === 'OVERRIDE'" size="small" @click="openOverride('revoke')">撤销修正</el-button>
              </span>
            </div>
            <div class="cat-effective">
              <span class="cat-label">生效分类</span>
              <CategoryBadge :category="d.category" :source="d.categorySource" />
            </div>
            <el-descriptions :column="3" border>
              <el-descriptions-item label="命中规则 ID">{{ catDetail?.ruleId ?? '未统计' }}</el-descriptions-item>
              <el-descriptions-item label="规则版本">{{ catDetail?.ruleVersion ?? '未统计' }}</el-descriptions-item>
              <el-descriptions-item label="分类时间">{{ catDetail?.classifiedAt ? fmtTime(catDetail.classifiedAt) : '未统计' }}</el-descriptions-item>
            </el-descriptions>
            <template v-if="hasOverrideSnapshot">
              <h4 class="cat-sub">人工修正快照</h4>
              <el-descriptions :column="3" border>
                <el-descriptions-item label="修正人">{{ catDetail?.overrideActor ?? '—' }}</el-descriptions-item>
                <el-descriptions-item label="修正时间">{{ catDetail?.overrideAt ? fmtTime(catDetail.overrideAt) : '—' }}</el-descriptions-item>
                <el-descriptions-item label="修订号">{{ catDetail?.overrideRevision ?? '—' }}</el-descriptions-item>
                <el-descriptions-item label="修正理由" :span="3">{{ catDetail?.overrideReason ?? '—' }}</el-descriptions-item>
              </el-descriptions>
            </template>
          </div>

          <div class="card block">
            <h3>属性</h3>
            <el-descriptions :column="2" border>
              <el-descriptions-item label="告警名">{{ d.alertname ?? '—' }}</el-descriptions-item>
              <el-descriptions-item label="服务">{{ d.service ?? '—' }}</el-descriptions-item>
              <el-descriptions-item label="严重度">{{ sev.label }}<template v-if="d.severity">（{{ d.severity }}）</template></el-descriptions-item>
              <el-descriptions-item label="状态"><StatusBadge :status="d.status" /></el-descriptions-item>
              <el-descriptions-item label="首次发生">{{ fmtTime(d.episodeStartedAt) }}</el-descriptions-item>
              <el-descriptions-item label="最近事件">{{ fmtTime(d.lastEventAt) }}</el-descriptions-item>
              <el-descriptions-item label="解决时间">{{ fmtTime(d.resolvedAt) }}</el-descriptions-item>
              <el-descriptions-item label="接收 / 事件 / 通知">{{ d.receivedCount ?? 0 }} / {{ d.distinctEventCount ?? 0 }} / {{ d.notificationCount ?? 0 }}</el-descriptions-item>
              <el-descriptions-item label="关联调查">
                <router-link v-if="runId" :to="`/runs/${runId}`"><code>{{ runId }}</code></router-link>
                <span v-else>未发起</span>
              </el-descriptions-item>
              <el-descriptions-item label="告警键（Incident Key）"><code>{{ d.incidentKey ?? '—' }}</code></el-descriptions-item>
            </el-descriptions>
          </div>

          <div v-if="d.run" class="card block">
            <h3>调查运行状态</h3>
            <el-descriptions :column="2" border>
              <el-descriptions-item label="调查 ID"><code>{{ d.run.runId }}</code></el-descriptions-item>
              <el-descriptions-item label="状态"><StatusBadge :status="d.run.state" /></el-descriptions-item>
              <el-descriptions-item label="开始时间">{{ fmtTime(d.run.startedAt) }}</el-descriptions-item>
              <el-descriptions-item label="结束时间">{{ fmtTime(d.run.finishedAt) }}</el-descriptions-item>
            </el-descriptions>
            <el-button type="primary" style="margin-top: 12px" @click="goRun">查看调查详情</el-button>
          </div>
        </el-tab-pane>

        <!-- 调查：关联 run 关键信息卡 + 断言三态（已验证/已排除/待验证）+ 步骤与用量内联 -->
        <el-tab-pane label="调查" name="run">
          <div v-if="runId" class="card block">
            <h3>关联调查</h3>
            <el-descriptions :column="2" border>
              <el-descriptions-item label="调查 ID"><code>{{ runId }}</code></el-descriptions-item>
              <el-descriptions-item label="状态">
                <StatusBadge v-if="runState" :status="runState" />
                <el-tag v-else type="primary" disable-transitions>调查中</el-tag>
              </el-descriptions-item>
              <el-descriptions-item label="开始时间">{{ fmtTime(d.run?.startedAt) }}</el-descriptions-item>
              <el-descriptions-item label="结束时间">{{ fmtTime(d.run?.finishedAt) }}</el-descriptions-item>
            </el-descriptions>
            <el-button type="primary" style="margin-top: 12px" @click="goRun">查看调查详情</el-button>
          </div>
          <div v-if="runClaims.length" class="card block">
            <h3>调查断言（假设与验证状态）</h3>
            <div v-for="c in runClaims" :key="c.id" class="claim-row">
              <div class="claim-head">
                <el-tag :type="claimTagType(c.verdict)" size="small" disable-transitions>{{ claimZh(c.verdict) }}</el-tag>
                <span class="cell-sub">{{ claimKindZh(c.kind) }}</span>
              </div>
              <div class="claim-text">{{ c.text || '（无文字说明）' }}</div>
              <div v-if="c.evidences && c.evidences.length" class="cell-sub">证据引用 {{ c.evidences.length }} 条（见调查详情）</div>
            </div>
          </div>
          <div v-if="runDetail && runDetail.tasks && runDetail.tasks.length" class="card block">
            <h3>调查步骤</h3>
            <el-table :data="runDetail.tasks" size="small" border>
              <el-table-column prop="name" label="步骤" />
              <el-table-column label="状态" width="120">
                <template #default="{ row }">{{ taskStateZh(row.status) }}</template>
              </el-table-column>
              <el-table-column prop="attempts" label="尝试次数" width="90" />
            </el-table>
            <p class="cell-sub" style="margin-top: 8px">
              模型调用 {{ runDetail.usage?.callCount ?? 0 }} 次<template v-if="runDetail.usage">（输入 {{ runDetail.usage.tokensIn ?? 0 }} / 输出 {{ runDetail.usage.tokensOut ?? 0 }} tokens）</template><template v-else>（本轮调查无模型调用记录）</template>
            </p>
          </div>
          <div v-if="runId && !runClaims.length" class="card block">
            <EmptyState kind="empty" description="本轮调查未产出结构化断言（确定性引擎无假设清单）；完整过程可在「查看调查详情」的事件流水逐条核对" />
          </div>
          <div v-if="runId" class="card block">
            <h3>问一问（引用式诊断问答）</h3>
            <div class="diag-qs">
              <el-button v-for="q in diagQuestions" :key="q.key" size="small" plain
                :loading="diag.loadingKey === q.key" @click="askDiag(q)">{{ q.text }}</el-button>
            </div>
            <div v-if="diag.items.length" class="diag-list">
              <div v-for="(d, i) in diag.items" :key="i" class="diag-row">
                <div class="cell-sub">{{ d.created_by }} · {{ fmtTime(d.created_at) }}</div>
                <div class="claim-text"><b>{{ d.question }}</b></div>
                <div class="claim-text">{{ d.answer }}</div>
              </div>
            </div>
          </div>
          <div v-if="!runId" class="card block">
            <EmptyState kind="empty" description="尚未发起调查" />
          </div>
        </el-tab-pane>

        <!-- 证据：labels/annotations jsonb 键值表（长值折叠），含逐事件负载 -->
        <el-tab-pane label="证据" name="evidence">
          <div class="card block">
            <h3>告警标签（labels）</h3>
            <KvTable :data="d.labels" />
          </div>
          <div class="card block">
            <h3>告警注解（annotations）</h3>
            <KvTable :data="d.annotations" />
          </div>
          <div v-if="timeline.length" class="card block">
            <h3>事件负载</h3>
            <el-collapse>
              <el-collapse-item
                v-for="ev in timeline" :key="ev.eventId" :name="ev.eventId"
                :title="`${fmtTime(ev.startsAt)} · ${ev.status === 'firing' ? '触发' : '恢复'}${ev.severity ? ' · ' + ev.severity : ''}`"
              >
                <h4 class="ev-sub">标签（labels）</h4>
                <KvTable :data="ev.labels" />
                <h4 class="ev-sub">注解（annotations）</h4>
                <KvTable :data="ev.annotations" />
              </el-collapse-item>
            </el-collapse>
          </div>
        </el-tab-pane>

        <!-- 时间线：垂直渲染，firing=红 / resolved=绿 -->
        <el-tab-pane label="时间线" name="timeline">
          <div class="card block">
            <el-timeline v-if="timeline.length">
              <el-timeline-item
                v-for="ev in timeline" :key="ev.eventId"
                :type="ev.status === 'firing' ? 'danger' : 'success'"
                :timestamp="fmtTime(ev.startsAt) + (ev.endsAt ? ' ~ ' + fmtTime(ev.endsAt) : '')"
                placement="top"
              >
                <div class="tl-head">
                  <b>{{ ev.status === 'firing' ? '告警触发' : '告警恢复' }}</b>
                  <StatusBadge v-if="mapSeverity(ev.severity).key" :severity="mapSeverity(ev.severity).key" />
                  <el-tag v-else-if="ev.severity" type="info" disable-transitions>{{ ev.severity }}</el-tag>
                </div>
                <div v-if="evSummary(ev)" class="tl-summary">{{ evSummary(ev) }}</div>
              </el-timeline-item>
            </el-timeline>
            <EmptyState v-else kind="empty" description="暂无时间线事件" />
          </div>
        </el-tab-pane>
      </el-tabs>
    </template>

    <EmptyState v-else-if="state === 'forbidden'" kind="forbidden" />
    <EmptyState v-else-if="state === 'notfound'" kind="empty" description="告警不存在或已被清理（404）">
      <el-button @click="$router.push('/alerts')">返回告警中心</el-button>
    </EmptyState>
    <EmptyState v-else-if="state === 'error'" kind="error" @retry="load" />
    <div v-else v-loading="true" class="loading-box" />

    <!-- UX-01 人工修正/撤销对话框：目标分类（词表静态）+ 理由必填；提交失败显式提示不静默 -->
    <el-dialog v-model="ov.open" :title="ov.mode === 'revoke' ? '撤销人工修正' : '人工修正分类'" width="480px">
      <el-form label-width="90px">
        <el-form-item v-if="ov.mode === 'set'" label="目标分类" required>
          <el-select v-model="ov.category" placeholder="选择目标分类" style="width: 100%">
            <el-option v-for="c in categoryOverrideOptions" :key="c.value" :value="c.value" :label="c.label" />
          </el-select>
        </el-form-item>
        <el-form-item label="理由" required>
          <el-input
            v-model="ov.reason" type="textarea" :rows="3" maxlength="500"
            placeholder="必填：修正/撤销理由（写入审计）"
          />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="ov.open = false">取消</el-button>
        <el-button type="primary" :loading="ov.submitting" :disabled="!ovValid" @click="submitOverride">提交</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
// UI-1 告警详情（/alerts/:incidentId）：页头徽章 + 四页签（概览/调查/证据/时间线），真端点 /v1/incidents/{id}
// UX-01：概览页签内分类区块（生效徽章 + 命中依据 + override 快照）与人工修正/撤销对话框，
//        旧契约后端字段缺席时降级（'未统计'/不渲染），修正请求 404/403 显式提示不静默
import { computed, onMounted, reactive, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { api } from '../api/client'
import PageHeader from '../components/common/PageHeader.vue'
import StatusBadge from '../components/common/StatusBadge.vue'
import CategoryBadge from '../components/common/CategoryBadge.vue'
import EmptyState from '../components/common/EmptyState.vue'
import KvTable from '../components/common/KvTable.vue'
import { mapSeverity } from '../utils/severity'
import { fmtDuration, fmtTime } from '../utils/format'
import { CATEGORY_OVERRIDE_OPTIONS } from '../utils/category'
import { useSessionStore } from '../stores/session'

const session = useSessionStore()

const route = useRoute()
const router = useRouter()

const incidentId = computed(() => String(route.params.incidentId))
const d = ref(null)
const state = ref('loading') // loading | ok | notfound | forbidden | error
const tab = ref('overview')

const sev = computed(() => mapSeverity(d.value?.severity))
const timeline = computed(() => d.value?.timeline ?? [])
const runId = computed(() => d.value?.run?.runId ?? d.value?.currentRcaRunId ?? null)
const runState = computed(() => d.value?.run?.state ?? d.value?.runState ?? null)

// UX-01 分类：categoryDetail 缺席（旧契约后端）→ 命中依据'未统计'、override 快照整段不渲染
const catDetail = computed(() => d.value?.categoryDetail ?? null)
const hasOverrideSnapshot = computed(() =>
  !!(catDetail.value?.overrideActor || catDetail.value?.overrideReason || catDetail.value?.overrideAt))

// UX-01 人工修正/撤销：POST /v1/incidents/{id}/category-override[/revoke]
// 后端 UX-01 未部署 → 404/403，捕获后显式提示，不静默不假装成功
const categoryOverrideOptions = CATEGORY_OVERRIDE_OPTIONS
const ov = reactive({ open: false, mode: 'set', category: '', reason: '', submitting: false })
const ovValid = computed(() => !!ov.reason.trim() && (ov.mode === 'revoke' || !!ov.category))

function openOverride(mode) {
  ov.mode = mode
  ov.category = ''
  ov.reason = ''
  ov.open = true
}

async function submitOverride() {
  ov.submitting = true
  try {
    const body = {
      reason: ov.reason.trim(),
      expectedRevision: catDetail.value?.overrideRevision ?? 0,
      idempotencyKey: crypto.randomUUID(),
    }
    const base = `/v1/incidents/${incidentId.value}/category-override`
    if (ov.mode === 'revoke') {
      await api(`${base}/revoke`, { method: 'POST', body })
    } else {
      await api(base, { method: 'POST', body: { ...body, category: ov.category } })
    }
    ElMessage.success(ov.mode === 'revoke' ? '已撤销人工修正' : '分类已修正')
    ov.open = false
    load()
  } catch (e) {
    const s = e?.response?.status
    if (s === 404 || s === 403) {
      ElMessageBox.alert('修正接口依赖后端 UX-01，当前未部署', '提示', { type: 'warning' })
    } else if (s === 409) {
      ElMessage.error('分类已被他人修改，正在刷新最新状态')
      ov.open = false
      load()
    } else if (s === 400) {
      ElMessage.error(`请求被后端拒绝（400）：${e?.response?.data?.message ?? '参数不合法'}`)
    } else {
      ElMessage.error(`修正提交失败${s ? `（HTTP ${s}）` : '（网络错误）'}，请重试`)
    }
  } finally {
    ov.submitting = false
  }
}

function evSummary(ev) {
  return ev?.annotations?.summary ?? ev?.annotations?.description ?? ''
}

// 前端产品化波次2/3：AI 结论人工复核（标注闭环 + 复核待办 + 结构化驳回 + 重查入口）
const fb = reactive({ items: [], submitting: false, rejectOpen: false, reason: '', category: '', actualCause: '', riSubmitting: false })

// 断言三态（后端 ClaimStatus：TRUE/FALSE/UNKNOWN）与调查步骤词表
const CLAIM_ZH = { TRUE: ['已验证', 'success'], FALSE: ['已排除', 'danger'], UNKNOWN: ['待验证', 'warning'] }
function claimZh(v) { return CLAIM_ZH[v]?.[0] ?? '断言' }
function claimTagType(v) { return CLAIM_ZH[v]?.[1] ?? 'info' }
function claimKindZh(k) {
  return { HYPOTHESIS: '假设', EVIDENCE: '证据', CONCLUSION: '结论' }[k] ?? '断言'
}
function taskStateZh(s) {
  return {
    QUEUED: '等待', LEASED: '执行中', RUNNING: '执行中', REPORTING: '报告组装中',
    DONE: '已完成', SKIPPED: '已跳过', BLOCKED: '卡住', RETRY_WAIT: '等待重试',
    CANCELLED: '已取消', DEAD: '失败', FAILED_TERMINAL: '失败', STALE: '已过期',
  }[s] ?? s
}
const REJECT_CATEGORY_ZH = {
  INSUFFICIENT_EVIDENCE: '证据不足', FALSE_POSITIVE: '误报',
  WRONG_DIRECTION: '根因方向错误', OTHER: '其他',
}
function rejectCategoryZh(c) { return REJECT_CATEGORY_ZH[c] ?? c }

// 调查页签内联证据链：复用 runs 只读投影（断言/任务/用量），无独立新面
const runDetail = ref(null)
const runClaims = computed(() => runDetail.value?.claims ?? [])
async function loadRunDetail() {
  if (!runId.value) { runDetail.value = null; return }
  try {
    runDetail.value = await api(`/rca-runs/${runId.value}`)
  } catch { runDetail.value = null }
}

const latestFeedback = computed(() => fb.items[0] ?? null)

// 复核归属（业界对齐）：待办归当前值班人（值班快照只读面，缺席如实 —）
const duty = reactive({ onCall: null })
async function loadDuty() {
  try {
    const res = await api('/duty/schedule/snapshot')
    duty.onCall = res?.onCall ?? null
  } catch { duty.onCall = null }
}
const latestRejected = computed(() => latestFeedback.value?.verdict === 'REJECTED')
const REVIEW_DUE_HOURS = 24
const reviewPending = computed(() =>
  !latestFeedback.value && d.value?.run?.state === 'SUCCEEDED' && !!d.value?.run?.finishedAt)
const reviewWait = computed(() => {
  const end = d.value?.run?.finishedAt
  if (!end) return '—'
  const ms = Date.now() - new Date(end).getTime()
  const h = Math.floor(ms / 3600000)
  const m = Math.floor((ms % 3600000) / 60000)
  return h > 0 ? `${h} 小时 ${m} 分钟` : `${m} 分钟`
})
const reviewOverdue = computed(() => {
  const end = d.value?.run?.finishedAt
  if (!end) return false
  return Date.now() - new Date(end).getTime() > REVIEW_DUE_HOURS * 3600000
})

async function loadFeedback() {
  try {
    const res = await api(`/v1/incidents/${incidentId.value}/conclusion-feedback`)
    fb.items = res?.items ?? []
  } catch { fb.items = [] }
}
async function submitFeedback(verdict) {
  fb.submitting = true
  try {
    const res = await api(`/v1/incidents/${incidentId.value}/conclusion-feedback`, {
      method: 'POST',
      body: {
        verdict,
        actor: `human:${session.user || 'oncall'}`,
        reason: verdict === 'REJECTED' ? fb.reason?.trim() : null,
        category: verdict === 'REJECTED' ? fb.category : null,
        actualCause: verdict === 'REJECTED' ? (fb.actualCause?.trim() || null) : null,
        runId: runId.value,
      },
    })
    if (res?.status === 'OK') {
      ElMessage.success(res.verdict)
      fb.rejectOpen = false
      fb.reason = ''
      fb.category = ''
      fb.actualCause = ''
      await loadFeedback()
    } else {
      ElMessage.warning(`被拒绝：${res?.reason ?? '未知原因'}`)
    }
  } catch (e) {
    ElMessage.error('提交失败，请重试')
  } finally {
    fb.submitting = false
  }
}

/** 重新排查：显式重查（复用等待重驱同闸路径；路由未放量/活跃 run 在则诚实拒绝） */
async function reinvestigate() {
  fb.riSubmitting = true
  try {
    const res = await api(`/v1/incidents/${incidentId.value}/reinvestigate`, { method: 'POST' })
    if (res?.status === 'OK') {
      ElMessage.success('已发起新一轮调查')
      await load()
      await loadRunDetail()
    } else if (res?.status === 'REJECTED') {
      ElMessage.warning('暂不能重查：已有进行中的调查，或该告警未在路由放量名单')
    } else {
      ElMessage.warning(`被拒绝：${res?.reason ?? '未知原因'}`)
    }
  } catch (e) {
    ElMessage.error('发起失败，请重试')
  } finally {
    fb.riSubmitting = false
  }
}

function goRun() { if (runId.value) router.push(`/runs/${runId.value}`) }

async function load() {
  state.value = 'loading'
  d.value = null
  tab.value = 'overview'
  try {
    d.value = await api(`/v1/incidents/${incidentId.value}`)
    state.value = 'ok'
  } catch (e) {
    const s = e?.response?.status
    state.value = s === 404 ? 'notfound' : (s === 403 ? 'forbidden' : 'error')
  }
}

// 诊断会话（业界路线v2第2项 v1）：五词表引用式问答，答案真源组装零幻觉，落库回放
const diag = reactive({ questions: [], items: [], loadingKey: '' })
const diagQuestions = computed(() => diag.questions)
async function loadDiag() {
  try {
    const qs = await api(`/v1/incidents/${incidentId.value}/diag/questions`)
    diag.questions = qs?.questions ?? []
    const his = await api(`/v1/incidents/${incidentId.value}/diag`)
    diag.items = (his?.items ?? []).slice().reverse()
  } catch { /* 问答面缺席如实留空 */ }
}
async function askDiag(q) {
  diag.loadingKey = q.key
  try {
    const res = await api(`/v1/incidents/${incidentId.value}/diag`, {
      method: 'POST', body: { key: q.key, createdBy: `human:${session.user || 'oncall'}` },
    })
    if (res?.status === 'OK') {
      await loadDiag()
    } else {
      ElMessage.warning(`被拒绝：${res?.reason ?? '未知原因'}`)
    }
  } catch { ElMessage.error('问答失败，请重试') } finally { diag.loadingKey = '' }
}

onMounted(() => {
  load()
  loadFeedback()
  loadRunDetail()
  loadDuty()
  loadDiag()
})
watch(incidentId, () => { load(); loadFeedback(); loadRunDetail(); loadDiag() })
// runId 由事件详情异步就绪（d.run.runId），就绪后补拉调查证据链
watch(runId, loadRunDetail)
</script>

<style scoped>
.incident-page { display: flex; flex-direction: column; gap: var(--section-gap); }
.detail-tabs :deep(.el-tabs__item) { font-size: var(--fs-body); }
.block { padding: var(--card-pad); margin-bottom: var(--section-gap); }
.block h3 { font-size: var(--fs-section); font-weight: 600; color: var(--head); margin-bottom: 12px; }
.impact { margin-bottom: 12px; color: var(--ink); }
.cat-title-row { display: flex; align-items: center; justify-content: space-between; margin-bottom: 12px; }
.cat-title-row h3 { margin-bottom: 0; }
.cat-effective { display: flex; align-items: center; gap: 10px; margin-bottom: 12px; }
.cat-label { font-size: var(--fs-aux); color: var(--ink-2); }
.cat-sub { font-size: var(--fs-body); font-weight: 600; color: var(--head); margin: 16px 0 8px; }
.ev-sub { font-size: var(--fs-aux); color: var(--ink-2); margin: 8px 0 4px; }
.tl-head { display: flex; align-items: center; gap: 8px; }
.tl-summary { margin-top: 4px; color: var(--ink-2); }
.loading-box { height: 320px; }
.fb-block { margin-top: 12px; }
.fb-actions { display: flex; align-items: center; gap: 8px; flex-wrap: wrap; }
.fb-pending { margin-top: 8px; font-size: var(--fs-aux); color: var(--ink-2); }
.fb-pending.overdue { color: var(--el-color-danger); font-weight: 600; }
.fb-list { margin-top: 10px; display: flex; flex-direction: column; gap: 6px; }
.fb-row { display: flex; align-items: center; gap: 10px; flex-wrap: wrap; }
.fb-actor { font-size: var(--fs-aux); color: var(--head); font-weight: 600; }
.claim-row { padding: 10px 0; border-bottom: 1px solid var(--line, #ebeef5); }
.claim-row:last-child { border-bottom: none; }
.claim-head { display: flex; align-items: center; gap: 8px; margin-bottom: 6px; }
.claim-text { font-size: var(--fs-body); color: var(--ink); }
.diag-qs { display: flex; gap: 8px; flex-wrap: wrap; }
.diag-list { margin-top: 10px; display: flex; flex-direction: column; gap: 10px; }
.diag-row { padding: 8px 0; border-bottom: 1px solid var(--line, #ebeef5); display: flex; flex-direction: column; gap: 4px; }
</style>
