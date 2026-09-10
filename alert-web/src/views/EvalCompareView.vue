<template>
  <div class="compare-page">
    <div class="crumb">
      <router-link to="/eval/runs">实验</router-link>
      <span class="sep">/</span>
      <b>对比工作台</b>
    </div>

    <div class="card panel">
      <div class="pair">
        <div class="pair-item">
          <div class="pi-label">基线</div>
          <div class="pi-value mono">{{ baseline || '—' }}</div>
        </div>
        <div class="pair-item">
          <div class="pi-label">候选</div>
          <div class="pi-value mono">{{ candidate || '—' }}</div>
        </div>
      </div>
      <EmptyState
        kind="empty"
        :image-size="160"
        description="对比工作台依赖 EV-07（配对统计与可比性检查接口），本批未交付。当前仅记录基线与候选身份，不生成任何对比结论。"
      />
      <div class="actions">
        <el-button @click="router.push('/eval/runs')">返回实验列表</el-button>
      </div>
    </div>
  </div>
</template>

<script setup>
// 对比工作台（/eval/compare?baseline=…&candidate=…）：本批只建路由与骨架，内容依赖 EV-07。
import { computed } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import EmptyState from '../components/common/EmptyState.vue'

const route = useRoute()
const router = useRouter()

const str = v => (typeof v === 'string' ? v : '')
const baseline = computed(() => str(route.query.baseline))
const candidate = computed(() => str(route.query.candidate))
</script>

<style scoped>
.compare-page { display: flex; flex-direction: column; gap: var(--section-gap); }

.crumb { font-size: 13px; color: var(--ink-2); }
.crumb a { color: var(--brand); }
.crumb .sep { margin: 0 6px; color: var(--line-strong); }

.panel { padding: var(--card-pad); }
.pair { display: flex; gap: 24px; flex-wrap: wrap; margin-bottom: 8px; }
.pi-label { font-size: var(--fs-aux); color: var(--ink-2); margin-bottom: 2px; }
.pi-value { font-size: var(--fs-body); word-break: break-all; }
.mono { font-family: var(--mono, monospace); }
.actions { display: flex; justify-content: center; padding-bottom: 24px; }
</style>
