<template>
  <div class="run-page" v-if="run">
    <div class="crumb">
      <span>
        <b>首页</b><span class="sep">/</span>调查<span class="sep">/</span>
        <router-link to="/runs">调查队列</router-link><span class="sep">/</span>
        <b>调查 {{ shortId(run.id) }}</b>
      </span>
      <span class="crumb-right">数据更新至 {{ fmtTime(loadedAt) }}</span>
    </div>

    <!-- 顶部摘要：告警 / 状态 chip / 耗时 / 已用费用（token×单价无则只显 token）+ 次级操作 -->
    <div class="card runhead">
      <router-link v-if="run.incident" class="inc-link" :to="`/alerts/${run.incident}`" :title="run.incident">
        告警 {{ shortId(run.incident) }}
      </router-link>
      <StatusBadge :status="run.status" />
      <span class="mini">耗时 {{ listRow?.duration ?? '—' }}</span>
      <span class="mini">预算 token：{{ run.budget?.token?.used ?? '—' }}</span>
      <span class="mini">任务 {{ run.progress.done }}/{{ run.progress.total }}</span>
      <span v-if="listRow?.blocker" class="mini blocker" :title="listRow.blocker">卡点：{{ listRow.blocker }}</span>
      <span class="ops">
        <template v-if="runActive">
          <el-button size="small" :disabled="cmdPending" @click="submitHint">补充线索</el-button>
          <el-button size="small" :disabled="cmdPending" @click="submitFeedback">报告反馈</el-button>
          <el-button size="small" type="danger" plain :disabled="cmdPending" @click="submitCancel">取消调查</el-button>
        </template>
      </span>
    </div>

    <div class="card tabs">
      <button
        v-for="t in viewTabs"
        :key="t.key"
        class="tab"
        :class="{ cur: viewTab === t.key }"
        @click="switchTab(t.key)"
      >{{ t.label }}</button>
    </div>

    <!-- ============ 摘要（默认）：当前结论与待补证 ============ -->
    <template v-if="viewTab === 'summary'">
      <div class="card panel">
        <div class="lbl">当前结论</div>
        <template v-if="currentConclusion">
          <div class="conclusion">
            <b>{{ currentConclusion.text }}</b>
            <span class="mini">（{{ currentConclusion.code }} ｜ {{ currentConclusion.verdict }}）</span>
          </div>
        </template>
        <template v-else>
          <div class="conclusion-pending">
            <el-tag type="warning" effect="dark" disable-transitions>原因待确认</el-tag>
            <span v-if="runActive" class="mini"><el-icon class="is-loading"><Loading /></el-icon> 调查进行中，等待事件与结论</span>
            <span v-else class="mini">本次调查未产生有效结论</span>
          </div>
        </template>
      </div>

      <div class="card panel">
        <div class="lbl">待补证</div>
        <template v-if="pendingHypotheses.length">
          <div v-for="c in pendingHypotheses" :key="c.id" class="line-item">
            {{ c.text }} <span class="mini">（{{ c.code }} ｜ {{ c.verdict }}）</span>
          </div>
        </template>
        <div v-else class="muted">暂无待补证项</div>
      </div>

      <div class="card panel">
        <div class="lbl">调查进展</div>
        <div class="progress-line">
          共 {{ run.progress.total }} 个任务：完成 {{ run.progress.done }} ｜ 运行 {{ run.progress.running }} ｜ 阻塞 {{ run.progress.blocked }}
          <template v-if="run.engine">｜ 引擎 {{ run.engine }}</template>
        </div>
        <div v-if="listRow?.blocker" class="blocker-box">卡点：{{ listRow.blocker }}</div>
      </div>

      <div class="card panel">
        <div class="lbl">最近事件</div>
        <template v-if="recentEvents.length">
          <div v-for="e in recentEvents" :key="e.seq" class="line-item">
            <span class="seq">seq{{ e.seq }}</span>
            <b>{{ eventZh(e.type) }}</b>
            <span class="mini">{{ e.summary }}</span>
          </div>
          <el-button text type="primary" @click="switchTab('events')">查看全部事件 →</el-button>
        </template>
        <div v-else class="muted">
          <template v-if="runActive"><el-icon class="is-loading"><Loading /></el-icon> 等待事件</template>
          <template v-else>暂无事件</template>
        </div>
      </div>
    </template>

    <!-- ============ 执行过程：Vue Flow DAG，画布为主 ============ -->
    <template v-else-if="viewTab === 'dag'">
      <div class="card toolbar">
        <el-button size="small" @click="dagRef?.fit()">适应画布</el-button>
        <el-button size="small" :type="abnormalOnly ? 'primary' : 'default'" @click="abnormalOnly = !abnormalOnly">仅看异常</el-button>
        <el-button size="small" :type="neighborFocus ? 'primary' : 'default'" :disabled="!selectedTask" @click="neighborFocus = !neighborFocus">上游/下游</el-button>
        <el-popover placement="bottom-start" trigger="click" width="520">
          <template #reference>
            <el-button size="small" text>状态图例</el-button>
          </template>
          <table class="legend">
            <thead>
              <tr><th>状态（节点内原文）</th><th>图形约定</th><th>含义</th></tr>
            </thead>
            <tbody>
              <tr v-for="code in statusOrder" :key="code">
                <td><b>{{ code }}</b> {{ statusStyle[code].zh }}</td>
                <td>{{ statusStyle[code].graphic }}</td>
                <td>{{ statusStyle[code].desc }}</td>
              </tr>
            </tbody>
          </table>
        </el-popover>
        <span class="flex-spacer" />
        <span class="tinfo">任务 {{ run.progress.total }}（完成 {{ run.progress.done }} / 运行 {{ run.progress.running }} / 阻塞 {{ run.progress.blocked }}）</span>
      </div>

      <div class="card dag-card">
        <RunDag
          ref="dagRef"
          :tasks="dagTasks"
          :edges="edges"
          :selected-id="selectedTaskId"
          :abnormal-only="abnormalOnly"
          :neighbor-focus="neighborFocus"
          @select="onSelectTask"
        />
        <div class="note">点击节点查看任务详情；实时事件驱动节点变色</div>
      </div>

      <!-- 选中节点才开任务详情抽屉（360~420px） -->
      <DetailDrawer
        :model-value="!!selectedTask"
        :title="`任务详情：${selectedTask?.name ?? ''}`"
        :size="400"
        @update:model-value="v => { if (!v) selectedTaskId = null }"
      >
        <template v-if="selectedTask">
          <div class="box">
            <StatusBadge :status="selectedTask.status" />
            <span class="mini">{{ statusStyle[selectedTask.status]?.zh }}</span><br>
            任务 {{ selectedTask.name }} ｜ 优先级 {{ selectedTask.priority }}<br>
            截止时间 {{ fmtTime(selectedTask.deadline) }}
            <template v-if="selectedTask.lease"><br>租约：{{ selectedTask.lease.worker }}（epoch {{ selectedTask.lease.epoch }}）</template>
            <br>尝试次数：{{ selectedTask.attempts }}
          </div>
          <div class="box">
            <b>依赖</b>：
            <template v-if="selectedTask.deps.length">
              <span v-for="(d, i) in selectedTask.deps" :key="d.name">
                <template v-if="i">｜ </template>{{ d.name }}（{{ statusStyle[d.status]?.zh ?? d.status }}）
              </span>
            </template>
            <template v-else>无上游</template>
            <br>
            <b>下游</b>：
            <template v-if="selectedTask.downstream.length">
              <span v-for="(d, i) in selectedTask.downstream" :key="d.name">
                <template v-if="i">｜ </template>{{ d.name }}（{{ statusStyle[d.status]?.zh ?? d.status }}）
              </span>
            </template>
            <template v-else>无下游</template>
          </div>
          <div class="drawer-ops">
            <el-button size="small" @click="copyTaskLink">复制任务链接</el-button>
            <el-button size="small" type="primary" @click="viewTaskEvents">仅看此任务事件</el-button>
          </div>
        </template>
      </DetailDrawer>
    </template>

    <!-- ============ 事件流：Transcript 卡片流 ============ -->
    <template v-else-if="viewTab === 'events'">
      <div class="card panel">
        <div class="ev-toolbar">
          范围：
          <el-button size="small" :type="eventScope === 'all' ? 'primary' : 'default'" @click="eventScope = 'all'">全部事件</el-button>
          <el-button
            size="small" :type="eventScope === 'task' ? 'primary' : 'default'"
            :disabled="!selectedTask" @click="eventScope = 'task'"
          >当前任务：{{ selectedTask?.name ?? '—' }}</el-button>
          <el-button size="small" :type="errorsOnly ? 'primary' : 'default'" @click="errorsOnly = !errorsOnly">仅错误</el-button>
          <el-select v-model="typeFilter" size="small" class="w-type" placeholder="全部类型" clearable>
            <el-option v-for="t in eventTypes" :key="t" :value="t" :label="eventZh(t)" />
          </el-select>
          <el-input v-model="search" size="small" class="w-search" placeholder="搜索事件码 / seq" clearable />
          <span class="flex-spacer" />
          <el-button size="small" @click="downloadEvents">下载白名单事件</el-button>
        </div>

        <div class="ev-scroll-wrap">
          <transition name="fade">
            <div v-if="newCount > 0" class="new-bar" @click="scrollEvToBottom">有 {{ newCount }} 条新事件，点击查看</div>
          </transition>

          <div ref="evListRef" class="ev-list" @scroll.passive="onEvScroll">
            <div
              v-for="ev in filteredEvents"
              :key="ev.seq"
              class="ev-card"
              :class="['lv-' + (ev.level ?? 'info'), { open: expandedSeq === ev.seq }]"
              @click="expandedSeq = expandedSeq === ev.seq ? null : ev.seq"
            >
              <div class="ev-line">
                <i class="lv-dot" />
                <b class="ev-name">{{ eventZh(ev.type) }}</b>
                <code>{{ ev.type }}</code>
                <span class="ev-summary">{{ ev.summary }}</span>
                <el-tag v-if="ev.taskName ?? taskNameOf(ev)" size="small" type="info" disable-transitions>
                  {{ ev.taskName ?? taskNameOf(ev) }}
                </el-tag>
                <span class="ev-meta">seq{{ ev.seq }} ｜ {{ fmtTime(ev.createdAt) }}</span>
              </div>
              <div v-if="expandedSeq === ev.seq" class="ev-detail">
                <pre v-if="ev.payload && Object.keys(ev.payload).length">{{ JSON.stringify(ev.payload, null, 2) }}</pre>
                <div class="mini">
                  seq={{ ev.seq }} ｜ task_id={{ ev.taskId ?? '—' }} ｜ level={{ ev.level }}<br>
                  脱敏红线：思考过程 / 原始提示词 / 密钥 / 完整工具参数不进前端，关联对象仅白名单引用与摘要（digest）
                </div>
              </div>
            </div>
            <div v-if="filteredEvents.length === 0" class="ev-empty">
              <template v-if="runActive && !allEvents.length">
                <el-icon class="is-loading"><Loading /></el-icon> 调查进行中，等待事件
              </template>
              <template v-else>当前筛选无事件</template>
            </div>
          </div>
        </div>

        <div class="ev-status">
          {{ sseStatusText }}（游标 seq={{ lastSeq }}）｜ 已加载 {{ allEvents.length }} 条 ｜ 断线自动重连续传；检测到 seq 缺口时全量重同步
        </div>
      </div>
    </template>

    <!-- ============ Claim 与证据：证据 / 假设 / 结论三层分级 ============ -->
    <template v-else-if="viewTab === 'claims'">
      <div v-for="g in claimGroups" :key="g.key" class="card panel">
        <div class="lbl">{{ g.title }}</div>
        <template v-if="g.items.length">
          <div v-for="c in g.items" :key="c.id" class="box">
            <b>{{ c.text }}</b> <span class="mini">（{{ c.code }}）</span>
            ｜ {{ c.verdict }} ｜ {{ c.agree }} ｜ {{ c.current ? '当前有效' : '已被取代' }}
            <el-button size="small" text type="primary" @click="toggleClaim(c.id)">
              证据 {{ c.evidences?.length ?? 0 }} 条 {{ openClaims.has(c.id) ? '▲' : '▼' }}
            </el-button>
            <div v-if="openClaims.has(c.id)" class="ev-refs">
              <div v-for="e in c.evidences" :key="e.id" class="line-item">
                {{ e.id }} {{ e.desc }} <code>{{ e.digest }}</code><template v-if="e.window"> [{{ e.window }}]</template>
              </div>
              <div class="mini">来源 / 时间窗 / 采集状态可展开（校验 observed_generation / schema_version / payload_digest）</div>
            </div>
          </div>
        </template>
        <div v-else class="muted">{{ g.emptyText }}</div>
      </div>
    </template>

    <!-- ============ 报告 ============ -->
    <template v-else-if="viewTab === 'report'">
      <div class="card panel">
        <div class="lbl">报告与发布状态</div>
        <div class="muted">未生成——{{ reportState?.note ?? '调查完成并经人工复核后在此发布' }}</div>
      </div>
    </template>

    <!-- ============ 运行详情：预算 / lease / attempt / config digest 等技术字段 ============ -->
    <template v-else-if="viewTab === 'meta'">
      <div class="card panel">
        <div class="lbl">基本信息</div>
        <KvTable :data="runHeadKv" />
      </div>
      <div class="card panel">
        <div class="lbl">预算分项</div>
        <KvTable v-if="run.budget" :data="run.budget" />
        <div v-else class="muted">预算账本无读面，投影未提供（不回填示意值）</div>
      </div>
      <div class="card panel">
        <div class="lbl">任务与尝试（rca_task 投影）</div>
        <el-table :data="dagTasks" size="small">
          <el-table-column label="任务" min-width="160">
            <template #default="{ row }"><b>{{ row.name }}</b></template>
          </el-table-column>
          <el-table-column label="状态" width="120">
            <template #default="{ row }"><StatusBadge :status="row.status" /></template>
          </el-table-column>
          <el-table-column prop="priority" label="优先级" width="80" align="right" />
          <el-table-column label="截止时间" width="170">
            <template #default="{ row }">{{ fmtTime(row.deadline) }}</template>
          </el-table-column>
          <el-table-column label="租约" min-width="150">
            <template #default="{ row }">
              <template v-if="row.lease">{{ row.lease.worker }} · epoch {{ row.lease.epoch }}</template>
              <span v-else class="muted">—</span>
            </template>
          </el-table-column>
          <el-table-column prop="attempts" label="尝试次数" width="110" align="right" />
          <el-table-column label="任务 ID" min-width="110">
            <template #default="{ row }"><code>{{ shortId(row.taskId) }}</code></template>
          </el-table-column>
        </el-table>
      </div>
    </template>
  </div>

  <div v-else-if="loadError" class="card"><EmptyState kind="error" :description="loadError" @retry="loadDetail" /></div>
  <div v-else class="loading card" v-loading="true" />
