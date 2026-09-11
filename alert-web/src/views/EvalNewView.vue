<template>
  <div class="new-page">
    <div class="crumb">
      <router-link to="/eval/runs">实验</router-link>
      <span class="sep">/</span>
      <b>新建实验</b>
    </div>

    <div class="card panel">
      <!-- 四步向导：模式 → 基线与候选 → 数据集/重复次数 → 预算/并发/截止时间；每步可点题头回看 -->
      <el-steps :active="step" align-center class="steps">
        <el-step
          v-for="(s, i) in stepTitles" :key="s" :title="s"
          class="step-clickable" @click="step = i"
        />
      </el-steps>

      <!-- 第一步：模式（E / B / L，中文说明真实副作用）+ 实验名称（契约必填） -->
      <div v-if="step === 0" class="step-body">
        <el-form label-width="120px" class="form">
          <el-form-item label="实验名称" required>
            <el-input v-model="form.displayName" placeholder="例如：qwen3-max-preview 基线回归" maxlength="128" show-word-limit />
          </el-form-item>
        </el-form>
        <div
          v-for="m in modes" :key="m.key"
          class="mode-card" :class="{ cur: form.mode === m.key }"
          @click="form.mode = m.key"
        >
          <div class="mode-head">
            <b>模式 {{ m.key }} · {{ m.name }}</b>
          </div>
          <div class="mode-desc">{{ m.desc }}</div>
          <div class="mode-side">{{ m.side }}</div>
        </div>
      </div>

      <!-- 第二步：被测版本（可选；空 = 沿用 worker env 元数据，EvalLaunchPlan 诚实边界） -->
      <div v-else-if="step === 1" class="step-body">
        <el-form label-width="120px" class="form">
          <el-form-item label="模型">
            <el-input v-model="form.model" placeholder="留空 = 沿用 worker 部署模型（digest 面落 configDigest）" />
          </el-form-item>
          <el-form-item label="Prompt 版本">
            <el-input v-model="form.promptVersion" placeholder="留空 = 沿用 worker 部署版本" />
          </el-form-item>
        </el-form>
        <div class="hint">
          后端契约（POST /api/eval/runs）只接受 model / promptVersion 两个版本面；
          基线对比不在此处指定——实验跑完后到对比工作台（/eval/compare）固定基线逐例比较。
          默认一次只变一个因素；多因素变更只能作组合验收，不能声称识别单因素收益。
        </div>
      </div>

      <!-- 第三步：数据集 / 重复次数 -->
      <div v-else-if="step === 2" class="step-body">
        <el-form label-width="120px" class="form">
          <el-form-item label="数据集版本">
            <el-select v-model="form.datasetVersion" placeholder="选择数据集版本" :loading="datasetsLoading" class="w-full">
              <el-option v-for="d in datasets" :key="d.version" :value="d.version" :label="`${d.version}（${d.caseCount} 案例）`" />
            </el-select>
            <div v-if="datasetsError" class="field-note">
              数据集列表加载失败（真实接口 GET /eval/datasets）。
              <el-button size="small" text type="primary" @click="loadDatasets">重试</el-button>
            </div>
            <div v-else-if="!datasetsLoading && !datasets.length" class="field-note">
              数据集接口（GET /eval/datasets）返回空列表，暂无可选版本；数据集版本为契约必填项，无数据集时无法提交，此处不提供静态兜底选项。
            </div>
          </el-form-item>
          <el-form-item label="重复次数">
            <el-input-number v-model="form.repeat" :min="1" :max="100" />
          </el-form-item>
        </el-form>
        <div class="hint">
          案例切片、分层与固定运行顺序策略依赖后端 EV-04 实验计划，本批未开放；
          HOLDOUT 盲测集不向普通操作者页面开放浏览。
        </div>
      </div>

      <!-- 第四步：预算 / 并发 / 截止时间（契约数值面；datetime 提交时换算 deadlineSeconds） -->
      <div v-else class="step-body">
        <el-form label-width="120px" class="form">
          <el-form-item label="预算（tokens）">
            <el-input-number v-model="form.budgetMaxTokens" :min="1" :max="100000000"
              placeholder="留空 = 不设预算约束" class="w-num" />
            <div class="field-note">本期仅持久化为计划约束，不做执行面强制（EV-04 诚实边界；用量强制归 EV-06）。</div>
          </el-form-item>
          <el-form-item label="并发">
            <el-input-number v-model="form.maxConcurrency" :min="1" :max="32" class="w-num" />
          </el-form-item>
          <el-form-item label="截止时间">
            <el-date-picker v-model="form.deadline" type="datetime" placeholder="留空 = 不设截止时间" />
            <div class="field-note">提交时换算为相对秒数（deadlineSeconds）；早于当前时间将被服务端拒绝。</div>
          </el-form-item>
        </el-form>
        <div class="hint">
          前置检查（依赖部署、schema 兼容、费用额度）归 worker 领取命令后的执行面；
          费用预估数据不足时显示未知，不承诺固定耗时。
        </div>
      </div>

      <div class="step-actions">
        <el-button :disabled="step === 0" @click="step--">上一步</el-button>
        <el-button v-if="step < 3" type="primary" @click="step++">下一步</el-button>
        <el-button
          v-else
          type="primary"
          :loading="submitting"
          :disabled="!canSubmit"
          @click="submit"
        >提交并启动</el-button>
      </div>
      <div v-if="step === 3 && !canSubmit" class="hint submit-hint">
        实验名称与数据集版本为契约必填项，补齐后可提交。
      </div>
    </div>
  </div>
