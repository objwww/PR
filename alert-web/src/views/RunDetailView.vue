<template>
  <div class="run-page" v-if="run">
    <div class="crumb">
      <span>
        <b>首页</b><span class="sep">/</span>审查台<span class="sep">/</span>
        <router-link to="/runs">调查队列</router-link><span class="sep">/</span>
        <b>{{ run.id }}</b>（Incident {{ run.incident }}）
      </span>
      <span class="crumb-right">生产环境 ｜ engine {{ run.engine }} ｜ config {{ run.config }}</span>
    </div>

    <!-- 头部摘要条（E-19 §0-2/§4-2）：状态 badge + 严重度 + 任务进度 + 预算微条；主操作按钮组右置 -->
    <div class="card runhead">
      <span class="tag t-blue">{{ run.status }}</span>
      <span class="tag" :class="run.severity === 'P2' ? 't-gray' : 't-red'">{{ run.severity }}</span>
      <b>{{ run.id }}</b>
      <span class="mini">任务进度 {{ run.progress.done }}/{{ run.progress.total }}（完成 {{ run.progress.done }} / 运行 {{ run.progress.running }} / 阻塞 {{ run.progress.blocked }}）</span>
      <span class="mini budget">
        预算 <span class="bar"><i :style="{ width: run.budget.pct + '%' }"></i></span>
        step {{ run.budget.step.used }}/{{ run.budget.step.total }} ｜ token {{ run.budget.token.used }}/{{ run.budget.token.total }} ｜ tool {{ run.budget.tool.used }}/{{ run.budget.tool.total }}
      </span>
      <span class="ops">
        <button class="btn danger" @click="cmd('取消 Run')">取消 Run</button>
        <button class="btn" @click="cmd('提交 Hint')">提交 Hint（标 UNTRUSTED）</button>
        <button class="btn" @click="cmd('报告 Feedback')">报告 Feedback</button>
      </span>
    </div>
    <div v-if="notice" class="notice">{{ notice }}</div>

    <div class="card tabs">
      <button
        v-for="t in viewTabs"
        :key="t.key"
        class="tab"
        :class="{ cur: viewTab === t.key }"
        @click="viewTab = t.key"
      >{{ t.label }}</button>
    </div>

    <!-- ============ DAG 图 tab ============ -->
    <template v-if="viewTab === 'dag'">
      <div class="card toolbar">
        <router-link class="btn" to="/runs">← 返回调查队列</router-link>
        <span class="tsep">｜</span>
        <button class="btn" @click="dagRef?.fit()">适应画布</button>
        <button class="btn" :class="{ primary: abnormalOnly }" @click="abnormalOnly = !abnormalOnly">仅看异常</button>
        <button class="btn" :class="{ primary: neighborFocus }" @click="neighborFocus = !neighborFocus">上游/下游</button>
        <span class="tsep">｜</span>
        <span class="tinfo">任务：{{ run.progress.total }}（完成 {{ run.progress.done }} / 运行 {{ run.progress.running }} / 阻塞 {{ run.progress.blocked }}）｜ 实时 <i class="live-dot"></i></span>
      </div>

      <div class="dag-row">
        <div class="card dag-col">
          <div class="lbl">
            任务 DAG（节点=任务，边=依赖；颜色+边框+图标+节点内原始状态标签四重编码；同一数据多视图切换，E-19 §0-3）
          </div>
          <RunDag
            ref="dagRef"
            :tasks="tasks"
            :edges="edges"
            :selected-id="selectedTaskId"
            :abnormal-only="abnormalOnly"
            :neighbor-focus="neighborFocus"
            @select="onSelectTask"
          />
          <div class="note">点击节点 → 事件流切「仅当前任务」；SSE 事件驱动节点实时变色（mock 为静态快照）</div>

          <!-- 11 态图例映射表（线框原表，annot：取消/跳过/过期/确定性失败不得混同） -->
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
        </div>

        <!-- 任务诊断抽屉：rca_task + rca_attempt 投影；只收白名单摘要/引用/digest -->
        <div class="card drawer" v-if="selectedTask">
          <div class="drawer-head">
            <span class="lbl">任务诊断抽屉：{{ selectedTask.name }}</span>
            <button class="x" title="关闭" @click="selectedTaskId = null">×</button>
          </div>
          <div class="box">
            <b>{{ selectedTask.status }} {{ statusStyle[selectedTask.status]?.zh }}</b>
            ｜ task#{{ selectedTask.id }} ｜ priority={{ selectedTask.priority }}<br>
            持续 {{ selectedTask.duration }} ｜ deadline {{ selectedTask.deadline }}
            <template v-if="selectedTask.lease">｜ lease {{ selectedTask.lease.worker }} / epoch {{ selectedTask.lease.epoch }}</template>
          </div>
          <div class="box">
            <b>依赖</b>：
            <template v-if="selectedTask.deps.length">
              <span v-for="(d, i) in selectedTask.deps" :key="d.name">
                <template v-if="i">｜ </template>{{ d.name }} {{ statusStyle[d.status]?.icon }} {{ d.req }}
              </span>
            </template>
            <template v-else>（无上游）</template>
            <br>
            <b>下游</b>：
            <template v-if="selectedTask.downstream.length">
              <span v-for="(d, i) in selectedTask.downstream" :key="d.name">
                <template v-if="i">｜ </template>{{ d.name }} {{ d.status }}
              </span>
            </template>
            <template v-else>（无下游）</template>
          </div>
          <div class="lbl sub">Attempt 时间轴</div>
          <div v-for="a in selectedTask.attempts" :key="a.n" class="attempt">
            #{{ a.n }} {{ a.status }} ｜ {{ a.worker }} ｜ {{ a.span }}
            <small v-if="a.error"><br>{{ a.error }} ｜ {{ a.backoff }}</small>
          </div>
          <div v-if="!selectedTask.attempts.length" class="attempt">尚无 Attempt（未领取）</div>
          <div class="box">
            <b>安全输出</b>：input digest={{ selectedTask.safety.inputDigest }}
            ｜ output refs={{ selectedTask.safety.outputRefs.join(',') || '—' }}<br>
            <button class="btn" @click="copyTaskLink">复制任务链接</button>
            <button class="btn" @click="viewTaskEvents">仅看此任务事件</button>
          </div>
        </div>
      </div>
    </template>

    <!-- ============ 事件流 tab ============ -->
    <template v-else-if="viewTab === 'events'">
      <div class="card events-card">
        <div class="lbl">实时事件流（SSE，after_seq 续传）</div>
        <div class="ev-toolbar">
          范围：
          <button class="btn" :class="{ primary: eventScope === 'all' }" @click="eventScope = 'all'">全部 Run</button>
          <button
            class="btn"
            :class="{ primary: eventScope === 'task' }"
            :disabled="!selectedTask"
            @click="eventScope = 'task'"
          >当前任务：{{ selectedTask?.name ?? '—' }}<template v-if="eventScope === 'task'"> ✓</template></button>
          <span class="tsep">｜</span>
          <button class="btn" :class="{ primary: errorsOnly }" @click="errorsOnly = !errorsOnly">仅错误</button>
          <select v-model="typeFilter">
            <option value="all">事件类型 ▾</option>
            <option v-for="t in eventTypes" :key="t" :value="t">{{ t }}</option>
          </select>
          <input v-model="search" class="ev-search" type="text" placeholder="搜索码/seq">
          <span class="tsep">｜</span>
          <button class="btn" @click="paused = !paused">{{ paused ? '恢复滚动' : '暂停滚动' }}</button>
          <button class="btn" @click="downloadEvents">下载白名单事件</button>
        </div>

        <div
          v-for="ev in filteredEvents"
          :key="ev.seq"
          class="ev-item"
          :class="{ err: ev.level === 'error', open: expandedSeq === ev.seq }"
          @click="expandedSeq = expandedSeq === ev.seq ? null : ev.seq"
        >
          <span class="seq">seq{{ ev.seq }}</span>
          <b>{{ eventZh(ev.type) }}</b>
          <code>{{ ev.type }}</code>
          ｜ {{ ev.summary }}
          <span v-if="ev.taskName">｜ task={{ ev.taskName }}</span>
          <div v-if="expandedSeq === ev.seq" class="ev-detail">
            白名单 payload：type={{ ev.type }} ｜ seq={{ ev.seq }} ｜ task_id={{ ev.taskId ?? '—' }} ｜ level={{ ev.level }}<br>
            <small>脱敏红线（§17.9.3）：thought/原始 prompt/secret/完整工具参数永不进前端；关联对象仅白名单引用/digest</small>
          </div>
        </div>
        <div v-if="filteredEvents.length === 0" class="ev-empty">当前筛选无事件</div>

        <div class="ev-status">
          ● 连接中（Last-Event-ID={{ lastSeq }}）｜ 延迟 0.8s ｜ 断线自动回放；检测到 seq 缺口或超窗时停止增量并提示「重新同步」 ｜ 点击事件展开白名单 payload/关联对象
        </div>
      </div>
    </template>

    <!-- ============ Claim与证据 tab ============ -->
    <template v-else-if="viewTab === 'claims'">
      <div class="card events-card">
        <div class="lbl">Claim 与证据（结论可逐条追溯）</div>
        <div v-for="c in claims" :key="c.id" class="box">
          <b>{{ c.kind }}</b>：{{ c.text }} <small>({{ c.code }})</small>
          ｜ {{ c.verdict }} ｜ {{ c.agree }} ｜ {{ c.current ? '当前有效' : '已被取代' }}
          <button class="btn" @click="evOpen = !evOpen">证据 {{ c.evidences.length }} 条 →</button>
          <div v-if="evOpen" class="ev-refs">
            <div v-for="e in c.evidences" :key="e.id">
              {{ e.id }} {{ e.desc }} {{ e.digest }}<template v-if="e.window"> [{{ e.window }}]</template>
            </div>
            <small>来源/时间窗/采集状态可展开（证据详情校验 observed_generation / schema_version / payload_digest）</small>
          </div>
        </div>
      </div>
    </template>

    <!-- ============ 报告 tab ============ -->
    <template v-else-if="viewTab === 'report'">
      <div class="card events-card">
        <div class="lbl">报告与发布状态</div>
        <div class="box">未生成——{{ reportState?.note }}</div>
      </div>
    </template>

    <!-- ============ 历史Attempt tab（内容同 DAG 抽屉的 Attempt 时间轴，此处跨任务汇总） ============ -->
    <template v-else-if="viewTab === 'attempts'">
      <div class="card events-card">
        <div class="lbl">历史 Attempt（rca_attempt 投影：worker/lease、错误码、retry/backoff、deadline）</div>
        <table class="atable">
          <thead>
            <tr><th>任务</th><th>Attempt</th><th>状态</th><th>worker</th><th>时间</th><th>错误 / 退避</th></tr>
          </thead>
          <tbody>
            <template v-for="t in tasks" :key="t.id">
              <tr v-for="a in t.attempts" :key="t.id + '#' + a.n">
                <td>{{ t.name }}</td><td>#{{ a.n }}</td>
                <td><code>{{ a.status }}</code></td><td>{{ a.worker }}</td><td>{{ a.span }}</td>
                <td>{{ a.error ? a.error + ' ｜ ' + a.backoff : '—' }}</td>
              </tr>
            </template>
            <tr v-if="!tasks.some(t => t.attempts.length)"><td colspan="6" class="empty">暂无 Attempt 记录</td></tr>
          </tbody>
        </table>
      </div>
    </template>
  </div>

  <div v-else class="loading card">加载中…</div>