</template>

<script setup>
// UI-3 调查详情（/runs/:runId）：摘要默认 + 执行过程(DAG) + 事件流(Transcript) +
// Claim 与证据(三层) + 报告 + 运行详情。
//
// ===== 事件流真接入（EX-C1 起真流，保留接线）=====
//  1) POST /api/rca-runs/{id}/events/stream-ticket 换 stream ticket（TTL 30s、单次、绑 run+主体）
//  2) new EventSource(.../stream?ticket=...)；初次读取用 REST ?after_seq= 游标做全量种子
//  3) SSE 每条消息写 id: seq，断线重连自动带 Last-Event-ID（服务端归一为 after_seq 游标）
//  4) resync 命名事件 → 停止增量，REST 全量重同步后重开流
//  5) FUT-33：断线不得改变 Run 状态；票单次有效——ES onerror 统一关流重新换票
//
// ===== 命令真接线（POST /api/rca-runs/{id}/commands）=====
//  命令体 {type: CANCEL|HINT|FEEDBACK, idempotencyKey, expectedRevision, payload}；
//  expectedRevision 锚 rca_run.last_event_seq——详情投影不暴露该字段，但事件端点
//  返回 latestSeq（同一计数器），以此作为修订号；SSE 每事件推进 revision。
//  409=修订过期（零副作用）→ 提示并刷新；403=终态越权。按钮仅在 Run 活动态显示。
import { computed, nextTick, onBeforeUnmount, onMounted, reactive, ref, watch } from 'vue'
import { useRoute } from 'vue-router'
import { Loading } from '@element-plus/icons-vue'
import { api } from '../api/client'
import RunDag from '../components/RunDag.vue'
import DetailDrawer from '../components/common/DetailDrawer.vue'
import StatusBadge from '../components/common/StatusBadge.vue'
import EmptyState from '../components/common/EmptyState.vue'
import KvTable from '../components/common/KvTable.vue'
import { STATUS_STYLE, STATUS_ORDER } from '../components/RunDagStatus.js'
import { EVENT_TYPE_ZH, zh } from '../dict/displayNameZh.js'
import { useSseStore } from '../stores/sseStatus.js'
import { fmtTime } from '../utils/format'

