<template>
  <div class="case-detail" v-if="detail">
    <div class="dt-tabs">
      <span
        v-for="t in detailTabs"
        :key="t.key"
        :class="{ cur: dtab === t.key }"
        @click="dtab = t.key"
      >{{ t.label }}</span>
    </div>

    <!-- 详情头部：裁决结论 + 状态/SLA/来源引用（固定区，不随 tab 切换） -->
    <div class="box head-box">
      <b>{{ detail.headline }}</b><br>
      <small>reason_code={{ detail.reasonCode }} ｜ revision={{ detail.revision }}</small>
    </div>
    <div class="box small">
      状态 <b>{{ detail.status }}</b> ｜ owner {{ detail.owner || '未分配' }} ｜ 来源
      <router-link :to="`/runs/${detail.runId}`">run#{{ detail.runId }}</router-link>/task#{{ detail.taskId }}<br>
      first_seen {{ detail.firstSeen }} ｜ ack_due {{ detail.ackDue }} ｜ resolve_due {{ detail.resolveDue }}<br>
      <b>Case 来源引用 {{ detail.sourceRefs.count }} 项</b>：{{ detail.sourceRefs.text }}；snapshot={{ detail.snapshot }} / generation={{ detail.generation }}
    </div>

    <!-- 摘要 -->
    <template v-if="dtab === 'summary'">
      <div class="box small">{{ detail.summary }}</div>
    </template>

    <!-- 证据工作区（Case 1:N EvidenceRef，N≥1；默认展示安全摘要与 provenance，不含 canonical payload） -->
    <template v-if="dtab === 'evidence'">
      <div class="lbl"> 证据工作区（Case 1:N EvidenceRef，N≥1）</div>
      <div v-for="ev in shownEvidence" :key="ev.id" class="list-item ev-item">
        <b>{{ ev.id }} {{ ev.type }}</b> ｜ {{ ev.source }} ｜ {{ ev.window }} ｜ gen{{ ev.generation }}
        <span class="tag" :class="verifyTag(ev.verify)">{{ verifyLabel(ev.verify) }}</span><br>
        <small>{{ ev.summary }}；payload_digest={{ ev.digest }}<template v-if="ev.taskId"> ｜ 来自 task#{{ ev.taskId }}</template></small>
        <button v-if="ev.expandable" class="btn" @click="expanded = !expanded">展开安全摘要</button>
        <div v-if="ev.expandable && expanded" class="safe-summary">
          安全摘要：prometheus 查询窗口内 cpu_throttle_seconds_total 峰值 92%，已按白名单截断并遮蔽 [REDACTED]；observed_generation=13 与 schema_version 校验通过。
        </div>
      </div>
      <div class="box small matrix">
        <b>Claim ↔ Evidence 引用矩阵</b><br>
        <template v-for="cl in detail.claims" :key="cl.id">
          {{ cl.id }} “{{ cl.text }}” {{ cl.verdict }} → {{ cl.evidenceRefs.join(', ') }}<br>
        </template>
        <template v-if="detail.conflictNote">
          <span class="tag t-red">冲突原因</span> {{ detail.conflictNote }}
        </template>
      </div>
      <div class="box small ops">
        <button v-if="detail.claims.length >= 2" class="btn" @click="dtab = 'claim'">比较两个 Claim</button>
        <button class="btn" @click="showAll = !showAll">{{ showAll ? '收起证据' : `查看全部 ${detail.evidence.length} 条证据` }}</button>
        <router-link class="btn" :to="`/runs/${detail.runId}`">打开调查详情</router-link>
      </div>
    </template>

    <!-- Claim 对比 -->
    <template v-if="dtab === 'claim'">
      <div class="lbl"> Claim 对比（结论可逐条追溯）</div>
      <div v-if="!detail.claims.length" class="box small muted">该 Case 无 Claim 冲突。</div>
      <div v-for="cl in detail.claims" :key="cl.id" class="list-item">
        <b>{{ cl.id }}</b> “{{ cl.text }}” <span class="tag" :class="cl.verdict === 'TRUE' ? 't-green' : 't-gray'">{{ cl.verdict }}</span><br>
        <small>证据引用：{{ cl.evidenceRefs.join(', ') }}</small>
      </div>
    </template>

    <!-- 活动 -->
    <template v-if="dtab === 'activity'">
      <div class="lbl"> 活动</div>
      <div v-for="(a, i) in detail.activities" :key="i" class="list-item"><small>{{ a }}</small></div>
    </template>

    <!-- 审计（actor/action/revision/idempotency_key 全留痕） -->
    <template v-if="dtab === 'audit'">
      <div class="lbl"> 审计</div>
      <div v-for="(a, i) in detail.audits" :key="i" class="list-item">
        <small>{{ a.time }} ｜ {{ a.actor }} ｜ {{ a.action }} ｜ revision={{ a.revision }} ｜ idempotency_key={{ a.key }}</small>
      </div>
    </template>

    <!-- Case 命令（认领/ACK/解决/转派） -->
    <div class="box small ops">
      <button v-if="!detail.owner" class="btn" @click="emit('command', 'claim')">认领</button>
      <button v-if="detail.status === 'OPEN'" class="btn" @click="emit('command', 'ack')">标记处理中</button>
      <button v-if="detail.status !== 'RESOLVED'" class="btn primary" @click="resolveOpen = true">解决并填写原因</button>
      <button class="btn" @click="assignOpen = !assignOpen">转派</button>
      <template v-if="assignOpen">
        <select v-model="assignee" class="mini-select">
          <option v-for="u in assignees" :key="u" :value="u">{{ u }}</option>
        </select>
        <button class="btn" @click="emit('command', 'assign', { assignee }); assignOpen = false">确认</button>
      </template>
    </div>
    <div class="box small muted">所有状态变更带 expected_revision + idempotency_key，操作写入审计事件。</div>

    <!-- 解决：结构化 reason + 备注（annot：解决必须填写） -->
    <div v-if="resolveOpen" class="resolve-mask" @click.self="resolveOpen = false">
      <div class="card resolve-card">
        <h4>解决 Case {{ detail.id }}</h4>
        <label>结构化原因
          <select v-model="resolveReason">
            <option value="CONFIRMED_FIXED">CONFIRMED_FIXED ｜ 已确认并修复</option>
            <option value="FALSE_POSITIVE">FALSE_POSITIVE ｜ 误报</option>
            <option value="DUPLICATE">DUPLICATE ｜ 重复 Case</option>
            <option value="WONT_FIX">WONT_FIX ｜ 暂不处理</option>
          </select>
        </label>
        <label>备注
          <textarea v-model="resolveRemark" rows="3" placeholder="必填：处置说明（写入审计事件）"></textarea>
        </label>
        <div class="resolve-ops">
          <button class="btn primary" :disabled="!resolveRemark.trim()" @click="submitResolve">提交（expected_revision={{ detail.revision }}）</button>
          <button class="btn" @click="resolveOpen = false">取消</button>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup>
