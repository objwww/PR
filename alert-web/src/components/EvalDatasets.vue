<template>
  <div class="eval-datasets">
    <div class="crumb"><b>首页</b><span class="sep">/</span>评测中心<span class="sep">/</span>数据集治理</div>

    <div v-for="ds in data.datasets" :key="ds.id" class="card ds-card">
      <div class="ds-head">
        <b>{{ ds.name }}</b>
        <span class="tag" :class="ds.visibility === 'PRIVATE' ? 't-blue' : 't-gray'">{{ ds.visibilityZh }}</span>
      </div>
      <div class="ds-meta">
        {{ ds.caseCount }} 案例
        <template v-if="ds.scenarioFamilies"> ｜ {{ ds.scenarioFamilies }} 个 scenario_family ｜ digest={{ ds.digest }} ｜ review={{ ds.review }} ｜ drift={{ ds.drift }}</template>
        <template v-if="ds.note"> ｜ {{ ds.note }}</template>
      </div>

      <div v-for="(p, i) in ds.partitions" :key="p.key" class="part-row">
        <span class="part-tree">{{ i === ds.partitions.length - 1 ? '└' : '├' }}</span>
        <b>{{ p.nameZh }} {{ p.key }}</b>
        <span v-if="p.hint" class="part-hint">（{{ p.hint }}）</span>
        <span class="part-meta">
          <template v-if="p.caseCount != null">{{ p.caseCount }} 例</template>
          <template v-if="p.coverage"> ｜ coverage {{ p.coverage }}</template>
          <template v-if="p.pendingReview"> ｜ {{ p.pendingReview }} 例待复核</template>
        </span>
        <span v-if="p.locked" class="lock">🔒</span>
        <span class="part-actions">
          <button v-if="p.canLaunchBatch" class="btn">查看案例</button>
          <button v-if="p.canLaunchBatch" class="btn primary" @click="$emit('launch', p.key)">发起批量评测</button>
          <button v-if="p.canLaunchGate" class="btn">发起发布门（需 release 权限）</button>
        </span>
        <div v-if="p.redline" class="part-redline">{{ p.redline }}</div>
      </div>
    </div>

    <div class="card gov-card">
      <b>治理检查</b>：
      family 泄漏 {{ gov.familyLeakage }} ｜ 重复输入 {{ gov.duplicateInput }} ｜ 真值缺失 {{ gov.missingGroundTruth }}
      ｜ 分布漂移 {{ gov.distributionDrift }} ｜ HOLDOUT 使用 {{ gov.holdoutWear }} 次
      <div class="gov-actions">
        <button class="btn">版本差异</button>
        <button class="btn">质量报告</button>
        <button class="btn">来源/审批记录</button>
      </div>
      <small class="gov-note">数据集版本化 insert-only；生产失败样本进入候选集前必须脱敏、人工复核并重新分区。</small>
    </div>
  </div>
</template>

<script setup>
import { onMounted, reactive, computed } from 'vue'
import { api } from '../api/client.js'
import { fetchDatasets } from '../mocks/eval.js'

defineEmits(['launch'])

const data = reactive({ datasets: [], governance: {} })
const gov = computed(() => data.governance)

onMounted(async () => {
  Object.assign(data, await api('/eval/datasets', { mock: fetchDatasets }))
})
</script>

<style scoped>
.crumb { font-size: 12px; color: var(--ink-2); margin-bottom: 10px; }
.crumb .sep { margin: 0 6px; color: var(--line-strong); }

.ds-card { padding: 12px 14px; margin-bottom: 10px; }
.ds-head { display: flex; align-items: center; gap: 8px; font-size: 14px; }
.ds-meta { font-size: 12px; color: var(--ink-2); margin: 2px 0 8px; }

.part-row {
  display: flex; align-items: center; flex-wrap: wrap; gap: 6px;
  border: 1px solid var(--line); border-radius: 8px;
  padding: 7px 10px; margin-top: 6px; font-size: 13px; background: #fbfcfe;
}
.part-tree { color: var(--ink-2); }
.part-hint { color: var(--ink-2); font-size: 12px; }
.part-meta { font-size: 12px; color: var(--ink-2); }
.lock { font-size: 12px; }
.part-actions { margin-left: auto; display: flex; gap: 6px; }
.part-redline {
  flex-basis: 100%; font-size: 12px; color: var(--ink-2);
  border-top: 1px dashed var(--line); padding-top: 5px;
}

.gov-card { padding: 12px 14px; font-size: 13px; }
.gov-actions { display: flex; gap: 6px; margin-top: 8px; }
.gov-note { display: block; color: var(--ink-2); margin-top: 8px; }
</style>