const sse = useSseStore()
const route = useRoute()

const detail = ref(null)
const listRow = ref(null) // 队列投影行（耗时/卡点；详情投影无 startedAt 字段）
const loadError = ref('')
const loadedAt = ref(null)

const viewTab = ref('summary')
const viewTabs = [
  { key: 'summary', label: '摘要' },
  { key: 'dag', label: '执行过程' },
  { key: 'events', label: '事件流' },
  { key: 'claims', label: '结论与证据' },
  { key: 'report', label: '报告' },
  { key: 'meta', label: '运行详情' },
]

const dagRef = ref(null)
const abnormalOnly = ref(false)
const neighborFocus = ref(false)
const selectedTaskId = ref(null)

// 事件流筛选状态
const eventScope = ref('all') // all=全部 Run ｜ task=仅当前任务
const errorsOnly = ref(false)
const typeFilter = ref(null)
const search = ref('')
const expandedSeq = ref(null)
const openClaims = reactive(new Set())

const run = computed(() => detail.value?.run)
const tasks = computed(() => detail.value?.tasks ?? [])
const edges = computed(() => detail.value?.edges ?? [])
const claims = computed(() => detail.value?.claims ?? [])
const reportState = computed(() => detail.value?.reportState)
const runActive = computed(() => ['QUEUED', 'RUNNING', 'REPORTING'].includes(run.value?.status))

