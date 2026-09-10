<template>
  <!-- UX-01 分类徽章：category 有值 → 中文徽章（色阶见 tokens --cat-*）；
       categorySource=OVERRIDE → 旁挂“人工”小标记；category 缺席 → '—'（不渲染徽章、不报错） -->
  <span v-if="cat" class="cat-wrap">
    <span class="cat-badge" :class="cat.cls">{{ cat.label }}</span>
    <el-tooltip v-if="source === 'OVERRIDE'" content="人工修正，见详情" placement="top">
      <span class="cat-manual">人工</span>
    </el-tooltip>
  </span>
  <span v-else class="cat-none">—</span>
</template>

<script setup>
import { computed } from 'vue'
import { mapCategory } from '../../utils/category'

const props = defineProps({
  category: { type: String, default: '' },
  source: { type: String, default: '' }, // RULE | OVERRIDE
})

const cat = computed(() => mapCategory(props.category))
</script>

<style scoped>
.cat-wrap { display: inline-flex; align-items: center; gap: 6px; }
.cat-badge {
  display: inline-block; border-radius: 999px; padding: 0 9px;
  font-size: 12px; line-height: 20px; font-weight: 600; white-space: nowrap;
}
.cat-business { color: var(--cat-business); background: var(--cat-business-bg); }
.cat-application { color: var(--cat-application); background: var(--cat-application-bg); }
.cat-dependency { color: var(--cat-dependency); background: var(--cat-dependency-bg); }
.cat-infra { color: var(--cat-infra); background: var(--cat-infra-bg); }
.cat-network { color: var(--cat-network); background: var(--cat-network-bg); }
.cat-data { color: var(--cat-data); background: var(--cat-data-bg); }
.cat-security { color: var(--cat-security); background: var(--cat-security-bg); }
.cat-platform { color: var(--cat-platform); background: var(--cat-platform-bg); }
.cat-unclassified { color: var(--cat-unclassified); background: var(--cat-unclassified-bg); }
.cat-manual {
  font-size: 11px; line-height: 16px; padding: 0 5px; border-radius: var(--radius-ctl);
  color: var(--brand); background: var(--brand-soft); border: 1px solid #b3d8ff; cursor: default;
}
.cat-none { color: var(--ink-2); }
</style>
