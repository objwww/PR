<template>
  <el-config-provider :locale="zhCn">
    <router-view v-if="route.meta.bare" />
    <AppShell v-else />
  </el-config-provider>
</template>

<script setup>
// M7-02：meta.bare 路由（P0 登录页）裸渲染，其余路由套 ⓪ 全局应用壳
// Element Plus 全站中文语言包（表格空态/分页/日期选择器等内置文案）
import { useRoute } from 'vue-router'
import zhCn from 'element-plus/es/locale/lang/zh-cn'
import AppShell from './layouts/AppShell.vue'

const route = useRoute()
</script>
