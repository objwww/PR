<template>
  <div class="runs-page">
    <div class="crumb">
      <span><b>首页</b><span class="sep">/</span>审查台<span class="sep">/</span>调查队列</span>
      <span class="crumb-right">生产环境 ｜ Asia/Shanghai ｜ 数据更新至 {{ summary?.updatedAt ?? '—' }}</span>
    </div>

    <!-- 状态 tab：计数来自队列投影汇总（GET /rca-runs 配套，未落码走 mock） -->
    <div class="card tabs">
      <button
        v-for="t in tabs"
        :key="t.key"
        class="tab"
        :class="{ cur: tab === t.key }"
        @click="setTab(t.key)"
      >{{ t.label }}<template v-if="t.count != null"> {{ t.count }}</template></button>
      <span class="tab saved" title="保存的视图：后端投影配套能力，未落码">保存的视图 ▾</span>
    </div>

    <div class="card toolbar">
      <label>状态
        <select v-model="fStatus" @change="syncQuery">
          <option value="all">全部</option>
          <option v-for="s in stageOptions" :key="s" :value="s">{{ s }}</option>
        </select>
      </label>
      <label>严重度
        <select v-model="fSev" @change="syncQuery">
          <option value="all">P0–P2</option>
          <option value="P0">P0</option>
          <option value="P1">P1</option>
          <option value="P2">P2</option>
        </select>
      </label>
      <label>负责人
        <select v-model="fOwner" @change="syncQuery">
          <option value="all">我 / 未分配</option>
          <option value="me">我</option>
          <option value="none">未分配</option>
        </select>
      </label>
      <label>时间
        <select v-model="fTime" @change="syncQuery">
          <option value="24h">近 24h</option>
          <option value="1h">近 1h</option>
          <option value="7d">近 7d</option>
        </select>
      </label>
      <button class="btn" :disabled="loading" @click="load">{{ loading ? '刷新中…' : '刷新' }}</button>
    </div>

    <!-- SLA 告警 banner：数值来自投影汇总 -->
    <div v-if="summary" class="banner">
      ⚠ {{ summary.sla.overSla }} 个 Run 超过 SLA，最老 READY 已等待 {{ summary.sla.oldestReadyWait }}；SSE 已连接，列表投影延迟 {{ summary.sla.projectionLag }}。
    </div>

    <div class="card table-card">
      <div class="lbl">调查工作队列（默认按风险与等待时间排序；表格 + 行首 4px severity 色条，E-19 §3-P3）</div>
      <table class="rtable">
        <thead>
          <tr>
            <th>severity</th><th>Run / Incident</th><th>阶段 ｜ 状态</th><th>任务进度</th>
            <th>卡点 / 错误</th><th>负责人</th><th>时长 / SLA</th><th>操作</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="r in filteredRows" :key="r.id" :class="'sev-' + r.severity.toLowerCase()">
            <td><span class="tag" :class="sevTag(r.severity)">{{ r.severity }}</span></td>
            <td class="c-run" @click="openRun(r)"><b>{{ r.id }}</b><br><span class="inc">{{ r.incident }}</span></td>
            <td>{{ r.stage }} <span class="tag" :class="'t-' + r.stageTone">{{ r.stageZh }}</span></td>
            <td>{{ r.progress }}</td>
            <td>{{ r.blocker }}</td>
            <td>{{ r.owner }}</td>
            <td :class="{ over: r.duration.includes('已超') }">{{ r.duration }}</td>
            <td>
              <button v-if="r.action === 'view'" class="btn" @click="openRun(r)">查看 →</button>
              <button v-else-if="r.action === 'claim'" class="btn" @click="claim(r, false)">认领 →</button>
              <button v-else class="btn primary" @click="claim(r, true)">认领并查看 →</button>
            </td>
          </tr>
          <tr v-if="!loading && filteredRows.length === 0">
            <td colspan="8" class="empty">当前筛选无匹配 Run（mock 仅含示意子集，正式投影支持游标分页）</td>
          </tr>
        </tbody>
      </table>
      <div class="note">
        列：严重度｜Run/Incident｜阶段｜任务进度｜卡点/错误｜负责人｜运行时长｜SLA｜预算｜最近事件（预算/最近事件列待投影字段补齐）；支持分页与 URL 持久化筛选
      </div>
    </div>

    <div v-if="notice" class="notice">{{ notice }}</div>
  </div>
</template>

