<template>
  <div class="drills-page">
    <PageHeader
      title="故障演练"
      subtitle="从选场景到恢复核验的完整演练作业；后端作业链 DR-02~04 交付前，本页仅展示真实状态。"
    >
      <template #actions>
        <router-link to="/eval" class="eval-link" title="一次演练可关联一次评测，但两者不是同一对象（§7.2）">去评测中心 →</router-link>
        <el-button :loading="loading" @click="loadList">刷新</el-button>
        <el-button type="primary" @click="router.push('/drills/new')">新建演练</el-button>
      </template>
    </PageHeader>

    <!-- 摘要卡：活动演练 / 恢复异常——无数据来源时诚实显示，不伪造 0 -->
    <div class="summary-row">
      <div class="card sum-card">
        <div class="sum-label">活动演练</div>
        <div class="sum-value">{{ notReady ? '—' : (summary.active ?? '—') }}</div>
        <div class="sum-note">{{ notReady ? '统计依赖 DR-02 作业投影，暂未开放' : '同一靶场同一时间仅允许一个活动演练（§7.3 互斥）' }}</div>
      </div>
      <div class="card sum-card">
        <div class="sum-label">恢复异常</div>
        <div class="sum-value">{{ notReady ? '—' : (summary.recoveryFailed ?? '—') }}</div>
        <div class="sum-note">{{ notReady ? '统计依赖 DR-02 作业投影，暂未开放' : 'RECOVERY_FAILED 保留占位并阻止下一场演练（§7.4）' }}</div>
      </div>
    </div>

    <!-- 演练列表 -->
    <div class="table-zone card">
      <template v-if="listState === 'ok'">
        <el-table :data="items" v-loading="loading" row-key="drillId">
          <el-table-column label="场景" min-width="160">
            <template #default="{ row }">
              <div class="cell-main">{{ row.scenarioName ?? row.scenarioId ?? '—' }}</div>
              <div class="cell-sub mono">{{ row.scenarioId ?? '' }}</div>
            </template>
          </el-table-column>
          <el-table-column label="靶场" min-width="120">
            <template #default="{ row }">{{ row.targetEnv ?? '—' }}</template>
          </el-table-column>
          <el-table-column label="发起人" width="120">
            <template #default="{ row }">{{ row.operator ?? '—' }}</template>
          </el-table-column>
          <el-table-column label="状态" width="140">
            <template #default="{ row }">
              <div class="cell-main">{{ row.state ?? '—' }}</div>
              <div class="cell-sub">阶段语义见 §7.4</div>
            </template>
          </el-table-column>
          <el-table-column label="时间" width="170">
            <template #default="{ row }">
              <div class="cell-main">{{ fmtTime(row.createdAt) }}</div>
              <div class="cell-sub">{{ fmtDuration(row.createdAt, row.closedAt) }}</div>
            </template>
          </el-table-column>
          <el-table-column label="结果" width="130">
            <template #default="{ row }">
              <div class="cell-main">{{ row.outcome ?? '—' }}</div>
              <div class="cell-sub">outcome 独立于 CLOSED（§7.4）</div>
            </template>
          </el-table-column>
          <template #empty>
            <EmptyState kind="empty" description="暂无演练记录：后端作业链 DR-02~04 未交付，尚无任何演练作业。" />
          </template>
        </el-table>
      </template>
      <!-- 404 = 接口未就绪，不能渲染成「暂无数据」假装正常 -->
      <el-result
        v-else-if="listState === 'not-ready'"
        icon="warning"
        title="演练列表接口未就绪"
        sub-title="GET /api/drills 当前被拒绝（接口不存在，实测 403/404）：后端作业链 DR-02~04 未交付，暂无演练记录。接口就绪前本页不展示任何模拟数据。"
      >
        <template #extra>
          <el-button :loading="loading" @click="loadList">重试</el-button>
        </template>
      </el-result>
      <EmptyState v-else-if="listState === 'error'" kind="error" @retry="loadList" />
      <div v-else v-loading="true" class="loading-box" />
    </div>
  </div>
</template>

<script setup>
// 故障演练列表（/drills，DR-01）：新建主按钮 + 活动/恢复异常摘要卡 + 六列表格。
// /api/drills 依赖 DR-02，未实现路由的 403/404 统一归类为「接口未就绪」，与真实错误、真实空数据三态区分。
import { onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import EmptyState from '../components/common/EmptyState.vue'
import PageHeader from '../components/common/PageHeader.vue'
import { listDrills, ApiNotReadyError } from '../api/drills'
import { fmtDuration, fmtTime } from '../utils/format'

const router = useRouter()

const items = ref([])
const summary = ref({})
const listState = ref('loading') // loading | ok | not-ready | error
const loading = ref(false)
const notReady = ref(false)

async function loadList() {
  loading.value = true
  try {
    const d = await listDrills()
    items.value = d.items ?? []
    summary.value = d.summary ?? {}
    notReady.value = false
    listState.value = 'ok'
  } catch (e) {
    if (e instanceof ApiNotReadyError) {
      notReady.value = true
      listState.value = 'not-ready'
    } else {
      listState.value = 'error'
    }
  } finally {
    loading.value = false
  }
}

onMounted(loadList)
</script>

<style scoped>
.drills-page { display: flex; flex-direction: column; gap: var(--section-gap); }
.eval-link { font-size: 13px; align-self: center; }

.summary-row { display: grid; grid-template-columns: repeat(auto-fit, minmax(240px, 1fr)); gap: var(--section-gap); }
.sum-card { padding: 16px var(--card-pad); }
.sum-label { font-size: var(--fs-aux); color: var(--ink-2); }
.sum-value { font-size: 28px; font-weight: 700; color: var(--head); line-height: 1.3; margin-top: 4px; }
.sum-note { font-size: var(--fs-aux); color: var(--ink-2); margin-top: 6px; }

.table-zone { padding: 8px var(--card-pad) 12px; }
.cell-main { font-size: var(--fs-body); line-height: 1.4; }
.cell-sub { font-size: var(--fs-aux); color: var(--ink-2); line-height: 1.4; }
.mono { font-family: var(--mono, monospace); }
.loading-box { height: 320px; }
</style>