// 任务规范化：真投影 {id:taskKey, taskId, status, priority, deadline, lease, attempts:次数}；
// name 供 DAG 节点显示，deps/downstream 由 edges 推导
const dagTasks = computed(() => tasks.value.map(t => ({
  ...t,
  name: t.name ?? t.id,
  deps: edges.value.filter(e => e.target === t.id).map(e => taskOf(e.source)).filter(Boolean),
  downstream: edges.value.filter(e => e.source === t.id).map(e => taskOf(e.target)).filter(Boolean),
})))
const selectedTask = computed(() => dagTasks.value.find(t => t.id === selectedTaskId.value) ?? null)

function taskOf(id) {
  const t = tasks.value.find(x => x.id === id)
  return t ? { ...t, name: t.name ?? t.id } : null
}

const statusStyle = STATUS_STYLE
const statusOrder = STATUS_ORDER

// ===== SSE：REST 游标种子 + ticket 换票开流 =====
const liveEvents = ref([])
const revision = ref(null) // rca_run.last_event_seq 锚（命令 expectedRevision）
let es = null
let esRetryTimer = null

const allEvents = computed(() => [...(detail.value?.events ?? []), ...liveEvents.value])
const lastSeq = computed(() => allEvents.value.reduce((m, e) => Math.max(m, e.seq), 0))
const eventTypes = computed(() => [...new Set(allEvents.value.map(e => e.type))])
const recentEvents = computed(() => [...allEvents.value].sort((a, b) => b.seq - a.seq).slice(0, 5))

