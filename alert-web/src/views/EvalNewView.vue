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

      <!-- 第一步：模式（E / B / L，中文说明真实副作用） -->
      <div v-if="step === 0" class="step-body">
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

      <!-- 第二步：基线与候选 release -->
      <div v-else-if="step === 1" class="step-body">
        <el-form label-width="120px" class="form">
          <el-form-item label="基线 release">
            <el-input v-model="form.baseline" placeholder="基线 release 标识 / digest" />
          </el-form-item>
          <el-form-item label="候选 release">
            <el-input v-model="form.candidate" placeholder="候选 release 标识 / digest" />
          </el-form-item>
        </el-form>
        <div class="hint">
          release 清单与实际变化的 Prompt/Skill/MCP/schema/模型/调度策略展示依赖后端 EV-09 资产清单，本批未开放；
          此处仅作配置草稿。默认一次只变一个因素；多因素变更只能作组合验收，不能声称识别单因素收益。
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
              数据集接口（GET /eval/datasets）返回空列表，暂无可选版本；提交本已禁用（依赖 EV-04 后端），此处不提供静态兜底选项。
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

      <!-- 第四步：预算 / 并发 / 截止时间 -->
      <div v-else class="step-body">
        <el-form label-width="120px" class="form">
          <el-form-item label="总预算">
            <el-input v-model="form.budget" placeholder="例如 50（币种与价格版本在提交时固定）" />
          </el-form-item>
          <el-form-item label="并发">
            <el-input-number v-model="form.concurrency" :min="1" :max="32" />
          </el-form-item>
          <el-form-item label="截止时间">
            <el-date-picker v-model="form.deadline" type="datetime" placeholder="选择截止时间" />
          </el-form-item>
        </el-form>
        <div class="hint">
          前置检查（依赖部署、schema 兼容、费用额度）依赖后端 EV-04，本批未运行；
          费用预估数据不足时显示未知，不承诺固定耗时。
        </div>
      </div>

      <div class="step-actions">
        <el-button :disabled="step === 0" @click="step--">上一步</el-button>
        <el-button v-if="step < 3" type="primary" @click="step++">下一步</el-button>
        <el-tooltip
          v-else
          content="启动命令依赖后端 EV-04 持久化作业，暂未开放——请走 eval-runner 命令行"
          placement="top"
        >
          <span>
            <el-button type="primary" disabled>提交并启动</el-button>
          </span>
        </el-tooltip>
      </div>
      <div v-if="step === 3" class="hint submit-hint">
        启动命令依赖后端 EV-04 持久化作业，暂未开放——请走 eval-runner 命令行。本页不会发起任何真实实验。
      </div>
    </div>
  </div>
</template>

<script setup>
// 新建实验（/eval/new）：四步向导骨架，配置每步可回看；提交禁用（EV-04 未交付，不做假启动）。
import { onMounted, reactive, ref } from 'vue'
import { api } from '../api/client'

const stepTitles = ['选择模式', '基线与候选', '数据集与重复次数', '预算与限额']
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
  mode: 'E',
  baseline: '',
  candidate: '',
  datasetVersion: '',
  repeat: 1,
  budget: '',
  concurrency: 1,
  deadline: null,
})

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
    datasetsError.value = true // 加载失败不阻塞骨架，提交本就禁用（EV-04）
  } finally {
    datasetsLoading.value = false
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
