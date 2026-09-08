<template>
  <div class="alerts">
    <!-- 面包屑 + 环境行（线框 P2-A .crumb） -->
    <div class="crumb card">
      <span><b>首页</b><span class="sep">/</span>告警<span class="sep">/</span>分类分诊</span>
      <span class="env" v-if="summary">{{ summary.env }} ｜ 数据更新至 {{ summary.updated_at }} ｜ {{ summary.operator }} ▾</span>
    </div>

    <!-- severity/规模计数卡：点击即过滤下方列表（Alerta ASI 先例，E-19 §2.2.6） -->
    <div class="state-grid" v-if="summary">
      <div
        v-for="c in summary.cards" :key="c.key"
        class="card metric" :class="{ clickable: !!c.filter, cur: isCardCur(c) }"
        @click="toggleCardFilter(c)"
      >
        <b>{{ c.label }}</b>
        <div class="num-row"><span class="num">{{ c.value }}</span><span class="sub">｜ {{ c.sub }}</span></div>
      </div>
    </div>

    <!-- 活动/历史等 tabs -->
    <div class="tabs card">
      <span
        v-for="t in summary?.tabs ?? []" :key="t.key"
        :class="{ cur: curTab === t.key }"
        @click="clickTab(t)"
      >{{ t.label }}{{ t.count != null ? ' ' + t.count : '' }}</span>
    </div>

    <div class="card hint">上方 severity/规模计数卡点击即过滤下方列表（Alerta ASI 先例，E-19 §2.2.6）；下方列表区为行业标准「左 facet + 顶搜索栏 + 右表格」形态（E-19 §0-1 / §4-1：Datadog / Keep / Alerta / PagerDuty 四家收敛）。</div>

    <!-- 顶搜索栏：DSL 表达式可存预设视图、列配置按视图记忆（Keep / PagerDuty 先例） -->
    <div class="searchbar card">
      <span>🔍 搜索 DSL</span>
      <input
        v-model="dsl" class="dsl-field" type="text" spellcheck="false"
        placeholder="service:order AND status:FIRING"
        @keyup.enter="applyDsl"
      >
      <button class="btn primary" @click="applyDsl">查询</button>
      <button class="btn" @click="saveView">存为视图</button>
      <span class="bar-sep">｜</span>
      <span>刷新频率</span>
      <select v-model="refresh" class="field-select">
        <option>手动</option><option>30s</option><option>1m</option><option>5m</option>
      </select>
      <span class="muted">（搜索表达式可存预设视图、列配置按视图记忆——Keep / PagerDuty 先例；活动/历史切换见上方 tabs）</span>
    </div>

    <!-- 批量操作条：勾选行首复选框出现 -->
    <div v-if="selected.size" class="batchbar card">
      已选 {{ selected.size }} 条 ｜
      <button class="btn">认领</button>
      <button class="btn">转派</button>
      <button class="btn">静默</button>
      <button class="btn danger" @click="selected.clear()">取消选择</button>
      <span class="muted">认领/转派/解决统一跳转 P4 处置中心执行，本页不另造状态</span>
    </div>

    <div class="main card">
      <!-- 左 facet 栏 -->
      <aside class="facet-rail">
        <template v-for="g in facets" :key="g.key">
          <h4>{{ g.group }}</h4>
          <div
            v-for="it in g.items" :key="it.value"
            class="f-it" :class="{ cur: isFacetCur(g.key, it.value), clickable: isCountable(it.count) }"
            @click="toggleFacet(g.key, it)"
          >
            <span>{{ it.label }}</span><i>{{ it.count }}</i>
          </div>
        </template>
      </aside>

      <!-- 右表格区 -->
      <div class="table-zone">
        <table class="grid">
          <thead>
            <tr>
              <th style="width:24px">
                <input type="checkbox" :checked="allChecked" @change="toggleAll">
              </th>
              <th>severity ｜ 状态</th><th>分类</th><th>告警</th><th>最近</th>
              <th>计数 / 指标</th><th>服务</th><th>归属</th><th>操作</th>
            </tr>
          </thead>
          <tbody>
            <tr
              v-for="r in filteredRows" :key="r.incident_id"
              :class="['sev-' + r.severity.toLowerCase(), { 'row-cur': curRowId === r.incident_id }]"
              @click="selectRow(r)"
            >
              <td @click.stop><input type="checkbox" :checked="selected.has(r.incident_id)" @change="toggleCheck(r.incident_id)"></td>
              <!-- severity 色条（行首 4px）与状态文字 badge 分离表达（E-19 §4-4/§4-5） -->
              <td>
                <span class="tag" :class="sevTag(r.severity)">{{ r.severity }}</span>
                <span class="tag" :class="statusTag(r.status)">{{ statusLabel(r.status) }}</span>
              </td>
              <td>
                <span class="tag" :class="r.category === 'UNKNOWN' ? 't-gray' : 't-blue'">{{ r.category_label }}</span>
                <span v-for="t in r.extra_tags" :key="t" class="tag t-gray">{{ t }}</span>
              </td>
              <td>
                <b>{{ r.alertname }}</b>
                <div v-if="r.classification" class="rule-hit">
                  <span class="tag t-blue">分类规则命中 {{ r.classification.confidence.toFixed(2) }}</span>
                </div>
              </td>
              <td>{{ r.last_at }}</td>
              <td>
                <template v-if="r.counts.received > 1">{{ r.counts.received }}接收/{{ r.counts.events }}事件/{{ r.counts.notified }}通知<br></template>
                <span class="muted">{{ r.metric }}</span>
              </td>
              <td>{{ r.service ?? '—' }}</td>
              <td>
                <template v-if="r.owner">owner={{ r.owner }}</template>
                <template v-else>未分配</template>
                <template v-if="r.run_id"> ｜ run#{{ r.run_id }}</template>
              </td>
              <td @click.stop>
                <router-link class="btn" :to="`/alerts/${r.incident_id}`">打开 →</router-link>
                <button v-if="r.category === 'UNKNOWN'" class="btn" @click="manualClassify(r)">人工归类</button>
              </td>
            </tr>
            <tr v-if="!filteredRows.length">
              <td colspan="9" class="empty">当前过滤条件下无告警；清除 facet 或修改搜索表达式</td>
            </tr>
          </tbody>
        </table>

        <div class="note-box">
          行首 4px severity 色条 + 状态用文字 badge，不整行着色（E-19 §4-4 / §4-5：incident.io / Grafana 高密度表格惯例）；勾选行首复选框出顶部批量操作条，高 severity 恒置顶（PagerDuty urgency 排序）。分类口径：业务=订单失败率/SLO/交易损失；应用=异常/延迟/状态机/进程；依赖=DB/MQ/缓存/下游 API；基础设施=主机/容器/K8s/CPU/内存/盘；网络=连接/DNS/TLS/丢包；数据=质量/新鲜度/流水线；安全=越权/泄露/策略违规；平台/未分类=控制面异常/待人工归类。
        </div>
        <div class="note-box">主分类只承担分诊，每条告警仍保留 team/service/resource_type/env/source/severity 等 facets；复合问题用一个主分类 + 多个标签表达。</div>

        <!-- 分类说明与修正（选中行；与 RCA 根因分类分开） -->
        <div v-if="clsDetail" class="cls-panel">
          <b>分类说明与修正（选中行 {{ curRow?.alertname }}）</b>
          <div class="cls-line">
            <b>主分类：{{ clsDetail.category_label }}</b>
            <small class="muted">taxonomy={{ clsDetail.taxonomy_version }} ｜ rule={{ clsDetail.rule_id }} ｜ source={{ clsDetail.source }} ｜ confidence={{ clsDetail.confidence }} ｜ mapped_at={{ clsDetail.mapped_at }}</small>
          </div>
          <div class="cls-line"><b>命中依据</b>：{{ clsDetail.basis }}</div>
          <div class="cls-line"><b>与根因分类分开</b>：{{ clsDetail.note }}</div>
          <div class="cls-line">
            <button class="btn" @click="overrideTo('APPLICATION')">修正为应用异常</button>
            <button class="btn" @click="addTag('依赖')">增加“依赖”标签</button>
            <small class="muted">{{ clsDetail.override_note }}</small>
          </div>
          <div v-if="overrideMsg" class="cls-line override-msg">✓ {{ overrideMsg }}</div>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup>
