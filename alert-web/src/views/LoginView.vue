<template>
  <div class="login-page">
    <div class="login-card card">
      <div class="login-title">告警 RCA 控制台</div>
      <form @submit.prevent="onLogin">
        <label class="field">
          <span class="field-lbl">账号</span>
          <input v-model.trim="username" type="text" autocomplete="username" placeholder="请输入账号">
        </label>
        <label class="field">
          <span class="field-lbl">密码</span>
          <input v-model="password" type="password" autocomplete="current-password" placeholder="请输入密码">
        </label>
        <div class="login-btn-row">
          <button class="btn primary login-btn" type="submit" :disabled="!canSubmit">登 录</button>
        </div>
      </form>
      <div class="login-hint">失败 N 次锁定 ｜ 会话 8h 过期</div>
      <div class="login-note">
        FUT-34：浏览器禁止复用 Alertmanager 机器 Bearer；必须用户会话或 stream ticket。
        第一期不做注册/找回密码/SSO，账号由运维在配置侧预置。
      </div>
    </div>
  </div>
</template>

<script setup>
import { computed, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'

// P0 登录页（线框图 v1.6 #p0）：应用壳外全屏居中卡片；登录成功默认落地 P1 总览
// 本轮为 mock：任意非空账号密码 → 写 sessionStorage 会话标记 → 跳转；正式登录端点由后端用户体系落地后接入
const router = useRouter()
const route = useRoute()
const username = ref('')
const password = ref('')

const canSubmit = computed(() => username.value !== '' && password.value !== '')

function onLogin() {
  if (!canSubmit.value) return
  sessionStorage.setItem('am7.session', '1')
  const target = typeof route.query.redirect === 'string' ? route.query.redirect : '/overview'
  router.replace(target)
}
</script>

<style scoped>
.login-page {
  min-height: 100vh; display: flex; align-items: center; justify-content: center;
  background: var(--bg); padding: 24px;
}
.login-card { width: 320px; padding: 22px 24px 18px; }
.login-title {
  text-align: center; font-weight: 700; color: var(--head); font-size: 16px;
  border: 1px solid var(--line); border-radius: 8px; background: #f7f9fc;
  padding: 8px 10px; margin-bottom: 14px;
}
.field { display: block; margin-bottom: 10px; }
.field-lbl { display: block; font-size: 12.5px; color: var(--ink-2); margin-bottom: 4px; }
.field input {
  width: 100%; border: 1px solid var(--line-strong); border-radius: 6px;
  padding: 7px 10px; font-size: 13px; background: #fff; color: var(--ink);
}
.field input:focus { outline: none; border-color: var(--brand); box-shadow: 0 0 0 2px var(--brand-soft); }
.login-btn-row { text-align: center; margin-top: 14px; }
.login-btn { width: 100%; padding: 6px 0; font-size: 13px; }
.login-btn:disabled { opacity: .55; cursor: not-allowed; }
.login-hint { text-align: center; font-size: 11.5px; color: #888; margin-top: 12px; }
.login-note {
  margin-top: 12px; font-size: 11.5px; line-height: 1.7; color: var(--ink-2);
  background: var(--warn-bg); border: 1px solid #ecd9a0; border-left: 4px solid #e6b93f;
  border-radius: 8px; padding: 8px 12px;
}
</style>