</template>

<script setup>
// P3-B 调查详情（/runs/:runId）：线框 v1.6 #p3 P3-B
//
// ===== 事件流真实接入点（后端落码后替换下方静态 mock + 定时器模拟）=====
//  1) POST /rca-runs/{id}/stream-ticket 换取 stream ticket（TTL 30s、单次、绑 run+主体；
//     annot「实时鉴权」：禁止 URL 带长效 token）
//  2) new EventSource(`/api/rca-runs/${id}/events?ticket=...`)；初次读取用 ?after_seq= 游标
//  3) SSE 每条消息写 `id: seq`，浏览器断线重连自动带 Last-Event-ID，
//     服务端统一转换为同一 after_seq 游标（annot「事件流」）
//  4) 检测到 seq 缺口或超窗 → 先停止增量追加，再提示「重新同步」做全量刷新
//  5) FUT-33：断线不得改变 Run 状态；重连带 after_seq 续传
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { useRoute } from 'vue-router'
import { api } from '../api/client.js'
import { fetchRunDetail } from '../mocks/runs.js'
import RunDag from '../components/RunDag.vue'
import { STATUS_STYLE, STATUS_ORDER } from '../components/RunDagStatus.js'
import { EVENT_TYPE_ZH, zh } from '../dict/displayNameZh.js'

