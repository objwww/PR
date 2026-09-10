<template>
  <div class="chat-page">
    <PageHeader
      title="通知预览"
      subtitle="演练环境 · 仿企微/钉钉群聊外观——此页证明模板可读，不证明真实企微/钉钉送达"
    >
      <template #actions>
        <el-tag type="warning" effect="plain">演练</el-tag>
        <el-button :loading="loading" @click="load">刷新</el-button>
      </template>
    </PageHeader>

    <div class="card frame">
      <el-tabs v-model="tab" class="page-tabs">
        <!-- ============ 消息预览 ============ -->
        <el-tab-pane label="消息预览" name="chat">
          <div class="chat-frame">
            <!-- D1 群聊容器：顶部群名栏（居中） -->
            <div class="chat-head">
              <span class="chat-title">值班通知群（模拟）</span>
              <span class="chat-sub">D01 演练替身期间：真企微/钉钉未接入，本页为站内模拟</span>
            </div>

            <div ref="scrollBox" class="chat-body" @scroll="onScroll">
              <div class="chat-col">
                <div v-if="loading && !messages.length" class="empty">加载中…</div>
                <div v-else-if="!messages.length" class="empty">暂无通知消息</div>

                <template v-for="item in chatItems" :key="item.key">
                  <!-- D1：灰色居中时间分隔条（>5min 或跨日出一条） -->
                  <div v-if="item.type === 'time'" class="time-bar">{{ item.text }}</div>

                  <!-- D1：机器人消息=左侧圆形头像 + 昵称（+灰色「机器人」标签，钉钉特征） -->
                  <div v-else class="msg">
                    <span class="avatar">PR</span>
                    <div class="msg-main">
                      <div class="nick">PR 告警中心 <span class="bot-tag">机器人</span></div>

                      <!-- §C text_notice/actionCard 共同骨架：来源行→标题→副标题→正文→跳转行 -->
                      <div class="msg-card">
                        <div class="c-source"><i class="src-ico">PR</i>PR 告警中心</div>
                        <div class="c-title">
                          <template v-if="m_sev(item.msg)">【{{ m_sev(item.msg) }}】</template>{{ item.msg.title }}
                        </div>
                        <div class="c-sub">
                          <span class="tag" :class="item.msg.eventStatus === 'firing' ? 't-page' : 't-res'">
                            {{ item.msg.eventStatus === 'firing' ? '告警' : '恢复' }}
                          </span>
                          {{ sourceLabel(item.msg.source) }} · {{ fmtTime(item.msg.createdAt) }}
                        </div>
                        <!-- D1：白底卡片内渲染 markdown 子集（标题加粗/引用灰竖条/三色 font/@蓝） -->
                        <div class="c-body" v-html="renderMd(item.msg.body)"></div>
                        <!-- 底部跳转行：台账无 runId 可绑——RCA_SYSTEM 行落到告警中心列表，其余来源不出此行 -->
                        <router-link
                          v-if="item.msg.source === 'RCA_SYSTEM'"
                          class="c-jump"
                          to="/alerts"
                        >查看 RCA 报告 <span class="arrow">&gt;</span></router-link>
                      </div>
                    </div>
                  </div>
                </template>
              </div>

              <!-- 阅读旧消息时来了新消息：不强制拉底，出浮动按钮 -->
              <transition name="fade">
                <button v-if="hasNew" class="new-tip" @click="jumpToLatest">有新消息，回到底部</button>
              </transition>
            </div>
          </div>
        </el-tab-pane>

        <!-- ============ 投递记录（duty_delivery 账本，不塞进消息气泡） ============ -->
        <el-tab-pane label="投递记录" name="ledger">
          <el-table :data="ledgerRows" size="small" max-height="560">
            <el-table-column label="通知时间" width="160">
              <template #default="{ row }">{{ fmtTime(row.createdAt) }}</template>
            </el-table-column>
            <el-table-column prop="title" label="通知标题" min-width="200" show-overflow-tooltip />
            <el-table-column prop="channel" label="通道" min-width="120" />
            <el-table-column prop="platform" label="平台" width="100" />
            <el-table-column label="状态" width="100">
              <template #default="{ row }">
                <el-tag size="small" :type="stateType(row.state)" effect="plain">{{ stateLabel(row.state) }}</el-tag>
              </template>
            </el-table-column>
            <el-table-column label="尝试" width="70" align="right">
              <template #default="{ row }">{{ row.attempts }}</template>
            </el-table-column>
            <el-table-column label="送达时间" width="160">
              <template #default="{ row }">{{ row.sentAt ? fmtTime(row.sentAt) : '—' }}</template>
            </el-table-column>
            <el-table-column label="演练" width="80">
              <template #default="{ row }">
                <el-tag v-if="row.drill" size="small" type="warning" effect="plain">演练通道</el-tag>
                <span v-else class="muted">—</span>
              </template>
            </el-table-column>
            <el-table-column label="最近错误" min-width="160" show-overflow-tooltip>
              <template #default="{ row }">{{ row.lastError || '—' }}</template>
            </el-table-column>
            <template #empty>
              <span class="muted">当前消息窗口内无投递行（探针直发链或空通道链不产生台账投递行）</span>
            </template>
          </el-table>
          <div class="ledger-hint">账本状态=duty_delivery 真实六态，随行展示不造假；投递记录仅覆盖当前消息窗口。</div>
        </el-tab-pane>
      </el-tabs>
    </div>
  </div>