// P4 处置中心 Case 详情面板（线框图 v1.6 #p4 右栏）：摘要/证据/Claim/活动/审计 tab + Case 命令
import { computed, ref, watch } from 'vue'
import { VERIFY_STATUS_ZH, zh } from '../dict/displayNameZh.js'

const props = defineProps({ detail: { type: Object, default: null } })
const emit = defineEmits(['command'])

const dtab = ref('evidence')
const showAll = ref(false)
const expanded = ref(false)
const resolveOpen = ref(false)
const resolveReason = ref('CONFIRMED_FIXED')
const resolveRemark = ref('')
const assignOpen = ref(false)
const assignee = ref('sre-li')
const assignees = ['sre-li', 'sre-chen', 'dba-wu']

// 切 Case 时重置到线框默认视图（证据 tab）
watch(() => props.detail?.id, () => {
  dtab.value = 'evidence'; showAll.value = false; expanded.value = false
  resolveOpen.value = false; assignOpen.value = false
})

const detailTabs = computed(() => {
  const d = props.detail
  if (!d) return []
  return [
    { key: 'summary', label: '摘要' },
    { key: 'evidence', label: `证据 ${d.evidence.length}` },
    { key: 'claim', label: `Claim ${d.claims.length}` },
    { key: 'activity', label: '活动' },
    { key: 'audit', label: '审计' },
  ]
})