// 事件类型中文名：M7-09 已统一抽至 src/dict/displayNameZh.js（版本化词典 display_name_zh，
// 机器码英文为稳定契约不变）

const route = useRoute()

const detail = ref(null)
const notice = ref('')

const viewTab = ref('dag')
const viewTabs = [
  { key: 'dag', label: 'DAG 图' },
  { key: 'events', label: '事件流' },
  { key: 'claims', label: 'Claim与证据' },
  { key: 'report', label: '报告' },
  { key: 'attempts', label: '历史Attempt' },
]

const dagRef = ref(null)
const abnormalOnly = ref(false)
const neighborFocus = ref(false)
const selectedTaskId = ref(null)

// 事件流筛选状态
const eventScope = ref('all') // all=全部 Run ｜ task=仅当前任务
const errorsOnly = ref(false)
const typeFilter = ref('all')
const search = ref('')
const paused = ref(false)
const expandedSeq = ref(null)
const evOpen = ref(true)

const run = computed(() => detail.value?.run)
const tasks = computed(() => detail.value?.tasks ?? [])
const edges = computed(() => detail.value?.edges ?? [])
const claims = computed(() => detail.value?.claims ?? [])
const reportState = computed(() => detail.value?.reportState)
const selectedTask = computed(() => tasks.value.find(t => t.id === selectedTaskId.value) ?? null)

