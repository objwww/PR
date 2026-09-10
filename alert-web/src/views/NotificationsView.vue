<template>
  <div class="notif-page">
    <PageHeader title="值班通知" :subtitle="`值班收件箱——未读 ${unreadCount} 条 ｜ 30s 自动刷新`">
      <template #actions>
        <el-tooltip
          placement="bottom-end" :show-after="200"
          content="已读仅表示值班人确认看到，不代表接单——接单与处置在「处置中心」完成"
        >
          <el-icon class="help-ico"><QuestionFilled /></el-icon>
        </el-tooltip>
        <el-button :loading="loading" @click="load(true)">刷新</el-button>
      </template>
    </PageHeader>

    <div class="card inbox">
      <div class="inbox-toolbar">
        <el-radio-group v-model="tab" @change="load(true)">
          <el-radio-button value="unread">未读（{{ unreadCount }}）</el-radio-button>
          <el-radio-button value="all">全部</el-radio-button>
        </el-radio-group>
      </div>

      <template v-if="rows.length">
        <div
          v-for="n in rows"
          :key="n.id"
          class="mail"
          :class="{ unread: n.status === 'UNREAD', open: expandedId === n.id }"
        >
          <div class="mail-head" @click="toggle(n)">
            <i class="dot" :class="{ on: n.status === 'UNREAD' }" />
            <div class="mail-lines">
              <div class="line-top">
                <b class="title">{{ n.title }}</b>
                <span class="time">{{ fmtAgo(n.createdAt) }}</span>
              </div>
              <div class="line-bottom">
                <StatusBadge v-if="mapSeverity(n.severity).key" :severity="mapSeverity(n.severity).key" />
                <el-tag v-else size="small" type="info">未分级</el-tag>
                <el-tag size="small" effect="plain">{{ sourceLabel(n.source) }}</el-tag>
                <el-tag size="small" :type="n.eventStatus === 'firing' ? 'danger' : 'success'" effect="plain">
                  {{ n.eventStatus === 'firing' ? '告警' : '恢复' }}
                </el-tag>
                <span class="delivery-brief">{{ deliveryBrief(n) }}</span>
              </div>
            </div>
            <el-icon class="chev" :class="{ open: expandedId === n.id }"><ArrowDown /></el-icon>
          </div>

          <!-- 展开：正文 + 通道尝试记录（数据来自 feed 窗口，窗口外如实标注） -->
          <div v-if="expandedId === n.id" class="mail-body">
            <template v-if="detailOf(n.id)">
              <div class="body-text">{{ detailOf(n.id).body || '（无正文）' }}</div>
              <div class="attempts">
                <div class="attempts-title">通道尝试记录</div>
                <template v-if="detailOf(n.id).deliveries?.length">
                  <div v-for="(d, i) in detailOf(n.id).deliveries" :key="i" class="attempt">
                    <span class="a-channel">{{ d.channel }}</span>
                    <el-tag size="small" :type="stateType(d.state)" effect="plain">{{ stateLabel(d.state) }}</el-tag>
                    <span class="muted">尝试 {{ d.attempts }} 次</span>
                    <span v-if="d.sentAt" class="muted">送达于 {{ fmtTime(d.sentAt) }}</span>
                    <el-tag v-if="d.drill" size="small" type="warning" effect="plain">演练通道</el-tag>
                    <div v-if="d.lastError" class="a-error">{{ d.lastError }}</div>
                  </div>
                </template>
                <div v-else class="muted">
                  {{ n.source === 'GATUS' ? '投递发生在 127 直发链，本台账无投递行' : '派发时通道链为空，只落台账行' }}
                </div>
              </div>
            </template>
            <div v-else class="muted">正文与投递记录不在当前消息窗口内，可在「通知预览」页查看最新窗口。</div>
            <div class="mail-ops">
              <el-button
                v-if="n.status === 'UNREAD'" size="small" type="primary" plain
                @click.stop="markRead(n)"
              >标记已读</el-button>
              <span v-else class="muted">已读</span>
            </div>
          </div>
        </div>
      </template>
      <EmptyState
        v-else-if="!loading" kind="empty"
        :description="tab === 'unread' ? '没有未读通知' : '暂无通知'"
      />
      <div v-else v-loading="true" class="loading-box" />

      <div v-if="nextCursor" class="pager">
        <el-button :loading="loading" @click="load(false)">加载更多</el-button>
      </div>
    </div>
  </div>
</template>

<script setup>
// UI-4 值班通知页（/notifications）：收件箱布局——上行标题+时间，下行级别/来源/事件状态/投递摘要。
// 未读=圆点+字重（不再叠 UNREAD 标签）；未读、告警/恢复、送达三维度分开表达。
// 正文与通道尝试来自 /duty/notifications/feed 窗口（inbox 契约不带 body/deliveries，不新造端点）。
import { onMounted, onUnmounted, ref } from 'vue'
import { ArrowDown, QuestionFilled } from '@element-plus/icons-vue'
import { api } from '../api/client.js'
import PageHeader from '../components/common/PageHeader.vue'
import EmptyState from '../components/common/EmptyState.vue'
import StatusBadge from '../components/common/StatusBadge.vue'
import { mapSeverity } from '../utils/severity'
import { fmtTime, fmtAgo } from '../utils/format'

const POLL_MS = 30000
const PAGE_SIZE = 50
const FEED_WINDOW = 100

const tab = ref('unread')
const rows = ref([])
const unreadCount = ref(0)
const nextCursor = ref(null)
const loading = ref(false)
const expandedId = ref(null)
const feedMap = ref(new Map())
let timer = null