// P2-A 告警中心 · 分类分诊（/alerts）：左 facet + 顶搜索栏 + 右表格
// 线框图 v1.6 section#p2 frame A；分类为 AlertTaxonomyV1 投影，只做入口分诊，不冒充根因
import { computed, onMounted, reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { api } from '../api/client'
import {
  fetchAlertsSummary, fetchAlertsFacets, fetchAlerts, fetchClassificationDetail,
} from '../mocks/alerts'

const router = useRouter()

const summary = ref(null)
const facets = ref([])
const rows = ref([])
const dsl = ref('service:order AND status:FIRING')
const refresh = ref('手动')
const curTab = ref('CURRENT')
const cardFilter = ref(null)          // 计数卡过滤，如 { severity: 'P0' }
const facetSel = reactive({})         // facet 多组单选：{ category: 'BUSINESS', status: 'FIRING' }
const selected = reactive(new Set())  // 勾选行
const curRowId = ref(null)
const clsDetail = ref(null)
const overrideMsg = ref('')

onMounted(async () => {
  const [s, f, list] = await Promise.all([
    api('/v1/alerts/summary', { mock: fetchAlertsSummary }),
    api('/v1/alerts/facets', { mock: fetchAlertsFacets }),
    api('/v1/incidents', { mock: fetchAlerts }),
  ])
  summary.value = s
  facets.value = f
  rows.value = list
  // 默认选中首行展示「分类说明与修正」面板（线框示例即 payment-failure）
  if (list.length) selectRow(list[0])
})

// 高 severity 恒置顶（PagerDuty urgency 排序）
const sevRank = { P0: 0, P1: 1, P2: 2 }
const sortedRows = computed(() =>
  [...rows.value].sort((a, b) => (sevRank[a.severity] ?? 9) - (sevRank[b.severity] ?? 9)))

const filteredRows = computed(() => sortedRows.value.filter(r => {
  if (cardFilter.value?.severity && r.severity !== cardFilter.value.severity) return false
  if (cardFilter.value?.status && r.status !== cardFilter.value.status) return false
  if (curTab.value === 'MINE' && r.owner !== 'operator') return false
  if (curTab.value === 'UNCLASSIFIED' && r.category !== 'UNKNOWN') return false
  if (facetSel.category && r.category !== facetSel.category) return false
  if (facetSel.severity && facetSel.severity !== 'RANGE' && r.severity !== facetSel.severity) return false
  if (facetSel.status && r.status !== facetSel.status) return false
  // mock 行均视为 prometheus 来源，来源 facet 选中不缩小结果（联调后按行级 source 字段过滤）
  if (facetSel.service) {
    const map = { payment: 'payment-api', checkout: 'checkout' }
    if (facetSel.service === 'team-pay' && !['payment-api'].includes(r.service)) return false
    if (facetSel.service === 'team-order' && !['order-arena', 'order-db'].includes(r.service)) return false
    if (map[facetSel.service] && r.service !== map[facetSel.service]) return false
  }
  if (dslCond.value?.service && !r.service?.includes(dslCond.value.service)) return false
  if (dslCond.value?.status && r.status !== dslCond.value.status) return false
  if (dslCond.value?.severity && r.severity !== dslCond.value.severity) return false
  return true
}))

const allChecked = computed(() => filteredRows.value.length > 0 && filteredRows.value.every(r => selected.has(r.incident_id)))
const curRow = computed(() => rows.value.find(r => r.incident_id === curRowId.value))

function isCountable(c) { return typeof c === 'number' }
function isCardCur(c) { return c.filter && cardFilter.value?.severity === c.filter.severity && cardFilter.value?.status === c.filter.status }
function toggleCardFilter(c) {
  if (!c.filter) return
  cardFilter.value = isCardCur(c) ? null : { ...c.filter }
}
function isFacetCur(key, value) { return facetSel[key] === value }
function toggleFacet(key, it) {
  if (!isCountable(it.count) || it.count === 0) return
  if (facetSel[key] === it.value) delete facetSel[key]
  else facetSel[key] = it.value
}
function clickTab(t) {
  if (t.route) { router.push(t.route); return }
  if (t.key === 'VIEWS') return
  curTab.value = t.key
  if (t.filter?.category) { facetSel.category = t.filter.category } else if (t.key !== 'UNCLASSIFIED') { delete facetSel.category }
}

// DSL 轻量解析：支持 service:x / status:X / severity:Px 的 AND 组合（与后端搜索 DSL 契约对齐前的 mock 语义）
const dslCond = ref(null)
function applyDsl() {
  const q = dsl.value.trim()
  if (!q) { dslCond.value = null; return }
  dslCond.value = {
    service: q.match(/service:(\S+)/i)?.[1] ?? null,
    status: q.match(/status:(\S+)/i)?.[1]?.toUpperCase() ?? null,
    severity: q.match(/severity:(P\d)/i)?.[1]?.toUpperCase() ?? null,
  }
}

function saveView() { /* 存为视图：视图服务落码后接入，mock 态仅保留交互位 */ }

function toggleCheck(id) { selected.has(id) ? selected.delete(id) : selected.add(id) }
function toggleAll() {
  if (allChecked.value) filteredRows.value.forEach(r => selected.delete(r.incident_id))
  else filteredRows.value.forEach(r => selected.add(r.incident_id))
}

async function selectRow(r) {
  curRowId.value = r.incident_id
  overrideMsg.value = ''
  clsDetail.value = await api(`/v1/incidents/${r.incident_id}/classification`, { mock: () => fetchClassificationDetail(r.incident_id) })
}

// 人工修正：写 versioned override + actor + reason 审计（不改原始 AlertEvent）
function overrideTo(cat) { overrideMsg.value = `已提交 override：修正为 ${cat}（version+1 ｜ actor=operator ｜ 待审计落盘）` }
function addTag(t) { overrideMsg.value = `已提交 override：增加“${t}”标签（version+1 ｜ actor=operator ｜ 待审计落盘）` }
function manualClassify(r) { selectRow(r) }

function sevTag(s) { return s === 'P2' ? 't-gray' : 't-red' }
function statusTag(s) {
  return { FIRING: 't-red', ACK: 't-orange', NODATA: 't-gray', RESOLVED: 't-green' }[s] ?? 't-gray'
}
function statusLabel(s) {
  return { FIRING: 'FIRING', ACK: 'ACK', NODATA: 'NoData', RESOLVED: '已解决' }[s] ?? s
}
</script>

<style scoped>
.alerts { display: flex; flex-direction: column; gap: 8px; }

/* 面包屑 */
.crumb { display: flex; justify-content: space-between; align-items: center; flex-wrap: wrap; gap: 4px 12px; padding: 7px 14px; font-size: 12px; color: var(--ink-2); }
.crumb b { color: var(--head); font-weight: 600; }
.crumb .sep { color: #9aa5b1; margin: 0 4px; }
.crumb .env { font-size: 11.5px; color: var(--ink-2); }

/* 计数卡（可点击过滤） */
.state-grid { display: grid; grid-template-columns: repeat(6, minmax(0, 1fr)); gap: 8px; }
.metric { padding: 10px 12px; border-left: 4px solid var(--brand); }
.metric.clickable { cursor: pointer; transition: border-color .15s, box-shadow .15s; }
.metric.clickable:hover { border-color: var(--brand); box-shadow: 0 0 0 2px var(--brand-soft), var(--shadow); }
.metric.cur { border-color: var(--brand); box-shadow: 0 0 0 2px var(--brand-soft); background: var(--brand-soft); }
.metric .num-row { margin-top: 2px; }
.metric .num { font-size: 20px; font-weight: 700; color: var(--head); }
.metric .sub { font-size: 11.5px; color: var(--ink-2); margin-left: 4px; }

/* tabs */
.tabs { display: flex; flex-wrap: wrap; gap: 5px; padding: 9px 12px 0; background: #f6f8fb; }
.tabs span { border: 1px solid var(--line-strong); border-bottom: none; border-radius: 8px 8px 0 0; padding: 5px 12px; font-size: 11.5px; background: #fff; color: var(--ink-2); cursor: pointer; }
.tabs span.cur { background: var(--brand); color: #fff; border-color: var(--brand); font-weight: 600; }

.hint { padding: 6px 10px; font-size: 11.5px; color: var(--ink-2); background: #f7f9fc; }

/* 搜索栏 */
.searchbar { display: flex; gap: 8px; align-items: center; flex-wrap: wrap; padding: 8px 12px; font-size: 11.5px; }
.dsl-field { min-width: 250px; flex: 1; max-width: 420px; border: 1px solid var(--line-strong); border-radius: 6px; padding: 3px 8px; font-size: 11.5px; color: var(--ink); background: #fff; font-family: inherit; }
.dsl-field:focus { outline: none; border-color: var(--brand); box-shadow: 0 0 0 2px var(--brand-soft); }
.field-select { border: 1px solid var(--line-strong); border-radius: 6px; background: #fff; padding: 3px 8px; font-size: 11.5px; color: var(--ink-2); font-family: inherit; }
.bar-sep { color: var(--line-strong); }
.muted { color: var(--ink-2); }

/* 批量操作条 */
.batchbar { padding: 7px 12px; font-size: 12px; background: var(--brand-soft); border-color: #a8c4f5; }

/* 主区：左 facet + 右表格 */
.main { display: flex; align-items: stretch; }
.facet-rail { width: 180px; flex: none; border-right: 1px solid var(--line); background: #fafbfd; padding: 10px 12px; font-size: 11.5px; border-radius: var(--radius) 0 0 var(--radius); }
.facet-rail h4 { font-size: 11px; color: var(--ink-2); margin: 9px 0 4px; letter-spacing: .4px; }
.facet-rail h4:first-child { margin-top: 0; }
.facet-rail .f-it { display: flex; justify-content: space-between; gap: 6px; padding: 2px 6px; border-radius: 5px; }
.facet-rail .f-it.clickable { cursor: pointer; }
.facet-rail .f-it.clickable:hover { background: #eef1f5; }
.facet-rail .f-it.cur { background: var(--brand-soft); color: var(--brand); font-weight: 600; }
.facet-rail .f-it i { font-style: normal; color: var(--ink-2); }

.table-zone { flex: 1; min-width: 0; padding: 10px 12px; }

/* 表格：行首 4px severity 色条，不整行着色 */
table.grid { width: 100%; border-collapse: collapse; font-size: 11.5px; background: #fff; }
table.grid th { background: #f1f4f9; color: var(--head); text-align: left; padding: 5px 8px; border-bottom: 1px solid var(--line-strong); font-size: 11px; white-space: nowrap; }
table.grid td { padding: 6px 8px; border-bottom: 1px solid #edf0f4; vertical-align: top; }
table.grid tbody tr { cursor: pointer; }
table.grid tbody tr:hover td { background: #f7fafd; }
table.grid tr.row-cur td { background: var(--brand-soft); }
table.grid tr.sev-p0 td:first-child { border-left: 4px solid var(--sev-p0); }
table.grid tr.sev-p1 td:first-child { border-left: 4px solid var(--sev-p1); }
table.grid tr.sev-p2 td:first-child { border-left: 4px solid var(--sev-p2); }
table.grid td.empty { text-align: center; color: var(--ink-2); padding: 18px; }
.rule-hit { margin-top: 2px; }
.rule-hit .tag { margin-right: 0; }
td .btn { text-decoration: none; margin-right: 4px; }

.note-box { border: 1px solid var(--line); border-radius: 8px; background: #f7f9fc; padding: 6px 10px; margin-top: 8px; font-size: 11.5px; line-height: 1.55; color: var(--ink-2); }

/* 分类说明与修正面板 */
.cls-panel { border: 1px solid var(--line); border-radius: 8px; background: #f7f9fc; padding: 8px 10px; margin-top: 8px; font-size: 12px; line-height: 1.6; }
.cls-line { margin-top: 5px; }
.cls-line small { margin-left: 6px; }
.override-msg { color: var(--ok); font-size: 11.5px; }

@media (max-width: 1100px) {
  .state-grid { grid-template-columns: repeat(3, minmax(0, 1fr)); }
  .main { flex-direction: column; }
  .facet-rail { width: auto; border-right: none; border-bottom: 1px solid var(--line); border-radius: var(--radius) var(--radius) 0 0; }
}
</style>