const statusStyle = STATUS_STYLE
const statusOrder = STATUS_ORDER

// ===== SSE 模拟：静态 mock 事件数组 + 定时器逐条追加（真实接入点见文件头注释） =====
const liveEvents = ref([])
const livePool = ref([])
let liveTimer = null

const allEvents = computed(() => [...(detail.value?.events ?? []), ...liveEvents.value])
const lastSeq = computed(() => allEvents.value.reduce((m, e) => Math.max(m, e.seq), 0))
const eventTypes = computed(() => [...new Set(allEvents.value.map(e => e.type))])

const filteredEvents = computed(() => allEvents.value.filter(e => {
  // annot「事件流」：任务事件带 task_id，Run 级事件只在「全部 Run」范围出现
  if (eventScope.value === 'task' && e.taskId !== selectedTaskId.value) return false
  if (errorsOnly.value && e.level !== 'error') return false
  if (typeFilter.value !== 'all' && e.type !== typeFilter.value) return false
  const q = search.value.trim().toLowerCase()
  if (q && !e.type.toLowerCase().includes(q) && !String(e.seq).includes(q)) return false
  return true
}))

function eventZh(type) { return zh(EVENT_TYPE_ZH, type) }

// 点击 DAG 节点 → 事件流切「仅当前任务」（线框 DAG tab 下方注释框）
function onSelectTask(id) {
  selectedTaskId.value = id
  if (id) eventScope.value = 'task'
}

