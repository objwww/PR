<template>
  <el-drawer
    :model-value="modelValue"
    :title="title"
    :size="size"
    @update:model-value="$emit('update:modelValue', $event)"
  >
    <slot />
  </el-drawer>
</template>

<script setup>
// 详情抽屉薄封装：统一尺寸 360~420px（默认 400px）、标题槽与默认内容槽
defineProps({
  modelValue: { type: Boolean, default: false },
  title: { type: String, default: '' },
  size: { type: [String, Number], default: 400 },
})
defineEmits(['update:modelValue'])
</script>
