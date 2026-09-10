<template>
  <div class="empty-state">
    <el-empty :description="text" :image-size="imageSize">
      <template v-if="kind === 'error'">
        <el-button @click="$emit('retry')">重试</el-button>
      </template>
      <slot />
    </el-empty>
  </div>
</template>

<script setup>
// 空态三态区分：empty=空数据 / error=加载失败（带重试）/ forbidden=无权限
import { computed } from 'vue'

const props = defineProps({
  kind: {
    type: String,
    default: 'empty',
    validator: v => ['empty', 'error', 'forbidden'].includes(v),
  },
  description: { type: String, default: '' },
  imageSize: { type: Number, default: 120 },
})
defineEmits(['retry'])

const text = computed(() => props.description || {
  empty: '暂无数据',
  error: '加载失败，请重试',
  forbidden: '没有查看该内容的权限',
}[props.kind])
</script>

<style scoped>
.empty-state { padding: 32px 0; }
</style>
