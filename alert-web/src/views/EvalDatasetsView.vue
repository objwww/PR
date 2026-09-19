<template>
  <div class="datasets-page">
    <div class="table-zone card">
      <template v-if="listState === 'ok'">
        <el-table :data="items" v-loading="loading" :row-key="rowKey" @expand-change="onExpand">
          <el-table-column type="expand">
            <template #default="{ row }">
              <div class="case-zone" v-loading="caseLoading[rowKey(row)]">
                <template v-if="caseRows(rowKey(row)).length">
                  <div v-for="c in caseRows(rowKey(row))" :key="c.caseKey" class="case-item">
                    <div class="case-head">
                      <span class="case-key">{{ c.caseKey }}</span>
                      <el-tag size="small" effect="plain" disable-transitions>{{ c.scenarioFamilyId ?? '—' }}</el-tag>
                      <el-tag v-if="c.partitionClass" size="small" type="info" effect="plain" disable-transitions>{{ c.partitionClass }}</el-tag>
                    </div>
                    <div v-if="c.note" class="case-note">{{ c.note }}</div>
                    <div class="case-meta">
                      <span>期望根因：<b>{{ c.expectedRootCause ?? '未标注' }}</b></span>
                      <span v-if="c.expectedSymptomCodes?.length">
                        期望症状：
                        <el-tag
                          v-for="s in c.expectedSymptomCodes" :key="s"
                          size="small" effect="plain" class="fam-chip" disable-transitions
                        >{{ s }}</el-tag>
                      </span>
                    </div>
                  </div>
                </template>
                <el-empty
                  v-else-if="caseLoaded[rowKey(row)]" description="该版本暂无可展示案例"
                  :image-size="48"
                />
              </div>
            </template>
          </el-table-column>
          <el-table-column prop="version" label="版本" min-width="150" show-overflow-tooltip />
          <el-table-column label="分层（冒烟/回归/探索/红队）" width="200">
            <template #default="{ row }">
              <el-select
                :model-value="tierOf(row)" size="small" clearable placeholder="未分层"
                @update:model-value="v => setTier(row, v)"
              >
                <el-option v-for="(label, key) in TIER_ZH" :key="key" :label="label" :value="key" />
              </el-select>
            </template>
          </el-table-column>
          <el-table-column prop="source" label="来源" min-width="140" show-overflow-tooltip>
            <template #default="{ row }">{{ row.source ?? '—' }}</template>
          </el-table-column>
          <el-table-column label="案例数" width="90" align="right">
            <template #default="{ row }">{{ row.caseCount ?? '未统计' }}</template>
          </el-table-column>
          <el-table-column label="评分细则（rubric）" width="150">
            <template #default="{ row }">{{ row.rubricVersion ?? '未统计' }}</template>
          </el-table-column>
          <el-table-column label="故障族" min-width="220">
            <template #default="{ row }">
              <template v-if="row.families?.length">
                <el-tag
                  v-for="f in row.families" :key="f"
                  size="small" effect="plain" class="fam-chip" disable-transitions
                >{{ f }}</el-tag>
              </template>
              <span v-else>—</span>
            </template>
          </el-table-column>
          <el-table-column label="创建时间" width="160">
            <template #default="{ row }">{{ fmtTime(row.createdAt) }}</template>
          </el-table-column>
          <template #empty>
            <EmptyState kind="empty" description="暂无评测数据集" />
          </template>
        </el-table>
      </template>
      <EmptyState v-else-if="listState === 'forbidden'" kind="forbidden" />
      <EmptyState v-else-if="listState === 'error'" kind="error" @retry="loadList" />
      <div v-else v-loading="true" class="loading-box" />
    </div>
  </div>
</template>

<script setup>
// UI-6 数据集页（/eval/datasets）：GET /eval/datasets 版本表
// 3.11 评测增强：数据集分层打标（冒烟/回归/探索/红队）——eval_dataset_tier 治理表（V133）
import { onMounted, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { api } from '../api/client'
import { useSessionStore } from '../stores/session.js'
import { TIER_ZH } from '../dict/zh.js'
import EmptyState from '../components/common/EmptyState.vue'
import { fmtTime } from '../utils/format'

const session = useSessionStore()
const items = ref([])
const tiers = ref({}) // name|version -> tier
const listState = ref('loading') // loading | ok | error | forbidden
const loading = ref(false)
const caseMap = ref({}) // rowKey -> 案例清单
const caseLoaded = ref({}) // rowKey -> 是否已请求过（含空表）
const caseLoading = ref({}) // rowKey -> loading

function rowKey(row) { return row.name + '|' + row.version }
function caseRows(key) { return caseMap.value[key] ?? [] }
async function onExpand(row, expanded) {
  const open = Array.isArray(expanded) ? expanded.includes(row) : expanded === row
  const key = rowKey(row)
  if (!open || caseLoaded.value[key]) return
  caseLoading.value = { ...caseLoading.value, [key]: true }
  try {
    const res = await api(`/eval/datasets/${encodeURIComponent(row.name)}/${encodeURIComponent(row.version)}/cases`)
    caseMap.value = { ...caseMap.value, [key]: res?.items ?? [] }
  } catch {
    caseMap.value = { ...caseMap.value, [key]: [] }
    ElMessage.error('案例清单加载失败，请重试')
  } finally {
    caseLoaded.value = { ...caseLoaded.value, [key]: true }
    caseLoading.value = { ...caseLoading.value, [key]: false }
  }
}

function tierOf(row) { return tiers.value[row.name + '|' + row.version] ?? null }
async function setTier(row, tier) {
  if (!tier) return
  try {
    const res = await api('/eval/governance/dataset-tiers', {
      method: 'POST',
      body: { name: row.name, version: row.version, tier, createdBy: `human:${session.user || 'oncall'}` },
    })
    if (res?.status === 'OK') {
      tiers.value[row.name + '|' + row.version] = tier
      tiers.value = { ...tiers.value }
    } else {
      ElMessage.warning(`被拒绝：${res?.reason ?? '未知原因'}`)
    }
  } catch { ElMessage.error('打标失败，请重试') }
}
async function loadTiers() {
  try {
    const res = await api('/eval/governance/dataset-tiers')
    const map = {}
    for (const it of res?.items ?? []) map[it.name + '|' + it.version] = it.tier
    tiers.value = map
  } catch { /* 治理面缺席如实留空 */ }
}

async function loadList() {
  listState.value = 'loading'
  loading.value = true
  try {
    const d = await api('/eval/datasets')
    items.value = d.items ?? []
    listState.value = 'ok'
  } catch (e) {
    listState.value = e?.response?.status === 403 ? 'forbidden' : 'error'
  } finally {
    loading.value = false
  }
}

onMounted(() => { loadList(); loadTiers() })
</script>

<style scoped>
.datasets-page { display: flex; flex-direction: column; gap: var(--section-gap); }
.table-zone { padding: 8px var(--card-pad) 12px; }
.fam-chip { margin: 2px 4px 2px 0; }
.loading-box { height: 320px; }
.case-zone { padding: 8px 16px 12px 48px; display: flex; flex-direction: column; gap: 10px; }
.case-item { border: 1px solid var(--el-border-color-lighter); border-radius: 6px; padding: 8px 12px; }
.case-head { display: flex; align-items: center; gap: 8px; }
.case-key { font-weight: 600; font-family: var(--mono-font, monospace); }
.case-note { color: var(--el-text-color-regular); font-size: 13px; margin-top: 4px; }
.case-meta { color: var(--el-text-color-secondary); font-size: 12px; margin-top: 4px; display: flex; gap: 16px; flex-wrap: wrap; }
</style>
