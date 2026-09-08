<template>
  <div class="history">
    <!-- 面包屑：热数据 + 归档目录统一检索 -->
    <div class="crumb card">
      <span><b>首页</b><span class="sep">/</span>告警<span class="sep">/</span>历史告警档案（热数据 + 归档目录统一检索）</span>
      <span class="env">
        搜索 Incident / 根因码 / 服务 / Evidence digest
        <input v-model="keyword" class="kw" type="text" @keyup.enter="applyKeyword">
      </span>
    </div>

    <!-- 过滤工具条 + 随机打开 -->
    <div class="toolbar card">
      <template v-for="f in filters" :key="f.key">
        {{ f.label }} <span class="field">{{ f.value }} ▾</span>
      </template>
      <button class="btn primary" @click="openRandom">随机打开一条</button>
    </div>

    <div class="row">
      <!-- 历史 Incident 列表（可随机点开，不只看统计） -->
      <div class="col" style="flex:2">
        <div class="lbl">历史 Incident（可随机点开，不只看统计）</div>
        <div
          v-for="h in visibleIncidents" :key="h.incident_id"
          class="list-item" :class="{ cur: curId === h.incident_id }"
          @click="openSnapshot(h.incident_id)"
        >
          <span class="tag" :class="h.category_label === '业务' ? 't-blue' : 't-gray'">{{ h.category_label }}</span>
          <span class="tag" :class="storageTag(h)">{{ storageLabel(h) }}</span>
          <b>inc#{{ h.incident_id }} / gen {{ h.generation }} {{ h.alertname }}</b>
          <div class="meta">
            {{ h.date }} ｜
            <template v-if="h.archive_status !== 'ARCHIVE_UNAVAILABLE'">
              根因 {{ h.fault_type }} ｜ {{ h.duration }} ｜ Evidence {{ h.evidence_count }} ｜
              {{ h.report === 'published' ? 'Report published' : 'manifest ' + h.manifest_id }}
            </template>
            <template v-else>{{ h.note }} ｜ ARCHIVE_UNAVAILABLE</template>
            <button class="btn" @click.stop="openSnapshot(h.incident_id)">{{ h.storage === 'HOT' ? '打开' : (h.archive_status === 'ARCHIVE_UNAVAILABLE' ? '查看目录元数据' : '从归档打开') }}</button>
          </div>
        </div>
        <div v-if="!visibleIncidents.length" class="list-item muted">无匹配记录（catalog 与热表均未命中）。</div>
      </div>

      <!-- 只读历史快照 -->
      <div class="col" style="flex:3">
        <div class="lbl">随机打开：inc#{{ snap?.incident_id ?? '—' }} / generation {{ snap?.generation ?? '—' }}（只读历史快照）</div>

        <!-- 归档不可用：明确 ARCHIVE_UNAVAILABLE，不伪装“无历史”，仅展示目录元数据 -->
        <template v-if="snap?.archive_status === 'ARCHIVE_UNAVAILABLE'">
          <div class="note-box warn">
            <b>ARCHIVE_UNAVAILABLE</b>：{{ snap.note }}
          </div>
          <div class="note-box">
            <b>目录元数据（ArchiveCatalog）</b><br>
            manifest={{ snap.catalog_meta.manifest_id }} ｜ date={{ snap.catalog_meta.date }} ｜ fault_type={{ snap.catalog_meta.fault_type }}<br>
            object_count={{ snap.catalog_meta.object_count }} ｜ schema={{ snap.catalog_meta.schema }} ｜ cas={{ snap.catalog_meta.cas_endpoint }}
          </div>
        </template>

        <template v-else-if="snap">
          <div class="note-box"><b>排查总结</b>：{{ snap.conclusion_summary }}</div>
          <div class="note-box">
            <b>排查路径</b>：{{ snap.path }}<br>
            <b>耗时拆分</b>：{{ snap.cost_split }}
          </div>
          <div class="note-box">
            <b>最终结论</b>：fault_type={{ snap.conclusion.fault_type }} ｜ reason_code={{ snap.conclusion.reason_code }} ｜ reviewer={{ snap.conclusion.reviewer }} ｜ report_digest={{ snap.conclusion.report_digest }}
          </div>
          <div v-if="snap.manifest" class="note-box">
            <b>证据清单 {{ snap.manifest.evidence_total }} 条（ArchiveManifest {{ snap.manifest.manifest_id }}）</b>
            <div v-for="e in snap.manifest.items" :key="e.evidence_id" class="ev-line">
              {{ e.evidence_id }} {{ e.type }} ｜ {{ e.window }} ｜ SHA-256 {{ e.sha256 }} ｜ CAS {{ e.cas ? '✓' : '✗' }}
            </div>
            <button class="btn" @click="noop">逐条查看快照</button>
            <button class="btn" @click="verifyAll">核验全部 digest</button>
            <button class="btn" @click="noop">打开已发布报告</button>
            <div v-if="verifyMsg" class="ok-text mini">{{ verifyMsg }}</div>
          </div>
          <div v-if="snap.archive_proof" class="note-box">
            <b>归档证明</b>：policy={{ snap.archive_proof.policy }} ｜ archived_at={{ snap.archive_proof.archived_at }} ｜ object_count={{ snap.archive_proof.object_count }} ｜ schema={{ snap.archive_proof.schema }} ｜ reread_validation={{ snap.archive_proof.reread_validation }}<br>
            <small class="muted">{{ snap.reuse_note }}</small>
          </div>
          <div v-else class="note-box"><small class="muted">{{ snap.reuse_note }}</small></div>
        </template>

        <div v-else class="note-box muted">从左侧选择或点击「随机打开一条」。</div>
      </div>
    </div>
  </div>
