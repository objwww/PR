<template>
  <div class="versions-page">
    <PageHeader
      title="版本中心"
      subtitle="发布资产与配置包版本的只读清单：当前激活指针、资产摘要与运行配置切换状态全部来自后端实时查询，刷新即恢复真实状态。"
    >
      <template #actions>
        <el-button :loading="bundlesLoading || assetsLoading" @click="reload">刷新</el-button>
      </template>
    </PageHeader>

    <!-- 配置包（config_bundle）：当前指针 + 版本列表；发布/激活仍走 RELEASE 机器线，本页只读 -->
    <div class="card zone">
      <div class="zone-head">
        <h2 class="zone-title">配置包版本</h2>
        <span class="zone-note">发布与激活不在此页操作（RELEASE 机器线）；此处只如实展示版本与当前指针</span>
      </div>
      <template v-if="bundlesState === 'ok'">
        <div class="pointer-line">
          <template v-if="bundles.active">
            <el-tag type="success" effect="plain">当前激活</el-tag>
            <span class="mono pointer-digest">{{ bundles.active.digest }}</span>
            <span class="pointer-meta">revision {{ bundles.active.revision }} · 生效于 {{ fmtTime(bundles.active.activated_at) }}</span>
          </template>
          <template v-else>
            <el-tag type="info" effect="plain">未激活</el-tag>
            <span class="pointer-meta">尚无任何配置包被激活（如实显示，不推算）</span>
          </template>
        </div>
        <el-table :data="bundles.items ?? []" v-loading="bundlesLoading" row-key="digest">
          <el-table-column label="digest" min-width="300">
            <template #default="{ row }">
              <span class="mono cell-digest">{{ row.digest }}</span>
            </template>
          </el-table-column>
          <el-table-column prop="revision" label="revision" width="100" />
          <el-table-column prop="created_by" label="发布人" width="140">
            <template #default="{ row }">{{ row.created_by ?? '—' }}</template>
          </el-table-column>
          <el-table-column label="创建时间" width="180">
            <template #default="{ row }">{{ fmtTime(row.created_at) }}</template>
          </el-table-column>
          <el-table-column label="状态" width="120">
            <template #default="{ row }">
              <el-tag v-if="row.active" type="success" effect="plain" size="small">激活中</el-tag>
              <el-tag v-else type="info" effect="plain" size="small">历史版本</el-tag>
            </template>
          </el-table-column>
          <template #empty>
            <EmptyState kind="empty" description="尚无配置包版本记录。" />
          </template>
        </el-table>
      </template>
      <el-result
        v-else-if="bundlesState === 'not-ready'"
        icon="warning"
        title="配置包接口不可用"
        :sub-title="bundlesNotReadyText"
      >
        <template #extra>
          <el-button :loading="bundlesLoading" @click="loadBundles">重试</el-button>
        </template>
      </el-result>
      <EmptyState v-else-if="bundlesState === 'error'" kind="error" @retry="loadBundles" />
      <div v-else v-loading="true" class="loading-box" />
    </div>

    <!-- 发布资产（release_asset）：kind 过滤 + 摘要列表 + 明细（含正文，仅点开明细时拉取） -->
    <div class="card zone">
      <div class="zone-head">
        <h2 class="zone-title">发布资产</h2>
        <el-select v-model="assetKind" class="kind-select" @change="loadAssets">
          <el-option label="全部类型" value="" />
          <el-option v-for="k in KINDS" :key="k" :label="k" :value="k" />
        </el-select>
      </div>
      <template v-if="assetsState === 'ok'">
        <el-table :data="assets.items ?? []" v-loading="assetsLoading" row-key="digest" @row-click="openDetail">
          <el-table-column prop="kind" label="类型" width="170" />
          <el-table-column label="摘要" min-width="220">
            <template #default="{ row }">
              <div class="cell-main">{{ summaryTitle(row.summary) }}</div>
              <div v-if="row.summary?.description" class="cell-sub">{{ row.summary.description }}</div>
            </template>
          </el-table-column>
          <el-table-column label="digest" min-width="280">
            <template #default="{ row }">
              <span class="mono cell-digest">{{ row.digest }}</span>
            </template>
          </el-table-column>
          <el-table-column label="创建时间" width="180">
            <template #default="{ row }">{{ fmtTime(row.created_at) }}</template>
          </el-table-column>
          <template #empty>
            <EmptyState kind="empty" description="该类型暂无发布资产。" />
          </template>
        </el-table>
      </template>
      <el-result
        v-else-if="assetsState === 'not-ready'"
        icon="warning"
        title="资产接口不可用"
        :sub-title="assetsNotReadyText"
      >
        <template #extra>
          <el-button :loading="assetsLoading" @click="loadAssets">重试</el-button>
        </template>
      </el-result>
      <EmptyState v-else-if="assetsState === 'error'" kind="error" @retry="loadAssets" />
      <div v-else v-loading="true" class="loading-box" />
    </div>

    <!-- 运行配置切换状态（EN-04 epoch）：按 runId 查询真实切换历史；输入草稿在失败时保留 -->
    <div class="card zone">
      <div class="zone-head">
        <h2 class="zone-title">运行配置切换状态</h2>
        <span class="zone-note">切换是否生效以这里查到的 epoch 历史为准——「已提交」不等于「已生效」</span>
      </div>
      <div class="epoch-query">
        <el-input
          v-model="runIdDraft"
          class="runid-input"
          placeholder="输入调查 runId（UUID）查询该运行的配置 epoch 历史"
          clearable
          @keyup.enter="loadEpochs"
        />
        <el-button type="primary" :loading="epochsLoading" @click="loadEpochs">查询</el-button>
      </div>
      <el-alert
        v-if="epochsState === 'not-ready'"
        class="epoch-alert"
        type="warning"
        :closable="false"
        show-icon
        title="查询接口不可用（输入已保留）"
        :description="epochsNotReadyText"
      />
      <el-alert
        v-else-if="epochsState === 'error'"
        class="epoch-alert"
        type="error"
        :closable="false"
        show-icon
        title="查询失败（输入已保留，可重试）"
        description="请求发出但后端返回错误，切换状态未知——本页不猜测、不展示推断结果。"
      />
      <template v-if="epochsState === 'ok'">
        <div class="pointer-line">
          <el-tag :type="epochs.mixed_config ? 'warning' : 'success'" effect="plain">
            {{ epochs.mixed_config ? '混合配置态' : '单一配置态' }}
          </el-tag>
          <span class="pointer-meta">mixed_config 由后端实时判定，非本地推断</span>
        </div>
        <el-table :data="epochs.epochs ?? []" v-loading="epochsLoading" row-key="config_epoch">
          <el-table-column prop="config_epoch" label="epoch" width="90" />
          <el-table-column label="release digest" min-width="280">
            <template #default="{ row }">
              <span class="mono cell-digest">{{ row.release_digest }}</span>
            </template>
          </el-table-column>
          <el-table-column label="来源命令" width="220">
            <template #default="{ row }">
              <span v-if="row.source_command_id" class="mono">{{ row.source_command_id }}</span>
              <span v-else class="cell-sub">准入播种（无命令）</span>
            </template>
          </el-table-column>
          <el-table-column prop="applied_by" label="操作者" width="140">
            <template #default="{ row }">{{ row.applied_by ?? '—' }}</template>
          </el-table-column>
          <el-table-column prop="reason" label="原因" min-width="160" />
          <el-table-column label="时间" width="180">
            <template #default="{ row }">{{ fmtTime(row.created_at) }}</template>
          </el-table-column>
          <template #empty>
            <EmptyState kind="empty" description="该运行尚无配置切换记录（如准入播种期），非查询失败。" />
          </template>
        </el-table>
      </template>
    </div>

    <!-- 资产明细：content 全量来自后端 detail 端点；仅点开时拉取，列表零正文 -->
    <el-dialog v-model="detailVisible" :title="detailTitle" width="720px">
      <div v-loading="detailLoading" class="detail-body">
        <template v-if="detail">
          <div class="detail-meta">
            <span>{{ detail.kind }}</span>
            <span class="mono">{{ detail.digest }}</span>
            <span>{{ fmtTime(detail.created_at) }}（{{ detail.created_by }}）</span>
          </div>
          <pre class="detail-pre mono">{{ detailContent }}</pre>
        </template>
      </div>
    </el-dialog>
  </div>
