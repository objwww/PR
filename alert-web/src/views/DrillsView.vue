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

    <!-- 韧性覆盖矩阵 + 四段复盘（3.12：/api/v1/drill-matrix 真账本直出） -->
    <div class="card matrix-zone" v-if="matrix">
      <div class="zone-head">
        <h3 class="zone-title">韧性覆盖矩阵（场景 × 靶场）</h3>
        <el-tag size="small" :type="matrix.coverage?.pct >= 50 ? 'success' : 'warning'" disable-transitions>
          覆盖率 {{ matrix.coverage?.pct ?? '—' }}%（{{ matrix.coverage?.covered ?? 0 }}/{{ matrix.coverage?.total ?? 0 }}）
        </el-tag>
        <span class="dim">空格 = 该组合从未演练，即韧性缺口；模板目录即故障模式库（registryVersion {{ matrix.registryVersion ?? '—' }}）</span>
      </div>
      <el-table :data="matrixRows" size="small">
        <el-table-column label="故障模式（场景）" min-width="240">
          <template #default="{ row }">
            <div class="cell-main">{{ row.name }}</div>
            <div class="dim">{{ row.faultSource }}（{{ row.chaosFamily }}）</div>
          </template>
        </el-table-column>
        <el-table-column v-for="env in matrix.envs" :key="env" :label="env" min-width="130">
          <template #default="{ row }">
            <template v-if="row.cells[env]">
              <el-tag size="small" :type="stateTone(row.cells[env].lastState)" disable-transitions>
                {{ row.cells[env].drills }} 次 · {{ stateZh(row.cells[env].lastState) }}
              </el-tag>
              <div class="dim">{{ fmtTime(row.cells[env].lastAt) }}</div>
            </template>
            <span v-else class="dim">未演练</span>
          </template>
        </el-table-column>
      </el-table>
    </div>

    <div class="card four-zone" v-if="fourPhase.length">
      <div class="zone-head">
        <h3 class="zone-title">四段复盘（注入 / 感知 / 定界 / 恢复）</h3>
        <span class="dim">注入=作业推进过预检；感知=已关联事故；定界=已关联调查；恢复=状态闭环。全部来自 drill_job 真列。</span>
      </div>
      <el-table :data="fourPhase" size="small">
        <el-table-column label="演练" min-width="180">
          <template #default="{ row }">
            {{ row.scenarioName }}<span class="dim">（{{ row.targetEnv }}）</span>
          </template>
        </el-table-column>
        <el-table-column label="① 注入" width="110">
          <template #default="{ row }">
            <el-tag size="small" :type="row.inject ? 'success' : 'info'" disable-transitions>{{ row.inject ? '已注入' : '未到' }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="② 感知" width="110">
          <template #default="{ row }">
            <el-tag size="small" :type="row.perceive ? 'success' : 'info'" disable-transitions>{{ row.perceive ? '已关联事故' : '未关联' }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="③ 定界" width="110">
          <template #default="{ row }">
            <el-tag size="small" :type="row.locate ? 'success' : 'info'" disable-transitions>{{ row.locate ? '已关联调查' : '未关联' }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="④ 恢复" width="130">
          <template #default="{ row }">
            <el-tag size="small" :type="row.recoverDone ? 'success' : (row.recoverFailed ? 'danger' : 'info')" disable-transitions>
              {{ row.recoverDone ? '闭环' : (row.recoverFailed ? '恢复失败' : '未到') }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="创建时间" width="170">
          <template #default="{ row }">{{ fmtTime(row.createdAt) }}</template>
        </el-table-column>
      </el-table>
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
        <!-- 键集游标分页（与 EvalRunsView 同式）：已加载计数 + 加载更多 -->
        <div class="pager">
          <span class="muted">已加载 {{ items.length }} 条</span>
          <el-button v-if="nextCursor" :loading="loadingMore" @click="loadMore">加载更多</el-button>
        </div>
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
import { api } from '../api/client'
import EmptyState from '../components/common/EmptyState.vue'
import PageHeader from '../components/common/PageHeader.vue'
import { listDrills, ApiNotReadyError } from '../api/drills'
import { fmtDuration, fmtTime } from '../utils/format'

// ===== 3.12 韧性覆盖矩阵 + 四段复盘（/api/v1/drill-matrix 真账本直出） =====
const matrix = ref(null)
const matrixRows = ref([])
const fourPhase = ref([])
const DRILL_STATE_ZH = {
  QUEUED: '排队中', PRECHECK: '预检中', INJECTING: '注入中', OBSERVING: '观测中',
  RECOVERING: '恢复中', VERIFYING: '恢复核验中', CLOSED: '已闭环', RECOVERY_FAILED: '恢复失败',
}
function stateZh(s) { return DRILL_STATE_ZH[s] ?? s }
function stateTone(s) {
  if (s === 'CLOSED') return 'success'
  if (s === 'RECOVERY_FAILED') return 'danger'
  return 'warning'
}
async function loadMatrix() {
  try {
    const res = await api('/v1/drill-matrix')
    if (res?.status === 'OK') {
      matrix.value = res
      const byKey = {}
      for (const c of res.cells ?? []) {
        byKey[c.scenarioName + '|' + c.targetEnv] = c
      }
      matrixRows.value = (res.scenarios ?? []).map(s => {
        const cells = {}
        for (const env of res.envs ?? []) {
          const hit = byKey[s.name + '|' + env]
          if (hit) cells[env] = hit
        }
        return { ...s, cells }
      })
    }
  } catch { /* 洞察面缺席如实隐藏 */ }
}
async function loadFourPhase() {
  try {
    const res = await api('/v1/drill-matrix/four-phase')
    if (res?.status === 'OK') fourPhase.value = res.items ?? []
  } catch { /* 四段面缺席如实隐藏 */ }
}

const router = useRouter()

const items = ref([])
const summary = ref({})
const nextCursor = ref(null)
const listState = ref('loading') // loading | ok | not-ready | error
const loading = ref(false)
const loadingMore = ref(false)
const notReady = ref(false)

async function loadList() {
  loading.value = true
  try {
    const d = await listDrills({ limit: 50 })
    items.value = d.items ?? []
    summary.value = d.summary ?? {}
    nextCursor.value = d.nextCursor ?? null
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

// 加载更多：服务端 nextCursor 为唯一翻页依据；summary 真计数以首页为准，不随翻页追加
async function loadMore() {
  if (!nextCursor.value) return
  loadingMore.value = true
  try {
    const d = await listDrills({ cursor: nextCursor.value, limit: 50 })
    items.value = items.value.concat(d.items ?? [])
    nextCursor.value = d.nextCursor ?? null
  } catch (e) {
    if (e instanceof ApiNotReadyError) {
      notReady.value = true
      listState.value = 'not-ready'
    } else {
      ElMessage.error('加载更多失败，请重试')
    }
  } finally {
    loadingMore.value = false
  }
}

onMounted(() => { loadList(); loadMatrix(); loadFourPhase() })
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
.pager { display: flex; align-items: center; justify-content: center; gap: 16px; padding: 12px 0 4px; }
.muted { color: var(--ink-2); font-size: var(--fs-aux); }
.loading-box { height: 320px; }
</style>
