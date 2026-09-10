<template>
  <div class="eval-page">
    <!-- 顶层四入口：实验 / 数据集 / 人工评审 / 能力版本（EV-09 未交付，禁用） -->
    <div class="card tabs">
      <template v-for="t in tabs" :key="t.label">
        <span v-if="t.disabled" class="tab tab-disabled" :title="t.disabledTip">
          {{ t.label }}<span class="tab-note">{{ t.disabledNote }}</span>
        </span>
        <router-link
          v-else
          :to="t.to"
          class="tab"
          :class="{ cur: isCur(t) }"
        >{{ t.label }}</router-link>
      </template>
    </div>

    <router-view />
  </div>
</template>

<script setup>
// 评测中心壳（/eval）：四个一级入口；默认进实验列表（router redirect）
import { useRoute } from 'vue-router'

const route = useRoute()

const tabs = [
  { label: '实验', to: '/eval/runs', match: '/eval/runs' },
  { label: '数据集', to: '/eval/datasets', match: '/eval/datasets' },
  { label: '人工评审', to: '/eval/review', match: '/eval/review' },
  {
    label: '能力版本', disabled: true,
    disabledNote: '（依赖 EV-09，暂未开放）',
    disabledTip: '能力版本中心依赖后端 EV-09 资产清单与发布接口，本批未交付',
  },
]

function isCur(t) {
  return route.path === t.match || route.path.startsWith(t.match + '/')
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
.tab-disabled { cursor: not-allowed; color: var(--line-strong); }
.tab-disabled:hover { background: none; }
.tab-note { font-size: 12px; font-weight: 400; }
</style>
