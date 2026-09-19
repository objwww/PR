<template>
  <div class="drafts">
    <div class="crumb">
      <router-link to="/prompt">Prompt 工作台</router-link>
      <span class="sep">/</span>
      <b>{{ mdZh.drafts.title }}</b>
    </div>

    <!-- 起草区：role 选自既有投产角色；基线由后端从 release_asset 最新行冻结 -->
    <div class="card panel">
      <div class="es-head">
        <span class="es-title">{{ mdZh.drafts.newDraft }}</span>
        <span class="muted es-note">{{ mdZh.drafts.source }}</span>
      </div>
      <el-form label-position="top">
        <el-form-item :label="mdZh.drafts.role">
          <el-select v-model="form.role" filterable style="width: 320px">
            <el-option v-for="r in roles" :key="r" :value="r" :label="r" />
          </el-select>
        </el-form-item>
        <el-form-item :label="mdZh.drafts.proposedTemplate">
          <el-input v-model="form.proposedTemplate" type="textarea" :rows="10"
            :placeholder="mdZh.drafts.baseSnapshot" />
        </el-form-item>
        <el-button type="primary" :loading="submitting" :disabled="!form.role || !form.proposedTemplate"
          @click="submitDraft">{{ mdZh.drafts.newDraft }}</el-button>
      </el-form>
    </div>

    <!-- 草稿列表 -->
    <div class="card panel">
      <el-table v-if="items.length" :data="items" size="small">
        <el-table-column prop="role" label="角色" min-width="120" />
        <el-table-column label="基线版本" min-width="90">
          <template #default="{ row }">{{ row.baseRoleVersion ?? '—' }}</template>
        </el-table-column>
        <el-table-column label="状态" min-width="90">
          <template #default="{ row }">
            <el-tag size="small" disable-transitions>{{ statusZh(row.status) }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="author" label="起草人" min-width="100" />
        <el-table-column label="字数 基线→提案" min-width="130">
          <template #default="{ row }">{{ row.baseLen }} → {{ row.proposedLen }}</template>
        </el-table-column>
        <el-table-column label="更新时间" min-width="160">
          <template #default="{ row }">{{ fmtTime(row.updatedAt) }}</template>
        </el-table-column>
        <el-table-column label="操作" min-width="150" fixed="right">
          <template #default="{ row }">
            <el-button size="small" text @click="openDiff(row)">{{ mdZh.drafts.diff }}</el-button>
            <el-button v-if="row.status === 'DRAFT'" size="small" text type="danger"
              @click="discardDraft(row)">{{ mdZh.drafts.discard }}</el-button>
          </template>
        </el-table-column>
      </el-table>
      <EmptyState v-else :title="mdZh.drafts.empty" />
    </div>

    <!-- 对照抽屉：基线快照 vs 本稿提案（后端行数组直出，行级对读） -->
    <el-drawer v-model="diffOpen" size="60%" :title="mdZh.drafts.diff + ' · ' + (diff?.role ?? '')">
      <template v-if="diff">
        <div class="diff-panes">
          <div class="diff-pane">
            <div class="es-label">{{ mdZh.drafts.baseline }}（{{ diff.baseRoleVersion }}）</div>
            <pre class="diff-pre">{{ diff.baseLines.join('\n') }}</pre>
          </div>
          <div class="diff-pane">
            <div class="es-label">{{ mdZh.drafts.proposal }}</div>
            <pre class="diff-pre">{{ diff.proposedLines.join('\n') }}</pre>
          </div>
        </div>
      </template>
    </el-drawer>
  </div>
</template>

<script setup>
// M-d T7 前端：提示词草稿工作面（独立页面——工作台主视图属他人在途改动面，零接触；
// 发布不在本页：走版本中心受控激活，零绕行，见 V153/方案 §7）
import { onMounted, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { api } from '../api/client'
import EmptyState from '../components/common/EmptyState.vue'
import { mdZh } from '../dict/mdZh'
import { fmtTime } from '../utils/format'

const roles = ref([])
const items = ref([])
const form = ref({ role: '', proposedTemplate: '' })
const submitting = ref(false)
const diffOpen = ref(false)
const diff = ref(null)

function statusZh(s) {
  return mdZh.drafts.status[s] ?? s
}

async function loadAll() {
  try {
    const d = await api('/v1/prompt-workbench/drafts')
    items.value = d.items ?? []
  } catch { /* 草稿面缺席如实留空 */ }
  try {
    const a = await api('/v1/prompt-workbench/assets')
    roles.value = a.roles ?? []
  } catch { /* 角色面缺席如实留空 */ }
}

async function submitDraft() {
  submitting.value = true
  try {
    await api('/v1/prompt-workbench/drafts', {
      method: 'POST',
      body: { role: form.value.role, proposedTemplate: form.value.proposedTemplate },
    })
    ElMessage.success(mdZh.drafts.submitOk)
    form.value.proposedTemplate = ''
    await loadAll()
  } catch (e) {
    ElMessage.error(e?.response?.data?.error ?? e?.message ?? '起草失败')
  } finally {
    submitting.value = false
  }
}

async function openDiff(row) {
  try {
    diff.value = await api(`/v1/prompt-workbench/drafts/${row.id}/diff`)
    diffOpen.value = true
  } catch { /* diff 缺席如实 */ }
}

async function discardDraft(row) {
  try {
    await ElMessageBox.confirm(`弃稿后不可恢复：${row.role}（基线 ${row.baseRoleVersion}）`, mdZh.drafts.discard, { type: 'warning' })
  } catch {
    return
  }
  try {
    await api(`/v1/prompt-workbench/drafts/${row.id}/discard`, { method: 'POST' })
    ElMessage.success(mdZh.drafts.discardOk)
    await loadAll()
  } catch (e) {
    ElMessage.error(e?.response?.data?.error ?? e?.message ?? '弃稿未中（可能已裁定）')
  }
}

onMounted(loadAll)
</script>

<style scoped>
.crumb { display: flex; gap: 8px; align-items: center; margin-bottom: 12px; color: var(--text-secondary); }
.crumb .sep { color: var(--border-color); }
.panel { margin-bottom: 16px; }
.es-head { display: flex; gap: 10px; align-items: baseline; margin-bottom: 12px; }
.es-title { font-weight: 600; }
.es-note { font-size: 12px; }
.es-label { font-size: 12px; color: var(--text-secondary); margin-bottom: 6px; }
.diff-panes { display: grid; grid-template-columns: 1fr 1fr; gap: 12px; }
.diff-pre { margin: 0; padding: 10px; background: var(--bg-secondary, #f7f8fa); border: 1px solid var(--border-color, #e5e6eb);
  border-radius: 4px; font-size: 12px; line-height: 1.5; white-space: pre-wrap; word-break: break-all;
  max-height: 70vh; overflow: auto; }
</style>
