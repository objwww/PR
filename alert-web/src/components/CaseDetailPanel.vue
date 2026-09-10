<template>
  <div class="case-detail" v-if="detail">
    <!-- 头部：裁决结论 + 状态/负责人/期限 -->
    <div class="head card-in">
      <div class="head-title">{{ detail.headline }}</div>
      <div class="head-meta">
        <el-tag :type="statusType(detail.status)" effect="light">{{ statusLabel(detail.status) }}</el-tag>
        <span>负责人 {{ detail.owner || '未分配' }}</span>
        <span>原因 {{ detail.reasonCode }}</span>
        <span>首次出现 {{ fmtTime(detail.firstSeen) }}</span>
        <span>认领期限 {{ fmtTime(detail.ackDue) }}</span>
        <span>解决期限 {{ fmtTime(detail.resolveDue) }}</span>
        <router-link :to="`/runs/${detail.runId}`">关联调查 →</router-link>
      </div>
    </div>

    <!-- 下一步操作（认领/处理中/解决/转派分清主次） -->
    <div class="card-in ops-block">
      <div class="blk-title">下一步操作</div>
      <div class="ops-row">
        <el-button
          v-if="detail.status !== 'RESOLVED'" type="primary"
          @click="resolveOpen = true"
        >解决并填写原因</el-button>
        <el-button v-if="!detail.owner" @click="emit('command', 'claim')">认领</el-button>
        <el-button v-if="detail.status === 'OPEN'" @click="emit('command', 'ack')">标记处理中</el-button>
        <el-popover v-model:visible="assignOpen" placement="bottom" trigger="click" width="240">
          <template #reference>
            <el-button>转派</el-button>
          </template>
          <div class="assign-pop">
            <el-select v-model="assignee" style="width: 100%" placeholder="选择接手人">
              <el-option v-for="u in assignees" :key="u" :value="u" :label="u" />
            </el-select>
            <el-button
              size="small" type="primary" style="margin-top: 8px"
              @click="emit('command', 'assign', { assignee }); assignOpen = false"
            >确认转派</el-button>
          </div>
        </el-popover>
      </div>
      <div class="ops-hint">状态变更带版本校验与幂等键，操作全部写入审计。</div>
    </div>

    <!-- 处置记录（紧跟操作，先于调查证据） -->
    <div class="card-in">
      <div class="blk-title">处置记录</div>
      <el-timeline v-if="detail.activities.length" class="activity-tl">
        <el-timeline-item v-for="(a, i) in detail.activities" :key="i">{{ a }}</el-timeline-item>
      </el-timeline>
      <div v-else class="muted">暂无处置记录</div>
    </div>

    <!-- 调查证据与其余信息收进页签 -->
    <el-tabs v-model="dtab" class="dt-tabs">
      <el-tab-pane label="摘要" name="summary">
        <div class="card-in body-text">{{ detail.summary }}</div>
        <div class="card-in small">
          来源引用 {{ detail.sourceRefs.count }} 项：{{ detail.sourceRefs.text }}
        </div>
      </el-tab-pane>

      <el-tab-pane :label="`证据（${detail.evidence.length}）`" name="evidence">
        <div v-for="ev in shownEvidence" :key="ev.id" class="card-in ev-item">
          <div class="ev-head">
            <b>{{ ev.id }}</b>
            <span class="muted">{{ ev.type }} ｜ 来源 {{ ev.source }} ｜ 窗口 {{ ev.window }}</span>
            <el-tag size="small" :type="verifyType(ev.verify)" effect="plain">{{ verifyLabel(ev.verify) }}</el-tag>
          </div>
          <div class="ev-summary">{{ ev.summary }}<template v-if="ev.taskId">（来自调查任务 {{ ev.taskId }}）</template></div>
        </div>
        <div v-if="!detail.evidence.length" class="card-in muted">暂无证据</div>
        <div class="ops-row" style="margin-top: 8px">
          <el-button v-if="detail.evidence.length > 3" size="small" @click="showAll = !showAll">
            {{ showAll ? '收起证据' : `查看全部 ${detail.evidence.length} 条证据` }}
          </el-button>
          <router-link :to="`/runs/${detail.runId}`"><el-button size="small">打开调查详情</el-button></router-link>
        </div>
      </el-tab-pane>

      <el-tab-pane :label="`结论对比（${detail.claims.length}）`" name="claim">
        <div v-if="!detail.claims.length" class="card-in muted">无结论冲突。</div>
        <div v-for="cl in detail.claims" :key="cl.id" class="card-in claim-item">
          <div>
            <b>{{ cl.id }}</b> “{{ cl.text }}”
            <el-tag size="small" :type="cl.verdict === 'TRUE' ? 'success' : 'info'" effect="plain">{{ verdictLabel(cl.verdict) }}</el-tag>
          </div>
          <div class="muted">证据引用：{{ cl.evidenceRefs.join('、') }}</div>
        </div>
        <div v-if="detail.conflictNote" class="card-in">
          <el-tag type="danger" size="small">冲突原因</el-tag> {{ detail.conflictNote }}
        </div>
      </el-tab-pane>

      <el-tab-pane label="审计" name="audit">
        <el-table :data="detail.audits" size="small">
          <el-table-column label="时间" width="170">
            <template #default="{ row }">{{ row.time }}</template>
          </el-table-column>
          <el-table-column prop="actor" label="操作人" width="120" />
          <el-table-column prop="action" label="动作" width="120" />
          <el-table-column label="版本" width="80">
            <template #default="{ row }">v{{ row.revision }}</template>
          </el-table-column>
          <el-table-column prop="key" label="幂等键" show-overflow-tooltip />
          <template #empty><span class="muted">暂无审计事件</span></template>
        </el-table>
      </el-tab-pane>
    </el-tabs>

    <!-- 解决：结构化原因 + 备注（必填，写入审计） -->
    <el-dialog v-model="resolveOpen" title="解决处置" width="460px">
      <el-form label-width="90px">
        <el-form-item label="结构化原因">
          <el-select v-model="resolveReason" style="width: 100%">
            <el-option value="CONFIRMED_FIXED" label="已确认并修复" />
            <el-option value="FALSE_POSITIVE" label="误报" />
            <el-option value="DUPLICATE" label="重复处置" />
            <el-option value="WONT_FIX" label="暂不处理" />
          </el-select>
        </el-form-item>
        <el-form-item label="备注" required>
          <el-input v-model="resolveRemark" type="textarea" :rows="3" placeholder="必填：处置说明（写入审计事件）" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="resolveOpen = false">取消</el-button>
        <el-button type="primary" :disabled="!resolveRemark.trim()" @click="submitResolve">提交解决</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