function appendLive(row) {
  if (typeof row?.seq !== 'number') return
  if (allEvents.value.some(e => e.seq === row.seq)) return
  liveEvents.value.push(row)
  liveEvents.value.sort((a, b) => a.seq - b.seq)
  revision.value = Math.max(revision.value ?? 0, row.seq)
}

async function seedEvents(runId) {
  const page = await api(`/rca-runs/${runId}/events`, { params: { after_seq: 0, limit: 200 } })
  detail.value.events = page.events ?? []
  if (typeof page.latestSeq === 'number') revision.value = page.latestSeq
}

function closeStream() {
  clearTimeout(esRetryTimer)
  esRetryTimer = null
  if (es) { es.close(); es = null }
  sse.setDisconnected()
}

function openStream(runId, delayMs = 800) {
  closeStream()
  sse.setConnecting()
  esRetryTimer = setTimeout(async () => {
    try {
      const { ticket } = await api(`/rca-runs/${runId}/events/stream-ticket`, { method: 'POST' })
      es = new EventSource(`/api/rca-runs/${runId}/events/stream?ticket=${encodeURIComponent(ticket)}`)
      es.onopen = () => sse.setConnected()
      es.onmessage = ev => {
        try {
          appendLive(JSON.parse(ev.data))
          sse.markEvent()
        } catch { /* 心跳/非 JSON 帧忽略 */ }
      }
      // 服务端判定客户端游标过旧：停增量、全量重同步后重新开流
      es.addEventListener('resync', async () => {
        closeStream()
        try { await seedEvents(runId) } finally { openStream(runId) }
      })
      // 票 TTL 30s 单次：浏览器自动重连带旧票必败——统一关流、重新换票开流
      es.onerror = () => { sse.setDisconnected(); openStream(runId) }
    } catch {
      openStream(runId, 3000)
    }
  }, delayMs)
}

const sseStatusText = computed(() => ({
  connected: '实时 已连接', connecting: '实时 连接中', disconnected: '实时 已断开',
}[sse.status] ?? '实时 已断开'))

// ===== Transcript：新事件不打断滚动 =====
const evListRef = ref(null)
const newCount = ref(0)
let nearBottom = true

function onEvScroll() {
  const el = evListRef.value
  if (!el) return
  nearBottom = el.scrollHeight - el.scrollTop - el.clientHeight < 40
  if (nearBottom) newCount.value = 0
}

