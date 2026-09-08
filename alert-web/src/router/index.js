import { createRouter, createWebHistory } from 'vue-router'
import LoginView from '../views/LoginView.vue'
import OverviewView from '../views/OverviewView.vue'
import AlertsView from '../views/AlertsView.vue'
import IncidentDetailView from '../views/IncidentDetailView.vue'
import HistoryView from '../views/HistoryView.vue'
import RunsView from '../views/RunsView.vue'
import RunDetailView from '../views/RunDetailView.vue'
import CasesView from '../views/CasesView.vue'
import EvalView from '../views/EvalView.vue'
import MonitorView from '../views/MonitorView.vue'

// 会话标记：mock 登录写入；正式用户会话（FUT-34 HttpOnly Cookie）落地后由此处统一替换
const SESSION_KEY = 'am7.session'
const hasSession = () => sessionStorage.getItem(SESSION_KEY) === '1'

const routes = [
  { path: '/login', name: 'login', component: LoginView, meta: { bare: true, title: '登录' } },
  { path: '/', redirect: '/overview' },
  { path: '/overview', name: 'overview', component: OverviewView, meta: { title: '总览' } },
  { path: '/alerts', name: 'alerts', component: AlertsView, meta: { title: '告警中心' } },
  { path: '/alerts/:incidentId', name: 'incident', component: IncidentDetailView, meta: { title: 'Incident 详情' } },
  { path: '/history', name: 'history', component: HistoryView, meta: { title: '历史档案' } },
  { path: '/runs', name: 'runs', component: RunsView, meta: { title: '调查队列' } },
  { path: '/runs/:runId', name: 'run', component: RunDetailView, meta: { title: '调查详情' } },
  { path: '/cases', name: 'cases', component: CasesView, meta: { title: '处置中心' } },
  { path: '/eval', name: 'eval', component: EvalView, meta: { title: '评测中心' } },
  { path: '/monitor', name: 'monitor', component: MonitorView, meta: { title: 'Agent 监控' } },
]

const router = createRouter({ history: createWebHistory(), routes })

// 登录守卫：壳内路由要求会话；未登录重定向 /login 并带回跳地址
router.beforeEach(to => {
  if (to.meta.bare) {
    if (to.name === 'login' && hasSession()) return { path: '/overview', replace: true }
    return true
  }
  if (!hasSession()) return { path: '/login', query: { redirect: to.fullPath } }
  return true
})

router.afterEach(to => { document.title = (to.meta.title ? to.meta.title + ' · ' : '') + '告警 RCA Agent 操作台' })
export default router
