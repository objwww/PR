<template>
  <div class="assets-page">
    <PageHeader
      title="能力版本"
      subtitle="Prompt / Skill / Tool 等能力资产的只读清单与配置包版本：全部数据来自后端 release-assets 实时查询；数据面没有的字段如实标注「未透出」，不推算、不模拟。"
    >
      <template #actions>
        <el-button :loading="bundlesLoading || assetsLoading" @click="reload">刷新</el-button>
      </template>
    </PageHeader>

    <!-- 资产列表：kind 过滤进 URL query（?kind=，浏览器前进/后退回灌） -->
    <div class="card zone">
      <div class="zone-head">
        <h2 class="zone-title">能力资产</h2>
        <div class="zone-tools">
          <span
              class="zone-note"
              title="方案 §4.1 定义七类资产；后端 kind 白名单当前仅五类（ReleaseAsset.java），MCP / RAG / 多Agent策略 / 上下文压缩策略暂无对应资产面，本批未透出">
            七类中 MCP / RAG / 多Agent / 上下文压缩暂无后端资产面
          </span>
          <el-select v-model="kind" class="kind-select" @change="onKindChange">
            <el-option label="全部类型" value="" />
            <el-option v-for="k in KINDS" :key="k" :label="kindLabel(k)" :value="k" />
          </el-select>
        </div>
      </div>
      <template v-if="assetsState === 'ok'">
        <el-table :data="assets.items ?? []" v-loading="assetsLoading" row-key="digest" @row-click="openDetail">
          <el-table-column label="显示名" min-width="200">
            <template #default="{ row }">
              <div class="cell-main">{{ displayName(row) }}</div>
              <div v-if="row.summary?.description" class="cell-sub">{{ row.summary.description }}</div>
            </template>
          </el-table-column>
          <el-table-column label="类型" width="150">
            <template #default="{ row }">
              <el-tag size="small" effect="plain" disable-transitions>{{ kindLabel(row.kind) }}</el-tag>
            </template>
          </el-table-column>
          <el-table-column label="digest" min-width="180">
            <template #default="{ row }">
              <span class="mono cell-digest" :title="row.digest">{{ shortDigest(row.digest) }}</span>
              <el-button size="small" text @click.stop="copyText(row.digest, 'digest 已复制')">复制</el-button>
            </template>
          </el-table-column>
          <el-table-column label="逻辑版本" width="110">
            <template #default>
              <el-tooltip
                content="资产身份 = 内容 digest，后端无独立逻辑 ID / 版本列（方案 §4.1 要求，数据面无），不做推算"
                placement="top">
                <span class="muted">未透出</span>
              </el-tooltip>
            </template>
          </el-table-column>
          <el-table-column label="状态" width="110">
            <template #default>
              <el-tooltip
                content="生效状态只能表达「是否在当前 active bundle 内」，但资产与 bundle 的归属关系无读面，无法判定，如实显示未透出"
                placement="top">
                <span class="muted">未透出</span>
              </el-tooltip>
            </template>
          </el-table-column>
          <el-table-column label="创建人" width="130">
            <template #default="{ row }">{{ row.created_by ?? '—' }}</template>
          </el-table-column>
          <el-table-column label="创建时间" width="170">
            <template #default="{ row }">{{ fmtTime(row.created_at) }}</template>
          </el-table-column>
          <template #empty>
            <EmptyState kind="empty" description="该类型暂无能力资产。" />
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

    <!-- 配置包（config_bundle）：当前 active 指针 + revision 历史；发布/激活走 RELEASE 机器线，本页只读 -->
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
          <el-table-column label="发布人" width="140">
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

    <!-- 资产明细抽屉：方案 §4.1 固定四块——内容有读面如实展示；差异 / 实验结果 / 发布记录后端无读面，骨架占位注明 -->
    <DetailDrawer v-model="detailVisible" :title="detailTitle" :size="640">
      <div v-loading="detailLoading" class="detail-body">
        <template v-if="detailState === 'ok' && detail">
          <div class="cd-section">
            <div class="cd-sec-title">内容</div>
            <div class="detail-meta">
              <span>{{ kindLabel(detail.kind) }}</span>
              <span class="mono">{{ detail.digest }}</span>
              <span>{{ fmtTime(detail.created_at) }}（{{ detail.created_by ?? '—' }}）</span>
            </div>
            <pre class="detail-pre mono">{{ detailContent }}</pre>
          </div>
          <div class="cd-section">
            <div class="cd-sec-title">差异</div>
            <div class="muted-block">版本差异对比依赖后端 diff 读面，本批未透出。</div>
          </div>
          <div class="cd-section">
            <div class="cd-sec-title">实验结果</div>
            <div class="muted-block">资产关联实验无后端读面，本批未透出。</div>
          </div>
          <div class="cd-section">
            <div class="cd-sec-title">发布记录</div>
            <div class="muted-block">资产发布历史无后端读面，本批未透出。</div>
          </div>
        </template>
        <EmptyState
          v-else-if="detailState === 'not-ready'"
          kind="forbidden"
          :description="detailNotReadyText" />
        <EmptyState
          v-else-if="detailState === 'notfound'"
          kind="empty"
          description="资产不存在（服务端 404）：可能刚被清理或 digest 有误，请刷新列表。" />
        <EmptyState v-else-if="detailState === 'error'" kind="error" @retry="reloadDetail" />
      </div>
    </DetailDrawer>
  </div>