function scrollEvToBottom() {
  const el = evListRef.value
  if (el) el.scrollTop = el.scrollHeight
  newCount.value = 0
  nearBottom = true
}

watch(() => liveEvents.value.length, () => {
  if (viewTab.value !== 'events') return
  if (nearBottom) nextTick(scrollEvToBottom)
  else newCount.value++
})

const filteredEvents = computed(() => allEvents.value.filter(e => {
  if (eventScope.value === 'task' && e.taskId !== selectedTask.value?.taskId) return false
  if (errorsOnly.value && e.level !== 'error') return false
  if (typeFilter.value && e.type !== typeFilter.value) return false
  const q = search.value.trim().toLowerCase()
  if (q && !e.type.toLowerCase().includes(q) && !String(e.seq).includes(q)) return false
  return true
}))

function eventZh(type) { return zh(EVENT_TYPE_ZH, type) }
function taskNameOf(ev) {
  if (!ev.taskId) return null
  return tasks.value.find(t => t.taskId === ev.taskId)?.id ?? null
}

// ===== Claim 三层分级：证据 / 假设 / 结论 =====
const claimGroups = computed(() => {
  const groups = { conclusion: [], hypothesis: [], evidence: [] }
  for (const c of claims.value) {
    const kind = String(c.kind ?? '')
    if (/evidence|证据/i.test(kind)) groups.evidence.push(c)
    else if (/hypoth|假设/i.test(kind)) groups.hypothesis.push(c)
    else groups.conclusion.push(c)
  }
  return [
    { key: 'conclusion', title: '结论', items: groups.conclusion, emptyText: '暂无结论——原因待确认' },
    { key: 'hypothesis', title: '假设', items: groups.hypothesis, emptyText: '暂无假设' },
    { key: 'evidence', title: '证据', items: groups.evidence, emptyText: '暂无证据' },
  ]
})
const currentConclusion = computed(() =>
  claims.value.find(c => c.current && !/evidence|证据|hypoth|假设/i.test(String(c.kind ?? ''))) ?? null)
const pendingHypotheses = computed(() =>
  claims.value.filter(c => /hypoth|假设/i.test(String(c.kind ?? '')) && !/validated|confirmed/i.test(String(c.verdict ?? ''))))

function toggleClaim(id) {
  openClaims.has(id) ? openClaims.delete(id) : openClaims.add(id)
}

// ===== 运行详情 =====
const runHeadKv = computed(() => ({
  '调查 ID': run.value?.id,
  '告警 ID': run.value?.incident,
  '状态': `${run.value?.status}（${listRow.value?.stageZh ?? '—'}）`,
  '严重度': run.value?.severity ?? '投影未提供',
  '引擎': run.value?.engine ?? '—',
  '配置摘要': run.value?.config ?? '—',
  '负责人': '认领面未落码',
  '事件游标（revision）': revision.value ?? '—',
}))

// ===== 干预命令（真端点：幂等键 + expectedRevision=事件游标） =====
const cmdPending = ref(false)

async function submitCommand(type, payload = {}) {
  if (revision.value == null) {
    ElMessage.warning('事件游标尚未就绪，请稍后重试')
    return
  }
  cmdPending.value = true
  try {
    const res = await api(`/rca-runs/${route.params.runId}/commands`, {
      method: 'POST',
      body: {
        type,
        idempotencyKey: crypto.randomUUID(),
        expectedRevision: revision.value,
        payload,
      },
    })
    ElMessage.success(res.state === 'APPLIED' ? '命令已生效' : '命令已受理')
    await reload()
  } catch (e) {
    const st = e?.response?.status
    if (st === 409) {
      ElMessage.warning('调查状态已变化（修订号过期），已为你刷新')
      await reload()
    } else if (st === 403) {
      ElMessage.error('终态调查不接受该命令')
    } else {
      ElMessage.error('命令提交失败：' + (e?.response?.data?.error ?? '网络异常'))
    }
  } finally {
    cmdPending.value = false
  }
}

async function submitCancel() {
  try {
    await ElMessageBox.confirm('取消后进行中的任务将被终止，该操作会留痕审计。', '取消调查', {
      type: 'warning', confirmButtonText: '取消调查', cancelButtonText: '保留',
    })
  } catch { return }
  submitCommand('CANCEL')
}

