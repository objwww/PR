<template>
  <div class="detail-page">
    <!-- 页头固定：场景 / 靶场 / 当前状态 / 停止并恢复（DR-02 真实接线，降级显式注明） -->
    <PageHeader :title="`演练详情 ${shortId}`" subtitle="页面刷新或关闭后作业继续；重新打开恢复真实状态，断网不显示假成功。">
      <template #actions>
        <el-button @click="loadDetail" :loading="loading">刷新</el-button>
        <el-button
          type="danger"
          :disabled="!!stopDisabledReason || stopping"
          :loading="stopping"
          :title="stopDisabledReason || 'POST /api/drills/{id}/stop（幂等键一次生成）；一旦注入可能发生，停止必须先进入恢复路径'"
          @click="onStop"
        >停止并恢复</el-button>
        <el-button text @click="router.push('/drills')">返回列表</el-button>
      </template>
    </PageHeader>

    <div class="card head-card">
      <div class="hd-item">
        <div class="hd-label">场景</div>
        <div class="hd-value">{{ drill?.scenarioName ?? drill?.scenarioId ?? '—' }}</div>
      </div>
      <div class="hd-item">
        <div class="hd-label">靶场</div>
        <div class="hd-value">{{ drill?.targetEnv ?? '—' }}</div>
      </div>
      <div class="hd-item">
        <div class="hd-label">当前状态</div>
        <div class="hd-value">{{ drill?.state ?? '—' }}</div>
        <div class="hd-note">CLOSED 仅表示恢复核验完成（§7.4）</div>
        <div v-if="stopAcceptedNote" class="hd-note hd-stop">{{ stopAcceptedNote }}</div>
      </div>
      <div class="hd-item">
        <div class="hd-label">结果（outcome）</div>
        <div class="hd-value">{{ drill?.outcome ?? '—' }}</div>
        <div class="hd-note">PASS / FAIL / INCONCLUSIVE 另存，不由 CLOSED 代替</div>
        <div v-if="drill?.terminalReason" class="hd-note">卡因：{{ drill.terminalReason }}</div>
      </div>
    </div>

    <!-- 接口未就绪 / 作业不存在：诚实提示，不渲染假详情 -->
    <el-result
      v-if="detailState === 'not-ready'"
      icon="warning"
      title="演练作业不存在或接口未就绪"
      :sub-title="`GET /api/drills/${route.params.drillId} 当前被拒绝（接口不存在，实测 403/404）：后端作业链 DR-02~04 未交付，无法确认该演练作业是否存在。`"
    >
      <template #extra>
        <el-button :loading="loading" @click="loadDetail">重试</el-button>
      </template>
    </el-result>
    <EmptyState v-else-if="detailState === 'error'" kind="error" @retry="loadDetail" />

    <!-- 时间线：八阶段（§7.2）；详情接口 timeline 有真实相位时高亮当前阶段，enteredAt 只取真实事件 -->
    <div class="card timeline-card">
      <div class="tl-title">作业时间线</div>
      <div class="tl-note">
        阶段语义来自方案 §7.4：QUEUED→PRECHECK→INJECTING→OBSERVING→RECOVERING→VERIFYING→CLOSED；
        调查、注入与恢复分别呈现，报告成功不代表故障已解除。
        {{ detailState === 'ok'
          ? '当前为详情接口返回的真实时间线：enteredAt 只取真实事件，无事件如实「—」。'
          : '事件流接口（/api/drills/{id}/events）依赖 DR-02/DR-06，暂未开放。' }}
      </div>
      <ol class="tl-list">
        <li
          v-for="(s, i) in timelineStages" :key="s.key ?? s.name"
          class="tl-item" :class="{ 'tl-active': s.status === 'ACTIVE', 'tl-failed': s.status === 'FAILED' }"
        >
          <span class="tl-idx">{{ i + 1 }}</span>
          <div class="tl-body">
            <div class="tl-name">{{ s.name }}<span class="tl-phase mono">{{ s.phase }}</span></div>
            <div class="tl-desc">{{ s.desc }}</div>
            <div class="tl-entered">进入时间：{{ fmtTime(s.enteredAt) }}</div>
          </div>
          <span class="tl-state" :class="stateCls(s.status)">{{ stateText(s.status) }}</span>
        </li>
      </ol>
    </div>

    <!-- 关联与证据分区：找不到关联就显示「尚未关联」 -->
    <div class="card link-card">
      <div class="tl-title">关联对象与证据</div>
      <dl class="preview-list">
        <div class="pv-row">
          <dt>关联告警</dt>
          <dd v-if="detailState === 'ok'" class="mono">{{ drill?.related?.incidentId ?? '尚未关联（DR-06 事件投影未回填）' }}</dd>
          <dd v-else>尚未关联（依赖 DR-06 事件投影）</dd>
        </div>
        <div class="pv-row">
          <dt>关联调查 Run</dt>
          <dd v-if="detailState === 'ok'" class="mono">{{ drill?.related?.runId ?? '尚未关联（DR-06 事件投影未回填）' }}</dd>
          <dd v-else>尚未关联（依赖 DR-06 事件投影）</dd>
        </div>
        <div class="pv-row"><dt>关联报告</dt><dd>尚未关联（依赖 DR-06 事件投影）</dd></div>
        <div class="pv-row">
          <dt>参数与审计</dt>
          <dd v-if="detailState === 'ok'">
            模板 digest <span class="mono">{{ drill?.templateDigest ?? '—' }}</span>；
            冻结参数 <span class="mono">{{ paramsText }}</span>
          </dd>
          <dd v-else>尚未关联（启动时冻结模板与参数，依赖 DR-02/DR-06）</dd>
        </div>
      </dl>
    </div>
  </div>