// 干预命令：annot「干预」——取消/Hint/Feedback 依赖 AM5 M5-14 命令 API（未落码；
// 幂等键 + expected_revision 旧版本拒绝），此处仅示意
function cmd(name) {
  notice.value = `「${name}」命令依赖 AM5 M5-14 命令 API（幂等键 + expected_revision），后端落码前为示意操作，不产生真实效果。`
}

function copyTaskLink() {
  const url = `${location.origin}/runs/${route.params.runId}?task=${selectedTaskId.value}`
  navigator.clipboard?.writeText(url).then(
    () => { notice.value = `任务链接已复制：${url}` },
    () => { notice.value = `任务链接：${url}` },
  )
}

function viewTaskEvents() {
  eventScope.value = 'task'
  viewTab.value = 'events'
}

function downloadEvents() {
  const blob = new Blob([JSON.stringify(filteredEvents.value, null, 2)], { type: 'application/json' })
  const a = document.createElement('a')
  a.href = URL.createObjectURL(blob)
  a.download = `${route.params.runId}-events-whitelist.json`
  a.click()
  URL.revokeObjectURL(a.href)
}

onMounted(async () => {
  detail.value = await api(`/rca-runs/${route.params.runId}`, {
    mock: () => fetchRunDetail(route.params.runId),
  })
  // 默认选中运行中任务（线框抽屉示例为根因 Agent）
  selectedTaskId.value =
    tasks.value.find(t => t.status === 'RUNNING')?.id ?? tasks.value[0]?.id ?? null
  eventScope.value = 'task'
  // SSE 增量模拟：每 5s 追加一条 liveEvents（paused 时停止追加）
  livePool.value = [...(detail.value.liveEvents ?? [])]
  liveTimer = setInterval(() => {
    if (paused.value || !livePool.value.length) return
    liveEvents.value.push(livePool.value.shift())
  }, 5000)
})

onBeforeUnmount(() => clearInterval(liveTimer))
</script>

<style scoped>
.crumb {
  display: flex; justify-content: space-between; flex-wrap: wrap; gap: 4px 12px;
  font-size: 12.5px; color: var(--ink-2); margin-bottom: 10px;
}
.crumb .sep { color: var(--line-strong); margin: 0 6px; }

.runhead {
  display: flex; align-items: center; gap: 10px; flex-wrap: wrap;
  padding: 10px 14px; margin-bottom: 8px; font-size: 13px;
}
.mini { font-size: 12px; color: var(--ink-2); }
.budget { display: inline-flex; align-items: center; gap: 6px; }
.bar {
  display: inline-block; width: 72px; height: 8px; border-radius: 4px;
  background: #e6eaf1; overflow: hidden; vertical-align: middle;
}
.bar i { display: block; height: 100%; background: var(--brand); }
.ops { margin-left: auto; display: inline-flex; gap: 8px; flex-wrap: wrap; }

.notice {
  font-size: 12.5px; color: var(--warn); background: var(--warn-bg);
  border: 1px solid #ecd9a0; border-radius: 6px; padding: 7px 12px; margin-bottom: 8px;
}

.tabs { display: flex; gap: 2px; padding: 4px 10px 0; margin-bottom: 10px; }
.tab {
  border: none; background: none; cursor: pointer;
  padding: 8px 14px; font-size: 13px; color: var(--ink-2);
  border-bottom: 2px solid transparent; margin-bottom: -1px;
}
.tab:hover { color: var(--brand); }
.tab.cur { color: var(--brand); font-weight: 700; border-bottom-color: var(--brand); }

.toolbar {
  display: flex; align-items: center; gap: 8px; flex-wrap: wrap;
  padding: 8px 12px; margin-bottom: 10px; font-size: 12.5px; color: var(--ink-2);
}
.tsep { color: var(--line-strong); }
.tinfo { color: var(--ink-2); }
.live-dot {
  display: inline-block; width: 8px; height: 8px; border-radius: 50%;
  background: var(--ok); vertical-align: middle;
}

