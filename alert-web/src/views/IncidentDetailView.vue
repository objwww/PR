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

        <!-- 调查：关联 run 关键信息卡 + 跳转；无 run 显式空态 -->
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
          <div v-else class="card block">
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

onMounted(load)
watch(incidentId, load)
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
</style>