</template>

<script setup>
// 版本中心（/versions，EN-10 O06~O08）：三区只读——bundle 指针/版本、发布资产摘要、
// 运行配置 epoch 历史。三区各自独立三态（ok / not-ready / error）：接口 404/403 就地
// 解释（403 区分权限拒绝），不渲染成空数据；epoch 查询失败时保留输入草稿。
import { onMounted, ref } from 'vue'
import PageHeader from '../components/common/PageHeader.vue'
import EmptyState from '../components/common/EmptyState.vue'
import {
  ApiNotReadyError, listAssets, listBundles, getAssetDetail, getRunConfigEpochs,
} from '../api/versions'
import { fmtTime } from '../utils/format'

const KINDS = ['PROMPT', 'SKILL', 'TOOL_SCHEMA', 'RUNBOOK_DOC', 'RUNBOOK_CATALOG']

const bundles = ref({})
const bundlesState = ref('loading')
const bundlesLoading = ref(false)
const assets = ref({})
const assetKind = ref('')
const assetsState = ref('loading')
const assetsLoading = ref(false)
const epochs = ref({})
const epochsState = ref('idle')
const epochsLoading = ref(false)
const runIdDraft = ref('')
const detailVisible = ref(false)
const detailLoading = ref(false)
const detail = ref(null)