</template>

<script setup>
// EV-09 能力版本中心（/eval/assets）：消费 EN-10 已交付的 release-assets 只读端点（本批不改后端）。
// 诚实语义：资产无独立逻辑 ID/版本列（身份=内容 digest，显示名回退 summary.name/title → digest 缩写）；
// 无 status 列（是否生效依赖资产↔bundle 归属读面，不存在）；无 diff / 发布历史 / 关联实验读面——
// 以上一律显示「未透出」并注明原因，不做假按钮、不伪造数据。
// kind 过滤进 URL query（?kind=），浏览器前进/后退回灌（同 EvalReviewView 模式）。
import { onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import PageHeader from '../components/common/PageHeader.vue'
import EmptyState from '../components/common/EmptyState.vue'
import DetailDrawer from '../components/common/DetailDrawer.vue'
import {
  ApiNotReadyError, listAssets, listBundles, getAssetDetail,
} from '../api/versions'
import { fmtTime } from '../utils/format'

const route = useRoute()
const router = useRouter()

const KINDS = ['PROMPT', 'SKILL', 'TOOL_SCHEMA', 'RUNBOOK_DOC', 'RUNBOOK_CATALOG']
const KIND_LABELS = {
  PROMPT: 'Prompt',
  SKILL: 'Skill',
  TOOL_SCHEMA: 'Tool Schema',
  RUNBOOK_DOC: 'Runbook 文档',
  RUNBOOK_CATALOG: 'Runbook 目录',
}
const kindLabel = k => KIND_LABELS[k] ?? k ?? '—'

const str = v => (typeof v === 'string' ? v : '')
// query 中的 kind 只接受白名单值；非法值（如方案中的 MCP）不落进请求（后端会 400），按「全部」处理
const sanitizeKind = v => (KINDS.includes(v) ? v : '')

const kind = ref(sanitizeKind(str(route.query.kind)))
const assets = ref({})
const assetsState = ref('loading')
const assetsLoading = ref(false)
const bundles = ref({})
const bundlesState = ref('loading')
const bundlesLoading = ref(false)

const assetsNotReadyText = ref('')
const bundlesNotReadyText = ref('')

function notReadyText(e) {
  return e.status === 403
    ? '当前账号无权访问版本中心接口（HTTP 403）：/api/release-assets/** 要求 OPERATOR 角色。本页不展示任何模拟数据。'
    : '版本中心接口不存在（HTTP 404）：后端未以 docker profile 部署 EN-10 版本中心（ReleaseAssetQueryController 未注册）。本页不展示任何模拟数据。'
}

async function loadAssets() {
  assetsLoading.value = true
  try {
    assets.value = await listAssets(kind.value || undefined, 200)
    assetsState.value = 'ok'
  } catch (e) {
    assetsState.value = e instanceof ApiNotReadyError ? 'not-ready' : 'error'
    if (assetsState.value === 'not-ready') assetsNotReadyText.value = notReadyText(e)
  } finally {
    assetsLoading.value = false
  }
}

async function loadBundles() {
  bundlesLoading.value = true
  try {
    bundles.value = await listBundles()
    bundlesState.value = 'ok'
  } catch (e) {
    bundlesState.value = e instanceof ApiNotReadyError ? 'not-ready' : 'error'
    if (bundlesState.value === 'not-ready') bundlesNotReadyText.value = notReadyText(e)
  } finally {
    bundlesLoading.value = false
  }
}

function reload() {
  loadAssets()
  loadBundles()
}

function onKindChange() {
  router.replace({ query: kind.value ? { kind: kind.value } : {} })
  loadAssets()
}

// 浏览器前进/后退：?kind= 回灌过滤器并重载（EU05）
watch(() => route.query.kind, v => {
  const next = sanitizeKind(str(v))
  if (next === kind.value) return
  kind.value = next
  loadAssets()
})

function shortDigest(d) {
  return d ? d.slice(0, 12) + '…' : '—'
}

// 逻辑名：仅取 summary.name/title（数据面真实键）；没有则回退 digest 缩写，不杜撰名称
function displayName(row) {
  return row.summary?.name ?? row.summary?.title ?? shortDigest(row.digest)
}

function copyText(text, tip) {
  navigator.clipboard?.writeText(text).then(
    () => ElMessage.success(tip),
    () => ElMessage.error('复制失败'),
  )
}

const detailVisible = ref(false)
const detailLoading = ref(false)
const detail = ref(null)
const detailState = ref('loading') // loading | ok | not-ready | notfound | error
const detailTitle = ref('资产明细')
const detailContent = ref('')
const detailNotReadyText = ref('')
let detailRow = null

async function reloadDetail() {
  if (!detailRow) return
  detailLoading.value = true
  detailState.value = 'loading'
  detail.value = null
  detailContent.value = ''
  try {
    const d = await getAssetDetail(detailRow.kind, detailRow.digest)
    detail.value = d
    detailContent.value = JSON.stringify(d.content, null, 2)
    detailState.value = 'ok'
  } catch (e) {
    if (e instanceof ApiNotReadyError) {
      detailState.value = 'not-ready'
      detailNotReadyText.value = notReadyText(e)
    } else {
      detailState.value = e?.response?.status === 404 ? 'notfound' : 'error'
    }
  } finally {
    detailLoading.value = false
  }
}

function openDetail(row) {
  detailRow = row
  detailTitle.value = `资产明细 · ${kindLabel(row.kind)}`
  detailVisible.value = true
  reloadDetail()
}

onMounted(reload)
</script>

<style scoped>
.assets-page { display: flex; flex-direction: column; gap: var(--section-gap); }
.zone { padding: 16px var(--card-pad) 12px; }
.zone-head { display: flex; align-items: baseline; justify-content: space-between; gap: 12px; margin-bottom: 12px; }
.zone-title { font-size: 16px; font-weight: 600; color: var(--head); margin: 0; }
.zone-tools { display: flex; align-items: center; gap: 12px; }
.zone-note { font-size: var(--fs-aux); color: var(--ink-2); cursor: help; }
.kind-select { width: 180px; }
.pointer-line { display: flex; align-items: center; gap: 10px; margin-bottom: 12px; flex-wrap: wrap; }
.pointer-digest { font-size: 13px; }
.pointer-meta { font-size: var(--fs-aux); color: var(--ink-2); }
.mono { font-family: var(--mono, monospace); }
.cell-digest { font-size: 12px; word-break: break-all; }
.cell-main { font-size: var(--fs-body); line-height: 1.4; }
.cell-sub { font-size: var(--fs-aux); color: var(--ink-2); line-height: 1.4; }
.muted { color: var(--ink-2); font-size: var(--fs-aux); cursor: help; }
.loading-box { height: 240px; }
.detail-body { display: flex; flex-direction: column; gap: 16px; min-height: 200px; }
.cd-section { display: flex; flex-direction: column; gap: 8px; }
.cd-sec-title { font-weight: 600; font-size: var(--fs-body); }
.detail-meta { display: flex; gap: 16px; font-size: var(--fs-aux); color: var(--ink-2); flex-wrap: wrap; }
.detail-pre {
  background: var(--bg); border: 1px solid var(--line); border-radius: var(--radius);
  padding: 12px; font-size: 12px; max-height: 480px; overflow: auto;
  white-space: pre-wrap; word-break: break-all; margin: 0;
}
.muted-block {
  background: var(--bg); border: 1px dashed var(--line); border-radius: var(--radius);
  padding: 12px; font-size: var(--fs-aux); color: var(--ink-2);
}
</style>