async function submitHint() {
  let text
  try {
    const { value } = await ElMessageBox.prompt(
      '线索将以 UNTRUSTED（不可信输入）身份进入调查上下文，仅作参考，不会被当作事实。',
      '补充线索',
      { inputPlaceholder: '例如：昨晚 22:00 有发布变更', confirmButtonText: '提交', cancelButtonText: '取消' },
    )
    text = value?.trim()
  } catch { return }
  if (text) submitCommand('HINT', { text })
}

async function submitFeedback() {
  let text
  try {
    const { value } = await ElMessageBox.prompt('对该调查的报告质量反馈（留痕审计）。', '报告反馈', {
      inputPlaceholder: '例如：结论与证据一致，可以发布', confirmButtonText: '提交', cancelButtonText: '取消',
    })
    text = value?.trim()
  } catch { return }
  if (text) submitCommand('FEEDBACK', { text })
}

// ===== 交互 =====
function switchTab(key) {
  viewTab.value = key
  if (key === 'events') nextTick(scrollEvToBottom)
}

function onSelectTask(id) {
  selectedTaskId.value = id
  if (id) eventScope.value = 'task'
}

function shortId(id) { return id ? String(id).slice(0, 8) : '—' }

function copyTaskLink() {
  const url = `${location.origin}/runs/${route.params.runId}?task=${selectedTaskId.value}`
  navigator.clipboard?.writeText(url).then(
    () => ElMessage.success('任务链接已复制'),
    () => ElMessage.info(`任务链接：${url}`),
  )
}

function viewTaskEvents() {
  eventScope.value = 'task'
  switchTab('events')
}

function downloadEvents() {
  const blob = new Blob([JSON.stringify(filteredEvents.value, null, 2)], { type: 'application/json' })
  const a = document.createElement('a')
  a.href = URL.createObjectURL(blob)
  a.download = `${route.params.runId}-events-whitelist.json`
  a.click()
  URL.revokeObjectURL(a.href)
}

async function loadDetail() {
  loadError.value = ''
  try {
    detail.value = await api(`/rca-runs/${route.params.runId}`)
    loadedAt.value = new Date().toISOString()
  } catch (e) {
    loadError.value = e?.response?.data?.error
      ? `调查加载失败：${e.response.data.error}`
      : '调查加载失败（后端不可达或参数非法）'
    return
  }
  // 耗时/卡点：详情投影无时间字段，从队列投影行取（失败不阻塞）
  api('/rca-runs').then(d => {
    listRow.value = (d.rows ?? []).find(r => r.id === route.params.runId) ?? null
  }).catch(() => { listRow.value = null })
}

async function reload() {
  await loadDetail()
  if (detail.value) {
    try { await seedEvents(route.params.runId) } catch { /* SSE resync 兜底 */ }
  }
}

onMounted(async () => {
  await loadDetail()
  if (!detail.value) return
  // 默认选中运行中任务
  selectedTaskId.value =
    dagTasks.value.find(t => t.status === 'RUNNING')?.id ?? dagTasks.value[0]?.id ?? null
  try { await seedEvents(route.params.runId) } catch { /* 种子失败不阻塞，SSE resync 兜底 */ }
  openStream(route.params.runId)
})

onBeforeUnmount(() => closeStream())
</script>

<style scoped>
.run-page { display: flex; flex-direction: column; gap: var(--section-gap); }
.crumb {
  display: flex; justify-content: space-between; flex-wrap: wrap; gap: 4px 12px;
  font-size: var(--fs-aux); color: var(--ink-2);
}
.crumb .sep { color: var(--line-strong); margin: 0 6px; }

