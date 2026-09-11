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
import EvalAssetsView from '../views/EvalAssetsView.vue'
import EvalNewView from '../views/EvalNewView.vue'
import EvalCompareView from '../views/EvalCompareView.vue'
import MonitorView from '../views/MonitorView.vue'
import DrillsView from '../views/DrillsView.vue'
import DrillCreateView from '../views/DrillCreateView.vue'
import DrillDetailView from '../views/DrillDetailView.vue'
import VersionsView from '../views/VersionsView.vue'

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
  // UI-6 评测中心路由化：/eval → /eval/runs；实验 / 数据集 / 评审 / 能力版本（EV-09）四个一级子路由 + 实验详情
  {
    path: '/eval', component: EvalView, meta: { title: '评测中心' },
    children: [
      { path: '', redirect: '/eval/runs' },
      { path: 'runs', name: 'eval-runs', component: EvalRunsView, meta: { title: '实验 · 评测中心' } },
      { path: 'runs/:runId', name: 'eval-run', component: EvalRunDetailView, meta: { title: '实验详情 · 评测中心' } },
      { path: 'new', name: 'eval-new', component: EvalNewView, meta: { title: '新建实验 · 评测中心' } },
      { path: 'compare', name: 'eval-compare', component: EvalCompareView, meta: { title: '对比工作台 · 评测中心' } },
      { path: 'datasets', name: 'eval-datasets', component: EvalDatasetsView, meta: { title: '数据集 · 评测中心' } },
      { path: 'review', name: 'eval-review', component: EvalReviewView, meta: { title: '评审 · 评测中心' } },
      { path: 'assets', name: 'eval-assets', component: EvalAssetsView, meta: { title: '能力版本 · 评测中心' } },
    ],
  },
  // DR-01 故障演练：列表 / 三步新建 / 详情，均可浏览器直达刷新（浏览器 history 模式 + nginx SPA 回退）
  { path: '/drills', name: 'drills', component: DrillsView, meta: { title: '故障演练' } },
  { path: '/drills/new', name: 'drill-new', component: DrillCreateView, meta: { title: '新建演练' } },
  { path: '/drills/:drillId', name: 'drill', component: DrillDetailView, meta: { title: '演练详情' } },
  { path: '/monitor', name: 'monitor', component: MonitorView, meta: { title: '监控' } },
  // EN-10 版本中心：发布资产/配置包只读清单 + 运行配置切换状态（发布/激活仍走机器线，不在页面）
  { path: '/versions', name: 'versions', component: VersionsView, meta: { title: '版本中心' } },
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