</template>

<script setup>
// 演练详情（/drills/:drillId，DR-02 接线）：页头固定四要素 + 「停止并恢复」真实接线——
// POST /api/drills/{id}/stop（幂等键 crypto.randomUUID）；受理 202 只表示取消中/恢复中
// （显示「恢复中」而非「已恢复」，核验完成才 CLOSED，§7.4/DU12）；RECOVERY_FAILED 占位 409
// 如实展示服务端「需处理恢复而非停止」。时间线八阶段取详情接口 timeline：有真实相位高亮
// 当前阶段（ACTIVE），无事件 enteredAt 如实「—」。接口未就绪（403/404）保持诚实降级。
import { computed, onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import EmptyState from '../components/common/EmptyState.vue'
import PageHeader from '../components/common/PageHeader.vue'
import { ApiNotReadyError, getDrill, newIdempotencyKey, stopDrill } from '../api/drills'
import { fmtTime } from '../utils/format'

const route = useRoute()
const router = useRouter()

const drill = ref(null)
const detailState = ref('loading') // loading | ok | not-ready | error
const loading = ref(false)
const stopping = ref(false)

const shortId = computed(() => {
  const id = String(route.params.drillId ?? '')
  return id.length > 16 ? `${id.slice(0, 8)}…${id.slice(-4)}` : id
})

// §7.4 终态集：停止非法（后端 409 CONFLICT_TERMINAL）
const TERMINAL = new Set(['CLOSED', 'CANCELLED', 'FAILED'])

// 停止按钮禁用原因：接口未就绪/终态/已受理——显式注明，不伪造可点；RECOVERY_FAILED 占位
// 保持可点，由服务端 409 如实返回「需处理恢复而非停止」
const stopDisabledReason = computed(() => {
  if (detailState.value === 'not-ready') {
    return '停止命令接口未就绪（POST /api/drills/{id}/stop 依赖 DR-02，实测 403/404）：作业状态无法确认，不允许盲停'
  }
  if (detailState.value !== 'ok') return '作业详情加载后可操作'
  if (TERMINAL.has(drill.value?.state)) return `作业已终态（${drill.value.state}），停止非法（§7.4）`
  if (drill.value?.stopRequestedAt) {
    return `停止请求已受理（${fmtTime(drill.value.stopRequestedAt)}）：恢复中——受理≠恢复完成，核验完成才 CLOSED`
  }
  return ''
})

// 受理后头卡注明「恢复中」而非「已恢复」（DU12 语义）
const stopAcceptedNote = computed(() => {
  if (detailState.value !== 'ok' || !drill.value?.stopRequestedAt) return ''
  if (TERMINAL.has(drill.value?.state)) return ''
  return `停止已受理（${fmtTime(drill.value.stopRequestedAt)}）：当前为恢复中，非「已恢复」`
})

// §7.2 八阶段骨架（详情未就绪时展示）；key 与后端 DrillTimeline 键序一致
const STAGE_SKELETON = [
  { key: 'ACCEPTED', name: '受理', phase: 'QUEUED', desc: '作业持久化并返回 202 受理，完成以查询状态为准。' },
  { key: 'PRECHECK', name: '预检', phase: 'PRECHECK', desc: '服务端检查靶场健康、资源水位、旧故障残留、管理面/观测面与恢复能力。' },
  { key: 'INJECTION', name: '注入', phase: 'INJECTING', desc: '复用受控 ScenarioDriver 激活故障；激活回执先持久化再产生流量。' },
  { key: 'TRAFFIC', name: '产生测试流量', phase: 'INJECTING', desc: '按完整 recipe 产生 chaos- 前缀测试流量；中断后不得继续发流量。' },
  { key: 'SYMPTOM_WAIT', name: '等待症状', phase: 'OBSERVING', desc: '等待期望症状与告警 firing，显示真实阶段，不伪造精确百分比。' },
  { key: 'AGENT_INVESTIGATION', name: 'Agent 调查', phase: 'OBSERVING', desc: '调查 Agent 在不知情（无 GT）前提下诊断；报告状态独立关联。' },
  { key: 'STOP', name: '停止', phase: 'RECOVERING', desc: '停止测试流量并发起恢复；202 仅代表接受恢复，不是恢复完成。' },
  { key: 'VERIFY_RECOVERY', name: '核验恢复', phase: 'VERIFYING→CLOSED', desc: '恢复判据全部满足且无残留 firing 后 CLOSED；CLOSED 不代表演练目标达成。' },
]
const STAGE_DESCS = Object.fromEntries(STAGE_SKELETON.map(s => [s.key, s.desc]))

// 详情接口 timeline（[{key,name,phase,status,enteredAt}]）优先；无真实数据回退骨架
const timelineStages = computed(() => {
  const tl = drill.value?.timeline
  if (detailState.value === 'ok' && Array.isArray(tl) && tl.length) {
    return tl.map(s => ({ ...s, desc: STAGE_DESCS[s.key] ?? '' }))
  }
  return STAGE_SKELETON.map(s => ({ ...s, status: null, enteredAt: null }))
})

const STAGE_STATUS_TEXT = {
  DONE: '已完成', ACTIVE: '进行中', FAILED: '失败',
  CANCELLED: '已取消', PENDING: '未开始', SKIPPED: '已跳过',
}
const stateText = s => (s == null ? '未开始' : STAGE_STATUS_TEXT[s] ?? s)
const stateCls = s => ({
  'tl-st-active': s === 'ACTIVE',
  'tl-st-done': s === 'DONE',
  'tl-st-failed': s === 'FAILED' || s === 'CANCELLED',
})

const paramsText = computed(() => {
  const p = drill.value?.params
  if (!p || typeof p !== 'object') return '—'
  const text = JSON.stringify(p)
  return text.length > 160 ? `${text.slice(0, 160)}…` : text
})

async function loadDetail() {
  loading.value = true
  try {
    drill.value = await getDrill(route.params.drillId)
    detailState.value = 'ok'
  } catch (e) {
    detailState.value = e instanceof ApiNotReadyError ? 'not-ready' : 'error'
  } finally {
    loading.value = false
  }
}

async function onStop() {
  if (stopDisabledReason.value) return
  try {
    await ElMessageBox.confirm(
      '停止将终止测试流量并进入恢复路径；202 受理只表示恢复中，恢复核验完成才 CLOSED（§7.4）。该操作留痕审计。',
      '停止并恢复',
      { type: 'warning', confirmButtonText: '确认停止', cancelButtonText: '再想想' },
    )
  } catch {
    return // 用户取消
  }
  stopping.value = true
  try {
    const res = await stopDrill(route.params.drillId, newIdempotencyKey())
    if (res?.alreadyRequested) {
      ElMessage.info(`停止请求此前已受理（异键幂等，零新副作用，DU14）；当前相位 ${res.state ?? '—'}`)
    } else if (res?.replayed) {
      ElMessage.info(`停止请求此前已受理（同键幂等重放）；停止路径 ${res.state ?? '—'}`)
    } else if (res?.state === 'CANCELLING') {
      ElMessage.success('停止请求已受理（202）：注入前取消中（CANCELLING）')
    } else {
      ElMessage.success('停止请求已受理（202）：恢复中（RECOVERING）——受理≠恢复完成，核验完成才 CLOSED')
    }
    await loadDetail()
  } catch (e) {
    if (e instanceof ApiNotReadyError) {
      ElMessage.warning('停止接口未就绪（POST /api/drills/{id}/stop 依赖 DR-02，实测 403/404）')
    } else if (e?.response?.status === 409) {
      // 终态 / RECOVERY_FAILED 占位：服务端文案如实透出（「需处理恢复而非停止」）
      ElMessage.error(e.response.data?.error || '停止冲突（409）')
      await loadDetail()
    } else {
      ElMessage.error(e?.response?.data?.error || '停止请求失败，请重试')
    }
  } finally {
    stopping.value = false
  }
}

onMounted(loadDetail)
</script>

<style scoped>
.detail-page { display: flex; flex-direction: column; gap: var(--section-gap); }

.head-card { display: grid; grid-template-columns: repeat(auto-fit, minmax(180px, 1fr)); gap: 16px; padding: 16px var(--card-pad); }
.hd-label { font-size: var(--fs-aux); color: var(--ink-2); }
.hd-value { font-size: var(--fs-section); font-weight: 600; color: var(--head); margin-top: 2px; }
.hd-note { font-size: var(--fs-aux); color: var(--ink-2); margin-top: 2px; }
.hd-stop { color: var(--warn); }

.timeline-card, .link-card { padding: var(--card-pad); }
.tl-title { font-size: var(--fs-section); font-weight: 600; color: var(--head); margin-bottom: 8px; }
.tl-note { font-size: var(--fs-aux); color: var(--ink-2); margin-bottom: 16px; line-height: 1.7; }

.tl-list { list-style: none; }
.tl-item {
  display: flex; align-items: flex-start; gap: 12px;
  padding: 10px 8px; border-bottom: 1px dashed var(--line); border-radius: var(--radius-ctl);
}
.tl-item:last-child { border-bottom: none; }
.tl-item.tl-active { background: var(--brand-soft); }
.tl-item.tl-active .tl-idx { border-color: var(--brand); color: var(--brand); font-weight: 700; }
.tl-item.tl-failed { background: var(--bad-bg); }
.tl-idx {
  flex: none; width: 24px; height: 24px; border-radius: 50%;
  border: 1px solid var(--line-strong); color: var(--ink-2);
  display: inline-flex; align-items: center; justify-content: center;
  font-size: var(--fs-aux); margin-top: 2px;
}
.tl-body { flex: 1; min-width: 0; }
.tl-name { font-size: var(--fs-body); font-weight: 600; color: var(--ink); }
.tl-phase {
  margin-left: 8px; font-size: var(--fs-aux); font-weight: 400; color: var(--ink-2);
  background: var(--bg); border-radius: var(--radius-ctl); padding: 1px 6px;
}
.tl-desc { font-size: var(--fs-aux); color: var(--ink-2); margin-top: 2px; line-height: 1.6; }
.tl-entered { font-size: var(--fs-aux); color: var(--ink-2); margin-top: 2px; }
.tl-state { flex: none; font-size: var(--fs-aux); color: var(--ink-2); padding-top: 4px; }
.tl-st-active { color: var(--brand); font-weight: 700; }
.tl-st-done { color: var(--ok); }
.tl-st-failed { color: var(--bad); }
.mono { font-family: var(--mono, monospace); word-break: break-all; }

.preview-list { border: 1px solid var(--line); border-radius: var(--radius); overflow: hidden; }
.pv-row { display: flex; gap: 16px; padding: 12px 16px; border-bottom: 1px solid var(--line); }
.pv-row:last-child { border-bottom: none; }
.pv-row dt { flex: none; width: 120px; font-size: var(--fs-body); font-weight: 600; color: var(--ink); }
.pv-row dd { font-size: var(--fs-body); color: var(--ink-2); }
</style>