</template>

<script setup>
// 新建实验（/eval/new）：四步向导，配置每步可回看；提交接真实端点 POST /api/eval/runs（EV-04 已交付）。
// 幂等键 = 计划字段稳定序列化的 SHA-256（同人同计划重复点 = 同键重放返回原 run）；
// 提交中按钮禁用防双击；409 展示 existingRunId 跳转链接（同键异计划，服务端判据）。
import { computed, onMounted, reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { api } from '../api/client'

const router = useRouter()

const stepTitles = ['选择模式', '被测版本', '数据集与重复次数', '预算与限额']
const step = ref(0)

const modes = [
  {
    key: 'E', name: '确定性全链测试',
    desc: '以确定性执行器重放冻结调用链，校验编排、工具调用与输出结构。',
    side: '不触发真实模型，无现场副作用；Replay 不属于真实端到端。',
  },
  {
    key: 'B', name: '冻结输入的质量对照',
    desc: '对冻结数据集输入调用真实模型并评分，与基线做同输入对照。',
    side: '触发真实模型调用并产生费用；不触碰靶场与生产现场。',
  },
  {
    key: 'L', name: '真实模型与靶场',
    desc: '真实模型 + 故障靶场注入的端到端实验，复用 drill 编排与环境互斥。',
    side: '有现场副作用，需在另行授权窗口执行；取消时转入恢复流程。',
  },
]

const form = reactive({
  displayName: '',
  mode: 'E',
  model: '',
  promptVersion: '',
  datasetVersion: '',
  repeat: 1,
  budgetMaxTokens: null,
  maxConcurrency: 1,
  deadline: null,
})

const canSubmit = computed(() =>
  form.displayName.trim().length > 0 && form.datasetVersion.length > 0)

// 数据集列表是真实端点（GET /eval/datasets）；失败/空列表诚实提示，不做静态兜底
const datasets = ref([])
const datasetsLoading = ref(false)
const datasetsError = ref(false)

async function loadDatasets() {
  datasetsLoading.value = true
  datasetsError.value = false
  try {
    const d = await api('/eval/datasets')
    datasets.value = d.items ?? []
  } catch {
    datasetsError.value = true
  } finally {
    datasetsLoading.value = false
  }
}

const submitting = ref(false)

// datetime → deadlineSeconds（契约是相对秒数）；幂等键用选定绝对时刻参与，不受提交时刻漂移
function deadlineSeconds() {
  if (!form.deadline) return null
  return Math.max(1, Math.round((new Date(form.deadline).getTime() - Date.now()) / 1000))
}

async function idempotencyKeyOf() {
  const canonical = [
    'eval-launch-ui/v1', form.displayName.trim(), form.mode, form.datasetVersion,
    form.model || 'null', form.promptVersion || 'null',
    String(form.budgetMaxTokens ?? 'null'), String(form.maxConcurrency),
    form.deadline ? String(new Date(form.deadline).getTime()) : 'null',
    String(form.repeat),
  ].join('|')
  const buf = await crypto.subtle.digest('SHA-256', new TextEncoder().encode(canonical))
  return Array.from(new Uint8Array(buf), b => b.toString(16).padStart(2, '0')).join('')
}

async function submit() {
  if (!canSubmit.value || submitting.value) return
  if (form.deadline && new Date(form.deadline).getTime() <= Date.now()) {
    ElMessage.error('截止时间早于当前时间，请调整后再提交')
    return
  }
  submitting.value = true
  try {
    const body = {
      idempotencyKey: await idempotencyKeyOf(),
      displayName: form.displayName.trim(),
      mode: form.mode,
      datasetVersion: form.datasetVersion,
      model: form.model || null,
      promptVersion: form.promptVersion || null,
      budgetMaxTokens: form.budgetMaxTokens ?? null,
      maxConcurrency: form.maxConcurrency,
      deadlineSeconds: deadlineSeconds(),
      roundsPerScenario: form.repeat,
    }
    const d = await api('/eval/runs', { method: 'POST', body })
    ElMessage.success(d.replayed
      ? '该计划此前已提交过（幂等重放，返回原实验）'
      : '实验已受理（ACCEPTED）；run 行由 worker 领取命令后落库，详情页短暂 404 属正常，请稍后刷新')
    router.push(`/eval/runs/${encodeURIComponent(d.runId)}`)
  } catch (e) {
    const st = e?.response?.status
    const data = e?.response?.data ?? {}
    if (st === 409 && data.existingRunId) {
      ElMessage.warning('相同幂等键已被不同计划占用——已为你定位到原实验')
      router.push(`/eval/runs/${encodeURIComponent(data.existingRunId)}`)
    } else if (st === 403) {
      ElMessage.error('无评测发起权限（需 OPERATOR 角色），未产生任何执行')
    } else {
      ElMessage.error(data.error || '提交失败，请检查表单后重试')
    }
  } finally {
    submitting.value = false
  }
}

onMounted(loadDatasets)
</script>

<style scoped>
.new-page { display: flex; flex-direction: column; gap: var(--section-gap); }

.crumb { font-size: 13px; color: var(--ink-2); }
.crumb a { color: var(--brand); }
.crumb .sep { margin: 0 6px; color: var(--line-strong); }

.panel { padding: var(--card-pad); }
.steps { margin-bottom: 24px; }
.step-clickable { cursor: pointer; }
.step-clickable :deep(.el-step__title) { cursor: pointer; }

.step-body { min-height: 260px; }
.form { max-width: 560px; }
.w-full { width: 100%; }
.w-num { width: 240px; }
.hint { font-size: var(--fs-aux); color: var(--ink-2); margin-top: 12px; max-width: 720px; line-height: 1.7; }
.field-note { font-size: var(--fs-aux); color: var(--ink-2); line-height: 1.6; }

.mode-card {
  border: 1px solid var(--line); border-radius: var(--radius); padding: 14px 16px;
  margin-bottom: 12px; cursor: pointer; transition: border-color .15s;
}
.mode-card:hover { border-color: var(--brand); }
.mode-card.cur { border-color: var(--brand); background: var(--brand-soft); }
.mode-head { font-size: 15px; margin-bottom: 4px; }
.mode-desc { font-size: var(--fs-body); color: var(--ink); }
.mode-side { font-size: var(--fs-aux); color: var(--ink-2); margin-top: 4px; }

.step-actions { display: flex; gap: 8px; margin-top: 24px; border-top: 1px solid var(--line); padding-top: 16px; }
.submit-hint { margin-top: 8px; }
</style>
