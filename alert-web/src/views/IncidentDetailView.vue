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
  </div>
</template>

<script setup>
// UI-1 告警详情（/alerts/:incidentId）：页头徽章 + 四页签（概览/调查/证据/时间线），真端点 /v1/incidents/{id}
import { computed, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { api } from '../api/client'
import PageHeader from '../components/common/PageHeader.vue'
import StatusBadge from '../components/common/StatusBadge.vue'
import EmptyState from '../components/common/EmptyState.vue'
import KvTable from '../components/common/KvTable.vue'
import { mapSeverity } from '../utils/severity'
import { fmtDuration, fmtTime } from '../utils/format'

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
.ev-sub { font-size: var(--fs-aux); color: var(--ink-2); margin: 8px 0 4px; }
.tl-head { display: flex; align-items: center; gap: 8px; }
.tl-summary { margin-top: 4px; color: var(--ink-2); }
.loading-box { height: 320px; }
</style>
