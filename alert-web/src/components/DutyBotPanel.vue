<template>
  <div class="bot-panel">
    <!-- 诚实态：195 后端未部署 UX-02 → 404/403，显式横幅 + 输入禁用，不伪造回复 -->
    <el-alert
      v-if="apiDown" type="warning" :closable="false" class="down-banner"
      title="机器人接口依赖后端 UX-02，当前未部署"
      description="会话与消息不可用；后端部署后刷新本面板即可恢复。"
    />
    <el-alert
      v-else-if="sessionFull" type="warning" :closable="false" class="down-banner"
      title="本会话已达上限，请新建会话"
    />
    <div class="sim-note">仿真机器人：只读值班/告警/通知真实投影，不产生真业务副作用</div>

    <!-- 会话栏：会话列表开关 + 当前会话 + 新建 -->
    <div class="session-bar">
      <el-button size="small" :disabled="apiDown" @click="showSessions = !showSessions">
        会话列表
      </el-button>
      <span class="cur-session" :title="activeSession?.title">
        {{ activeSession ? activeSession.title : '未选择会话' }}
      </span>
      <el-button size="small" type="primary" plain :disabled="apiDown" :loading="creating" @click="createSession">
        新建会话
      </el-button>
    </div>

    <!-- 会话列表（键集游标分页） -->
    <div v-if="showSessions" class="session-list">
      <div v-if="sessionsLoading && !sessions.length" class="pane-empty">加载中…</div>
      <div v-else-if="sessionsError" class="pane-empty">
        会话列表加载失败
        <el-button size="small" text type="primary" @click="loadSessions()">重试</el-button>
      </div>
      <template v-else>
        <div v-if="!sessions.length" class="pane-empty">暂无会话——点击「新建会话」开始</div>
        <div
          v-for="s in sessions" :key="s.id" class="session-item"
          :class="{ active: s.id === activeSession?.id }" @click="selectSession(s)"
        >
          <span class="s-title">{{ s.title }}</span>
          <span class="s-time">{{ fmtTime(s.createdAt) }}</span>
        </div>
        <el-button
          v-if="sessionsCursor" size="small" text type="primary"
          :loading="sessionsLoading" @click="loadSessions(true)"
        >加载更多</el-button>
      </template>
    </div>

    <!-- 消息流：用户右 / 机器人左 -->
    <div ref="scrollBox" class="msg-flow">
      <el-button
        v-if="msgCursor" size="small" text type="primary" class="older-btn"
        :loading="msgLoading" @click="loadMessages(true)"
      >加载更早消息</el-button>

      <div v-if="msgLoading && !messages.length" class="pane-empty">加载中…</div>
      <div v-else-if="!activeSession && !apiDown" class="pane-empty">请先选择或新建会话</div>
      <div v-else-if="activeSession && !messages.length" class="pane-empty">
        暂无消息——试着问「今晚谁值班」「现在有哪些告警」「通知都发出去了吗」
      </div>

      <div v-for="m in messages" :key="m.key" class="msg-row" :class="m.role">
        <div class="bubble">
          <div class="content">{{ m.content }}</div>
          <!-- references_json：incident 实体渲染为告警详情跳转链接，其余类型随行标注不伪造跳转 -->
          <div v-if="refsOf(m).length" class="refs">
            <template v-for="(r, i) in refsOf(m)" :key="i">
              <router-link v-if="r.type === 'incident'" class="ref-link" :to="`/alerts/${r.id}`">
                查看告警详情
              </router-link>
              <span v-else class="ref-chip">{{ refLabel(r) }}</span>
            </template>
          </div>
          <div class="meta">
            <span class="time">{{ fmtTime(m.createdAt) }}</span>
            <span v-if="m.status === 'sending'" class="sending">发送中…</span>
            <template v-if="m.status === 'failed'">
              <span class="fail">发送失败</span>
              <el-button size="small" text type="danger" @click="retry(m)">重试</el-button>
            </template>
          </div>
        </div>
      </div>
    </div>

    <!-- 输入区：400 字符计数，回车发送（Shift+Enter 换行），发送中禁用防重复 -->
    <div class="input-area">
      <el-input
        v-model="input" type="textarea" :rows="2" maxlength="400"
        :disabled="inputDisabled"
        :placeholder="inputDisabled ? '机器人不可用' : '输入问题，回车发送（Shift+Enter 换行）'"
        @keydown.enter.exact.prevent="send"
      />
      <div class="input-foot">
        <span class="count" :class="{ over: input.length >= 400 }">{{ input.length }}/400</span>
        <el-button type="primary" :disabled="sendDisabled" :loading="sending" @click="send">发送</el-button>
      </div>
    </div>
  </div>