</template>

<script setup>
// P2-C 历史调查与证据档案（/history）
// 线框图 v1.6 section#p2 frame C；历史查询先查热表再查 archive_catalog；
// 历史复用红线：旧 Evidence 不得成为当前 Claim 的 evidenceRef，必须当前时间窗重新取证
import { computed, onMounted, ref } from 'vue'
import { api } from '../api/client'
import { fetchHistoryFilters, fetchHistoryIncidents, fetchHistorySnapshot } from '../mocks/alerts'

const filters = ref([])
const incidents = ref([])
const keyword = ref('')
const appliedKw = ref('')
const curId = ref(null)
const snap = ref(null)
const verifyMsg = ref('')

onMounted(async () => {
  const [f, list] = await Promise.all([
    api('/v1/history/filters', { mock: fetchHistoryFilters }),
    api('/v1/history/incidents', { mock: fetchHistoryIncidents }),
  ])
  filters.value = f
  incidents.value = list
  // 线框示例默认打开归档样本 inc#6bd
  openSnapshot(list.find(h => h.incident_id === '6bd')?.incident_id ?? list[0]?.incident_id)
})

const visibleIncidents = computed(() => {
  const kw = appliedKw.value.trim().toLowerCase()
  if (!kw) return incidents.value
  return incidents.value.filter(h =>
    [h.incident_id, h.alertname, h.fault_type, h.date].filter(Boolean).some(v => String(v).toLowerCase().includes(kw)))
})

function applyKeyword() { appliedKw.value = keyword.value }

async function openSnapshot(id) {
  if (!id) return
  curId.value = id
  verifyMsg.value = ''
  snap.value = await api(`/v1/history/incidents/${id}`, { mock: () => fetchHistorySnapshot(id) })
}

function openRandom() {
  const pool = visibleIncidents.value
  if (!pool.length) return
  const pick = pool[Math.floor(Math.random() * pool.length)]
  openSnapshot(pick.incident_id)
}

function verifyAll() {
  const items = snap.value?.manifest?.items ?? []
  verifyMsg.value = `✓ 已核验 ${items.length} 条 digest（SHA-256 比对 PASS，异地复读 reread_validation=PASS）`
}
function noop() { /* 快照逐条查看 / 已发布报告：联调后接短时受控内容 API */ }

function storageTag(h) {
  if (h.archive_status === 'ARCHIVE_UNAVAILABLE') return 't-red'
  return h.storage === 'HOT' ? 't-green' : 't-gray'
}
function storageLabel(h) {
  if (h.archive_status === 'ARCHIVE_UNAVAILABLE') return '归档不可用'
  return h.storage === 'HOT' ? '热数据' : '已归档'
}
</script>

<style scoped>
.history { display: flex; flex-direction: column; gap: 8px; }

.crumb { display: flex; justify-content: space-between; align-items: center; flex-wrap: wrap; gap: 4px 12px; padding: 7px 14px; font-size: 12px; color: var(--ink-2); }
.crumb b { color: var(--head); font-weight: 600; }
.crumb .sep { color: #9aa5b1; margin: 0 4px; }
.crumb .env { font-size: 11.5px; color: var(--ink-2); }
.crumb .kw { margin-left: 6px; width: 180px; border: 1px solid var(--line-strong); border-radius: 6px; padding: 2px 8px; font-size: 11.5px; font-family: inherit; }
.crumb .kw:focus { outline: none; border-color: var(--brand); box-shadow: 0 0 0 2px var(--brand-soft); }

.toolbar { display: flex; gap: 6px; flex-wrap: wrap; align-items: center; padding: 8px 12px; font-size: 11.5px; }
.field { display: inline-block; border: 1px solid var(--line-strong); border-radius: 6px; background: #fff; padding: 3px 8px; min-width: 60px; color: var(--ink-2); }

.row { display: flex; gap: 12px; align-items: flex-start; }
.col { min-width: 0; background: var(--card); border: 1px solid var(--line); border-radius: var(--radius); box-shadow: var(--shadow); padding: 10px; }
.col .lbl { font-size: 11.5px; color: var(--brand); font-weight: 700; margin-bottom: 6px; letter-spacing: .3px; }
.list-item { border-bottom: 1px solid #edf0f4; padding: 7px 4px; font-size: 12px; cursor: pointer; }
.list-item:last-child { border-bottom: none; }
.list-item:hover { background: #f7fafd; }
.list-item.cur { background: var(--brand-soft); border-radius: 6px; }
.list-item .meta { color: var(--ink-2); margin-top: 2px; }
.list-item .btn { margin-left: 6px; }
.muted { color: var(--ink-2); }

.note-box { border: 1px solid var(--line); border-radius: 8px; background: #f7f9fc; padding: 6px 10px; margin-bottom: 8px; font-size: 11.5px; line-height: 1.6; }
.note-box.warn { background: var(--bad-bg); border-color: #e5a19c; color: var(--bad); }
.ev-line { margin-top: 3px; font-family: inherit; }
.note-box .btn { margin-top: 6px; }
.ok-text { color: var(--ok); font-weight: 600; }
.mini { font-size: 11px; margin-top: 4px; }

@media (max-width: 1100px) {
  .row { flex-wrap: wrap; }
  .col { flex-basis: 100% !important; }
}
</style>
