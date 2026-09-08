<template>
  <router-view v-if="route.meta.bare" />
  <AppShell v-else />
</template>

<script setup>
// M7-02：meta.bare 路由（P0 登录页）裸渲染，其余路由套 ⓪ 全局应用壳
import { useRoute } from 'vue-router'
import AppShell from './layouts/AppShell.vue'

const route = useRoute()
</script>