const shownEvidence = computed(() => {
  const ev = props.detail?.evidence || []
  return showAll.value ? ev : ev.slice(0, 3)
})

// 中文名统一走 M7-09 版本化词典 src/dict/displayNameZh.js；tag 配色是 UI 本地映射，非词典内容
const VERIFY_TAG = { FRESH_VERIFIED: 't-green', VERIFIED: 't-green', PENDING_REVIEW: 't-gray' }
const verifyLabel = v => zh(VERIFY_STATUS_ZH, v)
const verifyTag = v => VERIFY_TAG[v] || 't-gray'

function submitResolve() {
  emit('command', 'resolve', { reason: resolveReason.value, remark: resolveRemark.value.trim() })
  resolveOpen.value = false
  resolveRemark.value = ''
}
</script>

<style scoped>
.dt-tabs { display: flex; flex-wrap: wrap; gap: 5px; padding: 0 0 0 0; border-bottom: 1px solid var(--line); margin-bottom: 8px; }
.dt-tabs span {
  border: 1px solid var(--line-strong); border-bottom: none; border-radius: 8px 8px 0 0;
  padding: 5px 12px; font-size: 11.5px; background: #fff; color: var(--ink-2); cursor: pointer;
}
.dt-tabs span.cur { background: var(--brand); color: #fff; border-color: var(--brand); font-weight: 600; }

.box { border: 1px solid var(--line); border-radius: 8px; background: #fff; padding: 8px 10px; font-size: 12px; margin-bottom: 8px; }
.box.small { font-size: 11.5px; }
.head-box { background: var(--brand-soft); border-color: #a8c4f5; }
.lbl { font-size: 12px; font-weight: 700; color: var(--head); margin: 6px 0 4px; }
.muted { color: #888; }
.ops { display: flex; gap: 6px; flex-wrap: wrap; align-items: center; }
.matrix { line-height: 1.9; }

.list-item { border-bottom: 1px solid #edf0f4; padding: 7px 4px; font-size: 12px; }
.list-item:last-child { border-bottom: none; }
.ev-item .btn { margin-left: 6px; }
.safe-summary {
  margin-top: 6px; padding: 6px 8px; border-left: 3px solid var(--brand);
  background: #f5f8fe; border-radius: 4px; font-size: 11px; color: var(--ink-2);
}

.mini-select { border: 1px solid var(--line-strong); border-radius: 6px; padding: 3px 6px; font-size: 12px; background: #fff; color: var(--ink); }

.resolve-mask {
  position: fixed; inset: 0; background: rgba(30, 42, 58, .35);
  display: flex; align-items: center; justify-content: center; z-index: 50;
}
.resolve-card { width: 420px; padding: 16px; }
.resolve-card h4 { color: var(--head); margin-bottom: 10px; font-size: 14px; }
.resolve-card label { display: block; font-size: 12px; color: var(--ink-2); margin-bottom: 10px; }
.resolve-card select, .resolve-card textarea {
  display: block; width: 100%; margin-top: 4px; border: 1px solid var(--line-strong);
  border-radius: 6px; padding: 5px 8px; font-size: 12px; font-family: inherit; background: #fff; color: var(--ink);
}
.resolve-ops { display: flex; gap: 8px; }
.resolve-ops .btn:disabled { opacity: .5; cursor: not-allowed; }
</style>
