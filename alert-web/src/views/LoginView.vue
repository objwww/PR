<template>
  <div class="login-page">
    <el-card class="login-card">
      <div class="login-title">告警 RCA 控制台</div>
      <el-form @submit.prevent="onLogin">
        <el-form-item>
          <el-input
            v-model.trim="username" size="large" placeholder="请输入账号"
            autocomplete="username" :prefix-icon="User"
          />
        </el-form-item>
        <el-form-item>
          <el-input
            v-model="password" size="large" type="password" show-password
            placeholder="请输入密码" autocomplete="current-password" :prefix-icon="Lock"
            @keyup.enter="onLogin"
          />
        </el-form-item>
        <el-button
          class="login-btn" type="primary" size="large"
          native-type="submit" :loading="busy" :disabled="!canSubmit"
        >登 录</el-button>
      </el-form>
      <div v-if="failed" class="login-err">用户名或密码错误</div>
      <div class="login-foot">
        <span class="env-badge">{{ envLabel }}</span>
        <span class="acct-hint">账号由运维预置，如需开通请联系管理员</span>
      </div>
    </el-card>
  </div>
</template>

<script setup>
// 登录（/login）：GET /api/auth/csrf 引导落 XSRF-TOKEN cookie，
// axios 默认 xsrfCookieName/xsrfHeaderName 与后端 CookieCsrfTokenRepository 配对，
// POST /api/auth/login 自动回带 X-XSRF-TOKEN；formLogin 读表单参数（URLSearchParams
// → application/x-www-form-urlencoded），成功即服务端 JSESSIONID 会话（HttpOnly）
import { computed, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { Lock, User } from '@element-plus/icons-vue'
import { http } from '../api/client'
import { useSessionStore } from '../stores/session.js'

const router = useRouter()
const route = useRoute()
const session = useSessionStore()
const username = ref('')
const password = ref('')
const failed = ref(false)
const busy = ref(false)

// 环境徽章与顶栏同来源：构建期注入的 VITE_ENV_LABEL，读不到如实显示「未标记」
const envLabel = import.meta.env.VITE_ENV_LABEL || '未标记'

const canSubmit = computed(() => username.value !== '' && password.value !== '' && !busy.value)

async function onLogin() {
  if (!canSubmit.value) return
  busy.value = true
  failed.value = false
  try {
    await http.get('/auth/csrf')
    const form = new URLSearchParams({ username: username.value, password: password.value })
    await http.post('/auth/login', form)
    session.markLogin(username.value)
    const target = typeof route.query.redirect === 'string' ? route.query.redirect : '/overview'
    router.replace(target)
  } catch {
    failed.value = true
  } finally {
    busy.value = false
  }
}
</script>

<style scoped>
.login-page {
  min-height: 100vh; display: flex; align-items: center; justify-content: center;
  background: var(--bg); padding: 24px;
}
.login-card { width: 400px; max-width: 100%; }
.login-title {
  text-align: center; font-weight: 600; color: var(--head);
  font-size: var(--fs-page-title); margin-bottom: 20px;
}
.login-btn { width: 100%; }
.login-err {
  text-align: center; font-size: var(--fs-aux); color: var(--bad); margin-top: 12px;
  background: var(--bad-bg); border-radius: var(--radius-ctl); padding: 6px 8px;
}
.login-foot {
  margin-top: 16px; display: flex; flex-direction: column; align-items: center; gap: 6px;
}
.env-badge {
  font-size: var(--fs-aux); color: var(--ink-2);
  border: 1px solid var(--line); border-radius: 999px; padding: 1px 12px;
}
.acct-hint { font-size: var(--fs-aux); color: var(--ink-2); }
</style>