.dag-row { display: flex; gap: 10px; align-items: flex-start; }
.dag-col { flex: 3; min-width: 0; padding: 12px; }
.lbl { font-size: 12px; color: var(--ink-2); margin-bottom: 8px; }
.lbl.sub { margin-top: 10px; }
.note {
  margin-top: 8px; font-size: 11.5px; color: var(--ink-2);
  background: #f4f6fa; border: 1px solid var(--line); border-radius: 6px; padding: 6px 10px;
}

.legend { width: 100%; border-collapse: collapse; font-size: 11.5px; margin-top: 10px; }
.legend th, .legend td { border: 1px solid var(--line); padding: 3px 8px; text-align: left; }
.legend th { background: #f0f2f5; color: var(--ink-2); }

.drawer { flex: 2; min-width: 260px; padding: 12px; }
.drawer-head { display: flex; justify-content: space-between; align-items: center; }
.x {
  border: none; background: none; font-size: 16px; color: var(--ink-2);
  cursor: pointer; line-height: 1; padding: 2px 6px;
}
.x:hover { color: var(--bad); }
.box {
  border: 1px solid var(--line); border-radius: 6px; background: #fafbfd;
  padding: 7px 10px; font-size: 12px; margin-bottom: 8px; line-height: 1.7;
}
.box .btn { margin-top: 4px; margin-right: 6px; }
.attempt {
  border: 1px solid var(--line); border-radius: 6px; padding: 6px 10px;
  font-size: 12px; margin-bottom: 6px; background: #fff;
}
.attempt small { color: var(--ink-2); }

.events-card { padding: 12px; }
.ev-toolbar {
  display: flex; align-items: center; gap: 8px; flex-wrap: wrap;
  font-size: 12.5px; color: var(--ink-2);
  border: 1px solid var(--line); border-radius: 6px; background: #f4f6fa;
  padding: 7px 10px; margin-bottom: 8px;
}
.ev-toolbar select {
  border: 1px solid var(--line-strong); border-radius: 6px;
  padding: 2px 8px; font-size: 12px; background: #fff; color: var(--ink);
}
.ev-search {
  border: 1px solid var(--line-strong); border-radius: 6px;
  padding: 3px 10px; font-size: 12px; width: 140px;
}

.ev-item {
  border: 1px solid var(--line); border-radius: 6px; padding: 6px 10px;
  font-size: 12.5px; margin-bottom: 6px; background: #fff; cursor: pointer;
}
.ev-item:hover { border-color: var(--brand); }
.ev-item.err { border-left: 3px solid var(--bad); }
.ev-item .seq { color: var(--ink-2); font-size: 11.5px; margin-right: 6px; }
.ev-item code { margin: 0 4px; }
.ev-detail {
  margin-top: 6px; padding-top: 6px; border-top: 1px dashed var(--line);
  font-size: 11.5px; color: var(--ink-2);
}
.ev-empty { text-align: center; color: var(--ink-2); font-size: 12.5px; padding: 18px 0; }
.ev-status {
  font-size: 11.5px; color: var(--ink-2);
  background: #f4f6fa; border: 1px solid var(--line); border-radius: 6px; padding: 6px 10px;
}

.ev-refs {
  margin-top: 6px; padding-top: 6px; border-top: 1px dashed var(--line);
  font-size: 12px; line-height: 1.8;
}
.ev-refs small { color: var(--ink-2); }

.atable { width: 100%; border-collapse: collapse; font-size: 12.5px; }
.atable th {
  text-align: left; font-size: 12px; color: var(--ink-2);
  background: #f4f6fa; border-bottom: 1px solid var(--line); padding: 6px 10px;
}
.atable td { padding: 6px 10px; border-bottom: 1px solid var(--line); }
.empty { text-align: center; color: var(--ink-2); }

.loading { padding: 32px; text-align: center; color: var(--ink-2); }
</style>
