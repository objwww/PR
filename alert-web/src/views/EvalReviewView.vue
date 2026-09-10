<template>
  <div class="review-page card">
    <EmptyState kind="empty" description="评分器校准与人工复核建设中" :image-size="160" />
  </div>
</template>

<script setup>
// UI-6 评审页（/eval/review）：无后端数据，EmptyState 明示建设中
import EmptyState from '../components/common/EmptyState.vue'
</script>

<style scoped>
.review-page { padding: var(--card-pad); }
</style>