</template>

<script setup>
// UX-02 值班仿真机器人对话窗（/api/v1/duty-bot/**，契约见 docs/告警-UX02-仿真机器人-v1.md）。
// 幂等：clientMessageId=crypto.randomUUID，重试沿用同一 id（后端幂等重放 replayed=true 原样返回）。
// 诚实降级：接口 404/403 → apiDown 横幅 + 输入禁用，消息只标「发送失败」不伪造机器人回复。
import { computed, nextTick, onMounted, ref } from 'vue'
import { api } from '../api/client.js'
import { fmtTime } from '../utils/format'

const MAX_LEN = 400

const apiDown = ref(false)
const sessionFull = ref(false)
const sessions = ref([])
const sessionsCursor = ref(null)
const sessionsLoading = ref(false)
const sessionsError = ref(false)
const showSessions = ref(false)
const activeSession = ref(null)
const creating = ref(false)

const messages = ref([])
const msgCursor = ref(null)
const msgLoading = ref(false)
const input = ref('')
const sending = ref(false)
const scrollBox = ref(null)

const inputDisabled = computed(() => apiDown.value || !activeSession.value || sessionFull.value)
const sendDisabled = computed(() => inputDisabled.value || sending.value || !input.value.trim())

const newId = () =>
  (crypto.randomUUID ? crypto.randomUUID()
    : 'xxxxxxxx-xxxx-4xxx-yxxx-xxxxxxxxxxxx'.replace(/[xy]/g, c => {
        const r = (Math.random() * 16) | 0
        return (c === 'x' ? r : (r & 0x3) | 0x8).toString(16)
      }))

const isDown = e => [403, 404].includes(e?.response?.status)

async function loadSessions(append = false) {
  if (sessionsLoading.value) return
  sessionsLoading.value = true
  sessionsError.value = false
  try {
    const res = await api('/v1/duty-bot/sessions', {
      params: { limit: 50, ...(append && sessionsCursor.value ? { cursor: sessionsCursor.value } : {}) },
    })
    const list = res.sessions || []
    sessions.value = append ? sessions.value.concat(list) : list
    sessionsCursor.value = res.nextCursor || null
    if (!activeSession.value && sessions.value.length) selectSession(sessions.value[0])
  } catch (e) {
    if (isDown(e)) apiDown.value = true
    else sessionsError.value = true
  } finally {
    sessionsLoading.value = false
  }
}

async function createSession() {
  if (creating.value) return
  creating.value = true
  try {
    const s = await api('/v1/duty-bot/sessions', { method: 'POST', body: {} })
    sessions.value.unshift(s)
    selectSession(s)
  } catch (e) {
    if (isDown(e)) apiDown.value = true
    else ElMessage.error(e?.response?.data?.error || '新建会话失败，请重试')
  } finally {
    creating.value = false
  }
}

function selectSession(s) {
  activeSession.value = s
  sessionFull.value = false
  showSessions.value = false
  loadMessages()
}

// 后端 seq desc 最新在前 → 反转为升序渲染（新消息在下）；续页 prepend 更早消息
async function loadMessages(older = false) {
  if (!activeSession.value || msgLoading.value) return
  msgLoading.value = true
  try {
    const res = await api(`/v1/duty-bot/sessions/${activeSession.value.id}/messages`, {
      params: { limit: 50, ...(older && msgCursor.value ? { cursor: msgCursor.value } : {}) },
    })
    const batch = (res.messages || []).slice().reverse().map(m => ({ ...m, key: m.id }))
    messages.value = older ? batch.concat(messages.value) : batch
    msgCursor.value = res.nextCursor || null
    if (!older) scrollToBottom()
  } catch (e) {
    if (isDown(e)) apiDown.value = true
    else ElMessage.error(e?.response?.data?.error || '消息加载失败，请重试')
  } finally {
    msgLoading.value = false
  }
}

function send() {
  const content = input.value.trim()
  if (!content || sendDisabled.value) return
  const clientMessageId = newId()
  const optimistic = {
    key: 'local-' + clientMessageId, role: 'user', content,
    createdAt: new Date().toISOString(), status: 'sending', clientMessageId,
  }
  messages.value.push(optimistic)
  input.value = ''
  scrollToBottom()
  doSend(optimistic)
}

// 重试沿用同一 clientMessageId——后端 (session_id, client_message_id) 幂等锚去重
function retry(m) {
  if (sending.value) return
  m.status = 'sending'
  doSend(m)
}

