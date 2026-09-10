import { createRouter, createWebHistory } from 'vue-router'
import LoginView from '../views/LoginView.vue'
import OverviewView from '../views/OverviewView.vue'
import AlertsView from '../views/AlertsView.vue'
import IncidentDetailView from '../views/IncidentDetailView.vue'
import HistoryView from '../views/HistoryView.vue'
import RunsView from '../views/RunsView.vue'
import RunDetailView from '../views/RunDetailView.vue'
import CasesView from '../views/CasesView.vue'
import DutyView from '../views/DutyView.vue'
import DutyChatView from '../views/DutyChatView.vue'
import NotificationsView from '../views/NotificationsView.vue'
import EvalView from '../views/EvalView.vue'
import EvalRunsView from '../views/EvalRunsView.vue'
import EvalRunDetailView from '../views/EvalRunDetailView.vue'
import EvalDatasetsView from '../views/EvalDatasetsView.vue'
import EvalReviewView from '../views/EvalReviewView.vue'
import MonitorView from '../views/MonitorView.vue'

import { useSessionStore } from '../stores/session.js'

const routes = [
  { path: '/login', name: 'login', component: LoginView, meta: { bare: true, title: '登录' } },
  { path: '/', redirect: '/overview' },
  { path: '/overview', name: 'overview', component: OverviewView, meta: { title: '总览' } },
  { path: '/alerts', name: 'alerts', component: AlertsView, meta: { title: '告警中心' } },
  { path: '/alerts/:incidentId', name: 'incident', component: IncidentDetailView, meta: { title: '告警详情' } },
  { path: '/history', name: 'history', component: HistoryView, meta: { title: '历史档案' } },
  { path: '/runs', name: 'runs', component: RunsView, meta: { title: '调查队列' } },
  { path: '/runs/:runId', name: 'run', component: RunDetailView, meta: { title: '调查详情' } },
  { path: '/cases', name: 'cases', component: CasesView, meta: { title: '处置中心' } },
  { path: '/duty', name: 'duty', component: DutyView, meta: { title: '值班管理' } },
  { path: '/duty/chat', name: 'duty-chat', component: DutyChatView, meta: { title: '通知预览' } },
  { path: '/notifications', name: 'notifications', component: NotificationsView, meta: { title: '值班通知' } },
  // UI-6 评测中心路由化：/eval → /eval/runs；实验 / 数据集 / 评审 三个一级子路由 + 实验详情
  {
    path: '/eval', component: EvalView, meta: { title: '评测中心' },
    children: [
      { path: '', redirect: '/eval/runs' },
      { path: 'runs', name: 'eval-runs', component: EvalRunsView, meta: { title: '实验 · 评测中心' } },
      { path: 'runs/:runId', name: 'eval-run', component: EvalRunDetailView, meta: { title: '实验详情 · 评测中心' } },
      { path: 'datasets', name: 'eval-datasets', component: EvalDatasetsView, meta: { title: '数据集 · 评测中心' } },
      { path: 'review', name: 'eval-review', component: EvalReviewView, meta: { title: '评审 · 评测中心' } },
    ],
  },
  { path: '/monitor', name: 'monitor', component: MonitorView, meta: { title: '监控' } },
]

const router = createRouter({ history: createWebHistory(), routes })

// 登录守卫：壳内路由要求会话；未登录重定向 /login 并带回跳地址（会话判断统一走 session store）
router.beforeEach(to => {
  const session = useSessionStore()
  if (to.meta.bare) {
    if (to.name === 'login' && session.loggedIn) return { path: '/overview', replace: true }
    return true
  }
  if (!session.loggedIn) return { path: '/login', query: { redirect: to.fullPath } }
  return true
})

router.afterEach(to => { document.title = (to.meta.title ? to.meta.title + ' · ' : '') + '告警 RCA 操作台' })
export default router
