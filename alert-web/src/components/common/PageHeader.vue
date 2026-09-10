<template>
  <div class="page-header">
    <div class="ph-text">
      <h1 class="ph-title">{{ title }}</h1>
      <p v-if="subtitle" class="ph-sub">{{ subtitle }}</p>
    </div>
    <div v-if="$slots.actions" class="ph-actions">
      <slot name="actions" />
    </div>
  </div>
</template>

<script setup>
// 公共页头：标题 24px + 副标题（辅助 12px 档放大为 13px 正文辅助）+ 右侧操作槽
defineProps({
  title: { type: String, required: true },
  subtitle: { type: String, default: '' },
})
</script>

<style scoped>
.page-header {
  display: flex; align-items: flex-start; justify-content: space-between;
  gap: 16px; margin-bottom: var(--section-gap);
}
.ph-title { font-size: var(--fs-page-title); font-weight: 600; color: var(--head); line-height: 1.35; }
.ph-sub { font-size: var(--fs-body); color: var(--ink-2); margin-top: 4px; }
.ph-actions { flex: none; display: flex; align-items: center; gap: 8px; padding-top: 4px; }
</style>
