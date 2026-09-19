import axios from 'axios'

// API client：vite proxy /api → control-app:8080，全部真端点。
// EX-C1 补全方法面（GET/POST/PATCH/DELETE 经 method+body）+ 401 全局重定向。
// XSRF：axios 默认 xsrfCookieName=XSRF-TOKEN / xsrfHeaderName=X-XSRF-TOKEN，
// 与后端 CookieCsrfTokenRepository.withHttpOnlyFalse() 配对——登录页 GET /api/auth/csrf
// 落 cookie 后，写请求自动回带头（同源面自动生效，无需额外配置）
const http = axios.create({ baseURL: '/api', timeout: 15000 })

// EX-C1 401 全局重定向：任何 API 面会话过期/未登录 → 登录页（带回跳地址）。
// 登录页自身（密码错 401）不重定向，交 LoginView 就地提示，避免重定向环
// BA-168：401 必须先清本地已登录标记（sessionStorage am7.session，与 stores/session.js
// 同源键；此处直写是为了不引 store 造成 client←session←client 循环依赖）——
// 否则路由守卫见"已登录"把 /login 弹回 /overview，而服务端会话已死，
// overview 再 401 再跳 /login 再被弹回……整页无限重载（远程闪屏实锤：
// nginx 10 分钟 /login?redirect=%2Foverview 1542 次 + overview 三 API 各 1482 次）。
http.interceptors.response.use(
  r => r,
  err => {
    if (err?.response?.status === 401 && !location.pathname.startsWith('/login')) {
      sessionStorage.removeItem('am7.session')
      location.assign('/login?redirect=' + encodeURIComponent(location.pathname + location.search))
    }
    return Promise.reject(err)
  },
)

export async function api(path, { params, method = 'GET', body, signal } = {}) {
  const { data } = await http.request({ url: path, method, params, data: body, signal })
  return data
}

export { http }