async function doSend(m) {
  sending.value = true
  try {
    const res = await api(`/v1/duty-bot/sessions/${activeSession.value.id}/messages`, {
      method: 'POST', body: { content: m.content, clientMessageId: m.clientMessageId },
    })
    const idx = messages.value.indexOf(m)
    if (idx >= 0) messages.value.splice(idx, 1, { ...res.userMessage, key: res.userMessage.id })
    messages.value.push({ ...res.botMessage, key: res.botMessage.id })
    scrollToBottom()
  } catch (e) {
    m.status = 'failed'
    const st = e?.response?.status
    if (isDown(e)) apiDown.value = true
    else if (st === 409) sessionFull.value = true
    else if (st === 429) ElMessage.warning('消息太频繁，请稍后再试')
    else if (st === 400) ElMessage.error(e?.response?.data?.error || `消息不符合要求（最长 ${MAX_LEN} 字符）`)
    else ElMessage.error(e?.response?.data?.error || '发送失败，请重试')
  } finally {
    sending.value = false
  }
}

// references_json 兼容数组/JSON 字符串两种上线路径；解析失败按无引用处理
function refsOf(m) {
  let r = m.references ?? m.referencesJson
  if (typeof r === 'string') {
    try { r = JSON.parse(r) } catch { return [] }
  }
  return Array.isArray(r) ? r : []
}

const REF_TYPE_LABEL = { notify_outbox: '通知投递行' }
const refLabel = r => `${REF_TYPE_LABEL[r.type] || r.type} ${String(r.id).slice(0, 8)}…`

async function scrollToBottom() {
  await nextTick()
  const el = scrollBox.value
  if (el) el.scrollTop = el.scrollHeight
}

onMounted(loadSessions)
</script>

<style scoped>
.bot-panel { display: flex; flex-direction: column; gap: 10px; height: 100%; }

.down-banner { flex: none; }
.sim-note { flex: none; font-size: var(--fs-aux); color: var(--warn); }

.session-bar { flex: none; display: flex; align-items: center; gap: 8px; }
.cur-session {
  flex: 1; min-width: 0; overflow: hidden; text-overflow: ellipsis; white-space: nowrap;
  font-size: var(--fs-aux); color: var(--ink-2);
}

.session-list {
  flex: none; max-height: 200px; overflow-y: auto;
  border: 1px solid var(--line); border-radius: var(--radius); padding: 6px;
  display: flex; flex-direction: column; gap: 2px;
}
.session-item {
  display: flex; justify-content: space-between; align-items: center; gap: 8px;
  padding: 6px 8px; border-radius: var(--radius-ctl); cursor: pointer; font-size: var(--fs-aux);
}
.session-item:hover { background: var(--brand-soft); }
.session-item.active { background: var(--brand-soft); color: var(--brand); }
.s-title { overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.s-time { flex: none; color: var(--ink-2); }

.msg-flow {
  flex: 1; min-height: 200px; overflow-y: auto;
  border: 1px solid var(--line); border-radius: var(--radius);
  background: #f2f4f8; padding: 12px;
  display: flex; flex-direction: column;
}
.older-btn { align-self: center; }
.pane-empty { padding: 24px 8px; text-align: center; color: var(--ink-2); font-size: var(--fs-aux); }

.msg-row { display: flex; margin-bottom: 12px; }
.msg-row.user { justify-content: flex-end; }
.msg-row.assistant { justify-content: flex-start; }
.bubble {
  max-width: 85%; border-radius: 8px; padding: 8px 10px;
  font-size: 12.5px; line-height: 1.65; word-break: break-word;
}
.msg-row.user .bubble { background: var(--brand); color: #fff; }
.msg-row.assistant .bubble {
  background: #fff; border: 1px solid var(--line); color: var(--ink);
  box-shadow: 0 1px 2px rgba(20, 35, 60, .05);
}
.content { white-space: pre-wrap; }

.refs { margin-top: 6px; display: flex; flex-direction: column; gap: 4px; }
.ref-link { font-size: var(--fs-aux); }
.ref-chip { font-size: var(--fs-aux); color: var(--ink-2); }

.meta { margin-top: 4px; display: flex; align-items: center; gap: 6px; font-size: 11px; }
.msg-row.user .meta { justify-content: flex-end; }
.time { opacity: .75; }
.sending { opacity: .75; }
.fail { color: #ffccc7; font-weight: 600; }
.msg-row.assistant .fail { color: var(--bad); }
.msg-row.user .bubble :deep(.el-button--danger) { color: #ffccc7; }

.input-area { flex: none; }
.input-foot { margin-top: 6px; display: flex; justify-content: space-between; align-items: center; }
.count { font-size: var(--fs-aux); color: var(--ink-2); }
.count.over { color: var(--bad); }
</style>