// UI-4 处置详情面板：先下一步操作 + 处置记录，再展开调查证据（摘要/证据/结论对比/审计页签）。
// 命令经 emit('command') 交 CasesView 统一走 expectedRevision + idempotencyKey。
import { computed, ref, watch } from 'vue'
import { VERIFY_STATUS_ZH, zh } from '../dict/displayNameZh.js'
import { fmtTime } from '../utils/format'

const props = defineProps({ detail: { type: Object, default: null } })
const emit = defineEmits(['command'])

const dtab = ref('evidence')
const showAll = ref(false)
const resolveOpen = ref(false)
const resolveReason = ref('CONFIRMED_FIXED')
const resolveRemark = ref('')
const assignOpen = ref(false)
const assignee = ref('sre-li')
const assignees = ['sre-li', 'sre-chen', 'dba-wu']

// 切换选中项时回到证据页签并重置临时态
watch(() => props.detail?.id, () => {
  dtab.value = 'evidence'; showAll.value = false
  resolveOpen.value = false; assignOpen.value = false
})

const shownEvidence = computed(() => {
  const ev = props.detail?.evidence || []
  return showAll.value ? ev : ev.slice(0, 3)
})

// Case 状态：OPEN=待认领 / ACKED=处理中 / RESOLVED=已解决
const statusType = s => ({ OPEN: 'warning', ACKED: 'primary', RESOLVED: 'success' }[s] || 'info')
const statusLabel = s => ({ OPEN: '待认领', ACKED: '处理中', RESOLVED: '已解决' }[s] || s)

// 中文名统一走版本化词典；tag 类型是 UI 本地映射
const verifyLabel = v => zh(VERIFY_STATUS_ZH, v)
const verifyType = v => ({ FRESH_VERIFIED: 'success', VERIFIED: 'success', PENDING_REVIEW: 'warning' }[v] || 'info')

// 结论判定词：TRUE=成立 / FALSE=不成立，未收录原样展示
const verdictLabel = v => ({ TRUE: '成立', FALSE: '不成立' }[v] || v)

function submitResolve() {
  emit('command', 'resolve', { reason: resolveReason.value, remark: resolveRemark.value.trim() })
  resolveOpen.value = false
  resolveRemark.value = ''
}
</script>

<style scoped>
.case-detail { display: flex; flex-direction: column; gap: 12px; }

.card-in {
  border: 1px solid var(--line); border-radius: var(--radius);
  background: #fff; padding: 12px 14px; font-size: var(--fs-body);
}
.card-in.small { font-size: var(--fs-aux); color: var(--ink-2); }

.head { background: var(--brand-soft); border-color: #a8c4f5; }
.head-title { font-size: var(--fs-section); font-weight: 600; color: var(--head); line-height: 1.5; }
.head-meta {
  margin-top: 6px; display: flex; align-items: center; gap: 14px; flex-wrap: wrap;
  font-size: var(--fs-aux); color: var(--ink-2);
}

.blk-title { font-size: var(--fs-body); font-weight: 600; color: var(--head); margin-bottom: 8px; }
.ops-row { display: flex; gap: 8px; flex-wrap: wrap; align-items: center; }
.ops-hint { margin-top: 8px; font-size: var(--fs-aux); color: var(--ink-2); }
.assign-pop { display: flex; flex-direction: column; }

.activity-tl { padding-left: 2px; }
.activity-tl :deep(.el-timeline-item__content) { font-size: var(--fs-aux); color: var(--ink); }

.dt-tabs :deep(.el-tabs__content) { padding-top: 4px; }
.body-text { white-space: pre-wrap; word-break: break-word; line-height: 1.7; }

.ev-item { margin-bottom: 8px; }
.ev-head { display: flex; align-items: center; gap: 8px; flex-wrap: wrap; }
.ev-summary { margin-top: 4px; font-size: var(--fs-aux); color: var(--ink-2); }
.claim-item { margin-bottom: 8px; display: flex; flex-direction: column; gap: 4px; }

.muted { color: var(--ink-2); font-size: var(--fs-aux); }
</style>