<script setup>
// P3-A 调查队列（/runs）：线框 v1.6 #p3 P3-A
// 数据来源 annot：新增 GET /rca-runs 只读投影（游标分页+稳定排序），后端落码前走 src/mocks/runs.js
import { computed, onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { api } from '../api/client.js'
import { fetchRuns } from '../mocks/runs.js'

const route = useRoute()
const router = useRouter()

const loading = ref(false)
const summary = ref(null)
const rows = ref([])
const notice = ref('')

// 筛选状态经 URL query 持久化（annot「调查队列」：支持分页与 URL 持久化筛选）
const tab = ref(typeof route.query.tab === 'string' ? route.query.tab : 'mine')
const fStatus = ref(route.query.status || 'all')
const fSev = ref(route.query.sev || 'all')
const fOwner = ref(route.query.owner || 'all')
const fTime = ref(route.query.time || '24h')

const tabs = computed(() => [
  { key: 'mine', label: '待我处理', count: summary.value?.buckets.mine },
  { key: 'running', label: '执行中', count: summary.value?.buckets.running },
  { key: 'stuck', label: '卡住', count: summary.value?.buckets.stuck },
  { key: 'failed', label: '失败', count: summary.value?.buckets.failed },
  { key: 'review', label: '待审查', count: summary.value?.buckets.review },
  { key: 'done', label: '已完成', count: null },
])

const stageOptions = computed(() => [...new Set(rows.value.map(r => r.stage))])

const filteredRows = computed(() => rows.value.filter(r => {
  if (tab.value !== 'done' && r.bucket !== tab.value) return false
  if (fSev.value !== 'all' && r.severity !== fSev.value) return false
  if (fOwner.value === 'me' && r.owner !== 'operator') return false
  if (fOwner.value === 'none' && r.owner !== '未分配') return false
  if (fStatus.value !== 'all' && r.stage !== fStatus.value) return false
  return true
}))

function syncQuery() {
  router.replace({
    query: {
      ...(tab.value !== 'mine' ? { tab: tab.value } : {}),
      ...(fStatus.value !== 'all' ? { status: fStatus.value } : {}),
      ...(fSev.value !== 'all' ? { sev: fSev.value } : {}),
      ...(fOwner.value !== 'all' ? { owner: fOwner.value } : {}),
      ...(fTime.value !== '24h' ? { time: fTime.value } : {}),
    },
  })
}

function setTab(key) { tab.value = key; syncQuery() }

function sevTag(sev) {
  // 线框口径：P0/P1 红 tag、P2 灰 tag；severity 的等级区分由行首 4px 色条承担（与状态 badge 分离）
  return sev === 'P2' ? 't-gray' : 't-red'
}

async function load() {
  loading.value = true
  notice.value = ''
  try {
    const data = await api('/rca-runs', { mock: fetchRuns })
    summary.value = data.summary
    rows.value = data.rows
  } finally {
    loading.value = false
  }
}

function openRun(r) {
  router.push({ name: 'run', params: { runId: r.id } })
}

// 认领为命令操作（annot「干预」：命令 API 未落码）；mock 下仅提示，「认领并查看」附带跳转详情
function claim(r, andView) {
  notice.value = `${r.id} 认领命令待后端配套（GET /rca-runs 投影 + 命令 API：幂等键 + expected_revision）落码后生效。`
  if (andView) openRun(r)
}

onMounted(load)
</script>

<style scoped>
.crumb {
  display: flex; justify-content: space-between; flex-wrap: wrap; gap: 4px 12px;
  font-size: 12.5px; color: var(--ink-2); margin-bottom: 10px;
}
.crumb .sep { color: var(--line-strong); margin: 0 6px; }
.crumb-right { color: var(--ink-2); }

.tabs { display: flex; align-items: center; gap: 2px; padding: 4px 10px 0; margin-bottom: 10px; }
.tab {
  border: none; background: none; cursor: pointer;
  padding: 8px 14px; font-size: 13px; color: var(--ink-2);
  border-bottom: 2px solid transparent; margin-bottom: -1px;
}
.tab:hover { color: var(--brand); }
.tab.cur { color: var(--brand); font-weight: 700; border-bottom-color: var(--brand); }
.tab.saved { margin-left: auto; cursor: default; color: var(--ink-2); }

.toolbar {
  display: flex; align-items: center; gap: 14px; flex-wrap: wrap;
  padding: 8px 12px; margin-bottom: 10px; font-size: 12.5px; color: var(--ink-2);
}
.toolbar label { display: inline-flex; align-items: center; gap: 6px; }
.toolbar select {
  border: 1px solid var(--line-strong); border-radius: 6px;
  padding: 2px 8px; font-size: 12px; color: var(--ink); background: #fff;
}

.banner {
  background: var(--warn-bg); border: 1px solid #ecd9a0; color: var(--warn);
  border-radius: var(--radius); padding: 8px 14px; font-size: 12.5px; margin-bottom: 10px;
}

.table-card { padding: 12px; }
.lbl { font-size: 12px; color: var(--ink-2); margin-bottom: 8px; }

.rtable { width: 100%; border-collapse: collapse; font-size: 12.5px; }
.rtable th {
  text-align: left; font-weight: 600; color: var(--ink-2); font-size: 12px;
  background: #f4f6fa; border-bottom: 1px solid var(--line);
  padding: 6px 10px; white-space: nowrap;
}
.rtable td { padding: 7px 10px; border-bottom: 1px solid var(--line); vertical-align: middle; }
.rtable tbody tr:hover { background: #f7f9fc; }
.c-run { cursor: pointer; }
.c-run:hover b { color: var(--brand); }
.inc { color: var(--ink-2); font-size: 11.5px; }
.over { color: var(--bad); font-weight: 600; }
.empty { text-align: center; color: var(--ink-2); padding: 22px 10px; }

/* 行首 4px severity 色条（E-19 §4：色条=severity，badge=状态，分离表达） */
.rtable td:first-child { box-shadow: inset 4px 0 0 transparent; }
tr.sev-p0 td:first-child { box-shadow: inset 4px 0 0 var(--sev-p0); }
tr.sev-p1 td:first-child { box-shadow: inset 4px 0 0 var(--sev-p1); }
tr.sev-p2 td:first-child { box-shadow: inset 4px 0 0 var(--sev-p2); }

.note {
  margin-top: 8px; font-size: 11.5px; color: var(--ink-2);
  background: #f4f6fa; border: 1px solid var(--line); border-radius: 6px; padding: 6px 10px;
}
.notice {
  margin-top: 10px; font-size: 12.5px; color: var(--warn);
  background: var(--warn-bg); border: 1px solid #ecd9a0; border-radius: 6px; padding: 7px 12px;
}
</style>
