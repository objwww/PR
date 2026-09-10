import { computed, ref } from 'vue'
import { defineStore } from 'pinia'
import { http } from '../api/client'

// 会话 store（UI-0）：取代散落的 sessionStorage 直读，router 守卫与顶栏用户菜单统一走这里。
// sessionStorage 'am7.session'：EX-C3a 起为 JSON {"user":"..."}；旧值 '1' 仍视为已登录（用户名为空）。
export const SESSION_KEY = 'am7.session'

function readStoredUser() {
  const raw = sessionStorage.getItem(SESSION_KEY)
  if (!raw) return null
  if (raw === '1') return { user: '' }
  try {
    const parsed = JSON.parse(raw)
    return parsed && typeof parsed === 'object' ? { user: String(parsed.user ?? '') } : null
  } catch {
    return null
  }
}

export const useSessionStore = defineStore('session', () => {
  const stored = readStoredUser()
  const user = ref(stored?.user ?? '')
  const loggedIn = ref(stored !== null)

  function markLogin(username) {
    user.value = username
    loggedIn.value = true
    sessionStorage.setItem(SESSION_KEY, JSON.stringify({ user: username }))
  }

  function clearLocal() {
    user.value = ''
    loggedIn.value = false
    sessionStorage.removeItem(SESSION_KEY)
  }

  // 登出：Spring Security formLogin 默认 logoutUrl POST /api/auth/logout
  // （SecurityConfig 确认：permitAll、JSON {"ok":true}、invalidateHttpSession；
  // 写请求自动回带 X-XSRF-TOKEN，与登录同机制）。失败也清本地——本地标记本就该失效。
  async function logout() {
    try { await http.post('/auth/logout') } catch { /* 后端不可达时仍清本地会话 */ }
    clearLocal()
  }

  const displayName = computed(() => user.value || '已登录用户')

  return { user, loggedIn, displayName, markLogin, clearLocal, logout }
})