async function load(reset) {
  if (loading.value) return
  loading.value = true
  try {
    const params = { unread: tab.value === 'unread', limit: PAGE_SIZE }
    if (!reset && nextCursor.value) params.cursor = nextCursor.value
    const res = await api('/duty/notifications', { params })
    rows.value = reset ? res.notifications : rows.value.concat(res.notifications)
    unreadCount.value = res.unreadCount ?? 0
    nextCursor.value = res.nextCursor ?? null
  } catch (e) {
    ElMessage.error(e?.response?.data?.error || '加载失败，请重试')
  } finally {
    loading.value = false
  }
}

// feed 窗口：提供正文与真实投递行（duty_delivery 六态 + 演练标记），与通知预览页同源
async function loadFeedWindow() {
  try {
    const res = await api('/duty/notifications/feed', { params: { limit: FEED_WINDOW } })
    feedMap.value = new Map((res.messages || []).map(m => [m.id, m]))
  } catch { feedMap.value = new Map() }
}

const detailOf = id => feedMap.value.get(id)

function toggle(n) {
  expandedId.value = expandedId.value === n.id ? null : n.id
}

async function markRead(n) {
  // 幂等：已读再读 changed:false；行不在场 404 → 刷新列表
  try {
    await api(`/duty/notifications/${n.id}/read`, { method: 'POST' })
    if (n.status === 'UNREAD') unreadCount.value = Math.max(0, unreadCount.value - 1)
    n.status = 'READ'
  } catch (e) {
    if (e?.response?.status === 404) load(true)
    else ElMessage.error('标记已读失败，请重试')
  }
}

// 投递摘要（折叠行一行话）：送达/失败/重试计数；无投递行按来源如实标注
function deliveryBrief(n) {
  const d = detailOf(n.id)
  if (!d) return '投递情况见「通知预览」'
  const ds = d.deliveries || []
  if (!ds.length) return n.source === 'GATUS' ? '127 直发（台账无投递行）' : '无投递记录'
  const sent = ds.filter(x => x.state === 'SENT').length
  const dead = ds.filter(x => x.state === 'DEAD').length
  const pending = ds.length - sent - dead
  const parts = []
  if (sent) parts.push(`${sent} 送达`)
  if (dead) parts.push(`${dead} 失败`)
  if (pending) parts.push(`${pending} 进行中`)
  return `投递 ${parts.join(' · ')}`
}

const sourceLabel = s => ({ RCA_SYSTEM: '系统派发', MANUAL: '手动测试', GATUS: '探针直发' }[s] || s)
const stateType = s => ({
  SENT: 'success', DEAD: 'danger', RETRY_WAIT: 'warning',
  CLAIMED: 'primary', SUPPRESSED: 'info', PENDING: 'info',
}[s] || 'info')
const stateLabel = s => ({
  SENT: '已送达', DEAD: '失败', RETRY_WAIT: '待重试',
  CLAIMED: '投递中', SUPPRESSED: '已抑制', PENDING: '排队中',
}[s] || s)

onMounted(() => {
  load(true)
  loadFeedWindow()
  timer = setInterval(() => { load(true); loadFeedWindow() }, POLL_MS)
})
onUnmounted(() => clearInterval(timer))
</script>

<style scoped>
.notif-page { display: flex; flex-direction: column; gap: var(--section-gap); }
.help-ico { color: var(--ink-2); cursor: help; font-size: 18px; }

.inbox { padding: 0 0 8px; }
.inbox-toolbar { padding: 12px var(--card-pad); border-bottom: 1px solid var(--line); }

/* 收件箱行 */
.mail { border-bottom: 1px solid var(--line); }
.mail:last-of-type { border-bottom: none; }
.mail.open { background: #fafbfd; }
.mail-head {
  display: flex; align-items: flex-start; gap: 10px;
  padding: 12px var(--card-pad); cursor: pointer;
}
.mail-head:hover { background: #f6f9fe; }
.mail.open .mail-head:hover { background: transparent; }
.dot {
  width: 8px; height: 8px; border-radius: 50%; flex: none; margin-top: 9px;
  background: transparent;
}
.dot.on { background: var(--brand); }
.mail-lines { flex: 1; min-width: 0; display: flex; flex-direction: column; gap: 5px; }
.line-top { display: flex; align-items: baseline; gap: 12px; }
.title { font-size: var(--fs-body); color: var(--head); font-weight: 400; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.mail.unread .title { font-weight: 700; }
.time { margin-left: auto; flex: none; font-size: var(--fs-aux); color: var(--ink-2); }
.line-bottom { display: flex; align-items: center; gap: 6px; flex-wrap: wrap; font-size: var(--fs-aux); }
.delivery-brief { color: var(--ink-2); }
.chev { flex: none; margin-top: 6px; color: var(--ink-2); transition: transform .15s; }
.chev.open { transform: rotate(180deg); }

/* 展开区 */
.mail-body { padding: 0 var(--card-pad) 14px 42px; }
.body-text {
  font-size: var(--fs-body); color: var(--ink); white-space: pre-wrap; word-break: break-word;
  background: var(--bg); border-radius: var(--radius-ctl); padding: 10px 12px; line-height: 1.7;
}
.attempts { margin-top: 12px; }
.attempts-title { font-size: var(--fs-aux); font-weight: 600; color: var(--head); margin-bottom: 6px; }
.attempt { display: flex; align-items: center; gap: 8px; flex-wrap: wrap; font-size: var(--fs-aux); padding: 4px 0; }
.a-channel { font-weight: 600; color: var(--ink); }
.a-error { flex-basis: 100%; color: var(--bad); word-break: break-all; }
.muted { color: var(--ink-2); font-size: var(--fs-aux); }
.mail-ops { margin-top: 12px; display: flex; align-items: center; gap: 10px; }

.pager { display: flex; justify-content: center; padding: 12px 0 6px; }
.loading-box { height: 240px; }
</style>