.runhead {
  display: flex; align-items: center; gap: 14px; flex-wrap: wrap;
  padding: 12px var(--card-pad); font-size: var(--fs-body);
}
.inc-link { font-weight: 600; font-size: var(--fs-section); }
.mini { font-size: var(--fs-aux); color: var(--ink-2); }
.blocker { color: var(--warn); max-width: 320px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.ops { margin-left: auto; display: inline-flex; gap: 8px; flex-wrap: wrap; }

.tabs { display: flex; gap: 2px; padding: 4px 10px 0; }
.tab {
  border: none; background: none; cursor: pointer;
  padding: 8px 14px; font-size: var(--fs-body); color: var(--ink-2);
  border-bottom: 2px solid transparent; margin-bottom: -1px;
}
.tab:hover { color: var(--brand); }
.tab.cur { color: var(--brand); font-weight: 700; border-bottom-color: var(--brand); }

.panel { padding: 14px var(--card-pad); }
.lbl { font-size: var(--fs-aux); color: var(--ink-2); margin-bottom: 8px; }
.muted { color: var(--ink-2); font-size: var(--fs-body); }
.flex-spacer { flex: 1; }
.line-item { font-size: var(--fs-body); line-height: 1.9; }
.seq { color: var(--ink-2); font-size: var(--fs-aux); margin-right: 6px; }
.conclusion { font-size: var(--fs-body); }
.conclusion-pending { display: flex; align-items: center; gap: 10px; }
.progress-line { font-size: var(--fs-body); }
.blocker-box {
  margin-top: 8px; font-size: var(--fs-aux); color: var(--warn);
  background: var(--warn-bg); border: 1px solid #ecd9a0; border-radius: var(--radius-ctl); padding: 6px 10px;
}

.toolbar {
  display: flex; align-items: center; gap: 8px; flex-wrap: wrap;
  padding: 8px 12px; font-size: var(--fs-aux); color: var(--ink-2);
}
.tinfo { color: var(--ink-2); font-size: var(--fs-aux); }
.legend { width: 100%; border-collapse: collapse; font-size: var(--fs-aux); }
.legend th, .legend td { border: 1px solid var(--line); padding: 3px 8px; text-align: left; }
.legend th { background: #f0f2f5; color: var(--ink-2); }

.dag-card { padding: 12px; }
.dag-card :deep(.dag-canvas) { height: 560px; }
.note { margin-top: 8px; font-size: var(--fs-aux); color: var(--ink-2); }

.box {
  border: 1px solid var(--line); border-radius: var(--radius); background: #fafbfd;
  padding: 8px 12px; font-size: var(--fs-body); margin-bottom: 10px; line-height: 1.8;
}
.drawer-ops { display: flex; gap: 8px; }

/* ===== 事件流 Transcript ===== */
.ev-toolbar {
  display: flex; align-items: center; gap: 8px; flex-wrap: wrap;
  font-size: var(--fs-aux); color: var(--ink-2);
  border: 1px solid var(--line); border-radius: var(--radius); background: #f4f6fa;
  padding: 8px 10px; margin-bottom: 10px;
}
.ev-toolbar .w-type { width: 170px; }
.ev-toolbar .w-search { width: 170px; }

.ev-scroll-wrap { position: relative; }
.new-bar {
  position: sticky; top: 0; z-index: 2;
  text-align: center; font-size: var(--fs-aux); color: var(--brand);
  background: var(--brand-soft); border: 1px solid #b3d8ff; border-radius: var(--radius-ctl);
  padding: 5px 10px; margin-bottom: 6px; cursor: pointer;
}
.new-bar:hover { background: #d9ecff; }
.fade-enter-active, .fade-leave-active { transition: opacity .2s; }
.fade-enter-from, .fade-leave-to { opacity: 0; }

.ev-list { max-height: 560px; overflow-y: auto; }
.ev-card {
  border: 1px solid var(--line); border-radius: var(--radius); padding: 8px 12px;
  margin-bottom: 6px; background: #fff; cursor: pointer;
}
.ev-card:hover { border-color: var(--brand); }
.ev-card.lv-error { border-left: 3px solid var(--bad); }
.ev-card.lv-warn { border-left: 3px solid var(--sev-p2); }
.ev-card.lv-info { border-left: 3px solid var(--line-strong); }
.ev-line { display: flex; align-items: center; gap: 8px; flex-wrap: wrap; font-size: var(--fs-body); }
.lv-dot { width: 8px; height: 8px; border-radius: 50%; background: var(--line-strong); flex: none; }
.lv-error .lv-dot { background: var(--bad); }
.lv-warn .lv-dot { background: var(--sev-p2); }
.lv-info .lv-dot { background: var(--ok); }
.ev-name { flex: none; }
.ev-summary { color: var(--ink); overflow: hidden; text-overflow: ellipsis; white-space: nowrap; max-width: 46%; }
.ev-meta { margin-left: auto; font-size: var(--fs-aux); color: var(--ink-2); flex: none; }
.ev-detail {
  margin-top: 8px; padding-top: 8px; border-top: 1px dashed var(--line);
}
.ev-detail pre {
  background: #f4f6fa; border: 1px solid var(--line); border-radius: var(--radius-ctl);
  padding: 8px 10px; font-size: var(--fs-aux); overflow-x: auto; margin-bottom: 6px;
}
.ev-empty { text-align: center; color: var(--ink-2); font-size: var(--fs-body); padding: 24px 0; }
.ev-status { margin-top: 10px; font-size: var(--fs-aux); color: var(--ink-2); }

.ev-refs { margin-top: 6px; padding-top: 6px; border-top: 1px dashed var(--line); line-height: 1.8; }

.loading { height: 320px; }
</style>