</template>

<script setup>
// UI-4 通知预览页（/duty/chat，原群模拟）：保留仿企微/钉钉真群外观 + 演练标识；
// 消息区 720px 居中；投递账本移出气泡到「投递记录」页签；
// 滚动到旧消息时新消息不强制拉底（浮动"有新消息"按钮）。
import { computed, nextTick, onMounted, onUnmounted, ref } from 'vue'
import { api } from '../api/client.js'
import PageHeader from '../components/common/PageHeader.vue'

const POLL_MS = 30000
const PAGE_SIZE = 50
const TIME_GAP_MS = 5 * 60 * 1000

const tab = ref('chat')
const messages = ref([])
const loading = ref(false)
const scrollBox = ref(null)
const hasNew = ref(false)
let timer = null
let atBottom = true
let lastId = null

async function load() {
  if (loading.value) return
  loading.value = true
  try {
    const res = await api('/duty/notifications/feed', { params: { limit: PAGE_SIZE } })
    // 后端 created_at 降序 → 聊天面反转为升序（新消息在下）
    messages.value = (res.messages || []).slice().reverse()
    const newest = messages.value[messages.value.length - 1]?.id ?? null
    const arrived = lastId !== null && newest !== lastId
    lastId = newest
    await nextTick()
    if (!arrived || atBottom) scrollToBottom()
    else hasNew.value = true
  } catch (e) {
    ElMessage.error(e?.response?.data?.error || '加载失败，请重试')
  } finally {
    loading.value = false
  }
}

// 消息流 + 时间分隔条（D1：连续消息间隔 >5min 插灰色居中条）
const chatItems = computed(() => {
  const out = []
  let lastTs = 0
  for (const m of messages.value) {
    const ts = new Date(m.createdAt).getTime()
    if (!lastTs || ts - lastTs > TIME_GAP_MS) {
      out.push({ type: 'time', key: 't-' + m.id, text: fmtDivider(m.createdAt) })
    }
    out.push({ type: 'msg', key: m.id, msg: m })
    lastTs = ts
  }
  return out
})

// 投递记录：窗口内全部消息的真实投递行平铺（新→旧）
const ledgerRows = computed(() => {
  const rows = []
  for (const m of messages.value.slice().reverse()) {
    for (const d of m.deliveries || []) {
      rows.push({
        createdAt: m.createdAt, title: m.title,
        channel: d.channel, platform: d.platform, state: d.state,
        attempts: d.attempts, sentAt: d.sentAt, lastError: d.lastError, drill: d.drill,
      })
    }
  }
  return rows
})

function onScroll() {
  const el = scrollBox.value
  if (!el) return
  atBottom = el.scrollHeight - el.scrollTop - el.clientHeight < 40
  if (atBottom) hasNew.value = false
}

function scrollToBottom() {
  const el = scrollBox.value
  if (el) el.scrollTop = el.scrollHeight
}

async function jumpToLatest() {
  hasNew.value = false
  await nextTick()
  scrollToBottom()
}

// ---------------- markdown 手写子集（D1/§C：标题/粗体/引用/三色 font/链接/@人）——
// 不引 vditor 等依赖；先转义再渲染，v-html 面安全
function escapeHtml(s) {
  return s.replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;')
}