const notReadyBase = 'GET 请求被拒绝（接口不存在或当前账号无 OPERATOR 权限）：后端版本中心接口未部署（docker profile 才注册）或权限矩阵未放行。本页不展示任何模拟数据。'
const bundlesNotReadyText = ref(notReadyBase)
const assetsNotReadyText = ref(notReadyBase)
const epochsNotReadyText = ref(notReadyBase)

function markNotReady(kind, e) {
  const text = e.status === 403
    ? `当前账号无权访问版本中心接口（HTTP 403）：版本中心查询面要求 OPERATOR 角色。输入与页面数据保持原样，不渲染模拟数据。`
    : `版本中心接口不存在（HTTP 404）：后端未以 docker profile 部署 EN-10 版本中心（ReleaseAssetQueryController / RunConfigEpochController 未注册）。本页不展示任何模拟数据。`
  if (kind === 'bundles') bundlesNotReadyText.value = text
  else if (kind === 'assets') assetsNotReadyText.value = text
  else epochsNotReadyText.value = text
}

async function loadBundles() {
  bundlesLoading.value = true
  try {
    bundles.value = await listBundles()
    bundlesState.value = 'ok'
  } catch (e) {
    bundlesState.value = e instanceof ApiNotReadyError ? 'not-ready' : 'error'
    if (bundlesState.value === 'not-ready') markNotReady('bundles', e)
  } finally {
    bundlesLoading.value = false
  }
}

async function loadAssets() {
  assetsLoading.value = true
  try {
    assets.value = await listAssets(assetKind.value || undefined, 200)
    assetsState.value = 'ok'
  } catch (e) {
    assetsState.value = e instanceof ApiNotReadyError ? 'not-ready' : 'error'
    if (assetsState.value === 'not-ready') markNotReady('assets', e)
  } finally {
    assetsLoading.value = false
  }
}

async function loadEpochs() {
  const runId = runIdDraft.value.trim()
  if (!runId) return
  epochsLoading.value = true
  try {
    epochs.value = await getRunConfigEpochs(runId)
    epochsState.value = 'ok'
  } catch (e) {
    // 失败清空结果但保留 runId 草稿（O08：就地解释，不丢操作者输入）
    epochs.value = {}
    epochsState.value = e instanceof ApiNotReadyError ? 'not-ready' : 'error'
    if (epochsState.value === 'not-ready') markNotReady('epochs', e)
  } finally {
    epochsLoading.value = false
  }
}

function reload() {
  loadBundles()
  loadAssets()
}

function summaryTitle(summary) {
  if (!summary) return '—'
  return summary.title ?? summary.name ?? summary.runbook_id ?? '—'
}

const detailTitle = ref('资产明细')
const detailContent = ref('')

async function openDetail(row) {
  detailVisible.value = true
  detailLoading.value = true
  detail.value = null
  detailTitle.value = `资产明细 · ${row.kind}`
  detailContent.value = ''
  try {
    const d = await getAssetDetail(row.kind, row.digest)
    detail.value = d
    detailContent.value = JSON.stringify(d.content, null, 2)
  } catch (e) {
    detailContent.value = e instanceof ApiNotReadyError
      ? `明细接口不可用（${e.message}）`
      : '明细加载失败，请关闭后重试'
  } finally {
    detailLoading.value = false
  }
}

onMounted(reload)
</script>

<style scoped>
.versions-page { display: flex; flex-direction: column; gap: var(--section-gap); }
.zone { padding: 16px var(--card-pad) 12px; }
.zone-head { display: flex; align-items: baseline; justify-content: space-between; gap: 12px; margin-bottom: 12px; }
.zone-title { font-size: 16px; font-weight: 600; color: var(--head); margin: 0; }
.zone-note { font-size: var(--fs-aux); color: var(--ink-2); }
.kind-select { width: 180px; }
.pointer-line { display: flex; align-items: center; gap: 10px; margin-bottom: 12px; flex-wrap: wrap; }
.pointer-digest { font-size: 13px; }
.pointer-meta { font-size: var(--fs-aux); color: var(--ink-2); }
.epoch-query { display: flex; gap: 8px; margin-bottom: 12px; }
.runid-input { max-width: 480px; }
.epoch-alert { margin-bottom: 12px; }
.mono { font-family: var(--mono, monospace); }
.cell-digest { font-size: 12px; word-break: break-all; }
.cell-main { font-size: var(--fs-body); line-height: 1.4; }
.cell-sub { font-size: var(--fs-aux); color: var(--ink-2); line-height: 1.4; }
.loading-box { height: 240px; }
.detail-meta { display: flex; gap: 16px; font-size: var(--fs-aux); color: var(--ink-2); margin-bottom: 8px; flex-wrap: wrap; }
.detail-pre {
  background: var(--bg); border: 1px solid var(--line); border-radius: var(--radius);
  padding: 12px; font-size: 12px; max-height: 480px; overflow: auto;
  white-space: pre-wrap; word-break: break-all; margin: 0;
}
</style>
