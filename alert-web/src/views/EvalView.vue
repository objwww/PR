<template>
  <div class="eval-page">
    <PageHeader title="评测中心" subtitle="实验批次、评测数据集与人工评审">
      <template #actions>
        <el-button type="primary" @click="onLaunch">发起评测</el-button>
      </template>
    </PageHeader>

    <!-- 一级子路由页签：实验 / 数据集 / 评审，选中态进 URL -->
    <div class="card tabs">
      <router-link
        v-for="t in tabs"
        :key="t.to"
        :to="t.to"
        class="tab"
        :class="{ cur: isCur(t) }"
      >{{ t.label }}</router-link>
    </div>

    <router-view />
  </div>
</template>

<script setup>
// UI-6 评测中心壳（/eval）：页头 + 发起评测主按钮 + 三个路由化页签 + router-view
import { useRoute } from 'vue-router'
import PageHeader from '../components/common/PageHeader.vue'

const route = useRoute()

const tabs = [
  { label: '实验', to: '/eval/runs', match: '/eval/runs' },
  { label: '数据集', to: '/eval/datasets', match: '/eval/datasets' },
  { label: '评审', to: '/eval/review', match: '/eval/review' },
]

function isCur(t) {
  return route.path === t.match || route.path.startsWith(t.match + '/')
}

// 页面发起无后端支撑：诚实提示走命令行，不做假向导
function onLaunch() {
  ElMessage.info('发起评测请走命令行 eval-runner，页面发起功能建设中')
}
</script>

<style scoped>
.eval-page { display: flex; flex-direction: column; gap: var(--section-gap); }

.tabs { display: flex; gap: 2px; padding: 4px 6px; overflow-x: auto; }
.tab {
  white-space: nowrap; padding: 7px 14px; font-size: 13px; color: var(--ink-2);
  border-radius: 8px 8px 0 0; border-bottom: 2px solid transparent; cursor: pointer;
}
.tab:hover { background: var(--bg); }
.tab.cur { color: var(--brand); font-weight: 700; border-bottom-color: var(--brand); }
</style>