function inlineMd(s) {
  let t = escapeHtml(s)
  // 企微三色 font（橙红 warning=page 级 / 绿 info=恢复 / 灰 comment=元信息）
  t = t.replace(/&lt;font color="(warning|info|comment)"&gt;(.*?)&lt;\/font&gt;/g,
    '<span class="fc-$1">$2</span>')
  t = t.replace(/\[([^\]]+)\]\((https?:\/\/[^\s)]+)\)/g,
    '<a href="$2" target="_blank" rel="noopener">$1</a>')
  t = t.replace(/\*\*([^*]+)\*\*/g, '<b>$1</b>')
  // @人蓝色高亮（D1 样式占位；模板真 @ 走企微 mentioned_list 补发 text，此处只渲染外观）
  t = t.replace(/(^|\s)@([A-Za-z0-9_.\-]+|所有人)/g, '$1<span class="at">@$2</span>')
  return t
}

function renderMd(src) {
  if (!src) return ''
  const out = []
  let inQuote = false
  for (const raw of src.split(/\r?\n/)) {
    const quote = /^>\s?/.test(raw)
    const content = quote ? raw.replace(/^>\s?/, '') : raw
    const heading = /^(#{1,4})\s+(.*)$/.exec(content)
    if (quote) {
      if (!inQuote) { out.push('<div class="md-q">'); inQuote = true }
      out.push(inlineMd(content) + '<br>')
      continue
    }
    if (inQuote) { out.push('</div>'); inQuote = false }
    if (heading) {
      out.push('<div class="md-h">' + inlineMd(heading[2]) + '</div>')
    } else {
      out.push((content ? inlineMd(content) : '') + '<br>')
    }
  }
  if (inQuote) out.push('</div>')
  return out.join('')
}

const m_sev = m => m.severity || ''
const sourceLabel = s => ({ RCA_SYSTEM: '系统派发', MANUAL: '手动测试', GATUS: '探针直发' }[s] || s)
const stateType = s => ({
  SENT: 'success', DEAD: 'danger', RETRY_WAIT: 'warning',
  CLAIMED: 'primary', SUPPRESSED: 'info', PENDING: 'info',
}[s] || 'info')
const stateLabel = s => ({
  SENT: '已送达', DEAD: '失败', RETRY_WAIT: '待重试',
  CLAIMED: '投递中', SUPPRESSED: '已抑制', PENDING: '排队中',
}[s] || s)
const pad = n => String(n).padStart(2, '0')

function fmtDivider(t) {
  const d = new Date(t)
  if (Number.isNaN(d.getTime())) return '--'
  const now = new Date()
  const sameDay = d.toDateString() === now.toDateString()
  const hm = pad(d.getHours()) + ':' + pad(d.getMinutes())
  return sameDay ? hm : `${d.getMonth() + 1}月${d.getDate()}日 ${hm}`
}

const fmtTime = t => (t ? new Date(t).toLocaleString() : '--')

onMounted(() => {
  load()
  timer = setInterval(load, POLL_MS)
})
onUnmounted(() => clearInterval(timer))
</script>

<style scoped>
.chat-page { display: flex; flex-direction: column; gap: var(--section-gap); }

.frame { padding: 8px var(--card-pad) var(--card-pad); }
.page-tabs :deep(.el-tabs__content) { padding-top: 4px; }

.chat-frame {
  display: flex; flex-direction: column;
  height: calc(100vh - 260px); min-height: 420px;
  border: 1px solid var(--line); border-radius: var(--radius); overflow: hidden;
}

/* D1 群名栏：白底居中群名 */
.chat-head {
  padding: 10px 14px; text-align: center;
  border-bottom: 1px solid var(--line); background: #fff;
}
.chat-title { display: block; font-size: 13.5px; font-weight: 600; color: var(--head); }
.chat-sub { display: block; font-size: 11px; color: var(--warn); margin-top: 2px; }

.chat-body { flex: 1; overflow-y: auto; padding: 12px 14px; background: #f2f4f8; position: relative; }
/* 阅读宽度 ~720px 居中 */
.chat-col { max-width: 720px; margin: 0 auto; }

/* 有新消息浮动按钮（阅读旧消息时不强制拉底） */
.new-tip {
  position: sticky; bottom: 12px; left: 50%; transform: translateX(-50%);
  display: block; margin: 0 auto;
  border: none; border-radius: 999px; padding: 6px 16px;
  background: var(--brand); color: #fff; font-size: 12.5px; cursor: pointer;
  box-shadow: 0 2px 8px rgba(30, 60, 120, .3);
}
.fade-enter-active, .fade-leave-active { transition: opacity .2s; }
.fade-enter-from, .fade-leave-to { opacity: 0; }

/* D1 时间分隔条：灰色居中 */
.time-bar { text-align: center; color: #9aa5b1; font-size: 11px; margin: 10px 0 12px; }

.msg { display: flex; gap: 8px; margin-bottom: 14px; align-items: flex-start; }
/* D1 圆形头像（企微/钉钉机器人均为圆形） */
.avatar {
  width: 34px; height: 34px; flex: none; border-radius: 50%;
  background: linear-gradient(135deg, #2b3b52, #3a4d68); color: #fff;
  display: inline-flex; align-items: center; justify-content: center;
  font-size: 12px; font-weight: 700; letter-spacing: .5px;
}
.msg-main { flex: 1; min-width: 0; }
.nick { font-size: 11.5px; color: #7c8794; margin: 2px 0 4px 2px; }
/* D1 钉钉特征：昵称右侧灰色「机器人」小标签 */
.bot-tag {
  display: inline-block; margin-left: 4px; padding: 0 5px; border-radius: 3px;
  background: #e3e7ec; color: #7c8794; font-size: 10px; line-height: 15px; vertical-align: 1px;
}

/* §C template_card/actionCard 骨架：白色圆角卡片 + 1px 浅灰边框 */
.msg-card {
  background: #fff; border: 1px solid var(--line); border-radius: 8px;
  padding: 10px 12px 0; font-size: 12.5px; overflow: hidden;
  box-shadow: 0 1px 2px rgba(20, 35, 60, .05);
}
/* 来源行：16px 圆图标 + 灰小字 */
.c-source { display: flex; align-items: center; gap: 5px; color: #9aa5b1; font-size: 11px; }
.src-ico {
  width: 16px; height: 16px; border-radius: 50%; font-style: normal;
  background: var(--brand-soft); color: var(--brand);
  display: inline-flex; align-items: center; justify-content: center;
  font-size: 8px; font-weight: 700;
}
/* 黑粗一级标题 + 灰副标题 */
.c-title { margin-top: 5px; font-size: 13.5px; font-weight: 700; color: var(--head); }
.c-sub { margin-top: 3px; color: #9aa5b1; font-size: 11.5px; display: flex; align-items: center; gap: 6px; }
.c-body { margin-top: 7px; padding-bottom: 10px; color: var(--ink); line-height: 1.65; word-break: break-word; }
.c-body :deep(.md-h) { font-weight: 700; color: var(--head); margin: 2px 0; }
/* 引用：左侧灰竖条（D1 企微 markdown 引用形态） */
.c-body :deep(.md-q) {
  border-left: 3px solid #d6dbe2; padding: 1px 0 1px 8px; margin: 3px 0;
  color: #5b6572; background: #f7f8fa;
}
/* 三色 font */
.c-body :deep(.fc-warning) { color: #d4380d; font-weight: 600; }
.c-body :deep(.fc-info) { color: #2f9e44; font-weight: 600; }
.c-body :deep(.fc-comment) { color: #8a919c; }
/* @人蓝色高亮 */
.c-body :deep(.at) { color: var(--brand); font-weight: 600; }
.c-body :deep(a) { color: var(--brand); }

/* 底部分隔线 + 跳转指引 + 右箭头（jump_list / singleTitle 共同形态） */
.c-jump {
  display: flex; justify-content: space-between; align-items: center;
  margin: 9px -12px 0; padding: 8px 12px; border-top: 1px solid #edf0f4;
  color: #5b6572; font-size: 12px; text-decoration: none;
}
.c-jump:hover { background: #f7f9fc; color: var(--brand); }
.c-jump .arrow { color: #b6bec9; }

/* 三色 severity 语义：橙红=page（firing/DEAD）、绿=resolved/SENT、灰=元信息 */
.tag.t-page { background: #fdeceb; color: #c0392b; border: 1px solid #f0b8b2; }
.tag.t-res { background: var(--ok-bg); color: var(--ok); border: 1px solid #9ed3ac; }

.ledger-hint { margin-top: 8px; font-size: var(--fs-aux); color: var(--ink-2); }
.muted { color: var(--ink-2); }
.empty { padding: 32px; text-align: center; color: var(--ink-2); font-size: 12px; }
</style>
