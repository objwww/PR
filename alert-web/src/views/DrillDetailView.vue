<template>
  <div class="detail-page">
    <!-- 页头固定：场景 / 靶场 / 当前状态 / 停止并恢复（禁用+注明） -->
    <PageHeader :title="`演练详情 ${shortId}`" subtitle="页面刷新或关闭后作业继续；重新打开恢复真实状态，断网不显示假成功。">
      <template #actions>
        <el-button @click="loadDetail" :loading="loading">刷新</el-button>
        <el-button
          type="danger"
          disabled
          title="停止命令依赖 DR-02 持久化作业（POST /api/drills/{id}/stop），暂未开放；一旦注入可能发生，停止必须先进入恢复路径"
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
      </div>
      <div class="hd-item">
        <div class="hd-label">结果（outcome）</div>
        <div class="hd-value">{{ drill?.outcome ?? '—' }}</div>
        <div class="hd-note">PASS / FAIL / INCONCLUSIVE 另存，不由 CLOSED 代替</div>
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

    <!-- 时间线骨架：八阶段（§7.2），状态语义来自 §7.4 -->
    <div class="card timeline-card">
      <div class="tl-title">作业时间线</div>
      <div class="tl-note">
        阶段语义来自方案 §7.4：QUEUED→PRECHECK→INJECTING→OBSERVING→RECOVERING→VERIFYING→CLOSED；
        调查、注入与恢复分别呈现，报告成功不代表故障已解除。事件流接口（/api/drills/{id}/events）依赖 DR-02/DR-06，暂未开放。
      </div>
      <ol class="tl-list">
        <li v-for="(s, i) in stages" :key="s.name" class="tl-item">
          <span class="tl-idx">{{ i + 1 }}</span>
          <div class="tl-body">
            <div class="tl-name">{{ s.name }}<span class="tl-phase mono">{{ s.phase }}</span></div>
            <div class="tl-desc">{{ s.desc }}</div>
          </div>
          <span class="tl-state">未开始</span>
        </li>
      </ol>
    </div>

    <!-- 关联与证据分区：找不到关联就显示「尚未关联」 -->
    <div class="card link-card">
      <div class="tl-title">关联对象与证据</div>
      <dl class="preview-list">
        <div class="pv-row"><dt>关联告警</dt><dd>尚未关联（依赖 DR-06 事件投影）</dd></div>
        <div class="pv-row"><dt>关联调查 Run</dt><dd>尚未关联（依赖 DR-06 事件投影）</dd></div>
        <div class="pv-row"><dt>关联报告</dt><dd>尚未关联（依赖 DR-06 事件投影）</dd></div>
        <div class="pv-row"><dt>参数与审计</dt><dd>尚未关联（启动时冻结模板与参数，依赖 DR-02/DR-06）</dd></div>
      </dl>
    </div>
  </div>
</template>

<script setup>
// 演练详情（/drills/:drillId，DR-01）：页头固定四要素 + 「停止并恢复」（禁用）；
// 主区八阶段时间线骨架（§7.2），状态语义标注 §7.4。路由可直达刷新；
// /api/drills/{id} 未实现（403/404）→ 「演练作业不存在或接口未就绪」，不渲染假详情。
import { computed, onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import EmptyState from '../components/common/EmptyState.vue'
import PageHeader from '../components/common/PageHeader.vue'
import { getDrill, ApiNotReadyError } from '../api/drills'

const route = useRoute()
const router = useRouter()

const drill = ref(null)
const detailState = ref('loading') // loading | ok | not-ready | error
const loading = ref(false)

const shortId = computed(() => {
  const id = String(route.params.drillId ?? '')
  return id.length > 16 ? `${id.slice(0, 8)}…${id.slice(-4)}` : id
})

// §7.2 八阶段时间线；phase 对应 §7.4 作业状态机
const stages = [
  { name: '受理', phase: 'QUEUED', desc: '作业持久化并返回 202 受理，完成以查询状态为准。' },
  { name: '预检', phase: 'PRECHECK', desc: '服务端检查靶场健康、资源水位、旧故障残留、管理面/观测面与恢复能力。' },
  { name: '注入', phase: 'INJECTING', desc: '复用受控 ScenarioDriver 激活故障；激活回执先持久化再产生流量。' },
  { name: '产生测试流量', phase: 'INJECTING', desc: '按完整 recipe 产生 chaos- 前缀测试流量；中断后不得继续发流量。' },
  { name: '等待症状', phase: 'OBSERVING', desc: '等待期望症状与告警 firing，显示真实阶段，不伪造精确百分比。' },
  { name: 'Agent 调查', phase: 'OBSERVING', desc: '调查 Agent 在不知情（无 GT）前提下诊断；报告状态独立关联。' },
  { name: '停止', phase: 'RECOVERING', desc: '停止测试流量并发起恢复；202 仅代表接受恢复，不是恢复完成。' },
  { name: '核验恢复', phase: 'VERIFYING→CLOSED', desc: '恢复判据全部满足且无残留 firing 后 CLOSED；CLOSED 不代表演练目标达成。' },
]

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

onMounted(loadDetail)
</script>

<style scoped>
.detail-page { display: flex; flex-direction: column; gap: var(--section-gap); }

.head-card { display: grid; grid-template-columns: repeat(auto-fit, minmax(180px, 1fr)); gap: 16px; padding: 16px var(--card-pad); }
.hd-label { font-size: var(--fs-aux); color: var(--ink-2); }
.hd-value { font-size: var(--fs-section); font-weight: 600; color: var(--head); margin-top: 2px; }
.hd-note { font-size: var(--fs-aux); color: var(--ink-2); margin-top: 2px; }

.timeline-card, .link-card { padding: var(--card-pad); }
.tl-title { font-size: var(--fs-section); font-weight: 600; color: var(--head); margin-bottom: 8px; }
.tl-note { font-size: var(--fs-aux); color: var(--ink-2); margin-bottom: 16px; line-height: 1.7; }

.tl-list { list-style: none; }
.tl-item {
  display: flex; align-items: flex-start; gap: 12px;
  padding: 10px 0; border-bottom: 1px dashed var(--line);
}
.tl-item:last-child { border-bottom: none; }
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
.tl-state { flex: none; font-size: var(--fs-aux); color: var(--ink-2); padding-top: 4px; }
.mono { font-family: var(--mono, monospace); }

.preview-list { border: 1px solid var(--line); border-radius: var(--radius); overflow: hidden; }
.pv-row { display: flex; gap: 16px; padding: 12px 16px; border-bottom: 1px solid var(--line); }
.pv-row:last-child { border-bottom: none; }
.pv-row dt { flex: none; width: 120px; font-size: var(--fs-body); font-weight: 600; color: var(--ink); }
.pv-row dd { font-size: var(--fs-body); color: var(--ink-2); }
</style>
